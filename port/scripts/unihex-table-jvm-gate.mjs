#!/usr/bin/env node
// JVM gate of the build-time unihex glyph table (port/mc/tests/unihex-table/README.md).
//
//   node port/scripts/unihex-table-jvm-gate.mjs <scratch> [--profile 26.3] [--work <dir>] [--pack <vanilla-assets.pack.gz>]
//
// Builds the table with dev.gaius.tools.UnihexTableBuilder (vanilla as the oracle), writes the
// real browser font patches of the vanilla jar (UnihexGatePatcher), compiles the Gaius loader
// from the profile's staged sources, and runs port/mc/tests/unihex-table/UnihexGate.java in one
// JVM per arm: vanilla (unpatched jar, no Gaius class), the table, and the fallbacks (table
// switched off, a zip hash mismatch, a zip length mismatch, a corrupt header, a zip stream that is
// not the archive's, the zips replaced by a resource pack). Every arm's provider and font set dumps must equal vanilla's byte for
// byte; the table arm must take the table for every load and the fallback arms never.
// Offline; the vanilla jar and libraries come from <work> (default port/work/<profile>, or
// GAIUS_WORK_ROOT/<profile>), the font assets from --pack when given, else from the client jar and
// the SHA-1-checked asset index objects (the files build-teavm.sh puts into the pack).
import {execFileSync, spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync} from "node:fs";
import {delimiter, dirname, join, resolve} from "node:path";
import {homedir} from "node:os";
import {fileURLToPath} from "node:url";
import {gunzipSync} from "node:zlib";

