export const MINECRAFT_1_21_11 = {
    name: "1.21.11",
    protocolVersion: 774,
    worldVersion: 4671,
    resourcePackVersion: "75.0",
    dataPackVersion: "94.1",
};
export const MINECRAFT_26_2 = {
    name: "26.2",
    protocolVersion: 776,
    worldVersion: 4903,
    resourcePackVersion: "88.0",
    dataPackVersion: "107.1",
};
export const MINECRAFT_26_3 = {
    name: "26.3",
    protocolVersion: 777,
    worldVersion: 5023,
    resourcePackVersion: "97.1",
    dataPackVersion: "121.0",
};
const minecraftProfiles = [MINECRAFT_1_21_11, MINECRAFT_26_2, MINECRAFT_26_3];
const minecraftProfilesByName = Object.freeze(Object.fromEntries(
    minecraftProfiles.map((profile) => [profile.name, profile])));
const minecraftProfilesByProtocol = Object.freeze(Object.fromEntries(
    minecraftProfiles.map((profile) => [String(profile.protocolVersion), profile])));
/**
 * The protocol profiles supported by the wire helpers. The array is frozen so
 * callers cannot accidentally mutate the set used by version resolution.
 */
export const MINECRAFT_PROTOCOLS = Object.freeze([...minecraftProfiles]);
export function resolveMinecraftProtocol(value = MINECRAFT_1_21_11) {
    if (value === undefined || value === null) {
        return MINECRAFT_1_21_11;
    }
    if (typeof value === "number") {
        const profile = minecraftProfilesByProtocol[String(value)];
        if (profile === undefined) {
            throw new RangeError(`Unsupported Minecraft protocol version: ${value}`);
        }
        return profile;
    }
    if (typeof value === "string") {
        const key = value.trim();
        const profile = minecraftProfilesByName[key] ?? minecraftProfilesByProtocol[key];
        if (profile === undefined) {
            throw new RangeError(`Unsupported Minecraft version: ${value}`);
        }
        return profile;
    }
    if (typeof value === "object" &&
        Number.isInteger(value.protocolVersion) &&
        typeof value.name === "string") {
        const profile = minecraftProfilesByName[value.name];
        if (profile !== undefined && profile.protocolVersion === value.protocolVersion) {
            return profile;
        }
        throw new RangeError(
            `Minecraft profile name/protocol mismatch: ${value.name}/${value.protocolVersion}`);
    }
    throw new TypeError("Minecraft version must be a profile, name, or protocol number");
}
export const DEFAULT_MINECRAFT_PORT = 25565;
