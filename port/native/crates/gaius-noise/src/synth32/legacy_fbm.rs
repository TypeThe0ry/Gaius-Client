//! `synth.LegacyFbmInitializer`: the pre-positional octave seeding kept for
//! the legacy nether biome source.

use super::perlin::PerlinNoise;
use super::stack::{LayerNoise, NoiseStack};
use crate::java::pow2;
use crate::RandomSource;

/// `LegacyFbmInitializer.createForLegacyNetherBiome(random, firstOctave, amplitudes)`.
pub fn create_for_legacy_nether_biome(random: &mut RandomSource, first_octave: i32, amplitudes: &[f64]) -> NoiseStack {
    let size = amplitudes.len() as i32;
    let zero_octave_index = first_octave.wrapping_neg();
    let mut levels: Vec<Option<PerlinNoise>> = vec![None; amplitudes.len()];
    let zero_octave = PerlinNoise::new(random);
    if zero_octave_index >= 0 && zero_octave_index < size && amplitudes[zero_octave_index as usize] != 0.0 {
        levels[zero_octave_index as usize] = Some(zero_octave);
    }
    let mut i = zero_octave_index.wrapping_sub(1);
    while i >= 0 {
        if i < size && amplitudes[i as usize] != 0.0 {
            levels[i as usize] = Some(PerlinNoise::new(random));
        } else {
            random.consume_count(262);
        }
        i -= 1;
    }
    assert!(
        zero_octave_index >= size - 1,
        "Positive octaves are temporarily disabled"
    );
    let mut input_factor = pow2(zero_octave_index.wrapping_neg());
    let mut value_factor = pow2(size - 1) / (pow2(size) - 1.0);
    let mut builder = NoiseStack::builder();
    for (i, level) in levels.into_iter().enumerate() {
        if let Some(noise) = level {
            builder = builder.add(
                LayerNoise::Perlin(noise),
                input_factor,
                (value_factor * amplitudes[i]) as f32,
            );
        }
        input_factor *= 2.0;
        value_factor /= 2.0;
    }
    builder.build()
}
