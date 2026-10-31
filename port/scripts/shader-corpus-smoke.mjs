#!/usr/bin/env node
// Shader toolchain corpus smoke (PLAN D5 / P4 acceptance).
//
// Proves on the whole Minecraft 26.3 pipeline corpus (216 pipelines: the static
// RenderPipelines plus every post_effect pass) that the browser shader path
// produces the native LWJGL results, and that its output runs on WebGL2:
//
//  0. overlay   javap assertions on the build-overlays.sh output: Shaderc/Spvc no longer load a
//               native library or call JNI, and every Shaderc/Spvc method the client calls is
//               redirected to BrowserShaderc/BrowserSpvc (the rest throw).
//  1. harness   Compiles the browser shims (port/overrides/libraries/lwjgl-shaderc,
//               lwjgl-spvc), patches vanilla Shaderc/Spvc with LwjglShadercBrowserPatcher
//               and LwjglSpvcBrowserPatcher, and runs ShaderCorpusHarness on the JVM
//               three times: native (vanilla LWJGL), shim (patched bindings -> BrowserShaderc/
//               BrowserSpvc -> native backend, ESSL on) and shim-raw (ESSL off).
//  2. compare   native == shim-raw for every pipeline (reflection, patched SPIR-V, GLSL);
//               shim has the same reflection and SPIR-V; every shim ESSL output is a fixed
//               point of the real BrowserOpenGL.translateShaderSource; no include misses,
//               no leaked spvc contexts or shaderc results.
//  3. wasm      Replays the shim run's SPI tapes (every shaderc job, every spvc call) through
//               the WebAssembly loader API (gaius-shader-toolchain.js + the built modules)
//               and requires byte-identical SPIR-V, GLSL and return values; measures sizes
//               and compile times against the D5 budgets.
//  4. abort     Traps each WebAssembly module and checks that stale spvc handles are rejected,
//               that the next calls run on a fresh instance and still match the tape.
//  5. webgl     Compiles and links every shim ESSL program in headless Chrome WebGL2 and
//               checks the names GlProgram binds by.
//  6. browser   Loads gaius-shader-toolchain.js through a script tag in headless Chrome (the page
//               bootstrap and window.__gaiusShaderToolchainReady), replays the tapes there (cold,
//               then with the result cache), reloads the page and requires every shaderc job to be
//               served from the profile IndexedDB cache within the 0.5 s budget.
//
// usage: node port/scripts/shader-corpus-smoke.mjs [--profile 26.3] [--toolchain DIR]
//          [--out DIR] [--stages overlay,harness,compare,wasm,abort,webgl,browser] [--chrome PATH]
//          [--overlay-dir DIR]
//          [--swiftshader] [--report FILE]
// --toolchain defaults to port/target/shader-toolchain/out (build-wasm-shader-toolchain.sh).
// Exit status: 0 pass, 1 failure, 2 usage or environment error.

import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import {execFileSync, spawn} from "node:child_process";
import {createRequire} from "node:module";
import {copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync,
  writeFileSync} from "node:fs";
import {homedir} from "node:os";
import {dirname, isAbsolute, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {gzipSync} from "node:zlib";
import crypto from "node:crypto";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const require = createRequire(import.meta.url);
const isWindows = process.platform === "win32";
const separator = isWindows ? ";" : ":";
const nativePath = (value) => {
  const text = String(value);
  return isWindows && /^\/[A-Za-z](?:\/|$)/.test(text) ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const fromRoot = (value) => (isAbsolute(nativePath(value)) ? nativePath(value) : resolve(root, value));

function usage(message) {
  console.error(`shader-corpus-smoke: ${message}`);
  process.exit(2);
}

const options = {stages: "overlay,harness,compare,wasm,abort,webgl,browser"};
for (let index = 2; index < process.argv.length; index++) {
  const argument = process.argv[index];
  if (argument === "--swiftshader") options.swiftshader = true;
  else if (argument.startsWith("--") && index + 1 < process.argv.length) options[argument.slice(2)] = process.argv[++index];
  else usage(`unknown or incomplete argument ${argument}`);
}
const profile = options.profile || RENDERPEARL_PROFILE.id;
const stages = new Set(options.stages.split(",").map((stage) => stage.trim()).filter(Boolean));
const out = fromRoot(options.out || join("port/target/shader-corpus", profile));
const toolchainDir = fromRoot(options.toolchain || "port/target/shader-toolchain/out");
const work = join(root, "port/work", profile);
const maven = nativePath(process.env.GAIUS_MAVEN_REPOSITORY || join(homedir(), ".m2/repository"));
const failures = [];
const report = {profile, stages: [...stages]};
const fail = (message) => {
  failures.push(message);
  console.log(`FAIL ${message}`);
};
const sha256 = (data) => crypto.createHash("sha256").update(data).digest("hex");

function javaTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

function run(tool, args, label) {
  const started = performance.now();
  try {
    const output = execFileSync(javaTool(tool), args, {cwd: root, encoding: "utf8", maxBuffer: 256 << 20,
      stdio: ["ignore", "pipe", "pipe"]});
    return {output, ms: performance.now() - started};
  } catch (error) {
    console.error(String(error.stdout || ""));
    console.error(String(error.stderr || ""));
    throw new Error(`${label} failed (exit ${error.status})`);
  }
}

function listJava(directory) {
  const files = [];
  for (const entry of readdirSync(directory, {withFileTypes: true})) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...listJava(path));
    else if (entry.name.endsWith(".java")) files.push(path);
  }
  return files;
}

function javac(label, classpath, sources, output) {
  rmSync(output, {recursive: true, force: true});
  mkdirSync(output, {recursive: true});
  const argfile = `${output}.args`;
  writeFileSync(argfile, sources.map((file) => `"${file.replaceAll("\\", "/")}"`).join("\n"));
  run("javac", ["--release", "21", "-proc:none", "-nowarn", "-encoding", "UTF-8", "-d", output,
    "-cp", classpath.join(separator), `@${argfile}`], `javac ${label}`);
  rmSync(argfile, {force: true});
}

// ---------------------------------------------------------------------------------------------
// 1. JVM harness
const clientJar = join(work, "client-named.jar");
const libraries = join(work, "libraries");
const library = (path) => join(libraries, path);
const lwjgl = (module, suffix = "") =>
  library(`org/lwjgl/${module}/3.4.3/${module}-3.4.3${suffix}.jar`);
const mavenJar = (group, artifact, version) =>
  join(maven, group.replaceAll(".", "/"), artifact, version, `${artifact}-${version}.jar`);
const teavmVersion = JSON.parse(readFileSync(join(root, "port/config.json"), "utf8")).teaVMVersion;
const teavmJars = ["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-platform"]
  .map((artifact) => mavenJar("org.teavm", artifact, teavmVersion));
const asmJars = ["asm", "asm-tree", "asm-commons"].map((artifact) => mavenJar("org.ow2.asm", artifact, "9.8"));
const classes = join(out, "classes");

function workLibraries() {
  const listing = readFileSync(join(work, "classpath.txt"), "utf8").trim().split(/[:;\n](?=\/|[A-Za-z]:)/);
  const jars = [];
  for (const entry of listing) {
    const at = entry.replaceAll("\\", "/").indexOf("/libraries/");
    if (at < 0) continue;
    const path = join(libraries, entry.slice(at + "/libraries/".length).trim());
    if (existsSync(path)) jars.push(path);
  }
  return jars;
}

