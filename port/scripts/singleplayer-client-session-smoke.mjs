#!/usr/bin/env node

import {CANONICAL_PROFILE, LEGACY_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const sourceUrl = new URL(
  "../src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java",
  import.meta.url,
);
const source = await readFile(sourceUrl, "utf8");

for (const contract of [
  "worker.__gaiusClientPort = channel.port1",
  "worker.__gaiusLaunchGeneration = launchGeneration",
  "globalThis.__gaiusSingleplayerLaunchGeneration",
  "worker.__gaiusRuntimeReady = false",
  "worker.__gaiusServerReady = false",
  "workers.get(sessionId) === worker",
  "ports.get(sessionId) === worker.__gaiusClientPort",
  "message.type === 'runtime-ready'",
  "message.type === 'server-listener-ready'",
  "connectWhenWorkerReady(minecraft, sessionId, launchGeneration, 0)",
  "HostLoop.startThread(",
  "initialGameRules: String(initialGameRules || '')",
  "profileId: globalThis.__gaiusProfileId || null",
  "worldVersion: globalThis.__gaiusWorldVersion || null",
  "storageSchema: globalThis.__gaiusStorageSchema || null",
  "storageDatabaseName: globalThis.__gaiusStorageDatabaseName || null",
  "storagePrefix: globalThis.__gaiusStoragePrefix || null",
  "storageOpfsDirectory: globalThis.__gaiusStorageOpfsDirectory || null",
  "globalThis.__gaiusStorageDatabaseName || ''",
  "Number(globalThis.__gaiusStorageSchema)",
  "storageDatabaseName === 'gaius-fs-v2-" + LEGACY_PROFILE.id + "'",
  "storageDatabaseName === 'gaius-fs-v2-" + CANONICAL_PROFILE.id + "'",
  "storageMatchesProfile",
  "gaius-fs-v2-" + LEGACY_PROFILE.id + "",
  "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
  "gaius.fs.v2:" + LEGACY_PROFILE.id + ":",
  "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
  "regions-v2-" + LEGACY_PROFILE.id + "",
  "regions-v2-" + CANONICAL_PROFILE.id + "",
  "storageSchema === 2",
  "Promise.reject(new Error(",
  "indexedDB.open(storageDatabaseName, storageSchema)",
  "localWorkerState(sessionId, launchGeneration)",
  "reportAttachFailure(sessionId, launchGeneration, detail)",
  "rollbackLaunch(detail)",
  "openKeyCursor(range)",
  "const refreshGeneration = Number(",
  "globalThis.__gaiusPersistentFilesGeneration || 0",
  "refreshGeneration",
  "Deletion advances the generation before awaiting storage cleanup",
  "message.type === 'storage-flushing'",
  "message.type === 'storage-closed'",
  "worker.__gaiusStopTimeout",
  "measurementId: worker.__gaiusTelemetryMeasurementId",
  "message.measurementId",
  "worker.__gaiusTelemetryWorkerResetAt",
  "workerResetAt",
  "worker.__gaiusTelemetryMeasurementMismatches",
  "beginClientHandoff(sessionId, launchGeneration)",
  "globalThis.__gaiusSingleplayerHandoffGeneration",
  "singleplayer:handoff-disconnect-ignored",
  "const resolvedWorkerUrl = new URL(",
  "pageUrl.searchParams.get('gaiusMobAiTelemetry') === '1'",
  "resolvedWorkerUrl.searchParams.set('gaiusMobAiTelemetry', '1')",
  "globalThis.__gaiusSingleplayerWorkerUrl ||",
  "configuredWorkerUrl.protocol !== 'blob:'",
  "type: 'diagnostic-config'",
  "gaiusMobAiTelemetry: true",
  "gaiusServerTickTelemetry",
  "message.serverTick",
  "worker.__gaiusTelemetryServerTick",
  "state.serverTick = copyScalarTelemetry(worker.__gaiusTelemetryServerTick)",
  "private static final int READY_POLL_LIMIT = 7_200",
  "const STARTUP_INITIAL_DEADLINE_MS = 60000",
  "const STARTUP_PROGRESS_GRACE_MS = 30000",
  "const STARTUP_ABSOLUTE_DEADLINE_MS = 180000",
  "globalThis.__gaiusSingleplayerFailures",
  "const rememberStartupFailure = function(detail)",
  "const recordStartupProgress = function(kind, detail, counters)",
  "const recordTelemetryProgress = function(message)",
  "const armStartupWatchdog = function()",
  "A heartbeat proves transport only",
  "private static native String reportAttachFailure(",
  "return String(remembered && remembered.detail ? remembered.detail : detail)",
  "worker.__gaiusStartupSeenPhases",
  "Math.max(",
  "future-pump-waiting",
  "startup.future-pump.tasks",
]) {
  assert.ok(source.includes(contract), `missing session contract: ${contract}`);
}
const refreshGenerationStart = source.indexOf("const refreshGeneration = Number(");
const refreshGenerationCheck = source.indexOf(
  "globalThis.__gaiusPersistentFilesGeneration || 0) !==\n                        refreshGeneration",
  refreshGenerationStart,
);
assert.ok(refreshGenerationStart >= 0 && refreshGenerationCheck > refreshGenerationStart,
  "metadata refresh must discard snapshots invalidated by a world deletion");
const refreshAwait = source.indexOf("return listMetadataPaths.then(readMetadata).then(function(values) {");
const refreshWrite = source.indexOf("files[entry.path] = entry.value;", refreshAwait);
assert.ok(refreshAwait >= 0 && refreshGenerationCheck > refreshAwait && refreshWrite > refreshGenerationCheck,
  "refresh/delete interleaving must check the generation before writing metadata back");
const receivePongStart = source.indexOf("const receiveWorkerTelemetryPong = function(message)");
const receivePongEnd = source.indexOf("worker.__gaiusStopTelemetry = stopWorkerTelemetry", receivePongStart);
assert.ok(receivePongStart >= 0 && receivePongEnd > receivePongStart,
  "singleplayer telemetry pong handler could not be located");
const receivePong = source.slice(receivePongStart, receivePongEnd);
assert.ok(receivePong.indexOf("message.measurementId") <
  receivePong.indexOf("worker.__gaiusTelemetryPending.delete(sequence)"),
  "measurement-mismatched pong was accepted before validation");
assert.equal(
  source.includes("indexedDB.open('gaius-fs-v1'") ||
    source.includes('indexedDB.open("gaius-fs-v1"'),
  false,
  "singleplayer storage refresh still opens the legacy IndexedDB name",
);
const readyPollEnd = source.indexOf("READY_POLL_MILLIS);");
// HostLoop.schedule/startThread are TeaVM's Platform.schedule/startThread in the JavaScript build.
const readyPollStart = source.lastIndexOf("HostLoop.schedule(", readyPollEnd);
assert.ok(readyPollStart >= 0 && readyPollEnd > readyPollStart,
  "singleplayer readiness poll could not be located");
const readyPoll = source
  .slice(readyPollStart, readyPollEnd + "READY_POLL_MILLIS);".length)
  .replace(/\s+/g, "");
assert.equal(
  readyPoll,
  "HostLoop.schedule(()->HostLoop.startThread(()->connectWhenWorkerReady(minecraft,sessionId,launchGeneration,pollCount+1)),READY_POLL_MILLIS);",
  "singleplayer readiness poll must enter through HostLoop.startThread (TeaVM Platform.startThread)",
);
assert.equal(source.includes(".getAll()"), false,
  "singleplayer shutdown still performs an IndexedDB full-store read");
const attachFailureStart = source.indexOf("if (state < 0 || pollCount >= READY_POLL_LIMIT)");
const attachFailureEnd = source.indexOf("return;", attachFailureStart);
assert.ok(attachFailureStart >= 0 && attachFailureEnd > attachFailureStart,
  "singleplayer attach-failure branch could not be located");
const attachFailure = source.slice(attachFailureStart, attachFailureEnd);
assert.ok(attachFailure.indexOf("reportAttachFailure(sessionId, launchGeneration, detail)") >= 0,
  "attach failure did not retain its error event");
assert.ok(attachFailure.indexOf("cancelClientHandoff(sessionId, launchGeneration)") <
  attachFailure.indexOf("requestWorkerStop(sessionId, launchGeneration)"),
  "attach failure did not clear handoff before targeted Worker stop");
assert.ok(attachFailure.indexOf("requestWorkerStop(sessionId, launchGeneration)") <
  attachFailure.indexOf("minecraft.gaius$setScreen(new DisconnectedScreen("),
  "attach failure did not stop its Worker before returning to the title screen");

const ports = new Map();
const workers = new Map();

function createSession(sessionId, launchGeneration = "1") {
  const port = {sessionId, closed: false};
  const worker = {
    __gaiusLaunchGeneration: launchGeneration,
    __gaiusClientPort: port,
    __gaiusClientAttached: false,
    __gaiusRuntimeReady: false,
    __gaiusServerReady: false,
    __gaiusTerminal: false,
  };
  ports.set(sessionId, port);
  workers.set(sessionId, worker);
  return worker;
}

function cleanup(sessionId, worker) {
  worker.__gaiusTerminal = true;
  if (ports.get(sessionId) === worker.__gaiusClientPort) {
    worker.__gaiusClientPort.closed = true;
    ports.delete(sessionId);
  }
  if (workers.get(sessionId) === worker) workers.delete(sessionId);
}

function workerState(sessionId) {
  const worker = workers.get(sessionId);
  if (!worker || worker.__gaiusTerminal) return -1;
  if (worker.__gaiusClientAttached) return 1;
  if (ports.get(sessionId) !== worker.__gaiusClientPort) {
    ports.set(sessionId, worker.__gaiusClientPort);
  }
  return worker.__gaiusServerReady && ports.get(sessionId) ? 1 : 0;
}

const collisionId = "00".repeat(16);
const oldWorker = createSession(collisionId, "1");
const newWorker = createSession(collisionId, "2");
cleanup(collisionId, oldWorker);
assert.equal(workers.get(collisionId), newWorker, "late old cleanup removed the new worker");
assert.equal(ports.get(collisionId), newWorker.__gaiusClientPort,
  "late old cleanup removed the new client port");

ports.delete(collisionId);
assert.equal(workerState(collisionId), 0, "pending worker should remain pending");
assert.equal(ports.get(collisionId), newWorker.__gaiusClientPort,
  "readiness poll did not restore its owned pending port");

newWorker.__gaiusRuntimeReady = true;
assert.equal(workerState(collisionId), 0,
  "runtime-ready worker connected before its local listener was registered");
newWorker.__gaiusServerReady = true;
assert.equal(workerState(collisionId), 1, "listener-ready worker was not attachable");

ports.delete(collisionId);
newWorker.__gaiusClientAttached = true;
assert.equal(workerState(collisionId), 1, "attached worker should remain active");
assert.equal(ports.has(collisionId), false,
  "attached session incorrectly re-registered a consumed MessagePort");

function extractBody(marker) {
  const end = source.indexOf(marker);
  const annotation = source.lastIndexOf("@JSBody(", end);
  const start = source.indexOf('"""', annotation) + 3;
  const bodyEnd = source.lastIndexOf('""")', end);
  assert.ok(end > 0 && annotation > 0 && bodyEnd > start,
    `could not extract ${marker}`);
  return source.slice(start, bodyEnd).replace(/^\r?\n/, "");
}

// A poll/cleanup from generation 1 must not report, clear, or stop generation
// 2 after a same-key replacement has won the map slot.
{
  const stateScript = extractBody(
    "private static native int localWorkerState(String sessionId, String launchGeneration);",
  );
  const reportScript = extractBody(
    "private static native String reportAttachFailure(\n            String sessionId,\n            String launchGeneration,\n            String detail);",
  );
  const cancelScript = extractBody(
    "private static native void cancelClientHandoff(String sessionId, String launchGeneration);",
  );
  const staleMessages = [];
  const oldWorker = {
    __gaiusLaunchGeneration: "1",
    __gaiusClientPort: {id: "old"},
    __gaiusClientAttached: false,
    __gaiusServerReady: true,
    __gaiusTerminal: false,
    postMessage: (message) => staleMessages.push(["old", message]),
  };
  const newWorker = {
    __gaiusLaunchGeneration: "2",
    __gaiusClientPort: {id: "new"},
    __gaiusClientAttached: false,
    __gaiusServerReady: true,
    __gaiusTerminal: false,
    postMessage: (message) => staleMessages.push(["new", message]),
  };
  const raceContext = {
    Date,
    Map,
    __gaiusMinecraftEvents: [],
    __gaiusSingleplayerHandoff: "same-key",
    __gaiusSingleplayerHandoffGeneration: "2",
    __gaiusSingleplayerWorkers: new Map([["same-key", newWorker]]),
    __gaiusLocalServerPorts: new Map([["same-key", newWorker.__gaiusClientPort]]),
  };
  raceContext.globalThis = raceContext;
  vm.runInNewContext(
    `globalThis.workerState = function(sessionId, launchGeneration) {${stateScript}\n};` +
      `globalThis.reportFailure = function(sessionId, launchGeneration, detail) {${reportScript}\n};` +
      `globalThis.cancelHandoff = function(sessionId, launchGeneration) {${cancelScript}\n};`,
    raceContext,
  );
  assert.equal(raceContext.workerState("same-key", "1"), -2,
    "old readiness poll did not reject a replacement Worker generation");
  raceContext.reportFailure("same-key", "1", "old timeout");
  assert.equal(raceContext.__gaiusMinecraftEvents.length, 0,
    "old readiness poll reported failure for a replacement Worker");
  raceContext.cancelHandoff("same-key", "1");
  assert.equal(raceContext.__gaiusSingleplayerHandoff, "same-key",
    "old readiness poll cleared the replacement handoff session");
  assert.equal(raceContext.__gaiusSingleplayerHandoffGeneration, "2",
    "old readiness poll cleared the replacement handoff generation");

  const stopMarker = "private static native void requestWorkerStop(String sessionId, String launchGeneration);";
  const stopScript = extractBody(stopMarker);
  vm.runInNewContext(
    `globalThis.stopWorker = function(sessionId, launchGeneration) {${stopScript}\n};`,
    raceContext,
  );
  raceContext.stopWorker("same-key", "1");
  assert.deepEqual(staleMessages, [],
    "old targeted stop touched a replacement Worker under the same key");
  assert.equal(raceContext.__gaiusSingleplayerWorkers.get("same-key"), newWorker,
    "old targeted stop removed the replacement Worker");
}

const launchMarker = "private static native String launchWorker(";
const launchEnd = source.indexOf(launchMarker);
const launchAnnotation = source.lastIndexOf("@JSBody(", launchEnd);
const launchStart = source.indexOf('"""', launchAnnotation) + 3;
const launchScriptEnd = source.lastIndexOf('""")', launchEnd);
assert.ok(launchEnd > 0 && launchAnnotation > 0 && launchScriptEnd > launchStart,
  "launchWorker JSBody could not be extracted");
// Java text blocks preserve a regex backslash as `\\d`; normalize that one
// Java escape for the VM fixture, which executes the extracted JS directly.
// Java text blocks carry escaped regex boundaries (\\b/\\d). Normalize both
// forms before evaluating the extracted browser script so the fixture mirrors
// the generated JavaScript rather than treating \b as a literal backslash+b.
const launchScript = source.slice(launchStart, launchScriptEnd)
  .replace(/\\\\([bd])/g, "\\$1");

function createLaunchRuntime(failureMode, options = {}) {
  const channels = [];
  const createdWorkers = [];
  const capturedTimers = options.captureTimers ? [] : null;
  const runtimeSetTimeout = capturedTimers
    ? (callback, delay) => {
        const timer = {callback, delay, cleared: false};
        capturedTimers.push(timer);
        return timer;
      }
    : setTimeout;
  const runtimeClearTimeout = capturedTimers
    ? (timer) => {
        if (timer) timer.cleared = true;
      }
    : clearTimeout;
  class TestPort {
    constructor() {
      this.closed = false;
    }
    close() {
      this.closed = true;
    }
  }
  class TestMessageChannel {
    constructor() {
      this.port1 = new TestPort();
      this.port2 = new TestPort();
      channels.push(this);
    }
  }
  class TestWorker {
    constructor(url) {
      if (failureMode === "constructor") throw new Error("constructor failed");
      this.url = url;
      this.terminated = false;
      this.messages = [];
      this.messageListeners = [];
      createdWorkers.push(this);
    }
    postMessage(message) {
      this.messages.push(message);
      if (failureMode === "postMessage") throw new Error("postMessage failed");
    }
    terminate() {
      this.terminated = true;
    }
    addEventListener(type, listener) {
      if (type === "message" && typeof listener === "function") {
        this.messageListeners.push(listener);
      }
    }
    removeEventListener(type, listener) {
      if (type !== "message") return;
      this.messageListeners = this.messageListeners.filter((entry) => entry !== listener);
    }
    dispatchMessage(data) {
      for (const listener of this.messageListeners.slice()) listener({data});
    }
  }
  const context = {
    Array,
    Date,
    Map,
    Math,
    MessageChannel: TestMessageChannel,
    Number,
    Object,
    Promise,
    String,
    Uint8Array,
    URL,
    Worker: TestWorker,
    clearInterval,
    clearTimeout: runtimeClearTimeout,
    console,
    crypto: {getRandomValues: (bytes) => bytes.fill(7)},
    location: new URL(options.locationHref || "https://client.example/Gaius.html"),
    navigator: {hardwareConcurrency: 8},
    setInterval,
    setTimeout: runtimeSetTimeout,
    __gaiusProfileId: CANONICAL_PROFILE.id,
    __gaiusWorldVersion: 4903,
    __gaiusStorageSchema: 2,
    __gaiusStorageDatabaseName: "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
    __gaiusStoragePrefix: "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
    __gaiusStorageOpfsDirectory: "regions-v2-" + CANONICAL_PROFILE.id + "",
  };
  if (options.workerUrl !== undefined) {
    context.__gaiusSingleplayerWorkerUrl = options.workerUrl;
  }
  context.globalThis = context;
  vm.runInNewContext(
    `globalThis.launchWorker = function(worldId, worldSeed, newWorld, renderDistance, ` +
      `simulationDistance, initialGameRules) {${launchScript}\n};`,
    context,
  );
  return {context, channels, createdWorkers, capturedTimers};
}

// A failed stop must not surface until every previous Worker has reached a
// terminal result. This catches Promise.all's early rejection behavior.
{
  const runtime = createLaunchRuntime(null, {captureTimers: true});
  const makePreviousWorker = (generation) => ({
    __gaiusLaunchGeneration: generation,
    __gaiusTerminal: false,
    __gaiusStopped: false,
    __gaiusStorageClosed: false,
    __gaiusStorageRefresh: Promise.resolve(),
    messageListeners: [],
    addEventListener(type, listener) {
      if (type === "message") this.messageListeners.push(listener);
    },
    removeEventListener(type, listener) {
      this.messageListeners = this.messageListeners.filter((item) => item !== listener);
    },
    postMessage(message) { this.stopMessage = message; },
    dispatch(data) {
      for (const listener of this.messageListeners.slice()) listener({data});
    },
    terminate() { this.terminated = true; },
  });
  const first = makePreviousWorker("old-1");
  const second = makePreviousWorker("old-2");
  runtime.context.__gaiusSingleplayerWorkers = new Map();
  runtime.context.__gaiusMinecraftEvents = [];
  runtime.context.__gaiusSingleplayerWorkers.set("old-1", first);
  runtime.context.__gaiusSingleplayerWorkers.set("old-2", second);

  runtime.context.launchWorker("world", "123", true, 6, 4);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(first.stopMessage.type, "stop");
  assert.equal(second.stopMessage.type, "stop");

  first.dispatch({type: "stopped"});
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(runtime.context.__gaiusMinecraftEvents.length, 0,
    "launch failure surfaced before every previous Worker reached terminal state");

  second.__gaiusStorageClosed = true;
  second.dispatch({type: "stopped"});
  await new Promise((resolve) => setTimeout(resolve, 0));
  const stopErrors = runtime.context.__gaiusMinecraftEvents.filter((event) =>
    event.event === "singleplayer:worker-launch-error");
  assert.equal(stopErrors.length, 1,
    "failed multi-Worker handoff did not report one retryable status");
  assert.match(stopErrors[0].detail, /retry/i,
    "multi-Worker stop failure status did not tell the user it is retryable");
  assert.equal(runtime.createdWorkers[0].messages.some((message) =>
    message.type === "gaius-worker-bootstrap"), false,
  "new Worker received files before every previous Worker had terminal proof");
}

// Save and Quit can finish before the next launch registers a stop listener.
// A bare stopped event must retain a failed-close sentinel for that later launch.
{
  const runtime = createLaunchRuntime(null, {captureTimers: true});
  const oldSessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  await new Promise((resolve) => setTimeout(resolve, 0));
  const oldWorker = runtime.createdWorkers[0];
  oldWorker.onmessage({data: {type: "stopped"}});
  assert.notEqual(oldWorker.__gaiusStorageClosed, true,
    "stopped synthesized storage-close proof without storage-closed");
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.get(oldSessionId), oldWorker,
    "Save and Quit discarded the terminal Worker before storage-close proof");
  assert.equal(oldWorker.__gaiusTerminal, true,
    "missing-close Worker was not retained as a terminal sentinel");

  runtime.context.launchWorker("world", "123", false, 6, 4);
  await new Promise((resolve) => setTimeout(resolve, 0));
  const successor = runtime.createdWorkers[1];
  assert.equal(successor.messages.some((message) =>
    message.type === "gaius-worker-bootstrap"), false,
  "successor bootstrapped after Save and Quit without storage-close proof");
  const stopErrors = runtime.context.__gaiusMinecraftEvents.filter((event) =>
    event.event === "singleplayer:worker-launch-error");
  assert.equal(stopErrors.length, 1,
    "later launch did not refuse the retained missing-close sentinel");
  assert.match(stopErrors[0].detail, /retry/i,
    "missing-close launch refusal was not retryable");
  assert.equal(successor.terminated, true,
    "refused successor Worker was left alive after missing storage-close proof");
  assert.equal(successor.__gaiusTerminal, true,
    "refused successor Worker was not marked terminal");
}

