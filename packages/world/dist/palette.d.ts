export interface SerializedPalettedContainer {
    readonly bitsPerEntry: number;
    readonly palette: readonly number[];
    readonly data: readonly string[];
}
export declare class PalettedContainer {
    #private;
    constructor(defaultState: number, size?: number);
    get(index: number): number;
    set(index: number, state: number): void;
    fill(state: number): void;
    count(state: number): number;
    serialize(): SerializedPalettedContainer;
    static deserialize(serialized: SerializedPalettedContainer, size?: number): PalettedContainer;
    get paletteSize(): number;
    get bitsPerEntry(): number;
}
