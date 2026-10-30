#!/usr/bin/env node
// Node smoke for the packed telemetry publishers (v0.4.0 WP3). TeaVM's WebAssembly GC backend
// limits @JSBody natives to 32 parameters, so the wide publishers pass their numbers as one
// Float64Array with fixed index constants and unpack it in JS into the same objects as before.
// For each publisher this runs the @JSBody script in Node with a packed array built from the Java
// index constants and checks:
//   - BrowserRenderScheduler: every T_X constant lands in __gaiusChunkPipelineTelemetry.x
//     (booleans as 0/1, counters with the old "||0" default), the duration histograms and the
//     fixed fields, and the Java fill writes every index exactly once;
//   - BrowserGpuBufferPoolCache (one source; checked as each profile compiles it): every P_X
//     constant lands in the pool slot, the label crosses once per pool, close/overflow paths
//     keep their bookkeeping;
//   - BrowserOpenGL.reportMinecraftState: one call writes window.__gaiusMinecraftState with the
//     key paths and value types of the T0 schema dump (title screen and in world), the null
//     rules of the old two-stage report, no player-state stage global, and the memory footprint
//     telemetry (startup snapshots, once-a-second sampling, linear memory on WebAssembly GC);
//   - the WebAssembly GC BrowserMemory writes __gaiusMemoryTelemetry.browserMemory from 17
//     packed values.
//
//   node port/scripts/browser-packed-telemetry-smoke.mjs

import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const read = relative => readFile(new URL(relative, import.meta.url), "utf8");
// BrowserGpuBufferPoolCache is a single source (port/src/main); each profile compiles it with its
// type relocations applied (the renderpearl generation moves GpuBuffer).
const poolPath = "dev/gaius/browser/BrowserGpuBufferPoolCache.java";
const pool = sourceFile(CANONICAL_PROFILE.id, "src", poolPath)?.text;
const pool263 = sourceFile(RENDERPEARL_PROFILE.id, "src", poolPath)?.text;
assert.ok(pool && pool263, "every profile compiles BrowserGpuBufferPoolCache");
assert.equal(sourceFile(RENDERPEARL_PROFILE.id, "src", poolPath).origin, "shared",
  "the pool cache is not overridden by a source set");
const [scheduler, openGl, wasmMemory] = await Promise.all([
  read("../src/main/java/dev/gaius/browser/BrowserRenderScheduler.java"),
  read("../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java"),
  read("../overrides/libraries/lwjgl/src/target-wasmgc/java/org/lwjgl/system/BrowserMemory.java"),
]);

// The text of a `private static final String NAME = """...""";` constant of the source.
function textBlockConstant(source, name) {
  const marker = `private static final String ${name} = """`;
  const at = source.indexOf(marker);
  assert.notEqual(at, -1, `missing text block constant ${name}`);
  const start = at + marker.length;
  const end = source.indexOf('""";', start);
  assert.ok(end > start, `unterminated text block constant ${name}`);
  return source.slice(start, end);
}

// The script of an @JSBody: a text block, or text block constants joined with + (TeaVM reads the
// annotation's constant value, so both forms give the same script).
function jsBody(source, declaration) {
  const at = source.indexOf(declaration);
  assert.notEqual(at, -1, `missing JSBody declaration: ${declaration}`);
  const header = source.lastIndexOf("@JSBody(", at);
  const scriptAt = source.indexOf("script =", header);
  assert.ok(header >= 0 && scriptAt > header && scriptAt < at,
    `missing JSBody script: ${declaration}`);
  const paramsText = source.slice(header, scriptAt);
  const params = [...paramsText.matchAll(/"([A-Za-z0-9_]+)"/g)].map(match => match[1]);
  const valueAt = scriptAt + "script =".length;
  if (source.slice(valueAt).trimStart().startsWith('"""')) {
    const start = source.indexOf('"""', valueAt) + 3;
    const end = source.indexOf('""")', start);
    assert.ok(end > start && end < at, `missing JSBody script: ${declaration}`);
    return {params, script: source.slice(start, end)};
  }
  const expression = source.slice(valueAt, source.indexOf(")", valueAt));
  const names = expression.split("+").map(name => name.trim());
  assert.ok(names.every(name => /^[A-Z][A-Z0-9_]*$/.test(name)),
    `JSBody script of ${declaration} is neither a text block nor text block constants`);
  return {params, script: names.map(name => textBlockConstant(source, name)).join("")};
}

