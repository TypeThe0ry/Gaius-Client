//! 1.21.11 / 26.2 density functions: `DensityFunction.compute` and
//! `fillArray` over the router as `NoiseChunk` wraps it.
//!
//! The NoiseChunk markers keep their vanilla meaning:
//! - `interpolated`: corner values per cell; inside a cell fill
//!   (`fillingCell`) the value is `Mth.lerp3` of the in-cell fractions, during
//!   the per-block loop it is the `updateForY/X/Z` chain of lerps, elsewhere the
//!   wrapped function itself;
//! - `flat_cache`: the 5x5 quart table of the chunk, sampled at `y = 0`, and
//!   the wrapped function outside the table;
//! - `cache_all_in_cell`: the values of the current cell;
//! - `cache_2d`, `cache_once`: transparent (they only skip recomputation).
//!
//! Positions are evaluated in batches (one interpolator column, one cell)
//! through [`Batch`], so the hot loops run over flat arrays.

mod spline;

use crate::arena::Arena;
use crate::beard::Beard;
use crate::ir::{self, Binary, IrError, Marker, Op, Unary};
use crate::java::{max_f64, min_f64};
use crate::noises::NoiseInst;
use gaius_noise::mth::FloorMode;
use gaius_noise::mth::{clamp, clamped_map, lerp};
use gaius_noise::{synth64, PositionalRandomFactory, RandomKind, RandomSource};
pub use spline::SplineFn;

pub type Did = u32;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MapType {
    Abs,
    Square,
    Cube,
    HalfNegative,
    QuarterNegative,
    Invert,
    Squeeze,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Ap2Type {
    Add,
    Mul,
    Min,
    Max,
}

#[derive(Clone, Debug)]
pub enum D {
    Const(f64),
    Noise {
        noise: u32,
        xz: f64,
        y: f64,
    },
    Shifted {
        sx: Did,
        sy: Did,
        sz: Did,
        noise: u32,
        xz: f64,
        y: f64,
    },
    ShiftA(u32),
    ShiftB(u32),
    Shift(u32),
    ScaledNoise {
        noise: u32,
        scale: Did,
    },
    Blended(u32),
    EndIslands,
    YClamped {
        from_y: i32,
        to_y: i32,
        from_value: f64,
        to_value: f64,
    },
    Mapped(MapType, Did),
    Clamp(Did, f64, f64),
    MulOrAdd {
        add: bool,
        input: Did,
        argument: f64,
    },
    Ap2 {
        t: Ap2Type,
        a: Did,
        b: Did,
        b_min: f64,
        b_max: f64,
    },
    RangeChoice {
        input: Did,
        min: f64,
        max: f64,
        in_range: Did,
        out_of_range: Did,
    },
    IntervalSelect {
        input: Did,
        thresholds: Box<[f64]>,
        functions: Box<[Did]>,
    },
    Spline(u32),
    FindTop {
        density: Did,
        upper: Did,
        lower: i32,
        cell: i32,
    },
    Interpolated {
        input: Did,
        slot: u32,
    },
    FlatCache {
        input: Did,
        slot: u32,
    },
    CacheAllInCell {
        input: Did,
        slot: u32,
    },
    Beardifier,
}

/// How the current evaluation reaches the function (`FunctionContext` kind).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// A `SinglePointContext`, or the NoiseChunk outside a cell fill and the block loop.
    Direct,
    /// The NoiseChunk while filling a cell (`fillingCell`).
    Cell,
    /// The NoiseChunk in the per-block loop (interpolators hold `updateForZ` values).
    Block,
}

pub struct Program64 {
    pub functions: Vec<D>,
    pub noises: Vec<NoiseInst>,
    pub splines: Vec<SplineFn>,
    pub blended: Vec<synth64::BlendedNoise>,
    pub interpolated_slots: u32,
    pub flat_slots: u32,
    pub cell_slots: u32,
    end_islands: Option<synth64::SimplexNoise>,
    floor: FloorMode,
    compiled: Vec<Option<Did>>,
    /// `NoiseChunk.interpolators` in slot order: (slot, wrapped function).
    interpolators: Vec<(u32, Did)>,
    /// `NoiseChunk.cellCaches` in slot order: (slot, filler function).
    cell_fillers: Vec<(u32, Did)>,
}

