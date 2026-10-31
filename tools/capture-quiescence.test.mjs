import assert from "node:assert/strict";
import test from "node:test";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {CAPTURE_CAPABILITIES_EXPRESSION, CLIENT_COUNTERS, QUIESCENCE_REQUIREMENTS, captureCapabilityFailures,
  captureChunkBounds, captureRequest, confirmCaptureInputs, drawInputFingerprint, quiescenceFailures, summarizeSnapshot,
  waitForCaptureQuiescence, withoutLightmapUpdates} from "./capture-quiescence.mjs";
import {validateQuiescenceReceipt, verifyCaptureDeterminism} from "./verify-capture-determinism.mjs";
import {captureOptionsReadbackExpression, captureViews} from "./deterministic-world-capture.mjs";
import {encodePng} from "./capture-pixel-diff.mjs";
import {normalizeBackendFrame, projectionToGl} from "./backend-clip-space.mjs";

const camera = [0.5, 70, -0.5, 0, 0];
const capabilities = {runtime: "js", wasmExports: [], drawCapture: true,
  observer: {schema: 2, source: "vanilla", snapshot: true, capabilities: QUIESCENCE_REQUIREMENTS}};
function snapshot(request, sample, packets = {chunk: 9, light: 4}) {
  const loadedChunks = [];
  for (let chunkX = request.bounds.minX + 1; chunkX < request.bounds.maxX; chunkX++) {
    for (let chunkZ = request.bounds.minZ + 1; chunkZ < request.bounds.maxZ; chunkZ++) loadedChunks.push([chunkX, chunkZ]);
  }
  return {schema: 2, source: "vanilla", requestId: request.requestId, dimension: "minecraft:overworld",
    client: {requestId: request.requestId, bounds: request.bounds, sample, dimension: "minecraft:overworld", loadedChunks,
      expectedMissing: [], unexpected: [], trackedSections: loadedChunks.length * 24, visibleSections: 40,
      ...Object.fromEntries(CLIENT_COUNTERS.map(name => [name, 0]))},
    network: {requestId: request.requestId, bounds: request.bounds, sample, packets: {...packets}},
    pins: {lightFlicker: true, lightFlickerTicks: 20, gameTime: 6000, gameTimeFrames: 50}};
}
const requestOf = expression => JSON.parse(expression.slice(expression.indexOf("snapshot(") + 9, -1));
function frame(beginPresent = 1, mesh = "a", backend = "webgl2") {
  return {schema: 2, boundary: "surface-present", normalization: "referenced-index-window", backend,
    beginPresent, endPresent: beginPresent + 1, readTextures: true, textureReadbacks: 1, failures: [],
    draws: [{pipeline: "fixture-terrain", args: [3, 1, 0, 0, 0], formats: [],
      vertices: [{length: 4, sha256: mesh.repeat(64)}], indices: {length: 4, sha256: "b".repeat(64)},
      uniforms: [{name: "Sampler2", kind: "texture", label: "Lightmap", uploads: [
        {mip: 0, width: 16, height: 16, covered: 256, unknown: false, sha256: "c".repeat(64)},
      ]}]}]};
}

test("legacy telemetry cannot authorize capture and every staged observation is required", () => {
  assert.ok(captureCapabilityFailures({legacy: {audit: true, pipeline: true}}).length);
  assert.deepEqual(captureCapabilityFailures(capabilities), []);
  assert.match(captureCapabilityFailures(capabilities, {requireWasm: true})[0], /instantiated/);
  assert.ok(captureCapabilityFailures({...capabilities, observer: {...capabilities.observer, capabilities: []}}).includes("unobservable render-clock-pins"));
  assert.ok(captureCapabilityFailures({...capabilities, observer: {...capabilities.observer, schema: 1}}).length);
});

test("view bounds use floor division and include the two-chunk tracking border", () => {
  assert.deepEqual(captureChunkBounds([-16.01, 70, -0.01, 0, 0], 2), {minX: -6, maxX: 2, minZ: -5, maxZ: 3});
  assert.throws(() => captureChunkBounds(camera, 1), /bounded/);
  assert.deepEqual(captureRequest([-16.01, 70, -0.01, 0, 0], 8).view, {centerX: -2, centerZ: -1, distance: 8});
});