function constants(source, prefix) {
  const out = new Map();
  for (const match of source.matchAll(new RegExp(`private static final int ${prefix}_([A-Z0-9_]+) = (\\d+);`, "g"))) {
    out.set(match[1], Number(match[2]));
  }
  return out;
}

const camel = name => name.toLowerCase().replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());

function checkLayout(source, prefix, label) {
  const layout = constants(source, prefix);
  const count = layout.get("COUNT");
  assert.ok(count > 0, `${label}: missing ${prefix}_COUNT`);
  layout.delete("COUNT");
  const indices = [...layout.values()].sort((a, b) => a - b);
  assert.deepEqual(indices, Array.from({length: count}, (_, i) => i),
    `${label}: ${prefix}_* indices must be 0..${count - 1} without gaps or repeats`);
  for (const name of layout.keys()) {
    const writes = source.match(new RegExp(`\\[${prefix}_${name}\\] = `, "g")) || [];
    assert.equal(writes.length, 1, `${label}: ${prefix}_${name} must be written once`);
  }
  return {layout, count};
}

// --- BrowserRenderScheduler ------------------------------------------------------------------
{
  assert.ok(!scheduler.includes("HostLoop.isWasmGc()"),
    "render telemetry must publish on both targets (no isWasmGc skip)");
  assert.ok(!scheduler.includes("publishFrameBudgetJs"),
    "frame-budget fields are part of the packed snapshot");
  assert.match(scheduler, /publishTelemetryJs\(JsArrays\.float64\(t\)\)/);
  const {layout, count} = checkLayout(scheduler, "T", "BrowserRenderScheduler");
  const body = jsBody(scheduler, "private static native void publishTelemetryJs(");
  assert.deepEqual(body.params, ["v"]);
  const publish = new Function(...body.params, body.script);
  const booleans = new Set(["taskRunning", "highWaterActive", "lastTaskBudgetExhausted",
    "uploadBudgetExhausted", "pumpWaitingForFrame"]);
  delete globalThis.__gaiusChunkPipelineTelemetry;
  const packed = new Float64Array(count);
  for (const [name, index] of layout) packed[index] = booleans.has(camel(name)) ? 1 : index + 0.5;
  packed[layout.get("COMPLETED_TASKS")] = 3;
  packed[layout.get("LAST_TASK_MILLIS")] = 1.25;
  packed[layout.get("UPLOAD_PASSES")] = 2;
  packed[layout.get("LAST_UPLOAD_PASS_MILLIS")] = 0.5;
  publish(packed);
  const state = globalThis.__gaiusChunkPipelineTelemetry;
  for (const [name, index] of layout) {
    const key = camel(name);
    assert.ok(Object.hasOwn(state, key), `render telemetry lost field ${key}`);
    assert.equal(state[key], booleans.has(key) ? true : packed[index], `render telemetry ${key}`);
  }
  assert.equal(state.droppedTasks, 0);
  assert.equal(typeof state.updatedAt, "number");
  assert.equal(state.pumpDriver, "completion");
  assert.equal(state.taskHistogramCount, 3);
  assert.equal(state.taskHistogram[5], 3, "task histogram bucket (1.25 ms * 4)");
  assert.equal(state.uploadPassHistogram[2], 2, "upload-pass histogram bucket (0.5 ms * 4)");
  packed.fill(0);
  packed[layout.get("COMPLETED_TASKS")] = 4;
  packed[layout.get("LAST_TASK_MILLIS")] = 1.25;
  packed[layout.get("PENDING_TASKS")] = Number.NaN;
  publish(packed);
  assert.equal(state.taskHistogram[5], 4, "histogram adds only new completions");
  assert.equal(state.pendingTasks, 0, "NaN counters default to 0 as before");
  assert.equal(state.taskRunning, false);
  // The keys of the old two publishers, in their order.
  const keys = Object.keys(state).filter(key => !key.endsWith("Histogram")
    && !key.endsWith("HistogramCount"));
  assert.deepEqual(keys.slice(0, 3), ["pendingTasks", "queueCapacity", "taskRunning"]);
  assert.deepEqual(keys.slice(-3), ["meshResultsReady", "failedTasks", "pumpDriver"]);
  delete globalThis.__gaiusChunkPipelineTelemetry;
}