{
  const runtime = createLaunchRuntime("constructor");
  assert.equal(runtime.context.launchWorker("world", "123", true, 6, 4), null);
  assert.equal(runtime.channels.length, 1);
  assert.equal(runtime.channels[0].port1.closed, true,
    "Worker constructor failure leaked MessagePort port1");
  assert.equal(runtime.channels[0].port2.closed, true,
    "Worker constructor failure leaked MessagePort port2");
  assert.equal(runtime.context.__gaiusLocalServerPorts.size, 0,
    "Worker constructor failure leaked the local-port map entry");
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.size, 0,
    "Worker constructor failure leaked the worker map entry");
}

{
  const runtime = createLaunchRuntime(null, {
    locationHref: "https://client.example/Gaius.html?gaiusMobAiTelemetry=1",
    captureTimers: true,
  });
  const sessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  assert.ok(sessionId, "telemetry query fixture did not launch a Worker");
  const worker = runtime.createdWorkers[0];
  assert.equal(worker.messages[0]?.type, "diagnostic-config",
    "Mob AI telemetry opt-in was not sent to the Worker before startup");
  assert.equal(worker.url.searchParams.get("gaiusMobAiTelemetry"), "1",
    "Mob AI telemetry query was not propagated to the Worker URL");
  assert.equal(worker.url.searchParams.get("v"), "dev",
    "Worker URL lost its build cache token while propagating telemetry");
  for (const timer of runtime.capturedTimers) timer.cleared = true;
  worker.terminate();
}

