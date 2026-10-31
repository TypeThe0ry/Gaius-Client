#!/usr/bin/env node
// Post-deployment smoke for a Gaius RelayNode (v0.4.0 session-protocol relay).
//
// Usage:
//   node apps/bridge/relay-deploy-smoke.mjs <relay-url> [options]
//
// <relay-url> is the public base (https://host or wss://host/tunnel; ws/http for
// a local relay). The script needs no Minecraft server: it checks /health, the
// browser-visible CORS contract, the numeric protocol tables, the phase-2
// session declaration handshake (valid, tampered and absent), and a LAN
// (*.gaius-local) tunnel pair. The one step that needs an outbound TCP target
// is the declaration receipt, because the relay only answers `connected` after
// its TCP dial succeeds; by default the target is the relay's own listener
// (host and port taken from <relay-url>). Pass --target when the relay cannot
// dial its own public name.
//
// Options:
//   --origin <o>                  repeatable; default https://typethe0ry.github.io, null and
//                                 file:// (Chrome sends file:// for the portable page)
//   --expect-wildcard              expect every supplied browser Origin to be accepted
//   --target <host:port>          TCP target for the receipt checks
//   --skip-receipt                skip checks that need an outbound TCP dial
//   --expect-private-targets <true|false>   default false (public node)
//   --private-probe <host:port,...>         loopback probes for the guard check
//   --protocols <774,776,777>     numeric tables that must be loaded and declared
//   --protocols-dir <dir>         table directory (default: ./protocols next to this file)
//   --token <token>               relay token (or GAIUS_SMOKE_TOKEN); never printed
//   --timeout-ms <n>              per-step timeout (default 15000)
//
// Exit status: 0 when every check passes (warnings allowed), 1 otherwise. The
// final stdout line is a JSON summary without the relay address.

import {randomBytes} from "node:crypto";
import {readFileSync} from "node:fs";
import {EventEmitter} from "node:events";
import {request as httpRequest} from "node:http";
import {request as httpsRequest} from "node:https";
import {join} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {createSessionProtocolDeclaration} from "./dist/session-protocol.js";

const DEFAULT_PAGES_ORIGIN = "https://typethe0ry.github.io";
const REJECTED_ORIGIN = "https://origin-not-allowed.invalid";
const REQUIRED_CAPABILITIES = ["tcp-tunnel", "target-attestation", "session-protocol-table-v1", "runtime-telemetry"];
const REQUIRED_RUNTIME_COUNTERS = [
    "activeLocalTunnelSessions", "pendingSyntheticPlayTicks", "activeServerFrameDrainHandles",
    "activeServerFrameDrainTimers", "activeClientStallTimers", "activeTunnelLeases",
    "activeTransportWebSockets", "rssBytes", "cpuUserMicros", "cpuSystemMicros",
];

let WebSocket;
async function loadWebSocket() {
    if (WebSocket !== undefined) return WebSocket;
    try {
        ({WebSocket} = await import("ws"));
    } catch {
        throw new Error("the 'ws' package is not installed; run `npm ci --omit=dev` in apps/bridge " +
            "(or copy a prepared apps/bridge/node_modules next to this script)");
    }
    return WebSocket;
}

