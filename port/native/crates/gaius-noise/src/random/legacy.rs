//! `LegacyRandomSource` (the `java.util.Random` LCG) with `BitRandomSource`'s
//! default methods.

const MULTIPLIER: i64 = 0x5DEECE66D;
const INCREMENT: i64 = 0xB;
const MODULUS_MASK: i64 = (1 << 48) - 1;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LegacyRandom {
    seed: i64,
}

impl LegacyRandom {
    pub fn new(seed: i64) -> Self {
        LegacyRandom {
            seed: (seed ^ MULTIPLIER) & MODULUS_MASK,
        }
    }

    pub fn set_seed(&mut self, seed: i64) {
        *self = Self::new(seed);
    }

    #[inline]
    pub fn next(&mut self, bits: u32) -> i32 {
        self.seed = self.seed.wrapping_mul(MULTIPLIER).wrapping_add(INCREMENT) & MODULUS_MASK;
        (self.seed >> (48 - bits)) as i32
    }

    #[inline]
    pub fn next_int(&mut self) -> i32 {
        self.next(32)
    }

    pub fn next_int_bound(&mut self, bound: i32) -> i32 {
        assert!(bound > 0, "Bound must be positive");
        if bound & (bound - 1) == 0 {
            return ((bound as i64).wrapping_mul(self.next(31) as i64) >> 31) as i32;
        }
        loop {
            let bits = self.next(31);
            let value = bits % bound;
            if bits.wrapping_sub(value).wrapping_add(bound - 1) >= 0 {
                return value;
            }
        }
    }

    pub fn next_long(&mut self) -> i64 {
        let high = self.next(32);
        let low = self.next(32);
        ((high as i64) << 32).wrapping_add(low as i64)
    }

    pub fn next_boolean(&mut self) -> bool {
        self.next(1) != 0
    }

    pub fn next_float(&mut self) -> f32 {
        self.next(24) as f32 * 5.9604645E-8f32
    }

    #[inline]
    pub fn next_double(&mut self) -> f64 {
        let high = self.next(26);
        let low = self.next(27);
        (((high as i64) << 27) + low as i64) as f64 * 1.1102230246251565E-16
    }

    /// `RandomSource.consumeCount`: one `nextInt()` per step.
    pub fn consume_count(&mut self, count: i32) {
        for _ in 0..count {
            self.next_int();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_java_util_random() {
        // new java.util.Random(42): nextInt() = -1170105035,
        // nextInt(10) = 3 (second draw), nextLong() follows.
        let mut random = LegacyRandom::new(42);
        assert_eq!(random.next_int(), -1170105035);
        let mut random = LegacyRandom::new(42);
        assert_eq!(random.next_int_bound(10), 0);
        assert_eq!(random.next_int_bound(10), 3);
        let mut random = LegacyRandom::new(42);
        assert_eq!(random.next_double(), 0.7275636800328681);
    }
}
