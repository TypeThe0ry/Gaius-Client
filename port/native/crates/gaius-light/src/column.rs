//! One chunk column plus a one-block ring of its four neighbours, lit the way
//! `SkyLightEngine` / `BlockLightEngine` light it.
//!
//! Layout: structure of arrays over an 18 x H x 18 box (x and z run -1..=16,
//! H = 16 x light sections, the light sections being the world sections plus
//! one below and one above, as in `LevelLightEngine`). Cell
//! `(y * 18 + z + 1) * 18 + x + 1` holds the packed [`Props`] of its block
//! state and one `u8` level per light layer, so a neighbour is a constant
//! offset away and an x row is 16 contiguous bytes. The ring holds the
//! neighbours' states and stored levels so light can be checked against them
//! at the border, but light is only ever written inside the column: whatever
//! vanilla would push into a neighbour is reported as an [`Outgoing`] record
//! for the Java side to feed into the vanilla engine.
//!
//! Propagation mirrors `LightEngine.propagateIncreases/Decreases` and the
//! `propagateIncrease/Decrease` overrides entry for entry (same queue entries,
//! same storing checks, same `shapeOccludes`). Decreases run first-in
//! first-out like vanilla. Increases run from level 15 down instead of first
//! in first out: increase propagation only ever raises a level to the maximum
//! over its paths, so the final levels do not depend on the order, and the
//! bucket order sets every cell once instead of re-raising it.

use crate::dir::{self, opposite, DOWN, EAST, NORTH, SOUTH, UP, WEST};
use crate::nibble::{pack_row, unpack_row, LAYER_BYTES};
use crate::section::{emitting_props, lookup, transparent_props, DecodedSection, SectionStates};
use crate::table::{dampening, emission, is_empty_shape, opacity, shape_class, LightTable, Props};
use alloc::vec::Vec;

pub const SIDE: usize = 18;
pub const PLANE: usize = SIDE * SIDE;
const OFFSETS: [isize; 6] = [
    -(PLANE as isize),
    PLANE as isize,
    -(SIDE as isize),
    SIDE as isize,
    -1,
    1,
];

/// Ring sides, also the order of the neighbour data in a job.
pub const SIDE_NORTH: usize = 0;
pub const SIDE_SOUTH: usize = 1;
pub const SIDE_WEST: usize = 2;
pub const SIDE_EAST: usize = 3;

/// Most world sections a column may have (`LevelHeightAccessor` caps the
/// height at 4064 blocks).
pub const MAX_SECTIONS: usize = 254;

/// Cells of one ring slice: the 16 x 16 cells of a neighbour section that
/// touch the column, indexed `(y << 4) | along` (along = x for north and
/// south, z for west and east).
pub const RING_CELLS: usize = 256;
/// Nibble bytes of one ring slice of a light layer, packed like a
/// `DataLayer` (index `(y << 4) | along`, the even index in the low nibble).
pub const RING_LAYER_BYTES: usize = RING_CELLS / 2;

/// Per light section flags, one byte per section and slot.
pub mod flags {
    /// The sky layer stores data for this section (`storingLightForSection`).
    pub const SKY_STORING: u8 = 1;
    /// The block layer stores data for this section.
    pub const BLOCK_STORING: u8 = 2;
    /// A sky nibble array follows in the job.
    pub const SKY_DATA: u8 = 4;
    /// A block nibble array follows in the job.
    pub const BLOCK_DATA: u8 = 8;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Layer {
    Sky,
    Block,
}

impl Layer {
    #[inline(always)]
    const fn storing_bit(self) -> u8 {
        match self {
            Layer::Sky => flags::SKY_STORING,
            Layer::Block => flags::BLOCK_STORING,
        }
    }
}

/// What the column is and what the light engine knows about it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ColumnSpec {
    /// `LevelHeightAccessor.getMinSectionY()`.
    pub min_section: i32,
    /// `LevelHeightAccessor.getSectionsCount()`.
    pub section_count: usize,
    /// `SkyLightSectionStorage.getBottomSectionY()` (`currentLowestY`).
    pub sky_bottom_section: i32,
    /// `lightOnInSection` of the column, per layer (`checkNode`, emission).
    pub sky_light_on: bool,
    pub block_light_on: bool,
    /// Report light that leaves the column as [`Outgoing`] records.
    pub emit_outgoing: bool,
    /// Bit per ring side whose neighbour chunk is loaded (`getChunkForLighting`).
    pub neighbours: u8,
}

/// Outgoing record kinds.
pub mod outgoing {
    pub const SKY_INCREASE: u8 = 0;
    pub const BLOCK_INCREASE: u8 = 1;
    pub const SKY_DECREASE: u8 = 2;
    pub const BLOCK_DECREASE: u8 = 3;
}

