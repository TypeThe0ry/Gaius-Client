//! 26.3 density samplers: `DensityFunction.compileSampler` and the
//! `DensitySampler.sampleValue` / `sampleVolume` pair of every sampler class.
//!
//! The IR tree is the one vanilla's `DensityFunctionCompiler` compiles; this
//! module makes the same specialization choices (`ConstAddSampler`,
//! `SingleThresholdSampler`, the `pow` shortcuts, `min`/`max` range pruning,
//! ...) and evaluates them with the same float operation order. Volume
//! evaluation works on whole buffers (contiguous `y` columns), which is where
//! the lane helpers in [`crate::simd`] apply. Cache cells emulate
//! `SamplerContext.sampleVolumeCached` / `sampleValueCached` exactly, including
//! point lookups served from the last cached volume.

mod spline;

use crate::arena::Arena;
use crate::beard::Beard;
use crate::ir::{self, Axis, Binary, IrError, Metric, Op, RoundType, Tiling, Unary};
use crate::java::{block_pos_as_long, floor_div, floor_mod, max_f32, min_f32, round_f32, signum_f32};
use crate::noises::NoiseInst;
use crate::simd;
use crate::volume::Volume;
use gaius_noise::mth::float::{clamp, lerp, lerp3};
use gaius_noise::synth32::{BlendedNoiseParameters, SimplexNoise};
use gaius_noise::{PositionalRandomFactory, RandomKind, RandomSource};
pub use spline::SplineFn;

pub type Sid = u32;

#[derive(Clone, Copy, Debug)]
pub enum GradientMode {
    Clamped { min: i32, max: i32 },
    Repeat,
    Mirrored,
}

#[derive(Clone, Debug)]
pub enum S {
    Const(f32),
    Noise {
        noise: u32,
        xz: f64,
        y: f64,
    },
    NoiseXz {
        sx: Sid,
        sz: Sid,
        noise: u32,
        xz: f64,
        y: f64,
    },
    NoiseXyz {
        sx: Sid,
        sy: Sid,
        sz: Sid,
        noise: u32,
        xz: f64,
        y: f64,
    },
    ShiftB {
        noise: u32,
    },
    EndIslands,
    Gradient {
        axis: Axis,
        mode: GradientMode,
        from: i32,
        range: i32,
        from_value: f32,
        factor: f32,
    },
    Distance {
        point: [i32; 3],
        metric: Metric,
    },
    Beardifier,
    Abs(Sid),
    Square(Sid),
    Cube(Sid),
    Sqrt(Sid),
    LeakyRelu(Sid, f32),
    Reciprocal(Sid),
    Negate(Sid),
    Squeeze(Sid),
    Log(Sid),
    Sign(Sid),
    Add(Sid, Sid),
    Sub(Sid, Sid),
    Mul(Sid, Sid),
    Div(Sid, Sid),
    Min(Sid, Sid, f32),
    Max(Sid, Sid, f32),
    ConstAdd(Sid, f32),
    ConstSub(f32, Sid),
    ConstMul(Sid, f32),
    ConstDiv(f32, Sid),
    ConstMin(Sid, f32),
    ConstMax(Sid, f32),
    PowConstBase(f64, Sid),
    PowConstExp(Sid, f64),
    Pow(Sid, Sid),
    Clamp(Sid, f32, f32),
    Lerp(Sid, Sid, Sid),
    LerpConstFirst(Sid, f32, Sid),
    LerpConstSecond(Sid, Sid, f32),
    RangeConst {
        input: Sid,
        min: f32,
        max: f32,
        in_range: f32,
        out_of_range: f32,
    },
    Range {
        input: Sid,
        min: f32,
        max: f32,
        in_range: Sid,
        out_of_range: Sid,
    },
    IntervalSingle {
        input: Sid,
        threshold: f32,
        below: Sid,
        above: Sid,
    },
    Interval {
        input: Sid,
        thresholds: Box<[f32]>,
        samplers: Box<[Sid]>,
    },
    RoundInt(RoundType, Sid),
    Round(RoundType, Sid, Sid),
    SliceX(Sid, i32),
    SliceY(Sid, i32),
    SliceZ(Sid, i32),
    SliceXz(Sid, i32, i32),
    FindTop {
        density: Sid,
        upper: Sid,
        lower: i32,
        cell: i32,
    },
    Spline(u32),
    Interpolated {
        input: Sid,
        cxz: i32,
        cy: i32,
        inv_xz: f32,
        inv_y: f32,
    },
    Cache {
        id: u32,
        input: Sid,
    },
}

/// The compiled sampler graph of one generator.
pub struct Program32 {
    pub samplers: Vec<S>,
    pub noises: Vec<NoiseInst>,
    pub splines: Vec<SplineFn>,
    pub cache_count: u32,
    end_islands: Option<SimplexNoise>,
    /// IR node index -> sampler (compiled lazily, shared between roots like the vanilla compiler).
    compiled: Vec<Option<Sid>>,
    /// IR `CACHE` node -> cache id.
    cache_ids: Vec<Option<u32>>,
}

/// One `SamplerContext`'s cache cell.
#[derive(Default)]
pub struct CacheCell {
    volume: Option<Volume>,
    buffer: Option<Vec<f32>>,
    value_key: i64,
    value: f32,
}

/// A `SamplerContext`: buffer arena, cache cells and user fields (the beardifier).
pub struct Ctx32 {
    pub arena: Arena<f32>,
    pub caches: Vec<CacheCell>,
    pub caches_enabled: bool,
    pub beard: Beard,
}

impl Ctx32 {
    pub fn new(cache_count: u32, caches_enabled: bool, arena: Arena<f32>, beard: Beard) -> Ctx32 {
        Ctx32 {
            arena,
            caches: (0..cache_count)
                .map(|_| CacheCell {
                    value: f32::NAN,
                    ..CacheCell::default()
                })
                .collect(),
            caches_enabled,
            beard,
        }
    }

    /// Returns the arena (with every cached buffer released into it).
    pub fn into_arena(mut self) -> Arena<f32> {
        for cell in self.caches.drain(..) {
            if let Some(b) = cell.buffer {
                self.arena.give(b);
            }
        }
        self.arena
    }
}

struct Compiler<'a> {
    nodes: &'a [ir::Node],
    splines: &'a [ir::SplineDef],
    seed: i64,
    legacy_random_source: bool,
    factory: PositionalRandomFactory,
}

fn is_const(node: &ir::Node) -> Option<f32> {
    match node.op {
        Op::Const(v) => Some(v as f32),
        _ => None,
    }
}

impl Program32 {
    /// Prepares an empty program over the generator's noise table.
    pub fn new(noises: Vec<NoiseInst>, node_count: usize) -> Program32 {
        Program32 {
            samplers: Vec::new(),
            noises,
            splines: Vec::new(),
            cache_count: 0,
            end_islands: None,
            compiled: vec![None; node_count],
            cache_ids: vec![None; node_count],
        }
    }

    /// `DensityFunctionCompiler.getSampler(root)`.
    pub fn compile_root(&mut self, ir: &ir::Ir, factory: &PositionalRandomFactory, node: u32) -> Result<Sid, IrError> {
        let c = Compiler {
            nodes: &ir.nodes,
            splines: &ir.splines,
            seed: ir.settings.seed,
            legacy_random_source: ir.settings.legacy_random_source,
            factory: *factory,
        };
        self.compile(&c, node)
    }

    fn push(&mut self, s: S) -> Sid {
        self.samplers.push(s);
        (self.samplers.len() - 1) as Sid
    }

