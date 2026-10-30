#!/usr/bin/env node
import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {createHash} from "node:crypto";
import {createReadStream, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {createServer} from "node:http";
import {createServer as netServer} from "node:net";
import {dirname, join, resolve, sep} from "node:path";
import {fileURLToPath} from "node:url";
import {setTimeout as sleep} from "node:timers/promises";
import {chromeIsolationArgs} from "./bench/harness-common.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

function defaultChromePath() {
  if (process.platform === "win32") return "C:/Program Files/Google/Chrome/Application/chrome.exe";
  if (process.platform === "darwin") return "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  return "google-chrome";
}

function option(name) {
  const index = process.argv.indexOf(`--${name}`);
  const value = process.argv[index + 1];
  assert(index >= 0 && value && !value.startsWith("--"), `--${name} is required`);
  return value;
}
const output = resolve(option("out"));
const probes = resolve(option("probes"));
const frozenDirectories = option("frozen-js-dist").split(",").map(directory => resolve(directory));
const probeReport = JSON.parse(readFileSync(join(probes, "report.json")));
assert.equal(probeReport.status, "PASS", "compile and validate probes before browser testing");
mkdirSync(output, {recursive: true});
assert(!existsSync(join(output, "report.json")), "use a fresh output directory for each run");
const profiles = readdirSync(join(root, "port/versions")).filter(name => name.endsWith(".json"))
  .map(name => JSON.parse(readFileSync(join(root, "port/versions", name))))
  .filter(profile => probeReport.profiles.some(row => row.profile === profile.id));
assert(profiles.length > 0 && profiles.length === probeReport.profiles.length, "missing selected profile coverage");
const identity = path => ({path, sha256: createHash("sha256").update(readFileSync(path)).digest("hex")});
const inputs = new Map();
for (const profile of profiles) {
  const frozen = frozenDirectories.flatMap(directory => [join(directory, `js-${profile.id}`), directory])
    .map(directory => join(directory, "singleplayer-server-worker.js")).find(path => existsSync(path));
  assert(frozen, `missing frozen JS worker for ${profile.id}`);
  const row = probeReport.profiles.find(candidate => candidate.profile === profile.id);
  inputs.set(profile.id, {profile, frozen, probe: row});
}
const report = {scope: "real Chrome IndexedDB/OPFS, vanilla RegionFile probe, fresh Worker readers and clean shutdown",
  browserWorldGatePassed: false, status: "RUNNING", profiles: [], consoleErrors: []};
const server = createServer((request, response) => {
  const path = new URL(request.url, "http://localhost").pathname;
  let file;
  if (path === "/") {
    response.setHeader("Content-Type", "text/html");
    response.end('<!doctype html><title>Region storage regression</title><script src="/host.js"></script>');
    return;
  }
  const scripts = {"/host.js": "browser-host.js", "/worker.js": "browser-worker.js", "/loader.js": "browser-loader.js"};
  if (scripts[path]) file = join(root, "port/scripts/region-storage-parity", scripts[path]);
  const bootstrap = path.match(/^\/bootstrap\/([^/]+)\/(wasm|js)\.js$/);
  if (bootstrap && inputs.has(bootstrap[1])) {
    file = bootstrap[2] === "js" ? inputs.get(bootstrap[1]).frozen
      : join(root, "port/web/singleplayer/server-worker-bootstrap.js");
  }
  const asset = path.match(/^\/probe\/([^/]+)\/(wasm|js)\/([^/]+)$/);
  if (asset && inputs.has(asset[1]) && ["classes.js", "classes.wasm", "gaius-wasm-gc-runtime.js", "gaius-jspi.js"].includes(asset[3])) {
    file = resolve(probes, asset[1], asset[2], asset[3]);
    assert(file.startsWith(probes + sep), "asset outside probe output");
  }
  if (!file || !existsSync(file)) {response.writeHead(404); response.end(); return;}
  response.setHeader("Content-Type", file.endsWith(".wasm") ? "application/wasm" : "text/javascript");
  response.setHeader("Cache-Control", "no-store");
  createReadStream(file).on("error", () => response.destroy()).pipe(response);
});
class Cdp {
  constructor(url) {
    this.socket = new WebSocket(url);
    this.nextId = 1;
    this.pending = new Map();
    this.socket.addEventListener("message", event => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const entry = this.pending.get(message.id);
        this.pending.delete(message.id);
        clearTimeout(entry.timeout);
        if (message.error) entry.reject(new Error(message.error.message));
        else entry.resolve(message.result);
      } else if (message.method === "Runtime.exceptionThrown") {
        report.consoleErrors.push(message.params.exceptionDetails);
      }
    });
    this.socket.addEventListener("close", () => {
      for (const entry of this.pending.values()) {
        clearTimeout(entry.timeout);
        entry.reject(new Error("Chrome debugging connection closed"));
      }
      this.pending.clear();
    });
  }
  open() {
    return new Promise((resolve, reject) => {
      this.socket.addEventListener("open", resolve, {once: true});
      this.socket.addEventListener("error", reject, {once: true});
    });
  }
  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {this.pending.delete(id); reject(new Error(`${method} timed out`));}, 150000);
      this.pending.set(id, {resolve, reject, timeout});
      this.socket.send(JSON.stringify({id, method, params}));
    });
  }
}
async function evaluate(cdp, expression) {
  const reply = await cdp.send("Runtime.evaluate", {expression, awaitPromise: true, returnByValue: true});
  assert(!reply.exceptionDetails, reply.exceptionDetails?.exception?.description || reply.exceptionDetails?.text);
  return reply.result.value;
}
function metrics(result, backend) {
  const match = result.summary.match(/^REGION_STORAGE_PASS regions=(\d+) chunks=(\d+) absent=(\d+) payloadBytes=(\d+) fileBytes=(\d+)$/);
  assert(match, "Java probe did not complete all assertions");
  assert.equal(result.backend, backend === "opfs" ? "opfs-sync-worker" : "indexeddb-worker-lru",
    "requested storage backend was not exercised");
  assert.equal(result.cleanStop, true);
  assert.equal(result.stats.writeErrors, 0);
  assert.equal(result.stats.rejectedWrites, 0);
  return {...Object.fromEntries(["regions", "chunks", "absent", "payloadBytes", "fileBytes"]
    .map((name, index) => [name, Number(match[index + 1])])), runtime: result.runtime,
    backend: result.backend, cleanStop: result.cleanStop, stats: result.stats};
}
let chrome;
let cdp;
let chromeLog = "";
try {
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const portReservation = netServer();
  await new Promise(resolve => portReservation.listen(0, "127.0.0.1", resolve));
  const debuggingPort = portReservation.address().port;
  await new Promise(resolve => portReservation.close(resolve));
  const chromePath = process.env.GAIUS_CHROME_BIN || process.env.CHROME_BIN || defaultChromePath();
  assert(existsSync(chromePath), `Chrome not found: ${chromePath}`);
  chrome = spawn(chromePath, ["--headless=new", "--disable-gpu", `--remote-debugging-port=${debuggingPort}`,
    `--user-data-dir=${join(output, "chrome-profile")}`, ...chromeIsolationArgs({allowNetwork: false}), "about:blank"],
  {stdio: ["ignore", "ignore", "pipe"]});
  chrome.on("error", error => {report.chromeError = String(error);});
  chrome.stderr.on("data", bytes => {chromeLog = (chromeLog + bytes).slice(-65536);});
  let tabs;
  for (let attempt = 0; attempt < 200; attempt++) {
    try {tabs = await (await fetch(`http://127.0.0.1:${debuggingPort}/json/list`)).json(); break;} catch {}
    await sleep(100);
  }
  assert(tabs, report.chromeError || "Chrome debugging endpoint did not start");
  report.chromeVersion = await (await fetch(`http://127.0.0.1:${debuggingPort}/json/version`)).json();
  cdp = new Cdp(tabs.find(tab => tab.type === "page").webSocketDebuggerUrl);
  await cdp.open();
  await cdp.send("Runtime.enable");
  await cdp.send("Page.navigate", {url: `http://127.0.0.1:${server.address().port}/`});
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await evaluate(cdp, "typeof runStorageProbe === 'function'")) break;
    await sleep(100);
  }
  for (const {profile, frozen, probe} of inputs.values()) {
    const row = {profile: profile.id, frozenWorker: identity(frozen),
      wasmWorker: identity(join(root, "port/web/singleplayer/server-worker-bootstrap.js")),
      probeInputs: probe.runtimes, probeArtifacts: Object.fromEntries(["wasm", "js"].map(runtime =>
        [runtime, identity(join(probes, profile.id, runtime, runtime === "wasm" ? "classes.wasm" : "classes.js"))])),
      directions: []};
    report.profiles.push(row);
    for (const [backend, writer, reader] of ["opfs", "indexeddb"].flatMap(backend =>
      [["wasm", "js"], ["js", "wasm"]].map(([writer, reader]) => [backend, writer, reader]))) {
      const world = `storage-parity-${backend}-${writer}-first`;
      console.log(`${profile.id} ${backend} ${writer}->${reader}: starting`);
      const writeResult = await evaluate(cdp, `runStorageProbe(${JSON.stringify({profile, runtime: writer, mode: "write", world, backend})})`);
      const writeMetrics = metrics(writeResult, backend);
      assert.equal(Object.keys(writeResult.files).length, writeMetrics.regions);
      if (backend === "opfs") {
        assert(writeMetrics.stats.opfsPatchWrites > 0, "probe bypassed OPFS patch writes");
        assert(writeMetrics.stats.opfsFlushes > 0, "probe bypassed OPFS sync flush");
      }
      const readMetrics = metrics(await evaluate(cdp, `runStorageProbe(${JSON.stringify({profile, runtime: reader, mode: "read", world, backend})})`), backend);
      if (backend === "opfs") {
        assert(readMetrics.stats.opfsReconstructedRegions >= readMetrics.regions, "reader did not reconstruct persisted regions");
      }
      const destination = join(output, profile.id, backend, `${writer}-to-${reader}`);
      mkdirSync(destination, {recursive: true});
      for (const [name, bytes] of Object.entries(writeResult.files)) writeFileSync(join(destination, name), Buffer.from(bytes, "base64"));
      row.directions.push({backend, writer, reader, write: writeMetrics, read: readMetrics});
      console.log(`${profile.id} ${backend} ${writer}->${reader}: PASS regions=${readMetrics.regions} chunks=${readMetrics.chunks} patches=${writeMetrics.stats.opfsPatchWrites}`);
    }
  }
  assert.equal(report.consoleErrors.length, 0, "browser exceptions observed");
  report.status = "PASS";
} catch (error) {
  report.status = "FAIL";
  report.error = String(error.stack || error);
  process.exitCode = 1;
  console.error(report.error.split("\n").slice(0, 4).join("\n"));
} finally {
  if (cdp) {
    await cdp.send("Browser.close").catch(() => {});
    cdp.socket.close();
  } else if (chrome) chrome.kill();
  await new Promise(resolve => server.close(resolve));
  writeFileSync(join(output, "chrome.log"), chromeLog);
  writeFileSync(join(output, "report.json"), JSON.stringify(report, null, 2) + "\n");
}
