#!/usr/bin/env node
// Shader translation corpus check: every render pipeline of a Minecraft corpus (26.3, 26.2 or
// 1.21.11, see run-corpus.sh) through gaius-shaderxlat to WGSL, then through Chrome's WebGPU
// compiler (Tint) and pipeline creation with the pipeline's real layout. The translation options
// (binding layout, y flip, GL depth remap) are those of the native run (results.json
// summary.options).
//
//  link    (blaze3d corpora, needs --frontend) Runs the GLSL front-end's link of every pipeline
//          of the dump in the wasm build in Node and requires the linked modules and entries of
//          the native link (shaderxlat-link, the corpus); times the links.
//  wasm    Loads the wasm build in Node, translates every corpus module and requires the WGSL to
//          be byte-identical to the native run (shaderxlat-corpus results); times instantiate,
//          first and warm translations.
//  chrome  Headless Chrome over CDP on a local HTTP page: the same translations on a synchronous
//          main-thread instance (the finishCompile path), createShaderModule + getCompilationInfo
//          for every module, and createRenderPipelineAsync for every pipeline with the bind group
//          layouts from the translation's reflection (split layout: group 0 uniform buffers with
//          dynamic offsets, group 1 textures, samplers and storage buffers), the vertex buffers,
//          color targets, blending, depth state and topology of the vanilla CreateInfo, and the
//          front face of the y convention.
//
// usage: node port/native/golden/shaders/xlat-check.mjs [--stages link,wasm,chrome] [--corpus DIR]
//          [--native DIR] [--frontend DIR] [--wasm FILE] [--iterations N] [--chrome PATH] [--report FILE]
//          [--device-features LIST] [--chrome-args "FLAGS"] [--fallback-adapter true]
//          [--profile-dir DIR]
// --profile-dir keeps the Chrome profile (and with it Dawn's pipeline cache) between runs.
// --fallback-adapter true asks for the fallback (software) adapter, e.g. with
// --chrome-args "--enable-unsafe-webgpu --use-webgpu-adapter=swiftshader".
// --device-features: optional WebGPU features to request when the adapter has them (comma list,
// "none" for a baseline device); default float32-blendable,float32-filterable.
// Chrome runs with every non-loopback request sent to a dead proxy (no external network).
// Defaults: corpus port/native/target/shader-corpus/webgpu (dump-corpus.sh), native
// port/native/target/shaderxlat-out/webgpu (shaderxlat-corpus), wasm
// port/native/target/shaderxlat-wasm-simd/wasm32-unknown-unknown/release-size/gaius_shaderxlat_wasm.wasm.
// Exit status: 0 pass, 1 failure, 2 usage or environment error.

import {spawn} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {createServer} from "node:http";
import {tmpdir} from "node:os";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const native = resolve(here, "../..");
const options = {stages: "wasm,chrome", iterations: "20"};
for (let i = 2; i < process.argv.length; i++) {
  const arg = process.argv[i];
  if (arg.startsWith("--") && i + 1 < process.argv.length) options[arg.slice(2)] = process.argv[++i];
  else {
    console.error(`xlat-check: unknown or incomplete argument ${arg}`);
    process.exit(2);
  }
}
const stages = new Set(options.stages.split(","));
const corpusDir = resolve(options.corpus || join(native, "target/shader-corpus/webgpu"));
const nativeDir = resolve(options.native || join(native, "target/shaderxlat-out/webgpu"));
const wasmFile = resolve(options.wasm ||
  join(native, "target/shaderxlat-wasm-simd/wasm32-unknown-unknown/release-size/gaius_shaderxlat_wasm.wasm"));
const iterations = Number(options.iterations);
const deviceFeatures = (options["device-features"] || "float32-blendable,float32-filterable")
  .split(",").filter((f) => f && f !== "none");
