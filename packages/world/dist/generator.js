import { CHUNK_WIDTH, OVERWORLD_MIN_Y } from "./constants.js";
import { Chunk } from "./chunk.js";
export class SuperflatGenerator {
    states;
    constructor(states) {
        this.states = states;
    }
    generateChunk(x, z) {
        const chunk = new Chunk(x, z, this.states.air);
        for (let localZ = 0; localZ < CHUNK_WIDTH; localZ += 1) {
            for (let localX = 0; localX < CHUNK_WIDTH; localX += 1) {
                chunk.setBlock(localX, OVERWORLD_MIN_Y, localZ, this.states.bedrock);
                chunk.setBlock(localX, OVERWORLD_MIN_Y + 1, localZ, this.states.dirt);
                chunk.setBlock(localX, OVERWORLD_MIN_Y + 2, localZ, this.states.dirt);
                chunk.setBlock(localX, OVERWORLD_MIN_Y + 3, localZ, this.states.grassBlock);
            }
        }
        return chunk;
    }
    get spawnY() {
        return OVERWORLD_MIN_Y + 4;
    }
}
