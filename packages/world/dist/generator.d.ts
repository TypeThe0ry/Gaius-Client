import { Chunk } from "./chunk.js";
import type { BootstrapBlockStates } from "./registry.js";
export interface WorldGenerator {
    generateChunk(x: number, z: number): Chunk;
    readonly spawnY: number;
}
export declare class SuperflatGenerator implements WorldGenerator {
    readonly states: BootstrapBlockStates;
    constructor(states: BootstrapBlockStates);
    generateChunk(x: number, z: number): Chunk;
    get spawnY(): number;
}
