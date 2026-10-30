import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {configureWorldSeed, seedScreenSupportsShortcut} from "./configure-browser-world-seed.mjs";
import {stageAnimationCapture, captureViews, validateCaptureViews, captureCameraReadyExpression, captureDeterministicWorld, CAPTURE_OPTIONS, freezeCaptureWorld} from "./deterministic-world-capture.mjs";
import {decodePng, encodePng, compareCaptures, reportCaptureDiff} from "./capture-pixel-diff.mjs";
import {backendConsistencyChecks, clientRuntimeCheck, graphicsBackendFromLog, requestedFromQuery, fallbackReasons} from "./backend-consistency.mjs";
import {terrainCompleteness, terrainVerdict, readRenderDistance, optionRenderDistance, visibleTerrainPass} from "./terrain-settle.mjs";
import {enableCreativeFlight, flightWindowMovement, holdKeyDuring} from "./flight-input.mjs";

const output = resolve(process.env.OUT || "acceptance-harness-test-out");
await mkdir(output, {recursive: true});
const source = `let nmc_Minecraft_getInstance = () => {return minecraft;};
let nmc_Options_cloudStatus = $this => {return $this.$cloud;};
let nmc_Options_particles = $this => {return $this.$particles;};
let nmc_OptionInstance_get = $this => {return $this.$value;};
let jl_Enum_ordinal = $this => {return $this.$ordinal;};
function init($this){nmc_Options__init_(options, $this, dir);$this.$options = options;}
let nmc_SpriteContents$AnimationState_tick = $this => {
    $this.$subFrame = $this.$subFrame + 1 | 0;
    $this.$dirty = 0;
    const current = $this.$frames[$this.$frame];
    if($this.$subFrame>=current.time){
      const previous=current.index;
      $this.$frame = ($this.$frame + 1 | 0) % $this.$frames.length;
      $this.$subFrame=0;$this.$dirty=previous!==$this.$frames[$this.$frame].index?1:0;
    }
};`;
test("staged animation replays vanilla durations and preserves disabled behavior", () => {
  const staged = stageAnimationCapture(source);
  const context = vm.createContext({minecraft: {$options: {$cloud: {$value: {$ordinal: 2}}, $particles: {$value: {$ordinal: 2}}}}});
  vm.runInContext(staged.source + ";globalThis.tick=nmc_SpriteContents$AnimationState_tick;", context);
  const baseline = vm.createContext({}); vm.runInContext(source + ";globalThis.tick=nmc_SpriteContents$AnimationState_tick;", baseline);
  const create = () => ({$frame: 0, $subFrame: 0, $dirty: 1, $frames: [{index: 0, time: 2}, {index: 1, time: 3}, {index: 1, time: 1}]});
  const actual = create(), expected = create();
  for (let count = 0; count < 100; count++) { context.tick(actual); baseline.tick(expected); assert.deepEqual(actual, expected); }
  for (const target of [0, 1, 2, 4, 9, 19, 1200]) {
    const expectedPinned = create(); for (let count = 0; count < target; count++) baseline.tick(expectedPinned);
    context.__gaiusCapture = {enabled: true, animationTick: target};
    for (let repeat = 0; repeat < 3; repeat++) {
      const changed = actual.$frame !== expectedPinned.$frame || actual.$subFrame !== expectedPinned.$subFrame;
      context.tick(actual); assert.equal(actual.$frame, expectedPinned.$frame); assert.equal(actual.$subFrame, expectedPinned.$subFrame); assert.equal(actual.$dirty, Number(changed));
    }
    assert.equal(context.__gaiusCapture.optionsObserved, undefined);
    context.__gaiusCapture.enabled = false; baseline.tick(expectedPinned); context.tick(actual); assert.deepEqual(actual, expectedPinned);
  }
  assert.throws(() => stageAnimationCapture(source + source), /expected one/);
  assert.throws(() => stageAnimationCapture(source.replace("$subFrame + 1", "$subFrame + 2")), /structure changed/);
});