/// Per-interpolator state: the two slices and the selected cell's corners.
#[derive(Clone, Debug, Default)]
struct Interp {
    slice0: Vec<f64>,
    slice1: Vec<f64>,
    /// noise000, 100, 010, 110, 001, 101, 011, 111.
    corners: [f64; 8],
}

/// The NoiseChunk of one job: cell geometry, marker state and the beardifier.
pub struct Ctx64 {
    pub arena: Arena<f64>,
    pub beard: Beard,
    pub cell_width: i32,
    pub cell_height: i32,
    pub cell_count_xz: i32,
    pub cell_count_y: i32,
    pub cell_noise_min_y: i32,
    pub first_cell_x: i32,
    pub first_cell_z: i32,
    first_noise_x: i32,
    first_noise_z: i32,
    flat_size: i32,
    flat: Vec<Vec<Option<f64>>>,
    interps: Vec<Interp>,
    cells: Vec<Vec<f64>>,
    /// `cellStartBlockX/Y/Z` of the cell being filled or iterated.
    pub cell_start: [i32; 3],
}

impl Ctx64 {
    /// `new NoiseChunk(cellCountXZ, ..., chunkMinBlockX, chunkMinBlockZ, noiseSettings, ...)`.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        program: &Program64,
        cell_count_xz: i32,
        chunk_min_x: i32,
        chunk_min_z: i32,
        noise_min_y: i32,
        noise_height: i32,
        cell_width: i32,
        cell_height: i32,
        beard: Beard,
        arena: Arena<f64>,
    ) -> Ctx64 {
        let cell_count_y = crate::java::floor_div(noise_height, cell_height);
        let slice_len = ((cell_count_xz + 1) * (cell_count_y + 1)).max(0) as usize;
        let noise_size_xz = (cell_count_xz * cell_width) >> 2;
        let flat_size = noise_size_xz + 1;
        Ctx64 {
            arena,
            beard,
            cell_width,
            cell_height,
            cell_count_xz,
            cell_count_y,
            cell_noise_min_y: crate::java::floor_div(noise_min_y, cell_height),
            first_cell_x: crate::java::floor_div(chunk_min_x, cell_width),
            first_cell_z: crate::java::floor_div(chunk_min_z, cell_width),
            first_noise_x: chunk_min_x >> 2,
            first_noise_z: chunk_min_z >> 2,
            flat_size,
            flat: (0..program.flat_slots)
                .map(|_| vec![None; (flat_size * flat_size) as usize])
                .collect(),
            interps: (0..program.interpolated_slots)
                .map(|_| Interp {
                    slice0: vec![0.0; slice_len],
                    slice1: vec![0.0; slice_len],
                    corners: [0.0; 8],
                })
                .collect(),
            cells: (0..program.cell_slots)
                .map(|_| vec![0.0; (cell_width * cell_width * cell_height) as usize])
                .collect(),
            cell_start: [0; 3],
        }
    }

    pub fn into_arena(self) -> Arena<f64> {
        self.arena
    }
}

/// Positions of one `fillArray` call, in the provider's index order.
pub struct Batch {
    pub kind: Kind,
    pub x: Vec<i32>,
    pub y: Vec<i32>,
    pub z: Vec<i32>,
}

impl Batch {
    pub fn new(kind: Kind) -> Batch {
        Batch {
            kind,
            x: Vec::new(),
            y: Vec::new(),
            z: Vec::new(),
        }
    }

    pub fn len(&self) -> usize {
        self.x.len()
    }

    pub fn is_empty(&self) -> bool {
        self.x.is_empty()
    }

    pub fn clear(&mut self) {
        self.x.clear();
        self.y.clear();
        self.z.clear();
    }

    pub fn push(&mut self, x: i32, y: i32, z: i32) {
        self.x.push(x);
        self.y.push(y);
        self.z.push(z);
    }
}

struct Compiler<'a> {
    nodes: &'a [ir::Node],
    splines: &'a [ir::SplineDef],
    seed: i64,
    legacy_random_source: bool,
    factory: PositionalRandomFactory,
}