    fn compile(&mut self, c: &Compiler, index: u32) -> Result<Sid, IrError> {
        if let Some(s) = self.compiled[index as usize] {
            return Ok(s);
        }
        let s = self.compile_new(c, index)?;
        self.compiled[index as usize] = Some(s);
        Ok(s)
    }

    fn compile_new(&mut self, c: &Compiler, index: u32) -> Result<Sid, IrError> {
        let node = &c.nodes[index as usize];
        let child = |i: u32| &c.nodes[i as usize];
        let s = match &node.op {
            Op::Const(v) => S::Const(*v as f32),
            Op::Noise {
                noise,
                xz_scale,
                y_scale,
                shift,
            } => match shift {
                None => S::Noise {
                    noise: *noise,
                    xz: *xz_scale,
                    y: *y_scale,
                },
                Some([sx, sy, sz]) => {
                    // `shift.equals(DensityFunctions.zero())`: record equality compares float bits.
                    let zero = |i: u32| matches!(child(i).op, Op::Const(v) if (v as f32).to_bits() == 0);
                    if zero(*sx) && zero(*sy) && zero(*sz) {
                        S::Noise {
                            noise: *noise,
                            xz: *xz_scale,
                            y: *y_scale,
                        }
                    } else {
                        let x = self.compile(c, *sx)?;
                        let z = self.compile(c, *sz)?;
                        if zero(*sy) {
                            S::NoiseXz {
                                sx: x,
                                sz: z,
                                noise: *noise,
                                xz: *xz_scale,
                                y: *y_scale,
                            }
                        } else {
                            let y = self.compile(c, *sy)?;
                            S::NoiseXyz {
                                sx: x,
                                sy: y,
                                sz: z,
                                noise: *noise,
                                xz: *xz_scale,
                                y: *y_scale,
                            }
                        }
                    }
                }
            },
            Op::ShiftA(noise) => {
                let inner = self.push(S::Noise {
                    noise: *noise,
                    xz: 0.25,
                    y: 0.0,
                });
                S::ConstMul(inner, 4.0)
            }
            Op::Shift(noise) => {
                let inner = self.push(S::Noise {
                    noise: *noise,
                    xz: 0.25,
                    y: 0.25,
                });
                S::ConstMul(inner, 4.0)
            }
            Op::ShiftB(noise) => S::ShiftB { noise: *noise },
            Op::OldBlendedNoise(f) => {
                let mut random = if c.legacy_random_source {
                    RandomSource::new(RandomKind::Legacy, c.seed)
                } else {
                    c.factory.from_hash_of(gaius_noise::synth32::NOISE_SEED)
                };
                let params = BlendedNoiseParameters {
                    xz_scale: f[0],
                    y_scale: f[1],
                    xz_factor: f[2],
                    y_factor: f[3],
                    smear_scale_multiplier: f[4],
                };
                let fbms = params.compile(&mut random);
                let sampler = |s: gaius_noise::synth32::NoiseSampler, p: &mut Program32| {
                    p.noises.push(NoiseInst::Stack(s.noise));
                    let n = (p.noises.len() - 1) as u32;
                    p.push(S::Noise {
                        noise: n,
                        xz: s.xz_scale,
                        y: s.y_scale,
                    })
                };
                let min = sampler(fbms.min_limit, self);
                let max = sampler(fbms.max_limit, self);
                let main = sampler(fbms.main, self);
                let shifted = self.push(S::ConstAdd(main, 0.5));
                let choice = self.push(S::Clamp(shifted, 0.0, 1.0));
                S::Lerp(choice, min, max)
            }
            Op::EndIslands => {
                if self.end_islands.is_none() {
                    let mut random = RandomSource::new(RandomKind::Legacy, c.seed);
                    random.consume_count(17292);
                    self.end_islands = Some(SimplexNoise::with_zero_offsets(&mut random, true));
                }
                S::EndIslands
            }
            Op::Gradient {
                axis,
                tiling,
                from,
                to,
                from_value,
                to_value,
            } => {
                let range = to - from;
                let (fv, tv) = (*from_value as f32, *to_value as f32);
                let factor = (tv - fv) / range as f32;
                S::Gradient {
                    axis: *axis,
                    mode: match tiling {
                        Tiling::Clamp => GradientMode::Clamped {
                            min: (*from).min(*to),
                            max: (*from).max(*to),
                        },
                        Tiling::Repeat => GradientMode::Repeat,
                        Tiling::Mirrored => GradientMode::Mirrored,
                    },
                    from: *from,
                    range,
                    from_value: fv,
                    factor,
                }
            }
            Op::YClampedGradient {
                from_y,
                to_y,
                from_value,
                to_value,
            } => {
                let range = to_y - from_y;
                let (fv, tv) = (*from_value as f32, *to_value as f32);
                S::Gradient {
                    axis: Axis::Y,
                    mode: GradientMode::Clamped {
                        min: (*from_y).min(*to_y),
                        max: (*from_y).max(*to_y),
                    },
                    from: *from_y,
                    range,
                    from_value: fv,
                    factor: (tv - fv) / range as f32,
                }
            }
            Op::DistanceToPoint { point, metric } => S::Distance {
                point: *point,
                metric: *metric,
            },
            // No blender reaches the kernel (blended chunks stay on the Java path), so the
            // context-bound samplers fall back to their constants.
            Op::BlendAlpha => S::Const(1.0),
            Op::BlendOffset => S::Const(0.0),
            Op::Beardifier => S::Beardifier,
            Op::BlendDensity(input) => return self.compile(c, *input),
            Op::Unary(t, input) => {
                let i = self.compile(c, *input)?;
                match t {
                    Unary::Abs => S::Abs(i),
                    Unary::Square => S::Square(i),
                    Unary::Cube => S::Cube(i),
                    Unary::Sqrt => S::Sqrt(i),
                    Unary::HalfNegative => S::LeakyRelu(i, 0.5),
                    Unary::QuarterNegative => S::LeakyRelu(i, 0.25),
                    Unary::Reciprocal => S::Reciprocal(i),
                    Unary::Negate => S::Negate(i),
                    Unary::Squeeze => S::Squeeze(i),
                    Unary::Log => S::Log(i),
                    Unary::Sign => S::Sign(i),
                }
            }
            Op::Binary(t, l, r) => {
                let (ln, rn) = (child(*l), child(*r));
                let left = self.compile(c, *l)?;
                let right = self.compile(c, *r)?;
                match t {
                    Binary::Add => match (is_const(ln), is_const(rn)) {
                        (Some(v), _) => S::ConstAdd(right, v),
                        (_, Some(v)) => S::ConstAdd(left, v),
                        _ => S::Add(left, right),
                    },
                    Binary::Sub => match (is_const(ln), is_const(rn)) {
                        (Some(v), _) => S::ConstSub(v, right),
                        (_, Some(v)) => S::ConstAdd(left, -v),
                        _ => S::Sub(left, right),
                    },
                    Binary::Mul => match (is_const(ln), is_const(rn)) {
                        (Some(v), _) => S::ConstMul(right, v),
                        (_, Some(v)) => S::ConstMul(left, v),
                        _ => S::Mul(left, right),
                    },
                    Binary::Div => match (is_const(ln), is_const(rn)) {
                        (Some(v), _) => S::ConstDiv(v, right),
                        (_, Some(v)) => S::ConstMul(left, 1.0 / v),
                        _ => S::Div(left, right),
                    },
                    Binary::Min => {
                        let (lmin, lmax, rmin, rmax) = (ln.min as f32, ln.max as f32, rn.min as f32, rn.max as f32);
                        if lmax < rmin {
                            return Ok(left);
                        } else if rmax < lmin {
                            return Ok(right);
                        }
                        match (is_const(ln), is_const(rn)) {
                            (Some(v), _) => S::ConstMin(right, v),
                            (_, Some(v)) => S::ConstMin(left, v),
                            _ => S::Min(left, right, rmin),
                        }
                    }
                    Binary::Max => {
                        let (lmin, lmax, rmin, rmax) = (ln.min as f32, ln.max as f32, rn.min as f32, rn.max as f32);
                        if lmin > rmax {
                            return Ok(left);
                        } else if rmin > lmax {
                            return Ok(right);
                        }
                        match (is_const(ln), is_const(rn)) {
                            (Some(v), _) => S::ConstMax(right, v),
                            (_, Some(v)) => S::ConstMax(left, v),
                            _ => S::Max(left, right, rmax),
                        }
                    }
                }
            }
            Op::MulOrAdd { add, input, argument } => {
                // Only exported for pre-26.3 routers; compile it like the equivalent binary op.
                let i = self.compile(c, *input)?;
                if *add {
                    S::ConstAdd(i, *argument as f32)
                } else {
                    S::ConstMul(i, *argument as f32)
                }
            }
            Op::Pow(b, e) => {
                let base = self.compile(c, *b)?;
                let exponent = self.compile(c, *e)?;
                if let Some(v) = is_const(child(*b)) {
                    S::PowConstBase(v as f64, exponent)
                } else if let Some(v) = is_const(child(*e)) {
                    let abs = v.abs();
                    let special = if abs == 0.5 {
                        self.push(S::Sqrt(base))
                    } else if abs == 1.0 {
                        base
                    } else if abs == 2.0 {
                        self.push(S::Square(base))
                    } else if abs == 3.0 {
                        self.push(S::Cube(base))
                    } else {
                        return Ok(self.push(S::PowConstExp(base, v as f64)));
                    };
                    if v >= 0.0 {
                        return Ok(special);
                    }
                    S::Reciprocal(special)
                } else {
                    S::Pow(base, exponent)
                }
            }
            Op::Clamp(input, min, max) => S::Clamp(self.compile(c, *input)?, *min as f32, *max as f32),
            Op::Lerp(a, f, s) => {
                let alpha = self.compile(c, *a)?;
                let first = self.compile(c, *f)?;
                let second = self.compile(c, *s)?;
                if let Some(v) = is_const(child(*f)) {
                    S::LerpConstFirst(alpha, v, second)
                } else if let Some(v) = is_const(child(*s)) {
                    S::LerpConstSecond(alpha, first, v)
                } else {
                    S::Lerp(alpha, first, second)
                }
            }
            Op::RangeChoice {
                input,
                min_inclusive,
                max_exclusive,
                in_range,
                out_of_range,
            } => {
                let i = self.compile(c, *input)?;
                let (min, max) = (*min_inclusive as f32, *max_exclusive as f32);
                match (is_const(child(*in_range)), is_const(child(*out_of_range))) {
                    (Some(a), Some(b)) => S::RangeConst {
                        input: i,
                        min,
                        max,
                        in_range: a,
                        out_of_range: b,
                    },
                    _ => S::Range {
                        input: i,
                        min,
                        max,
                        in_range: self.compile(c, *in_range)?,
                        out_of_range: self.compile(c, *out_of_range)?,
                    },
                }
            }
            Op::IntervalSelect {
                input,
                thresholds,
                functions,
            } => {
                let i = self.compile(c, *input)?;
                if thresholds.len() == 1 {
                    S::IntervalSingle {
                        input: i,
                        threshold: thresholds[0] as f32,
                        below: self.compile(c, functions[0])?,
                        above: self.compile(c, functions[functions.len() - 1])?,
                    }
                } else {
                    let samplers = functions
                        .iter()
                        .map(|&f| self.compile(c, f))
                        .collect::<Result<Vec<_>, _>>()?;
                    S::Interval {
                        input: i,
                        thresholds: thresholds.iter().map(|&t| t as f32).collect(),
                        samplers: samplers.into(),
                    }
                }
            }
            Op::Round(t, input, multiple) => {
                let i = self.compile(c, *input)?;
                if is_const(child(*multiple)) == Some(1.0) {
                    S::RoundInt(*t, i)
                } else {
                    S::Round(*t, i, self.compile(c, *multiple)?)
                }
            }
            Op::Slice(axis, coordinate, input) => {
                if let Op::Slice(inner_axis, inner_coordinate, inner_input) = child(*input).op {
                    let pair = match (axis, inner_axis) {
                        (Axis::X, Axis::Z) => Some((*coordinate, inner_coordinate)),
                        (Axis::Z, Axis::X) => Some((inner_coordinate, *coordinate)),
                        _ => None,
                    };
                    if let Some((x, z)) = pair {
                        let i = self.compile(c, inner_input)?;
                        return Ok(self.push(S::SliceXz(i, x, z)));
                    }
                }
                let i = self.compile(c, *input)?;
                match axis {
                    Axis::X => S::SliceX(i, *coordinate),
                    Axis::Y => S::SliceY(i, *coordinate),
                    Axis::Z => S::SliceZ(i, *coordinate),
                }
            }
            Op::FindTopSurface {
                density,
                upper_bound,
                lower_bound,
                cell_height,
            } => {
                let d = self.compile(c, *density)?;
                let u = self.compile(c, *upper_bound)?;
                let find = self.push(S::FindTop {
                    density: d,
                    upper: u,
                    lower: *lower_bound,
                    cell: *cell_height,
                });
                S::SliceY(find, 0)
            }
            Op::Spline(root) => {
                let f = spline::compile(self, c, *root)?;
                self.splines.push(f);
                S::Spline((self.splines.len() - 1) as u32)
            }
            Op::Interpolated { input, cell_xz, cell_y } => S::Interpolated {
                input: self.compile(c, *input)?,
                cxz: *cell_xz,
                cy: *cell_y,
                inv_xz: 1.0 / *cell_xz as f32,
                inv_y: 1.0 / *cell_y as f32,
            },
            Op::Cache(input) => {
                let id = match self.cache_ids[index as usize] {
                    Some(id) => id,
                    None => {
                        let id = self.cache_count;
                        self.cache_count += 1;
                        self.cache_ids[index as usize] = Some(id);
                        id
                    }
                };
                S::Cache {
                    id,
                    input: self.compile(c, *input)?,
                }
            }
            Op::ScaledNoise { .. } => return Err(IrError::new("scaled noise requires a double router")),
            Op::Marker(_, input) => return self.compile(c, *input),
        };
        Ok(self.push(s))
    }
}

