//! `synth.GradientNoise`: the permutation table and offsets shared by the
//! 26.3 Perlin and simplex noises.

use crate::RandomSource;

/// `GradientNoise.Gradient`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Gradient {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl Gradient {
    #[inline]
    pub fn dot_f64(self, x: f64, y: f64, z: f64) -> f64 {
        self.x as f64 * x + self.y as f64 * y + self.z as f64 * z
    }

    #[inline]
    pub fn dot(self, x: f32, y: f32, z: f32) -> f32 {
        self.x as f32 * x + self.y as f32 * y + self.z as f32 * z
    }

    #[inline]
    pub fn dot_xz(self, x: f32, z: f32) -> f32 {
        self.x as f32 * x + self.z as f32 * z
    }
}

const fn g(x: i32, y: i32, z: i32) -> Gradient {
    Gradient { x, y, z }
}

#[rustfmt::skip]
pub const GRADIENT: [Gradient; 16] = [
    g(1, 1, 0), g(-1, 1, 0), g(1, -1, 0), g(-1, -1, 0),
    g(1, 0, 1), g(-1, 0, 1), g(1, 0, -1), g(-1, 0, -1),
    g(0, 1, 1), g(0, -1, 1), g(0, 1, -1), g(0, -1, -1),
    g(1, 1, 0), g(0, -1, 1), g(-1, 1, 0), g(0, -1, -1),
];

const ROUND_OFF: f64 = 33554432.0;
/// `Math.nextDown(16777216.0)`.
const HALF_ROUND_OFF: f64 = 16777215.999999998;

/// `GradientNoise.wrap(double)` with `DEBUG_ENABLE_FARLANDS` off.
#[inline]
pub fn wrap(x: f64) -> f64 {
    if (-HALF_ROUND_OFF..HALF_ROUND_OFF).contains(&x) {
        x
    } else {
        x - (x / ROUND_OFF + 0.5).floor() * ROUND_OFF
    }
}

#[derive(Clone, Debug)]
pub struct GradientBase {
    pub perms: [u8; 256],
    pub offset_x: f64,
    pub offset_y: f64,
    pub offset_z: f64,
}

impl GradientBase {
    /// `GradientNoise(random, offsetScale)`.
    pub fn new(random: &mut RandomSource, offset_scale: f64) -> Self {
        let offset_x = random.next_double() * offset_scale;
        let offset_y = random.next_double() * offset_scale;
        let offset_z = random.next_double() * offset_scale;
        let perms = crate::synth64::shuffled_permutation(random);
        GradientBase {
            perms,
            offset_x,
            offset_y,
            offset_z,
        }
    }

    #[inline]
    pub fn permute(&self, index: i32) -> i32 {
        self.perms[(index & 255) as usize] as i32
    }

    #[inline]
    pub fn permute_to_grad(&self, index: i32) -> Gradient {
        GRADIENT[(self.permute(index) & 15) as usize]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn half_round_off_is_next_down_of_two_to_the_24() {
        assert_eq!(HALF_ROUND_OFF.to_bits(), 16777216.0f64.to_bits() - 1);
    }

    #[test]
    fn wrap_keeps_small_values() {
        assert_eq!(wrap(123.5), 123.5);
        assert_eq!(wrap(-16777215.0), -16777215.0);
        assert_eq!(wrap(33554432.0 + 3.0), 3.0);
        assert_eq!(wrap(16777216.0), -16777216.0);
    }
}
