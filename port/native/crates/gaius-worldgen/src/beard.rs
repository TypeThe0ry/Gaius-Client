//! `Beardifier`: terrain adaptation around structure pieces. The Java side
//! collects the pieces and junctions (`Beardifier.forStructuresInChunk`) and
//! sends them with the terrain job.

use crate::java::{fast_inv_sqrt, floor_div};
use crate::volume::Volume;
use gaius_noise::mth::clamped_map;
use std::sync::OnceLock;

/// `TerrainAdjustment`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Adjustment {
    None,
    Bury,
    BeardThin,
    BeardBox,
    Encapsulate,
}

impl Adjustment {
    pub fn from_code(code: i32) -> Option<Adjustment> {
        Some(match code {
            0 => Adjustment::None,
            1 => Adjustment::Bury,
            2 => Adjustment::BeardThin,
            3 => Adjustment::BeardBox,
            4 => Adjustment::Encapsulate,
            _ => return None,
        })
    }
}

/// `Beardifier.Rigid`: an inclusive bounding box.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rigid {
    pub min: [i32; 3],
    pub max: [i32; 3],
    pub adjustment: Adjustment,
    pub ground_level_delta: i32,
}

/// `JigsawJunction` source position.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Junction {
    pub x: i32,
    pub ground_y: i32,
    pub z: i32,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Beard {
    pub rigids: Vec<Rigid>,
    pub junctions: Vec<Junction>,
    /// `affectedBox` (`[min, max]` inclusive); `None` is `Beardifier.EMPTY`.
    pub affected: Option<([i32; 3], [i32; 3])>,
}

const KERNEL_SIZE: i32 = 24;
const KERNEL_RADIUS: i32 = 12;

/// `BEARD_KERNEL`: `(float) Math.pow(Math.E, -distanceSqr / 16.0)`.
fn kernel() -> &'static [f32] {
    static KERNEL: OnceLock<Vec<f32>> = OnceLock::new();
    KERNEL.get_or_init(|| {
        let mut k = vec![0.0f32; (KERNEL_SIZE * KERNEL_SIZE * KERNEL_SIZE) as usize];
        for zi in 0..KERNEL_SIZE {
            for xi in 0..KERNEL_SIZE {
                for yi in 0..KERNEL_SIZE {
                    let dx = (xi - KERNEL_RADIUS) as f64;
                    let dy = (yi - KERNEL_RADIUS) as f64 + 0.5;
                    let dz = (zi - KERNEL_RADIUS) as f64;
                    let distance_sqr = dx * dx + dy * dy + dz * dz;
                    k[(zi * KERNEL_SIZE * KERNEL_SIZE + xi * KERNEL_SIZE + yi) as usize] =
                        std::f64::consts::E.powf(-distance_sqr / 16.0) as f32;
                }
            }
        }
        k
    })
}

#[inline]
fn in_kernel(i: i32) -> bool {
    (0..KERNEL_SIZE).contains(&i)
}

#[inline]
fn kernel_at(xi: i32, yi: i32, zi: i32) -> f32 {
    kernel()[(zi * KERNEL_SIZE * KERNEL_SIZE + xi * KERNEL_SIZE + yi) as usize]
}

fn inside(min: [i32; 3], max: [i32; 3], x: i32, y: i32, z: i32) -> bool {
    x >= min[0] && x <= max[0] && z >= min[2] && z <= max[2] && y >= min[1] && y <= max[1]
}

struct Offsets {
    dx: i32,
    dy: i32,
    dz: i32,
    dy_to_ground: i32,
}

fn rigid_offsets(rigid: &Rigid, x: i32, y: i32, z: i32) -> Offsets {
    let (min, max) = (rigid.min, rigid.max);
    let dx = 0.max((min[0] - x).max(x - max[0]));
    let dz = 0.max((min[2] - z).max(z - max[2]));
    let ground_y = min[1] + rigid.ground_level_delta;
    let dy_to_ground = y - ground_y;
    let dy = match rigid.adjustment {
        Adjustment::None => 0,
        Adjustment::Bury | Adjustment::BeardThin => dy_to_ground,
        Adjustment::BeardBox => 0.max((ground_y - y).max(y - max[1])),
        Adjustment::Encapsulate => 0.max((min[1] - y).max(y - max[1])),
    };
    Offsets {
        dx,
        dy,
        dz,
        dy_to_ground,
    }
}

impl Beard {
    pub fn is_empty(&self) -> bool {
        self.affected.is_none()
    }

    // ---- 26.3 (float) ----

    fn bury32(dx: f32, dy: f32, dz: f32) -> f32 {
        let distance_sq = dx * dx + dy * dy + dz * dz;
        if distance_sq >= 36.0 {
            0.0
        } else {
            1.0 - distance_sq.sqrt() / 6.0
        }
    }

    fn beard32(dx: i32, dy: i32, dz: i32, y_to_ground: i32) -> f32 {
        let (xi, yi, zi) = (dx + KERNEL_RADIUS, dy + KERNEL_RADIUS, dz + KERNEL_RADIUS);
        if in_kernel(xi) && in_kernel(yi) && in_kernel(zi) {
            let dy_with_offset = y_to_ground as f32 + 0.5;
            let (fx, fz) = (dx as f32, dz as f32);
            let distance_sqr = fx * fx + dy_with_offset * dy_with_offset + fz * fz;
            let value = -dy_with_offset * fast_inv_sqrt((distance_sqr / 2.0) as f64) as f32 / 2.0;
            value * kernel_at(xi, yi, zi)
        } else {
            0.0
        }
    }

