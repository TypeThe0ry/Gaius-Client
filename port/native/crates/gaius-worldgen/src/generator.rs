//! A loaded generator: the compiled density program of one dimension plus
//! everything around it, and the per-chunk jobs (`createBiomes`, the terrain
//! fill of `buildTerrain` / `fillFromNoise`, and `buildSurface`).
//!
//! Every job is a pure function of the generator and its request, so jobs can
//! run in any worker in any order. Within a job the evaluation order follows
//! vanilla so sampler cache state and the biome tree's warm start behave alike.

use crate::aquifer::{disabled_substance, Aquifer, AquiferNoises, Picker};
use crate::arena::Arena;
use crate::beard::Beard;
use crate::biome::{zoom, BiomeGrid, RING_COLUMNS, STORED_COLUMNS};
use crate::chunk::{ChunkData, HeightmapKind};
use crate::climate::{quantize, RTree, Searcher};
use crate::df32::{Ctx32, Program32, Sid};
use crate::df64::{Batch, Ctx64, Did, Kind, Program64};
use crate::ir::{self, state_flags, BiomeInfo, Ir, IrError, Role, Special, Veins};
use crate::java::{floor_div, pack_xz};
use crate::noises::{instantiate, positional_factory};
use crate::surface::{SurfaceEnv, SurfaceHost, SurfaceProgram, SurfaceRun};
use crate::volume::Volume;
use gaius_noise::{PositionalRandomFactory, Semantics};
use std::collections::HashMap;

/// Default cap on pooled scratch elements kept between jobs (8 MB of `f32`).
pub const DEFAULT_ARENA_BUDGET: usize = 2 << 20;

#[derive(Clone, Debug)]
enum Source {
    MultiNoise(RTree),
    Fixed(u16),
    TheEnd {
        end: u16,
        highlands: u16,
        midlands: u16,
        islands: u16,
        barrens: u16,
    },
}

struct Roots32 {
    climate: [Sid; 6],
    final_density: Sid,
    preliminary: Option<Sid>,
    barrier: Option<Sid>,
    floodedness: Option<Sid>,
    spread: Option<Sid>,
    lava: Option<Sid>,
    exclusion: Option<Sid>,
    surface_level: Option<Sid>,
    /// Ore vein rule functions: IR node -> sampler.
    nodes: HashMap<u32, Sid>,
}

struct Roots64 {
    climate: [Did; 6],
    full_density: Did,
    preliminary: Did,
    barrier: Did,
    floodedness: Did,
    spread: Did,
    lava: Did,
    veins: Option<[Did; 3]>,
}

enum Kernel {
    V32 { program: Program32, roots: Roots32 },
    V64 { program: Program64, roots: Roots64 },
}

/// A terrain job.
#[derive(Clone, Debug, Default)]
pub struct TerrainRequest {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub beard: Beard,
    /// Also run the surface rules (vanilla 26.3 does both in `buildTerrain`).
    pub surface: bool,
    /// Also return the chunk's biomes.
    pub biomes: bool,
    /// The biomes the neighbouring chunks store in the grid's border quarts (local biome
    /// indices from [`Generator::ring_biomes`]); `None` computes them like the chunk's own.
    pub ring_biomes: Option<Vec<u16>>,
}

/// Why a ring of neighbour biomes was refused.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RingError {
    /// The ring does not have [`Generator::ring_len`] entries.
    Length(usize),
    /// A neighbour stores a biome outside the generator's biome table (another dimension's
    /// biome set with `/fillbiome`, say); the chunk takes the Java path.
    UnknownBiome(u32),
}

pub struct TerrainResult {
    pub chunk: ChunkData,
    /// Global biome ids, 64 per section (`(qy * 4 + qz) * 4 + qx`).
    pub biomes: Option<Vec<u32>>,
}

/// Approximate memory held by a generator.
#[derive(Clone, Copy, Debug, Default)]
pub struct Footprint {
    pub nodes: usize,
    pub noises: usize,
    pub states: usize,
    pub biomes: usize,
    pub pooled_bytes: usize,
}

pub struct Generator {
    pub semantics: Semantics,
    pub settings: ir::Settings,
    pub states: ir::States,
    pub biomes: Vec<BiomeInfo>,
    state_lookup: HashMap<u32, u16>,
    biome_lookup: HashMap<u32, u16>,
    biome_fallback: bool,
    source: Source,
    aquifer_random: PositionalRandomFactory,
    ore_random: PositionalRandomFactory,
    picker: Picker,
    surface: Option<SurfaceProgram>,
    veins: Option<Veins>,
    kernel: Kernel,
    arena32: Arena<f32>,
    biome_arena: Arena<f32>,
    arena64: Arena<f64>,
    pub arena_budget: usize,
}

impl Generator {
    pub fn load(bytes: &[u8]) -> Result<Generator, IrError> {
        let ir = Ir::decode(bytes)?;
        Generator::from_ir(&ir)
    }