fn map_transform(t: MapType, v: f64) -> f64 {
    match t {
        MapType::Abs => v.abs(),
        MapType::Square => v * v,
        MapType::Cube => v * v * v,
        MapType::HalfNegative => {
            if v > 0.0 {
                v
            } else {
                v * 0.5
            }
        }
        MapType::QuarterNegative => {
            if v > 0.0 {
                v
            } else {
                v * 0.25
            }
        }
        MapType::Invert => 1.0 / v,
        MapType::Squeeze => {
            let c = clamp(v, -1.0, 1.0);
            c / 2.0 - c * c * c / 24.0
        }
    }
}

impl Program64 {
    pub fn new(noises: Vec<NoiseInst>, node_count: usize, floor: FloorMode) -> Program64 {
        Program64 {
            functions: Vec::new(),
            noises,
            splines: Vec::new(),
            blended: Vec::new(),
            interpolated_slots: 0,
            flat_slots: 0,
            cell_slots: 0,
            end_islands: None,
            floor,
            compiled: vec![None; node_count],
            interpolators: Vec::new(),
            cell_fillers: Vec::new(),
        }
    }

    /// Collects the interpolators and cell caches once every root is compiled.
    pub fn finish(&mut self) {
        let mut interpolators = Vec::new();
        let mut cell_fillers = Vec::new();
        for d in &self.functions {
            match d {
                D::Interpolated { input, slot } => interpolators.push((*slot, *input)),
                D::CacheAllInCell { input, slot } => cell_fillers.push((*slot, *input)),
                _ => {}
            }
        }
        interpolators.sort_by_key(|&(slot, _)| slot);
        cell_fillers.sort_by_key(|&(slot, _)| slot);
        self.interpolators = interpolators;
        self.cell_fillers = cell_fillers;
    }

    pub fn compile_root(&mut self, ir: &ir::Ir, factory: &PositionalRandomFactory, node: u32) -> Result<Did, IrError> {
        let c = Compiler {
            nodes: &ir.nodes,
            splines: &ir.splines,
            seed: ir.settings.seed,
            legacy_random_source: ir.settings.legacy_random_source,
            factory: *factory,
        };
        self.compile(&c, node)
    }

    /// `cacheAllInCell(add(function, beardifier))`, the NoiseChunk's full density.
    pub fn full_density(&mut self, final_density: Did) -> Did {
        let beard = self.push(D::Beardifier);
        let sum = self.push(D::Ap2 {
            t: Ap2Type::Add,
            a: final_density,
            b: beard,
            b_min: f64::NEG_INFINITY,
            b_max: f64::INFINITY,
        });
        let slot = self.cell_slots;
        self.cell_slots += 1;
        self.push(D::CacheAllInCell { input: sum, slot })
    }

    fn push(&mut self, d: D) -> Did {
        self.functions.push(d);
        (self.functions.len() - 1) as Did
    }

    fn compile(&mut self, c: &Compiler, index: u32) -> Result<Did, IrError> {
        if let Some(d) = self.compiled[index as usize] {
            return Ok(d);
        }
        let d = self.compile_new(c, index)?;
        self.compiled[index as usize] = Some(d);
        Ok(d)
    }

