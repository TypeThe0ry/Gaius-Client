//! `SurfaceSystem.buildSurface` (1.21.11 / 26.2) and `MaterialSystem.buildSurface`
//! (26.3) with their rule trees (`SurfaceRules` / `material` rules), evaluated per
//! column and block over [`ChunkData`].
//!
//! Lazy conditions in vanilla cache their result between context updates; every
//! condition is a pure function of the context, so evaluating it on demand
//! returns the same values.

use crate::aquifer::WAY_BELOW_MIN_Y;
use crate::biome::temperature;
use crate::chunk::{ChunkData, HeightmapKind};
use crate::ir::{self, state_flags, surface_noise, BiomeInfo, Condition, Rule, Special, States, NONE};
use crate::java::{min_f64, round_f32, round_f64};
use crate::volume::Volume;
use gaius_noise::mth::{map, FloorMode};
use gaius_noise::{PositionalRandomFactory, RandomSource};

const BAND_COUNT: usize = 192;

/// What the surface needs from the generator and the job.
pub trait SurfaceHost {
    /// A noise of the table: 26.3 `Noise.get` (widened), earlier `NormalNoise.getValue`.
    fn noise(&self, noise: u32, x: f64, y: f64, z: f64) -> f64;
    /// `biomeManager.getBiome(x, y, z)` as a biome table index.
    fn biome(&mut self, x: i32, y: i32, z: i32) -> u16;
    /// `Mth.floor` of the preliminary surface level used by `getMinSurfaceLevel`.
    fn preliminary_surface(&mut self, x: i32, z: i32) -> i32;
    /// 26.3 ore veins: samples a density function over the expected volume.
    fn prefill(&mut self, _node: u32, _volume: &Volume) -> Vec<f32> {
        Vec::new()
    }
    /// 26.3 ore veins: `sampleValue` of a density function.
    fn value(&mut self, _node: u32, _x: i32, _y: i32, _z: i32) -> f32 {
        0.0
    }
}

/// The part of a surface program that does not depend on the chunk.
pub struct SurfaceProgram {
    pub conditions: Vec<Condition>,
    pub rules: Vec<Rule>,
    pub root: u32,
    pub noises: [u32; surface_noise::COUNT],
    gradient_randoms: Vec<Option<PositionalRandomFactory>>,
    biome_sets: Vec<Vec<bool>>,
    ore_random: PositionalRandomFactory,
    noise_random: PositionalRandomFactory,
    bands: [u16; BAND_COUNT],
}

fn generate_bands(random: &mut RandomSource, special: &[u32; ir::SPECIAL_COUNT]) -> Option<[u16; BAND_COUNT]> {
    let s = |k: Special| {
        let v = special[k as usize];
        (v != NONE).then_some(v as u16)
    };
    let terracotta = s(Special::Terracotta)?;
    let orange = s(Special::OrangeTerracotta)?;
    let yellow = s(Special::YellowTerracotta)?;
    let brown = s(Special::BrownTerracotta)?;
    let red = s(Special::RedTerracotta)?;
    let white = s(Special::WhiteTerracotta)?;
    let light_gray = s(Special::LightGrayTerracotta)?;
    let mut bands = [terracotta; BAND_COUNT];
    let n = BAND_COUNT as i32;
    let mut i = 0i32;
    while i < n {
        i += random.next_int_bound(5) + 1;
        if i < n {
            bands[i as usize] = orange;
        }
        i += 1;
    }
    let mut make = |random: &mut RandomSource, base_width: i32, state: u16| {
        let count = random.next_int_between_inclusive(6, 15);
        for _ in 0..count {
            let width = base_width + random.next_int_bound(3);
            let start = random.next_int_bound(n);
            let mut p = 0;
            while start + p < n && p < width {
                bands[(start + p) as usize] = state;
                p += 1;
            }
        }
    };
    make(random, 1, yellow);
    make(random, 2, brown);
    make(random, 1, red);
    let white_count = random.next_int_between_inclusive(9, 15);
    let mut placed = 0;
    let mut start = 0i32;
    while placed < white_count && start < n {
        bands[start as usize] = white;
        if start - 1 > 0 && random.next_boolean() {
            bands[(start - 1) as usize] = light_gray;
        }
        if start + 1 < n && random.next_boolean() {
            bands[(start + 1) as usize] = light_gray;
        }
        placed += 1;
        start += random.next_int_bound(16) + 4;
    }
    Some(bands)
}