const root = fileURLToPath(new URL("../../", import.meta.url));
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  if (index < 0) return fallback;
  const value = args[index + 1];
  args.splice(index, 2);
  return value;
};
const profile = option("--profile", "26.3");
const workOption = option("--work", null);
const packOption = option("--pack", null);
const keep = args.includes("--keep-dumps");
const positional = args.filter((arg) => !arg.startsWith("--"));
if (positional.length !== 1) {
  console.error("usage: unihex-table-jvm-gate.mjs <scratch> [--profile P] [--work DIR] [--pack FILE] [--keep-dumps]");
  process.exit(2);
}
const scratch = resolve(positional[0]);
const work = resolve(workOption ?? join(process.env.GAIUS_WORK_ROOT ?? join(root, "port/work"), profile));
const jar = join(work, "client-named.jar");
if (!existsSync(jar)) throw new Error(`no vanilla client jar ${jar}`);
rmSync(scratch, {recursive: true, force: true});
mkdirSync(scratch, {recursive: true});
const maven = process.env.GAIUS_MAVEN_REPOSITORY ?? join(homedir(), ".m2/repository");
const teavmVersion = JSON.parse(readFileSync(join(root, "port/config.json"), "utf8")).teaVMVersion;
const asm = ["asm", "asm-tree"].map((name) => join(maven, "org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
const teavm = ["teavm-interop", "teavm-jso", "teavm-jso-apis"].map((name) =>
  join(maven, "org/teavm", name, teavmVersion, `${name}-${teavmVersion}.jar`));
for (const file of [...asm, ...teavm]) if (!existsSync(file)) throw new Error(`missing ${file} (offline Maven repository)`);

function jarsUnder(directory) {
  const result = [];
  const walk = (path) => {
    for (const entry of readdirSync(path, {withFileTypes: true})) {
      const child = join(path, entry.name);
      if (entry.isDirectory()) walk(child);
      else if (entry.name.endsWith(".jar")) result.push(child);
    }
  };
  walk(directory);
  return result.sort();
}
const libraries = jarsUnder(join(work, "libraries"));
const run = (tool, toolArgs, options = {}) => {
  const result = spawnSync(tool, toolArgs, {cwd: scratch, encoding: "utf8", maxBuffer: 1 << 28, ...options});
  if (result.status !== 0) {
    process.stderr.write(result.stdout ?? "");
    process.stderr.write(result.stderr ?? "");
    throw new Error(`${tool} ${toolArgs.slice(0, 3).join(" ")} ... exited ${result.status}`);
  }
  return result.stdout;
};
const sha = (data, algorithm = "sha256") => createHash(algorithm).update(data).digest("hex");

// --- font assets -------------------------------------------------------------------------------
const pack = join(scratch, "pack");
const FONT_ASSET = /^assets\/minecraft\/(font\/|textures\/font\/)/;
function write(path, data) {
  mkdirSync(dirname(path), {recursive: true});
  writeFileSync(path, data);
}
let assetSource;
if (packOption) {
  const raw = gunzipSync(readFileSync(packOption));
  if (raw.subarray(0, 8).toString("latin1") !== "GAIUSVP1") throw new Error(`${packOption} is not a GAIUSVP1 pack`);
  const indexLength = raw.readUInt32LE(8);
  const index = JSON.parse(raw.subarray(12, 12 + indexLength).toString("utf8"));
  const dataOffset = 12 + indexLength;
  let count = 0;
  for (const [name, [offset, length]] of Object.entries(index)) {
    if (!FONT_ASSET.test(name)) continue;
    write(join(pack, name), raw.subarray(dataOffset + offset, dataOffset + offset + length));
    count++;
  }
  assetSource = {pack: resolve(packOption), packSha256: sha(readFileSync(packOption)), entries: count};
} else {
  run("unzip", ["-q", "-o", jar, "assets/minecraft/font/*", "assets/minecraft/textures/font/*", "-d", pack]);
  const version = JSON.parse(readFileSync(join(work, "version.json"), "utf8"));
  const indexId = version.assetIndex?.id ?? version.assets;
  const assetIndex = JSON.parse(readFileSync(join(work, "assets/indexes", `${indexId}.json`), "utf8"));
  const names = ["minecraft/font/include/unifont.json", "minecraft/font/include/unifont_pua.json",
    "minecraft/font/unifont.zip", "minecraft/font/unifont_jp.zip", "minecraft/font/unifont_pua.zip"];
  for (const name of names) {
    const hash = assetIndex.objects[name]?.hash;
    if (!hash) throw new Error(`asset index ${indexId} has no ${name}`);
    const data = readFileSync(join(work, "assets/objects", hash.slice(0, 2), hash));
    if (sha(data, "sha1") !== hash) throw new Error(`SHA-1 mismatch for ${name}`);
    write(join(pack, "assets", name), data);
  }
  assetSource = {jar, assetIndex: indexId, objects: names};
}
const zips = readdirSync(join(pack, "assets/minecraft/font")).filter((name) => name.endsWith(".zip")).sort();
const zipInfo = Object.fromEntries(zips.map((name) => {
  const data = readFileSync(join(pack, "assets/minecraft/font", name));
  return [name, {bytes: data.length, sha256: sha(data)}];
}));

// --- compile -----------------------------------------------------------------------------------
const toolClasses = join(scratch, "tool-classes");
mkdirSync(toolClasses);
const toolSources = join(root, "port/tools/src/main/java");
run("javac", ["-J-Xmx1g", "--release", "21", "-proc:none", "-nowarn", "-cp", asm.join(delimiter), "-sourcepath", toolSources,
  "-d", toolClasses, join(toolSources, "dev/gaius/tools/UnihexTableBuilder.java"),
  join(root, "port/mc/tests/unihex-table/UnihexGatePatcher.java")]);

const table = join(scratch, "gaius-unihex.bin");
const builder = run("java", ["-cp", toolClasses, "dev.gaius.tools.UnihexTableBuilder", "--client-jar", jar,
  "--libraries", join(work, "libraries"),
  ...zips.flatMap((name) => ["--entry", `assets/minecraft/font/${name}=${join(pack, "assets/minecraft/font", name)}`]),
  "--out", table]);
const builderSummary = JSON.parse(builder.trim().split("\n").pop());

const overlay = join(scratch, "overlay");
run("java", ["-cp", [toolClasses, ...asm].join(delimiter), `-Dgaius.patch.input=${jar}`, "UnihexGatePatcher", jar, overlay]);

const stage = join(scratch, "stage");
run("node", [join(root, "port/tools/sourcesets/sourcesets.mjs"), "stage", "--profile", profile, "--kind", "src",
  "--target", "wasmgc", "--out", join(stage, "src")]);
run("node", [join(root, "port/tools/sourcesets/sourcesets.mjs"), "stage", "--profile", profile, "--kind", "client",
  "--target", "wasmgc", "--out", join(stage, "client")]);
const runtime = join(scratch, "runtime-classes");
mkdirSync(runtime);
const runtimeSources = [
  join(stage, "src/net/minecraft/client/gui/font/providers/BrowserUnihexLoader.java"),
  join(stage, "src/dev/gaius/browser/UnihexGlyphTable.java"),
  join(stage, "src/dev/gaius/browser/BrowserFontProviderIndex.java"),
  join(stage, "client/dev/gaius/browser/ArchivedStreams.java"),
  join(root, "port/overrides/classlib/src/main/java/dev/gaius/browser/JsArrays.java"),
];
// VersionOps of the profile's generation pulls the whole browser runtime into a compile; the gate
// compiles the interface with only its glyph upload, whose body is copied verbatim from the
// generation's staged implementation (the class VersionOps.INSTANCE names).
const opsSource = readFileSync(join(stage, "src/dev/gaius/browser/ops/VersionOps.java"), "utf8");
const implementation = /INSTANCE\s*=\s*new\s+([\w.]+)\(\)/.exec(opsSource)[1];
const implementationSource = readFileSync(join(stage, "src", implementation.replaceAll(".", "/") + ".java"), "utf8");
const upload = /\n(\s*@Override\s*\n\s*public void uploadGlyph\([^)]*\)\s*\{[\s\S]*?\n    \})/.exec(implementationSource);
if (!upload) throw new Error(`no uploadGlyph in ${implementation}`);
const imports = implementationSource.split("\n").filter((line) => /^import (com\.mojang|java\.nio)\./.test(line));
const uploadSignature = /void uploadGlyph\([^)]*\)/.exec(opsSource)[0];
const gateOps = join(scratch, "gate-ops/dev/gaius/browser/ops");
mkdirSync(gateOps, {recursive: true});
writeFileSync(join(gateOps, "VersionOps.java"), ["package dev.gaius.browser.ops;", "", ...imports, "",
  "/** Unihex gate copy: VersionOps with the glyph upload of " + implementation + " only. */",
  "public interface VersionOps {", "    VersionOps INSTANCE = new GateVersionOps();", "",
  `    ${uploadSignature};`, "}", "", "final class GateVersionOps implements VersionOps {", upload[1], "}", ""].join("\n"));
