import { BLOCKS_PER_SECTION, CHUNK_WIDTH, OVERWORLD_HEIGHT, OVERWORLD_MAX_Y, OVERWORLD_MIN_Y, OVERWORLD_SECTION_COUNT, } from "./constants.js";
import { blockYToLocal, blockYToSection, sectionIndex, } from "./coordinates.js";
import { PalettedContainer, } from "./palette.js";
export class ChunkSection {
    airState;
    blockStates;
    #nonAirCount = 0;
    constructor(airState, blockStates = new PalettedContainer(airState)) {
        this.airState = airState;
        this.blockStates = blockStates;
        this.#nonAirCount = BLOCKS_PER_SECTION - blockStates.count(airState);
    }
    get(x, y, z) {
        return this.blockStates.get(sectionIndex(x, y, z));
    }
    set(x, y, z, state) {
        const index = sectionIndex(x, y, z);
        const previous = this.blockStates.get(index);
        if (previous === state) {
            return false;
        }
        if (previous === this.airState) {
            this.#nonAirCount += 1;
        }
        if (state === this.airState) {
            this.#nonAirCount -= 1;
        }
        this.blockStates.set(index, state);
        return true;
    }
    get nonAirCount() {
        return this.#nonAirCount;
    }
}
export class Chunk {
    x;
    z;
    airState;
    minimumY;
    height;
    #sections;
    #revision = 0;
    constructor(x, z, airState, minimumY = OVERWORLD_MIN_Y, height = OVERWORLD_HEIGHT) {
        this.x = x;
        this.z = z;
        this.airState = airState;
        this.minimumY = minimumY;
        this.height = height;
        if (height % 16 !== 0) {
            throw new RangeError("Chunk height must be divisible by 16");
        }
        this.#sections = new Array(height / 16);
    }
    getBlock(localX, y, localZ) {
        this.#assertLocal(localX, y, localZ);
        const section = this.#sections[blockYToSection(y, this.minimumY)];
        return (section?.get(localX, blockYToLocal(y, this.minimumY), localZ) ??
            this.airState);
    }
    setBlock(localX, y, localZ, state) {
        this.#assertLocal(localX, y, localZ);
        const sectionIndexValue = blockYToSection(y, this.minimumY);
        let section = this.#sections[sectionIndexValue];
        if (section === undefined) {
            if (state === this.airState) {
                return false;
            }
            section = new ChunkSection(this.airState);
            this.#sections[sectionIndexValue] = section;
        }
        const changed = section.set(localX, blockYToLocal(y, this.minimumY), localZ, state);
        if (changed) {
            this.#revision += 1;
        }
        return changed;
    }
    highestBlockY(localX, localZ) {
        for (let y = this.minimumY + this.height - 1; y >= this.minimumY; y -= 1) {
            if (this.getBlock(localX, y, localZ) !== this.airState) {
                return y;
            }
        }
        return undefined;
    }
    serialize() {
        const sections = [];
        this.#sections.forEach((section, index) => {
            if (section !== undefined && section.nonAirCount > 0) {
                sections.push({
                    index,
                    nonAirCount: section.nonAirCount,
                    blockStates: section.blockStates.serialize(),
                });
            }
        });
        return {
            x: this.x,
            z: this.z,
            minimumY: this.minimumY,
            height: this.height,
            revision: this.#revision,
            sections,
        };
    }
    static deserialize(serialized, airState) {
        const chunk = new Chunk(serialized.x, serialized.z, airState, serialized.minimumY, serialized.height);
        for (const serializedSection of serialized.sections) {
            if (serializedSection.index < 0 ||
                serializedSection.index >= chunk.#sections.length) {
                throw new Error(`Invalid serialized section: ${serializedSection.index}`);
            }
            chunk.#sections[serializedSection.index] = new ChunkSection(airState, PalettedContainer.deserialize(serializedSection.blockStates));
        }
        chunk.#revision = serialized.revision;
        return chunk;
    }
    get revision() {
        return this.#revision;
    }
    get sectionCount() {
        return this.#sections.length;
    }
    #assertLocal(x, y, z) {
        if (!Number.isInteger(x) ||
            !Number.isInteger(z) ||
            x < 0 ||
            x >= CHUNK_WIDTH ||
            z < 0 ||
            z >= CHUNK_WIDTH) {
            throw new RangeError(`Chunk-local position outside bounds: ${x},${y},${z}`);
        }
        if (!Number.isInteger(y) ||
            y < this.minimumY ||
            y >= this.minimumY + this.height) {
            throw new RangeError(`Block Y outside chunk bounds: ${y}`);
        }
    }
}
export function assertOverworldShape(chunk) {
    if (chunk.minimumY !== OVERWORLD_MIN_Y ||
        chunk.height !== OVERWORLD_HEIGHT ||
        chunk.sectionCount !== OVERWORLD_SECTION_COUNT) {
        throw new Error(`Expected 1.21.11 overworld ${OVERWORLD_MIN_Y}..${OVERWORLD_MAX_Y}`);
    }
}
