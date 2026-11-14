// Opt-in browser shader-pack prototype for WebGPU.
//
// This is deliberately a small compatibility slice rather than a replacement for the
// Renderpearl terrain pipeline.  It loads a first-party pack, preprocesses its GLSL, sends the
// stages through the existing shaderc and SPIR-V -> WGSL translator, and records three fullscreen
// passes on the game's open command encoder.  The URL switch is intentionally fail-closed:
// without ?gaiusShaders=1 this module allocates no GPU resources and does no compilation.
(function installGaiusShaderPack(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.shaderPack) return;
  const Compat = root.GaiusShaderPackCompat;

  const PACK = Object.freeze({
    "shaders.properties": [
      "shaderPack=Gaius Compatibility Pack",
      "texture.composite.colortex0=minecraft:main",
      "uniform.frameTimeCounter=0"
    ].join("\n") + "\n",
    "block.properties": "# First-party prototype: the ID table is intentionally small.\nmatchBlocks=minecraft:stone minecraft:grass_block\n",
    "item.properties": "matchItems=minecraft:stone\n",
    "entity.properties": "entity.0=minecraft:zombie\n",
    "shaders/common.glsl": [
      "vec4 gaiusPackColor(sampler2D source, vec2 uv) {",
      "  return texture(source, uv);",
      "}"
    ].join("\n") + "\n",
    "shaders/gbuffers_terrain.vsh": [
      "#version 450",
      "layout(location = 0) out vec2 texCoord;",
      "void main() {",
      "  vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);",
      "  texCoord = p * 0.5;",
      "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);",
      "}"
    ].join("\n") + "\n",
    "shaders/gbuffers_terrain.fsh": [
      "#version 450",
      "#include \"common.glsl\"",
      "layout(location = 0) in vec2 texCoord;",
      "layout(location = 0) out vec4 fragColor;",
      "layout(binding = 0) uniform sampler2D colortex0;",
      "layout(binding = 1) uniform PackFrame { vec4 frameTimeCounter; };",
      "void main() {",
      "  if (frameTimeCounter.x < -1.0) { fragColor = vec4(0.0); return; }",
      "  fragColor = gaiusPackColor(colortex0, texCoord);",
      "}"
    ].join("\n") + "\n",
    "shaders/composite.vsh": [
      "#version 450",
      "layout(location = 0) out vec2 texCoord;",
      "void main() {",
      "  vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);",
      "  texCoord = p * 0.5;",
      "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);",
      "}"
    ].join("\n") + "\n",
    "shaders/composite.fsh": [
      "#version 450",
      "#include \"common.glsl\"",
      "layout(location = 0) in vec2 texCoord;",
      "layout(location = 0) out vec4 fragColor;",
      "layout(binding = 0) uniform sampler2D colortex0;",
      "layout(binding = 1) uniform PackFrame { vec4 frameTimeCounter; };",
      "void main() {",
      "  if (frameTimeCounter.x < -1.0) { fragColor = vec4(0.0); return; }",
      "  vec4 c = gaiusPackColor(colortex0, texCoord);",
      "  fragColor = vec4(c.rgb, c.a);",
      "}"
    ].join("\n") + "\n",
    "shaders/final.vsh": [
      "#version 450",
      "layout(location = 0) out vec2 texCoord;",
      "void main() {",
      "  vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);",
      "  texCoord = p * 0.5;",
      "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);",
      "}"
    ].join("\n") + "\n",
    "shaders/final.fsh": [
      "#version 450",
      "#include \"common.glsl\"",
      "layout(location = 0) in vec2 texCoord;",
      "layout(location = 0) out vec4 fragColor;",
      "layout(binding = 0) uniform sampler2D colortex0;",
      "layout(binding = 1) uniform PackFrame { vec4 frameTimeCounter; };",
      "void main() {",
      "  if (frameTimeCounter.x < -1.0) { fragColor = vec4(0.0); return; }",
      "  fragColor = gaiusPackColor(colortex0, texCoord);",
      "}"
    ].join("\n") + "\n"
  });

  const REQUIRED = [
    "shaders/gbuffers_terrain.vsh", "shaders/gbuffers_terrain.fsh",
    "shaders/composite.vsh", "shaders/composite.fsh",
    "shaders/final.vsh", "shaders/final.fsh"
  ];
  const UNSUPPORTED = [
    {re: /#version\s+.*\b(460|4[6-9]\d|[5-9]\d\d)\b/i, reason: "GLSL version is newer than the supported browser profile"},
    {re: /\b(layout\s*\([^)]*\b(point|line)\b|\bgl_in\b|\bEmitVertex\b|\bEndPrimitive\b)/i, reason: "geometry shader features are unavailable on WebGPU/WebGL2"},
    {re: /\b(local_size|gl_GlobalInvocationID|imageStore|imageLoad)\b/i, reason: "compute/image features are not available in the WebGL2 fallback"},
    {re: /#extension\s+GL_(?:geometry|compute|tessellation)/i, reason: "requested GLSL extension has no portable fallback"},
    {re: /sampler(?:2D|Cube|3D)\s*\[[^\]]+\]/i, reason: "sampler arrays are not supported by the prototype binding mapper"}
  ];

  const stats = {
    enabled: false, initialized: false, runs: 0, passes: 0, compiles: 0, translations: 0,
    compileMs: 0, translateMs: 0, lastStages: [], source: "builtin", backend: "webgpu",
    fallback: null, lastError: null, packFiles: Object.keys(PACK).length, resources: 0,
    uniform: {frameTimeCounter: 0}, routes: {}
  };
  root.__gaiusShaderPackStats = stats;
  let state = null;

  function now() { return root.performance && root.performance.now ? root.performance.now() : Date.now(); }

  function enabled() {
    try { return new URLSearchParams(root.location ? root.location.search : "").get("gaiusShaders") === "1" ||
      !!(root.__gaiusShaderPacks && root.__gaiusShaderPacks.selected && root.__gaiusShaderPacks.selected !== "OFF"); }
    catch (_) { return false; }
  }

  function fail(reason, error) {
    stats.fallback = String(reason);
    stats.lastError = error ? String(error && (error.message || error)) : String(reason);
    if (root.console && root.console.warn) root.console.warn("Gaius shader pack fallback: " + stats.lastError);
    try { if (root.__gaiusShaderPacks && root.__gaiusShaderPacks.toast) root.__gaiusShaderPacks.toast("Shader pack fallback: " + stats.lastError); } catch (_) {}
    return false;
  }

  function properties(text) {
    const out = {};
    String(text || "").split(/\r?\n/).forEach((line) => {
      const value = line.replace(/#.*/, "").trim();
      if (!value) return;
      const at = value.indexOf("=");
      if (at > 0) out[value.slice(0, at).trim()] = value.slice(at + 1).trim();
    });
    return out;
  }

  function preprocess(name, files, options, stack) {
    const seen = stack || [];
    if (seen.indexOf(name) >= 0) throw new Error("shader include cycle: " + seen.concat(name).join(" -> "));
    let source = files[name];
    if (source === undefined) throw new Error("shader pack file is missing: " + name);
    source = String(source).replace(/^\uFEFF/, "");
    let version = "#version 450";
    source = source.replace(/^\s*#version\s+[^\r\n]+/m, (line) => {
      const match = /#version\s+(\d+)/i.exec(line);
      const number = match ? Number(match[1]) : 450;
      if (number < 450) return version;
      version = line.trim();
      return version;
    });
    for (const item of UNSUPPORTED) if (item.re.test(source)) throw new Error(item.reason);
    source = source.replace(/^\s*#include\s+["<]([^">]+)[">]\s*$/gm, (_, include) => {
      const clean = include.replace(/^\/+/, "");
      const path = clean.indexOf("shaders/") === 0 ? clean : "shaders/" + clean;
      // An included file is a fragment of the current translation unit; its own normalized
      // version line must not become a second #version directive in the parent.
      return preprocess(path, files, options, seen.concat(name))
        .replace(/^\s*#version\s+[^\r\n]+\s*/m, "");
    });
    const defines = Object.keys(options || {}).map((key) => "#define " + key + " " + options[key]);
    return version + "\n" + defines.join("\n") + (defines.length ? "\n" : "")
      + source.replace(/^\s*#version\s+[^\r\n]+\s*/m, "");
  }

  function bytesOf(view) {
    return view instanceof Uint8Array ? view : new Uint8Array(view.buffer || view, view.byteOffset || 0, view.byteLength);
  }

  function compile(source, name, fragment) {
    const toolchain = root.__gaiusShaderToolchain;
    if (!toolchain || typeof toolchain.shadercBegin !== "function") throw new Error("shader toolchain is not ready");
    const encoder = new TextEncoder();
    const id = toolchain.shadercBegin();
    const started = now();
    try {
      toolchain.shadercOption(id, 1, 0, 4202496);
      toolchain.shadercOption(id, 2, 1, 0);
      toolchain.shadercOption(id, 4, 0, 0);
      toolchain.shadercOption(id, 5, 0, 0);
      const status = toolchain.shadercCompile(id, encoder.encode(source), fragment ? 1 : 0,
        encoder.encode(name), encoder.encode("main"));
      if (status !== 0) throw new Error(toolchain.shadercErrorMessage(id) || ("shaderc status " + status));
      const result = new Uint8Array(toolchain.shadercOutputLength(id));
      toolchain.shadercOutputCopy(id, result);
      stats.compiles++;
      stats.compileMs += now() - started;
      return result;
    } finally {
      toolchain.shadercEnd(id);
    }
  }

  function translate(bytes, stage) {
    const gpu = root.GaiusWebGpu;
    if (!gpu || !gpu.translator || typeof gpu.translator.translate !== "function") {
      throw new Error("gaius-shaderxlat translator is not ready");
    }
    const started = now();
    const result = gpu.translator.translate(bytesOf(bytes), [], {flipVertexY: true, layout: "split", glDepthRemap: false});
    stats.translations++;
    stats.translateMs += now() - started;
    if (!result || !result.wgsl || result.stage !== stage) throw new Error("shader translation returned an unexpected stage");
    return result;
  }

  function programNames(files, stage) {
    if (Compat && Compat.program) {
      const result = Compat.program(files, stage);
      if (result) return {vertex: result.vertex, fragment: result.fragment, stage: stage};
    }
    const candidates = ["shaders/" + stage, "shaders/world0/" + stage, "shaders/program/" +
      (stage === "gbuffers_terrain" ? "basic" : stage)];
    for (const base of candidates) if (files[base + ".vsh"] !== undefined && files[base + ".fsh"] !== undefined) {
      return {vertex: base + ".vsh", fragment: base + ".fsh", stage: stage};
    }
    return null;
  }

  function colortexFormat(files, index, fallback) {
    const props = properties(files["shaders.properties"]);
    const declared = props["colortex" + index + "Format"];
    if (!declared && index === 0) return fallback;
    const key = String(declared || "RGBA8").toUpperCase();
    const formats = {RGBA8: "rgba8unorm", RGBA16F: "rgba16float", RGBA32F: "rgba32float",
      RG16F: "rg16float", R16F: "r16float", RG32F: "rg32float", R32F: "r32float", R8: "r8unorm"};
    if (!formats[key]) throw new Error("unsupported colortex format: " + key);
    return formats[key] || fallback;
  }

  function buildPipeline(device, stage, files) {
    const names = programNames(files, stage);
    if (!names) throw new Error("shader pack program is missing: " + stage);
    const vertexName = names.vertex;
    const fragmentName = names.fragment;
    const options = {GAIUS_SHADER_PACK: 1};
    const vertexSource = preprocess(vertexName, files, options);
    const parsed = Compat ? Compat.parseDirectives(files[fragmentName]) : {targets: [0], source: files[fragmentName]};
    const fragmentSource = preprocess(fragmentName, Object.assign({}, files, {[fragmentName]: parsed.source}), options);
    const vertex = translate(compile(vertexSource, vertexName, false), "vertex");
    const fragment = translate(compile(fragmentSource, fragmentName, true), "fragment");
    const vs = device.createShaderModule({label: "gaius shader pack " + stage + " vertex", code: vertex.wgsl});
    const fs = device.createShaderModule({label: "gaius shader pack " + stage + " fragment", code: fragment.wgsl});
    const pipeline = device.createRenderPipeline({
      label: "gaius shader pack " + stage,
      layout: "auto",
      vertex: {module: vs, entryPoint: "main"},
      fragment: {module: fs, entryPoint: "main", targets: parsed.targets.map((index) =>
        ({format: index === 0 && stage === "final" ? files.__format : colortexFormat(files, index, files.__format)}))},
      primitive: {topology: "triangle-list"}
    });
    const textureNames = [];
    const samplerPattern = /\buniform\s+sampler\w+\s+(\w+)/g;
    let samplerMatch;
    while ((samplerMatch = samplerPattern.exec(fragmentSource))) textureNames.push(samplerMatch[1]);
    return {pipeline, stage, targets: parsed.targets, targetFormats: parsed.targets.map((index) =>
      index === 0 && stage === "final" ? files.__format : colortexFormat(files, index, files.__format)),
      mipmapped: parsed.mipmapped || [], textureNames,
      bindings: (vertex.bindings || []).concat(fragment.bindings || [])};
  }

  function makeTarget(device, old, width, height, format, label) {
    if (old && old.width === width && old.height === height && old.format === format) return old;
    if (old && old.texture && old.texture.destroy) old.texture.destroy();
    const texture = device.createTexture({label: label, size: {width, height}, format: format,
      usage: 0x14}); // TEXTURE_BINDING | RENDER_ATTACHMENT
    stats.resources++;
    return {texture, view: texture.createView(), width, height, format};
  }

  function encodePass(res, encoder, source, dest, stage, frameCounter, frame) {
    const device = root.GaiusWebGpu.device;
    const values = Object.assign({}, frame && frame.uniforms || {}, {
      frameTimeCounter: frameCounter, worldTime: frame && frame.worldTime || 0,
      viewWidth: frame && frame.viewWidth || dest.width, viewHeight: frame && frame.viewHeight || dest.height,
      aspectRatio: dest.width / Math.max(1, dest.height)
    });
    const layout = Compat ? Compat.uniformLayout(values) : {data: new Float32Array([frameCounter, 0, dest.width, dest.height]), byteLength: 16};
    const uniform = layout.data;
    device.queue.writeBuffer(res.uniforms, 0, uniform.buffer, 0, uniform.byteLength);
    const groups = {};
    for (const binding of (stage.bindings || [])) {
      if (!binding || binding.used === false) continue;
      const g = binding.group || 0;
      const list = groups[g] || (groups[g] = []);
      let resource = null;
      if (binding.type === "uniform") resource = {buffer: res.uniforms, offset: 0, size: Math.min(256, Math.max(16, binding.size || 16))};
      else if (binding.type === "texture") {
        const name = stage.textureNames && stage.textureNames[binding.uniform];
        const match = /^colortex([0-7])$/.exec(name || "");
        const index = match ? Number(match[1]) : -1;
        if (index === 0) resource = source.view;
        else if (index > 0 && res.mrt && res.mrt[index]) resource = res.mrt[index].view;
        else if (/^depthtex[01]$/.test(name || "") && frame && frame.depth && frame.depth.createView) resource = frame.depth.createView();
        else resource = source.view;
      }
      else if (binding.type === "sampler") resource = res.sampler;
      if (resource) list.push({binding: binding.binding, resource: resource});
    }
    const bindGroups = Object.keys(groups).map((key) => device.createBindGroup({
      layout: stage.pipeline.getBindGroupLayout(Number(key)), entries: groups[key]
    }));
    const targetIndices = stage.targets && stage.targets.length ? stage.targets : [0];
    const attachments = targetIndices.map((index, i) => ({view: i === 0 ? (dest.view || dest) :
      (res.mrt && res.mrt[index] ? res.mrt[index].view : (dest.view || dest)),
      loadOp: "clear", storeOp: "store", clearValue: {r: 0, g: 0, b: 0, a: 1}}));
    const pass = encoder.beginRenderPass({colorAttachments: attachments});
    pass.setPipeline(stage.pipeline);
    for (let i = 0; i < bindGroups.length; i++) pass.setBindGroup(i, bindGroups[i]);
    pass.setScissorRect(0, 0, frame && frame.viewWidth || dest.width || 1, frame && frame.viewHeight || dest.height || 1);
    pass.draw(3, 1, 0, 0);
    pass.end();
    stats.passes++;
  }

  function run(frame) {
    stats.enabled = enabled();
    if (!stats.enabled) return false;
    const gpu = root.GaiusWebGpu;
    if (!gpu || gpu.active !== true || !gpu.device || typeof gpu.externalEncoder !== "function") return fail("WebGPU is not active");
    if (!frame || !frame.color || !frame.width || !frame.height) return fail("world color target is unavailable");
    const colorFormat = typeof gpu.debugTextureFormat === "function"
      ? gpu.debugTextureFormat(frame.color) : (frame.color.format || "");
    if (!colorFormat) return fail("world color format is unavailable");
    const encoder = gpu.externalEncoder();
    if (!encoder) return fail("the game's command encoder is unavailable");
    try {
      if (Compat && Compat.route) {
        const types = Array.isArray(frame.renderTypes) ? frame.renderTypes : [frame.renderType || "terrain_solid"];
        for (const type of types) {
          const route = Compat.route(type);
          stats.routes[route] = (stats.routes[route] || 0) + 1;
        }
      }
      if (!state || state.device !== gpu.device || state.format !== colorFormat
          || state.width !== frame.width || state.height !== frame.height) {
        let files = Object.assign({}, PACK);
        files.__format = colorFormat;
        const store = root.__gaiusShaderPacks;
        const selected = store && store.selected && store.selected !== "OFF" ? store.selected : null;
        if (state && state.pendingFiles) {
          files = Object.assign({}, state.pendingFiles);
          files.__format = colorFormat;
        } else if (selected && typeof store.getSelectedFiles === "function") {
          if (state && state.loadingFor === selected) return false;
          state = {loadingFor: selected};
          store.getSelectedFiles().then((loaded) => {
            if (loaded) { state = {pendingFiles: loaded, loadingFor: null}; }
          }).catch((error) => { fail("shader pack import failed", error); state = null; });
          return false;
        }
        const props = properties(files["shaders.properties"]);
        if (!props.shaderPack) throw new Error("shader pack metadata is missing");
        const chain = ["gbuffers_terrain"];
        if (programNames(files, "deferred")) chain.push("deferred");
        for (let pass = 0; pass < 8; pass++) {
          const name = pass === 0 ? "composite" : "composite" + pass;
          if (programNames(files, name)) chain.push(name);
          else if (pass > 0) break;
        }
        chain.push("final");
        const stages = chain.map((name) => buildPipeline(gpu.device, name, files));
        state = {device: gpu.device, format: frame.color.format, width: frame.width, height: frame.height, files,
          stages, targetA: null, targetB: null,
          sampler: gpu.device.createSampler({minFilter: "nearest", magFilter: "nearest", addressModeU: "clamp-to-edge", addressModeV: "clamp-to-edge"}),
          uniforms: gpu.device.createBuffer({label: "gaius shader pack uniforms", size: 2048, usage: 0x48})};
        stats.initialized = true;
        stats.lastStages = stages.map((x) => x.stage);
      }
      const workingFormat = colortexFormat(state.files || PACK, 0, colorFormat);
      state.targetA = makeTarget(gpu.device, state.targetA, frame.width, frame.height, workingFormat, "gaius shader pack ping");
      state.targetB = makeTarget(gpu.device, state.targetB, frame.width, frame.height, workingFormat, "gaius shader pack pong");
      const source = {view: frame.color.createView()};
      state.mrt = state.mrt || {};
      for (let i = 1; i < 8; i++) state.mrt[i] = makeTarget(gpu.device, state.mrt[i], frame.width, frame.height,
        colortexFormat(state.files || PACK, i, colorFormat), "gaius colortex" + i);
      let current = source;
      let output = state.targetA;
      for (let i = 0; i < state.stages.length; i++) {
        const stage = state.stages[i];
        const last = i === state.stages.length - 1;
        const dest = last ? frame.color : output;
        encodePass(state, encoder, current, dest, stage, ++stats.uniform.frameTimeCounter, frame);
        if (!last) {
          current = {view: dest.view};
          output = output === state.targetA ? state.targetB : state.targetA;
        }
      }
      stats.runs++;
      return true;
    } catch (error) {
      return fail("shader pack pass failed", error);
    }
  }

  function dispose() {
    if (!state) return false;
    for (const target of [state.targetA, state.targetB].concat(Object.keys(state.mrt || {}).map((key) => state.mrt[key])))
      if (target && target.texture && target.texture.destroy) target.texture.destroy();
    if (state.uniforms && state.uniforms.destroy) state.uniforms.destroy();
    state = null;
    stats.initialized = false;
    return true;
  }

  Q.shaderPack = Object.freeze({run, dispose, enabled, stats, pack: PACK, required: REQUIRED});
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
