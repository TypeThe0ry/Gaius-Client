//! `synth.BlendedNoise` (1.21.11 / 26.2): the legacy 3D terrain noise.

use super::{wrap, PerlinNoise};
use crate::mth::{clamped_lerp, FloorMode};
use crate::RandomSource;

const BASE_SCALE: f64 = 684.412;
const LIMIT_OCTAVES: [i32; 16] = [-15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0];
const MAIN_OCTAVES: [i32; 8] = [-7, -6, -5, -4, -3, -2, -1, 0];

#[derive(Clone, Debug)]
pub struct BlendedNoise {
    min_limit_noise: PerlinNoise,
    max_limit_noise: PerlinNoise,
    main_noise: PerlinNoise,
    xz_multiplier: f64,
    y_multiplier: f64,
    xz_factor: f64,
    y_factor: f64,
    smear_scale_multiplier: f64,
    max_value: f64,
    floor: FloorMode,
}

impl BlendedNoise {
    /// `new BlendedNoise(random, xzScale, yScale, xzFactor, yFactor, smearScaleMultiplier)`.
    pub fn new(
        random: &mut RandomSource,
        xz_scale: f64,
        y_scale: f64,
        xz_factor: f64,
        y_factor: f64,
        smear_scale_multiplier: f64,
        floor: FloorMode,
    ) -> Self {
        let min_limit_noise = PerlinNoise::create_legacy_for_blended_noise(random, &LIMIT_OCTAVES, floor);
        let max_limit_noise = PerlinNoise::create_legacy_for_blended_noise(random, &LIMIT_OCTAVES, floor);
        let main_noise = PerlinNoise::create_legacy_for_blended_noise(random, &MAIN_OCTAVES, floor);
        let xz_multiplier = BASE_SCALE * xz_scale;
        let y_multiplier = BASE_SCALE * y_scale;
        let max_value = min_limit_noise.max_broken_value(y_multiplier);
        BlendedNoise {
            min_limit_noise,
            max_limit_noise,
            main_noise,
            xz_multiplier,
            y_multiplier,
            xz_factor,
            y_factor,
            smear_scale_multiplier,
            max_value,
            floor,
        }
    }

    /// `BlendedNoise.createUnseeded(...)`: seeded with `new XoroshiroRandomSource(0)`.
    pub fn create_unseeded(
        xz_scale: f64,
        y_scale: f64,
        xz_factor: f64,
        y_factor: f64,
        smear: f64,
        floor: FloorMode,
    ) -> Self {
        let mut random = RandomSource::new(crate::RandomKind::Xoroshiro, 0);
        Self::new(&mut random, xz_scale, y_scale, xz_factor, y_factor, smear, floor)
    }

    pub fn max_value(&self) -> f64 {
        self.max_value
    }

    pub fn min_value(&self) -> f64 {
        -self.max_value
    }

    /// `compute(FunctionContext)` at block `(x, y, z)`.
    pub fn compute(&self, block_x: i32, block_y: i32, block_z: i32) -> f64 {
        let w = |v: f64| wrap(self.floor, v);
        let x = block_x as f64 * self.xz_multiplier;
        let y = block_y as f64 * self.y_multiplier;
        let z = block_z as f64 * self.xz_multiplier;
        let main_x = x / self.xz_factor;
        let main_y = y / self.y_factor;
        let main_z = z / self.xz_factor;
        let smear = self.y_multiplier * self.smear_scale_multiplier;
        let main_smear = smear / self.y_factor;
        let mut min_limit = 0.0;
        let mut max_limit = 0.0;
        let mut main = 0.0;
        let mut pow = 1.0;
        for octave in 0..8 {
            if let Some(noise) = self.main_noise.get_octave_noise(octave) {
                main += noise.noise_smeared(
                    w(main_x * pow),
                    w(main_y * pow),
                    w(main_z * pow),
                    main_smear * pow,
                    main_y * pow,
                ) / pow;
            }
            pow /= 2.0;
        }
        let factor = (main / 10.0 + 1.0) / 2.0;
        let only_max = factor >= 1.0;
        let only_min = factor <= 0.0;
        pow = 1.0;
        for octave in 0..16 {
            let sx = w(x * pow);
            let sy = w(y * pow);
            let sz = w(z * pow);
            let y_scale = smear * pow;
            if !only_max {
                if let Some(noise) = self.min_limit_noise.get_octave_noise(octave) {
                    min_limit += noise.noise_smeared(sx, sy, sz, y_scale, y * pow) / pow;
                }
            }
            if !only_min {
                if let Some(noise) = self.max_limit_noise.get_octave_noise(octave) {
                    max_limit += noise.noise_smeared(sx, sy, sz, y_scale, y * pow) / pow;
                }
            }
            pow /= 2.0;
        }
        clamped_lerp(factor, min_limit / 512.0, max_limit / 512.0) / 128.0
    }
}
