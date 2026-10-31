export interface BlockPosition {
    readonly x: number;
    readonly y: number;
    readonly z: number;
}
export interface ChunkPosition {
    readonly x: number;
    readonly z: number;
}
export declare function floorDiv(value: number, divisor: number): number;
export declare function floorMod(value: number, divisor: number): number;
export declare function blockToChunk(value: number): number;
export declare function blockToLocal(value: number): number;
export declare function blockYToSection(y: number, minimumY: number): number;
export declare function blockYToLocal(y: number, minimumY: number): number;
export declare function chunkKey(x: number, z: number): string;
export declare function sectionIndex(x: number, y: number, z: number): number;