/// `EndIslandFunction.getHeightValue`.
fn end_height(noise: &SimplexNoise, section_x: i32, section_z: i32) -> f32 {
    let chunk_x = section_x / 2;
    let chunk_z = section_z / 2;
    let sub_x = section_x % 2;
    let sub_z = section_z % 2;
    let mut doffs = -100.0f32;
    for xo in -12..=12i32 {
        for zo in -12..=12i32 {
            let tx = chunk_x as i64 + xo as i64;
            let tz = chunk_z as i64 + zo as i64;
            if tx.wrapping_mul(tx).wrapping_add(tz.wrapping_mul(tz)) > 4096 && noise.get_2d(tx as f64, tz as f64) < -0.9
            {
                let size = ((tx as f32).abs() * 3439.0 + (tz as f32).abs() * 147.0) % 13.0 + 9.0;
                let xd = (sub_x - xo * 2) as f32;
                let zd = (sub_z - zo * 2) as f32;
                let d = clamp(100.0 - (xd * xd + zd * zd).sqrt() * size, -100.0, 80.0);
                doffs = max_f32(doffs, d);
            }
        }
    }
    doffs
}

#[inline]
fn gradient_compute(mode: GradientMode, from: i32, range: i32, from_value: f32, factor: f32, c: i32) -> f32 {
    match mode {
        GradientMode::Clamped { min, max } => {
            let clamped = if c < min { min } else { c.min(max) };
            from_value + (clamped - from) as f32 * factor
        }
        GradientMode::Repeat => from_value + floor_mod(c.wrapping_sub(from), range) as f32 * factor,
        GradientMode::Mirrored => {
            let rel = c.wrapping_sub(from);
            let tile = floor_div(rel, range);
            let local = rel - tile * range;
            if tile & 1 == 0 {
                from_value + local as f32 * factor
            } else {
                from_value + (range - local) as f32 * factor
            }
        }
    }
}

