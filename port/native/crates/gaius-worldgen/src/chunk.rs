//! A proto chunk as flat arrays: one `u16` state table index per block
//! (`((y - min_y) << 8) | (z << 4) | x`, the section storage order), the two
//! worldgen heightmaps and the post-processing list. [`ChunkData::encode`]
//! writes the result format documented in `gaius-worldgen-wasm`.

use crate::ir::{state_flags, States};

/// `ChunkAccess.packOffsetCoordinates`.
#[inline]
pub fn pack_offset(x: i32, y: i32, z: i32) -> u16 {
    ((x & 15) | ((y & 15) << 4) | ((z & 15) << 8)) as u16
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HeightmapKind {
    /// `WORLD_SURFACE_WG`: `!isAir()`.
    WorldSurface,
    /// `OCEAN_FLOOR_WG`: blocks motion.
    OceanFloor,
}

#[derive(Clone, Debug)]
pub struct ChunkData {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub min_y: i32,
    pub height: i32,
    pub blocks: Vec<u16>,
    /// First available y per column (`x + z * 16`), `min_y` when empty.
    pub world_surface: [i32; 256],
    pub ocean_floor: [i32; 256],
    /// (section index, packed offset) in marking order.
    pub post_processing: Vec<(u16, u16)>,
    non_air: Vec<u16>,
}

impl ChunkData {
    pub fn new(chunk_x: i32, chunk_z: i32, min_y: i32, height: i32) -> ChunkData {
        ChunkData {
            chunk_x,
            chunk_z,
            min_y,
            height,
            blocks: vec![0; (height as usize) << 8],
            world_surface: [min_y; 256],
            ocean_floor: [min_y; 256],
            post_processing: Vec::new(),
            non_air: vec![0; (height >> 4) as usize],
        }
    }

    #[inline]
    pub fn sections(&self) -> usize {
        (self.height >> 4) as usize
    }

    #[inline]
    pub fn inside(&self, y: i32) -> bool {
        y >= self.min_y && y < self.min_y + self.height
    }

    #[inline]
    fn index(&self, x: i32, y: i32, z: i32) -> usize {
        (((y - self.min_y) << 8) | ((z & 15) << 4) | (x & 15)) as usize
    }

    /// `getBlockState`: air outside the build height (`VOID_AIR` behaves as air).
    #[inline]
    pub fn get(&self, x: i32, y: i32, z: i32) -> u16 {
        if self.inside(y) {
            self.blocks[self.index(x, y, z)]
        } else {
            0
        }
    }

    /// `LevelChunkSection.setBlockState` without heightmap work (the fill path).
    #[inline]
    pub fn set_raw(&mut self, states: &States, x: i32, y: i32, z: i32, state: u16) {
        let i = self.index(x, y, z);
        let old = self.blocks[i];
        self.blocks[i] = state;
        let section = ((y - self.min_y) >> 4) as usize;
        let was_air = states.flags[old as usize] & state_flags::AIR != 0;
        let is_air = states.flags[state as usize] & state_flags::AIR != 0;
        if was_air && !is_air {
            self.non_air[section] += 1;
        } else if !was_air && is_air {
            self.non_air[section] -= 1;
        }
    }

    #[inline]
    fn opaque(states: &States, kind: HeightmapKind, state: u16) -> bool {
        let f = states.flags[state as usize];
        match kind {
            HeightmapKind::WorldSurface => f & state_flags::AIR == 0,
            HeightmapKind::OceanFloor => f & state_flags::OCEAN_FLOOR_OPAQUE != 0,
        }
    }

    fn heightmap(&mut self, kind: HeightmapKind) -> &mut [i32; 256] {
        match kind {
            HeightmapKind::WorldSurface => &mut self.world_surface,
            HeightmapKind::OceanFloor => &mut self.ocean_floor,
        }
    }

    pub fn first_available(&self, kind: HeightmapKind, x: i32, z: i32) -> i32 {
        let i = ((x & 15) + ((z & 15) << 4)) as usize;
        match kind {
            HeightmapKind::WorldSurface => self.world_surface[i],
            HeightmapKind::OceanFloor => self.ocean_floor[i],
        }
    }

    /// `ChunkAccess.getHeight(type, x, z)`.
    #[inline]
    pub fn height_at(&self, kind: HeightmapKind, x: i32, z: i32) -> i32 {
        self.first_available(kind, x, z) - 1
    }

    /// `Heightmap.update(x, y, z, state)`.
    pub fn update_heightmap(&mut self, states: &States, kind: HeightmapKind, x: i32, y: i32, z: i32, state: u16) {
        let i = ((x & 15) + ((z & 15) << 4)) as usize;
        let first = self.first_available(kind, x, z);
        if y <= first - 2 {
            return;
        }
        if Self::opaque(states, kind, state) {
            if y >= first {
                self.heightmap(kind)[i] = y + 1;
            }
        } else if first - 1 == y {
            let mut found = self.min_y;
            let mut yy = y - 1;
            while yy >= self.min_y {
                if Self::opaque(states, kind, self.get(x, yy, z)) {
                    found = yy + 1;
                    break;
                }
                yy -= 1;
            }
            self.heightmap(kind)[i] = found;
        }
    }

    /// `ProtoChunk.setBlockState` for a chunk past the noise step: sets the block and
    /// updates both worldgen heightmaps.
    pub fn set_block(&mut self, states: &States, x: i32, y: i32, z: i32, state: u16) {
        if !self.inside(y) {
            return;
        }
        let section = ((y - self.min_y) >> 4) as usize;
        if self.non_air[section] == 0 && state == 0 {
            return;
        }
        self.set_raw(states, x, y, z, state);
        self.update_heightmap(states, HeightmapKind::OceanFloor, x, y, z, state);
        self.update_heightmap(states, HeightmapKind::WorldSurface, x, y, z, state);
    }

    /// `markPosForPostProcessing`.
    pub fn mark_post_processing(&mut self, x: i32, y: i32, z: i32) {
        if self.inside(y) {
            let section = ((y - self.min_y) >> 4) as u16;
            self.post_processing.push((section, pack_offset(x, y, z)));
        }
    }

    /// `getHighestFilledSectionIndex()`; `-1` when every section is air.
    pub fn highest_filled_section(&self) -> i32 {
        self.non_air.iter().rposition(|&n| n > 0).map_or(-1, |i| i as i32)
    }

    /// Recomputes the per-section non-air counters after the blocks were replaced wholesale.
    pub fn recount(&mut self, states: &States) {
        for (s, count) in self.non_air.iter_mut().enumerate() {
            *count = self.blocks[s << 12..(s + 1) << 12]
                .iter()
                .filter(|&&b| states.flags[b as usize] & state_flags::AIR == 0)
                .count() as u16;
        }
    }

    /// Writes sections, heightmaps and post-processing (see the result format).
    pub fn encode(&self, states: &States, out: &mut Vec<u8>) {
        let mut lookup = vec![u16::MAX; states.global_ids.len()];
        let mut palette: Vec<u16> = Vec::with_capacity(64);
        for s in 0..self.sections() {
            let blocks = &self.blocks[s << 12..(s + 1) << 12];
            palette.clear();
            for &b in blocks {
                if lookup[b as usize] == u16::MAX {
                    lookup[b as usize] = palette.len() as u16;
                    palette.push(b);
                }
            }
            let bits: u8 = if palette.len() == 1 {
                0
            } else if palette.len() <= 256 {
                8
            } else {
                16
            };
            out.extend_from_slice(&(palette.len() as u16).to_le_bytes());
            out.push(bits);
            out.push((self.non_air[s] > 0) as u8);
            for &p in &palette {
                out.extend_from_slice(&states.global_ids[p as usize].to_le_bytes());
            }
            match bits {
                8 => out.extend(blocks.iter().map(|&b| lookup[b as usize] as u8)),
                16 => {
                    for &b in blocks {
                        out.extend_from_slice(&lookup[b as usize].to_le_bytes());
                    }
                }
                _ => {}
            }
            while !out.len().is_multiple_of(4) {
                out.push(0);
            }
            for &p in &palette {
                lookup[p as usize] = u16::MAX;
            }
        }
        for v in self.world_surface.iter().chain(self.ocean_floor.iter()) {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.extend_from_slice(&(self.post_processing.len() as u32).to_le_bytes());
        for &(section, packed) in &self.post_processing {
            out.extend_from_slice(&(((section as u32) << 16) | packed as u32).to_le_bytes());
        }
    }
}
