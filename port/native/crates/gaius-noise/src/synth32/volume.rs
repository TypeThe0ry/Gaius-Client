//! `densityfunction.DensityVolume`: the block grid a volume fill walks.
//!
//! Volume fills visit z, then x, then y (innermost) and write one float per
//! cell in that order, matching `DensityBuffer` indexing.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DensityVolume {
    pub size_x: i32,
    pub size_y: i32,
    pub size_z: i32,
    pub min_block_x: i32,
    pub min_block_y: i32,
    pub min_block_z: i32,
    pub step_block_x: i32,
    pub step_block_y: i32,
    pub step_block_z: i32,
}

impl DensityVolume {
    /// Returns `None` where the vanilla constructor throws (sizes and steps must be positive).
    pub fn new(size: [i32; 3], min_block: [i32; 3], step_block: [i32; 3]) -> Option<DensityVolume> {
        if size.iter().any(|&s| s <= 0) || step_block.iter().any(|&s| s <= 0) {
            return None;
        }
        Some(DensityVolume {
            size_x: size[0],
            size_y: size[1],
            size_z: size[2],
            min_block_x: min_block[0],
            min_block_y: min_block[1],
            min_block_z: min_block[2],
            step_block_x: step_block[0],
            step_block_y: step_block[1],
            step_block_z: step_block[2],
        })
    }

    pub fn len(&self) -> usize {
        self.size_x as usize * self.size_y as usize * self.size_z as usize
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    #[inline]
    pub fn block_x(&self, i: i32) -> i32 {
        self.min_block_x.wrapping_add(i.wrapping_mul(self.step_block_x))
    }

    #[inline]
    pub fn block_y(&self, i: i32) -> i32 {
        self.min_block_y.wrapping_add(i.wrapping_mul(self.step_block_y))
    }

    #[inline]
    pub fn block_z(&self, i: i32) -> i32 {
        self.min_block_z.wrapping_add(i.wrapping_mul(self.step_block_z))
    }
}