const chromeBin = options.chrome || process.env.GAIUS_CHROME || "C:/Program Files/Google/Chrome/Application/chrome.exe";
for (const [label, path] of [["corpus", join(corpusDir, "corpus.json")], ["native results", join(nativeDir, "results.json")],
  ["wasm module", wasmFile]]) {
  if (!existsSync(path)) {
    console.error(`xlat-check: missing ${label}: ${path}`);
    process.exit(2);
  }
}

const corpus = JSON.parse(readFileSync(join(corpusDir, "corpus.json"), "utf8"));
const nativeResults = JSON.parse(readFileSync(join(nativeDir, "results.json"), "utf8"));
// A results.json without options is the first prototype's (flat layout, no flip).
const translateOptions = Object.assign({layout: "flat", flipVertexY: false, glDepthRemap: false},
  nativeResults.summary && nativeResults.summary.options);
const failures = [];
const fail = (message) => {
  failures.push(message);
  console.log(`FAIL ${message}`);
};

// The jobs of the native run: one per (module, texel buffer formats).
const texelBuffers = (pipeline) => (pipeline.uniforms || []).flatMap((u, i) =>
  u.type === "TEXEL_BUFFER" ? [[i, u.gpuFormat]] : []);
const texelKey = (texel) => texel.map(([b, f]) => `-t${b}${{R8_SINT: "R8Sint", R8_UINT: "R8Uint", R16_SINT: "R16Sint",
  R16_UINT: "R16Uint", R32_SINT: "R32Sint", R32_UINT: "R32Uint", R32_FLOAT: "R32Float"}[f]}`).join("");
const jobs = new Map();
for (const pipeline of corpus.pipelines) {
  const texel = texelBuffers(pipeline);
  for (const shader of pipeline.shaders || []) {
    const key = shader.spirv + texelKey(texel);
    if (!jobs.has(key)) jobs.set(key, {key, spirv: shader.spirv, texel});
  }
}
const nativeWgsl = (key) => readFileSync(join(nativeDir, "wgsl", `${key}.wgsl`), "utf8");
const report = {corpus: corpus.summary, native: nativeResults.summary, translateOptions, jobs: jobs.size};

const stats = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  const pick = (q) => sorted[Math.round((sorted.length - 1) * q)];
  const round = (v) => Math.round(v * 1000) / 1000;
  return {count: sorted.length, sum: round(sorted.reduce((a, b) => a + b, 0)),
    mean: round(sorted.reduce((a, b) => a + b, 0) / sorted.length), p50: round(pick(0.5)), p95: round(pick(0.95)),
    max: round(pick(1))};
};

