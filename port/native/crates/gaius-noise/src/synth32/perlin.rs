//! `synth.PerlinNoise` (one octave, float result) and `synth.SmearedPerlinNoise`.

use super::gradient::{wrap, GradientBase, GRADIENT};
use super::volume::DensityVolume;
use crate::mth::float::{lerp2, lerp3, smoothstep, smoothstep_derivative};
use crate::mth::floor;
use crate::RandomSource;

/// `(double) 1.0E-7f`
const SHIFT_UP_EPSILON: f64 = 1.0000000116860974E-7;

#[derive(Clone, Debug)]
pub struct PerlinNoise {
    base: GradientBase,
}

impl PerlinNoise {
    pub fn new(random: &mut RandomSource) -> Self {
        PerlinNoise {
            base: GradientBase::new(random, 256.0),
        }
    }

    pub fn base(&self) -> &GradientBase {
        &self.base
    }

    /// `get(x, z)`.
    pub fn get_2d(&self, x: f64, z: f64) -> f32 {
        self.get(wrap(x), 0.0, wrap(z))
    }

    /// `get(x, y, z)`.
    pub fn get(&self, x: f64, y: f64, z: f64) -> f32 {
        let x = wrap(x) + self.base.offset_x;
        let y = wrap(y) + self.base.offset_y;
        let z = wrap(z) + self.base.offset_z;
        let (xi, yi, zi) = (floor(x), floor(y), floor(z));
        let xr = (x - xi as f64) as f32;
        let yr = (y - yi as f64) as f32;
        let zr = (z - zi as f64) as f32;
        sample_and_lerp(&self.base, xi, yi, zi, xr, yr, zr, yr)
    }

    /// `noiseWithDerivative(x, y, z, derivativeOut)`: adds the gradient to `out`.
    pub fn noise_with_derivative(&self, x: f64, y: f64, z: f64, out: &mut [f32; 3]) -> f32 {
        let x = wrap(x) + self.base.offset_x;
        let y = wrap(y) + self.base.offset_y;
        let z = wrap(z) + self.base.offset_z;
        let (xi, yi, zi) = (floor(x), floor(y), floor(z));
        let xr = (x - xi as f64) as f32;
        let yr = (y - yi as f64) as f32;
        let zr = (z - zi as f64) as f32;
        sample_with_derivative(&self.base, xi, yi, zi, xr, yr, zr, out)
    }

    /// `addToVolume(buffer, volume, xzScale, yScale, scale)`.
    pub fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, scale: f32) {
        fill_volume(&self.base, buffer, volume, xz_scale, y_scale, scale, |_, yr| yr as f32);
    }
}

#[derive(Clone, Debug)]
pub struct SmearedPerlinNoise {
    base: GradientBase,
    fudge_y_scale: f64,
}

impl SmearedPerlinNoise {
    pub fn new(random: &mut RandomSource, fudge_y_scale: f64) -> Self {
        SmearedPerlinNoise {
            base: GradientBase::new(random, 256.0),
            fudge_y_scale,
        }
    }

    pub fn base(&self) -> &GradientBase {
        &self.base
    }

    pub fn fudge_y_scale(&self) -> f64 {
        self.fudge_y_scale
    }

    /// `computeFudgeY(y, yFract)`: `y` is the unwrapped input coordinate.
    #[inline]
    fn compute_fudge_y(&self, y: f64, y_fract: f64) -> f64 {
        let limit = if y >= 0.0 && y < y_fract { y } else { y_fract };
        floor(limit / self.fudge_y_scale + SHIFT_UP_EPSILON) as f64 * self.fudge_y_scale
    }

    /// `get(x, z)`, inherited from `PerlinNoise`.
    pub fn get_2d(&self, x: f64, z: f64) -> f32 {
        self.get(wrap(x), 0.0, wrap(z))
    }

    pub fn get(&self, x: f64, y: f64, z: f64) -> f32 {
        let xw = wrap(x) + self.base.offset_x;
        let yw = wrap(y) + self.base.offset_y;
        let zw = wrap(z) + self.base.offset_z;
        let (xi, yi, zi) = (floor(xw), floor(yw), floor(zw));
        let xr = (xw - xi as f64) as f32;
        let yr = yw - yi as f64;
        let zr = (zw - zi as f64) as f32;
        let fudged = (yr - self.compute_fudge_y(y, yr)) as f32;
        sample_and_lerp(&self.base, xi, yi, zi, xr, fudged, zr, yr as f32)
    }

    pub fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, scale: f32) {
        fill_volume(&self.base, buffer, volume, xz_scale, y_scale, scale, |y_in, yr| {
            (yr - self.compute_fudge_y(y_in, yr)) as f32
        });
    }
}

#[inline]
fn grad_dot(hash: i32, x: f32, y: f32, z: f32) -> f32 {
    GRADIENT[(hash & 15) as usize].dot(x, y, z)
}

