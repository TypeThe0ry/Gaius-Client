#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const source = await readFile(new URL(
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
  import.meta.url,
), "utf8");

function jsBody(declaration) {
  const declarationAt = source.indexOf(declaration);
  assert.notEqual(declarationAt, -1, `missing declaration: ${declaration}`);
  const marker = 'script = """';
  const bodyAt = source.lastIndexOf(marker, declarationAt);
  const start = bodyAt + marker.length;
  const end = source.indexOf('""")', start);
  assert.ok(bodyAt >= 0 && end > start && end < declarationAt,
    `missing JSBody for ${declaration}`);
  return source.slice(start, end);
}

const detachStart = source.indexOf("window.__gaiusGL.detachShiftedIndexEntry=function(entry)");
const detachEnd = source.indexOf("window.__gaiusGL.deleteShiftedIndexEntry=function", detachStart);
assert.ok(detachStart >= 0 && detachEnd > detachStart, "missing shifted-index detach helper");
assert.ok(!source.slice(detachStart, detachEnd).includes("this.vaoEmu.forEach"),
  "base-vertex eviction still scans every VAO");
assert.ok(source.includes("this.detachShiftedIndexEntry(entry);"),
  "base-vertex eviction does not clear exact VAO fast references");

function vao({element = 0, pointer = 0, physical = null} = {}) {
  const pointers = new Map();
  if (pointer) pointers.set(0, {buffer: pointer});
  return {
    attribPointers: pointers,
    vertexBuffers: new Map(),
    attribHasBuffer: new Set(pointer ? [0] : []),
    misalignedAttribs: new Set(),
    elementArrayBuffer: element,
    elementArrayBufferObject: physical,
    actualElementArrayBuffer: physical,
    attribVersion: 1,
  };
}

const deletedObjects = [];
const object7 = {id: 7};
const object8 = {id: 8};
const shiftedObject = {id: 700};
const unrelated = Array.from({length: 256}, () => vao());
const referencedElement = vao({element: 7, physical: object7});
const referencedAttrib = vao({pointer: 7, physical: shiftedObject});
const state = {
  buffers: new Map([[7, object7], [8, object8]]),
  bufferSizes: new Map([[7, 1024], [8, 1024]]),
  bufferVersions: new Map([[7, 1], [8, 1]]),
  bufferWebglTypes: new Map(),
  bufferZeroFresh: new Set(),
  shadowCompleteRequired: new Set(),
  textureBufferInfo: new Map(),
  boundBuffers: new Map(),
  indexedBufferBindings: new Map(),
  shadowRequiredBuffers: new Set(),
  misalignedBufferRefs: new Map(),
  vaoEmu: new Map([[0, vao()], [1, referencedElement], [2, referencedAttrib]]),
  currentVaoId: 0,
  currentVaoCache: null,
  deleteBufferShadow() {},
  dropBufferDerivedCaches() {},
  setAttribBufferPresence(current, index, present) {
    if (present) current.attribHasBuffer.add(index);
    else current.attribHasBuffer.delete(index);
  },
  setAttribMisaligned(current, index, misaligned) {
    if (misaligned) current.misalignedAttribs.add(index);
    else current.misalignedAttribs.delete(index);
  },
  bumpVaoAttribVersion(current) {
    current.attribVersion++;
  },
  newVaoEmu() {
    return vao();
  },
  getVaoEmu() {
    let current = this.vaoEmu.get(this.currentVaoId | 0);
    if (!current) {
      current = this.newVaoEmu();
      this.vaoEmu.set(this.currentVaoId | 0, current);
    }
    return current;
  },
  bindPhysicalElementBuffer(current, object) {
    const next = object || null;
    if (current.actualElementArrayBuffer === next) return false;
    current.actualElementArrayBuffer = next;
    return true;
  },
};
for (let index = 0; index < unrelated.length; index++) {
  state.vaoEmu.set(index + 3, unrelated[index]);
}

const previousWindow = globalThis.window;
globalThis.window = {
  __gaiusGL: state,
  __gaiusWebGL: {
    deleteBuffer(object) {
      deletedObjects.push(object.id);
    },
  },
};
try {
  new Function(jsBody("private static native void initializeVaoBufferRefsJs();"))();
  assert.deepEqual([...state.vaoBufferRefs.get(7)].sort((a, b) => a - b), [1, 2]);
  assert.deepEqual([...state.physicalElementBufferVaoRefs.get(object7)], [1]);
  assert.deepEqual([...state.physicalElementBufferVaoRefs.get(shiftedObject)], [2]);

  const deleteBody = jsBody("private static native void deleteBufferJs(int buffer);");
  assert.ok(!deleteBody.includes("state.vaoEmu.forEach"),
    "deleteBufferJs still scans every VAO");
  new Function("buffer", deleteBody)(7);

  assert.deepEqual(deletedObjects, [7]);
  assert.equal(referencedElement.elementArrayBuffer, 0);
  assert.equal(referencedElement.actualElementArrayBuffer, null);
  assert.equal(referencedAttrib.attribPointers.has(0), false);
  assert.equal(referencedAttrib.actualElementArrayBuffer, shiftedObject,
    "deleting a logical buffer detached an unrelated physical shifted-index buffer");
  assert.equal(state.vaoBufferRefs.has(7), false);
  assert.equal(state.physicalElementBufferVaoRefs.has(object7), false);
  assert.ok(unrelated.every((entry) => entry.attribVersion === 1),
    "deleting one buffer invalidated an unrelated VAO");

  state.forgetPhysicalElementBuffer(shiftedObject);
  assert.equal(referencedAttrib.actualElementArrayBuffer, null);
  assert.equal(state.physicalElementBufferVaoRefs.has(shiftedObject), false);
} finally {
  if (previousWindow === undefined) delete globalThis.window;
  else globalThis.window = previousWindow;
}

console.log("Browser VAO reverse-reference smoke passed: 2 referenced, 256 untouched");
