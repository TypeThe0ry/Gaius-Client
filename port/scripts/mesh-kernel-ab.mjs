#!/usr/bin/env node
// Mesh kernel A/B in the client (v0.4.0 M2 golden check of the mesher on the build's artifacts):
// the same saved world rendered from the same top-down camera once with the Rust mesh kernel of the
// build (its wasm module in the kernel worker pool) and once with the vanilla Java section compiler
// of the same client (?gaiusKernelsOff=mesher), compared pixel by pixel.
//
//   node port/scripts/mesh-kernel-ab.mjs --dist <dist> --profile <id> --out <dir>
//        [--query runtime=wasm&serverRuntime=wasm] [--seed 26300]
//   node port/scripts/mesh-kernel-ab.mjs --evaluate <out dir>
//
// Two runs of minecraft-263-singleplayer-cdp.mjs in fresh Chrome profiles, both re-entering their
// world after a page reload on the same origin (REENTER=reload): "control" reloads the same page,
// so both halves mesh with the kernel and the top-down difference (report.terrainBaseline: the
// screenshot before Save and Quit against the one after re-entry) is what animation, lighting
// ticks and the reload alone change; "vanilla" reloads with ?gaiusKernelsOff=mesher, so the second
// half meshes every section with the vanilla compiler. The mesher is in parity when the vanilla
// run's difference stays within the control's (changed fraction at most control + 1 percentage
// point and mean difference at most control + 2) and the evidence shows the kernel meshing in the
// first half and not in the second (report.runtimes.{beforeQuit,end}.meshKernel.poolJobs). Evidence: <out>/<run>/ and
// <out>/mesh-kernel-ab.json. Exit status: 0 parity, 1 difference or failure, 2 usage.
import {spawn} from "node:child_process";
import {createReadStream, existsSync, mkdirSync, readFileSync, statSync, writeFileSync} from "node:fs";
import {createServer} from "node:http";
import {dirname, extname, isAbsolute, join, normalize, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

function usage(message) {
  if (message) console.error(`mesh-kernel-ab: ${message}`);
  console.error("usage: mesh-kernel-ab.mjs --dist <dist> --profile <id> --out <dir> [--query <q>] [--seed <n>] | --evaluate <dir>");
  process.exit(2);
}

/** Verdict from the two harness reports. */
export function evaluate(control, vanilla, exits = {}) {
  const problems = [];
  const diffOf = (report, name) => {
    if (report?.verdict !== "PASS" || report?.error) problems.push(`${name}: the singleplayer harness did not pass`);
    if (exits[name] !== undefined && exits[name] !== 0) problems.push(`${name}: harness exited with ${exits[name]}`);
    const diff = report?.terrainBaseline?.diff;
    const valid = diff && !diff.error && Number.isFinite(diff.changedFraction) && diff.changedFraction >= 0
      && diff.changedFraction <= 1 && Number.isFinite(diff.meanDiff) && diff.meanDiff >= 0;
    if (!valid) problems.push(`${name}: no valid top-down comparison (${report?.error ?? diff?.error ?? "missing metrics"})`);
    return valid ? diff : null;
  };
  const a = diffOf(control, "control");
  const b = diffOf(vanilla, "vanilla");
  // Mesher jobs the kernel pool completed since the page loaded: before Save and Quit for the
  // first half, at the end (after the reload) for the second.
  const jobs = (report, point) => report?.runtimes?.[point]?.meshKernel?.poolJobs ?? null;
  if (control && !(jobs(control, "beforeQuit") > 0)) problems.push("control: the mesh kernel meshed nothing before Save and Quit");
  if (control && !(jobs(control, "end") > 0)) problems.push("control: the mesh kernel meshed nothing after the reload");
  if (vanilla && !(jobs(vanilla, "beforeQuit") > 0)) problems.push("vanilla: the mesh kernel meshed nothing before Save and Quit");
  if (vanilla && jobs(vanilla, "end") !== 0) {
    problems.push(`vanilla: the mesh kernel completed ${jobs(vanilla, "end")} jobs after the reload`);
  }
  if (a && b) {
    if (b.changedFraction > a.changedFraction + 0.01 || b.meanDiff > a.meanDiff + 2) {
      problems.push(`kernel vs vanilla mesher top-down difference (changed ${b.changedFraction}, mean ${b.meanDiff}) exceeds `
        + `the control's (changed ${a.changedFraction}, mean ${a.meanDiff}) by more than 1 point / 2`);
    }
  }
  return {pass: problems.length === 0, problems, control: a, vanilla: b};
}

function serve(dist) {
  const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json",
    ".gz": "application/gzip", ".png": "image/png", ".br": "application/octet-stream"};
  const server = createServer((request, response) => {
    const url = new URL(request.url, "http://127.0.0.1");
    let path = normalize(join(dist, decodeURIComponent(url.pathname)));
    const rel = relative(dist, path);
    if (rel.startsWith("..") || isAbsolute(rel)) { response.writeHead(403); return response.end(); }
    if (url.pathname === "/") path = join(dist, "index.html");
    if (!existsSync(path) || statSync(path).isDirectory()) { response.writeHead(404); return response.end(); }
    response.writeHead(200, {"Content-Type": MIME[extname(path)] || "application/octet-stream",
      "Content-Length": statSync(path).size, "Cache-Control": "no-store"});
    createReadStream(path).pipe(response);
  });
  return new Promise((ready) => server.listen(0, "127.0.0.1", () => ready(server)));
}

