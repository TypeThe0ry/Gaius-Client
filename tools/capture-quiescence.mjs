import {createHash, randomUUID} from "node:crypto";
import {compareDrawInputs} from "./compare-draw-inputs.mjs";
import {QUIESCENCE_REQUIREMENTS} from "./stage-capture-quiescence.mjs";

export {QUIESCENCE_REQUIREMENTS};

// Fresh vanilla-state evidence for a frozen capture. The producer is staged into the served
// client (stage-capture-quiescence.mjs); this module only consumes it. A capture is allowed
// only when the client reports every chunk the server tracks for the view as loaded and lit,
// every client light, dirty-section, compile, upload and mesh-kernel queue as empty, the
// render-clock pins as active, and the world packets the integrated server sends (chunks,
// light, block changes) have stopped arriving for the whole stable window.

export const CAPTURE_CAPABILITIES_EXPRESSION = `(()=>{
const observer=globalThis.__gaiusCaptureQuiescence;
const selection=globalThis.__gaiusRuntimeSelection;
return {schema:2,runtime:selection?.selected||globalThis.__gaiusBoot?.runtime||null,
backend:globalThis.__gaiusGpuBackend||null,
observer:observer?{schema:observer.schema,source:observer.source,
capabilities:observer.capabilities,snapshot:typeof observer.snapshot==='function'}:null,
drawCapture:typeof globalThis.__gaiusDrawCapture?.frame==='function',
wasmExports:globalThis.__gaiusWasm?.instance?Object.keys(globalThis.__gaiusWasm.instance.exports):[],
legacy:{audit:!!globalThis.__gaiusSectionAudit,pipeline:!!globalThis.__gaiusChunkPipelineTelemetry}};
})()`;

export const CLIENT_COUNTERS = Object.freeze(["lightPending", "dirtySections", "uncompiledVisible",
  "compileQueued", "sectionWork", "uploadQueued", "meshPending"]);
export const NETWORK_PACKETS = Object.freeze(["chunk", "light", "block", "sectionBlocks", "forget", "biomes", "blockEntity"]);

export function captureCapabilityFailures(value, {requireWasm = false} = {}) {
  const failures = [];
  if (requireWasm && (value?.runtime !== "wasm" || !value.wasmExports?.length)) failures.push("actual instantiated client runtime is not wasm");
  if (!value?.drawCapture) failures.push("present-bounded logical draw capture unavailable");
  const observer = value?.observer;
  if (observer?.schema !== 2 || observer.source !== "vanilla" || !observer.snapshot) {
    failures.push("fresh vanilla quiescence observer unavailable");
  }
  for (const name of QUIESCENCE_REQUIREMENTS) {
    if (!Array.isArray(observer?.capabilities) || !observer.capabilities.includes(name)) {
      failures.push("unobservable " + name);
    }
  }
  return failures;
}

/** Chunk bounds scanned for a view: the render distance plus the two-chunk tracking border. */
export function captureChunkBounds(camera, radius) {
  if (!Array.isArray(camera) || camera.length !== 5 || !camera.every(Number.isFinite)
      || !Number.isInteger(radius) || radius < 2 || radius > 32) {
    throw new Error("quiescence requires a finite camera and bounded render distance");
  }
  const chunkX = Math.floor(camera[0] / 16), chunkZ = Math.floor(camera[2] / 16), reach = radius + 2;
  return {minX: chunkX - reach, maxX: chunkX + reach, minZ: chunkZ - reach, maxZ: chunkZ + reach};
}

export function captureRequest(camera, radius) {
  return {requestId: randomUUID(), bounds: captureChunkBounds(camera, radius),
    view: {centerX: Math.floor(camera[0] / 16), centerZ: Math.floor(camera[2] / 16), distance: radius}};
}

const sha256 = value => createHash("sha256").update(JSON.stringify(value)).digest("hex");

