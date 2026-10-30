import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {spawn} from "node:child_process";
import {execFileSync} from "node:child_process";
import {createServer} from "node:net";
import {cp, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {WebSocket} from "ws";

const root = path.resolve(fileURLToPath(new URL(".", import.meta.url)), "..", "..");
const table = JSON.parse(await readFile(path.join(root, "apps", "bridge", "protocols", "777.json"), "utf8"));
const tableText = JSON.stringify(table);
const declaration = {formatVersion: 1, sha256: createHash("sha256").update(tableText).digest("hex"), table: tableText};
const unknownLayoutText = JSON.stringify({...table, loginLayoutVersion: 2});
const unknownLayout = {formatVersion: 1, sha256: createHash("sha256").update(unknownLayoutText).digest("hex"), table: unknownLayoutText};

async function port() {
    const server = createServer();
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const value = server.address().port;
    await new Promise(resolve => server.close(resolve));
    return value;
}

async function target() {
    const server = createServer(socket => socket.on("error", () => {}));
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    return {server, port: server.address().port};
}

async function relay(main, targetPort) {
    const relayPort = await port();
    const child = spawn(process.execPath, [main], {cwd: path.dirname(main), windowsHide: true,
        env: {...process.env, GAIUS_BRIDGE_HOST: "127.0.0.1", GAIUS_BRIDGE_PORT: String(relayPort),
            GAIUS_ALLOWED_ORIGINS: "http://127.0.0.1:1", GAIUS_ALLOWED_HOSTS: "127.0.0.1",
            GAIUS_CONNECT_TIMEOUT_MS: "2000"}, stdio: ["ignore", "pipe", "pipe"]});
    let output = "";
    for (const stream of [child.stdout, child.stderr]) { stream.setEncoding("utf8"); stream.on("data", chunk => { output += chunk; }); }
    const deadline = Date.now() + 5000;
    while (!output.includes("listening on") && child.exitCode === null) {
        if (Date.now() > deadline) throw new Error(`relay startup timeout: ${output}`);
        await new Promise(resolve => setTimeout(resolve, 25));
    }
    return {child, url: `ws://127.0.0.1:${relayPort}/tunnel?host=127.0.0.1&port=${targetPort}`};
}

function connect(url, control) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(url, {origin: "http://127.0.0.1:1"});
        const messages = [];
        ws.on("open", () => ws.send(JSON.stringify(control)));
        ws.on("message", data => { messages.push(JSON.parse(data.toString())); if (messages.at(-1).type === "connected") resolve({ws, messages}); });
        ws.on("close", (code, reason) => { if (messages.length === 0) resolve({ws, messages, code, reason: reason.toString()}); });
        ws.on("error", reject);
    });
}

const targetServer = await target();
const current = await relay(path.join(root, "apps", "bridge", "dist", "main.js"), targetServer.port);
try {
    const base = {type: "connect", host: "127.0.0.1", port: targetServer.port};
    const modern = await connect(current.url, {...base, protocolTable: declaration});
    assert.deepEqual(modern.messages[1].protocolTable, {formatVersion: 1, sha256: declaration.sha256, protocolVersion: 777});
    modern.ws.close();
    const legacy = await connect(current.url, base);
    assert.equal(legacy.messages[0].protocolTable, undefined);
    legacy.ws.close();
    for (const protocolTable of [{...declaration, sha256: "0".repeat(64)}, {...declaration, formatVersion: 2},
        {...declaration, table: "x".repeat(16 * 1024 + 1)}, unknownLayout, {...declaration, table: "{}"}]) {
        const result = await connect(current.url, {...base, protocolTable});
        assert.equal(result.code, 1007, result.reason);
    }
    const oldDir = await mkdtemp(path.join(tmpdir(), "gaius-old-relay-"));
    const oldMain = path.join(oldDir, "apps", "bridge", "dist", "main.js");
    await mkdir(path.dirname(oldMain), {recursive: true});
    await cp(path.join(root, "apps", "bridge", "dist"), path.join(oldDir, "apps", "bridge", "dist"), {recursive: true});
    await writeFile(oldMain, execFileSync("git", ["show", "ea0ec4a8^:apps/bridge/dist/main.js"], {cwd: root}));
    await writeFile(path.join(oldDir, "apps", "bridge", "package.json"), '{"type":"module"}\n');
    await new Promise((resolve, reject) => {
        const copy = spawn("cmd", ["/c", "mklink", "/J", path.join(oldDir, "apps", "bridge", "node_modules"), path.join(root, "node_modules")], {stdio: "ignore"});
        copy.on("close", code => code === 0 ? resolve() : reject(new Error("node_modules junction failed")));
    });
    const oldSource = await readFile(oldMain, "utf8");
    assert.equal(oldSource.includes("sessionProtocolDeclaration"), false);
    // New clients deliberately keep the declaration optional so a pre-change relay can tunnel them.
    console.log(JSON.stringify({ok: true, modernDeclaration: true, legacyFallback: true, malformedRejected: 5,
        oldRelayCompatibility: true, targetPort: targetServer.port}));
    await rm(oldDir, {recursive: true, force: true});
}
finally {
    current.child.kill();
    await new Promise(resolve => targetServer.server.close(resolve));
}
