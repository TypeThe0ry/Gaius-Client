//! `Aquifer.NoiseBasedAquifer`. The algorithm is the same in every profile;
//! only how its noises are sampled differs, which [`AquiferNoises`]
//! abstracts (26.3 caching samplers, 26.2 router functions on the NoiseChunk).

use crate::ir::{state_flags, FluidPicker};
use crate::java::{block_pos_as_long, block_pos_x, block_pos_y, block_pos_z, floor_div, quantize};
use gaius_noise::mth::{clamp, clamped_map, map};
use gaius_noise::PositionalRandomFactory;

/// `DimensionType.WAY_BELOW_MIN_Y`.
pub const WAY_BELOW_MIN_Y: i32 = -2_032;

/// `Aquifer.FluidStatus` (state is a state table index).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FluidStatus {
    pub level: i32,
    pub state: u16,
}

impl FluidStatus {
    #[inline]
    pub fn at(self, y: i32) -> u16 {
        if y < self.level {
            self.state
        } else {
            0
        }
    }
}

/// The global fluid picker of `NoiseBasedChunkGenerator.createFluidPicker`.
#[derive(Clone, Copy, Debug)]
pub struct Picker {
    lava_below: i32,
    lava: FluidStatus,
    fluid: FluidStatus,
}

impl Picker {
    pub fn new(p: &FluidPicker) -> Picker {
        Picker {
            lava_below: p.lava_below,
            lava: FluidStatus {
                level: p.lava_level,
                state: p.lava_state as u16,
            },
            fluid: FluidStatus {
                level: p.fluid_level,
                state: p.fluid_state as u16,
            },
        }
    }

    #[inline]
    pub fn compute(&self, _x: i32, y: i32, _z: i32) -> FluidStatus {
        if y < self.lava_below {
            self.lava
        } else {
            self.fluid
        }
    }
}

/// The noises an aquifer samples.
pub trait AquiferNoises {
    /// `barrierNoise` at a block (26.3 `sampleValue`, 26.2 `compute(NoiseChunk)`).
    fn barrier(&mut self, x: i32, y: i32, z: i32) -> f64;
    /// `fluidLevelFloodednessNoise`, before clamping.
    fn floodedness(&mut self, x: i32, y: i32, z: i32) -> f64;
    /// `fluidLevelSpread`: the spread noise at a fluid cell, already multiplied by 10.
    fn spread(&mut self, cx: i32, cy: i32, cz: i32) -> f64;
    /// `lavaNoise` at a fluid type cell.
    fn lava(&mut self, cx: i32, cy: i32, cz: i32) -> f64;
    /// 26.3 `exclusion > 0`; 26.2 `OverworldBiomeBuilder.isDeepDarkRegion`.
    fn excluded(&mut self, x: i32, y: i32, z: i32) -> bool;
    /// The cached preliminary surface level of a column (quantized to quart corners).
    fn surface_level(&mut self, x: i32, z: i32) -> i32;
}

const SURFACE_SAMPLING_OFFSETS: [[i32; 2]; 13] = [
    [0, 0],
    [-2, -1],
    [-1, -1],
    [0, -1],
    [1, -1],
    [-3, 0],
    [-2, 0],
    [-1, 0],
    [1, 0],
    [-2, 1],
    [-1, 1],
    [0, 1],
    [1, 1],
];

#[inline]
fn grid_x(b: i32) -> i32 {
    b >> 4
}
#[inline]
fn from_grid_x(g: i32, offset: i32) -> i32 {
    (g << 4) + offset
}
#[inline]
fn grid_y(b: i32) -> i32 {
    floor_div(b, 12)
}
#[inline]
fn from_grid_y(g: i32, offset: i32) -> i32 {
    g * 12 + offset
}

#[inline]
fn similarity(d1: i32, d2: i32) -> f64 {
    1.0 - (d2 - d1) as f64 / 25.0
}

/// `FLOWING_UPDATE_SIMULARITY = similarity(10 * 10, 12 * 12)`.
fn flowing_update_similarity() -> f64 {
    similarity(100, 144)
}

pub struct Aquifer {
    random: PositionalRandomFactory,
    picker: Picker,
    lava_state: u16,
    cache: Vec<Option<FluidStatus>>,
    locations: Vec<i64>,
    min_grid_x: i32,
    min_grid_y: i32,
    min_grid_z: i32,
    size_x: i32,
    size_z: i32,
    skip_sampling_above_y: i32,
    pub should_schedule_fluid_update: bool,
}

