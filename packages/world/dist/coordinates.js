import { CHUNK_WIDTH, SECTION_HEIGHT } from "./constants.js";
export function floorDiv(value, divisor) {
    return Math.floor(value / divisor);
}
export function floorMod(value, divisor) {
    return ((value % divisor) + divisor) % divisor;
}
export function blockToChunk(value) {
    return floorDiv(value, CHUNK_WIDTH);
}
export function blockToLocal(value) {
    return floorMod(value, CHUNK_WIDTH);
}
export function blockYToSection(y, minimumY) {
    return floorDiv(y - minimumY, SECTION_HEIGHT);
}
export function blockYToLocal(y, minimumY) {
    return floorMod(y - minimumY, SECTION_HEIGHT);
}
export function chunkKey(x, z) {
    return `${x},${z}`;
}
export function sectionIndex(x, y, z) {
    if (x < 0 ||
        x >= CHUNK_WIDTH ||
        y < 0 ||
        y >= SECTION_HEIGHT ||
        z < 0 ||
        z >= CHUNK_WIDTH) {
        throw new RangeError(`Section coordinate outside 0..15: ${x},${y},${z}`);
    }
    return (y << 8) | (z << 4) | x;
}