    pub fn from_ir(ir: &Ir) -> Result<Generator, IrError> {
        let s = &ir.settings;
        let factory = positional_factory(s.seed, s.legacy_random);
        let noises = instantiate(ir.semantics, s.seed, &factory, &ir.noises)?;
        let root = |role: Role| ir.roots.require(role);
        let opt = |role: Role| ir.roots.get(role);
        let kernel = if ir.semantics.uses_sampler32() {
            let mut program = Program32::new(noises, ir.nodes.len());
            let mut c = |node: u32| program.compile_root(ir, &factory, node);
            let climate = [
                c(root(Role::Temperature)?)?,
                c(root(Role::Vegetation)?)?,
                c(root(Role::Continents)?)?,
                c(root(Role::Erosion)?)?,
                c(root(Role::Depth)?)?,
                c(root(Role::Ridges)?)?,
            ];
            let final_density = c(root(Role::FinalDensity)?)?;
            let mut c_opt = |role: Role| opt(role).map(|n| program.compile_root(ir, &factory, n)).transpose();
            let preliminary = c_opt(Role::PreliminarySurface)?;
            let barrier = c_opt(Role::AquiferBarrier)?;
            let floodedness = c_opt(Role::AquiferFloodedness)?;
            let spread = c_opt(Role::AquiferSpread)?;
            let lava = c_opt(Role::AquiferLava)?;
            let exclusion = c_opt(Role::AquiferExclusion)?;
            let surface_level = c_opt(Role::AquiferSurfaceLevel)?;
            if s.aquifers_enabled
                && [barrier, floodedness, spread, lava, exclusion, surface_level]
                    .iter()
                    .any(Option::is_none)
            {
                return Err(IrError::new("aquifers are enabled but an aquifer root is missing"));
            }
            let mut nodes = HashMap::new();
            if let Some(surface) = &ir.surface {
                for rule in &surface.rules {
                    if let ir::Rule::OreVein {
                        density,
                        richness,
                        filler_gap,
                        ..
                    } = rule
                    {
                        for &n in [density, richness, filler_gap] {
                            if let std::collections::hash_map::Entry::Vacant(e) = nodes.entry(n) {
                                e.insert(program.compile_root(ir, &factory, n)?);
                            }
                        }
                    }
                }
            }
            Kernel::V32 {
                program,
                roots: Roots32 {
                    climate,
                    final_density,
                    preliminary,
                    barrier,
                    floodedness,
                    spread,
                    lava,
                    exclusion,
                    surface_level,
                    nodes,
                },
            }
        } else {
            let mut program = Program64::new(noises, ir.nodes.len(), ir.semantics.floor_mode);
            let mut c = |node: u32| program.compile_root(ir, &factory, node);
            let climate = [
                c(root(Role::Temperature)?)?,
                c(root(Role::Vegetation)?)?,
                c(root(Role::Continents)?)?,
                c(root(Role::Erosion)?)?,
                c(root(Role::Depth)?)?,
                c(root(Role::Ridges)?)?,
            ];
            let final_density = c(root(Role::FinalDensity)?)?;
            let preliminary = c(root(Role::PreliminarySurface)?)?;
            let barrier = c(root(Role::AquiferBarrier)?)?;
            let floodedness = c(root(Role::AquiferFloodedness)?)?;
            let spread = c(root(Role::AquiferSpread)?)?;
            let lava = c(root(Role::AquiferLava)?)?;
            let veins = if s.ore_veins_enabled {
                Some([
                    c(root(Role::VeinToggle)?)?,
                    c(root(Role::VeinRidged)?)?,
                    c(root(Role::VeinGap)?)?,
                ])
            } else {
                None
            };
            let full_density = program.full_density(final_density);
            program.finish();
            Kernel::V64 {
                program,
                roots: Roots64 {
                    climate,
                    full_density,
                    preliminary,
                    barrier,
                    floodedness,
                    spread,
                    lava,
                    veins,
                },
            }
        };
        if !ir.semantics.uses_sampler32() && s.ore_veins_enabled && ir.veins.is_none() {
            return Err(IrError::new("ore veins are enabled but the VEIN section is missing"));
        }
        let source = match &ir.biomes.source {
            ir::BiomeSource::MultiNoise {
                children_per_node,
                entries,
            } => Source::MultiNoise(RTree::new(entries, *children_per_node as usize)),
            ir::BiomeSource::Fixed(b) => Source::Fixed(*b as u16),
            ir::BiomeSource::TheEnd {
                end,
                highlands,
                midlands,
                islands,
                barrens,
            } => Source::TheEnd {
                end: *end as u16,
                highlands: *highlands as u16,
                midlands: *midlands as u16,
                islands: *islands as u16,
                barrens: *barrens as u16,
            },
        };
        let state_lookup = ir
            .states
            .global_ids
            .iter()
            .enumerate()
            .map(|(i, &g)| (g, i as u16))
            .collect();
        let mut biome_lookup = HashMap::new();
        for (i, b) in ir.biomes.biomes.iter().enumerate() {
            biome_lookup.entry(b.global_id).or_insert(i as u16);
        }
        let surface = ir
            .surface
            .as_ref()
            .map(|def| SurfaceProgram::new(def, &ir.states, ir.biomes.biomes.len(), factory));
        Ok(Generator {
            semantics: ir.semantics,
            settings: ir.settings.clone(),
            states: ir.states.clone(),
            biomes: ir.biomes.biomes.clone(),
            state_lookup,
            biome_lookup,
            biome_fallback: false,
            source,
            aquifer_random: factory.from_hash_of("minecraft:aquifer").fork_positional(),
            ore_random: factory.from_hash_of("minecraft:ore").fork_positional(),
            picker: Picker::new(&ir.fluid),
            surface,
            veins: ir.veins,
            kernel,
            arena32: Arena::new(),
            biome_arena: Arena::new(),
            arena64: Arena::new(),
            arena_budget: DEFAULT_ARENA_BUDGET,
        })
    }

    /// Local state index of a global block state id.
    pub fn local_state(&self, global: u32) -> Option<u16> {
        self.state_lookup.get(&global).copied()
    }

    /// Entries of a ring of neighbour biomes: the 20 quart columns around the chunk, each
    /// over the level height.
    pub fn ring_len(&self) -> usize {
        RING_COLUMNS * (self.settings.level_height >> 2) as usize
    }

    pub fn stored_biomes_len(&self) -> usize {
        STORED_COLUMNS * (self.settings.level_height >> 2) as usize
    }

    pub fn biome_fallback_required(&self) -> bool {
        self.biome_fallback
    }

    pub fn biome_context_requires_vanilla(&self) -> bool {
        matches!(self.source, Source::MultiNoise(_))
    }

    /// Maps a ring of global biome ids (see [`BiomeGrid::seed_ring`] for the order) to local
    /// biome indices.
    pub fn ring_biomes(&self, ring: &[u32]) -> Result<Vec<u16>, RingError> {
        if ring.len() != self.ring_len() && ring.len() != self.stored_biomes_len() {
            return Err(RingError::Length(ring.len()));
        }
        let mut out = Vec::with_capacity(ring.len());
        let mut last = (u32::MAX, 0u16);
        for &global in ring {
            if global != last.0 {
                let local = *self.biome_lookup.get(&global).ok_or(RingError::UnknownBiome(global))?;
                last = (global, local);
            }
            out.push(last.1);
        }
        Ok(out)
    }

    fn seed_ring(&self, grid: &mut BiomeGrid, ring: Option<&[u16]>) {
        if let Some(ring) = ring {
            if ring.len() == self.ring_len() || ring.len() == self.stored_biomes_len() {
                grid.seed_ring(ring);
            }
        }
    }

    pub fn footprint(&self) -> Footprint {
        let (nodes, noises) = match &self.kernel {
            Kernel::V32 { program, .. } => (program.samplers.len(), program.noises.len()),
            Kernel::V64 { program, .. } => (program.functions.len(), program.noises.len()),
        };
        Footprint {
            nodes,
            noises,
            states: self.states.global_ids.len(),
            biomes: self.biomes.len(),
            pooled_bytes: (self.arena32.held() + self.biome_arena.held()) * 4 + self.arena64.held() * 8,
        }
    }

