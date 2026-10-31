import { BLOCKS_PER_SECTION } from "./constants.js";
const MINIMUM_INDIRECT_BITS = 4;
const MAXIMUM_INDIRECT_BITS = 8;
export class PalettedContainer {
    #size;
    #palette;
    #paletteIndices;
    #bitsPerEntry;
    #data;
    constructor(defaultState, size = BLOCKS_PER_SECTION) {
        if (!Number.isInteger(defaultState) || defaultState < 0) {
            throw new RangeError(`Invalid default state: ${defaultState}`);
        }
        if (!Number.isInteger(size) || size <= 0) {
            throw new RangeError(`Invalid palette container size: ${size}`);
        }
        this.#size = size;
        this.#palette = [defaultState];
        this.#paletteIndices = new Map([[defaultState, 0]]);
        this.#bitsPerEntry = 0;
        this.#data = new BigUint64Array();
    }
    get(index) {
        this.#assertIndex(index);
        if (this.#bitsPerEntry === 0) {
            return this.#palette[0];
        }
        const paletteIndex = this.#readPacked(index);
        const state = this.#palette[paletteIndex];
        if (state === undefined) {
            throw new Error(`Corrupt palette index ${paletteIndex}`);
        }
        return state;
    }
    set(index, state) {
        this.#assertIndex(index);
        if (!Number.isInteger(state) || state < 0) {
            throw new RangeError(`Invalid block state: ${state}`);
        }
        let paletteIndex = this.#paletteIndices.get(state);
        if (paletteIndex === undefined) {
            paletteIndex = this.#palette.length;
            this.#palette.push(state);
            this.#paletteIndices.set(state, paletteIndex);
            this.#ensureCapacity(this.#palette.length);
        }
        else if (this.#bitsPerEntry === 0 && paletteIndex === 0) {
            return;
        }
        this.#writePacked(index, paletteIndex);
    }
    fill(state) {
        if (!Number.isInteger(state) || state < 0) {
            throw new RangeError(`Invalid block state: ${state}`);
        }
        this.#palette = [state];
        this.#paletteIndices = new Map([[state, 0]]);
        this.#bitsPerEntry = 0;
        this.#data = new BigUint64Array();
    }
    count(state) {
        let count = 0;
        for (let index = 0; index < this.#size; index += 1) {
            if (this.get(index) === state) {
                count += 1;
            }
        }
        return count;
    }
    serialize() {
        return {
            bitsPerEntry: this.#bitsPerEntry,
            palette: [...this.#palette],
            data: Array.from(this.#data, (value) => value.toString()),
        };
    }
    static deserialize(serialized, size = BLOCKS_PER_SECTION) {
        const first = serialized.palette[0];
        if (first === undefined || serialized.palette.length === 0) {
            throw new Error("Serialized palette must contain at least one state");
        }
        const container = new PalettedContainer(first, size);
        container.#palette = [...serialized.palette];
        container.#paletteIndices = new Map(container.#palette.map((state, index) => [state, index]));
        container.#bitsPerEntry = serialized.bitsPerEntry;
        container.#data = BigUint64Array.from(serialized.data, (value) => BigInt(value));
        container.#validateLayout();
        return container;
    }
    get paletteSize() {
        return this.#palette.length;
    }
    get bitsPerEntry() {
        return this.#bitsPerEntry;
    }
    #ensureCapacity(paletteLength) {
        const requiredBits = paletteLength <= 1
            ? 0
            : Math.max(MINIMUM_INDIRECT_BITS, Math.ceil(Math.log2(paletteLength)));
        if (requiredBits > MAXIMUM_INDIRECT_BITS) {
            throw new RangeError("Bootstrap palette exceeded 256 states; direct global palette mode is not implemented yet");
        }
        if (requiredBits === this.#bitsPerEntry) {
            return;
        }
        const oldBits = this.#bitsPerEntry;
        const oldData = this.#data;
        this.#bitsPerEntry = requiredBits;
        this.#data = new BigUint64Array(this.#requiredLongCount(requiredBits));
        if (oldBits === 0) {
            return;
        }
        for (let index = 0; index < this.#size; index += 1) {
            this.#writePacked(index, readPacked(oldData, oldBits, index));
        }
    }
    #readPacked(index) {
        return readPacked(this.#data, this.#bitsPerEntry, index);
    }
    #writePacked(index, value) {
        if (this.#bitsPerEntry === 0) {
            if (value !== 0) {
                throw new Error("Cannot store a non-zero index in a single-value palette");
            }
            return;
        }
        const valuesPerLong = Math.floor(64 / this.#bitsPerEntry);
        const longIndex = Math.floor(index / valuesPerLong);
        const bitOffset = (index % valuesPerLong) * this.#bitsPerEntry;
        const mask = (1n << BigInt(this.#bitsPerEntry)) - 1n;
        const current = this.#data[longIndex] ?? 0n;
        this.#data[longIndex] =
            (current & ~(mask << BigInt(bitOffset))) |
                (BigInt(value) << BigInt(bitOffset));
    }
    #requiredLongCount(bits) {
        if (bits === 0) {
            return 0;
        }
        return Math.ceil(this.#size / Math.floor(64 / bits));
    }
    #validateLayout() {
        if (this.#bitsPerEntry !== 0 &&
            (this.#bitsPerEntry < MINIMUM_INDIRECT_BITS ||
                this.#bitsPerEntry > MAXIMUM_INDIRECT_BITS)) {
            throw new Error(`Unsupported bits per entry: ${this.#bitsPerEntry}`);
        }
        if (this.#data.length !== this.#requiredLongCount(this.#bitsPerEntry)) {
            throw new Error("Serialized palette data has an invalid long count");
        }
        if (this.#palette.length > 1 << this.#bitsPerEntry) {
            throw new Error("Serialized palette does not fit its bits per entry");
        }
    }
    #assertIndex(index) {
        if (!Number.isInteger(index) || index < 0 || index >= this.#size) {
            throw new RangeError(`Palette index outside 0..${this.#size - 1}: ${index}`);
        }
    }
}
function readPacked(data, bitsPerEntry, index) {
    if (bitsPerEntry === 0) {
        return 0;
    }
    const valuesPerLong = Math.floor(64 / bitsPerEntry);
    const longIndex = Math.floor(index / valuesPerLong);
    const bitOffset = (index % valuesPerLong) * bitsPerEntry;
    const mask = (1n << BigInt(bitsPerEntry)) - 1n;
    return Number(((data[longIndex] ?? 0n) >> BigInt(bitOffset)) & mask);
}
