//! Biome helpers: `BiomeManager` zoom, the per-job quart biome grid, and
//! `Biome.getTemperature` (used by the surface rules' temperature condition and
//! the frozen ocean extension).

use crate::ir::BiomeInfo;
use gaius_noise::mth::FloorMode;
use gaius_noise::synth32::{LayerNoise, NoiseStack, SimplexNoise as Simplex32};
use gaius_noise::{synth64, RandomKind, RandomSource};
use std::sync::OnceLock;

/// `LinearCongruentialGenerator.next(rval, c)`.
#[inline]
fn lcg(rval: i64, c: i64) -> i64 {
    rval.wrapping_mul(rval.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407))
        .wrapping_add(c)
}

#[inline]
fn fiddle(rval: i64) -> f64 {
    let uniform = ((rval >> 24).rem_euclid(1024)) as f64 / 1024.0;
    (uniform - 0.5) * 0.9
}

fn fiddled_distance(seed: i64, x: i32, y: i32, z: i32, dx: f64, dy: f64, dz: f64) -> f64 {
    let mut r = lcg(seed, x as i64);
    r = lcg(r, y as i64);
    r = lcg(r, z as i64);
    r = lcg(r, x as i64);
    r = lcg(r, y as i64);
    r = lcg(r, z as i64);
    let fx = fiddle(r);
    r = lcg(r, seed);
    let fy = fiddle(r);
    r = lcg(r, seed);
    let fz = fiddle(r);
    let sq = |v: f64| v * v;
    sq(dz + fz) + sq(dy + fy) + sq(dx + fx)
}

/// `BiomeManager.getBiome(x, y, z)`: the quart whose biome a block shows.
pub fn zoom(seed: i64, x: i32, y: i32, z: i32) -> [i32; 3] {
    let (ax, ay, az) = (x - 2, y - 2, z - 2);
    let (px, py, pz) = (ax >> 2, ay >> 2, az >> 2);
    let fx = (ax & 3) as f64 / 4.0;
    let fy = (ay & 3) as f64 / 4.0;
    let fz = (az & 3) as f64 / 4.0;
    let mut min_i = 0;
    let mut min_d = f64::INFINITY;
    for i in 0..8 {
        let x_even = i & 4 == 0;
        let y_even = i & 2 == 0;
        let z_even = i & 1 == 0;
        let cx = if x_even { px } else { px + 1 };
        let cy = if y_even { py } else { py + 1 };
        let cz = if z_even { pz } else { pz + 1 };
        let dx = if x_even { fx } else { fx - 1.0 };
        let dy = if y_even { fy } else { fy - 1.0 };
        let dz = if z_even { fz } else { fz - 1.0 };
        let d = fiddled_distance(seed, cx, cy, cz, dx, dy, dz);
        if min_d > d {
            min_i = i;
            min_d = d;
        }
    }
    [
        if min_i & 4 == 0 { px } else { px + 1 },
        if min_i & 2 == 0 { py } else { py + 1 },
        if min_i & 1 == 0 { pz } else { pz + 1 },
    ]
}

/// Quart biomes of a chunk and its one-quart border (what `BiomeManager.getBiome`
/// can reach from inside the chunk), filled on demand.
pub struct BiomeGrid {
    pub min_qx: i32,
    pub min_qz: i32,
    pub min_qy: i32,
    pub size_y: i32,
    values: Vec<u16>,
}

pub const GRID_XZ: i32 = 6;
/// Grid columns outside the chunk (the 6 x 6 grid minus the chunk's 4 x 4).
pub const RING_COLUMNS: usize = 20;
pub const STORED_COLUMNS: usize = (GRID_XZ * GRID_XZ) as usize;
const UNKNOWN: u16 = u16::MAX;

impl BiomeGrid {
    pub fn new(chunk_x: i32, chunk_z: i32, level_min_y: i32, level_height: i32) -> BiomeGrid {
        let size_y = level_height >> 2;
        BiomeGrid {
            min_qx: (chunk_x << 2) - 1,
            min_qz: (chunk_z << 2) - 1,
            min_qy: level_min_y >> 2,
            size_y,
            values: vec![UNKNOWN; (GRID_XZ * GRID_XZ * size_y) as usize],
        }
    }

    /// Clamps a quart y like `ChunkAccess.getNoiseBiome`.
    #[inline]
    pub fn clamp_y(&self, qy: i32) -> i32 {
        qy.clamp(self.min_qy, self.min_qy + self.size_y - 1)
    }

    #[inline]
    pub fn index(&self, qx: i32, qy: i32, qz: i32) -> Option<usize> {
        let x = qx - self.min_qx;
        let z = qz - self.min_qz;
        let y = self.clamp_y(qy) - self.min_qy;
        if (0..GRID_XZ).contains(&x) && (0..GRID_XZ).contains(&z) {
            Some(((x * GRID_XZ + z) * self.size_y + y) as usize)
        } else {
            None
        }
    }