export function parseArguments(argv) {
    const options = {
        origins: [], expectPrivateTargets: false, expectWildcard: false,
        protocols: [774, 776, 777], timeoutMs: 15000,
        skipReceipt: false, privateProbes: ["127.0.0.1:8080", "127.0.0.1:18080"],
        protocolsDirectory: fileURLToPath(new URL("./protocols/", import.meta.url)),
        token: process.env.GAIUS_SMOKE_TOKEN || undefined,
    };
    const positional = [];
    for (let index = 0; index < argv.length; index++) {
        const argument = argv[index];
        const value = () => {
            if (index + 1 >= argv.length) throw new Error(`${argument} needs a value`);
            return argv[++index];
        };
        switch (argument) {
            case "--origin": options.origins.push(value()); break;
            case "--expect-wildcard": options.expectWildcard = true; break;
            case "--target": options.target = value(); break;
            case "--skip-receipt": options.skipReceipt = true; break;
            case "--expect-private-targets": {
                const text = value();
                if (text !== "true" && text !== "false") throw new Error("--expect-private-targets must be true or false");
                options.expectPrivateTargets = text === "true";
                break;
            }
            case "--private-probe": options.privateProbes = value().split(",").map(item => item.trim()).filter(Boolean); break;
            case "--protocols": options.protocols = value().split(",").map(item => Number(item.trim())); break;
            case "--protocols-dir": options.protocolsDirectory = value(); break;
            case "--token": options.token = value(); break;
            case "--timeout-ms": options.timeoutMs = Number(value()); break;
            default:
                if (argument.startsWith("--")) throw new Error(`unknown option ${argument}`);
                positional.push(argument);
        }
    }
    if (positional.length !== 1) throw new Error("exactly one <relay-url> is required");
    if (options.protocols.some(item => !Number.isInteger(item) || item < 0)) throw new Error("--protocols must be numeric");
    if (!Number.isInteger(options.timeoutMs) || options.timeoutMs < 500) throw new Error("--timeout-ms must be at least 500");
    if (options.origins.length === 0) {
        options.origins = options.expectWildcard
            ? ["https://gaius.fun", DEFAULT_PAGES_ORIGIN, "https://random-origin.invalid", "null", "file://"]
            : [DEFAULT_PAGES_ORIGIN, "null", "file://"];
    }
    options.relayUrl = positional[0];
    return options;
}

export function deriveEndpoints(relayUrl) {
    const url = new URL(relayUrl);
    const secure = url.protocol === "https:" || url.protocol === "wss:";
    if (!["http:", "https:", "ws:", "wss:"].includes(url.protocol)) throw new Error("relay URL must be http(s) or ws(s)");
    if (url.pathname !== "/" && url.pathname !== "/tunnel") throw new Error("relay URL path must be empty or /tunnel");
    if (url.username || url.password || url.search || url.hash) throw new Error("relay URL must not carry credentials or a query");
    const port = Number(url.port || (secure ? 443 : 80));
    return {
        httpBase: `${secure ? "https" : "http"}://${url.host}`,
        tunnelUrl: `${secure ? "wss" : "ws"}://${url.host}/tunnel`,
        defaultTarget: {host: url.hostname.replace(/^\[|\]$/gu, ""), port},
    };
}

function parseTarget(text) {
    const url = new URL(`minecraft://${text}`);
    const port = Number(url.port);
    if (!url.hostname || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`invalid target ${text}`);
    return {host: url.hostname.replace(/^\[|\]$/gu, ""), port};
}

function httpGet(base, path, {origin, token, timeoutMs, method = "GET"}) {
    const url = new URL(path, base);
    const send = url.protocol === "https:" ? httpsRequest : httpRequest;
    return new Promise((resolve, reject) => {
        const headers = {accept: "application/json"};
        if (origin !== undefined) headers.origin = origin;
        if (token !== undefined) headers.authorization = `Bearer ${token}`;
        const request = send(url, {headers, method}, response => {
            const chunks = [];
            let bytes = 0;
            response.on("data", chunk => {
                bytes += chunk.byteLength;
                if (bytes > 1024 * 1024) request.destroy(new Error("response exceeds 1 MiB"));
                else chunks.push(chunk);
            });
            response.on("end", () => resolve({status: response.statusCode, headers: response.headers,
                text: Buffer.concat(chunks).toString("utf8")}));
        });
        request.setTimeout(timeoutMs, () => request.destroy(new Error(`timed out after ${timeoutMs} ms`)));
        request.once("error", reject);
        request.end();
    });
}

async function openTunnel(url, origin, timeoutMs) {
    const Socket = await loadWebSocket();
    const state = Object.assign(new EventEmitter(), {controls: [], binaries: [], open: false, closed: false});
    state.socket = new Socket(url, {origin, handshakeTimeout: timeoutMs});
    state.socket.on("open", () => { state.open = true; state.emit("change"); });
    state.socket.on("message", (data, isBinary) => {
        if (isBinary) state.binaries.push(Buffer.from(data));
        else {
            try { state.controls.push(JSON.parse(data.toString("utf8"))); } catch { /* ignored: not a control frame */ }
        }
        state.emit("change");
    });
    state.socket.on("unexpected-response", (_request, response) => {
        state.upgradeStatus = response.statusCode;
        response.resume();
        state.emit("change");
    });
    state.socket.on("close", (code, reason) => {
        state.closed = true;
        state.closeCode = code;
        state.closeReason = reason.toString("utf8");
        state.emit("change");
    });
    state.socket.on("error", error => { state.error = error.message; state.emit("change"); });
    return state;
}

