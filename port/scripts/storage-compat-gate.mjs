#!/usr/bin/env node
// Storage compatibility gate of the two runtimes (v0.4.0): the WebAssembly GC
// build and the frozen TeaVM JavaScript build of a profile share one browser store (IndexedDB
// database, key prefix and OPFS directory of port/versions/<id>.json "storage"), so a world one
// of them saved must load in the other.
//
//   node port/scripts/storage-compat-gate.mjs --profile 26.3 --wasm <wasm dist> --js <js dist>
//        [--direction wasm-to-js,js-to-wasm] [--out <dir>] [--seed 26300]
//        [--wasm-query runtime=wasm&serverRuntime=wasm] [--js-query ""]
//   node port/scripts/storage-compat-gate.mjs --profile 26.3 --site <staged site> --out <dir>
//   node port/scripts/storage-compat-gate.mjs --profile 26.3 --artifact <portable HTML> --out <dir>
// Same-page modes change only runtime query parameters and keep the Chrome profile until both
// halves finish; the harness deletes it afterwards. The two-dist mode also supports older saves.
//   node port/scripts/storage-compat-gate.mjs --self-test
//
// Both dists are served from one origin (http://127.0.0.1:<port>/wasm/ and /js/), so they see the
// same storage. Per direction, minecraft-263-singleplayer-cdp.mjs runs in a fresh Chrome profile:
// the writer page creates a world (fixed seed, creative), moves, breaks and places a block, saves
// and quits (the server Worker reports "stopped" after flushing); the harness then navigates to
// the reader page (REENTER=reload with REENTER_PAGE_URL) and re-enters the world from the world
// list there. The gate requires the persistence checks of that run (world listed, chunks reload,
// player position and placed block persisted, top-down terrain within tolerance) and runtime
// evidence for both halves: the client runtime the page selected and the runtime the live server
// Worker reports in its pong must be the writer's before Save and Quit and the reader's after
// re-entry. The server Workers of both halves must also log no region file error (a chunk whose
// header or stream is truncated or missing, a chunk stored at another position, a failed chunk
// load or store): vanilla recovers from those by regenerating the chunk, so a world can pass the
// persistence checks and still lose terrain. Evidence: <out>/<direction>/ (report.json,
// console.log, screenshots) and <out>/storage-compat-gate.json. Exit status: 0 pass, 1 gate
// failure, 2 usage error.
import {spawn} from "node:child_process";
import {createReadStream, existsSync, readFileSync, statSync} from "node:fs";
import {mkdir, writeFile} from "node:fs/promises";
import {createServer} from "node:http";
import {dirname, extname, isAbsolute, join, normalize, relative, resolve} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const harness = join(here, "minecraft-263-singleplayer-cdp.mjs");

// The checks of the harness that prove the world crossed from the writer to the reader.
export const REQUIRED_CHECKS = Object.freeze([
  "worldEntered", "placesBlock", "saveAndQuit", "worldListed", "chunksReload",
  "playerPositionPersisted", "placedBlockPersisted", "terrainBaseline", "noUncaughtExceptions",
]);

// Rendering diagnostics of the harness that say nothing about whether a saved world is readable.
export const RENDER_ONLY_CHECKS = new Set(["terrainRendered", "gpuBackend", "gpuBackendMatches",
  "noSyncGlCalls", "noShaderErrors", "noWebGLErrors", "noWebGpuErrors"]);

// Region file errors of the vanilla RegionFile / ChunkStorage / ChunkMap code in a server Worker
// console line.
export const STORAGE_ERROR = /Chunk \[-?\d+, ?-?\d+\] (?:header is truncated|stream is truncated|is allocated, but stream is missing)|Chunk found in invalid location|Failed to (?:load|store) chunk|Couldn't load chunk|Failed to read chunk/;

/** Counts the region file errors of a harness console.log (Worker lines only, each once). */
export function storageErrors(consoleText) {
  const lines = consoleText.split(/\r?\n/).filter((line) => /(?:\[worker\]|\bworker\b)/.test(line)
    && STORAGE_ERROR.test(line));
  // The harness records a Worker line as console.*[worker] and again as log.* worker.
  const distinct = [...new Set(lines.map((line) => line.replace(/^\s*[\d.]+\s+\S+\s+(?:worker\s+)?/, "")
    .replace(/\s+blob:\S+$/, "").trim()))];
  return {count: distinct.length, samples: distinct.slice(0, 8)};
}

const RUNTIMES = Object.freeze({
  wasm: {client: "wasm", server: "wasm"},
  js: {client: "js", server: "js"},
});