    fn compile_new(&mut self, c: &Compiler, index: u32) -> Result<Did, IrError> {
        let node = &c.nodes[index as usize];
        let unsupported = || IrError::new(format!("node {index}: {:?} does not exist before 26.3", node.op));
        let d = match &node.op {
            Op::Const(v) => D::Const(*v),
            Op::Noise {
                noise,
                xz_scale,
                y_scale,
                shift,
            } => match shift {
                None => D::Noise {
                    noise: *noise,
                    xz: *xz_scale,
                    y: *y_scale,
                },
                Some([sx, sy, sz]) => D::Shifted {
                    sx: self.compile(c, *sx)?,
                    sy: self.compile(c, *sy)?,
                    sz: self.compile(c, *sz)?,
                    noise: *noise,
                    xz: *xz_scale,
                    y: *y_scale,
                },
            },
            Op::ShiftA(n) => D::ShiftA(*n),
            Op::ShiftB(n) => D::ShiftB(*n),
            Op::Shift(n) => D::Shift(*n),
            Op::OldBlendedNoise(f) => {
                let mut random = if c.legacy_random_source {
                    RandomSource::new(RandomKind::Legacy, c.seed)
                } else {
                    c.factory.from_hash_of("minecraft:terrain")
                };
                self.blended.push(synth64::BlendedNoise::new(
                    &mut random,
                    f[0],
                    f[1],
                    f[2],
                    f[3],
                    f[4],
                    self.floor,
                ));
                D::Blended((self.blended.len() - 1) as u32)
            }
            Op::EndIslands => {
                if self.end_islands.is_none() {
                    let mut random = RandomSource::new(RandomKind::Legacy, c.seed);
                    random.consume_count(17292);
                    self.end_islands = Some(synth64::SimplexNoise::new(&mut random, self.floor));
                }
                D::EndIslands
            }
            Op::YClampedGradient {
                from_y,
                to_y,
                from_value,
                to_value,
            } => D::YClamped {
                from_y: *from_y,
                to_y: *to_y,
                from_value: *from_value,
                to_value: *to_value,
            },
            Op::BlendAlpha => D::Const(1.0),
            Op::BlendOffset => D::Const(0.0),
            Op::Beardifier => D::Beardifier,
            Op::ScaledNoise { noise, scale } => D::ScaledNoise {
                noise: *noise,
                scale: self.compile(c, *scale)?,
            },
            Op::BlendDensity(input) => return self.compile(c, *input),
            Op::Unary(t, input) => {
                let i = self.compile(c, *input)?;
                let m = match t {
                    Unary::Abs => MapType::Abs,
                    Unary::Square => MapType::Square,
                    Unary::Cube => MapType::Cube,
                    Unary::HalfNegative => MapType::HalfNegative,
                    Unary::QuarterNegative => MapType::QuarterNegative,
                    Unary::Reciprocal => MapType::Invert,
                    Unary::Squeeze => MapType::Squeeze,
                    _ => return Err(unsupported()),
                };
                D::Mapped(m, i)
            }
            Op::Clamp(input, min, max) => D::Clamp(self.compile(c, *input)?, *min, *max),
            Op::MulOrAdd { add, input, argument } => D::MulOrAdd {
                add: *add,
                input: self.compile(c, *input)?,
                argument: *argument,
            },
            Op::Binary(t, a, b) => {
                let t = match t {
                    Binary::Add => Ap2Type::Add,
                    Binary::Mul => Ap2Type::Mul,
                    Binary::Min => Ap2Type::Min,
                    Binary::Max => Ap2Type::Max,
                    _ => return Err(unsupported()),
                };
                let bn = &c.nodes[*b as usize];
                D::Ap2 {
                    t,
                    a: self.compile(c, *a)?,
                    b: self.compile(c, *b)?,
                    b_min: bn.min,
                    b_max: bn.max,
                }
            }
            Op::RangeChoice {
                input,
                min_inclusive,
                max_exclusive,
                in_range,
                out_of_range,
            } => D::RangeChoice {
                input: self.compile(c, *input)?,
                min: *min_inclusive,
                max: *max_exclusive,
                in_range: self.compile(c, *in_range)?,
                out_of_range: self.compile(c, *out_of_range)?,
            },
            Op::IntervalSelect {
                input,
                thresholds,
                functions,
            } => D::IntervalSelect {
                input: self.compile(c, *input)?,
                thresholds: thresholds.clone().into(),
                functions: functions
                    .iter()
                    .map(|&f| self.compile(c, f))
                    .collect::<Result<Vec<_>, _>>()?
                    .into(),
            },
            Op::Spline(root) => {
                let f = spline::compile(self, c, *root)?;
                self.splines.push(f);
                D::Spline((self.splines.len() - 1) as u32)
            }
            Op::FindTopSurface {
                density,
                upper_bound,
                lower_bound,
                cell_height,
            } => D::FindTop {
                density: self.compile(c, *density)?,
                upper: self.compile(c, *upper_bound)?,
                lower: *lower_bound,
                cell: *cell_height,
            },
            Op::Interpolated { input, .. } => {
                let input = self.compile(c, *input)?;
                let slot = self.interpolated_slots;
                self.interpolated_slots += 1;
                D::Interpolated { input, slot }
            }
            Op::Marker(Marker::FlatCache, input) => {
                let input = self.compile(c, *input)?;
                let slot = self.flat_slots;
                self.flat_slots += 1;
                D::FlatCache { input, slot }
            }
            Op::Marker(Marker::CacheAllInCell, input) => {
                let input = self.compile(c, *input)?;
                let slot = self.cell_slots;
                self.cell_slots += 1;
                D::CacheAllInCell { input, slot }
            }
            Op::Marker(Marker::Cache2D | Marker::CacheOnce, input) | Op::Cache(input) => {
                return self.compile(c, *input)
            }
            Op::Gradient { .. }
            | Op::DistanceToPoint { .. }
            | Op::Pow(..)
            | Op::Lerp(..)
            | Op::Round(..)
            | Op::Slice(..) => return Err(unsupported()),
        };
        Ok(self.push(d))
    }