impl SurfaceProgram {
    pub fn new(
        def: &ir::SurfaceDef,
        states: &States,
        biome_count: usize,
        noise_random: PositionalRandomFactory,
    ) -> SurfaceProgram {
        let gradient_randoms = def
            .conditions
            .iter()
            .map(|c| match c {
                Condition::VerticalGradient { random_name, .. } => {
                    Some(noise_random.from_hash_of(random_name).fork_positional())
                }
                _ => None,
            })
            .collect();
        let biome_sets = def
            .conditions
            .iter()
            .map(|c| match c {
                Condition::Biome(list) => {
                    let mut set = vec![false; biome_count];
                    for &b in list {
                        set[b as usize] = true;
                    }
                    set
                }
                _ => Vec::new(),
            })
            .collect();
        let bands = generate_bands(&mut noise_random.from_hash_of("minecraft:clay_bands"), &states.special)
            .unwrap_or([0; BAND_COUNT]);
        SurfaceProgram {
            conditions: def.conditions.clone(),
            rules: def.rules.clone(),
            root: def.root,
            noises: def.noises,
            gradient_randoms,
            biome_sets,
            ore_random: noise_random.from_hash_of("minecraft:ore").fork_positional(),
            noise_random,
            bands,
        }
    }
}

/// Generator-level facts the surface run needs.
pub struct SurfaceEnv<'a> {
    pub states: &'a States,
    pub biomes: &'a [BiomeInfo],
    pub synth32: bool,
    pub floor: FloorMode,
    pub sea_level: i32,
    pub default_block: u16,
    /// 26.2 surface biome sampled at `y = 0` (`useLegacyRandomSource`).
    pub legacy_surface_biome_y: bool,
}

/// `MaterialRuleContext` / `SurfaceRules.Context` state.
struct Context {
    block_x: i32,
    block_z: i32,
    block_y: i32,
    surface_depth: i32,
    surface_secondary: Option<f64>,
    min_surface_level: Option<i32>,
    gradient: [i32; 2],
    steep_heights: Option<bool>,
    stone_depth_above: i32,
    stone_depth_below: i32,
    water_height: i32,
    biome: Option<u16>,
    noise_xz: NoiseCache,
    noise_y: NoiseCache,
}

struct NoiseCache {
    values: Vec<f64>,
    stamps: Vec<u32>,
    generation: u32,
}

impl NoiseCache {
    fn new(size: usize) -> Self {
        Self {
            values: vec![0.0; size],
            stamps: vec![0; size],
            generation: 1,
        }
    }

    fn clear(&mut self) {
        self.generation = self.generation.wrapping_add(1);
        if self.generation == 0 {
            self.stamps.fill(0);
            self.generation = 1;
        }
    }

    fn get(&self, slot: usize) -> Option<f64> {
        (self.stamps[slot] == self.generation).then(|| self.values[slot])
    }

    fn set(&mut self, slot: usize, value: f64) {
        self.values[slot] = value;
        self.stamps[slot] = self.generation;
    }
}

struct OreBuffers {
    density: Vec<f32>,
    richness: Vec<f32>,
}

pub struct SurfaceRun<'a, H: SurfaceHost> {
    pub program: &'a SurfaceProgram,
    pub env: SurfaceEnv<'a>,
    pub host: H,
    expected: Volume,
    ores: Vec<Option<OreBuffers>>,
    ctx: Context,
}

