//! Worldgen job payloads and the per-instance generator cache.
//!
//! All integers are little-endian; "pad8" skips to the next multiple of 8 bytes
//! from the payload start. Every job starts with a generator reference:
//!
//! ```text
//! u32 key          the page's generator slot (one per dimension)
//! u32 flags        bit 0: the IR follows inline
//! u64 hash         FNV-1a 64 of the IR bytes (identifies the content)
//! u32 ir_len       0 when the IR is not inline
//! u32 reserved
//! u8  ir[ir_len], pad8
//! ```
//!
//! A worker keeps up to [`MAX_GENERATORS`] generators (least recently used
//! first out). A job whose `key` + `hash` is not loaded and carries no IR fails
//! with a `BadPayload` message starting with `generator-missing:`; the page then
//! resends it with the IR inline (`port/web/kernels/worldgen-job.js` does this).
//!
//! `load_generator` (kind `0x0401`): generator reference (IR inline) then
//! `u32 arena_budget_kb` (0: default). Result: `u32 nodes, noises, states, biomes,
//! pooled_bytes, section_count; i32 level_min_y; u32 simd (1 when built with simd128)`.
//!
//! `biomes` (kind `0x0402`): reference, `i32 chunk_x, chunk_z`. Result:
//! `u32 section_count, i32 level_min_y`, then `u32 ids[section_count * 64]`
//! (global biome ids, per section `(qy * 4 + qz) * 4 + qx`).
//!
//! `terrain` (kind `0x0403`): reference, `i32 chunk_x, chunk_z, u32 flags` (bit 0 run the
//! surface rules, bit 1 return biomes, bit 2 ring biomes follow), then the beardifier:
//!
//! ```text
//! u32 beard_flags (bit 0: affected box present), i32 affected[6] (min x y z, max x y z)
//! u32 rigid_count; rigid: i32 min[3], i32 max[3], i32 adjustment (0 none, 1 bury,
//!                  2 beard_thin, 3 beard_box, 4 encapsulate), i32 ground_level_delta
//! u32 junction_count; junction: i32 source_x, source_ground_y, source_z
//! ```
//!
//! and with flags bit 2 the stored biomes in and around the chunk (what
//! vanilla's surface rules read through the `WorldGenRegion`):
//!
//! ```text
//! u32 ring_len (36 * level_height / 4), u32 ring[ring_len]   global biome ids; for each quart
//!     column of the complete 6 x 6 grid (x outer, z inner, from
//!     chunk_x * 4 - 1 / chunk_z * 4 - 1), every quart y from the level bottom
//! ```
//! The old 20-column border remains readable. Missing center biomes that require ambiguous
//! climate searches decline; a complete stored grid never reconstructs those biomes.
//!
//! A ring that names a biome outside the generator's biome table fails the job with a
//! `BadPayload` message starting with `worldgen-fallback:`: only that chunk takes the Java path.
//! Multi-noise BIOMES jobs also decline because vanilla retains search history across chunks.
//!
//! `surface` (kind `0x0404`): reference, `i32 chunk_x, chunk_z, u32 flags` (bit 2 ring biomes
//! follow), the beardifier, the ring biomes, then a chunk in the result layout below (from
//! `pad8`) without the biome block.
//!
//! Chunk result (terrain and surface):
//!
//! ```text
//! i32 chunk_x, chunk_z, level_min_y; u32 section_count, flags (bit 0 biomes, bit 1 surface), reserved
//! per section: u16 palette_len, u8 index_bits (0 single state, 8, 16), u8 has_non_air,
//!              u32 palette[palette_len] (global state ids), indices (4096 x u8 or u16 in
//!              (y << 8 | z << 4 | x) order), pad to 4
//! i32 world_surface_wg[256], ocean_floor_wg[256]   first free y per column (x + z * 16)
//! u32 post_count, u32 post[post_count]             section_index << 16 | (x | y << 4 | z << 8)
//! u32 biomes[section_count * 64]                   when flags bit 0
//! ```

