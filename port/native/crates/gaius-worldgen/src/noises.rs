//! Noise instances of a generator: the `RandomState` noise table.
//!
//! 26.3 noises are float `NoiseStack`s (point `get` and volume `addToVolume`
//! paths); 1.21.11 and 26.2 noises are double `NormalNoise`s.

use crate::ir::{IrError, NoiseDef, NoiseParams};
use gaius_noise::synth32::{NoiseStack, NormalNoise, NormalNoiseParameters, Normalization};
use gaius_noise::{synth64, PositionalRandomFactory, RandomKind, RandomSource, Semantics};

#[derive(Clone, Debug)]
pub enum NoiseInst {
    Stack(NoiseStack),
    Normal(synth64::NormalNoise),
}

impl NoiseInst {
    /// 26.3 `Noise.get(x, y, z)`.
    #[inline]
    pub fn get32(&self, x: f64, y: f64, z: f64) -> f32 {
        match self {
            NoiseInst::Stack(s) => s.get(x, y, z),
            NoiseInst::Normal(n) => n.get_value(x, y, z) as f32,
        }
    }

    /// `NormalNoise.getValue(x, y, z)` (float results widen exactly).
    #[inline]
    pub fn get64(&self, x: f64, y: f64, z: f64) -> f64 {
        match self {
            NoiseInst::Stack(s) => s.get(x, y, z) as f64,
            NoiseInst::Normal(n) => n.get_value(x, y, z),
        }
    }

    /// 26.3 `Noise.addToVolume(buffer, volume, xzScale, yScale, amplitude)`.
    pub fn add_to_volume(
        &self,
        buffer: &mut [f32],
        volume: &crate::volume::Volume,
        xz_scale: f64,
        y_scale: f64,
        amplitude: f32,
    ) {
        match self {
            NoiseInst::Stack(s) => s.add_to_volume(buffer, &volume.to_noise(), xz_scale, y_scale, amplitude),
            NoiseInst::Normal(n) => {
                let mut index = 0usize;
                for zi in 0..volume.size_z {
                    let z = volume.block_z(zi) as f64 * xz_scale;
                    for xi in 0..volume.size_x {
                        let x = volume.block_x(xi) as f64 * xz_scale;
                        for yi in 0..volume.size_y {
                            let y = volume.block_y(yi) as f64 * y_scale;
                            buffer[index] += amplitude * n.get_value(x, y, z) as f32;
                            index += 1;
                        }
                    }
                }
            }
        }
    }

    /// `NormalNoise.maxValue()` (pre-26.3).
    pub fn max_value(&self) -> f64 {
        match self {
            NoiseInst::Normal(n) => n.max_value(),
            NoiseInst::Stack(_) => 2.0,
        }
    }
}

/// The `RandomState` positional factory: `algorithm.newInstance(seed).forkPositional()`.
pub fn positional_factory(seed: i64, legacy: bool) -> PositionalRandomFactory {
    let kind = if legacy {
        RandomKind::Legacy
    } else {
        RandomKind::Xoroshiro
    };
    RandomSource::new(kind, seed).fork_positional()
}

fn recipe(params: &NoiseParams) -> NormalNoiseParameters {
    match params {
        NoiseParams::Parity {
            first_octave,
            amplitudes,
        } => NormalNoiseParameters::parity(*first_octave, amplitudes),
        NoiseParams::Recipe {
            base_amplitude,
            base_octave,
            octave_count,
            normalize,
            amplitude_modifiers,
        } => NormalNoiseParameters {
            base_amplitude: *base_amplitude,
            base_octave: *base_octave,
            octave_count: *octave_count,
            normalize: match normalize {
                0 => Normalization::Disabled,
                1 => Normalization::Enabled,
                _ => Normalization::Legacy,
            },
            amplitude_modifiers: amplitude_modifiers.clone(),
        },
    }
}

/// Builds every noise exactly like `RandomState` (`Noises.instantiate` and the nether
/// legacy biome special cases).
pub fn instantiate(
    semantics: Semantics,
    seed: i64,
    factory: &PositionalRandomFactory,
    defs: &[NoiseDef],
) -> Result<Vec<NoiseInst>, IrError> {
    defs.iter()
        .map(|def| {
            let mut random = match def.legacy_nether_offset {
                Some(offset) => RandomSource::new(RandomKind::Legacy, seed.wrapping_add(offset)),
                None => factory.from_hash_of(&def.name),
            };
            if semantics.uses_synth32() {
                let noise = NormalNoise::new(recipe(&def.params));
                Ok(NoiseInst::Stack(if def.legacy_nether_offset.is_some() {
                    noise.create_for_legacy_nether_biome(&mut random)
                } else {
                    noise.create(&mut random)
                }))
            } else {
                let NoiseParams::Parity {
                    first_octave,
                    amplitudes,
                } = &def.params
                else {
                    return Err(IrError::new(format!(
                        "noise {}: recipe parameters exist only on 26.3",
                        def.name
                    )));
                };
                let floor = semantics.floor_mode;
                Ok(NoiseInst::Normal(if def.legacy_nether_offset.is_some() {
                    synth64::NormalNoise::create_legacy_nether_biome(&mut random, *first_octave, amplitudes, floor)
                } else {
                    synth64::NormalNoise::create(&mut random, *first_octave, amplitudes, floor)
                }))
            }
        })
        .collect()
}
