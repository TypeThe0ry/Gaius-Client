import { decodeVarInt, encodeVarInt } from "./varint.js";
const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder("utf-8", { fatal: true });
export function concatBytes(...parts) {
    const length = parts.reduce((sum, part) => sum + part.byteLength, 0);
    const output = new Uint8Array(length);
    let offset = 0;
    for (const part of parts) {
        output.set(part, offset);
        offset += part.byteLength;
    }
    return output;
}
export function encodeUnsignedShort(value) {
    if (!Number.isInteger(value) || value < 0 || value > 0xffff) {
        throw new RangeError(`Unsigned short is outside range: ${value}`);
    }
    return Uint8Array.of((value >>> 8) & 0xff, value & 0xff);
}
export function encodeLong(value) {
    const output = new Uint8Array(8);
    new DataView(output.buffer).setBigInt64(0, value, false);
    return output;
}
export function decodeLong(bytes, offset = 0) {
    if (bytes.byteLength - offset < 8) {
        throw new RangeError("Not enough bytes to decode a signed long");
    }
    return new DataView(bytes.buffer, bytes.byteOffset + offset, 8).getBigInt64(0, false);
}
export function encodeString(value) {
    const encoded = textEncoder.encode(value);
    return concatBytes(encodeVarInt(encoded.byteLength), encoded);
}
export function decodeString(bytes, offset = 0, maximumBytes = 1_048_576) {
    const length = decodeVarInt(bytes, offset);
    if (length === undefined) {
        throw new RangeError("Not enough bytes to decode string length");
    }
    if (length.value < 0 || length.value > maximumBytes) {
        throw new RangeError(`Invalid string byte length: ${length.value}`);
    }
    const start = offset + length.bytesRead;
    const end = start + length.value;
    if (end > bytes.byteLength) {
        throw new RangeError("Not enough bytes to decode string body");
    }
    return {
        value: textDecoder.decode(bytes.subarray(start, end)),
        bytesRead: length.bytesRead + length.value,
    };
}
