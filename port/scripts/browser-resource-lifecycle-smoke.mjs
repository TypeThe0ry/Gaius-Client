#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const sourcePath = new URL(
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
  import.meta.url,
);
const source = readFileSync(sourcePath, "utf8");

function jsBody(method) {
  const escaped = method.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const declaration = new RegExp(
    `(?:private|public)\\s+static\\s+native\\s+[\\w<>\\[\\]]+\\s+${escaped}\\s*\\(`,
  ).exec(source);
  assert.ok(declaration, `missing native declaration for ${method}`);
  const annotationStart = source.lastIndexOf("@JSBody", declaration.index);
  const marker = 'script = """';
  const bodyStart = source.indexOf(marker, annotationStart);
  const contentStart = bodyStart + marker.length;
  const bodyEnd = source.indexOf('"""', contentStart);
  assert.ok(
    annotationStart >= 0 && bodyStart >= 0 && bodyEnd >= 0 && bodyEnd < declaration.index,
    `missing JS body for ${method}`,
  );
  return source.slice(contentStart, bodyEnd);
}

function compile(method, parameters) {
  return Function(...parameters, jsBody(method));
}

const calls = {
  deleteBuffer: 0,
  deleteProgram: 0,
  deleteTexture: 0,
  unbindProgram: 0,
};
const gl = {
  deleteBuffer() {
    calls.deleteBuffer++;
  },
  deleteProgram() {
    calls.deleteProgram++;
  },
  deleteTexture() {
    calls.deleteTexture++;
  },
  useProgram(program) {
    assert.equal(program, null, "deleting the current program did not physically unbind it");
    calls.unbindProgram++;
  },
};

const state = {
  alignedAttribCache: new Map(),
  alignedAttribCacheKeys: new Map(),
  boundBuffers: new Map(),
  bufferBytes: new Map(),
  bufferShadowTouch: new Map(),
  bufferWebglTypes: new Map(),
  bufferZeroFresh: new Set(),
  bufferSizes: new Map(),
  bufferVersions: new Map(),
  buffers: new Map(),
  currentProgram: 0,
  framebufferColorTextureMisses: new Set(),
  framebufferColorTextures: new Map(),
  indexedBufferBindings: new Map(),
  misalignedBufferRefs: new Map(),
  programAttribs: new Map(),
  programs: new Map(),
  shadowCompleteRequired: new Set(),
  shadowRequiredBuffers: new Set(),
  shiftedIndexCache: new Map(),
  shiftedIndexCacheKeys: new Map(),
  textureBindings: new Map(),
  textureBufferDefaults: new Map(),
  textureBufferInfo: new Map(),
  textureInfo: new Map(),
  textureParameters: new Map(),
  textures: new Map(),
  vaoEmu: new Map(),
  bumpDrawProgramGeneration() {},
  bumpVaoAttribVersion() {},
  clearProgramUniforms() {},
  deleteBufferShadow(buffer) {
    this.bufferBytes.delete(buffer);
    this.bufferShadowTouch.delete(buffer);
  },
  dropBufferDerivedCaches(buffer) {
    this.alignedAttribCache.delete(buffer);
    this.alignedAttribCacheKeys.delete(buffer);
    this.shiftedIndexCache.delete(buffer);
    this.shiftedIndexCacheKeys.delete(buffer);
  },
  forgetPhysicalElementBuffer() {},
  invalidateGuiItemAtlasBlitCache() {},
  refreshFramebufferOffscreen512(framebuffer) {
    this.framebufferColorTextureMisses.delete(framebuffer);
  },
  setAttribBufferPresence() {},
  setAttribMisaligned() {},
};

globalThis.window = {
  __gaiusGL: state,
  __gaiusGLStats: { textureInfo: {} },
  __gaiusWebGL: gl,
};

const deleteBuffer = compile("deleteBufferJs", ["buffer"]);
const deleteTexture = compile("deleteTexture", ["texture"]);
const deleteProgram = compile("deleteProgramJs", ["program"]);