test("every client queue, every tracked chunk and both render clock pins are required", () => {
  const request = captureRequest(camera, 2);
  const idle = summarizeSnapshot(snapshot(request, 1));
  assert.deepEqual(quiescenceFailures(idle, request), []);
  for (const name of CLIENT_COUNTERS) {
    for (const value of [undefined, 1, -1, NaN]) {
      const altered = structuredClone(idle); altered.client[name] = value;
      assert.ok(quiescenceFailures(altered, request).some(failure => failure.includes("client." + name)), name);
    }
  }
  const missing = snapshot(request, 1); missing.client.expectedMissing.push([0, 0]);
  assert.ok(quiescenceFailures(summarizeSnapshot(missing), request).some(failure => failure.includes("incomplete")));
  for (const pin of ["lightFlicker", "gameTime"]) {
    const unpinned = snapshot(request, 1); unpinned.pins[pin] = pin === "gameTime" ? null : false;
    assert.ok(quiescenceFailures(summarizeSnapshot(unpinned), request).includes("render clock pins inactive"));
  }
  assert.deepEqual(quiescenceFailures({...idle, requestId: "old"}, request), ["missing or stale vanilla snapshot"]);
  const otherView = structuredClone(idle); otherView.client.bounds.minX--;
  assert.ok(quiescenceFailures(otherView, request).some(failure => failure.includes("another view")));
});

test("fingerprints preserve mesh, draw order, uniforms and lightmap, not native arena addresses", () => {
  const original = frame(); const hash = drawInputFingerprint(original);
  const moved = structuredClone(original); moved.draws[0].rawArgs = [3, 1, 400, 800, 0];
  assert.equal(drawInputFingerprint(moved), hash);
  assert.notEqual(drawInputFingerprint(frame(3, "d")), hash);
  const changedLight = structuredClone(original); changedLight.draws[0].uniforms[0].uploads[0].sha256 = "d".repeat(64);
  assert.notEqual(drawInputFingerprint(changedLight), hash);
  const changedUniform = structuredClone(original); changedUniform.draws[0].uniforms.push({name: "Projection", length: 64, sha256: "e".repeat(64)});
  assert.notEqual(drawInputFingerprint(changedUniform), hash);
  const ordered = frame(); ordered.draws.push(frame(1, "d").draws[0]);
  const reversed = structuredClone(ordered); reversed.draws.reverse();
  assert.notEqual(drawInputFingerprint(ordered), drawInputFingerprint(reversed));
  for (const altered of [{...original, readTextures: false}, {...original, textureReadbacks: 0}, {...original, endPresent: 1}, {...original, draws: []}]) {
    assert.throws(() => drawInputFingerprint(altered));
  }
});

test("stable frames follow a changed mesh, and arriving packets restart the streak", async () => {
  let clock = 0, sample = 0, draws = 0;
  const result = await waitForCaptureQuiescence({camera, radius: 2, now: () => clock, sleep: async () => { clock += 50; },
    evaluate: async expression => {
      if (expression === CAPTURE_CAPABILITIES_EXPRESSION) return capabilities;
      if (expression.endsWith(".frame()")) return frame(++draws * 2 - 1, draws === 1 ? "d" : "a");
      // A light packet arrives during the third frame: the streak must restart after it.
      return snapshot(requestOf(expression), ++sample, {chunk: 9, light: sample >= 6 ? 5 : 4});
    }});
  assert.equal(result.receipt.consecutive, 4);
  assert.ok(draws >= 7, "the changed packet counter forced additional frames");
  validateQuiescenceReceipt(result.receipt, captureChunkBounds(camera, 2), drawInputFingerprint(result.frame));
});

