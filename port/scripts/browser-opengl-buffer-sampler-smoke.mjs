#!/usr/bin/env node
// Executes the embedded sampler and texture binding glue, including target
// alias transitions, rather than a second implementation of the policy.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source = readFileSync(new URL('../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java', import.meta.url), 'utf8');
function body(method) {
  const end = source.indexOf(`native void ${method}(`);
  assert.ok(end >= 0, method);
  const begin = source.lastIndexOf('@JSBody(', end);
  return source.slice(begin, end).match(/script = """([\s\S]*?)"""\)/)[1];
}
const calls = [];
const created = [];
const gl = {TEXTURE_2D: 3553, TEXTURE_MIN_FILTER: 10241, TEXTURE_MAG_FILTER: 10240,
  TEXTURE_WRAP_S: 10242, TEXTURE_WRAP_T: 10243, NEAREST: 9728, LINEAR: 9729,
  NEAREST_MIPMAP_NEAREST: 9984, NEAREST_MIPMAP_LINEAR: 9986, CLAMP_TO_EDGE: 33071,
  bindSampler(unit, obj) {calls.push([unit, obj]);}, bindTexture() {}, texParameteri() {},
  deleteSampler(obj) {obj.deleted = true;}, getExtension() {return null;},
  createSampler() {const sampler = {params: new Map()}; created.push(sampler); return sampler;},
  samplerParameteri(sampler, parameter, value) {sampler.params.set(parameter, value);},
  samplerParameterf(sampler, parameter, value) {sampler.params.set(parameter, value);}};
const samplerA = {params: new Map()}, samplerB = {params: new Map()}, texture = {};
const state = {samplers: new Map([[1, samplerA], [2, samplerB]]), textures: new Map([[10, texture], [11, {}]]),
  samplerBindings: new Map(), effectiveSamplerBindings: new Map(), textureBufferUnits: new Set(),
  activeTextureUnit: 0, textureBindings: new Map(), textureParameters: new Map(), textureBufferDefaults: new Set(),
  textureInfo: new Map([[10, {internalFormat: 0x8058}], [11, {internalFormat: 0x81A6}]])};
const window = {__gaiusGL: state, __gaiusWebGL: gl};
// The whole sampler policy block of the GL layer: parameter records, the nearest-filter twins of
// unfilterable formats and applySamplerBinding itself.
const helper = source.match(/(            state\.samplerParams=[\s\S]*?state\.applySamplerBinding=function\(unit\) \{[\s\S]*?\n            \};)/);
assert.ok(helper, 'sampler policy helper');
// Format decisions come from the formats recorded at upload: querying framebuffer attachments
// or other GL state here would cost a sync and can raise GL errors (depth/stencil queries).
assert.doesNotMatch(helper[1], /getFramebufferAttachmentParameter|getParameter|getTexParameter|getError/,
  'sampler policy issues no GL queries');
new Function('window', 'state', helper[1])(window, state);
const bindSampler = new Function('window', 'unit', 'sampler', body('bindSampler')).bind(null, window);
const bindTexture = new Function('window', 'target', 'texture', body('bindTextureJs')).bind(null, window);
bindSampler(0, 1);
assert.deepEqual(calls.at(-1), [0, samplerA]);
bindTexture(0x8c2a, 10);
assert.deepEqual(calls.at(-1), [0, null], 'buffer texels ignore a previous linear sampler');
assert.equal(state.samplerBindings.get(0), 1, 'requested sampler is preserved');
let count = calls.length;
bindTexture(0x8c2a, 10);
bindSampler(0, 1);
assert.equal(calls.length, count, 'same effective binding is skipped');
bindSampler(0, 2);
assert.equal(calls.length, count, 'sampler updates while buffer is bound stay suppressed');
bindTexture(gl.TEXTURE_2D, 10);
assert.deepEqual(calls.at(-1), [0, samplerB], 'same texture object changing target restores sampler');
bindTexture(0x8c2a, 10);
bindSampler(0, 0);
bindTexture(gl.TEXTURE_2D, 10);
assert.deepEqual(calls.at(-1), [0, null], 'explicit sampler zero survives target transitions');
state.activeTextureUnit = 1;
bindSampler(1, 1);
bindTexture(gl.TEXTURE_2D, 10);
assert.deepEqual(calls.at(-1), [1, samplerA], 'units remain independent');
assert.equal(state.effectiveSamplerBindings.get(0), 0);
const deleteSampler = new Function('window', 'sampler', body('deleteSampler')).bind(null, window);
deleteSampler(1);
assert.deepEqual(calls.at(-1), [1, null], 'deletion clears the restored physical sampler');
state.activeTextureUnit = 0;
bindSampler(0, 2);
bindTexture(0x8c2a, 10);
deleteSampler(2);
bindTexture(gl.TEXTURE_2D, 10);
assert.deepEqual(calls.at(-1), [0, null], 'deletion while suppressed cannot restore a deleted sampler');
// Depth (and other unfilterable) textures are sampled through a nearest-filter twin of the
// requested sampler: WebGL2 makes them incomplete under vanilla's NEAREST_MIPMAP_LINEAR.
const samplerParameteri = new Function('window', 'sampler', 'parameter', 'value', body('samplerParameteri')).bind(null, window);
state.samplers.set(3, gl.createSampler());
const samplerC = state.samplers.get(3);
samplerParameteri(3, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
samplerParameteri(3, gl.TEXTURE_MIN_FILTER, gl.NEAREST_MIPMAP_LINEAR);
samplerParameteri(3, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
state.activeTextureUnit = 2;
bindSampler(2, 3);
bindTexture(gl.TEXTURE_2D, 10);
assert.equal(calls.at(-1)[1], samplerC, 'colour textures keep the requested sampler');
bindTexture(gl.TEXTURE_2D, 11);
const twin = calls.at(-1)[1];
assert.notEqual(twin, samplerC, 'a depth texture gets the twin');
assert.equal(twin.params.get(gl.TEXTURE_MIN_FILTER), gl.NEAREST_MIPMAP_NEAREST, 'twin minification filter');
assert.equal(twin.params.get(gl.TEXTURE_MAG_FILTER), gl.NEAREST, 'twin magnification filter');
assert.equal(twin.params.get(gl.TEXTURE_WRAP_S), gl.CLAMP_TO_EDGE, 'twin keeps the other parameters');
count = calls.length;
bindTexture(gl.TEXTURE_2D, 11);
assert.equal(calls.length, count, 'same twin binding is skipped');
samplerParameteri(3, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
assert.ok(twin.deleted, 'a parameter change retires the twin');
const twin2 = calls.at(-1)[1];
assert.notEqual(twin2, twin, 'the bound unit gets a fresh twin');
assert.equal(twin2.params.get(gl.TEXTURE_WRAP_T), gl.CLAMP_TO_EDGE, 'fresh twin has the new parameter');
bindTexture(gl.TEXTURE_2D, 10);
assert.equal(calls.at(-1)[1], samplerC, 'binding a colour texture restores the requested sampler');
const perf = readFileSync(new URL('../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGLPerf.java', import.meta.url), 'utf8');
assert.match(perf, /if \(target == 0x8C2A\) java\.util\.Arrays\.fill\(TEXTURES\[index\], Integer.MIN_VALUE\);/,
  'Java texture cache invalidates ordinary targets for sampler restoration');
console.log(`browser-opengl-buffer-sampler-smoke: ok (${calls.length} physical binds)`);
