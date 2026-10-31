//! Lane-wise buffer arithmetic. With `simd128` enabled (the optimized wasm
//! build) the loops run four `f32` lanes at a time; the baseline build and
//! native tests use the scalar loops. Every lane operation is the same IEEE 754
//! single-precision add/multiply as the scalar code, so both paths are bit
//! identical to the vanilla `float` loops.

#[cfg(all(target_arch = "wasm32", target_feature = "simd128"))]
mod imp {
    use core::arch::wasm32::*;

    macro_rules! binary {
        ($name:ident, $op:ident, $scalar:expr) => {
            pub fn $name(out: &mut [f32], rhs: &[f32]) {
                let n = out.len().min(rhs.len());
                let split = n & !3;
                let (head, tail) = out[..n].split_at_mut(split);
                let (rhead, rtail) = rhs[..n].split_at(split);
                for (o, r) in head.chunks_exact_mut(4).zip(rhead.chunks_exact(4)) {
                    // SAFETY: both chunks hold exactly four f32 (16 bytes); v128 loads and
                    // stores accept unaligned addresses.
                    unsafe {
                        let a = v128_load(o.as_ptr() as *const v128);
                        let b = v128_load(r.as_ptr() as *const v128);
                        v128_store(o.as_mut_ptr() as *mut v128, $op(a, b));
                    }
                }
                let f: fn(f32, f32) -> f32 = $scalar;
                for (o, &r) in tail.iter_mut().zip(rtail) {
                    *o = f(*o, r);
                }
            }
        };
    }

    macro_rules! scalar_op {
        ($name:ident, $op:ident, $scalar:expr) => {
            pub fn $name(out: &mut [f32], c: f32) {
                let split = out.len() & !3;
                let (head, tail) = out.split_at_mut(split);
                let cv = f32x4_splat(c);
                for o in head.chunks_exact_mut(4) {
                    // SAFETY: the chunk holds exactly four f32; unaligned v128 access is allowed.
                    unsafe {
                        let a = v128_load(o.as_ptr() as *const v128);
                        v128_store(o.as_mut_ptr() as *mut v128, $op(a, cv));
                    }
                }
                let f: fn(f32, f32) -> f32 = $scalar;
                for o in tail.iter_mut() {
                    *o = f(*o, c);
                }
            }
        };
    }

    binary!(add_assign, f32x4_add, |a, b| a + b);
    binary!(sub_assign, f32x4_sub, |a, b| a - b);
    binary!(mul_assign, f32x4_mul, |a, b| a * b);
    scalar_op!(add_scalar, f32x4_add, |a, c| a + c);
    scalar_op!(mul_scalar, f32x4_mul, |a, c| a * c);
}

#[cfg(not(all(target_arch = "wasm32", target_feature = "simd128")))]
mod imp {
    pub fn add_assign(out: &mut [f32], rhs: &[f32]) {
        for (o, &r) in out.iter_mut().zip(rhs) {
            *o += r;
        }
    }

    pub fn sub_assign(out: &mut [f32], rhs: &[f32]) {
        for (o, &r) in out.iter_mut().zip(rhs) {
            *o -= r;
        }
    }

    pub fn mul_assign(out: &mut [f32], rhs: &[f32]) {
        for (o, &r) in out.iter_mut().zip(rhs) {
            *o *= r;
        }
    }

    pub fn add_scalar(out: &mut [f32], c: f32) {
        for o in out.iter_mut() {
            *o += c;
        }
    }

    pub fn mul_scalar(out: &mut [f32], c: f32) {
        for o in out.iter_mut() {
            *o *= c;
        }
    }
}

/// `out[i] += rhs[i]` (`DensityBuffer.addTo`).
#[inline]
pub fn add_assign(out: &mut [f32], rhs: &[f32]) {
    imp::add_assign(out, rhs)
}

/// `out[i] += -rhs[i]`, which IEEE 754 defines as `out[i] - rhs[i]`.
#[inline]
pub fn sub_assign(out: &mut [f32], rhs: &[f32]) {
    imp::sub_assign(out, rhs)
}

/// `out[i] *= rhs[i]`.
#[inline]
pub fn mul_assign(out: &mut [f32], rhs: &[f32]) {
    imp::mul_assign(out, rhs)
}

/// `out[i] += c`.
#[inline]
pub fn add_scalar(out: &mut [f32], c: f32) {
    imp::add_scalar(out, c)
}

/// `out[i] *= c`.
#[inline]
pub fn mul_scalar(out: &mut [f32], c: f32) {
    imp::mul_scalar(out, c)
}

/// Whether this build runs the four-lane paths.
pub const SIMD128: bool = cfg!(all(target_arch = "wasm32", target_feature = "simd128"));

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lanes_match_scalar() {
        let a: Vec<f32> = (0..11).map(|i| i as f32 * 0.37 - 1.5).collect();
        let b: Vec<f32> = (0..11).map(|i| 3.0 - i as f32 * 0.21).collect();
        let mut out = a.clone();
        add_assign(&mut out, &b);
        for i in 0..11 {
            assert_eq!(out[i].to_bits(), (a[i] + b[i]).to_bits());
        }
        let mut out = a.clone();
        sub_assign(&mut out, &b);
        mul_scalar(&mut out, 0.5);
        for i in 0..11 {
            assert_eq!(out[i].to_bits(), ((a[i] - b[i]) * 0.5).to_bits());
        }
    }
}