// build-overlays.sh generates GaiusProfileCapabilities from the profile for the lwjgl-opengl
// overlay (BrowserOpenGL reads the patch set and the terrain block from it).
function profileCapabilitiesSource() {
  const fields = JSON.parse(readFileSync(join(root, "port/versions", `${profile}.json`), "utf8"));
  const file = join(out, "generated/org/lwjgl/opengl/GaiusProfileCapabilities.java");
  mkdirSync(dirname(file), {recursive: true});
  writeFileSync(file, [
    "package org.lwjgl.opengl;",
    "final class GaiusProfileCapabilities {",
    `    static final String PATCH_SET = ${JSON.stringify(fields.patchSet || "")};`,
    `    static final boolean TERRAIN_SECTION_UNIFORM_BLOCK = ${fields.terrainSectionUniformBlock === true};`,
    "}",
    "",
  ].join("\n"));
  return file;
}

function stageHarness() {
  for (const [label, path] of [["client jar", clientJar], ["lwjgl-shaderc", lwjgl("lwjgl-shaderc")],
    ["lwjgl-spvc", lwjgl("lwjgl-spvc")], ...teavmJars.map((jar) => ["TeaVM", jar]), ...asmJars.map((jar) => ["ASM", jar])]) {
    if (!existsSync(path)) usage(`${label} is missing: ${path}`);
  }
  const libs = workLibraries();
  if (libs.length < 40) usage(`too few libraries resolved from ${join(work, "classpath.txt")}: ${libs.length}`);
  const timings = {};

  javac("patchers", asmJars, [
    join(root, "port/tools/src/main/java/dev/gaius/tools/LwjglShadercBrowserPatcher.java"),
    join(root, "port/tools/src/main/java/dev/gaius/tools/LwjglSpvcBrowserPatcher.java"),
    join(root, "port/tools/src/main/java/dev/gaius/tools/LwjglBrowserShimRedirect.java")], join(classes, "tools"));
  // The library overlays pass Java arrays to JavaScript through dev.gaius.browser.JsArrays of the
  // classlib overlay, which build-overlays.sh puts on their class path; its JavaScript
  // implementation is compiled once here and stays outside the library jars.
  const classlib = join(classes, "classlib");
  javac("classlib JsArrays", teavmJars,
    [join(root, "port/overrides/classlib/src/main/java/dev/gaius/browser/JsArrays.java")], classlib);
  const shimSources = (module) => listJava(join(root, "port/overrides/libraries", module, "src/main/java"));
  javac("lwjgl-shaderc overlay", [lwjgl("lwjgl-shaderc"), lwjgl("lwjgl"), classlib, ...teavmJars],
    shimSources("lwjgl-shaderc"), join(classes, "shaderc"));
  javac("lwjgl-spvc overlay", [lwjgl("lwjgl-spvc"), lwjgl("lwjgl"), classlib, ...teavmJars],
    shimSources("lwjgl-spvc"), join(classes, "spvc"));

  // The patchers read the vanilla binding plus the compiled shim from one jar, as
  // build-overlays.sh does (without the later callback-descriptor step, which
  // only matters for the browser's LWJGL core).
  const patched = join(classes, "patched");
  rmSync(patched, {recursive: true, force: true});
  for (const [module, patcher, shim] of [["lwjgl-shaderc", "LwjglShadercBrowserPatcher", "shaderc"],
    ["lwjgl-spvc", "LwjglSpvcBrowserPatcher", "spvc"]]) {
    const jar = join(classes, `${module}-with-shim.jar`);
    copyFileSync(lwjgl(module), jar);
    run("jar", ["--update", "--file", jar, "-C", join(classes, shim), "."], `jar ${module}`);
    const {output} = run("java", ["-cp", [join(classes, "tools"), ...asmJars].join(separator),
      `dev.gaius.tools.${patcher}`, jar, patched], patcher);
    report[`${shim}Patcher`] = output.trim();
    console.log(output.trim());
  }
  const harnessSources = join(root, "port/tools/shader-corpus/java");
  javac("relocation", asmJars, [join(harnessSources, "dev/gaius/shadercorpus/ShaderCorpusRelocate.java")],
    join(classes, "relocate"));
  run("java", ["-cp", [join(classes, "relocate"), ...asmJars].join(separator),
    "dev.gaius.shadercorpus.ShaderCorpusRelocate", lwjgl("lwjgl-shaderc"), lwjgl("lwjgl-spvc"),
    join(classes, "nat")], "relocation");

  // BrowserOpenGL.translateShaderSource, compiled from the unchanged overlay source.
  const openglClasses = join(classes, "opengl");
  javac("lwjgl-opengl overlay", [lwjgl("lwjgl-opengl"), clientJar, ...libs, classlib, ...teavmJars],
    [...listJava(join(root, "port/overrides/libraries/lwjgl-opengl/src/main/java")),
      // BrowserOpenGL accounts its native buffers with the overlay's BrowserMemory, which the
      // vanilla lwjgl core of the class path does not have.
      join(root, "port/overrides/libraries/lwjgl/src/main/java/org/lwjgl/system/BrowserMemory.java"),
      profileCapabilitiesSource()], openglClasses);
  const browserOpenGl = join(classes, "browser-opengl/org/lwjgl/opengl");
  rmSync(join(classes, "browser-opengl"), {recursive: true, force: true});
  mkdirSync(browserOpenGl, {recursive: true});
  for (const name of readdirSync(join(openglClasses, "org/lwjgl/opengl"))) {
    if (name.startsWith("BrowserOpenGL")) copyFileSync(join(openglClasses, "org/lwjgl/opengl", name), join(browserOpenGl, name));
  }

  javac("harness", [clientJar, ...libs, join(classes, "shaderc"), join(classes, "spvc"), join(classes, "nat"),
    classlib, ...teavmJars, ...asmJars], listJava(harnessSources).filter((file) => !file.endsWith("ShaderCorpusRelocate.java")),
  join(classes, "harness"));

  const javaArgs = ["--enable-native-access=ALL-UNNAMED"];
  const harnessMain = "dev.gaius.shadercorpus.ShaderCorpusHarness";
  // The native run keeps the vanilla Shaderc/Spvc; the shim and relocated classes are
  // only there so the harness class verifies (it installs the shim backends in shim modes).
  const nativeClasspath = [join(classes, "harness"), join(classes, "shaderc"), join(classes, "spvc"),
    join(classes, "nat"), classlib, ...teavmJars, clientJar, ...libs];
  const shimClasspath = [join(classes, "harness"), patched, join(classes, "shaderc"), join(classes, "spvc"),
    join(classes, "nat"), join(classes, "browser-opengl"), classlib, ...teavmJars, clientJar, ...libs];
  for (const [mode, classpath, extra] of [["native", nativeClasspath, []],
    ["shim", shimClasspath, ["-Dgaius.translate.check=true"]], ["shim-raw", shimClasspath, []]]) {
    const directory = join(out, mode);
    rmSync(directory, {recursive: true, force: true});
    const {output, ms} = run("java", [...javaArgs, ...extra, "-cp", classpath.join(separator), harnessMain, mode,
      clientJar, directory], `harness ${mode}`);
    timings[mode] = Math.round(ms);
    const summary = JSON.parse(readFileSync(join(directory, "summary.json"), "utf8"));
    report[`harness-${mode}`] = summary;
    console.log(`harness ${mode}: ${output.trim().split("\n").pop()}`);
  }
  report.harnessMs = timings;
}

// ---------------------------------------------------------------------------------------------
// 2. compare
function readPipelines(mode) {
  const path = join(out, mode, "pipelines.json");
  if (!existsSync(path)) usage(`missing ${path} (run the harness stage)`);
  return JSON.parse(readFileSync(path, "utf8"));
}

