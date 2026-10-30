import { concatBytes, decodeLong, decodeString, encodeLong, encodeString, encodeUnsignedShort, } from "./binary.js";
import { DEFAULT_MINECRAFT_PORT, MINECRAFT_1_21_11, resolveMinecraftProtocol, } from "./constants.js";
import { encodePacket } from "./framing.js";
import { encodeVarInt } from "./varint.js";
export function createStatusHandshake(host, port = DEFAULT_MINECRAFT_PORT, protocol = MINECRAFT_1_21_11) {
    if (host.length === 0 || host.length > 255) {
        throw new RangeError("Minecraft host must contain between 1 and 255 chars");
    }
    // Keep accepting a raw protocol number for callers that talk to a custom
    // server, while allowing the shared version profiles to select 774/776/777.
    const protocolVersion = typeof protocol === "number"
        ? protocol
        : resolveMinecraftProtocol(protocol).protocolVersion;
    if (!Number.isSafeInteger(protocolVersion) || protocolVersion < 0) {
        throw new RangeError(`Minecraft protocol version must be a non-negative integer, got ${protocolVersion}`);
    }
    return encodePacket(0, concatBytes(encodeVarInt(protocolVersion), encodeString(host), encodeUnsignedShort(port), encodeVarInt(1)));
}
export function createStatusRequest() {
    return encodePacket(0);
}
export function createPingRequest(timestamp) {
    return encodePacket(1, encodeLong(timestamp));
}
export function parseStatusResponse(payload) {
    const decoded = decodeString(payload);
    if (decoded.bytesRead !== payload.byteLength) {
        throw new Error("Status response contains trailing bytes");
    }
    const status = JSON.parse(decoded.value);
    if (typeof status !== "object" || status === null || Array.isArray(status)) {
        throw new TypeError("Status response JSON must be an object");
    }
    return status;
}
export function parsePingResponse(payload) {
    if (payload.byteLength !== 8) {
        throw new RangeError(`Ping response must contain 8 bytes, got ${payload.byteLength}`);
    }
    return decodeLong(payload);
}
export function flattenStatusDescription(description) {
    if (typeof description === "string") {
        return description;
    }
    if (description === undefined) {
        return "";
    }
    return [
        typeof description.text === "string" ? description.text : "",
        ...(description.extra ?? []).map((part) => flattenStatusDescription(part)),
    ].join("");
}