runtimeSources.push(join(gateOps, "VersionOps.java"));
const vanillaClasspath = [jar, ...libraries];
run("javac", ["-J-Xmx2g", "--release", "21", "-proc:none", "-nowarn",
  "-cp", [overlay, ...vanillaClasspath, ...teavm].join(delimiter),
  "-d", runtime, ...runtimeSources]);
// Only Gaius classes may come out of the runtime compile: a vanilla class compiled from a staged
// override would shadow the jar in the browser arms.
const vanillaEntries = new Set(execFileSync("unzip", ["-Z1", jar], {encoding: "utf8", maxBuffer: 1 << 28}).split("\n"));
const shadowing = [];
const walkClasses = (directory, prefix) => {
  for (const entry of readdirSync(directory, {withFileTypes: true})) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) walkClasses(join(directory, entry.name), relative);
    else if (vanillaEntries.has(relative)) shadowing.push(relative);
  }
};
walkClasses(runtime, "");
if (shadowing.length) throw new Error(`runtime compile produced vanilla classes: ${shadowing.join(", ")}`);

const harness = join(scratch, "harness-classes");
mkdirSync(harness);
run("javac", ["-J-Xmx1g", "--release", "21", "-proc:none", "-nowarn", "-cp", vanillaClasspath.join(delimiter),
  "-d", harness, join(root, "port/mc/tests/unihex-table/UnihexGate.java")]);