{
  const runtime = createLaunchRuntime(null, {
    locationHref: "https://client.example/Gaius.html?gaiusMobAiTelemetry=1",
    workerUrl: "https://client.example/custom-worker.js?v=custom",
    captureTimers: true,
  });
  const sessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  assert.ok(sessionId, "custom Worker URL fixture did not launch");
  const worker = runtime.createdWorkers[0];
  assert.equal(worker.messages[0]?.type, "diagnostic-config",
    "Mob AI telemetry opt-in was not sent for a configured Worker URL");
  assert.equal(worker.url.searchParams.get("gaiusMobAiTelemetry"), "1",
    "Mob AI telemetry query was not propagated to a configured Worker URL");
  assert.equal(worker.url.searchParams.get("v"), "custom",
    "configured Worker URL lost its cache token while propagating telemetry");
  for (const timer of runtime.capturedTimers) timer.cleared = true;
  worker.terminate();
}

{
  const blobWorkerUrl = "blob:https://client.example/opaque-worker";
  const runtime = createLaunchRuntime(null, {
    locationHref: "https://client.example/Gaius.html?gaiusMobAiTelemetry=1",
    workerUrl: blobWorkerUrl,
    captureTimers: true,
  });
  const sessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  assert.ok(sessionId, "Blob Worker URL fixture did not launch");
  const worker = runtime.createdWorkers[0];
  assert.equal(worker.url, blobWorkerUrl,
    "diagnostic query rewriting changed the opaque Blob Worker URL");
  assert.equal(worker.messages[0]?.type, "diagnostic-config",
    "Blob Worker did not receive the pre-start Mob AI telemetry opt-in");
  for (const timer of runtime.capturedTimers) timer.cleared = true;
  worker.terminate();
}

