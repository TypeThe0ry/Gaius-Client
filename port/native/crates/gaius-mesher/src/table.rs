//! The static MODEL TABLE: everything the section compiler asks of a
//! `BlockState`, its baked model and its fluid, flattened once per resource
//! reload (an *epoch*) by the Java exporter and kept in each mesh worker.
//!
//! The kernel never sees a Java object: every per-state predicate the vanilla
//! compiler evaluates is precomputed here, block models become flat quad
//! arrays, occlusion shapes become 16 x 16 face masks and tint sources become
//! a small closed set of kinds. A state the exporter cannot express is marked
//! [`flags::UNSUPPORTED`]; a section containing one is handed back to the
//! vanilla compiler instead of being meshed differently.
//!
//! # Binary format, version 1
//!
//! Little-endian. The header is 64 bytes; every section after it starts at an
//! offset that is a multiple of 8 from the table start, in the order below.
//!
//! ```text
//! header
//!   @0  u32 magic "GMTB"          @4  u16 version (1)   @6 u16 semantics (kernelSemantics.code)
//!   @8  u32 epoch                 resource-reload epoch; jobs name the epoch they were built for
//!   @12 u32 state_count           global block state ids are 0..state_count
//!   @16 u32 group_count   @20 u32 entry_count   @24 u32 part_count   @28 u32 quad_ref_count
//!   @32 u32 quad_count    @36 u32 geometry_count @40 u32 uv_count    @44 u32 tint_count
//!   @48 u32 mask_count    @52 u32 fluid_model_count @56 u32 air_id_count (<= 8) @60 u32 reserved (0)
//!
//! states        state_count x 56 bytes, indexed by global state id (Block.BLOCK_STATE_REGISTRY)
//!   @0  u32 flags                 see [flags]
//!   @4  u16 block_id              Block registry id ("neighbor.is(this)" checks)
//!   @6  u16 fluid_model           index into fluid_models for getFluidState(), 0xFFFF = none
//!   @8  u16 face[6]               getFaceOcclusionShape(dir) per Direction ordinal
//!                                 (DOWN UP NORTH SOUTH WEST EAST): 0 = Shapes.empty() itself,
//!                                 1 = Shapes.block() itself, n >= 2 = masks[n - 2]
//!   @20 f32 shade_brightness      getShadeBrightness(level, pos)
//!   @24 f32 fluid_height          getFluidState().getOwnHeight()
//!   @28 f32 max_horizontal_offset Block.getMaxHorizontalOffset()
//!   @32 f32 max_vertical_offset   Block.getMaxVerticalOffset()
//!   @36 u32 first_group           model selector groups (see groups)
//!   @40 u32 first_tint            tint sources, BlockColors.getTintSources(state)
//!   @44 u8  group_count           0 = the state collects no model part
//!   @45 u8  tint_count            <= 64
//!   @46 u8  light_emission        getLightEmission()
//!   @47 u8  skip_kind             skipRendering() rule of the block, see [skip]
//!   @48 u8  fluid_group           fluid type up to Fluid.isSame (0 empty, 1 water, 2 lava, 3.. others)
//!   @49 u8  sturdy_faces          bit d: isFaceSturdy(level, pos, d) (SupportType.FULL)
//!   @50 u8  conn_true             bit d: PipeBlock.PROPERTY_BY_DIRECTION[d] present and true
//!   @51 u8  conn_present          bit d: that property is present (IronBarsBlock.skipRendering)
//!   @52 i8  seed_dx, @53 i8 seed_dy, @54 i8 seed_dz
//!                                 getSeed(pos) == Mth.getSeed(pos + (dx, dy, dz)) (beds, doors, plants)
//!   @55 u8  offset_type | 0x80 multipart
//!                                 bits 0-1: 0 none, 1 XZ, 2 XYZ (BlockBehaviour.OffsetType);
//!                                 bit 7: the state's model is a MultiPartModel
//!
//! groups        group_count x 12 bytes: u32 first_entry, u16 entry_count, u8 weighted, u8 0,
//!               u32 total_weight
//!   A state's model is `group_count` groups. Not multipart: exactly one group, picked with the
//!   random seeded by the block seed. Multipart: seed = nextLong() of that random, and every
//!   group (one per selected model, in MultiPartModel order) is picked after setSeed(seed).
//!   A weighted group (WeightedVariants) draws nextInt(total_weight) and walks the entries;
//!   an unweighted group (SingleVariant) has one entry and draws nothing.
//! entries       entry_count x 8 bytes: u32 weight, u32 part
//! parts         part_count x 20 bytes: u32 first_ref, u16 end[7], u8 flags (bit 0: useAmbientOcclusion), u8 0
//!               getQuads(DOWN..EAST) then getQuads(null), stored back to back from first_ref;
//!               end[k] is the running total after list k (end[6] = quads of the part)
//! quad_refs     quad_ref_count x u32: quad ids
//! quads         quad_count x 16 bytes: u32 geometry, u32 uv, i16 tint_index, u8 direction,
//!               u8 shade_face, u8 layer (0 solid, 1 cutout, 2 translucent), u8 light_emission, u16 0
//!               shade_face: the CardinalLighting face of the quad: 26.3 shadeDirectionOverride
//!               or direction; 26.2/1.21.11 direction when shade() else UP
//! geometries    geometry_count x 48 bytes: f32 x, y, z for position(0..4) (block units)
//! uvs           uv_count x 32 bytes: f32 u, v for packedUV(0..4) (atlas units)
//! tints         tint_count x 8 bytes: u8 kind (see [tint]), u8[3] 0, i32 argb (CONSTANT)
//! masks         mask_count x 32 bytes: u16 row[16]; row v, bit u set when the 1/16 cell
//!               [u, u+1) x [v, v+1) of the face is covered. Axes per face: DOWN/UP u=x v=z,
//!               NORTH/SOUTH u=x v=y, WEST/EAST u=z v=y. A cell only partly covered (shapes off
//!               the 1/16 grid) is set for a state's own face and clear for an occluder face.
//!               An empty shape that is not Shapes.empty() itself is a mask with no bit set.
//! fluid_models  fluid_model_count x 56 bytes: u8 layer, u8 has_overlay, u8 tint_kind (0xFF none),
//!               u8 0, i32 tint_argb, then f32 u0, u1, v0, v1 of the still, flowing and overlay
//!               sprites (TextureAtlasSprite.getU0/getU1/getV0/getV1)
//! air_ids       air_id_count x u32 state ids with flags::AIR (lets the kernel skip air with SIMD)
//! ```

