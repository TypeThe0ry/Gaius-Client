//! `densityfunction.DensityVolume` (26.3): a strided block grid. Buffers over
//! a volume are flat `f32` arrays indexed `y + (x + z * size_x) * size_y`, so
//! a column of `y` values is contiguous.

use crate::java::{floor_div, floor_mod};
use gaius_noise::synth32::DensityVolume;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Volume {
    pub size_x: i32,
    pub size_y: i32,
    pub size_z: i32,
    pub min_x: i32,
    pub min_y: i32,
    pub min_z: i32,
    pub step_x: i32,
    pub step_y: i32,
    pub step_z: i32,
}

impl Volume {
    /// A strided volume. Sizes and steps must be positive (vanilla throws).
    pub fn new(size: [i32; 3], min: [i32; 3], step: [i32; 3]) -> Volume {
        debug_assert!(size.iter().all(|&s| s > 0) && step.iter().all(|&s| s > 0));
        Volume {
            size_x: size[0],
            size_y: size[1],
            size_z: size[2],
            min_x: min[0],
            min_y: min[1],
            min_z: min[2],
            step_x: step[0],
            step_y: step[1],
            step_z: step[2],
        }
    }

    /// A unit-step volume.
    pub fn blocks(size: [i32; 3], min: [i32; 3]) -> Volume {
        Volume::new(size, min, [1, 1, 1])
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.size_x as usize * self.size_y as usize * self.size_z as usize
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// `indexUnchecked(x, y, z)`.
    #[inline]
    pub fn index(&self, x: i32, y: i32, z: i32) -> usize {
        (y + (x + z * self.size_x) * self.size_y) as usize
    }

    #[inline]
    pub fn block_x(&self, i: i32) -> i32 {
        self.min_x.wrapping_add(i.wrapping_mul(self.step_x))
    }

    #[inline]
    pub fn block_y(&self, i: i32) -> i32 {
        self.min_y.wrapping_add(i.wrapping_mul(self.step_y))
    }

    #[inline]
    pub fn block_z(&self, i: i32) -> i32 {
        self.min_z.wrapping_add(i.wrapping_mul(self.step_z))
    }

    #[inline]
    pub fn max_x(&self) -> i32 {
        self.min_x + self.size_x * self.step_x - 1
    }

    #[inline]
    pub fn max_y(&self) -> i32 {
        self.min_y + self.size_y * self.step_y - 1
    }

    #[inline]
    pub fn max_z(&self) -> i32 {
        self.min_z + self.size_z * self.step_z - 1
    }

    /// `intersects(BoundingBox)` with an inclusive box `[min, max]`.
    pub fn intersects(&self, min: [i32; 3], max: [i32; 3]) -> bool {
        max[0] >= self.min_x
            && min[0] <= self.max_x()
            && max[2] >= self.min_z
            && min[2] <= self.max_z()
            && max[1] >= self.min_y
            && min[1] <= self.max_y()
    }

    fn contains_relative(&self, rx: i32, ry: i32, rz: i32) -> bool {
        rx >= 0
            && ry >= 0
            && rz >= 0
            && rx < self.size_x * self.step_x
            && ry < self.size_y * self.step_y
            && rz < self.size_z * self.step_z
            && floor_mod(rx, self.step_x) == 0
            && floor_mod(ry, self.step_y) == 0
            && floor_mod(rz, self.step_z) == 0
    }

    /// `indexOfBlock(x, y, z)`: the buffer index of a block, or `None`
    /// (vanilla `-1`) when the volume does not sample it.
    pub fn index_of_block(&self, x: i32, y: i32, z: i32) -> Option<usize> {
        let rx = x.wrapping_sub(self.min_x);
        let ry = y.wrapping_sub(self.min_y);
        let rz = z.wrapping_sub(self.min_z);
        if self.step_x == 1 && self.step_y == 1 && self.step_z == 1 {
            if rx >= 0 && ry >= 0 && rz >= 0 && rx < self.size_x && ry < self.size_y && rz < self.size_z {
                return Some(self.index(rx, ry, rz));
            }
        } else if self.contains_relative(rx, ry, rz) {
            return Some(self.index(
                floor_div(rx, self.step_x),
                floor_div(ry, self.step_y),
                floor_div(rz, self.step_z),
            ));
        }
        None
    }

    /// The same grid as the noise crate's volume type (for `addToVolume`).
    pub fn to_noise(&self) -> DensityVolume {
        DensityVolume {
            size_x: self.size_x,
            size_y: self.size_y,
            size_z: self.size_z,
            min_block_x: self.min_x,
            min_block_y: self.min_y,
            min_block_z: self.min_z,
            step_block_x: self.step_x,
            step_block_y: self.step_y,
            step_block_z: self.step_z,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn index_of_block_follows_steps() {
        let v = Volume::new([5, 3, 5], [-16, -64, 32], [4, 8, 4]);
        assert_eq!(v.index_of_block(-16, -64, 32), Some(0));
        assert_eq!(v.index_of_block(-12, -56, 36), Some(v.index(1, 1, 1)));
        assert_eq!(v.index_of_block(-13, -56, 36), None);
        assert_eq!(v.index_of_block(4, -64, 32), None);
        let b = Volume::blocks([16, 384, 16], [0, -64, 0]);
        assert_eq!(b.index_of_block(15, 319, 15), Some(b.len() - 1));
        assert_eq!(b.index_of_block(16, 0, 0), None);
    }
}
