import { lookup } from "node:dns/promises";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { setTimeout as delay } from "node:timers/promises";
import { WebSocket } from "ws";
import {
    MINECRAFT_PROFILES,
} from "./dist/protocol.js";

const registryPath = new URL("../../relay-nodes.json", import.meta.url);
const registry = JSON.parse(await readFile(registryPath, "utf8"));
const relayUrl = process.env.GAIUS_PUBLIC_RELAY_URL ?? registry.nodes?.[0]?.url;
const configuredTarget = process.env.GAIUS_PUBLIC_RELAY_TARGET;
assert(typeof configuredTarget === "string" && configuredTarget.trim().length > 0,
    "GAIUS_PUBLIC_RELAY_TARGET is required; do not store a public target in the repository");
const target = parseTarget(configuredTarget);
const handshakeHost = process.env.GAIUS_PUBLIC_RELAY_HANDSHAKE_HOST ?? target.host;
const minecraftProfile = resolveSmokeMinecraftProfile(
    process.env.GAIUS_PUBLIC_RELAY_MINECRAFT_VERSION ??
    process.env.GAIUS_PUBLIC_RELAY_PROTOCOL_VERSION ??
    MINECRAFT_PROFILES[0]?.name);
const origin = process.env.GAIUS_PUBLIC_RELAY_ORIGIN ?? "null";
const timeoutMs = parsePositiveInteger(
    process.env.GAIUS_PUBLIC_RELAY_TIMEOUT_MS ?? "15000",
    "GAIUS_PUBLIC_RELAY_TIMEOUT_MS");

assert(typeof relayUrl === "string" && relayUrl.length > 0,
    "relay-nodes.json does not contain a public RelayNode");

const relayHostname = new URL(relayUrl).hostname;
const relayAddress = process.env.GAIUS_PUBLIC_RELAY_EDGE_IP
    ?? await syntheticDnsFallback(relayHostname, timeoutMs);
// A diagnostic edge-IP URL can sit behind a virtual-host proxy.  Keep the
// transport address in the URL while allowing the caller to supply the
// canonical HTTP/WebSocket Host header used by that proxy.  The default is
// the URL hostname, so normal DNS/TLS behavior is unchanged.
const relayHostHeader = process.env.GAIUS_PUBLIC_RELAY_HOST_HEADER
    ?? new URL(relayUrl).host;
assert(typeof relayHostHeader === "string" && relayHostHeader.trim().length > 0,
    "GAIUS_PUBLIC_RELAY_HOST_HEADER must be non-empty when supplied");
const manifestUrl = relayManifestUrl(relayUrl, target);
const before = await fetchManifest(
    manifestUrl, origin, timeoutMs, relayAddress, relayHostHeader);
assert(before.ok === true && before.protocolVersion === 1,
    "public RelayNode manifest is incompatible");
assert(before.requiresToken === false,
    "public RelayNode unexpectedly requires a shared browser token");
assert(before.allowsPrivateTargets === false,
    "public RelayNode does not enable the public-target guard");
assert(before.capabilities?.includes("target-attestation"),
    "public RelayNode cannot attest which Minecraft target it connected");
assert(before.availableConnections > 0,
    "public RelayNode has no available tunnel capacity");

const beforeActive = before.target?.activeConnections ?? 0;
const webSocket = await openRelay(
    relayUrl, origin, timeoutMs, relayAddress, relayHostHeader);
const controls = [];
let during;
let connectedControl;
let responseBuffer = Buffer.alloc(0);
let settled = false;

