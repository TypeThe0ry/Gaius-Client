#!/usr/bin/env node
// Light route harness: first light of the seeded light golden world through the profile's
// patched ThreadedLevelLightEngine, BrowserLightKernel and the shipped light kernel, compared
// byte for byte with the vanilla engine. See README.md.
//
//   node route.mjs --profile <id> --out <dir> --dist <dist> --patched <patch chain output>
//       [--fixtures <dump.mjs fixtures dir>] [--seeds <csv>] [--java-heap 3g]
//
// --patched is the class output of the profile's patch chain for the target that applies the
// light kernel patches (for example the <profile>-wasmgc/out directory of
// `patchkit.mjs check --keep`); only its ThreadedLevelLightEngine is put ahead of the vanilla jar.
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {delimiter, dirname, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {homedir} from "node:os";
import {spawnSync} from "node:child_process";
import {waitForHeadroom} from "../headroom.mjs";
import {resolveSources, sourceFile} from "../../../../tools/sourcesets/sourcesets.mjs";

const root = fileURLToPath(new URL("../../../../..", import.meta.url));
const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const option = (name, fallback = null) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const profileId = option("--profile");
assert(profileId && option("--out") && option("--dist") && option("--patched"),
  "usage: route.mjs --profile <id> --out <dir> --dist <dist> --patched <dir> [--fixtures <dir>] [--seeds <csv>]");
const out = resolve(option("--out"));
const dist = resolve(option("--dist"));
const patched = resolve(option("--patched"));
const fixtures = option("--fixtures") ? resolve(option("--fixtures")) : null;
const seeds = option("--seeds", "12345,20261008").split(",");
assert(seeds.length >= 2 && seeds.every((seed) => /^-?\d+$/.test(seed)), "two or more integer seeds required");
const minSection = Number(option("--min-section", "-4"));
const sectionCount = Number(option("--section-count", "24"));
const heap = option("--java-heap", "2g");
assert(/^(?:[1-9]\d*m|[123]g)$/.test(heap) && (!heap.endsWith("m") || Number.parseInt(heap) <= 3072), "JVM heap must not exceed 3 GiB");
mkdirSync(out, {recursive: true});
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const hashes = {};
const read = (path) => {
  const bytes = readFileSync(path);
  hashes[relative(root, path).replaceAll("\\", "/")] = hash(bytes);
  return bytes;
};

function run(command, parameters, label) {
  waitForHeadroom(out, label);
  const result = spawnSync(command, parameters, {cwd: out, windowsHide: true, encoding: "utf8", maxBuffer: 16 * 1024 * 1024});
  writeFileSync(join(out, `${label}.log`), `${result.stdout || ""}\n${result.stderr || ""}`);
  assert(!result.error && result.status === 0, `${label}: ${String(result.error || result.stderr).slice(-2500)}`);
  return result.stdout;
}

// Vanilla jar and libraries, as dump.mjs uses them.
const profile = JSON.parse(readFileSync(join(root, "port/versions", `${profileId}.json`)));
const work = join(root, "port/work", profile.id);
const jar = join(work, "client-named.jar");
assert(existsSync(jar), "local vanilla named jar missing; no downloads");
const vanillaSha256 = hash(readFileSync(jar));
const javaPath = (path) => process.platform === "win32"
  ? path.replace(/^\/([a-z])\//i, (_, drive) => `${drive.toUpperCase()}:/`) : path;
const libraries = readFileSync(join(work, "classpath.txt"), "utf8").trim().split(":").map(javaPath);
const teavmVersion = JSON.parse(readFileSync(join(root, "port/config.json"))).teaVMVersion;
const jso = join(process.env.GAIUS_MAVEN_REPOSITORY || join(homedir(), ".m2/repository"),
  `org/teavm/teavm-jso/${teavmVersion}/teavm-jso-${teavmVersion}.jar`);
assert(existsSync(jso), `missing ${jso}; no downloads`);
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const javaTool = (name) => javaHome ? join(javaHome, "bin", `${name}${process.platform === "win32" ? ".exe" : ""}`) : name;

// The light kernel bridge exactly as the profile compiles it (source sets, relocations and the
// generation's ops), for the target the patched class comes from, with the JVM host stand-in.
const target = option("--target", "wasmgc");
const sources = join(out, "sources");
const staged = [];
const resolution = resolveSources(profile.id, "src", target);
for (const [rel] of resolution.files) {
  const bridge = rel.startsWith("dev/gaius/browser/kernel/light/") || rel === "net/minecraft/world/level/lighting/GaiusLightColumnAccess.java";
  if (!bridge || rel.endsWith("/LightKernelHost.java")) continue;
  const file = sourceFile(profile.id, "src", rel, {target});
  const destination = join(sources, rel);
  mkdirSync(dirname(destination), {recursive: true});
  writeFileSync(destination, file.text);
  hashes[relative(root, file.path).replaceAll("\\", "/")] = hash(readFileSync(file.path));
  staged.push({rel, origin: file.origin, relocated: file.relocated, sha256: hash(file.text)});
}
assert(staged.some((entry) => entry.rel.endsWith("/BrowserLightKernel.java")), "the profile does not compile the light kernel bridge");
const harness = [join(here, "LightRoute.java"), join(here, "LightKernelJvmTest.java"),
  join(here, "host/dev/gaius/browser/kernel/light/LightKernelHost.java")];
for (const path of harness) read(path);
// These two sources sit outside the light source set but are part of the Java contract tested by
// this route. Keep them on javac's explicit list so the harness cannot silently fall back to a JS model.
for (const rel of ["dev/gaius/browser/kernel/KernelStatusFrontier.java",
  "dev/gaius/browser/kernel/light/CompletionFrontier.java",
  "dev/gaius/browser/kernel/light/InFlightReleaseGate.java"]) {
  const file = join(root, "port/src/main/java", rel);
  assert(existsSync(file), `${file} missing`);
  const destination = join(sources, rel);
  mkdirSync(dirname(destination), {recursive: true});
  copyFileSync(file, destination);
  hashes[relative(root, file).replaceAll("\\", "/")] = hash(readFileSync(file));
  staged.push({rel, origin: file, relocated: false, sha256: hash(readFileSync(file))});
}
const classes = join(out, "classes");
mkdirSync(classes, {recursive: true});
const compileClasspath = [jar, ...libraries, jso].join(delimiter);
const javacArguments = ["-encoding", "UTF-8", "-proc:none", "-nowarn", "-cp", compileClasspath, "-d", classes,
  ...harness, ...staged.map((entry) => join(sources, entry.rel))];
writeFileSync(join(out, "javac.args"), javacArguments.map((value) => `"${value.replaceAll("\\", "/")}"`).join("\n"));
run(javaTool("javac"), [`-J-Xmx${heap}`, "-J-XX:ActiveProcessorCount=1", "-J-XX:+UseSerialGC",
  "-J-Duser.language=en", `@${join(out, "javac.args")}`], "javac");
run(javaTool("java"), [`-Xmx${heap}`, "-cp", [classes, jar, ...libraries, jso].join(delimiter),
  "dev.gaius.browser.kernel.light.LightKernelJvmTest"], "jvm-contract-test");

// Only the patched light engine class goes ahead of the jar: the other classes of the chain
// are browser shims that a JVM cannot run.
const engineClass = "net/minecraft/server/level/ThreadedLevelLightEngine.class";
const patchedClass = join(patched, engineClass);
assert(existsSync(patchedClass), `${patchedClass} missing: the chain did not patch the light engine`);
const engineBytes = read(patchedClass);
assert(engineBytes.includes(Buffer.from("dev/gaius/browser/kernel/light/BrowserLightEngineHooks")),
  "the patched light engine does not implement BrowserLightEngineHooks");
const engineRoot = join(out, "patched-engine");
mkdirSync(dirname(join(engineRoot, engineClass)), {recursive: true});
copyFileSync(patchedClass, join(engineRoot, engineClass));

const node = process.execPath;
const bridge = [node, join(here, "kernel-bridge.mjs"), "--dist", dist, "--variant"];
for (const variant of ["simd", "baseline"]) read(join(dist, `kernels/light.${variant}.wasm`));
read(join(dist, "kernels/kernels.json"));
const modes = [
  {mode: "vanilla", patched: false, properties: {}},
  {mode: "kernel-off", patched: true, properties: {"gaius.lightRoute.kernel": "off"}},
  {mode: "simd", patched: true, properties: {"gaius.lightRoute.kernel": "on", variant: "simd"}},
  {mode: "baseline", patched: true, properties: {"gaius.lightRoute.kernel": "on", variant: "baseline"}},
  {mode: "simd-out-of-order", patched: true, properties: {"gaius.lightRoute.kernel": "on", variant: "simd",
    "gaius.lightRoute.outOfOrder": "true"}},
  {mode: "simd-one-slot", patched: true, properties: {"gaius.lightRoute.kernel": "on", variant: "simd",
    "gaius.lightRoute.maxInFlight": "1"}},
  {mode: "simd-transient", patched: true, properties: {"gaius.lightRoute.kernel": "on", variant: "simd",
    "gaius.lightRoute.fail": "every:3:backpressure"}},
  {mode: "simd-errors", patched: true, properties: {"gaius.lightRoute.kernel": "on", variant: "simd",
    "gaius.lightRoute.fail": "all:kernel-error"}},
];

function readColumns(directory) {
  const columns = new Map();
  for (const name of readdirSync(directory).filter((entry) => entry.endsWith(".light"))) {
    const column = name.slice(0, -".light".length);
    columns.set(column, {
      light: readFileSync(join(directory, name)),
      flags: readFileSync(join(directory, `${column}.flags`)),
      states: existsSync(join(directory, `${column}.states`)) ? readFileSync(join(directory, `${column}.states`)) : null,
    });
  }
  return columns;
}

function compare(expected, actual) {
  let nibbles = 0;
  let mismatches = 0;
  let flagMismatches = 0;
  let stateMismatches = 0;
  assert.deepEqual([...actual.keys()].sort(), [...expected.keys()].sort(), "column sets differ");
  for (const [name, reference] of expected) {
    const column = actual.get(name);
    assert.equal(column.light.length, reference.light.length);
    for (let index = 0; index < reference.light.length; index++) {
      const a = reference.light[index];
      const b = column.light[index];
      nibbles += 2;
      mismatches += ((a & 15) !== (b & 15)) + ((a >> 4) !== (b >> 4));
    }
    for (let index = 0; index < reference.flags.length; index++) flagMismatches += reference.flags[index] !== column.flags[index];
    if (reference.states && column.states) stateMismatches += Buffer.compare(reference.states, column.states) !== 0;
  }
  return {columns: expected.size, nibbles, mismatches, flagMismatches, stateMismatches};
}

const report = {schema: 1, profile: profile.id, target, vanillaSha256, dist, patched: patchedClass,
  patchedSha256: hash(engineBytes), staged, seeds: [], hashes};
let pass = true;
for (const seed of seeds) {
  const entry = {seed, modes: {}};
  for (const config of modes) {
    const directory = join(out, seed, config.mode);
    const classpath = [...(config.patched ? [engineRoot] : []), classes, jar, ...libraries, jso].join(delimiter);
    const properties = Object.entries(config.properties).filter(([name]) => name !== "variant")
      .map(([name, value]) => `-D${name}=${value}`);
    if (config.properties.variant) properties.push(`-Dgaius.lightRoute.bridge=${[...bridge, config.properties.variant].join("|")}`);
    properties.push(`-Dgaius.lightRoute.stats=${join(directory, "host.json")}`);
    mkdirSync(directory, {recursive: true});
    const parameters = [`-Xmx${heap}`, "-XX:+UseSerialGC", "-Djava.awt.headless=true", "-Dmax.bg.threads=1",
      ...properties, "-cp", classpath, "dev.gaius.golden.light.LightRoute", directory, profile.id, vanillaSha256,
      seed, String(minSection), String(sectionCount), config.mode];
    const argumentFile = join(out, `java-${seed}-${config.mode}.args`);
    writeFileSync(argumentFile, parameters.map((value) => `"${value.replaceAll("\\", "/")}"`).join("\n"));
    console.log(run(javaTool("java"), [`@${argumentFile}`], `java-${seed}-${config.mode}`).trim().split("\n").pop());
    entry.modes[config.mode] = {route: JSON.parse(readFileSync(join(directory, "route.json"))),
      host: existsSync(join(directory, "host.json")) ? JSON.parse(readFileSync(join(directory, "host.json"))) : null};
  }
  const vanilla = readColumns(join(out, seed, "vanilla"));
  for (const config of modes.slice(1)) {
    const result = compare(vanilla, readColumns(join(out, seed, config.mode)));
    entry.modes[config.mode].versusVanilla = result;
    pass &&= result.mismatches === 0 && result.flagMismatches === 0 && result.stateMismatches === 0;
  }
  if (fixtures) {
    const directory = join(fixtures, seed, "initial");
    const manifest = JSON.parse(read(join(directory, "fixtures.json")));
    assert.equal(manifest.sourceSha256, vanillaSha256, "fixtures come from another vanilla jar");
    assert.equal(manifest.minSection, minSection);
    assert.equal(manifest.sectionCount, sectionCount);
    const golden = readColumns(directory);
    entry.versusLightGolden = compare(golden, vanilla);
    pass &&= entry.versusLightGolden.mismatches === 0 && entry.versusLightGolden.flagMismatches === 0
      && entry.versusLightGolden.stateMismatches === 0;
  }
  // The kernel must really have lit the columns where it was on, and declined nothing else.
  const columns = vanilla.size;
  const counters = (mode) => entry.modes[mode].route;
  pass &&= counters("vanilla").jobs === 0 && counters("kernel-off").jobs === 0 && counters("kernel-off").fallbacks === 0;
  for (const mode of ["simd", "baseline", "simd-one-slot", "simd-out-of-order"]) {
    pass &&= counters(mode).jobs === columns && counters(mode).applied === columns && counters(mode).fallbacks === 0;
  }
  pass &&= counters("simd-transient").applied + counters("simd-transient").fallbacks === columns
    && counters("simd-transient").fallbacks > 0 && counters("simd-transient").kernelEnabled;
  pass &&= counters("simd-errors").applied === 0 && !counters("simd-errors").kernelEnabled;
  report.seeds.push(entry);
  console.log(`LIGHT_ROUTE_SEED ${seed} ${JSON.stringify(Object.fromEntries(Object.entries(entry.modes)
    .map(([mode, value]) => [mode, {jobs: value.route.jobs, applied: value.route.applied, fallbacks: value.route.fallbacks,
      mismatches: value.versusVanilla?.mismatches ?? null}])))}`);
}
report.verdict = pass ? "PASS" : "FAIL";
writeFileSync(join(out, "route-report.json"), JSON.stringify(report, null, 2) + "\n");
console.log(`LIGHT_ROUTE ${report.verdict} profile=${profile.id} seeds=${seeds.length} modes=${modes.length}`);
process.exitCode = pass ? 0 : 1;
