//! The `noise_points` job: build one noise from a seed and sample it at N
//! positions.
//!
//! Payload (little-endian; "pad8" skips to the next multiple of 8 bytes from
//! the payload start):
//!
//! ```text
//! u8   semantics     kernelSemantics.code of the profile (port/versions/<id>.json)
//! u8   noise         0 = NormalNoise(first_octave, amplitudes)
//!                    1 = NormalNoise recipe (26.3 codec fields; synth f32 only)
//!                    2 = BlendedNoise
//! u8   random        0 = XoroshiroRandomSource(seed), 1 = LegacyRandomSource(seed)
//! u8   reserved      0
//! u32  point_count
//! i64  seed
//! u32  fork_len      > 0: re-seed with forkPositional().fromHashOf(fork)
//! u8   fork[fork_len] UTF-8, then pad8
//! noise 0: i32 first_octave, u32 count, pad8, f64 amplitudes[count]
//! noise 1: f64 base_amplitude, i32 base_octave, i32 octave_count,
//!          u8 normalize (0 disabled, 1 enabled, 2 legacy), pad to 4,
//!          u32 modifier_count (0 or octave_count), pad8, f64 modifiers[]
//! noise 2: f64 xz_scale, y_scale, xz_factor, y_factor, smear_scale_multiplier
//! pad8, f64 xyz[3 * point_count]   (blended: integral block coordinates)
//! ```
//!
//! Result: `f64[point_count]`; 26.3 float results are widened exactly.
//! Normal noise uses `NormalNoise.create` (26.3: `createParity(...).create`
//! for noise 0, `recipe.create` for noise 1); blended noise uses
//! `new BlendedNoise(random, ...).compute` before 26.3 and
//! `compileSampler(random).sampleValue` on 26.3.
//!
//! `port/web/kernels/noise-job.js` writes the same layout on the page; keep
//! the two in step.

use gaius_kernel_abi::{KernelError, Reader, Status};
use gaius_noise::batch::{PointSampler, ProfileNormalNoise};
use gaius_noise::synth32::{BlendedNoiseParameters, NormalNoiseParameters, Normalization};
use gaius_noise::{synth64, RandomKind, RandomSource, Semantics};

/// Upper bounds that keep a hostile payload from asking for unbounded work.
const MAX_AMPLITUDES: u32 = 256;
const MAX_FORK_LEN: u32 = 4096;
/// Cap one request's output and sampling work before any backing vectors are allocated.
const MAX_POINTS: u32 = 1 << 20;

#[derive(Clone, Debug, PartialEq)]
pub enum NoiseSpec {
    Normal { first_octave: i32, amplitudes: Vec<f64> },
    NormalRecipe(NormalNoiseParameters),
    Blended([f64; 5]),
}

#[derive(Clone, Debug, PartialEq)]
pub struct NoisePointsJob {
    pub semantics: Semantics,
    pub random: RandomKind,
    pub seed: i64,
    pub fork: Option<String>,
    pub noise: NoiseSpec,
    pub xyz: Vec<f64>,
}

fn bad(message: &str) -> KernelError {
    KernelError::new(Status::BadPayload, message)
}