// ---------------------------------------------------------------------------------------------
// The GLSL front-end's link in wasm (Node) against the native link.
async function stageLink() {
  const frontendDir = options.frontend && resolve(options.frontend);
  if (!frontendDir || !existsSync(join(frontendDir, "frontend.json"))) {
    console.error(`xlat-check: the link stage needs --frontend DIR with frontend.json`);
    process.exit(2);
  }
  await import(pathToFileURL(join(here, "shaderxlat-job.js")).href);
  const X = globalThis.GaiusShaderXlatJob;
  const xlat = X.instantiateSync(await WebAssembly.compile(readFileSync(wasmFile)));
  const frontend = JSON.parse(readFileSync(join(frontendDir, "frontend.json"), "utf8"));
  const lenient = (corpus.summary && corpus.summary.mode) === "lenient";
  const linkedByLocation = new Map(corpus.pipelines.map((p) => [p.location, p]));
  const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");
  const times = [];
  let identical = 0, compared = 0;
  for (const pipeline of frontend.pipelines) {
    if (!pipeline.intermediate) continue;
    const linked = linkedByLocation.get(pipeline.location);
    const spirv = (hash) => readFileSync(join(frontendDir, "spirv", `${hash}.spv`));
    const layout = {
      uniforms: pipeline.layout.uniforms.map((u) => ({name: u.name, texelFormat: u.gpuFormat})),
      samplers: pipeline.layout.samplers,
      attributes: pipeline.layout.attributes,
    };
    compared++;
    let result;
    const t = performance.now();
    try {
      result = xlat.link(spirv(pipeline.intermediate.VERTEX), spirv(pipeline.intermediate.FRAGMENT), layout, {lenient});
    } catch (e) {
      if (linked && linked.error && String(linked.error) === String(e.message)) identical++;
      else fail(`wasm link of ${pipeline.location}: ${e.message}`);
      continue;
    }
    times.push(performance.now() - t);
    if (!linked || !linked.shaders) {
      fail(`wasm links ${pipeline.location}, the native link did not`);
      continue;
    }
    const sameModules = sha(result.vertex) === linked.shaders[0].spirv && sha(result.fragment) === linked.shaders[1].spirv;
    const sameEntries = JSON.stringify(result.entries) === JSON.stringify(linked.uniforms.map((u) =>
      ({name: u.name, type: u.type, gpuFormat: u.gpuFormat, declared: u.declared})));
    const sameFixes = JSON.stringify(result.fixes.map((f) => f.kind + ":" + f.name)) ===
      JSON.stringify((linked.frontend?.fixes || []).map((f) => f.split(":").slice(0, 2).join(":")));
    if (sameModules && sameEntries && sameFixes) identical++;
    else fail(`wasm link of ${pipeline.location} differs from native (modules ${sameModules}, entries ${sameEntries}, fixes ${sameFixes})`);
  }
  report.link = {pipelines: compared, identical, linkMs: stats(times)};
  console.log(`link (wasm, node): ${identical}/${compared} identical to native; ${stats(times).sum} ms total, ` +
    `p50 ${stats(times).p50} ms`);
}

// ---------------------------------------------------------------------------------------------
// wasm in Node
async function stageWasm() {
  await import(pathToFileURL(join(here, "shaderxlat-job.js")).href);
  const X = globalThis.GaiusShaderXlatJob;
  const bytes = readFileSync(wasmFile);
  let t = performance.now();
  const module = await WebAssembly.compile(bytes);
  const compileMs = performance.now() - t;
  if (WebAssembly.Module.imports(module).length) fail(`wasm module has imports: ${JSON.stringify(WebAssembly.Module.imports(module))}`);
  t = performance.now();
  const xlat = X.instantiateSync(module);
  const instantiateMs = performance.now() - t;
  const first = [];
  const warm = [];
  let identical = 0;
  for (const job of jobs.values()) {
    const spirv = readFileSync(join(corpusDir, "spirv", `${job.spirv}.spv`));
    t = performance.now();
    const result = xlat.translate(spirv, job.texel, translateOptions);
    first.push(performance.now() - t);
    if (result.wgsl === nativeWgsl(job.key)) identical++;
    else fail(`wasm WGSL of ${job.key} differs from native`);
    const runs = [];
    for (let i = 0; i < iterations; i++) {
      t = performance.now();
      xlat.translate(spirv, job.texel, translateOptions);
      runs.push(performance.now() - t);
    }
    runs.sort((a, b) => a - b);
    warm.push(runs[runs.length >> 1]);
  }
  report.wasmNode = {bytes: bytes.length, compileMs, instantiateMs, identical, firstMs: stats(first), warmMs: stats(warm)};
  console.log(`wasm (node): ${identical}/${jobs.size} identical to native; first ${stats(first).sum} ms total, ` +
    `warm p50 ${stats(warm).p50} ms`);
}