const status = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
        rejectOnce(new Error(`public RelayNode status smoke timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    const rejectOnce = (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        reject(error);
    };
    const resolveOnce = (value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        resolve(value);
    };

    webSocket.on("error", rejectOnce);
    webSocket.on("close", (code, reason) => {
        if (!settled) {
            rejectOnce(new Error(
                `public RelayNode closed before status response (${code}): ${reason}`));
        }
    });
    webSocket.on("message", (data, binary) => {
        if (!binary) {
            let control;
            try {
                control = JSON.parse(data.toString("utf8"));
            }
            catch (error) {
                rejectOnce(new Error(`invalid RelayNode control message: ${error.message}`));
                return;
            }
            controls.push(control.type);
            if (control.type === "connected") {
                assert(control.host === target.host && control.port === target.port,
                    `RelayNode attested the wrong target ${control.host}:${control.port}`);
                assert(typeof control.candidateHost === "string" &&
                    Number.isInteger(control.candidatePort) && control.candidatePort > 0,
                    "RelayNode did not report the TCP candidate it connected to");
                assert(typeof control.remoteAddress === "string" &&
                    Number.isInteger(control.remotePort) && control.remotePort > 0,
                    "RelayNode did not report its TCP remote peer");
                connectedControl = control;
                void sendStatusRequest().catch(rejectOnce);
            }
            return;
        }

        responseBuffer = Buffer.concat([responseBuffer, Buffer.from(data)]);
        try {
            const parsed = readStatusResponse(responseBuffer);
            if (parsed !== undefined) resolveOnce(parsed);
        }
        catch (error) {
            rejectOnce(error);
        }
    });

    webSocket.send(JSON.stringify({
        type: "connect",
        host: target.host,
        port: target.port,
    }));

    async function sendStatusRequest() {
        during = await fetchManifest(
            manifestUrl, origin, timeoutMs, relayAddress, relayHostHeader);
        assert((during.target?.activeConnections ?? 0) >= beforeActive + 1,
            "RelayNode target affinity did not report the temporary tunnel");
        const handshake = Buffer.concat([
            encodeVarInt(minecraftProfile.protocolVersion),
            encodeString(handshakeHost),
            Buffer.from([target.port >> 8, target.port & 0xff]),
            encodeVarInt(1),
        ]);
        webSocket.send(Buffer.concat([
            encodePacket(0, handshake),
            encodePacket(0),
        ]));
    }
});

const closed = new Promise((resolve) => webSocket.once("close", resolve));
webSocket.close(1000, "public relay smoke complete");
await closed;

const leaseReleased = await waitForLeaseRelease(
    manifestUrl, origin, beforeActive, timeoutMs, relayAddress, relayHostHeader);
assert(leaseReleased, "RelayNode did not release the target tunnel after WebSocket close");

console.log(JSON.stringify({
    ok: true,
    relayUrl,
    relayHostHeader,
    relayName: before.name,
    target: `${target.host}:${target.port}`,
    handshakeHost,
    controls,
    minecraft: {
        protocol: status.version?.protocol,
        version: status.version?.name,
        onlinePlayers: status.players?.online,
        maximumPlayers: status.players?.max,
        description: flattenDescription(status.description),
        statusSha256: createHash("sha256")
            .update(JSON.stringify(status))
            .digest("hex"),
    },
    targetConnections: {
        before: beforeActive,
        during: during.target.activeConnections,
        released: leaseReleased,
        total: during.target.totalConnections,
    },
    attestedTarget: `${connectedControl.candidateHost}:${connectedControl.candidatePort}`,
    remotePeer: `${connectedControl.remoteAddress}:${connectedControl.remotePort}`,
}));

function resolveSmokeMinecraftProfile(value) {
    const key = String(value ?? "").trim();
    const profile = MINECRAFT_PROFILES
        .find((candidate) => candidate.name === key ||
            String(candidate.protocolVersion) === key);
    if (profile === undefined) {
        throw new Error(
            `Unsupported public smoke Minecraft version ${value}; ` +
            `expected ${MINECRAFT_PROFILES.map(profile => `${profile.name}/${profile.protocolVersion}`).join(", ")}`);
    }
    return profile;
}

async function openRelay(url, requestOrigin, timeout, address, hostHeader) {
    return new Promise((resolve, reject) => {
        let settled = false;
        const timer = setTimeout(() => finish(
            new Error(`WebSocket connection timed out after ${timeout}ms`)), timeout);
        const options = {
            origin: requestOrigin,
            headers: {host: hostHeader},
        };
        if (address !== undefined) {
            options.lookup = lookupAddress(address);
        }
        const socket = new WebSocket(url, options);
        socket.once("open", () => finish(undefined, socket));
        socket.once("error", finish);

        function finish(error, value) {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            if (error === undefined) resolve(value);
            else reject(error);
        }
    });
}

async function syntheticDnsFallback(hostname, timeout) {
    try {
        const addresses = await lookup(hostname, {all: true});
        if (!addresses.some(({address}) => isSyntheticAddress(address))) return undefined;
    }
    catch {
        // The HTTPS DNS query below also handles local resolver failures.
    }

    const response = await fetch(
        `https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(hostname)}&type=A`, {
            headers: {accept: "application/dns-json"},
            signal: AbortSignal.timeout(timeout),
        });
    assert(response.ok, `public DNS fallback failed with HTTP ${response.status}`);
    const result = await response.json();
    const address = result.Answer?.find((answer) => answer.type === 1)?.data;
    assert(typeof address === "string", `public DNS did not return an A record for ${hostname}`);
    return address;
}

function isSyntheticAddress(address) {
    const octets = address.split(".").map(Number);
    return octets.length === 4 && octets[0] === 198 && (octets[1] === 18 || octets[1] === 19);
}

async function fetchManifest(url, requestOrigin, timeout, address, hostHeader) {
    const parsed = new URL(url);
    const request = parsed.protocol === "https:" ? httpsRequest : httpRequest;
    return new Promise((resolve, reject) => {
        const options = {
            headers: {
                accept: "application/json",
                origin: requestOrigin,
                host: hostHeader,
            },
        };
        if (address !== undefined) options.lookup = lookupAddress(address);
        const clientRequest = request(parsed, options, (response) => {
            const chunks = [];
            let bytes = 0;
            response.on("data", (chunk) => {
                bytes += chunk.byteLength;
                if (bytes > 1024 * 1024) {
                    clientRequest.destroy(new Error("RelayNode manifest exceeds 1 MiB"));
                    return;
                }
                chunks.push(chunk);
            });
            response.on("end", () => {
                try {
                    assert(response.statusCode >= 200 && response.statusCode < 300,
                        `RelayNode manifest failed with HTTP ${response.statusCode}`);
                    const allowOrigin = response.headers["access-control-allow-origin"];
                    assert(allowOrigin === "*" || allowOrigin === requestOrigin,
                        `RelayNode manifest rejected portable origin ${requestOrigin}`);
                    resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
                }
                catch (error) {
                    reject(error);
                }
            });
        });
        clientRequest.setTimeout(timeout, () => clientRequest.destroy(
            new Error(`RelayNode manifest timed out after ${timeout}ms`)));
        clientRequest.once("error", reject);
        clientRequest.end();
    });
}

async function waitForLeaseRelease(
    url, requestOrigin, maximumActive, timeout, address, hostHeader) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
        const manifest = await fetchManifest(
            url, requestOrigin, timeout, address, hostHeader);
        if ((manifest.target?.activeConnections ?? 0) <= maximumActive) return true;
        await delay(100);
    }
    return false;
}

function lookupAddress(address) {
    const family = address.includes(":") ? 6 : 4;
    return (_hostname, lookupOptions, callback) => {
        if (lookupOptions?.all) callback(null, [{address, family}]);
        else callback(null, address, family);
    };
}

function relayManifestUrl(relay, destination) {
    const url = new URL(relay);
    url.protocol = url.protocol === "wss:" ? "https:" : "http:";
    url.pathname = "/relay-node/v1";
    url.search = new URLSearchParams({
        host: destination.host,
        port: String(destination.port),
    }).toString();
    return url;
}

function parseTarget(value) {
    const parsed = new URL(`minecraft://${value}`);
    const port = parsePositiveInteger(parsed.port || "25565", "target port");
    assert(port <= 65535, "target port must not exceed 65535");
    return {host: parsed.hostname, port};
}

