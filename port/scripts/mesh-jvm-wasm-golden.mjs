#!/usr/bin/env node
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {join, resolve, relative, delimiter} from "node:path";
import {fileURLToPath} from "node:url";
import {spawnSync} from "node:child_process";
import {compareSection} from "./mesh-golden-compare.mjs";
import {stageMeshJvmSources} from "./mesh-jvm-sources.mjs";
import {compiledText, resolveSources} from "../tools/sourcesets/sourcesets.mjs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const argumentsList = process.argv.slice(2);
const option = (name) => argumentsList.includes(name) ? argumentsList[argumentsList.indexOf(name) + 1] : null;
const profileId = option("--profile");
const output = option("--out");
if (!profileId || !output) throw new Error("usage: mesh-jvm-wasm-golden.mjs --profile <id> --out <dir> [--generate] [--dist <shipped-dist>] [--target <cargo-target>] [--quarantined]");
assert(/^[\w.-]+$/.test(profileId), "invalid profile identifier");
const profileBytes = readFileSync(join(root, "port/versions", `${profileId}.json`));
const profile = JSON.parse(profileBytes);
const artifactKind = option("--artifact-kind") || "shipped";
assert(["shipped", "built-candidate"].includes(artifactKind), "invalid artifact kind");
// --quarantined keeps the parity evidence of a profile whose mesher gate is held off by a recorded
// quarantine (the kernel is compiled in but vanilla meshes at runtime), so the gate can be lifted on
// evidence rather than on a temporarily edited profile. The report records the gate it ran under.
const quarantine = profile.kernelMirror?.quarantined?.mesher;
const quarantinedRun = argumentsList.includes("--quarantined");
if (quarantinedRun) {
  assert(profile.kernelMirror?.enabled?.mesher !== true && typeof quarantine === "string" && quarantine.length > 0,
    "--quarantined requires a profile whose mesher is disabled with a recorded quarantine");
} else {
  assert.equal(profile.kernelMirror?.enabled?.mesher, true, "mesher must be enabled in the selected profile");
}
const gate = quarantinedRun ? {enabled: false, quarantined: quarantine} : {enabled: true};
const out = resolve(output);
const fixtures = option("--fixtures") ? resolve(option("--fixtures")) : join(out, "fixtures");
mkdirSync(out, {recursive: true});
const nativeRoot = join(root, "port/native");
const target = resolve(option("--target") || join(out, "target"));
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, {cwd, encoding: "utf8", windowsHide: true,
    env: {...process.env, CARGO_TARGET_DIR: target}, maxBuffer: 8 * 1024 * 1024});
  writeFileSync(join(out, `${command.replace(/[^a-z]/gi, "_")}.log`), `${result.stdout || ""}\n${result.stderr || ""}`);
  if (result.error || result.status !== 0) throw new Error(`${command} failed (${result.status}): ${`${result.stdout || ""}\n${result.stderr || result.error?.message || ""}`.slice(-5000)}`);
  return result.stdout;
}
function javaPath(path) {
  return process.platform === "win32" ? path.replace(/^\/([a-z])\//i, (_, drive) => `${drive.toUpperCase()}:/`) : path;
}
if (argumentsList.includes("--generate")) {
  const work = join(root, "port/work", profile.id);
  const jar = join(work, "client-named.jar");
  assert(existsSync(jar), "local named vanilla jar missing; downloads are not allowed");
  const classpath = [jar, ...readFileSync(join(work, "classpath.txt"), "utf8").trim()
    .split(/;|:(?=\/|[A-Za-z]:\/)/u).filter(Boolean).map(javaPath)].join(delimiter);
  const harness = join(nativeRoot, "golden/mesh");
  const classes = join(out, "classes");
  mkdirSync(classes, {recursive: true}); mkdirSync(fixtures, {recursive: true});
  const javaFiles = [join(harness, "src/dev/gaius/browser/kernel/mesh/MeshGolden.java"),
    join(harness, "src/dev/gaius/browser/kernel/mesh/SnapshotTint.java")];
  const adapter = profile.kernelSemantics.code === 0 ? "legacy" : "modern";
  javaFiles.push(join(harness, adapter, "dev/gaius/browser/kernel/mesh/ExportModels.java"),
    join(harness, adapter, "net/minecraft/client/renderer/chunk/SnapshotRegion.java"));
  if (adapter === "legacy") javaFiles.push(join(harness, adapter, "dev/gaius/browser/kernel/mesh/LegacyModelTable.java"),
    join(root, "port/src/main/java/dev/gaius/browser/kernel/mesh/MeshTableWriter.java"));
  const sourceFiles = [...javaFiles];
  let compiledSources;
  if (adapter === "modern") {
    const staged = stageMeshJvmSources(profile, join(out, "sources"), root);
    javaFiles.push(...staged.javaFiles);
    sourceFiles.push(...staged.sourceFiles);
    compiledSources = staged.compiledSources;
  }
  const args = ["-encoding", "UTF-8", "-cp", classpath, "-d", classes, ...javaFiles];
  writeFileSync(join(out, "javac.args"), args.map((value) => `"${value.replaceAll("\\", "/")}"`).join("\n"));
  run("javac", ["-J-Xmx3g", "-J-Duser.language=en", `@${join(out, "javac.args")}`]);
  const runtimeArgs = ["-Xmx3g", "-Djava.awt.headless=true", "-cp", `${classes}${delimiter}${classpath}`,
    "dev.gaius.browser.kernel.mesh.MeshGolden", fixtures, String(profile.kernelSemantics.code), String(profile.kernelSemantics.meshVertexStride)];
  writeFileSync(join(out, "java.args"), runtimeArgs.map((value) => `"${value.replaceAll("\\", "/")}"`).join("\n"));
  run("java", [`@${join(out, "java.args")}`], out);
  const declarations = {};
  if (profile.kernelSemantics.meshVertexStride !== 28) {
    const sourceTable = readFileSync(join(fixtures, "table.bin"));
    const states = new Set();
    for (const section of JSON.parse(readFileSync(join(fixtures, "sections.json"), "utf8"))) {
      const job = readFileSync(join(fixtures, `${section.name}.job`));
      for (let index = 96; index < 16096; index += 2) states.add(job.readUInt16LE(index));
    }
    for (const state of states) if (!(sourceTable.readUInt32LE(64 + state * 56) & 1)) declarations[state] = {
      reason: `Vanilla BLOCK stride ${profile.kernelSemantics.meshVertexStride} contains normals; mesh ABI emits 28 bytes. Entire section must run vanilla.`,
      kind: "vertex-layout", semantics: profile.kernelSemantics.code};
  }
  writeFileSync(join(fixtures, "declines.json"), JSON.stringify(declarations, null, 2) + "\n");
  const files = Object.fromEntries(readdirSync(fixtures).filter((name) => name !== "fixtures.json").sort()
    .map((name) => [name, hash(readFileSync(join(fixtures, name)))]));
  writeFileSync(join(fixtures, "fixtures.json"), JSON.stringify({schema: 1, profile: profile.id,
    reference: "vanilla JVM SectionCompiler", corpus: "synthetic baked models, real block states and renderers",
    kernelSemantics: profile.kernelSemantics, profileSha256: hash(readFileSync(join(root, "port/versions", `${profileId}.json`))),
    vanillaSha256: hash(readFileSync(jar)), sources: Object.fromEntries(sourceFiles.map((path) => [relative(root, path).replaceAll("\\", "/"), hash(readFileSync(path))])), compiledSources, files}, null, 2) + "\n");
  console.log(`${profile.id}: 300 JVM sections captured`);
}
if (!option("--dist") && !argumentsList.includes("--native-only")) process.exit(0);
const manifest = JSON.parse(readFileSync(join(fixtures, "fixtures.json"), "utf8"));
assert.equal(manifest.profile, profile.id);
assert.equal(manifest.reference, "vanilla JVM SectionCompiler");
assert.deepEqual(manifest.kernelSemantics, profile.kernelSemantics, "fixture semantics changed");
assert.equal(manifest.profileSha256, hash(profileBytes), "fixture profile changed");
assert.equal(manifest.vanillaSha256, hash(readFileSync(join(root, "port/work", profile.id, "client-named.jar"))), "vanilla jar changed");
for (const [name, digest] of Object.entries(manifest.sources)) {
  assert(!name.startsWith("/") && !name.includes("..") && !name.includes(":"), "invalid harness path");
  assert.equal(hash(readFileSync(join(root, name))), digest, `harness changed: ${name}`);
}
if (manifest.compiledSources) {
  const resolved = resolveSources(profile, "src", "js", root);
  for (const [name, digest] of Object.entries(manifest.compiledSources)) {
    assert(resolved.files.has(name), `staged harness source missing: ${name}`);
    assert.equal(hash(compiledText(resolved.files.get(name))), digest, `staged harness changed: ${name}`);
  }
}
for (const [name, digest] of Object.entries(manifest.files)) {
  assert(!name.includes("/") && !name.includes("\\") && name !== "..", "fixture paths must be local basenames");
  assert.equal(hash(readFileSync(join(fixtures, name))), digest, `fixture changed: ${name}`);
}
const sections = JSON.parse(readFileSync(join(fixtures, "sections.json"), "utf8"));
assert(sections.length >= 300 && new Set(sections.map((section) => section.name)).size === sections.length);
for (const name of ["table.bin", "sections.json", "declines.json", ...sections.flatMap((section) => {
  assert(/^section-\d+$/.test(section.name), "invalid section name");
  return [`${section.name}.job`, ...["solid", "cutout", "translucent"].flatMap((layer) =>
    [`${section.name}.${layer}.vertices`, `${section.name}.${layer}.indices`])];
})]) assert(manifest.files[name], `unbound fixture: ${name}`);
const table = readFileSync(join(fixtures, "table.bin"));
assert.equal(table.readUInt16LE(6), profile.kernelSemantics.code, "table semantics");
const nativeOutput = join(out, "native");
run("cargo", ["run", "--quiet", "--offline", "--locked", "-j", "1", "-p", "gaius-mesher-wasm", "--example", "jvm_mesh_golden", "--", fixtures, nativeOutput], nativeRoot);
const engines = [{variant: "native", run: (name) => readFileSync(join(nativeOutput, `${name}.native`))}];
const modules = {};
function frame(kind, sequence, payload) {
  const framed = Buffer.alloc(16 + payload.length);
  framed.write("GKJB"); framed.writeUInt16LE(1, 4); framed.writeUInt16LE(kind, 6);
  framed.writeUInt32LE(sequence, 8); framed.writeUInt32LE(payload.length, 12); payload.copy(framed, 16);
  return framed;
}
if (option("--dist")) {
  const dist = resolve(option("--dist"));
  const kernelDirectory = existsSync(join(dist, "kernels.json")) ? dist : join(dist, "kernels");
  const kernelManifest = JSON.parse(readFileSync(join(kernelDirectory, "kernels.json"), "utf8"));
  const entry = kernelManifest.kernels.mesh || kernelManifest.kernels.mesher;
  assert(entry, "shipped manifest has no mesher kernel");
  for (const variant of ["simd", "baseline"]) {
    const metadata = entry.variants[variant];
    const filename = metadata?.path || metadata?.file;
    assert(typeof filename === "string" && !filename.includes("..") && !filename.includes(":") && !filename.startsWith("/"));
    const modulePath = join(kernelDirectory, filename);
    const bytes = readFileSync(modulePath);
    assert.equal(hash(bytes), metadata.sha256, "wasm manifest digest");
    assert.equal(bytes.length, metadata.bytes, "wasm manifest size");
    modules[variant] = {sha256: hash(bytes), bytes: bytes.length, manifestVerified: true, artifactKind,
      manifestSha256: hash(readFileSync(join(kernelDirectory, "kernels.json"))),
      manifestPath: join(kernelDirectory, "kernels.json"), path: modulePath};
    const module = new WebAssembly.Module(bytes);
    assert.equal(WebAssembly.Module.imports(module).length, 0);
    const {exports} = new WebAssembly.Instance(module, {});
    assert.equal(exports.gaius_abi_version(), 1);
    function execute(kind, sequence, payload, name) {
      const job = frame(kind, sequence, payload);
      const pointer = exports.alloc(job.length) >>> 0;
      assert(pointer, "wasm input allocation");
      new Uint8Array(exports.memory.buffer, pointer, job.length).set(job);
      const descriptor = exports[name](pointer, job.length) >>> 0;
      assert(descriptor, "wasm result descriptor");
      const view = new DataView(exports.memory.buffer);
      const status = view.getUint32(descriptor, true);
      const dataPointer = view.getUint32(descriptor + 4, true);
      const length = view.getUint32(descriptor + 8, true);
      const result = Buffer.from(new Uint8Array(exports.memory.buffer, dataPointer, length));
      exports.release(descriptor); exports.dealloc(pointer, job.length);
      assert.equal(status, 0, result.toString());
      assert.equal(result.subarray(0, 4).toString(), "GKRS");
      assert.equal(result.readUInt16LE(4), 1); assert.equal(result.readUInt16LE(6), 0, "result status");
      assert.equal(result.readUInt32LE(8), sequence); assert.equal(result.readUInt32LE(12), result.length - 16);
      return result.subarray(16);
    }
    const loaded = execute(0x0201, 0, table, "run_load_model_table");
    assert.equal(loaded.readUInt32LE(0), table.readUInt32LE(8), "loaded table epoch");
    assert.equal(loaded.readUInt32LE(4), table.readUInt32LE(12), "loaded state count");
    engines.push({variant, run: (name, job) => execute(0x0202, job.readUInt32LE(4), job, "run_mesh_section")});
  }
}
const results = [];
const declarations = existsSync(join(fixtures, "declines.json")) ? JSON.parse(readFileSync(join(fixtures, "declines.json"), "utf8")) : {};
for (const section of sections) {
  const job = readFileSync(join(fixtures, `${section.name}.job`));
  const references = Object.fromEntries(["solid", "cutout", "translucent"].map((layer) => [layer, {
    vertices: readFileSync(join(fixtures, `${section.name}.${layer}.vertices`)), indices: readFileSync(join(fixtures, `${section.name}.${layer}.indices`))}]));
  const native = engines[0].run(section.name, job);
  for (const engine of engines) {
    const result = engine.variant === "native" ? native : engine.run(section.name, job);
    const comparison = compareSection(result, references, job, table, profile.kernelSemantics.meshVertexStride);
    const declaration = declarations[comparison.declinedState];
    if (comparison.declinedState !== undefined) {
      assert(declaration?.reason, `unregistered decline ${comparison.declinedState}`);
      assert.equal(declaration.semantics, profile.kernelSemantics.code);
      if (declaration.kind === "vertex-layout") assert.notEqual(profile.kernelSemantics.meshVertexStride, 28);
      else assert.equal(declaration.kind, "unsupported-state");
    }
    const nativeEqual = result.equals(native);
    results.push({...section, variant: engine.variant, ...comparison, nativeEqual,
      reason: declaration?.reason, declineKind: declaration?.kind,
      declineVerified: comparison.declinedState !== undefined, resultSha256: hash(result)});
  }
}
const failures = results.filter((row) => row.bytesDifferent || !row.nativeEqual);
const report = {schema: 1, profile: profile.id, reference: manifest.reference, comparison: "vertex-bytes",
  corpus: manifest.corpus, sectionCount: sections.length, sources: {vanillaSha256: manifest.vanillaSha256,
    fixtureSha256: hash(readFileSync(join(fixtures, "fixtures.json"))), harness: manifest.sources,
    profileSha256: manifest.profileSha256,
    fixtureManifestPath: join(fixtures, "fixtures.json"),
    nativeOutput,
    vanillaPath: join(root, "port/work", profile.id, "client-named.jar"),
    nativePath: join(target, "debug/examples", `jvm_mesh_golden${process.platform === "win32" ? ".exe" : ""}`),
    nativeSha256: hash(readFileSync(join(target, "debug/examples", `jvm_mesh_golden${process.platform === "win32" ? ".exe" : ""}`)))},
  kernelSemantics: profile.kernelSemantics, gate, declarations, modules,
  verifierSources: Object.fromEntries(["port/scripts/mesh-jvm-wasm-golden.mjs", "port/scripts/mesh-golden-compare.mjs",
    "port/native/crates/gaius-mesher-wasm/examples/jvm_mesh_golden.rs"].map((name) => [name, hash(readFileSync(join(root, name)))])),
  results, verdict: failures.length ? "FAIL" : "PASS"};
writeFileSync(join(out, "report.json"), JSON.stringify(report, null, 2) + "\n");
console.log(JSON.stringify({profile: profile.id, gate: gate.enabled ? "enabled" : "quarantined", verdict: report.verdict, sections: sections.length,
  comparisons: results.length, bytesCompared: results.reduce((sum, row) => sum + row.bytesCompared, 0),
  bytesDifferent: results.reduce((sum, row) => sum + row.bytesDifferent, 0),
  declined: results.filter((row) => row.declinedState !== undefined).length, failures: failures.length}));
if (failures.length) process.exitCode = 1;
