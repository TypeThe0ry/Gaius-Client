//! Bit-exact ports of the small vanilla helpers the section compiler leans on:
//! `ARGB`, `LightCoordsUtil`, `Mth.sin/cos/atan2/getSeed`, `Vec3.normalize`
//! and the `LinearCongruentialGenerator` used by the biome zoom.
//!
//! Rust's `as` casts from float to int saturate and map NaN to 0, exactly
//! like the JVM's `f2i`/`d2l`, and float `+ - * /` are IEEE 754 without
//! contraction, so every expression below keeps Java's operand order.

/// `LightCoordsUtil.FULL_BRIGHT`.
pub const FULL_BRIGHT: i32 = 0x00F0_00F0;

/// `Mth.floor(float)`.
#[inline]
pub fn floor_f(value: f32) -> i32 {
    let i = value as i32;
    if value < i as f32 {
        i - 1
    } else {
        i
    }
}

/// `ARGB.as8BitChannel(float)`.
#[inline]
pub fn as_8bit(value: f32) -> i32 {
    floor_f(value * 255.0)
}

#[inline]
pub fn alpha(c: i32) -> i32 {
    ((c as u32) >> 24) as i32
}

#[inline]
pub fn red(c: i32) -> i32 {
    (c >> 16) & 255
}

#[inline]
pub fn green(c: i32) -> i32 {
    (c >> 8) & 255
}

#[inline]
pub fn blue(c: i32) -> i32 {
    c & 255
}

/// `ARGB.color(int, int, int, int)`.
#[inline]
pub fn argb(a: i32, r: i32, g: i32, b: i32) -> i32 {
    ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255)
}

/// `ARGB.gray(float)`.
#[inline]
pub fn gray(value: f32) -> i32 {
    let c = as_8bit(value);
    argb(255, c, c, c)
}

/// `ARGB.opaque(int)`.
#[inline]
pub fn opaque(c: i32) -> i32 {
    c | -16_777_216
}

/// `ARGB.multiply(int, int)`.
#[inline]
pub fn multiply(a: i32, b: i32) -> i32 {
    if a == -1 {
        return b;
    }
    if b == -1 {
        return a;
    }
    argb(
        alpha(a) * alpha(b) / 255,
        red(a) * red(b) / 255,
        green(a) * green(b) / 255,
        blue(a) * blue(b) / 255,
    )
}

/// `ARGB.scaleRGB(int, float)`.
#[inline]
pub fn scale_rgb(c: i32, f: f32) -> i32 {
    let channel = |v: i32| ((v as f32 * f) as i32).clamp(0, 255);
    argb(alpha(c), channel(red(c)), channel(green(c)), channel(blue(c)))
}

/// `ARGB.toABGR(int)`: the int whose little-endian bytes are R, G, B, A.
#[inline]
pub fn to_abgr(c: i32) -> i32 {
    (c & -16_711_936) | ((c & 16_711_680) >> 16) | ((c & 255) << 16)
}

/// `LightCoordsUtil.pack(block, sky)`.
#[inline]
pub fn light_pack(block: i32, sky: i32) -> i32 {
    (block << 4) | (sky << 20)
}

#[inline]
pub fn light_block(c: i32) -> i32 {
    (c >> 4) & 15
}

#[inline]
pub fn light_sky(c: i32) -> i32 {
    (c >> 20) & 15
}

/// `LightCoordsUtil.withBlock(int, int)`.
#[inline]
pub fn light_with_block(c: i32, block: i32) -> i32 {
    (c & 16_711_680) | (block << 4)
}

#[inline]
pub fn smooth_pack(block: i32, sky: i32) -> i32 {
    (block & 255) | ((sky & 255) << 16)
}

#[inline]
pub fn smooth_block(c: i32) -> i32 {
    c & 255
}

#[inline]
pub fn smooth_sky(c: i32) -> i32 {
    (c >> 16) & 255
}

