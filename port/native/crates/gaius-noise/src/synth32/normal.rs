//! `synth.NormalNoise` (26.3): parameters that build a [`NoiseStack`].

use super::legacy_fbm::create_for_legacy_nether_biome;
use super::perlin::PerlinNoise;
use super::stack::{LayerNoise, NoiseStack};
use crate::java::{double_stream_sum, pow2, pow_half};
use crate::RandomSource;

const INPUT_FACTOR: f64 = 1.0181268882175227;
const TARGET_DEVIATION: f64 = 0.3333333333333333;
/// `PerlinNoise.STANDARD_DEVIATION`.
const PERLIN_STANDARD_DEVIATION: f64 = 0.2702247831245211;

/// `NormalNoise.Normalization`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Normalization {
    Disabled,
    Enabled,
    Legacy,
}

/// `NormalNoise.Parameters`, with the codec defaults.
#[derive(Clone, Debug, PartialEq)]
pub struct NormalNoiseParameters {
    pub base_amplitude: f64,
    pub base_octave: i32,
    pub octave_count: i32,
    pub normalize: Normalization,
    /// Empty means "all 1.0"; otherwise one entry per octave.
    pub amplitude_modifiers: Vec<f64>,
}

impl NormalNoiseParameters {
    pub fn new(base_octave: i32) -> Self {
        NormalNoiseParameters {
            base_amplitude: 1.0,
            base_octave,
            octave_count: 1,
            normalize: Normalization::Enabled,
            amplitude_modifiers: Vec::new(),
        }
    }

    /// `NormalNoise.createParity(baseOctave, amplitudes)`: the 26.3 form of a
    /// pre-26.3 `NoiseParameters(firstOctave, amplitudes)`.
    pub fn parity(base_octave: i32, amplitudes: &[f64]) -> Self {
        assert!(!amplitudes.is_empty(), "Need at least 1 amplitude");
        let count = amplitudes.len() as i32;
        let mut params = NormalNoiseParameters::new(base_octave);
        params.octave_count = count;
        params.base_amplitude = parity_base_amplitude(base_octave, amplitudes);
        for (i, &amplitude) in amplitudes.iter().enumerate() {
            if amplitude != 1.0 {
                if params.amplitude_modifiers.is_empty() {
                    params.amplitude_modifiers = vec![1.0; count as usize];
                }
                params.amplitude_modifiers[i] = amplitude;
            }
        }
        params
    }
}

/// `NormalNoise.OctaveInfo`: one octave of the plan, before seeding.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct OctaveInfo {
    pub octave_index: i32,
    pub frequency: f64,
    pub amplitude: f64,
}

impl OctaveInfo {
    /// The `fromHashOf` name both Perlin layers of this octave are seeded with.
    pub fn seed(&self) -> String {
        format!("octave_{}", self.octave_index)
    }
}

/// A constructed `NormalNoise`: the octave plan plus its normalization.
#[derive(Clone, Debug)]
pub struct NormalNoise {
    parameters: NormalNoiseParameters,
    octaves: Vec<OctaveInfo>,
    normalization_factor: f64,
    /// Half-width of `range()` before narrowing to float.
    range_half_width: f64,
}

impl NormalNoise {
    pub fn new(parameters: NormalNoiseParameters) -> Self {
        let p = &parameters;
        let octaves = build_octaves(
            p.base_octave,
            p.base_amplitude,
            p.octave_count,
            p.normalize != Normalization::Disabled,
            &p.amplitude_modifiers,
        );
        let mut amplitude_sum = double_stream_sum(octaves.iter().map(|o| o.amplitude.abs()));
        let mut factor = normalization_factor(amplitude_sum, &octaves);
        if p.normalize == Normalization::Legacy && factor != 0.0 {
            let parity = parity_normalization_factor(p.base_amplitude, p.octave_count, &p.amplitude_modifiers);
            amplitude_sum *= parity / factor;
            factor = parity;
        }
        let range_half_width = amplitude_sum * TARGET_DEVIATION * 6.0;
        NormalNoise {
            parameters,
            octaves,
            normalization_factor: factor,
            range_half_width,
        }
    }

    pub fn parameters(&self) -> &NormalNoiseParameters {
        &self.parameters
    }

    pub fn octaves(&self) -> &[OctaveInfo] {
        &self.octaves
    }

    pub fn normalization_factor(&self) -> f64 {
        self.normalization_factor
    }

    /// `range()` is `Interval.ofSymmetric((float) halfWidth)`.
    pub fn range_half_width(&self) -> f32 {
        self.range_half_width as f32
    }

