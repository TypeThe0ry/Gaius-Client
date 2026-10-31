//! `synth.SimplexNoise` (1.21.11 / 26.2). Inputs are used as given; the
//! `xo/yo/zo` offsets are only exposed for callers that add them.

use super::{dot, shuffled_permutation, GRADIENT};
use crate::mth::FloorMode;
use crate::RandomSource;

const F3: f64 = 0.3333333333333333;
const G3: f64 = 0.16666666666666666;

#[derive(Clone, Debug)]
pub struct SimplexNoise {
    p: [u8; 256],
    pub xo: f64,
    pub yo: f64,
    pub zo: f64,
    f2: f64,
    g2: f64,
    floor: FloorMode,
}

impl SimplexNoise {
    pub fn new(random: &mut RandomSource, floor: FloorMode) -> Self {
        let xo = random.next_double() * 256.0;
        let yo = random.next_double() * 256.0;
        let zo = random.next_double() * 256.0;
        let p = shuffled_permutation(random);
        let sqrt3 = 3.0f64.sqrt();
        SimplexNoise {
            p,
            xo,
            yo,
            zo,
            f2: 0.5 * (sqrt3 - 1.0),
            g2: (3.0 - sqrt3) / 6.0,
            floor,
        }
    }

    #[inline]
    fn p(&self, index: i32) -> i32 {
        self.p[(index & 255) as usize] as i32
    }

    #[inline]
    fn corner(&self, index: i32, x: f64, y: f64, z: f64, base: f64) -> f64 {
        let mut t = base - x * x - y * y - z * z;
        if t < 0.0 {
            0.0
        } else {
            t *= t;
            t * t * dot(&GRADIENT[index as usize], x, y, z)
        }
    }

    /// `getValue(double, double)`.
    pub fn get_value_2d(&self, x: f64, y: f64) -> f64 {
        let s = (x + y) * self.f2;
        let i = self.floor.floor(x + s);
        let j = self.floor.floor(y + s);
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
        let gi0 = self.p(ii + self.p(jj)) % 12;
        let gi1 = self.p(ii + i1 + self.p(jj + j1)) % 12;
        let gi2 = self.p(ii + 1 + self.p(jj + 1)) % 12;
        let n0 = self.corner(gi0, x0, y0, 0.0, 0.5);
        let n1 = self.corner(gi1, x1, y1, 0.0, 0.5);
        let n2 = self.corner(gi2, x2, y2, 0.0, 0.5);
        70.0 * (n0 + n1 + n2)
    }

    /// `getValue(double, double, double)`.
    pub fn get_value_3d(&self, x: f64, y: f64, z: f64) -> f64 {
        let s = (x + y + z) * F3;
        let i = self.floor.floor(x + s);
        let j = self.floor.floor(y + s);
        let k = self.floor.floor(z + s);
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
        let gi0 = self.p(ii + self.p(jj + self.p(kk))) % 12;
        let gi1 = self.p(ii + i1 + self.p(jj + j1 + self.p(kk + k1))) % 12;
        let gi2 = self.p(ii + i2 + self.p(jj + j2 + self.p(kk + k2))) % 12;
        let gi3 = self.p(ii + 1 + self.p(jj + 1 + self.p(kk + 1))) % 12;
        let n0 = self.corner(gi0, x0, y0, z0, 0.6);
        let n1 = self.corner(gi1, x1, y1, z1, 0.6);
        let n2 = self.corner(gi2, x2, y2, z2, 0.6);
        let n3 = self.corner(gi3, x3, y3, z3, 0.6);
        32.0 * (n0 + n1 + n2 + n3)
    }
}

/// Second and third simplex corner offsets, in vanilla's branch order.
#[inline]
pub(crate) fn simplex_corners(x0: f64, y0: f64, z0: f64) -> (i32, i32, i32, i32, i32, i32) {
    if x0 >= y0 {
        if y0 >= z0 {
            (1, 0, 0, 1, 1, 0)
        } else if x0 >= z0 {
            (1, 0, 0, 1, 0, 1)
        } else {
            (0, 0, 1, 1, 0, 1)
        }
    } else if y0 < z0 {
        (0, 0, 1, 0, 1, 1)
    } else if x0 < z0 {
        (0, 1, 0, 0, 1, 1)
    } else {
        (0, 1, 0, 1, 1, 0)
    }
}
