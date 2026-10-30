//! `RandomSupport` and `RandomSupport.Seed128bit`.

use md5::{Digest, Md5};

pub const GOLDEN_RATIO_64: i64 = -7046029254386353131;
pub const SILVER_RATIO_64: i64 = 7640891576956012809;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Seed128 {
    pub lo: i64,
    pub hi: i64,
}

impl Seed128 {
    pub fn xor(self, lo: i64, hi: i64) -> Seed128 {
        Seed128 {
            lo: self.lo ^ lo,
            hi: self.hi ^ hi,
        }
    }

    pub fn mixed(self) -> Seed128 {
        Seed128 {
            lo: mix_stafford13(self.lo),
            hi: mix_stafford13(self.hi),
        }
    }
}

pub fn mix_stafford13(seed: i64) -> i64 {
    let mut z = seed as u64;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
    (z ^ (z >> 31)) as i64
}

pub fn upgrade_seed_to_128bit_unmixed(seed: i64) -> Seed128 {
    let lo = seed ^ SILVER_RATIO_64;
    Seed128 {
        lo,
        hi: lo.wrapping_add(GOLDEN_RATIO_64),
    }
}

pub fn upgrade_seed_to_128bit(seed: i64) -> Seed128 {
    upgrade_seed_to_128bit_unmixed(seed).mixed()
}

/// MD5 of the UTF-8 name, read as two big-endian longs (Guava `Longs.fromBytes`).
pub fn seed_from_hash_of(name: &str) -> Seed128 {
    let digest = Md5::digest(name.as_bytes());
    let mut lo = [0u8; 8];
    let mut hi = [0u8; 8];
    lo.copy_from_slice(&digest[0..8]);
    hi.copy_from_slice(&digest[8..16]);
    Seed128 {
        lo: i64::from_be_bytes(lo),
        hi: i64::from_be_bytes(hi),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stafford_constants_match_java_longs() {
        assert_eq!(0xBF58476D1CE4E5B9u64 as i64, -4658895280553007687);
        assert_eq!(0x94D049BB133111EBu64 as i64, -7723592293110705685);
        assert_eq!(mix_stafford13(0), 0);
    }

    #[test]
    fn md5_seed_of_empty_string() {
        // md5("") = d41d8cd98f00b204 e9800998ecf8427e
        let seed = seed_from_hash_of("");
        assert_eq!(seed.lo as u64, 0xd41d8cd98f00b204);
        assert_eq!(seed.hi as u64, 0xe9800998ecf8427e);
    }
}
