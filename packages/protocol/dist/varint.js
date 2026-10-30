export function encodeVarInt(value) {
    if (!Number.isInteger(value) || value < -0x80000000 || value > 0x7fffffff) {
        throw new RangeError(`VarInt value is outside signed 32-bit range: ${value}`);
    }
    let remaining = value >>> 0;
    const bytes = [];
    do {
        let current = remaining & 0x7f;
        remaining >>>= 7;
        if (remaining !== 0) {
            current |= 0x80;
        }
        bytes.push(current);
    } while (remaining !== 0);
    return Uint8Array.from(bytes);
}
export function decodeVarInt(bytes, offset = 0) {
    let value = 0;
    for (let index = 0; index < 5; index += 1) {
        const current = bytes[offset + index];
        if (current === undefined) {
            return undefined;
        }
        value |= (current & 0x7f) << (7 * index);
        if ((current & 0x80) === 0) {
            return {
                value: value | 0,
                bytesRead: index + 1,
            };
        }
    }
    throw new Error("Invalid VarInt: exceeds 5 bytes");
}