for (const layout of ["shortcut", "button"]) test(`seed input uses ${layout} layout without a tab-bar telemetry requirement`, async () => {
  const seedWidget = {type: "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab$1", text: "Seed for the world generator", x: 10, y: 20, width: 100, height: 20, active: true, visible: true, focused: false, value: ""};
  const context = vm.createContext({window: {__gaiusMinecraftState: {screen: "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen", screenSize: {width: 320, height: 180}, screenWidgets: []}},
    document: {querySelector: () => ({getBoundingClientRect: () => ({left: 0, top: 0, width: 640, height: 360})})}});
  let controlHeld = false, typed = "";
  const openTab = () => { context.window.__gaiusMinecraftState.screenWidgets = [seedWidget]; };
  const result = await configureWorldSeed({send: async (method, args) => {
    if (method === "Input.dispatchMouseEvent") seedWidget.focused = true;
    if (args?.text) { assert.equal(controlHeld, false); typed += args.text; seedWidget.value += args.text; }
    if (method === "Page.captureScreenshot") return {data: Buffer.from("mock").toString("base64")};
  }}, "20261004", {
    findButton: async () => layout === "button" ? {text: "World", x: 20, y: 10} : null,
    click: async () => openTab(), dispatchKey: async (_, code, type) => { if (code === "ControlLeft") controlHeld = type === "keyDown"; if (code === "Digit2" && type === "keyUp") openTab(); },
    evaluate: async (_, expression) => vm.runInContext(expression, context),
    waitFor: async (_, expression) => assert.equal(vm.runInContext(expression, context), true), sleep: async () => {}, outputPath: join(output, layout),
  });
  assert.equal(typed, "20261004"); assert.equal(result.usedWorldTabShortcut, layout === "shortcut");
  assert.equal(result.widget.value, "20261004");
  assert.equal(result.valueObserved, true);
  assert.equal(seedScreenSupportsShortcut({screen: "UnrelatedScreen"}), false);
});

test("PNG round-trip and repeat masks separate stable residuals from temporal noise", () => {
  const image = {width: 2, height: 3, pixels: Buffer.alloc(24, 255)};
  assert.deepEqual(decodePng(encodePng(image)), image);
  const clone = () => ({...image, pixels: Buffer.from(image.pixels)});
  const webgl = [clone(), clone(), clone()], webgpu = [clone(), clone(), clone()];
  for (const snapshot of webgpu) snapshot.pixels[0] = 0;
  webgpu[1].pixels[4] = 0;
  const result = compareCaptures(webgl, webgpu);
  assert.equal(result.regions.full.rawDifferent, 1); assert.equal(result.regions.full.stableDifferent, 1); assert.equal(result.regions.full.unstable, 1);
  assert.equal(result.regions.full.stableFraction, 0.2);
  assert.deepEqual([...result.heatmap.pixels.subarray(0, 8)], [255, 0, 0, 255, 255, 170, 0, 255]);
  assert.equal(captureViews({x: 4.2, y: 70, z: -2}).topdown[1], 110);
  assert.deepEqual(validateCaptureViews(captureViews({x: 4.2, y: 70, z: -2})), captureViews({x: 4.2, y: 70, z: -2}));
  assert.throws(() => validateCaptureViews({horizon: [0, 0, 0, 0, 0]}), /four named/);
  const invalid = captureViews({x: 4.2, y: 70, z: -2}); invalid.high[1] = Infinity;
  assert.throws(() => validateCaptureViews(invalid), /four named/);
});