    /// `DensityFunction.compute`.
    pub fn compute(&self, ctx: &mut Ctx64, d: Did, x: i32, y: i32, z: i32, kind: Kind) -> f64 {
        match &self.functions[d as usize] {
            D::Const(v) => *v,
            D::Noise { noise, xz, y: ys } => {
                self.noises[*noise as usize].get64(x as f64 * xz, y as f64 * ys, z as f64 * xz)
            }
            D::Shifted {
                sx,
                sy,
                sz,
                noise,
                xz,
                y: ys,
            } => {
                let nx = x as f64 * xz + self.compute(ctx, *sx, x, y, z, kind);
                let ny = y as f64 * ys + self.compute(ctx, *sy, x, y, z, kind);
                let nz = z as f64 * xz + self.compute(ctx, *sz, x, y, z, kind);
                self.noises[*noise as usize].get64(nx, ny, nz)
            }
            D::ShiftA(n) => self.noises[*n as usize].get64(x as f64 * 0.25, 0.0, z as f64 * 0.25) * 4.0,
            D::ShiftB(n) => self.noises[*n as usize].get64(z as f64 * 0.25, x as f64 * 0.25, 0.0) * 4.0,
            D::Shift(n) => self.noises[*n as usize].get64(x as f64 * 0.25, y as f64 * 0.25, z as f64 * 0.25) * 4.0,
            D::ScaledNoise { noise, scale } => {
                let scale = self.compute(ctx, *scale, x, y, z, kind);
                scale
                    * self.noises[*noise as usize]
                        .get64(x as f64 / scale, y as f64 / scale, z as f64 / scale)
                        .abs()
            }
            D::Blended(b) => self.blended[*b as usize].compute(x, y, z),
            D::EndIslands => {
                let n = self.end_islands.as_ref().expect("end islands noise");
                (end_height(n, x / 8, z / 8) as f64 - 8.0) / 128.0
            }
            D::YClamped {
                from_y,
                to_y,
                from_value,
                to_value,
            } => clamped_map(y as f64, *from_y as f64, *to_y as f64, *from_value, *to_value),
            D::Mapped(t, i) => map_transform(*t, self.compute(ctx, *i, x, y, z, kind)),
            D::Clamp(i, min, max) => clamp(self.compute(ctx, *i, x, y, z, kind), *min, *max),
            D::MulOrAdd { add, input, argument } => {
                let v = self.compute(ctx, *input, x, y, z, kind);
                if *add {
                    v + argument
                } else {
                    v * argument
                }
            }
            D::Ap2 { t, a, b, b_min, b_max } => {
                let v1 = self.compute(ctx, *a, x, y, z, kind);
                match t {
                    Ap2Type::Add => v1 + self.compute(ctx, *b, x, y, z, kind),
                    Ap2Type::Mul => {
                        if v1 == 0.0 {
                            0.0
                        } else {
                            v1 * self.compute(ctx, *b, x, y, z, kind)
                        }
                    }
                    Ap2Type::Min => {
                        if v1 < *b_min {
                            v1
                        } else {
                            min_f64(v1, self.compute(ctx, *b, x, y, z, kind))
                        }
                    }
                    Ap2Type::Max => {
                        if v1 > *b_max {
                            v1
                        } else {
                            max_f64(v1, self.compute(ctx, *b, x, y, z, kind))
                        }
                    }
                }
            }
            D::RangeChoice {
                input,
                min,
                max,
                in_range,
                out_of_range,
            } => {
                let v = self.compute(ctx, *input, x, y, z, kind);
                if v >= *min && v < *max {
                    self.compute(ctx, *in_range, x, y, z, kind)
                } else {
                    self.compute(ctx, *out_of_range, x, y, z, kind)
                }
            }
            D::IntervalSelect {
                input,
                thresholds,
                functions,
            } => {
                let v = self.compute(ctx, *input, x, y, z, kind);
                let i = thresholds.iter().position(|&t| v < t).unwrap_or(functions.len() - 1);
                self.compute(ctx, functions[i], x, y, z, kind)
            }
            D::Spline(f) => spline::compute(self, ctx, &self.splines[*f as usize], x, y, z, kind),
            D::FindTop {
                density,
                upper,
                lower,
                cell,
            } => {
                let top = (self.compute(ctx, *upper, x, y, z, kind) / *cell as f64).floor() as i32;
                let top_y = top.wrapping_mul(*cell);
                if top_y <= *lower {
                    return *lower as f64;
                }
                let mut probe = top_y;
                while probe >= *lower {
                    if self.compute(ctx, *density, x, probe, z, Kind::Direct) > 0.0 {
                        return probe as f64;
                    }
                    let next = probe.wrapping_sub(*cell);
                    if next > probe {
                        break;
                    }
                    probe = next;
                }
                *lower as f64
            }
            D::Interpolated { input, slot } => match kind {
                Kind::Direct => self.compute(ctx, *input, x, y, z, kind),
                Kind::Cell | Kind::Block => {
                    let w = ctx.cell_width as f64;
                    let h = ctx.cell_height as f64;
                    let fx = (x - ctx.cell_start[0]) as f64 / w;
                    let fy = (y - ctx.cell_start[1]) as f64 / h;
                    let fz = (z - ctx.cell_start[2]) as f64 / w;
                    let c = &ctx.interps[*slot as usize].corners;
                    if kind == Kind::Cell {
                        gaius_noise::mth::lerp3(fx, fy, fz, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7])
                    } else {
                        let xz00 = lerp(fy, c[0], c[2]);
                        let xz10 = lerp(fy, c[1], c[3]);
                        let xz01 = lerp(fy, c[4], c[6]);
                        let xz11 = lerp(fy, c[5], c[7]);
                        let z0 = lerp(fx, xz00, xz10);
                        let z1 = lerp(fx, xz01, xz11);
                        lerp(fz, z0, z1)
                    }
                }
            },
            D::FlatCache { input, slot } => {
                let ix = (x >> 2) - ctx.first_noise_x;
                let iz = (z >> 2) - ctx.first_noise_z;
                if ix >= 0 && iz >= 0 && ix < ctx.flat_size && iz < ctx.flat_size {
                    let i = (ix + iz * ctx.flat_size) as usize;
                    if let Some(v) = ctx.flat[*slot as usize][i] {
                        return v;
                    }
                    let v = self.compute(ctx, *input, (x >> 2) << 2, 0, (z >> 2) << 2, Kind::Direct);
                    ctx.flat[*slot as usize][i] = Some(v);
                    v
                } else {
                    self.compute(ctx, *input, x, y, z, kind)
                }
            }
            D::CacheAllInCell { input, slot } => {
                if kind == Kind::Block {
                    let ix = x - ctx.cell_start[0];
                    let iy = y - ctx.cell_start[1];
                    let iz = z - ctx.cell_start[2];
                    let (w, h) = (ctx.cell_width, ctx.cell_height);
                    if ix >= 0 && iy >= 0 && iz >= 0 && ix < w && iy < h && iz < w {
                        return ctx.cells[*slot as usize][(((h - 1 - iy) * w + ix) * w + iz) as usize];
                    }
                }
                self.compute(ctx, *input, x, y, z, kind)
            }
            D::Beardifier => ctx.beard.compute64(x, y, z),
        }
    }

    /// `DensityFunction.fillArray` over `batch` (its kind is the provider's context).
    pub fn fill(&self, ctx: &mut Ctx64, d: Did, out: &mut [f64], batch: &Batch) {
        let kind = batch.kind;
        let direct = |p: &Program64, ctx: &mut Ctx64, out: &mut [f64]| {
            for i in 0..out.len() {
                out[i] = p.compute(ctx, d, batch.x[i], batch.y[i], batch.z[i], kind);
            }
        };
        match &self.functions[d as usize] {
            D::Const(v) => out.fill(*v),
            D::Mapped(t, i) => {
                self.fill(ctx, *i, out, batch);
                for o in out.iter_mut() {
                    *o = map_transform(*t, *o);
                }
            }
            D::Clamp(i, min, max) => {
                self.fill(ctx, *i, out, batch);
                for o in out.iter_mut() {
                    *o = clamp(*o, *min, *max);
                }
            }
            D::MulOrAdd { add, input, argument } => {
                self.fill(ctx, *input, out, batch);
                if *add {
                    for o in out.iter_mut() {
                        *o += argument;
                    }
                } else {
                    for o in out.iter_mut() {
                        *o *= argument;
                    }
                }
            }
            D::Ap2 { t, a, b, b_min, b_max } => {
                self.fill(ctx, *a, out, batch);
                match t {
                    Ap2Type::Add => {
                        let mut tmp = ctx.arena.take(out.len());
                        self.fill(ctx, *b, &mut tmp[..out.len()], batch);
                        for (o, v) in out.iter_mut().zip(tmp.iter()) {
                            *o += v;
                        }
                        ctx.arena.give(tmp);
                    }
                    Ap2Type::Mul => {
                        for i in 0..out.len() {
                            let v = out[i];
                            out[i] = if v == 0.0 {
                                0.0
                            } else {
                                v * self.compute(ctx, *b, batch.x[i], batch.y[i], batch.z[i], kind)
                            };
                        }
                    }
                    Ap2Type::Min => {
                        for i in 0..out.len() {
                            let v = out[i];
                            out[i] = if v < *b_min {
                                v
                            } else {
                                min_f64(v, self.compute(ctx, *b, batch.x[i], batch.y[i], batch.z[i], kind))
                            };
                        }
                    }
                    Ap2Type::Max => {
                        for i in 0..out.len() {
                            let v = out[i];
                            out[i] = if v > *b_max {
                                v
                            } else {
                                max_f64(v, self.compute(ctx, *b, batch.x[i], batch.y[i], batch.z[i], kind))
                            };
                        }
                    }
                }
            }
            D::RangeChoice {
                input,
                min,
                max,
                in_range,
                out_of_range,
            } => {
                self.fill(ctx, *input, out, batch);
                for i in 0..out.len() {
                    let v = out[i];
                    let f = if v >= *min && v < *max {
                        *in_range
                    } else {
                        *out_of_range
                    };
                    out[i] = self.compute(ctx, f, batch.x[i], batch.y[i], batch.z[i], kind);
                }
            }
            D::IntervalSelect {
                input,
                thresholds,
                functions,
            } => {
                self.fill(ctx, *input, out, batch);
                for i in 0..out.len() {
                    let v = out[i];
                    let k = thresholds.iter().position(|&t| v < t).unwrap_or(functions.len() - 1);
                    out[i] = self.compute(ctx, functions[k], batch.x[i], batch.y[i], batch.z[i], kind);
                }
            }
            D::Interpolated { input, .. } => {
                if kind == Kind::Cell {
                    direct(self, ctx, out);
                } else {
                    self.fill(ctx, *input, out, batch);
                }
            }
            _ => direct(self, ctx, out),
        }
    }

    // ---- NoiseChunk interpolation driver ----

    /// `fillSlice(slice0, cellX)`: fills every interpolator's column slices of cell column `cell_x`.
    fn fill_slice(&self, ctx: &mut Ctx64, first: bool, cell_x: i32, batch: &mut Batch) {
        let start_x = cell_x * ctx.cell_width;
        let rows = (ctx.cell_count_y + 1) as usize;
        let mut column = ctx.arena.take(rows);
        for cz in 0..=ctx.cell_count_xz {
            let start_z = (ctx.first_cell_z + cz) * ctx.cell_width;
            batch.clear();
            batch.kind = Kind::Direct;
            for cy in 0..=ctx.cell_count_y {
                batch.push(start_x, (cy + ctx.cell_noise_min_y) * ctx.cell_height, start_z);
            }
            for &(slot, input) in &self.interpolators {
                self.fill(ctx, input, &mut column[..rows], batch);
                let interp = &mut ctx.interps[slot as usize];
                let target = if first { &mut interp.slice0 } else { &mut interp.slice1 };
                target[cz as usize * rows..(cz as usize + 1) * rows].copy_from_slice(&column[..rows]);
            }
        }
        ctx.arena.give(column);
    }

    /// `initializeForFirstCellX`.
    pub fn begin_interpolation(&self, ctx: &mut Ctx64, batch: &mut Batch) {
        let first = ctx.first_cell_x;
        self.fill_slice(ctx, true, first, batch);
    }

    /// `advanceCellX(cellXIndex)`.
    pub fn advance_cell_x(&self, ctx: &mut Ctx64, cell_x_index: i32, batch: &mut Batch) {
        let next = ctx.first_cell_x + cell_x_index + 1;
        self.fill_slice(ctx, false, next, batch);
        ctx.cell_start[0] = (ctx.first_cell_x + cell_x_index) * ctx.cell_width;
    }

    /// `swapSlices`.
    pub fn swap_slices(&self, ctx: &mut Ctx64) {
        for interp in ctx.interps.iter_mut() {
            core::mem::swap(&mut interp.slice0, &mut interp.slice1);
        }
    }

    /// `selectCellYZ`: loads the corners and fills the cell caches through `batch` (a Cell batch).
    pub fn select_cell_yz(&self, ctx: &mut Ctx64, cell_y: i32, cell_z: i32, batch: &mut Batch) {
        let rows = (ctx.cell_count_y + 1) as usize;
        let (y, z) = (cell_y as usize, cell_z as usize);
        for interp in ctx.interps.iter_mut() {
            let s0 = &interp.slice0;
            let s1 = &interp.slice1;
            interp.corners = [
                s0[z * rows + y],
                s1[z * rows + y],
                s0[z * rows + y + 1],
                s1[z * rows + y + 1],
                s0[(z + 1) * rows + y],
                s1[(z + 1) * rows + y],
                s0[(z + 1) * rows + y + 1],
                s1[(z + 1) * rows + y + 1],
            ];
        }
        ctx.cell_start[1] = (cell_y + ctx.cell_noise_min_y) * ctx.cell_height;
        ctx.cell_start[2] = (ctx.first_cell_z + cell_z) * ctx.cell_width;
        if ctx.cells.is_empty() {
            return;
        }
        batch.clear();
        batch.kind = Kind::Cell;
        let (w, h) = (ctx.cell_width, ctx.cell_height);
        let [sx, sy, sz] = ctx.cell_start;
        for iy in (0..h).rev() {
            for ix in 0..w {
                for iz in 0..w {
                    batch.push(sx + ix, sy + iy, sz + iz);
                }
            }
        }
        for &(slot, input) in &self.cell_fillers {
            let mut values = core::mem::take(&mut ctx.cells[slot as usize]);
            let n = values.len();
            self.fill(ctx, input, &mut values[..n], batch);
            ctx.cells[slot as usize] = values;
        }
    }
}

