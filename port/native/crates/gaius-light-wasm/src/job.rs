//! The `light_column` job: light one chunk column (first light of a generated
//! chunk, and/or `checkBlock` for changed positions) and return its
//! `DataLayer`s plus the light that leaves it.
//!
//! Payload (little-endian; "pad8" skips to the next multiple of 8 bytes from
//! the payload start; L = section_count + 2 light sections, index 0 being the
//! padding section below the world):
//!
//! ```text
//! u8   version          2 (a version 1 job is refused: it shipped whole
//!                       neighbour sections)
//! u8   ops              bit 0 INITIAL: propagateLightSources (light turns on)
//!                       bit 1 CHECKS: checkBlock at the listed positions
//! u16  flags            bit 0 SKY (the dimension has sky light), bit 1 BLOCK,
//!                       bit 2 SKY_LIGHT_ON, bit 3 BLOCK_LIGHT_ON (lightOnInSection
//!                       of the column when INITIAL is not set),
//!                       bit 4 PULL_RING (seed the column from the ring levels),
//!                       bit 5 EMIT_OUTGOING (report light leaving the column),
//!                       bit 6 OMIT_UNCHANGED (return only changed layers)
//! i32  chunk_x, chunk_z (informational)
//! i32  min_section      LevelHeightAccessor.getMinSectionY()
//! u32  section_count    1..=254
//! i32  sky_bottom_section  SkyLightSectionStorage.getBottomSectionY()
//! u8   neighbours       bit per present neighbour: 0 north (z-1), 1 south,
//!                       2 west (x-1), 3 east
//! u8[3] reserved
//! u32  table_len        > 0: a light table follows (gaius_light::table format);
//!                       0: use the cached table of table_epoch
//! u64  table_epoch      identifies the table; a cached table with the same
//!                       epoch is reused and the inline copy skipped
//! u32  check_count
//! u32  reserved
//! u8   table[table_len], pad8
//! u8   flags[L] of the column, then flags[L] per present neighbour (in side
//!      order), pad8: gaius_light::flags (storing bits, data-follows bits)
//! sections: section_count blobs for the column, then section_count ring
//!      slices per present neighbour, each u8 encoding, u8[3] reserved,
//!      u32 byte_len, bytes, pad8:
//!      column: 0 single (u32 state id), 1 PalettedContainer.write bytes,
//!              2 flat u16 (4096 ids)
//!      ring:   0 single (u32 state id), 3 ring palette (u16 count 1..=256,
//!              u16 reserved, u32 ids[count], u8 index[256])
//!      A ring slice is the 16 x 16 neighbour cells that touch the column,
//!      cell (y << 4) | along, along = x for north/south and z for west/east:
//!      the column reads nothing else of its neighbours.
//! layers: for the column 2048 bytes per light section with SKY_DATA, then
//!      2048 per section with BLOCK_DATA; then for each present neighbour
//!      128 bytes (the ring slice, packed like a DataLayer) per light section
//!      with SKY_DATA, then per section with BLOCK_DATA
//! checks: check_count x (u8 x, u8 z, u16 reserved, i32 y)  (column-local x/z)
//! ```
//!
//! Result:
//!
//! ```text
//! u8   version 2, u8 status (0 lit, 1 table missing: the job named a table
//!      epoch this instance does not hold and carried no table; nothing else
//!      follows the header, resend the job with the table inline)
//! u16  light_sections (L)
//! u32  sky_layer_count, block_layer_count, outgoing_count
//! u32  increase_pops, decrease_pops, reserved, reserved
//! u8   section_flags[L], pad8: bit 0 sky layer included, bit 1 block layer
//!      included, bit 2 sky changed, bit 3 block changed
//! sky layers (2048 bytes each, ascending section), then block layers
//! outgoing_count x (u8 kind, u8 side, u8 along, u8 level, i32 y,
//!                   u16 entry, u16 count)    see gaius_light::Outgoing
//! ```
//!
//! A layer is included for every section the column stores for that layer,
//! or only for the changed ones with OMIT_UNCHANGED.
//!
//! `port/web/kernels/light-job.js` writes and reads the same layout; keep the
//! two in step.

