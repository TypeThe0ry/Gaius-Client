//! Java library semantics used by world generation that Rust does not give
//! verbatim (`Math.max`/`signum`/`round`, `floorDiv`, `BlockPos.asLong`).

pub use gaius_noise::java::{min_f32, min_f64};

/// `Math.max(float, float)`: NaN wins and `0.0 > -0.0`.
#[inline]
pub fn max_f32(a: f32, b: f32) -> f32 {
    if a.is_nan() {
        a
    } else if a == 0.0 && b == 0.0 && a.to_bits() == (-0.0f32).to_bits() {
        b
    } else if a >= b {
        a
    } else {
        b
    }
}

/// `Math.max(double, double)`.
#[inline]
pub fn max_f64(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if a == 0.0 && b == 0.0 && a.to_bits() == (-0.0f64).to_bits() {
        b
    } else if a >= b {
        a
    } else {
        b
    }
}

/// `Math.signum(float)`: zeros and NaN are returned unchanged.
#[inline]
pub fn signum_f32(v: f32) -> f32 {
    if v == 0.0 || v.is_nan() {
        v
    } else if v > 0.0 {
        1.0
    } else {
        -1.0
    }
}

/// `Math.round(float)`: ties towards positive infinity, NaN is 0, saturating.
pub fn round_f32(a: f32) -> i32 {
    let bits = a.to_bits() as i32;
    let biased_exp = (bits & 0x7F80_0000) >> 23;
    let shift = (24 - 2 + 127) - biased_exp;
    if shift & -32 == 0 {
        let mut r = (bits & 0x007F_FFFF) | (0x007F_FFFF + 1);
        if bits < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        a as i32
    }
}

/// `Math.round(double)`.
pub fn round_f64(a: f64) -> i64 {
    let bits = a.to_bits() as i64;
    let biased_exp = (bits & 0x7FF0_0000_0000_0000) >> 52;
    let shift = (53 - 2 + 1023) - biased_exp;
    if shift & -64 == 0 {
        let mut r = (bits & 0x000F_FFFF_FFFF_FFFF) | (0x000F_FFFF_FFFF_FFFF + 1);
        if bits < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        a as i64
    }
}

/// `Math.floorDiv(int, int)`.
#[inline]
pub fn floor_div(a: i32, b: i32) -> i32 {
    let q = a.wrapping_div(b);
    if (a ^ b) < 0 && q.wrapping_mul(b) != a {
        q - 1
    } else {
        q
    }
}

/// `Math.floorMod(int, int)`.
#[inline]
pub fn floor_mod(a: i32, b: i32) -> i32 {
    a.wrapping_sub(floor_div(a, b).wrapping_mul(b))
}

/// `BlockPos.asLong(x, y, z)`.
#[inline]
pub fn block_pos_as_long(x: i32, y: i32, z: i32) -> i64 {
    ((x as i64 & 0x3FF_FFFF) << 38) | (y as i64 & 0xFFF) | ((z as i64 & 0x3FF_FFFF) << 12)
}

/// `BlockPos.getX(long)` and friends.
#[inline]
pub fn block_pos_x(packed: i64) -> i32 {
    (packed >> 38) as i32
}

#[inline]
pub fn block_pos_y(packed: i64) -> i32 {
    ((packed << 52) >> 52) as i32
}

#[inline]
pub fn block_pos_z(packed: i64) -> i32 {
    ((packed << 26) >> 38) as i32
}

/// `ChunkPos.pack(x, z)` / `ColumnPos.asLong(x, z)`.
#[inline]
pub fn pack_xz(x: i32, z: i32) -> i64 {
    (x as i64 & 0xFFFF_FFFF) | ((z as i64 & 0xFFFF_FFFF) << 32)
}

/// `Mth.binarySearch(from, to, predicate)`: the first index in `[from, to)`
/// for which `predicate` holds, or `to`.
#[inline]
pub fn binary_search(mut from: i32, to: i32, predicate: impl Fn(i32) -> bool) -> i32 {
    let mut len = to - from;
    while len > 0 {
        let half = len / 2;
        let middle = from + half;
        if predicate(middle) {
            len = half;
        } else {
            from = middle + 1;
            len -= half + 1;
        }
    }
    from
}

/// `Mth.fastInvSqrt(double)`.
#[inline]
pub fn fast_inv_sqrt(x: f64) -> f64 {
    let xhalf = 0.5 * x;
    let i = 6910469410427058090i64.wrapping_sub((x.to_bits() as i64) >> 1);
    let y = f64::from_bits(i as u64);
    y * (1.5 - xhalf * y * y)
}

/// `Mth.quantize(double, int)`.
#[inline]
pub fn quantize(value: f64, resolution: i32) -> i32 {
    ((value / resolution as f64).floor() as i32).wrapping_mul(resolution)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounding_matches_java() {
        assert_eq!(round_f32(0.5), 1);
        assert_eq!(round_f32(-0.5), 0);
        assert_eq!(round_f32(-1.5), -1);
        assert_eq!(round_f32(0.49999997), 0);
        assert_eq!(round_f32(f32::NAN), 0);
        assert_eq!(round_f32(1e20), i32::MAX);
        assert_eq!(round_f64(2.5), 3);
        assert_eq!(round_f64(-2.5), -2);
        assert_eq!(round_f64(0.49999999999999994), 0);
        assert_eq!(round_f64(f64::NAN), 0);
    }

    #[test]
    fn floor_div_mod() {
        assert_eq!(floor_div(-1, 16), -1);
        assert_eq!(floor_mod(-1, 16), 15);
        assert_eq!(floor_div(7, 2), 3);
        assert_eq!(floor_mod(-7, 3), 2);
    }

    #[test]
    fn block_pos_round_trip() {
        for (x, y, z) in [(0, 0, 0), (-30000000, -64, 29999999), (123, 319, -456)] {
            let packed = block_pos_as_long(x, y, z);
            assert_eq!(
                (block_pos_x(packed), block_pos_y(packed), block_pos_z(packed)),
                (x, y, z)
            );
        }
    }

    #[test]
    fn signum_keeps_zero() {
        assert_eq!(signum_f32(-0.0).to_bits(), (-0.0f32).to_bits());
        assert_eq!(signum_f32(3.0), 1.0);
        assert_eq!(signum_f32(-3.0), -1.0);
    }

    #[test]
    fn max_follows_java() {
        assert_eq!(max_f32(-0.0, 0.0).to_bits(), 0.0f32.to_bits());
        assert_eq!(max_f32(0.0, -0.0).to_bits(), 0.0f32.to_bits());
        assert!(max_f32(f32::NAN, 1.0).is_nan());
        assert!(max_f64(1.0, f64::NAN).is_nan());
    }
}
