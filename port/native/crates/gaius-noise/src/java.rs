//! Java library semantics that Rust does not provide verbatim.
//!
//! Rust's `as` casts already match Java (`d2i`/`d2l` saturate, NaN becomes 0,
//! `l2d`/`d2f` round to nearest) and Rust never fuses multiply-adds, so only
//! the helpers below need explicit code.

/// `Math.min(double, double)`: NaN wins and `-0.0 < 0.0`.
#[inline]
pub fn min_f64(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if a == 0.0 && b == 0.0 && b.to_bits() == (-0.0f64).to_bits() {
        b
    } else if a <= b {
        a
    } else {
        b
    }
}

/// `Math.min(float, float)`.
#[inline]
pub fn min_f32(a: f32, b: f32) -> f32 {
    if a.is_nan() {
        a
    } else if a == 0.0 && b == 0.0 && b.to_bits() == (-0.0f32).to_bits() {
        b
    } else if a <= b {
        a
    } else {
        b
    }
}

/// `Math.pow(2.0, n)`, which is exact for every integer exponent.
pub fn pow2(n: i32) -> f64 {
    if n > 1023 {
        f64::INFINITY
    } else if n >= -1022 {
        f64::from_bits(((n + 1023) as u64) << 52)
    } else if n >= -1074 {
        f64::from_bits(1u64 << (n + 1074))
    } else {
        0.0
    }
}

/// `Math.pow(0.5, n)`.
pub fn pow_half(n: i32) -> f64 {
    pow2(n.checked_neg().unwrap_or(i32::MAX))
}

/// `String.hashCode()` over the UTF-16 code units of `s`.
pub fn string_hash(s: &str) -> i32 {
    s.encode_utf16()
        .fold(0i32, |h, unit| h.wrapping_mul(31).wrapping_add(unit as i32))
}

/// `DoubleStream.sum()` of a sequential stream: Kahan summation as written in
/// `Collectors.sumWithCompensation` / `computeFinalSum` (JDK 17+).
pub fn double_stream_sum<I: IntoIterator<Item = f64>>(values: I) -> f64 {
    let (mut high, mut low_negated, mut simple) = (0.0f64, 0.0f64, 0.0f64);
    for value in values {
        let tmp = value - low_negated;
        let velvel = high + tmp;
        low_negated = (velvel - high) - tmp;
        high = velvel;
        simple += value;
    }
    let tmp = high - low_negated;
    if tmp.is_nan() && simple.is_infinite() {
        simple
    } else {
        tmp
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn min_follows_java() {
        assert_eq!(min_f64(0.0, -0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(min_f64(-0.0, 0.0).to_bits(), (-0.0f64).to_bits());
        assert!(min_f64(1.0, f64::NAN).is_nan());
        assert!(min_f32(f32::NAN, 1.0).is_nan());
        assert_eq!(min_f32(2.0, 1.0), 1.0);
    }

    #[test]
    fn pow2_is_exact() {
        assert_eq!(pow2(0), 1.0);
        assert_eq!(pow2(-15), 1.0 / 32768.0);
        assert_eq!(pow2(10), 1024.0);
        assert_eq!(pow2(-1074), f64::from_bits(1));
        assert_eq!(pow_half(-3), 8.0);
    }

    #[test]
    fn string_hash_matches_java() {
        assert_eq!(string_hash(""), 0);
        assert_eq!(
            string_hash("octave_-7"),
            "octave_-7"
                .bytes()
                .fold(0i32, |h, b| h.wrapping_mul(31).wrapping_add(b as i32))
        );
        // "hello".hashCode() == 99162322
        assert_eq!(string_hash("hello"), 99162322);
    }

    #[test]
    fn kahan_sum_matches_jvm() {
        // Values printed by DoubleStream.of(...).sum() on JDK 25.
        assert_eq!(double_stream_sum([1.0, 1e100, 1.0, -1e100]), 0.0);
        assert_eq!(double_stream_sum([0.1, 0.2, 0.3]).to_bits(), 0x3fe3333333333333);
        assert_ne!((0.1f64 + 0.2 + 0.3).to_bits(), 0x3fe3333333333333);
        assert_eq!(double_stream_sum([f64::INFINITY, f64::INFINITY]), f64::INFINITY);
    }
}