use gaius_kernel_abi::{KernelError, Reader, Status};
use gaius_light::column::{MAX_SECTIONS, RING_CELLS, RING_LAYER_BYTES};
use gaius_light::{flags, ColumnSpec, Layer, LightColumn, LightTable, SectionStates, LAYER_BYTES};

pub use gaius_kernel_abi::kind::LIGHT_COLUMN;
pub const JOB_VERSION: u8 = 2;
pub const RESULT_VERSION: u8 = 2;
pub const HEADER_LEN: usize = 48;
pub const RESULT_HEADER_LEN: usize = 32;
pub const OUTGOING_LEN: usize = 12;
/// Bound on listed positions, so a hostile payload cannot ask for unbounded work.
pub const MAX_CHECKS: u32 = 1 << 20;

pub mod ops {
    pub const INITIAL: u8 = 1;
    pub const CHECKS: u8 = 2;
}

pub mod job_flags {
    pub const SKY: u16 = 1;
    pub const BLOCK: u16 = 2;
    pub const SKY_LIGHT_ON: u16 = 4;
    pub const BLOCK_LIGHT_ON: u16 = 8;
    pub const PULL_RING: u16 = 16;
    pub const EMIT_OUTGOING: u16 = 32;
    pub const OMIT_UNCHANGED: u16 = 64;
}

pub mod encoding {
    pub const SINGLE: u8 = 0;
    pub const NETWORK: u8 = 1;
    pub const FLAT_U16: u8 = 2;
    pub const RING_PALETTE: u8 = 3;
}

/// Result status byte.
pub mod result_status {
    pub const LIT: u8 = 0;
    pub const TABLE_MISSING: u8 = 1;
}

fn bad(message: &'static str) -> KernelError {
    KernelError::new(Status::BadPayload, message)
}

fn truncated(_: Status) -> KernelError {
    KernelError::from(Status::Truncated)
}

/// Column state kept alive between jobs.
pub struct Kernel {
    column: LightColumn,
    table: Option<(u64, LightTable)>,
    checks: Vec<(i32, i32, i32)>,
    palette: Vec<u32>,
}

impl Default for Kernel {
    fn default() -> Self {
        Self::new()
    }
}

impl Kernel {
    pub fn new() -> Kernel {
        Kernel {
            column: LightColumn::new(),
            table: None,
            checks: Vec::new(),
            palette: Vec::new(),
        }
    }

    /// Epoch of the cached light table, if any.
    pub fn table_epoch(&self) -> Option<u64> {
        self.table.as_ref().map(|(epoch, _)| *epoch)
    }

