#!/usr/bin/env node
// Quality runtime state shadow (port/web/runtime/quality/gl-pass.js): saveState and the cached
// game-texture framebuffers make no call that waits for the GPU process. On a model WebGL2
// context: after the first pass group (which reads each value it has never seen once), a
// pass group makes no getParameter(VIEWPORT|SCISSOR_BOX|COLOR_WRITEMASK) and no isFramebuffer;
// what the passes change is restored exactly (viewport, scissor box, draw buffer 0's colour
// mask through colorMaskiOES, bindings, enables); a restored context reads the values again;
// verify mode catches a value set behind the shadow's back; ?gaiusGlShadow=0 queries as before.
//
//   node port/scripts/quality-gl-shadow-smoke.mjs

import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const glPass = readFileSync(new URL("../web/runtime/quality/gl-pass.js", import.meta.url), "utf8");
const VIEWPORT = 0x0BA2, SCISSOR_BOX = 0x0C10, COLOR_WRITEMASK = 0x0C23;

function model() {
  const listeners = {};
  const state = {
    viewport: [0, 0, 300, 150], scissor: [0, 0, 300, 150], masks: [[true, true, true, true], [true, true, true, true]],
    enabled: new Set(), program: null, vao: null, draw: null, read: null, active: 0x84C0, unpack: null,
    textures: [null, null, null, null], samplers: [null, null, null, null],
  };
  const sync = {VIEWPORT: 0, SCISSOR_BOX: 0, COLOR_WRITEMASK: 0, isFramebuffer: 0, queries: 0};
  const ext = {
    enableiOES() {}, disableiOES() {},
    colorMaskiOES(index, r, g, b, a) { state.masks[index] = [!!r, !!g, !!b, !!a]; },
  };
  const gl = {
    CURRENT_PROGRAM: 0x8B8D, VERTEX_ARRAY_BINDING: 0x85B5, DRAW_FRAMEBUFFER_BINDING: 0x8CA6,
    READ_FRAMEBUFFER_BINDING: 0x8CAA, ACTIVE_TEXTURE: 0x84E0, PIXEL_UNPACK_BUFFER_BINDING: 0x88EF,
    TEXTURE_BINDING_2D: 0x8069, SAMPLER_BINDING: 0x8919, VIEWPORT, SCISSOR_BOX, COLOR_WRITEMASK,
    SCISSOR_TEST: 0x0C11, BLEND: 0x0BE2, DEPTH_TEST: 0x0B71, STENCIL_TEST: 0x0B90, CULL_FACE: 0x0B44,
    RASTERIZER_DISCARD: 0x8C89, TEXTURE0: 0x84C0, DRAW_FRAMEBUFFER: 0x8CA9, READ_FRAMEBUFFER: 0x8CA8,
    PIXEL_UNPACK_BUFFER: 0x88EC, TEXTURE_2D: 0x0DE1, COLOR_ATTACHMENT0: 0x8CE0, FRAMEBUFFER_COMPLETE: 0x8CD5,
    canvas: {addEventListener(type, fn) { (listeners[type] ??= []).push(fn); }},
    getExtension(name) { return name === "OES_draw_buffers_indexed" ? ext : null; },
    getParameter(p) {
      sync.queries++;
      switch (p) {
        case VIEWPORT: sync.VIEWPORT++; return new Int32Array(state.viewport);
        case SCISSOR_BOX: sync.SCISSOR_BOX++; return new Int32Array(state.scissor);
        case COLOR_WRITEMASK: sync.COLOR_WRITEMASK++; return state.masks[0].slice();
        case this.CURRENT_PROGRAM: return state.program;
        case this.VERTEX_ARRAY_BINDING: return state.vao;
        case this.DRAW_FRAMEBUFFER_BINDING: return state.draw;
        case this.READ_FRAMEBUFFER_BINDING: return state.read;
        case this.ACTIVE_TEXTURE: return state.active;
        case this.PIXEL_UNPACK_BUFFER_BINDING: return state.unpack;
        case this.TEXTURE_BINDING_2D: return state.textures[state.active - 0x84C0];
        case this.SAMPLER_BINDING: return state.samplers[state.active - 0x84C0];
        default: throw new Error("unexpected getParameter 0x" + p.toString(16));
      }
    },
    isEnabled(cap) { return state.enabled.has(cap); },
    enable(cap) { state.enabled.add(cap); }, disable(cap) { state.enabled.delete(cap); },
    viewport(x, y, w, h) { state.viewport = [x, y, w, h]; },
    scissor(x, y, w, h) { state.scissor = [x, y, w, h]; },
    colorMask(r, g, b, a) { state.masks = state.masks.map(() => [!!r, !!g, !!b, !!a]); },
    activeTexture(unit) { state.active = unit; },
    bindTexture(target, texture) { state.textures[state.active - 0x84C0] = texture; },
    bindSampler(unit, sampler) { state.samplers[unit] = sampler; },
    bindVertexArray(vao) { state.vao = vao; }, useProgram(program) { state.program = program; },
    bindFramebuffer(target, fb) { if (target === 0x8CA9 || target === 0x8D40) state.draw = fb; if (target === 0x8CA8 || target === 0x8D40) state.read = fb; },
    bindBuffer(target, buffer) { if (target === 0x88EC) state.unpack = buffer; },
    isFramebuffer() { sync.isFramebuffer++; return true; },
    createFramebuffer() { return {framebuffer: true}; },
    framebufferTexture2D() {}, checkFramebufferStatus() { return 0x8CD5; }, deleteFramebuffer() {},
    drawArrays() {},
  };
  return {gl, state, sync, ext, listeners, fire: type => (listeners[type] || []).forEach(fn => fn())};
}