use gaius_kernel_abi::{KernelError, Status};
use gaius_worldgen::beard::{Adjustment, Beard, Junction, Rigid};
use gaius_worldgen::chunk::ChunkData;
use gaius_worldgen::{Generator, RingError, TerrainRequest};
use std::cell::RefCell;

pub use gaius_kernel_abi::kind::{
    BIOMES as KIND_BIOMES, LOAD_GENERATOR as KIND_LOAD_GENERATOR, SURFACE as KIND_SURFACE, TERRAIN as KIND_TERRAIN,
};

pub const MAX_GENERATORS: usize = 4;
const MAX_STRUCTURE_PIECES: u32 = 1 << 16;

pub const FLAG_SURFACE: u32 = 1;
pub const FLAG_BIOMES: u32 = 2;
pub const FLAG_RING_BIOMES: u32 = 4;
/// Prefix of a job error that only sends that chunk to the Java path.
pub const FALLBACK_PREFIX: &str = "worldgen-fallback:";

fn bad(message: impl Into<String>) -> KernelError {
    KernelError::new(Status::BadPayload, message)
}

fn truncated() -> KernelError {
    KernelError::new(Status::Truncated, "worldgen job is truncated")
}

/// FNV-1a 64 over the IR bytes (the `hash` of a generator reference).
pub fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// Little-endian cursor with 8-byte alignment from the payload start.
pub struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Cursor { buf, pos: 0 }
    }

    pub fn take(&mut self, n: usize) -> Result<&'a [u8], KernelError> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|&e| e <= self.buf.len())
            .ok_or_else(truncated)?;
        let s = &self.buf[self.pos..end];
        self.pos = end;
        Ok(s)
    }

    pub fn align(&mut self, a: usize) -> Result<(), KernelError> {
        let pad = (a - self.pos % a) % a;
        self.take(pad).map(|_| ())
    }

    pub fn u8(&mut self) -> Result<u8, KernelError> {
        Ok(self.take(1)?[0])
    }

    pub fn u16(&mut self) -> Result<u16, KernelError> {
        let b = self.take(2)?;
        Ok(u16::from_le_bytes([b[0], b[1]]))
    }

    pub fn u32(&mut self) -> Result<u32, KernelError> {
        let b = self.take(4)?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    pub fn i32(&mut self) -> Result<i32, KernelError> {
        Ok(self.u32()? as i32)
    }

    pub fn u64(&mut self) -> Result<u64, KernelError> {
        let lo = self.u32()? as u64;
        let hi = self.u32()? as u64;
        Ok(lo | (hi << 32))
    }

    pub fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }
}

/// A decoded generator reference.
pub struct GeneratorRef<'a> {
    pub key: u32,
    pub hash: u64,
    pub ir: Option<&'a [u8]>,
}

pub fn read_ref<'a>(c: &mut Cursor<'a>) -> Result<GeneratorRef<'a>, KernelError> {
    let key = c.u32()?;
    let flags = c.u32()?;
    let hash = c.u64()?;
    let ir_len = c.u32()? as usize;
    c.u32()?;
    let ir = if flags & 1 != 0 {
        let bytes = c.take(ir_len)?;
        if fnv1a64(bytes) != hash {
            return Err(bad("generator IR does not match its hash"));
        }
        Some(bytes)
    } else {
        None
    };
    c.align(8)?;
    Ok(GeneratorRef { key, hash, ir })
}

