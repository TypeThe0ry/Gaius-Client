//! `Xoroshiro128PlusPlus` and `XoroshiroRandomSource`.

use super::support::{upgrade_seed_to_128bit, Seed128, GOLDEN_RATIO_64, SILVER_RATIO_64};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Xoroshiro128PlusPlus {
    lo: u64,
    hi: u64,
}

impl Xoroshiro128PlusPlus {
    pub fn new(lo: i64, hi: i64) -> Self {
        if lo | hi == 0 {
            Xoroshiro128PlusPlus {
                lo: GOLDEN_RATIO_64 as u64,
                hi: SILVER_RATIO_64 as u64,
            }
        } else {
            Xoroshiro128PlusPlus {
                lo: lo as u64,
                hi: hi as u64,
            }
        }
    }

    #[inline]
    pub fn next_long(&mut self) -> i64 {
        let lo = self.lo;
        let mut hi = self.hi;
        let result = lo.wrapping_add(hi).rotate_left(17).wrapping_add(lo);
        hi ^= lo;
        self.lo = lo.rotate_left(49) ^ hi ^ (hi << 21);
        self.hi = hi.rotate_left(28);
        result as i64
    }
}

/// `XoroshiroRandomSource` without the Gaussian source (see `random` docs).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct XoroshiroRandom {
    rng: Xoroshiro128PlusPlus,
}

impl XoroshiroRandom {
    /// `new XoroshiroRandomSource(long)`: the seed is upgraded and mixed.
    pub fn new(seed: i64) -> Self {
        Self::from_seed128(upgrade_seed_to_128bit(seed))
    }

    /// `new XoroshiroRandomSource(Seed128bit)`: used as is.
    pub fn from_seed128(seed: Seed128) -> Self {
        Self::from_parts(seed.lo, seed.hi)
    }

    /// `new XoroshiroRandomSource(long, long)`: used as is.
    pub fn from_parts(lo: i64, hi: i64) -> Self {
        XoroshiroRandom {
            rng: Xoroshiro128PlusPlus::new(lo, hi),
        }
    }

    pub fn set_seed(&mut self, seed: i64) {
        *self = Self::new(seed);
    }

    #[inline]
    pub fn next_long(&mut self) -> i64 {
        self.rng.next_long()
    }

    #[inline]
    pub fn next_int(&mut self) -> i32 {
        self.next_long() as i32
    }

    /// Lemire's nearly-divisionless bounded draw, as in vanilla.
    pub fn next_int_bound(&mut self, bound: i32) -> i32 {
        assert!(bound > 0, "Bound must be positive");
        let bound64 = bound as i64;
        let mut product = (self.next_int() as u32 as i64).wrapping_mul(bound64);
        let mut low = product & 0xFFFF_FFFF;
        if low < bound64 {
            let threshold = (bound.wrapping_neg() as u32 % bound as u32) as i32 as i64;
            while low < threshold {
                product = (self.next_int() as u32 as i64).wrapping_mul(bound64);
                low = product & 0xFFFF_FFFF;
            }
        }
        (product >> 32) as i32
    }

    #[inline]
    fn next_bits(&mut self, bits: u32) -> i64 {
        ((self.next_long() as u64) >> (64 - bits)) as i64
    }

    pub fn next_boolean(&mut self) -> bool {
        self.next_long() & 1 != 0
    }

    pub fn next_float(&mut self) -> f32 {
        self.next_bits(24) as f32 * 5.9604645E-8f32
    }

    #[inline]
    pub fn next_double(&mut self) -> f64 {
        self.next_bits(53) as f64 * 1.1102230246251565E-16
    }

    pub fn consume_count(&mut self, count: i32) {
        for _ in 0..count {
            self.rng.next_long();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_state_is_replaced() {
        let mut a = Xoroshiro128PlusPlus::new(0, 0);
        let mut b = Xoroshiro128PlusPlus::new(GOLDEN_RATIO_64, SILVER_RATIO_64);
        assert_eq!(a.next_long(), b.next_long());
    }

    #[test]
    fn reference_xoroshiro128pp_vector() {
        // Reference xoroshiro128++ with s0 = 1, s1 = 2: first output is
        // rotl(1 + 2, 17) + 1 = 393217.
        let mut rng = Xoroshiro128PlusPlus::new(1, 2);
        assert_eq!(rng.next_long(), 393217);
    }

    #[test]
    fn bounded_draw_stays_in_range() {
        let mut random = XoroshiroRandom::new(12345);
        for bound in [1, 2, 3, 7, 256, i32::MAX] {
            for _ in 0..64 {
                let value = random.next_int_bound(bound);
                assert!((0..bound).contains(&value));
            }
        }
    }
}
