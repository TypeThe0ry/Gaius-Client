import { BlockList, isIP } from "node:net";
import {MAXIMUM_CONNECT_CONTROL_BYTES, parseSessionProtocolDeclaration} from "./session-protocol.js";

const privateNetworkBlockList = createPrivateNetworkBlockList();
export function isOriginAllowed(origin, allowedOrigins) {
    if (origin === undefined) {
        return false;
    }
    if (allowedOrigins.includes("*")) {
        return true;
    }
    // Chromium serializes file:// fetch origins as "file://" in some request
    // paths; the portable page has an opaque origin represented as "null".
    // Treat the browser spelling as null only when null was explicitly opted in.
    return allowedOrigins.includes(origin) ||
        (origin === "file://" && allowedOrigins.includes("null"));
}
export function isHostAllowed(host, allowedHosts) {
    const normalized = normalizeHost(host);
    return allowedHosts.some((entry) => {
        const allowed = normalizeHost(entry);
        if (allowed === "*") {
            return true;
        }
        if (allowed.startsWith("*.")) {
            const suffix = allowed.slice(1);
            return normalized.endsWith(suffix) && normalized.length > suffix.length;
        }
        return normalized === allowed;
    });
}
export function filterAllowedTargets(targets, allowedHosts) {
    return targets.filter((target) => isHostAllowed(target.host, allowedHosts));
}
export function normalizeHost(host) {
    const trimmed = host.trim().toLowerCase();
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        return trimmed.slice(1, -1);
    }
    return trimmed.endsWith(".") ? trimmed.slice(0, -1) : trimmed;
}
export function isPrivateNetworkAddress(value) {
    const normalized = normalizeHost(value);
    const version = isIP(normalized);
    if (version === 0) {
        return false;
    }
    if (privateNetworkBlockList.check(normalized, version === 4 ? "ipv4" : "ipv6")) {
        return true;
    }
    // Some IPv6 transition mechanisms carry an IPv4 destination inside an
    // otherwise valid IPv6 literal.  Node's BlockList treats these as IPv6,
    // so an address such as `::7f00:1` or `64:ff9b::7f00:1` would otherwise
    // evade the IPv4 private-range checks above.  Canonicalize the embedded
    // address before allowing a public-target dial.
    const embedded = embeddedIpv4Address(normalized);
    return embedded !== undefined && privateNetworkBlockList.check(embedded, "ipv4");
}

function embeddedIpv4Address(value) {
    const groups = expandIpv6Groups(value);
    if (groups === undefined) {
        return undefined;
    }
    if (groups.some((group) => !/^[0-9a-f]{1,4}$/u.test(group))) {
        return undefined;
    }
    const words = groups.map((group) => Number.parseInt(group, 16));
    let bits;
    // IPv4-compatible (::/96), IPv4-mapped (::ffff:0:0/96), and the
    // well-known NAT64 prefix all carry the IPv4 value in the final 32 bits.
    const first96Zero = words.slice(0, 6).every((word) => word === 0);
    const mapped = first96Zero && (words[5] === 0 || words[5] === 0xffff);
    const nat64 = words.slice(0, 6).every((word, index) =>
        word === [0x0064, 0xff9b, 0, 0, 0, 0][index]);
    if (mapped || nat64) {
        bits = (words[6] << 16) | words[7];
    }
    // 6to4 embeds an IPv4 address in bits 16..48 (2002:v4::/48).
    else if (words[0] === 0x2002) {
        bits = (words[1] << 16) | words[2];
    }
    // Teredo encodes the client IPv4 address as the one's complement of the
    // final 32 bits (2001:0000::/32).
    else if (words[0] === 0x2001 && words[1] === 0) {
        bits = (~((words[6] << 16) | words[7])) >>> 0;
    }
    else {
        return undefined;
    }
    return `${bits >>> 24}.${(bits >>> 16) & 0xff}.${(bits >>> 8) & 0xff}.${bits & 0xff}`;
}

