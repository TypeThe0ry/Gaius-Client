//! `net.minecraft.util.Mth`, double and float overloads.

use crate::java;

/// How `Mth.floor` / `Mth.lfloor` are implemented by a profile.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum FloorMode {
    /// 1.21.11: `int i = (int) d; return d < i ? i - 1 : i;`
    CastAdjust,
    /// 26.2+: `(int) Math.floor(d)`.
    MathFloor,
}

impl FloorMode {
    /// `Mth.floor(double)`.
    #[inline]
    pub fn floor(self, d: f64) -> i32 {
        match self {
            FloorMode::CastAdjust => {
                let i = d as i32;
                if d < i as f64 {
                    i.wrapping_sub(1)
                } else {
                    i
                }
            }
            FloorMode::MathFloor => d.floor() as i32,
        }
    }

    /// `Mth.floor(float)`.
    #[inline]
    pub fn floor_f32(self, f: f32) -> i32 {
        match self {
            FloorMode::CastAdjust => {
                let i = f as i32;
                if f < i as f32 {
                    i.wrapping_sub(1)
                } else {
                    i
                }
            }
            FloorMode::MathFloor => (f as f64).floor() as i32,
        }
    }

    /// `Mth.lfloor(double)`.
    #[inline]
    pub fn lfloor(self, d: f64) -> i64 {
        match self {
            FloorMode::CastAdjust => {
                let l = d as i64;
                if d < l as f64 {
                    l.wrapping_sub(1)
                } else {
                    l
                }
            }
            FloorMode::MathFloor => d.floor() as i64,
        }
    }
}

/// `Mth.floor(double)` of 26.2 and later.
#[inline]
pub fn floor(d: f64) -> i32 {
    d.floor() as i32
}

#[inline]
pub fn square(d: f64) -> f64 {
    d * d
}

#[inline]
pub fn lerp(delta: f64, start: f64, end: f64) -> f64 {
    start + delta * (end - start)
}

#[inline]
pub fn lerp2(dx: f64, dy: f64, x0y0: f64, x1y0: f64, x0y1: f64, x1y1: f64) -> f64 {
    lerp(dy, lerp(dx, x0y0, x1y0), lerp(dx, x0y1, x1y1))
}

#[allow(clippy::too_many_arguments)]
#[inline]
pub fn lerp3(
    dx: f64,
    dy: f64,
    dz: f64,
    c000: f64,
    c100: f64,
    c010: f64,
    c110: f64,
    c001: f64,
    c101: f64,
    c011: f64,
    c111: f64,
) -> f64 {
    lerp(
        dz,
        lerp2(dx, dy, c000, c100, c010, c110),
        lerp2(dx, dy, c001, c101, c011, c111),
    )
}