/** Bounded per-observation evidence; the full chunk list is kept once per receipt. */
export function summarizeSnapshot(snapshot) {
  const client = snapshot?.client || {};
  const chunks = Array.isArray(client.loadedChunks)
    ? client.loadedChunks.map(position => position.join(",")).sort() : null;
  return {requestId: snapshot?.requestId ?? null, dimension: snapshot?.dimension ?? null,
    client: client.requestId === undefined ? null : {requestId: client.requestId, sample: client.sample,
      bounds: client.bounds, loadedCount: chunks?.length ?? null, loadedSha256: chunks ? sha256(chunks) : null,
      expectedMissing: client.expectedMissing?.length ?? null, unexpected: client.unexpected?.length ?? null,
      trackedSections: client.trackedSections, visibleSections: client.visibleSections,
      ...Object.fromEntries(CLIENT_COUNTERS.map(name => [name, client[name]]))},
    network: snapshot?.network ? {requestId: snapshot.network.requestId, sample: snapshot.network.sample,
      packets: Object.fromEntries(NETWORK_PACKETS.map(name => [name, snapshot.network.packets?.[name] || 0]))} : null,
    pins: snapshot?.pins ?? null};
}

export function quiescenceFailures(summary, request) {
  if (summary?.requestId !== request.requestId || typeof summary.dimension !== "string" || !summary.dimension) {
    return ["missing or stale vanilla snapshot"];
  }
  const failures = [];
  const client = summary.client, network = summary.network;
  if (!client || client.requestId !== request.requestId
      || Object.keys(request.bounds).some(name => client.bounds?.[name] !== request.bounds[name])) {
    failures.push("client observation is missing or belongs to another view");
  } else {
    if (!Number.isSafeInteger(client.sample) || client.sample < 1) failures.push("client sample is unknown");
    if (!Number.isSafeInteger(client.loadedCount) || client.loadedCount < 1) failures.push("client chunks unknown");
    if (client.expectedMissing !== 0) failures.push("client tracked chunks incomplete");
    if (!Number.isSafeInteger(client.trackedSections) || client.trackedSections < 1) failures.push("client sections unknown");
    for (const name of CLIENT_COUNTERS) {
      if (!Number.isSafeInteger(client[name]) || client[name] !== 0) failures.push("client." + name + " is busy or unknown");
    }
  }
  if (!network || network.requestId !== request.requestId || !Number.isSafeInteger(network.sample)) {
    failures.push("network observation is missing");
  }
  if (summary.pins?.lightFlicker !== true || !(summary.pins.lightFlickerTicks > 0)
      || !Number.isInteger(summary.pins.gameTime) || !(summary.pins.gameTimeFrames > 0)) {
    failures.push("render clock pins inactive");
  }
  return failures;
}

/**
 * The lightmap is re-rendered into its texture on every client tick, even while the level is
 * frozen, so whether a presented frame contains that pass depends only on frame timing. The pass
 * is removed from the ordered world draws; its result is still compared through the sampled
 * Lightmap texture (Sampler2), which is read back in every frame.
 */
export function withoutLightmapUpdates(frame) {
  if (!Array.isArray(frame?.draws)) return frame;
  const draws = frame.draws.filter(draw => draw.pipeline !== "minecraft:pipeline/lightmap");
  return {...frame, draws, lightmapUpdates: frame.draws.length - draws.length};
}