use gaius_kernel_abi::{KernelError, Reader, Status};

pub const TABLE_MAGIC: u32 = u32::from_le_bytes(*b"GMTB");
pub const TABLE_VERSION: u16 = 1;
pub const HEADER_LEN: usize = 64;
pub const STATE_LEN: usize = 56;
pub const GROUP_LEN: usize = 12;
pub const ENTRY_LEN: usize = 8;
pub const PART_LEN: usize = 20;
pub const QUAD_LEN: usize = 16;
pub const GEOMETRY_LEN: usize = 48;
pub const UV_LEN: usize = 32;
pub const TINT_LEN: usize = 8;
pub const MASK_LEN: usize = 32;
pub const FLUID_MODEL_LEN: usize = 56;
pub const MAX_STATES: u32 = 1 << 20;
pub const MAX_TINTS_PER_STATE: u8 = 64;
pub const MAX_AIR_IDS: u32 = 8;

pub const FACE_EMPTY: u16 = 0;
pub const FACE_FULL: u16 = 1;
pub const NO_FLUID_MODEL: u16 = 0xFFFF;
pub const NO_TINT: u8 = 0xFF;

/// Per-state boolean properties (`StateInfo::flags`).
pub mod flags {
    /// `isAir()`.
    pub const AIR: u32 = 1 << 0;
    /// `isSolidRender()`.
    pub const SOLID_RENDER: u32 = 1 << 1;
    /// `getRenderShape() == RenderShape.MODEL`.
    pub const RENDER_MODEL: u32 = 1 << 2;
    /// The AO corner test: 26.3 `isLightPermeable()`; 26.2 and 1.21.11
    /// `!isViewBlocking(level, pos) || getLightDampening() == 0`.
    pub const LIGHT_PERMEABLE: u32 = 1 << 3;
    /// `isCollisionShapeFullBlock(level, pos)`.
    pub const COLLISION_FULL: u32 = 1 << 4;
    /// `emissiveRendering()`.
    pub const EMISSIVE: u32 = 1 << 5;
    /// `isSolid()` (legacy solid flag; fluid heights).
    pub const SOLID: u32 = 1 << 6;
    /// `getBlock() instanceof LeavesBlock`.
    pub const LEAVES: u32 = 1 << 7;
    /// `getBlock() instanceof HalfTransparentBlock` (fluid overlay sprite).
    pub const HALF_TRANSPARENT: u32 = 1 << 8;
    /// 26.3 `is(BlockTags.BLOCKS_FLUID_FLOW)`; 26.2 and 1.21.11 `blocksMotion()`.
    pub const BLOCKS_FLUID_FLOW: u32 = 1 << 9;
    /// `getBlock() instanceof IceBlock`.
    pub const ICE: u32 = 1 << 10;
    /// `getFluidState().getValue(FlowingFluid.FALLING)`.
    pub const FLUID_FALLING: u32 = 1 << 11;
    /// `is(BlockTags.BARS)`.
    pub const BARS_TAG: u32 = 1 << 12;
    /// The exporter could not express this state; sections holding it go to the vanilla compiler.
    pub const UNSUPPORTED: u32 = 1 << 13;
    /// `hasBlockEntity()`; informational, block entities stay on the Java side.
    pub const HAS_BLOCK_ENTITY: u32 = 1 << 14;
}