// The game rules chosen on the Create World screen are not part of the saved level on the
// profiles whose vanilla IntegratedServer takes them as a separate Optional<GameRules>; the Worker
// start message is the only channel the integrated server Worker gets them from.
{
  const rules = '{"minecraft:random_tick_speed":7,"minecraft:advance_time":false}';
  const runtime = createLaunchRuntime(null, {captureTimers: true});
  const sessionId = runtime.context.launchWorker("world", "123", true, 6, 4, rules);
  assert.ok(sessionId, "game rules fixture did not launch a Worker");
  await new Promise((resolve) => setTimeout(resolve, 5));
  const worker = runtime.createdWorkers[0];
  const start = worker.messages.find((message) => message?.type === "start");
  assert.ok(start, "the Worker never received its start message");
  assert.equal(start.initialGameRules, rules,
    "the initial game rules did not reach the Worker start message verbatim");
  assert.equal(start.newWorld, true);
  for (const timer of runtime.capturedTimers) timer.cleared = true;
  worker.terminate();
}

// Opening a saved world, or a profile without a separate rules argument, sends no rules: the
// empty text, never undefined or the string 'undefined' (the Worker decodes any non-empty text).
for (const [label, launch] of [
  ["empty text", (runtime) => runtime.context.launchWorker("world", "123", false, 6, 4, "")],
  ["omitted", (runtime) => runtime.context.launchWorker("world", "123", false, 6, 4)],
]) {
  const runtime = createLaunchRuntime(null, {captureTimers: true});
  assert.ok(launch(runtime), `${label}: fixture did not launch a Worker`);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const worker = runtime.createdWorkers[0];
  const start = worker.messages.find((message) => message?.type === "start");
  assert.ok(start, `${label}: the Worker never received its start message`);
  assert.equal(start.initialGameRules, "", `${label}: a launch without game rules sent a value`);
  for (const timer of runtime.capturedTimers) timer.cleared = true;
  worker.terminate();
}

