//! The generator IR: everything the kernel needs to generate chunks of one
//! dimension exactly like the Java `NoiseBasedChunkGenerator`, exported by the
//! Java side after `RandomState` creation (the noise router is fully resolved:
//! registry references inlined, noises named, block states and biomes mapped to
//! their registry ids).
//!
//! # Binary format (version 1)
//!
//! All integers and floats are little-endian; there is no alignment inside the
//! file. `str` is a `u16` byte length followed by UTF-8. `NONE` is
//! `0xFFFF_FFFF`. Node and spline references must point at *earlier* entries
//! (the exporter writes children before parents), so the graph is acyclic.
//!
//! ```text
//! header   u32 magic "GWIR" (bytes 47 57 49 52), u16 version = 1,
//!          u8 semantics (kernelSemantics.code of the profile, port/versions/<id>.json;
//!          its irVersion must equal the header version), u8 reserved,
//!          u32 section_count, u32 total_len (header included)
//! section  u32 tag (four ASCII bytes read as little-endian u32), u32 len, len bytes
//! ```
//!
//! Unknown section tags are skipped. Sections:
//!
//! `SETT` settings
//! ```text
//! i64 seed; u8 random (0 xoroshiro, 1 legacy); u8 aquifers_enabled; u8 ore_veins_enabled (26.2);
//! u8 legacy_random_source (NoiseGeneratorSettings.useLegacyRandomSource)
//! i32 noise_min_y, noise_height       NoiseSettings clamped to the chunk's height accessor
//! i32 cell_width, cell_height          NoiseSettings cell size (26.2 NoiseChunk)
//! i32 sea_level
//! u32 default_block, default_fluid     state table indices
//! i64 biome_zoom_seed                  BiomeManager.obfuscateSeed(seed)
//! i32 level_min_y, level_height        the level's height accessor (chunk sections)
//! ```
//!
//! `STAT` block states (index 0 must be `minecraft:air`)
//! ```text
//! u32 n; n x (u32 global_id, u32 flags)
//!   flags: 1 isAir, 2 fluid state not empty, 4 opaque for OCEAN_FLOOR_WG (BLOCKS_MOTION_IN_HEIGHTMAP /
//!          blocksMotion before 26.3), 8 block is minecraft:water, 16 block is minecraft:lava,
//!          32 block is the settings' default block (state.is(defaultBlock.getBlock()))
//! u32 m; m x u32 special states, in this order (NONE when absent):
//!   air, water, lava, terracotta, white, orange, yellow, brown, red, light_gray terracotta,
//!   packed_ice, snow_block
//! ```
//!
//! `NOIS` noise instances
//! ```text
//! u32 n; per noise:
//!   u8 kind (0 = NoiseParameters(firstOctave, amplitudes), 1 = 26.3 NormalNoise.Parameters)
//!   u8 seeding (0 = RandomState positional factory fromHashOf(name),
//!               1 = createLegacyNetherBiome(new LegacyRandomSource(seed + legacy_offset)))
//!   u16 reserved; i64 legacy_offset; str name (e.g. "minecraft:temperature")
//!   kind 0: i32 first_octave, u32 count, f64 amplitudes[count]
//!   kind 1: f64 base_amplitude, i32 base_octave, i32 octave_count,
//!           u8 normalize (0 disabled, 1 enabled, 2 legacy), u32 count, f64 amplitude_modifiers[count]
//! ```
//!
//! `DENS` density nodes, then splines
//! ```text
//! u32 n; per node: u8 op, u8 arg, u8 domain_axes (26.3, bit 1 x / 2 y / 4 z), u8 reserved,
//!                  f64 min_value, f64 max_value (26.3: range(); 26.2: minValue()/maxValue()), payload
//!   op  0 CONST              f64 value
//!   op  1 NOISE              u32 noise, f64 xz_scale, f64 y_scale, u32 shift_x, u32 shift_y, u32 shift_z
//!                            (NONE shifts = 26.3 DensityFunctions.zero() / 26.2 Noise; arg 1 = 26.2 ShiftedNoise)
//!   op  2 SHIFT_A            u32 noise
//!   op  3 SHIFT_B            u32 noise
//!   op  4 SHIFT              u32 noise
//!   op  5 OLD_BLENDED_NOISE  f64 xz_scale, y_scale, xz_factor, y_factor, smear_scale_multiplier
//!   op  6 END_ISLANDS
//!   op  7 GRADIENT (26.3)    u8 axis (0 x, 1 y, 2 z), u8 tiling (0 clamp, 1 repeat, 2 mirrored),
//!                            i32 from, i32 to, f64 from_value, f64 to_value
//!   op  8 Y_CLAMPED_GRADIENT i32 from_y, i32 to_y, f64 from_value, f64 to_value
//!   op  9 DISTANCE_TO_POINT  i32 x, y, z, u8 metric (0 euclidean, 1 squared, 2 manhattan, 3 chebyshev)
//!   op 10 BLEND_ALPHA, 11 BLEND_OFFSET, 12 BEARDIFIER
//!   op 13 UNARY              arg type (0 abs, 1 square, 2 cube, 3 sqrt, 4 half_negative,
//!                            5 quarter_negative, 6 reciprocal/invert, 7 negate, 8 squeeze, 9 log, 10 sign);
//!                            u32 input
//!   op 14 BINARY             arg type (0 add, 1 sub, 2 mul, 3 div, 4 min, 5 max); u32 left, u32 right
//!   op 15 MUL_OR_ADD (26.2)  arg (0 mul, 1 add); u32 input, f64 argument
//!   op 16 POW                u32 base, u32 exponent
//!   op 17 CLAMP              u32 input, f64 min, f64 max
//!   op 18 LERP               u32 alpha, u32 first, u32 second
//!   op 19 RANGE_CHOICE       u32 input, f64 min_inclusive, f64 max_exclusive, u32 in_range, u32 out_of_range
//!   op 20 INTERVAL_SELECT    u32 input, u32 n, f64 thresholds[n], u32 functions[n + 1]
//!   op 21 ROUND              arg type (0 floor, 1 round, 2 ceil, 3 truncate); u32 input, u32 multiple
//!   op 22 SLICE              arg axis; i32 coordinate, u32 input
//!   op 23 FIND_TOP_SURFACE   u32 density, u32 upper_bound, i32 lower_bound, i32 cell_height
//!   op 24 SPLINE             u32 spline
//!   op 25 INTERPOLATED       u32 input, i32 cell_xz, i32 cell_y (26.2: 0, the settings' cells)
//!   op 26 CACHE (26.3)       u32 input   (a deduplicated DensityFunctionCompiler cache)
//!   op 27 FLAT_CACHE, 28 CACHE_2D, 29 CACHE_ONCE, 30 CACHE_ALL_IN_CELL (26.2 markers)  u32 input
//!   op 31 BLEND_DENSITY      u32 input
//!   op 32 SCALED_NOISE       u32 noise, u32 positive scale node; scale * abs(noise(x/scale, y/scale, z/scale))
//! u32 m; per spline: u8 kind
//!   0 constant: f32 value
//!   1 multipoint: u32 coordinate (node), u32 n, f32 locations[n], f32 derivatives[n], u32 values[n] (splines)
//! ```
//!
//! The 26.3 tree is the one `DensityFunctionCompiler` compiles: references
//! inlined, `cache` markers replaced by deduplicated `CACHE` nodes (one per
//! distinct input), `DfRewriteRule.SLICE_UNIFORM_AXES` applied. The 26.2 tree
//! is `RandomState.router()` (noises wired, markers kept).
//!
//! `ROOT` named functions: `u32 n; n x (u16 role, u16 reserved, u32 node)`, roles in [`Role`].
//!
//! `FLUI` global fluid picker: `i32 lava_below, i32 lava_level, u32 lava_state, i32 fluid_level,
//! u32 fluid_state` (`y < lava_below` picks `FluidStatus(lava_level, lava_state)`, otherwise
//! `FluidStatus(fluid_level, fluid_state)`).
//!
//! `VEIN` (26.2 OreVeinifier): `u32 copper_ore, raw_copper_block, granite, deepslate_iron_ore,
//! raw_iron_block, tuff; i32 copper_min_y, copper_max_y, iron_min_y, iron_max_y`.
//!
//! `SURF` surface rules
//! ```text
//! u32 noises[9]: surface, surface_secondary, clay_bands_offset, badlands_pillar, badlands_pillar_roof,
//!                badlands_surface, iceberg_pillar, iceberg_pillar_roof, iceberg_surface (NONE = absent)
//! u32 n; conditions: u8 type, payload
//!   0 BIOME u32 k, u32 biomes[k]           1 NOISE_THRESHOLD u32 noise, f64 min, f64 max, u8 is_3d
//!   2 VERTICAL_GRADIENT str random_name, i32 true_at_and_below, i32 false_at_and_above (resolved anchors)
//!   3 Y_ABOVE i32 anchor_y, i32 surface_depth_multiplier, u8 add_stone_depth
//!   4 WATER i32 offset, i32 surface_depth_multiplier, u8 add_stone_depth
//!   5 TEMPERATURE  6 STEEP  7 NOT u32 condition  8 HOLE  9 ABOVE_PRELIMINARY_SURFACE
//!   10 STONE_DEPTH i32 offset, u8 add_surface_depth, i32 secondary_depth_range, u8 ceiling
//! u32 m; rules: u8 type, payload
//!   0 BLOCK u32 state   1 SEQUENCE u32 k, u32 rules[k]   2 CONDITION u32 condition, u32 rule
//!   3 BANDLANDS         4 ORE_VEIN (26.3) u32 ore, raw_ore, filler, f32 raw_ore_chance,
//!                       u32 density_node, richness_node, filler_gap_node
//! u32 root_rule
//! ```
//! Conditions and rules may only reference earlier entries.
//!
//! `BIOM` biomes
//! ```text
//! u8 source (0 multi_noise, 1 fixed, 2 the_end), u8 reserved[3]
//! u32 n; n x (u32 global_id, f32 base_temperature, u8 temperature_modifier (0 none, 1 frozen),
//!             u8 flags (1 eroded_badlands, 2 frozen_ocean, 4 deep_frozen_ocean), u16 reserved)
//! fixed:       u32 biome
//! the_end:     u32 end, highlands, midlands, small_end_islands, end_barrens
//! multi_noise: u32 children_per_node (26.3: 19, before: 6), u32 k,
//!              k x (i64 min, max for temperature, humidity, continentalness, erosion, depth,
//!                   weirdness, offset (min = max); u32 biome)
//! ```