/// `Block.skipRendering(state, neighbor, direction)` overrides.
pub mod skip {
    pub const NONE: u8 = 0;
    /// `HalfTransparentBlock`, `PowderSnowBlock`: `neighbor.is(this)`.
    pub const SAME_BLOCK: u8 = 1;
    /// `LiquidBlock`: the neighbor's fluid `isSame` this block's fluid.
    pub const SAME_FLUID: u8 = 2;
    /// `IronBarsBlock`.
    pub const BARS: u8 = 3;
    /// `MangroveRootsBlock`: same block and a vertical direction.
    pub const SAME_BLOCK_VERTICAL: u8 = 4;
    /// `LeavesBlock`: `!cutoutLeaves && neighbor instanceof LeavesBlock`.
    pub const LEAVES: u8 = 5;
}

/// Tint source kinds (`BlockTintSources`).
pub mod tint {
    /// `constant(...)`, `redstone()`, `stem()` and any source whose world color is a function
    /// of the state alone: the exporter stores `colorInWorld` as the constant.
    pub const CONSTANT: u8 = 0;
    /// `grass()`, `grassBlock()`, `sugarCane()`: `BiomeColors.getAverageGrassColor(pos)`.
    pub const GRASS: u8 = 1;
    /// `doubleTallGrass()` on the UPPER half: the grass color of `pos.below()`.
    pub const GRASS_BELOW: u8 = 2;
    /// `foliage()`: `getAverageFoliageColor(pos)`.
    pub const FOLIAGE: u8 = 3;
    /// `dryFoliage()`: `getAverageDryFoliageColor(pos)`.
    pub const DRY_FOLIAGE: u8 = 4;
    /// `water()`, `waterParticles()`: `getAverageWaterColor(pos)`.
    pub const WATER: u8 = 5;
}

pub const LAYER_SOLID: u8 = 0;
pub const LAYER_CUTOUT: u8 = 1;
pub const LAYER_TRANSLUCENT: u8 = 2;

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct StateInfo {
    pub flags: u32,
    pub block_id: u16,
    pub fluid_model: u16,
    pub faces: [u16; 6],
    pub shade: f32,
    pub fluid_height: f32,
    pub max_h: f32,
    pub max_v: f32,
    pub first_group: u32,
    pub first_tint: u32,
    pub group_count: u8,
    pub tint_count: u8,
    pub emission: u8,
    pub skip: u8,
    pub fluid_group: u8,
    pub sturdy: u8,
    pub conn_true: u8,
    pub conn_present: u8,
    pub seed_offset: [i8; 3],
    pub offset_type: u8,
    pub multipart: bool,
}