function usage(message) {
  if (message) console.error(`storage-compat-gate: ${message}`);
  console.error("usage: storage-compat-gate.mjs --profile <id> --wasm <dist> --js <dist> "
    + "[--direction wasm-to-js,js-to-wasm] [--out <dir>] [--seed <n>] [--wasm-query <q>] [--js-query <q>] | --self-test");
  process.exit(2);
}

/**
 * The verdict of one direction from the harness report: required checks, then the runtime
 * evidence of the writer half (report.runtimes.world) and the reader half (.reentered).
 */
export function evaluateDirection(report, writer, reader, consoleText = "", harnessExit = null) {
  const problems = [];
  if (!report) return {pass: false, problems: ["no report.json"]};
  // The shared harness also reports rendering-only checks (terrain screenshot, GL/WebGPU backend,
  // shader and sync-readback diagnostics), which are outside this storage gate; the failed ones
  // stay listed in the summary. Only its fatal error, the other checks and the storage checks
  // below affect this result, so a child may exit 1 for a graphics check after valid evidence.
  const unrelated = RENDER_ONLY_CHECKS;
  const failedChecks = Object.entries(report.checks || {}).filter(([, value]) => value.ok !== true).map(([name]) => name);
  for (const name of failedChecks) if (!unrelated.has(name) && !REQUIRED_CHECKS.includes(name)) problems.push(`harness check ${name} failed`);
  if (harnessExit !== null && harnessExit !== 0 && !(harnessExit === 1 && failedChecks.length && failedChecks.every(name => unrelated.has(name)))) {
    problems.push(`harness exited with ${harnessExit}`);
  }
  if (report.verdict !== "PASS" && !failedChecks.length) problems.push(`harness verdict ${report.verdict ?? "missing"} without check evidence`);
  if (report.error) problems.push(`harness error: ${String(report.error).split("\n")[0]}`);
  const checks = report.checks || {};
  for (const name of REQUIRED_CHECKS) {
    const check = checks[name];
    if (!check) problems.push(`check ${name} did not run`);
    else if (check.ok !== true) problems.push(`check ${name} failed`);
  }
  const storage = storageErrors(consoleText);
  if (storage.count) {
    problems.push(`${storage.count} region file error(s) in the server Workers, e.g. ${storage.samples.slice(0, 2).join(" | ")}`);
  }
  const runtimes = report.runtimes || {};
  for (const [half, expected, evidence] of [["writer", RUNTIMES[writer], runtimes.world],
    ["reader", RUNTIMES[reader], runtimes.reentered]]) {
    if (!evidence || evidence.error) {
      problems.push(`${half}: no runtime evidence${evidence?.error ? ` (${evidence.error})` : ""}`);
      continue;
    }
    if (evidence.client !== expected.client) {
      problems.push(`${half}: client runtime ${JSON.stringify(evidence.client ?? null)} instead of ${expected.client}`);
    }
    const server = evidence.server;
    if (!server || server.timeout || server.error || server.runtime !== expected.server) {
      problems.push(`${half}: server runtime ${JSON.stringify(server ?? null)} instead of ${expected.server}`);
    }
    if (report.storageGatePortable && evidence.storage?.backend !== "indexeddb-worker-lru") {
      problems.push(`${half}: portable storage backend ${JSON.stringify(evidence.storage?.backend ?? null)} instead of indexeddb-worker-lru`);
    }
  }
  return {pass: problems.length === 0, problems};
}