/// `PerlinNoise.sampleAndLerp`.
#[allow(clippy::too_many_arguments)]
#[inline]
pub(crate) fn sample_and_lerp(
    b: &GradientBase,
    x: i32,
    y: i32,
    z: i32,
    xr: f32,
    yr: f32,
    zr: f32,
    yr_original: f32,
) -> f32 {
    let x0 = b.permute(x);
    let x1 = b.permute(x.wrapping_add(1));
    let y00 = b.permute(x0.wrapping_add(y));
    let y01 = b.permute(x0.wrapping_add(y).wrapping_add(1));
    let y10 = b.permute(x1.wrapping_add(y));
    let y11 = b.permute(x1.wrapping_add(y).wrapping_add(1));
    let z1 = z.wrapping_add(1);
    let d000 = grad_dot(b.permute(y00.wrapping_add(z)), xr, yr, zr);
    let d100 = grad_dot(b.permute(y10.wrapping_add(z)), xr - 1.0, yr, zr);
    let d010 = grad_dot(b.permute(y01.wrapping_add(z)), xr, yr - 1.0, zr);
    let d110 = grad_dot(b.permute(y11.wrapping_add(z)), xr - 1.0, yr - 1.0, zr);
    let d001 = grad_dot(b.permute(y00.wrapping_add(z1)), xr, yr, zr - 1.0);
    let d101 = grad_dot(b.permute(y10.wrapping_add(z1)), xr - 1.0, yr, zr - 1.0);
    let d011 = grad_dot(b.permute(y01.wrapping_add(z1)), xr, yr - 1.0, zr - 1.0);
    let d111 = grad_dot(b.permute(y11.wrapping_add(z1)), xr - 1.0, yr - 1.0, zr - 1.0);
    lerp3(
        smoothstep(xr),
        smoothstep(yr_original),
        smoothstep(zr),
        d000,
        d100,
        d010,
        d110,
        d001,
        d101,
        d011,
        d111,
    )
}

#[allow(clippy::too_many_arguments)]
fn sample_with_derivative(
    b: &GradientBase,
    x: i32,
    y: i32,
    z: i32,
    xr: f32,
    yr: f32,
    zr: f32,
    out: &mut [f32; 3],
) -> f32 {
    let x0 = b.permute(x);
    let x1 = b.permute(x.wrapping_add(1));
    let y00 = b.permute(x0.wrapping_add(y));
    let y01 = b.permute(x0.wrapping_add(y).wrapping_add(1));
    let y10 = b.permute(x1.wrapping_add(y));
    let y11 = b.permute(x1.wrapping_add(y).wrapping_add(1));
    let z1 = z.wrapping_add(1);
    let g000 = b.permute_to_grad(y00.wrapping_add(z));
    let g100 = b.permute_to_grad(y10.wrapping_add(z));
    let g010 = b.permute_to_grad(y01.wrapping_add(z));
    let g110 = b.permute_to_grad(y11.wrapping_add(z));
    let g001 = b.permute_to_grad(y00.wrapping_add(z1));
    let g101 = b.permute_to_grad(y10.wrapping_add(z1));
    let g011 = b.permute_to_grad(y01.wrapping_add(z1));
    let g111 = b.permute_to_grad(y11.wrapping_add(z1));
    let d000 = g000.dot(xr, yr, zr);
    let d100 = g100.dot(xr - 1.0, yr, zr);
    let d010 = g010.dot(xr, yr - 1.0, zr);
    let d110 = g110.dot(xr - 1.0, yr - 1.0, zr);
    let d001 = g001.dot(xr, yr, zr - 1.0);
    let d101 = g101.dot(xr - 1.0, yr, zr - 1.0);
    let d011 = g011.dot(xr, yr - 1.0, zr - 1.0);
    let d111 = g111.dot(xr - 1.0, yr - 1.0, zr - 1.0);
    let (xa, ya, za) = (smoothstep(xr), smoothstep(yr), smoothstep(zr));
    let corners = [g000, g100, g010, g110, g001, g101, g011, g111];
    let axis = |pick: fn(&super::gradient::Gradient) -> i32| {
        let c = corners.map(|g| pick(&g) as f32);
        lerp3(xa, ya, za, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7])
    };
    let gx = axis(|g| g.x);
    let gy = axis(|g| g.y);
    let gz = axis(|g| g.z);
    let dx = lerp2(ya, za, d100 - d000, d110 - d010, d101 - d001, d111 - d011);
    let dy = lerp2(za, xa, d010 - d000, d011 - d001, d110 - d100, d111 - d101);
    let dz = lerp2(xa, ya, d001 - d000, d101 - d100, d011 - d010, d111 - d110);
    out[0] += gx + smoothstep_derivative(xr) * dx;
    out[1] += gy + smoothstep_derivative(yr) * dy;
    out[2] += gz + smoothstep_derivative(zr) * dz;
    lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111)
}

