//! The SECTION JOB: one chunk section plus the blocks around it, as flat
//! structure-of-arrays snapshots that the client copies out of its
//! `RenderRegionCache` (no Java object crosses the boundary).
//!
//! # Region
//!
//! States and light cover a 20 x 20 x 20 box: the 16 x 16 x 16 section plus a
//! 2-block margin. One block of margin is what culling, fluids and light read;
//! the second is needed because smooth lighting probes `pos + 2 * face + corner`
//! for a face flush with the block boundary (`BlockModelLighter`, "faceCubic").
//! Index of a section-relative position (x, y, z), each in -2..=17:
//! `((y + 2) * 20 + (z + 2)) * 20 + (x + 2)`. Light only has to be valid inside
//! the 18 x 18 x 18 box (margin 1); the outer shell is never read.
//!
//! Biomes come as the 6 x 6 x 6 quart grid the vanilla biome zoom
//! (`BiomeManager.getBiome`) can touch for the section and the layer below it,
//! quart origin `section * 4 - 1` on every axis, index `(qy * 6 + qz) * 6 + qx`,
//! each a palette index. The client fills it with `level.getNoiseBiome(qx, qy, qz)`
//! (the resolver `BiomeManager` uses, with its height clamp and unloaded-chunk
//! fallback) and the palette with the biome's colors. With a biome blend radius
//! r (`flags::BIOME_BLEND`, r <= 2) a block's tint averages the colors of the
//! (2r + 1)^2 columns around it at the same height, as
//! `ClientLevel.calculateBlockTint` does; the quart grid already reaches the
//! blocks two columns outside the section.
//!
//! # Payload (after the ABI job header), little-endian
//!
//! ```text
//! @0   u32 table_epoch      epoch of the model table this job was built against
//! @4   u32 request_seq      echoed in the result
//! @8   u32 section_version  echoed in the result (lets the caller drop stale results)
//! @12  u32 flags            see [flags]
//! @16  i32 section_x  @20 i32 section_y  @24 i32 section_z   (section coordinates)
//! @28  u32 biome_palette_len (1..=256)
//! @32  f32 cardinal[6]      CardinalLighting down, up, north, south, west, east of the level
//! @56  f32 camera[3]        camera position minus the section origin (translucency sort)
//! @68  u32 inline_table_len 0, or a model table follows the job (flags::INLINE_TABLE)
//! @72  i64 biome_zoom_seed  BiomeManager.biomeZoomSeed of the client level
//! @80  u32 blend_radius     Options.biomeBlendRadius (0..=2), read with flags::BIOME_BLEND
//! @84  u32 reserved[3]      0
//! @96  states               8000 x u16 global state ids (8000 x u32 with flags::WIDE_IDS)
//!      light                8000 x u8: (sky << 4) | block
//!      biome_quarts         216 x u8 palette indices
//!      pad8, biome_palette  len x 5 x i32: grass_base, grass_modifier (0 none, 1 dark forest,
//!                           2 swamp), foliage, dry_foliage, water
//!      swamp_mask           32 bytes: bit (z * 16 + x) set when the swamp grass modifier picks
//!                           its "below -0.1" color for that column (all zero without swamps);
//!                           with flags::BIOME_BLEND 50 bytes covering the columns x, z in
//!                           -2..=17, bit ((z + 2) * 20 + (x + 2))
//!      pad8, model table    inline_table_len bytes (only with flags::INLINE_TABLE)
//! ```

use crate::table::ModelTable;
use gaius_kernel_abi::{KernelError, Reader, Status};

pub const REGION: usize = 20;
pub const MARGIN: i32 = 2;
pub const VOLUME: usize = REGION * REGION * REGION;
pub const QUARTS: usize = 6;
pub const QUART_VOLUME: usize = QUARTS * QUARTS * QUARTS;
pub const HEADER_LEN: usize = 96;
pub const PALETTE_ENTRY_LEN: usize = 20;
pub const MAX_PALETTE: u32 = 256;

