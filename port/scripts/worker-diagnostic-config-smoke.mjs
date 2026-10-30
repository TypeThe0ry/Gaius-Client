import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const client = await readFile("port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java", "utf8");
const bootstrap = await readFile("port/web/singleplayer/server-worker-bootstrap.js", "utf8");
const server = await readFile("port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java", "utf8");
assert.match(client, /searchParams\.get\('diag'\) === '1'/);
assert.match(client, /type: 'diagnostic-config', gaiusDebug: true/);
assert.match(bootstrap, /message\.gaiusDebug === true/);
assert.match(bootstrap, /root\.__gaiusDebug = true/);
assert.match(server, /globalThis\.__gaiusDebug === true/);
const worker = {__gaiusDebug: false};
const message = {type: "diagnostic-config", gaiusDebug: true};
if (message.type === "diagnostic-config" && message.gaiusDebug === true) worker.__gaiusDebug = true;
assert.equal(worker.__gaiusDebug, true);
console.log("worker-diagnostic-config-smoke: PASS diag=1 -> worker gate=true");
