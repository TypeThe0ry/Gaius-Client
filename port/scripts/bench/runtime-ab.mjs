#!/usr/bin/env node
// Runs the title acceptance on the JavaScript and the WebAssembly GC runtime alternately (js,
// wasm, js, wasm, ...) and summarises boot time, boot phases, memory and the verdicts, so both
// runtimes are measured under the same machine conditions.
//
//   node port/scripts/bench/runtime-ab.mjs --out <dir> [--runs N] [--form site|file]
//        [--js-dist <dir>] [--wasm-dist <dir>] [--runtimes js,wasm] [--wrap "<prefix>"]
//
// --form site (default): minecraft-title-screen-cdp.mjs serves each dist on 127.0.0.1 and opens
//   the runtime's page (index-wasm.html or index.html?runtime=wasm; harness-common.mjs).
// --form file: browser-file-entry-acceptance.mjs with GAIUS_FILE_MODE=title opens the runtime's
//   portable page over file:// (the WebAssembly GC portable page arrives with WP6).
// One dist may serve both runtimes (a WP5 dist); --wasm-dist defaults to --js-dist.
// --wrap: a command prefix for every harness run, for example a machine slot that serialises
//   headless Chrome ("bash slot.sh chrome ab --"). Each run's evidence stays in <out>/<form>-<runtime>-<n>.
// Exit 0 when every run passed.
import {spawn} from "node:child_process";
import {existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const scripts = resolve(here, "..");
function option(name, fallback = null) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : fallback;
}
const out = resolve(option("out") || "runtime-ab");
const runs = Math.max(1, Number(option("runs", "3")));
const form = option("form", "site");
const runtimes = option("runtimes", "js,wasm").split(",");
const jsDist = option("js-dist") ? resolve(option("js-dist")) : null;
const wasmDist = option("wasm-dist") ? resolve(option("wasm-dist")) : jsDist;
const wrap = (option("wrap") || "").trim().split(/\s+/).filter(Boolean);
if (!["site", "file"].includes(form)) throw new Error("--form must be site or file");
for (const runtime of runtimes) {
  if (!["js", "wasm"].includes(runtime)) throw new Error(`unknown runtime ${runtime}`);
  if (!(runtime === "js" ? jsDist : wasmDist)) throw new Error(`--${runtime}-dist is required`);
}
mkdirSync(out, {recursive: true});

function run(command, args, env, logFile) {
  return new Promise(resolveRun => {
    const [head, ...rest] = wrap.length ? [...wrap, command, ...args] : [command, ...args];
    const child = spawn(head, rest, {env: {...process.env, ...env}, windowsHide: true});
    let text = "";
    child.stdout.on("data", d => { text += d; });
    child.stderr.on("data", d => { text += d; });
    child.on("close", code => { writeFileSync(logFile, text); resolveRun({code, text}); });
  });
}

function lastJson(text) {
  const lines = text.trim().split(/\r?\n/);
  for (let i = lines.length - 1; i >= 0; i--) {
    const start = lines.slice(i).join("\n");
    if (lines[i].startsWith("{")) {
      try { return JSON.parse(start); } catch {}
    }
  }
  return null;
}

const results = [];
for (let n = 1; n <= runs; n++) {
  for (const runtime of runtimes) {
    const dist = runtime === "js" ? jsDist : wasmDist;
    const dir = join(out, `${form}-${runtime}-${n}`);
    mkdirSync(dir, {recursive: true});
    const started = new Date().toISOString();
    let result;
    if (form === "site") {
      const {code, text} = await run("node", [join(scripts, "minecraft-title-screen-cdp.mjs"), "--runtime", runtime],
        {DIST: dist, OUT: dir}, join(dir, "stdout.txt"));
      const report = existsSync(join(dir, "report.json")) ? JSON.parse(readFileSync(join(dir, "report.json"), "utf8")) : null;
      result = {code, summary: lastJson(text), report};
    } else {
      const {code, text} = await run("node", [join(scripts, "browser-file-entry-acceptance.mjs"), "--runtime", runtime],
        {GAIUS_FILE_DIST: dist, GAIUS_FILE_MODE: "title", GAIUS_FILE_OUTPUT: join(dir, "report.json")}, join(dir, "stdout.txt"));
      const report = existsSync(join(dir, "report.json")) ? JSON.parse(readFileSync(join(dir, "report.json"), "utf8")) : null;
      result = {code, summary: lastJson(text), report};
    }
    const telemetry = result.report?.runtimeTelemetry || null;
    const entry = {
      n, runtime, form, started, exit: result.code,
      pass: form === "site" ? result.report?.verdict === "PASS" : result.report?.success === true,
      observedRuntime: telemetry?.runtime || null,
      failed: form === "site" ? result.report?.failed || null : result.report?.error?.split("\n")[0] || null,
      firstScreenMillis: form === "site" ? result.report?.firstScreenMillis ?? null : null,
      phases: telemetry?.phases || null,
      memory: telemetry?.memory || null,
      fps: result.report?.pacing?.reported?.fps ?? null,
      // Title-screen window of tools/gl-frame-guard.mjs: unique presented frames and synchronous
      // GL calls per frame (the file:// report keeps the title and world windows apart).
      glFrames: (form === "site" ? result.report?.glFrames : result.report?.glFrames?.title) || null,
      dir,
    };
    results.push(entry);
    console.log(`${form} ${runtime} #${n}: ${entry.pass ? "PASS" : "FAIL"} observed=${entry.observedRuntime}`
      + ` firstScreen=${entry.firstScreenMillis != null ? Math.round(entry.firstScreenMillis) + " ms" : "-"}`
      + ` heap=${entry.memory?.usedJSHeapSize ? Math.round(entry.memory.usedJSHeapSize / 1048576) + " MB" : "-"}`
      + ` linear=${entry.memory?.linearMemoryBytes ? Math.round(entry.memory.linearMemoryBytes / 1048576) + " MB" : "-"}`
      + (entry.pass ? "" : ` failed=${JSON.stringify(entry.failed)}`));
  }
}
const median = values => {
  const sorted = values.filter(v => typeof v === "number").sort((a, b) => a - b);
  return sorted.length ? sorted[Math.floor(sorted.length / 2)] : null;
};
const byRuntime = Object.fromEntries(runtimes.map(runtime => {
  const own = results.filter(r => r.runtime === runtime);
  return [runtime, {runs: own.length, passed: own.filter(r => r.pass).length,
    medianFirstScreenMillis: median(own.map(r => r.firstScreenMillis)),
    medianUsedJSHeapBytes: median(own.map(r => r.memory?.usedJSHeapSize)),
    medianLinearMemoryBytes: median(own.map(r => r.memory?.linearMemoryBytes)),
    medianUniqueFps: median(own.map(r => r.glFrames?.uniqueFps)),
    maxSyncGlPerFrame: own.reduce((max, r) => typeof r.glFrames?.syncPerFrame === "number"
      ? Math.max(max ?? 0, r.glFrames.syncPerFrame) : max, null)}];
}));
const summary = {form, runs, runtimes, jsDist, wasmDist, finishedAt: new Date().toISOString(), byRuntime, results};
writeFileSync(join(out, "summary.json"), JSON.stringify(summary, null, 2) + "\n");
console.log(JSON.stringify(byRuntime));
process.exitCode = results.every(r => r.pass) ? 0 : 1;