pub mod example;
mod reader;
pub mod writer;

use reader::Reader;

pub const MAGIC: u32 = u32::from_le_bytes(*b"GWIR");
pub const VERSION: u16 = 1;
pub const NONE: u32 = u32::MAX;

pub const fn tag(name: &[u8; 4]) -> u32 {
    u32::from_le_bytes(*name)
}

pub const TAG_SETTINGS: u32 = tag(b"SETT");
pub const TAG_STATES: u32 = tag(b"STAT");
pub const TAG_NOISES: u32 = tag(b"NOIS");
pub const TAG_DENSITY: u32 = tag(b"DENS");
pub const TAG_ROOTS: u32 = tag(b"ROOT");
pub const TAG_FLUID: u32 = tag(b"FLUI");
pub const TAG_VEINS: u32 = tag(b"VEIN");
pub const TAG_SURFACE: u32 = tag(b"SURF");
pub const TAG_BIOMES: u32 = tag(b"BIOM");

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IrError {
    pub message: String,
}

impl IrError {
    pub fn new(message: impl Into<String>) -> Self {
        IrError {
            message: message.into(),
        }
    }
}

impl core::fmt::Display for IrError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(&self.message)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    pub seed: i64,
    pub legacy_random: bool,
    pub aquifers_enabled: bool,
    pub ore_veins_enabled: bool,
    pub legacy_random_source: bool,
    pub noise_min_y: i32,
    pub noise_height: i32,
    pub cell_width: i32,
    pub cell_height: i32,
    pub sea_level: i32,
    pub default_block: u32,
    pub default_fluid: u32,
    pub biome_zoom_seed: i64,
    pub level_min_y: i32,
    pub level_height: i32,
}

/// State flag bits of the `STAT` table.
pub mod state_flags {
    pub const AIR: u32 = 1;
    pub const FLUID: u32 = 2;
    pub const OCEAN_FLOOR_OPAQUE: u32 = 4;
    pub const WATER: u32 = 8;
    pub const LAVA: u32 = 16;
    pub const DEFAULT_BLOCK: u32 = 32;
}

