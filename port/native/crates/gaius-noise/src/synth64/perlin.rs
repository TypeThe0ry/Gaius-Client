//! `synth.PerlinNoise`: octaves of [`ImprovedNoise`].

use super::{wrap, ImprovedNoise};
use crate::java::pow2;
use crate::mth::FloorMode;
use crate::RandomSource;

#[derive(Clone, Debug)]
pub struct PerlinNoise {
    noise_levels: Vec<Option<ImprovedNoise>>,
    first_octave: i32,
    amplitudes: Vec<f64>,
    lowest_freq_value_factor: f64,
    lowest_freq_input_factor: f64,
    max_value: f64,
    floor: FloorMode,
}

impl PerlinNoise {
    /// `PerlinNoise.create(random, firstOctave, amplitudes)`: octaves are
    /// seeded through `forkPositional().fromHashOf("octave_" + octave)`.
    pub fn create(random: &mut RandomSource, first_octave: i32, amplitudes: &[f64], floor: FloorMode) -> Self {
        Self::new(random, first_octave, amplitudes.to_vec(), true, floor)
    }

    /// `PerlinNoise.createLegacyForBlendedNoise(random, octaves)`.
    pub fn create_legacy_for_blended_noise(random: &mut RandomSource, octaves: &[i32], floor: FloorMode) -> Self {
        let (first_octave, amplitudes) = make_amplitudes(octaves);
        Self::new(random, first_octave, amplitudes, false, floor)
    }

    /// `PerlinNoise.createLegacyForLegacyNetherBiome(random, firstOctave, amplitudes)`.
    pub fn create_legacy_for_legacy_nether_biome(
        random: &mut RandomSource,
        first_octave: i32,
        amplitudes: &[f64],
        floor: FloorMode,
    ) -> Self {
        Self::new(random, first_octave, amplitudes.to_vec(), false, floor)
    }

    fn new(
        random: &mut RandomSource,
        first_octave: i32,
        amplitudes: Vec<f64>,
        use_new_factory: bool,
        floor: FloorMode,
    ) -> Self {
        let size = amplitudes.len() as i32;
        let zero_octave_index = first_octave.wrapping_neg();
        let mut noise_levels: Vec<Option<ImprovedNoise>> = vec![None; amplitudes.len()];
        if use_new_factory {
            let positional = random.fork_positional();
            for (i, level) in noise_levels.iter_mut().enumerate() {
                if amplitudes[i] != 0.0 {
                    let octave = first_octave.wrapping_add(i as i32);
                    let mut octave_random = positional.from_hash_of(&format!("octave_{octave}"));
                    *level = Some(ImprovedNoise::new(&mut octave_random, floor));
                }
            }
        } else {
            let zero_octave = ImprovedNoise::new(random, floor);
            if zero_octave_index >= 0 && zero_octave_index < size && amplitudes[zero_octave_index as usize] != 0.0 {
                noise_levels[zero_octave_index as usize] = Some(zero_octave);
            }
            let mut i = zero_octave_index.wrapping_sub(1);
            while i >= 0 {
                if i < size && amplitudes[i as usize] != 0.0 {
                    noise_levels[i as usize] = Some(ImprovedNoise::new(random, floor));
                } else {
                    skip_octave(random);
                }
                i -= 1;
            }
            assert!(
                zero_octave_index >= size - 1,
                "Positive octaves are temporarily disabled"
            );
        }
        let lowest_freq_input_factor = pow2(zero_octave_index.wrapping_neg());
        let lowest_freq_value_factor = pow2(size - 1) / (pow2(size) - 1.0);
        let mut noise = PerlinNoise {
            noise_levels,
            first_octave,
            amplitudes,
            lowest_freq_value_factor,
            lowest_freq_input_factor,
            max_value: 0.0,
            floor,
        };
        noise.max_value = noise.edge_value(2.0);
        noise
    }

    pub fn first_octave(&self) -> i32 {
        self.first_octave
    }

    pub fn amplitudes(&self) -> &[f64] {
        &self.amplitudes
    }

    pub fn max_value(&self) -> f64 {
        self.max_value
    }

    pub fn get_value(&self, x: f64, y: f64, z: f64) -> f64 {
        self.get_value_smeared(x, y, z, 0.0, 0.0)
    }

    /// `getValue(x, y, z, yScale, yFractMax)`.
    pub fn get_value_smeared(&self, x: f64, y: f64, z: f64, y_scale: f64, y_fract_max: f64) -> f64 {
        let mut value = 0.0;
        let mut input_factor = self.lowest_freq_input_factor;
        let mut value_factor = self.lowest_freq_value_factor;
        for (i, level) in self.noise_levels.iter().enumerate() {
            if let Some(noise) = level {
                let sample = noise.noise_smeared(
                    wrap(self.floor, x * input_factor),
                    wrap(self.floor, y * input_factor),
                    wrap(self.floor, z * input_factor),
                    y_scale * input_factor,
                    y_fract_max * input_factor,
                );
                value += self.amplitudes[i] * sample * value_factor;
            }
            input_factor *= 2.0;
            value_factor /= 2.0;
        }
        value
    }

    pub fn max_broken_value(&self, y_scale: f64) -> f64 {
        self.edge_value(y_scale + 2.0)
    }

    fn edge_value(&self, noise_value: f64) -> f64 {
        let mut value = 0.0;
        let mut value_factor = self.lowest_freq_value_factor;
        for (i, level) in self.noise_levels.iter().enumerate() {
            if level.is_some() {
                value += self.amplitudes[i] * noise_value * value_factor;
            }
            value_factor /= 2.0;
        }
        value
    }

    /// `getOctaveNoise(i)`: octave `i` counted from the highest frequency.
    pub fn get_octave_noise(&self, i: usize) -> Option<&ImprovedNoise> {
        self.noise_levels
            .len()
            .checked_sub(1 + i)
            .and_then(|index| self.noise_levels[index].as_ref())
    }
}

/// `PerlinNoise.makeAmplitudes(IntSortedSet)`.
fn make_amplitudes(octaves: &[i32]) -> (i32, Vec<f64>) {
    let mut sorted = octaves.to_vec();
    sorted.sort_unstable();
    sorted.dedup();
    let (first, last) = match (sorted.first(), sorted.last()) {
        (Some(&first), Some(&last)) => (first.wrapping_neg(), last),
        _ => panic!("Need some octaves!"),
    };
    let size = first.wrapping_add(last).wrapping_add(1);
    assert!(size >= 1, "Total number of octaves needs to be >= 1");
    let mut amplitudes = vec![0.0; size as usize];
    for octave in sorted {
        amplitudes[octave.wrapping_add(first) as usize] = 1.0;
    }
    (first.wrapping_neg(), amplitudes)
}

fn skip_octave(random: &mut RandomSource) {
    random.consume_count(262);
}
