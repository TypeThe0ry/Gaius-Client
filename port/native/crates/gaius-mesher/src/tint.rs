//! Biome tint as `ClientLevel.calculateBlockTint` computes it:
//! `BiomeManager.getBiome(pos)` (the fuzzy zoom from quarts to blocks) followed
//! by the biome's color resolver, and with a `biomeBlendRadius` r > 0 the
//! per-channel integer average of that color over the (2r + 1)^2 columns
//! around the block at the same height.

use crate::job::{
    flags as job_flags, BiomeColors, SectionJob, GRASS_MODIFIER_DARK_FOREST, GRASS_MODIFIER_SWAMP, QUARTS,
};
use crate::mth::{lcg_next, opaque};
use crate::table::tint;

/// Per-job memo of block -> palette index, for x, z in -2..18 (the section plus
/// the columns a blend radius of 2 reaches) and y in -1..16 (the layer below the
/// section serves `GRASS_BELOW`).
pub struct BiomeCache {
    slots: Vec<u8>,
}

const UNSET: u8 = 0xFF;
const CACHE_MARGIN: i32 = 2;
const CACHE_SPAN: i32 = 16 + 2 * CACHE_MARGIN;
const CACHE_HEIGHT: i32 = 17;

impl Default for BiomeCache {
    fn default() -> Self {
        BiomeCache {
            slots: vec![UNSET; (CACHE_SPAN * CACHE_SPAN * CACHE_HEIGHT) as usize],
        }
    }
}

impl BiomeCache {
    pub fn reset(&mut self) {
        self.slots.fill(UNSET);
    }
}

#[inline]
fn fiddle(l: i64) -> f64 {
    let d = (l >> 24).rem_euclid(1024) as f64 / 1024.0;
    (d - 0.5) * 0.9
}

/// `BiomeManager.getFiddledDistance`.
#[inline]
fn fiddled_distance(seed: i64, x: i32, y: i32, z: i32, xf: f64, yf: f64, zf: f64) -> f64 {
    let mut m = lcg_next(seed, x as i64);
    m = lcg_next(m, y as i64);
    m = lcg_next(m, z as i64);
    m = lcg_next(m, x as i64);
    m = lcg_next(m, y as i64);
    m = lcg_next(m, z as i64);
    let d = fiddle(m);
    m = lcg_next(m, seed);
    let e = fiddle(m);
    m = lcg_next(m, seed);
    let f = fiddle(m);
    let sq = |v: f64| v * v;
    sq(zf + f) + sq(yf + e) + sq(xf + d)
}

/// `BiomeManager.getBiome(x, y, z)` reduced to the quart it picks.
pub fn zoom(seed: i64, x: i32, y: i32, z: i32) -> (i32, i32, i32) {
    let i = x - 2;
    let j = y - 2;
    let k = z - 2;
    let l = i >> 2;
    let m = j >> 2;
    let n = k >> 2;
    let d = (i & 3) as f64 / 4.0;
    let e = (j & 3) as f64 / 4.0;
    let f = (k & 3) as f64 / 4.0;
    let mut best = 0;
    let mut best_distance = f64::INFINITY;
    for p in 0..8 {
        let bx = p & 4 == 0;
        let by = p & 2 == 0;
        let bz = p & 1 == 0;
        let q = if bx { l } else { l + 1 };
        let r = if by { m } else { m + 1 };
        let s = if bz { n } else { n + 1 };
        let h = if bx { d } else { d - 1.0 };
        let t = if by { e } else { e - 1.0 };
        let u = if bz { f } else { f - 1.0 };
        let v = fiddled_distance(seed, q, r, s, h, t, u);
        if best_distance > v {
            best = p;
            best_distance = v;
        }
    }
    (
        if best & 4 == 0 { l } else { l + 1 },
        if best & 2 == 0 { m } else { m + 1 },
        if best & 1 == 0 { n } else { n + 1 },
    )
}

/// The color resolvers of one job.
pub struct Tinter<'j, 'a> {
    pub job: &'j SectionJob<'a>,
    pub origin: [i32; 3],
}

impl Tinter<'_, '_> {
    /// Palette index of the biome at a world position.
    fn biome(&self, cache: &mut BiomeCache, x: i32, y: i32, z: i32) -> usize {
        let lx = x - self.origin[0];
        let ly = y - self.origin[1];
        let lz = z - self.origin[2];
        let span = -CACHE_MARGIN..16 + CACHE_MARGIN;
        let slot = if span.contains(&lx) && (-1..16).contains(&ly) && span.contains(&lz) {
            Some((((ly + 1) * CACHE_SPAN + lz + CACHE_MARGIN) * CACHE_SPAN + lx + CACHE_MARGIN) as usize)
        } else {
            None
        };
        if let Some(s) = slot {
            let v = cache.slots[s];
            if v != UNSET {
                return v as usize;
            }
        }
        let (qx, qy, qz) = zoom(self.job.biome_zoom_seed, x, y, z);
        let base = |o: i32| (o >> 2) - 1;
        let gx = (qx - base(self.origin[0])).clamp(0, QUARTS as i32 - 1);
        let gy = (qy - base(self.origin[1])).clamp(0, QUARTS as i32 - 1);
        let gz = (qz - base(self.origin[2])).clamp(0, QUARTS as i32 - 1);
        let index = self.job.biome_quarts[((gy * QUARTS as i32 + gz) * QUARTS as i32 + gx) as usize];
        if let Some(s) = slot {
            cache.slots[s] = index;
        }
        index as usize
    }