test("the drain window must last minStableMs without packets", async () => {
  let clock = 0, sample = 0;
  const result = await waitForCaptureQuiescence({camera, radius: 2, drawFrames: false, minStableMs: 1000,
    now: () => clock, sleep: async () => { clock += 250; },
    evaluate: async expression => expression === CAPTURE_CAPABILITIES_EXPRESSION ? capabilities
      : snapshot(requestOf(expression), ++sample)});
  assert.ok(result.receipt.stableMs >= 1000);
  assert.ok(sample >= 5);
  validateQuiescenceReceipt(result.receipt, captureChunkBounds(camera, 2));
});

test("stale snapshots time out and duplicate presents fail closed", async () => {
  for (const stale of ["sample", "present"]) {
    let clock = 0, sample = 0;
    await assert.rejects(waitForCaptureQuiescence({camera, radius: 2, timeoutMs: 150,
      now: () => clock, sleep: async () => { clock += 50; }, evaluate: async expression => {
        if (expression === CAPTURE_CAPABILITIES_EXPRESSION) return capabilities;
        if (expression.endsWith(".frame()")) return frame();
        return snapshot(requestOf(expression), stale === "sample" ? 1 : ++sample);
      }}), stale === "sample" ? /timed out/ : /sequence did not advance/);
  }
});

test("capability refusal occurs before requesting any world state or frame", async () => {
  await assert.rejects(waitForCaptureQuiescence({camera, sleep: async () => {}, evaluate: async expression => {
    assert.equal(expression, CAPTURE_CAPABILITIES_EXPRESSION); return {runtime: "js", observer: null};
  }}), /capture blocked/);
});

test("screenshot brackets reject changed packets or inputs", async () => {
  let sample = 0, clock = 0;
  const evaluate = async expression => expression === CAPTURE_CAPABILITIES_EXPRESSION ? capabilities
    : expression.endsWith(".frame()") ? frame(++sample * 2 + 100) : snapshot(requestOf(expression), ++sample);
  const stable = await waitForCaptureQuiescence({camera, radius: 2, evaluate, now: () => clock, sleep: async () => { clock += 50; }});
  assert.deepEqual((await confirmCaptureInputs({evaluate, camera, radius: 2, stable: stable.receipt})).failures, []);
  const changed = await confirmCaptureInputs({camera, radius: 2, stable: stable.receipt,
    evaluate: async expression => expression.endsWith(".frame()") ? frame(900, "d") : snapshot(requestOf(expression), ++sample, {chunk: 10, light: 4})});
  assert.ok(changed.failures.includes("draw inputs changed"));
  assert.ok(changed.failures.includes("world packets changed"));
});

test("projection normalization maps the WebGPU clip range back to the GL convention only", () => {
  const gl = [0.621624, 0, 0, 0, 0, 1.257172, 0, 0, 0, 0, 1.000049, -1, 0, 0, 0.100002, 0];
  const gpu = [0.621624, 0, 0, 0, 0, 1.257172, 0, 0, 0, 0, 0.0000245, -1, 0, 0, 0.050001, 0];
  assert.deepEqual(projectionToGl(gpu, "webgpu"), projectionToGl(gl, "webgl2"));
  assert.throws(() => projectionToGl(gl, "other"));
  const hex = values => Buffer.from(new Float32Array(values).buffer).toString("hex");
  const draw = values => ({pipeline: "p", args: [], formats: [], vertices: [], indices: null,
    uniforms: [{name: "Projection", length: 64, sha256: "f".repeat(64), hex: hex(values)}, {name: "Fog", length: 4, sha256: "1".repeat(64)}]});
  const left = normalizeBackendFrame({backend: "webgl2", draws: [draw(gl)]});
  const right = normalizeBackendFrame({backend: "webgpu", draws: [draw(gpu)]});
  assert.deepEqual(left.draws[0].uniforms, right.draws[0].uniforms);
  const skewed = normalizeBackendFrame({backend: "webgpu", draws: [draw(gpu.map((value, index) => index === 0 ? value * 1.01 : value))]});
  assert.notDeepEqual(skewed.draws[0].uniforms, left.draws[0].uniforms);
});

