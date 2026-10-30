//! The few inner loops that profit from wasm `simd128`, each with a scalar
//! twin. Both paths evaluate exactly the same IEEE operations in the same
//! order per lane (no fused multiply-add, no reassociation), so the optimized
//! and the baseline module produce identical bytes.
//!
//! The simd path is compiled when the crate is built with
//! `-C target-feature=+simd128` (see `port/native/build-wasm.sh`); the page
//! picks the module by feature detection.

#[cfg(all(target_arch = "wasm32", target_feature = "simd128"))]
use core::arch::wasm32::*;

/// For each of the 256 rows (y, z) of the section, the bits of the x positions whose state
/// is not one of `air` (at most 4 ids; callers fall back to flags beyond that).
/// `ids` is the 20^3 region; rows start at `row_start(y, z)`.
pub fn non_air_rows(ids: &[u32], air: &[u32], out: &mut [u16; 256]) {
    debug_assert!(!air.is_empty() && air.len() <= 4);
    let a = |k: usize| air[k.min(air.len() - 1)];
    let (a0, a1, a2, a3) = (a(0), a(1), a(2), a(3));
    for y in 0..16 {
        for z in 0..16 {
            let start = crate::job::region_index(0, y, z);
            let row = &ids[start..start + 16];
            out[(y * 16 + z) as usize] = row_mask(row, a0, a1, a2, a3);
        }
    }
}

#[cfg(all(target_arch = "wasm32", target_feature = "simd128"))]
#[inline]
fn row_mask(row: &[u32], a0: u32, a1: u32, a2: u32, a3: u32) -> u16 {
    let (s0, s1, s2, s3) = (u32x4_splat(a0), u32x4_splat(a1), u32x4_splat(a2), u32x4_splat(a3));
    let mut mask = 0u16;
    for k in 0..4 {
        let c = &row[k * 4..k * 4 + 4];
        let v = u32x4(c[0], c[1], c[2], c[3]);
        let is_air = v128_or(
            v128_or(i32x4_eq(v, s0), i32x4_eq(v, s1)),
            v128_or(i32x4_eq(v, s2), i32x4_eq(v, s3)),
        );
        mask |= ((!i32x4_bitmask(is_air)) as u16 & 0xF) << (k * 4);
    }
    mask
}

#[cfg(not(all(target_arch = "wasm32", target_feature = "simd128")))]
#[inline]
fn row_mask(row: &[u32], a0: u32, a1: u32, a2: u32, a3: u32) -> u16 {
    let mut mask = 0u16;
    for (x, &id) in row.iter().enumerate() {
        if id != a0 && id != a1 && id != a2 && id != a3 {
            mask |= 1 << x;
        }
    }
    mask
}

/// The weighted AO of `BlockModelLighter` for the four output vertices at once:
/// `clamp(ao[0] * w[k][0] + ao[1] * w[k][1] + ao[2] * w[k][2] + ao[3] * w[k][3], 0, 1)`
/// for k = 0..4, summed left to right like the bytecode.
#[inline]
pub fn weighted_ao(ao: [f32; 4], w: &[[f32; 4]; 4]) -> [f32; 4] {
    #[cfg(all(target_arch = "wasm32", target_feature = "simd128"))]
    {
        let col = |j: usize| f32x4(w[0][j], w[1][j], w[2][j], w[3][j]);
        let mut acc = f32x4_mul(f32x4_splat(ao[0]), col(0));
        acc = f32x4_add(acc, f32x4_mul(f32x4_splat(ao[1]), col(1)));
        acc = f32x4_add(acc, f32x4_mul(f32x4_splat(ao[2]), col(2)));
        acc = f32x4_add(acc, f32x4_mul(f32x4_splat(ao[3]), col(3)));
        // Math.clamp(v, 0, 1) = min(1, max(v, 0)); pmax/pmin match it for every non-NaN lane.
        let clamped = f32x4_pmin(f32x4_splat(1.0), f32x4_pmax(f32x4_splat(0.0), acc));
        [
            f32x4_extract_lane::<0>(clamped),
            f32x4_extract_lane::<1>(clamped),
            f32x4_extract_lane::<2>(clamped),
            f32x4_extract_lane::<3>(clamped),
        ]
    }
    #[cfg(not(all(target_arch = "wasm32", target_feature = "simd128")))]
    {
        let mut out = [0f32; 4];
        for k in 0..4 {
            let v = ao[0] * w[k][0] + ao[1] * w[k][1] + ao[2] * w[k][2] + ao[3] * w[k][3];
            out[k] = 1.0f32.min(v.max(0.0));
        }
        out
    }
}

