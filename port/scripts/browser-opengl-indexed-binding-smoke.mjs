import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java', import.meta.url), 'utf8');
function body(name) {
  const declaration = source.indexOf(`native void ${name}(`);
  assert.ok(declaration >= 0, name);
  const start = source.indexOf('script = """', source.lastIndexOf('@JSBody', declaration)) + 'script = """'.length;
  return source.slice(start, source.indexOf('"""', start));
}

// Indexed binds change BOTH the indexed slot and the target's generic binding.
// Model the driver separately from the shim, then upload through the generic
// target: a stale shadow must never redirect the upload to another buffer.
for (const target of [0x8A11, 0x8C8E]) {
  const buffers = new Map([[1, {id: 1}], [2, {id: 2}]]);
  const physical = new Map(), indexed = new Map(), storage = new Map();
  const calls = {generic: 0, range: 0, base: 0};
  for (const object of buffers.values()) storage.set(object, new Uint8Array(512));
  const gl = {
    ELEMENT_ARRAY_BUFFER: 0x8893,
    bindBuffer(t, object) { calls.generic++; physical.set(t, object); },
    bindBufferRange(t, slot, object, offset, size) {
      calls.range++; physical.set(t, object); indexed.set(t + ':' + slot, {object, offset, size});
    },
    bindBufferBase(t, slot, object) {
      calls.base++; physical.set(t, object); indexed.set(t + ':' + slot, {object});
    },
    bufferSubData(t, offset, bytes) { storage.get(physical.get(t)).set(bytes, offset); },
  };
  const state = {buffers, boundBuffers: new Map(), indexedBufferBindings: new Map(),
    bufferSizes: new Map([[1, 512], [2, 512]]), bufferZeroFresh: new Set(),
    noteBufferWebglType() {}};
  const context = vm.createContext({window: {__gaiusWebGL: gl, __gaiusGL: state}});
  const bind = vm.runInContext(`(function(target,buffer){${body('bindBuffer')}})`, context);
  const range = vm.runInContext(`(function(target,index,buffer,offset,size){${body('bindBufferRangeJs')}})`, context);
  const base = vm.runInContext(`(function(target,index,buffer){${body('bindBufferBase')}})`, context);
  const genericIs = id => {
    assert.equal(state.boundBuffers.get(target), id, 'logical generic binding');
    assert.equal(physical.get(target), buffers.get(id) || null, 'physical generic binding');
  };

  bind(target, 1);
  range(target, 0, 2, 256, 16);
  genericIs(2);
  bind(target, 1);
  gl.bufferSubData(target, 0, new Uint8Array([0xA5]));
  assert.equal(storage.get(buffers.get(1))[0], 0xA5);
  assert.equal(storage.get(buffers.get(2))[0], 0);
  // Same indexed range after an unrelated generic bind must still restore the
  // generic target. It need not re-submit an already correct indexed range.
  const ranges = calls.range;
  range(target, 0, 2, 256, 16);
  genericIs(2);
  assert.equal(calls.range, ranges);
  const generic = calls.generic;
  range(target, 0, 2, 256, 16);
  assert.equal(calls.generic, generic, 'fully redundant bind stays cached');

  base(target, 1, 1);
  genericIs(1);
  bind(target, 2);
  const bases = calls.base;
  base(target, 1, 1);
  genericIs(1);
  assert.equal(calls.base, bases);
  const before = calls.generic;
  base(target, 1, 1);
  assert.equal(calls.generic, before);
  base(target, 1, 0);
  genericIs(0);
  assert.equal(indexed.get(target + ':1').object, null);
}
console.log('browser-opengl-indexed-binding-smoke PASS');