// --- BrowserGpuBufferPoolCache ---------------------------------------------------------------
{
  const strip = text => text.slice(text.indexOf("public final class BrowserGpuBufferPoolCache"))
    .replace(/\/\*\*[\s\S]*?\*\//g, "").replace(/com\.mojang\.[\w.]+GpuBuffer/g, "GpuBuffer");
  const telemetrySection = text => {
    const from = text.indexOf("    private void publishTelemetry(boolean closed, String reason) {");
    const to = text.indexOf("    int availableCountForTesting()");
    return text.slice(from, to);
  };
  assert.equal(telemetrySection(strip(pool263)), telemetrySection(strip(pool)),
    "the " + RENDERPEARL_PROFILE.id + " pool cache telemetry must stay identical to the canonical one");
  assert.ok(pool263.includes("import com.mojang.renderpearl.api.buffers.GpuBuffer;")
    && pool.includes("import com.mojang.blaze3d.buffers.GpuBuffer;"),
    "GpuBuffer follows the profile's relocation");
  for (const [source, label] of [[pool, CANONICAL_PROFILE.id], [pool263, RENDERPEARL_PROFILE.id]]) {
    assert.ok(!source.includes("HostLoop.isWasmGc()"), `${label}: pool telemetry skips wasm`);
    const {layout, count} = checkLayout(source, "P", `BrowserGpuBufferPoolCache ${label}`);
    const body = jsBody(source, "private static native void publishTelemetryBrowser(");
    assert.deepEqual(body.params, ["v", "label", "reason"]);
    const publish = new Function(...body.params, body.script);
    globalThis.__gaiusGLStats = {};
    const packed = new Float64Array(count);
    for (const [name, index] of layout) packed[index] = 1000 + index;
    packed[layout.get("POOL_ID")] = 7;
    packed[layout.get("OWNER_CLOSED")] = 0;
    packed[layout.get("MAX_POOLS")] = 24;
    publish(packed, "pool-label", "periodic");
    const root = globalThis.__gaiusGLStats.gpuBufferPoolCache;
    assert.equal(root.pools.length, 1);
    const slot = root.pools[0];
    assert.equal(slot.poolId, 7);
    assert.equal(slot.label, "pool-label");
    assert.equal(slot.lastPublishReason, "periodic");
    for (const [name, index] of layout) {
      const key = camel(name);
      if (key === "poolId" || key === "ownerClosed" || key === "maxPools") continue;
      assert.equal(slot[key], 1000 + index, `${label}: pool slot field ${key}`);
    }
    // Later publishes send no label (JS keeps it outside the published object) and no reason
    // when periodic.
    packed[layout.get("FRAME")] = 77;
    publish(packed, null, null);
    assert.equal(root.pools[0].label, "pool-label", `${label}: label kept across publishes`);
    assert.equal(root.pools[0].frame, 77);
    assert.equal(root.pools[0].lastPublishReason, "periodic");
    assert.ok(!Object.hasOwn(root, "labels"), `${label}: labels stay outside the published object`);
    packed[layout.get("OWNER_CLOSED")] = 1;
    publish(packed, null, null);
    assert.equal(globalThis.__gaiusGpuPoolLabels.has(7), false, `${label}: close drops the label`);
    assert.equal(root.pools.length, 0);
    assert.equal(root.closedPools, 1);
    assert.equal(root.lastClosedPoolId, 7);
    assert.equal(root.lastCloseReason, "owner-close");
    packed[layout.get("OWNER_CLOSED")] = 0;
    packed[layout.get("MAX_POOLS")] = 0;
    publish(packed, "pool-label", "periodic");
    assert.equal(root.overflowPublishes, 1);
    assert.equal(root.lastOverflowPoolId, 7);
    assert.equal(root.totalPublishes, 4);
    delete globalThis.__gaiusGpuPoolLabels;
  }
  delete globalThis.__gaiusGLStats;
}

// --- BrowserOpenGL.reportMinecraftState ---------------------------------------------------------
{
  assert.ok(!openGl.includes("stageMinecraftPlayerStateJs"), "the two-stage report is gone");
  assert.ok(!openGl.includes("__gaiusMinecraftPlayerStateStage"));
  const {layout, count} = checkLayout(openGl, "S", "BrowserOpenGL state report");
  const body = jsBody(openGl, "private static native void reportMinecraftStateJs(");
  assert.equal(body.params[0], "v");
  assert.ok(body.params.length <= 32, "state report must stay within 32 JSBody parameters");
  const report = new Function(...body.params, body.script);
  const strings = {
    playerClass: null, playerMode: null, selectedItem: null,
    screen: "net.minecraft.client.gui.screens.TitleScreen", screenTitle: "Minecraft",
    screenWidgetsJson: '[{"type":"B","text":"Singleplayer","x":1,"y":2,"width":3,"height":4,'
      + '"active":true,"visible":true,"focused":false}]',
    overlay: null, level: null,
    hitClass: null, hitType: null, hitBlockPos: null, hitDirection: null, hitBlockState: null,
    hitEntity: null,
  };
  const values = numbers => {
    const packed = new Float64Array(count);
    for (const [name, value] of Object.entries(numbers)) {
      assert.ok(layout.has(name), `unknown state index ${name}`);
      packed[layout.get(name)] = value;
    }
    return packed;
  };
  const call = (numbers, overrides = {}) => {
    const all = {...strings, ...overrides};
    report(values(numbers), ...body.params.slice(1).map(name => all[name]));
    return globalThis.window.__gaiusMinecraftState;
  };
  const shape = value => {
    const out = {};
    const walk = (node, path) => {
      if (node === null) { out[path] = "null"; return; }
      if (typeof node !== "object") { out[path] = typeof node; return; }
      if (Array.isArray(node)) {
        out[path] = "array";
        for (const item of node) walk(item, `${path}[]`);
        return;
      }
      out[path] = "object";
      for (const key of Object.keys(node)) walk(node[key], `${path}.${key}`);
    };
    walk(value, "state");
    return out;
  };
  let clock = 1000;
  globalThis.performance = {now: () => clock, memory: {usedJSHeapSize: 100, totalJSHeapSize: 200,
    jsHeapSizeLimit: 300}};
  globalThis.window = globalThis;
  delete globalThis.__gaiusMemoryTelemetry;
  delete globalThis.__gaiusWasmMemory;
  const title = call({SCREEN_WIDTH: 427, SCREEN_HEIGHT: 240, LOADED_CHUNK_COUNT: -1,
    RAW_RENDER_DISTANCE: 12, EFFECTIVE_RENDER_DISTANCE: 12, RAW_SIMULATION_DISTANCE: 12,
    SERVER_SIMULATION_DISTANCE: -1, SELECTED_SLOT: -1, PLAYER_X: Number.NaN, RUNNING: 1});
  // T0 schema dump (<work-dir>/T0/build-263-js/accept/schema), title screen.
  assert.deepEqual(shape(title), {
    "state": "object", "state.screen": "string", "state.screenTitle": "string",
    "state.screenSize": "object", "state.screenSize.width": "number",
    "state.screenSize.height": "number", "state.screenWidgets": "array",
    "state.screenWidgets[]": "object", "state.screenWidgets[].type": "string",
    "state.screenWidgets[].text": "string", "state.screenWidgets[].x": "number",
    "state.screenWidgets[].y": "number", "state.screenWidgets[].width": "number",
    "state.screenWidgets[].height": "number", "state.screenWidgets[].active": "boolean",
    "state.screenWidgets[].visible": "boolean", "state.screenWidgets[].focused": "boolean",
    "state.overlay": "null", "state.level": "null", "state.loadedChunkCount": "null",
    "state.player": "null", "state.gameMode": "null", "state.hit": "null",
    "state.worldSelection": "null", "state.clientDistance": "object",
    "state.clientDistance.rawRenderDistance": "number",
    "state.clientDistance.effectiveRenderDistance": "number",
    "state.clientDistance.rawSimulationDistance": "number",
    "state.clientDistance.serverSimulationDistance": "null",
    "state.clientDistance.sampledAtMillis": "number", "state.noRender": "boolean",
    "state.running": "boolean", "state.pause": "boolean", "state.at": "number",
  });
  assert.deepEqual(Object.keys(title), ["screen", "screenTitle", "screenSize", "screenWidgets",
    "overlay", "level", "loadedChunkCount", "player", "gameMode", "hit", "worldSelection",
    "clientDistance", "noRender", "running", "pause", "at"], "state key order");
  assert.equal(title.running, true);
  assert.equal(title.noRender, false);
  assert.equal(globalThis.__gaiusMinecraftPlayerStateStage, undefined);
  const footprint = globalThis.__gaiusMemoryTelemetry.footprint;
  assert.equal(footprint.runtime, "js");
  assert.equal(footprint.usedJSHeapSize, 100);
  assert.equal(footprint.linearMemoryBytes, 0);
  assert.equal(footprint.footprintBytes, 100);
  assert.equal(footprint.browserMemoryLiveBytes, null);
  assert.equal(footprint.firstScreen.screen, strings.screen);
  assert.equal(footprint.firstTitleScreen.atMillis, 1000);
  assert.equal(footprint.samples, 1);

  const world = call({PLAYER_X: 0.5, PLAYER_Y: 79, PLAYER_Z: 0.5, PLAYER_YAW: 0, PLAYER_PITCH: 0,
    PLAYER_COLLISION_KNOWN: 1, PLAYER_COLLISION_FREE: 1, SELECTED_SLOT: 0, SELECTED_COUNT: 0,
    SCREEN_WIDTH: -1, SCREEN_HEIGHT: -1, LOADED_CHUNK_COUNT: 441, RAW_RENDER_DISTANCE: 12,
    EFFECTIVE_RENDER_DISTANCE: 12, RAW_SIMULATION_DISTANCE: 12, SERVER_SIMULATION_DISTANCE: 12,
    RUNNING: 1}, {
    playerClass: "net.minecraft.client.player.LocalPlayer", playerMode: "SURVIVAL",
    screen: null, screenTitle: null, screenWidgetsJson: "[]",
    level: "net.minecraft.client.multiplayer.ClientLevel",
    hitClass: "net.minecraft.world.phys.BlockHitResult", hitType: "BLOCK", hitBlockPos: "1, 2, 3",
    hitDirection: "up", hitBlockState: "Block{minecraft:grass_block}[snowy=false]",
  });
  const worldShape = shape(world);
  for (const [path, type] of Object.entries({
    "state.player": "object", "state.player.className": "string",
    "state.player.collisionFree": "boolean", "state.player.gameMode": "string",
    "state.player.pitch": "number", "state.player.selectedItem": "object",
    "state.player.selectedItem.count": "number", "state.player.selectedItem.item": "null",
    "state.player.selectedItem.slot": "number", "state.player.x": "number",
    "state.player.y": "number", "state.player.yaw": "number", "state.player.z": "number",
    "state.hit.entity": "null", "state.hit.blockPos": "string", "state.level": "string",
    "state.loadedChunkCount": "number", "state.screen": "null", "state.screenSize": "null",
    "state.clientDistance.serverSimulationDistance": "number", "state.gameMode": "string",
  })) {
    assert.equal(worldShape[path], type, `world state ${path}`);
  }
  assert.deepEqual(world.player, {className: "net.minecraft.client.player.LocalPlayer", x: 0.5,
    y: 79, z: 0.5, yaw: 0, pitch: 0, collisionFree: true, gameMode: "SURVIVAL",
    selectedItem: {slot: 0, item: null, count: 0}});
  const unknown = call({PLAYER_X: Number.NaN, PLAYER_YAW: Number.POSITIVE_INFINITY,
    PLAYER_COLLISION_KNOWN: 0, PLAYER_COLLISION_FREE: 1, SELECTED_SLOT: -1}, {
    playerClass: "P", screen: null});
  assert.equal(unknown.player.x, null);
  assert.equal(unknown.player.yaw, null);
  assert.equal(unknown.player.collisionFree, null);
  assert.equal(unknown.player.selectedItem, null);
  assert.equal(footprint.samples, 1, "footprint samples at most once a second");
  clock = 2500;
  globalThis.__gaiusWasmMemory = {buffer: {byteLength: 65536 * 3}};
  globalThis.__gaiusMemoryTelemetry.browserMemory = {liveBytes: 10, peakLiveBytes: 20};
  call({});
  assert.equal(footprint.samples, 2);
  assert.equal(footprint.runtime, "wasm");
  assert.equal(footprint.linearMemoryBytes, 196608);
  assert.equal(footprint.footprintBytes, 100 + 196608);
  assert.equal(footprint.peakLinearMemoryBytes, 196608);
  assert.equal(footprint.browserMemoryLiveBytes, 10);
  assert.equal(footprint.browserMemoryPeakBytes, 20);
  assert.equal(footprint.firstTitleScreen.linearMemoryBytes, 0, "startup snapshot is kept");
  delete globalThis.__gaiusWasmMemory;
  delete globalThis.__gaiusMemoryTelemetry;
  delete globalThis.__gaiusMinecraftState;
  delete globalThis.performance;
}

// --- BrowserMemory (WebAssembly GC) -------------------------------------------------------------
{
  const body = jsBody(wasmMemory, "private static native void publishTelemetryBrowser(");
  assert.deepEqual(body.params, ["v"]);
  const publish = new Function(...body.params, body.script);
  // 23 scalars, 5 owner pairs and 8 size-bucket pairs.
  const packed = Float64Array.from({length: 49}, (_, i) => i * 10 + 1);
  publish(packed);
  const memory = globalThis.__gaiusMemoryTelemetry.browserMemory;
  assert.deepEqual(Object.keys(memory), ["backend", "liveRegions", "liveBytes",
    "associatedBuffers", "peakLiveRegions", "peakLiveBytes", "peakAssociatedBuffers",
    "allocations", "frees", "registrations", "reallocations", "collectedAssociations",
    "addressLookupMisses", "maxLiveBytes", "allocationFailures", "maxTemporaryBytes",
    "peakTemporaryBytes", "temporaryAllocationFailures", "reservedBytes", "peakReservedBytes",
    "pooledBytes", "reuseHits", "inPlaceReallocations", "poolLimitBytes", "owners", "sizeBuckets"]);
  assert.equal(memory.backend, "linear-memory");
  assert.equal(memory.liveBytes, 11);
  assert.equal(memory.peakLiveBytes, 41);
  assert.equal(memory.temporaryAllocationFailures, 161);
  assert.equal(memory.reservedBytes, 171);
  assert.equal(memory.poolLimitBytes, 221);
  assert.deepEqual(Object.keys(memory.owners),
    ["native", "heap-buffer-copy", "aligned", "decoded-image", "mapped-staging"]);
  assert.deepEqual(memory.owners.native, {liveBytes: 231, peakBytes: 241});
  assert.deepEqual(memory.owners["mapped-staging"], {liveBytes: 311, peakBytes: 321});
  assert.equal(memory.sizeBuckets.length, 8);
  assert.deepEqual(memory.sizeBuckets[0], {liveBytes: 331, count: 341});
  assert.deepEqual(memory.sizeBuckets[7], {liveBytes: 471, count: 481});
  assert.match(wasmMemory, /TELEMETRY_INTERVAL_MILLIS = 250L/);
  delete globalThis.__gaiusMemoryTelemetry;
}

console.log("Packed telemetry smoke passed");