function stageCompare() {
  const reference = readPipelines("native");
  const shim = readPipelines("shim");
  const raw = readPipelines("shim-raw");
  const summaries = Object.fromEntries(["native", "shim", "shim-raw"].map((mode) =>
    [mode, JSON.parse(readFileSync(join(out, mode, "summary.json"), "utf8"))]));
  const counts = {pipelines: reference.length, shaders: 0, identicalRaw: 0, identicalReflection: 0, fixedPoint: 0};
  if (reference.length !== 216) fail(`expected 216 pipelines, native run has ${reference.length}`);
  for (const [mode, summary] of Object.entries(summaries)) {
    if (summary.failed !== 0) fail(`${mode}: ${summary.failed} pipelines failed`);
  }
  for (const mode of ["shim", "shim-raw"]) {
    const summary = summaries[mode];
    if (summary.includeMisses !== 0) fail(`${mode}: ${summary.includeMisses} include requests missed the pre-resolved table`);
    if (summary.liveSpvcContexts !== 0) fail(`${mode}: ${summary.liveSpvcContexts} spvc contexts leaked`);
    if (summary.liveShadercHandles !== 0) fail(`${mode}: ${summary.liveShadercHandles} shaderc handles leaked`);
  }
  if (!summaries.shim.translateChecked) fail("shim run did not check translateShaderSource");
  if (summaries.shim.translateFixedPointFailures !== 0) {
    fail(`${summaries.shim.translateFixedPointFailures} ESSL outputs are not fixed points of translateShaderSource`);
  }
  const reflection = (pipeline) => JSON.stringify([pipeline.location, pipeline.error, pipeline.uniforms,
    pipeline.pushConstantsSize, pipeline.attribBindings, pipeline.vertexBuffers,
    (pipeline.shaders || []).map((shader) => [shader.name, shader.type, shader.spirv])]);
  const byLocation = (list) => new Map(list.map((pipeline) => [pipeline.location, pipeline]));
  const shimBy = byLocation(shim);
  const rawBy = byLocation(raw);
  for (const pipeline of reference) {
    const shimPipeline = shimBy.get(pipeline.location);
    const rawPipeline = rawBy.get(pipeline.location);
    if (!shimPipeline || !rawPipeline) {
      fail(`${pipeline.location} missing from a shim run`);
      continue;
    }
    counts.shaders += (pipeline.shaders || []).length;
    if (reflection(pipeline) === reflection(shimPipeline) && reflection(pipeline) === reflection(rawPipeline)) {
      counts.identicalReflection++;
    } else {
      fail(`${pipeline.location}: reflection or SPIR-V differs from native`);
    }
    const glsl = (list) => (list.shaders || []).map((shader) => shader.glsl);
    if (JSON.stringify(glsl(pipeline)) === JSON.stringify(glsl(rawPipeline))) counts.identicalRaw++;
    else fail(`${pipeline.location}: shim-raw GLSL differs from native`);
    for (const shader of shimPipeline.shaders || []) {
      if (shader.translateFixedPoint === true) counts.fixedPoint++;
      else fail(`${pipeline.location} ${shader.name}: ESSL is not a translateShaderSource fixed point`);
      if (!shader.glsl) fail(`${pipeline.location} ${shader.name}: no ESSL output`);
    }
  }
  // The raw spvc text the shim transported is the native text of the same module.
  const tapeGlsl = new Set(readdirSync(join(out, "shim", "tape-glsl")).map((name) => name.replace(/\.glsl$/, "")));
  const nativeGlsl = new Set(reference.flatMap((pipeline) => (pipeline.shaders || []).map((shader) => shader.glsl)));
  for (const hash of nativeGlsl) {
    if (!tapeGlsl.has(hash)) fail(`native GLSL ${hash} never came out of the shim's spvc backend`);
  }
  report.compare = counts;
  console.log(`compare: ${JSON.stringify(counts)}`);
}

// ---------------------------------------------------------------------------------------------
// 3. wasm replay through the loader API
async function loadToolchain() {
  for (const file of ["gaius-shader-toolchain.js", "gaius-shaderc.js", "gaius-shaderc.wasm", "gaius-spvc.js",
    "gaius-spvc.wasm"]) {
    if (!existsSync(join(toolchainDir, file))) {
      usage(`${join(toolchainDir, file)} is missing (run port/scripts/build-wasm-shader-toolchain.sh)`);
    }
  }
  const loader = require(join(toolchainDir, "gaius-shader-toolchain.js"));
  const started = performance.now();
  const [shadercModule, spvcModule] = await Promise.all([
    WebAssembly.compile(readFileSync(join(toolchainDir, "gaius-shaderc.wasm"))),
    WebAssembly.compile(readFileSync(join(toolchainDir, "gaius-spvc.wasm")))]);
  const compiledMs = performance.now() - started;
  const api = await loader.createToolchain({
    shadercFactory: require(join(toolchainDir, "gaius-shaderc.js")),
    spvcFactory: require(join(toolchainDir, "gaius-spvc.js")),
    shadercModule, spvcModule, cacheVersion: sha256(readFileSync(join(toolchainDir, "gaius-shaderc.wasm"))).slice(0, 16)});
  return {api, compiledMs, readyMs: performance.now() - started};
}

const readLines = (file) => readFileSync(file, "utf8").split("\n").filter(Boolean).map((line) => JSON.parse(line));
// Tape blobs are read once, so the timed replay measures the toolchain, not the disk.
const blobCache = new Map();
const tapeBlob = (sub, hash, ext) => {
  const key = `${sub}/${hash}${ext}`;
  let data = blobCache.get(key);
  if (!data) {
    data = readFileSync(join(out, "shim", sub, `${hash}${ext}`));
    blobCache.set(key, data);
  }
  return data;
};

// The two replay functions are self-contained (they also run inside Chrome, see
// stageBrowser): ctx = {blob(sub, hash, ext) -> bytes, hash(bytes) -> hex, utf8(text) -> bytes}.
function replayShadercJob(api, job, ctx) {
  const int8 = (bytes) => new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const id = api.shadercBegin();
  try {
    for (const op of job.ops) {
      if ("macro" in op) api.shadercMacro(id, ctx.utf8(op.macro), op.value === null ? null : ctx.utf8(op.value));
      else api.shadercOption(id, op.code, op.a, op.b);
    }
    for (const include of job.includes) {
      api.shadercInclude(id, include.type, ctx.utf8(include.requested), ctx.utf8(include.requesting),
        ctx.utf8(include.sourceName), ctx.blob("tape-include", include.content, ".glsl"));
    }
    const status = api.shadercCompile(id, int8(ctx.blob("tape-source", job.source, ".glsl")), job.kind,
      ctx.utf8(job.inputFileName), ctx.utf8(job.entryPointName));
    const spirv = new Int8Array(api.shadercOutputLength(id));
    if (spirv.length) api.shadercOutputCopy(id, spirv);
    return {status, spirv: ctx.hash(new Uint8Array(spirv.buffer)), error: api.shadercErrorMessage(id),
      warnings: api.shadercWarnings(id), errors: api.shadercErrors(id), misses: api.shadercIncludeMisses(id)};
  } finally {
    api.shadercEnd(id);
  }
}

