#!/usr/bin/env node
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";
import {execFileSync} from "node:child_process";
import {compareLightColumn, effectiveLight as effective} from "./light-golden-compare.mjs";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const options = {};
const args = process.argv.slice(2);
for (let index = 0; index < args.length; index++) {
  const name = args[index];
  if (!["--dist", "--fixtures", "--out", "--profile", "--native-runner"].includes(name) || !args[index + 1]) {
    throw new Error("usage: light-jvm-wasm-golden.mjs --profile <id> --dist <dist> --fixtures <JVM fixture dir> --out <dir>");
  }
  options[name.slice(2)] = args[++index];
}
for (const name of ["dist", "fixtures", "out", "profile"]) assert.ok(options[name], `missing --${name}`);
const hashes = {};
const read = (path) => {
  const bytes = readFileSync(path);
  hashes[path] = createHash("sha256").update(bytes).digest("hex");
  return bytes;
};
const fixtureRoot = resolve(options.fixtures);
const manifest = JSON.parse(read(join(fixtureRoot, "fixtures.json")));
assert.equal(manifest.schema, 1);
assert.equal(manifest.profile, options.profile, "fixture profile does not match the selected profile");
assert.ok(manifest.cases.length > 0, "no complete vanilla neighbourhoods");
assert.ok(Number.isInteger(manifest.sectionCount) && manifest.sectionCount > 0 && manifest.sectionCount <= 254);
const profile = JSON.parse(read(join(root, "port/versions", `${options.profile}.json`)));
// Seeded column goldens (port/native/golden/light) run on the named jar of every profile; the
// saved-world light fixtures run on the profile's distribution jar. Either binds the fixture to
// a local vanilla jar of the selected profile.
const jars = [...new Set([
  join(root, "port/work", options.profile, "client-named.jar"),
  join(root, "port/work", options.profile,
    profile.clientDistribution === "obfuscated-with-mappings" ? "client-named.jar" : "client-original.jar"),
])];
for (const jar of jars) read(jar);
assert(jars.some((jar) => manifest.sourceSha256 === hashes[jar]), "vanilla jar differs from the fixture source");
const context = vm.createContext({BigInt, DataView, ArrayBuffer, Uint8Array, Uint16Array, Uint32Array, Int32Array,
  Math, Object, Array, Error, RangeError, TypeError, WebAssembly});