function harness(env) {
  return new Promise((done) => {
    const child = spawn(process.execPath, [join(here, "minecraft-263-singleplayer-cdp.mjs")],
      {env: {...process.env, ...env}, stdio: ["ignore", "pipe", "pipe"]});
    let tail = "";
    child.stdout.on("data", (chunk) => { tail = (tail + chunk).slice(-1500); });
    child.stderr.on("data", (chunk) => { tail = (tail + chunk).slice(-1500); });
    child.on("error", (error) => done({code: 1, tail: String(error)}));
    child.on("close", (code) => done({code: code ?? 1, tail}));
  });
}

function readReport(dir) {
  try { return JSON.parse(readFileSync(join(dir, "report.json"), "utf8")); } catch { return null; }
}

const args = process.argv.slice(2);
if (args[0] === "--self-test") {
  const evidence = (jobs) => ({meshKernel: {poolJobs: jobs}});
  const control = {verdict: "PASS", terrainBaseline: {diff: {changedFraction: 0.01, meanDiff: 1}},
    runtimes: {beforeQuit: evidence(3), end: evidence(2)}};
  const vanilla = {...control, runtimes: {beforeQuit: evidence(3), end: evidence(0)}};
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  assert(evaluate(control, vanilla).pass, "complete reports must pass");
  assert(!evaluate(control, {...vanilla, verdict: "FAIL"}).pass, "failed singleplayer run must fail");
  assert(!evaluate(control, {...vanilla, runtimes: {beforeQuit: evidence(3)}}).pass, "missing end telemetry must fail");
  assert(!evaluate(control, {...vanilla, terrainBaseline: {diff: {}}}).pass, "missing pixel metrics must fail");
  assert(!evaluate(control, vanilla, {vanilla: 1}).pass, "failed child must fail");
  assert(!evaluate(control, {...vanilla, terrainBaseline: {diff: {changedFraction: 0.1, meanDiff: 10}}}).pass,
    "different pixels must fail");
  console.log("mesh-kernel-ab self-test: OK");
  process.exit(0);
}
const options = {seed: "26300", query: ""};
for (let index = 0; index < args.length; index++) {
  const value = () => args[++index] ?? usage(`${args[index - 1]} needs a value`);
  switch (args[index]) {
    case "--dist": options.dist = resolve(value()); break;
    case "--profile": options.profile = value(); break;
    case "--out": options.out = resolve(value()); break;
    case "--query": options.query = value(); break;
    case "--seed": options.seed = value(); break;
    case "--evaluate": options.evaluate = resolve(value()); break;
    default: usage(`unknown argument ${args[index]}`);
  }
}
if (options.evaluate) {
  const verdict = evaluate(readReport(join(options.evaluate, "control")), readReport(join(options.evaluate, "vanilla")));
  console.log(`MESH_KERNEL_AB ${verdict.pass ? "PASS" : "FAIL"} ${JSON.stringify({control: verdict.control,
    vanilla: verdict.vanilla, problems: verdict.problems})}`);
  process.exit(verdict.pass ? 0 : 1);
}
if (!options.dist || !options.profile || !options.out) usage("--dist, --profile and --out are required");
for (const name of ["control", "vanilla"]) {
  if (existsSync(join(options.out, name, "report.json"))) usage(`${name} already has a report; choose a fresh --out`);
}
mkdirSync(options.out, {recursive: true});
const server = await serve(options.dist);
const base = `http://127.0.0.1:${server.address().port}/index.html`;
const page = (extra) => {
  const parts = [options.query, extra].filter(Boolean).join("&");
  return parts ? `${base}?${parts}` : base;
};
const runs = {};
for (const [name, reenter] of [["control", page("")], ["vanilla", page("gaiusKernelsOff=mesher")]]) {
  const out = join(options.out, name);
  mkdirSync(out, {recursive: true});
  console.log(`mesh-kernel-ab: ${options.profile} ${name}: ${page("")} -> ${reenter}`);
  const run = await harness({OUT: out, PAGE_URL: page(""), REENTER: "reload", REENTER_PAGE_URL: reenter,
    PROFILE: options.profile, SEED: options.seed, PLAYER_NAME: "MeshCheck"});
  runs[name] = {harnessExit: run.code, report: readReport(out)};
}
server.close();
const verdict = evaluate(runs.control.report, runs.vanilla.report,
  {control: runs.control.harnessExit, vanilla: runs.vanilla.harnessExit});
const summary = {profile: options.profile, dist: options.dist, query: options.query, seed: options.seed,
  verdict: verdict.pass ? "PASS" : "FAIL", problems: verdict.problems, control: verdict.control, vanilla: verdict.vanilla,
  meshKernel: Object.fromEntries(Object.entries(runs).map(([name, run]) => [name, {
    beforeQuit: run.report?.runtimes?.beforeQuit?.meshKernel ?? null, end: run.report?.runtimes?.end?.meshKernel ?? null,
    harnessExit: run.harnessExit}]))};
writeFileSync(join(options.out, "mesh-kernel-ab.json"), JSON.stringify(summary, null, 2) + "\n");
console.log(`MESH_KERNEL_AB ${options.profile} ${summary.verdict} control ${JSON.stringify(verdict.control)} `
  + `vanilla ${JSON.stringify(verdict.vanilla)}${verdict.problems.length ? ` (${verdict.problems.join("; ")})` : ""}`);
process.exit(verdict.pass ? 0 : 1);
