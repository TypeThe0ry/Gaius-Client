// Gaius full-screen pass helpers shared by the upscaler and the post-processing chain.
// Registers GaiusQuality.gl.
//
// The passes run in the middle of the game's own WebGL2 command stream (BrowserOpenGL keeps
// JavaScript-side caches of bindings and the 26.3 renderer keeps Java-side ones), so every pass
// group is wrapped in saveState()/restoreState(): whatever a pass binds or enables is put back
// exactly as the context had it before the group started (read from the browser's cached state
// or, for the values it does not cache, from the shadow below; never with a query that waits
// for the GPU process). Passes never touch the game's
// buffers, vertex arrays or uniform-buffer bindings; full-screen triangles come from gl_VertexID
// with a private empty vertex array.
//
// Blend enable and colour write mask are per draw buffer under OES_draw_buffers_indexed, and the
// 26.3 renderer caches them per index (its order-independent transparency pipelines blend into
// several attachments). Passes only write draw buffer 0, so with the extension they change and
// restore index 0 alone; the non-indexed enable/colorMask calls would overwrite every index
// behind the game's back.
(function installGaiusGlPass(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.gl) return;

  const FULLSCREEN_VS = "#version 300 es\n"
      + "void main() {\n"
      + "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
      + "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
      + "}\n";

  // Common fragment prologue: rect-aware sampling helpers. uTexel is 1/size of the textures the
  // pass reads (all pass targets share the frame size); uClamp limits linear taps to the rendered
  // sub-rectangle when the world renders below full resolution.
  const FRAGMENT_PROLOGUE = "#version 300 es\n"
      + "precision highp float;\n"
      + "precision highp int;\n"
      + "precision highp sampler2D;\n"
      + "uniform vec2 uTexel;\n"
      + "uniform vec2 uClamp;\n"
      + "out vec4 outColor;\n"
      + "vec2 fragUv() { return gl_FragCoord.xy * uTexel; }\n"
      + "vec2 clampUv(vec2 uv) { return clamp(uv, uTexel * 0.5, uClamp); }\n"
      + "float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n";

  function compileShader(gl, type, source, name) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS) && !gl.isContextLost()) {
      const log = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      throw new Error("Gaius quality shader " + name + " failed to compile: " + log);
    }
    return shader;
  }

  function createProgram(gl, name, fragmentSource, vertexSource) {
    const vs = compileShader(gl, gl.VERTEX_SHADER, vertexSource || FULLSCREEN_VS, name + ".vs");
    const fs = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource, name + ".fs");
    const program = gl.createProgram();
    gl.attachShader(program, vs);
    gl.attachShader(program, fs);
    gl.linkProgram(program);
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS) && !gl.isContextLost()) {
      const log = gl.getProgramInfoLog(program);
      gl.deleteProgram(program);
      throw new Error("Gaius quality program " + name + " failed to link: " + log);
    }
    const uniforms = new Map();
    return {
      name: name,
      program: program,
      uniform: function (uniformName) {
        if (uniforms.has(uniformName)) return uniforms.get(uniformName);
        const location = gl.getUniformLocation(program, uniformName);
        uniforms.set(uniformName, location);
        return location;
      }
    };
  }

  // Units the passes bind textures and samplers on. Restored individually.
  const UNIT_COUNT = 4;

  // State shadow of one context. The browser answers the object-binding, active-texture and
  // enable queries saveState makes from its own cache, but not SCISSOR_BOX and COLOR_WRITEMASK
  // (nor VIEWPORT before the page has set it): each getParameter for them waits for the GPU
  // process to drain the command buffer (CommandBufferHelper::Finish), about 1 ms per call
  // behind a busy frame. So the shadow records them at the context's entry points (viewport, scissor,
  // colorMask and colorMaskiOES on draw buffer 0, wrapped on the context object, so the
  // BrowserOpenGL calls, the passes' own calls and any other caller are all seen). A value no
  // call has set is seeded once at installation or context restoration, outside frame saves.
  // Program, VAO, framebuffer, texture, sampler and enable bindings are tracked too.
  // Verify mode (?gaiusGlVerify=1, or
  // GaiusQuality.gl.setVerify(true)) also reads the real values on every saveState and
  // counts disagreements in GaiusQuality.gl.shadowStats. ?gaiusGlShadow=0 (or
  // GaiusQuality.gl.setShadowEnabled(false)) queries every value again, and re-checks the
  // cached game-texture framebuffers with isFramebuffer, as before the shadow (A/B runs).
  const VIEWPORT = 0x0BA2;
  const SCISSOR_BOX = 0x0C10;
  const COLOR_WRITEMASK = 0x0C23;
  const shadows = new WeakMap();
  const shadowStats = {installs: 0, seeds: 0, saves: 0, verified: 0, mismatches: 0, lastMismatch: null};
  let verify = false;
  let useShadow = true;
  try {
    const search = root.location && root.location.search ? String(root.location.search) : "";
    verify = /[?&]gaiusGlVerify=(1|true|on)(&|$)/i.test(search);
    useShadow = !/[?&]gaiusGlShadow=(0|false|off)(&|$)/i.test(search);
  } catch (error) {
    verify = false;
  }

  function lookUpIndexedExtension(gl) {
    let extension = null;
    try {
      extension = gl.getExtension("OES_draw_buffers_indexed") || null;
    } catch (error) {
      extension = null;
    }
    if (extension && (typeof extension.enableiOES !== "function"
        || typeof extension.disableiOES !== "function"
        || typeof extension.colorMaskiOES !== "function")) {
      return null;
    }
    return extension;
  }

  function wrapColorMaskIndexed(shadow, extension) {
    if (!extension || extension.__gaiusShadowWrapped) return;
    const original = extension.colorMaskiOES;
    extension.colorMaskiOES = function (index, red, green, blue, alpha) {
      if ((index | 0) === 0) {
        shadow.colorMask = [!!red, !!green, !!blue, !!alpha];
      }
      return original.call(this, index, red, green, blue, alpha);
    };
    extension.__gaiusShadowWrapped = true;
    for (const name of ["enableiOES", "disableiOES"]) {
      const originalEnable = extension[name];
      extension[name] = function (cap, index) {
        if ((index | 0) === 0) shadow.enabled.set(cap, name === "enableiOES");
        return originalEnable.call(this, cap, index);
      };
    }
  }

  // Records viewport, scissor box and draw buffer 0's colour mask for gl. Idempotent; the
  // quality runtime installs it as soon as it sees the context (onContext), saveState
  // installs it lazily otherwise.
  function installShadow(gl) {
    let shadow = shadows.get(gl);
    if (shadow) return shadow;
    shadow = {viewport: null, scissorBox: null, colorMask: null, indexed: undefined,
      enabled: new Map(), bindings: null, textures: [], samplers: []};
    shadows.set(gl, shadow);
    shadowStats.installs++;
    const viewport = gl.viewport;
    const scissor = gl.scissor;
    const colorMask = gl.colorMask;
    gl.viewport = function (x, y, width, height) {
      if (width >= 0 && height >= 0) shadow.viewport = [x | 0, y | 0, width | 0, height | 0];
      return viewport.call(this, x, y, width, height);
    };
    gl.scissor = function (x, y, width, height) {
      if (width >= 0 && height >= 0) shadow.scissorBox = [x | 0, y | 0, width | 0, height | 0];
      return scissor.call(this, x, y, width, height);
    };
    gl.colorMask = function (red, green, blue, alpha) {
      shadow.colorMask = [!!red, !!green, !!blue, !!alpha];
      return colorMask.call(this, red, green, blue, alpha);
    };
    seedBindings(gl, shadow);
    for (const name of ["enable", "disable"]) {
      const original = gl[name];
      gl[name] = function (cap) {
        shadow.enabled.set(cap, name === "enable");
        return original.call(this, cap);
      };
    }
    for (const entry of [["useProgram", "program"], ["bindVertexArray", "vao"], ["activeTexture", "activeTexture"]]) {
      const original = gl[entry[0]];
      gl[entry[0]] = function (value) {
        shadow.bindings[entry[1]] = value;
        return original.call(this, value);
      };
    }
    const framebuffer = gl.bindFramebuffer;
    gl.bindFramebuffer = function (target, value) {
      if (target === gl.DRAW_FRAMEBUFFER || target === 0x8D40) shadow.bindings.drawFramebuffer = value;
      if (target === gl.READ_FRAMEBUFFER || target === 0x8D40) shadow.bindings.readFramebuffer = value;
      return framebuffer.call(this, target, value);
    };
    const buffer = gl.bindBuffer;
    gl.bindBuffer = function (target, value) {
      if (target === gl.PIXEL_UNPACK_BUFFER) shadow.bindings.unpackBuffer = value;
      return buffer.call(this, target, value);
    };
    const texture = gl.bindTexture;
    gl.bindTexture = function (target, value) {
      if (target === gl.TEXTURE_2D) shadow.textures[shadow.bindings.activeTexture - gl.TEXTURE0] = value;
      return texture.call(this, target, value);
    };
    const sampler = gl.bindSampler;
    gl.bindSampler = function (unit, value) {
      shadow.samplers[unit] = value;
      return sampler.call(this, unit, value);
    };
    for (const name of ["deleteTexture", "deleteSampler", "deleteVertexArray", "deleteFramebuffer", "deleteBuffer"]) {
      const original = gl[name];
      if (!original) continue;
      gl[name] = function (value) {
        if (value) {
          if (name === "deleteTexture" || name === "deleteSampler") {
            const units = name === "deleteTexture" ? shadow.textures : shadow.samplers;
            for (let unit = 0; unit < units.length; unit++) if (units[unit] === value) units[unit] = null;
          } else {
            const keys = name === "deleteFramebuffer" ? ["drawFramebuffer", "readFramebuffer"]
              : [name === "deleteBuffer" ? "unpackBuffer" : "vao"];
            for (const key of keys) if (shadow.bindings[key] === value) shadow.bindings[key] = null;
          }
        }
        return original.call(this, value);
      };
    }
    shadow.indexed = lookUpIndexedExtension(gl);
    wrapColorMaskIndexed(shadow, shadow.indexed);
    if (gl.canvas && gl.canvas.addEventListener) {
      // A restored context starts from the default state and hands out new extension objects.
      gl.canvas.addEventListener("webglcontextrestored", function () {
        resetShadow(gl);
        seedBindings(gl, shadow);
      }, false);
    }
    return shadow;
  }

  function seedBindings(gl, shadow) {
    shadow.viewport = Array.from(gl.getParameter(VIEWPORT));
    shadow.scissorBox = Array.from(gl.getParameter(SCISSOR_BOX));
    shadow.colorMask = Array.from(gl.getParameter(COLOR_WRITEMASK));
    shadow.bindings = {
      program: gl.getParameter(gl.CURRENT_PROGRAM), vao: gl.getParameter(gl.VERTEX_ARRAY_BINDING),
      drawFramebuffer: gl.getParameter(gl.DRAW_FRAMEBUFFER_BINDING),
      readFramebuffer: gl.getParameter(gl.READ_FRAMEBUFFER_BINDING),
      activeTexture: gl.getParameter(gl.ACTIVE_TEXTURE),
      unpackBuffer: gl.getParameter(gl.PIXEL_UNPACK_BUFFER_BINDING)
    };
    for (const cap of [gl.SCISSOR_TEST, gl.BLEND, gl.DEPTH_TEST, gl.STENCIL_TEST, gl.CULL_FACE, gl.RASTERIZER_DISCARD]) {
      shadow.enabled.set(cap, gl.isEnabled(cap));
    }
    const active = shadow.bindings.activeTexture;
    for (let unit = 0; unit < UNIT_COUNT; unit++) {
      gl.activeTexture(gl.TEXTURE0 + unit);
      shadow.textures[unit] = gl.getParameter(gl.TEXTURE_BINDING_2D);
      shadow.samplers[unit] = gl.getParameter(gl.SAMPLER_BINDING);
    }
    gl.activeTexture(active);
  }

  function resetShadow(gl) {
    const shadow = shadows.get(gl);
    if (!shadow) return;
    shadow.viewport = null;
    shadow.scissorBox = null;
    shadow.colorMask = null;
    shadow.indexed = undefined;
  }

  // OES_draw_buffers_indexed of this context, or null; looked up once per context (and again
  // after a restore, which hands out new extension objects).
  function indexedExtension(gl) {
    const shadow = installShadow(gl);
    if (shadow.indexed === undefined) {
      shadow.indexed = lookUpIndexedExtension(gl);
      wrapColorMaskIndexed(shadow, shadow.indexed);
    }
    return shadow.indexed;
  }

  function sameValues(a, b) {
    if (!a || !b || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      if (a[i] !== b[i]) return false;
    }
    return true;
  }

  // The recorded value of pname, or the real one (recorded from now on) when none is known.
  function shadowed(gl, shadow, key, pname) {
    let value = useShadow ? shadow[key] : null;
    if (value && !verify) return value.slice();
    const real = Array.prototype.slice.call(gl.getParameter(pname) || []);
    if (value) {
      shadowStats.verified++;
      if (!sameValues(real, value)) {
        shadowStats.mismatches++;
        shadowStats.lastMismatch = {pname: pname, shadow: value.slice(), real: real.slice()};
      }
    } else if (useShadow) {
      shadowStats.seeds++;
    }
    if (real.length) value = real;
    if (!value) {
      // Lost context: nothing to restore into, any value will do.
      return pname === COLOR_WRITEMASK ? [true, true, true, true] : [0, 0, 0, 0];
    }
    if (useShadow) shadow[key] = value;
    return value.slice();
  }

  // The non-indexed BLEND query reports draw buffer 0; colorMask is draw buffer 0's mask.
  function saveState(gl) {
    const shadow = installShadow(gl);
    const indexed = indexedExtension(gl);
    shadowStats.saves++;
    if (useShadow && !verify) {
      return Object.assign({indexed: indexed}, shadow.bindings, {
        viewport: shadowed(gl, shadow, "viewport", VIEWPORT),
        scissorBox: shadowed(gl, shadow, "scissorBox", SCISSOR_BOX),
        colorMask: shadowed(gl, shadow, "colorMask", COLOR_WRITEMASK),
        scissorTest: shadow.enabled.get(gl.SCISSOR_TEST), blend: shadow.enabled.get(gl.BLEND),
        depthTest: shadow.enabled.get(gl.DEPTH_TEST), stencilTest: shadow.enabled.get(gl.STENCIL_TEST),
        cullFace: shadow.enabled.get(gl.CULL_FACE), rasterizerDiscard: shadow.enabled.get(gl.RASTERIZER_DISCARD),
        textures: shadow.textures.slice(0, UNIT_COUNT), samplers: shadow.samplers.slice(0, UNIT_COUNT)
      });
    }
    const s = {
      indexed: indexed,
      program: gl.getParameter(gl.CURRENT_PROGRAM),
      vao: gl.getParameter(gl.VERTEX_ARRAY_BINDING),
      drawFramebuffer: gl.getParameter(gl.DRAW_FRAMEBUFFER_BINDING),
      readFramebuffer: gl.getParameter(gl.READ_FRAMEBUFFER_BINDING),
      activeTexture: gl.getParameter(gl.ACTIVE_TEXTURE),
      unpackBuffer: gl.getParameter(gl.PIXEL_UNPACK_BUFFER_BINDING),
      viewport: shadowed(gl, shadow, "viewport", VIEWPORT),
      scissorTest: gl.isEnabled(gl.SCISSOR_TEST),
      scissorBox: shadowed(gl, shadow, "scissorBox", SCISSOR_BOX),
      blend: gl.isEnabled(gl.BLEND),
      depthTest: gl.isEnabled(gl.DEPTH_TEST),
      stencilTest: gl.isEnabled(gl.STENCIL_TEST),
      cullFace: gl.isEnabled(gl.CULL_FACE),
      rasterizerDiscard: gl.isEnabled(gl.RASTERIZER_DISCARD),
      colorMask: shadowed(gl, shadow, "colorMask", COLOR_WRITEMASK),
      textures: new Array(UNIT_COUNT),
      samplers: new Array(UNIT_COUNT)
    };
    for (let unit = 0; unit < UNIT_COUNT; unit++) {
      gl.activeTexture(gl.TEXTURE0 + unit);
      s.textures[unit] = gl.getParameter(gl.TEXTURE_BINDING_2D);
      s.samplers[unit] = gl.getParameter(gl.SAMPLER_BINDING);
    }
    gl.activeTexture(s.activeTexture);
    if (useShadow && verify) {
      const tracked = Object.assign({}, shadow.bindings, {
        scissorTest: shadow.enabled.get(gl.SCISSOR_TEST), blend: shadow.enabled.get(gl.BLEND),
        depthTest: shadow.enabled.get(gl.DEPTH_TEST), stencilTest: shadow.enabled.get(gl.STENCIL_TEST),
        cullFace: shadow.enabled.get(gl.CULL_FACE), rasterizerDiscard: shadow.enabled.get(gl.RASTERIZER_DISCARD)
      });
      for (const key of Object.keys(tracked)) {
        shadowStats.verified++;
        if (tracked[key] !== s[key]) {
          shadowStats.mismatches++;
          shadowStats.lastMismatch = {key: key};
        }
      }
      for (let unit = 0; unit < UNIT_COUNT; unit++) {
        shadowStats.verified++;
        if (shadow.textures[unit] !== s.textures[unit] || shadow.samplers[unit] !== s.samplers[unit]) {
          shadowStats.mismatches++;
          shadowStats.lastMismatch = {unit: unit};
        }
      }
    }
    return s;
  }

  // The fixed-function state every pass expects: no blending, depth, stencil, culling, scissor
  // or discard; all colour channels written; no pixel-unpack buffer (texture allocation).
  function prepareState(gl, vao) {
    const indexed = indexedExtension(gl);
    if (indexed) {
      indexed.disableiOES(gl.BLEND, 0);
      indexed.colorMaskiOES(0, true, true, true, true);
    } else {
      gl.disable(gl.BLEND);
      gl.colorMask(true, true, true, true);
    }
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.STENCIL_TEST);
    gl.disable(gl.CULL_FACE);
    gl.disable(gl.SCISSOR_TEST);
    gl.disable(gl.RASTERIZER_DISCARD);
    gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, null);
    gl.bindVertexArray(vao);
  }

  function setEnabled(gl, cap, enabled) {
    if (enabled) gl.enable(cap);
    else gl.disable(cap);
  }

  function restoreState(gl, s) {
    for (let unit = UNIT_COUNT - 1; unit >= 0; unit--) {
      gl.activeTexture(gl.TEXTURE0 + unit);
      gl.bindTexture(gl.TEXTURE_2D, s.textures[unit]);
      gl.bindSampler(unit, s.samplers[unit]);
    }
    gl.activeTexture(s.activeTexture);
    gl.bindVertexArray(s.vao);
    gl.useProgram(s.program);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, s.drawFramebuffer);
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, s.readFramebuffer);
    gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, s.unpackBuffer);
    gl.viewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3]);
    gl.scissor(s.scissorBox[0], s.scissorBox[1], s.scissorBox[2], s.scissorBox[3]);
    setEnabled(gl, gl.SCISSOR_TEST, s.scissorTest);
    setEnabled(gl, gl.DEPTH_TEST, s.depthTest);
    setEnabled(gl, gl.STENCIL_TEST, s.stencilTest);
    setEnabled(gl, gl.CULL_FACE, s.cullFace);
    setEnabled(gl, gl.RASTERIZER_DISCARD, s.rasterizerDiscard);
    if (s.indexed) {
      if (s.blend) s.indexed.enableiOES(gl.BLEND, 0);
      else s.indexed.disableiOES(gl.BLEND, 0);
      s.indexed.colorMaskiOES(0, s.colorMask[0], s.colorMask[1], s.colorMask[2], s.colorMask[3]);
    } else {
      setEnabled(gl, gl.BLEND, s.blend);
      gl.colorMask(s.colorMask[0], s.colorMask[1], s.colorMask[2], s.colorMask[3]);
    }
  }

  function internalFormatOf(gl, format) {
    switch (format) {
      case "rgba16f": return gl.RGBA16F;
      case "r8": return gl.R8;
      case "rg8": return gl.RG8;
      default: return gl.RGBA8;
    }
  }

  function createTarget(gl, width, height, format) {
    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.texStorage2D(gl.TEXTURE_2D, 1, internalFormatOf(gl, format), width, height);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const framebuffer = gl.createFramebuffer();
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer);
    gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
    const status = gl.checkFramebufferStatus(gl.DRAW_FRAMEBUFFER);
    if (status !== gl.FRAMEBUFFER_COMPLETE) {
      gl.deleteFramebuffer(framebuffer);
      gl.deleteTexture(texture);
      throw new Error("Gaius quality target " + format + " " + width + "x" + height
          + " is incomplete (0x" + status.toString(16) + ")");
    }
    return {texture: texture, framebuffer: framebuffer, width: width, height: height, format: format};
  }

  function deleteTarget(gl, target) {
    if (!target) return;
    try {
      gl.deleteFramebuffer(target.framebuffer);
      gl.deleteTexture(target.texture);
    } catch (error) {
      // Lost context: nothing to free.
    }
  }

  // Named render targets that follow the frame size; reallocated only when the size or format
  // changes, so switching the world render scale never allocates.
  function TargetPool() {
    this.targets = new Map();
  }

  TargetPool.prototype.get = function (gl, name, width, height, format) {
    const w = Math.max(1, width | 0);
    const h = Math.max(1, height | 0);
    let target = this.targets.get(name);
    if (target && target.width === w && target.height === h && target.format === format) {
      return target;
    }
    deleteTarget(gl, target);
    target = createTarget(gl, w, h, format);
    this.targets.set(name, target);
    return target;
  };

  TargetPool.prototype.dispose = function (gl) {
    const targets = this.targets;
    targets.forEach(function (target) {
      deleteTarget(gl, target);
    });
    targets.clear();
  };

  TargetPool.prototype.forget = function () {
    this.targets.clear();
  };

  // Framebuffers wrapping textures the game owns (main colour, entity outline). Keyed by the
  // WebGLTexture object, so a texture BrowserOpenGL deletes and recreates gets a fresh wrapper.
  // Only these wrappers' owner (the runtime's resources) deletes them, and a lost or restored
  // context drops the whole cache (quality-runtime.js), so a cached wrapper is always live: no
  // isFramebuffer check, which would wait for the GPU process like the queries above.
  function ExternalFramebuffers() {
    this.map = new WeakMap();
    this.attachments = new WeakMap();
  }

  ExternalFramebuffers.prototype.get = function (gl, texture) {
    const perf = root.__gaiusGL && root.__gaiusGL.perfGl2;
    if (perf && perf.flags.fbo) {
      return this.getAttachments(gl, [{point: gl.COLOR_ATTACHMENT0, texture: texture,
        target: gl.TEXTURE_2D, level: 0}]);
    }
    let framebuffer = this.map.get(texture);
    if (framebuffer && (useShadow || gl.isFramebuffer(framebuffer))) return framebuffer;
    framebuffer = gl.createFramebuffer();
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer);
    gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
    if (gl.checkFramebufferStatus(gl.DRAW_FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
      gl.deleteFramebuffer(framebuffer);
      throw new Error("Gaius quality cannot render into the game texture");
    }
    this.map.set(texture, framebuffer);
    return framebuffer;
  };

  ExternalFramebuffers.prototype.forget = function () {
    this.map = new WeakMap();
    this.attachments = new WeakMap();
  };

  ExternalFramebuffers.prototype.getAttachments = function (gl, attachments) {
    if (!attachments.length) throw new Error("Empty framebuffer attachment set");
    const ordered = attachments.slice().sort(function (left, right) {
      return left.point - right.point;
    });
    let nodes = this.attachments;
    let leaf;
    for (let index = 0; index < ordered.length; index++) {
      const attachment = ordered[index];
      if (!attachment.texture || (index && ordered[index - 1].point === attachment.point)) {
        throw new Error("Invalid framebuffer attachment set");
      }
      let variants = nodes.get(attachment.texture);
      if (!variants) nodes.set(attachment.texture, variants = new Map());
      const key = [attachment.point, attachment.target, attachment.level || 0,
        attachment.layer === undefined ? "2d" : attachment.layer].join(":");
      leaf = variants.get(key);
      if (!leaf) variants.set(key, leaf = {next: new WeakMap(), framebuffer: null});
      nodes = leaf.next;
    }
    if (leaf.framebuffer) return leaf.framebuffer;
    const framebuffer = gl.createFramebuffer();
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer);
    const colors = [];
    for (const attachment of ordered) {
      if (attachment.layer === undefined) {
        gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, attachment.point, attachment.target,
          attachment.texture, attachment.level || 0);
      } else {
        gl.framebufferTextureLayer(gl.DRAW_FRAMEBUFFER, attachment.point, attachment.texture,
          attachment.level || 0, attachment.layer);
      }
      if (attachment.point >= gl.COLOR_ATTACHMENT0 && attachment.point <= gl.COLOR_ATTACHMENT15) {
        colors[attachment.point - gl.COLOR_ATTACHMENT0] = attachment.point;
      }
    }
    if (colors.length) {
      for (let index = 0; index < colors.length; index++) if (colors[index] === undefined) colors[index] = gl.NONE;
      gl.drawBuffers(colors);
    } else {
      gl.drawBuffers([gl.NONE]);
    }
    if (gl.checkFramebufferStatus(gl.DRAW_FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
      gl.deleteFramebuffer(framebuffer);
      throw new Error("Gaius quality cannot render into the attachment set");
    }
    leaf.framebuffer = framebuffer;
    return framebuffer;
  };

  function createSamplers(gl) {
    const make = function (filter) {
      const sampler = gl.createSampler();
      gl.samplerParameteri(sampler, gl.TEXTURE_MIN_FILTER, filter);
      gl.samplerParameteri(sampler, gl.TEXTURE_MAG_FILTER, filter);
      gl.samplerParameteri(sampler, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.samplerParameteri(sampler, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.samplerParameteri(sampler, gl.TEXTURE_COMPARE_MODE, gl.NONE);
      return sampler;
    };
    return {linear: make(gl.LINEAR), nearest: make(gl.NEAREST)};
  }

  function bindTexture(gl, unit, texture, sampler) {
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.bindSampler(unit, sampler);
  }

  // Draws one full-screen triangle into framebuffer over the viewport rectangle.
  function draw(gl, framebuffer, x, y, width, height) {
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer);
    gl.viewport(x, y, width, height);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  function setRect(gl, program, texWidth, texHeight, rectWidth, rectHeight) {
    gl.uniform2f(program.uniform("uTexel"), 1 / texWidth, 1 / texHeight);
    gl.uniform2f(program.uniform("uClamp"),
        (rectWidth - 0.5) / texWidth, (rectHeight - 0.5) / texHeight);
  }

  Q.gl = Object.freeze({
    FULLSCREEN_VS: FULLSCREEN_VS,
    FRAGMENT_PROLOGUE: FRAGMENT_PROLOGUE,
    UNIT_COUNT: UNIT_COUNT,
    createProgram: createProgram,
    installShadow: installShadow,
    resetShadow: resetShadow,
    shadowStats: shadowStats,
    setVerify: function (on) {
      verify = !!on;
    },
    setShadowEnabled: function (on) {
      useShadow = !!on;
    },
    saveState: saveState,
    prepareState: prepareState,
    restoreState: restoreState,
    createTarget: createTarget,
    deleteTarget: deleteTarget,
    TargetPool: TargetPool,
    ExternalFramebuffers: ExternalFramebuffers,
    createSamplers: createSamplers,
    bindTexture: bindTexture,
    draw: draw,
    setRect: setRect
  });
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
