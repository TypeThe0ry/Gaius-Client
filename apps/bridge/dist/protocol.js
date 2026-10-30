import {readFileSync, readdirSync} from "node:fs";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {validateProtocolTable} from "./protocol-schema.js";

export function loadMinecraftProfiles(directory) {
    const profiles = [];
    for (const filename of readdirSync(directory).sort()) {
        if (!/^(?:0|[1-9]\d*)\.json$/u.test(filename)) continue;
        const protocolVersion = Number(filename.slice(0, -5));
        const table = JSON.parse(readFileSync(resolve(directory, filename), "utf8"));
        const profile = validateProtocolTable(table, protocolVersion);
        if (profile !== undefined) profiles.push(profile);
    }
    return Object.freeze(profiles.sort((left, right) => left.protocolVersion - right.protocolVersion));
}

export const MINECRAFT_PROFILES = loadMinecraftProfiles(
    process.env.GAIUS_BRIDGE_PROTOCOL_DIRECTORY?.trim() ||
    fileURLToPath(new URL("../protocols/", import.meta.url)));

const byVersion = new Map(
    MINECRAFT_PROFILES.map(profile => [profile.protocolVersion, profile]));

export function resolveMinecraftProfile(protocolVersion) {
    return byVersion.get(protocolVersion);
}

// ClientboundLoginPacket carries the initial view/simulation distances. A
// vanilla server is not required to repeat those values with the later
// set_chunk_cache_radius/set_simulation_distance packets unless they change.
// Keep this prefix parser independent from Buffer so the bridge and smoke
// harnesses can share the exact wire contract.
export function decodeClientboundLoginDistances(payload) {
    if (payload === undefined || payload === null ||
        !Number.isInteger(payload.byteLength) || payload.byteLength < 6) {
        throw new Error("clientbound login payload is truncated");
    }

    // int playerId + boolean hardcore
    let offset = 5;
    const levelCount = decodeVarIntAt(payload, offset, "level count");
    offset = levelCount.nextOffset;
    if (levelCount.value < 0 || levelCount.value > 1024) {
        throw new Error(`clientbound login level count is invalid: ${levelCount.value}`);
    }
    for (let index = 0; index < levelCount.value; index++) {
        const levelLength = decodeVarIntAt(payload, offset, "level name length");
        offset = levelLength.nextOffset;
        if (levelLength.value < 1 || levelLength.value > 32767 ||
            offset + levelLength.value > payload.byteLength) {
            throw new Error("clientbound login level name is invalid");
        }
        offset += levelLength.value;
    }

    const maxPlayers = decodeVarIntAt(payload, offset, "max players");
    offset = maxPlayers.nextOffset;
    const chunkRadius = decodeVarIntAt(payload, offset, "chunk radius");
    offset = chunkRadius.nextOffset;
    const simulationDistance = decodeVarIntAt(payload, offset, "simulation distance");
    if (chunkRadius.value < 2 || chunkRadius.value > 32 ||
        simulationDistance.value < 2 || simulationDistance.value > 32) {
        throw new Error(
            `clientbound login distance contract is invalid: ` +
            `${chunkRadius.value}/${simulationDistance.value}`,
        );
    }
    return Object.freeze({
        chunkRadius: chunkRadius.value,
        simulationDistance: simulationDistance.value,
        prefixBytesRead: simulationDistance.nextOffset,
    });
}

function decodeVarIntAt(bytes, offset, label) {
    let value = 0;
    for (let index = 0; index < 5; index++) {
        const position = offset + index;
        if (position >= bytes.byteLength) {
            throw new Error(`clientbound login ${label} is truncated`);
        }
        const next = bytes[position];
        value |= (next & 0x7f) << (index * 7);
        if ((next & 0x80) === 0) {
            return { value: value | 0, nextOffset: position + 1 };
        }
    }
    throw new Error(`clientbound login ${label} exceeded five bytes`);
}