// Replays one spvc session; returns a list of mismatch descriptions.
function replaySpvcSession(api, session, generation, ctx) {
  const int8 = (bytes) => new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const handles = new Map();
  const mismatches = [];
  const expect = (call, got, want, what) => {
    const same = (got === null || typeof got !== "object") ? got === want
      : JSON.stringify(got) === JSON.stringify(want);
    if (!same) {
      mismatches.push(`session ${session.session} ${call[0]} ${what}: got ${JSON.stringify(got)} want ${JSON.stringify(want)}`);
    }
  };
  const h = (symbol) => (symbol === null ? 0 : handles.get(symbol));
  const bind = (symbol, value) => { if (symbol !== null) handles.set(symbol, value); };
  for (const call of session.calls) {
    switch (call[0]) {
      case "contextCreate": {
        const result = api.spvcContextCreate(generation);
        expect(call, result, call[1], "result");
        bind(call[2], api.spvcOut(0));
        break;
      }
      case "contextDestroy": api.spvcContextDestroy(generation, h("c")); break;
      case "(open)": api.spvcContextDestroy(generation, h("c")); break;
      case "contextLastError": expect(call, api.spvcContextLastError(generation, h("c")), call[2], "text"); break;
      case "parseSpirv": {
        const words = ctx.blob("tape-spirv", call[1], ".spv");
        const result = api.spvcParseSpirv(generation, h("c"), int8(words), call[2]);
        expect(call, result, call[3], "result");
        if (result === 0) bind(call[4], api.spvcOut(0));
        break;
      }
      case "createCompiler": {
        const result = api.spvcCreateCompiler(generation, h("c"), call[1], h(call[2]), call[3]);
        expect(call, result, call[4], "result");
        if (result === 0) bind(call[5], api.spvcOut(0));
        break;
      }
      case "createCompilerOptions": {
        const result = api.spvcCreateCompilerOptions(generation, h(call[1]));
        expect(call, result, call[2], "result");
        if (result === 0) bind(call[3], api.spvcOut(0));
        break;
      }
      case "optionsSetBool": expect(call, api.spvcOptionsSetBool(generation, h(call[1]), call[2], call[3]), call[4], "result"); break;
      case "optionsSetUint": expect(call, api.spvcOptionsSetUint(generation, h(call[1]), call[2], Number(call[3])), call[4], "result"); break;
      case "installCompilerOptions": expect(call, api.spvcInstallCompilerOptions(generation, h(call[1]), h(call[2])), call[3], "result"); break;
      case "createShaderResources": {
        const result = api.spvcCreateShaderResources(generation, h(call[1]));
        expect(call, result, call[2], "result");
        if (result === 0) bind(call[3], api.spvcOut(0));
        break;
      }
      case "resourceList": {
        const result = api.spvcResourceList(generation, h(call[1]), call[2]);
        expect(call, result, call[3], "result");
        const count = api.spvcOut(0);
        const triples = new Int32Array(count * 3);
        if (count) api.spvcResourceFill(generation, triples);
        const list = [];
        for (let i = 0; i < count; i++) {
          list.push([triples[i * 3], triples[i * 3 + 1], triples[i * 3 + 2], api.spvcResourceName(generation, i)]);
        }
        expect(call, list, call[4], "list");
        break;
      }
      case "getDecoration": expect(call, api.spvcGetDecoration(generation, h(call[1]), call[2], call[3]), call[4], "result"); break;
      case "getBinaryOffsetForDecoration": {
        const found = api.spvcGetBinaryOffsetForDecoration(generation, h(call[1]), call[2], call[3]) !== 0;
        expect(call, [found, found ? api.spvcOut(0) : 0], [call[4], call[5]], "offset");
        break;
      }
      case "setName": api.spvcSetName(generation, h(call[1]), call[2], ctx.utf8(call[3])); break;
      case "getName": expect(call, api.spvcGetName(generation, h(call[1]), call[2]), call[3], "name"); break;
      case "setEntryPoint": expect(call, api.spvcSetEntryPoint(generation, h(call[1]), ctx.utf8(call[2]), call[3]), call[4], "result"); break;
      case "compile": {
        const result = api.spvcCompile(generation, h(call[1]));
        expect(call, result, call[2], "result");
        const source = api.spvcCompiledSource(generation);
        expect(call, source === null ? null : ctx.hash(ctx.utf8(source)), call[3], "glsl");
        break;
      }
      case "getDeclaredStructSize": {
        const result = api.spvcGetDeclaredStructSize(generation, h(call[1]), h(call[2]));
        expect(call, [result, api.spvcOut(0)], [call[3], call[4]], "size");
        break;
      }
      case "getTypeHandle": {
        const type = api.spvcGetTypeHandle(generation, h(call[1]), call[2]);
        expect(call, type !== 0, call[3] !== null, "non-null");
        if (type !== 0 && call[3] !== null) {
          if (handles.has(call[3]) && handles.get(call[3]) !== type) mismatches.push(`session ${session.session} type ${call[3]} changed`);
          bind(call[3], type);
        }
        break;
      }
      case "typeGetBasetype": expect(call, api.spvcTypeGetBasetype(generation, h(call[1])), call[2], "result"); break;
      case "typeGetImageDimension": expect(call, api.spvcTypeGetImageDimension(generation, h(call[1])), call[2], "result"); break;
      case "typeGetVectorSize": expect(call, api.spvcTypeGetVectorSize(generation, h(call[1])), call[2], "result"); break;
      case "typeGetNumArrayDimensions": expect(call, api.spvcTypeGetNumArrayDimensions(generation, h(call[1])), call[2], "result"); break;
      case "typeGetArrayDimension": expect(call, api.spvcTypeGetArrayDimension(generation, h(call[1]), call[2]), call[3], "result"); break;
      default: throw new Error(`unknown tape call ${call[0]}`);
    }
  }
  return mismatches;
}

const nodeReplayContext = {blob: tapeBlob, hash: (bytes) => sha256(bytes), utf8: (text) => new TextEncoder().encode(text)};

function preloadTapes() {
  const jobs = readLines(join(out, "shim", "tape-shaderc.jsonl"));
  const sessions = readLines(join(out, "shim", "tape-spvc.jsonl"));
  for (const job of jobs) {
    tapeBlob("tape-source", job.source, ".glsl");
    for (const include of job.includes) tapeBlob("tape-include", include.content, ".glsl");
  }
  for (const session of sessions) {
    for (const call of session.calls) if (call[0] === "parseSpirv") tapeBlob("tape-spirv", call[1], ".spv");
  }
  return {jobs, sessions};
}

function replayAll(api, tapes) {
  const {jobs, sessions} = tapes;
  const result = {shadercJobs: jobs.length, spirvIdentical: 0, spvcSessions: sessions.length, spvcCalls: 0,
    spvcMismatches: 0};
  let started = performance.now();
  for (const job of jobs) {
    const got = replayShadercJob(api, job, nodeReplayContext);
    const want = {status: job.status, spirv: job.spirv, error: job.error, warnings: job.warnings, errors: job.errors,
      misses: 0};
    if (JSON.stringify(got) === JSON.stringify(want)) result.spirvIdentical++;
    else fail(`shaderc job ${job.inputFileName}: WebAssembly ${JSON.stringify(got)} vs native ${JSON.stringify(want)}`);
  }
  result.shadercMs = Math.round(performance.now() - started);
  started = performance.now();
  const generation = api.spvcGeneration();
  for (const session of sessions) {
    result.spvcCalls += session.calls.length;
    const mismatches = replaySpvcSession(api, session, generation, nodeReplayContext);
    result.spvcMismatches += mismatches.length;
    for (const mismatch of mismatches.slice(0, 3)) fail(mismatch);
  }
  result.spvcMs = Math.round(performance.now() - started);
  return result;
}

// Every window.__gaiusShaderToolchain call in the @JSBody glue (BrowserShadercWasm,
// BrowserSpvcWasm) must name an API function taking exactly those arguments.
function checkJsBodyGlue(api) {
  const calls = [];
  for (const file of ["lwjgl-shaderc/src/main/java/org/lwjgl/util/shaderc/BrowserShadercWasm.java",
    "lwjgl-spvc/src/main/java/org/lwjgl/util/spvc/BrowserSpvcWasm.java"]) {
    const source = readFileSync(join(root, "port/overrides/libraries", file), "utf8").replace(/"\s*\+\s*"/g, "");
    for (const match of source.matchAll(/window\.__gaiusShaderToolchain\.([A-Za-z0-9_]+)\(([^)]*)\)/g)) {
      const args = match[2].trim() === "" ? 0 : match[2].split(",").length;
      calls.push({file: file.split("/").pop(), name: match[1], args});
    }
  }
  for (const call of calls) {
    if (typeof api[call.name] !== "function") fail(`${call.file} calls ${call.name}, which the loader API lacks`);
    else if (api[call.name].length !== call.args) {
      fail(`${call.file} calls ${call.name} with ${call.args} arguments; the loader takes ${api[call.name].length}`);
    }
  }
  return calls.length;
}