const lifecycleIterations = 50_000;
for (let index = 1; index <= lifecycleIterations; index++) {
  const object = { id: index };
  state.buffers.set(index, object);
  state.bufferSizes.set(index, 4096);
  state.bufferBytes.set(index, new Uint8Array(1));
  state.bufferShadowTouch.set(index, index);
  state.bufferVersions.set(index, 3);
  state.shadowRequiredBuffers.add(index);
  state.misalignedBufferRefs.set(index, 1);
  state.alignedAttribCache.set(index, object);
  state.alignedAttribCacheKeys.set(index, new Set([index]));
  state.shiftedIndexCache.set(index, object);
  state.shiftedIndexCacheKeys.set(index, new Set([index]));
  state.boundBuffers.set(index, index);
  state.indexedBufferBindings.set(index, { buffer: index });
  state.textureBufferInfo.set(index, { buffer: index });
  deleteBuffer(index);

  state.textures.set(index, object);
  state.textureInfo.set(index, {});
  state.textureBufferDefaults.set(index, {});
  state.textureParameters.set(index, new Map());
  state.textureBufferInfo.set(index, { buffer: 0 });
  state.textureBindings.set(index, index);
  state.framebufferColorTextures.set(index, index);
  window.__gaiusGLStats.textureInfo[String(index)] = {};
  deleteTexture(index);

  state.programs.set(index, object);
  state.programAttribs.set(index, new Map());
  state.currentProgram = index;
  deleteProgram(index);
}

for (const [name, registry] of [
  ["buffers", state.buffers],
  ["bufferSizes", state.bufferSizes],
  ["bufferBytes", state.bufferBytes],
  ["bufferShadowTouch", state.bufferShadowTouch],
  ["bufferVersions", state.bufferVersions],
  ["shadowRequiredBuffers", state.shadowRequiredBuffers],
  ["misalignedBufferRefs", state.misalignedBufferRefs],
  ["alignedAttribCache", state.alignedAttribCache],
  ["alignedAttribCacheKeys", state.alignedAttribCacheKeys],
  ["shiftedIndexCache", state.shiftedIndexCache],
  ["shiftedIndexCacheKeys", state.shiftedIndexCacheKeys],
  ["indexedBufferBindings", state.indexedBufferBindings],
  ["textureBufferInfo", state.textureBufferInfo],
  ["textures", state.textures],
  ["textureInfo", state.textureInfo],
  ["textureBufferDefaults", state.textureBufferDefaults],
  ["textureParameters", state.textureParameters],
  ["textureBindings", state.textureBindings],
  ["framebufferColorTextures", state.framebufferColorTextures],
  ["framebufferColorTextureMisses", state.framebufferColorTextureMisses],
  ["programs", state.programs],
  ["programAttribs", state.programAttribs],
]) {
  assert.equal(registry.size, 0, `${name} did not return to baseline`);
}
assert.equal(Object.keys(window.__gaiusGLStats.textureInfo).length, 0);
assert.equal(state.currentProgram, 0);
assert.equal(calls.deleteBuffer, lifecycleIterations);
assert.equal(calls.deleteTexture, lifecycleIterations);
assert.equal(calls.deleteProgram, lifecycleIterations);
assert.equal(calls.unbindProgram, lifecycleIterations);

const noteMappedBufferMap = compile("noteMappedBufferMapJs", ["bytes", "count"]);
const noteMappedNamedBufferMap = compile(
  "noteMappedNamedBufferMapJs", ["bytes", "count"],
);
const noteMappedBufferFlush = compile("noteMappedBufferFlushJs", ["bytes"]);
const noteMappedNamedBufferFlush = compile("noteMappedNamedBufferFlushJs", ["bytes"]);
const noteMappedBufferUnmap = compile(
  "noteMappedBufferUnmapJs", ["uploaded", "bytes", "count"],
);
const noteMappedNamedBufferUnmap = compile(
  "noteMappedNamedBufferUnmapJs", ["uploaded", "bytes", "count"],
);
const noteMappedBufferForcedRelease = compile(
  "noteMappedBufferForcedReleaseJs", ["released", "bytes", "count"],
);
const noteMappableRingCurrentBuffer = compile("noteMappableRingCurrentBuffer", []);
const noteMappableRingAwaitResult = compile("noteMappableRingAwaitResult", ["ready"]);

const mappedStats = {};
window.__gaiusGLStats = mappedStats;
noteMappedBufferMap(64, 1);
noteMappedNamedBufferMap(128, 2);
noteMappedBufferFlush(16);
noteMappedNamedBufferFlush(32);
noteMappedBufferUnmap(true, 64, 1);
noteMappedNamedBufferUnmap(false, 0, 0);
noteMappedBufferMap(256, 1);
noteMappedNamedBufferMap(512, 2);
noteMappedBufferForcedRelease(2, 768, 0);

