// Gaius shader translation job codec: frames run_shader_xlat and run_shader_link jobs for the
// gaius-shaderxlat wasm module (port/native/crates/gaius-shaderxlat/wasm) and reads their results.
// The layout mirrors port/native/crates/gaius-kernel-abi (job/result headers) and the wasm crate's
// payloads; all integers are little-endian.
//
// A plain script (installs globalThis.GaiusShaderXlatJob) so it loads from a <script> tag, a
// worker or a Node import alike:
//
//   const xlat = GaiusShaderXlatJob.instantiateSync(module);   // main thread, finishCompile
//   const {stage, bindings, wgsl} = xlat.translate(spirvBytes, [[uniformIndex, "R8_SINT"]],
//       {flipVertexY: true, layout: "split", glDepthRemap: false});
//   // blaze3d (26.2, 1.21.11): reflect and rebind the two shaderc modules first.
//   const {entries, fixes, vertex, fragment} = xlat.link(vertexSpirv, fragmentSpirv,
//       {uniforms: [{name, texelFormat}], samplers: [name], attributes: [{name, format}]}, {lenient});
//
// translate() and link() throw GaiusShaderXlatJob.TranslateError (with .status) when the module
// refuses the input; the message names the failing step (link, spirv patch, naga spv-in,
// validation, wgsl-out).
(function (global) {
  "use strict";

  var KIND = 0x0501;
  var LINK_KIND = 0x0502;
  var ABI_VERSION = 1;
  var JOB_MAGIC = 0x424a4b47; // "GKJB"
  var RESULT_MAGIC = 0x53524b47; // "GKRS"
  var HEADER = 16;
  var FORMATS = {R8_SINT: 0, R8_UINT: 1, R16_SINT: 2, R16_UINT: 3, R32_SINT: 4, R32_UINT: 5, R32_FLOAT: 6};
  var DIMS = ["1d", "2d", "2d-array", "3d", "cube", "cube-array"];
  var SAMPLE_KINDS = ["float", "sint", "uint", "depth"];
  var KINDS = ["uniform", "storage", "texture", "sampler"];
  var ENTRY_KINDS = ["UNIFORM_BUFFER", "TEXEL_BUFFER", "COMBINED_IMAGE_SAMPLER"];
  var FIX_KINDS = ["undeclared-uniform-buffer", "undeclared-sampler", "drop-unused-fragment-input",
    "attribute-base-type"];
  var BASE_TYPES = ["float", "sint", "uint"];

  function TranslateError(status, message) {
    var error = new Error(message);
    error.name = "TranslateError";
    error.status = status;
    return error;
  }

  // Job flags of run_shader_xlat. options.flipVertexY negates the vertex position's y, options.layout
  // "flat" selects the S4 single-group binding layout (default "split", the v0.4.0 layout),
  // options.glDepthRemap maps GL's depth range at the vertex output (see the crate README).
  function flags(options) {
    var o = options || {};
    if (o.layout && o.layout !== "split" && o.layout !== "flat") throw new TypeError("unknown layout " + o.layout);
    return (o.flipVertexY ? 1 : 0) | (o.layout === "flat" ? 2 : 0) | (o.glDepthRemap ? 4 : 0);
  }

  // texelBuffers: [[uniform index, renderpearl GpuFormat name], ...].
  function encode(spirv, texelBuffers, jobId, options) {
    var buffers = texelBuffers || [];
    var payloadLen = 8 + buffers.length * 8 + spirv.byteLength;
    var bytes = new Uint8Array(HEADER + payloadLen);
    var view = new DataView(bytes.buffer);
    view.setUint32(0, JOB_MAGIC, true);
    view.setUint16(4, ABI_VERSION, true);
    view.setUint16(6, KIND, true);
    view.setUint32(8, jobId >>> 0, true);
    view.setUint32(12, payloadLen, true);
    view.setUint32(16, flags(options), true);
    view.setUint32(20, buffers.length, true);
    for (var i = 0; i < buffers.length; i++) {
      var code = FORMATS[buffers[i][1]];
      if (code === undefined) throw new TypeError("unsupported texel buffer format " + buffers[i][1]);
      view.setUint32(24 + i * 8, buffers[i][0] >>> 0, true);
      view.setUint32(28 + i * 8, code, true);
    }
    bytes.set(new Uint8Array(spirv.buffer || spirv, spirv.byteOffset || 0, spirv.byteLength), 24 + buffers.length * 8);
    return bytes;
  }

  // data: the descriptor's data bytes of a successful run (result header + payload).
  function decode(data, jobId) {
    var view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    checkHeader(view, jobId);
    var at = HEADER;
    var stage = view.getUint32(at, true) === 1 ? "fragment" : "vertex";
    var count = view.getUint32(at + 4, true);
    at += 8;
    var bindings = [];
    for (var i = 0; i < count; i++, at += 32) {
      var w = function (k) { return view.getUint32(at + k * 4, true); };
      var kind = KINDS[w(3)];
      var binding = {group: w(0), binding: w(1), uniform: w(2), type: kind, used: w(7) === 1};
      if (kind === "uniform") binding.size = w(4);
      if (kind === "texture") {
        binding.dim = DIMS[w(4)];
        binding.sampleKind = SAMPLE_KINDS[w(5)];
        binding.multisampled = (w(6) & 1) !== 0;
        binding.sampled = (w(6) & 2) !== 0;
      }
      if (kind === "sampler") binding.comparison = w(4) === 1;
      bindings.push(binding);
    }
    var length = view.getUint32(at, true);
    var wgsl = new TextDecoder().decode(data.subarray(at + 4, at + 4 + length));
    return {stage: stage, bindings: bindings, wgsl: wgsl};
  }

  function utf8(text) {
    return new TextEncoder().encode(String(text));
  }

  function bytesOf(view) {
    return view instanceof Uint8Array ? view
      : new Uint8Array(view.buffer || view, view.byteOffset || 0, view.byteLength);
  }

  // layout: {uniforms: [{name, texelFormat}] (texelFormat: the texel buffer's GpuFormat, absent for
  // a uniform buffer), samplers: [name], attributes: [{name, format}] (the vertex GpuFormat, or
  // base: "float" | "sint" | "uint")}; options.lenient selects the 1.21.11 rules.
  function encodeLink(vertex, fragment, layout, jobId, options) {
    var parts = [];
    var u32 = function (value) {
      var word = new Uint8Array(4);
      new DataView(word.buffer).setUint32(0, value >>> 0, true);
      parts.push(word);
    };
    var str = function (text) {
      var bytes = utf8(text || "");
      u32(bytes.length);
      parts.push(bytes);
    };
    var blob = function (view) {
      var bytes = bytesOf(view);
      u32(bytes.length);
      parts.push(bytes);
    };
    var base = function (attribute) {
      if (attribute.base) {
        var code = BASE_TYPES.indexOf(attribute.base);
        if (code < 0) throw new TypeError("unknown attribute base type " + attribute.base);
        return code;
      }
      var format = String(attribute.format || "");
      return /_SINT$/.test(format) ? 1 : /_UINT$/.test(format) ? 2 : 0;
    };
    var uniforms = layout.uniforms || [], samplers = layout.samplers || [], attributes = layout.attributes || [];
    u32(options && options.lenient ? 1 : 0);
    u32(uniforms.length);
    uniforms.forEach(function (u) { str(u.name); str(u.texelFormat || ""); });
    u32(samplers.length);
    samplers.forEach(function (name) { str(name); });
    u32(attributes.length);
    attributes.forEach(function (a) { str(a.name); u32(base(a)); });
    blob(vertex);
    blob(fragment);
    var payloadLen = parts.reduce(function (n, p) { return n + p.length; }, 0);
    var bytes = new Uint8Array(HEADER + payloadLen);
    var view = new DataView(bytes.buffer);
    view.setUint32(0, JOB_MAGIC, true);
    view.setUint16(4, ABI_VERSION, true);
    view.setUint16(6, LINK_KIND, true);
    view.setUint32(8, jobId >>> 0, true);
    view.setUint32(12, payloadLen, true);
    var at = HEADER;
    parts.forEach(function (p) { bytes.set(p, at); at += p.length; });
    return bytes;
  }

  function checkHeader(view, jobId) {
    if (view.getUint32(0, true) !== RESULT_MAGIC || view.getUint16(4, true) !== ABI_VERSION) {
      throw TranslateError(-1, "bad shaderxlat result header");
    }
    if (jobId !== undefined && view.getUint32(8, true) !== (jobId >>> 0)) {
      throw TranslateError(-1, "shaderxlat result for another job");
    }
  }

  // Returns {entries: [{name, type, gpuFormat, declared}] (type as in the corpus: UNIFORM_BUFFER,
  // TEXEL_BUFFER, COMBINED_IMAGE_SAMPLER; index = vanilla uniform index), fixes: [{kind, name,
  // base?}], vertex, fragment (Uint8Array SPIR-V)}.
  function decodeLink(data, jobId) {
    var view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    checkHeader(view, jobId);
    var at = HEADER;
    var u32 = function () { var v = view.getUint32(at, true); at += 4; return v; };
    var take = function () { var n = u32(); var b = data.slice(at, at + n); at += n; return b; };
    var str = function () { return new TextDecoder().decode(take()); };
    var entries = [];
    for (var i = 0, n = u32(); i < n; i++) {
      var kind = ENTRY_KINDS[u32()];
      var declared = u32() === 1;
      var name = str();
      var format = str();
      entries.push({name: name, type: kind, gpuFormat: format || null, declared: declared});
    }
    var fixes = [];
    for (var j = 0, m = u32(); j < m; j++) {
      var fixKind = FIX_KINDS[u32()];
      var fixBase = BASE_TYPES[u32()];
      var fix = {kind: fixKind, name: str()};
      if (fixKind === "attribute-base-type") fix.base = fixBase;
      fixes.push(fix);
    }
    return {entries: entries, fixes: fixes, vertex: take(), fragment: take()};
  }

  // Runs jobs on one instance in the calling thread.
  function fromInstance(instance) {
    var ex = instance.exports;
    var nextId = 1;
    function call(exportName, job, jobId, decoder) {
      var ptr = ex.alloc(job.length);
      if (!ptr) throw TranslateError(6, "shaderxlat: out of memory");
      new Uint8Array(ex.memory.buffer, ptr, job.length).set(job);
      var desc = ex[exportName](ptr, job.length);
      ex.dealloc(ptr, job.length);
      if (!desc) throw TranslateError(6, "shaderxlat: out of memory");
      try {
        var head = new DataView(ex.memory.buffer, desc, 16);
        var status = head.getUint32(0, true);
        var data = new Uint8Array(ex.memory.buffer, head.getUint32(4, true), head.getUint32(8, true));
        if (status !== 0) throw TranslateError(status, new TextDecoder().decode(data));
        return decoder(data.slice(), jobId);
      } finally {
        ex.release(desc);
      }
    }
    return {
      translate: function (spirv, texelBuffers, options) {
        var jobId = nextId++;
        return call("run_shader_xlat", encode(spirv, texelBuffers, jobId, options), jobId, decode);
      },
      link: function (vertex, fragment, layout, options) {
        var jobId = nextId++;
        return call("run_shader_link", encodeLink(vertex, fragment, layout, jobId, options), jobId, decodeLink);
      }
    };
  }

  function instantiateSync(module) {
    return fromInstance(new WebAssembly.Instance(module, {}));
  }

  global.GaiusShaderXlatJob = {
    KIND: KIND,
    LINK_KIND: LINK_KIND,
    FORMATS: FORMATS,
    TranslateError: TranslateError,
    flags: flags,
    encode: encode,
    decode: decode,
    encodeLink: encodeLink,
    decodeLink: decodeLink,
    fromInstance: fromInstance,
    instantiateSync: instantiateSync
  };
})(typeof globalThis !== "undefined" ? globalThis : self);