pub mod flags {
    /// Options.ambientOcclusion (the SectionCompiler's ambientOcclusion).
    pub const AMBIENT_OCCLUSION: u32 = 1 << 0;
    /// Options.cutoutLeaves (LeavesBlock.cutoutLeaves and ModelBlockRenderer.forceOpaque).
    pub const CUTOUT_LEAVES: u32 = 1 << 1;
    /// Emit vanilla BLOCK vertices (28 bytes).
    pub const EMIT_VANILLA: u32 = 1 << 2;
    /// Emit the compact format (12 bytes per vertex plus a tint per quad).
    pub const EMIT_COMPACT: u32 = 1 << 3;
    /// Sort translucent quads back to front for `camera`, as MeshData.sortQuads does.
    pub const SORT_TRANSLUCENT: u32 = 1 << 4;
    /// State ids are u32 instead of u16.
    pub const WIDE_IDS: u32 = 1 << 5;
    /// Reserved for greedy merging of the compact format; rejected while it would change
    /// the vanilla output.
    pub const GREEDY: u32 = 1 << 6;
    /// A model table follows the job; the kernel loads it when its epoch is not resident.
    pub const INLINE_TABLE: u32 = 1 << 7;
    /// Return translucent quad centroids (for later resorts on the client).
    pub const EMIT_CENTROIDS: u32 = 1 << 8;
    /// `blend_radius` is set and the swamp mask covers the 20 x 20 columns of the region.
    pub const BIOME_BLEND: u32 = 1 << 9;
    pub const KNOWN: u32 = (1 << 10) - 1;
}

/// Swamp mask bytes of the legacy layout (16 x 16 columns).
pub const SWAMP_LEN: usize = 32;
/// Swamp mask bytes with `flags::BIOME_BLEND` (20 x 20 columns, x and z in -2..=17).
pub const SWAMP_BLEND_LEN: usize = 50;
/// Largest biome blend radius the quart grid and the swamp mask cover.
pub const MAX_BLEND_RADIUS: u32 = 2;

/// One biome palette entry.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BiomeColors {
    pub grass: i32,
    pub grass_modifier: i32,
    pub foliage: i32,
    pub dry_foliage: i32,
    pub water: i32,
}

pub const GRASS_MODIFIER_NONE: i32 = 0;
pub const GRASS_MODIFIER_DARK_FOREST: i32 = 1;
pub const GRASS_MODIFIER_SWAMP: i32 = 2;

/// Borrowed view of a decoded job.
#[derive(Clone, Debug)]
pub struct SectionJob<'a> {
    pub table_epoch: u32,
    pub request_seq: u32,
    pub section_version: u32,
    pub flags: u32,
    pub section: [i32; 3],
    pub cardinal: [f32; 6],
    pub camera: [f32; 3],
    pub biome_zoom_seed: i64,
    /// Raw little-endian ids, 2 or 4 bytes each.
    pub states: &'a [u8],
    pub light: &'a [u8],
    pub biome_quarts: &'a [u8],
    pub palette: Vec<BiomeColors>,
    /// Options.biomeBlendRadius; 0 without flags::BIOME_BLEND.
    pub blend_radius: i32,
    /// SWAMP_LEN bytes, or SWAMP_BLEND_LEN with flags::BIOME_BLEND (the rest is zero).
    pub swamp_mask: [u8; SWAMP_BLEND_LEN],
    pub inline_table: Option<&'a [u8]>,
}

fn bad(message: &str) -> KernelError {
    KernelError::new(Status::BadPayload, message)
}

fn t(s: Status) -> KernelError {
    KernelError::from(s)
}