#[inline]
fn metric(metric: Metric, dx: f32, dy: f32, dz: f32) -> f32 {
    match metric {
        Metric::Euclidean => (dx * dx + dy * dy + dz * dz).sqrt(),
        Metric::EuclideanSquared => dx * dx + dy * dy + dz * dz,
        Metric::Manhattan => dx.abs() + dy.abs() + dz.abs(),
        Metric::Chebyshev => max_f32(max_f32(dx.abs(), dy.abs()), dz.abs()),
    }
}

#[inline]
fn squeeze(v: f32) -> f32 {
    let c = clamp(v, -1.0, 1.0);
    c / 2.0 - c * c * c / 24.0
}

#[inline]
fn round_to_integer(v: f32, t: RoundType) -> f32 {
    match t {
        RoundType::Floor => (v as f64).floor() as f32,
        RoundType::Round => round_f32(v) as f32,
        RoundType::Ceil => (v as f64).ceil() as f32,
        RoundType::Truncate => {
            if v > 0.0 {
                (v as f64).floor() as f32
            } else {
                (v as f64).ceil() as f32
            }
        }
    }
}

#[inline]
fn round_apply(t: RoundType, input: f32, multiple: f32) -> f32 {
    if multiple == 0.0 {
        input
    } else {
        round_to_integer(input / multiple, t) * multiple
    }
}

#[inline]
fn lerp_select(alpha: f32, first: f32, second: f32) -> f32 {
    if alpha == 0.0 {
        first
    } else if alpha == 1.0 {
        second
    } else {
        lerp(alpha, first, second)
    }
}

impl Program32 {
    fn map(&self, ctx: &mut Ctx32, input: Sid, out: &mut [f32], v: &Volume, f: impl Fn(f32) -> f32) {
        self.sample_volume(ctx, input, out, v);
        for o in out.iter_mut() {
            *o = f(*o);
        }
    }

    /// Samples `input` into a scratch buffer from the arena.
    fn scratch(&self, ctx: &mut Ctx32, input: Sid, v: &Volume) -> Vec<f32> {
        let mut b = ctx.arena.take(v.len());
        self.sample_volume(ctx, input, &mut b[..v.len()], v);
        b
    }