    fn special(&self, s: Special) -> u16 {
        self.states.special[s as usize] as u16
    }

    pub fn section_count(&self) -> usize {
        (self.settings.level_height >> 4) as usize
    }

    fn trim(&mut self) {
        self.arena32.trim(self.arena_budget);
        self.biome_arena.trim(self.arena_budget / 8);
        self.arena64.trim(self.arena_budget / 2);
    }

    /// Global biome ids of the chunk's sections (`createBiomes` / `fillBiomesFromNoise`).
    pub fn run_biomes(&mut self, chunk_x: i32, chunk_z: i32) -> Vec<u32> {
        self.biome_fallback = false;
        let s = &self.settings;
        let mut grid = BiomeGrid::new(chunk_x, chunk_z, s.level_min_y, s.level_height);
        match &self.kernel {
            Kernel::V32 { program, roots } => {
                let ctx = Ctx32::new(
                    program.cache_count,
                    true,
                    core::mem::take(&mut self.biome_arena),
                    Beard::default(),
                );
                let mut b = Biomes32 {
                    program,
                    roots,
                    source: &self.source,
                    searcher: searcher(&self.source),
                    ctx,
                };
                b.fill_chunk(&mut grid, chunk_x, chunk_z);
                self.biome_fallback = b.searcher.as_ref().is_some_and(Searcher::is_ambiguous);
                self.biome_arena = b.ctx.into_arena();
            }
            Kernel::V64 { program, roots } => {
                let mut ctx = chunk_ctx64(
                    program,
                    &self.settings,
                    chunk_x,
                    chunk_z,
                    Beard::default(),
                    core::mem::take(&mut self.arena64),
                );
                let mut b = Biomes64 {
                    program,
                    roots,
                    source: &self.source,
                    searcher: searcher(&self.source),
                };
                b.fill_chunk(&mut ctx, &mut grid, chunk_x, chunk_z);
                self.biome_fallback = b.searcher.as_ref().is_some_and(Searcher::is_ambiguous);
                self.arena64 = ctx.into_arena();
            }
        }
        let out = self.chunk_biomes(&grid, chunk_x, chunk_z);
        self.trim();
        out
    }

    /// The terrain step: density fill with aquifers (and pre-26.3 ore veins), then
    /// optionally the surface rules in the same sampler context, like vanilla.
    pub fn run_terrain(&mut self, request: &TerrainRequest) -> TerrainResult {
        self.biome_fallback = false;
        let s = &self.settings;
        let mut chunk = ChunkData::new(request.chunk_x, request.chunk_z, s.level_min_y, s.level_height);
        let mut grid = BiomeGrid::new(request.chunk_x, request.chunk_z, s.level_min_y, s.level_height);
        self.seed_ring(&mut grid, request.ring_biomes.as_deref());
        let want_biomes = request.biomes
            && request
                .ring_biomes
                .as_ref()
                .is_none_or(|biomes| biomes.len() != self.stored_biomes_len());
        match &self.kernel {
            Kernel::V32 { program, roots } => {
                let mut ctx = Ctx32::new(
                    program.cache_count,
                    true,
                    core::mem::take(&mut self.arena32),
                    request.beard.clone(),
                );
                let biome_ctx = Ctx32::new(
                    program.cache_count,
                    true,
                    core::mem::take(&mut self.biome_arena),
                    Beard::default(),
                );
                let mut biomes = Biomes32 {
                    program,
                    roots,
                    source: &self.source,
                    searcher: searcher(&self.source),
                    ctx: biome_ctx,
                };
                if want_biomes {
                    biomes.fill_chunk(&mut grid, request.chunk_x, request.chunk_z);
                }
                fill32(self_view(self), program, roots, &mut ctx, request, &mut chunk);
                if request.surface {
                    if let Some(surface) = &self.surface {
                        let host = Host32 {
                            program,
                            roots,
                            ctx: &mut ctx,
                            biomes: &mut biomes,
                            grid: &mut grid,
                            zoom_seed: self.settings.biome_zoom_seed,
                            preliminary: None,
                            chunk_x: request.chunk_x,
                            chunk_z: request.chunk_z,
                        };
                        let env = surface_env(self.semantics, &self.settings, &self.states, &self.biomes);
                        SurfaceRun::new(surface, env, host, &chunk, self.settings.noise_min_y).run(&mut chunk);
                    }
                }
                self.biome_fallback = biomes.searcher.as_ref().is_some_and(Searcher::is_ambiguous);
                self.biome_arena = biomes.ctx.into_arena();
                self.arena32 = ctx.into_arena();
            }
            Kernel::V64 { program, roots } => {
                let mut ctx = chunk_ctx64(
                    program,
                    &self.settings,
                    request.chunk_x,
                    request.chunk_z,
                    request.beard.clone(),
                    core::mem::take(&mut self.arena64),
                );
                let mut biomes = Biomes64 {
                    program,
                    roots,
                    source: &self.source,
                    searcher: searcher(&self.source),
                };
                if want_biomes {
                    biomes.fill_chunk(&mut ctx, &mut grid, request.chunk_x, request.chunk_z);
                }
                fill64(self_view(self), program, roots, &mut ctx, request, &mut chunk);
                if request.surface {
                    if let Some(surface) = &self.surface {
                        let host = Host64 {
                            program,
                            roots,
                            ctx: &mut ctx,
                            biomes: &mut biomes,
                            grid: &mut grid,
                            zoom_seed: self.settings.biome_zoom_seed,
                            surface_cache: HashMap::new(),
                            corner_origin: None,
                            corners: [0; 4],
                        };
                        let env = surface_env(self.semantics, &self.settings, &self.states, &self.biomes);
                        SurfaceRun::new(surface, env, host, &chunk, self.settings.noise_min_y).run(&mut chunk);
                    }
                }
                self.biome_fallback = biomes.searcher.as_ref().is_some_and(Searcher::is_ambiguous);
                self.arena64 = ctx.into_arena();
            }
        }
        let biomes = request
            .biomes
            .then(|| self.chunk_biomes(&grid, request.chunk_x, request.chunk_z));
        self.trim();
        TerrainResult { chunk, biomes }
    }

    /// `buildSurface` alone over a chunk the terrain job produced earlier.
    pub fn run_surface(&mut self, chunk: &mut ChunkData, beard: Beard) {
        self.run_surface_with(chunk, beard, None);
    }