/// Light that vanilla would propagate from the column into a neighbour.
///
/// The ring cell is `side` (0 north z-1, 1 south z+16, 2 west x-1, 3 east
/// x+16), `along` (x for north/south, z for west/east) and absolute `y`; the
/// direction from the column into it is `side + 2` (`Direction` ordinal).
///
/// - increase: vanilla's `propagateIncrease` reached the ring cell with
///   `level` = `newToLevel` (already checked against opacity, the shapes and
///   the ring level of the job); the Java side re-checks `level` against the
///   live stored level, stores it and enqueues `entry` when `level > 1`. Sky
///   records with `count > 0` also run `propagateFromEmptySections(pos, dir,
///   level, true, count)`;
/// - decrease: `propagateDecrease` reached the ring cell from a cell whose old
///   level was `level`; the Java side runs the vanilla branch on the live
///   stored level (zero and enqueue a decrease, or pull light back in), sky
///   records with `count > 0` also run `propagateFromEmptySections(pos, dir,
///   storedLevel, false, count)` when the cell is zeroed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Outgoing {
    pub kind: u8,
    pub side: u8,
    pub along: u8,
    pub level: u8,
    pub y: i32,
    pub entry: u16,
    pub count: u16,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Stats {
    pub increase_pops: u32,
    pub decrease_pops: u32,
}

pub struct LightColumn {
    spec: ColumnSpec,
    light_sections: usize,
    height: usize,
    props: Vec<Props>,
    sky: Vec<u8>,
    block: Vec<u8>,
    /// Slot 0 is the column, slots 1..=4 the ring sides.
    slot_flags: [Vec<u8>; 5],
    /// Per world section and slot: bit 0 transparent, bit 1 may emit.
    section_hints: [Vec<u8>; 5],
    changed: Vec<u8>,
    sources: [i32; 256],
    ring_sources: [[i32; 16]; 4],
    increase: [Vec<u64>; 16],
    fifo_increase: Option<Vec<u64>>,
    decrease: Vec<u64>,
    outgoing: Vec<Outgoing>,
    palette_scratch: Vec<Props>,
    stats: Stats,
}

const HINT_TRANSPARENT: u8 = 1;
const HINT_MAY_EMIT: u8 = 2;

#[inline(always)]
const fn cell(x: i32, y: usize, z: i32) -> usize {
    (y * SIDE + (z + 1) as usize) * SIDE + (x + 1) as usize
}

#[inline(always)]
const fn coords(cell: usize) -> (i32, usize, i32) {
    let y = cell / PLANE;
    let rem = cell - y * PLANE;
    let z = (rem / SIDE) as i32 - 1;
    let x = (rem % SIDE) as i32 - 1;
    (x, y, z)
}

#[inline(always)]
const fn outside(v: i32) -> bool {
    v < 0 || v > 15
}

/// Ring side of a non-corner ring cell.
#[inline(always)]
const fn side_of(x: i32, z: i32) -> usize {
    if z < 0 {
        SIDE_NORTH
    } else if z > 15 {
        SIDE_SOUTH
    } else if x < 0 {
        SIDE_WEST
    } else {
        SIDE_EAST
    }
}

#[inline(always)]
const fn along_of(side: usize, x: i32, z: i32) -> u8 {
    if side <= SIDE_SOUTH {
        x as u8
    } else {
        z as u8
    }
}

/// Ring cell coordinates of `side`/`along` at the given row.
#[inline(always)]
const fn ring_xz(side: usize, along: i32) -> (i32, i32) {
    match side {
        SIDE_NORTH => (along, -1),
        SIDE_SOUTH => (along, 16),
        SIDE_WEST => (-1, along),
        _ => (16, along),
    }
}

/// Direction from a ring side back into the column.
#[inline(always)]
const fn inward(side: usize) -> usize {
    match side {
        SIDE_NORTH => SOUTH,
        SIDE_SOUTH => NORTH,
        SIDE_WEST => EAST,
        _ => WEST,
    }
}

impl Default for LightColumn {
    fn default() -> Self {
        Self::new()
    }
}

impl LightColumn {
    pub fn new() -> LightColumn {
        LightColumn {
            spec: ColumnSpec {
                min_section: 0,
                section_count: 0,
                sky_bottom_section: 0,
                sky_light_on: false,
                block_light_on: false,
                emit_outgoing: false,
                neighbours: 0,
            },
            light_sections: 0,
            height: 0,
            props: Vec::new(),
            sky: Vec::new(),
            block: Vec::new(),
            slot_flags: Default::default(),
            section_hints: Default::default(),
            changed: Vec::new(),
            sources: [i32::MIN; 256],
            ring_sources: [[i32::MIN; 16]; 4],
            increase: Default::default(),
            fifo_increase: None,
            decrease: Vec::new(),
            outgoing: Vec::new(),
            palette_scratch: Vec::new(),
            stats: Stats::default(),
        }
    }