impl<'a, H: SurfaceHost> SurfaceRun<'a, H> {
    /// "Compiles" the rule tree for a chunk: 26.3 ore vein rules prefill their densities
    /// over the chunk volume narrowed to the highest filled section.
    pub fn new(
        program: &'a SurfaceProgram,
        env: SurfaceEnv<'a>,
        mut host: H,
        chunk: &ChunkData,
        noise_min_y: i32,
    ) -> Self {
        let highest = chunk.highest_filled_section();
        let max_block_y = (chunk.min_y >> 4).wrapping_add(highest) * 16 + 15;
        let expected = Volume::blocks(
            [16, (max_block_y - noise_min_y + 1).max(1), 16],
            [chunk.chunk_x << 4, noise_min_y, chunk.chunk_z << 4],
        );
        let mut ores: Vec<Option<OreBuffers>> = (0..program.rules.len()).map(|_| None).collect();
        fn compile_order<H: SurfaceHost>(
            p: &SurfaceProgram,
            rule: u32,
            host: &mut H,
            expected: &Volume,
            ores: &mut Vec<Option<OreBuffers>>,
        ) {
            match &p.rules[rule as usize] {
                Rule::Sequence(list) => {
                    for &r in list {
                        compile_order(p, r, host, expected, ores);
                    }
                }
                Rule::Condition(_, r) => compile_order(p, *r, host, expected, ores),
                Rule::OreVein { density, richness, .. } => {
                    let density = host.prefill(*density, expected);
                    let richness = host.prefill(*richness, expected);
                    ores[rule as usize] = Some(OreBuffers { density, richness });
                }
                Rule::Block(_) | Rule::Bandlands => {}
            }
        }
        compile_order(program, program.root, &mut host, &expected, &mut ores);
        let noise_slots = program.conditions.len();
        SurfaceRun {
            program,
            env,
            host,
            expected,
            ores,
            ctx: Context {
                block_x: 0,
                block_z: 0,
                block_y: 0,
                surface_depth: 0,
                surface_secondary: None,
                min_surface_level: None,
                gradient: [0, 0],
                steep_heights: None,
                stone_depth_above: 0,
                stone_depth_below: 0,
                water_height: i32::MIN,
                biome: None,
                noise_xz: NoiseCache::new(noise_slots),
                noise_y: NoiseCache::new(noise_slots),
            },
        }
    }

    fn noise_value(&self, slot: usize, x: f64, y: f64, z: f64) -> f64 {
        let n = self.program.noises[slot];
        if n == NONE {
            0.0
        } else {
            self.host.noise(n, x, y, z)
        }
    }

    /// `getSurfaceDepth(x, z)`.
    fn surface_depth(&self, x: i32, z: i32) -> i32 {
        let noise = self.noise_value(surface_noise::SURFACE, x as f64, 0.0, z as f64);
        let r = self.program.noise_random.at(x, 0, z).next_double();
        (noise * 2.75 + 3.0 + r * 0.25) as i32
    }

    fn update_xz(&mut self, x: i32, z: i32, gradient: [i32; 2]) {
        self.ctx.block_x = x;
        self.ctx.block_z = z;
        self.ctx.gradient = gradient;
        self.ctx.surface_depth = self.surface_depth(x, z);
        self.ctx.surface_secondary = None;
        self.ctx.min_surface_level = None;
        self.ctx.steep_heights = None;
        self.ctx.noise_xz.clear();
        self.ctx.noise_y.clear();
        self.ctx.biome = None;
    }

    fn update_y(&mut self, above: i32, below: i32, water: i32, y: i32) {
        self.ctx.stone_depth_above = above;
        self.ctx.stone_depth_below = below;
        self.ctx.water_height = water;
        self.ctx.block_y = y;
        self.ctx.biome = None;
        self.ctx.noise_y.clear();
    }

    fn biome(&mut self) -> u16 {
        if let Some(b) = self.ctx.biome {
            return b;
        }
        let b = self.host.biome(self.ctx.block_x, self.ctx.block_y, self.ctx.block_z);
        self.ctx.biome = Some(b);
        b
    }

    fn surface_secondary(&mut self) -> f64 {
        if let Some(v) = self.ctx.surface_secondary {
            return v;
        }
        let (x, z) = (self.ctx.block_x, self.ctx.block_z);
        let v = self.noise_value(surface_noise::SURFACE_SECONDARY, x as f64, 0.0, z as f64);
        self.ctx.surface_secondary = Some(v);
        v
    }

    fn min_surface_level(&mut self) -> i32 {
        if let Some(v) = self.ctx.min_surface_level {
            return v;
        }
        let v = self.host.preliminary_surface(self.ctx.block_x, self.ctx.block_z) + self.ctx.surface_depth - 8;
        self.ctx.min_surface_level = Some(v);
        v
    }