    pub fn run(&mut self, payload: &[u8]) -> Result<Vec<u8>, KernelError> {
        let mut r = Reader::new(payload);
        if r.u8().map_err(truncated)? != JOB_VERSION {
            return Err(KernelError::new(
                Status::BadVersion,
                "unsupported light_column job version",
            ));
        }
        let job_ops = r.u8().map_err(truncated)?;
        let job_flags = r.u16().map_err(truncated)?;
        let _chunk_x = r.i32().map_err(truncated)?;
        let _chunk_z = r.i32().map_err(truncated)?;
        let min_section = r.i32().map_err(truncated)?;
        let section_count = r.u32().map_err(truncated)? as usize;
        let sky_bottom_section = r.i32().map_err(truncated)?;
        let neighbours = r.u8().map_err(truncated)? & 0x0f;
        r.take(3).map_err(truncated)?;
        let table_len = r.u32().map_err(truncated)? as usize;
        let table_epoch = r.i64().map_err(truncated)? as u64;
        let check_count = r.u32().map_err(truncated)?;
        r.u32().map_err(truncated)?;
        if section_count == 0 || section_count > MAX_SECTIONS {
            return Err(bad("light_column section_count must be 1..=254"));
        }
        if check_count > MAX_CHECKS {
            return Err(bad("light_column lists too many positions"));
        }
        if job_ops & !(ops::INITIAL | ops::CHECKS) != 0 {
            return Err(bad("light_column has unknown ops"));
        }

        let table_bytes = r.take(table_len).map_err(truncated)?;
        r.align(8).map_err(truncated)?;
        if table_len > 0 && self.table_epoch() != Some(table_epoch) {
            self.table = None;
            let table = LightTable::decode(table_bytes).map_err(bad)?;
            self.table = Some((table_epoch, table));
        }
        let table = match &self.table {
            Some((epoch, table)) if *epoch == table_epoch => table,
            // A fresh or trimmed instance: the caller resends the job with the table.
            _ => return Ok(table_missing(section_count + 2)),
        };

        let initial = job_ops & ops::INITIAL != 0;
        let spec = ColumnSpec {
            min_section,
            section_count,
            sky_bottom_section,
            // propagateLightSources turns the column's light on first.
            sky_light_on: initial || job_flags & job_flags::SKY_LIGHT_ON != 0,
            block_light_on: initial || job_flags & job_flags::BLOCK_LIGHT_ON != 0,
            emit_outgoing: job_flags & job_flags::EMIT_OUTGOING != 0,
            neighbours,
        };
        let column = &mut self.column;
        column.begin(spec).map_err(bad)?;
        let light_sections = column.light_sections();
        let slots: Vec<usize> = core::iter::once(0)
            .chain((0..4).filter(|side| neighbours & (1 << side) != 0).map(|side| side + 1))
            .collect();

        for &slot in &slots {
            let section_flags = r.take(light_sections).map_err(truncated)?;
            for (light_section, &value) in section_flags.iter().enumerate() {
                column.set_section_flags(slot, light_section, value).map_err(bad)?;
            }
        }
        r.align(8).map_err(truncated)?;

        for &slot in &slots {
            for section in 0..section_count {
                let encoding = r.u8().map_err(truncated)?;
                r.take(3).map_err(truncated)?;
                let byte_len = r.u32().map_err(truncated)? as usize;
                let bytes = r.take(byte_len).map_err(truncated)?;
                r.align(8).map_err(truncated)?;
                if slot > 0 {
                    load_ring(column, slot - 1, section, encoding, bytes, table, &mut self.palette)?;
                    continue;
                }
                let states = match encoding {
                    encoding::SINGLE => SectionStates::Single(single_id(bytes)?),
                    encoding::NETWORK => SectionStates::Network(bytes),
                    encoding::FLAT_U16 => SectionStates::FlatU16(bytes),
                    _ => return Err(bad("unknown section encoding")),
                };
                column.load_section(slot, section, states, table).map_err(bad)?;
            }
        }

        for &slot in &slots {
            for (layer, data_bit) in [(Layer::Sky, flags::SKY_DATA), (Layer::Block, flags::BLOCK_DATA)] {
                for light_section in 0..light_sections {
                    if column.section_flags(slot, light_section) & data_bit == 0 {
                        continue;
                    }
                    if slot == 0 {
                        let nibbles = r.take(LAYER_BYTES).map_err(truncated)?;
                        column.load_layer(slot, light_section, layer, nibbles).map_err(bad)?;
                    } else {
                        let nibbles = r.take(RING_LAYER_BYTES).map_err(truncated)?;
                        column
                            .load_ring_layer(slot - 1, light_section, layer, nibbles)
                            .map_err(bad)?;
                    }
                }
            }
        }

        self.checks.clear();
        let world_min_y = min_section * 16;
        let world_max_y = world_min_y + section_count as i32 * 16;
        for _ in 0..check_count {
            let x = r.u8().map_err(truncated)? as i32;
            let z = r.u8().map_err(truncated)? as i32;
            r.u16().map_err(truncated)?;
            let y = r.i32().map_err(truncated)?;
            if x > 15 || z > 15 || y < world_min_y || y >= world_max_y {
                return Err(bad("light_column check position is outside the column"));
            }
            self.checks.push((x, y, z));
        }

        column.compute_sources(table);
        let checks = job_ops & ops::CHECKS != 0;
        if job_flags & job_flags::BLOCK != 0 {
            if initial {
                column.enqueue_block_sources();
            }
            if checks {
                for &(x, y, z) in &self.checks {
                    column.check_block(x, y, z);
                }
            }
            if job_flags & job_flags::PULL_RING != 0 {
                column.enqueue_ring_light(Layer::Block);
            }
            column.propagate(Layer::Block, table);
        }
        if job_flags & job_flags::SKY != 0 {
            if initial {
                column.enqueue_sky_sources();
            }
            if checks {
                for &(x, y, z) in &self.checks {
                    column.check_sky(x, y, z);
                }
            }
            if job_flags & job_flags::PULL_RING != 0 {
                column.enqueue_ring_light(Layer::Sky);
            }
            column.propagate(Layer::Sky, table);
        }

        Ok(encode_result(column, job_flags))
    }
}

