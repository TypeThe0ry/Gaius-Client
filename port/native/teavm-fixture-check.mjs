// Runs the 26.3 golden fixtures through the TeaVM-compiled Java noise of a client build, to
// measure and lock the difference between the browser's Java path and vanilla.
//
//   node port/native/teavm-fixture-check.mjs [classes.js] [--fixtures dir] [--report]
//
// classes.js must be a non-minified build (default: port/target/26.3/boot-dist/classes.js of the
// main checkout). The random-source lines must always match. Every noise line must match bit for
// bit; a build from before the StrictMath263 rewrite runs float math in double precision, so it
// is measured and fails unless --report is given. Volume lines are not evaluated.
import {RENDERPEARL_PROFILE} from "../scripts/verification-profiles.mjs";
import {execFileSync, spawnSync} from "node:child_process";
import {existsSync, readFileSync, readdirSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import {loadTeaVM} from "./js/teavm-artifact.mjs";
import {TeaVMNoise, f64} from "./js/teavm-noise.mjs";

// The artifact needs a heap of a few GB; re-run with one when node was started without it.
if (!process.execArgv.some((arg) => arg.startsWith("--max-old-space-size"))) {
  const child = spawnSync(process.execPath, ["--max-old-space-size=8192", ...process.execArgv,
    fileURLToPath(import.meta.url), ...process.argv.slice(2)], {stdio: "inherit"});
  process.exit(child.status ?? 1);
}

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const report = args.includes("--report");
const fixturesAt = args.indexOf("--fixtures");
const fixturesDir = fixturesAt >= 0 ? args[fixturesAt + 1] : join(here, "fixtures");
const positional = args.filter((arg, index) => !arg.startsWith("--") && args[index - 1] !== "--fixtures");

function defaultArtifact() {
  const common = execFileSync("git", ["-C", here, "rev-parse", "--path-format=absolute", "--git-common-dir"],
    {encoding: "utf8"}).trim();
  return join(dirname(common), "port", "target", RENDERPEARL_PROFILE.id, "boot-dist", "classes.js");
}

const artifactPath = positional[0] ?? defaultArtifact();
if (!existsSync(artifactPath)) {
  console.error(`teavm fixture check: no artifact at ${artifactPath}`);
  process.exit(2);
}

const started = Date.now();
const noise = new TeaVMNoise(loadTeaVM(artifactPath));
const strict = noise.strictFloat();
console.log(`loaded ${artifactPath} in ${((Date.now() - started) / 1000).toFixed(1)} s, `
  + `StrictMath263 rewrite: ${strict ? "yes" : "no"}`);

const rowBits = (value) => new BigUint64Array(Float64Array.of(value).buffer)[0];
// Distance in units in the last place of a float, both values rounded to float first (vanilla's
// 26.3 noise returns floats), so accumulated error shows apart from a missing final rounding.
const floatUlps = (a, b) => {
  const key = (value) => {
    const bits = new Int32Array(Float32Array.of(value).buffer)[0];
    return bits < 0 ? -(bits & 0x7fffffff) : bits;
  };
  return Math.abs(key(a) - key(b));
};

// Random-source lines check that the harness builds the same objects as the golden harness.
function checkRandom(line) {
  const p = line.params;
  if (line.kind === "positional_from_hash") {
    const random = noise.random({...p, fork: p.name});
    return line.outputs.map((expected) => noise.nextLong(random) === BigInt(expected));
  }
  if (line.kind === "xoroshiro_next_long" && p.ctor === "seed") {
    const random = noise.base("xoroshiro", p.seed);
    return line.outputs.map((expected) => noise.nextLong(random) === BigInt(expected));
  }
  return null;
}

const groups = new Map();
const group = (key) => {
  let entry = groups.get(key);
  if (!entry) groups.set(key, entry = {rows: 0, mismatched: 0, unrounded: 0, ambiguous: 0, maxUlps: 0, failure: null, random: false});
  return entry;
};

const files = readdirSync(join(fixturesDir, RENDERPEARL_PROFILE.id)).filter((name) => name.endsWith(".jsonl")).sort();
for (const file of files) {
  for (const text of readFileSync(join(fixturesDir, RENDERPEARL_PROFILE.id, file), "utf8").split("\n")) {
    if (!text) continue;
    const line = JSON.parse(text);
    const p = line.params;
    const randomRows = checkRandom(line);
    if (randomRows) {
      const entry = group(`${line.kind}/${p.random ?? "xoroshiro"}`);
      entry.random = true;
      entry.rows += randomRows.length;
      entry.mismatched += randomRows.filter((ok) => !ok).length;
      continue;
    }
    const key = [line.kind, p.method ?? p.fn, p.precision ?? p.ctor, p.random].filter(Boolean).join("/");
    let sample;
    try {
      sample = noise.sampler(line);
    } catch (error) {
      group(key).failure ??= error.message;
      continue;
    }
    if (!sample) continue;
    const entry = group(key);
    line.inputs.forEach((inputs, row) => {
      entry.rows++;
      let actual;
      try {
        actual = sample(inputs);
      } catch (error) {
        if (error.ambiguous) entry.ambiguous++;
        else entry.failure ??= error.message;
        return;
      }
      const expected = line.outputs[row];
      if (typeof expected === "string" && expected.length === 16) {
        const want = f64(expected);
        if (rowBits(actual) !== rowBits(want)) {
          entry.mismatched++;
          if (Math.fround(actual) === want) entry.unrounded++;
          entry.maxUlps = Math.max(entry.maxUlps, floatUlps(actual, want));
        }
      } else if (Number(actual) !== Number(expected)) {
        entry.mismatched++;
      }
    });
  }
}

Object.assign(group("normal_noise/normalization_factor"),
  {rows: noise.factors.checked, mismatched: noise.factors.differ});

let randomFailures = 0;
let noiseMismatches = 0;
let failures = 0;
let totalRows = 0;
for (const [key, entry] of [...groups].sort(([a], [b]) => a.localeCompare(b))) {
  totalRows += entry.rows;
  const state = entry.failure ? `ERROR ${entry.failure}`
    : entry.mismatched && entry.maxUlps === 0 && entry.unrounded === 0 ? `${entry.mismatched} differ`
      : entry.mismatched ? `${entry.mismatched} differ (${entry.unrounded} only unrounded, `
        + `max ${entry.maxUlps} float ulp)`
      : "exact";
  const ambiguous = entry.ambiguous ? `, ${entry.ambiguous} ambiguous overloads` : "";
  console.log(`  ${key.padEnd(44)} ${String(entry.rows).padStart(5)} rows  ${state}${ambiguous}`);
  if (entry.failure) failures++;
  if (entry.random) randomFailures += entry.mismatched;
  else noiseMismatches += entry.mismatched;
}
console.log(`${totalRows} rows, ${noiseMismatches} noise, Mth and normalization rows differ from vanilla`);

if (failures || randomFailures) {
  console.error("teavm fixture check: the harness could not reproduce the golden setup");
  process.exit(1);
}
if (noiseMismatches && !report) {
  console.error(strict
    ? "teavm fixture check: the StrictMath263 build still differs from vanilla"
    : "teavm fixture check: this build predates the StrictMath263 rewrite; its float noise runs in "
      + "double precision (rerun with --report to only measure)");
  process.exit(1);
}