function until(state, predicate, timeoutMs) {
    return new Promise(resolve => {
        const done = result => { clearTimeout(timer); state.off("change", check); resolve(result); };
        const check = () => { if (predicate(state)) done(true); else if (state.closed || state.upgradeStatus !== undefined) done(false); };
        const timer = setTimeout(() => done(predicate(state)), timeoutMs);
        state.on("change", check);
        check();
    });
}

async function finish(state) {
    if (!state.closed) {
        state.socket.terminate();
        await until(state, current => current.closed, 2000);
    }
}

const connectedControl = state => state.controls.find(control => control.type === "connected");

function connectFrame(target, token, protocolTable) {
    return JSON.stringify({type: "connect", host: target.host, port: target.port,
        ...(token === undefined ? {} : {token}), ...(protocolTable === undefined ? {} : {protocolTable})});
}

export async function runDeploySmoke(options) {
    const {httpBase, tunnelUrl, defaultTarget} = deriveEndpoints(options.relayUrl);
    const target = options.target === undefined ? defaultTarget : parseTarget(options.target);
    const [pagesOrigin] = options.origins;
    const results = [];
    const record = (name, status, detail = "") => {
        results.push({name, status, detail});
        console.log(`${status.padEnd(4)} ${name}${detail ? ` - ${detail}` : ""}`);
    };
    const check = async (name, body) => {
        try {
            const outcome = await body();
            if (outcome === undefined) record(name, "PASS");
            else record(name, outcome.status ?? "PASS", outcome.detail ?? "");
        } catch (error) {
            record(name, "FAIL", error instanceof Error ? error.message : String(error));
        }
    };
    const need = (condition, message) => { if (!condition) throw new Error(message); };
    const get = (path, origin, method) => httpGet(httpBase, path,
        {origin, token: options.token, timeoutMs: options.timeoutMs, method});
    const receipts = {};

    // Declarations are built exactly as the browser build does (port/scripts/build-relay-protocol.mjs).
    const declarations = new Map();
    for (const protocol of options.protocols) {
        const table = JSON.parse(readFileSync(join(options.protocolsDirectory, `${protocol}.json`), "utf8"));
        declarations.set(protocol, createSessionProtocolDeclaration(table));
    }

    let health;
    await check("health.ok", async () => {
        const response = await get("/health", pagesOrigin);
        need(response.status === 200, `GET /health returned HTTP ${response.status}`);
        health = JSON.parse(response.text);
        need(health.ok === true && health.kind === "gaius-relay-node", "not a Gaius RelayNode health response");
        need(health.protocolVersion === 1 && health.tunnelPath === "/tunnel", "unexpected RelayNode protocolVersion or tunnelPath");
        return {detail: `name="${health.name}" active=${health.activeConnections}/${health.maximumConnections}`};
    });
    if (health === undefined) return finalize(results, receipts);

    await check("health.capabilities", async () => {
        const missing = REQUIRED_CAPABILITIES.filter(item => !health.capabilities?.includes(item));
        need(missing.length === 0, `missing capabilities: ${missing.join(", ")} (relay older than v0.4.0?)`);
        return {detail: `${health.capabilities.length} capabilities`};
    });
    await check("health.runtime", async () => {
        const runtime = health.runtime;
        need(runtime && typeof runtime === "object", "runtime block missing");
        const bad = REQUIRED_RUNTIME_COUNTERS.filter(key => !Number.isSafeInteger(runtime[key]) || runtime[key] < 0);
        need(bad.length === 0, `runtime counters missing or invalid: ${bad.join(", ")}`);
        return {detail: `uptimeMillis=${runtime.uptimeMillis} rssBytes=${runtime.rssBytes}`};
    });
    await check("health.protocol-tables", async () => {
        // The relay exports one profilesSelected<protocol> counter per loaded numeric table.
        const loaded = Object.keys(health.runtime ?? {}).map(key => /^profilesSelected(\d+)$/u.exec(key))
            .filter(Boolean).map(match => Number(match[1])).sort((a, b) => a - b);
        const missing = options.protocols.filter(protocol => !loaded.includes(protocol));
        need(missing.length === 0, `numeric tables not loaded: ${missing.join(", ")} (loaded: ${loaded.join(", ") || "none"})`);
        return {detail: `loaded ${loaded.join(", ")}`};
    });
    await check("health.token-policy", async () => {
        const expected = options.token !== undefined;
        need(health.requiresToken === expected,
            `requiresToken is ${health.requiresToken}, expected ${expected}`);
    });
    await check("health.private-target-policy", async () => {
        need(health.allowsPrivateTargets === options.expectPrivateTargets,
            `allowsPrivateTargets is ${health.allowsPrivateTargets}, expected ${options.expectPrivateTargets}`);
    });
    await check("health.registration", async () => {
        const registration = health.registration ?? {};
        return {status: "PASS", detail: registration.configured
            ? `configured registered=${registration.registered} failures=${registration.failures}` : "not configured"};
    });
    const baselineLeases = health.runtime?.activeTunnelLeases ?? 0;

    await check("manifest.v1", async () => {
        const response = await get("/relay-node/v1", pagesOrigin);
        need(response.status === 200, `GET /relay-node/v1 returned HTTP ${response.status}`);
        const manifest = JSON.parse(response.text);
        need(manifest.protocolVersion === 1 && manifest.capabilities?.includes("session-protocol-table-v1"),
            "manifest does not advertise session-protocol-table-v1");
    });
    await check("manifest.runtime-endpoint", async () => {
        const response = await get("/relay-node/v1.runtime", pagesOrigin);
        need(response.status === 200, `GET /relay-node/v1.runtime returned HTTP ${response.status}`);
    });

    await check("http.missing-origin", async () => {
        const response = await get("/health", undefined);
        need(response.status === 200, `GET /health without Origin returned HTTP ${response.status}`);
        need(response.headers["access-control-allow-origin"] === undefined,
            "missing Origin unexpectedly received an ACAO header");
    });
    await check("proxy.missing-origin", async () => {
        const response = await get("/proxy/auth", undefined);
        need(response.status === 403, `GET /proxy/auth without Origin returned HTTP ${response.status}, expected 403`);
    });
    await check("ws.missing-origin", async () => {
        const state = await openTunnel(tunnelUrl, undefined, options.timeoutMs);
        try {
            await until(state, current => current.upgradeStatus !== undefined || current.open || current.closed, options.timeoutMs);
            need(!state.open && state.upgradeStatus === 403,
                `missing Origin was not refused with 403 (${state.upgradeStatus ?? "upgraded"})`);
        } finally { await finish(state); }
    });

    for (const origin of options.origins) {
        await check(`cors.allowed[${origin}]`, async () => {
            for (const [path, method, expectedStatus] of [
                ["/health", "GET", 200], ["/relay-node/v1", "GET", 200],
                ["/proxy/auth", "OPTIONS", 204], ["/proxy/auth", "GET", 400],
            ]) {
                const response = await get(path, origin, method);
                need(response.status === expectedStatus,
                    `${method} ${path} returned HTTP ${response.status}, expected ${expectedStatus}`);
                need(response.headers["access-control-allow-origin"] === origin,
                    `${path} ACAO is ${response.headers["access-control-allow-origin"]}`);
                need(response.headers.vary?.split(/,\s*/u).some(value => value.toLowerCase() === "origin"),
                    `${path} is missing Vary: Origin`);
                need(response.headers["access-control-allow-credentials"] === undefined,
                    `${path} unexpectedly enables credentialed CORS`);
            }
        });
        await check(`ws.origin-accepted[${origin}]`, async () => {
            const state = await openTunnel(tunnelUrl, origin, options.timeoutMs);
            try {
                need(await until(state, current => current.open, options.timeoutMs),
                    `WebSocket upgrade failed (${state.upgradeStatus ?? state.error ?? "no response"})`);
            } finally { await finish(state); }
        });
    }
    if (options.expectWildcard) {
        await check("cors.wildcard-random-origin", async () => {
            const response = await get("/health", REJECTED_ORIGIN);
            need(response.status === 200, `random origin got HTTP ${response.status}, expected 200`);
            need(response.headers["access-control-allow-origin"] === REJECTED_ORIGIN,
                `random origin ACAO is ${response.headers["access-control-allow-origin"]}`);
        });
        await check("ws.wildcard-random-origin", async () => {
            const state = await openTunnel(tunnelUrl, REJECTED_ORIGIN, options.timeoutMs);
            try {
                need(await until(state, current => current.open, options.timeoutMs),
                    `random origin WebSocket upgrade failed (${state.upgradeStatus ?? state.error ?? "no response"})`);
            } finally { await finish(state); }
        });
    }
    else {
        await check("cors.rejected-origin", async () => {
            const response = await get("/health", REJECTED_ORIGIN);
            need(response.status === 403, `unlisted origin got HTTP ${response.status}, expected 403`);
        });
        await check("ws.origin-rejected", async () => {
            const state = await openTunnel(tunnelUrl, REJECTED_ORIGIN, options.timeoutMs);
            try {
                await until(state, current => current.upgradeStatus !== undefined || current.open || current.closed, options.timeoutMs);
                need(!state.open && state.upgradeStatus === 403, `unlisted origin was not refused with 403 (${state.upgradeStatus ?? "upgraded"})`);
            } finally { await finish(state); }
        });
    }
    await check("lan.host-policy", async () => {
        const host = `lan-server-${randomBytes(16).toString("hex")}.gaius-local`;
        const response = await get(`/relay-node/v1?host=${host}&port=25565`, pagesOrigin);
        need(response.status === 200,
            `target query for a *.gaius-local host returned HTTP ${response.status}; GAIUS_ALLOWED_HOSTS must include *.gaius-local (or *)`);
    });

    // Phase-2 declaration handling that needs no outbound TCP dial.
    await check("ws.tampered-declaration", async () => {
        const [protocol] = options.protocols;
        const declaration = declarations.get(protocol);
        const flipped = declaration.sha256.slice(0, -1) + (declaration.sha256.endsWith("0") ? "1" : "0");
        const state = await openTunnel(tunnelUrl, pagesOrigin, options.timeoutMs);
        try {
            need(await until(state, current => current.open, options.timeoutMs), "WebSocket did not open");
            state.socket.send(connectFrame(target, options.token, {...declaration, sha256: flipped}));
            await until(state, current => current.closed, options.timeoutMs);
            need(connectedControl(state) === undefined, "relay accepted a declaration whose hash does not match (phase-1 relay)");
            need(state.closeCode === 1007, `close code ${state.closeCode}, expected 1007`);
        } finally { await finish(state); }
    });
    await check("lan.tunnel-pair", async () => {
        const session = randomBytes(16).toString("hex");
        const server = await openTunnel(tunnelUrl, pagesOrigin, options.timeoutMs);
        const client = await openTunnel(tunnelUrl, pagesOrigin, options.timeoutMs);
        try {
            need(await until(server, current => current.open, options.timeoutMs) &&
                await until(client, current => current.open, options.timeoutMs), "WebSocket did not open");
            server.socket.send(connectFrame({host: `lan-server-${session}.gaius-local`, port: 25565}, options.token));
            client.socket.send(connectFrame({host: `client-${session}.gaius-local`, port: 25565}, options.token));
            need(await until(server, current => connectedControl(current) !== undefined, options.timeoutMs) &&
                await until(client, current => connectedControl(current) !== undefined, options.timeoutMs),
                "LAN tunnel pair did not receive `connected` on both sides");
            const toServer = randomBytes(24);
            const toClient = randomBytes(24);
            client.socket.send(toServer);
            server.socket.send(toClient);
            need(await until(server, current => current.binaries.length > 0, options.timeoutMs) &&
                await until(client, current => current.binaries.length > 0, options.timeoutMs), "LAN tunnel did not forward bytes");
            need(Buffer.concat(server.binaries).equals(toServer) && Buffer.concat(client.binaries).equals(toClient),
                "LAN tunnel forwarded different bytes");
        } finally { await finish(server); await finish(client); }
    });

    if (options.skipReceipt) {
        record("ws.receipts", "SKIP", "--skip-receipt");
    } else {
        const dial = async protocolTable => {
            const state = await openTunnel(tunnelUrl, pagesOrigin, options.timeoutMs);
            try {
                need(await until(state, current => current.open, options.timeoutMs), "WebSocket did not open");
                state.socket.send(connectFrame(target, options.token, protocolTable));
                await until(state, current => connectedControl(current) !== undefined, options.timeoutMs);
                const connected = connectedControl(state);
                need(connected !== undefined,
                    `no \`connected\` (closed ${state.closeCode ?? "open"} ${state.closeReason}); the relay could not dial its target — pass --target <public host:port>`);
                return connected;
            } finally { await finish(state); }
        };
        await check("ws.legacy-connect", async () => {
            const connected = await dial();
            need(connected.protocolTable === undefined, "connected carried a receipt although no declaration was sent");
            need(typeof connected.candidateHost === "string" && Number.isInteger(connected.remotePort), "connected lacks target attestation fields");
        });
        for (const protocol of options.protocols) {
            const declaration = declarations.get(protocol);
            await check(`ws.receipt[${protocol}]`, async () => {
                const connected = await dial(declaration);
                const receipt = connected.protocolTable;
                need(receipt !== undefined, "connected has no protocolTable receipt (relay does not implement session-protocol-table-v1)");
                need(receipt.formatVersion === declaration.formatVersion && receipt.sha256 === declaration.sha256 &&
                    receipt.protocolVersion === protocol,
                    `receipt mismatch: ${JSON.stringify(receipt)}`);
                receipts[protocol] = declaration.sha256;
                return {detail: `sha256=${declaration.sha256.slice(0, 16)}...`};
            });
        }
    }

    if (!options.expectPrivateTargets) {
        await check("private.loopback-probe", async () => {
            for (const probe of options.privateProbes) {
                const probeTarget = parseTarget(probe);
                const state = await openTunnel(tunnelUrl, pagesOrigin, options.timeoutMs);
                try {
                    need(await until(state, current => current.open, options.timeoutMs), "WebSocket did not open");
                    state.socket.send(connectFrame(probeTarget, options.token));
                    await until(state, current => connectedControl(current) !== undefined || current.closed, options.timeoutMs);
                    need(connectedControl(state) === undefined, `relay dialed loopback ${probe}; GAIUS_ALLOW_PRIVATE_TARGETS must be 0`);
                } finally { await finish(state); }
            }
            return {detail: `${options.privateProbes.length} loopback probe(s) refused`};
        });
    }

    await check("lease.release", async () => {
        const deadline = Date.now() + 5000;
        let leases = Infinity;
        while (Date.now() < deadline) {
            leases = JSON.parse((await get("/health", pagesOrigin)).text).runtime?.activeTunnelLeases;
            if (leases <= baselineLeases) return {detail: `activeTunnelLeases=${leases}`};
            await new Promise(resolve => setTimeout(resolve, 250));
        }
        return {status: "WARN", detail: `activeTunnelLeases=${leases} above baseline ${baselineLeases} (other traffic or a leak)`};
    });
    return finalize(results, receipts);
}

function finalize(results, receipts) {
    const failures = results.filter(item => item.status === "FAIL").length;
    const summary = {ok: failures === 0, pass: results.filter(item => item.status === "PASS").length,
        fail: failures, warn: results.filter(item => item.status === "WARN").length,
        skip: results.filter(item => item.status === "SKIP").length, receipts, results};
    return summary;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    let options;
    try {
        options = parseArguments(process.argv.slice(2));
    } catch (error) {
        console.error(`relay-deploy-smoke: ${error.message}`);
        console.error("usage: node relay-deploy-smoke.mjs <relay-url> [--origin o]... [--target host:port] " +
            "[--skip-receipt] [--expect-private-targets true|false] [--protocols 774,776,777] [--timeout-ms n]");
        process.exit(2);
    }
    try {
        const summary = await runDeploySmoke(options);
        console.log(JSON.stringify(summary));
        process.exitCode = summary.ok ? 0 : 1;
    } catch (error) {
        console.error(`relay-deploy-smoke: ${error.message}`);
        process.exitCode = 1;
    }
}