async function stageWasm() {
  const {api, compiledMs, readyMs} = await loadToolchain();
  const glueCalls = checkJsBodyGlue(api);
  if (glueCalls < 40) fail(`only ${glueCalls} toolchain calls found in the @JSBody glue`);
  const sizes = {};
  let gzipTotal = 0;
  for (const file of ["gaius-shader-toolchain.js", "gaius-shaderc.js", "gaius-shaderc.wasm", "gaius-spvc.js",
    "gaius-spvc.wasm"]) {
    const bytes = readFileSync(join(toolchainDir, file));
    const gzip = gzipSync(bytes, {level: 9}).length;
    sizes[file] = {bytes: bytes.length, gzip};
    gzipTotal += gzip;
  }
  const tapes = preloadTapes();
  const timed = (label) => {
    const before = api.stats();
    const result = replayAll(api, tapes);
    const after = api.stats();
    // Time inside shadercCompile / spvcCompile themselves (the loader's own counters).
    result.shadercCompileMs = Math.round(after.shadercMs - before.shadercMs);
    result.spvcCompileMs = Math.round(after.spvcMs - before.spvcMs);
    result.cacheHits = after.cache.hits - before.cache.hits;
    result.totalMs = result.shadercMs + result.spvcMs;
    result.label = label;
    return result;
  };
  // Pass 1 runs every job in WebAssembly (the equivalence proof), pass 2 is a
  // first reload with an empty result cache (duplicates within the pass hit),
  // pass 3 a reload with a warm cache (the D5 hot path).
  api.setCacheEnabled(false);
  const cold = timed("cold, no cache");
  api.setCacheEnabled(true);
  api.clearCache();
  const firstReload = timed("empty cache");
  const warm = timed("warm cache");
  const coldMs = cold.totalMs;
  const warmMs = warm.totalMs;
  report.wasm = {glueCalls, sizes, gzipTotal, compiledMs: Math.round(compiledMs), readyMs: Math.round(readyMs), cold,
    firstReload, warm, budgets: {gzipBytes: 3 * 1024 * 1024, coldMs: 5000, warmMs: 500}, stats: api.stats()};
  for (const pass of [cold, firstReload, warm]) {
    if (pass.spirvIdentical !== pass.shadercJobs) fail(`${pass.label}: shaderc matched ${pass.spirvIdentical}/${pass.shadercJobs} jobs`);
    if (pass.spvcMismatches !== 0) fail(`${pass.label}: ${pass.spvcMismatches} spvc mismatches`);
  }
  if (cold.cacheHits !== 0) fail(`cold pass used the cache (${cold.cacheHits} hits)`);
  if (warm.cacheHits !== warm.shadercJobs) fail(`warm pass hit the cache ${warm.cacheHits}/${warm.shadercJobs} times`);
  if (gzipTotal > 3 * 1024 * 1024) fail(`toolchain download is ${gzipTotal} bytes gzip (budget 3 MiB)`);
  if (coldMs > 5000) fail(`cold toolchain pass took ${coldMs} ms (budget 5000 ms)`);
  if (warmMs > 500) fail(`warm toolchain pass took ${warmMs} ms (budget 500 ms)`);
  console.log(`wasm: gzip ${gzipTotal} B, ready ${Math.round(readyMs)} ms; cold ${coldMs} ms`
    + ` (shaderc ${cold.shadercMs} + spvc ${cold.spvcMs}), empty cache ${firstReload.totalMs} ms`
    + ` (${firstReload.cacheHits} hits), warm ${warmMs} ms (${warm.cacheHits} hits);`
    + ` ${cold.spirvIdentical}/${cold.shadercJobs} SPIR-V, ${cold.spvcCalls} spvc calls, ${cold.spvcMismatches} mismatches`);
  return api;
}

// ---------------------------------------------------------------------------------------------
// 4. abort recovery
async function stageAbort(existing) {
  const api = existing || (await loadToolchain()).api;
  await api.settled();
  const jobs = readLines(join(out, "shim", "tape-shaderc.jsonl"));
  const sessions = readLines(join(out, "shim", "tape-spvc.jsonl"));
  const result = {};
  // spvc: a context of the old generation must be rejected after the trap.
  const before = api.spvcGeneration();
  if (api.spvcContextCreate(before) !== 0) fail("abort: spvc context creation failed before the trap");
  const staleContext = api.spvcOut(0);
  result.spvcTrap = api.debugTrap("spvc");
  const after = api.spvcGeneration();
  result.spvcGenerations = [before, after];
  if (!(after > before)) fail(`abort: spvc generation did not advance (${before} -> ${after})`);
  if (api.spvcParseSpirv(before, staleContext, new Int8Array(4), 1) !== -3) fail("abort: a stale spvc context was not rejected");
  const mismatches = replaySpvcSession(api, sessions.find((session) => session.calls.some((call) => call[0] === "compile")),
    after, nodeReplayContext);
  if (mismatches.length) fail(`abort: spvc session after the trap: ${mismatches[0]}`);
  // shaderc: the job after the trap runs on the promoted spare and still matches
  // (with the result cache off, so the job really reaches WebAssembly).
  api.setCacheEnabled(false);
  const shadercBefore = api.stats().shaderc.generation;
  result.shadercTrap = api.debugTrap("shaderc");
  const job = jobs[0];
  const got = replayShadercJob(api, job, nodeReplayContext);
  if (got.status !== job.status || got.spirv !== job.spirv) fail(`abort: shaderc after the trap: ${JSON.stringify(got)}`);
  if (!(api.stats().shaderc.generation > shadercBefore)) fail("abort: shaderc did not move to a fresh instance");
  api.setCacheEnabled(true);
  await api.settled();
  result.stats = api.stats();
  if (!result.stats.shaderc.spareReady || !result.stats.spvc.spareReady) fail("abort: no spare instance after recovery");
  result.instantiationFailure = await checkInstantiationFailures(jobs[0]);
  report.abort = result;
  console.log(`abort: spvc generation ${before} -> ${after}, shaderc generation ${result.stats.shaderc.generation}, `
    + `instantiations shaderc=${result.stats.shaderc.instantiations} spvc=${result.stats.spvc.instantiations}; `
    + `failed instantiation: start-up rejected, spare failure counted and retried`);
}