{
  const runtime = createLaunchRuntime("postMessage");
  assert.notEqual(runtime.context.launchWorker("world", "123", true, 6, 4), null);
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(runtime.createdWorkers[0].terminated, true,
    "start postMessage failure did not terminate the Worker");
  assert.equal(runtime.channels[0].port1.closed, true,
    "start postMessage failure leaked MessagePort port1");
  assert.equal(runtime.channels[0].port2.closed, true,
    "start postMessage failure leaked MessagePort port2");
  assert.equal(runtime.context.__gaiusLocalServerPorts.size, 0,
    "start postMessage failure leaked the local-port map entry");
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.size, 0,
    "start postMessage failure leaked the Worker map entry");
}

{
  const runtime = createLaunchRuntime(null);
  const sessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  assert.notEqual(sessionId, null);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const worker = runtime.createdWorkers[0];
  assert.equal(worker.__gaiusServerReady, false,
    "new Worker started with its server listener marked ready");
  worker.onmessage({data: {type: "server-listener-ready", detail: sessionId}});
  assert.equal(worker.__gaiusServerReady, true,
    "listener-ready event did not release the client connection gate");
  clearTimeout(worker.__gaiusHandoffTimeout);
  worker.terminate();
}

// Startup watchdog: no progress expires the 60 s initial bound, while a
// strictly advancing startup counter renews only the bounded 30 s grace.
{
  const runtime = createLaunchRuntime(null, {captureTimers: true});
  const sessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  const worker = runtime.createdWorkers[0];
  assert.ok(worker.__gaiusHandoffTimeout, "startup watchdog was not armed");
  worker.__gaiusStartupInactivityDeadlineAt = 0;
  worker.__gaiusStartupAbsoluteDeadlineAt = Date.now() + 180000;
  worker.__gaiusHandoffTimeout.callback();
  assert.equal(worker.terminated, true,
    "watchdog did not terminate a no-progress Worker");
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.has(sessionId), false,
    "watchdog left the failed Worker in the active map");
  const failureKey = sessionId + ":" + worker.__gaiusLaunchGeneration;
  const failure = runtime.context.__gaiusSingleplayerFailures.get(failureKey);
  assert.ok(failure && /watchdog expired/.test(failure.detail),
    "watchdog failure was not retained after Worker deletion");
}