function selfTest() {
  const okChecks = Object.fromEntries(REQUIRED_CHECKS.map((name) => [name, {ok: true}]));
  const evidence = (runtime) => ({client: runtime, server: {runtime}});
  const good = {verdict: "PASS", checks: okChecks, runtimes: {world: evidence("wasm"), reentered: evidence("js")}};
  const assert = (condition, message) => { if (!condition) throw new Error(`self-test: ${message}`); };
  assert(evaluateDirection(good, "wasm", "js").pass, "a complete wasm-to-js report passes");
  assert(!evaluateDirection(good, "js", "wasm").pass, "reversed runtime evidence fails");
  const lost = {...good, checks: {...okChecks, placedBlockPersisted: {ok: false}}};
  assert(evaluateDirection(lost, "wasm", "js").problems.includes("check placedBlockPersisted failed"),
    "a lost block fails");
  const missing = {...good, checks: {...okChecks}};
  delete missing.checks.chunksReload;
  assert(evaluateDirection(missing, "wasm", "js").problems.includes("check chunksReload did not run"),
    "a check that did not run fails");
  const silent = {...good, runtimes: {world: evidence("wasm"), reentered: {client: "js", server: {timeout: true}}}};
  assert(!evaluateDirection(silent, "wasm", "js").pass,
    "a missing server pong fails");
  const mixed = {...good, runtimes: {world: {client: "wasm", server: {runtime: "js"}}, reentered: evidence("js")}};
  assert(!evaluateDirection(mixed, "wasm", "js").pass, "mixed client/server runtime evidence fails");
  for (const half of ["world", "reentered"]) {
    for (const badEvidence of [null, {}, {error: "evaluation failed"},
      {client: null, server: {runtime: half === "world" ? "wasm" : "js"}},
      {client: half === "world" ? "wasm" : "js", server: null},
      {client: half === "world" ? "wasm" : "js", server: {error: "postMessage failed"}},
      {client: {selected: half === "world" ? "wasm" : "js"}, server: {runtime: half === "world" ? "wasm" : "js"}}]) {
      assert(!evaluateDirection({...good, runtimes: {...good.runtimes, [half]: badEvidence}}, "wasm", "js").pass,
        `${half} malformed or unavailable runtime evidence fails: ${JSON.stringify(badEvidence)}`);
    }
  }
  const reverse = {...good, runtimes: {world: evidence("js"), reentered: evidence("wasm")}};
  assert(evaluateDirection(reverse, "js", "wasm").pass, "a complete js-to-wasm report passes");
  assert(!evaluateDirection(null, "wasm", "js").pass, "no report fails");
  assert(!evaluateDirection(good, "wasm", "js", "", 1).pass, "unexplained failed child fails");
  assert(!evaluateDirection({...good, verdict: "FAIL"}, "wasm", "js").pass, "unexplained harness failure fails");
  const renderOnly = {...good, verdict:"FAIL", checks:{...okChecks, terrainRendered:{ok:false}, gpuBackend:{ok:false}, noSyncGlCalls:{ok:false}}};
  assert(evaluateDirection(renderOnly, "wasm", "js", "", 1).pass, "initial terrain timing does not invalidate completed persistence evidence");
  assert(!evaluateDirection(renderOnly, "wasm", "js", "", 137).pass, "killed child fails even with rendering-only check");
  assert(!evaluateDirection({...good, checks: {...okChecks, chunksReload: {ok: false}}}, "wasm", "js").pass, "a storage check failure still fails");
  assert(!evaluateDirection({...good, verdict: "FAIL", checks: {...okChecks, noSomethingElse: {ok: false}}}, "wasm", "js", "", 1).pass,
    "a failed non-rendering check outside the required list fails");
  const portable = {...good, storageGatePortable: true, runtimes: {world: {...evidence("wasm"), storage: {backend: "indexeddb-worker-lru"}},
    reentered: {...evidence("js"), storage: {backend: "indexeddb-worker-lru"}}}};
  assert(evaluateDirection(portable, "wasm", "js").pass, "portable report with the IndexedDB backend passes");
  const opfs = {...portable, runtimes: {...portable.runtimes, reentered: {...portable.runtimes.reentered, storage: {backend: "opfs-sync-worker"}}}};
  assert(!evaluateDirection(opfs, "wasm", "js").pass, "portable page must use the IndexedDB backend");
  assert(!evaluateDirection({...portable, runtimes: {...portable.runtimes, world: evidence("wasm")}}, "wasm", "js").pass,
    "portable report without storage evidence fails");
  assert(!evaluateDirection({...good, error: "boom"}, "wasm", "js").pass, "a harness error fails");
  const errorLog = [
    "   42.4 console.error[worker] [ERROR] Minecraft: Chunk [16, -17] header is truncated: expected 69632 but read 0",
    "   42.4 log.error worker [ERROR] Minecraft: Chunk [16, -17] header is truncated: expected 69632 but read 0 blob:http://x/y",
    "  252.5 log.error worker [ERROR] Minecraft: Failed to load chunk -23,-2",
    "   40.0 console.error [ERROR] Minecraft: Chunk [1, 1] header is truncated: expected 1 but read 0",
  ].join("\n");
  const counted = storageErrors(errorLog);
  assert(counted.count === 2, `two distinct Worker storage errors, not ${counted.count}`);
  assert(evaluateDirection(good, "wasm", "js", errorLog).problems.some((p) => p.includes("region file error")),
    "a region file error fails");
  console.log("storage-compat-gate self-test: OK");
}