/// `LightCoordsUtil.max(int, int)`.
#[inline]
pub fn light_max(a: i32, b: i32) -> i32 {
    light_pack(light_block(a).max(light_block(b)), light_sky(a).max(light_sky(b)))
}

/// `LightCoordsUtil.lightCoordsWithEmission(int, int)`.
#[inline]
pub fn light_with_emission(c: i32, emission: i32) -> i32 {
    if emission == 0 {
        return c;
    }
    let sky = light_sky(c).max(emission);
    let block = light_block(c).max(emission);
    light_pack(block, sky)
}

/// `LightCoordsUtil.smoothBlend(int, int, int, int)`; `center` is the fourth argument.
#[inline]
pub fn smooth_blend(mut a: i32, mut b: i32, mut c: i32, center: i32) -> i32 {
    if light_sky(center) > 2 || light_block(center) > 2 {
        if a == 0 {
            a = center;
        } else if light_sky(a) == 0 {
            a |= center & 16_711_680;
        }
        if b == 0 {
            b = center;
        } else if light_sky(b) == 0 {
            b |= center & 16_711_680;
        }
        if c == 0 {
            c = center;
        } else if light_sky(c) == 0 {
            c |= center & 16_711_680;
        }
    }
    (a.wrapping_add(b).wrapping_add(c).wrapping_add(center) >> 2) & 16_711_935
}

/// `LightCoordsUtil.smoothWeightedBlend(int, int, int, int, float, float, float, float)`; the
/// simd128 build evaluates the same expression four lanes at a time (`simd::weighted_light`).
#[cfg_attr(all(target_arch = "wasm32", target_feature = "simd128"), allow(dead_code))]
#[inline]
pub fn smooth_weighted_blend(l: [i32; 4], w: [f32; 4]) -> i32 {
    let sky = (smooth_sky(l[0]) as f32 * w[0]
        + smooth_sky(l[1]) as f32 * w[1]
        + smooth_sky(l[2]) as f32 * w[2]
        + smooth_sky(l[3]) as f32 * w[3]) as i32;
    let block = (smooth_block(l[0]) as f32 * w[0]
        + smooth_block(l[1]) as f32 * w[1]
        + smooth_block(l[2]) as f32 * w[2]
        + smooth_block(l[3]) as f32 * w[3]) as i32;
    smooth_pack(block, sky)
}

/// `Mth.getSeed(int, int, int)`.
#[inline]
pub fn block_seed(x: i32, y: i32, z: i32) -> i64 {
    let mut l = (x.wrapping_mul(3_129_871) as i64) ^ (z as i64).wrapping_mul(116_129_781) ^ (y as i64);
    l = l
        .wrapping_mul(l)
        .wrapping_mul(42_317_861)
        .wrapping_add(l.wrapping_mul(11));
    l >> 16
}

/// One entry of `Mth.SIN`: `(float) Math.sin(i / 10430.378350470453)`.
#[inline]
fn sin_table(index: i64) -> f32 {
    ((index as f64) / 10430.378350470453).sin() as f32
}

/// `Mth.sin(double)`.
#[inline]
pub fn mth_sin(value: f64) -> f32 {
    sin_table(((value * 10430.378350470453) as i64) & 65535)
}

/// `Mth.cos(double)`.
#[inline]
pub fn mth_cos(value: f64) -> f32 {
    sin_table(((value * 10430.378350470453 + 16384.0) as i64) & 65535)
}

/// `Mth.FRAC_BIAS`.
const FRAC_BIAS: f64 = f64::from_bits(4_805_340_802_404_319_232);

/// `Mth.fastInvSqrt(double)`.
#[inline]
fn fast_inv_sqrt(value: f64) -> f64 {
    let half = 0.5 * value;
    let bits = 6_910_469_410_427_058_090i64.wrapping_sub((value.to_bits() as i64) >> 1);
    let d = f64::from_bits(bits as u64);
    d * (1.5 - half * d * d)
}