    /// [`Generator::run_surface`] with the biomes the neighbouring chunks store (see
    /// [`TerrainRequest::ring_biomes`]).
    pub fn run_surface_with(&mut self, chunk: &mut ChunkData, beard: Beard, ring_biomes: Option<&[u16]>) {
        self.biome_fallback = false;
        if self.surface.is_none() {
            return;
        }
        let mut grid = BiomeGrid::new(
            chunk.chunk_x,
            chunk.chunk_z,
            self.settings.level_min_y,
            self.settings.level_height,
        );
        self.seed_ring(&mut grid, ring_biomes);
        let Some(surface) = &self.surface else {
            return;
        };
        let s = &self.settings;
        let env = surface_env(self.semantics, &self.settings, &self.states, &self.biomes);
        match &self.kernel {
            Kernel::V32 { program, roots } => {
                let mut ctx = Ctx32::new(program.cache_count, true, core::mem::take(&mut self.arena32), beard);
                let biome_ctx = Ctx32::new(
                    program.cache_count,
                    true,
                    core::mem::take(&mut self.biome_arena),
                    Beard::default(),
                );
                let mut biomes = Biomes32 {
                    program,
                    roots,
                    source: &self.source,
                    searcher: searcher(&self.source),
                    ctx: biome_ctx,
                };
                let host = Host32 {
                    program,
                    roots,
                    ctx: &mut ctx,
                    biomes: &mut biomes,
                    grid: &mut grid,
                    zoom_seed: s.biome_zoom_seed,
                    preliminary: None,
                    chunk_x: chunk.chunk_x,
                    chunk_z: chunk.chunk_z,
                };
                SurfaceRun::new(surface, env, host, chunk, s.noise_min_y).run(chunk);
                self.biome_fallback = biomes.searcher.as_ref().is_some_and(Searcher::is_ambiguous);
                self.biome_arena = biomes.ctx.into_arena();
                self.arena32 = ctx.into_arena();
            }
            Kernel::V64 { program, roots } => {
                let mut ctx = chunk_ctx64(
                    program,
                    s,
                    chunk.chunk_x,
                    chunk.chunk_z,
                    beard,
                    core::mem::take(&mut self.arena64),
                );
                let mut biomes = Biomes64 {
                    program,
                    roots,
                    source: &self.source,
                    searcher: searcher(&self.source),
                };
                let host = Host64 {
                    program,
                    roots,
                    ctx: &mut ctx,
                    biomes: &mut biomes,
                    grid: &mut grid,
                    zoom_seed: s.biome_zoom_seed,
                    surface_cache: HashMap::new(),
                    corner_origin: None,
                    corners: [0; 4],
                };
                SurfaceRun::new(surface, env, host, chunk, s.noise_min_y).run(chunk);
                self.biome_fallback = biomes.searcher.as_ref().is_some_and(Searcher::is_ambiguous);
                self.arena64 = ctx.into_arena();
            }
        }
        self.trim();
    }

    /// The 26.3 final density volume of a chunk, sampled in a fresh caching context (for
    /// comparisons with vanilla); `None` before 26.3.
    pub fn debug_final_density(&mut self, chunk_x: i32, chunk_z: i32) -> Option<Vec<f32>> {
        let Kernel::V32 { program, roots } = &self.kernel else {
            return None;
        };
        let s = &self.settings;
        let v = Volume::blocks([16, s.noise_height, 16], [chunk_x << 4, s.noise_min_y, chunk_z << 4]);
        let mut ctx = Ctx32::new(program.cache_count, true, Arena::new(), Beard::default());
        let mut out = vec![0.0f32; v.len()];
        program.sample_volume(&mut ctx, roots.final_density, &mut out, &v);
        Some(out)
    }

    /// The quantized 26.3 climate target of a quart, sampled like `createBiomes` (one quart
    /// column volume); for comparisons with vanilla.
    pub fn debug_climate_target(&mut self, qx: i32, qy: i32, qz: i32) -> Option<[i64; 6]> {
        let Kernel::V32 { program, roots } = &self.kernel else {
            return None;
        };
        let s = &self.settings;
        let (min_qy, size_y) = (s.level_min_y >> 2, s.level_height >> 2);
        let v = Volume::new([1, size_y, 1], [qx << 2, min_qy << 2, qz << 2], [4, 4, 4]);
        let mut ctx = Ctx32::new(program.cache_count, true, Arena::new(), Beard::default());
        let mut target = [0i64; 6];
        for (k, t) in target.iter_mut().enumerate() {
            let mut b = vec![0.0f32; v.len()];
            program.sample_volume(&mut ctx, roots.climate[k], &mut b, &v);
            *t = quantize(b[v.index(0, qy - min_qy, 0)]);
        }
        Some(target)
    }

    fn chunk_biomes(&self, grid: &BiomeGrid, chunk_x: i32, chunk_z: i32) -> Vec<u32> {
        let mut out = Vec::with_capacity(self.section_count() * 64);
        for section in 0..self.section_count() as i32 {
            let qy0 = grid.min_qy + section * 4;
            for qy in 0..4 {
                for qz in 0..4 {
                    for qx in 0..4 {
                        let b = grid
                            .index((chunk_x << 2) + qx, qy0 + qy, (chunk_z << 2) + qz)
                            .and_then(|i| grid.get_index(i))
                            .unwrap_or(0);
                        out.push(self.biomes[b as usize].global_id);
                    }
                }
            }
        }
        out
    }
}

/// The parts of the generator the fill loops read while the kernel is borrowed.
struct View<'a> {
    settings: &'a ir::Settings,
    states: &'a ir::States,
    picker: Picker,
    aquifer_random: PositionalRandomFactory,
    ore_random: PositionalRandomFactory,
    veins: Option<Veins>,
    lava: u16,
}

fn self_view(g: &Generator) -> View<'_> {
    View {
        settings: &g.settings,
        states: &g.states,
        picker: g.picker,
        aquifer_random: g.aquifer_random,
        ore_random: g.ore_random,
        veins: g.veins,
        lava: g.special(Special::Lava),
    }
}

fn searcher(source: &Source) -> Option<Searcher<'_>> {
    match source {
        Source::MultiNoise(tree) => Some(Searcher::new(tree)),
        _ => None,
    }
}