#[inline]
pub fn smoothstep(x: f64) -> f64 {
    x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

#[inline]
pub fn smoothstep_derivative(x: f64) -> f64 {
    30.0 * x * x * (x - 1.0) * (x - 1.0)
}

#[inline]
pub fn clamp(value: f64, min: f64, max: f64) -> f64 {
    if value < min {
        min
    } else {
        java::min_f64(value, max)
    }
}

#[inline]
pub fn inverse_lerp(value: f64, start: f64, end: f64) -> f64 {
    (value - start) / (end - start)
}

#[inline]
pub fn clamped_lerp(factor: f64, min: f64, max: f64) -> f64 {
    if factor < 0.0 {
        min
    } else if factor > 1.0 {
        max
    } else {
        lerp(factor, min, max)
    }
}

#[inline]
pub fn clamped_map(value: f64, from_a: f64, from_b: f64, to_a: f64, to_b: f64) -> f64 {
    clamped_lerp(inverse_lerp(value, from_a, from_b), to_a, to_b)
}

#[inline]
pub fn map(value: f64, from_a: f64, from_b: f64, to_a: f64, to_b: f64) -> f64 {
    lerp(inverse_lerp(value, from_a, from_b), to_a, to_b)
}

/// `Mth.getSeed(int, int, int)`.
pub fn get_seed(x: i32, y: i32, z: i32) -> i64 {
    let mut seed = (x.wrapping_mul(3129871) as i64) ^ (z as i64).wrapping_mul(116129781) ^ (y as i64);
    seed = seed
        .wrapping_mul(seed)
        .wrapping_mul(42317861)
        .wrapping_add(seed.wrapping_mul(11));
    seed >> 16
}

/// Float overloads (26.3 noise).
pub mod float {
    use crate::java;

    #[inline]
    pub fn lerp(delta: f32, start: f32, end: f32) -> f32 {
        start + delta * (end - start)
    }

    #[inline]
    pub fn lerp2(dx: f32, dy: f32, x0y0: f32, x1y0: f32, x0y1: f32, x1y1: f32) -> f32 {
        lerp(dy, lerp(dx, x0y0, x1y0), lerp(dx, x0y1, x1y1))
    }

    #[allow(clippy::too_many_arguments)]
    #[inline]
    pub fn lerp3(
        dx: f32,
        dy: f32,
        dz: f32,
        c000: f32,
        c100: f32,
        c010: f32,
        c110: f32,
        c001: f32,
        c101: f32,
        c011: f32,
        c111: f32,
    ) -> f32 {
        lerp(
            dz,
            lerp2(dx, dy, c000, c100, c010, c110),
            lerp2(dx, dy, c001, c101, c011, c111),
        )
    }

    #[inline]
    pub fn smoothstep(x: f32) -> f32 {
        x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
    }

    #[inline]
    pub fn smoothstep_derivative(x: f32) -> f32 {
        30.0 * x * x * (x - 1.0) * (x - 1.0)
    }

    #[inline]
    pub fn inverse_lerp(value: f32, start: f32, end: f32) -> f32 {
        (value - start) / (end - start)
    }

    #[inline]
    pub fn clamped_lerp(factor: f32, min: f32, max: f32) -> f32 {
        if factor < 0.0 {
            min
        } else if factor > 1.0 {
            max
        } else {
            lerp(factor, min, max)
        }
    }

    #[inline]
    pub fn clamped_map(value: f32, from_a: f32, from_b: f32, to_a: f32, to_b: f32) -> f32 {
        clamped_lerp(inverse_lerp(value, from_a, from_b), to_a, to_b)
    }

    #[inline]
    pub fn map(value: f32, from_a: f32, from_b: f32, to_a: f32, to_b: f32) -> f32 {
        lerp(inverse_lerp(value, from_a, from_b), to_a, to_b)
    }

    #[inline]
    pub fn clamp(value: f32, min: f32, max: f32) -> f32 {
        if value < min {
            min
        } else {
            java::min_f32(value, max)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn floor_modes_agree_in_range_and_differ_below_int_min() {
        for d in [-2.5, -2.0, -0.0, 0.0, 0.5, 1.0, 1e9, -1e9, f64::NAN] {
            assert_eq!(FloorMode::CastAdjust.floor(d), FloorMode::MathFloor.floor(d), "{d}");
            assert_eq!(FloorMode::CastAdjust.lfloor(d), FloorMode::MathFloor.lfloor(d), "{d}");
        }
        // (int) -3e9 == MIN_VALUE, then MIN_VALUE - 1 wraps around.
        assert_eq!(FloorMode::CastAdjust.floor(-3e9), i32::MAX);
        assert_eq!(FloorMode::MathFloor.floor(-3e9), i32::MIN);
        assert_eq!(FloorMode::MathFloor.floor(3e9), i32::MAX);
    }

    #[test]
    fn get_seed_known_value() {
        // Hand-computed with the Java expression for (1, 2, 3).
        let l: i64 = (3129871i64) ^ (3i64 * 116129781) ^ 2;
        let expected = (l
            .wrapping_mul(l)
            .wrapping_mul(42317861)
            .wrapping_add(l.wrapping_mul(11)))
            >> 16;
        assert_eq!(get_seed(1, 2, 3), expected);
        // Printed by Mth.getSeed on the JVM.
        assert_eq!(get_seed(1, 2, 3), -33674130277896);
        assert_eq!(get_seed(-30000000, 320, 29999999), 52871691179874);
    }

    #[test]
    fn clamped_lerp_bounds() {
        assert_eq!(clamped_lerp(-1.0, 3.0, 5.0), 3.0);
        assert_eq!(clamped_lerp(2.0, 3.0, 5.0), 5.0);
        assert_eq!(clamped_lerp(0.5, 3.0, 5.0), 4.0);
        assert!(clamped_lerp(f64::NAN, 3.0, 5.0).is_nan());
        assert_eq!(clamp(-0.0, 0.0, 1.0).to_bits(), (-0.0f64).to_bits());
    }
}
