//! Double-precision noise of 1.21.11 and 26.2 (`levelgen.synth` before the
//! 26.3 rewrite). Both profiles share this code; they only differ in
//! `Mth.floor`/`Mth.lfloor`, carried as a [`FloorMode`].

mod blended;
mod improved;
mod normal;
mod perlin;
mod simplex;

pub use blended::BlendedNoise;
pub use improved::ImprovedNoise;
pub use normal::NormalNoise;
pub use perlin::PerlinNoise;
pub(crate) use simplex::simplex_corners;
pub use simplex::SimplexNoise;

use crate::mth::FloorMode;

/// `SimplexNoise.GRADIENT`, also used by `ImprovedNoise.gradDot`.
#[rustfmt::skip]
pub(crate) const GRADIENT: [[i32; 3]; 16] = [
    [1, 1, 0], [-1, 1, 0], [1, -1, 0], [-1, -1, 0],
    [1, 0, 1], [-1, 0, 1], [1, 0, -1], [-1, 0, -1],
    [0, 1, 1], [0, -1, 1], [0, 1, -1], [0, -1, -1],
    [1, 1, 0], [0, -1, 1], [-1, 1, 0], [0, -1, -1],
];

/// `SimplexNoise.dot(int[], double, double, double)`.
#[inline]
pub(crate) fn dot(g: &[i32; 3], x: f64, y: f64, z: f64) -> f64 {
    g[0] as f64 * x + g[1] as f64 * y + g[2] as f64 * z
}

const ROUND_OFF: f64 = 33554432.0;

/// `PerlinNoise.wrap(double)`.
#[inline]
pub fn wrap(floor: FloorMode, x: f64) -> f64 {
    x - floor.lfloor(x / ROUND_OFF + 0.5) as f64 * ROUND_OFF
}

/// Shuffles the identity permutation exactly like the vanilla constructors.
pub(crate) fn shuffled_permutation(random: &mut crate::RandomSource) -> [u8; 256] {
    let mut p = [0u8; 256];
    for (i, slot) in p.iter_mut().enumerate() {
        *slot = i as u8;
    }
    for i in 0..256 {
        let j = random.next_int_bound(256 - i as i32) as usize;
        p.swap(i, i + j);
    }
    p
}