    /// `Biome.getGrassColor(x, z)` with the swamp noise precomputed per column.
    fn grass(&self, colors: &BiomeColors, x: i32, z: i32) -> i32 {
        match colors.grass_modifier {
            GRASS_MODIFIER_DARK_FOREST => opaque(((colors.grass & 16_711_422) + 2_634_762) >> 1),
            GRASS_MODIFIER_SWAMP => {
                let bit = if self.job.has(job_flags::BIOME_BLEND) {
                    // 20 x 20 columns; a blend radius of at most 2 stays inside them.
                    let lx = (x - self.origin[0]).clamp(-2, 17) + 2;
                    let lz = (z - self.origin[2]).clamp(-2, 17) + 2;
                    (lz * 20 + lx) as usize
                } else {
                    (((z & 15) << 4) | (x & 15)) as usize
                };
                if self.job.swamp_mask[bit >> 3] & (1 << (bit & 7)) != 0 {
                    -11_766_212
                } else {
                    -9_801_671
                }
            }
            _ => colors.grass,
        }
    }

    /// The color resolver of a (non-constant) tint kind for the biome at one position.
    fn sample(&self, cache: &mut BiomeCache, kind: u8, x: i32, y: i32, z: i32) -> i32 {
        let b = self.biome(cache, x, y, z);
        let colors = &self.job.palette[b];
        match kind {
            tint::GRASS | tint::GRASS_BELOW => self.grass(colors, x, z),
            tint::FOLIAGE => colors.foliage,
            tint::DRY_FOLIAGE => colors.dry_foliage,
            _ => colors.water,
        }
    }

    /// `BlockTintSource.colorInWorld` for a tint kind at a world position.
    pub fn color(&self, cache: &mut BiomeCache, kind: u8, constant: i32, x: i32, y: i32, z: i32) -> i32 {
        if kind == tint::CONSTANT {
            return constant;
        }
        // GRASS_BELOW is the grass tint of pos.below().
        let y = if kind == tint::GRASS_BELOW { y - 1 } else { y };
        let r = self.job.blend_radius;
        if r <= 0 {
            return self.sample(cache, kind, x, y, z);
        }
        let (mut red, mut green, mut blue) = (0i32, 0i32, 0i32);
        for dz in -r..=r {
            for dx in -r..=r {
                let c = self.sample(cache, kind, x + dx, y, z + dz);
                red += (c >> 16) & 255;
                green += (c >> 8) & 255;
                blue += c & 255;
            }
        }
        let n = (2 * r + 1) * (2 * r + 1);
        // ARGB.color(r / n, g / n, b / n)
        opaque(((red / n) << 16) | ((green / n) << 8) | (blue / n))
    }
}

#[cfg(test)]
mod tests {
    use super::{zoom, BiomeCache, Tinter};
    use crate::job::{BiomeColors, SectionJob, SectionJobData, GRASS_MODIFIER_DARK_FOREST, GRASS_MODIFIER_SWAMP};
    use crate::table::tint;

    const KINDS: [u8; 5] = [
        tint::GRASS,
        tint::GRASS_BELOW,
        tint::FOLIAGE,
        tint::DRY_FOLIAGE,
        tint::WATER,
    ];

    /// Swamp noise stand-in per section-relative column.
    fn swamp_column(x: i32, z: i32) -> bool {
        (x * 31 + z * 17).rem_euclid(5) < 2
    }

    /// Three biomes (plain, swamp, dark forest) spread over the quart grid.
    fn job(blend: Option<u32>) -> SectionJobData {
        let mut data = SectionJobData::empty(7);
        data.section = [-3, 4, 11];
        data.biome_zoom_seed = 0x0123_4567_89AB_CDEF;
        data.palette.push(BiomeColors {
            grass: -9_801_671,
            grass_modifier: GRASS_MODIFIER_SWAMP,
            foliage: -9_801_671,
            dry_foliage: -10_732_494,
            water: -10_195_342,
        });
        data.palette.push(BiomeColors {
            grass: -11_042_284,
            grass_modifier: GRASS_MODIFIER_DARK_FOREST,
            foliage: -14_843_365,
            dry_foliage: -8_301_777,
            water: -12_618_012,
        });
        for (k, q) in data.biome_quarts.iter_mut().enumerate() {
            *q = ((k * 7 + k / 6) % 3) as u8;
        }
        for z in 0..16 {
            for x in 0..16 {
                if swamp_column(x, z) {
                    let bit = (z * 16 + x) as usize;
                    data.swamp_mask[bit >> 3] |= 1 << (bit & 7);
                }
            }
        }
        if let Some(radius) = blend {
            data.blend_radius = Some(radius);
            data.swamp_mask_blend = vec![0; crate::job::SWAMP_BLEND_LEN];
            for z in -2..18 {
                for x in -2..18 {
                    if swamp_column(x, z) {
                        let bit = ((z + 2) * 20 + (x + 2)) as usize;
                        data.swamp_mask_blend[bit >> 3] |= 1 << (bit & 7);
                    }
                }
            }
        }
        data
    }