fn surface_env<'a>(
    semantics: Semantics,
    s: &ir::Settings,
    states: &'a ir::States,
    biomes: &'a [BiomeInfo],
) -> SurfaceEnv<'a> {
    SurfaceEnv {
        states,
        biomes,
        synth32: semantics.uses_synth32(),
        floor: semantics.floor_mode,
        sea_level: s.sea_level,
        default_block: s.default_block as u16,
        legacy_surface_biome_y: !semantics.uses_synth32() && s.legacy_random_source,
    }
}

fn chunk_ctx64(
    program: &Program64,
    s: &ir::Settings,
    chunk_x: i32,
    chunk_z: i32,
    beard: Beard,
    arena: Arena<f64>,
) -> Ctx64 {
    Ctx64::new(
        program,
        16 / s.cell_width,
        chunk_x << 4,
        chunk_z << 4,
        s.noise_min_y,
        s.noise_height,
        s.cell_width,
        s.cell_height,
        beard,
        arena,
    )
}

/// `section.setBlockState` + both heightmaps + fluid post-processing (`doFill`).
#[inline]
fn place(chunk: &mut ChunkData, states: &ir::States, x: i32, y: i32, z: i32, state: u16, schedule: bool) {
    if !chunk.inside(y) {
        return;
    }
    chunk.set_raw(states, x, y, z, state);
    chunk.update_heightmap(states, HeightmapKind::OceanFloor, x, y, z, state);
    chunk.update_heightmap(states, HeightmapKind::WorldSurface, x, y, z, state);
    if schedule && states.flags[state as usize] & state_flags::FLUID != 0 {
        chunk.mark_post_processing(x, y, z);
    }
}

/// 26.3 `NoiseBasedChunkGenerator.doFill` (with the NoiseChunk's aquifer).
fn fill32(
    v: View,
    program: &Program32,
    roots: &Roots32,
    ctx: &mut Ctx32,
    request: &TerrainRequest,
    chunk: &mut ChunkData,
) {
    let s = v.settings;
    if s.noise_height <= 0 {
        return;
    }
    let volume = Volume::blocks(
        [16, s.noise_height, 16],
        [request.chunk_x << 4, s.noise_min_y, request.chunk_z << 4],
    );
    let mut noises = Noises32 {
        program,
        roots,
        ctx,
        surface_cache: HashMap::new(),
    };
    let mut aquifer = s.aquifers_enabled.then(|| {
        Aquifer::new(
            v.aquifer_random,
            v.picker,
            v.lava,
            volume.min_x,
            volume.max_x(),
            volume.min_y,
            volume.max_y(),
            volume.min_z,
            volume.max_z(),
            |a, b, c, d| noises.max_surface(a, b, c, d),
        )
    });
    let n = volume.len();
    let mut density = noises.ctx.arena.take(n);
    program.sample_volume(noises.ctx, roots.final_density, &mut density[..n], &volume);
    let flags = &v.states.flags;
    let default_block = s.default_block as u16;
    for z in 0..volume.size_z {
        let bz = volume.block_z(z);
        for x in 0..volume.size_x {
            let bx = volume.block_x(x);
            for y in (0..volume.size_y).rev() {
                let by = volume.block_y(y);
                let d = density[volume.index(x, y, z)] as f64;
                let (state, schedule) = match aquifer.as_mut() {
                    Some(a) => {
                        let st = a.compute_substance(&mut noises, flags, bx, by, bz, d);
                        (st, a.should_schedule_fluid_update)
                    }
                    None => (disabled_substance(&v.picker, bx, by, bz, d), false),
                };
                let state = state.unwrap_or(default_block);
                if state != 0 {
                    place(chunk, v.states, bx, by, bz, state, schedule);
                }
            }
        }
    }
    noises.ctx.arena.give(density);
}

/// Pre-26.3 `NoiseBasedChunkGenerator.doFill` driving the NoiseChunk interpolation.
fn fill64(
    v: View,
    program: &Program64,
    roots: &Roots64,
    ctx: &mut Ctx64,
    request: &TerrainRequest,
    chunk: &mut ChunkData,
) {
    let s = v.settings;
    let cell_min_y = floor_div(s.noise_min_y, s.cell_height);
    let cell_count_y = floor_div(s.noise_height, s.cell_height);
    if cell_count_y <= 0 {
        return;
    }
    let min_x = request.chunk_x << 4;
    let min_z = request.chunk_z << 4;
    let mut noises = Noises64 {
        program,
        roots,
        ctx,
        surface_cache: HashMap::new(),
    };
    let mut aquifer = s.aquifers_enabled.then(|| {
        Aquifer::new(
            v.aquifer_random,
            v.picker,
            v.lava,
            min_x,
            min_x + 15,
            s.noise_min_y,
            s.noise_min_y + s.noise_height,
            min_z,
            min_z + 15,
            |a, b, c, d| noises.max_surface(a, b, c, d),
        )
    });
    let flags = &v.states.flags;
    let default_block = s.default_block as u16;
    let (cw, ch) = (s.cell_width, s.cell_height);
    let cell_count_xz = 16 / cw;
    let mut batch = Batch::new(Kind::Direct);
    program.begin_interpolation(noises.ctx, &mut batch);
    for cell_x in 0..cell_count_xz {
        program.advance_cell_x(noises.ctx, cell_x, &mut batch);
        for cell_z in 0..cell_count_xz {
            for cell_y in (0..cell_count_y).rev() {
                program.select_cell_yz(noises.ctx, cell_y, cell_z, &mut batch);
                for y_in in (0..ch).rev() {
                    let pos_y = (cell_min_y + cell_y) * ch + y_in;
                    for x_in in 0..cw {
                        let pos_x = min_x + cell_x * cw + x_in;
                        for z_in in 0..cw {
                            let pos_z = min_z + cell_z * cw + z_in;
                            let density =
                                program.compute(noises.ctx, roots.full_density, pos_x, pos_y, pos_z, Kind::Block);
                            let (mut state, schedule) = match aquifer.as_mut() {
                                Some(a) => {
                                    let st = a.compute_substance(&mut noises, flags, pos_x, pos_y, pos_z, density);
                                    (st, a.should_schedule_fluid_update)
                                }
                                None => (disabled_substance(&v.picker, pos_x, pos_y, pos_z, density), false),
                            };
                            if state.is_none() {
                                if let (Some(veins), Some(vr)) = (&v.veins, roots.veins) {
                                    state =
                                        ore_vein64(program, noises.ctx, veins, vr, &v.ore_random, pos_x, pos_y, pos_z);
                                }
                            }
                            let state = state.unwrap_or(default_block);
                            if state != 0 {
                                place(chunk, v.states, pos_x, pos_y, pos_z, state, schedule);
                            }
                        }
                    }
                }
            }
        }
        program.swap_slices(noises.ctx);
    }
}