fn single_id(bytes: &[u8]) -> Result<u32, KernelError> {
    let raw = bytes
        .get(..4)
        .ok_or_else(|| bad("single-state section needs a u32 id"))?;
    Ok(u32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]))
}

/// Loads one neighbour ring slice (encoding SINGLE or RING_PALETTE).
fn load_ring(
    column: &mut LightColumn,
    side: usize,
    section: usize,
    encoding: u8,
    bytes: &[u8],
    table: &LightTable,
    palette: &mut Vec<u32>,
) -> Result<(), KernelError> {
    palette.clear();
    let indices = match encoding {
        encoding::SINGLE => {
            palette.push(single_id(bytes)?);
            None
        }
        encoding::RING_PALETTE => {
            let mut r = Reader::new(bytes);
            let count = r.u16().map_err(truncated)? as usize;
            r.u16().map_err(truncated)?;
            if count == 0 || count > RING_CELLS {
                return Err(bad("ring slice palette must hold 1..=256 ids"));
            }
            for _ in 0..count {
                palette.push(r.u32().map_err(truncated)?);
            }
            Some(r.take(RING_CELLS).map_err(truncated)?)
        }
        _ => return Err(bad("unknown ring slice encoding")),
    };
    column
        .load_ring_states(side, section, palette, indices, table)
        .map_err(bad)
}

/// The result of a job whose table this instance does not hold.
fn table_missing(light_sections: usize) -> Vec<u8> {
    let mut out = vec![0u8; RESULT_HEADER_LEN];
    out[0] = RESULT_VERSION;
    out[1] = result_status::TABLE_MISSING;
    out[2..4].copy_from_slice(&(light_sections as u16).to_le_bytes());
    out
}

fn encode_result(column: &LightColumn, job_flags: u16) -> Vec<u8> {
    let light_sections = column.light_sections();
    let omit_unchanged = job_flags & job_flags::OMIT_UNCHANGED != 0;
    let mut section_flags = vec![0u8; light_sections];
    let mut counts = [0u32; 2];
    for (light_section, out) in section_flags.iter_mut().enumerate() {
        let stored = column.section_flags(0, light_section);
        let changed = column.changed(light_section);
        for (i, (enabled, storing)) in [
            (job_flags & job_flags::SKY != 0, flags::SKY_STORING),
            (job_flags & job_flags::BLOCK != 0, flags::BLOCK_STORING),
        ]
        .into_iter()
        .enumerate()
        {
            let was_changed = changed & (1 << i) != 0;
            if was_changed {
                *out |= 4 << i;
            }
            if enabled && stored & storing != 0 && (was_changed || !omit_unchanged) {
                *out |= 1 << i;
                counts[i] += 1;
            }
        }
    }
    let flags_end = (RESULT_HEADER_LEN + light_sections).next_multiple_of(8);
    let records = column.outgoing();
    let total = flags_end + (counts[0] + counts[1]) as usize * LAYER_BYTES + records.len() * OUTGOING_LEN;
    let mut out = vec![0u8; total];
    out[0] = RESULT_VERSION;
    out[2..4].copy_from_slice(&(light_sections as u16).to_le_bytes());
    let stats = column.stats();
    for (i, value) in [
        counts[0],
        counts[1],
        records.len() as u32,
        stats.increase_pops,
        stats.decrease_pops,
    ]
    .into_iter()
    .enumerate()
    {
        out[4 + 4 * i..8 + 4 * i].copy_from_slice(&value.to_le_bytes());
    }
    out[RESULT_HEADER_LEN..RESULT_HEADER_LEN + light_sections].copy_from_slice(&section_flags);
    let mut at = flags_end;
    for (bit, layer) in [(1u8, Layer::Sky), (2u8, Layer::Block)] {
        for (light_section, &value) in section_flags.iter().enumerate() {
            if value & bit != 0 {
                column.write_layer(light_section, layer, &mut out[at..at + LAYER_BYTES]);
                at += LAYER_BYTES;
            }
        }
    }
    for record in records {
        let raw = &mut out[at..at + OUTGOING_LEN];
        raw[0] = record.kind;
        raw[1] = record.side;
        raw[2] = record.along;
        raw[3] = record.level;
        raw[4..8].copy_from_slice(&record.y.to_le_bytes());
        raw[8..10].copy_from_slice(&record.entry.to_le_bytes());
        raw[10..12].copy_from_slice(&record.count.to_le_bytes());
        at += OUTGOING_LEN;
    }
    out
}

