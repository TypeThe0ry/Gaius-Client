export interface DecodedVarInt {
    readonly value: number;
    readonly bytesRead: number;
}
export declare function encodeVarInt(value: number): Uint8Array;
export declare function decodeVarInt(bytes: Uint8Array, offset?: number): DecodedVarInt | undefined;