    fn origin(job: &SectionJob) -> [i32; 3] {
        [job.section[0] << 4, job.section[1] << 4, job.section[2] << 4]
    }

    #[test]
    fn zoom_stays_next_to_the_block_quart() {
        for &(x, y, z) in &[(0, 64, 0), (-17, -60, 33), (1000, 300, -999)] {
            let (qx, qy, qz) = zoom(123_456_789, x, y, z);
            assert!((qx - ((x - 2) >> 2)).abs() <= 1);
            assert!((qy - ((y - 2) >> 2)).abs() <= 1);
            assert!((qz - ((z - 2) >> 2)).abs() <= 1);
        }
    }

    #[test]
    fn blend_radius_zero_matches_the_legacy_layout() {
        let legacy_bytes = job(None).encode();
        let blend_bytes = job(Some(0)).encode();
        let legacy = SectionJob::decode(&legacy_bytes).unwrap();
        let blend = SectionJob::decode(&blend_bytes).unwrap();
        let a = Tinter {
            job: &legacy,
            origin: origin(&legacy),
        };
        let b = Tinter {
            job: &blend,
            origin: origin(&blend),
        };
        let (mut ca, mut cb) = (BiomeCache::default(), BiomeCache::default());
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    let (wx, wy, wz) = (a.origin[0] + x, a.origin[1] + y, a.origin[2] + z);
                    for &kind in &KINDS {
                        assert_eq!(
                            a.color(&mut ca, kind, 0, wx, wy, wz),
                            b.color(&mut cb, kind, 0, wx, wy, wz)
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn blend_averages_the_columns_around_the_block() {
        let plain_bytes = job(Some(0)).encode();
        let plain = SectionJob::decode(&plain_bytes).unwrap();
        let p = Tinter {
            job: &plain,
            origin: origin(&plain),
        };
        let mut pc = BiomeCache::default();
        for radius in 1..=2i32 {
            let bytes = job(Some(radius as u32)).encode();
            let blended = SectionJob::decode(&bytes).unwrap();
            let b = Tinter {
                job: &blended,
                origin: origin(&blended),
            };
            let mut bc = BiomeCache::default();
            let mut differs = false;
            for y in 0..16 {
                for z in 0..16 {
                    for x in 0..16 {
                        let (wx, wy, wz) = (p.origin[0] + x, p.origin[1] + y, p.origin[2] + z);
                        for &kind in &KINDS {
                            let below = if kind == tint::GRASS_BELOW { 1 } else { 0 };
                            let mut sum = [0i32; 3];
                            for dz in -radius..=radius {
                                for dx in -radius..=radius {
                                    // A grass sample is the radius-0 grass of that column.
                                    let probe = if kind == tint::GRASS_BELOW { tint::GRASS } else { kind };
                                    let c = p.color(&mut pc, probe, 0, wx + dx, wy - below, wz + dz);
                                    sum[0] += (c >> 16) & 255;
                                    sum[1] += (c >> 8) & 255;
                                    sum[2] += c & 255;
                                }
                            }
                            let n = (2 * radius + 1) * (2 * radius + 1);
                            let expected = (255 << 24) | ((sum[0] / n) << 16) | ((sum[1] / n) << 8) | (sum[2] / n);
                            let got = b.color(&mut bc, kind, 0, wx, wy, wz);
                            assert_eq!(got, expected, "radius {radius} kind {kind} at {x} {y} {z}");
                            differs |= got != p.color(&mut pc, kind, 0, wx, wy, wz);
                        }
                    }
                }
            }
            assert!(differs, "radius {radius} never changed a tint");
        }
    }

    #[test]
    fn constant_tints_ignore_the_blend() {
        let bytes = job(Some(2)).encode();
        let blended = SectionJob::decode(&bytes).unwrap();
        let b = Tinter {
            job: &blended,
            origin: origin(&blended),
        };
        assert_eq!(
            b.color(&mut BiomeCache::default(), tint::CONSTANT, 0x1234_5678, 0, 64, 0),
            0x1234_5678
        );
    }

    #[test]
    fn a_blend_radius_beyond_the_margin_is_rejected() {
        let mut bytes = job(Some(2)).encode();
        bytes[80..84].copy_from_slice(&3u32.to_le_bytes());
        assert!(SectionJob::decode(&bytes).is_err());
    }
}