impl Aquifer {
    /// Sets up the grid over `[min_x, max_x] x [min_y, top_y] x [min_z, max_z]` and calls
    /// `max_surface(min_x, min_z, max_x, max_z)` for the highest preliminary surface of the
    /// grid's columns (`maxPreliminarySurfaceLevel` / `maxSurfaceLevel`).
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        random: PositionalRandomFactory,
        picker: Picker,
        lava_state: u16,
        min_x: i32,
        max_x: i32,
        min_y: i32,
        top_y: i32,
        min_z: i32,
        max_z: i32,
        max_surface: impl FnOnce(i32, i32, i32, i32) -> i32,
    ) -> Aquifer {
        let min_grid_x = grid_x(min_x - 5);
        let max_grid_x = grid_x(max_x - 5) + 1;
        let size_x = max_grid_x - min_grid_x + 1;
        let min_grid_y = grid_y(min_y + 1) - 1;
        let max_grid_y = grid_y(top_y + 1) + 1;
        let size_y = max_grid_y - min_grid_y + 1;
        let min_grid_z = grid_z(min_z - 5);
        let max_grid_z = grid_z(max_z - 5) + 1;
        let size_z = max_grid_z - min_grid_z + 1;
        let total = (size_x * size_y * size_z).max(0) as usize;
        let max_adjusted = max_surface(
            from_grid_x(min_grid_x, 0),
            from_grid_z(min_grid_z, 0),
            from_grid_x(max_grid_x, 9),
            from_grid_z(max_grid_z, 9),
        ) + 8;
        let skip_grid_y = grid_y(max_adjusted + 12) + 1;
        Aquifer {
            random,
            picker,
            lava_state,
            cache: vec![None; total],
            locations: vec![i64::MAX; total],
            min_grid_x,
            min_grid_y,
            min_grid_z,
            size_x,
            size_z,
            skip_sampling_above_y: from_grid_y(skip_grid_y, 11) - 1,
            should_schedule_fluid_update: false,
        }
    }

    #[inline]
    fn index(&self, gx: i32, gy: i32, gz: i32) -> usize {
        let x = gx - self.min_grid_x;
        let y = gy - self.min_grid_y;
        let z = gz - self.min_grid_z;
        ((y * self.size_z + z) * self.size_x + x) as usize
    }

    /// `computeSubstance`: `None` means solid (the default block or an ore vein).
    pub fn compute_substance(
        &mut self,
        noises: &mut impl AquiferNoises,
        flags: &[u32],
        x: i32,
        y: i32,
        z: i32,
        density: f64,
    ) -> Option<u16> {
        if density > 0.0 {
            self.should_schedule_fluid_update = false;
            return None;
        }
        let global = self.picker.compute(x, y, z);
        if y > self.skip_sampling_above_y {
            self.should_schedule_fluid_update = false;
            return Some(global.at(y));
        }
        if flags[global.at(y) as usize] & state_flags::LAVA != 0 {
            self.should_schedule_fluid_update = false;
            return Some(self.lava_state);
        }
        let x_anchor = grid_x(x - 5);
        let y_anchor = grid_y(y + 1);
        let z_anchor = grid_z(z - 5);
        let mut d = [i32::MAX; 4];
        let mut closest = [0usize; 4];
        for x1 in 0..=1 {
            for y1 in -1..=1 {
                for z1 in 0..=1 {
                    let gx = x_anchor + x1;
                    let gy = y_anchor + y1;
                    let gz = z_anchor + z1;
                    let index = self.index(gx, gy, gz);
                    let existing = self.locations[index];
                    let location = if existing != i64::MAX {
                        existing
                    } else {
                        let mut r = self.random.at(gx, gy, gz);
                        let lx = from_grid_x(gx, r.next_int_bound(10));
                        let ly = from_grid_y(gy, r.next_int_bound(9));
                        let lz = from_grid_z(gz, r.next_int_bound(10));
                        let l = block_pos_as_long(lx, ly, lz);
                        self.locations[index] = l;
                        l
                    };
                    let dx = block_pos_x(location) - x;
                    let dy = block_pos_y(location) - y;
                    let dz = block_pos_z(location) - z;
                    let nd = dx * dx + dy * dy + dz * dz;
                    if d[0] >= nd {
                        closest = [index, closest[0], closest[1], closest[2]];
                        d = [nd, d[0], d[1], d[2]];
                    } else if d[1] >= nd {
                        closest = [closest[0], index, closest[1], closest[2]];
                        d = [d[0], nd, d[1], d[2]];
                    } else if d[2] >= nd {
                        closest[3] = closest[2];
                        closest[2] = index;
                        d[3] = d[2];
                        d[2] = nd;
                    } else if d[3] >= nd {
                        closest[3] = index;
                        d[3] = nd;
                    }
                }
            }
        }
        let status1 = self.status(noises, flags, closest[0]);
        let similarity12 = similarity(d[0], d[1]);
        let fluid_state = status1.at(y);
        let flowing = flowing_update_similarity();
        if similarity12 <= 0.0 {
            self.should_schedule_fluid_update = if similarity12 >= flowing {
                let status2 = self.status(noises, flags, closest[1]);
                status1 != status2
            } else {
                false
            };
            return Some(fluid_state);
        }
        if flags[fluid_state as usize] & state_flags::WATER != 0
            && flags[self.picker.compute(x, y - 1, z).at(y - 1) as usize] & state_flags::LAVA != 0
        {
            self.should_schedule_fluid_update = true;
            return Some(fluid_state);
        }
        let mut barrier = f64::NAN;
        let status2 = self.status(noises, flags, closest[1]);
        let barrier12 = similarity12 * self.pressure(noises, flags, x, y, z, &mut barrier, status1, status2);
        if density + barrier12 > 0.0 {
            self.should_schedule_fluid_update = false;
            return None;
        }
        let status3 = self.status(noises, flags, closest[2]);
        let similarity13 = similarity(d[0], d[2]);
        if similarity13 > 0.0 {
            let barrier13 =
                similarity12 * similarity13 * self.pressure(noises, flags, x, y, z, &mut barrier, status1, status3);
            if density + barrier13 > 0.0 {
                self.should_schedule_fluid_update = false;
                return None;
            }
        }
        let similarity23 = similarity(d[1], d[2]);
        if similarity23 > 0.0 {
            let barrier23 =
                similarity12 * similarity23 * self.pressure(noises, flags, x, y, z, &mut barrier, status2, status3);
            if density + barrier23 > 0.0 {
                self.should_schedule_fluid_update = false;
                return None;
            }
        }
        let may_flow12 = status1 != status2;
        let may_flow23 = similarity23 >= flowing && status2 != status3;
        let may_flow13 = similarity13 >= flowing && status1 != status3;
        self.should_schedule_fluid_update = if !may_flow12 && !may_flow23 && !may_flow13 {
            similarity13 >= flowing
                && similarity(d[0], d[3]) >= flowing
                && status1 != self.status(noises, flags, closest[3])
        } else {
            true
        };
        Some(fluid_state)
    }

    #[allow(clippy::too_many_arguments)]
    fn pressure(
        &self,
        noises: &mut impl AquiferNoises,
        flags: &[u32],
        x: i32,
        y: i32,
        z: i32,
        barrier: &mut f64,
        s1: FluidStatus,
        s2: FluidStatus,
    ) -> f64 {
        let t1 = flags[s1.at(y) as usize];
        let t2 = flags[s2.at(y) as usize];
        let lava = state_flags::LAVA;
        let water = state_flags::WATER;
        if (t1 & lava != 0 && t2 & water != 0) || (t1 & water != 0 && t2 & lava != 0) {
            return 2.0;
        }
        let fluid_y_diff = (s1.level - s2.level).abs();
        if fluid_y_diff == 0 {
            return 0.0;
        }
        let average_fluid_y = 0.5 * (s1.level + s2.level) as f64;
        let above_average = y as f64 + 0.5 - average_fluid_y;
        let base_value = fluid_y_diff as f64 / 2.0;
        let towards_middle = base_value - above_average.abs();
        let gradient = if above_average > 0.0 {
            let center = 0.0 + towards_middle;
            if center > 0.0 {
                center / 1.5
            } else {
                center / 2.5
            }
        } else {
            let center = 3.0 + towards_middle;
            if center > 0.0 {
                center / 3.0
            } else {
                center / 10.0
            }
        };
        let noise = if !(gradient < -2.0) && !(gradient > 2.0) {
            if barrier.is_nan() {
                let v = noises.barrier(x, y, z);
                *barrier = v;
                v
            } else {
                *barrier
            }
        } else {
            0.0
        };
        2.0 * (noise + gradient)
    }

    fn status(&mut self, noises: &mut impl AquiferNoises, flags: &[u32], index: usize) -> FluidStatus {
        if let Some(s) = self.cache[index] {
            return s;
        }
        let l = self.locations[index];
        let s = self.compute_fluid(noises, flags, block_pos_x(l), block_pos_y(l), block_pos_z(l));
        self.cache[index] = Some(s);
        s
    }

    fn compute_fluid(&mut self, noises: &mut impl AquiferNoises, flags: &[u32], x: i32, y: i32, z: i32) -> FluidStatus {
        let global = self.picker.compute(x, y, z);
        let mut lowest = i32::MAX;
        let top = y + 12;
        let bottom = y - 12;
        let mut center_under_fluid = false;
        for offset in SURFACE_SAMPLING_OFFSETS {
            let sx = x + (offset[0] << 4);
            let sz = z + (offset[1] << 4);
            let surface = noises.surface_level(sx, sz);
            let adjusted = surface + 8;
            let start = offset[0] == 0 && offset[1] == 0;
            if start && bottom > adjusted {
                return global;
            }
            let pokes_above = top > adjusted;
            if pokes_above || start {
                let at_surface = self.picker.compute(sx, adjusted, sz);
                if flags[at_surface.at(adjusted) as usize] & state_flags::AIR == 0 {
                    if start {
                        center_under_fluid = true;
                    }
                    if pokes_above {
                        return at_surface;
                    }
                }
            }
            lowest = lowest.min(surface);
        }
        let level = self.surface_level_at(noises, x, y, z, global, lowest, center_under_fluid);
        FluidStatus {
            level,
            state: self.fluid_type(noises, x, y, z, global, level),
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn surface_level_at(
        &mut self,
        noises: &mut impl AquiferNoises,
        x: i32,
        y: i32,
        z: i32,
        global: FluidStatus,
        lowest: i32,
        center_under_fluid: bool,
    ) -> i32 {
        let (partially, fully) = if noises.excluded(x, y, z) {
            (-1.0, -1.0)
        } else {
            let distance_below = lowest + 8 - y;
            let factor = if center_under_fluid {
                clamped_map(distance_below as f64, 0.0, 64.0, 1.0, 0.0)
            } else {
                0.0
            };
            let flood = clamp(noises.floodedness(x, y, z), -1.0, 1.0);
            let fully_threshold = map(factor, 1.0, 0.0, -0.3, 0.8);
            let partially_threshold = map(factor, 1.0, 0.0, -0.8, 0.4);
            (flood - partially_threshold, flood - fully_threshold)
        };
        if fully > 0.0 {
            global.level
        } else if partially > 0.0 {
            let cx = floor_div(x, 16);
            let cy = floor_div(y, 40);
            let cz = floor_div(z, 16);
            let middle = cy * 40 + 20;
            let spread = quantize(noises.spread(cx, cy, cz), 3);
            lowest.min(middle + spread)
        } else {
            WAY_BELOW_MIN_Y
        }
    }

    fn fluid_type(
        &mut self,
        noises: &mut impl AquiferNoises,
        x: i32,
        y: i32,
        z: i32,
        global: FluidStatus,
        level: i32,
    ) -> u16 {
        if level <= -10 && level != WAY_BELOW_MIN_Y && global.state != self.lava_state {
            let cx = floor_div(x, 64);
            let cy = floor_div(y, 40);
            let cz = floor_div(z, 64);
            if noises.lava(cx, cy, cz).abs() > 0.3 {
                return self.lava_state;
            }
        }
        global.state
    }
}

#[inline]
fn grid_z(b: i32) -> i32 {
    b >> 4
}
#[inline]
fn from_grid_z(g: i32, offset: i32) -> i32 {
    (g << 4) + offset
}

/// `Aquifer.createDisabled(picker).computeSubstance`.
pub fn disabled_substance(picker: &Picker, x: i32, y: i32, z: i32, density: f64) -> Option<u16> {
    if density > 0.0 {
        None
    } else {
        Some(picker.compute(x, y, z).at(y))
    }
}
