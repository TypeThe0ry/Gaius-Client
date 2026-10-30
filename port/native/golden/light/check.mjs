#!/usr/bin/env node
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {spawnSync} from "node:child_process";
import {waitForHeadroom} from "./headroom.mjs";

const root = fileURLToPath(new URL("../../../..", import.meta.url));
const args = process.argv.slice(2);
const option = (name) => args.includes(name) ? args[args.indexOf(name) + 1] : null;
const profile = option("--profile");
assert(profile && option("--out") && option("--dist"), "usage: check.mjs --profile <id> --out <dir> --dist <shipped-dist> [--reuse]");
const out = resolve(option("--out"));
const dist = resolve(option("--dist"));
const golden = join(out, "golden");
const target = join(out, "target");
mkdirSync(out, {recursive: true});
function run(command, parameters, label, heavy = false) {
  if (heavy) waitForHeadroom(out, label);
  const result = spawnSync(command, parameters, {cwd: root, windowsHide: true, encoding: "utf8", maxBuffer: 2 * 1024 * 1024,
    env: {...process.env, CARGO_TARGET_DIR: target}});
  writeFileSync(join(out, `${label}.log`), `${result.stdout || ""}\n${result.stderr || ""}`);
  assert(!result.error, `${label}: ${result.error}`);
  return result;
}
const comparisonTests = run(process.execPath, [join(root, "port/scripts/light-golden-compare.test.mjs")], "comparison-tests");
assert.equal(comparisonTests.status, 0, "comparison tests failed");
if (!args.includes("--reuse")) {
  const dump = run(process.execPath, [join(root, "port/native/golden/light/dump.mjs"), "--profile", profile, "--out", golden,
    "--java-heap", option("--java-heap") || "2g",
    ...(option("--seeds") ? ["--seeds", option("--seeds")] : [])], "dump");
  assert.equal(dump.status, 0, "JVM fixtures missing or NOT_RUN; light approval forbidden");
}
const corpus = JSON.parse(readFileSync(join(golden, "corpus.json")));
assert.equal(corpus.profile, profile);
assert.equal(corpus.schema, 1);
assert(corpus.seeds.length >= 2 && new Set(corpus.seeds).size === corpus.seeds.length);
assert(corpus.seeds.every((seed) => /^-?\d+$/.test(seed) && BigInt(seed).toString() === seed), "canonical integer seeds required");
const stages = ["initial", "emitter-add", "emitter-remove", "roof-close", "roof-open"];
assert.equal(corpus.snapshots.length, corpus.seeds.length * stages.length, "all incremental stages required for each seed");
const seen = new Set();
let initialSections = 0;
for (const snapshot of corpus.snapshots) {
  assert(corpus.seeds.includes(snapshot.seed) && stages.includes(snapshot.stage), "unknown seed or stage");
  const key = `${snapshot.seed}/${snapshot.stage}`;
  assert(!seen.has(key), "duplicate snapshot");
  seen.add(key);
  assert.equal(snapshot.path, `fixtures/${key}`, "invalid snapshot path");
  const bytes = readFileSync(join(golden, snapshot.path, "fixtures.json"));
  assert.equal(createHash("sha256").update(bytes).digest("hex"), snapshot.sha256);
  const manifest = JSON.parse(bytes);
  assert.equal(manifest.profile, profile);
  assert.equal(manifest.seed, snapshot.seed);
  assert.equal(manifest.stage, snapshot.stage);
  assert.equal(manifest.sectionCount, corpus.sectionCount);
  assert.equal(manifest.cases.length, 9);
  assert.equal(new Set(manifest.cases).size, 9);
  assert.equal(manifest.previous, snapshot.stage === "initial" ? null : `../${stages[stages.indexOf(snapshot.stage) - 1]}`);
  if (snapshot.stage === "initial") initialSections += manifest.sectionCount * manifest.cases.length;
}
assert.equal(corpus.distinctInitialSections, initialSections, "section count differs from the actual snapshots");
assert(initialSections >= 300, "at least 300 distinct initial sections required");
const build = run("cargo", ["build", "--manifest-path", join(root, "port/native/Cargo.toml"), "--offline", "--locked", "-j", "1",
  "-p", "gaius-light-wasm", "--example", "jvm_light_golden"], "native-build", true);
assert.equal(build.status, 0, "native runner build failed");
const native = join(target, "debug/examples", `jvm_light_golden${process.platform === "win32" ? ".exe" : ""}`);
const reports = [];
for (const snapshot of corpus.snapshots) {
  assert(!snapshot.path.startsWith("/") && !snapshot.path.includes("..") && !snapshot.path.includes(":"), "invalid snapshot path");
  const fixtures = join(golden, snapshot.path);
  assert.equal(createHash("sha256").update(readFileSync(join(fixtures, "fixtures.json"))).digest("hex"), snapshot.sha256);
  const output = join(out, "compare", snapshot.seed, snapshot.stage);
  const comparison = run(process.execPath, [join(root, "port/scripts/light-jvm-wasm-golden.mjs"), "--profile", profile,
    "--fixtures", fixtures, "--out", output, "--dist", dist, "--native-runner", native], `compare-${snapshot.seed}-${snapshot.stage}`);
  assert(comparison.status === 0 || comparison.status === 1, "comparison did not run");
  const report = JSON.parse(readFileSync(join(output, "light-jvm-wasm-golden.json")));
  assert.equal(report.profile, profile);
  assert.equal(report.seed, snapshot.seed);
  assert.equal(report.stage, snapshot.stage);
  assert.equal(report.results.length, 27, "all nine native/SIMD/baseline columns required");
  assert.equal(new Set(report.results.map((result) => `${result.variant}/${result.name}`)).size, 27);
  assert.equal(comparison.status, report.verdict === "PASS" ? 0 : 1, "comparison verdict differs from its exit status");
  reports.push(report);
  console.log(`LIGHT_JVM ${snapshot.seed} ${snapshot.stage} ${report.verdict}`);
}
const pass = reports.every((report) => report.verdict === "PASS");
const totals = {distinctInitialSections: corpus.distinctInitialSections, comparisons: 0, nibbles: 0, blockMismatches: 0, skyMismatches: 0};
for (const report of reports) for (const result of report.results) {
  totals.comparisons++;
  totals.nibbles += result.cellsPerLayer * 2;
  totals.blockMismatches += result.mismatches.block;
  totals.skyMismatches += result.mismatches.sky;
}
writeFileSync(join(out, "reports.json"), JSON.stringify({schema: 1, profile, verdict: pass ? "PASS" : "FAIL", totals, reports}, null, 2) + "\n");
console.log(`LIGHT_JVM ${pass ? "PASS" : "FAIL"} ${JSON.stringify(totals)}`);
process.exitCode = pass ? 0 : 1;