{
  const runtime = createLaunchRuntime(null, {captureTimers: true});
  const sessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  const worker = runtime.createdWorkers[0];
  const now = Date.now();
  worker.__gaiusStartupStartedAt = now - 1000;
  worker.__gaiusStartupInactivityDeadlineAt = now + 59000;
  worker.__gaiusStartupAbsoluteDeadlineAt = now + 120000;
  const before = worker.__gaiusStartupInactivityDeadlineAt;
  worker.onmessage({data: {type: "server-startup-progress", detail: "block-states-cached=29184"}});
  const renewed = worker.__gaiusStartupInactivityDeadlineAt;
  assert.ok(renewed >= before,
    "first startup progress shortened the initial 60 second window");
  worker.onmessage({data: {type: "server-startup-progress", detail: "block-states-cached=29184"}});
  assert.equal(worker.__gaiusStartupInactivityDeadlineAt, renewed,
    "repeated identical startup progress incorrectly renewed the grace");
  worker.__gaiusStartupInactivityDeadlineAt = Date.now() + 1000;
  worker.__gaiusTelemetryPending.set(2, Date.now());
  worker.onmessage({data: {
    type: "telemetry-pong",
    sessionId,
    sequence: 2,
    measurementId: "",
    network: {packets: 0},
  }});
  assert.ok(worker.__gaiusStartupInactivityDeadlineAt <= Date.now() + 1100,
    "initial zero telemetry counter incorrectly renewed the grace");
  worker.__gaiusStartupInactivityDeadlineAt = Date.now() + 1000;
  worker.__gaiusTelemetryPending.set(3, Date.now());
  worker.onmessage({data: {
    type: "telemetry-pong",
    sessionId,
    sequence: 3,
    measurementId: "",
    network: {packets: 1},
  }});
  const counterRenewed = worker.__gaiusStartupInactivityDeadlineAt;
  assert.ok(counterRenewed > Date.now() + 500,
    "first telemetry counter did not renew the inactivity grace");
  worker.__gaiusStartupInactivityDeadlineAt = Date.now() + 1000;
  worker.onmessage({data: {
    type: "server-startup-progress",
    detail: "future-pump-waiting polls=11 tasks=0 emptyYields=11 queue=0",
  }});
  const futureWaitDeadline = worker.__gaiusStartupInactivityDeadlineAt;
  worker.onmessage({data: {
    type: "server-startup-progress",
    detail: "future-pump-waiting polls=12 tasks=0 emptyYields=12 queue=0",
  }});
  // Date.now() can tick between the two synthetic progress deliveries even
  // though the watchdog correctly rejects the repeated no-task poll.  Allow
  // that tiny clock jitter while still failing any real grace renewal.
  assert.ok(worker.__gaiusStartupInactivityDeadlineAt <= futureWaitDeadline + 5,
    "stuck future-pump polls incorrectly renewed the grace");
  worker.__gaiusStartupInactivityDeadlineAt = Date.now() + 1000;
  worker.onmessage({data: {
    type: "server-startup-progress",
    detail: "future-pump-waiting polls=13 tasks=1 emptyYields=13 queue=0",
  }});
  assert.ok(worker.__gaiusStartupInactivityDeadlineAt > Date.now() + 20000,
    "completed future-pump task did not renew the grace");
  worker.__gaiusStartupInactivityDeadlineAt = Date.now() + 1000;
  const duplicatePhaseDeadline = worker.__gaiusStartupInactivityDeadlineAt;
  worker.onmessage({data: {type: "server-startup-progress", detail: "block-states-cached=29184"}});
  assert.equal(worker.__gaiusStartupInactivityDeadlineAt, duplicatePhaseDeadline,
    "alternating duplicate phase incorrectly renewed the grace");
  worker.__gaiusTelemetryPending.set(1, Date.now());
  worker.onmessage({data: {
    type: "telemetry-pong",
    sessionId,
    sequence: 1,
    measurementId: "",
    chunkPriority: {},
    network: {},
    globalPump: {},
    worldgen: {},
    serverTick: {},
    storage: {},
  }});
  assert.ok(worker.__gaiusStartupInactivityDeadlineAt <= Date.now() + 1100,
    "heartbeat-only activity incorrectly renewed the grace");
  await new Promise((resolve) => setTimeout(resolve, 5));
  worker.__gaiusStartupInactivityDeadlineAt = Date.now() + 1000;
  worker.onmessage({data: {type: "server-startup-progress", detail: "block-states-cached=29696"}});
  assert.ok(worker.__gaiusStartupInactivityDeadlineAt > Date.now() + 20000,
    "increasing startup counter did not renew the grace");
  worker.__gaiusStartupAbsoluteDeadlineAt = Date.now() + 100;
  worker.onmessage({data: {type: "server-startup-progress", detail: "block-states-cached=30208"}});
  assert.ok(worker.__gaiusStartupInactivityDeadlineAt <= worker.__gaiusStartupAbsoluteDeadlineAt,
    "progress grace exceeded the absolute startup deadline");
  worker.__gaiusStartupAbsoluteDeadlineAt = 0;
  worker.__gaiusStartupInactivityDeadlineAt = 0;
  worker.__gaiusHandoffTimeout.callback();
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.has(sessionId), false,
    "absolute startup deadline did not terminate the fixture Worker");
}