    /// Resets the column for a new job, keeping every buffer's capacity.
    pub fn begin(&mut self, spec: ColumnSpec) -> Result<(), &'static str> {
        if spec.section_count == 0 || spec.section_count > MAX_SECTIONS {
            return Err("column section count must be 1..=254");
        }
        if spec.min_section < -2048 || spec.min_section > 2048 {
            return Err("column min section is out of range");
        }
        let mut spec = spec;
        // The storage never holds data below the padding section.
        spec.sky_bottom_section = spec.sky_bottom_section.max(spec.min_section - 1);
        self.spec = spec;
        self.light_sections = spec.section_count + 2;
        self.height = self.light_sections * 16;
        let cells = PLANE * self.height;
        for buffer in [&mut self.sky, &mut self.block] {
            buffer.clear();
            buffer.resize(cells, 0);
        }
        self.props.clear();
        self.props.resize(cells, 0);
        for slot in self.slot_flags.iter_mut() {
            slot.clear();
            slot.resize(self.light_sections, 0);
        }
        for hints in self.section_hints.iter_mut() {
            hints.clear();
            hints.resize(spec.section_count, HINT_TRANSPARENT);
        }
        self.changed.clear();
        self.changed.resize(self.light_sections, 0);
        self.sources = [i32::MIN; 256];
        self.ring_sources = [[i32::MIN; 16]; 4];
        for bucket in self.increase.iter_mut() {
            bucket.clear();
        }
        if let Some(fifo) = self.fifo_increase.as_mut() {
            fifo.clear();
        }
        self.decrease.clear();
        self.outgoing.clear();
        self.stats = Stats::default();
        Ok(())
    }

    /// Test hook: run increases first in first out, exactly like vanilla.
    #[doc(hidden)]
    pub fn set_vanilla_increase_order(&mut self, fifo: bool) {
        self.fifo_increase = if fifo { Some(Vec::new()) } else { None };
    }

    pub fn spec(&self) -> &ColumnSpec {
        &self.spec
    }

    pub fn light_sections(&self) -> usize {
        self.light_sections
    }

    pub fn stats(&self) -> Stats {
        self.stats
    }

    pub fn outgoing(&self) -> &[Outgoing] {
        &self.outgoing
    }

    /// Changed bits of a light section: bit 0 sky, bit 1 block.
    pub fn changed(&self, light_section: usize) -> u8 {
        self.changed[light_section]
    }

    fn neighbour_present(&self, side: usize) -> bool {
        self.spec.neighbours & (1 << side) != 0
    }

    /// Loads the states of world section `section` of slot 0 (the column) or
    /// 1 + side (a neighbour; only its cells facing the column are kept).
    pub fn load_section(
        &mut self,
        slot: usize,
        section: usize,
        states: SectionStates<'_>,
        table: &LightTable,
    ) -> Result<(), &'static str> {
        if slot > 4 || section >= self.spec.section_count {
            return Err("section slot is out of range");
        }
        if slot > 0 && !self.neighbour_present(slot - 1) {
            return Err("section for a neighbour that is not present");
        }
        let LightColumn {
            props,
            palette_scratch,
            section_hints,
            ..
        } = self;
        let decoded = DecodedSection::decode(states, table, palette_scratch)?;
        let base = (section + 1) * 16;
        let mut hints = 0;
        if decoded.is_transparent() {
            hints |= HINT_TRANSPARENT;
        }
        if decoded.may_emit() {
            hints |= HINT_MAY_EMIT;
        }
        section_hints[slot][section] = hints;
        if slot == 0 {
            return decoded.write_all(props, |y, z| cell(0, base + y, z as i32));
        }
        let side = slot - 1;
        if let Some(uniform) = decoded.uniform() {
            for y in 0..16 {
                for along in 0..16 {
                    let (x, z) = ring_xz(side, along);
                    props[cell(x, base + y, z)] = uniform;
                }
            }
            return Ok(());
        }
        for y in 0..16 {
            for along in 0..16i32 {
                // The neighbour's own cell that touches the column.
                let (nx, nz) = match side {
                    SIDE_NORTH => (along, 15),
                    SIDE_SOUTH => (along, 0),
                    SIDE_WEST => (15, along),
                    _ => (0, along),
                };
                let (x, z) = ring_xz(side, along);
                props[cell(x, base + y, z)] = decoded.get((y << 8) | ((nz as usize) << 4) | nx as usize)?;
            }
        }
        Ok(())
    }

    /// Sets the [`flags`] of a light section (0 = the padding section below
    /// the world) for slot 0 (the column) or 1 + side.
    pub fn set_section_flags(&mut self, slot: usize, light_section: usize, value: u8) -> Result<(), &'static str> {
        if slot > 4 || light_section >= self.light_sections {
            return Err("section flags slot is out of range");
        }
        if slot > 0 && !self.neighbour_present(slot - 1) {
            return Err("section flags for a neighbour that is not present");
        }
        self.slot_flags[slot][light_section] = value;
        Ok(())
    }

    pub fn section_flags(&self, slot: usize, light_section: usize) -> u8 {
        self.slot_flags[slot][light_section]
    }

    fn levels_mut(&mut self, layer: Layer) -> &mut Vec<u8> {
        match layer {
            Layer::Sky => &mut self.sky,
            Layer::Block => &mut self.block,
        }
    }

    /// Loads a stored `DataLayer` (2048 nibble bytes) into slot 0 or a ring side.
    pub fn load_layer(
        &mut self,
        slot: usize,
        light_section: usize,
        layer: Layer,
        nibbles: &[u8],
    ) -> Result<(), &'static str> {
        if slot > 4 || light_section >= self.light_sections || nibbles.len() < LAYER_BYTES {
            return Err("layer slot is out of range");
        }
        let base = light_section * 16;
        let levels = self.levels_mut(layer);
        for y in 0..16 {
            match slot {
                0 => {
                    for z in 0..16 {
                        let start = cell(0, base + y, z);
                        let at = (y << 7) | ((z as usize) << 3);
                        unpack_row(&nibbles[at..at + 8], &mut levels[start..start + 16]);
                    }
                }
                1 | 2 => {
                    // North ring row = the neighbour's z 15 row, south = its z 0 row.
                    let nz = if slot == 1 { 15 } else { 0 };
                    let rz = if slot == 1 { -1 } else { 16 };
                    let start = cell(0, base + y, rz);
                    let at = (y << 7) | (nz << 3);
                    unpack_row(&nibbles[at..at + 8], &mut levels[start..start + 16]);
                }
                _ => {
                    let (nx, rx) = if slot == 3 { (15usize, -1) } else { (0usize, 16) };
                    for z in 0..16 {
                        let index = (y << 8) | (z << 4) | nx;
                        let level = (nibbles[index >> 1] >> ((index & 1) << 2)) & 15;
                        levels[cell(rx, base + y, z as i32)] = level;
                    }
                }
            }
        }
        Ok(())
    }

    /// Loads the ring slice of world section `section` on `side`: the
    /// neighbour cells that touch the column, as `palette` ids picked by one
    /// byte per cell from `indices` ([`RING_CELLS`], `(y << 4) | along`), or
    /// a single id for the whole slice when `indices` is `None`. The column
    /// reads nothing else of a neighbour's states, so this lights exactly like
    /// [`LightColumn::load_section`] with the full neighbour section.
    pub fn load_ring_states(
        &mut self,
        side: usize,
        section: usize,
        palette: &[u32],
        indices: Option<&[u8]>,
        table: &LightTable,
    ) -> Result<(), &'static str> {
        if side > 3 || section >= self.spec.section_count {
            return Err("ring slice is out of range");
        }
        if !self.neighbour_present(side) {
            return Err("ring slice for a neighbour that is not present");
        }
        if palette.is_empty() || palette.len() > RING_CELLS {
            return Err("ring slice palette must hold 1..=256 ids");
        }
        if indices.is_none() && palette.len() != 1 {
            return Err("a ring slice without indices names exactly one id");
        }
        if indices.is_some_and(|indices| indices.len() != RING_CELLS) {
            return Err("ring slice indices must cover 256 cells");
        }
        let LightColumn {
            props,
            palette_scratch,
            section_hints,
            ..
        } = self;
        palette_scratch.clear();
        for &id in palette {
            palette_scratch.push(lookup(table, id)?);
        }
        let mut hints = 0;
        if palette_scratch.iter().all(|&p| transparent_props(p)) {
            hints |= HINT_TRANSPARENT;
        }
        if palette_scratch.iter().any(|&p| emitting_props(p)) {
            hints |= HINT_MAY_EMIT;
        }
        section_hints[side + 1][section] = hints;
        let base = (section + 1) * 16;
        for y in 0..16 {
            for along in 0..16 {
                let value = match indices {
                    Some(indices) => *palette_scratch
                        .get(indices[(y << 4) | along] as usize)
                        .ok_or("ring slice index is outside its palette")?,
                    None => palette_scratch[0],
                };
                let (x, z) = ring_xz(side, along as i32);
                props[cell(x, base + y, z)] = value;
            }
        }
        Ok(())
    }

    /// Loads the stored levels of the ring slice of light section
    /// `light_section` on `side` ([`RING_LAYER_BYTES`] nibble bytes).
    pub fn load_ring_layer(
        &mut self,
        side: usize,
        light_section: usize,
        layer: Layer,
        nibbles: &[u8],
    ) -> Result<(), &'static str> {
        if side > 3 || light_section >= self.light_sections || nibbles.len() < RING_LAYER_BYTES {
            return Err("ring layer is out of range");
        }
        let base = light_section * 16;
        let levels = self.levels_mut(layer);
        for y in 0..16 {
            let row = &nibbles[y * 8..y * 8 + 8];
            match side {
                SIDE_NORTH | SIDE_SOUTH => {
                    let rz = if side == SIDE_NORTH { -1 } else { 16 };
                    let start = cell(0, base + y, rz);
                    unpack_row(row, &mut levels[start..start + 16]);
                }
                _ => {
                    let rx = if side == SIDE_WEST { -1 } else { 16 };
                    let mut unpacked = [0u8; 16];
                    unpack_row(row, &mut unpacked);
                    for (z, &level) in unpacked.iter().enumerate() {
                        levels[cell(rx, base + y, z as i32)] = level;
                    }
                }
            }
        }
        Ok(())
    }

    /// Packs the column's levels of a light section into `out` (2048 bytes).
    pub fn write_layer(&self, light_section: usize, layer: Layer, out: &mut [u8]) {
        let levels = match layer {
            Layer::Sky => &self.sky,
            Layer::Block => &self.block,
        };
        let base = light_section * 16;
        for y in 0..16 {
            for z in 0..16 {
                let start = cell(0, base + y, z);
                let at = (y << 7) | ((z as usize) << 3);
                pack_row(&levels[start..start + 16], &mut out[at..at + 8]);
            }
        }
    }

    /// Level of a column cell at absolute `y` (tests and tools).
    pub fn level(&self, layer: Layer, x: i32, y: i32, z: i32) -> u8 {
        let levels = match layer {
            Layer::Sky => &self.sky,
            Layer::Block => &self.block,
        };
        levels[cell(x, self.y_cell(y), z)]
    }

    /// Lowest sky source y of a column x/z (`ChunkSkyLightSources`).
    pub fn lowest_source(&self, x: usize, z: usize) -> i32 {
        self.sources[(z << 4) | x]
    }

    #[inline(always)]
    fn light_min_y(&self) -> i32 {
        (self.spec.min_section - 1) * 16
    }

    #[inline(always)]
    fn y_cell(&self, y: i32) -> usize {
        (y - self.light_min_y()) as usize
    }

    #[inline(always)]
    fn abs_y(&self, y_cell: usize) -> i32 {
        y_cell as i32 + self.light_min_y()
    }

    /// `storingLightForSection` of the column for an absolute section y.
    #[inline(always)]
    fn storing_at(&self, slot: usize, layer: Layer, section_y: i32) -> bool {
        let index = section_y - (self.spec.min_section - 1);
        index >= 0
            && (index as usize) < self.light_sections
            && self.slot_flags[slot][index as usize] & layer.storing_bit() != 0
    }

    #[inline(always)]
    fn storing(&self, slot: usize, layer: Layer, light_section: usize) -> bool {
        self.slot_flags[slot][light_section] & layer.storing_bit() != 0
    }

    /// `SkyLightSectionStorage.getTopSectionY` of the column.
    fn top_section(&self) -> i32 {
        (0..self.light_sections)
            .rev()
            .find(|&s| self.storing(0, Layer::Sky, s))
            .map(|s| s as i32 + self.spec.min_section)
            .unwrap_or(self.spec.sky_bottom_section)
    }

    /// `ChunkSkyLightSources.findLowestSourceY` for one x/z of a slot.
    fn find_lowest_source(&self, slot: usize, x: i32, z: i32, table: &LightTable) -> i32 {
        let mut above = 0usize;
        for section in (0..self.spec.section_count).rev() {
            let base = (section + 1) * 16;
            let bottom_y = (self.spec.min_section + section as i32) * 16;
            if self.section_hints[slot][section] & HINT_TRANSPARENT != 0 {
                if above != 0 && table.occludes(above, 0, DOWN) {
                    return bottom_y + 16;
                }
                above = 0;
                continue;
            }
            for y in (0..16).rev() {
                let props = self.props[cell(x, base + y, z)];
                if dampening(props) != 0 || table.occludes(above, shape_class(props), DOWN) {
                    return bottom_y + y as i32 + 1;
                }
                above = shape_class(props);
            }
        }
        // Nothing occludes: the heightmap holds minY, which reads as "sources
        // extend below the world".
        i32::MIN
    }

    /// Fills the sky sources of the column and of the ring columns.
    pub fn compute_sources(&mut self, table: &LightTable) {
        for z in 0..16 {
            for x in 0..16 {
                self.sources[(z << 4) | x] = self.find_lowest_source(0, x as i32, z as i32, table);
            }
        }
        for side in 0..4 {
            for along in 0..16 {
                self.ring_sources[side][along] = if self.neighbour_present(side) {
                    let (x, z) = ring_xz(side, along as i32);
                    self.find_lowest_source(side + 1, x, z, table)
                } else {
                    // getChunkSources == null: emptyChunkSources, all MIN_VALUE.
                    i32::MIN
                };
            }
        }
    }

    /// `getLowestSourceY(x, z, MIN_VALUE)` for column or ring coordinates.
    #[inline(always)]
    fn lowest_at(&self, x: i32, z: i32) -> i32 {
        if !outside(x) && !outside(z) {
            self.sources[((z as usize) << 4) | x as usize]
        } else {
            let side = side_of(x, z);
            self.ring_sources[side][along_of(side, x, z) as usize]
        }
    }

    #[inline(always)]
    fn mark(&mut self, layer: Layer, y_cell: usize) {
        self.changed[y_cell >> 4] |= match layer {
            Layer::Sky => 1,
            Layer::Block => 2,
        };
    }

    #[inline(always)]
    fn push_increase(&mut self, cell: usize, entry: u16) {
        let packed = ((cell as u64) << 16) | entry as u64;
        match self.fifo_increase.as_mut() {
            Some(fifo) => fifo.push(packed),
            None => self.increase[dir::level(entry) as usize].push(packed),
        }
    }

    #[inline(always)]
    fn push_decrease(&mut self, cell: usize, entry: u16) {
        self.decrease.push(((cell as u64) << 16) | entry as u64);
    }

    /// `BlockLightEngine.propagateLightSources`: every emitting block of the
    /// column (`findBlockLightSources`) starts an emission increase.
    pub fn enqueue_block_sources(&mut self) {
        for section in 0..self.spec.section_count {
            if self.section_hints[0][section] & HINT_MAY_EMIT == 0 || !self.storing(0, Layer::Block, section + 1) {
                continue;
            }
            let base = (section + 1) * 16;
            for y in base..base + 16 {
                for z in 0..16 {
                    let start = cell(0, y, z);
                    for c in start..start + 16 {
                        let props = self.props[c];
                        let light = emission(props);
                        if light != 0 {
                            self.push_increase(c, dir::increase_from_emission(light, is_empty_shape(props)));
                        }
                    }
                }
            }
        }
    }

    /// `SkyLightEngine.propagateLightSources`.
    pub fn enqueue_sky_sources(&mut self) {
        let top = self.top_section();
        let bottom = self.spec.sky_bottom_section;
        let mut section_y = top - 1;
        while section_y >= bottom {
            if !self.storing_at(0, Layer::Sky, section_y) {
                section_y -= 1;
                continue;
            }
            let section_bottom = section_y * 16;
            let section_top = section_bottom + 15;
            let mut continues_below = false;
            for z in 0..16i32 {
                for x in 0..16i32 {
                    let lowest = self.lowest_at(x, z);
                    if lowest > section_top {
                        continue;
                    }
                    let north = self.lowest_at(x, z - 1);
                    let south = self.lowest_at(x, z + 1);
                    let west = self.lowest_at(x - 1, z);
                    let east = self.lowest_at(x + 1, z);
                    let neighbour_max = north.max(south).max(west.max(east));
                    let mut y = section_top;
                    while y >= section_bottom.max(lowest) {
                        let y_cell = self.y_cell(y);
                        let c = cell(x, y_cell, z);
                        self.sky[c] = 15;
                        self.mark(Layer::Sky, y_cell);
                        if y == lowest || y < neighbour_max {
                            let entry = dir::increase_sky_source(y == lowest, y < north, y < south, y < west, y < east);
                            self.push_increase(c, entry);
                        }
                        y -= 1;
                    }
                    if lowest < section_bottom {
                        continues_below = true;
                    }
                }
            }
            if !continues_below {
                break;
            }
            section_y -= 1;
        }
    }

    /// `BlockLightEngine.checkNode` for an absolute column position.
    pub fn check_block(&mut self, x: i32, y: i32, z: i32) {
        let y_cell = self.y_cell(y);
        if !self.storing(0, Layer::Block, y_cell >> 4) {
            return;
        }
        let c = cell(x, y_cell, z);
        let props = self.props[c];
        let light = self.emission_on(props);
        let old = self.block[c] as u32;
        if light < old {
            self.block[c] = 0;
            self.mark(Layer::Block, y_cell);
            self.push_decrease(c, dir::decrease_all(old));
        } else {
            self.push_decrease(c, dir::PULL_LIGHT_IN);
        }
        if light > 0 {
            self.push_increase(c, dir::increase_from_emission(light, is_empty_shape(props)));
        }
    }

    /// `SkyLightEngine.checkNode` for an absolute column position.
    pub fn check_sky(&mut self, x: i32, y: i32, z: i32) {
        let lowest = if self.spec.sky_light_on {
            self.lowest_at(x, z)
        } else {
            i32::MAX
        };
        if lowest != i32::MAX {
            let world_bottom = self.spec.sky_bottom_section * 16;
            self.remove_sources_below(x, z, lowest, world_bottom);
            self.add_sources_above(x, z, lowest, world_bottom);
        }
        let y_cell = self.y_cell(y);
        if !self.storing(0, Layer::Sky, y_cell >> 4) {
            return;
        }
        let c = cell(x, y_cell, z);
        if y >= lowest {
            self.push_decrease(c, dir::REMOVE_SKY_SOURCE);
            self.push_increase(c, dir::ADD_SKY_SOURCE);
        } else {
            let old = self.sky[c] as u32;
            if old > 0 {
                self.sky[c] = 0;
                self.mark(Layer::Sky, y_cell);
                self.push_decrease(c, dir::decrease_all(old));
            } else {
                self.push_decrease(c, dir::PULL_LIGHT_IN);
            }
        }
    }

    fn remove_sources_below(&mut self, x: i32, z: i32, lowest: i32, world_bottom: i32) {
        if lowest <= world_bottom {
            return;
        }
        let source_y = lowest - 1;
        let mut section_y = source_y >> 4;
        while section_y >= self.spec.sky_bottom_section {
            if self.storing_at(0, Layer::Sky, section_y) {
                let section_bottom = section_y * 16;
                let mut y = (section_bottom + 15).min(source_y);
                while y >= section_bottom {
                    let y_cell = self.y_cell(y);
                    let c = cell(x, y_cell, z);
                    if self.sky[c] != 15 {
                        return;
                    }
                    self.sky[c] = 0;
                    self.mark(Layer::Sky, y_cell);
                    let entry = if y == lowest - 1 {
                        dir::REMOVE_TOP_SKY_SOURCE
                    } else {
                        dir::REMOVE_SKY_SOURCE
                    };
                    self.push_decrease(c, entry);
                    y -= 1;
                }
            }
            section_y -= 1;
        }
    }

    fn add_sources_above(&mut self, x: i32, z: i32, lowest: i32, world_bottom: i32) {
        let neighbour_lowest = self
            .lowest_at(x - 1, z)
            .max(self.lowest_at(x + 1, z))
            .max(self.lowest_at(x, z - 1).max(self.lowest_at(x, z + 1)));
        let source_y = lowest.max(world_bottom);
        let top = self.top_section();
        let bottom = self.spec.sky_bottom_section;
        let mut section_y = source_y >> 4;
        // isAboveData
        while !(top == bottom || section_y >= top) {
            if self.storing_at(0, Layer::Sky, section_y) {
                let section_bottom = section_y * 16;
                let mut y = section_bottom.max(source_y);
                while y <= section_bottom + 15 {
                    let y_cell = self.y_cell(y);
                    let c = cell(x, y_cell, z);
                    if self.sky[c] == 15 {
                        return;
                    }
                    self.sky[c] = 15;
                    self.mark(Layer::Sky, y_cell);
                    if y < neighbour_lowest || y == lowest {
                        self.push_increase(c, dir::ADD_SKY_SOURCE);
                    }
                    y += 1;
                }
            }
            section_y += 1;
        }
    }

    /// `getEmission`: emission only counts once the column's light is on.
    #[inline(always)]
    fn emission_on(&self, props: Props) -> u32 {
        let light = emission(props);
        if light > 0 && self.spec.block_light_on {
            light
        } else {
            0
        }
    }

    /// Seeds increases from the neighbours' stored levels into the column, as
    /// if each lit ring cell had just been lit (`increaseOnlyOneDirection`).
    /// Vanilla does not do this when lighting a chunk (the neighbours wrote
    /// into the column when they were lit); use it when the column's own
    /// stored data is not passed in. Sky light pulled in this way does not
    /// run `propagateFromEmptySections` (see `propagate_increase`).
    pub fn enqueue_ring_light(&mut self, layer: Layer) {
        for side in 0..4 {
            if !self.neighbour_present(side) {
                continue;
            }
            let toward = inward(side);
            for light_section in 0..self.light_sections {
                if !self.storing(side + 1, layer, light_section) || !self.storing(0, layer, light_section) {
                    continue;
                }
                for y in light_section * 16..light_section * 16 + 16 {
                    for along in 0..16 {
                        let (x, z) = ring_xz(side, along);
                        let c = cell(x, y, z);
                        let level = match layer {
                            Layer::Sky => self.sky[c],
                            Layer::Block => self.block[c],
                        } as u32;
                        if level > 1 {
                            self.push_increase(c, dir::increase_only_one(level, false, toward));
                        }
                    }
                }
            }
        }
    }

    /// `countEmptySectionsBelowIfAtBorder` for a cell of `slot` (the caller
    /// knows the cell sits on a chunk border).
    fn empty_sections_below(&self, slot: usize, y_cell: usize) -> u16 {
        if y_cell & 15 != 0 {
            return 0;
        }
        let section_y = (self.abs_y(y_cell)) >> 4;
        let mut count = 0i32;
        while !self.storing_at(slot, Layer::Sky, section_y - count - 1)
            && section_y - count > self.spec.sky_bottom_section
        {
            count += 1;
        }
        count as u16
    }

    /// Runs the queued decreases, then the queued increases, of one layer.
    pub fn propagate(&mut self, layer: Layer, table: &LightTable) {
        let mut head = 0;
        while head < self.decrease.len() {
            let packed = self.decrease[head];
            head += 1;
            self.stats.decrease_pops += 1;
            self.propagate_decrease(layer, (packed >> 16) as usize, packed as u16);
        }
        self.decrease.clear();

        if let Some(mut fifo) = self.fifo_increase.take() {
            let mut head = 0;
            while head < fifo.len() {
                let packed = fifo[head];
                head += 1;
                self.fifo_increase = Some(fifo);
                self.pop_increase(layer, packed, table);
                fifo = self.fifo_increase.take().unwrap();
            }
            fifo.clear();
            self.fifo_increase = Some(fifo);
            return;
        }
        for bucket in (1..16).rev() {
            let mut i = 0;
            while i < self.increase[bucket].len() {
                let packed = self.increase[bucket][i];
                i += 1;
                self.pop_increase(layer, packed, table);
            }
            self.increase[bucket].clear();
        }
        self.increase[0].clear();
    }

    /// `LightEngine.propagateIncreases` loop body.
    #[inline(always)]
    fn pop_increase(&mut self, layer: Layer, packed: u64, table: &LightTable) {
        self.stats.increase_pops += 1;
        let c = (packed >> 16) as usize;
        let entry = packed as u16;
        let entry_level = dir::level(entry);
        let levels = self.levels_mut(layer);
        let mut from_level = levels[c] as u32;
        if entry_level == 0 {
            return;
        }
        if dir::from_emission(entry) && from_level < entry_level {
            levels[c] = entry_level as u8;
            from_level = entry_level;
            self.mark(layer, c / PLANE);
        }
        if from_level == entry_level {
            self.propagate_increase(layer, c, entry, from_level, table);
        }
    }

    #[allow(clippy::needless_range_loop)]
    fn propagate_increase(&mut self, layer: Layer, from: usize, entry: u16, from_level: u32, table: &LightTable) {
        let (fx, fy, fz) = coords(from);
        let from_ring = outside(fx) || outside(fz);
        let from_class = if dir::from_empty_shape(entry) {
            0
        } else {
            shape_class(self.props[from])
        };
        for d in 0..6 {
            if !dir::propagates(entry, d) {
                continue;
            }
            let (tx, ty, tz) = match d {
                DOWN => {
                    if fy == 0 {
                        continue;
                    }
                    (fx, fy - 1, fz)
                }
                UP => {
                    if fy + 1 >= self.height {
                        continue;
                    }
                    (fx, fy + 1, fz)
                }
                NORTH => (fx, fy, fz - 1),
                SOUTH => (fx, fy, fz + 1),
                WEST => (fx - 1, fy, fz),
                _ => (fx + 1, fy, fz),
            };
            let to_ring = outside(tx) || outside(tz);
            if to_ring && (from_ring || (outside(tx) && outside(tz)) || !self.spec.emit_outgoing) {
                continue;
            }
            let slot = if to_ring { side_of(tx, tz) + 1 } else { 0 };
            if !self.storing(slot, layer, ty >> 4) {
                continue;
            }
            let to = (from as isize + OFFSETS[d]) as usize;
            let to_level = match layer {
                Layer::Sky => self.sky[to],
                Layer::Block => self.block[to],
            } as u32;
            if from_level - 1 <= to_level {
                continue;
            }
            let to_props = self.props[to];
            let new_level = from_level as i32 - opacity(to_props) as i32;
            if new_level <= to_level as i32 {
                continue;
            }
            if table.occludes(from_class, shape_class(to_props), d) {
                continue;
            }
            let new_level = new_level as u32;
            self.levels_mut(layer)[to] = new_level as u8;
            if to_ring {
                let side = slot - 1;
                let (kind, count) = match layer {
                    Layer::Sky => (outgoing::SKY_INCREASE, self.empty_sections_below(0, fy)),
                    Layer::Block => (outgoing::BLOCK_INCREASE, 0),
                };
                self.outgoing.push(Outgoing {
                    kind,
                    side: side as u8,
                    along: along_of(side, tx, tz),
                    level: new_level as u8,
                    y: self.abs_y(ty),
                    entry: dir::increase_skip_one(new_level, is_empty_shape(to_props), opposite(d)),
                    count,
                });
                continue;
            }
            self.mark(layer, ty);
            if new_level > 1 {
                self.push_increase(
                    to,
                    dir::increase_skip_one(new_level, is_empty_shape(to_props), opposite(d)),
                );
            }
            // Light pulled in from a ring cell skips vanilla's
            // propagateFromEmptySections: that step overwrites levels
            // unconditionally, so its outcome depends on queue order. The
            // outgoing decrease record makes the Java side redo the pull with it.
        }
    }

    #[allow(clippy::needless_range_loop)]
    fn propagate_decrease(&mut self, layer: Layer, from: usize, entry: u16) {
        let (fx, fy, fz) = coords(from);
        let old = dir::level(entry);
        for d in 0..6 {
            if !dir::propagates(entry, d) {
                continue;
            }
            let (tx, ty, tz) = match d {
                DOWN => {
                    if fy == 0 {
                        continue;
                    }
                    (fx, fy - 1, fz)
                }
                UP => {
                    if fy + 1 >= self.height {
                        continue;
                    }
                    (fx, fy + 1, fz)
                }
                NORTH => (fx, fy, fz - 1),
                SOUTH => (fx, fy, fz + 1),
                WEST => (fx - 1, fy, fz),
                _ => (fx + 1, fy, fz),
            };
            let to_ring = outside(tx) || outside(tz);
            if to_ring && ((outside(tx) && outside(tz)) || !self.spec.emit_outgoing) {
                continue;
            }
            let slot = if to_ring { side_of(tx, tz) + 1 } else { 0 };
            if !self.storing(slot, layer, ty >> 4) {
                continue;
            }
            let to = (from as isize + OFFSETS[d]) as usize;
            let to_level = match layer {
                Layer::Sky => self.sky[to],
                Layer::Block => self.block[to],
            } as u32;
            if to_ring {
                // The Java side runs vanilla's branch on the live level; mirror
                // it here on the ring level the job carried.
                let side = slot - 1;
                let (kind, count) = match layer {
                    Layer::Sky => (outgoing::SKY_DECREASE, self.empty_sections_below(0, fy)),
                    Layer::Block => (outgoing::BLOCK_DECREASE, 0),
                };
                self.outgoing.push(Outgoing {
                    kind,
                    side: side as u8,
                    along: along_of(side, tx, tz),
                    level: old as u8,
                    y: self.abs_y(ty),
                    entry: dir::decrease_skip_one(old, opposite(d)),
                    count,
                });
                if to_level != 0 {
                    if to_level < old {
                        self.levels_mut(layer)[to] = 0;
                    } else {
                        self.push_increase(to, dir::increase_only_one(to_level, false, opposite(d)));
                    }
                }
                continue;
            }
            if to_level == 0 {
                continue;
            }
            if to_level < old {
                let to_props = self.props[to];
                self.levels_mut(layer)[to] = 0;
                self.mark(layer, ty);
                match layer {
                    Layer::Block => {
                        let light = self.emission_on(to_props);
                        if light < to_level {
                            self.push_decrease(to, dir::decrease_skip_one(to_level, opposite(d)));
                        }
                        if light > 0 {
                            self.push_increase(to, dir::increase_from_emission(light, is_empty_shape(to_props)));
                        }
                    }
                    Layer::Sky => {
                        // propagateFromEmptySections only acts across a chunk
                        // border, which a column cell never crosses into.
                        self.push_decrease(to, dir::decrease_skip_one(to_level, opposite(d)));
                    }
                }
            } else {
                self.push_increase(to, dir::increase_only_one(to_level, false, opposite(d)));
            }
        }
    }

    /// Ring cell levels (tests).
    #[doc(hidden)]
    pub fn ring_level(&self, layer: Layer, side: usize, along: i32, y: i32) -> u8 {
        let (x, z) = ring_xz(side, along);
        let c = cell(x, self.y_cell(y), z);
        match layer {
            Layer::Sky => self.sky[c],
            Layer::Block => self.block[c],
        }
    }

    /// Props of a column cell (tests and tools).
    pub fn props_at(&self, x: i32, y: i32, z: i32) -> Props {
        self.props[cell(x, self.y_cell(y), z)]
    }

    /// Overwrites the props of one column cell (incremental jobs that patch a
    /// snapshot, tests). Call [`Self::compute_sources`] afterwards.
    pub fn set_props(&mut self, x: i32, y: i32, z: i32, props: Props) {
        let y_cell = self.y_cell(y);
        self.props[cell(x, y_cell, z)] = props;
        let section = (y_cell >> 4).wrapping_sub(1);
        if section < self.spec.section_count {
            // Stay conservative: the section may now block or emit light.
            self.section_hints[0][section] = HINT_MAY_EMIT;
        }
    }
}