// ---------------------------------------------------------------------------------------------
// Chrome
const page = `<!doctype html><meta charset="utf-8"><title>shaderxlat check</title>
<script src="shaderxlat-job.js"></script>
<script type="module">
const X = globalThis.GaiusShaderXlatJob;
const VERTEX_FORMATS = {RGB32_FLOAT: "float32x3", RGBA8_UNORM: "unorm8x4", RG16_SINT: "sint16x2", RG32_FLOAT: "float32x2",
  RGBA8_SNORM: "snorm8x4", R32_FLOAT: "float32", RGB32_SINT: "sint32x3", RGBA32_FLOAT: "float32x4", RG32_SINT: "sint32x2",
  R32_SINT: "sint32", RGBA16_FLOAT: "float16x4", RG16_FLOAT: "float16x2", RGBA8_UINT: "uint8x4", RGBA16_SINT: "sint16x4",
  RG8_UNORM: "unorm8x2", RGBA16_UNORM: "unorm16x4", RG16_UNORM: "unorm16x2", R32_UINT: "uint32", RGBA32_UINT: "uint32x4"};
const COLOR_FORMATS = {RGBA8_UNORM: "rgba8unorm", RGBA16_FLOAT: "rgba16float", RGBA32_FLOAT: "rgba32float",
  R8_UNORM: "r8unorm", RG8_UNORM: "rg8unorm", R16_FLOAT: "r16float", R32_FLOAT: "r32float", RG16_FLOAT: "rg16float",
  RGB10A2_UNORM: "rgb10a2unorm", RG11B10_FLOAT: "rg11b10ufloat"};
const FACTORS = {ZERO: "zero", ONE: "one", SRC_COLOR: "src", ONE_MINUS_SRC_COLOR: "one-minus-src", DST_COLOR: "dst",
  ONE_MINUS_DST_COLOR: "one-minus-dst", SRC_ALPHA: "src-alpha", ONE_MINUS_SRC_ALPHA: "one-minus-src-alpha",
  DST_ALPHA: "dst-alpha", ONE_MINUS_DST_ALPHA: "one-minus-dst-alpha", SRC_ALPHA_SATURATE: "src-alpha-saturated",
  CONSTANT_COLOR: "constant", ONE_MINUS_CONSTANT_COLOR: "one-minus-constant", CONSTANT_ALPHA: "constant",
  ONE_MINUS_CONSTANT_ALPHA: "one-minus-constant"};
const OPS = {ADD: "add", SUBTRACT: "subtract", REVERSE_SUBTRACT: "reverse-subtract", MIN: "min", MAX: "max"};
const COMPARE = {ALWAYS_PASS: "always", LESS_THAN: "less", LESS_THAN_OR_EQUAL: "less-equal", EQUAL: "equal",
  NOT_EQUAL: "not-equal", GREATER_THAN_OR_EQUAL: "greater-equal", GREATER_THAN: "greater", NEVER_PASS: "never"};
const TOPOLOGY = {QUADS: "triangle-list", TRIANGLES: "triangle-list", TRIANGLE_FAN: "triangle-list", LINES: "triangle-list",
  TRIANGLE_STRIP: "triangle-strip", DEBUG_LINES: "line-list", DEBUG_LINE_STRIP: "line-strip", POINTS: "point-list"};
const ms = (t) => performance.now() - t;

window.__check = (async () => {
  const R = {notes: []};
  const {jobs, iterations, deviceFeatures, fallbackAdapter, translateOptions} = await (await fetch("jobs.json")).json();
  const corpus = await (await fetch("corpus.json")).json();
  const spirv = new Map();
  await Promise.all(jobs.map(async (job) => {
    if (!spirv.has(job.spirv)) spirv.set(job.spirv, new Uint8Array(await (await fetch("spirv/" + job.spirv + ".spv")).arrayBuffer()));
  }));
  const nativeText = new Map();
  await Promise.all(jobs.map(async (job) => nativeText.set(job.key, await (await fetch("native/" + job.key + ".wgsl")).text())));

  // 1. Translation on a synchronous main-thread instance.
  let t = performance.now();
  const module = await WebAssembly.compileStreaming(fetch("shaderxlat.wasm"));
  R.wasmCompileMs = ms(t);
  t = performance.now();
  const xlat = X.instantiateSync(module);
  R.wasmInstantiateMs = ms(t);
  const translated = new Map();
  const first = [], warm = [];
  let identical = 0;
  const failed = [];
  for (const job of jobs) {
    try {
      t = performance.now();
      const result = xlat.translate(spirv.get(job.spirv), job.texel, translateOptions);
      first.push(ms(t));
      translated.set(job.key, result);
      if (result.wgsl === nativeText.get(job.key)) identical++;
      const runs = [];
      for (let i = 0; i < iterations; i++) {
        t = performance.now();
        xlat.translate(spirv.get(job.spirv), job.texel, translateOptions);
        runs.push(ms(t));
      }
      runs.sort((a, b) => a - b);
      warm.push(runs[runs.length >> 1]);
    } catch (e) {
      failed.push({key: job.key, error: String(e.message || e)});
    }
  }
  R.translate = {jobs: jobs.length, ok: translated.size, identical, failed, first, warm};

  // 2. WebGPU.
  if (!navigator.gpu) return Object.assign(R, {error: "no navigator.gpu"});
  const adapter = await navigator.gpu.requestAdapter(fallbackAdapter ? {forceFallbackAdapter: true}
    : {powerPreference: "high-performance"});
  if (!adapter) return Object.assign(R, {error: "no adapter"});
  R.adapter = {vendor: adapter.info.vendor, architecture: adapter.info.architecture, description: adapter.info.description,
    features: [...adapter.features].sort()};
  const requiredFeatures = deviceFeatures.filter((f) => adapter.features.has(f));
  const device = await adapter.requestDevice({requiredFeatures});
  R.deviceFeatures = requiredFeatures;
  const lost = [];
  device.lost.then((info) => lost.push(info.message));
  const uncaptured = [];
  device.addEventListener("uncapturederror", (e) => uncaptured.push(e.error.message));

  const shaderModules = new Map();
  const compile = [];
  const shaderFailures = [];
  let warnings = 0;
  for (const [key, result] of translated) {
    device.pushErrorScope("validation");
    t = performance.now();
    const sm = device.createShaderModule({code: result.wgsl, label: key});
    const info = await sm.getCompilationInfo();
    compile.push(ms(t));
    const error = await device.popErrorScope();
    const errors = info.messages.filter((m) => m.type === "error");
    warnings += info.messages.filter((m) => m.type === "warning").length;
    if (errors.length || error) {
      shaderFailures.push({key, error: error && error.message,
        messages: info.messages.map((m) => m.type + " " + m.lineNum + ":" + m.linePos + " " + m.message).slice(0, 5)});
    } else {
      shaderModules.set(key, sm);
    }
  }
  R.shaders = {modules: translated.size, ok: shaderModules.size, warnings, failed: shaderFailures, compile};

  const texelKey = (texel) => texel.map(([b, f]) => "-t" + b + ({R8_SINT: "R8Sint", R8_UINT: "R8Uint", R16_SINT: "R16Sint",
    R16_UINT: "R16Uint", R32_SINT: "R32Sint", R32_UINT: "R32Uint", R32_FLOAT: "R32Float"})[f]).join("");
  const pipelineFailures = [];
  const create = [], createAgain = [];
  let ok = 0;
  for (const pipeline of corpus.pipelines) {
    const texel = (pipeline.uniforms || []).flatMap((u, i) => u.type === "TEXEL_BUFFER" ? [[i, u.gpuFormat]] : []);
    const stages = (pipeline.shaders || []).map((s) => ({type: s.type, key: s.spirv + texelKey(texel), entryPoint: s.entryPoint}));
    const vs = stages.find((s) => s.type === "VERTEX"), fs = stages.find((s) => s.type === "FRAGMENT");
    if (!vs || !shaderModules.has(vs.key) || (fs && !shaderModules.has(fs.key))) {
      pipelineFailures.push({location: pipeline.location, error: "shader module missing"});
      continue;
    }
    // The bind groups from the union of both stages' reflection, keyed "group:binding".
    const entries = new Map();
    const slot = (group, binding) => group + ":" + binding;
    for (const s of [vs, fs].filter(Boolean)) {
      for (const b of translated.get(s.key).bindings) {
        const visibility = s.type === "VERTEX" ? GPUShaderStage.VERTEX : GPUShaderStage.FRAGMENT;
        const old = entries.get(slot(b.group, b.binding));
        const sampled = (old && old.sampled) || b.sampled;
        entries.set(slot(b.group, b.binding),
          Object.assign({}, old || b, {visibility: (old ? old.visibility : 0) | visibility, sampled}));
      }
    }
    const groups = [];
    for (const b of [...entries.values()].sort((a, b) => a.group - b.group || a.binding - b.binding)) {
      const entry = {binding: b.binding, visibility: b.visibility};
      // The split layout binds every uniform buffer from the uniform ring with a dynamic offset.
      if (b.type === "uniform") {
        entry.buffer = {type: "uniform", minBindingSize: b.size, hasDynamicOffset: translateOptions.layout === "split"};
      } else if (b.type === "storage") entry.buffer = {type: "read-only-storage"};
      else if (b.type === "texture") {
        entry.texture = {viewDimension: b.dim, multisampled: b.multisampled,
          sampleType: b.sampleKind === "float" ? (b.sampled ? "float" : "unfilterable-float") : b.sampleKind};
      } else {
        const texture = entries.get(slot(b.group, b.binding - 1));
        entry.sampler = {type: b.comparison ? "comparison" : (texture && texture.sampled ? "filtering" : "non-filtering")};
      }
      (groups[b.group] ||= []).push(entry);
    }
    // A group without bindings (no uniform buffer, say) still needs a layout below a used one.
    const bindGroupLayouts = Array.from(groups, (g) => device.createBindGroupLayout({entries: g || []}));
    const vertexBuffers = [];
    for (const vb of pipeline.vertexBuffers || []) {
      vertexBuffers[vb.bufferSlot] = {arrayStride: vb.stride, stepMode: vb.stepRate === 0 ? "vertex" : "instance",
        attributes: (pipeline.attribBindings || []).filter((a) => a.bufferSlot === vb.bufferSlot)
          .map((a) => ({shaderLocation: a.location, offset: a.offset, format: VERTEX_FORMATS[a.format]}))};
    }
    for (let i = 0; i < vertexBuffers.length; i++) if (!vertexBuffers[i]) vertexBuffers[i] = null;
    const targets = (pipeline.colorTargets || []).map((c) => c && {
      format: COLOR_FORMATS[c.format], writeMask: c.writeMask,
      blend: c.blendFunction ? {
        color: {srcFactor: FACTORS[c.blendFunction.color.sourceFactor], dstFactor: FACTORS[c.blendFunction.color.destFactor],
          operation: OPS[c.blendFunction.color.op]},
        alpha: {srcFactor: FACTORS[c.blendFunction.alpha.sourceFactor], dstFactor: FACTORS[c.blendFunction.alpha.destFactor],
          operation: OPS[c.blendFunction.alpha.op]}} : undefined});
    const topology = TOPOLOGY[pipeline.topology];
    const ds = pipeline.depthStencil;
    const descriptor = {
      label: pipeline.location,
      layout: device.createPipelineLayout({bindGroupLayouts}),
      vertex: {module: shaderModules.get(vs.key), entryPoint: "main", buffers: vertexBuffers},
      fragment: fs ? {module: shaderModules.get(fs.key), entryPoint: "main", targets} : undefined,
      // Rendering with y flipped (GL row order) mirrors the winding: front faces turn clockwise.
      primitive: {topology, cullMode: pipeline.cull ? "back" : "none", frontFace: translateOptions.flipVertexY ? "cw" : "ccw"},
      depthStencil: ds ? {format: "depth32float", depthWriteEnabled: ds.writeDepth, depthCompare: COMPARE[ds.depthTest],
        depthBias: topology.startsWith("triangle") ? Math.round(ds.depthBiasConstant) : 0,
        depthBiasSlopeScale: topology.startsWith("triangle") ? ds.depthBiasScaleFactor : 0} : undefined,
    };
    try {
      t = performance.now();
      await device.createRenderPipelineAsync(descriptor);
      create.push(ms(t));
      t = performance.now();
      await device.createRenderPipelineAsync(descriptor);
      createAgain.push(ms(t));
      ok++;
    } catch (e) {
      pipelineFailures.push({location: pipeline.location, error: String(e.message || e).slice(0, 600)});
    }
  }
  R.pipelines = {count: corpus.pipelines.length, ok, failed: pipelineFailures, create, createAgain};
  R.lost = lost;
  R.uncaptured = uncaptured.slice(0, 20);
  return R;
})();
</script>`;