    /// `create(random)`.
    pub fn create(&self, random: &mut RandomSource) -> NoiseStack {
        let first = random.fork_positional();
        let second = random.fork_positional();
        let mut builder = NoiseStack::builder();
        for octave in &self.octaves {
            let seed = octave.seed();
            let a = PerlinNoise::new(&mut first.from_hash_of(&seed));
            let b = PerlinNoise::new(&mut second.from_hash_of(&seed));
            let amplitude = (self.normalization_factor * octave.amplitude) as f32;
            builder = builder.add(LayerNoise::Perlin(a), octave.frequency, amplitude).add(
                LayerNoise::Perlin(b),
                octave.frequency * INPUT_FACTOR,
                amplitude,
            );
        }
        builder.build()
    }

    /// `createForLegacyNetherBiome(random)`.
    pub fn create_for_legacy_nether_biome(&self, random: &mut RandomSource) -> NoiseStack {
        let p = &self.parameters;
        let modifiers = if p.amplitude_modifiers.is_empty() {
            vec![1.0; p.octave_count.max(0) as usize]
        } else {
            p.amplitude_modifiers.clone()
        };
        let first = create_for_legacy_nether_biome(random, p.base_octave, &modifiers);
        let second = create_for_legacy_nether_biome(random, p.base_octave, &modifiers);
        let amplitude = (self.normalization_factor * p.base_amplitude) as f32;
        NoiseStack::builder()
            .add_stack(&first, 1.0, amplitude)
            .add_stack(&second, INPUT_FACTOR, amplitude)
            .build()
    }
}

fn amplitude_modifier(modifiers: &[f64], i: i32) -> f64 {
    if modifiers.is_empty() {
        1.0
    } else {
        modifiers[i as usize]
    }
}

fn build_octaves(
    base_octave: i32,
    base_amplitude: f64,
    count: i32,
    normalize: bool,
    modifiers: &[f64],
) -> Vec<OctaveInfo> {
    let mut frequency = pow2(base_octave);
    let mut amplitude = base_amplitude;
    if normalize {
        amplitude *= pow_half(count.wrapping_sub(1).wrapping_neg()) / (pow_half(count.wrapping_neg()) - 1.0);
    }
    let mut octaves = Vec::with_capacity(count.max(0) as usize);
    for i in 0..count {
        let modifier = amplitude_modifier(modifiers, i);
        if modifier != 0.0 {
            octaves.push(OctaveInfo {
                octave_index: base_octave.wrapping_add(i),
                frequency,
                amplitude: amplitude * modifier,
            });
        }
        frequency *= 2.0;
        amplitude *= 0.5;
    }
    octaves
}

fn normalization_factor(amplitude_sum: f64, octaves: &[OctaveInfo]) -> f64 {
    let deviation = estimate_deviation(octaves);
    if deviation == 0.0 {
        return 0.0;
    }
    let expected = deviation * 2.0f64.sqrt();
    let target = amplitude_sum * TARGET_DEVIATION;
    target / expected
}

fn estimate_deviation(octaves: &[OctaveInfo]) -> f64 {
    let mut variance = 0.0;
    for octave in octaves {
        let deviation = PERLIN_STANDARD_DEVIATION * octave.amplitude.abs();
        variance += deviation * deviation;
    }
    variance.sqrt()
}

fn parity_base_amplitude(base_octave: i32, amplitudes: &[f64]) -> f64 {
    let count = amplitudes.len() as i32;
    let octaves = build_octaves(base_octave, 1.0, count, true, amplitudes);
    let sum = double_stream_sum(octaves.iter().map(|o| o.amplitude.abs()));
    let factor = normalization_factor(sum, &octaves);
    if factor == 0.0 {
        return 1.0;
    }
    parity_normalization_factor(1.0, count, amplitudes) / factor
}

fn parity_normalization_factor(base_amplitude: f64, count: i32, modifiers: &[f64]) -> f64 {
    let mut min = i32::MAX;
    let mut max = i32::MIN;
    for i in 0..count {
        if amplitude_modifier(modifiers, i) != 0.0 {
            min = min.min(i);
            max = max.max(i);
        }
    }
    base_amplitude * 0.5 * TARGET_DEVIATION / parity_expected_deviation(max.wrapping_sub(min))
}

fn parity_expected_deviation(octaves: i32) -> f64 {
    0.1 * (1.0 + 1.0 / octaves.wrapping_add(1) as f64)
}
