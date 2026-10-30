//! `SingleThreadedRandomSource`, the random source `ModelBlockRenderer`
//! reseeds with `BlockState.getSeed(pos)` before it collects model parts.

const MULTIPLIER: i64 = 0x5_DEEC_E66D;
const INCREMENT: i64 = 0xB;
const MASK: i64 = (1 << 48) - 1;

#[derive(Clone, Copy, Debug, Default)]
pub struct Rng {
    seed: i64,
}

impl Rng {
    #[inline]
    pub fn set_seed(&mut self, seed: i64) {
        self.seed = (seed ^ MULTIPLIER) & MASK;
    }

    #[inline]
    fn next(&mut self, bits: u32) -> i32 {
        let next = self.seed.wrapping_mul(MULTIPLIER).wrapping_add(INCREMENT) & MASK;
        self.seed = next;
        (next >> (48 - bits)) as i32
    }

    /// `BitRandomSource.nextInt(int)`; `bound` must be positive.
    #[inline]
    pub fn next_int(&mut self, bound: i32) -> i32 {
        if bound & bound.wrapping_sub(1) == 0 {
            return ((bound as i64).wrapping_mul(self.next(31) as i64) >> 31) as i32;
        }
        loop {
            let i = self.next(31);
            let j = i % bound;
            if i.wrapping_sub(j).wrapping_add(bound - 1) >= 0 {
                return j;
            }
        }
    }

    /// `BitRandomSource.nextLong()`.
    #[inline]
    pub fn next_long(&mut self) -> i64 {
        let hi = self.next(32) as i64;
        let lo = self.next(32) as i64;
        (hi << 32).wrapping_add(lo)
    }
}

#[cfg(test)]
mod tests {
    use super::Rng;

    #[test]
    fn matches_java_util_random() {
        // new java.util.Random(42): nextInt(10) = 0, nextInt(10) = 3, nextLong() = -5025562857975149833.
        let mut r = Rng::default();
        r.set_seed(42);
        assert_eq!(r.next_int(10), 0);
        assert_eq!(r.next_int(10), 3);
        let mut r = Rng::default();
        r.set_seed(42);
        assert_eq!(r.next_long(), -5_025_562_857_975_149_833);
    }
}
