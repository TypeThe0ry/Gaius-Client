//! `synth.ImprovedNoise`: one octave of 3D Perlin noise.

use super::{dot, shuffled_permutation, GRADIENT};
use crate::mth::{lerp3, smoothstep, smoothstep_derivative, FloorMode};
use crate::RandomSource;

/// `(double) 1.0E-7f`
const SHIFT_UP_EPSILON: f64 = 1.0000000116860974E-7;

#[derive(Clone, Debug)]
pub struct ImprovedNoise {
    p: [u8; 256],
    pub xo: f64,
    pub yo: f64,
    pub zo: f64,
    floor: FloorMode,
}

#[inline]
fn grad_dot(hash: i32, x: f64, y: f64, z: f64) -> f64 {
    dot(&GRADIENT[(hash & 15) as usize], x, y, z)
}

impl ImprovedNoise {
    pub fn new(random: &mut RandomSource, floor: FloorMode) -> Self {
        let xo = random.next_double() * 256.0;
        let yo = random.next_double() * 256.0;
        let zo = random.next_double() * 256.0;
        let p = shuffled_permutation(random);
        ImprovedNoise { p, xo, yo, zo, floor }
    }

    #[inline]
    fn p(&self, index: i32) -> i32 {
        self.p[(index & 255) as usize] as i32
    }

    pub fn noise(&self, x: f64, y: f64, z: f64) -> f64 {
        self.noise_smeared(x, y, z, 0.0, 0.0)
    }

    /// `noise(x, y, z, yScale, yFractMax)`.
    pub fn noise_smeared(&self, x: f64, y: f64, z: f64, y_scale: f64, y_fract_max: f64) -> f64 {
        let x = x + self.xo;
        let y = y + self.yo;
        let z = z + self.zo;
        let xi = self.floor.floor(x);
        let yi = self.floor.floor(y);
        let zi = self.floor.floor(z);
        let xr = x - xi as f64;
        let yr = y - yi as f64;
        let zr = z - zi as f64;
        let y_fudge = if y_scale != 0.0 {
            let limit = if y_fract_max >= 0.0 && y_fract_max < yr {
                y_fract_max
            } else {
                yr
            };
            self.floor.floor(limit / y_scale + SHIFT_UP_EPSILON) as f64 * y_scale
        } else {
            0.0
        };
        self.sample_and_lerp(xi, yi, zi, xr, yr - y_fudge, zr, yr)
    }