impl StateInfo {
    #[inline]
    pub fn has(&self, flag: u32) -> bool {
        self.flags & flag != 0
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Group {
    pub first_entry: u32,
    pub entry_count: u16,
    pub weighted: bool,
    pub total_weight: u32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Entry {
    pub weight: u32,
    pub part: u32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Part {
    pub first_ref: u32,
    pub end: [u16; 7],
    pub use_ao: bool,
}

impl Part {
    /// Quad ref range of list `k` (0..6 = Direction ordinal, 6 = unculled).
    #[inline]
    pub fn range(&self, k: usize) -> core::ops::Range<usize> {
        let start = if k == 0 { 0 } else { self.end[k - 1] as usize };
        let base = self.first_ref as usize;
        base + start..base + self.end[k] as usize
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Quad {
    pub geometry: u32,
    pub uv: u32,
    pub tint_index: i16,
    pub direction: u8,
    pub shade_face: u8,
    pub layer: u8,
    pub emission: u8,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Tint {
    pub kind: u8,
    pub argb: i32,
}

/// `TextureAtlasSprite` bounds.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Sprite {
    pub u0: f32,
    pub u1: f32,
    pub v0: f32,
    pub v1: f32,
}

impl Sprite {
    /// `TextureAtlasSprite.getU(float)`.
    #[inline]
    pub fn u(&self, offset: f32) -> f32 {
        let diff = self.u1 - self.u0;
        self.u0 + diff * offset
    }

    /// `TextureAtlasSprite.getV(float)`.
    #[inline]
    pub fn v(&self, offset: f32) -> f32 {
        let diff = self.v1 - self.v0;
        self.v0 + diff * offset
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct FluidModel {
    pub layer: u8,
    pub has_overlay: bool,
    pub tint_kind: u8,
    pub tint_argb: i32,
    pub still: Sprite,
    pub flowing: Sprite,
    pub overlay: Sprite,
}

/// A face mask: 256 bits, bit `v * 16 + u`.
pub type Mask = [u64; 4];

pub const MASK_NONE: Mask = [0; 4];
pub const MASK_ALL: Mask = [u64::MAX; 4];

/// The parsed table. Masks are indexed by face id (0 empty, 1 full, then the stored masks).
#[derive(Clone, Debug, Default)]
pub struct ModelTable {
    /// The writing profile's kernelSemantics.code (`port/versions/<id>.json`); informational.
    pub semantics: u16,
    pub epoch: u32,
    pub states: Vec<StateInfo>,
    pub groups: Vec<Group>,
    pub entries: Vec<Entry>,
    pub parts: Vec<Part>,
    pub quad_refs: Vec<u32>,
    pub quads: Vec<Quad>,
    pub geometry: Vec<[f32; 12]>,
    pub uvs: Vec<[f32; 8]>,
    pub tints: Vec<Tint>,
    pub masks: Vec<Mask>,
    pub fluids: Vec<FluidModel>,
    pub air_ids: Vec<u32>,
}

fn bad(message: &str) -> KernelError {
    KernelError::new(Status::BadPayload, message)
}

fn truncated(_: Status) -> KernelError {
    KernelError::new(Status::Truncated, "model table is truncated")
}

/// Reads `count` records of `len` bytes each with `parse`, then pads to 8.
fn section<T>(
    r: &mut Reader<'_>,
    count: u32,
    len: usize,
    parse: impl Fn(&mut Reader<'_>) -> Result<T, Status>,
) -> Result<Vec<T>, KernelError> {
    let bytes = (count as usize)
        .checked_mul(len)
        .ok_or_else(|| bad("model table section overflows"))?;
    if bytes > r.remaining() {
        return Err(truncated(Status::Truncated));
    }
    let mut out = Vec::new();
    out.try_reserve_exact(count as usize)
        .map_err(|_| KernelError::new(Status::OutOfMemory, "model table does not fit in memory"))?;
    for _ in 0..count {
        out.push(parse(r).map_err(truncated)?);
    }
    r.align(8).map_err(truncated)?;
    Ok(out)
}

fn f32le(r: &mut Reader<'_>) -> Result<f32, Status> {
    r.u32().map(f32::from_bits)
}

fn i16le(r: &mut Reader<'_>) -> Result<i16, Status> {
    r.u16().map(|v| v as i16)
}

impl ModelTable {
    /// Parses and validates a table. Every index is bounds-checked here, so the mesher can
    /// trust the table afterwards.
    pub fn parse(bytes: &[u8]) -> Result<ModelTable, KernelError> {
        let mut r = Reader::new(bytes);
        let t = |s| truncated(s);
        if r.u32().map_err(t)? != TABLE_MAGIC {
            return Err(bad("not a model table"));
        }
        if r.u16().map_err(t)? != TABLE_VERSION {
            return Err(KernelError::new(Status::BadVersion, "unsupported model table version"));
        }
        let semantics = r.u16().map_err(t)?;
        let epoch = r.u32().map_err(t)?;
        // Twelve counts and the reserved word end the 64-byte header.
        let mut counts = [0u32; 13];
        for c in counts.iter_mut() {
            *c = r.u32().map_err(t)?;
        }
        let [state_count, group_count, entry_count, part_count, quad_ref_count, quad_count, geometry_count, uv_count, tint_count, mask_count, fluid_count, air_count, _] =
            counts;
        if state_count == 0 || state_count > MAX_STATES {
            return Err(bad("state count out of range"));
        }
        if air_count > MAX_AIR_IDS {
            return Err(bad("too many air ids"));
        }

        let states = section(&mut r, state_count, STATE_LEN, |r| {
            let flags = r.u32()?;
            let block_id = r.u16()?;
            let fluid_model = r.u16()?;
            let mut faces = [0u16; 6];
            for f in faces.iter_mut() {
                *f = r.u16()?;
            }
            let shade = f32le(r)?;
            let fluid_height = f32le(r)?;
            let max_h = f32le(r)?;
            let max_v = f32le(r)?;
            let first_group = r.u32()?;
            let first_tint = r.u32()?;
            let group_count = r.u8()?;
            let tint_count = r.u8()?;
            let emission = r.u8()?;
            let skip = r.u8()?;
            let fluid_group = r.u8()?;
            let sturdy = r.u8()?;
            let conn_true = r.u8()?;
            let conn_present = r.u8()?;
            let seed_offset = [r.u8()? as i8, r.u8()? as i8, r.u8()? as i8];
            let packed = r.u8()?;
            Ok(StateInfo {
                flags,
                block_id,
                fluid_model,
                faces,
                shade,
                fluid_height,
                max_h,
                max_v,
                first_group,
                first_tint,
                group_count,
                tint_count,
                emission,
                skip,
                fluid_group,
                sturdy,
                conn_true,
                conn_present,
                seed_offset,
                offset_type: packed & 3,
                multipart: packed & 0x80 != 0,
            })
        })?;
        let groups = section(&mut r, group_count, GROUP_LEN, |r| {
            let first_entry = r.u32()?;
            let entry_count = r.u16()?;
            let weighted = r.u8()? != 0;
            r.u8()?;
            let total_weight = r.u32()?;
            Ok(Group {
                first_entry,
                entry_count,
                weighted,
                total_weight,
            })
        })?;
        let entries = section(&mut r, entry_count, ENTRY_LEN, |r| {
            Ok(Entry {
                weight: r.u32()?,
                part: r.u32()?,
            })
        })?;
        let parts = section(&mut r, part_count, PART_LEN, |r| {
            let first_ref = r.u32()?;
            let mut end = [0u16; 7];
            for e in end.iter_mut() {
                *e = r.u16()?;
            }
            let flags = r.u8()?;
            r.u8()?;
            Ok(Part {
                first_ref,
                end,
                use_ao: flags & 1 != 0,
            })
        })?;
        let quad_refs = section(&mut r, quad_ref_count, 4, |r| r.u32())?;
        let quads = section(&mut r, quad_count, QUAD_LEN, |r| {
            let geometry = r.u32()?;
            let uv = r.u32()?;
            let tint_index = i16le(r)?;
            let direction = r.u8()?;
            let shade_face = r.u8()?;
            let layer = r.u8()?;
            let emission = r.u8()?;
            r.u16()?;
            Ok(Quad {
                geometry,
                uv,
                tint_index,
                direction,
                shade_face,
                layer,
                emission,
            })
        })?;
        let geometry = section(&mut r, geometry_count, GEOMETRY_LEN, |r| {
            let mut g = [0f32; 12];
            for v in g.iter_mut() {
                *v = f32le(r)?;
            }
            Ok(g)
        })?;
        let uvs = section(&mut r, uv_count, UV_LEN, |r| {
            let mut g = [0f32; 8];
            for v in g.iter_mut() {
                *v = f32le(r)?;
            }
            Ok(g)
        })?;
        let tints = section(&mut r, tint_count, TINT_LEN, |r| {
            let kind = r.u8()?;
            r.take(3)?;
            Ok(Tint { kind, argb: r.i32()? })
        })?;
        let mut masks = Vec::new();
        masks
            .try_reserve_exact(mask_count as usize + 2)
            .map_err(|_| KernelError::new(Status::OutOfMemory, "model table does not fit in memory"))?;
        masks.push(MASK_NONE);
        masks.push(MASK_ALL);
        masks.extend(section(&mut r, mask_count, MASK_LEN, |r| {
            let mut m = MASK_NONE;
            for row in 0..16 {
                let bits = r.u16()? as u64;
                m[row / 4] |= bits << ((row % 4) * 16);
            }
            Ok(m)
        })?);
        let sprite = |r: &mut Reader<'_>| -> Result<Sprite, Status> {
            Ok(Sprite {
                u0: f32le(r)?,
                u1: f32le(r)?,
                v0: f32le(r)?,
                v1: f32le(r)?,
            })
        };
        let fluids = section(&mut r, fluid_count, FLUID_MODEL_LEN, |r| {
            let layer = r.u8()?;
            let has_overlay = r.u8()? != 0;
            let tint_kind = r.u8()?;
            r.u8()?;
            let tint_argb = r.i32()?;
            Ok(FluidModel {
                layer,
                has_overlay,
                tint_kind,
                tint_argb,
                still: sprite(r)?,
                flowing: sprite(r)?,
                overlay: sprite(r)?,
            })
        })?;
        let air_ids = section(&mut r, air_count, 4, |r| r.u32())?;

        let table = ModelTable {
            semantics,
            epoch,
            states,
            groups,
            entries,
            parts,
            quad_refs,
            quads,
            geometry,
            uvs,
            tints,
            masks,
            fluids,
            air_ids,
        };
        table.validate()?;
        Ok(table)
    }

    fn validate(&self) -> Result<(), KernelError> {
        let faces = self.masks.len();
        for s in &self.states {
            if s.faces.iter().any(|&f| f as usize >= faces) {
                return Err(bad("state face shape out of range"));
            }
            if s.fluid_model != NO_FLUID_MODEL && s.fluid_model as usize >= self.fluids.len() {
                return Err(bad("state fluid model out of range"));
            }
            if s.fluid_group != 0 && s.fluid_model == NO_FLUID_MODEL && !s.has(flags::UNSUPPORTED) {
                return Err(bad("fluid state without a fluid model"));
            }
            if s.first_group as usize + s.group_count as usize > self.groups.len() {
                return Err(bad("state model groups out of range"));
            }
            if !s.multipart && s.group_count > 1 {
                return Err(bad("a single-variant model has more than one group"));
            }
            if s.tint_count > MAX_TINTS_PER_STATE || s.first_tint as usize + s.tint_count as usize > self.tints.len() {
                return Err(bad("state tint sources out of range"));
            }
            if s.skip > skip::LEAVES || s.offset_type > 2 {
                return Err(bad("state rule out of range"));
            }
        }
        for g in &self.groups {
            if g.entry_count == 0 || g.first_entry as usize + g.entry_count as usize > self.entries.len() {
                return Err(bad("group entries out of range"));
            }
            if g.weighted {
                let entries = &self.entries[g.first_entry as usize..][..g.entry_count as usize];
                let sum = entries.iter().map(|e| e.weight as u64).sum::<u64>();
                if g.total_weight == 0 || sum != g.total_weight as u64 || g.total_weight > i32::MAX as u32 {
                    return Err(bad("group weights do not add up"));
                }
            }
        }
        if self.entries.iter().any(|e| e.part as usize >= self.parts.len()) {
            return Err(bad("entry part out of range"));
        }
        for p in &self.parts {
            if p.end.windows(2).any(|w| w[0] > w[1]) || p.first_ref as usize + p.end[6] as usize > self.quad_refs.len()
            {
                return Err(bad("part quads out of range"));
            }
        }
        if self.quad_refs.iter().any(|&q| q as usize >= self.quads.len()) {
            return Err(bad("quad ref out of range"));
        }
        for q in &self.quads {
            if q.geometry as usize >= self.geometry.len()
                || q.uv as usize >= self.uvs.len()
                || q.direction > 5
                || q.shade_face > 5
                || q.layer > LAYER_TRANSLUCENT
                || q.emission > 15
            {
                return Err(bad("quad fields out of range"));
            }
        }
        if self.tints.iter().any(|t| t.kind > tint::WATER) {
            return Err(bad("unknown tint kind"));
        }
        for f in &self.fluids {
            if f.layer > LAYER_TRANSLUCENT || (f.tint_kind != NO_TINT && f.tint_kind > tint::WATER) {
                return Err(bad("fluid model fields out of range"));
            }
        }
        for &id in &self.air_ids {
            if id as usize >= self.states.len() || !self.states[id as usize].has(flags::AIR) {
                return Err(bad("air id is not an air state"));
            }
        }
        Ok(())
    }

    /// Approximate bytes held by the parsed table (memory budget reporting).
    pub fn memory_bytes(&self) -> usize {
        use core::mem::size_of;
        self.states.capacity() * size_of::<StateInfo>()
            + self.groups.capacity() * size_of::<Group>()
            + self.entries.capacity() * size_of::<Entry>()
            + self.parts.capacity() * size_of::<Part>()
            + self.quad_refs.capacity() * 4
            + self.quads.capacity() * size_of::<Quad>()
            + self.geometry.capacity() * 48
            + self.uvs.capacity() * 32
            + self.tints.capacity() * size_of::<Tint>()
            + self.masks.capacity() * 32
            + self.fluids.capacity() * size_of::<FluidModel>()
    }
}

/// Builds tables in the binary format; the tests use it to feed synthetic tables through the
/// real parser, and it documents the layout in code.
#[derive(Clone, Debug, Default)]
pub struct TableWriter {
    pub table: ModelTable,
}

fn pad8(out: &mut Vec<u8>) {
    while !out.len().is_multiple_of(8) {
        out.push(0);
    }
}

impl TableWriter {
    pub fn encode(&self) -> Vec<u8> {
        let t = &self.table;
        let mut o = Vec::new();
        o.extend_from_slice(&TABLE_MAGIC.to_le_bytes());
        o.extend_from_slice(&TABLE_VERSION.to_le_bytes());
        o.extend_from_slice(&t.semantics.to_le_bytes());
        o.extend_from_slice(&t.epoch.to_le_bytes());
        let stored_masks = t.masks.len().saturating_sub(2);
        for c in [
            t.states.len(),
            t.groups.len(),
            t.entries.len(),
            t.parts.len(),
            t.quad_refs.len(),
            t.quads.len(),
            t.geometry.len(),
            t.uvs.len(),
            t.tints.len(),
            stored_masks,
            t.fluids.len(),
            t.air_ids.len(),
            0,
        ] {
            o.extend_from_slice(&(c as u32).to_le_bytes());
        }
        debug_assert_eq!(o.len(), HEADER_LEN);
        for s in &t.states {
            o.extend_from_slice(&s.flags.to_le_bytes());
            o.extend_from_slice(&s.block_id.to_le_bytes());
            o.extend_from_slice(&s.fluid_model.to_le_bytes());
            for f in s.faces {
                o.extend_from_slice(&f.to_le_bytes());
            }
            for f in [s.shade, s.fluid_height, s.max_h, s.max_v] {
                o.extend_from_slice(&f.to_le_bytes());
            }
            o.extend_from_slice(&s.first_group.to_le_bytes());
            o.extend_from_slice(&s.first_tint.to_le_bytes());
            o.extend_from_slice(&[
                s.group_count,
                s.tint_count,
                s.emission,
                s.skip,
                s.fluid_group,
                s.sturdy,
                s.conn_true,
                s.conn_present,
                s.seed_offset[0] as u8,
                s.seed_offset[1] as u8,
                s.seed_offset[2] as u8,
                s.offset_type | if s.multipart { 0x80 } else { 0 },
            ]);
        }
        pad8(&mut o);
        for g in &t.groups {
            o.extend_from_slice(&g.first_entry.to_le_bytes());
            o.extend_from_slice(&g.entry_count.to_le_bytes());
            o.extend_from_slice(&[g.weighted as u8, 0]);
            o.extend_from_slice(&g.total_weight.to_le_bytes());
        }
        pad8(&mut o);
        for e in &t.entries {
            o.extend_from_slice(&e.weight.to_le_bytes());
            o.extend_from_slice(&e.part.to_le_bytes());
        }
        pad8(&mut o);
        for p in &t.parts {
            o.extend_from_slice(&p.first_ref.to_le_bytes());
            for e in p.end {
                o.extend_from_slice(&e.to_le_bytes());
            }
            o.extend_from_slice(&[p.use_ao as u8, 0]);
        }
        pad8(&mut o);
        for q in &t.quad_refs {
            o.extend_from_slice(&q.to_le_bytes());
        }
        pad8(&mut o);
        for q in &t.quads {
            o.extend_from_slice(&q.geometry.to_le_bytes());
            o.extend_from_slice(&q.uv.to_le_bytes());
            o.extend_from_slice(&q.tint_index.to_le_bytes());
            o.extend_from_slice(&[q.direction, q.shade_face, q.layer, q.emission, 0, 0]);
        }
        pad8(&mut o);
        for g in &t.geometry {
            for v in g {
                o.extend_from_slice(&v.to_le_bytes());
            }
        }
        pad8(&mut o);
        for g in &t.uvs {
            for v in g {
                o.extend_from_slice(&v.to_le_bytes());
            }
        }
        pad8(&mut o);
        for tint in &t.tints {
            o.extend_from_slice(&[tint.kind, 0, 0, 0]);
            o.extend_from_slice(&tint.argb.to_le_bytes());
        }
        pad8(&mut o);
        for m in t.masks.iter().skip(2) {
            for row in 0..16 {
                o.extend_from_slice(&(((m[row / 4] >> ((row % 4) * 16)) & 0xFFFF) as u16).to_le_bytes());
            }
        }
        pad8(&mut o);
        for f in &t.fluids {
            o.extend_from_slice(&[f.layer, f.has_overlay as u8, f.tint_kind, 0]);
            o.extend_from_slice(&f.tint_argb.to_le_bytes());
            for s in [f.still, f.flowing, f.overlay] {
                for v in [s.u0, s.u1, s.v0, s.v1] {
                    o.extend_from_slice(&v.to_le_bytes());
                }
            }
        }
        pad8(&mut o);
        for a in &t.air_ids {
            o.extend_from_slice(&a.to_le_bytes());
        }
        pad8(&mut o);
        o
    }
}