const args = process.argv.slice(2);
if (args[0] === "--self-test") {
  selfTest();
  process.exit(0);
}
// --evaluate <out dir>: judge the direction runs a gate wrote earlier again (no Chrome).
if (args[0] === "--evaluate") {
  const dir = resolve(args[1] || usage("--evaluate needs the gate's --out directory"));
  let allPass = true;
  for (const direction of ["wasm-to-js", "js-to-wasm"]) {
    const reportPath = join(dir, direction, "report.json");
    const [writer, reader] = direction.split("-to-");
    let consoleText = "";
    try { consoleText = readFileSync(join(dir, direction, "console.log"), "utf8"); } catch {}
    let report = null;
    try { report = JSON.parse(readFileSync(reportPath, "utf8")); } catch {}
    const verdict = evaluateDirection(report, writer, reader, consoleText);
    allPass = allPass && verdict.pass;
    console.log(`storage-compat-gate: ${direction} ${verdict.pass ? "PASS" : "FAIL"}`
      + (verdict.problems.length ? ` (${verdict.problems.join("; ")})` : ""));
  }
  process.exit(allPass ? 0 : 1);
}
const options = {direction: "wasm-to-js,js-to-wasm", seed: "26300", wasmQuery: "runtime=wasm&serverRuntime=wasm",
  jsQuery: "runtime=js&serverRuntime=js"};
for (let index = 0; index < args.length; index++) {
  const value = () => {
    if (index + 1 >= args.length) usage(`${args[index]} needs a value`);
    return args[++index];
  };
  switch (args[index]) {
    case "--profile": options.profile = value(); break;
    case "--site": options.site = resolve(value()); break;
    case "--artifact": options.artifact = resolve(value()); break;
    case "--wasm": options.wasm = resolve(value()); break;
    case "--js": options.js = resolve(value()); break;
    case "--direction": options.direction = value(); break;
    case "--out": options.out = resolve(value()); break;
    case "--seed": options.seed = value(); break;
    case "--wasm-query": options.wasmQuery = value(); break;
    case "--js-query": options.jsQuery = value(); break;
    default: usage(`unknown argument ${args[index]}`);
  }
}
if (options.site) options.wasm = options.js = options.site;
if (!options.profile || (!options.artifact && (!options.wasm || !options.js))) usage("--profile and --site, --artifact, or --wasm/--js are required");
if (options.artifact && (options.wasm || options.js)) usage("--artifact cannot be combined with site/dists");
if (options.artifact && !existsSync(options.artifact)) usage("artifact does not exist");
for (const key of options.artifact ? [] : ["wasm", "js"]) {
  if (!existsSync(join(options[key], "index.html"))) usage(`${options[key]} has no index.html`);
}
const directions = options.direction.split(",").map((d) => d.trim()).filter(Boolean);
if (!directions.length || new Set(directions).size !== directions.length) usage("select nonempty, unique directions");
for (const direction of directions) {
  if (!/^(wasm|js)-to-(wasm|js)$/.test(direction) || direction.split("-to-")[0] === direction.split("-to-")[1]) {
    usage(`bad direction ${direction} (wasm-to-js or js-to-wasm)`);
  }
}
const out = options.out || resolve(`storage-compat-gate-${options.profile}`);
for (const direction of directions) {
  if (existsSync(join(out, direction, "report.json"))) usage(`${direction} already has a report; choose a fresh --out`);
}
await mkdir(out, {recursive: true});

// One origin, two dists: /wasm/<file> and /js/<file>.
const MIME = {".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".wasm": "application/wasm",
  ".json": "application/json", ".gz": "application/gzip", ".png": "image/png", ".br": "application/octet-stream"};