function parsePositiveInteger(value, name) {
    const parsed = Number.parseInt(value, 10);
    assert(Number.isInteger(parsed) && parsed > 0, `${name} must be a positive integer`);
    return parsed;
}

function encodeString(value) {
    const bytes = Buffer.from(value, "utf8");
    return Buffer.concat([encodeVarInt(bytes.byteLength), bytes]);
}

function encodePacket(id, payload = Buffer.alloc(0)) {
    const frame = Buffer.concat([encodeVarInt(id), payload]);
    return Buffer.concat([encodeVarInt(frame.byteLength), frame]);
}

function encodeVarInt(value) {
    const bytes = [];
    value >>>= 0;
    do {
        let byte = value & 0x7f;
        value >>>= 7;
        if (value !== 0) byte |= 0x80;
        bytes.push(byte);
    } while (value !== 0);
    return Buffer.from(bytes);
}

function decodeVarInt(buffer, offset = 0) {
    let value = 0;
    for (let index = 0; index < 5; index++) {
        const byte = buffer[offset + index];
        if (byte === undefined) return undefined;
        value |= (byte & 0x7f) << (index * 7);
        if ((byte & 0x80) === 0) return {value: value >>> 0, bytesRead: index + 1};
    }
    throw new Error("Minecraft VarInt exceeds five bytes");
}

function readStatusResponse(buffer) {
    const frameLength = decodeVarInt(buffer);
    if (frameLength === undefined ||
            buffer.byteLength < frameLength.bytesRead + frameLength.value) {
        return undefined;
    }
    const frame = buffer.subarray(
        frameLength.bytesRead, frameLength.bytesRead + frameLength.value);
    const packetId = decodeVarInt(frame);
    assert(packetId !== undefined && packetId.value === 0,
        "Minecraft status response used an unexpected packet id");
    const stringLength = decodeVarInt(frame, packetId.bytesRead);
    assert(stringLength !== undefined, "Minecraft status response omitted its JSON length");
    const start = packetId.bytesRead + stringLength.bytesRead;
    assert(frame.byteLength >= start + stringLength.value,
        "Minecraft status response JSON was truncated");
    return JSON.parse(frame.subarray(start, start + stringLength.value).toString("utf8"));
}

function flattenDescription(value) {
    if (typeof value === "string") return value;
    if (Array.isArray(value)) return value.map(flattenDescription).join("");
    if (value === null || typeof value !== "object") return "";
    return String(value.text ?? "") + flattenDescription(value.extra ?? []);
}

function assert(condition, message) {
    if (!condition) throw new Error(message);
}