/// `LightCoordsUtil.smoothWeightedBlend(l0, l1, l2, l3, w[k])` for k = 0..4.
#[inline]
pub fn weighted_light(l: [i32; 4], w: &[[f32; 4]; 4]) -> [i32; 4] {
    #[cfg(all(target_arch = "wasm32", target_feature = "simd128"))]
    {
        use crate::mth::{smooth_block, smooth_pack, smooth_sky};
        let col = |j: usize| f32x4(w[0][j], w[1][j], w[2][j], w[3][j]);
        let blend = |c: [i32; 4]| {
            let mut acc = f32x4_mul(f32x4_splat(c[0] as f32), col(0));
            acc = f32x4_add(acc, f32x4_mul(f32x4_splat(c[1] as f32), col(1)));
            acc = f32x4_add(acc, f32x4_mul(f32x4_splat(c[2] as f32), col(2)));
            acc = f32x4_add(acc, f32x4_mul(f32x4_splat(c[3] as f32), col(3)));
            // (int) casts saturate and map NaN to 0, like i32x4.trunc_sat_f32x4_s.
            i32x4_trunc_sat_f32x4(acc)
        };
        let sky = blend([smooth_sky(l[0]), smooth_sky(l[1]), smooth_sky(l[2]), smooth_sky(l[3])]);
        let block = blend([
            smooth_block(l[0]),
            smooth_block(l[1]),
            smooth_block(l[2]),
            smooth_block(l[3]),
        ]);
        [
            smooth_pack(i32x4_extract_lane::<0>(block), i32x4_extract_lane::<0>(sky)),
            smooth_pack(i32x4_extract_lane::<1>(block), i32x4_extract_lane::<1>(sky)),
            smooth_pack(i32x4_extract_lane::<2>(block), i32x4_extract_lane::<2>(sky)),
            smooth_pack(i32x4_extract_lane::<3>(block), i32x4_extract_lane::<3>(sky)),
        ]
    }
    #[cfg(not(all(target_arch = "wasm32", target_feature = "simd128")))]
    {
        [
            crate::mth::smooth_weighted_blend(l, w[0]),
            crate::mth::smooth_weighted_blend(l, w[1]),
            crate::mth::smooth_weighted_blend(l, w[2]),
            crate::mth::smooth_weighted_blend(l, w[3]),
        ]
    }
}

/// Squared distances from `camera` to each centroid, as JOML
/// `Vector3f.distanceSquared` computes them without FMA: `dx*dx + (dy*dy + dz*dz)`.
pub fn distances(camera: [f32; 3], cx: &[f32], cy: &[f32], cz: &[f32], out: &mut Vec<f32>) {
    let n = cx.len();
    out.clear();
    out.resize(n, 0.0);
    let mut i = 0;
    #[cfg(all(target_arch = "wasm32", target_feature = "simd128"))]
    {
        let (px, py, pz) = (f32x4_splat(camera[0]), f32x4_splat(camera[1]), f32x4_splat(camera[2]));
        while i + 4 <= n {
            let load = |s: &[f32]| f32x4(s[i], s[i + 1], s[i + 2], s[i + 3]);
            let dx = f32x4_sub(px, load(cx));
            let dy = f32x4_sub(py, load(cy));
            let dz = f32x4_sub(pz, load(cz));
            let d = f32x4_add(f32x4_mul(dx, dx), f32x4_add(f32x4_mul(dy, dy), f32x4_mul(dz, dz)));
            out[i] = f32x4_extract_lane::<0>(d);
            out[i + 1] = f32x4_extract_lane::<1>(d);
            out[i + 2] = f32x4_extract_lane::<2>(d);
            out[i + 3] = f32x4_extract_lane::<3>(d);
            i += 4;
        }
    }
    while i < n {
        let dx = camera[0] - cx[i];
        let dy = camera[1] - cy[i];
        let dz = camera[2] - cz[i];
        out[i] = dx * dx + (dy * dy + dz * dz);
        i += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn weighted_paths_match_the_scalar_formula() {
        let w = [
            [0.25, 0.5, 0.125, 0.125],
            [1.0, 0.0, 0.0, 0.0],
            [0.1, 0.2, 0.3, 0.4],
            [0.0; 4],
        ];
        let ao = [0.2, 0.9, 1.0, 0.6];
        let out = weighted_ao(ao, &w);
        for k in 0..4 {
            let v = ao[0] * w[k][0] + ao[1] * w[k][1] + ao[2] * w[k][2] + ao[3] * w[k][3];
            assert_eq!(out[k].to_bits(), v.clamp(0.0, 1.0).to_bits());
        }
        let l = [0x00F0_00F0, 0x0050_0020, 0, 0x00A0_0010];
        let lw = weighted_light(l, &w);
        for k in 0..4 {
            assert_eq!(lw[k], crate::mth::smooth_weighted_blend(l, w[k]));
        }
    }
}