{
  const runtime = createLaunchRuntime(null, {captureTimers: true});
  const oldSessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const oldWorker = runtime.createdWorkers[0];
  const oldGeneration = oldWorker.__gaiusLaunchGeneration;
  runtime.context.__gaiusSingleplayerHandoff = oldSessionId;
  runtime.context.__gaiusSingleplayerHandoffGeneration = oldGeneration;

  const newSessionId = runtime.context.launchWorker("world", "123", true, 6, 4);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const newWorker = runtime.createdWorkers[1];
  const newGeneration = newWorker.__gaiusLaunchGeneration;
  assert.equal(newSessionId, oldSessionId,
    "generation race fixture did not reuse its session key");
  assert.notEqual(newGeneration, oldGeneration,
    "same-key replacement did not receive a fresh launch generation");
  runtime.context.__gaiusSingleplayerHandoff = newSessionId;
  runtime.context.__gaiusSingleplayerHandoffGeneration = newGeneration;

  const lateTimeout = oldWorker.__gaiusHandoffTimeout;
  assert.ok(lateTimeout && typeof lateTimeout.callback === "function",
    "old Worker handoff timeout was not captured");
  // The fixture invokes the watchdog callback immediately; make that
  // invocation represent an actually expired bounded deadline.
  oldWorker.__gaiusStartupAbsoluteDeadlineAt = 0;
  oldWorker.__gaiusStartupInactivityDeadlineAt = 0;
  lateTimeout.callback();
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.get(oldSessionId), newWorker,
    "late old handoff timeout removed the replacement Worker");
  assert.equal(runtime.context.__gaiusLocalServerPorts.get(oldSessionId),
    newWorker.__gaiusClientPort,
    "late old handoff timeout removed the replacement client port");
  assert.equal(runtime.context.__gaiusSingleplayerHandoff, newSessionId,
    "late old handoff timeout cleared the replacement handoff session");
  assert.equal(runtime.context.__gaiusSingleplayerHandoffGeneration, newGeneration,
    "late old handoff timeout cleared the replacement handoff generation");
  assert.equal(oldWorker.__gaiusClientPort.closed, true,
    "late old handoff timeout leaked the old client port");
  assert.equal(newWorker.terminated, false,
    "late old handoff timeout terminated the replacement Worker");

  // A stopped/error callback from the old closure is another cleanup path;
  // it must retain the same-key replacement and its lease as well.
  oldWorker.onmessage({data: {type: "stopped"}});
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.get(oldSessionId), newWorker,
    "old stopped cleanup removed the replacement Worker");
  assert.equal(runtime.context.__gaiusSingleplayerHandoffGeneration, newGeneration,
    "old stopped cleanup cleared the replacement handoff generation");
  assert.equal(oldWorker.__gaiusStorageRefresh, undefined,
    "old stopped cleanup refreshed persistent files for the replacement Worker");
  assert.equal(newWorker.terminated, false,
    "old stopped cleanup terminated the replacement Worker");
}

