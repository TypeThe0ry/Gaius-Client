import type { SingleplayerCommand, SingleplayerEvent, WorldSnapshot } from "./messages.js";
import type { WorldStorage } from "./storage.js";
export declare class SingleplayerServer {
    #private;
    constructor(storage: WorldStorage);
    handle(command: SingleplayerCommand): Promise<SingleplayerEvent[]>;
    tick(): Promise<WorldSnapshot | undefined>;
    createWorld(id: string, name: string, seed: string): Promise<void>;
    loadWorld(id: string): Promise<void>;
    flushDirtyChunks(): Promise<void>;
    snapshot(radius?: number): Promise<WorldSnapshot>;
    breakBlock(x: number, y: number, z: number): Promise<void>;
    placeBlock(x: number, y: number, z: number): Promise<void>;
    private getChunk;
    private findStandingY;
}
