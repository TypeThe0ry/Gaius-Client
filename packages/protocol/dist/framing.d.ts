export interface MinecraftPacket {
    readonly id: number;
    readonly payload: Uint8Array;
}
export declare function encodePacket(id: number, payload?: Uint8Array): Uint8Array;
export declare class PacketStream {
    #private;
    constructor(maximumPacketBytes?: number);
    push(chunk: Uint8Array): MinecraftPacket[];
    get bufferedBytes(): number;
}