    fn test(&mut self, chunk: &ChunkData, c: u32) -> bool {
        let program = self.program;
        match &program.conditions[c as usize] {
            Condition::Biome(_) => {
                let b = self.biome();
                program.biome_sets[c as usize][b as usize]
            }
            Condition::NoiseThreshold { noise, min, max, is_3d } => {
                let cache = if *is_3d { &self.ctx.noise_y } else { &self.ctx.noise_xz };
                let value = match cache.get(c as usize) {
                    Some(v) => v,
                    None => {
                        let (x, y, z) = (
                            self.ctx.block_x as f64,
                            self.ctx.block_y as f64,
                            self.ctx.block_z as f64,
                        );
                        let v = self.host.noise(*noise, x, if *is_3d { y } else { 0.0 }, z);
                        if *is_3d {
                            self.ctx.noise_y.set(c as usize, v);
                        } else {
                            self.ctx.noise_xz.set(c as usize, v);
                        }
                        v
                    }
                };
                value >= *min && value <= *max
            }
            Condition::VerticalGradient {
                true_at_and_below,
                false_at_and_above,
                ..
            } => {
                let y = self.ctx.block_y;
                if y <= *true_at_and_below {
                    true
                } else if y >= *false_at_and_above {
                    false
                } else {
                    let probability = map(
                        y as f64,
                        *true_at_and_below as f64,
                        *false_at_and_above as f64,
                        1.0,
                        0.0,
                    );
                    let factory = program.gradient_randoms[c as usize].as_ref().expect("gradient random");
                    let mut random = factory.at(self.ctx.block_x, y, self.ctx.block_z);
                    (random.next_float() as f64) < probability
                }
            }
            Condition::YAbove {
                anchor_y,
                surface_depth_multiplier,
                add_stone_depth,
            } => {
                let stone = if *add_stone_depth {
                    self.ctx.stone_depth_above
                } else {
                    0
                };
                self.ctx.block_y + stone >= anchor_y + self.ctx.surface_depth * surface_depth_multiplier
            }
            Condition::Water {
                offset,
                surface_depth_multiplier,
                add_stone_depth,
            } => {
                if self.ctx.water_height == i32::MIN {
                    return true;
                }
                let stone = if *add_stone_depth {
                    self.ctx.stone_depth_above
                } else {
                    0
                };
                self.ctx.block_y + stone
                    >= self.ctx.water_height + offset + self.ctx.surface_depth * surface_depth_multiplier
            }
            Condition::Temperature => {
                let b = self.biome();
                let t = temperature(
                    &self.env.biomes[b as usize],
                    self.env.synth32,
                    self.env.floor,
                    self.ctx.block_x,
                    self.ctx.block_y,
                    self.ctx.block_z,
                    self.env.sea_level,
                );
                !(t >= 0.15)
            }
            Condition::Steep => {
                if self.env.synth32 {
                    self.ctx.gradient[0] <= -4 || self.ctx.gradient[1] >= 4
                } else {
                    if let Some(v) = self.ctx.steep_heights {
                        return v;
                    }
                    let (x, z) = (self.ctx.block_x & 15, self.ctx.block_z & 15);
                    let h = |xx: i32, zz: i32| chunk.height_at(HeightmapKind::WorldSurface, xx, zz);
                    let north = h(x, (z - 1).max(0));
                    let south = h(x, (z + 1).min(15));
                    let v = south >= north + 4 || h((x - 1).max(0), z) >= h((x + 1).min(15), z) + 4;
                    self.ctx.steep_heights = Some(v);
                    v
                }
            }
            Condition::Not(target) => !self.test(chunk, *target),
            Condition::Hole => self.ctx.surface_depth <= 0,
            Condition::AbovePreliminarySurface => self.ctx.block_y >= self.min_surface_level(),
            Condition::StoneDepth {
                offset,
                add_surface_depth,
                secondary_depth_range,
                ceiling,
            } => {
                let stone = if *ceiling {
                    self.ctx.stone_depth_below
                } else {
                    self.ctx.stone_depth_above
                };
                let surface = if *add_surface_depth { self.ctx.surface_depth } else { 0 };
                let secondary = if *secondary_depth_range == 0 {
                    0
                } else {
                    map(self.surface_secondary(), -1.0, 1.0, 0.0, *secondary_depth_range as f64) as i32
                };
                stone <= 1 + offset + surface + secondary
            }
        }
    }

