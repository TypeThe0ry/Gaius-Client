export const CHUNK_WIDTH = 16;
export const SECTION_HEIGHT = 16;
export const BLOCKS_PER_SECTION = 16 * 16 * 16;
// Java Edition 1.21.11 overworld dimensions.
export const OVERWORLD_MIN_Y = -64;
export const OVERWORLD_HEIGHT = 384;
export const OVERWORLD_MAX_Y = OVERWORLD_MIN_Y + OVERWORLD_HEIGHT - 1;
export const OVERWORLD_SECTION_COUNT = OVERWORLD_HEIGHT / SECTION_HEIGHT;