/// Pre-26.3 `EndIslandDensityFunction.getHeightValue`.
fn end_height(noise: &synth64::SimplexNoise, section_x: i32, section_z: i32) -> f32 {
    use crate::java::max_f32;
    use gaius_noise::mth::float::clamp as clampf;
    let chunk_x = section_x / 2;
    let chunk_z = section_z / 2;
    let sub_x = section_x % 2;
    let sub_z = section_z % 2;
    let dist = section_x
        .wrapping_mul(section_x)
        .wrapping_add(section_z.wrapping_mul(section_z)) as f32;
    let mut doffs = clampf(100.0 - dist.sqrt() * 8.0, -100.0, 80.0);
    for xo in -12..=12i32 {
        for zo in -12..=12i32 {
            let tx = chunk_x as i64 + xo as i64;
            let tz = chunk_z as i64 + zo as i64;
            if tx.wrapping_mul(tx).wrapping_add(tz.wrapping_mul(tz)) > 4096
                && noise.get_value_2d(tx as f64, tz as f64) < -0.9f32 as f64
            {
                let size = ((tx as f32).abs() * 3439.0 + (tz as f32).abs() * 147.0) % 13.0 + 9.0;
                let xd = (sub_x - xo * 2) as f32;
                let zd = (sub_z - zo * 2) as f32;
                let d = clampf(100.0 - (xd * xd + zd * zd).sqrt() * size, -100.0, 80.0);
                doffs = max_f32(doffs, d);
            }
        }
    }
    doffs
}