    /// `getBand(x, y, z)`.
    fn band(&self, x: i32, y: i32, z: i32) -> u16 {
        let raw = self.noise_value(surface_noise::CLAY_BANDS_OFFSET, x as f64, 0.0, z as f64);
        let offset = if self.env.synth32 {
            round_f32(raw as f32 * 4.0)
        } else {
            round_f64(raw * 4.0) as i32
        };
        let n = BAND_COUNT as i32;
        self.program.bands[(y.wrapping_add(offset).wrapping_add(n)).rem_euclid(n) as usize]
    }

    fn apply(&mut self, chunk: &ChunkData, rule: u32, x: i32, y: i32, z: i32) -> Option<u16> {
        let program = self.program;
        match &program.rules[rule as usize] {
            Rule::Block(s) => Some(*s as u16),
            Rule::Sequence(list) => {
                for &r in list.iter() {
                    if let Some(s) = self.apply(chunk, r, x, y, z) {
                        return Some(s);
                    }
                }
                None
            }
            Rule::Condition(c, r) => {
                if self.test(chunk, *c) {
                    self.apply(chunk, *r, x, y, z)
                } else {
                    None
                }
            }
            Rule::Bandlands => Some(self.band(x, y, z)),
            Rule::OreVein {
                ore,
                raw_ore,
                filler,
                raw_ore_chance,
                filler_gap,
                ..
            } => {
                let (density_node, richness_node) = match &program.rules[rule as usize] {
                    Rule::OreVein { density, richness, .. } => (*density, *richness),
                    _ => unreachable!(),
                };
                let (bx, by, bz) = (self.ctx.block_x, self.ctx.block_y, self.ctx.block_z);
                let index = self.expected.index_of_block(bx, by, bz);
                let density = match index {
                    Some(i) => self.ores[rule as usize].as_ref().expect("ore vein buffers").density[i],
                    None => self.host.value(density_node, bx, by, bz),
                };
                if density <= 0.0 {
                    return None;
                }
                let mut random = program.ore_random.at(x, y, z);
                if random.next_float() > density {
                    return None;
                }
                let richness = match index {
                    Some(i) => self.ores[rule as usize].as_ref().expect("ore vein buffers").richness[i],
                    None => self.host.value(richness_node, bx, by, bz),
                };
                if random.next_float() < richness && self.host.value(*filler_gap, bx, by, bz) < 0.0 {
                    Some(if random.next_float() < *raw_ore_chance {
                        *raw_ore as u16
                    } else {
                        *ore as u16
                    })
                } else {
                    Some(*filler as u16)
                }
            }
        }
    }

    #[inline]
    fn flags(&self, state: u16) -> u32 {
        self.env.states.flags[state as usize]
    }

    #[inline]
    fn is_stone(&self, state: u16) -> bool {
        let f = self.flags(state);
        f & state_flags::AIR == 0 && f & state_flags::FLUID == 0
    }

    /// `column.setBlock`: inside the build height, with fluid post-processing.
    fn set(&self, chunk: &mut ChunkData, x: i32, y: i32, z: i32, state: u16) {
        if chunk.inside(y) {
            chunk.set_block(self.env.states, x, y, z, state);
            if self.flags(state) & state_flags::FLUID != 0 {
                chunk.mark_post_processing(x, y, z);
            }
        }
    }

    fn gradients(chunk: &ChunkData, x: i32, z: i32) -> [i32; 2] {
        let h = |xx: i32, zz: i32| chunk.height_at(HeightmapKind::WorldSurface, xx, zz);
        [
            h((x + 1).min(15), z) - h((x - 1).max(0), z),
            h(x, (z + 1).min(15)) - h(x, (z - 1).max(0)),
        ]
    }