// A WebAssembly instantiation that fails (LinkError here, out-of-memory in a
// browser) must settle: start-up rejects instead of hanging, and a failed spare
// is counted and retried instead of blocking every later recovery.
async function checkInstantiationFailures(job) {
  const loader = require(join(toolchainDir, "gaius-shader-toolchain.js"));
  const shadercFactory = require(join(toolchainDir, "gaius-shaderc.js"));
  const spvcFactory = require(join(toolchainDir, "gaius-spvc.js"));
  const [shadercModule, spvcModule] = await Promise.all([
    WebAssembly.compile(readFileSync(join(toolchainDir, "gaius-shaderc.wasm"))),
    WebAssembly.compile(readFileSync(join(toolchainDir, "gaius-spvc.wasm")))]);
  const within = (promise, ms, label) => {
    let timer = null;
    const timeout = new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`${label} did not settle within ${ms} ms`)), ms);
    });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
  };
  // A valid module importing env.missing, which the emscripten imports lack.
  const unlinkable = new WebAssembly.Module(Uint8Array.from([0, 97, 115, 109, 1, 0, 0, 0, 1, 4, 1, 96, 0, 0,
    2, 15, 1, 3, 101, 110, 118, 7, 109, 105, 115, 115, 105, 110, 103, 0, 0]));
  const result = {};
  try {
    await within(loader.createToolchain({shadercFactory, spvcFactory, shadercModule: unlinkable, spvcModule,
      cacheVersion: "instantiation-failure"}), 10000, "createToolchain with an unlinkable shaderc module");
    fail("abort: createToolchain resolved with an unlinkable shaderc module");
  } catch (error) {
    if (/did not settle/.test(error.message)) fail(`abort: ${error.message}`);
    result.startup = error.message;
  }
  // The third shaderc instantiation (the spare started after the first trap)
  // fails; the fourth, started by the next call that needs an instance, works.
  let instantiations = 0;
  const flakyFactory = (moduleArg) => {
    instantiations++;
    if (instantiations === 3) {
      const instantiate = moduleArg.instantiateWasm;
      moduleArg.instantiateWasm = (imports, receiveInstance) => instantiate({}, receiveInstance);
    }
    return shadercFactory(moduleArg);
  };
  const api = await within(loader.createToolchain({shadercFactory: flakyFactory, spvcFactory, shadercModule, spvcModule,
    cacheVersion: "instantiation-failure"}), 30000, "createToolchain");
  api.setCacheEnabled(false);
  await within(api.settled(), 30000, "settled() after start-up");
  api.debugTrap("shaderc");
  replayShadercJob(api, job, nodeReplayContext);
  await within(api.settled(), 30000, "settled() after a failed spare instantiation");
  const failed = api.stats().shaderc;
  if (failed.spareFailures !== 1) fail(`abort: a failed spare instantiation was counted ${failed.spareFailures} times`);
  api.debugTrap("shaderc");
  await within(api.settled(), 30000, "settled() after retrying the spare");
  const got = replayShadercJob(api, job, nodeReplayContext);
  if (got.status !== job.status || got.spirv !== job.spirv) {
    fail(`abort: shaderc after a failed spare instantiation: ${JSON.stringify(got)}`);
  }
  await within(api.settled(), 30000, "settled() after recovery");
  result.stats = api.stats().shaderc;
  if (!result.stats.spareReady) fail("abort: no spare shaderc instance after retrying a failed instantiation");
  return result;
}

// ---------------------------------------------------------------------------------------------
// 5. WebGL2 in headless Chrome
function chromePath() {
  const candidates = [options.chrome, process.env.GAIUS_CHROME,
    "C:/Program Files/Google/Chrome/Application/chrome.exe", "/usr/bin/google-chrome", "/usr/bin/chromium"];
  return candidates.find((candidate) => candidate && existsSync(candidate));
}

function webglPage(data) {
  const canvas = document.createElement("canvas");
  const gl = canvas.getContext("webgl2");
  if (!gl) return {error: "no webgl2"};
  const debug = gl.getExtension("WEBGL_debug_renderer_info");
  const env = {version: gl.getParameter(gl.VERSION),
    renderer: debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER)};
  const results = [];
  const started = performance.now();
  for (const pipeline of data) {
    const result = {location: pipeline.location, linked: false, log: "", missing: []};
    const shaders = [];
    for (const [type, source] of [[gl.VERTEX_SHADER, pipeline.vs], [gl.FRAGMENT_SHADER, pipeline.fs]]) {
      const shader = gl.createShader(type);
      gl.shaderSource(shader, source);
      gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) result.log += gl.getShaderInfoLog(shader) || "compile failed";
      shaders.push(shader);
    }
    if (!result.log) {
      const program = gl.createProgram();
      shaders.forEach((shader) => gl.attachShader(program, shader));
      gl.linkProgram(program);
      result.linked = !!gl.getProgramParameter(program, gl.LINK_STATUS);
      if (!result.linked) result.log = gl.getProgramInfoLog(program) || "link failed";
      else {
        for (const uniform of pipeline.uniforms) {
          const found = uniform.type === "UNIFORM_BUFFER"
            ? gl.getUniformBlockIndex(program, uniform.glName) !== gl.INVALID_INDEX
            : gl.getUniformLocation(program, uniform.glName) !== null;
          if (!found) result.missing.push(`${uniform.glName}(${uniform.name})`);
        }
        if (pipeline.pushConstantsSize > 0 && gl.getUniformBlockIndex(program, "_push_constants") === gl.INVALID_INDEX) {
          result.missing.push("_push_constants");
        }
      }
      gl.deleteProgram(program);
    }
    shaders.forEach((shader) => gl.deleteShader(shader));
    results.push(result);
  }
  return {env, totalMs: Math.round(performance.now() - started), results};
}

// Starts headless Chrome and calls body(send, navigate) with a CDP connection to its page.
async function withChrome(label, body) {
  const chrome = chromePath();
  if (!chrome) usage("Chrome was not found (pass --chrome)");
  const profileDir = join(out, `${label}${options.swiftshader ? "-swiftshader" : ""}`);
  rmSync(profileDir, {recursive: true, force: true});
  mkdirSync(profileDir, {recursive: true});
  const args = ["--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profileDir}`, "--no-first-run",
    "--no-default-browser-check", "--disable-extensions", "--mute-audio", "about:blank"];
  if (options.swiftshader) args.splice(1, 0, "--use-angle=swiftshader", "--enable-unsafe-swiftshader");
  const browser = spawn(chromePath(), args, {stdio: ["ignore", "ignore", "pipe"]});
  let stderr = "";
  browser.stderr.on("data", (chunk) => { stderr += chunk; });
  try {
    const portFile = join(profileDir, "DevToolsActivePort");
    let port = 0;
    for (let attempt = 0; attempt < 300 && !port; attempt++) {
      if (existsSync(portFile)) port = Number(readFileSync(portFile, "utf8").split("\n")[0]) || 0;
      if (!port) await new Promise((done) => setTimeout(done, 100));
    }
    if (!port) throw new Error(`Chrome did not start: ${stderr.slice(0, 1000)}`);
    const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    const socket = new WebSocket(targets.find((target) => target.type === "page").webSocketDebuggerUrl);
    await new Promise((done, reject) => { socket.onopen = done; socket.onerror = reject; });
    const pending = new Map();
    const listeners = [];
    let nextId = 1;
    socket.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.id && pending.has(message.id)) {
        pending.get(message.id)(message);
        pending.delete(message.id);
      } else if (message.method) {
        for (const listener of listeners) listener(message);
      }
    };
    const send = (method, params = {}) => new Promise((done) => {
      const id = nextId++;
      pending.set(id, done);
      socket.send(JSON.stringify({id, method, params}));
    });
    const navigate = async (url) => {
      await send("Page.enable");
      const loaded = new Promise((done) => listeners.push((message) => {
        if (message.method === "Page.loadEventFired") done();
      }));
      await send("Page.navigate", {url});
      await loaded;
      listeners.length = 0;
    };
    try {
      return await body(send, navigate);
    } finally {
      socket.close();
    }
  } finally {
    browser.kill();
  }
}

async function evaluate(send, expression) {
  const response = await send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true});
  if (!response.result || response.result.exceptionDetails) {
    throw new Error(`page evaluation failed: ${JSON.stringify(response).slice(0, 3000)}`);
  }
  return response.result.result.value;
}

