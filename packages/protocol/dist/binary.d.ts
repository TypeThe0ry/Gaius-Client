export declare function concatBytes(...parts: readonly Uint8Array[]): Uint8Array;
export declare function encodeUnsignedShort(value: number): Uint8Array;
export declare function encodeLong(value: bigint): Uint8Array;
export declare function decodeLong(bytes: Uint8Array, offset?: number): bigint;
export declare function encodeString(value: string): Uint8Array;
export interface DecodedString {
    readonly value: string;
    readonly bytesRead: number;
}
export declare function decodeString(bytes: Uint8Array, offset?: number, maximumBytes?: number): DecodedString;
