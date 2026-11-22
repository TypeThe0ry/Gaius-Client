import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {once} from "node:events";
import {createServer} from "node:net";
import {setTimeout as delay} from "node:timers/promises";
import {fileURLToPath} from "node:url";
import {test} from "node:test";
import {WebSocket} from "ws";

const entryPoint = fileURLToPath(new URL("./test-fixtures/gaius-relay-pr1/dist/main.js", import.meta.url));
const host = "127.0.0.1";
const origin = `http://${host}:8781`;

async function listen(server) {
    server.listen(0, host);
    await once(server, "listening");
    return server.address().port;
}

async function startRelay(t, token) {
    const placeholder = createServer();
    const port = await listen(placeholder);
    await new Promise(resolve => placeholder.close(resolve));
    const relay = spawn(process.execPath, [entryPoint], {env: {
        NODE_ENV: "test",
        GAIUS_BRIDGE_HOST: host,
        GAIUS_BRIDGE_PORT: String(port),
        GAIUS_ALLOWED_ORIGINS: origin,
        GAIUS_ALLOWED_HOSTS: host,
        GAIUS_ALLOW_PRIVATE_TARGETS: "1",
        ...(token === undefined ? {} : {GAIUS_BRIDGE_TOKEN: token}),
    }});
    const closed = once(relay, "close");
    t.after(async () => {relay.kill(); await closed;});
    let log = "";
    for (const stream of [relay.stdout, relay.stderr]) stream.on("data", chunk => {log += chunk;});
    for (let i = 0; i < 100 && relay.exitCode === null; i++) {
        try {
            const response = await fetch(`http://${host}:${port}/health`, {signal: AbortSignal.timeout(250)});
            if (response.ok) return {port, health: await response.json()};
        } catch (error) {
            if (error.name !== "TypeError" && error.name !== "TimeoutError") throw error;
        }
        await delay(25);
    }
    assert.fail(`vendored relay startup failed: ${log}`);
}

async function join(t, url, control) {
    const socket = new WebSocket(url, {origin, handshakeTimeout: 2500});
    t.after(() => socket.terminate());
    const signal = AbortSignal.timeout(3000);
    // Ignore 'connecting' and wait for the TCP leg's acknowledgement.
    const connected = new Promise((resolve, reject) => {
        const aborted = () => reject(signal.reason);
        signal.addEventListener("abort", aborted, {once: true});
        socket.on("error", reject);
        socket.on("close", (code, reason) => reject(new Error(`${code}: ${reason}`)));
        socket.on("message", (raw, binary) => {
            if (binary) return;
            const message = JSON.parse(raw.toString());
            if (message.type === "connected") {
                signal.removeEventListener("abort", aborted);
                resolve(message);
            }
        });
    });
    if (control) socket.on("open", () => socket.send(JSON.stringify(control)));
    const result = await connected;
    const data = Buffer.from("race9g fixture");
    const echoed = once(socket, "message", {signal});
    socket.send(data);
    const [bytes, binary] = await echoed;
    assert.equal(binary, true);
    assert.deepEqual(bytes, data);
    socket.close();
    await once(socket, "close", {signal});
    return result;
}

test("PR #1 accepts URL connect only on a tokenless relay; legacy control still works", async t => {
    const sockets = new Set();
    const tcp = createServer(socket => {
        sockets.add(socket);
        socket.on("close", () => sockets.delete(socket));
        socket.on("error", () => socket.destroy());
        socket.pipe(socket);
    });
    const port = await listen(tcp);
    t.after(() => { for (const socket of sockets) socket.destroy(); tcp.close(); });
    const request = {type: "connect", host, port};
    const encoded = Buffer.from(JSON.stringify(request), "utf8").toString("base64url");
    assert.ok(encoded.length <= 1024);
    const tokenless = await startRelay(t);
    assert.equal(tokenless.health.requiresToken, false);
    assert.ok(tokenless.health.capabilities.includes("url-connect"));
    const base = `ws://${host}:${tokenless.port}/tunnel`;
    for (const result of [await join(t, `${base}?c=${encoded}`), await join(t, base, request)]) {
        assert.equal(result.host, host);
        assert.equal(result.port, port);
        assert.equal(result.protocolTable, undefined);
    }

    const token = "race9g-local-fixture-token";
    const protectedRelay = await startRelay(t, token);
    assert.equal(protectedRelay.health.requiresToken, true);
    assert.ok(!protectedRelay.health.capabilities.includes("url-connect"));
    const protectedUrl = `ws://${host}:${protectedRelay.port}/tunnel`;
    assert.equal((await join(t, protectedUrl, {...request, token})).port, port);
});