/// `OreVeinifier` (1.21.11 / 26.2) for one block, evaluated on the NoiseChunk.
#[allow(clippy::too_many_arguments)]
fn ore_vein64(
    program: &Program64,
    ctx: &mut Ctx64,
    v: &Veins,
    roots: [Did; 3],
    random: &PositionalRandomFactory,
    x: i32,
    y: i32,
    z: i32,
) -> Option<u16> {
    let toggle = program.compute(ctx, roots[0], x, y, z, Kind::Block);
    let copper = toggle > 0.0;
    let (min_y, max_y) = if copper {
        (v.copper_y[0], v.copper_y[1])
    } else {
        (v.iron_y[0], v.iron_y[1])
    };
    let ridged = toggle.abs();
    let from_top = max_y - y;
    let from_bottom = y - min_y;
    if from_bottom < 0 || from_top < 0 {
        return None;
    }
    let edge = from_top.min(from_bottom);
    let roundoff = gaius_noise::mth::clamped_map(edge as f64, 0.0, 20.0, -0.2, 0.0);
    if ridged + roundoff < 0.4f32 as f64 {
        return None;
    }
    let mut r = random.at(x, y, z);
    if r.next_float() > 0.7 {
        return None;
    }
    if program.compute(ctx, roots[1], x, y, z, Kind::Block) >= 0.0 {
        return None;
    }
    let richness = gaius_noise::mth::clamped_map(ridged, 0.4f32 as f64, 0.6f32 as f64, 0.1f32 as f64, 0.3f32 as f64);
    if (r.next_float() as f64) < richness && program.compute(ctx, roots[2], x, y, z, Kind::Block) > -0.3f32 as f64 {
        Some(if r.next_float() < 0.02 {
            if copper {
                v.raw_copper as u16
            } else {
                v.raw_iron as u16
            }
        } else if copper {
            v.copper_ore as u16
        } else {
            v.iron_ore as u16
        })
    } else {
        Some(if copper { v.granite as u16 } else { v.tuff as u16 })
    }
}

// ---- aquifer noise hosts ----

struct Noises32<'a> {
    program: &'a Program32,
    roots: &'a Roots32,
    ctx: &'a mut Ctx32,
    surface_cache: HashMap<i64, i32>,
}

impl Noises32<'_> {
    fn value(&mut self, s: Option<Sid>, x: i32, y: i32, z: i32) -> f32 {
        self.program.sample_value(self.ctx, s.expect("aquifer root"), x, y, z)
    }

    /// `NoiseBasedAquifer.maxSurfaceLevel`.
    fn max_surface(&mut self, min_x: i32, min_z: i32, max_x: i32, max_z: i32) -> i32 {
        let (qx0, qx1, qz0, qz1) = (min_x >> 2, max_x >> 2, min_z >> 2, max_z >> 2);
        let v = Volume::new([qx1 - qx0 + 1, 1, qz1 - qz0 + 1], [qx0 << 2, 0, qz0 << 2], [4, 1, 4]);
        let mut b = self.ctx.arena.take(v.len());
        let s = self.roots.surface_level.expect("aquifer surface level");
        self.program.sample_volume(self.ctx, s, &mut b[..v.len()], &v);
        let mut max = i32::MIN;
        for z in 0..v.size_z {
            for x in 0..v.size_x {
                let level = (b[v.index(x, 0, z)] as f64).floor() as i32;
                self.surface_cache.insert(pack_xz(v.block_x(x), v.block_z(z)), level);
                max = max.max(level);
            }
        }
        self.ctx.arena.give(b);
        max
    }
}

impl AquiferNoises for Noises32<'_> {
    fn barrier(&mut self, x: i32, y: i32, z: i32) -> f64 {
        self.value(self.roots.barrier, x, y, z) as f64
    }

    fn floodedness(&mut self, x: i32, y: i32, z: i32) -> f64 {
        self.value(self.roots.floodedness, x, y, z) as f64
    }

    fn spread(&mut self, cx: i32, cy: i32, cz: i32) -> f64 {
        (self.value(self.roots.spread, cx, cy, cz) * 10.0f32) as f64
    }

    fn lava(&mut self, cx: i32, cy: i32, cz: i32) -> f64 {
        self.value(self.roots.lava, cx, cy, cz) as f64
    }

    fn excluded(&mut self, x: i32, y: i32, z: i32) -> bool {
        self.value(self.roots.exclusion, x, y, z) as f64 > 0.0
    }

    fn surface_level(&mut self, x: i32, z: i32) -> i32 {
        let (qx, qz) = ((x >> 2) << 2, (z >> 2) << 2);
        let key = pack_xz(qx, qz);
        if let Some(&v) = self.surface_cache.get(&key) {
            return v;
        }
        let v = (self.value(self.roots.surface_level, qx, 0, qz) as f64).floor() as i32;
        self.surface_cache.insert(key, v);
        v
    }
}

struct Noises64<'a> {
    program: &'a Program64,
    roots: &'a Roots64,
    ctx: &'a mut Ctx64,
    surface_cache: HashMap<i64, i32>,
}

/// `NoiseChunk.preliminarySurfaceLevel(x, z)` with its column cache.
fn preliminary64(
    program: &Program64,
    root: Did,
    ctx: &mut Ctx64,
    cache: &mut HashMap<i64, i32>,
    x: i32,
    z: i32,
) -> i32 {
    let (qx, qz) = ((x >> 2) << 2, (z >> 2) << 2);
    let key = pack_xz(qx, qz);
    if let Some(&v) = cache.get(&key) {
        return v;
    }
    let v = program.compute(ctx, root, qx, 0, qz, Kind::Direct).floor() as i32;
    cache.insert(key, v);
    v
}

impl Noises64<'_> {
    /// `NoiseChunk.maxPreliminarySurfaceLevel`.
    fn max_surface(&mut self, min_x: i32, min_z: i32, max_x: i32, max_z: i32) -> i32 {
        let mut max = i32::MIN;
        let mut z = min_z;
        while z <= max_z {
            let mut x = min_x;
            while x <= max_x {
                let level = preliminary64(
                    self.program,
                    self.roots.preliminary,
                    self.ctx,
                    &mut self.surface_cache,
                    x,
                    z,
                );
                max = max.max(level);
                x += 4;
            }
            z += 4;
        }
        max
    }
}

