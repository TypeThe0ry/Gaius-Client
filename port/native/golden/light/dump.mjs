#!/usr/bin/env node
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {delimiter, dirname, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {spawnSync} from "node:child_process";
import {waitForHeadroom} from "./headroom.mjs";
import {resolveSources, sourceFile} from "../../../tools/sourcesets/sourcesets.mjs";

const root = fileURLToPath(new URL("../../../..", import.meta.url));
const args = process.argv.slice(2);
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const profileId = option("--profile");
const out = resolve(option("--out", ""));
assert(profileId && option("--out"), "usage: dump.mjs --profile <id> --out <dir> [--seeds <csv>] [--java-heap <heap>]");
const profile = JSON.parse(readFileSync(join(root, "port/versions", `${profileId}.json`)));
const seedArguments = option("--seeds", "12345,20261008").split(",");
assert(seedArguments.every((seed) => /^-?\d+$/.test(seed)), "integer seeds required");
const seeds = seedArguments.map((seed) => BigInt(seed).toString());
assert(seeds.every((seed) => BigInt(seed) >= -(1n << 63n) && BigInt(seed) < (1n << 63n)), "seed exceeds Java long");
assert(seeds.length >= 2 && new Set(seeds).size === seeds.length, "two or more distinct integer seeds required");
const minSection = Number(option("--min-section", "-4"));
const sectionCount = Number(option("--section-count", "24"));
assert(Number.isInteger(minSection) && Number.isInteger(sectionCount) && sectionCount >= 1 && sectionCount <= 254);
assert(seeds.length * sectionCount * 9 >= 300, "at least 300 distinct initial sections required");
const heap = option("--java-heap", "2g");
assert(/^(?:[1-9]\d*m|[123]g)$/.test(heap) && (!heap.endsWith("m") || Number.parseInt(heap) <= 3072), "JVM heap must not exceed 3 GiB");
mkdirSync(out, {recursive: true});
function run(command, parameters, label) {
  waitForHeadroom(out, label);
  const result = spawnSync(command, parameters, {cwd: out, windowsHide: true, encoding: "utf8", maxBuffer: 2 * 1024 * 1024});
  writeFileSync(join(out, `${label}.log`), `${result.stdout || ""}\n${result.stderr || ""}`);
  assert(!result.error && result.status === 0, `${label}: ${String(result.error || result.stderr).slice(-2500)}`);
  return result.stdout;
}
const work = join(root, "port/work", profile.id);
const jar = join(work, "client-named.jar");
assert(existsSync(jar), "local vanilla named jar missing; no downloads");
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const vanillaSha256 = hash(readFileSync(jar));
const javaPath = (path) => process.platform === "win32"
  ? path.replace(/^\/([a-z])\//i, (_, drive) => `${drive.toUpperCase()}:/`) : path;
const classpath = [jar, ...readFileSync(join(work, "classpath.txt"), "utf8").trim().split(":").map(javaPath)].join(delimiter);
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const javaTool = (name) => javaHome ? join(javaHome, "bin", `${name}${process.platform === "win32" ? ".exe" : ""}`) : name;
// The light table exporter is the production BrowserLightStateTable with the profile's light
// ops, staged from the source sets exactly as the profile compiles them (relocations applied).
const lightPackage = "dev/gaius/browser/kernel/light/";
const resolution = resolveSources(profile.id, "src", "wasmgc", root);
const exporter = [...resolution.files.keys()].filter((rel) => rel === `${lightPackage}BrowserLightStateTable.java`
  || (rel.startsWith(lightPackage) && rel.endsWith("Ops.java")));
assert(exporter.includes(`${lightPackage}BrowserLightStateTable.java`) && exporter.includes(`${lightPackage}LightVersionOps.java`),
  "the profile does not compile the light table exporter and its ops");
const harness = join(root, "port/native/golden/light/LightGolden.java");
mkdirSync(join(out, "sources"), {recursive: true});
mkdirSync(join(out, "classes"), {recursive: true});
const staged = {};
const sources = {[relative(root, harness).replaceAll("\\", "/")]: hash(readFileSync(harness))};
const stagedPaths = exporter.map((rel) => {
  const file = sourceFile(profile.id, "src", rel, {target: "wasmgc", root});
  const path = join(out, "sources", rel);
  mkdirSync(dirname(path), {recursive: true});
  writeFileSync(path, file.text);
  sources[relative(root, file.path).replaceAll("\\", "/")] = hash(readFileSync(file.path));
  staged[rel] = hash(file.text);
  return path;
});
function verifySources() {
  for (const [path, digest] of Object.entries(sources)) assert.equal(hash(readFileSync(join(root, path))), digest, `source changed during dump: ${path}`);
  for (const [rel, digest] of Object.entries(staged)) assert.equal(hash(readFileSync(join(out, "sources", rel))), digest, `staged source changed during dump: ${rel}`);
}
const compile = ["-encoding", "UTF-8", "-cp", classpath, "-d", join(out, "classes"), harness, ...stagedPaths];
writeFileSync(join(out, "javac.args"), compile.map((value) => `"${value.replaceAll("\\", "/")}"`).join("\n"));
run(javaTool("javac"), [`-J-Xmx${heap}`, "-J-XX:ActiveProcessorCount=1", "-J-XX:+UseSerialGC",
  "-J-Duser.language=en", `@${join(out, "javac.args")}`], "javac");
verifySources();
const snapshots = [];
for (const seed of seeds) {
  const fixtures = join(out, "fixtures", seed);
  const parameters = [`-Xmx${heap}`, "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC",
    "-Djava.awt.headless=true", "-Dmax.bg.threads=1", "-cp", `${join(out, "classes")}${delimiter}${classpath}`,
    "dev.gaius.golden.light.LightGolden", fixtures, profile.id, vanillaSha256, seed, String(minSection), String(sectionCount)];
  const argumentFile = join(out, `java-${seed}.args`);
  writeFileSync(argumentFile, parameters.map((value) => `"${value.replaceAll("\\", "/")}"`).join("\n"));
  console.log(run(javaTool("java"), [`@${argumentFile}`], `java-${seed}`).trim());
  verifySources();
  for (const stage of ["initial", "emitter-add", "emitter-remove", "roof-close", "roof-open"]) {
    const directory = join(fixtures, stage);
    const manifest = JSON.parse(readFileSync(join(directory, "fixtures.json")));
    manifest.reference = "vanilla JVM LevelLightEngine";
    manifest.sources = sources;
    manifest.staged = staged;
    manifest.files = Object.fromEntries(readdirSync(directory).filter((name) => name !== "fixtures.json").sort()
      .map((name) => [name, hash(readFileSync(join(directory, name)))]));
    writeFileSync(join(directory, "fixtures.json"), JSON.stringify(manifest, null, 2) + "\n");
    snapshots.push({seed, stage, path: relative(out, directory).replaceAll("\\", "/"), sha256: hash(readFileSync(join(directory, "fixtures.json")))});
  }
}
writeFileSync(join(out, "corpus.json"), JSON.stringify({schema: 1, profile: profile.id, seeds, sectionCount,
  distinctInitialSections: seeds.length * sectionCount * 9, snapshots}, null, 2) + "\n");