const roots = {wasm: options.wasm, js: options.js};
const server = createServer((request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  const match = /^\/(wasm|js)(\/.*)?$/.exec(url.pathname);
  if (!match) { response.writeHead(404); return response.end(); }
  const root = roots[match[1]];
  let logical = decodeURIComponent(match[2] && match[2] !== "/" ? match[2] : "/index.html");
  // Staged distributions use content-addressed names, while Workers may request logical names.
  const manifestPath = join(root, "gaius-site.json");
  if (existsSync(manifestPath)) {
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    logical = manifest.assets?.[logical.replace(/^\//, "")] || logical;
  }
  const path = normalize(join(root, logical));
  const rel = relative(root, path);
  if (rel.startsWith("..") || isAbsolute(rel)) { response.writeHead(403); return response.end(); }
  if (!existsSync(path) || statSync(path).isDirectory()) { response.writeHead(404); return response.end(); }
  response.writeHead(200, {"Content-Type": MIME[extname(path)] || "application/octet-stream",
    "Content-Length": statSync(path).size, "Cache-Control": "no-store"});
  createReadStream(path).pipe(response);
});
await new Promise((ready) => server.listen(Number(process.env.GAIUS_GATE_PORT || 0), "127.0.0.1", ready));
const origin = `http://127.0.0.1:${server.address().port}`;
const pageOf = (runtime) => {
  const query = runtime === "wasm" ? options.wasmQuery : options.jsQuery;
  const page = options.artifact ? pathToFileURL(options.artifact).href
    : `${origin}/${options.site ? "wasm" : runtime}/index.html`;
  const params = new URLSearchParams(query || "");
  const suffix = params.toString();
  return `${page}${suffix ? `?${suffix}` : ""}`;
};

function runHarness(env, logPath) {
  return new Promise((done) => {
    const child = spawn(process.execPath, [harness], {env: {...process.env, ...env}, stdio: ["ignore", "pipe", "pipe"]});
    let tail = "";
    const keep = (chunk) => { tail = (tail + chunk.toString()).slice(-4000); };
    child.stdout.on("data", keep);
    child.stderr.on("data", keep);
    child.on("error", (error) => done({code: 1, tail: String(error), logPath}));
    child.on("close", (code) => done({code: code ?? 1, tail, logPath}));
  });
}

// Compact runtime/storage evidence of one half for the summary.
const halfOf = (evidence) => evidence ? {client: evidence.client ?? null, server: evidence.server?.runtime ?? null,
  storageBackend: evidence.storage?.backend ?? null, worldId: evidence.storage?.worldId ?? null} : null;
options.samePage = Boolean(options.site || options.artifact);
const summary = {profile: options.profile, artifact: options.artifact || null, samePage: Boolean(options.site || options.artifact), origin, wasm: options.wasm, js: options.js, seed: options.seed,
  startedAt: new Date().toISOString(), directions: {}};
let allPass = true;
for (const direction of directions) {
  const [writer, reader] = direction.split("-to-");
  const directionOut = join(out, direction);
  await mkdir(directionOut, {recursive: true});
  const env = {OUT: directionOut, PAGE_URL: pageOf(writer), REENTER: "reload", REENTER_PAGE_URL: pageOf(reader),
    DIST: "", ARTIFACT: "", QUERY: "", STORAGE_GATE_PORTABLE: options.artifact ? "1" : "", RUNTIME: writer, GAIUS_RUNTIME: writer, SERVER_RUNTIME: writer,
    PROFILE: options.profile, SEED: options.seed, PLAYER_NAME: process.env.PLAYER_NAME || "GateCheck"};
  console.log(`storage-compat-gate: ${options.profile} ${direction}: ${env.PAGE_URL} -> ${env.REENTER_PAGE_URL}`);
  const started = Date.now();
  const run = await runHarness(env, join(directionOut, "console.log"));
  const reportPath = join(directionOut, "report.json");
  let report = null;
  try { report = JSON.parse(readFileSync(reportPath, "utf8")); } catch {}
  let consoleText = "";
  try { consoleText = readFileSync(join(directionOut, "console.log"), "utf8"); } catch {}
  const verdict = evaluateDirection(report, writer, reader, consoleText, run.code);
  summary.directions[direction] = {pass: verdict.pass, problems: verdict.problems, harnessExit: run.code,
    seconds: Math.round((Date.now() - started) / 1000), runtimes: report?.runtimes ?? null,
    failedChecks: report ? Object.entries(report.checks || {}).filter(([, c]) => c.ok !== true).map(([n]) => n) : null,
    halves: {writer: halfOf(report?.runtimes?.world), reader: halfOf(report?.runtimes?.reentered)},
    storageErrors: storageErrors(consoleText),
    placedBlock: report?.placedBlock ?? null, report: reportPath};
  if (!verdict.pass) {
    allPass = false;
    summary.directions[direction].harnessTail = run.tail.split("\n").slice(-6).join("\n");
  }
  console.log(`storage-compat-gate: ${options.profile} ${direction} ${verdict.pass ? "PASS" : "FAIL"}`
    + (verdict.problems.length ? ` (${verdict.problems.join("; ")})` : ""));
}
server.close();
summary.verdict = allPass ? "PASS" : "FAIL";
summary.finishedAt = new Date().toISOString();
await writeFile(join(out, "storage-compat-gate.json"), JSON.stringify(summary, null, 2) + "\n");
console.log(`STORAGE_COMPAT_GATE ${options.profile} ${summary.verdict} ${directions.map((d) =>
  `${d}=${summary.directions[d].pass ? "PASS" : "FAIL"}`).join(" ")} ${join(out, "storage-compat-gate.json")}`);
process.exit(allPass ? 0 : 1);