export function drawInputFingerprint(frame) {
  if (frame?.schema !== 2 || frame.boundary !== "surface-present"
      || frame.normalization !== "referenced-index-window"
      || !Array.isArray(frame.draws) || !Array.isArray(frame.failures)
      || frame.readTextures !== true || !Number.isSafeInteger(frame.textureReadbacks) || frame.textureReadbacks < 1
      || !Number.isSafeInteger(frame.beginPresent) || frame.beginPresent < 1
      || frame.endPresent !== frame.beginPresent + 1) {
    throw new Error("missing complete present-bounded draw frame");
  }
  for (const draw of frame.draws) {
    if (typeof draw.pipeline !== "string" || !Array.isArray(draw.args)
        || !Array.isArray(draw.formats) || !Array.isArray(draw.vertices)
        || !Array.isArray(draw.uniforms)) throw new Error("incomplete logical draw inputs");
    for (const buffer of [...draw.vertices, draw.indices, ...draw.uniforms.filter(uniform => uniform.kind !== "texture")]) {
      if (buffer && (!Number.isSafeInteger(buffer.length) || buffer.length < 0
          || !/^[a-f0-9]{64}$/.test(buffer.sha256 || ""))) throw new Error("verified buffer hash unavailable");
    }
  }
  const check = compareDrawInputs(frame, frame);
  if (check.verdict !== "PASS") {
    throw new Error("unknown or failed draw inputs: " + JSON.stringify({unknown: check.unknown.slice(0, 8),
      errors: check.errors.slice(0, 8), draws: frame.draws.length}));
  }
  if (!frame.draws.some(draw => draw.pipeline.includes("terrain") && draw.vertices.some(buffer => buffer?.length > 0))) {
    throw new Error("terrain mesh inputs unavailable");
  }
  const lightmaps = frame.draws.flatMap(draw => draw.uniforms)
    .filter(uniform => uniform.kind === "texture" && uniform.label === "Lightmap");
  if (!lightmaps.length || lightmaps.some(texture => !texture.uploads?.length
      || texture.uploads.some(mip => !/^[a-f0-9]{64}$/.test(mip.sha256 || "")
        || !Number.isInteger(mip.width) || mip.width < 1 || !Number.isInteger(mip.height) || mip.height < 1
        || mip.unknown || mip.covered !== mip.width * mip.height))) throw new Error("verified lightmap mips unavailable");
  return createHash("sha256").update(JSON.stringify(check.drawHashes.map(draw => draw.referenceSha256))).digest("hex");
}

export async function sampleCaptureQuiescence(evaluate, camera, radius = 8) {
  const request = captureRequest(camera, radius);
  const snapshot = await evaluate("globalThis.__gaiusCaptureQuiescence.snapshot(" + JSON.stringify(request) + ")");
  const summary = summarizeSnapshot(snapshot);
  return {request, summary, failures: quiescenceFailures(summary, request), loadedChunks: snapshot?.client?.loadedChunks ?? null};
}

const samePackets = (left, right) => JSON.stringify(left?.network?.packets) === JSON.stringify(right?.network?.packets);

/**
 * Waits until consecutive fresh observations are all idle, see the same loaded chunks and the
 * same packet counters for at least minStableMs, and (with drawFrames) bracket the same ordered
 * draw fingerprint on distinct presented frames. Any busy, stale or changed observation restarts
 * the streak; nothing is cleared or skipped to get there.
 */