test("the four-run gate rejects nondeterminism before cross-backend or pixel comparison", async () => {
  assert.ok(process.env.OUT, "tests require an explicit output directory");
  const root = join(resolve(process.env.OUT), "synthetic-four-run-gate");
  const directories = ["gl-a", "gl-b", "gpu-a", "gpu-b"].map(name => join(root, name));
  const cameras = captureViews({x: 0, y: 67, z: 0});
  async function receiptFor(viewCamera, input = null) {
    let sample = 0, clock = 0;
    const evaluate = async expression => expression === CAPTURE_CAPABILITIES_EXPRESSION ? capabilities
      : expression.endsWith(".frame()") ? {...input, beginPresent: ++sample * 2 + 1, endPresent: sample * 2 + 2}
        : snapshot(requestOf(expression), ++sample);
    const stable = await waitForCaptureQuiescence({camera: viewCamera, evaluate, drawFrames: !!input,
      now: () => clock, sleep: async () => { clock += 50; }});
    const brackets = input ? await Promise.all([0, 1, 2].map(async () => ({
      before: await confirmCaptureInputs({evaluate, camera: viewCamera, stable: stable.receipt}),
      after: await confirmCaptureInputs({evaluate, camera: viewCamera, stable: stable.receipt})}))) : null;
    return {receipt: stable.receipt, brackets};
  }
  async function save(meshes) {
    for (const [index, directory] of directories.entries()) {
      await mkdir(directory, {recursive: true});
      const backend = index < 2 ? "webgl2" : "webgpu";
      const capture = {schema: 2, complete: true, profile: "fixture", seed: "fixture", capabilities,
        runIdentity: {id: "12345678-1234-1234-1234-" + String(index + 1).padStart(12, "0")},
        settings: {animationTick: 0}, options: [], clientIdentity: {artifact: {sha256: "e".repeat(64)}},
        stageReceipt: {originalSha256: "1".repeat(64), stagedSha256: "2".repeat(64)},
        quiescence: {radius: 8}, views: {}};
      for (const [name, viewCamera] of Object.entries(cameras)) {
        const input = frame(9, meshes[index], backend);
        const drained = await receiptFor(viewCamera), stable = await receiptFor(viewCamera, input);
        const files = [0, 1, 2].map(repeat => name + "-" + repeat + ".png");
        capture.views[name] = {camera: viewCamera, files, drained: drained.receipt, stable: stable.receipt,
          screenshotInputs: stable.brackets,
          states: files.map(() => ({backend, animationTick: 0, animationApplications: 1,
            options: {cloudsOff: false, particlesMinimal: true}, pins: {lightFlicker: true, gameTime: 6000},
            player: Object.fromEntries(["x", "y", "z", "yaw", "pitch"].map((field, offset) => [field, viewCamera[offset]]))}))};
        await writeFile(join(directory, "draw-inputs-" + name + ".json"), JSON.stringify(input));
        for (const filename of files) await writeFile(join(directory, filename), encodePng({width: 1, height: 3, pixels: Buffer.alloc(12, 255)}));
      }
      await writeFile(join(directory, "capture.json"), JSON.stringify(capture));
    }
  }
  await save(["a", "d", "a", "a"]);
  const unstable = await verifyCaptureDeterminism(directories, join(root, "unstable"));
  assert.equal(unstable.verdict, "NONDETERMINISTIC", unstable.error); assert.equal(unstable.crossBackend, null); assert.equal(unstable.pixels, null);
  await save(["a", "a", "d", "d"]);
  const cross = await verifyCaptureDeterminism(directories, join(root, "cross"));
  assert.equal(cross.verdict, "INPUT_MISMATCH", cross.error);
  await save(["a", "a", "a", "a"]);
  const same = await verifyCaptureDeterminism(directories, join(root, "same"));
  assert.equal(same.verdict, "PASS", same.error);
  const receipt = JSON.parse(await readFile(join(directories[0], "capture.json"), "utf8"));
  delete receipt.views.high.stable; await writeFile(join(directories[0], "capture.json"), JSON.stringify(receipt));
  assert.equal((await verifyCaptureDeterminism(directories, join(root, "missing-state"))).verdict, "BLOCKED");
  assert.equal((await verifyCaptureDeterminism([directories[0], directories[0], directories[2], directories[3]], join(root, "same-run"))).verdict, "BLOCKED");
});