/// Writes a generator reference (used by tests and native tools).
pub fn write_ref(out: &mut Vec<u8>, key: u32, ir: &[u8], inline: bool) {
    out.extend_from_slice(&key.to_le_bytes());
    out.extend_from_slice(&(inline as u32).to_le_bytes());
    out.extend_from_slice(&fnv1a64(ir).to_le_bytes());
    out.extend_from_slice(&(if inline { ir.len() as u32 } else { 0 }).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    if inline {
        out.extend_from_slice(ir);
    }
    while !out.len().is_multiple_of(8) {
        out.push(0);
    }
}

struct Entry {
    key: u32,
    hash: u64,
    generator: Generator,
}

#[derive(Default)]
struct Cache {
    entries: Vec<Entry>,
}

thread_local! {
    static CACHE: RefCell<Cache> = RefCell::new(Cache::default());
}

/// Runs `f` with the referenced generator, loading it from the inline IR when needed.
fn with_generator<T>(
    r: &GeneratorRef,
    f: impl FnOnce(&mut Generator) -> Result<T, KernelError>,
) -> Result<T, KernelError> {
    CACHE.with(|cache| {
        let mut cache = cache.borrow_mut();
        let found = cache.entries.iter().position(|e| e.key == r.key && e.hash == r.hash);
        let index = match found {
            Some(i) => i,
            None => {
                let Some(ir) = r.ir else {
                    return Err(bad(format!("generator-missing:{}:{:016x}", r.key, r.hash)));
                };
                let generator = Generator::load(ir).map_err(|e| bad(format!("bad generator IR: {e}")))?;
                cache.entries.retain(|e| e.key != r.key);
                if cache.entries.len() >= MAX_GENERATORS {
                    cache.entries.remove(0);
                }
                cache.entries.push(Entry {
                    key: r.key,
                    hash: r.hash,
                    generator,
                });
                cache.entries.len() - 1
            }
        };
        // Most recently used last.
        let entry = cache.entries.remove(index);
        cache.entries.push(entry);
        let last = cache.entries.len() - 1;
        f(&mut cache.entries[last].generator)
    })
}

/// Drops every cached generator (used by tests).
pub fn clear_cache() {
    CACHE.with(|c| c.borrow_mut().entries.clear());
}

fn read_beard(c: &mut Cursor) -> Result<Beard, KernelError> {
    let flags = c.u32()?;
    let mut affected = [0i32; 6];
    for v in affected.iter_mut() {
        *v = c.i32()?;
    }
    let rigid_count = c.u32()?;
    if rigid_count > MAX_STRUCTURE_PIECES || rigid_count as usize * 32 > c.remaining() {
        return Err(bad("too many structure pieces"));
    }
    let mut rigids = Vec::with_capacity(rigid_count as usize);
    for _ in 0..rigid_count {
        let min = [c.i32()?, c.i32()?, c.i32()?];
        let max = [c.i32()?, c.i32()?, c.i32()?];
        let adjustment = Adjustment::from_code(c.i32()?).ok_or_else(|| bad("unknown terrain adjustment"))?;
        let ground_level_delta = c.i32()?;
        rigids.push(Rigid {
            min,
            max,
            adjustment,
            ground_level_delta,
        });
    }
    let junction_count = c.u32()?;
    if junction_count > MAX_STRUCTURE_PIECES || junction_count as usize * 12 > c.remaining() {
        return Err(bad("too many jigsaw junctions"));
    }
    let mut junctions = Vec::with_capacity(junction_count as usize);
    for _ in 0..junction_count {
        junctions.push(Junction {
            x: c.i32()?,
            ground_y: c.i32()?,
            z: c.i32()?,
        });
    }
    Ok(Beard {
        rigids,
        junctions,
        affected: (flags & 1 != 0).then(|| {
            (
                [affected[0], affected[1], affected[2]],
                [affected[3], affected[4], affected[5]],
            )
        }),
    })
}

/// Reads the ring biomes of a job whose flags carry [`FLAG_RING_BIOMES`] (global ids).
fn read_ring(c: &mut Cursor, flags: u32) -> Result<Option<Vec<u32>>, KernelError> {
    if flags & FLAG_RING_BIOMES == 0 {
        return Ok(None);
    }
    let len = c.u32()? as usize;
    if len.checked_mul(4).is_none_or(|bytes| bytes > c.remaining()) {
        return Err(truncated());
    }
    let mut ring = Vec::with_capacity(len);
    for _ in 0..len {
        ring.push(c.u32()?);
    }
    Ok(Some(ring))
}

/// Local biome indices of a decoded ring, checked against the generator.
fn ring_locals(generator: &Generator, ring: Option<&[u32]>) -> Result<Option<Vec<u16>>, KernelError> {
    let Some(ring) = ring else {
        return Ok(None);
    };
    match generator.ring_biomes(ring) {
        Ok(locals) => Ok(Some(locals)),
        Err(RingError::Length(len)) => Err(bad(format!(
            "ring biomes: {len} entries, the generator needs {}",
            generator.ring_len()
        ))),
        Err(RingError::UnknownBiome(id)) => Err(bad(format!(
            "{FALLBACK_PREFIX}neighbour biome {id} is not in the generator's biome table"
        ))),
    }
}

/// Writes the ring biomes block of a job (tests and tools).
pub fn write_ring(out: &mut Vec<u8>, ring: &[u32]) {
    out.extend_from_slice(&(ring.len() as u32).to_le_bytes());
    for id in ring {
        out.extend_from_slice(&id.to_le_bytes());
    }
}

/// Writes the beardifier block (empty when `beard` is `Beard::default()`).
pub fn write_beard(out: &mut Vec<u8>, beard: &Beard) {
    let (flags, affected) = match beard.affected {
        Some((min, max)) => (1u32, [min[0], min[1], min[2], max[0], max[1], max[2]]),
        None => (0, [0; 6]),
    };
    out.extend_from_slice(&flags.to_le_bytes());
    affected.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
    out.extend_from_slice(&(beard.rigids.len() as u32).to_le_bytes());
    for r in &beard.rigids {
        for v in r.min.iter().chain(r.max.iter()) {
            out.extend_from_slice(&v.to_le_bytes());
        }
        let code = match r.adjustment {
            Adjustment::None => 0i32,
            Adjustment::Bury => 1,
            Adjustment::BeardThin => 2,
            Adjustment::BeardBox => 3,
            Adjustment::Encapsulate => 4,
        };
        out.extend_from_slice(&code.to_le_bytes());
        out.extend_from_slice(&r.ground_level_delta.to_le_bytes());
    }
    out.extend_from_slice(&(beard.junctions.len() as u32).to_le_bytes());
    for j in &beard.junctions {
        for v in [j.x, j.ground_y, j.z] {
            out.extend_from_slice(&v.to_le_bytes());
        }
    }
}

fn chunk_header(out: &mut Vec<u8>, chunk: &ChunkData, flags: u32) {
    for v in [chunk.chunk_x, chunk.chunk_z, chunk.min_y] {
        out.extend_from_slice(&v.to_le_bytes());
    }
    for v in [chunk.sections() as u32, flags, 0] {
        out.extend_from_slice(&v.to_le_bytes());
    }
}

/// Reads a chunk in the result layout (sections, heightmaps, post-processing).
fn read_chunk(c: &mut Cursor, generator: &Generator) -> Result<ChunkData, KernelError> {
    let chunk_x = c.i32()?;
    let chunk_z = c.i32()?;
    let min_y = c.i32()?;
    let sections = c.u32()? as usize;
    c.u32()?;
    c.u32()?;
    let s = &generator.settings;
    if min_y != s.level_min_y || sections != generator.section_count() {
        return Err(bad("chunk height does not match the generator"));
    }
    let mut chunk = ChunkData::new(chunk_x, chunk_z, s.level_min_y, s.level_height);
    let mut palette: Vec<u16> = Vec::with_capacity(64);
    for section in 0..sections {
        let palette_len = c.u16()? as usize;
        let bits = c.u8()?;
        c.u8()?;
        if palette_len == 0 || palette_len > 4096 {
            return Err(bad("bad section palette"));
        }
        palette.clear();
        for _ in 0..palette_len {
            let global = c.u32()?;
            palette.push(
                generator
                    .local_state(global)
                    .ok_or_else(|| bad(format!("state {global} is not in the generator's state table")))?,
            );
        }
        let blocks = &mut chunk.blocks[section << 12..(section + 1) << 12];
        match bits {
            0 => blocks.fill(palette[0]),
            8 => {
                let idx = c.take(4096)?;
                for (b, &i) in blocks.iter_mut().zip(idx) {
                    *b = *palette
                        .get(i as usize)
                        .ok_or_else(|| bad("palette index out of range"))?;
                }
            }
            16 => {
                let idx = c.take(8192)?;
                for (b, i) in blocks.iter_mut().zip(idx.as_chunks::<2>().0) {
                    let i = u16::from_le_bytes(*i) as usize;
                    *b = *palette.get(i).ok_or_else(|| bad("palette index out of range"))?;
                }
            }
            _ => return Err(bad("bad section index bits")),
        }
        c.align(4)?;
    }
    for i in 0..256 {
        chunk.world_surface[i] = c.i32()?;
    }
    for i in 0..256 {
        chunk.ocean_floor[i] = c.i32()?;
    }
    let posts = c.u32()? as usize;
    if posts * 4 > c.remaining() {
        return Err(truncated());
    }
    for _ in 0..posts {
        let v = c.u32()?;
        chunk.post_processing.push(((v >> 16) as u16, (v & 0xFFFF) as u16));
    }
    chunk.recount(&generator.states);
    Ok(chunk)
}

fn encode_chunk(generator: &Generator, chunk: &ChunkData, flags: u32, biomes: Option<&[u32]>) -> Vec<u8> {
    let mut out = Vec::with_capacity(chunk.sections() * 4200 + 4096);
    chunk_header(&mut out, chunk, flags);
    chunk.encode(&generator.states, &mut out);
    if let Some(b) = biomes {
        for id in b {
            out.extend_from_slice(&id.to_le_bytes());
        }
    }
    out
}

/// `run_load_generator`.
pub fn load_generator(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut c = Cursor::new(payload);
    let r = read_ref(&mut c)?;
    if r.ir.is_none() {
        return Err(bad("load_generator needs the IR inline"));
    }
    let budget_kb = if c.remaining() >= 4 { c.u32()? } else { 0 };
    // Force a reload so a changed budget applies.
    CACHE.with(|cache| cache.borrow_mut().entries.retain(|e| e.key != r.key));
    with_generator(&r, |g| {
        if budget_kb > 0 {
            g.arena_budget = (budget_kb as usize * 1024) / 4;
        }
        let f = g.footprint();
        let mut out = Vec::with_capacity(32);
        for v in [
            f.nodes as u32,
            f.noises as u32,
            f.states as u32,
            f.biomes as u32,
            f.pooled_bytes as u32,
            g.section_count() as u32,
        ] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.extend_from_slice(&g.settings.level_min_y.to_le_bytes());
        out.extend_from_slice(&(gaius_worldgen::simd::SIMD128 as u32).to_le_bytes());
        Ok(out)
    })
}

/// `run_biomes`.
pub fn biomes(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut c = Cursor::new(payload);
    let r = read_ref(&mut c)?;
    let chunk_x = c.i32()?;
    let chunk_z = c.i32()?;
    with_generator(&r, |g| {
        if g.biome_context_requires_vanilla() {
            return Err(bad(format!(
                "{FALLBACK_PREFIX}multi-noise biomes require vanilla thread-local search history"
            )));
        }
        let ids = g.run_biomes(chunk_x, chunk_z);
        if g.biome_fallback_required() {
            return Err(bad(format!(
                "{FALLBACK_PREFIX}climate tie needs the vanilla thread's previous biome"
            )));
        }
        let mut out = Vec::with_capacity(8 + ids.len() * 4);
        out.extend_from_slice(&(g.section_count() as u32).to_le_bytes());
        out.extend_from_slice(&g.settings.level_min_y.to_le_bytes());
        for id in ids {
            out.extend_from_slice(&id.to_le_bytes());
        }
        Ok(out)
    })
}

/// `run_terrain`.
pub fn terrain(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut c = Cursor::new(payload);
    let r = read_ref(&mut c)?;
    let chunk_x = c.i32()?;
    let chunk_z = c.i32()?;
    let flags = c.u32()?;
    let beard = read_beard(&mut c)?;
    let ring = read_ring(&mut c, flags)?;
    with_generator(&r, |g| {
        let request = TerrainRequest {
            chunk_x,
            chunk_z,
            beard,
            surface: flags & FLAG_SURFACE != 0,
            biomes: flags & FLAG_BIOMES != 0,
            ring_biomes: ring_locals(g, ring.as_deref())?,
        };
        let result = g.run_terrain(&request);
        if g.biome_fallback_required() {
            return Err(bad(format!(
                "{FALLBACK_PREFIX}surface biome needs the vanilla thread's previous biome"
            )));
        }
        let out_flags = (result.biomes.is_some() as u32) | ((request.surface as u32) << 1);
        Ok(encode_chunk(g, &result.chunk, out_flags, result.biomes.as_deref()))
    })
}

/// `run_surface`.
pub fn surface(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut c = Cursor::new(payload);
    let r = read_ref(&mut c)?;
    let chunk_x = c.i32()?;
    let chunk_z = c.i32()?;
    let flags = c.u32()?;
    let beard = read_beard(&mut c)?;
    let ring = read_ring(&mut c, flags)?;
    c.align(8)?;
    with_generator(&r, |g| {
        let ring = ring_locals(g, ring.as_deref())?;
        let mut chunk = read_chunk(&mut c, g)?;
        if chunk.chunk_x != chunk_x || chunk.chunk_z != chunk_z {
            return Err(bad("surface chunk position does not match the job"));
        }
        g.run_surface_with(&mut chunk, beard, ring.as_deref());
        if g.biome_fallback_required() {
            return Err(bad(format!(
                "{FALLBACK_PREFIX}surface biome needs the vanilla thread's previous biome"
            )));
        }
        Ok(encode_chunk(g, &chunk, 2, None))
    })
}

/// Encodes a terrain payload (tests and tools).
pub fn encode_terrain(
    key: u32,
    ir: &[u8],
    inline: bool,
    chunk_x: i32,
    chunk_z: i32,
    flags: u32,
    beard: &Beard,
) -> Vec<u8> {
    let mut out = Vec::new();
    write_ref(&mut out, key, ir, inline);
    out.extend_from_slice(&chunk_x.to_le_bytes());
    out.extend_from_slice(&chunk_z.to_le_bytes());
    out.extend_from_slice(&flags.to_le_bytes());
    write_beard(&mut out, beard);
    out
}

/// Encodes a surface payload from a terrain result's chunk bytes (tests and tools).
pub fn encode_surface(
    key: u32,
    ir: &[u8],
    inline: bool,
    chunk_x: i32,
    chunk_z: i32,
    beard: &Beard,
    chunk: &[u8],
) -> Vec<u8> {
    encode_surface_with(key, ir, inline, (chunk_x, chunk_z), beard, None, chunk)
}

/// [`encode_surface`] with optional ring biomes (global ids, see the module docs).
pub fn encode_surface_with(
    key: u32,
    ir: &[u8],
    inline: bool,
    (chunk_x, chunk_z): (i32, i32),
    beard: &Beard,
    ring: Option<&[u32]>,
    chunk: &[u8],
) -> Vec<u8> {
    let mut out = Vec::new();
    write_ref(&mut out, key, ir, inline);
    out.extend_from_slice(&chunk_x.to_le_bytes());
    out.extend_from_slice(&chunk_z.to_le_bytes());
    let flags = if ring.is_some() { FLAG_RING_BIOMES } else { 0 };
    out.extend_from_slice(&flags.to_le_bytes());
    write_beard(&mut out, beard);
    if let Some(ring) = ring {
        write_ring(&mut out, ring);
    }
    while out.len() % 8 != 0 {
        out.push(0);
    }
    out.extend_from_slice(chunk);
    out
}