function load(search) {
  const root = {location: {search}};
  root.globalThis = root;
  vm.createContext(root);
  vm.runInContext(glPass, root);
  return root.GaiusQuality.gl;
}

const snapshot = s => JSON.stringify({...s, enabled: [...s.enabled].sort()});
const syncTotal = sync => sync.VIEWPORT + sync.SCISSOR_BOX + sync.COLOR_WRITEMASK + sync.isFramebuffer;

// A pass group as endLevel runs it: save, prepare, draw into the game texture's framebuffer, restore.
function passGroup(G, gl, external, texture) {
  const saved = G.saveState(gl);
  G.prepareState(gl, {vao: true});
  const fb = external.get(gl, texture);
  G.draw(gl, fb, 0, 0, 64, 32);
  G.restoreState(gl, saved);
}

{
  const G = load("");
  const {gl, state, sync, ext, fire} = model();
  G.installShadow(gl);
  // The game sets its state through the context (BrowserOpenGL's entry points).
  gl.viewport(1, 2, 640, 360);
  gl.scissor(3, 4, 50, 60);
  gl.enable(gl.SCISSOR_TEST);
  gl.enable(gl.BLEND);
  gl.getExtension("OES_draw_buffers_indexed").colorMaskiOES(0, true, false, true, false);
  ext.colorMaskiOES(1, false, false, false, true);
  gl.useProgram({program: 1}); gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, {fb: 1});
  gl.activeTexture(gl.TEXTURE0 + 2); gl.bindTexture(gl.TEXTURE_2D, {tex: 2});
  const external = new G.ExternalFramebuffers();
  const texture = {texture: "main"};
  const before = snapshot(state);
  const startupQueries = sync.queries;
  passGroup(G, gl, external, texture);
  // The colour mask of draw buffer 0 had been set before the extension object was wrapped:
  // it is read once; the viewport and scissor were recorded at their calls.
  assert.equal(sync.VIEWPORT, 1, "viewport seeded before rendering");
  assert.equal(sync.SCISSOR_BOX, 1, "scissor box seeded before rendering");
  assert.equal(sync.COLOR_WRITEMASK, 1, "colour mask seeded before rendering");
  assert.equal(snapshot(state), before, "the pass group restores everything it changed");
  for (let frame = 0; frame < 50; frame++) {
    gl.viewport(0, 0, 640 + frame, 360);
    ext.colorMaskiOES(0, frame % 2 === 0, true, true, true);
    const frameState = snapshot(state);
    passGroup(G, gl, external, texture);
    assert.equal(snapshot(state), frameState, `frame ${frame} restored`);
  }
  assert.equal(syncTotal(sync), 3, "no synchronous query after context initialization");
  assert.equal(sync.queries, startupQueries, "saveState makes zero getParameter calls");
  assert.equal(G.shadowStats.mismatches, 0);
  // A restored context starts from defaults and new extension objects: values are read again.
  const restoredReads = syncTotal(sync);
  fire("webglcontextrestored");
  passGroup(G, gl, new G.ExternalFramebuffers(), texture);
  assert.equal(syncTotal(sync) - restoredReads, 3, "a restored context seeds the three values once");
  // Verify mode: a viewport set behind the shadow (the context's own prototype method) is caught.
  G.setVerify(true);
  const raw = {viewport: (x, y, w, h) => { state.viewport = [x, y, w, h]; }};
  raw.viewport(9, 9, 9, 9);
  G.saveState(gl);
  assert.equal(G.shadowStats.mismatches, 1, "verify mode counts a disagreement");
  G.setVerify(false);
}

// ?gaiusGlShadow=0: every pass group queries the values and re-checks the framebuffer (old path).
{
  const G = load("?gaiusGlShadow=0");
  const {gl, sync} = model();
  G.installShadow(gl);
  const external = new G.ExternalFramebuffers();
  const texture = {texture: 1};
  for (let frame = 0; frame < 5; frame++) passGroup(G, gl, external, texture);
  assert.equal(sync.VIEWPORT, 6);
  assert.equal(sync.SCISSOR_BOX, 6);
  assert.equal(sync.COLOR_WRITEMASK, 6);
  assert.equal(sync.isFramebuffer, 4, "the cached framebuffer is re-checked");
}

console.log("quality-gl-shadow-smoke: ok");