    /// `buildSurface` over the whole chunk.
    pub fn run(&mut self, chunk: &mut ChunkData) {
        let min_x = chunk.chunk_x << 4;
        let min_z = chunk.chunk_z << 4;
        let min_y = chunk.min_y;
        let max_y = chunk.min_y + chunk.height - 1;
        for x in 0..16 {
            for z in 0..16 {
                let bx = min_x + x;
                let bz = min_z + z;
                let starting_height = chunk.height_at(HeightmapKind::WorldSurface, x, z) + 1;
                let biome_y = if self.env.legacy_surface_biome_y {
                    0
                } else {
                    starting_height
                };
                let surface_biome = self.host.biome(bx, biome_y, bz);
                let biome_flags = self.env.biomes[surface_biome as usize].flags;
                if biome_flags & ir::biome_flags::ERODED_BADLANDS != 0 {
                    self.eroded_badlands(chunk, bx, bz, starting_height);
                }
                let height = chunk.height_at(HeightmapKind::WorldSurface, x, z) + 1;
                let gradient = if self.env.synth32 {
                    Self::gradients(chunk, x, z)
                } else {
                    [0, 0]
                };
                self.update_xz(bx, bz, gradient);
                let mut stone_above = 0;
                let mut water_height = i32::MIN;
                let mut next_ceiling = i32::MAX;
                let end_y = min_y;
                let mut y = height;
                while y >= end_y {
                    let old = chunk.get(bx, y, bz);
                    let f = self.flags(old);
                    if f & state_flags::AIR != 0 {
                        stone_above = 0;
                        water_height = i32::MIN;
                    } else if f & state_flags::FLUID != 0 {
                        if water_height == i32::MIN {
                            water_height = y + 1;
                        }
                    } else {
                        if next_ceiling >= y {
                            next_ceiling = WAY_BELOW_MIN_Y;
                            let mut look = y - 1;
                            while look >= end_y - 1 {
                                if !self.is_stone(chunk.get(bx, look, bz)) {
                                    next_ceiling = look + 1;
                                    break;
                                }
                                look -= 1;
                            }
                        }
                        stone_above += 1;
                        let stone_below = y - next_ceiling + 1;
                        self.update_y(stone_above, stone_below, water_height, y);
                        let applies = if self.env.synth32 {
                            y >= min_y && y <= max_y
                        } else {
                            old == self.env.default_block
                        };
                        if applies {
                            if let Some(state) = self.apply(chunk, self.program.root, bx, y, bz) {
                                self.set(chunk, bx, y, bz, state);
                            }
                        }
                    }
                    y -= 1;
                }
                if biome_flags & (ir::biome_flags::FROZEN_OCEAN | ir::biome_flags::DEEP_FROZEN_OCEAN) != 0 {
                    let min_surface = self.min_surface_level();
                    self.frozen_ocean(chunk, min_surface, surface_biome, bx, bz, starting_height);
                }
            }
        }
    }

    fn eroded_badlands(&mut self, chunk: &mut ChunkData, bx: i32, bz: i32, height: i32) {
        let surface = self.noise_value(surface_noise::BADLANDS_SURFACE, bx as f64, 0.0, bz as f64);
        let pillar = self.noise_value(surface_noise::BADLANDS_PILLAR, bx as f64 * 0.2, 0.0, bz as f64 * 0.2);
        let pillar = if self.env.synth32 {
            (pillar as f32 * 15.0f32) as f64
        } else {
            pillar * 15.0
        };
        let buffer = min_f64((surface * 8.25).abs(), pillar);
        if buffer <= 0.0 {
            return;
        }
        let roof = self.noise_value(
            surface_noise::BADLANDS_PILLAR_ROOF,
            bx as f64 * 0.75,
            0.0,
            bz as f64 * 0.75,
        );
        let floor = (roof * 1.5).abs();
        let top = 64.0 + min_f64(buffer * buffer * 2.5, (floor * 50.0).ceil() + 24.0);
        let start_y = top.floor() as i32;
        if height > start_y {
            return;
        }
        let mut y = start_y;
        while y >= chunk.min_y {
            let f = self.flags(chunk.get(bx, y, bz));
            if f & state_flags::DEFAULT_BLOCK != 0 {
                break;
            }
            if f & state_flags::WATER != 0 {
                return;
            }
            y -= 1;
        }
        let mut y = start_y;
        while y >= chunk.min_y && self.flags(chunk.get(bx, y, bz)) & state_flags::AIR != 0 {
            self.set(chunk, bx, y, bz, self.env.default_block);
            y -= 1;
        }
    }

