import type { SerializedChunk } from "@gaius/world";
export interface WorldMetadata {
    readonly id: string;
    readonly name: string;
    readonly seed: string;
    readonly createdAt: number;
    readonly updatedAt: number;
    readonly gameVersion: "1.21.11";
    readonly generator: "minecraft:flat";
}
export interface WorldStorage {
    loadMetadata(worldId: string): Promise<WorldMetadata | undefined>;
    saveMetadata(metadata: WorldMetadata): Promise<void>;
    deleteWorld(worldId: string): Promise<void>;
    loadChunk(worldId: string, x: number, z: number): Promise<SerializedChunk | undefined>;
    saveChunk(worldId: string, chunk: SerializedChunk): Promise<void>;
}
export declare class MemoryWorldStorage implements WorldStorage {
    #private;
    loadMetadata(worldId: string): Promise<WorldMetadata | undefined>;
    saveMetadata(metadata: WorldMetadata): Promise<void>;
    deleteWorld(worldId: string): Promise<void>;
    loadChunk(worldId: string, x: number, z: number): Promise<SerializedChunk | undefined>;
    saveChunk(worldId: string, chunk: SerializedChunk): Promise<void>;
}