test("lightmap update passes are removed from the ordered draws but the sampled lightmap stays compared", () => {
  const plain = frame();
  const ticked = frame(); ticked.draws.unshift({pipeline: "minecraft:pipeline/lightmap", args: [3, 1, 0, 0], formats: [], vertices: [], indices: null, uniforms: []});
  const normalized = withoutLightmapUpdates(ticked);
  assert.equal(normalized.lightmapUpdates, 1);
  assert.equal(drawInputFingerprint(normalized), drawInputFingerprint(withoutLightmapUpdates(plain)));
  const relit = withoutLightmapUpdates(frame()); relit.draws[0].uniforms[0].uploads[0].sha256 = "9".repeat(64);
  assert.notEqual(drawInputFingerprint(relit), drawInputFingerprint(normalized));
});

test("a full capture reloads each view at its camera and brackets every screenshot", async () => {
  assert.ok(process.env.OUT, "tests require an explicit output directory");
  const {captureDeterministicWorld} = await import("./deterministic-world-capture.mjs");
  const directory = join(resolve(process.env.OUT), "synthetic-full-capture"); await mkdir(directory, {recursive: true});
  const commands = [], keys = [];
  let sample = 0, present = 0, player = {x: 0, y: 0, z: 0, yaw: 0, pitch: 0};
  const reloadedViews = [];
  const content = {blocks: "00000000", biomes: "00000000"};
  const evaluate = async expression => {
    if (expression === CAPTURE_CAPABILITIES_EXPRESSION) return capabilities;
    if (expression === captureOptionsReadbackExpression) return {cloudsOff: false, particlesMinimal: true};
    if (expression.endsWith(".frame()")) { present += 2; return frame(present); }
    if (expression.includes(".snapshot(")) return snapshot(requestOf(expression), ++sample);
    if (expression.includes(".worldDigest(")) return Object.fromEntries(JSON.parse(expression.slice(expression.indexOf("(") + 1, -1)).map(([x, z]) => [x + "," + z, {packet: null, sky: "0", block: "0"}]));
    if (expression.includes(".contentDigest(")) return Object.fromEntries(JSON.parse(expression.slice(expression.indexOf("(") + 1, -1)).map(([x, z]) => [x + "," + z, content]));
    if (expression.includes("animationHook:capture?.animationHook")) return {player, screen: null, backend: "webgl2", animationHook: "fixture",
      animationApplications: 1, animationTick: 0, options: {cloudsOff: false, particlesMinimal: true}, pins: {lightFlicker: true, gameTime: 6000}};
    return true;
  };
  const result = await captureDeterministicWorld({
    command: async text => {
      commands.push(text);
      if (text.startsWith("/tp ")) { const [x, y, z, yaw, pitch] = text.split(" ").slice(2).map(Number); player = {x, y, z, yaw, pitch}; }
      if (text.startsWith("/gamerule ") && text.split(" ").length === 2) return {ok: true, response: "Game rule x is currently set to " + (text.endsWith("random_tick_speed") ? "0" : "false")};
      return {ok: true};
    },
    evaluate, waitFor: async () => true, key: async name => keys.push(name), sleep: async () => {}, screenshot: async () => true,
    player: {x: 0, y: 67, z: 0}, outputPath: directory, seed: "fixture", profile: "fixture", drainMs: 0,
    reloadChunks: async name => { reloadedViews.push(name); return {method: "vanilla-f3-a"}; }, reloadEachView: true,
  });
  assert.equal(result.complete, true);
  assert.deepEqual(reloadedViews, ["horizon", "entities", "high", "topdown"]);
  assert.deepEqual(Object.keys(result.reloads), reloadedViews);
  const views = captureViews({x: 0, y: 67, z: 0});
  for (const camera of Object.values(views)) {
    const at = commands.indexOf("/tp @s " + camera.join(" "));
    assert.ok(at >= 0, "camera teleport is recorded before its allChanged reload");
  }
});