// --- arms --------------------------------------------------------------------------------------
const lwjgl = join(scratch, "lwjgl");
const jvm = ["-Xmx6g", "-XX:+UseParallelGC", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED",
  "-Djava.awt.headless=true", `-Dorg.lwjgl.system.SharedLibraryExtractPath=${lwjgl}`];
const browserClasspath = [harness, runtime, overlay, ...vanillaClasspath, ...teavm].join(delimiter);
// A resource pack above vanilla that ships the same font zips.
const userPack = join(scratch, "user-pack");
for (const name of zips) write(join(userPack, "assets/minecraft/font", name), readFileSync(join(pack, "assets/minecraft/font", name)));
const arms = [
  {name: "vanilla", classpath: [harness, ...vanillaClasspath].join(delimiter), props: []},
  {name: "table", classpath: browserClasspath, props: [`-Dgate.table=${table}`], expect: "hits"},
  {name: "disabled", classpath: browserClasspath, props: ["-Dgate.table=disabled"], expect: "fallback"},
  {name: "sha-mismatch", classpath: browserClasspath, props: [`-Dgate.table=${table}`, "-Dgate.tamper=sha"], expect: "fallback"},
  {name: "length-mismatch", classpath: browserClasspath, props: [`-Dgate.table=${table}`, "-Dgate.tamper=length"], expect: "fallback"},
  {name: "bad-header", classpath: browserClasspath, props: [`-Dgate.table=${table}`, "-Dgate.tamper=header"], expect: "fallback"},
  {name: "not-archived", classpath: browserClasspath, props: [`-Dgate.table=${table}`, "-Dgate.archived=false"], expect: "fallback"},
  {name: "user-pack", classpath: browserClasspath, props: [`-Dgate.table=${table}`, `-Dgate.userPack=${userPack}`], expect: "fallback"},
];
const modes = ["providers", "fontsets"];
const results = [];
let failed = false;
for (const mode of modes) {
  let reference = null;
  for (const arm of arms) {
    const dump = join(scratch, `${mode}-${arm.name}.txt.gz`);
    const started = Date.now();
    const output = run("java", [...jvm, ...arm.props, "-cp", arm.classpath, "UnihexGate", mode, pack, dump]);
    const seconds = (Date.now() - started) / 1000;
    const digest = /UNIHEX_GATE_DUMP mode=\S+ sha256=([0-9a-f]+) lines=(\d+)/.exec(output);
    const counters = /UNIHEX_TABLE hits=(\d+) fallbacks=(\d+) reasons=(.*)/.exec(output);
    if (!digest) throw new Error(`no dump digest from ${arm.name}/${mode}`);
    const result = {mode, arm: arm.name, sha256: digest[1], lines: Number(digest[2]), seconds,
      hits: counters ? Number(counters[1]) : null, fallbacks: counters ? Number(counters[2]) : null,
      reasons: counters ? counters[3] : null};
    if (arm.name === "vanilla") reference = result;
    result.equalsVanilla = result.sha256 === reference.sha256;
    if (arm.expect === "hits") result.pathOk = result.hits > 0 && result.fallbacks === 0;
    else if (arm.expect === "fallback") result.pathOk = result.hits === 0 && result.fallbacks > 0;
    else result.pathOk = counters === null;
    result.pass = result.equalsVanilla && result.pathOk;
    if (!result.pass) failed = true;
    console.log(JSON.stringify(result));
    results.push(result);
    if (!keep && arm.name !== "vanilla" && result.pass) rmSync(dump);
  }
  if (!keep && !failed) rmSync(join(scratch, `${mode}-vanilla.txt.gz`));
}
const report = {profile, work, assets: assetSource, zips: zipInfo, table: builderSummary, results,
  verdict: failed ? "FAIL" : "PASS"};
writeFileSync(join(scratch, "report.json"), JSON.stringify(report, null, 2) + "\n");
rmSync(join(scratch, "stage"), {recursive: true, force: true});
rmSync(lwjgl, {recursive: true, force: true});
console.log(`UNIHEX_TABLE_JVM_GATE ${report.verdict} (${results.filter((r) => r.pass).length}/${results.length} arm dumps)`);
process.exit(failed ? 1 : 0);