/// The shared body of `PerlinNoise.addToVolume` and
/// `SmearedPerlinNoise.addToVolume`. `y_fraction(y_input, y_fract)` returns
/// the float y fraction fed to the corner dot products.
///
/// The corner cache deliberately persists across x and z columns and starts
/// at zero, exactly like the vanilla locals.
#[allow(clippy::too_many_arguments)]
fn fill_volume(
    b: &GradientBase,
    buffer: &mut [f32],
    volume: &DensityVolume,
    xz_scale: f64,
    y_scale: f64,
    scale: f32,
    y_fraction: impl Fn(f64, f64) -> f32,
) {
    let mut dxz = [0.0f32; 8];
    let mut gy = [0.0f32; 8];
    let mut index = 0usize;
    for zi in 0..volume.size_z {
        let zw = wrap(volume.block_z(zi) as f64 * xz_scale) + b.offset_z;
        let zf = floor(zw);
        let zr = (zw - zf as f64) as f32;
        let zs = smoothstep(zr);
        for xi in 0..volume.size_x {
            let xw = wrap(volume.block_x(xi) as f64 * xz_scale) + b.offset_x;
            let xf = floor(xw);
            let xr = (xw - xf as f64) as f32;
            let px0 = b.permute(xf);
            let px1 = b.permute(xf.wrapping_add(1));
            let xs = smoothstep(xr);
            let mut last_y = i32::MIN;
            for yi in 0..volume.size_y {
                let y_in = volume.block_y(yi) as f64 * y_scale;
                let yw = wrap(y_in) + b.offset_y;
                let yf = floor(yw);
                let yr = yw - yf as f64;
                let ys = smoothstep(yr as f32);
                if last_y != yf {
                    let p00 = b.permute(px0.wrapping_add(yf));
                    let p01 = b.permute(px0.wrapping_add(yf).wrapping_add(1));
                    let p10 = b.permute(px1.wrapping_add(yf));
                    let p11 = b.permute(px1.wrapping_add(yf).wrapping_add(1));
                    let corners = [
                        (p00, zf, xr, zr),
                        (p10, zf, xr - 1.0, zr),
                        (p01, zf, xr, zr),
                        (p11, zf, xr - 1.0, zr),
                        (p00, zf.wrapping_add(1), xr, zr - 1.0),
                        (p10, zf.wrapping_add(1), xr - 1.0, zr - 1.0),
                        (p01, zf.wrapping_add(1), xr, zr - 1.0),
                        (p11, zf.wrapping_add(1), xr - 1.0, zr - 1.0),
                    ];
                    for (k, (p, z, cx, cz)) in corners.into_iter().enumerate() {
                        let gradient = b.permute_to_grad(p.wrapping_add(z));
                        dxz[k] = gradient.dot_xz(cx, cz);
                        gy[k] = gradient.y as f32;
                    }
                    last_y = yf;
                }
                let fy = y_fraction(y_in, yr);
                let fy1 = fy - 1.0;
                let value = lerp3(
                    xs,
                    ys,
                    zs,
                    dxz[0] + gy[0] * fy,
                    dxz[1] + gy[1] * fy,
                    dxz[2] + gy[2] * fy1,
                    dxz[3] + gy[3] * fy1,
                    dxz[4] + gy[4] * fy,
                    dxz[5] + gy[5] * fy,
                    dxz[6] + gy[6] * fy1,
                    dxz[7] + gy[7] * fy1,
                );
                buffer[index] += scale * value;
                index += 1;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RandomKind;

    /// `noiseWithDerivative` bits printed by the 26.3 client on the JVM.
    #[test]
    fn derivative_matches_jvm() {
        let noise = PerlinNoise::new(&mut RandomSource::new(RandomKind::Xoroshiro, 7));
        let cases: [([f64; 3], [u32; 4]); 3] = [
            ([0.5, 1.25, -3.75], [0x3e9e562e, 0x3f07321c, 0xbdda31c4, 0xbfb757c0]),
            (
                [-1234.5, 64.0, 98765.4321],
                [0xbecc0e8d, 0xbf268b65, 0xbede65cf, 0xbf54fba3],
            ),
            ([3e7, -2.5, -3e7], [0xbd8bdd2f, 0xbf8b205d, 0x3f76133e, 0xbec98f5d]),
        ];
        for (p, bits) in cases {
            let mut d = [0.0f32; 3];
            let v = noise.noise_with_derivative(p[0], p[1], p[2], &mut d);
            assert_eq!(
                [v.to_bits(), d[0].to_bits(), d[1].to_bits(), d[2].to_bits()],
                bits,
                "{p:?}"
            );
        }
    }
}