assert.equal(mappedStats.mappedBufferMapCalls, 2);
assert.equal(mappedStats.mappedBufferMapBytes, 320);
assert.equal(mappedStats.mappedNamedBufferMapCalls, 2);
assert.equal(mappedStats.mappedNamedBufferMapBytes, 640);
assert.equal(mappedStats.mappedBufferFlushCalls, 1);
assert.equal(mappedStats.mappedBufferFlushBytes, 16);
assert.equal(mappedStats.mappedNamedBufferFlushCalls, 1);
assert.equal(mappedStats.mappedNamedBufferFlushBytes, 32);
assert.equal(mappedStats.mappedBufferUnmapCalls, 1);
assert.equal(mappedStats.mappedBufferUnmapUploadCalls, 1);
assert.equal(mappedStats.mappedBufferUnmapUploadBytes, 64);
assert.equal(mappedStats.mappedNamedBufferUnmapCalls, 1);
assert.equal(mappedStats.mappedNamedBufferUnmapUploadCalls ?? 0, 0,
  "explicitly flushed named mapping uploaded again during unmap");
assert.equal(mappedStats.mappedNamedBufferUnmapUploadBytes ?? 0, 0,
  "explicitly flushed named mapping reported unmap-upload bytes");
assert.equal(mappedStats.mappedBufferForcedReleases, 2);
assert.equal(mappedStats.mappedBufferForcedReleaseBytes, 768);
assert.equal(mappedStats.mappedBufferRegions, 0);
assert.equal(mappedStats.mappedBufferPeakRegions, 2);
const mappedAllocations = mappedStats.mappedBufferMapCalls
  + mappedStats.mappedNamedBufferMapCalls;
const mappedReleases = mappedStats.mappedBufferUnmapCalls
  + mappedStats.mappedNamedBufferUnmapCalls
  + mappedStats.mappedBufferForcedReleases;
assert.equal(mappedAllocations, mappedReleases,
  "mapped-buffer telemetry lifecycle did not balance");
assert.equal(
  mappedStats.mappedBufferMapBytes + mappedStats.mappedNamedBufferMapBytes,
  64 + 128 + mappedStats.mappedBufferForcedReleaseBytes,
  "mapped-buffer telemetry release-byte model did not balance",
);

noteMappableRingCurrentBuffer();
noteMappableRingAwaitResult(true);
noteMappableRingCurrentBuffer();
noteMappableRingAwaitResult(false);
noteMappableRingCurrentBuffer();
noteMappableRingAwaitResult(true);
assert.equal(mappedStats.mappableRingCurrentBufferCalls, 3);
assert.equal(mappedStats.mappableRingFenceChecks, 3);
assert.equal(mappedStats.mappableRingFenceReady, 2);
assert.equal(mappedStats.mappableRingFencePending, 1);
const mappableRingObserved = Object.freeze({
  currentCalls: mappedStats.mappableRingCurrentBufferCalls,
  checks: mappedStats.mappableRingFenceChecks,
  ready: mappedStats.mappableRingFenceReady,
  pending: mappedStats.mappableRingFencePending,
});

const callLimit = 2_147_483_647;
const byteLimit = Number.MAX_SAFE_INTEGER;
mappedStats.mappedBufferMapCalls = callLimit;
mappedStats.mappedBufferMapBytes = byteLimit;
noteMappedBufferMap(byteLimit, 1);
assert.equal(mappedStats.mappedBufferMapCalls, callLimit);
assert.equal(mappedStats.mappedBufferMapBytes, byteLimit);
mappedStats.mappableRingCurrentBufferCalls = callLimit;
mappedStats.mappableRingFenceChecks = callLimit;
mappedStats.mappableRingFencePending = callLimit;
noteMappableRingCurrentBuffer();
noteMappableRingAwaitResult(false);
assert.equal(mappedStats.mappableRingCurrentBufferCalls, callLimit);
assert.equal(mappedStats.mappableRingFenceChecks, callLimit);
assert.equal(mappedStats.mappableRingFencePending, callLimit);