function expandIpv6Groups(value) {
    const halves = value.split("::");
    if (halves.length > 2) {
        return undefined;
    }
    const left = halves[0] === "" ? [] : halves[0].split(":");
    const right = halves.length === 2 && halves[1] !== "" ? halves[1].split(":") : [];
    const missing = 8 - left.length - right.length;
    if (halves.length === 1 && missing !== 0 || halves.length === 2 && missing < 1) {
        return undefined;
    }
    return [...left, ...Array(missing).fill("0"), ...right];
}
export function parseConnectRequest(text) {
    if (Buffer.byteLength(text, "utf8") > MAXIMUM_CONNECT_CONTROL_BYTES) {
        throw new TypeError("Connect control message exceeds 64 KiB");
    }
    const value = JSON.parse(text);
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
        throw new TypeError("Control message must be an object");
    }
    const candidate = value;
    if (candidate.type !== "connect") {
        throw new TypeError("First control message must have type 'connect'");
    }
    if (typeof candidate.host !== "string" || candidate.host.length > 255) {
        throw new TypeError("Connect host must be a string of at most 255 chars");
    }
    if (typeof candidate.port !== "number" ||
        !Number.isInteger(candidate.port) ||
        candidate.port < 1 ||
        candidate.port > 65535) {
        throw new TypeError("Connect port must be an integer from 1 to 65535");
    }
    if (candidate.token !== undefined &&
        typeof candidate.token !== "string") {
        throw new TypeError("Connect token must be a string");
    }
    const skinDescriptor = parseSkinDescriptor(candidate.skinDescriptor);
    const protocolTable = parseSessionProtocolDeclaration(candidate.protocolTable);
    const host = normalizeConnectHost(candidate.host, candidate.port);
    if (host.length === 0 || (isIP(host) === 0 && !isValidDnsName(host))) {
        throw new TypeError("Connect host is not a valid IP address or DNS name");
    }
    if (skinDescriptor !== undefined &&
        !/^(?:client|server|lan-server)-[0-9a-f]{32}\.gaius-local$/u.test(host)) {
        throw new TypeError("Skin descriptor is only valid for a LAN tunnel");
    }
    return {
        type: "connect",
        host,
        port: candidate.port,
        ...(candidate.token === undefined ? {} : { token: candidate.token }),
        ...(skinDescriptor === undefined ? {} : { skinDescriptor }),
        ...(protocolTable === undefined ? {} : { protocolTable }),
    };
}
function parseSkinDescriptor(value) {
    if (value === undefined) {
        return undefined;
    }
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
        throw new TypeError("Skin descriptor must be an object");
    }
    const uuid = typeof value.uuid === "string" ? value.uuid.trim().toLowerCase() : "";
    const username = typeof value.username === "string" ? value.username : "";
    const textureValue = typeof value.value === "string" ? value.value : "";
    const signature = value.signature === undefined
        ? ""
        : typeof value.signature === "string" ? value.signature : null;
    if (!/^[0-9a-f]{32}$/u.test(uuid) || username.length < 1 || username.length > 16 ||
        textureValue.length < 1 || textureValue.length > 16384 || signature === null ||
        signature.length > 16384) {
        throw new TypeError("Skin descriptor is invalid or exceeds its size limit");
    }
    return { uuid, username, value: textureValue, signature };
}
function normalizeConnectHost(host, port) {
    const value = host.trim().toLowerCase();
    if (value.startsWith("[")) {
        const closingBracket = value.indexOf("]");
        if (closingBracket <= 1) {
            return value;
        }
        const remainder = value.slice(closingBracket + 1);
        if (remainder.length === 0) {
            return value.slice(1, closingBracket);
        }
        if (remainder === `:${port}`) {
            return value.slice(1, closingBracket);
        }
        return value;
    }
    const firstColon = value.indexOf(":");
    if (firstColon > 0 && firstColon === value.lastIndexOf(":")) {
        const suffix = value.slice(firstColon + 1);
        if (suffix === String(port)) {
            return value.slice(0, firstColon);
        }
    }
    return normalizeHost(value);
}
function isValidDnsName(host) {
    if (host.length > 253) {
        return false;
    }
    return host.split(".").every((label) => label.length >= 1 &&
        label.length <= 63 &&
        /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/u.test(label));
}
function createPrivateNetworkBlockList() {
    const blockList = new BlockList();
    for (const [network, prefix] of [
        ["0.0.0.0", 8],
        ["10.0.0.0", 8],
        ["100.64.0.0", 10],
        ["127.0.0.0", 8],
        ["169.254.0.0", 16],
        ["172.16.0.0", 12],
        ["192.0.0.0", 24],
        ["192.0.2.0", 24],
        ["192.168.0.0", 16],
        ["198.18.0.0", 15],
        ["198.51.100.0", 24],
        ["203.0.113.0", 24],
        ["224.0.0.0", 4],
        ["240.0.0.0", 4],
    ]) {
        blockList.addSubnet(network, prefix, "ipv4");
    }
    for (const [network, prefix] of [
        ["::", 128],
        ["::1", 128],
        ["64:ff9b:1::", 48],
        ["100::", 64],
        ["2001:db8::", 32],
        ["2001:10::", 28],
        ["2001:20::", 28],
        ["fc00::", 7],
        ["fe80::", 10],
        ["ff00::", 8],
    ]) {
        blockList.addSubnet(network, prefix, "ipv6");
    }
    return blockList;
}