impl<'a> SectionJob<'a> {
    pub fn decode(payload: &'a [u8]) -> Result<SectionJob<'a>, KernelError> {
        let mut r = Reader::new(payload);
        let table_epoch = r.u32().map_err(t)?;
        let request_seq = r.u32().map_err(t)?;
        let section_version = r.u32().map_err(t)?;
        let flags = r.u32().map_err(t)?;
        if flags & !flags::KNOWN != 0 {
            return Err(bad("unknown mesh job flags"));
        }
        if flags & flags::GREEDY != 0 {
            return Err(bad("greedy merging would change the vanilla mesh and is not available"));
        }
        let section = [r.i32().map_err(t)?, r.i32().map_err(t)?, r.i32().map_err(t)?];
        let palette_len = r.u32().map_err(t)?;
        if palette_len == 0 || palette_len > MAX_PALETTE {
            return Err(bad("biome palette length out of range"));
        }
        let mut cardinal = [0f32; 6];
        for c in cardinal.iter_mut() {
            *c = f32::from_bits(r.u32().map_err(t)?);
        }
        let mut camera = [0f32; 3];
        for c in camera.iter_mut() {
            *c = f32::from_bits(r.u32().map_err(t)?);
        }
        let inline_len = r.u32().map_err(t)?;
        let biome_zoom_seed = r.i64().map_err(t)?;
        let blend_field = r.u32().map_err(t)?;
        r.take(12).map_err(t)?;
        let blend = flags & flags::BIOME_BLEND != 0;
        if blend && blend_field > MAX_BLEND_RADIUS {
            return Err(bad("biome blend radius out of range"));
        }
        let blend_radius = if blend { blend_field as i32 } else { 0 };
        let id_width = if flags & flags::WIDE_IDS != 0 { 4 } else { 2 };
        let states = r.take(VOLUME * id_width).map_err(t)?;
        let light = r.take(VOLUME).map_err(t)?;
        let biome_quarts = r.take(QUART_VOLUME).map_err(t)?;
        if biome_quarts.iter().any(|&b| b as u32 >= palette_len) {
            return Err(bad("biome quart outside the palette"));
        }
        r.align(8).map_err(t)?;
        let mut palette = Vec::with_capacity(palette_len as usize);
        for _ in 0..palette_len {
            palette.push(BiomeColors {
                grass: r.i32().map_err(t)?,
                grass_modifier: r.i32().map_err(t)?,
                foliage: r.i32().map_err(t)?,
                dry_foliage: r.i32().map_err(t)?,
                water: r.i32().map_err(t)?,
            });
        }
        let swamp_len = if blend { SWAMP_BLEND_LEN } else { SWAMP_LEN };
        let mut swamp_mask = [0u8; SWAMP_BLEND_LEN];
        swamp_mask[..swamp_len].copy_from_slice(r.take(swamp_len).map_err(t)?);
        let inline_table = if flags & flags::INLINE_TABLE != 0 {
            if inline_len == 0 {
                return Err(bad("inline table flag without a table"));
            }
            r.align(8).map_err(t)?;
            Some(r.take(inline_len as usize).map_err(t)?)
        } else {
            None
        };
        Ok(SectionJob {
            table_epoch,
            request_seq,
            section_version,
            flags,
            section,
            cardinal,
            camera,
            biome_zoom_seed,
            states,
            light,
            biome_quarts,
            palette,
            blend_radius,
            swamp_mask,
            inline_table,
        })
    }

    #[inline]
    pub fn has(&self, flag: u32) -> bool {
        self.flags & flag != 0
    }

    /// Widens the ids into `out` (VOLUME entries) and checks them against the table.
    pub fn read_ids(&self, table: &ModelTable, out: &mut Vec<u32>) -> Result<(), KernelError> {
        out.clear();
        out.reserve(VOLUME);
        let count = table.states.len() as u32;
        let mut max = 0u32;
        if self.has(flags::WIDE_IDS) {
            for c in self.states.as_chunks::<4>().0 {
                let id = u32::from_le_bytes(*c);
                max = max.max(id);
                out.push(id);
            }
        } else {
            for c in self.states.as_chunks::<2>().0 {
                let id = u16::from_le_bytes(*c) as u32;
                max = max.max(id);
                out.push(id);
            }
        }
        if max >= count {
            return Err(bad("block state id outside the model table"));
        }
        Ok(())
    }
}

/// Index of a section-relative position in the 20^3 region.
#[inline]
pub const fn region_index(x: i32, y: i32, z: i32) -> usize {
    (((y + MARGIN) * REGION as i32 + (z + MARGIN)) * REGION as i32 + (x + MARGIN)) as usize
}

/// Owned job inputs; `encode` writes the payload described above (tests and tools).
#[derive(Clone, Debug, Default)]
pub struct SectionJobData {
    pub table_epoch: u32,
    pub request_seq: u32,
    pub section_version: u32,
    pub flags: u32,
    pub section: [i32; 3],
    pub cardinal: [f32; 6],
    pub camera: [f32; 3],
    pub biome_zoom_seed: i64,
    pub states: Vec<u32>,
    pub light: Vec<u8>,
    pub biome_quarts: Vec<u8>,
    pub palette: Vec<BiomeColors>,
    pub swamp_mask: [u8; 32],
    /// Some(radius) writes flags::BIOME_BLEND with `swamp_mask_blend` (SWAMP_BLEND_LEN bytes,
    /// zero padded) in place of `swamp_mask`.
    pub blend_radius: Option<u32>,
    pub swamp_mask_blend: Vec<u8>,
    pub inline_table: Vec<u8>,
}

impl SectionJobData {
    /// An all-air section in full skylight, plains-like colors, default cardinal lighting.
    pub fn empty(epoch: u32) -> SectionJobData {
        SectionJobData {
            table_epoch: epoch,
            flags: flags::AMBIENT_OCCLUSION | flags::EMIT_VANILLA | flags::SORT_TRANSLUCENT,
            cardinal: [0.5, 1.0, 0.8, 0.8, 0.6, 0.6],
            camera: [8.0, 8.0, 8.0],
            states: vec![0; VOLUME],
            light: vec![0xF0; VOLUME],
            biome_quarts: vec![0; QUART_VOLUME],
            palette: vec![BiomeColors {
                grass: -7_226_023,
                grass_modifier: GRASS_MODIFIER_NONE,
                foliage: -12_012_264,
                dry_foliage: -10_732_494,
                water: -12_618_012,
            }],
            ..SectionJobData::default()
        }
    }

