//! `synth.SimplexNoise` (26.3): offsets are now applied inside `get`, and
//! the result is narrowed to float.

use super::gradient::{GradientBase, GRADIENT};
use super::volume::DensityVolume;
use crate::mth::floor;
use crate::synth64::simplex_corners;
use crate::RandomSource;

const F3: f64 = 0.3333333333333333;
const G3: f64 = 0.16666666666666666;

#[derive(Clone, Debug)]
pub struct SimplexNoise {
    base: GradientBase,
    f2: f64,
    g2: f64,
}

impl SimplexNoise {
    pub fn new(random: &mut RandomSource) -> Self {
        Self::with_zero_offsets(random, false)
    }

    /// `new SimplexNoise(random, boolean)`: `true` scales the offsets by zero
    /// (the three offset doubles are still drawn).
    pub fn with_zero_offsets(random: &mut RandomSource, zero_offsets: bool) -> Self {
        let scale = if zero_offsets { 0.0 } else { 256.0 };
        let sqrt3 = 3.0f64.sqrt();
        SimplexNoise {
            base: GradientBase::new(random, scale),
            f2: 0.5 * (sqrt3 - 1.0),
            g2: (3.0 - sqrt3) / 6.0,
        }
    }

    pub fn base(&self) -> &GradientBase {
        &self.base
    }

    #[inline]
    fn corner(index: i32, x: f64, y: f64, z: f64, base: f64) -> f64 {
        let mut t = base - x * x - y * y - z * z;
        if t < 0.0 {
            0.0
        } else {
            t *= t;
            t * t * GRADIENT[index as usize].dot_f64(x, y, z)
        }
    }

    /// `get(x, y)`.
    pub fn get_2d(&self, x: f64, y: f64) -> f32 {
        let b = &self.base;
        let x = x + b.offset_x;
        let y = y + b.offset_y;
        let s = (x + y) * self.f2;
        let i = floor(x + s);
        let j = floor(y + s);
        let t = i.wrapping_add(j) as f64 * self.g2;
        let x0 = x - (i as f64 - t);
        let y0 = y - (j as f64 - t);
        let (i1, j1) = if x0 > y0 { (1, 0) } else { (0, 1) };
        let x1 = x0 - i1 as f64 + self.g2;
        let y1 = y0 - j1 as f64 + self.g2;
        let x2 = x0 - 1.0 + 2.0 * self.g2;
        let y2 = y0 - 1.0 + 2.0 * self.g2;
        let ii = i & 255;
        let jj = j & 255;
        let gi0 = b.permute(ii + b.permute(jj)) % 12;
        let gi1 = b.permute(ii + i1 + b.permute(jj + j1)) % 12;
        let gi2 = b.permute(ii + 1 + b.permute(jj + 1)) % 12;
        let n0 = Self::corner(gi0, x0, y0, 0.0, 0.5);
        let n1 = Self::corner(gi1, x1, y1, 0.0, 0.5);
        let n2 = Self::corner(gi2, x2, y2, 0.0, 0.5);
        (70.0 * (n0 + n1 + n2)) as f32
    }

    /// `get(x, y, z)`.
    pub fn get(&self, x: f64, y: f64, z: f64) -> f32 {
        let b = &self.base;
        let x = x + b.offset_x;
        let y = y + b.offset_y;
        let z = z + b.offset_z;
        let s = (x + y + z) * F3;
        let i = floor(x + s);
        let j = floor(y + s);
        let k = floor(z + s);
        let t = i.wrapping_add(j).wrapping_add(k) as f64 * G3;
        let x0 = x - (i as f64 - t);
        let y0 = y - (j as f64 - t);
        let z0 = z - (k as f64 - t);
        let (i1, j1, k1, i2, j2, k2) = simplex_corners(x0, y0, z0);
        let x1 = x0 - i1 as f64 + G3;
        let y1 = y0 - j1 as f64 + G3;
        let z1 = z0 - k1 as f64 + G3;
        let x2 = x0 - i2 as f64 + F3;
        let y2 = y0 - j2 as f64 + F3;
        let z2 = z0 - k2 as f64 + F3;
        let x3 = x0 - 1.0 + 0.5;
        let y3 = y0 - 1.0 + 0.5;
        let z3 = z0 - 1.0 + 0.5;
        let ii = i & 255;
        let jj = j & 255;
        let kk = k & 255;
        let gi0 = b.permute(ii + b.permute(jj + b.permute(kk))) % 12;
        let gi1 = b.permute(ii + i1 + b.permute(jj + j1 + b.permute(kk + k1))) % 12;
        let gi2 = b.permute(ii + i2 + b.permute(jj + j2 + b.permute(kk + k2))) % 12;
        let gi3 = b.permute(ii + 1 + b.permute(jj + 1 + b.permute(kk + 1))) % 12;
        let n0 = Self::corner(gi0, x0, y0, z0, 0.6);
        let n1 = Self::corner(gi1, x1, y1, z1, 0.6);
        let n2 = Self::corner(gi2, x2, y2, z2, 0.6);
        let n3 = Self::corner(gi3, x3, y3, z3, 0.6);
        (32.0 * (n0 + n1 + n2 + n3)) as f32
    }

    /// `Noise.addToVolume` default: one `get` per cell.
    pub fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, scale: f32) {
        super::add_to_volume_pointwise(buffer, volume, xz_scale, y_scale, scale, |x, y, z| self.get(x, y, z));
    }
}