impl AquiferNoises for Noises64<'_> {
    fn barrier(&mut self, x: i32, y: i32, z: i32) -> f64 {
        self.program.compute(self.ctx, self.roots.barrier, x, y, z, Kind::Block)
    }

    fn floodedness(&mut self, x: i32, y: i32, z: i32) -> f64 {
        self.program
            .compute(self.ctx, self.roots.floodedness, x, y, z, Kind::Direct)
    }

    fn spread(&mut self, cx: i32, cy: i32, cz: i32) -> f64 {
        self.program
            .compute(self.ctx, self.roots.spread, cx, cy, cz, Kind::Direct)
            * 10.0
    }

    fn lava(&mut self, cx: i32, cy: i32, cz: i32) -> f64 {
        self.program
            .compute(self.ctx, self.roots.lava, cx, cy, cz, Kind::Direct)
    }

    fn excluded(&mut self, x: i32, y: i32, z: i32) -> bool {
        let erosion = self
            .program
            .compute(self.ctx, self.roots.climate[3], x, y, z, Kind::Direct);
        erosion < -0.225f32 as f64
            && self
                .program
                .compute(self.ctx, self.roots.climate[4], x, y, z, Kind::Direct)
                > 0.9f32 as f64
    }

    fn surface_level(&mut self, x: i32, z: i32) -> i32 {
        preliminary64(
            self.program,
            self.roots.preliminary,
            self.ctx,
            &mut self.surface_cache,
            x,
            z,
        )
    }
}

// ---- biomes ----

/// `TheEndBiomeSource.getNoiseBiome`.
fn end_biome(source: &Source, qx: i32, qy: i32, qz: i32, mut erosion: impl FnMut(i32, i32, i32) -> f64) -> u16 {
    let Source::TheEnd {
        end,
        highlands,
        midlands,
        islands,
        barrens,
    } = *source
    else {
        unreachable!("only the end source samples erosion")
    };
    let (bx, by, bz) = (qx << 2, qy << 2, qz << 2);
    let (cx, cz) = ((bx >> 4) as i64, (bz >> 4) as i64);
    if cx * cx + cz * cz <= 4096 {
        return end;
    }
    let wx = ((bx >> 4) * 2 + 1) * 8;
    let wz = ((bz >> 4) * 2 + 1) * 8;
    let h = erosion(wx, by, wz);
    if h > 0.25 {
        highlands
    } else if h >= -0.0625 {
        midlands
    } else if h < -0.21875 {
        islands
    } else {
        barrens
    }
}

/// 26.3 biomes: climate volumes in their own sampler context (`createBiomes`).
struct Biomes32<'a> {
    program: &'a Program32,
    roots: &'a Roots32,
    source: &'a Source,
    searcher: Option<Searcher<'a>>,
    ctx: Ctx32,
}

impl Biomes32<'_> {
    /// Fills a quart box of the grid; multi-noise boxes are one climate volume
    /// (`createResolverForChunk`), searched in `fillBiomesFromNoise` order.
    fn fill_box(&mut self, grid: &mut BiomeGrid, qx0: i32, qz0: i32, nx: i32, nz: i32) {
        let (min_qy, size_y) = (grid.min_qy, grid.size_y);
        match self.source {
            Source::Fixed(b) => {
                for qx in qx0..qx0 + nx {
                    for qz in qz0..qz0 + nz {
                        for qy in min_qy..min_qy + size_y {
                            if let Some(i) = grid.index(qx, qy, qz) {
                                grid.set_index(i, *b);
                            }
                        }
                    }
                }
            }
            Source::TheEnd { .. } => {
                for qy in min_qy..min_qy + size_y {
                    for qx in qx0..qx0 + nx {
                        for qz in qz0..qz0 + nz {
                            let (program, ctx, root) = (self.program, &mut self.ctx, self.roots.climate[3]);
                            let b = end_biome(self.source, qx, qy, qz, |x, y, z| {
                                program.sample_value(ctx, root, x, y, z) as f64
                            });
                            if let Some(i) = grid.index(qx, qy, qz) {
                                grid.set_index(i, b);
                            }
                        }
                    }
                }
            }
            Source::MultiNoise(_) => {
                let v = Volume::new([nx, size_y, nz], [qx0 << 2, min_qy << 2, qz0 << 2], [4, 4, 4]);
                let n = v.len();
                let mut buffers: [Vec<f32>; 6] = core::array::from_fn(|_| Vec::new());
                for (k, b) in buffers.iter_mut().enumerate() {
                    let mut buf = self.ctx.arena.take(n);
                    self.program
                        .sample_volume(&mut self.ctx, self.roots.climate[k], &mut buf[..n], &v);
                    *b = buf;
                }
                let searcher = self.searcher.as_mut().expect("multi noise searcher");
                // Section by section, then x, y, z like LevelChunkSection.fillBiomesFromNoise.
                for section_y in (0..size_y).step_by(4) {
                    for x in 0..nx {
                        for y in section_y..(section_y + 4).min(size_y) {
                            for z in 0..nz {
                                let i = v.index(x, y, z);
                                let target = core::array::from_fn(|k| quantize(buffers[k][i]));
                                let b = searcher.find(target) as u16;
                                if let Some(gi) = grid.index(qx0 + x, min_qy + y, qz0 + z) {
                                    grid.set_index(gi, b);
                                }
                            }
                        }
                    }
                }
                for b in buffers {
                    self.ctx.arena.give(b);
                }
            }
        }
    }

    fn fill_chunk(&mut self, grid: &mut BiomeGrid, chunk_x: i32, chunk_z: i32) {
        self.fill_box(grid, chunk_x << 2, chunk_z << 2, 4, 4);
    }

    /// `biomeManager.getBiome(x, y, z)`; neighbor quarts are filled a column at a time
    /// (per-point climate values do not depend on the volume they are sampled in).
    fn biome_at(&mut self, grid: &mut BiomeGrid, zoom_seed: i64, x: i32, y: i32, z: i32) -> u16 {
        let q = zoom(zoom_seed, x, y, z);
        let Some(i) = grid.index(q[0], q[1], q[2]) else {
            return 0;
        };
        if let Some(b) = grid.get_index(i) {
            return b;
        }
        self.fill_box(grid, q[0], q[2], 1, 1);
        grid.get_index(i).unwrap_or(0)
    }
}