async function stageWebgl() {
  const pipelines = readPipelines("shim").filter((pipeline) => !pipeline.error);
  const glsl = (hash) => readFileSync(join(out, "shim", "glsl", `${hash}.glsl`), "utf8");
  const data = pipelines.map((pipeline) => ({
    location: pipeline.location,
    vs: glsl(pipeline.shaders.find((shader) => shader.type === "VERTEX").glsl),
    fs: glsl(pipeline.shaders.find((shader) => shader.type === "FRAGMENT").glsl),
    uniforms: pipeline.uniforms,
    pushConstantsSize: pipeline.pushConstantsSize,
  }));
  const value = await withChrome("chrome-webgl", (send) => evaluate(send, `(${webglPage})(${JSON.stringify(data)})`));
  if (!value || value.error) throw new Error(`WebGL2 evaluation failed: ${JSON.stringify(value).slice(0, 2000)}`);
  const failed = value.results.filter((result) => !result.linked);
  const missing = value.results.filter((result) => result.linked && result.missing.length);
  report.webgl = {env: value.env, pipelines: value.results.length, linked: value.results.length - failed.length,
    totalMs: value.totalMs, failed: failed.slice(0, 20), missingBindings: missing.map((result) =>
      ({location: result.location, missing: result.missing}))};
  if (value.results.length !== 216) fail(`WebGL2 checked ${value.results.length} pipelines, expected 216`);
  for (const result of failed.slice(0, 10)) fail(`WebGL2 ${result.location}: ${result.log.split("\n")[0]}`);
  // GlProgram tolerates -1 for samplers the compiler removed; report, do not fail.
  console.log(`webgl: ${value.results.length - failed.length}/${value.results.length} linked on ${value.env.renderer}`
    + ` in ${value.totalMs} ms; ${missing.length} programs without some binding name`);
}

// ---------------------------------------------------------------------------------------------
// 6. the page loader in Chrome: script-tag bootstrap, WebAssembly replay, IndexedDB cache across a reload
const CONTENT_TYPES = {".js": "text/javascript", ".wasm": "application/wasm", ".jsonl": "application/json",
  ".html": "text/html", ".glsl": "text/plain", ".spv": "application/octet-stream"};

// Runs inside the page.
async function browserReplay(tapeFiles, pass) {
  const api = await window.__gaiusShaderToolchainReady;
  const text = async (path) => (await fetch(path)).text();
  const lines = (body) => body.split("\n").filter(Boolean).map((line) => JSON.parse(line));
  const jobs = lines(await text("tape/tape-shaderc.jsonl"));
  const sessions = lines(await text("tape/tape-spvc.jsonl"));
  const blobs = new Map();
  await Promise.all(tapeFiles.map(async (file) => {
    blobs.set(file, new Uint8Array(await (await fetch(`tape/${file}`)).arrayBuffer()));
  }));
  const encoder = new TextEncoder();
  const ctx = {blob: (sub, hash, ext) => blobs.get(`${sub}/${hash}${ext}`), hash: api.sha256Hex,
    utf8: (value) => encoder.encode(value)};
  // eslint-disable-next-line no-eval
  const replayShaderc = (0, eval)(`(${pass.replayShadercJob})`);
  // eslint-disable-next-line no-eval
  const replaySpvc = (0, eval)(`(${pass.replaySpvcSession})`);
  const run = () => {
    const before = api.stats();
    const started = performance.now();
    let identical = 0;
    for (const job of jobs) {
      const got = replayShaderc(api, job, ctx);
      if (got.status === job.status && got.spirv === job.spirv && got.error === job.error) identical++;
    }
    const shadercMs = performance.now() - started;
    const spvcStarted = performance.now();
    const generation = api.spvcGeneration();
    let mismatches = 0;
    for (const session of sessions) mismatches += replaySpvc(api, session, generation, ctx).length;
    const after = api.stats();
    return {jobs: jobs.length, identical, sessions: sessions.length, mismatches,
      shadercMs: Math.round(shadercMs), spvcMs: Math.round(performance.now() - spvcStarted),
      cacheHits: after.cache.hits - before.cache.hits};
  };
  const result = {loadMs: api.loadMs, cacheAtStart: api.stats().cache, passes: []};
  if (pass.cold) {
    api.setCacheEnabled(false);
    result.passes.push({label: "cold, no cache", ...run()});
    api.setCacheEnabled(true);
  }
  result.passes.push({label: pass.cold ? "empty cache" : "after reload", ...run()});
  result.flushed = await api.flushCache();
  result.stats = api.stats();
  result.userAgent = navigator.userAgent;
  return result;
}

async function stageBrowser() {
  const {createServer} = await import("node:http");
  const tapeDir = join(out, "shim");
  const tapeFiles = [];
  for (const sub of ["tape-source", "tape-include", "tape-spirv"]) {
    for (const name of readdirSync(join(tapeDir, sub))) tapeFiles.push(`${sub}/${name}`);
  }
  const token = sha256(Buffer.concat(["gaius-shader-toolchain.js", "gaius-shaderc.js", "gaius-shaderc.wasm",
    "gaius-spvc.js", "gaius-spvc.wasm"].map((file) => readFileSync(join(toolchainDir, file))))).slice(0, 16);
  const page = "<!doctype html><html><head><meta charset=\"utf-8\">"
    + `<script data-gaius-shader-toolchain data-profile="smoke-${profile}" src="gaius-shader-toolchain.js?v=${token}"></script>`
    + "</head><body>shader toolchain smoke</body></html>";
  const server = createServer((request, response) => {
    const path = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    if (path === "/" || path === "/index.html") {
      response.writeHead(200, {"Content-Type": "text/html"});
      response.end(page);
      return;
    }
    const base = path.startsWith("/tape/") ? tapeDir : toolchainDir;
    const file = join(base, path.startsWith("/tape/") ? path.slice("/tape/".length) : path.slice(1));
    if (relative(base, file).startsWith("..") || !existsSync(file) || !statSync(file).isFile()) {
      response.writeHead(404);
      response.end();
      return;
    }
    response.writeHead(200, {"Content-Type": CONTENT_TYPES[file.slice(file.lastIndexOf("."))]
      || "application/octet-stream"});
    response.end(readFileSync(file));
  });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const functions = {replayShadercJob: replayShadercJob.toString(), replaySpvcSession: replaySpvcSession.toString()};
  try {
    const [first, second] = await withChrome("chrome-loader", async (send, navigate) => {
      await navigate(url);
      const cold = await evaluate(send,
        `(${browserReplay})(${JSON.stringify(tapeFiles)}, ${JSON.stringify({...functions, cold: true})})`);
      await navigate(url);
      const reload = await evaluate(send,
        `(${browserReplay})(${JSON.stringify(tapeFiles)}, ${JSON.stringify({...functions, cold: false})})`);
      return [cold, reload];
    });
    report.browser = {first, second};
    for (const pass of [...first.passes, ...second.passes]) {
      if (pass.identical !== pass.jobs) fail(`browser ${pass.label}: shaderc matched ${pass.identical}/${pass.jobs}`);
      if (pass.mismatches !== 0) fail(`browser ${pass.label}: ${pass.mismatches} spvc mismatches`);
    }
    const cold = first.passes[0];
    const warm = second.passes[0];
    if (second.cacheAtStart.loaded < 1) fail("browser: the reload loaded nothing from the IndexedDB shader cache");
    if (warm.cacheHits !== warm.jobs) fail(`browser: after the reload ${warm.cacheHits}/${warm.jobs} jobs hit the cache`);
    if (cold.shadercMs + cold.spvcMs > 5000) fail(`browser: cold pass took ${cold.shadercMs + cold.spvcMs} ms (budget 5000 ms)`);
    if (warm.shadercMs + warm.spvcMs > 500) fail(`browser: reload pass took ${warm.shadercMs + warm.spvcMs} ms (budget 500 ms)`);
    console.log(`browser: ${(first.userAgent.match(/Chrome\/[0-9.]+/) || ["Chrome"])[0]}; load ${first.loadMs} ms;`
      + ` cold ${cold.shadercMs + cold.spvcMs} ms (shaderc ${cold.shadercMs} + spvc ${cold.spvcMs});`
      + ` reload loaded ${second.cacheAtStart.loaded} cached results, ${warm.shadercMs + warm.spvcMs} ms`
      + ` (${warm.cacheHits}/${warm.jobs} hits); ${cold.identical}/${cold.jobs} SPIR-V, ${cold.mismatches} spvc mismatches`);
  } finally {
    server.close();
  }
}

