import { PalettedContainer, type SerializedPalettedContainer } from "./palette.js";
export interface SerializedChunkSection {
    readonly index: number;
    readonly nonAirCount: number;
    readonly blockStates: SerializedPalettedContainer;
}
export interface SerializedChunk {
    readonly x: number;
    readonly z: number;
    readonly minimumY: number;
    readonly height: number;
    readonly revision: number;
    readonly sections: readonly SerializedChunkSection[];
}
export declare class ChunkSection {
    #private;
    readonly airState: number;
    readonly blockStates: PalettedContainer;
    constructor(airState: number, blockStates?: PalettedContainer);
    get(x: number, y: number, z: number): number;
    set(x: number, y: number, z: number, state: number): boolean;
    get nonAirCount(): number;
}
export declare class Chunk {
    #private;
    readonly x: number;
    readonly z: number;
    readonly airState: number;
    readonly minimumY: number;
    readonly height: number;
    constructor(x: number, z: number, airState: number, minimumY?: number, height?: number);
    getBlock(localX: number, y: number, localZ: number): number;
    setBlock(localX: number, y: number, localZ: number, state: number): boolean;
    highestBlockY(localX: number, localZ: number): number | undefined;
    serialize(): SerializedChunk;
    static deserialize(serialized: SerializedChunk, airState: number): Chunk;
    get revision(): number;
    get sectionCount(): number;
}
export declare function assertOverworldShape(chunk: Chunk): void;