    #[inline]
    pub fn get_index(&self, i: usize) -> Option<u16> {
        let v = self.values[i];
        (v != UNKNOWN).then_some(v)
    }

    #[inline]
    pub fn set_index(&mut self, i: usize, biome: u16) {
        self.values[i] = biome;
    }

    /// Whether grid column (`x`, `z`) (grid-relative) lies outside the chunk.
    #[inline]
    pub fn is_ring_column(x: i32, z: i32) -> bool {
        !(1..=4).contains(&x) || !(1..=4).contains(&z)
    }

    /// Fills the stored 6 x 6 grid, or the legacy 20-column border: in grid order (x, then
    /// z), every quart y from the bottom. These are the biomes the region's chunks store,
    /// which is what vanilla's `BiomeManager` reads through the `WorldGenRegion`.
    pub fn seed_ring(&mut self, ring: &[u16]) {
        let size_y = self.size_y as usize;
        let stored = ring.len() == STORED_COLUMNS * size_y;
        assert!(stored || ring.len() == RING_COLUMNS * size_y, "ring biome count");
        let mut k = 0;
        for x in 0..GRID_XZ {
            for z in 0..GRID_XZ {
                if !stored && !Self::is_ring_column(x, z) {
                    continue;
                }
                let base = ((x * GRID_XZ + z) * self.size_y) as usize;
                self.values[base..base + size_y].copy_from_slice(&ring[k..k + size_y]);
                k += size_y;
            }
        }
    }
}

/// Seeded noises of `Biome` temperature (constants in vanilla).
struct TemperatureNoises32 {
    temperature: Simplex32,
    frozen: NoiseStack,
    info: Simplex32,
}

fn noises32() -> &'static TemperatureNoises32 {
    static N: OnceLock<TemperatureNoises32> = OnceLock::new();
    N.get_or_init(|| {
        let temperature = Simplex32::with_zero_offsets(&mut RandomSource::new(RandomKind::Legacy, 1234), true);
        let mut r = RandomSource::new(RandomKind::Legacy, 3456);
        let a = Simplex32::with_zero_offsets(&mut r, true);
        let b = Simplex32::with_zero_offsets(&mut r, true);
        let c = Simplex32::with_zero_offsets(&mut r, true);
        let frozen = NoiseStack::builder()
            .add(LayerNoise::Simplex(a), 1.0, 0.14285715)
            .add(LayerNoise::Simplex(b), 0.5, 0.2857143)
            .add(LayerNoise::Simplex(c), 0.25, 0.5714286)
            .build();
        let info = Simplex32::with_zero_offsets(&mut RandomSource::new(RandomKind::Legacy, 2345), true);
        TemperatureNoises32 {
            temperature,
            frozen,
            info,
        }
    })
}

/// Pre-26.3 `PerlinSimplexNoise` for octave sets without positive octaves.
pub struct PerlinSimplex {
    levels: Vec<Option<synth64::SimplexNoise>>,
    input_factor: f64,
    value_factor: f64,
}

impl PerlinSimplex {
    pub fn new(seed: i64, octaves: &[i32], floor: FloorMode) -> PerlinSimplex {
        let mut random = RandomSource::new(RandomKind::Legacy, seed);
        let low = -octaves.iter().min().copied().unwrap_or(0);
        let high = octaves.iter().max().copied().unwrap_or(0);
        assert!(high <= 0, "positive octaves are not used by biome temperature");
        let count = (low + high + 1) as usize;
        let zero = synth64::SimplexNoise::new(&mut random, floor);
        let mut levels: Vec<Option<synth64::SimplexNoise>> = (0..count).map(|_| None).collect();
        if high >= 0 && (high as usize) < count && octaves.contains(&0) {
            levels[high as usize] = Some(zero);
        }
        for (i, level) in levels.iter_mut().enumerate().skip(high as usize + 1) {
            if octaves.contains(&(high - i as i32)) {
                *level = Some(synth64::SimplexNoise::new(&mut random, floor));
            } else {
                random.consume_count(262);
            }
        }
        PerlinSimplex {
            levels,
            input_factor: gaius_noise::java::pow2(high),
            value_factor: 1.0 / (gaius_noise::java::pow2(count as i32) - 1.0),
        }
    }

    /// `getValue(x, y, false)`.
    pub fn get(&self, x: f64, y: f64) -> f64 {
        let mut value = 0.0;
        let mut factor = self.input_factor;
        let mut value_factor = self.value_factor;
        for level in &self.levels {
            if let Some(n) = level {
                value += n.get_value_2d(x * factor, y * factor) * value_factor;
            }
            factor /= 2.0;
            value_factor *= 2.0;
        }
        value
    }
}