export async function waitForCaptureQuiescence({evaluate, sleep, camera, radius = 8,
  stableFrames = 4, minStableMs = 0, intervalMs = 250, timeoutMs = 60000, requireWasm = false, drawFrames = true,
  onProgress = async () => {}, onFrameError = async () => {}, now = Date.now}) {
  if (!Number.isInteger(stableFrames) || stableFrames < 2 || stableFrames > 120
      || !Number.isFinite(timeoutMs) || timeoutMs <= 0 || !Number.isFinite(minStableMs) || minStableMs < 0) {
    throw new Error("invalid quiescence limits");
  }
  const capabilities = await evaluate(CAPTURE_CAPABILITIES_EXPRESSION);
  const missing = captureCapabilityFailures(capabilities, {requireWasm});
  if (missing.length) throw new Error("capture blocked: " + missing.join("; "));
  const started = now(), history = [];
  let consecutive = 0, fingerprint = null, lastFrame = null, lastPresent = 0, streakStart = null, streakBase = null, loadedChunks = null;
  const samples = {client: 0, network: 0};
  const freshSample = async () => {
    const observation = await sampleCaptureQuiescence(evaluate, camera, radius);
    for (const realm of ["client", "network"]) {
      const sample = observation.summary[realm]?.sample;
      if (!Number.isSafeInteger(sample) || sample <= samples[realm]) observation.failures.push(realm + " sample did not advance");
      else samples[realm] = sample;
    }
    return observation;
  };
  while (now() - started < timeoutMs) {
    const before = await freshSample();
    let after = null, frame = null, nextHash = null;
    const failures = [...before.failures];
    if (!failures.length && drawFrames) {
      frame = withoutLightmapUpdates(await evaluate("globalThis.__gaiusDrawCapture.frame()"));
      try { nextHash = drawInputFingerprint(frame); } catch (error) { await onFrameError(frame, error); throw error; }
      if (frame.beginPresent <= lastPresent) throw new Error("draw capture present sequence did not advance");
      lastPresent = frame.endPresent;
      after = await freshSample(); failures.push(...after.failures);
      if (!failures.length && !samePackets(before.summary, after.summary)) failures.push("world packets arrived during the frame");
    }
    if (!failures.length && streakBase && (!samePackets(streakBase, before.summary)
        || streakBase.client.loadedSha256 !== before.summary.client.loadedSha256)) failures.push("world packets or loaded chunks changed");
    if (failures.length) { consecutive = 0; fingerprint = null; streakStart = null; streakBase = null; }
    else {
      if (!drawFrames || nextHash === fingerprint) consecutive++;
      else { consecutive = 1; streakStart = null; streakBase = null; }
      if (streakStart === null) { streakStart = now(); streakBase = before.summary; }
      fingerprint = nextHash; lastFrame = frame; loadedChunks = before.loadedChunks;
    }
    history.push({before: {request: before.request, summary: before.summary}, after: after && {request: after.request, summary: after.summary},
      failures, fingerprint: nextHash, consecutive, present: frame ? [frame.beginPresent, frame.endPresent] : null});
    if (history.length > 120) history.shift();
    const stableMs = streakStart === null ? 0 : now() - streakStart;
    const receipt = {schema: 2, source: "vanilla", complete: consecutive >= stableFrames && stableMs >= minStableMs,
      bounds: captureChunkBounds(camera, radius), radius, stableFrames, minStableMs, stableMs, consecutive,
      fingerprint, loadedChunks, elapsedMs: now() - started, history};
    await onProgress(receipt);
    if (receipt.complete) return {receipt, frame: lastFrame};
    await sleep(intervalMs);
  }
  throw new Error("vanilla world quiescence timed out after " + timeoutMs + " ms");
}

/** One bracket around a screenshot: idle before and after, same packets and the stable fingerprint. */
export async function confirmCaptureInputs({evaluate, camera, radius = 8, stable}) {
  const before = await sampleCaptureQuiescence(evaluate, camera, radius);
  const frame = withoutLightmapUpdates(await evaluate("globalThis.__gaiusDrawCapture.frame()"));
  const fingerprint = drawInputFingerprint(frame);
  const after = await sampleCaptureQuiescence(evaluate, camera, radius);
  const base = stable.history.at(-1).before.summary;
  const failures = [...before.failures, ...after.failures];
  if (!samePackets(base, before.summary) || !samePackets(before.summary, after.summary)) failures.push("world packets changed");
  if (before.summary.client?.loadedSha256 !== base.client?.loadedSha256) failures.push("loaded chunks changed");
  if (fingerprint !== stable.fingerprint) failures.push("draw inputs changed");
  return {failures, fingerprint, ...(failures.length ? {rejectedFrame: frame} : {}), present: [frame.beginPresent, frame.endPresent],
    before: {request: before.request, summary: before.summary}, after: {request: after.request, summary: after.summary}};
}
