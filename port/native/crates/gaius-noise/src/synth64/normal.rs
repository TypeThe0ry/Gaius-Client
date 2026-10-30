//! `synth.NormalNoise` (1.21.11 / 26.2): two Perlin stacks, the second
//! sampled at a slightly stretched input.

use super::PerlinNoise;
use crate::mth::FloorMode;
use crate::RandomSource;

const INPUT_FACTOR: f64 = 1.0181268882175227;
/// `TARGET_DEVIATION / 2` folded by javac.
const HALF_TARGET_DEVIATION: f64 = 0.16666666666666666;

#[derive(Clone, Debug)]
pub struct NormalNoise {
    value_factor: f64,
    first: PerlinNoise,
    second: PerlinNoise,
    max_value: f64,
}

impl NormalNoise {
    /// `NormalNoise.create(random, new NoiseParameters(firstOctave, amplitudes))`.
    pub fn create(random: &mut RandomSource, first_octave: i32, amplitudes: &[f64], floor: FloorMode) -> Self {
        let first = PerlinNoise::create(random, first_octave, amplitudes, floor);
        let second = PerlinNoise::create(random, first_octave, amplitudes, floor);
        Self::from_parts(first, second, amplitudes)
    }

    /// `NormalNoise.createLegacyNetherBiome(random, parameters)`.
    pub fn create_legacy_nether_biome(
        random: &mut RandomSource,
        first_octave: i32,
        amplitudes: &[f64],
        floor: FloorMode,
    ) -> Self {
        let first = PerlinNoise::create_legacy_for_legacy_nether_biome(random, first_octave, amplitudes, floor);
        let second = PerlinNoise::create_legacy_for_legacy_nether_biome(random, first_octave, amplitudes, floor);
        Self::from_parts(first, second, amplitudes)
    }

    fn from_parts(first: PerlinNoise, second: PerlinNoise, amplitudes: &[f64]) -> Self {
        let mut min_octave = i32::MAX;
        let mut max_octave = i32::MIN;
        for (index, &amplitude) in amplitudes.iter().enumerate() {
            if amplitude != 0.0 {
                min_octave = min_octave.min(index as i32);
                max_octave = max_octave.max(index as i32);
            }
        }
        let value_factor = HALF_TARGET_DEVIATION / expected_deviation(max_octave.wrapping_sub(min_octave));
        let max_value = (first.max_value() + second.max_value()) * value_factor;
        NormalNoise {
            value_factor,
            first,
            second,
            max_value,
        }
    }

    pub fn max_value(&self) -> f64 {
        self.max_value
    }

    pub fn get_value(&self, x: f64, y: f64, z: f64) -> f64 {
        let x2 = x * INPUT_FACTOR;
        let y2 = y * INPUT_FACTOR;
        let z2 = z * INPUT_FACTOR;
        (self.first.get_value(x, y, z) + self.second.get_value(x2, y2, z2)) * self.value_factor
    }
}

fn expected_deviation(octaves: i32) -> f64 {
    0.1 * (1.0 + 1.0 / octaves.wrapping_add(1) as f64)
}