struct TemperatureNoises64 {
    temperature: PerlinSimplex,
    frozen: PerlinSimplex,
    info: PerlinSimplex,
}

fn noises64(floor: FloorMode) -> &'static TemperatureNoises64 {
    static CAST: OnceLock<TemperatureNoises64> = OnceLock::new();
    static FLOOR: OnceLock<TemperatureNoises64> = OnceLock::new();
    let cell = if floor == FloorMode::CastAdjust { &CAST } else { &FLOOR };
    cell.get_or_init(|| TemperatureNoises64 {
        temperature: PerlinSimplex::new(1234, &[0], floor),
        frozen: PerlinSimplex::new(3456, &[-2, -1, 0], floor),
        info: PerlinSimplex::new(2345, &[0], floor),
    })
}

/// `Biome.getTemperature(pos, seaLevel)` (`getHeightAdjustedTemperature`; the cache is
/// transparent).
pub fn temperature(biome: &BiomeInfo, synth32: bool, floor: FloorMode, x: i32, y: i32, z: i32, sea_level: i32) -> f32 {
    let mut adjusted = biome.base_temperature;
    if biome.frozen_modifier {
        let frozen = if synth32 {
            let n = noises32();
            let large = (n.frozen.get_2d(x as f64 * 0.05, z as f64 * 0.05) * 7.0f32) as f64;
            let edge = n.info.get_2d(x as f64 * 0.2, z as f64 * 0.2) as f64;
            large + edge < 0.3 && (n.info.get_2d(x as f64 * 0.09, z as f64 * 0.09) as f64) < 0.8
        } else {
            let n = noises64(floor);
            let large = n.frozen.get(x as f64 * 0.05, z as f64 * 0.05) * 7.0;
            let edge = n.info.get(x as f64 * 0.2, z as f64 * 0.2);
            large + edge < 0.3 && n.info.get(x as f64 * 0.09, z as f64 * 0.09) < 0.8
        };
        if frozen {
            adjusted = 0.2;
        }
    }
    let snow_level = sea_level + 17;
    if y > snow_level {
        let (nx, nz) = ((x as f32 / 8.0) as f64, (z as f32 / 8.0) as f64);
        let v = if synth32 {
            noises32().temperature.get_2d(nx, nz) * 8.0
        } else {
            (noises64(floor).temperature.get(nx, nz) * 8.0) as f32
        };
        adjusted - (v + y as f32 - snow_level as f32) * 0.05 / 40.0
    } else {
        adjusted
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zoom_stays_near_the_block() {
        for (x, y, z) in [(0, 64, 0), (-1, -64, 17), (12345, 100, -9876)] {
            let q = zoom(0x5DEE_CE66, x, y, z);
            assert!((q[0] - (x >> 2)).abs() <= 1);
            assert!((q[1] - (y >> 2)).abs() <= 1);
            assert!((q[2] - (z >> 2)).abs() <= 1);
        }
    }

    #[test]
    fn temperature_drops_with_height() {
        let plains = BiomeInfo {
            global_id: 0,
            base_temperature: 0.8,
            frozen_modifier: false,
            flags: 0,
        };
        for synth32 in [false, true] {
            let low = temperature(&plains, synth32, FloorMode::MathFloor, 10, 63, 10, 63);
            let high = temperature(&plains, synth32, FloorMode::MathFloor, 10, 200, 10, 63);
            assert_eq!(low, 0.8);
            assert!(high < low);
        }
    }

    #[test]
    fn ring_seeds_only_the_quarts_outside_the_chunk() {
        let (cx, cz) = (-3, 7);
        let mut grid = BiomeGrid::new(cx, cz, -64, 384);
        let size_y = grid.size_y;
        let ring: Vec<u16> = (0..RING_COLUMNS as i32 * size_y).map(|i| (i % 1000) as u16).collect();
        grid.seed_ring(&ring);
        let mut k = 0;
        for x in 0..GRID_XZ {
            for z in 0..GRID_XZ {
                let (qx, qz) = ((cx << 2) - 1 + x, (cz << 2) - 1 + z);
                let outside = !(0..4).contains(&(qx - (cx << 2))) || !(0..4).contains(&(qz - (cz << 2)));
                for y in 0..size_y {
                    let i = grid.index(qx, grid.min_qy + y, qz).expect("inside the grid");
                    if outside {
                        assert_eq!(grid.get_index(i), Some(ring[k]));
                        k += 1;
                    } else {
                        assert_eq!(grid.get_index(i), None);
                    }
                }
            }
        }
        assert_eq!(k, ring.len());
    }
}
