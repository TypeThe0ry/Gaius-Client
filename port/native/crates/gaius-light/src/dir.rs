//! `Direction` ordinals and the packed queue entries of `LightEngine$QueueEntry`.
//!
//! Entry layout (the low 16 bits of the vanilla `long`): level in bits 0-3,
//! one "propagate this way" bit per direction in bits 4-9 (bit `4 + ordinal`),
//! `FLAG_FROM_EMPTY_SHAPE` in bit 10 and `FLAG_INCREASE_FROM_EMISSION` in bit
//! 11. The Java side can pass an entry straight to `enqueueIncrease` /
//! `enqueueDecrease` after widening it to a `long`.

/// `Direction.values()` order, which is also `PROPAGATION_DIRECTIONS`.
pub const DOWN: usize = 0;
pub const UP: usize = 1;
pub const NORTH: usize = 2;
pub const SOUTH: usize = 3;
pub const WEST: usize = 4;
pub const EAST: usize = 5;

/// `Direction.getOpposite()`: the ordinals pair up as (0,1), (2,3), (4,5).
#[inline(always)]
pub const fn opposite(dir: usize) -> usize {
    dir ^ 1
}

pub const LEVEL_MASK: u16 = 0x000f;
pub const DIRECTIONS_MASK: u16 = 0x03f0;
pub const FLAG_FROM_EMPTY_SHAPE: u16 = 0x0400;
pub const FLAG_INCREASE_FROM_EMISSION: u16 = 0x0800;

#[inline(always)]
const fn dir_bit(dir: usize) -> u16 {
    1 << (dir + 4)
}

#[inline(always)]
const fn with_level(entry: u16, level: u32) -> u16 {
    (entry & !LEVEL_MASK) | (level as u16 & LEVEL_MASK)
}

/// `QueueEntry.decreaseSkipOneDirection(level, dir)`.
#[inline(always)]
pub const fn decrease_skip_one(level: u32, dir: usize) -> u16 {
    with_level(DIRECTIONS_MASK & !dir_bit(dir), level)
}

/// `QueueEntry.decreaseAllDirections(level)`.
#[inline(always)]
pub const fn decrease_all(level: u32) -> u16 {
    with_level(DIRECTIONS_MASK, level)
}

/// `QueueEntry.increaseLightFromEmission(level, fromEmptyShape)`.
#[inline(always)]
pub const fn increase_from_emission(level: u32, from_empty_shape: bool) -> u16 {
    let mut entry = DIRECTIONS_MASK | FLAG_INCREASE_FROM_EMISSION;
    if from_empty_shape {
        entry |= FLAG_FROM_EMPTY_SHAPE;
    }
    with_level(entry, level)
}

/// `QueueEntry.increaseSkipOneDirection(level, fromEmptyShape, dir)`.
#[inline(always)]
pub const fn increase_skip_one(level: u32, from_empty_shape: bool, dir: usize) -> u16 {
    let mut entry = DIRECTIONS_MASK & !dir_bit(dir);
    if from_empty_shape {
        entry |= FLAG_FROM_EMPTY_SHAPE;
    }
    with_level(entry, level)
}

/// `QueueEntry.increaseOnlyOneDirection(level, fromEmptyShape, dir)`.
#[inline(always)]
pub const fn increase_only_one(level: u32, from_empty_shape: bool, dir: usize) -> u16 {
    let mut entry = dir_bit(dir);
    if from_empty_shape {
        entry |= FLAG_FROM_EMPTY_SHAPE;
    }
    with_level(entry, level)
}

/// `QueueEntry.increaseSkySourceInDirections(down, north, south, west, east)`.
#[inline(always)]
pub const fn increase_sky_source(down: bool, north: bool, south: bool, west: bool, east: bool) -> u16 {
    let mut entry = 15;
    if down {
        entry |= dir_bit(DOWN);
    }
    if north {
        entry |= dir_bit(NORTH);
    }
    if south {
        entry |= dir_bit(SOUTH);
    }
    if west {
        entry |= dir_bit(WEST);
    }
    if east {
        entry |= dir_bit(EAST);
    }
    entry
}

#[inline(always)]
pub const fn level(entry: u16) -> u32 {
    (entry & LEVEL_MASK) as u32
}

#[inline(always)]
pub const fn propagates(entry: u16, dir: usize) -> bool {
    entry & dir_bit(dir) != 0
}

#[inline(always)]
pub const fn from_empty_shape(entry: u16) -> bool {
    entry & FLAG_FROM_EMPTY_SHAPE != 0
}

#[inline(always)]
pub const fn from_emission(entry: u16) -> bool {
    entry & FLAG_INCREASE_FROM_EMISSION != 0
}

/// `LightEngine.PULL_LIGHT_IN_ENTRY`.
pub const PULL_LIGHT_IN: u16 = decrease_all(1);
/// `SkyLightEngine.REMOVE_TOP_SKY_SOURCE_ENTRY`.
pub const REMOVE_TOP_SKY_SOURCE: u16 = decrease_all(15);
/// `SkyLightEngine.REMOVE_SKY_SOURCE_ENTRY`.
pub const REMOVE_SKY_SOURCE: u16 = decrease_skip_one(15, UP);
/// `SkyLightEngine.ADD_SKY_SOURCE_ENTRY`.
pub const ADD_SKY_SOURCE: u16 = increase_skip_one(15, false, UP);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entries_match_the_vanilla_bits() {
        // Values from LightEngine$QueueEntry: DIRECTIONS_MASK = 1008, the UP bit is 1 << 5.
        assert_eq!(decrease_all(1), 1008 | 1);
        assert_eq!(REMOVE_SKY_SOURCE, (1008 & !(1 << 5)) | 15);
        assert_eq!(increase_from_emission(14, true), 1008 | 2048 | 1024 | 14);
        assert_eq!(increase_only_one(7, false, WEST), (1 << 8) | 7);
        assert_eq!(
            increase_sky_source(true, false, false, false, true),
            15 | (1 << 4) | (1 << 9)
        );
        assert!(propagates(ADD_SKY_SOURCE, DOWN) && !propagates(ADD_SKY_SOURCE, UP));
    }
}