/// Pre-26.3 biomes: the climate functions on the chunk's NoiseChunk, point by point.
struct Biomes64<'a> {
    program: &'a Program64,
    roots: &'a Roots64,
    source: &'a Source,
    searcher: Option<Searcher<'a>>,
}

impl Biomes64<'_> {
    fn quart(&mut self, ctx: &mut Ctx64, qx: i32, qy: i32, qz: i32) -> u16 {
        match self.source {
            Source::Fixed(b) => *b,
            Source::TheEnd { .. } => {
                let (program, root) = (self.program, self.roots.climate[3]);
                end_biome(self.source, qx, qy, qz, |x, y, z| {
                    program.compute(ctx, root, x, y, z, Kind::Direct)
                })
            }
            Source::MultiNoise(_) => {
                let (x, y, z) = (qx << 2, qy << 2, qz << 2);
                let target = core::array::from_fn(|k| {
                    quantize(self.program.compute(ctx, self.roots.climate[k], x, y, z, Kind::Direct) as f32)
                });
                self.searcher.as_mut().expect("multi noise searcher").find(target) as u16
            }
        }
    }

    fn fill_chunk(&mut self, ctx: &mut Ctx64, grid: &mut BiomeGrid, chunk_x: i32, chunk_z: i32) {
        let (min_qx, min_qz) = (chunk_x << 2, chunk_z << 2);
        for section_y in (0..grid.size_y).step_by(4) {
            for qx in 0..4 {
                for qy in section_y..(section_y + 4).min(grid.size_y) {
                    for qz in 0..4 {
                        let b = self.quart(ctx, min_qx + qx, grid.min_qy + qy, min_qz + qz);
                        if let Some(i) = grid.index(min_qx + qx, grid.min_qy + qy, min_qz + qz) {
                            grid.set_index(i, b);
                        }
                    }
                }
            }
        }
    }

    fn biome_at(&mut self, ctx: &mut Ctx64, grid: &mut BiomeGrid, zoom_seed: i64, x: i32, y: i32, z: i32) -> u16 {
        let q = zoom(zoom_seed, x, y, z);
        let Some(i) = grid.index(q[0], q[1], q[2]) else {
            return 0;
        };
        if let Some(b) = grid.get_index(i) {
            return b;
        }
        let qy = grid.clamp_y(q[1]);
        let b = self.quart(ctx, q[0], qy, q[2]);
        grid.set_index(i, b);
        b
    }
}

// ---- surface hosts ----

struct Host32<'a, 'b> {
    program: &'a Program32,
    roots: &'a Roots32,
    ctx: &'b mut Ctx32,
    biomes: &'b mut Biomes32<'a>,
    grid: &'b mut BiomeGrid,
    zoom_seed: i64,
    preliminary: Option<Vec<f32>>,
    chunk_x: i32,
    chunk_z: i32,
}

impl SurfaceHost for Host32<'_, '_> {
    fn noise(&self, noise: u32, x: f64, y: f64, z: f64) -> f64 {
        self.program.noises[noise as usize].get32(x, y, z) as f64
    }

    fn biome(&mut self, x: i32, y: i32, z: i32) -> u16 {
        self.biomes.biome_at(self.grid, self.zoom_seed, x, y, z)
    }

    /// `MaterialRuleContext.getMinSurfaceLevel`'s preliminary surface buffer.
    fn preliminary_surface(&mut self, x: i32, z: i32) -> i32 {
        let v = Volume::blocks([16, 1, 16], [self.chunk_x << 4, 0, self.chunk_z << 4]);
        let value = match (v.index_of_block(x, 0, z), self.roots.preliminary) {
            (_, None) => 0.0,
            (Some(i), Some(s)) => {
                if self.preliminary.is_none() {
                    let mut b = vec![0.0f32; v.len()];
                    self.program.sample_volume(self.ctx, s, &mut b, &v);
                    self.preliminary = Some(b);
                }
                self.preliminary.as_ref().expect("preliminary buffer")[i]
            }
            (None, Some(s)) => self.program.sample_value(self.ctx, s, x, 0, z),
        };
        (value as f64).floor() as i32
    }

    fn prefill(&mut self, node: u32, volume: &Volume) -> Vec<f32> {
        let mut b = vec![0.0f32; volume.len()];
        if let Some(&s) = self.roots.nodes.get(&node) {
            self.program.sample_volume(self.ctx, s, &mut b, volume);
        }
        b
    }

    fn value(&mut self, node: u32, x: i32, y: i32, z: i32) -> f32 {
        match self.roots.nodes.get(&node) {
            Some(&s) => self.program.sample_value(self.ctx, s, x, y, z),
            None => 0.0,
        }
    }
}

struct Host64<'a, 'b> {
    program: &'a Program64,
    roots: &'a Roots64,
    ctx: &'b mut Ctx64,
    biomes: &'b mut Biomes64<'a>,
    grid: &'b mut BiomeGrid,
    zoom_seed: i64,
    surface_cache: HashMap<i64, i32>,
    corner_origin: Option<(i32, i32)>,
    corners: [i32; 4],
}

impl SurfaceHost for Host64<'_, '_> {
    fn noise(&self, noise: u32, x: f64, y: f64, z: f64) -> f64 {
        self.program.noises[noise as usize].get64(x, y, z)
    }

    fn biome(&mut self, x: i32, y: i32, z: i32) -> u16 {
        self.biomes.biome_at(self.ctx, self.grid, self.zoom_seed, x, y, z)
    }

    /// `SurfaceRules.Context.getMinSurfaceLevel`: `Mth.floor(Mth.lerp2(...))` over the
    /// preliminary surface at the corners of the 16-block surface cell.
    fn preliminary_surface(&mut self, x: i32, z: i32) -> i32 {
        let (cx, cz) = (x >> 4, z >> 4);
        if self.corner_origin != Some((cx, cz)) {
            self.corner_origin = Some((cx, cz));
            let (p, r) = (self.program, self.roots.preliminary);
            let mut at = |bx: i32, bz: i32| preliminary64(p, r, self.ctx, &mut self.surface_cache, bx, bz);
            self.corners = [
                at(cx << 4, cz << 4),
                at((cx + 1) << 4, cz << 4),
                at(cx << 4, (cz + 1) << 4),
                at((cx + 1) << 4, (cz + 1) << 4),
            ];
        }
        let c = self.corners.map(|v| v as f32);
        let level =
            gaius_noise::mth::float::lerp2((x & 15) as f32 / 16.0, (z & 15) as f32 / 16.0, c[0], c[1], c[2], c[3]);
        (level as f64).floor() as i32
    }
}