// ---------------------------------------------------------------------------------------------
// 0. overlay: javap assertions on the build-overlays.sh output (M1/M2)
function javapMethods(jar, className) {
  const {output} = run("javap", ["-c", "-p", "-s", "-cp", jar, className], `javap ${className}`);
  const methods = new Map();
  let current = null;
  for (const line of output.split(/\r?\n/)) {
    const header = /^ {2}(?:[a-z]+ )*(?:[^ (]+ )?([A-Za-z0-9_$.<>]+)\(.*\);$/.exec(line)
      || /^ {2}static \{\};$/.exec(line);
    if (header) {
      const name = header[1] || "<clinit>";
      current = {name: name.includes(".") ? "<init>" : name, descriptor: null, code: []};
      continue;
    }
    if (!current) continue;
    const descriptor = /^ {4}descriptor: (.+)$/.exec(line);
    if (descriptor) {
      current.descriptor = descriptor[1];
      methods.set(current.name + current.descriptor, current);
      continue;
    }
    current.code.push(line);
  }
  return methods;
}

function clientShimCalls(client) {
  // Every Shaderc/Spvc method the client calls (renderpearl frontend and GL backend),
  // including method references (GlslCompiler.close() passes Shaderc::shaderc_compiler_release
  // as a LongConsumer), which only show up as REF_invokeStatic handles in the constant pool.
  const listing = run("jar", ["--list", "--file", client], "jar --list").output.split(/\r?\n/);
  const classes = listing.filter((name) => /^com\/mojang\/(renderpearl|blaze3d)\/.*\.class$/.test(name))
    .map((name) => name.slice(0, -6).replaceAll("/", "."));
  const calls = new Set();
  for (let index = 0; index < classes.length; index += 200) {
    const {output} = run("javap", ["-v", "-p", "-cp", client, ...classes.slice(index, index + 200)], "javap client");
    for (const match of output.matchAll(/(?:\/\/ Method|REF_invokeStatic) org\/lwjgl\/util\/(shaderc\/Shaderc|spvc\/Spvc)\.([A-Za-z0-9_]+):(\S+)/g)) {
      calls.add(`${match[1]}#${match[2]}${match[3]}`);
    }
  }
  return calls;
}

function stageOverlay() {
  const overlay = fromRoot(options["overlay-dir"] || join("port/work/overlays", profile));
  const jar = (module) => join(overlay, "libraries/org/lwjgl", module, "3.4.3", `${module}-3.4.3.jar`);
  // The patched client (its NativeLibrariesBootstrap no longer asks for the native libraries),
  // or the vanilla jar when the overlay has none.
  const patchedClient = join(overlay, `client-named-${profile}-gaius.jar`);
  const calls = clientShimCalls(existsSync(patchedClient) ? patchedClient : clientJar);
  const result = {clientCalls: calls.size, client: existsSync(patchedClient) ? "patched" : "vanilla"};
  if (calls.size < 41) fail(`the client calls only ${calls.size} Shaderc/Spvc methods (expected 41)`);
  if (!calls.has("shaderc/Shaderc#shaderc_compiler_release(J)V")) {
    fail("the Shaderc::shaderc_compiler_release method reference of GlslCompiler.close() was not found");
  }
  for (const [module, owner, shim] of [["lwjgl-shaderc", "shaderc/Shaderc", "org/lwjgl/util/shaderc/BrowserShaderc"],
    ["lwjgl-spvc", "spvc/Spvc", "org/lwjgl/util/spvc/BrowserSpvc"]]) {
    if (!existsSync(jar(module))) usage(`${jar(module)} is missing (run build-overlays.sh for ${profile})`);
    const methods = javapMethods(jar(module), `org.lwjgl.util.${owner.replace("/", ".")}`);
    const clinit = methods.get("<clinit>()V");
    if (!clinit || clinit.code.some((line) => line.includes("Library.loadNative"))) {
      fail(`${module}: the static initializer still loads the native library`);
    }
    let redirected = 0;
    let unsupported = 0;
    for (const [key, method] of methods) {
      const code = method.code.join("\n");
      if (code.includes(`Method ${shim}.${method.name}:${method.descriptor}`)) redirected++;
      else if (method.name !== "<init>" && code.includes("java/lang/UnsupportedOperationException")) unsupported++;
      if (/Method org\/lwjgl\/system\/JNI\./.test(code)) fail(`${module}: ${key} still calls JNI`);
    }
    // getLibrary() only returns the SharedLibrary field, which the new initializer leaves null.
    const getLibrary = methods.get("getLibrary()Lorg/lwjgl/system/SharedLibrary;");
    if (!getLibrary || getLibrary.code.some((line) => /invoke/.test(line))) {
      fail(`${module}: getLibrary() is not a plain field read`);
    }
    for (const call of calls) {
      if (!call.startsWith(`${owner}#`)) continue;
      const key = call.slice(owner.length + 1);
      if (key === "getLibrary()Lorg/lwjgl/system/SharedLibrary;") continue;
      const method = methods.get(key);
      if (!method || !method.code.join("\n").includes(`Method ${shim}.${method.name}:${method.descriptor}`)) {
        fail(`${module}: the client calls ${key}, which is not redirected to ${shim}`);
      }
    }
    for (const shimClass of ["BrowserShaderc", "BrowserShadercWasm", "BrowserShadercJob", "BrowserSpvc",
      "BrowserSpvcWasm", "BrowserEsslPostProcessor"]) {
      const inModule = module === "lwjgl-shaderc" ? shimClass.startsWith("BrowserShaderc") : !shimClass.startsWith("BrowserShaderc");
      if (inModule) {
        const listing = run("jar", ["--list", "--file", jar(module)], "jar --list").output;
        if (!listing.includes(`/${shimClass}.class`)) fail(`${module}: ${shimClass} is not in the overlay jar`);
      }
    }
    result[module] = {redirected, unsupported};
  }
  report.overlay = result;
  console.log(`overlay: client calls ${calls.size} Shaderc/Spvc methods; `
    + `Shaderc ${result["lwjgl-shaderc"].redirected} redirected / ${result["lwjgl-shaderc"].unsupported} unsupported, `
    + `Spvc ${result["lwjgl-spvc"].redirected} redirected / ${result["lwjgl-spvc"].unsupported} unsupported`);
}

// ---------------------------------------------------------------------------------------------
mkdirSync(out, {recursive: true});
try {
  if (stages.has("overlay")) stageOverlay();
  if (stages.has("harness")) stageHarness();
  if (stages.has("compare")) stageCompare();
  let api = null;
  if (stages.has("wasm")) api = await stageWasm();
  if (stages.has("abort")) await stageAbort(api);
  if (stages.has("webgl")) await stageWebgl();
  if (stages.has("browser")) await stageBrowser();
} catch (error) {
  fail(error.stack || String(error));
}
report.failures = failures;
const reportFile = options.report ? fromRoot(options.report) : join(out, "report.json");
writeFileSync(reportFile, `${JSON.stringify(report, null, 2)}\n`);
console.log(`report: ${relative(root, reportFile) || reportFile}`);
if (failures.length) {
  console.log(`SMOKE FAILED (${failures.length} failures)`);
  process.exit(1);
}
console.log("SMOKE PASSED");