    fn frozen_ocean(&mut self, chunk: &mut ChunkData, min_surface: i32, biome: u16, bx: i32, bz: i32, height: i32) {
        let surface = self.noise_value(surface_noise::ICEBERG_SURFACE, bx as f64, 0.0, bz as f64);
        let pillar = self.noise_value(surface_noise::ICEBERG_PILLAR, bx as f64 * 1.28, 0.0, bz as f64 * 1.28);
        let pillar = if self.env.synth32 {
            (pillar as f32 * 15.0f32) as f64
        } else {
            pillar * 15.0
        };
        let iceberg = min_f64((surface * 8.25).abs(), pillar);
        if iceberg <= 1.8 {
            return;
        }
        let roof = self.noise_value(
            surface_noise::ICEBERG_PILLAR_ROOF,
            bx as f64 * 1.17,
            0.0,
            bz as f64 * 1.17,
        );
        let roof = (roof * 1.5).abs();
        let sea = self.env.sea_level;
        let mut top = min_f64(iceberg * iceberg * 1.2, (roof * 40.0).ceil() + 14.0);
        let info = &self.env.biomes[biome as usize];
        if temperature(info, self.env.synth32, self.env.floor, bx, sea, bz, sea) > 0.1 {
            top -= 2.0;
        }
        let bottom;
        if self.env.synth32 {
            if top <= 2.0 {
                return;
            }
            bottom = sea as f64 - top - 7.0;
            top += sea as f64;
        } else if top > 2.0 {
            bottom = sea as f64 - top - 7.0;
            top += sea as f64;
        } else {
            top = 0.0;
            bottom = 0.0;
        }
        let extension_top = top;
        let mut random = self.program.noise_random.at(bx, 0, bz);
        let max_snow_depth = 2 + random.next_int_bound(4);
        let min_snow_height = sea + 18 + random.next_int_bound(10);
        let mut snow_depth = 0;
        let snow = self.env.states.special[Special::SnowBlock as usize];
        let ice = self.env.states.special[Special::PackedIce as usize];
        if snow == NONE || ice == NONE {
            return;
        }
        let mut y = height.max(top as i32 + 1);
        while y >= min_surface {
            let f = self.flags(chunk.get(bx, y, bz));
            let air_case = f & state_flags::AIR != 0 && y < extension_top as i32 && random.next_double() > 0.01;
            let place = air_case || {
                f & state_flags::WATER != 0
                    && y > bottom as i32
                    && y < sea
                    && (self.env.synth32 || bottom != 0.0)
                    && random.next_double() > 0.15
            };
            if place {
                if snow_depth <= max_snow_depth && y > min_snow_height {
                    self.set(chunk, bx, y, bz, snow as u16);
                    snow_depth += 1;
                } else {
                    self.set(chunk, bx, y, bz, ice as u16);
                }
            }
            y -= 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::NoiseCache;

    #[test]
    fn noise_cache_invalidation_preserves_bits() {
        let mut cache = NoiseCache::new(2);
        assert_eq!(cache.get(0), None);
        for value in [-0.0, f64::from_bits(0x7ff8_0000_0000_0042)] {
            cache.set(0, value);
            assert_eq!(cache.get(0).unwrap().to_bits(), value.to_bits());
            assert_eq!(cache.get(1), None);
            cache.clear();
            assert_eq!(cache.get(0), None);
        }
    }

    #[test]
    fn noise_cache_generation_wrap_drops_stale_entries() {
        let mut cache = NoiseCache::new(2);
        cache.set(0, 12.0);
        cache.generation = u32::MAX;
        cache.set(1, 34.0);
        cache.clear();
        assert_eq!(cache.generation, 1);
        assert_eq!(cache.get(0), None);
        assert_eq!(cache.get(1), None);
        cache.set(1, 56.0);
        assert_eq!(cache.get(1), Some(56.0));
    }
}