/// Builds `light_column` payloads (tests and tools; the page uses
/// `light-job.js`).
pub struct JobBuilder {
    pub ops: u8,
    pub flags: u16,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub min_section: i32,
    pub section_count: u32,
    pub sky_bottom_section: i32,
    pub neighbours: u8,
    pub table: Vec<u8>,
    pub table_epoch: u64,
    /// Per slot present (column first): light section flags.
    pub section_flags: Vec<Vec<u8>>,
    /// Per slot present: (encoding, bytes) per world section; ring slices for
    /// the neighbours (see [`ring_slice`]).
    pub sections: Vec<Vec<(u8, Vec<u8>)>>,
    /// Per slot present: the nibble arrays named by the flags, sky then block
    /// (2048 bytes for the column, 128-byte ring slices for the neighbours).
    pub layers: Vec<Vec<Vec<u8>>>,
    pub checks: Vec<(u8, u8, i32)>,
}

/// Encodes a ring slice of 256 state ids (`(y << 4) | along`): SINGLE when
/// every cell holds the same id, RING_PALETTE otherwise.
pub fn ring_slice(ids: &[u32]) -> (u8, Vec<u8>) {
    assert_eq!(ids.len(), RING_CELLS, "a ring slice holds 256 ids");
    let mut palette: Vec<u32> = Vec::new();
    let mut indices = vec![0u8; RING_CELLS];
    for (cell, &id) in ids.iter().enumerate() {
        let index = match palette.iter().position(|&p| p == id) {
            Some(index) => index,
            None => {
                palette.push(id);
                palette.len() - 1
            }
        };
        indices[cell] = index as u8;
    }
    if palette.len() == 1 {
        return (encoding::SINGLE, palette[0].to_le_bytes().to_vec());
    }
    let mut out = Vec::with_capacity(4 + palette.len() * 4 + RING_CELLS);
    out.extend_from_slice(&(palette.len() as u16).to_le_bytes());
    out.extend_from_slice(&[0, 0]);
    for id in &palette {
        out.extend_from_slice(&id.to_le_bytes());
    }
    out.extend_from_slice(&indices);
    (encoding::RING_PALETTE, out)
}

