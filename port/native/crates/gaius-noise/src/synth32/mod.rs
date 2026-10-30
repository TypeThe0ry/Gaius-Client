//! Float noise of 26.3 (`levelgen.synth` after the `GradientNoise` rewrite).
//!
//! Each noise has two evaluation paths that vanilla keeps separate and that
//! may differ in the last bit: `get` (single points, used by `sampleValue`)
//! and `add_to_volume` (whole grids, used by `sampleVolume` during chunk
//! generation). Both are ported.

mod blended;
mod gradient;
mod legacy_fbm;
mod normal;
mod perlin;
mod simplex;
mod stack;
mod volume;

pub use blended::{create_fbm, BlendedNoiseParameters, BlendedNoiseSampler, NoiseSampler, NOISE_SEED};
pub use gradient::{wrap, Gradient, GradientBase, GRADIENT};
pub use legacy_fbm::create_for_legacy_nether_biome;
pub use normal::{NormalNoise, NormalNoiseParameters, Normalization, OctaveInfo};
pub use perlin::{PerlinNoise, SmearedPerlinNoise};
pub use simplex::SimplexNoise;
pub use stack::{Layer, LayerNoise, NoiseStack, NoiseStackBuilder};
pub use volume::DensityVolume;

/// `Noise.addToVolume` default implementation.
pub(crate) fn add_to_volume_pointwise(
    buffer: &mut [f32],
    volume: &DensityVolume,
    xz_scale: f64,
    y_scale: f64,
    scale: f32,
    get: impl Fn(f64, f64, f64) -> f32,
) {
    let mut index = 0usize;
    for zi in 0..volume.size_z {
        let z = volume.block_z(zi) as f64 * xz_scale;
        for xi in 0..volume.size_x {
            let x = volume.block_x(xi) as f64 * xz_scale;
            for yi in 0..volume.size_y {
                let y = volume.block_y(yi) as f64 * y_scale;
                buffer[index] += scale * get(x, y, z);
                index += 1;
            }
        }
    }
}
