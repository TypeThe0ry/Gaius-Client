//! Many-positions-per-call evaluation on top of the scalar reference paths.
//!
//! Positions are interleaved `x, y, z` triples. The default loop calls the
//! scalar sampler; a SIMD specialisation can override `sample_points` per
//! type as long as it stays bit-identical to `sample`.

use crate::{synth32, synth64, RandomSource, Semantics};

pub trait PointSampler {
    /// One sample; float results (26.3) are widened exactly to `f64`.
    fn sample(&self, x: f64, y: f64, z: f64) -> f64;

    /// Samples `xyz.len() / 3` positions into `out`.
    fn sample_points(&self, xyz: &[f64], out: &mut [f64]) {
        assert_eq!(xyz.len(), out.len() * 3, "positions and outputs disagree");
        for (p, o) in xyz.as_chunks::<3>().0.iter().zip(out.iter_mut()) {
            *o = self.sample(p[0], p[1], p[2]);
        }
    }
}

macro_rules! point_sampler {
    ($ty:ty, |$s:ident, $x:ident, $y:ident, $z:ident| $body:expr) => {
        impl PointSampler for $ty {
            #[inline]
            fn sample(&self, $x: f64, $y: f64, $z: f64) -> f64 {
                let $s = self;
                $body
            }
        }
    };
}

point_sampler!(synth64::ImprovedNoise, |s, x, y, z| s.noise(x, y, z));
point_sampler!(synth64::PerlinNoise, |s, x, y, z| s.get_value(x, y, z));
point_sampler!(synth64::NormalNoise, |s, x, y, z| s.get_value(x, y, z));
point_sampler!(synth64::SimplexNoise, |s, x, y, z| s.get_value_3d(x, y, z));
point_sampler!(synth32::PerlinNoise, |s, x, y, z| s.get(x, y, z) as f64);
point_sampler!(synth32::SmearedPerlinNoise, |s, x, y, z| s.get(x, y, z) as f64);
point_sampler!(synth32::SimplexNoise, |s, x, y, z| s.get(x, y, z) as f64);
point_sampler!(synth32::NoiseStack, |s, x, y, z| s.get(x, y, z) as f64);

/// A `NormalNoise` of any semantics, ready to sample.
#[derive(Clone, Debug)]
pub enum ProfileNormalNoise {
    /// 1.21.11 / 26.2.
    Double(synth64::NormalNoise),
    /// 26.3: the `NoiseStack` built by `NormalNoise.create(random)`.
    Float(synth32::NoiseStack),
}

impl ProfileNormalNoise {
    /// Pre-26.3 `NoiseParameters(firstOctave, amplitudes)`; with float synth classes (26.3)
    /// this is `NormalNoise.createParity(firstOctave, amplitudes).create(random)`.
    pub fn parity(semantics: Semantics, random: &mut RandomSource, first_octave: i32, amplitudes: &[f64]) -> Self {
        if semantics.uses_synth32() {
            let params = synth32::NormalNoiseParameters::parity(first_octave, amplitudes);
            ProfileNormalNoise::Float(synth32::NormalNoise::new(params).create(random))
        } else {
            ProfileNormalNoise::Double(synth64::NormalNoise::create(
                random,
                first_octave,
                amplitudes,
                semantics.floor_mode,
            ))
        }
    }

    /// 26.3 codec parameters (`base_amplitude`, `base_octave`, ...).
    pub fn from_parameters(params: synth32::NormalNoiseParameters, random: &mut RandomSource) -> Self {
        ProfileNormalNoise::Float(synth32::NormalNoise::new(params).create(random))
    }
}

impl PointSampler for ProfileNormalNoise {
    #[inline]
    fn sample(&self, x: f64, y: f64, z: f64) -> f64 {
        match self {
            ProfileNormalNoise::Double(noise) => noise.get_value(x, y, z),
            ProfileNormalNoise::Float(noise) => noise.get(x, y, z) as f64,
        }
    }

    fn sample_points(&self, xyz: &[f64], out: &mut [f64]) {
        match self {
            ProfileNormalNoise::Double(noise) => noise.sample_points(xyz, out),
            ProfileNormalNoise::Float(noise) => noise.sample_points(xyz, out),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RandomKind;

    #[test]
    fn batch_matches_scalar() {
        for &semantics in Semantics::all() {
            let mut random = RandomSource::new(RandomKind::Xoroshiro, 1234);
            let noise = ProfileNormalNoise::parity(semantics, &mut random, -7, &[1.0, 1.0, 0.0, 2.0]);
            let xyz: Vec<f64> = (0..30).map(|i| i as f64 * 13.37 - 100.0).collect();
            let mut out = vec![0.0; 10];
            noise.sample_points(&xyz, &mut out);
            for (p, &o) in xyz.as_chunks::<3>().0.iter().zip(&out) {
                assert_eq!(o.to_bits(), noise.sample(p[0], p[1], p[2]).to_bits());
            }
            assert!(out.iter().all(|v| v.is_finite()));
            assert!(out.iter().any(|&v| v != 0.0), "{semantics:?} produced only zeros");
        }
    }
}
