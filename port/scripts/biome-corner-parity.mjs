#!/usr/bin/env node
// JVM parity of BrowserBiomeNearestCorner (the WebAssembly GC nearest-corner biome lookup of
// BrowserBiomeManager) with the vanilla BiomeManager, and of the JavaScript BigInt
// implementation (the BrowserBiomeManager JSBody, which the JavaScript build keeps) with the same
// reference.
//
//   node port/scripts/biome-corner-parity.mjs [--profile <id>]... [--count N] [--seed S]
//        [--js-vectors N] [--bench] [--out-dir <dir>]
//
// For each profile (default: every profile in port/versions with a work/<id>/client-named.jar)
// biome-corner-parity/BiomeCornerParity.java is compiled together with
// port/src/main/java/dev/gaius/browser/BrowserBiomeNearestCorner.java against the vanilla client
// and compares, over --count (default 1000000) cases of every random family plus an edge grid
// (seed extremes, +-2^28/2^29/2^30, Integer.MIN/MAX_VALUE, negative coordinates, quart
// boundaries), the implementation with (a) the real BiomeManager.getBiome(BlockPos) driven
// through a recording biome source and (b) a replica built from LinearCongruentialGenerator.next
// and Mth.square. Families with one seed revisit corners (cache hits, collisions, evictions).
// Then the first profile writes --js-vectors (default 300000) vectors and Node evaluates the
// JSBody script of BrowserBiomeManager.java on them. --bench prints timings of the HotSpot
// implementation and of the JavaScript one under Node. Exit status: 0 parity, 1 mismatch,
// 2 environment.

import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const harness = join(root, "port/scripts/biome-corner-parity/BiomeCornerParity.java");
const implementation = join(root, "port/src/main/java/dev/gaius/browser/BrowserBiomeNearestCorner.java");
const managerSource = join(root, "port/src/main/java/dev/gaius/browser/BrowserBiomeManager.java");

function usage(message) {
  console.error(`biome-corner-parity: ${message}`);
  process.exit(2);
}

const options = {profiles: [], count: "1000000", seed: "20261113", jsVectors: 300000, bench: false, outDir: null};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const value = () => argv[++i] ?? usage(`${argv[i - 1]} needs a value`);
  switch (argv[i]) {
    case "--profile": options.profiles.push(value()); break;
    case "--count": options.count = value(); break;
    case "--seed": options.seed = value(); break;
    case "--js-vectors": options.jsVectors = Number(value()); break;
    case "--bench": options.bench = true; break;
    case "--out-dir": options.outDir = resolve(value()); break;
    default: usage(`unknown argument ${argv[i]}`);
  }
}

function javaTool(name) {
  const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
  return home ? join(home, "bin", name) : name;
}

function run(command, args, label) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 64 * 1024 * 1024});
  if (result.error) throw result.error;
  if (result.status !== 0 && result.status !== 1) {
    usage(`${label} failed (exit ${result.status})\n${`${result.stdout || ""}${result.stderr || ""}`.slice(-6000)}`);
  }
  return result;
}

if (options.profiles.length === 0) {
  options.profiles = readdirSync(join(root, "port/versions")).filter((name) => name.endsWith(".json"))
    .map((name) => name.slice(0, -5)).filter((id) => existsSync(join(root, "port/work", id, "client-named.jar")));
}
if (options.profiles.length === 0) usage("no profile has port/work/<id>/client-named.jar");