impl JobBuilder {
    pub fn encode(&self) -> Vec<u8> {
        fn pad(out: &mut Vec<u8>) {
            while !out.len().is_multiple_of(8) {
                out.push(0);
            }
        }
        let mut out = Vec::new();
        out.push(JOB_VERSION);
        out.push(self.ops);
        out.extend_from_slice(&self.flags.to_le_bytes());
        for value in [
            self.chunk_x,
            self.chunk_z,
            self.min_section,
            self.section_count as i32,
            self.sky_bottom_section,
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&[self.neighbours, 0, 0, 0]);
        out.extend_from_slice(&(self.table.len() as u32).to_le_bytes());
        out.extend_from_slice(&self.table_epoch.to_le_bytes());
        out.extend_from_slice(&(self.checks.len() as u32).to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        debug_assert_eq!(out.len(), HEADER_LEN);
        out.extend_from_slice(&self.table);
        pad(&mut out);
        for slot_flags in &self.section_flags {
            out.extend_from_slice(slot_flags);
        }
        pad(&mut out);
        for slot in &self.sections {
            for (encoding, bytes) in slot {
                out.extend_from_slice(&[*encoding, 0, 0, 0]);
                out.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
                out.extend_from_slice(bytes);
                pad(&mut out);
            }
        }
        for slot in &self.layers {
            for layer in slot {
                out.extend_from_slice(layer);
            }
        }
        for &(x, z, y) in &self.checks {
            out.extend_from_slice(&[x, z, 0, 0]);
            out.extend_from_slice(&y.to_le_bytes());
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gaius_light::table::make_props;

    fn table() -> LightTable {
        let props = vec![make_props(0, 0, 0), make_props(15, 0, 0), make_props(0, 14, 0)];
        LightTable::from_parts(props, &[[0; 6]], 1, |_, _| false).unwrap()
    }

    fn job(ops: u8, table: &LightTable, checks: Vec<(u8, u8, i32)>) -> JobBuilder {
        // One section: stone floor at y 0, a torch at (8, 1, 8), air above.
        let mut flat = vec![0u8; 8192];
        for i in 0..256 {
            flat[i * 2] = 1;
        }
        let torch = (1 << 8) | (8 << 4) | 8;
        flat[torch * 2] = 2;
        JobBuilder {
            ops,
            flags: job_flags::SKY | job_flags::BLOCK | job_flags::EMIT_OUTGOING,
            chunk_x: 0,
            chunk_z: 0,
            min_section: 0,
            section_count: 1,
            sky_bottom_section: -1,
            neighbours: 0,
            table: table.encode(),
            table_epoch: 42,
            section_flags: vec![vec![flags::SKY_STORING | flags::BLOCK_STORING; 3]],
            sections: vec![vec![(encoding::FLAT_U16, flat)]],
            layers: vec![vec![]],
            checks,
        }
    }

    #[test]
    fn first_light_returns_every_stored_layer() {
        let table = table();
        let mut kernel = Kernel::new();
        let out = kernel.run(&job(ops::INITIAL, &table, vec![]).encode()).unwrap();
        assert_eq!(out[0], RESULT_VERSION);
        assert_eq!(u16::from_le_bytes([out[2], out[3]]), 3);
        let sky_layers = u32::from_le_bytes(out[4..8].try_into().unwrap());
        let block_layers = u32::from_le_bytes(out[8..12].try_into().unwrap());
        assert_eq!((sky_layers, block_layers), (3, 3));
        // Section 1 (world y 0..15): the torch cell holds 14 block light.
        let block_start = 40 + 3 * 2048;
        let index = (1 << 8) | (8 << 4) | 8;
        let section1 = &out[block_start + 2048..block_start + 4096];
        assert_eq!((section1[index >> 1] >> ((index & 1) << 2)) & 15, 14);
        // Sky: y 1 open to the sky, y 0 stone.
        let sky1 = &out[40 + 2048..40 + 4096];
        let above = (1 << 8) | (3 << 4) | 3;
        assert_eq!((sky1[above >> 1] >> ((above & 1) << 2)) & 15, 15);
        assert_eq!(sky1[0] & 15, 0);

        // A second job reuses the cached table without the inline copy.
        let mut lean = job(ops::INITIAL | ops::CHECKS, &table, vec![(8, 8, 1)]);
        lean.table = Vec::new();
        let out = kernel.run(&lean.encode()).unwrap();
        assert_eq!(out[1], result_status::LIT);
        // An epoch the instance does not hold, without the table: "table missing".
        lean.table_epoch = 7;
        let out = kernel.run(&lean.encode()).unwrap();
        assert_eq!(
            (out[0], out[1], out.len()),
            (RESULT_VERSION, result_status::TABLE_MISSING, RESULT_HEADER_LEN)
        );
        assert_eq!(u16::from_le_bytes([out[2], out[3]]), 3);
        let mut fresh = Kernel::new();
        let mut cold = job(ops::INITIAL, &table, vec![]);
        cold.table = Vec::new();
        assert_eq!(fresh.run(&cold.encode()).unwrap()[1], result_status::TABLE_MISSING);
    }

    #[test]
    fn rejects_version_1_jobs() {
        let table = table();
        let mut bytes = job(ops::INITIAL, &table, vec![]).encode();
        bytes[0] = 1;
        assert_eq!(Kernel::new().run(&bytes).unwrap_err().status, Status::BadVersion);
    }

    #[test]
    fn ring_slices_reach_the_column() {
        // The east neighbour's touching cells are stone except a torch at (y 1, z 8): light
        // leaves the column east only into that one cell.
        let table = table();
        let mut ids = [0u32; RING_CELLS];
        for (cell, id) in ids.iter_mut().enumerate() {
            *id = if cell == (1 << 4) | 8 { 2 } else { 1 };
        }
        let (enc, bytes) = ring_slice(&ids);
        assert_eq!(enc, encoding::RING_PALETTE);
        assert_eq!(bytes.len(), 4 + 2 * 4 + RING_CELLS);
        let (single, single_bytes) = ring_slice(&[1u32; RING_CELLS]);
        assert_eq!((single, single_bytes.len()), (encoding::SINGLE, 4));

        let mut builder = job(ops::INITIAL, &table, vec![]);
        builder.neighbours = 1 << 3;
        builder
            .section_flags
            .push(vec![flags::SKY_STORING | flags::BLOCK_STORING | flags::SKY_DATA; 3]);
        builder.sections.push(vec![(enc, bytes)]);
        builder.layers.push(vec![vec![0x77; RING_LAYER_BYTES]; 3]);
        let out = Kernel::new().run(&builder.encode()).unwrap();
        assert_eq!(out[1], result_status::LIT);
        let layers =
            u32::from_le_bytes(out[4..8].try_into().unwrap()) + u32::from_le_bytes(out[8..12].try_into().unwrap());
        let records = u32::from_le_bytes(out[12..16].try_into().unwrap());
        let mut at = 40 + layers as usize * LAYER_BYTES;
        let mut sky_into_torch = 0;
        for _ in 0..records {
            let (kind, side, along, level) = (out[at], out[at + 1], out[at + 2], out[at + 3]);
            let y = i32::from_le_bytes(out[at + 4..at + 8].try_into().unwrap());
            assert_eq!(
                (side, along, y),
                (3, 8, 1),
                "light left the column into a stone ring cell"
            );
            if kind == gaius_light::outgoing::SKY_INCREASE && level == 14 {
                sky_into_torch += 1;
            }
            at += OUTGOING_LEN;
        }
        assert_eq!(sky_into_torch, 1);

        // A truncated ring slice or a ring layer cut short is refused, not misread.
        let mut short = builder.clone_with_ring(vec![(encoding::RING_PALETTE, vec![2, 0, 0, 0, 1, 0, 0, 0])]);
        assert_eq!(
            Kernel::new().run(&short.encode()).unwrap_err().status,
            Status::Truncated
        );
        short = builder.clone_with_ring(vec![(encoding::NETWORK, vec![0; 8])]);
        assert_eq!(
            Kernel::new().run(&short.encode()).unwrap_err().status,
            Status::BadPayload
        );
        let mut cut = builder.encode();
        cut.truncate(cut.len() - 1);
        assert_eq!(Kernel::new().run(&cut).unwrap_err().status, Status::Truncated);
    }

    impl JobBuilder {
        fn clone_with_ring(&self, ring: Vec<(u8, Vec<u8>)>) -> JobBuilder {
            JobBuilder {
                ops: self.ops,
                flags: self.flags,
                chunk_x: self.chunk_x,
                chunk_z: self.chunk_z,
                min_section: self.min_section,
                section_count: self.section_count,
                sky_bottom_section: self.sky_bottom_section,
                neighbours: self.neighbours,
                table: self.table.clone(),
                table_epoch: self.table_epoch,
                section_flags: self.section_flags.clone(),
                sections: vec![self.sections[0].clone(), ring],
                layers: self.layers.clone(),
                checks: self.checks.clone(),
            }
        }
    }
}