vm.runInContext(read(join(resolve(options.dist), "kernels/light-job.js")).toString("utf8"), context);
const codec = context.GaiusLightJob;
const count = manifest.sectionCount;
const table = read(join(fixtureRoot, "table.bin"));
function loadColumns(directory, snapshot) {
  const columns = new Map();
  assert.equal(snapshot.sectionCount, count);
  assert.equal(snapshot.minSection, manifest.minSection);
  assert.equal(snapshot.profile, manifest.profile);
  assert.equal(snapshot.seed, manifest.seed);
  assert.equal(snapshot.sky, manifest.sky);
  assert.equal(snapshot.sourceSha256, manifest.sourceSha256);
  // Seeded goldens name the light table exporter as the profile compiles it (dump.mjs stages the
  // production BrowserLightStateTable and the profile's light ops from the source sets).
  assert(!snapshot.adapterSha256, "fixture predates the light ops; dump it again");
  for (const [rel, digest] of Object.entries(snapshot.staged || {})) {
    const file = sourceFile(options.profile, "src", rel, {target: "wasmgc", root});
    assert(file, `the profile no longer compiles ${rel}`);
    assert.equal(createHash("sha256").update(file.text).digest("hex"), digest, `fixture exporter differs from the source: ${rel}`);
  }
  for (const [path, digest] of Object.entries(snapshot.sources || {})) {
    assert(!path.startsWith("/") && !path.includes("..") && !path.includes(":"), "invalid harness path");
    assert.equal(createHash("sha256").update(read(join(root, path))).digest("hex"), digest, `harness changed: ${path}`);
  }
  if (snapshot.files) {
    for (const [name, digest] of Object.entries(snapshot.files)) {
      assert(!name.includes("/") && !name.includes("\\") && name !== "..", "invalid fixture basename");
      assert.equal(createHash("sha256").update(read(join(directory, name))).digest("hex"), digest, `fixture changed: ${name}`);
    }
  }
  for (const row of snapshot.columns) {
    assert.equal(row.name, `${row.x},${row.z}`);
    assert(!columns.has(row.name), "duplicate fixture column");
    const states = read(join(directory, `${row.name}.states`));
    const light = read(join(directory, `${row.name}.light`));
    const flags = new Uint8Array(read(join(directory, `${row.name}.flags`)));
    assert.equal(states.length, count * 4096 * 2);
    assert.equal(light.length, (count + 2) * 4096);
    assert.equal(flags.length, count + 2);
    const sections = Array.from({length: count}, (_, section) => ({flat: Uint16Array.from({length: 4096},
      (_, cell) => states.readUInt16LE((section * 4096 + cell) * 2))}));
    const layers = (kind, bit) => Array.from({length: count + 2}, (_, section) => flags[section] & bit
      ? new Uint8Array(light.subarray(section * 4096 + kind * 2048, section * 4096 + (kind + 1) * 2048)) : null);
    columns.set(row.name, {flags, sections, block: layers(0, 2), sky: layers(1, 1), x: row.x, z: row.z});
  }
  return columns;
}
const columns = loadColumns(fixtureRoot, manifest);
const previousRoot = manifest.previous ? resolve(fixtureRoot, manifest.previous) : null;
const previous = previousRoot ? loadColumns(previousRoot, JSON.parse(read(join(previousRoot, "fixtures.json")))) : null;
const results = [];
mkdirSync(options.out, {recursive: true});
const jobs = new Map();
for (const [caseIndex, name] of manifest.cases.entries()) {
  const column = columns.get(name);
  assert.ok(column, `unknown case ${name}`);
  const neighbours = {};
  const checks = [...(manifest.checks?.[name] || [])];
  for (const [side, deltaX, deltaZ] of [["north", 0, -1], ["south", 0, 1], ["west", -1, 0], ["east", 1, 0]]) {
    const key = `${column.x + deltaX},${column.z + deltaZ}`;
    neighbours[side] = columns.get(key);
    assert.ok(neighbours[side], `missing ${side} neighbour`);
    if (!previous) continue;
    const before = previous.get(key);
    assert.ok(before, `missing previous ${side} neighbour`);
    for (let section = 1; section <= count; section++) {
      for (let vertical = 0; vertical < 16; vertical++) for (let along = 0; along < 16; along++) {
        const neighbourX = side === "west" ? 15 : side === "east" ? 0 : along;
        const neighbourZ = side === "north" ? 15 : side === "south" ? 0 : along;
        const cell = (vertical << 8) | (neighbourZ << 4) | neighbourX;
        // Vanilla checks a changed neighbour block itself; its decrease reaches the column
        // through this border cell even where the settled neighbour level ends up unchanged.
        if (before.sections[section - 1].flat[cell] !== neighbours[side].sections[section - 1].flat[cell]
            || ["block", "sky"].some((kind) => effective(before[kind], section, cell, kind === "sky")
            !== effective(neighbours[side][kind], section, cell, kind === "sky"))) {
          checks.push(side === "west" ? 0 : side === "east" ? 15 : along,
            (manifest.minSection + section - 1) * 16 + vertical,
            side === "north" ? 0 : side === "south" ? 15 : along);
        }
      }
    }
  }
  const before = previous?.get(name);
  assert(!previous || before, `missing previous center ${name}`);
  // The ring holds settled vanilla neighbour light: a boundary condition, not live storage.
  // EMIT_OUTGOING makes the kernel treat the ring as live storage that the Java side updates
  // afterwards: a decrease that reaches a ring cell zeroes the kernel's copy and leaves the
  // relight of that neighbour (and the light it sends back into the column) to the vanilla
  // replay of the outgoing record. Nothing replays it here, so incremental jobs must not
  // emit outgoing light; first light never decreases, so INITIAL keeps the production flags.
  const emitOutgoing = !previous;
  const input = new Uint8Array(codec.lightColumn({ops: previous ? codec.OPS.CHECKS : codec.OPS.INITIAL,
    sky: manifest.sky, block: true, skyLightOn: true, blockLightOn: true, pullRing: true, emitOutgoing,
    chunkX: column.x, chunkZ: column.z, minSection: manifest.minSection, sectionCount: count,
    skyBottomSection: manifest.minSection - 1, table, tableEpoch: 1, checks,
    column: {flags: column.flags, sections: column.sections, ...(before ? {block: before.block, sky: before.sky} : {})}, neighbours}, caseIndex + 1));
  jobs.set(name, input);
}
let nativeOutput = null;
if (options["native-runner"]) {
  const jobDirectory = join(resolve(options.out), "jobs");
  nativeOutput = join(resolve(options.out), "native");
  mkdirSync(jobDirectory, {recursive: true});
  for (const [name, input] of jobs) writeFileSync(join(jobDirectory, `${name}.job`), input);
  read(resolve(options["native-runner"]));
  execFileSync(resolve(options["native-runner"]), [jobDirectory, nativeOutput], {windowsHide: true});
}
const kernelDirectory = join(resolve(options.dist), "kernels");
const artifactManifest = JSON.parse(read(join(kernelDirectory, "kernels.json")));
for (const variant of [...(nativeOutput ? ["native"] : []), "simd", "baseline"]) {
  let exports = null;
  if (variant !== "native") {
    const modulePath = join(resolve(options.dist), `kernels/light.${variant}.wasm`);
    const moduleBytes = read(modulePath);
    const metadata = artifactManifest.kernels.light.variants[variant];
    assert.equal(metadata.file || metadata.path, `light.${variant}.wasm`);
    assert.equal(metadata.bytes, moduleBytes.length, "wasm manifest size");
    assert.equal(metadata.sha256, hashes[modulePath], "wasm manifest digest");
    assert.ok(WebAssembly.validate(moduleBytes), `invalid ${modulePath}`);
    ({exports} = new WebAssembly.Instance(new WebAssembly.Module(moduleBytes), {}));
    assert.equal(exports.gaius_abi_version(), 1);
  }
  for (const [caseIndex, name] of manifest.cases.entries()) {
    const column = columns.get(name);
    const jobId = caseIndex + 1;
    const input = jobs.get(name);
    let output;
    if (variant === "native") {
      output = new Uint8Array(read(join(nativeOutput, `${name}.native`)));
    } else {
      const pointer = exports.alloc(input.length) >>> 0;
      new Uint8Array(exports.memory.buffer, pointer, input.length).set(input);
      const descriptor = exports.run_light_column(pointer, input.length) >>> 0;
      const view = new DataView(exports.memory.buffer);
      const status = view.getUint32(descriptor, true);
      output = new Uint8Array(exports.memory.buffer, view.getUint32(descriptor + 4, true),
        view.getUint32(descriptor + 8, true)).slice();
      exports.release(descriptor);
      exports.dealloc(pointer, input.length);
      assert.equal(status, 0, new TextDecoder().decode(output));
    }
    const actual = codec.readLightColumn(output.buffer, jobId);
    const {mismatches, samples} = compareLightColumn(column, actual, manifest);
    const result = {variant, name, cellsPerLayer: (count + 2) * 4096, mismatches, samples,
      jobSha256: createHash("sha256").update(input).digest("hex"),
      resultSha256: createHash("sha256").update(output).digest("hex")};
    results.push(result);
    if (mismatches.block || mismatches.sky) console.log(`LIGHT_JVM_WASM ${variant} ${name} block=${mismatches.block} sky=${mismatches.sky}`);
  }
}
const pass = results.every((result) => result.mismatches.block === 0 && result.mismatches.sky === 0);
mkdirSync(options.out, {recursive: true});
writeFileSync(join(options.out, "light-jvm-wasm-golden.json"), JSON.stringify({profile: options.profile,
  verdict: pass ? "PASS" : "FAIL", reference: "vanilla JVM LevelLightEngine", fixtures: fixtureRoot,
  fixtureKind: manifest.kind, seed: manifest.seed, stage: manifest.stage || "initial", incremental: !!previous,
  boundaryCondition: previous
    ? "settled vanilla neighbour layers with PULL_RING and no EMIT_OUTGOING; incremental centers use previous JVM layers and changed-border checks"
    : "settled vanilla neighbour layers with PULL_RING; first light with the production EMIT_OUTGOING flag",
  emitOutgoing: !previous, dist: resolve(options.dist), hashes, results, observedAt: new Date().toISOString()}, null, 2) + "\n");
process.exitCode = pass ? 0 : 1;
console.log(`LIGHT_JVM_WASM ${pass ? "PASS" : "FAIL"} stage=${manifest.stage || "initial"} columns=${manifest.cases.length} variants=${nativeOutput ? 3 : 2}`);