    /// `DensitySampler.sampleValue`.
    pub fn sample_value(&self, ctx: &mut Ctx32, s: Sid, x: i32, y: i32, z: i32) -> f32 {
        match &self.samplers[s as usize] {
            S::Const(v) => *v,
            S::Noise { noise, xz, y: ys } => {
                self.noises[*noise as usize].get32(x as f64 * xz, y as f64 * ys, z as f64 * xz)
            }
            S::NoiseXz {
                sx,
                sz,
                noise,
                xz,
                y: ys,
            } => {
                let nx = x as f64 * xz + self.sample_value(ctx, *sx, x, y, z) as f64;
                let ny = y as f64 * ys;
                let nz = z as f64 * xz + self.sample_value(ctx, *sz, x, y, z) as f64;
                self.noises[*noise as usize].get32(nx, ny, nz)
            }
            S::NoiseXyz {
                sx,
                sy,
                sz,
                noise,
                xz,
                y: ys,
            } => {
                let nx = x as f64 * xz + self.sample_value(ctx, *sx, x, y, z) as f64;
                let ny = y as f64 * ys + self.sample_value(ctx, *sy, x, y, z) as f64;
                let nz = z as f64 * xz + self.sample_value(ctx, *sz, x, y, z) as f64;
                self.noises[*noise as usize].get32(nx, ny, nz)
            }
            S::ShiftB { noise } => self.noises[*noise as usize].get32(z as f64 * 0.25, x as f64 * 0.25, 0.0) * 4.0,
            S::EndIslands => {
                let n = self.end_islands.as_ref().expect("end islands noise");
                (end_height(n, x / 8, z / 8) - 8.0) / 128.0
            }
            S::Gradient {
                axis,
                mode,
                from,
                range,
                from_value,
                factor,
            } => gradient_compute(*mode, *from, *range, *from_value, *factor, axis.choose(x, y, z)),
            S::Distance { point, metric: m } => {
                metric(*m, (point[0] - x) as f32, (point[1] - y) as f32, (point[2] - z) as f32)
            }
            S::Beardifier => ctx.beard.sample_value32(x, y, z),
            S::Abs(i) => self.sample_value(ctx, *i, x, y, z).abs(),
            S::Square(i) => {
                let v = self.sample_value(ctx, *i, x, y, z);
                v * v
            }
            S::Cube(i) => {
                let v = self.sample_value(ctx, *i, x, y, z);
                v * v * v
            }
            S::Sqrt(i) => self.sample_value(ctx, *i, x, y, z).sqrt(),
            S::LeakyRelu(i, f) => {
                let v = self.sample_value(ctx, *i, x, y, z);
                if v > 0.0 {
                    v
                } else {
                    v * f
                }
            }
            S::Reciprocal(i) => 1.0 / self.sample_value(ctx, *i, x, y, z),
            S::Negate(i) => -self.sample_value(ctx, *i, x, y, z),
            S::Squeeze(i) => squeeze(self.sample_value(ctx, *i, x, y, z)),
            S::Log(i) => (self.sample_value(ctx, *i, x, y, z) as f64).ln() as f32,
            S::Sign(i) => signum_f32(self.sample_value(ctx, *i, x, y, z)),
            S::Add(l, r) => self.sample_value(ctx, *l, x, y, z) + self.sample_value(ctx, *r, x, y, z),
            S::Sub(l, r) => self.sample_value(ctx, *l, x, y, z) - self.sample_value(ctx, *r, x, y, z),
            S::Mul(l, r) => {
                let left = self.sample_value(ctx, *l, x, y, z);
                if left == 0.0 {
                    0.0
                } else {
                    left * self.sample_value(ctx, *r, x, y, z)
                }
            }
            S::Div(l, r) => {
                let left = self.sample_value(ctx, *l, x, y, z);
                if left == 0.0 {
                    0.0
                } else {
                    left / self.sample_value(ctx, *r, x, y, z)
                }
            }
            S::Min(l, r, rmin) => {
                let left = self.sample_value(ctx, *l, x, y, z);
                if left <= *rmin {
                    left
                } else {
                    min_f32(left, self.sample_value(ctx, *r, x, y, z))
                }
            }
            S::Max(l, r, rmax) => {
                let left = self.sample_value(ctx, *l, x, y, z);
                if left >= *rmax {
                    left
                } else {
                    max_f32(left, self.sample_value(ctx, *r, x, y, z))
                }
            }
            S::ConstAdd(i, c) => self.sample_value(ctx, *i, x, y, z) + c,
            S::ConstSub(c, i) => c - self.sample_value(ctx, *i, x, y, z),
            S::ConstMul(i, c) => self.sample_value(ctx, *i, x, y, z) * c,
            S::ConstDiv(c, i) => c / self.sample_value(ctx, *i, x, y, z),
            S::ConstMin(i, c) => min_f32(self.sample_value(ctx, *i, x, y, z), *c),
            S::ConstMax(i, c) => max_f32(self.sample_value(ctx, *i, x, y, z), *c),
            S::PowConstBase(b, e) => b.powf(self.sample_value(ctx, *e, x, y, z) as f64) as f32,
            S::PowConstExp(b, e) => (self.sample_value(ctx, *b, x, y, z) as f64).powf(*e) as f32,
            S::Pow(b, e) => {
                let base = self.sample_value(ctx, *b, x, y, z) as f64;
                base.powf(self.sample_value(ctx, *e, x, y, z) as f64) as f32
            }
            S::Clamp(i, min, max) => clamp(self.sample_value(ctx, *i, x, y, z), *min, *max),
            S::Lerp(a, f, s2) => {
                let alpha = self.sample_value(ctx, *a, x, y, z);
                if alpha == 0.0 {
                    self.sample_value(ctx, *f, x, y, z)
                } else if alpha == 1.0 {
                    self.sample_value(ctx, *s2, x, y, z)
                } else {
                    let first = self.sample_value(ctx, *f, x, y, z);
                    lerp(alpha, first, self.sample_value(ctx, *s2, x, y, z))
                }
            }
            S::LerpConstFirst(a, first, s2) => {
                let alpha = self.sample_value(ctx, *a, x, y, z);
                if alpha == 0.0 {
                    *first
                } else if alpha == 1.0 {
                    self.sample_value(ctx, *s2, x, y, z)
                } else {
                    lerp(alpha, *first, self.sample_value(ctx, *s2, x, y, z))
                }
            }
            S::LerpConstSecond(a, f, second) => {
                let alpha = self.sample_value(ctx, *a, x, y, z);
                if alpha == 0.0 {
                    self.sample_value(ctx, *f, x, y, z)
                } else if alpha == 1.0 {
                    *second
                } else {
                    lerp(alpha, self.sample_value(ctx, *f, x, y, z), *second)
                }
            }
            S::RangeConst {
                input,
                min,
                max,
                in_range,
                out_of_range,
            } => {
                let v = self.sample_value(ctx, *input, x, y, z);
                if v >= *min && v < *max {
                    *in_range
                } else {
                    *out_of_range
                }
            }
            S::Range {
                input,
                min,
                max,
                in_range,
                out_of_range,
            } => {
                let v = self.sample_value(ctx, *input, x, y, z);
                if v >= *min && v < *max {
                    self.sample_value(ctx, *in_range, x, y, z)
                } else {
                    self.sample_value(ctx, *out_of_range, x, y, z)
                }
            }
            S::IntervalSingle {
                input,
                threshold,
                below,
                above,
            } => {
                let v = self.sample_value(ctx, *input, x, y, z);
                if v < *threshold {
                    self.sample_value(ctx, *below, x, y, z)
                } else {
                    self.sample_value(ctx, *above, x, y, z)
                }
            }
            S::Interval {
                input,
                thresholds,
                samplers,
            } => {
                let v = self.sample_value(ctx, *input, x, y, z);
                let i = thresholds.iter().position(|&t| v < t).unwrap_or(samplers.len() - 1);
                self.sample_value(ctx, samplers[i], x, y, z)
            }
            S::RoundInt(t, i) => round_to_integer(self.sample_value(ctx, *i, x, y, z), *t),
            S::Round(t, i, m) => {
                let input = self.sample_value(ctx, *i, x, y, z);
                let multiple = self.sample_value(ctx, *m, x, y, z);
                round_apply(*t, input, multiple)
            }
            S::SliceX(i, sx) => self.sample_value(ctx, *i, *sx, y, z),
            S::SliceY(i, sy) => self.sample_value(ctx, *i, x, *sy, z),
            S::SliceZ(i, sz) => self.sample_value(ctx, *i, x, y, *sz),
            S::SliceXz(i, sx, sz) => self.sample_value(ctx, *i, *sx, y, *sz),
            S::FindTop {
                density,
                upper,
                lower,
                cell,
            } => {
                let upper_bound = self.sample_value(ctx, *upper, x, y, z);
                self.find_surface_from(ctx, *density, *lower, *cell, x, z, upper_bound)
            }
            S::Spline(f) => spline::sample_value(self, ctx, &self.splines[*f as usize], x, y, z),
            S::Interpolated { input, cxz, cy, .. } => {
                let xi = floor_mod(x, *cxz);
                let yi = floor_mod(y, *cy);
                let zi = floor_mod(z, *cxz);
                if xi == 0 && yi == 0 && zi == 0 {
                    self.sample_value(ctx, *input, x, y, z)
                } else {
                    let v = Volume::new([2, 2, 2], [x - xi, y - yi, z - zi], [*cxz, *cy, *cxz]);
                    let b = self.scratch(ctx, *input, &v);
                    let r = lerp3(
                        xi as f32 / *cxz as f32,
                        yi as f32 / *cy as f32,
                        zi as f32 / *cxz as f32,
                        b[v.index(0, 0, 0)],
                        b[v.index(1, 0, 0)],
                        b[v.index(0, 1, 0)],
                        b[v.index(1, 1, 0)],
                        b[v.index(0, 0, 1)],
                        b[v.index(1, 0, 1)],
                        b[v.index(0, 1, 1)],
                        b[v.index(1, 1, 1)],
                    );
                    ctx.arena.give(b);
                    r
                }
            }
            S::Cache { id, input } => {
                if !ctx.caches_enabled {
                    return self.sample_value(ctx, *input, x, y, z);
                }
                let key = block_pos_as_long(x, y, z);
                let cell = &ctx.caches[*id as usize];
                if cell.value_key == key && !cell.value.is_nan() {
                    return cell.value;
                }
                if let (Some(v), Some(b)) = (&cell.volume, &cell.buffer) {
                    if let Some(i) = v.index_of_block(x, y, z) {
                        return b[i];
                    }
                }
                let value = self.sample_value(ctx, *input, x, y, z);
                let cell = &mut ctx.caches[*id as usize];
                cell.value_key = key;
                cell.value = value;
                value
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn find_surface_from(
        &self,
        ctx: &mut Ctx32,
        density: Sid,
        lower: i32,
        cell: i32,
        x: i32,
        z: i32,
        upper: f32,
    ) -> f32 {
        let top_y = ((upper / cell as f32) as f64).floor() as i32;
        let top_y = top_y.wrapping_mul(cell);
        if top_y <= lower {
            return lower as f32;
        }
        let mut probe = top_y;
        while probe >= lower {
            if self.sample_value(ctx, density, x, probe, z) > 0.0 {
                return probe as f32;
            }
            probe = probe.wrapping_sub(cell);
            if probe > top_y {
                break;
            }
        }
        lower as f32
    }

    /// `DensitySampler.sampleVolume`: `out.len()` must equal `v.len()`.
    pub fn sample_volume(&self, ctx: &mut Ctx32, s: Sid, out: &mut [f32], v: &Volume) {
        debug_assert_eq!(out.len(), v.len());
        match &self.samplers[s as usize] {
            S::Const(c) => out.fill(*c),
            S::Noise { noise, xz, y } => {
                out.fill(0.0);
                self.noises[*noise as usize].add_to_volume(out, v, *xz, *y, 1.0);
            }
            S::NoiseXz { sx, sz, noise, xz, y } => {
                self.sample_volume(ctx, *sx, out, v);
                let shift_z = self.scratch(ctx, *sz, v);
                let n = &self.noises[*noise as usize];
                let mut index = 0usize;
                for zi in 0..v.size_z {
                    let base_z = v.block_z(zi) as f64 * xz;
                    for xi in 0..v.size_x {
                        let base_x = v.block_x(xi) as f64 * xz;
                        for yi in 0..v.size_y {
                            let nx = base_x + out[index] as f64;
                            let ny = v.block_y(yi) as f64 * y;
                            let nz = base_z + shift_z[index] as f64;
                            out[index] = n.get32(nx, ny, nz);
                            index += 1;
                        }
                    }
                }
                ctx.arena.give(shift_z);
            }
            S::NoiseXyz {
                sx,
                sy,
                sz,
                noise,
                xz,
                y,
            } => {
                self.sample_volume(ctx, *sx, out, v);
                let shift_y = self.scratch(ctx, *sy, v);
                let shift_z = self.scratch(ctx, *sz, v);
                let n = &self.noises[*noise as usize];
                let mut index = 0usize;
                for zi in 0..v.size_z {
                    let base_z = v.block_z(zi) as f64 * xz;
                    for xi in 0..v.size_x {
                        let base_x = v.block_x(xi) as f64 * xz;
                        for yi in 0..v.size_y {
                            let nx = base_x + out[index] as f64;
                            let ny = v.block_y(yi) as f64 * y + shift_y[index] as f64;
                            let nz = base_z + shift_z[index] as f64;
                            out[index] = n.get32(nx, ny, nz);
                            index += 1;
                        }
                    }
                }
                ctx.arena.give(shift_z);
                ctx.arena.give(shift_y);
            }
            S::ShiftB { noise } => {
                let t = Volume::new([v.size_z, v.size_x, 1], [v.min_z, v.min_x, 0], [v.step_z, v.step_x, 1]);
                let mut tb = ctx.arena.take(t.len());
                let tb_slice = &mut tb[..t.len()];
                tb_slice.fill(0.0);
                self.noises[*noise as usize].add_to_volume(tb_slice, &t, 0.25, 0.25, 4.0);
                let sy = v.size_y as usize;
                for z in 0..v.size_z {
                    for x in 0..v.size_x {
                        let value = tb_slice[t.index(z, x, 0)];
                        let start = v.index(x, 0, z);
                        out[start..start + sy].fill(value);
                    }
                }
                ctx.arena.give(tb);
            }
            S::EndIslands => {
                let sy = v.size_y as usize;
                for z in 0..v.size_z {
                    let bz = v.block_z(z);
                    for x in 0..v.size_x {
                        let value = self.sample_value(ctx, s, v.block_x(x), 0, bz);
                        let start = v.index(x, 0, z);
                        out[start..start + sy].fill(value);
                    }
                }
            }
            S::Gradient {
                axis,
                mode,
                from,
                range,
                from_value,
                factor,
            } => {
                let g = |c: i32| gradient_compute(*mode, *from, *range, *from_value, *factor, c);
                let sy = v.size_y as usize;
                match axis {
                    Axis::X => {
                        for x in 0..v.size_x {
                            let value = g(v.block_x(x));
                            for z in 0..v.size_z {
                                let start = v.index(x, 0, z);
                                out[start..start + sy].fill(value);
                            }
                        }
                    }
                    Axis::Y => {
                        for y in 0..v.size_y {
                            let value = g(v.block_y(y));
                            for z in 0..v.size_z {
                                for x in 0..v.size_x {
                                    out[v.index(x, y, z)] = value;
                                }
                            }
                        }
                    }
                    Axis::Z => {
                        let layer = (v.size_x * v.size_y) as usize;
                        for z in 0..v.size_z {
                            let value = g(v.block_z(z));
                            let start = v.index(0, 0, z);
                            out[start..start + layer].fill(value);
                        }
                    }
                }
            }
            S::Distance { .. } => self.naive(ctx, s, out, v),
            S::Beardifier => {
                let beard = core::mem::take(&mut ctx.beard);
                beard.sample_volume32(out, v);
                ctx.beard = beard;
            }
            S::Abs(i) => self.map(ctx, *i, out, v, f32::abs),
            S::Square(i) => self.map(ctx, *i, out, v, |a| a * a),
            S::Cube(i) => self.map(ctx, *i, out, v, |a| a * a * a),
            S::Sqrt(i) => self.map(ctx, *i, out, v, f32::sqrt),
            S::LeakyRelu(i, f) => {
                let f = *f;
                self.map(ctx, *i, out, v, |a| if a > 0.0 { a } else { a * f })
            }
            S::Reciprocal(i) => self.map(ctx, *i, out, v, |a| 1.0 / a),
            S::Negate(i) => self.map(ctx, *i, out, v, |a| -a),
            S::Squeeze(i) => self.map(ctx, *i, out, v, squeeze),
            S::Log(i) => self.map(ctx, *i, out, v, |a| (a as f64).ln() as f32),
            S::Sign(i) => self.map(ctx, *i, out, v, signum_f32),
            S::Add(l, r) => {
                self.sample_volume(ctx, *l, out, v);
                let right = self.scratch(ctx, *r, v);
                simd::add_assign(out, &right[..out.len()]);
                ctx.arena.give(right);
            }
            S::Sub(l, r) => {
                self.sample_volume(ctx, *l, out, v);
                let right = self.scratch(ctx, *r, v);
                simd::sub_assign(out, &right[..out.len()]);
                ctx.arena.give(right);
            }
            S::Mul(l, r) => {
                self.sample_volume(ctx, *l, out, v);
                let right = self.scratch(ctx, *r, v);
                simd::mul_assign(out, &right[..out.len()]);
                ctx.arena.give(right);
            }
            S::Div(l, r) => {
                self.sample_volume(ctx, *l, out, v);
                let right = self.scratch(ctx, *r, v);
                for (o, &d) in out.iter_mut().zip(right.iter()) {
                    *o /= d;
                }
                ctx.arena.give(right);
            }
            S::Min(l, r, _) => {
                self.sample_volume(ctx, *l, out, v);
                let right = self.scratch(ctx, *r, v);
                for (o, &rv) in out.iter_mut().zip(right.iter()) {
                    if rv < *o {
                        *o = rv;
                    }
                }
                ctx.arena.give(right);
            }
            S::Max(l, r, _) => {
                self.sample_volume(ctx, *l, out, v);
                let right = self.scratch(ctx, *r, v);
                for (o, &rv) in out.iter_mut().zip(right.iter()) {
                    if rv > *o {
                        *o = rv;
                    }
                }
                ctx.arena.give(right);
            }
            S::ConstAdd(i, c) => {
                self.sample_volume(ctx, *i, out, v);
                simd::add_scalar(out, *c);
            }
            S::ConstSub(c, i) => {
                let c = *c;
                self.map(ctx, *i, out, v, |a| c - a)
            }
            S::ConstMul(i, c) => {
                self.sample_volume(ctx, *i, out, v);
                simd::mul_scalar(out, *c);
            }
            S::ConstDiv(c, i) => {
                let c = *c;
                self.map(ctx, *i, out, v, |a| c / a)
            }
            S::ConstMin(i, c) => {
                let c = *c;
                self.map(ctx, *i, out, v, |a| if c < a { c } else { a })
            }
            S::ConstMax(i, c) => {
                let c = *c;
                self.map(ctx, *i, out, v, |a| if c > a { c } else { a })
            }
            S::PowConstBase(b, e) => {
                let b = *b;
                self.map(ctx, *e, out, v, |a| b.powf(a as f64) as f32)
            }
            S::PowConstExp(b, e) => {
                let e = *e;
                self.map(ctx, *b, out, v, |a| (a as f64).powf(e) as f32)
            }
            S::Pow(b, e) => {
                self.sample_volume(ctx, *b, out, v);
                let exponent = self.scratch(ctx, *e, v);
                for (o, &ev) in out.iter_mut().zip(exponent.iter()) {
                    *o = (*o as f64).powf(ev as f64) as f32;
                }
                ctx.arena.give(exponent);
            }
            S::Clamp(i, min, max) => {
                let (min, max) = (*min, *max);
                self.map(ctx, *i, out, v, |a| clamp(a, min, max))
            }
            S::Lerp(a, f, s2) => {
                self.sample_volume(ctx, *a, out, v);
                let first = self.scratch(ctx, *f, v);
                let second = self.scratch(ctx, *s2, v);
                for i in 0..out.len() {
                    out[i] = lerp_select(out[i], first[i], second[i]);
                }
                ctx.arena.give(second);
                ctx.arena.give(first);
            }
            S::LerpConstFirst(a, first, s2) => {
                self.sample_volume(ctx, *a, out, v);
                let second = self.scratch(ctx, *s2, v);
                for i in 0..out.len() {
                    out[i] = lerp_select(out[i], *first, second[i]);
                }
                ctx.arena.give(second);
            }
            S::LerpConstSecond(a, f, second) => {
                self.sample_volume(ctx, *a, out, v);
                let first = self.scratch(ctx, *f, v);
                for i in 0..out.len() {
                    out[i] = lerp_select(out[i], first[i], *second);
                }
                ctx.arena.give(first);
            }
            S::RangeConst {
                input,
                min,
                max,
                in_range,
                out_of_range,
            } => {
                let (min, max, a, b) = (*min, *max, *in_range, *out_of_range);
                self.map(ctx, *input, out, v, |x| if x >= min && x < max { a } else { b })
            }
            S::Range {
                input,
                min,
                max,
                in_range,
                out_of_range,
            } => {
                self.sample_volume(ctx, *in_range, out, v);
                let inputs = self.scratch(ctx, *input, v);
                let outside = self.scratch(ctx, *out_of_range, v);
                for i in 0..out.len() {
                    let x = inputs[i];
                    if !(x >= *min) || !(x < *max) {
                        out[i] = outside[i];
                    }
                }
                ctx.arena.give(outside);
                ctx.arena.give(inputs);
            }
            S::IntervalSingle {
                input,
                threshold,
                below,
                above,
            } => {
                self.sample_volume(ctx, *input, out, v);
                let b = self.scratch(ctx, *below, v);
                let a = self.scratch(ctx, *above, v);
                for i in 0..out.len() {
                    out[i] = if out[i] < *threshold { b[i] } else { a[i] };
                }
                ctx.arena.give(a);
                ctx.arena.give(b);
            }
            S::Interval {
                input,
                thresholds,
                samplers,
            } => {
                self.sample_volume(ctx, *input, out, v);
                let buffers: Vec<Vec<f32>> = samplers.iter().map(|&sm| self.scratch(ctx, sm, v)).collect();
                // Not a copy: every lane picks its own buffer.
                #[allow(clippy::manual_memcpy)]
                for i in 0..out.len() {
                    let x = out[i];
                    let k = thresholds.iter().position(|&t| x < t).unwrap_or(samplers.len() - 1);
                    out[i] = buffers[k][i];
                }
                for b in buffers {
                    ctx.arena.give(b);
                }
            }
            S::RoundInt(t, i) => {
                let t = *t;
                self.map(ctx, *i, out, v, |a| round_to_integer(a, t))
            }
            S::Round(t, i, m) => {
                self.sample_volume(ctx, *i, out, v);
                let multiples = self.scratch(ctx, *m, v);
                for (o, &mv) in out.iter_mut().zip(multiples.iter()) {
                    *o = round_apply(*t, *o, mv);
                }
                ctx.arena.give(multiples);
            }
            S::SliceX(i, sx) => {
                if v.size_x == 1 && v.min_x == *sx {
                    self.sample_volume(ctx, *i, out, v);
                } else {
                    let iv = Volume::new(
                        [1, v.size_y, v.size_z],
                        [*sx, v.min_y, v.min_z],
                        [v.step_x, v.step_y, v.step_z],
                    );
                    let b = self.scratch(ctx, *i, &iv);
                    let mut index = 0usize;
                    for z in 0..v.size_z {
                        for _x in 0..v.size_x {
                            for y in 0..v.size_y {
                                out[index] = b[iv.index(0, y, z)];
                                index += 1;
                            }
                        }
                    }
                    ctx.arena.give(b);
                }
            }
            S::SliceXz(i, sx, sz) => {
                if v.size_x == 1 && v.size_z == 1 && v.min_x == *sx && v.min_z == *sz {
                    self.sample_volume(ctx, *i, out, v);
                } else {
                    let iv = Volume::new([1, v.size_y, 1], [*sx, v.min_y, *sz], [v.step_x, v.step_y, v.step_z]);
                    let b = self.scratch(ctx, *i, &iv);
                    let sy = v.size_y as usize;
                    for y in 0..v.size_y {
                        let value = b[iv.index(0, y, 0)];
                        let mut index = v.index(0, y, 0);
                        for _ in 0..(v.size_x * v.size_z) {
                            out[index] = value;
                            index += sy;
                        }
                    }
                    ctx.arena.give(b);
                }
            }
            S::SliceY(i, sy) => {
                if v.size_y == 1 && v.min_y == *sy {
                    self.sample_volume(ctx, *i, out, v);
                } else {
                    let iv = Volume::new(
                        [v.size_x, 1, v.size_z],
                        [v.min_x, *sy, v.min_z],
                        [v.step_x, v.step_y, v.step_z],
                    );
                    let b = self.scratch(ctx, *i, &iv);
                    let n = v.size_y as usize;
                    for z in 0..v.size_z {
                        for x in 0..v.size_x {
                            let value = b[iv.index(x, 0, z)];
                            let start = v.index(x, 0, z);
                            out[start..start + n].fill(value);
                        }
                    }
                    ctx.arena.give(b);
                }
            }
            S::SliceZ(i, sz) => {
                if v.size_z == 1 && v.min_z == *sz {
                    self.sample_volume(ctx, *i, out, v);
                } else {
                    let iv = Volume::new(
                        [v.size_x, v.size_y, 1],
                        [v.min_x, v.min_y, *sz],
                        [v.step_x, v.step_y, v.step_z],
                    );
                    let b = self.scratch(ctx, *i, &iv);
                    let mut index = 0usize;
                    for _z in 0..v.size_z {
                        for x in 0..v.size_x {
                            for y in 0..v.size_y {
                                out[index] = b[iv.index(x, y, 0)];
                                index += 1;
                            }
                        }
                    }
                    ctx.arena.give(b);
                }
            }
            S::FindTop {
                density,
                upper,
                lower,
                cell,
            } => {
                assert_eq!(v.size_y, 1, "find_top_surface samples one layer");
                self.sample_volume(ctx, *upper, out, v);
                let mut index = 0usize;
                for z in 0..v.size_z {
                    let bz = v.block_z(z);
                    for x in 0..v.size_x {
                        let bx = v.block_x(x);
                        let upper_bound = out[index];
                        out[index] = self.find_surface_from(ctx, *density, *lower, *cell, bx, bz, upper_bound);
                        index += 1;
                    }
                }
            }
            S::Spline(f) => spline::sample_volume(self, ctx, &self.splines[*f as usize], out, v),
            S::Interpolated {
                input,
                cxz,
                cy,
                inv_xz,
                inv_y,
            } => {
                let (cxz, cy) = (*cxz, *cy);
                if (v.step_x == cxz || v.size_x == 1)
                    && (v.step_y == cy || v.size_y == 1)
                    && (v.step_z == cxz || v.size_z == 1)
                    && floor_mod(v.min_x, cxz) == 0
                    && floor_mod(v.min_y, cy) == 0
                    && floor_mod(v.min_z, cxz) == 0
                {
                    self.sample_volume(ctx, *input, out, v);
                } else if v.step_x == 1 && v.step_y == 1 && v.step_z == 1 {
                    self.interpolate_blocks(ctx, *input, cxz, cy, *inv_xz, *inv_y, out, v);
                } else {
                    let bv = Volume::blocks(
                        [v.size_x * v.step_x, v.size_y * v.step_y, v.size_z * v.step_z],
                        [v.min_x, v.min_y, v.min_z],
                    );
                    let mut b = ctx.arena.take(bv.len());
                    self.interpolate_blocks(ctx, *input, cxz, cy, *inv_xz, *inv_y, &mut b[..bv.len()], &bv);
                    for z in 0..v.size_z {
                        for x in 0..v.size_x {
                            for y in 0..v.size_y {
                                out[v.index(x, y, z)] = b[bv.index(x * v.step_x, y * v.step_y, z * v.step_z)];
                            }
                        }
                    }
                    ctx.arena.give(b);
                }
            }
            S::Cache { id, input } => {
                if !ctx.caches_enabled {
                    self.sample_volume(ctx, *input, out, v);
                    return;
                }
                let id = *id as usize;
                if ctx.caches[id].buffer.is_none() || ctx.caches[id].volume != Some(*v) {
                    if let Some(old) = ctx.caches[id].buffer.take() {
                        ctx.arena.give(old);
                    }
                    ctx.caches[id].volume = Some(*v);
                    let mut b = ctx.arena.take(v.len());
                    self.sample_volume(ctx, *input, &mut b[..v.len()], v);
                    ctx.caches[id].buffer = Some(b);
                }
                out.copy_from_slice(&ctx.caches[id].buffer.as_ref().expect("cache buffer")[..v.len()]);
            }
        }
    }

    /// `DensitySampler.sampleVolumeNaive`.
    fn naive(&self, ctx: &mut Ctx32, s: Sid, out: &mut [f32], v: &Volume) {
        let mut index = 0usize;
        for z in 0..v.size_z {
            let bz = v.block_z(z);
            for x in 0..v.size_x {
                let bx = v.block_x(x);
                for y in 0..v.size_y {
                    out[index] = self.sample_value(ctx, s, bx, v.block_y(y), bz);
                    index += 1;
                }
            }
        }
    }

    /// `InterpolatedFunction.Sampler.sampleWithBlockStep`.
    #[allow(clippy::too_many_arguments)]
    fn interpolate_blocks(
        &self,
        ctx: &mut Ctx32,
        input: Sid,
        cxz: i32,
        cy: i32,
        inv_xz: f32,
        inv_y: f32,
        out: &mut [f32],
        v: &Volume,
    ) {
        let min_cx = floor_div(v.min_x, cxz);
        let min_cy = floor_div(v.min_y, cy);
        let min_cz = floor_div(v.min_z, cxz);
        let count_x = floor_div(v.max_x(), cxz) - min_cx + 1;
        let count_y = floor_div(v.max_y(), cy) - min_cy + 1;
        let count_z = floor_div(v.max_z(), cxz) - min_cz + 1;
        let cell_volume = Volume::new(
            [
                if floor_mod(v.max_x(), cxz) == 0 {
                    count_x
                } else {
                    count_x + 1
                },
                if floor_mod(v.max_y(), cy) == 0 {
                    count_y
                } else {
                    count_y + 1
                },
                if floor_mod(v.max_z(), cxz) == 0 {
                    count_z
                } else {
                    count_z + 1
                },
            ],
            [min_cx * cxz, min_cy * cy, min_cz * cxz],
            [cxz, cy, cxz],
        );
        let cells = self.scratch(ctx, input, &cell_volume);
        let cv = &cell_volume;
        for cz in 0..count_z {
            let nz = (cz + 1).min(cv.size_z - 1);
            for cx in 0..count_x {
                let nx = (cx + 1).min(cv.size_x - 1);
                let mut v000 = cells[cv.index(cx, 0, cz)];
                let mut v100 = cells[cv.index(nx, 0, cz)];
                let mut v001 = cells[cv.index(cx, 0, nz)];
                let mut v101 = cells[cv.index(nx, 0, nz)];
                for cyi in 0..count_y {
                    let ny = (cyi + 1).min(cv.size_y - 1);
                    let v010 = cells[cv.index(cx, ny, cz)];
                    let v110 = cells[cv.index(nx, ny, cz)];
                    let v011 = cells[cv.index(cx, ny, nz)];
                    let v111 = cells[cv.index(nx, ny, nz)];
                    let corners = [v000, v100, v010, v110, v001, v101, v011, v111];
                    fill_cell(out, v, cv, [cx, cyi, cz], [cxz, cy], [inv_xz, inv_y], corners);
                    v000 = v010;
                    v100 = v110;
                    v001 = v011;
                    v101 = v111;
                }
            }
        }
        ctx.arena.give(cells);
    }
}

/// `InterpolatedFunction.Sampler.fillCell`; corners are `v000, v100, v010, v110, v001, v101, v011, v111`.
#[inline]
fn fill_cell(out: &mut [f32], ov: &Volume, cv: &Volume, cell: [i32; 3], size: [i32; 2], inv: [f32; 2], c: [f32; 8]) {
    let [v000, v100, v010, v110, v001, v101, v011, v111] = c;
    let (cxz, cy) = (size[0], size[1]);
    let ox = cv.block_x(cell[0]) - ov.min_x;
    let oy = cv.block_y(cell[1]) - ov.min_y;
    let oz = cv.block_z(cell[2]) - ov.min_z;
    let x0 = 0.max(-ox);
    let y0 = 0.max(-oy);
    let z0 = 0.max(-oz);
    let x1 = cxz.min(ov.size_x - ox) - 1;
    let y1 = cy.min(ov.size_y - oy) - 1;
    let z1 = cxz.min(ov.size_z - oz) - 1;
    for z in z0..=z1 {
        let alpha_z = z as f32 * inv[0];
        let v00 = lerp(alpha_z, v000, v001);
        let v01 = lerp(alpha_z, v010, v011);
        let v10 = lerp(alpha_z, v100, v101);
        let v11 = lerp(alpha_z, v110, v111);
        for x in x0..=x1 {
            let alpha_x = x as f32 * inv[0];
            let v0 = lerp(alpha_x, v00, v10);
            let v1 = lerp(alpha_x, v01, v11);
            let step = (v1 - v0) * inv[1];
            let mut value = v0 + step * y0 as f32;
            let mut index = ov.index(ox + x, oy + y0, oz + z);
            #[allow(clippy::explicit_counter_loop)]
            for _ in y0..=y1 {
                out[index] = value;
                index += 1;
                value += step;
            }
        }
    }
}

#[cfg(test)]
mod tests;