async function stageChrome() {
  const files = new Map([
    ["/index.html", () => ({type: "text/html", body: page})],
    ["/shaderxlat-job.js", () => ({type: "text/javascript", body: readFileSync(join(here, "shaderxlat-job.js"))})],
    ["/shaderxlat.wasm", () => ({type: "application/wasm", body: readFileSync(wasmFile)})],
    ["/corpus.json", () => ({type: "application/json", body: readFileSync(join(corpusDir, "corpus.json"))})],
    ["/jobs.json", () => ({type: "application/json", body: JSON.stringify({jobs: [...jobs.values()], iterations, deviceFeatures,
      fallbackAdapter: options["fallback-adapter"] === "true", translateOptions})})],
  ]);
  const server = createServer((request, response) => {
    const path = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    let entry = files.get(path) && files.get(path)();
    if (!entry && /^\/spirv\/[0-9a-f]{64}\.spv$/.test(path)) {
      entry = {type: "application/octet-stream", body: readFileSync(join(corpusDir, path.slice(1)))};
    } else if (!entry && /^\/native\/[0-9A-Za-z-]+\.wgsl$/.test(path)) {
      entry = {type: "text/plain", body: readFileSync(join(nativeDir, "wgsl", path.slice("/native/".length)))};
    }
    if (!entry) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, {"content-type": entry.type, "cache-control": "no-store"}).end(entry.body);
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const httpPort = server.address().port;
  const keepProfile = Boolean(options["profile-dir"]);
  const profile = keepProfile ? resolve(options["profile-dir"]) : mkdtempSync(join(tmpdir(), "gaius-xlat-chrome-"));
  const debugPort = 9400 + Math.floor(Math.random() * 500);
  const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${debugPort}`, "--remote-allow-origins=*",
    `--user-data-dir=${profile}`, "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
    "--disable-component-update", "--ignore-gpu-blocklist",
    // No external network: everything but loopback goes to a closed local port.
    "--proxy-server=http://127.0.0.1:9", "--proxy-bypass-list=127.0.0.1;localhost",
    ...(options["chrome-args"] || "").split(" ").filter(Boolean), "about:blank"], {stdio: "ignore"});
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  try {
    let targets = [];
    for (let i = 0; i < 100 && !targets.some((t) => t.type === "page"); i++) {
      try {
        targets = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json();
      } catch {
        // Chrome is still starting.
      }
      await sleep(200);
    }
    const target = targets.find((t) => t.type === "page");
    if (!target) throw new Error("headless Chrome did not open a page");
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((r, j) => {
      ws.addEventListener("open", r);
      ws.addEventListener("error", j);
    });
    let id = 0;
    const pending = new Map();
    ws.addEventListener("message", (event) => {
      const message = JSON.parse(event.data);
      if (message.id && pending.has(message.id)) {
        pending.get(message.id)(message);
        pending.delete(message.id);
      }
    });
    const send = (method, params = {}) => new Promise((r) => {
      const i = ++id;
      pending.set(i, r);
      ws.send(JSON.stringify({id: i, method, params}));
    });
    await send("Page.enable");
    await send("Page.navigate", {url: `http://127.0.0.1:${httpPort}/index.html`});
    let value;
    for (let i = 0; i < 50 && value === undefined; i++) {
      await sleep(200);
      const probe = await send("Runtime.evaluate", {expression: "typeof window.__check"});
      if (probe.result?.result?.value === "object") value = null;
    }
    const evaluated = await send("Runtime.evaluate", {expression: "window.__check.then((r) => JSON.stringify(r))",
      awaitPromise: true, timeout: 600000});
    const text = evaluated.result?.result?.value;
    if (typeof text !== "string") throw new Error(`page did not report: ${JSON.stringify(evaluated).slice(0, 2000)}`);
    const result = JSON.parse(text);
    ws.close();
    if (result.error) fail(`chrome: ${result.error}`);
    const t = result.translate;
    for (const f of t.failed) fail(`chrome translate ${f.key}: ${f.error}`);
    if (t.identical !== jobs.size) fail(`chrome: ${t.identical}/${jobs.size} translations identical to native`);
    for (const f of result.shaders?.failed || []) fail(`tint ${f.key}: ${f.error || ""} ${f.messages.join(" | ")}`);
    for (const f of result.pipelines?.failed || []) fail(`pipeline ${f.location}: ${f.error}`);
    for (const m of result.uncaptured || []) fail(`uncaptured WebGPU error: ${m}`);
    for (const m of result.lost || []) fail(`device lost: ${m}`);
    report.chrome = {
      adapter: result.adapter, deviceFeatures: result.deviceFeatures,
      wasmCompileMs: result.wasmCompileMs, wasmInstantiateMs: result.wasmInstantiateMs,
      translate: {ok: t.ok, identical: t.identical, firstMs: stats(t.first), warmMs: stats(t.warm)},
      shaders: result.shaders && {modules: result.shaders.modules, ok: result.shaders.ok, warnings: result.shaders.warnings,
        failed: result.shaders.failed, compileMs: stats(result.shaders.compile)},
      pipelines: result.pipelines && {count: result.pipelines.count, ok: result.pipelines.ok, failed: result.pipelines.failed,
        createMs: stats(result.pipelines.create), createAgainMs: stats(result.pipelines.createAgain)},
    };
    console.log(`chrome: translate ${t.ok}/${jobs.size} (identical ${t.identical}), tint ${result.shaders?.ok}/${result.shaders?.modules}` +
      ` (${result.shaders?.warnings} warnings), pipelines ${result.pipelines?.ok}/${result.pipelines?.count}`);
  } finally {
    // Close the browser through CDP so every child process exits and releases the profile.
    const exited = new Promise((r) => (chrome.exitCode !== null ? r() : chrome.once("exit", r)));
    try {
      const version = await (await fetch(`http://127.0.0.1:${debugPort}/json/version`)).json();
      const browser = new WebSocket(version.webSocketDebuggerUrl);
      await new Promise((r, j) => {
        browser.addEventListener("open", r);
        browser.addEventListener("error", j);
      });
      browser.send(JSON.stringify({id: 1, method: "Browser.close"}));
    } catch {
      chrome.kill();
    }
    if (await Promise.race([exited.then(() => true), sleep(10000).then(() => false)]) === false) {
      chrome.kill();
      await Promise.race([exited, sleep(5000)]);
    }
    server.close();
    for (let attempt = 0; !keepProfile && attempt < 20; attempt++) {
      try {
        rmSync(profile, {recursive: true, force: true});
        break;
      } catch (error) {
        if (attempt === 19) console.warn(`xlat-check: could not remove ${profile}: ${error.message}`);
        else await sleep(500);
      }
    }
  }
}

if (stages.has("link")) await stageLink();
if (stages.has("wasm")) await stageWasm();
if (stages.has("chrome")) await stageChrome();
report.failures = failures;
if (options.report) writeFileSync(resolve(options.report), JSON.stringify(report, null, 1) + "\n");
console.log(failures.length ? `xlat-check: ${failures.length} failures` : "xlat-check: pass");
process.exit(failures.length ? 1 : 0);