impl NoisePointsJob {
    pub fn decode(payload: &[u8]) -> Result<NoisePointsJob, KernelError> {
        let mut r = Reader::new(payload);
        let semantics = Semantics::from_code(r.u8()?).ok_or_else(|| bad("unknown kernel semantics code"))?;
        let noise_code = r.u8()?;
        let random = match r.u8()? {
            0 => RandomKind::Xoroshiro,
            1 => RandomKind::Legacy,
            _ => return Err(bad("unknown random source")),
        };
        r.u8()?;
        let point_count = r.u32()?;
        if point_count > MAX_POINTS {
            return Err(bad("point count exceeds the kernel budget"));
        }
        let seed = r.i64()?;
        let fork_len = r.u32()?;
        if fork_len > MAX_FORK_LEN {
            return Err(bad("fork name too long"));
        }
        let fork = match fork_len {
            0 => None,
            len => Some(String::from(
                core::str::from_utf8(r.take(len as usize)?).map_err(|_| bad("fork name is not UTF-8"))?,
            )),
        };
        r.align(8)?;
        let noise = match noise_code {
            0 => {
                let first_octave = r.i32()?;
                let count = r.u32()?;
                if count == 0 || count > MAX_AMPLITUDES {
                    return Err(bad("amplitude count out of range"));
                }
                r.align(8)?;
                let amplitudes = (0..count).map(|_| r.f64()).collect::<Result<Vec<_>, _>>()?;
                NoiseSpec::Normal {
                    first_octave,
                    amplitudes,
                }
            }
            1 => {
                let base_amplitude = r.f64()?;
                let base_octave = r.i32()?;
                let octave_count = r.i32()?;
                let normalize = match r.u8()? {
                    0 => Normalization::Disabled,
                    1 => Normalization::Enabled,
                    2 => Normalization::Legacy,
                    _ => return Err(bad("unknown normalization")),
                };
                r.align(4)?;
                let modifier_count = r.u32()?;
                if !(-32..=32).contains(&base_octave) || !(1..=32).contains(&octave_count) {
                    return Err(bad("octave range out of codec bounds"));
                }
                if modifier_count != 0 && modifier_count != octave_count as u32 {
                    return Err(bad("modifier count must be 0 or octave_count"));
                }
                r.align(8)?;
                let amplitude_modifiers = (0..modifier_count).map(|_| r.f64()).collect::<Result<Vec<_>, _>>()?;
                NoiseSpec::NormalRecipe(NormalNoiseParameters {
                    base_amplitude,
                    base_octave,
                    octave_count,
                    normalize,
                    amplitude_modifiers,
                })
            }
            2 => NoiseSpec::Blended([r.f64()?, r.f64()?, r.f64()?, r.f64()?, r.f64()?]),
            _ => return Err(bad("unknown noise")),
        };
        r.align(8)?;
        let values = (point_count as usize)
            .checked_mul(3)
            .ok_or_else(|| bad("too many points"))?;
        if r.remaining() != values * 8 {
            return Err(KernelError::new(
                Status::Truncated,
                "position block does not match point_count",
            ));
        }
        let xyz = (0..values).map(|_| r.f64()).collect::<Result<Vec<_>, _>>()?;
        Ok(NoisePointsJob {
            semantics,
            random,
            seed,
            fork,
            noise,
            xyz,
        })
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let pad = |out: &mut Vec<u8>, align: usize| out.resize(out.len().div_ceil(align) * align, 0);
        let noise_code = match self.noise {
            NoiseSpec::Normal { .. } => 0u8,
            NoiseSpec::NormalRecipe(_) => 1,
            NoiseSpec::Blended(_) => 2,
        };
        let random_code = match self.random {
            RandomKind::Xoroshiro => 0u8,
            RandomKind::Legacy => 1,
        };
        out.extend_from_slice(&[self.semantics.code, noise_code, random_code, 0]);
        out.extend_from_slice(&((self.xyz.len() / 3) as u32).to_le_bytes());
        out.extend_from_slice(&self.seed.to_le_bytes());
        let fork = self.fork.as_deref().unwrap_or("");
        out.extend_from_slice(&(fork.len() as u32).to_le_bytes());
        out.extend_from_slice(fork.as_bytes());
        pad(&mut out, 8);
        match &self.noise {
            NoiseSpec::Normal {
                first_octave,
                amplitudes,
            } => {
                out.extend_from_slice(&first_octave.to_le_bytes());
                out.extend_from_slice(&(amplitudes.len() as u32).to_le_bytes());
                pad(&mut out, 8);
                amplitudes.iter().for_each(|a| out.extend_from_slice(&a.to_le_bytes()));
            }
            NoiseSpec::NormalRecipe(p) => {
                out.extend_from_slice(&p.base_amplitude.to_le_bytes());
                out.extend_from_slice(&p.base_octave.to_le_bytes());
                out.extend_from_slice(&p.octave_count.to_le_bytes());
                out.push(match p.normalize {
                    Normalization::Disabled => 0,
                    Normalization::Enabled => 1,
                    Normalization::Legacy => 2,
                });
                pad(&mut out, 4);
                out.extend_from_slice(&(p.amplitude_modifiers.len() as u32).to_le_bytes());
                pad(&mut out, 8);
                p.amplitude_modifiers
                    .iter()
                    .for_each(|a| out.extend_from_slice(&a.to_le_bytes()));
            }
            NoiseSpec::Blended(fields) => fields.iter().for_each(|f| out.extend_from_slice(&f.to_le_bytes())),
        }
        pad(&mut out, 8);
        self.xyz.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        out
    }

    fn random_source(&self) -> RandomSource {
        let mut random = RandomSource::new(self.random, self.seed);
        if let Some(name) = &self.fork {
            random = random.fork_positional().from_hash_of(name);
        }
        random
    }

    /// Builds the noise and samples every position.
    pub fn run(&self) -> Result<Vec<f64>, KernelError> {
        let mut random = self.random_source();
        let mut out = vec![0.0; self.xyz.len() / 3];
        match &self.noise {
            NoiseSpec::Normal {
                first_octave,
                amplitudes,
            } => {
                ProfileNormalNoise::parity(self.semantics, &mut random, *first_octave, amplitudes)
                    .sample_points(&self.xyz, &mut out);
            }
            NoiseSpec::NormalRecipe(params) => {
                if !self.semantics.uses_synth32() {
                    return Err(bad("normal noise recipes need the float synth classes"));
                }
                ProfileNormalNoise::from_parameters(params.clone(), &mut random).sample_points(&self.xyz, &mut out);
            }
            NoiseSpec::Blended(f) => {
                let blocks = self
                    .xyz
                    .iter()
                    .map(|&v| {
                        if v == (v as i32) as f64 {
                            Ok(v as i32)
                        } else {
                            Err(bad("blended noise needs integral block positions"))
                        }
                    })
                    .collect::<Result<Vec<i32>, _>>()?;
                if self.semantics.uses_synth32() {
                    let params = BlendedNoiseParameters {
                        xz_scale: f[0],
                        y_scale: f[1],
                        xz_factor: f[2],
                        y_factor: f[3],
                        smear_scale_multiplier: f[4],
                    };
                    let sampler = params.compile(&mut random);
                    for (o, p) in out.iter_mut().zip(blocks.as_chunks::<3>().0) {
                        *o = sampler.sample_value(p[0], p[1], p[2]) as f64;
                    }
                } else {
                    let noise = synth64::BlendedNoise::new(
                        &mut random,
                        f[0],
                        f[1],
                        f[2],
                        f[3],
                        f[4],
                        self.semantics.floor_mode,
                    );
                    for (o, p) in out.iter_mut().zip(blocks.as_chunks::<3>().0) {
                        *o = noise.compute(p[0], p[1], p[2]);
                    }
                }
            }
        }
        Ok(out)
    }
}

/// `run_noise_points` handler: payload in, `f64` results out.
pub fn run_noise_points(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let values = NoisePointsJob::decode(payload)?.run()?;
    let mut out = Vec::with_capacity(values.len() * 8);
    values.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
    Ok(out)
}