for (const method of [
  "noteMappedBufferMapJs",
  "noteMappedNamedBufferMapJs",
  "noteMappedBufferFlushJs",
  "noteMappedNamedBufferFlushJs",
  "noteMappedBufferUnmapJs",
  "noteMappedNamedBufferUnmapJs",
  "noteMappedBufferForcedReleaseJs",
  "noteMappableRingCurrentBuffer",
  "noteMappableRingAwaitResult",
]) {
  const body = jsBody(method);
  assert.doesNotMatch(body,
    /\b(?:new\s+(?:Array|Map|Set)|push\s*\(|setTimeout|setInterval|requestAnimationFrame|reason)\b/,
    `${method} introduced unbounded or asynchronous telemetry state`);
  assert.match(body, /(?:2147483647|9007199254740991)/,
    `${method} is missing a scalar saturation bound`);
}

const deleteBufferBody = jsBody("deleteBufferJs");
assert.doesNotMatch(
  deleteBufferBody,
  /bumpBufferVersion\s*\(/,
  "deleteBuffer reinserted a deleted buffer version",
);
assert.match(source, /releaseMappedBuffer\(buffer\);\s*deleteBufferJs\(buffer\);/s);
assert.match(source, /MAPPED_BUFFERS\.containsKey\(target\)/);
assert.match(source, /ensureBufferNotMapped\(logicalBuffer\);/);
assert.match(source, /ensureBufferNotMapped\(buffer\);/);
assert.match(source, /finally\s*\{\s*releaseMappedStorage\(mapped\);/s);
assert.match(source, /private static void releaseMappedStorage\(MappedBuffer mapped\)[^]*?MemoryUtil\.memFree\(mapped\.buffer\(\)\);/);
assert.match(source, /MAP_WRITE_BIT\s*=\s*0x0002/);
assert.match(source, /MAP_FLUSH_EXPLICIT_BIT\s*=\s*0x0010/);
assert.match(source,
  /return \(access & MAP_WRITE_BIT\) != 0\s*&& \(access & MAP_FLUSH_EXPLICIT_BIT\) == 0;/);
assert.equal(source.match(/boolean uploadOnUnmap = mapped\.uploadOnUnmap\(\);/g)?.length, 2,
  "target and named mapped buffers do not capture explicit-flush ownership");
assert.equal(source.match(/if \(uploadOnUnmap\)/g)?.length, 2,
  "target and named mapped buffers do not share explicit-flush upload behavior");
assert.match(source,
  /ByteBuffer slice = copy\.slice\(\)\.order\(buffer\.order\(\)\);\s*return Int8Array\.fromJavaBuffer\(slice\);/s,
  "mapped-buffer sub-range uploads must export a true sliced ByteBuffer view");
assert.match(source, /stats\.mappedBufferRegions=count\|0;/);
assert.match(source,
  /noteMappedBufferUnmapJs\(\s*uploadOnUnmap, \(double\) \(uploadOnUnmap \? mappedBytes : 0\),/s);
assert.match(source,
  /noteMappedNamedBufferUnmapJs\(\s*uploadOnUnmap, \(double\) \(uploadOnUnmap \? mappedBytes : 0\),/s);
assert.match(source,
  /noteMappedBufferForcedReleaseJs\(\s*released, \(double\) releasedBytes,/s);
assert.match(source,
  /noteMappedBufferForcedReleaseJs\(\s*staleMappings\.size\(\), \(double\) releasedBytes, 0\);/s);

console.log("Browser resource lifecycle smoke passed");
console.log(JSON.stringify({
  lifecycleIterations,
  deleted: {
    buffers: calls.deleteBuffer,
    textures: calls.deleteTexture,
    programs: calls.deleteProgram,
  },
  physicalProgramUnbinds: calls.unbindProgram,
  registriesAtBaseline: true,
  mappedRegionGuardsVerified: true,
  mappedSubrangeViewVerified: true,
  mappedTelemetry: {
    allocations: mappedAllocations,
    releases: mappedReleases,
    peakLive: mappedStats.mappedBufferPeakRegions,
    targetFlushBytes: mappedStats.mappedBufferFlushBytes,
    namedFlushBytes: mappedStats.mappedNamedBufferFlushBytes,
    targetUnmapUploadBytes: mappedStats.mappedBufferUnmapUploadBytes,
    namedUnmapUploadBytes: mappedStats.mappedNamedBufferUnmapUploadBytes ?? 0,
    forcedReleaseBytes: mappedStats.mappedBufferForcedReleaseBytes,
  },
  mappableRing: mappableRingObserved,
}, null, 2));