    pub fn set(&mut self, x: i32, y: i32, z: i32, id: u32) {
        let i = region_index(x, y, z);
        self.states[i] = id;
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut flags = self.flags;
        if !self.inline_table.is_empty() {
            flags |= flags::INLINE_TABLE;
        }
        let wide = self.states.iter().any(|&s| s > u16::MAX as u32);
        if wide {
            flags |= flags::WIDE_IDS;
        }
        if self.blend_radius.is_some() {
            flags |= flags::BIOME_BLEND;
        }
        let mut o = Vec::with_capacity(HEADER_LEN + VOLUME * 3 + 512 + self.inline_table.len());
        for v in [self.table_epoch, self.request_seq, self.section_version, flags] {
            o.extend_from_slice(&v.to_le_bytes());
        }
        for v in self.section {
            o.extend_from_slice(&v.to_le_bytes());
        }
        o.extend_from_slice(&(self.palette.len() as u32).to_le_bytes());
        for v in self.cardinal.iter().chain(self.camera.iter()) {
            o.extend_from_slice(&v.to_le_bytes());
        }
        o.extend_from_slice(&(self.inline_table.len() as u32).to_le_bytes());
        o.extend_from_slice(&self.biome_zoom_seed.to_le_bytes());
        o.extend_from_slice(&self.blend_radius.unwrap_or(0).to_le_bytes());
        o.extend_from_slice(&[0u8; 12]);
        debug_assert_eq!(o.len(), HEADER_LEN);
        for &s in &self.states {
            if wide {
                o.extend_from_slice(&s.to_le_bytes());
            } else {
                o.extend_from_slice(&(s as u16).to_le_bytes());
            }
        }
        o.extend_from_slice(&self.light);
        o.extend_from_slice(&self.biome_quarts);
        while !o.len().is_multiple_of(8) {
            o.push(0);
        }
        for p in &self.palette {
            for v in [p.grass, p.grass_modifier, p.foliage, p.dry_foliage, p.water] {
                o.extend_from_slice(&v.to_le_bytes());
            }
        }
        if self.blend_radius.is_some() {
            let mut mask = [0u8; SWAMP_BLEND_LEN];
            let n = self.swamp_mask_blend.len().min(SWAMP_BLEND_LEN);
            mask[..n].copy_from_slice(&self.swamp_mask_blend[..n]);
            o.extend_from_slice(&mask);
        } else {
            o.extend_from_slice(&self.swamp_mask);
        }
        if !self.inline_table.is_empty() {
            while !o.len().is_multiple_of(8) {
                o.push(0);
            }
            o.extend_from_slice(&self.inline_table);
        }
        o
    }
}
