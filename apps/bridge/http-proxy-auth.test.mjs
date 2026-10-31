import assert from "node:assert/strict";
import {createServer} from "node:http";
import {spawn} from "node:child_process";
import {fileURLToPath} from "node:url";

const listen = (server, host = "127.0.0.1") => new Promise(resolve => {
    server.listen(0, host, () => resolve(server.address().port));
});
const upstreamRequests = [];
const upstream = createServer((request, response) => {
    upstreamRequests.push({url: request.url, authorization: request.headers.authorization});
    response.writeHead(200, {"content-type": "application/json"});
    response.end(JSON.stringify({ok: true}));
});
const upstreamPort = await listen(upstream);
const reservation = createServer();
const bridgePort = await listen(reservation);
await new Promise(resolve => reservation.close(resolve));
const bridge = spawn(process.execPath, ["dist/main.js"], {cwd: fileURLToPath(new URL(".", import.meta.url)), env: {
    ...process.env, NODE_ENV: "test", GAIUS_BRIDGE_HOST: "127.0.0.1", GAIUS_BRIDGE_PORT: String(bridgePort),
    GAIUS_ALLOWED_ORIGINS: "http://127.0.0.1:5173", GAIUS_ALLOWED_RESOURCE_PACK_HOSTS: "127.0.0.1",
    GAIUS_ALLOW_PRIVATE_TARGETS: "1", GAIUS_BRIDGE_TOKEN: "bridge-secret",
}, stdio: ["ignore", "pipe", "pipe"]});
let output = "";
bridge.stdout.on("data", chunk => { output += chunk; });
bridge.stderr.on("data", chunk => { output += chunk; });
const target = `http://127.0.0.1:${upstreamPort}/session/profile`;
const proxy = (extra = "") => `http://127.0.0.1:${bridgePort}/proxy/auth?url=${encodeURIComponent(target)}${extra}`;
try {
    const deadline = Date.now() + 5000;
    while (!output.includes("Gaius translator node listening")) {
        assert.ok(Date.now() < deadline, output);
        await new Promise(resolve => setTimeout(resolve, 20));
    }
    const common = {origin: "http://127.0.0.1:5173", authorization: "Bearer minecraft-access-token"};
    const online = await fetch(proxy(), {headers: {...common, "x-gaius-bridge-token": "bridge-secret"}});
    assert.equal(online.status, 200, await online.text());
    assert.equal(upstreamRequests.at(-1).authorization, "Bearer minecraft-access-token");
    const legacy = await fetch(proxy("&token=bridge-secret"), {headers: {origin: common.origin}});
    assert.equal(legacy.status, 200);
    assert.equal(upstreamRequests.at(-1).url, "/session/profile");
    const noLegacyToken = await fetch(proxy(), {headers: {origin: common.origin}});
    assert.equal(noLegacyToken.status, 403);
    const duplicateGoodThenWrong = await fetch(proxy("&token=bridge-secret&token=wrong"), {headers: {origin: common.origin}});
    assert.equal(duplicateGoodThenWrong.status, 403);
    const duplicateWrongThenGood = await fetch(proxy("&token=wrong&token=bridge-secret"), {headers: {origin: common.origin}});
    assert.equal(duplicateWrongThenGood.status, 403);
    const headerPreferred = await fetch(proxy("&token=wrong-query"), {headers: {...common, "x-gaius-bridge-token": "bridge-secret"}});
    assert.equal(headerPreferred.status, 200);
    const headerWithDuplicateQuery = await fetch(proxy("&token=wrong-query&token=another-wrong-query"), {headers: {...common, "x-gaius-bridge-token": "bridge-secret"}});
    assert.equal(headerWithDuplicateQuery.status, 200);
    const wrongHeaderWins = await fetch(proxy("&token=bridge-secret"), {headers: {...common, "x-gaius-bridge-token": "wrong-header"}});
    assert.equal(wrongHeaderWins.status, 403);
    console.log("HTTP proxy auth contract tests passed");
} finally {
    const waitForExit = child => new Promise(resolve => {
        if (child.exitCode !== null || child.signalCode !== null) {
            resolve();
            return;
        }
        const timer = setTimeout(resolve, 2000);
        child.once("exit", () => {
            clearTimeout(timer);
            resolve();
        });
    });
    const exited = waitForExit(bridge);
    if (bridge.exitCode === null && !bridge.killed) {
        bridge.kill();
    }
    await exited;
    if (bridge.exitCode === null) bridge.kill("SIGKILL");
    await waitForExit(bridge);
    upstream.closeAllConnections();
    await new Promise(resolve => upstream.close(resolve));
}
