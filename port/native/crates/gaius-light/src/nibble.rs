//! `DataLayer` nibble rows.
//!
//! A `DataLayer` stores cell `(y << 8) | (z << 4) | x` in byte `index >> 1`,
//! the even index in the low nibble. One x row of 16 levels is therefore 8
//! consecutive bytes, and the column keeps each x row contiguous too, so a row
//! packs or unpacks with a handful of SIMD instructions (scalar fallback for
//! the baseline build).

pub const LAYER_BYTES: usize = 2048;

/// Packs 16 levels (each 0..=15) into 8 nibble bytes.
#[inline(always)]
pub fn pack_row(levels: &[u8], out: &mut [u8]) {
    let levels = &levels[..16];
    let out = &mut out[..8];
    #[cfg(all(target_arch = "wasm32", target_feature = "simd128"))]
    {
        use core::arch::wasm32::*;
        // SAFETY: both slices were bounds-checked above; wasm loads and stores
        // need no alignment.
        unsafe {
            let v = v128_load(levels.as_ptr() as *const v128);
            // Each u16 lane holds (odd << 8) | even; fold it to even | odd << 4.
            let lo = v128_and(v, u16x8_splat(0x000f));
            let hi = v128_and(u16x8_shr(v, 4), u16x8_splat(0x00f0));
            let packed = u8x16_narrow_i16x8(v128_or(lo, hi), v128_or(lo, hi));
            v128_store64_lane::<0>(packed, out.as_mut_ptr() as *mut u64);
        }
    }
    #[cfg(not(all(target_arch = "wasm32", target_feature = "simd128")))]
    {
        for (byte, pair) in out.iter_mut().zip(levels.as_chunks::<2>().0) {
            *byte = (pair[0] & 15) | (pair[1] << 4);
        }
    }
}

/// Unpacks 8 nibble bytes into 16 levels.
#[inline(always)]
pub fn unpack_row(nibbles: &[u8], out: &mut [u8]) {
    let nibbles = &nibbles[..8];
    let out = &mut out[..16];
    #[cfg(all(target_arch = "wasm32", target_feature = "simd128"))]
    {
        use core::arch::wasm32::*;
        // SAFETY: both slices were bounds-checked above.
        unsafe {
            let bytes = v128_load64_zero(nibbles.as_ptr() as *const u64);
            let wide = u16x8_extend_low_u8x16(bytes);
            let even = v128_and(wide, u16x8_splat(0x000f));
            let odd = u16x8_shl(u16x8_shr(wide, 4), 8);
            v128_store(out.as_mut_ptr() as *mut v128, v128_or(even, odd));
        }
    }
    #[cfg(not(all(target_arch = "wasm32", target_feature = "simd128")))]
    {
        for (pair, &byte) in out.as_chunks_mut::<2>().0.iter_mut().zip(nibbles) {
            pair[0] = byte & 15;
            pair[1] = byte >> 4;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rows_round_trip_in_data_layer_order() {
        let levels: [u8; 16] = core::array::from_fn(|i| (i * 7 % 16) as u8);
        let mut packed = [0u8; 8];
        pack_row(&levels, &mut packed);
        // DataLayer.get: data[i >> 1] >> ((i & 1) << 2) & 15.
        for (i, &level) in levels.iter().enumerate() {
            assert_eq!((packed[i >> 1] >> ((i & 1) << 2)) & 15, level);
        }
        let mut back = [0u8; 16];
        unpack_row(&packed, &mut back);
        assert_eq!(back, levels);
    }
}