const scratch = mkdtempSync(join(options.outDir ?? tmpdir(), "gaius-biome-corner-"));
let failed = false;
let vectorFile = null;
try {
  for (const profile of options.profiles) {
    const work = join(root, "port/work", profile);
    const vanillaJar = join(work, "client-named.jar");
    if (!existsSync(vanillaJar)) usage(`missing ${vanillaJar}`);
    const libraries = readFileSync(join(work, "classpath.txt"), "utf8").trim().split(":").filter(Boolean)
      .filter((entry) => !/client-(named|original)\.jar$/.test(entry));
    const release = String(JSON.parse(readFileSync(join(root, "port/versions", `${profile}.json`), "utf8")).javaVersion);
    const classpath = [vanillaJar, ...libraries];
    const classes = join(scratch, `classes-${profile}`);
    mkdirSync(classes, {recursive: true});
    run(javaTool("javac"), ["-J-Duser.language=en", "--release", release, "-proc:none", "-nowarn",
      "-cp", classpath.join(delimiter), "-d", classes, implementation, harness], `javac ${profile}`);
    const java = (extra) => run(javaTool("java"), ["-Xmx2g", "-Duser.language=en", "-Dmax.bg.threads=1",
      "-cp", [classes, ...classpath].join(delimiter), "dev.gaius.parity.BiomeCornerParity", ...extra],
    `java ${profile}`);
    const result = java(["--count", options.count, "--seed", options.seed]);
    const lines = `${result.stdout}\n${result.stderr}`.split(/\r?\n/)
      .filter((line) => /BIOME_CORNER_(FAMILY|MISMATCH|TOTAL)/.test(line)).map((line) => line.slice(line.indexOf("BIOME_CORNER_")));
    console.log(`profile ${profile} (java ${release})`);
    for (const line of lines) console.log(`  ${line}`);
    const total = lines.find((line) => line.startsWith("BIOME_CORNER_TOTAL"));
    const ok = result.status === 0 && total && /mismatches=0$/.test(total);
    console.log(`BIOME_CORNER_PARITY ${profile} ${ok ? "PASS" : "FAIL"}`);
    if (!ok) failed = true;
    if (options.bench && profile === options.profiles[0]) {
      const bench = java(["--bench"]);
      for (const line of `${bench.stdout}`.split(/\r?\n/).filter((entry) => entry.startsWith("BIOME_CORNER_BENCH"))) console.log(line);
    }
    if (vectorFile === null && options.jsVectors > 0) {
      vectorFile = join(scratch, "vectors.txt");
      java(["--seed", options.seed, "--emit", vectorFile, String(options.jsVectors)]);
    }
  }

  // The JavaScript implementation: the script of the JSBody, evaluated as a function of
  // (seed: BigInt, shiftedX, shiftedY, shiftedZ), the way the WebAssembly GC marshalling calls it.
  if (vectorFile) {
    const text = readFileSync(managerSource, "utf8");
    const match = text.match(/script = """\n([\s\S]*?)"""\)\s*private static native int nearestCornerBigInt/);
    if (!match) usage("cannot find the nearestCornerBigInt JSBody script");
    const nearestCorner = new Function("seed", "shiftedX", "shiftedY", "shiftedZ", match[1]);
    let count = 0;
    let bad = 0;
    for (const line of readFileSync(vectorFile, "utf8").split("\n")) {
      if (!line) continue;
      const [seed, x, y, z, corner] = line.split(" ");
      count++;
      if (nearestCorner(BigInt(seed), Number(x), Number(y), Number(z)) !== Number(corner)) {
        if (bad++ < 5) console.log(`BIOME_CORNER_JS_MISMATCH ${line}`);
      }
    }
    console.log(`BIOME_CORNER_JS vectors=${count} mismatches=${bad}`);
    if (bad || count === 0) failed = true;

    if (options.bench) {
      const seed = 8682522807148012n;
      const n = 1 << 20;
      let state = 7;
      const random = () => (state = (Math.imul(state, 1664525) + 1013904223) >>> 0) / 4294967296;
      const patterns = {
        "scan(16x16 blocks, y step 4)": (i) => [1000 + (i % 256) % 16 + Math.floor(Math.floor(i / 256) / 90) * 16,
          (Math.floor(i / 256) % 90) * 4, 3000 + Math.floor((i % 256) / 16)],
        "sparse(random 64x64x64 window)": () => [Math.floor(random() * 64), Math.floor(random() * 64), Math.floor(random() * 64)],
        "cold(random 60M range)": () => [Math.floor(random() * 60000000) - 30000000, Math.floor(random() * 384) - 64,
          Math.floor(random() * 60000000) - 30000000],
      };
      for (const [name, make] of Object.entries(patterns)) {
        const data = new Int32Array(n * 3);
        for (let i = 0; i < n; i++) data.set(make(i), i * 3);
        let best = Infinity;
        for (let round = 0; round < 6; round++) {
          let acc = 0;
          const start = process.hrtime.bigint();
          for (let i = 0; i < n; i++) acc += nearestCorner(seed, data[i * 3], data[i * 3 + 1], data[i * 3 + 2]);
          best = Math.min(best, Number(process.hrtime.bigint() - start) / n);
          if (acc < 0) console.log(acc);
        }
        console.log(`BIOME_CORNER_BENCH_JS ${name} bigint-js=${best.toFixed(1)} ns/call (no wasm-to-js crossing)`);
      }
    }
  }
} finally {
  rmSync(scratch, {recursive: true, force: true});
}
process.exit(failed ? 1 : 0);
