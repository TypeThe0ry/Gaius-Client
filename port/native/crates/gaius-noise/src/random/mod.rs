//! `RandomSource` implementations used by world generation.
//!
//! `nextGaussian` is not ported: it goes through `Math.log`, whose result the
//! JVM only bounds to one ulp, so it cannot be reproduced bit-exactly here.
//! None of the noise constructors call it.

pub mod legacy;
pub mod support;
pub mod xoroshiro;

use crate::java::string_hash;
use crate::mth::get_seed;
pub use legacy::LegacyRandom;
pub use support::{seed_from_hash_of, Seed128};
pub use xoroshiro::XoroshiroRandom;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RandomKind {
    Xoroshiro,
    Legacy,
}

/// A vanilla `RandomSource`; enum dispatch keeps the hot paths inlinable.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RandomSource {
    Xoroshiro(XoroshiroRandom),
    Legacy(LegacyRandom),
}

macro_rules! dispatch {
    ($self:ident, $r:ident => $body:expr) => {
        match $self {
            RandomSource::Xoroshiro($r) => $body,
            RandomSource::Legacy($r) => $body,
        }
    };
}

impl RandomSource {
    /// `new XoroshiroRandomSource(seed)` or `new LegacyRandomSource(seed)`.
    pub fn new(kind: RandomKind, seed: i64) -> Self {
        match kind {
            RandomKind::Xoroshiro => RandomSource::Xoroshiro(XoroshiroRandom::new(seed)),
            RandomKind::Legacy => RandomSource::Legacy(LegacyRandom::new(seed)),
        }
    }

    pub fn kind(&self) -> RandomKind {
        match self {
            RandomSource::Xoroshiro(_) => RandomKind::Xoroshiro,
            RandomSource::Legacy(_) => RandomKind::Legacy,
        }
    }

    pub fn set_seed(&mut self, seed: i64) {
        dispatch!(self, r => r.set_seed(seed))
    }

    #[inline]
    pub fn next_int(&mut self) -> i32 {
        dispatch!(self, r => r.next_int())
    }

    #[inline]
    pub fn next_int_bound(&mut self, bound: i32) -> i32 {
        dispatch!(self, r => r.next_int_bound(bound))
    }

    /// `RandomSource.nextIntBetweenInclusive(min, max)`.
    pub fn next_int_between_inclusive(&mut self, min: i32, max: i32) -> i32 {
        self.next_int_bound(max.wrapping_sub(min).wrapping_add(1))
            .wrapping_add(min)
    }

    #[inline]
    pub fn next_long(&mut self) -> i64 {
        dispatch!(self, r => r.next_long())
    }

    pub fn next_boolean(&mut self) -> bool {
        dispatch!(self, r => r.next_boolean())
    }

    pub fn next_float(&mut self) -> f32 {
        dispatch!(self, r => r.next_float())
    }

    #[inline]
    pub fn next_double(&mut self) -> f64 {
        dispatch!(self, r => r.next_double())
    }

    pub fn consume_count(&mut self, count: i32) {
        dispatch!(self, r => r.consume_count(count))
    }

    pub fn fork(&mut self) -> RandomSource {
        match self {
            RandomSource::Xoroshiro(r) => {
                let lo = r.next_long();
                let hi = r.next_long();
                RandomSource::Xoroshiro(XoroshiroRandom::from_parts(lo, hi))
            }
            RandomSource::Legacy(r) => RandomSource::Legacy(LegacyRandom::new(r.next_long())),
        }
    }

    pub fn fork_positional(&mut self) -> PositionalRandomFactory {
        match self {
            RandomSource::Xoroshiro(r) => {
                let lo = r.next_long();
                let hi = r.next_long();
                PositionalRandomFactory::Xoroshiro {
                    seed_lo: lo,
                    seed_hi: hi,
                }
            }
            RandomSource::Legacy(r) => PositionalRandomFactory::Legacy { seed: r.next_long() },
        }
    }
}

/// `PositionalRandomFactory` of either source.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PositionalRandomFactory {
    Xoroshiro { seed_lo: i64, seed_hi: i64 },
    Legacy { seed: i64 },
}

impl PositionalRandomFactory {
    pub fn at(&self, x: i32, y: i32, z: i32) -> RandomSource {
        let position_seed = get_seed(x, y, z);
        match *self {
            PositionalRandomFactory::Xoroshiro { seed_lo, seed_hi } => {
                RandomSource::Xoroshiro(XoroshiroRandom::from_parts(position_seed ^ seed_lo, seed_hi))
            }
            PositionalRandomFactory::Legacy { seed } => RandomSource::Legacy(LegacyRandom::new(position_seed ^ seed)),
        }
    }

    pub fn from_hash_of(&self, name: &str) -> RandomSource {
        match *self {
            PositionalRandomFactory::Xoroshiro { seed_lo, seed_hi } => RandomSource::Xoroshiro(
                XoroshiroRandom::from_seed128(seed_from_hash_of(name).xor(seed_lo, seed_hi)),
            ),
            PositionalRandomFactory::Legacy { seed } => {
                RandomSource::Legacy(LegacyRandom::new(string_hash(name) as i64 ^ seed))
            }
        }
    }

    pub fn from_seed(&self, seed: i64) -> RandomSource {
        match *self {
            PositionalRandomFactory::Xoroshiro { seed_lo, seed_hi } => {
                RandomSource::Xoroshiro(XoroshiroRandom::from_parts(seed ^ seed_lo, seed ^ seed_hi))
            }
            PositionalRandomFactory::Legacy { .. } => RandomSource::Legacy(LegacyRandom::new(seed)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Values printed by the 26.3 client classes on the JVM for the same call sequence.
    #[test]
    fn draws_and_forks_match_jvm() {
        let expected = [
            (
                RandomKind::Xoroshiro,
                1061082275,
                31,
                1842156893,
                -1441753141,
                -2,
                -8482295454657025052,
                -5593131857664615225,
                -5778934031573904113,
            ),
            (
                RandomKind::Legacy,
                1060782493,
                63,
                1467211248,
                1325939940,
                3,
                7216114216446205705,
                -5119754439980850796,
                -6884842495663697104,
            ),
        ];
        for (kind, float_bits, int100, int_max, int, between, at, from_seed, fork) in expected {
            let mut r = RandomSource::new(kind, 42);
            assert_eq!(r.next_float().to_bits() as i32, float_bits, "{kind:?}");
            assert_eq!(r.next_int_bound(100), int100, "{kind:?}");
            assert_eq!(r.next_int_bound(i32::MAX), int_max, "{kind:?}");
            assert!(!r.next_boolean(), "{kind:?}");
            assert_eq!(r.next_int(), int, "{kind:?}");
            assert_eq!(r.next_int_between_inclusive(-5, 5), between, "{kind:?}");
            let factory = r.fork_positional();
            assert_eq!(factory.at(1, -2, 3).next_long(), at, "{kind:?}");
            assert_eq!(factory.from_seed(99).next_long(), from_seed, "{kind:?}");
            assert_eq!(r.fork().next_long(), fork, "{kind:?}");
        }
    }
}