{
  const stopMarker = "private static native void requestWorkerStop();";
  const stopEnd = source.indexOf(stopMarker);
  const stopAnnotation = source.lastIndexOf("@JSBody(", stopEnd);
  const stopStart = source.indexOf('"""', stopAnnotation) + 3;
  const stopScriptEnd = source.lastIndexOf('""")', stopEnd);
  const messages = [];
  const armed = [];
  const pendingWorker = {
    __gaiusLaunchGeneration: "1",
    __gaiusHandoffPending: true,
    __gaiusClientAttached: false,
    postMessage: (message) => messages.push(message),
    __gaiusArmStopTimeout: (delay, detail) => armed.push({delay, detail}),
  };
  const context = {
    Date,
    Map,
    clearTimeout,
    setTimeout,
    __gaiusMinecraftEvents: [],
    __gaiusSingleplayerHandoff: "pending",
    __gaiusSingleplayerHandoffGeneration: "1",
    __gaiusSingleplayerWorkers: new Map([["pending", pendingWorker]]),
  };
  context.globalThis = context;
  vm.runInNewContext(
    `globalThis.requestWorkerStop = function() {` +
      `${source.slice(stopStart, stopScriptEnd)}\n};`,
    context,
  );
  context.requestWorkerStop();
  assert.equal(messages.length, 0,
    "the delayed disconnect from world handoff stopped the new Worker");
  assert.equal(context.__gaiusMinecraftEvents.at(-1)?.event,
    "singleplayer:handoff-disconnect-ignored");

  context.__gaiusSingleplayerHandoff = "";
  context.__gaiusSingleplayerHandoffGeneration = "";
  context.requestWorkerStop();
  assert.equal(messages.length, 1,
    "explicit disconnect did not send exactly one pending Worker stop");
  assert.equal(messages[0].type, "stop",
    "explicit disconnect did not stop a pending Worker");
  assert.equal(pendingWorker.__gaiusStopRequested, true);
  assert.equal(armed[0].delay, 35000,
    "pending Worker stop did not install the bounded stop deadline");
}

{
  const stopMarker = "private static native void requestWorkerStop(String sessionId, String launchGeneration);";
  const stopEnd = source.indexOf(stopMarker);
  const stopAnnotation = source.lastIndexOf("@JSBody(", stopEnd);
  const stopStart = source.indexOf('"""', stopAnnotation) + 3;
  const stopScriptEnd = source.lastIndexOf('""")', stopEnd);
  assert.ok(stopEnd > 0 && stopAnnotation > 0 && stopScriptEnd > stopStart,
    "targeted Worker stop JSBody could not be extracted");
  const stopScript = source.slice(stopStart, stopScriptEnd);
  const failedMessages = [];
  const failedArmed = [];
  const otherMessages = [];
  const otherArmed = [];
  const failedPort = {closed: false, close() { this.closed = true; }};
  const otherPort = {closed: false, close() { this.closed = true; }};
  const failedWorker = {
    __gaiusLaunchGeneration: "1",
    __gaiusHandoffPending: true,
    __gaiusClientAttached: false,
    __gaiusClientPort: failedPort,
    __gaiusStopTimeout: 0,
    postMessage: (message) => failedMessages.push(message),
    __gaiusArmStopTimeout: (delay, detail) => failedArmed.push({delay, detail}),
  };
  const otherWorker = {
    __gaiusLaunchGeneration: "2",
    __gaiusHandoffPending: true,
    __gaiusClientAttached: false,
    __gaiusClientPort: otherPort,
    __gaiusStopTimeout: 0,
    postMessage: (message) => otherMessages.push(message),
    __gaiusArmStopTimeout: (delay, detail) => otherArmed.push({delay, detail}),
  };
  const targetedContext = {
    Date,
    Map,
    clearTimeout,
    setTimeout,
    __gaiusSingleplayerHandoff: "failed",
    __gaiusSingleplayerHandoffGeneration: "1",
    __gaiusSingleplayerWorkers: new Map([
      ["failed", failedWorker],
      ["other", otherWorker],
    ]),
    __gaiusLocalServerPorts: new Map([
      ["failed", failedPort],
      ["other", otherPort],
    ]),
  };
  targetedContext.globalThis = targetedContext;
  vm.runInNewContext(
    `globalThis.requestWorkerStop = function(sessionId, launchGeneration) {${stopScript}\n};`,
    targetedContext,
  );
  targetedContext.requestWorkerStop("failed", "1");
  assert.equal(targetedContext.__gaiusSingleplayerHandoff, "",
    "targeted attach failure did not clear its handoff lease");
  assert.equal(failedMessages.length, 1,
    "targeted attach failure did not post exactly one Worker stop");
  assert.equal(failedMessages[0].type, "stop",
    "targeted attach failure posted the wrong Worker message");
  assert.equal(failedWorker.__gaiusStopRequested, true,
    "targeted attach failure did not mark its Worker stop request");
  assert.equal(failedWorker.__gaiusHandoffPending, false,
    "targeted attach failure left its handoff pending");
  assert.equal(failedArmed.length, 1,
    "targeted attach failure did not arm its stop timeout");
  assert.equal(failedArmed[0].delay, 35000,
    "targeted attach failure armed the wrong stop timeout");
  assert.equal(otherMessages.length, 0,
    "targeted attach failure stopped another session");
  assert.equal(otherWorker.__gaiusStopRequested, undefined,
    "targeted attach failure marked another session for stop");
  assert.equal(otherArmed.length, 0,
    "targeted attach failure armed another session's stop timeout");

  // A state<0 poll can have already lost its Worker entry.  The targeted
  // helper still clears only that handoff and does not touch a new session.
  targetedContext.__gaiusSingleplayerHandoff = "gone";
  targetedContext.__gaiusSingleplayerHandoffGeneration = "1";
  targetedContext.requestWorkerStop("gone", "1");
  assert.equal(targetedContext.__gaiusSingleplayerHandoff, "gone",
    "stale targeted stop cleared a handoff without its Worker generation");
  assert.equal(otherMessages.length, 0,
    "state<0 attach failure stopped another session");
}

console.log("Singleplayer client session smoke passed");