    fn sample_unchecked32(&self, x: i32, y: i32, z: i32) -> f32 {
        let mut noise = 0.0f32;
        for rigid in &self.rigids {
            let o = rigid_offsets(rigid, x, y, z);
            noise += match rigid.adjustment {
                Adjustment::None => 0.0,
                Adjustment::Bury => Self::bury32(o.dx as f32, o.dy as f32 / 2.0, o.dz as f32),
                Adjustment::BeardThin | Adjustment::BeardBox => Self::beard32(o.dx, o.dy, o.dz, o.dy_to_ground) * 0.8,
                Adjustment::Encapsulate => Self::bury32(o.dx as f32 / 2.0, o.dy as f32 / 2.0, o.dz as f32 / 2.0) * 0.8,
            };
        }
        for j in &self.junctions {
            let dy = y - j.ground_y;
            noise += Self::beard32(x - j.x, dy, z - j.z, dy) * 0.4;
        }
        noise
    }

    /// 26.3 `Beardifier.sampleValue`.
    pub fn sample_value32(&self, x: i32, y: i32, z: i32) -> f32 {
        match self.affected {
            Some((min, max)) if inside(min, max, x, y, z) => self.sample_unchecked32(x, y, z),
            _ => 0.0,
        }
    }

    /// 26.3 `Beardifier.sampleVolume`.
    pub fn sample_volume32(&self, out: &mut [f32], v: &Volume) {
        out.fill(0.0);
        let Some((min, max)) = self.affected else {
            return;
        };
        if !v.intersects(min, max) {
            return;
        }
        let x0 = floor_div(0.max(min[0] - v.min_x), v.step_x);
        let y0 = floor_div(0.max(min[1] - v.min_y), v.step_y);
        let z0 = floor_div(0.max(min[2] - v.min_z), v.step_z);
        let x1 = (v.size_x - 1).min(floor_div(max[0] - v.min_x, v.step_x));
        let y1 = (v.size_y - 1).min(floor_div(max[1] - v.min_y, v.step_y));
        let z1 = (v.size_z - 1).min(floor_div(max[2] - v.min_z, v.step_z));
        for z in z0..=z1 {
            let bz = v.block_z(z);
            for x in x0..=x1 {
                let bx = v.block_x(x);
                for y in y0..=y1 {
                    out[v.index(x, y, z)] = self.sample_unchecked32(bx, v.block_y(y), bz);
                }
            }
        }
    }

    // ---- 1.21.11 / 26.2 (double) ----

    fn bury64(dx: f64, dy: f64, dz: f64) -> f64 {
        let distance = (dx * dx + dy * dy + dz * dz).sqrt();
        clamped_map(distance, 0.0, 6.0, 1.0, 0.0)
    }

    fn beard64(dx: i32, dy: i32, dz: i32, y_to_ground: i32) -> f64 {
        let (xi, yi, zi) = (dx + KERNEL_RADIUS, dy + KERNEL_RADIUS, dz + KERNEL_RADIUS);
        if in_kernel(xi) && in_kernel(yi) && in_kernel(zi) {
            let dy_with_offset = y_to_ground as f64 + 0.5;
            let (fx, fz) = (dx as f64, dz as f64);
            let distance_sqr = fx * fx + dy_with_offset * dy_with_offset + fz * fz;
            let value = -dy_with_offset * fast_inv_sqrt(distance_sqr / 2.0) / 2.0;
            value * kernel_at(xi, yi, zi) as f64
        } else {
            0.0
        }
    }

    /// Pre-26.3 `Beardifier.compute`.
    pub fn compute64(&self, x: i32, y: i32, z: i32) -> f64 {
        let Some((min, max)) = self.affected else {
            return 0.0;
        };
        if !inside(min, max, x, y, z) {
            return 0.0;
        }
        let mut noise = 0.0f64;
        for rigid in &self.rigids {
            let o = rigid_offsets(rigid, x, y, z);
            noise += match rigid.adjustment {
                Adjustment::None => 0.0,
                Adjustment::Bury => Self::bury64(o.dx as f64, o.dy as f64 / 2.0, o.dz as f64),
                Adjustment::BeardThin | Adjustment::BeardBox => Self::beard64(o.dx, o.dy, o.dz, o.dy_to_ground) * 0.8,
                Adjustment::Encapsulate => Self::bury64(o.dx as f64 / 2.0, o.dy as f64 / 2.0, o.dz as f64 / 2.0) * 0.8,
            };
        }
        for j in &self.junctions {
            let dy = y - j.ground_y;
            noise += Self::beard64(x - j.x, dy, z - j.z, dy) * 0.4;
        }
        noise
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_beard_is_zero_and_box_contributes() {
        let empty = Beard::default();
        assert_eq!(empty.sample_value32(0, 64, 0), 0.0);
        assert_eq!(empty.compute64(0, 64, 0), 0.0);
        let beard = Beard {
            rigids: vec![Rigid {
                min: [0, 60, 0],
                max: [8, 70, 8],
                adjustment: Adjustment::BeardThin,
                ground_level_delta: 0,
            }],
            junctions: vec![],
            affected: Some(([-24, 36, -24], [32, 94, 32])),
        };
        let below = beard.sample_value32(4, 57, 4);
        assert!(below > 0.0, "below the ground the beard adds density: {below}");
        assert!(beard.compute64(4, 63, 4) < 0.0);
        let v = Volume::blocks([2, 4, 2], [3, 55, 3]);
        let mut out = vec![1.0; v.len()];
        beard.sample_volume32(&mut out, &v);
        assert_eq!(
            out[v.index(1, 2, 1)].to_bits(),
            beard.sample_value32(4, 57, 4).to_bits()
        );
    }
}