    #[allow(clippy::too_many_arguments)]
    fn sample_and_lerp(&self, x: i32, y: i32, z: i32, xr: f64, yr: f64, zr: f64, yr_original: f64) -> f64 {
        let x0 = self.p(x);
        let x1 = self.p(x.wrapping_add(1));
        let y00 = self.p(x0.wrapping_add(y));
        let y01 = self.p(x0.wrapping_add(y).wrapping_add(1));
        let y10 = self.p(x1.wrapping_add(y));
        let y11 = self.p(x1.wrapping_add(y).wrapping_add(1));
        let z1 = z.wrapping_add(1);
        let d000 = grad_dot(self.p(y00.wrapping_add(z)), xr, yr, zr);
        let d100 = grad_dot(self.p(y10.wrapping_add(z)), xr - 1.0, yr, zr);
        let d010 = grad_dot(self.p(y01.wrapping_add(z)), xr, yr - 1.0, zr);
        let d110 = grad_dot(self.p(y11.wrapping_add(z)), xr - 1.0, yr - 1.0, zr);
        let d001 = grad_dot(self.p(y00.wrapping_add(z1)), xr, yr, zr - 1.0);
        let d101 = grad_dot(self.p(y10.wrapping_add(z1)), xr - 1.0, yr, zr - 1.0);
        let d011 = grad_dot(self.p(y01.wrapping_add(z1)), xr, yr - 1.0, zr - 1.0);
        let d111 = grad_dot(self.p(y11.wrapping_add(z1)), xr - 1.0, yr - 1.0, zr - 1.0);
        let xa = smoothstep(xr);
        let ya = smoothstep(yr_original);
        let za = smoothstep(zr);
        lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111)
    }

    /// `noiseWithDerivative(x, y, z, derivativeOut)`: adds the gradient to `out`.
    pub fn noise_with_derivative(&self, x: f64, y: f64, z: f64, out: &mut [f64; 3]) -> f64 {
        let x = x + self.xo;
        let y = y + self.yo;
        let z = z + self.zo;
        let xi = self.floor.floor(x);
        let yi = self.floor.floor(y);
        let zi = self.floor.floor(z);
        self.sample_with_derivative(xi, yi, zi, x - xi as f64, y - yi as f64, z - zi as f64, out)
    }

    #[allow(clippy::too_many_arguments)]
    fn sample_with_derivative(&self, x: i32, y: i32, z: i32, xr: f64, yr: f64, zr: f64, out: &mut [f64; 3]) -> f64 {
        let x0 = self.p(x);
        let x1 = self.p(x.wrapping_add(1));
        let y00 = self.p(x0.wrapping_add(y));
        let y01 = self.p(x0.wrapping_add(y).wrapping_add(1));
        let y10 = self.p(x1.wrapping_add(y));
        let y11 = self.p(x1.wrapping_add(y).wrapping_add(1));
        let z1 = z.wrapping_add(1);
        let g = |hash: i32| &GRADIENT[(self.p(hash) & 15) as usize];
        let g000 = g(y00.wrapping_add(z));
        let g100 = g(y10.wrapping_add(z));
        let g010 = g(y01.wrapping_add(z));
        let g110 = g(y11.wrapping_add(z));
        let g001 = g(y00.wrapping_add(z1));
        let g101 = g(y10.wrapping_add(z1));
        let g011 = g(y01.wrapping_add(z1));
        let g111 = g(y11.wrapping_add(z1));
        let d000 = dot(g000, xr, yr, zr);
        let d100 = dot(g100, xr - 1.0, yr, zr);
        let d010 = dot(g010, xr, yr - 1.0, zr);
        let d110 = dot(g110, xr - 1.0, yr - 1.0, zr);
        let d001 = dot(g001, xr, yr, zr - 1.0);
        let d101 = dot(g101, xr - 1.0, yr, zr - 1.0);
        let d011 = dot(g011, xr, yr - 1.0, zr - 1.0);
        let d111 = dot(g111, xr - 1.0, yr - 1.0, zr - 1.0);
        let xa = smoothstep(xr);
        let ya = smoothstep(yr);
        let za = smoothstep(zr);
        let axis = |k: usize| {
            lerp3(
                xa,
                ya,
                za,
                g000[k] as f64,
                g100[k] as f64,
                g010[k] as f64,
                g110[k] as f64,
                g001[k] as f64,
                g101[k] as f64,
                g011[k] as f64,
                g111[k] as f64,
            )
        };
        let (gx, gy, gz) = (axis(0), axis(1), axis(2));
        let dx = crate::mth::lerp2(ya, za, d100 - d000, d110 - d010, d101 - d001, d111 - d011);
        let dy = crate::mth::lerp2(za, xa, d010 - d000, d011 - d001, d110 - d100, d111 - d101);
        let dz = crate::mth::lerp2(xa, ya, d001 - d000, d101 - d100, d011 - d010, d111 - d110);
        let sx = smoothstep_derivative(xr);
        let sy = smoothstep_derivative(yr);
        let sz = smoothstep_derivative(zr);
        out[0] += gx + sx * dx;
        out[1] += gy + sy * dy;
        out[2] += gz + sz * dz;
        lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RandomKind;

    /// `noiseWithDerivative` bits printed by the 26.2 client on the JVM.
    #[test]
    fn derivative_matches_jvm() {
        let noise = ImprovedNoise::new(&mut RandomSource::new(RandomKind::Xoroshiro, 7), FloorMode::MathFloor);
        let cases: [([f64; 3], [u64; 4]); 3] = [
            (
                [0.5, 1.25, -3.75],
                [
                    0x3fd3cac658b36e50,
                    0x3fe0e6441dc121d1,
                    0xbfbb46346251e4a0,
                    0xbff6eaf7af42dfb2,
                ],
            ),
            (
                [-1234.5, 64.0, 98765.4321],
                [
                    0xbfd981d16c7242aa,
                    0xbfe4d16c9d6e14d8,
                    0xbfdbccb9780bfc89,
                    0xbfea9f74a5ace5ea,
                ],
            ),
            (
                [3e7, -2.5, -3e7],
                [
                    0xbfb17ba5c15d8ee3,
                    0xbff1640b8f6a1fcd,
                    0x3feec267a507a254,
                    0xbfd931eb8d01e815,
                ],
            ),
        ];
        for (p, bits) in cases {
            let mut d = [0.0; 3];
            let v = noise.noise_with_derivative(p[0], p[1], p[2], &mut d);
            assert_eq!(
                [v.to_bits(), d[0].to_bits(), d[1].to_bits(), d[2].to_bits()],
                bits,
                "{p:?}"
            );
        }
    }
}