/// `Mth.atan2(double y, double x)`, the table approximation (not `Math.atan2`).
pub fn mth_atan2(mut y: f64, mut x: f64) -> f64 {
    let d = x * x + y * y;
    if d.is_nan() {
        return f64::NAN;
    }
    let neg_y = y < 0.0;
    if neg_y {
        y = -y;
    }
    let neg_x = x < 0.0;
    if neg_x {
        x = -x;
    }
    let swap = y > x;
    if swap {
        core::mem::swap(&mut x, &mut y);
    }
    let inv = fast_inv_sqrt(d);
    x *= inv;
    y *= inv;
    let biased = FRAC_BIAS + y;
    let index = biased.to_bits() as i32;
    // ASIN_TAB[i] = Math.asin(i / 256.0), COS_TAB[i] = Math.cos(ASIN_TAB[i]), 0 <= i <= 256.
    let asin = (index as f64 / 256.0).asin();
    let cos = asin.cos();
    let frac = biased - FRAC_BIAS;
    let e = y * cos - x * frac;
    let correction = (6.0 + e * e) * e * 0.16666666666666666;
    let mut angle = asin + correction;
    if swap {
        angle = core::f64::consts::FRAC_PI_2 - angle;
    }
    if neg_x {
        angle = core::f64::consts::PI - angle;
    }
    if neg_y {
        angle = -angle;
    }
    angle
}

/// `Vec3.normalize()` on (x, y, z).
#[inline]
pub fn normalize(x: f64, y: f64, z: f64) -> (f64, f64, f64) {
    let d = (x * x + y * y + z * z).sqrt();
    if d < 9.999999747378752E-6 {
        (0.0, 0.0, 0.0)
    } else {
        (x / d, y / d, z / d)
    }
}

/// `LinearCongruentialGenerator.next(long, long)`.
#[inline]
pub fn lcg_next(a: i64, b: i64) -> i64 {
    a.wrapping_mul(
        a.wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407),
    )
    .wrapping_add(b)
}

/// `Mth.clamp(double, double, double)`.
#[inline]
pub fn clamp_d(value: f64, min: f64, max: f64) -> f64 {
    if value < min {
        min
    } else {
        value.min(max)
    }
}

/// `DoubleMath.fuzzyEquals(a, b, tolerance)`.
#[inline]
pub fn fuzzy_equals(a: f64, b: f64, tolerance: f64) -> bool {
    (a - b).abs() <= tolerance || a == b || (a.is_nan() && b.is_nan())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colors_follow_argb() {
        assert_eq!(gray(1.0), -1);
        assert_eq!(gray(0.5), argb(255, 127, 127, 127));
        assert_eq!(multiply(-1, 0x1234_5678), 0x1234_5678);
        assert_eq!(multiply(gray(0.5), opaque(0x00FF_8000)), argb(255, 127, 63, 0));
        assert_eq!(scale_rgb(-1, 0.8), argb(255, 204, 204, 204));
        assert_eq!(to_abgr(argb(1, 2, 3, 4)), i32::from_le_bytes([2, 3, 4, 1]));
    }

    #[test]
    fn light_helpers_follow_light_coords_util() {
        assert_eq!(light_pack(15, 15), FULL_BRIGHT);
        assert_eq!(smooth_blend(0, 0, 0, light_pack(4, 15)), light_pack(4, 15));
        assert_eq!(light_with_emission(light_pack(2, 3), 7), light_pack(7, 7));
        assert_eq!(smooth_weighted_blend([FULL_BRIGHT; 4], [0.25; 4]), FULL_BRIGHT);
    }

    #[test]
    fn atan2_is_close_to_the_exact_angle() {
        for &(y, x) in &[(1.0, 1.0), (-0.3, 0.7), (0.9, -0.1), (-1.0, -0.5), (0.0, 1.0)] {
            assert!((mth_atan2(y, x) - f64::atan2(y, x)).abs() < 1e-6, "{y} {x}");
        }
        assert!((mth_sin(1.0) as f64 - 1f64.sin()).abs() < 1e-3);
        assert!((mth_cos(1.0) as f64 - 1f64.cos()).abs() < 1e-3);
    }
}