test("M4 position persistence tolerance remains strict", async () => {
  const harness = await readFile(new URL("../port/scripts/minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
  assert.match(harness, /< 2\.5/);
});

test("camera settlement expressions handle negative coordinates and pitch", () => {
  for (const camera of Object.values(captureViews({x: -12.2, y: -60, z: -2.3}))) {
    const player = Object.fromEntries(["x", "y", "z", "yaw", "pitch"].map((name, index) => [name, camera[index]]));
    const context = vm.createContext({window: {__gaiusMinecraftState: {player},
      __gaiusSectionAudit: {uncompiled: 0, at: Date.now()}, __gaiusChunkPipelineTelemetry: {compileBacklog: 0, uploadBacklog: 0}}});
    assert.equal(vm.runInContext(captureCameraReadyExpression(camera), context), true);
    player.pitch += 1;
    assert.equal(vm.runInContext(captureCameraReadyExpression(camera), context), false);
  }
});

test("pixel acceptance rejects temporal changes and mismatched capture provenance", async () => {
  const root = join(output, "synthetic-pixel-policy");
  const directories = [join(root, "webgl2"), join(root, "webgpu")];
  for (const directory of directories) await mkdir(directory, {recursive: true});
  const cameras = captureViews({x: 0, y: 67, z: 0});
  const captures = ["webgl2", "webgpu"].map(backend => ({complete: true, profile: "fixture", seed: "fixture",
    settings: {animationTick: 0}, options: [], stageReceipt: {originalSha256: "1".repeat(64), stagedSha256: "2".repeat(64)},
    views: Object.fromEntries(Object.entries(cameras).map(([name, camera]) => [name, {camera: [...camera],
      files: [0, 1, 2].map(repeat => `${name}-${repeat}.png`),
      states: [0, 1, 2].map(() => ({backend, animationTick: 0, animationApplications: 1,
        options: {cloudsOff: false, particlesMinimal: true},
        player: Object.fromEntries(["x", "y", "z", "yaw", "pitch"].map((field, index) => [field, camera[index]]))})),
    }]))}));
  const save = async mode => {
    for (const [backendIndex, capture] of captures.entries()) {
      await writeFile(join(directories[backendIndex], "capture.json"), JSON.stringify(capture));
      for (const [name, view] of Object.entries(capture.views)) for (const [repeat, filename] of view.files.entries()) {
        const image = {width: 1, height: 3, pixels: Buffer.alloc(12, 255)};
        if (backendIndex === 1 && name === "horizon" && (mode === "different" || mode === "unstable" && repeat === 1)) image.pixels[0] = 0;
        await writeFile(join(directories[backendIndex], filename), encodePng(image));
      }
    }
  };
  for (const [mode, verdict] of [["same", "PASS"], ["different", "FAIL"], ["unstable", "UNSTABLE"]]) {
    await save(mode);
    assert.equal((await reportCaptureDiff(...directories, join(root, mode))).verdict, verdict);
  }
  captures[1].stageReceipt.stagedSha256 = "3".repeat(64); await save("same");
  await assert.rejects(reportCaptureDiff(...directories, join(root, "wrong-stage")), /same staged/);
  for (const capture of captures) delete capture.stageReceipt; await save("same");
  await assert.rejects(reportCaptureDiff(...directories, join(root, "no-identity")), /identity is required/);
  for (const capture of captures) capture.clientIdentity = {artifact: {sha256: "4".repeat(64)}}; await save("same");
  assert.equal((await reportCaptureDiff(...directories, join(root, "native-identity"))).verdict, "PASS");
  captures[1].views.horizon.camera[4] -= 1; await save("same");
  await assert.rejects(reportCaptureDiff(...directories, join(root, "wrong-camera")), /camera horizon/);
});

test("failed vanilla freeze commands leave an incomplete receipt and restore ticking", async () => {
  const directory = join(output, "synthetic-capture-failure"); await mkdir(directory, {recursive: true});
  const commands = [], evaluations = [];
  await assert.rejects(captureDeterministicWorld({
    command: async text => { commands.push(text); return {ok: text.startsWith("/tick ")}; },
    evaluate: async expression => { evaluations.push(expression); return true; },
    waitFor: async () => {}, key: async () => {}, sleep: async () => {}, screenshot: async () => true,
    player: {x: 0, y: 67, z: 0}, outputPath: directory, seed: "fixture", profile: "fixture",
  }), /not acknowledged/);
  const receipt = JSON.parse(await readFile(join(directory, "capture.json"), "utf8"));
  assert.equal(receipt.complete, false); assert.match(receipt.error, /not acknowledged/);
  assert.equal(receipt.cleanup.unfreeze.ok, true);
  assert.deepEqual(commands, ["/tick freeze", "/gamerule advance_time false", "/tick unfreeze"]);
  assert.match(evaluations.at(-1), /enabled=false/);
});


test("capture writes the vanilla cloud option and freezes before settling", async () => {
  assert.ok(CAPTURE_OPTIONS.includes("renderClouds:true"));
  assert.ok(!CAPTURE_OPTIONS.some(option => option.startsWith("clouds:")));
  const calls = [];
  await freezeCaptureWorld(async text => { calls.push(text); return {ok: true}; });
  calls.push("settle");
  assert.deepEqual(calls, ["/tick freeze", "settle"]);
  await assert.rejects(freezeCaptureWorld(async () => ({ok: false})), /not acknowledged/);
  const runner = await readFile(new URL("../port/scripts/minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
  assert.ok(runner.indexOf("report.captureEarlyFreeze") < runner.indexOf("Date.now() + settleMs"),
    "capture freezes before the world settling interval");
});

// ---- backend / runtime consistency -------------------------------------------------------------

const gpuState = (backend, selection = {}, extra = {}) => ({
  gpu: {selection: {requested: "webgpu", source: "url", selected: backend, fallback: false, reason: "url-webgpu", inject: "", initMs: 120, ...selection},
    backend, webgpu: backend === "webgpu" ? {status: 2, failure: null, lost: null} : {status: 0, failure: null, lost: null},
    toolchain: {declared: true, ready: backend === "webgpu"}, ...extra.gpu},
  runtime: {requested: "wasm", source: "url", selected: "wasm", fallback: false, late: false, reason: "url-wasm", form: "site"},
  bootServerRuntime: "wasm", ...extra.top,
});

test("a requested WebGPU run that renders with WebGL 2 fails the backend check and names the reason", () => {
  const lines = ["    3.0 console.info [INFO] Minecraft: Using graphics backend OpenGL, using drivers: OpenGL 3.3 (WebGL2)",
    "    2.9 console.warn Gaius: WebGPU is unavailable (shader toolchain: could not load gaius-shader-toolchain.js); rendering with WebGL 2"];
  const state = gpuState("webgl2", {selected: "webgl2", fallback: true, reason: "shader toolchain: could not load gaius-shader-toolchain.js"});
  const {gpuBackendMatches} = backendConsistencyChecks({requested: {gpu: "webgpu", gpuSource: "url"}, state, lines});
  assert.equal(gpuBackendMatches.ok, false);
  assert.equal(gpuBackendMatches.detail.expected, "webgpu");
  assert.equal(gpuBackendMatches.detail.actual, "webgl2");
  assert.match(gpuBackendMatches.detail.problem, /requested webgpu but the game renders with webgl2/);
  assert.ok(gpuBackendMatches.detail.fallbackReasons.some(reason => /shader toolchain: could not load/.test(reason)), JSON.stringify(gpuBackendMatches.detail.fallbackReasons));
});

test("a fallback the boot selection does not know of is still caught from the vanilla log and the glue", () => {
  // The page offered WebGPU and never marked a fallback; Java's front-end failed and vanilla picked OpenGL.
  const state = gpuState("webgl2", {selected: "webgpu", fallback: false}, {gpu: {webgpu: {status: 3, failure: "pipeline compile failed", lost: null}}});
  const lines = ["    3.0 console.info [INFO] Minecraft: Using graphics backend OpenGL, using drivers: OpenGL 3.3 (WebGL2)"];
  const {gpuBackendMatches} = backendConsistencyChecks({requested: {gpu: "webgpu"}, state, lines});
  assert.equal(gpuBackendMatches.ok, false);
  assert.ok(gpuBackendMatches.detail.fallbackReasons.some(reason => /pipeline compile failed/.test(reason)));
});

test("matching backends pass, in either direction, and evidence that disagrees fails", () => {
  const webgpuLine = ["  3.0 console.info [INFO] Minecraft: Using graphics backend WebGPU, using drivers: lovelace"];
  assert.equal(backendConsistencyChecks({requested: {gpu: "webgpu"}, state: gpuState("webgpu"), lines: webgpuLine}).gpuBackendMatches.ok, true);
  const gl = gpuState("webgl2", {requested: "webgl2", selected: "webgl2", reason: "url-webgl2"});
  assert.equal(backendConsistencyChecks({requested: {gpu: "webgl2"}, state: gl, lines: []}).gpuBackendMatches.ok, true);
  // WebGL 2 requested but WebGPU renders (an auto site): also a mismatch.
  assert.equal(backendConsistencyChecks({requested: {gpu: "webgl2"}, state: gpuState("webgpu"), lines: webgpuLine}).gpuBackendMatches.ok, false);
  // The page value and the log name different backends.
  const conflict = backendConsistencyChecks({requested: {gpu: "webgpu"}, state: gpuState("webgpu"), lines: ["x console.info Using graphics backend OpenGL, using drivers: y"]});
  assert.equal(conflict.gpuBackendMatches.ok, false);
  assert.match(conflict.gpuBackendMatches.detail.problem, /page reports webgpu, the log says webgl2/);
});

test("with no explicit request the page default is the expectation, and an unobservable backend fails", () => {
  const defaultWebgl = backendConsistencyChecks({requested: {}, state: gpuState("webgl2", {requested: "webgl2", selected: "webgl2"}), lines: []});
  assert.equal(defaultWebgl.gpuBackendMatches.ok, true);
  assert.equal(defaultWebgl.gpuBackendMatches.detail.requestedSource, "page-default");
  const blind = gpuState(null, {requested: "webgpu", selected: "pending"});
  const result = backendConsistencyChecks({requested: {gpu: "webgpu"}, state: blind, lines: []});
  assert.equal(result.gpuBackendMatches.ok, false);
  assert.match(result.gpuBackendMatches.detail.problem, /not observable/);
  assert.equal(backendConsistencyChecks({requested: {}, state: null, lines: []}).gpuBackendMatches.ok, true);
});

test("the server runtime must be the requested one, as the Worker reports it", () => {
  const glState = gpuState("webgl2", {requested: "webgl2"});
  const ok = backendConsistencyChecks({requested: {serverRuntime: "wasm"}, state: glState, serverReply: {runtime: "wasm"}, lines: []});
  assert.equal(ok.serverRuntimeMatches.ok, true);
  assert.equal(ok.serverRuntimeMatches.detail.confirmedByWorker, true);
  const wrong = backendConsistencyChecks({requested: {serverRuntime: "wasm"}, state: glState, serverReply: {runtime: "js"}, lines: []});
  assert.equal(wrong.serverRuntimeMatches.ok, false);
  assert.match(wrong.serverRuntimeMatches.detail.problem, /expected the wasm server runtime but the js one runs/);
  // Nothing requested, but the page decided on a js server while the Worker runs wasm.
  const drift = backendConsistencyChecks({requested: {}, state: gpuState("webgl2", {requested: "webgl2"}, {top: {bootServerRuntime: "js"}}), serverReply: {runtime: "wasm"}, lines: []});
  assert.equal(drift.serverRuntimeMatches.ok, false);
  // A Worker that predates the ping falls back to the boot decision and says so.
  const old = backendConsistencyChecks({requested: {serverRuntime: "wasm"}, state: glState, serverReply: {timeout: true}, lines: []});
  assert.equal(old.serverRuntimeMatches.ok, true);
  assert.equal(old.serverRuntimeMatches.detail.confirmedByWorker, false);
});

test("the client runtime check carries the fallback reason", () => {
  const state = gpuState("webgl2", {}, {top: {runtime: {requested: "wasm", selected: "js", fallback: true, late: true, reason: "wasm-load-failed:boom"}}});
  const result = clientRuntimeCheck({requested: "wasm", observed: "js", telemetry: "reported", state});
  assert.equal(result.ok, false);
  assert.match(result.detail.problem, /requested wasm but the js client runs/);
  assert.ok(result.detail.fallbackReasons.some(reason => /wasm-load-failed:boom/.test(reason)));
  assert.equal(clientRuntimeCheck({requested: "wasm", observed: "wasm", telemetry: "reported", state: gpuState("webgpu")}).ok, true);
  assert.equal(clientRuntimeCheck({requested: "js", observed: null, telemetry: "reported", state: null}).ok, false);
});

test("the request is read from the page URL and GAIUS_GPU_EXPECT overrides the gpu", () => {
  assert.deepEqual(requestedFromQuery("http://127.0.0.1:1/index.html?runtime=wasm&gpu=webgpu&serverRuntime=wasm"),
    {gpu: "webgpu", gpuSource: "url", serverRuntime: "wasm"});
  assert.deepEqual(requestedFromQuery("http://127.0.0.1:1/index.html?gpu=webgpu", {GAIUS_GPU_EXPECT: "webgl2", SERVER_RUNTIME: "js"}),
    {gpu: "webgl2", gpuSource: "GAIUS_GPU_EXPECT", serverRuntime: "js"});
  assert.deepEqual(requestedFromQuery("http://127.0.0.1:1/index.html"), {gpu: null, gpuSource: null, serverRuntime: null});
  assert.equal(graphicsBackendFromLog(["a Using graphics backend WebGPU, using drivers: x", "b Using graphics backend OpenGL, using drivers: y"]).backend, "webgl2");
  assert.equal(graphicsBackendFromLog(["nothing"]), null);
  assert.deepEqual(fallbackReasons(null, ["1 console.info shader toolchain loaded"]), []);
});

// ---- terrain settle -----------------------------------------------------------------------------
test("329 loaded chunks with no uncompiled section complete a render distance 8 view", () => {
  const sample = {loaded: 329, dist: {effectiveRenderDistance: 8, rawRenderDistance: 8}, audit: {uncompiled: 0}};
  const result = terrainCompleteness(sample);
  assert.equal(result.complete, true);
  assert.equal(result.expectedLoaded, 289);
  assert.equal(result.renderDistanceSource, "effectiveRenderDistance");
  const verdict = terrainVerdict({spawn: sample, firstTerrainSeconds: 31, settleSeconds: 44, px: {other: 0.9}, requestedOptions: ["renderDistance:8"]});
  assert.equal(verdict.ok, true);
  assert.equal(verdict.detail.renderDistanceMatchesOptions, true);
  // The settle loop did not see the window out, but the final state is complete with terrain drawn.
  const late = terrainVerdict({spawn: sample, firstTerrainSeconds: 31, settleSeconds: null, px: null});
  assert.equal(late.ok, true);
  assert.equal(late.detail.stable, false);
});

test("the effective render distance is what counts, and an options mismatch is reported", () => {
  const sample = {loaded: 478, dist: {effectiveRenderDistance: 12, rawRenderDistance: 12}, audit: {uncompiled: 0}};
  const verdict = terrainVerdict({spawn: sample, firstTerrainSeconds: 20, settleSeconds: null, requestedOptions: ["renderDistance:8", "simulationDistance:6"]});
  assert.equal(verdict.ok, false);
  assert.equal(verdict.detail.incomplete, "chunks-missing");
  assert.equal(verdict.detail.expectedLoaded, 625);
  assert.equal(verdict.detail.optionRenderDistance, 8);
  assert.equal(verdict.detail.renderDistanceMatchesOptions, false);
  assert.equal(optionRenderDistance(["x:1", "renderDistance:8"]), 8);
  assert.equal(optionRenderDistance(undefined), null);
});

test("terrain stays strict about what it cannot read or what is still missing", () => {
  assert.equal(readRenderDistance({effectiveRenderDistance: 0, rawRenderDistance: 10}).value, 10);
  assert.equal(readRenderDistance({}).value, null);
  const unreadable = terrainCompleteness({loaded: 5000, dist: null, audit: {uncompiled: 0}});
  assert.equal(unreadable.complete, false);
  assert.equal(unreadable.reason, "render-distance-unreadable");
  assert.equal(terrainCompleteness({loaded: 400, dist: {effectiveRenderDistance: 8}, audit: {uncompiled: 0}}).complete, true);
  assert.equal(terrainCompleteness({loaded: 288, dist: {effectiveRenderDistance: 8}, audit: {uncompiled: 0}}).reason, "chunks-missing");
  assert.equal(terrainCompleteness({loaded: 329, dist: {effectiveRenderDistance: 8}, audit: {uncompiled: 3}}).reason, "sections-uncompiled");
  assert.equal(terrainCompleteness({loaded: 289, dist: {effectiveRenderDistance: 8}, audit: {uncompiled: 0}}).complete, true);
  assert.equal(terrainCompleteness({loaded: null, dist: {effectiveRenderDistance: 8}, audit: {uncompiled: 0}}).complete, false);
  assert.equal(terrainCompleteness({loaded: 289, dist: {effectiveRenderDistance: 8}, audit: null}).complete, true);
  const noPixels = terrainVerdict({spawn: {loaded: 329, dist: {effectiveRenderDistance: 8}, audit: {uncompiled: 0}}, firstTerrainSeconds: null, settleSeconds: null});
  assert.equal(noPixels.ok, false);
  assert.equal(noPixels.detail.incomplete, "no-terrain-pixels");
});

test("visible terrain accepts an optional section audit but keeps pixel and chunk gates", () => {
  const px = {other: 0.83};
  assert.equal(visibleTerrainPass({loaded: 119, audit: null}, 14.9, px), true);
  assert.equal(visibleTerrainPass({loaded: 329, audit: {visible: 233}}, 14.9, px), true);
  assert.equal(visibleTerrainPass({loaded: 329, audit: {visible: 8}}, 14.9, px), false);
  assert.equal(visibleTerrainPass({loaded: 8, audit: null}, 14.9, px), false);
  assert.equal(visibleTerrainPass({loaded: 119, audit: null}, null, px), false);
  assert.equal(visibleTerrainPass({loaded: 119, audit: null}, 14.9, {other: 0.2}), false);
});

test("the singleplayer harness samples carry the render distance the settle loop reads", async () => {
  const harness = await readFile(new URL("../port/scripts/minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
  const sampleBody = /async function sample\([^)]*\) \{([\s\S]*?)\n\}/.exec(harness)?.[1] || "";
  assert.match(sampleBody, /dist:\s*s\.dist/);
  assert.match(harness, /terrainCompleteness\(row\)/);
});

test("the flying performance window is recognised by the name it is captured under", async () => {
  const harness = await readFile(new URL("../port/scripts/minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
  const pattern = /const flyingWindow = (\/.*\/)\.test\(state\);/.exec(harness);
  assert.ok(pattern, "flying window detection");
  const matcher = new RegExp(pattern[1].slice(1, -1));
  for (const call of harness.matchAll(/captureMainPerformance\(cdp, "([^"]+)"\)/g)) {
    assert.equal(matcher.test(call[1]), call[1].endsWith("flying"), call[1]);
  }
  assert.ok(matcher.test("perf-flying"));
  assert.ok(!matcher.test("perf-standing"));
});

// ---- creative flight ------------------------------------------------------------------------------
test("flight is enabled only when the height holds after the double tap", async () => {
  let y = 70, flying = false, taps = 0, lifted = 0;
  const player = {x: 0, z: 0};
  const result = await enableCreativeFlight({
    sleep: async () => {},
    tap: async () => { taps++; if (taps % 2 === 0 && taps >= 4) flying = true; },   // the first double tap is lost
    readPlayer: async () => { if (!flying) y -= 3; return {...player, y}; },
    lift: async () => { lifted++; y = 170; },
  });
  assert.equal(result.enabled, true);
  assert.equal(result.attempts.length, 2);
  assert.equal(result.attempts[0].stable, false);
  assert.equal(result.attempts[1].stable, true);
  assert.equal(lifted, 2);
  assert.equal(result.attempts[1].airborne, true);
  const never = await enableCreativeFlight({sleep: async () => {}, attempts: 2, tap: async () => {}, lift: async () => {}, readPlayer: async () => ({x: 0, z: 0, y: (y -= 2)})});
  assert.equal(never.enabled, false);
  assert.equal((await enableCreativeFlight({sleep: async () => {}, tap: async () => {}, lift: async () => {}, readPlayer: async () => null})).enabled, false);
});

test("a stable player standing on terrain is not mistaken for creative flight", async () => {
  let y = 64;
  const result = await enableCreativeFlight({
    sleep: async () => {}, attempts: 2, tap: async () => {},
    lift: async () => { y = 164; },
    readPlayer: async () => ({x: 0, z: 0, y: Math.max(64, y - 100)}),
  });
  assert.equal(result.enabled, false);
  assert.equal(result.attempts.every(attempt => !attempt.airborne), true);
});

test("a flying window must move and a held key is released exactly once", async () => {
  assert.equal(flightWindowMovement({start: {x: 0, y: 170, z: 0}, end: {x: 0, y: 170, z: 218}, durationMs: 20000}).ok, true);
  const still = flightWindowMovement({start: {x: 0, y: 170, z: 0}, end: {x: 0.1, y: 170, z: 0.2}, durationMs: 20000});
  assert.equal(still.ok, false);
  assert.equal(flightWindowMovement({start: null, end: {x: 1, z: 1}, durationMs: 1000}).ok, false);
  const events = [];
  const release = await holdKeyDuring({down: () => events.push("down"), repeat: () => events.push("repeat"), up: () => events.push("up"), repeatMs: 5});
  await new Promise(resolve => setTimeout(resolve, 30));
  await release(); await release();
  assert.equal(events[0], "down");
  assert.ok(events.includes("repeat"));
  assert.equal(events.filter(event => event === "up").length, 1);
  assert.equal(events.at(-1), "up");
});
