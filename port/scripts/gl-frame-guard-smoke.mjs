#!/usr/bin/env node

import assert from "node:assert/strict";
import vm from "node:vm";
import {GL_FRAME_GUARD_SOURCE, GL_FRAME_GUARD_WINDOW_EXPRESSION, glFrameGuardFailures}
  from "../../tools/gl-frame-guard.mjs";

class Context {
  getParameter() { return null; }
  viewport() {}
  bindFramebuffer() {}
  drawArrays() {}
  getBufferSubData() {}
  finish() {}
}
Object.assign(Context, {VIEWPORT: 0x0BA2, SCISSOR_BOX: 0x0C10, CURRENT_PROGRAM: 0x8B8D});
const callbacks = [];
const context = vm.createContext({WebGL2RenderingContext: Context,
  requestAnimationFrame: callback => callbacks.push(callback)});
vm.runInContext(GL_FRAME_GUARD_SOURCE, context);
const gl = new Context();
const events = {};
gl.canvas = {addEventListener: (name, handler) => { events[name] = handler; }};
gl.getParameter(Context.VIEWPORT);
assert.equal(context.__gaiusGlFrameGuard.syncCalls, 1);
gl.viewport(0, 0, 128, 64);
gl.getParameter(Context.VIEWPORT);
gl.getParameter(Context.CURRENT_PROGRAM);
assert.equal(context.__gaiusGlFrameGuard.syncCalls, 1);
gl.getParameter(Context.SCISSOR_BOX);
gl.getBufferSubData();
assert.equal(context.__gaiusGlFrameGuard.syncCalls, 3);
const window = vm.runInContext(GL_FRAME_GUARD_WINDOW_EXPRESSION(40), context);
gl.finish();
callbacks.shift()(0);
for (const stamp of [10, 20, 30, 40]) {
  if (stamp !== 20) {
    gl.bindFramebuffer(0x8CA9, {});
    gl.drawArrays();
    gl.bindFramebuffer(0x8CA9, null);
    gl.drawArrays();
    gl.drawArrays();
  }
  callbacks.shift()(stamp);
}
const measured = await window;
assert.equal(measured.gameFrames, 3);
assert.equal(measured.defaultWrites, 6);
assert.equal(measured.rafFps, 100);
assert.equal(measured.uniqueFps, 75);
assert.equal(measured.syncCalls, 0);
assert.deepEqual(glFrameGuardFailures(measured), []);
assert.equal(glFrameGuardFailures({...measured, gameFrames: 0}).length, 1);
assert.equal(glFrameGuardFailures({...measured, syncCalls: 1, syncByName: {finish: 1}}).length, 1);
const gpu = {stats: {presents: 0}, present() { if (this.surface) this.stats.presents++; }};
context.GaiusWebGpu = gpu;
const beforeGpu = context.__gaiusGlFrameGuard.frames;
gpu.present();
assert.equal(context.__gaiusGlFrameGuard.frames, beforeGpu);
gpu.surface = true;
gpu.present();
assert.equal(context.__gaiusGlFrameGuard.frames, beforeGpu + 1);
gpu.present();
assert.equal(context.__gaiusGlFrameGuard.frames, beforeGpu + 2);
context.GaiusWebGpu = gpu;
gpu.present();
assert.equal(context.__gaiusGlFrameGuard.frames, beforeGpu + 3);
events.webglcontextrestored();
const callsBeforeRestore = context.__gaiusGlFrameGuard.syncCalls;
gl.getParameter(Context.VIEWPORT);
assert.equal(context.__gaiusGlFrameGuard.syncCalls, callsBeforeRestore + 1);
console.log("gl-frame-guard-smoke: ok (4 rAF intervals, 3 unique frames, 0 measured sync calls)");