/// Order of the special states after the `STAT` table.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(usize)]
pub enum Special {
    Air = 0,
    Water,
    Lava,
    Terracotta,
    WhiteTerracotta,
    OrangeTerracotta,
    YellowTerracotta,
    BrownTerracotta,
    RedTerracotta,
    LightGrayTerracotta,
    PackedIce,
    SnowBlock,
}

pub const SPECIAL_COUNT: usize = 12;

#[derive(Clone, Debug, PartialEq)]
pub struct States {
    pub global_ids: Vec<u32>,
    pub flags: Vec<u32>,
    pub special: [u32; SPECIAL_COUNT],
}

#[derive(Clone, Debug, PartialEq)]
pub enum NoiseParams {
    /// Pre-26.3 `NoiseParameters(firstOctave, amplitudes)`.
    Parity { first_octave: i32, amplitudes: Vec<f64> },
    /// 26.3 `NormalNoise.Parameters`.
    Recipe {
        base_amplitude: f64,
        base_octave: i32,
        octave_count: i32,
        normalize: u8,
        amplitude_modifiers: Vec<f64>,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct NoiseDef {
    pub name: String,
    /// `None`: `fromHashOf(name)`; `Some(offset)`: legacy nether biome seeded with `seed + offset`.
    pub legacy_nether_offset: Option<i64>,
    pub params: NoiseParams,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Axis {
    X,
    Y,
    Z,
}

impl Axis {
    fn from_code(code: u8) -> Option<Axis> {
        match code {
            0 => Some(Axis::X),
            1 => Some(Axis::Y),
            2 => Some(Axis::Z),
            _ => None,
        }
    }

    pub fn code(self) -> u8 {
        self as u8
    }

    #[inline]
    pub fn choose(self, x: i32, y: i32, z: i32) -> i32 {
        match self {
            Axis::X => x,
            Axis::Y => y,
            Axis::Z => z,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tiling {
    Clamp,
    Repeat,
    Mirrored,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Metric {
    Euclidean,
    EuclideanSquared,
    Manhattan,
    Chebyshev,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Unary {
    Abs,
    Square,
    Cube,
    Sqrt,
    HalfNegative,
    QuarterNegative,
    Reciprocal,
    Negate,
    Squeeze,
    Log,
    Sign,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Binary {
    Add,
    Sub,
    Mul,
    Div,
    Min,
    Max,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RoundType {
    Floor,
    Round,
    Ceil,
    Truncate,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Marker {
    FlatCache,
    Cache2D,
    CacheOnce,
    CacheAllInCell,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Op {
    Const(f64),
    Noise {
        noise: u32,
        xz_scale: f64,
        y_scale: f64,
        shift: Option<[u32; 3]>,
    },
    ShiftA(u32),
    ShiftB(u32),
    Shift(u32),
    OldBlendedNoise([f64; 5]),
    EndIslands,
    Gradient {
        axis: Axis,
        tiling: Tiling,
        from: i32,
        to: i32,
        from_value: f64,
        to_value: f64,
    },
    YClampedGradient {
        from_y: i32,
        to_y: i32,
        from_value: f64,
        to_value: f64,
    },
    DistanceToPoint {
        point: [i32; 3],
        metric: Metric,
    },
    BlendAlpha,
    BlendOffset,
    Beardifier,
    Unary(Unary, u32),
    Binary(Binary, u32, u32),
    MulOrAdd {
        add: bool,
        input: u32,
        argument: f64,
    },
    Pow(u32, u32),
    Clamp(u32, f64, f64),
    Lerp(u32, u32, u32),
    RangeChoice {
        input: u32,
        min_inclusive: f64,
        max_exclusive: f64,
        in_range: u32,
        out_of_range: u32,
    },
    IntervalSelect {
        input: u32,
        thresholds: Vec<f64>,
        functions: Vec<u32>,
    },
    Round(RoundType, u32, u32),
    Slice(Axis, i32, u32),
    FindTopSurface {
        density: u32,
        upper_bound: u32,
        lower_bound: i32,
        cell_height: i32,
    },
    Spline(u32),
    Interpolated {
        input: u32,
        cell_xz: i32,
        cell_y: i32,
    },
    Cache(u32),
    Marker(Marker, u32),
    BlendDensity(u32),
    ScaledNoise {
        noise: u32,
        scale: u32,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Node {
    pub op: Op,
    pub axes: u8,
    pub min: f64,
    pub max: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub enum SplineDef {
    Constant(f32),
    Multipoint {
        coordinate: u32,
        locations: Vec<f32>,
        derivatives: Vec<f32>,
        values: Vec<u32>,
    },
}

/// Named roots (`ROOT` roles).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u16)]
pub enum Role {
    Temperature = 1,
    Vegetation = 2,
    Continents = 3,
    Erosion = 4,
    Depth = 5,
    Ridges = 6,
    FinalDensity = 7,
    /// 26.2 `preliminary_surface_level`; 26.3 `chunk_surface_level` (the material system's
    /// preliminary surface function).
    PreliminarySurface = 8,
    AquiferBarrier = 9,
    AquiferFloodedness = 10,
    AquiferSpread = 11,
    AquiferLava = 12,
    /// 26.3 `Aquifer.Config.exclusion`.
    AquiferExclusion = 13,
    /// 26.3 `Aquifer.Config.surfaceLevel`.
    AquiferSurfaceLevel = 14,
    VeinToggle = 15,
    VeinRidged = 16,
    VeinGap = 17,
}

pub const ROLE_COUNT: usize = 18;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Roots {
    pub nodes: [Option<u32>; ROLE_COUNT],
}

impl Roots {
    pub fn get(&self, role: Role) -> Option<u32> {
        self.nodes[role as usize]
    }

    pub fn require(&self, role: Role) -> Result<u32, IrError> {
        self.get(role)
            .ok_or_else(|| IrError::new(format!("missing root {role:?}")))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FluidPicker {
    pub lava_below: i32,
    pub lava_level: i32,
    pub lava_state: u32,
    pub fluid_level: i32,
    pub fluid_state: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Veins {
    pub copper_ore: u32,
    pub raw_copper: u32,
    pub granite: u32,
    pub iron_ore: u32,
    pub raw_iron: u32,
    pub tuff: u32,
    pub copper_y: [i32; 2],
    pub iron_y: [i32; 2],
}

#[derive(Clone, Debug, PartialEq)]
pub enum Condition {
    Biome(Vec<u32>),
    NoiseThreshold {
        noise: u32,
        min: f64,
        max: f64,
        is_3d: bool,
    },
    VerticalGradient {
        random_name: String,
        true_at_and_below: i32,
        false_at_and_above: i32,
    },
    YAbove {
        anchor_y: i32,
        surface_depth_multiplier: i32,
        add_stone_depth: bool,
    },
    Water {
        offset: i32,
        surface_depth_multiplier: i32,
        add_stone_depth: bool,
    },
    Temperature,
    Steep,
    Not(u32),
    Hole,
    AbovePreliminarySurface,
    StoneDepth {
        offset: i32,
        add_surface_depth: bool,
        secondary_depth_range: i32,
        ceiling: bool,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub enum Rule {
    Block(u32),
    Sequence(Vec<u32>),
    Condition(u32, u32),
    Bandlands,
    OreVein {
        ore: u32,
        raw_ore: u32,
        filler: u32,
        raw_ore_chance: f32,
        density: u32,
        richness: u32,
        filler_gap: u32,
    },
}

/// Indices into [`SurfaceDef::noises`].
pub mod surface_noise {
    pub const SURFACE: usize = 0;
    pub const SURFACE_SECONDARY: usize = 1;
    pub const CLAY_BANDS_OFFSET: usize = 2;
    pub const BADLANDS_PILLAR: usize = 3;
    pub const BADLANDS_PILLAR_ROOF: usize = 4;
    pub const BADLANDS_SURFACE: usize = 5;
    pub const ICEBERG_PILLAR: usize = 6;
    pub const ICEBERG_PILLAR_ROOF: usize = 7;
    pub const ICEBERG_SURFACE: usize = 8;
    pub const COUNT: usize = 9;
}

#[derive(Clone, Debug, PartialEq)]
pub struct SurfaceDef {
    pub noises: [u32; surface_noise::COUNT],
    pub conditions: Vec<Condition>,
    pub rules: Vec<Rule>,
    pub root: u32,
}

pub mod biome_flags {
    pub const ERODED_BADLANDS: u8 = 1;
    pub const FROZEN_OCEAN: u8 = 2;
    pub const DEEP_FROZEN_OCEAN: u8 = 4;
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BiomeInfo {
    pub global_id: u32,
    pub base_temperature: f32,
    pub frozen_modifier: bool,
    pub flags: u8,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ClimateEntry {
    /// `[min, max]` per parameter: temperature, humidity, continentalness, erosion, depth,
    /// weirdness, offset.
    pub params: [[i64; 2]; 7],
    pub biome: u32,
}

#[derive(Clone, Debug, PartialEq)]
pub enum BiomeSource {
    MultiNoise {
        children_per_node: u32,
        entries: Vec<ClimateEntry>,
    },
    Fixed(u32),
    TheEnd {
        end: u32,
        highlands: u32,
        midlands: u32,
        islands: u32,
        barrens: u32,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Biomes {
    pub biomes: Vec<BiomeInfo>,
    pub source: BiomeSource,
}

/// A decoded generator IR.
#[derive(Clone, Debug, PartialEq)]
pub struct Ir {
    pub semantics: gaius_noise::Semantics,
    pub settings: Settings,
    pub states: States,
    pub noises: Vec<NoiseDef>,
    pub nodes: Vec<Node>,
    pub splines: Vec<SplineDef>,
    pub roots: Roots,
    pub fluid: FluidPicker,
    pub veins: Option<Veins>,
    pub surface: Option<SurfaceDef>,
    pub biomes: Biomes,
}

/// Upper bounds that keep a malformed IR from asking for unbounded work.
pub mod limits {
    pub const MAX_NODES: usize = 1 << 16;
    pub const MAX_NOISES: usize = 1024;
    pub const MAX_STATES: usize = 1 << 16;
    pub const MAX_OCTAVES: usize = 64;
    pub const MAX_BIOMES: usize = 1 << 12;
}

impl Ir {
    pub fn decode(bytes: &[u8]) -> Result<Ir, IrError> {
        let mut r = Reader::new(bytes, "header");
        if r.u32()? != MAGIC {
            return Err(IrError::new("not a generator IR (bad magic)"));
        }
        let version = r.u16()?;
        if version != VERSION {
            return Err(IrError::new(format!("unsupported IR version {version}")));
        }
        let code = r.u8()?;
        let semantics = gaius_noise::Semantics::from_code(code)
            .ok_or_else(|| IrError::new(format!("unknown kernel semantics code {code}")))?;
        if semantics.ir_version != version {
            return Err(IrError::new(format!(
                "kernel semantics code {code} declares IR version {}, not {version}",
                semantics.ir_version
            )));
        }
        r.u8()?;
        let section_count = r.u32()?;
        let total = r.u32()? as usize;
        if total != bytes.len() {
            return Err(IrError::new(format!(
                "IR length {} does not match header {total}",
                bytes.len()
            )));
        }
        let mut settings = None;
        let mut states = None;
        let mut noises = Vec::new();
        let mut nodes = Vec::new();
        let mut splines = Vec::new();
        let mut roots = Roots::default();
        let mut fluid = None;
        let mut veins = None;
        let mut surface = None;
        let mut biomes = None;
        for _ in 0..section_count {
            let tag = r.u32()?;
            let len = r.u32()? as usize;
            let body = r.take(len)?;
            match tag {
                TAG_SETTINGS => settings = Some(decode_settings(body)?),
                TAG_STATES => states = Some(decode_states(body)?),
                TAG_NOISES => noises = decode_noises(body)?,
                TAG_DENSITY => (nodes, splines) = decode_density(body)?,
                TAG_ROOTS => roots = decode_roots(body)?,
                TAG_FLUID => fluid = Some(decode_fluid(body)?),
                TAG_VEINS => veins = Some(decode_veins(body)?),
                TAG_SURFACE => surface = Some(decode_surface(body)?),
                TAG_BIOMES => biomes = Some(decode_biomes(body)?),
                _ => {}
            }
        }
        let ir = Ir {
            semantics,
            settings: settings.ok_or_else(|| IrError::new("missing SETT section"))?,
            states: states.ok_or_else(|| IrError::new("missing STAT section"))?,
            noises,
            nodes,
            splines,
            roots,
            fluid: fluid.ok_or_else(|| IrError::new("missing FLUI section"))?,
            veins,
            surface,
            biomes: biomes.ok_or_else(|| IrError::new("missing BIOM section"))?,
        };
        ir.validate()?;
        Ok(ir)
    }

    /// Checks every cross reference so the evaluators can index without bounds errors.
    fn validate(&self) -> Result<(), IrError> {
        let n_states = self.states.global_ids.len() as u32;
        let n_noises = self.noises.len() as u32;
        let n_nodes = self.nodes.len() as u32;
        let n_splines = self.splines.len() as u32;
        let n_biomes = self.biomes.biomes.len() as u32;
        let state = |s: u32, what: &str| {
            if s < n_states {
                Ok(())
            } else {
                Err(IrError::new(format!("{what}: state {s} out of range")))
            }
        };
        if n_states == 0 || self.states.flags[0] & state_flags::AIR == 0 {
            return Err(IrError::new("state 0 must be air"));
        }
        state(self.settings.default_block, "default_block")?;
        state(self.settings.default_fluid, "default_fluid")?;
        for (i, &s) in self.states.special.iter().enumerate() {
            if s != NONE {
                state(s, "special state")?;
            } else if i <= Special::Lava as usize {
                return Err(IrError::new(
                    "air, water and lava must be present in the special states",
                ));
            }
        }
        state(self.fluid.lava_state, "fluid picker")?;
        state(self.fluid.fluid_state, "fluid picker")?;
        if self.settings.noise_height < 0 || self.settings.level_height <= 0 || self.settings.level_height % 16 != 0 {
            return Err(IrError::new("bad height settings"));
        }
        if self.settings.cell_width <= 0 || self.settings.cell_height <= 0 || 16 % self.settings.cell_width != 0 {
            return Err(IrError::new("bad cell size"));
        }
        let before = |child: u32, index: u32, what: &str| {
            if child < index {
                Ok(())
            } else {
                Err(IrError::new(format!(
                    "node {index}: {what} reference {child} is not an earlier node"
                )))
            }
        };
        for (index, node) in self.nodes.iter().enumerate() {
            let index = index as u32;
            let check = |child: u32| before(child, index, "child");
            match &node.op {
                Op::Noise { noise, shift, .. } => {
                    if *noise >= n_noises {
                        return Err(IrError::new(format!("node {index}: noise out of range")));
                    }
                    if let Some(s) = shift {
                        for &c in s {
                            check(c)?;
                        }
                    }
                }
                Op::ShiftA(noise) | Op::ShiftB(noise) | Op::Shift(noise) => {
                    if *noise >= n_noises {
                        return Err(IrError::new(format!("node {index}: noise out of range")));
                    }
                }
                Op::ScaledNoise { noise, scale } => {
                    check(*scale)?;
                    let positive_constant = |child: u32| matches!(self.nodes[child as usize].op, Op::Const(value) if value.is_finite() && value > 0.0);
                    let safe_scale = match &self.nodes[*scale as usize].op {
                        Op::Const(_) => positive_constant(*scale),
                        Op::IntervalSelect { functions, .. } => functions.iter().all(|child| positive_constant(*child)),
                        _ => false,
                    };
                    if *noise >= n_noises
                        || self.nodes[*scale as usize].min <= 0.0
                        || !self.nodes[*scale as usize].min.is_finite()
                        || !self.nodes[*scale as usize].max.is_finite()
                        || !safe_scale
                    {
                        return Err(IrError::new(format!("node {index}: invalid scaled noise")));
                    }
                }
                Op::Unary(_, a)
                | Op::MulOrAdd { input: a, .. }
                | Op::Clamp(a, _, _)
                | Op::Slice(_, _, a)
                | Op::Interpolated { input: a, .. }
                | Op::Cache(a)
                | Op::Marker(_, a)
                | Op::BlendDensity(a) => check(*a)?,
                Op::Binary(_, a, b) | Op::Pow(a, b) | Op::Round(_, a, b) => {
                    check(*a)?;
                    check(*b)?;
                }
                Op::Lerp(a, b, c) => {
                    check(*a)?;
                    check(*b)?;
                    check(*c)?;
                }
                Op::RangeChoice {
                    input,
                    in_range,
                    out_of_range,
                    ..
                } => {
                    check(*input)?;
                    check(*in_range)?;
                    check(*out_of_range)?;
                }
                Op::IntervalSelect {
                    input,
                    thresholds,
                    functions,
                } => {
                    check(*input)?;
                    if functions.len() != thresholds.len() + 1 {
                        return Err(IrError::new(format!("node {index}: interval_select arity")));
                    }
                    for &f in functions {
                        check(f)?;
                    }
                }
                Op::FindTopSurface {
                    density,
                    upper_bound,
                    cell_height,
                    ..
                } => {
                    check(*density)?;
                    check(*upper_bound)?;
                    if *cell_height <= 0 {
                        return Err(IrError::new(format!("node {index}: find_top_surface cell height")));
                    }
                }
                Op::Spline(s) => {
                    if *s >= n_splines {
                        return Err(IrError::new(format!("node {index}: spline out of range")));
                    }
                }
                Op::Gradient { from, to, .. } if from == to => {
                    return Err(IrError::new(format!("node {index}: gradient from == to")));
                }
                _ => {}
            }
            if let Op::Interpolated { cell_xz, cell_y, .. } = node.op {
                if cell_xz < 0 || cell_y < 0 || (self.semantics.uses_sampler32() && (cell_xz == 0 || cell_y == 0)) {
                    return Err(IrError::new(format!("node {index}: bad interpolation cell")));
                }
            }
        }
        for (index, spline) in self.splines.iter().enumerate() {
            if let SplineDef::Multipoint {
                coordinate,
                locations,
                derivatives,
                values,
            } = spline
            {
                if *coordinate >= n_nodes {
                    return Err(IrError::new(format!("spline {index}: coordinate out of range")));
                }
                if locations.is_empty() || locations.len() != derivatives.len() || locations.len() != values.len() {
                    return Err(IrError::new(format!("spline {index}: point arrays disagree")));
                }
                if values.iter().any(|&v| v as usize >= index) {
                    return Err(IrError::new(format!("spline {index}: value is not an earlier spline")));
                }
            }
        }
        for root in self.roots.nodes.iter().flatten() {
            if *root >= n_nodes {
                return Err(IrError::new("root out of range"));
            }
        }
        if let Some(v) = &self.veins {
            for s in [v.copper_ore, v.raw_copper, v.granite, v.iron_ore, v.raw_iron, v.tuff] {
                state(s, "veins")?;
            }
        }
        if let Some(surface) = &self.surface {
            for &n in &surface.noises {
                if n != NONE && n >= n_noises {
                    return Err(IrError::new("surface noise out of range"));
                }
            }
            for (index, c) in surface.conditions.iter().enumerate() {
                match c {
                    Condition::Biome(list) => {
                        if list.iter().any(|&b| b >= n_biomes) {
                            return Err(IrError::new("biome condition out of range"));
                        }
                    }
                    Condition::NoiseThreshold { noise, .. } => {
                        if *noise >= n_noises {
                            return Err(IrError::new("noise condition out of range"));
                        }
                    }
                    Condition::Not(target) if *target as usize >= index => {
                        return Err(IrError::new("not condition must reference an earlier condition"));
                    }
                    _ => {}
                }
            }
            let n_conditions = surface.conditions.len() as u32;
            for (index, rule) in surface.rules.iter().enumerate() {
                let earlier = |r: u32| (r as usize) < index;
                match rule {
                    Rule::Block(s) => state(*s, "block rule")?,
                    Rule::Sequence(list) => {
                        if list.is_empty() || !list.iter().all(|&r| earlier(r)) {
                            return Err(IrError::new("sequence rule references"));
                        }
                    }
                    Rule::Condition(c, r) => {
                        if *c >= n_conditions || !earlier(*r) {
                            return Err(IrError::new("condition rule references"));
                        }
                    }
                    Rule::Bandlands => {}
                    Rule::OreVein {
                        ore,
                        raw_ore,
                        filler,
                        density,
                        richness,
                        filler_gap,
                        ..
                    } => {
                        state(*ore, "ore vein")?;
                        state(*raw_ore, "ore vein")?;
                        state(*filler, "ore vein")?;
                        if *density >= n_nodes || *richness >= n_nodes || *filler_gap >= n_nodes {
                            return Err(IrError::new("ore vein node out of range"));
                        }
                    }
                }
            }
            if surface.root as usize >= surface.rules.len() {
                return Err(IrError::new("surface root out of range"));
            }
        }
        if n_biomes == 0 {
            return Err(IrError::new("no biomes"));
        }
        match &self.biomes.source {
            BiomeSource::MultiNoise {
                children_per_node,
                entries,
            } => {
                if entries.is_empty() || *children_per_node < 2 {
                    return Err(IrError::new("bad multi noise parameter list"));
                }
                if entries.iter().any(|e| e.biome >= n_biomes) {
                    return Err(IrError::new("climate entry biome out of range"));
                }
            }
            BiomeSource::Fixed(b) => {
                if *b >= n_biomes {
                    return Err(IrError::new("fixed biome out of range"));
                }
            }
            BiomeSource::TheEnd {
                end,
                highlands,
                midlands,
                islands,
                barrens,
            } => {
                if [end, highlands, midlands, islands, barrens]
                    .iter()
                    .any(|&&b| b >= n_biomes)
                {
                    return Err(IrError::new("end biome out of range"));
                }
            }
        }
        Ok(())
    }
}

fn decode_settings(body: &[u8]) -> Result<Settings, IrError> {
    let mut r = Reader::new(body, "SETT");
    Ok(Settings {
        seed: r.i64()?,
        legacy_random: r.bool()?,
        aquifers_enabled: r.bool()?,
        ore_veins_enabled: r.bool()?,
        legacy_random_source: r.bool()?,
        noise_min_y: r.i32()?,
        noise_height: r.i32()?,
        cell_width: r.i32()?,
        cell_height: r.i32()?,
        sea_level: r.i32()?,
        default_block: r.u32()?,
        default_fluid: r.u32()?,
        biome_zoom_seed: r.i64()?,
        level_min_y: r.i32()?,
        level_height: r.i32()?,
    })
}

fn decode_states(body: &[u8]) -> Result<States, IrError> {
    let mut r = Reader::new(body, "STAT");
    let n = r.count(8)?;
    if n > limits::MAX_STATES {
        return Err(r.error("too many states"));
    }
    let mut global_ids = Vec::with_capacity(n);
    let mut flags = Vec::with_capacity(n);
    for _ in 0..n {
        global_ids.push(r.u32()?);
        flags.push(r.u32()?);
    }
    let m = r.count(4)?;
    let mut special = [NONE; SPECIAL_COUNT];
    for i in 0..m {
        let s = r.u32()?;
        if i < SPECIAL_COUNT {
            special[i] = s;
        }
    }
    Ok(States {
        global_ids,
        flags,
        special,
    })
}

fn decode_noises(body: &[u8]) -> Result<Vec<NoiseDef>, IrError> {
    let mut r = Reader::new(body, "NOIS");
    let n = r.count(16)?;
    if n > limits::MAX_NOISES {
        return Err(r.error("too many noises"));
    }
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        let kind = r.u8()?;
        let seeding = r.u8()?;
        r.u16()?;
        let legacy_offset = r.i64()?;
        let name = r.string()?;
        let params = match kind {
            0 => {
                let first_octave = r.i32()?;
                let count = r.count(8)?;
                if count == 0 || count > limits::MAX_OCTAVES {
                    return Err(r.error("amplitude count out of range"));
                }
                NoiseParams::Parity {
                    first_octave,
                    amplitudes: r.f64s(count)?,
                }
            }
            1 => {
                let base_amplitude = r.f64()?;
                let base_octave = r.i32()?;
                let octave_count = r.i32()?;
                let normalize = r.u8()?;
                let count = r.count(8)?;
                if !(1..=limits::MAX_OCTAVES as i32).contains(&octave_count)
                    || normalize > 2
                    || (count != 0 && count != octave_count as usize)
                {
                    return Err(r.error("bad noise recipe"));
                }
                NoiseParams::Recipe {
                    base_amplitude,
                    base_octave,
                    octave_count,
                    normalize,
                    amplitude_modifiers: r.f64s(count)?,
                }
            }
            _ => return Err(r.error("unknown noise kind")),
        };
        let legacy_nether_offset = match seeding {
            0 => None,
            1 => Some(legacy_offset),
            _ => return Err(r.error("unknown noise seeding")),
        };
        out.push(NoiseDef {
            name,
            legacy_nether_offset,
            params,
        });
    }
    Ok(out)
}

fn decode_density(body: &[u8]) -> Result<(Vec<Node>, Vec<SplineDef>), IrError> {
    let mut r = Reader::new(body, "DENS");
    let n = r.count(20)?;
    if n > limits::MAX_NODES {
        return Err(r.error("too many nodes"));
    }
    let mut nodes = Vec::with_capacity(n);
    for _ in 0..n {
        let op = r.u8()?;
        let arg = r.u8()?;
        let axes = r.u8()?;
        r.u8()?;
        let min = r.f64()?;
        let max = r.f64()?;
        let op = match op {
            0 => Op::Const(r.f64()?),
            1 => {
                let noise = r.u32()?;
                let xz_scale = r.f64()?;
                let y_scale = r.f64()?;
                let s = [r.u32()?, r.u32()?, r.u32()?];
                let shift = if s.iter().all(|&c| c == NONE) {
                    None
                } else if s.contains(&NONE) {
                    return Err(r.error("noise shifts must be all present or all NONE"));
                } else {
                    Some(s)
                };
                Op::Noise {
                    noise,
                    xz_scale,
                    y_scale,
                    shift,
                }
            }
            2 => Op::ShiftA(r.u32()?),
            3 => Op::ShiftB(r.u32()?),
            4 => Op::Shift(r.u32()?),
            5 => Op::OldBlendedNoise([r.f64()?, r.f64()?, r.f64()?, r.f64()?, r.f64()?]),
            6 => Op::EndIslands,
            7 => {
                let axis = Axis::from_code(r.u8()?).ok_or_else(|| r.error("bad axis"))?;
                let tiling = match r.u8()? {
                    0 => Tiling::Clamp,
                    1 => Tiling::Repeat,
                    2 => Tiling::Mirrored,
                    _ => return Err(r.error("bad tiling")),
                };
                Op::Gradient {
                    axis,
                    tiling,
                    from: r.i32()?,
                    to: r.i32()?,
                    from_value: r.f64()?,
                    to_value: r.f64()?,
                }
            }
            8 => Op::YClampedGradient {
                from_y: r.i32()?,
                to_y: r.i32()?,
                from_value: r.f64()?,
                to_value: r.f64()?,
            },
            9 => {
                let point = [r.i32()?, r.i32()?, r.i32()?];
                let metric = match r.u8()? {
                    0 => Metric::Euclidean,
                    1 => Metric::EuclideanSquared,
                    2 => Metric::Manhattan,
                    3 => Metric::Chebyshev,
                    _ => return Err(r.error("bad metric")),
                };
                Op::DistanceToPoint { point, metric }
            }
            10 => Op::BlendAlpha,
            11 => Op::BlendOffset,
            12 => Op::Beardifier,
            13 => {
                let t = match arg {
                    0 => Unary::Abs,
                    1 => Unary::Square,
                    2 => Unary::Cube,
                    3 => Unary::Sqrt,
                    4 => Unary::HalfNegative,
                    5 => Unary::QuarterNegative,
                    6 => Unary::Reciprocal,
                    7 => Unary::Negate,
                    8 => Unary::Squeeze,
                    9 => Unary::Log,
                    10 => Unary::Sign,
                    _ => return Err(r.error("bad unary type")),
                };
                Op::Unary(t, r.u32()?)
            }
            14 => {
                let t = match arg {
                    0 => Binary::Add,
                    1 => Binary::Sub,
                    2 => Binary::Mul,
                    3 => Binary::Div,
                    4 => Binary::Min,
                    5 => Binary::Max,
                    _ => return Err(r.error("bad binary type")),
                };
                Op::Binary(t, r.u32()?, r.u32()?)
            }
            15 => Op::MulOrAdd {
                add: arg == 1,
                input: r.u32()?,
                argument: r.f64()?,
            },
            16 => Op::Pow(r.u32()?, r.u32()?),
            17 => Op::Clamp(r.u32()?, r.f64()?, r.f64()?),
            18 => Op::Lerp(r.u32()?, r.u32()?, r.u32()?),
            19 => Op::RangeChoice {
                input: r.u32()?,
                min_inclusive: r.f64()?,
                max_exclusive: r.f64()?,
                in_range: r.u32()?,
                out_of_range: r.u32()?,
            },
            20 => {
                let input = r.u32()?;
                let k = r.count(12)?;
                let thresholds = r.f64s(k)?;
                let functions = r.u32s(k + 1)?;
                Op::IntervalSelect {
                    input,
                    thresholds,
                    functions,
                }
            }
            21 => {
                let t = match arg {
                    0 => RoundType::Floor,
                    1 => RoundType::Round,
                    2 => RoundType::Ceil,
                    3 => RoundType::Truncate,
                    _ => return Err(r.error("bad round type")),
                };
                Op::Round(t, r.u32()?, r.u32()?)
            }
            22 => {
                let axis = Axis::from_code(arg).ok_or_else(|| r.error("bad slice axis"))?;
                let coordinate = r.i32()?;
                Op::Slice(axis, coordinate, r.u32()?)
            }
            23 => Op::FindTopSurface {
                density: r.u32()?,
                upper_bound: r.u32()?,
                lower_bound: r.i32()?,
                cell_height: r.i32()?,
            },
            24 => Op::Spline(r.u32()?),
            25 => Op::Interpolated {
                input: r.u32()?,
                cell_xz: r.i32()?,
                cell_y: r.i32()?,
            },
            26 => Op::Cache(r.u32()?),
            27 => Op::Marker(Marker::FlatCache, r.u32()?),
            28 => Op::Marker(Marker::Cache2D, r.u32()?),
            29 => Op::Marker(Marker::CacheOnce, r.u32()?),
            30 => Op::Marker(Marker::CacheAllInCell, r.u32()?),
            31 => Op::BlendDensity(r.u32()?),
            32 => Op::ScaledNoise {
                noise: r.u32()?,
                scale: r.u32()?,
            },
            _ => return Err(r.error("unknown density op")),
        };
        nodes.push(Node { op, axes, min, max });
    }
    let m = r.count(5)?;
    let mut splines = Vec::with_capacity(m);
    for _ in 0..m {
        splines.push(match r.u8()? {
            0 => SplineDef::Constant(r.f32()?),
            1 => {
                let coordinate = r.u32()?;
                let k = r.count(12)?;
                SplineDef::Multipoint {
                    coordinate,
                    locations: r.f32s(k)?,
                    derivatives: r.f32s(k)?,
                    values: r.u32s(k)?,
                }
            }
            _ => return Err(r.error("unknown spline kind")),
        });
    }
    Ok((nodes, splines))
}

fn decode_roots(body: &[u8]) -> Result<Roots, IrError> {
    let mut r = Reader::new(body, "ROOT");
    let n = r.count(8)?;
    let mut roots = Roots::default();
    for _ in 0..n {
        let role = r.u16()? as usize;
        r.u16()?;
        let node = r.u32()?;
        if role > 0 && role < ROLE_COUNT && node != NONE {
            roots.nodes[role] = Some(node);
        }
    }
    Ok(roots)
}

fn decode_fluid(body: &[u8]) -> Result<FluidPicker, IrError> {
    let mut r = Reader::new(body, "FLUI");
    Ok(FluidPicker {
        lava_below: r.i32()?,
        lava_level: r.i32()?,
        lava_state: r.u32()?,
        fluid_level: r.i32()?,
        fluid_state: r.u32()?,
    })
}

fn decode_veins(body: &[u8]) -> Result<Veins, IrError> {
    let mut r = Reader::new(body, "VEIN");
    Ok(Veins {
        copper_ore: r.u32()?,
        raw_copper: r.u32()?,
        granite: r.u32()?,
        iron_ore: r.u32()?,
        raw_iron: r.u32()?,
        tuff: r.u32()?,
        copper_y: [r.i32()?, r.i32()?],
        iron_y: [r.i32()?, r.i32()?],
    })
}

fn decode_surface(body: &[u8]) -> Result<SurfaceDef, IrError> {
    let mut r = Reader::new(body, "SURF");
    let mut noises = [NONE; surface_noise::COUNT];
    for n in noises.iter_mut() {
        *n = r.u32()?;
    }
    let n = r.count(1)?;
    let mut conditions = Vec::with_capacity(n);
    for _ in 0..n {
        conditions.push(match r.u8()? {
            0 => {
                let k = r.count(4)?;
                Condition::Biome(r.u32s(k)?)
            }
            1 => Condition::NoiseThreshold {
                noise: r.u32()?,
                min: r.f64()?,
                max: r.f64()?,
                is_3d: r.bool()?,
            },
            2 => Condition::VerticalGradient {
                random_name: r.string()?,
                true_at_and_below: r.i32()?,
                false_at_and_above: r.i32()?,
            },
            3 => Condition::YAbove {
                anchor_y: r.i32()?,
                surface_depth_multiplier: r.i32()?,
                add_stone_depth: r.bool()?,
            },
            4 => Condition::Water {
                offset: r.i32()?,
                surface_depth_multiplier: r.i32()?,
                add_stone_depth: r.bool()?,
            },
            5 => Condition::Temperature,
            6 => Condition::Steep,
            7 => Condition::Not(r.u32()?),
            8 => Condition::Hole,
            9 => Condition::AbovePreliminarySurface,
            10 => Condition::StoneDepth {
                offset: r.i32()?,
                add_surface_depth: r.bool()?,
                secondary_depth_range: r.i32()?,
                ceiling: r.bool()?,
            },
            _ => return Err(r.error("unknown surface condition")),
        });
    }
    let m = r.count(1)?;
    let mut rules = Vec::with_capacity(m);
    for _ in 0..m {
        rules.push(match r.u8()? {
            0 => Rule::Block(r.u32()?),
            1 => {
                let k = r.count(4)?;
                Rule::Sequence(r.u32s(k)?)
            }
            2 => Rule::Condition(r.u32()?, r.u32()?),
            3 => Rule::Bandlands,
            4 => Rule::OreVein {
                ore: r.u32()?,
                raw_ore: r.u32()?,
                filler: r.u32()?,
                raw_ore_chance: r.f32()?,
                density: r.u32()?,
                richness: r.u32()?,
                filler_gap: r.u32()?,
            },
            _ => return Err(r.error("unknown surface rule")),
        });
    }
    let root = r.u32()?;
    Ok(SurfaceDef {
        noises,
        conditions,
        rules,
        root,
    })
}

fn decode_biomes(body: &[u8]) -> Result<Biomes, IrError> {
    let mut r = Reader::new(body, "BIOM");
    let kind = r.u8()?;
    r.take(3)?;
    let n = r.count(12)?;
    if n > limits::MAX_BIOMES {
        return Err(r.error("too many biomes"));
    }
    let mut biomes = Vec::with_capacity(n);
    for _ in 0..n {
        let global_id = r.u32()?;
        let base_temperature = r.f32()?;
        let modifier = r.u8()?;
        let flags = r.u8()?;
        r.u16()?;
        biomes.push(BiomeInfo {
            global_id,
            base_temperature,
            frozen_modifier: modifier == 1,
            flags,
        });
    }
    let source = match kind {
        0 => {
            let children_per_node = r.u32()?;
            let k = r.count(116)?;
            let mut entries = Vec::with_capacity(k);
            for _ in 0..k {
                let mut params = [[0i64; 2]; 7];
                for p in params.iter_mut() {
                    *p = [r.i64()?, r.i64()?];
                }
                entries.push(ClimateEntry {
                    params,
                    biome: r.u32()?,
                });
            }
            BiomeSource::MultiNoise {
                children_per_node,
                entries,
            }
        }
        1 => BiomeSource::Fixed(r.u32()?),
        2 => BiomeSource::TheEnd {
            end: r.u32()?,
            highlands: r.u32()?,
            midlands: r.u32()?,
            islands: r.u32()?,
            barrens: r.u32()?,
        },
        _ => return Err(r.error("unknown biome source")),
    };
    Ok(Biomes { biomes, source })
}
