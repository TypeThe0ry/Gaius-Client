//! `synth.BlendedNoise` (26.3): codec parameters compiled into a sampler
//! tree `lerp(clamp(main + 0.5, 0, 1), minLimit, maxLimit)`.

use super::perlin::SmearedPerlinNoise;
use super::stack::{LayerNoise, NoiseStack};
use super::volume::DensityVolume;
use crate::java::pow2;
use crate::mth::float::{clamp, lerp};
use crate::RandomSource;

const BASE_SCALE: f64 = 684.412;
const LIMIT_FACTOR: f64 = 0.9999847412109375;
const MAIN_FACTOR: f64 = 12.75;
const LIMIT_FIRST_OCTAVE: i32 = -15;
const MAIN_FIRST_OCTAVE: i32 = -7;
/// `BlendedNoise.NOISE_SEED`, the name `CompileContext.createRandom` hashes.
pub const NOISE_SEED: &str = "minecraft:terrain";

/// The codec fields of `BlendedNoise`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BlendedNoiseParameters {
    pub xz_scale: f64,
    pub y_scale: f64,
    pub xz_factor: f64,
    pub y_factor: f64,
    pub smear_scale_multiplier: f64,
}

impl BlendedNoiseParameters {
    fn xz_multiplier(&self) -> f64 {
        BASE_SCALE * self.xz_scale
    }

    fn y_multiplier(&self) -> f64 {
        BASE_SCALE * self.y_scale
    }

    /// `compileSampler(random)`.
    pub fn compile(&self, random: &mut RandomSource) -> BlendedNoiseSampler {
        let smear = self.y_multiplier() * self.smear_scale_multiplier;
        let main_smear = smear / self.y_factor;
        let min_limit = create_fbm(random, LIMIT_FIRST_OCTAVE, smear, LIMIT_FACTOR);
        let max_limit = create_fbm(random, LIMIT_FIRST_OCTAVE, smear, LIMIT_FACTOR);
        let main = create_fbm(random, MAIN_FIRST_OCTAVE, main_smear, MAIN_FACTOR);
        let xz = self.xz_multiplier();
        let y = self.y_multiplier();
        BlendedNoiseSampler {
            min_limit: NoiseSampler {
                noise: min_limit,
                xz_scale: xz,
                y_scale: y,
            },
            max_limit: NoiseSampler {
                noise: max_limit,
                xz_scale: xz,
                y_scale: y,
            },
            main: NoiseSampler {
                noise: main,
                xz_scale: xz / self.xz_factor,
                y_scale: y / self.y_factor,
            },
        }
    }
}

/// `BlendedNoise.createFbm(random, firstOctave, smearScale, amplitudeFactor)`.
pub fn create_fbm(random: &mut RandomSource, first_octave: i32, smear_scale: f64, amplitude_factor: f64) -> NoiseStack {
    assert!(first_octave <= 0, "firstOctave>0");
    let octaves = first_octave.wrapping_neg().wrapping_add(1);
    let mut frequency = 1.0;
    let mut amplitude = amplitude_factor / (pow2(octaves) - 1.0);
    let mut builder = NoiseStack::builder();
    for _ in 0..octaves {
        let noise = SmearedPerlinNoise::new(random, smear_scale * frequency);
        builder = builder.add(LayerNoise::SmearedPerlin(noise), frequency, amplitude as f32);
        frequency /= 2.0;
        amplitude *= 2.0;
    }
    builder.build()
}

/// `generator.NoiseFunction.Sampler`.
#[derive(Clone, Debug)]
pub struct NoiseSampler {
    pub noise: NoiseStack,
    pub xz_scale: f64,
    pub y_scale: f64,
}

impl NoiseSampler {
    #[inline]
    pub fn sample_value(&self, x: i32, y: i32, z: i32) -> f32 {
        self.noise.get(
            x as f64 * self.xz_scale,
            y as f64 * self.y_scale,
            z as f64 * self.xz_scale,
        )
    }

    /// `sampleVolume`: clears `buffer`, then adds the noise volume.
    pub fn sample_volume(&self, buffer: &mut [f32], volume: &DensityVolume) {
        buffer.fill(0.0);
        self.noise
            .add_to_volume(buffer, volume, self.xz_scale, self.y_scale, 1.0);
    }
}

/// The compiled `BlendedNoise` sampler tree.
#[derive(Clone, Debug)]
pub struct BlendedNoiseSampler {
    pub min_limit: NoiseSampler,
    pub max_limit: NoiseSampler,
    pub main: NoiseSampler,
}

impl BlendedNoiseSampler {
    /// `LerpFunction.Sampler.sampleValue` over `ClampFunction(ConstAdd(main, 0.5), 0, 1)`.
    pub fn sample_value(&self, x: i32, y: i32, z: i32) -> f32 {
        let alpha = clamp(self.main.sample_value(x, y, z) + 0.5, 0.0, 1.0);
        if alpha == 0.0 {
            self.min_limit.sample_value(x, y, z)
        } else if alpha == 1.0 {
            self.max_limit.sample_value(x, y, z)
        } else {
            lerp(
                alpha,
                self.min_limit.sample_value(x, y, z),
                self.max_limit.sample_value(x, y, z),
            )
        }
    }

    /// `LerpFunction.Sampler.sampleVolume`: all three volumes are filled,
    /// then combined per cell. `buffer.len()` must equal `volume.len()`.
    pub fn sample_volume(&self, buffer: &mut [f32], volume: &DensityVolume) {
        self.main.sample_volume(buffer, volume);
        for value in buffer.iter_mut() {
            *value = clamp(*value + 0.5, 0.0, 1.0);
        }
        let mut first = vec![0.0f32; buffer.len()];
        let mut second = vec![0.0f32; buffer.len()];
        self.min_limit.sample_volume(&mut first, volume);
        self.max_limit.sample_volume(&mut second, volume);
        for ((value, &a), &b) in buffer.iter_mut().zip(&first).zip(&second) {
            let alpha = *value;
            *value = if alpha == 0.0 {
                a
            } else if alpha == 1.0 {
                b
            } else {
                lerp(alpha, a, b)
            };
        }
    }
}
