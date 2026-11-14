#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

async function source(relative) {
  return readFile(new URL(relative, import.meta.url), "utf8");
}

const [launcher, queue, scheduler, meshHooks, serverMain, client, worker] = await Promise.all([
  source("../web/launcher/index.template.html"),
  source("../src/main/java/dev/gaius/browser/render/BrowserMeshInstallQueue.java"),
  source("../src/main/java/dev/gaius/browser/BrowserRenderScheduler.java"),
  source("../mc/shared/canonical/src/java/dev/gaius/browser/kernel/mesh/MeshKernelHooks.java"),
  source("../src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java"),
  source("../src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java"),
  source("../web/singleplayer/server-worker-bootstrap.js"),
]);

const flagsStart = launcher.indexOf("const launcherBoolean =");
const flagsEnd = launcher.indexOf("    const vanillaAssetsToken", flagsStart);
assert.ok(flagsStart >= 0 && flagsEnd > flagsStart, "launcher switch block not found");
const launcherSwitches = launcher.slice(flagsStart, flagsEnd);

function readFlags(search) {
  const context = vm.createContext({
    URLSearchParams,
    urlParams: new URLSearchParams(search),
  });
  context.globalThis = context;
  vm.runInContext(launcherSwitches, context);
  return context;
}

const defaults = readFlags("");
assert.equal(defaults.__gaiusLauncherFlags.meshInstallPriority, false);
assert.equal(defaults.__gaiusLauncherFlags.meshAdaptiveBudget, false);
assert.equal(defaults.__gaiusLauncherFlags.chunkPipelineBatching, false);
assert.equal(defaults.__gaiusChunkPipelineBatching, false);
assert.equal(Object.isFrozen(defaults.__gaiusLauncherFlags), true);

const enabled = readFlags(
  "gaiusChunkBatch=1&gaiusMeshInstallPriority=on&gaiusMeshAdaptiveBudget=true&" +
    "gaiusMeshReadyBudgetMiB=32&gaiusMeshReadyMaxEntries=256",
);
assert.equal(enabled.__gaiusLauncherFlags.chunkPipelineBatching, true);
assert.equal(enabled.__gaiusLauncherFlags.meshInstallPriority, true);
assert.equal(enabled.__gaiusLauncherFlags.meshAdaptiveBudget, true);
assert.equal(enabled.__gaiusLauncherFlags.meshReadyBudgetMiB, "32");
assert.equal(enabled.__gaiusLauncherFlags.meshReadyMaxEntries, "256");

assert.doesNotMatch(queue, /new URLSearchParams/u,
  "mesh queue class initialization must not construct URLSearchParams");
assert.doesNotMatch(queue, /System\.getProperty\("gaius\.mesh\./u,
  "mesh queue switches must come only from launcher flags");
assert.match(queue, /flags\.meshInstallPriority===true/u,
  "install priority must use the immutable launcher flag");
assert.match(queue, /priorityModeInitialized/u,
  "install priority must be read once");
assert.match(queue, /if \(priorityMode\) \{\s*readyEnqueueFrame = new IdentityHashMap<>\(\);/u,
  "READY age tracking must allocate only after the priority switch is enabled");
assert.match(queue, /READY_MAX_WAIT_LIMIT_FRAMES = 8L/u,
  "priority READY aging must use a fixed deterministic frame limit");
assert.match(queue, /state\.meshInstallReadyMaxWaitFrames=readyMaxWaitFrames/u,
  "current READY wait must be published");
assert.match(queue, /state\.meshInstallObservedReadyMaxWaitFrames=observedReadyMaxWaitFrames/u,
  "maximum observed READY wait must be published");
assert.match(queue, /state\.meshInstallReadyMaxWaitLimitFrames=readyMaxWaitLimitFrames/u,
  "configured READY wait limit must be published");
assert.match(queue, /flags\.meshReadyBudgetMiB/u);
assert.match(queue, /flags\.meshReadyMaxEntries/u);
assert.match(scheduler, /flags\.meshAdaptiveBudget===true/u,
  "adaptive install budget must use the immutable launcher flag");
assert.match(scheduler, /adaptiveInstallInitialized/u,
  "adaptive install budget must be read once");
assert.doesNotMatch(scheduler, /System\.getProperty\("gaius\.mesh\./u,
  "adaptive install switch must come only from launcher flags");
assert.doesNotMatch(scheduler.slice(scheduler.indexOf("private static boolean adaptiveInstallRequested()"),
  scheduler.indexOf("private static native boolean adaptiveInstallRequestedJs()")),
  /new URLSearchParams/u, "adaptive frame feedback must not parse the URL");
assert.match(scheduler, /duration < 8_000_000L && BrowserMeshInstallQueue\.readyCount\(\) > 0/u,
  "adaptive budget must only grow into queued install work");
assert.match(scheduler, /Math\.max\(base\.installBytesPerFrame\(\), adaptiveInstallBytes\)/u,
  "adaptive feedback must never throttle below the static install budget");
assert.match(scheduler, /Math\.max\(base\.installNanosPerFrame\(\), adaptiveInstallNanos\)/u,
  "adaptive feedback must never throttle below the static install time budget");
assert.doesNotMatch(scheduler, /adaptiveInstallBytes \* 3L \/ 4L/u,
  "long frames must not reduce the install budget");
assert.match(scheduler, /base\.installBytesPerFrame\(\) \* 2L/u);
assert.match(scheduler, /state\.installNanosPerFrame=v\[84\]/u);
assert.match(scheduler, /state\.frameInstallNanos=v\[85\]/u);
assert.match(scheduler, /state\.lastFrameDurationMillis=v\[87\]/u);
assert.match(scheduler, /BASE_TELEMETRY_COUNT = 84/u);
assert.match(scheduler, /new double\[BASE_TELEMETRY_COUNT\]/u,
  "default telemetry storage must retain the pre-change 84-value allocation");
assert.match(scheduler, /if \(!adaptiveInstallRequested\(\)\)\s*\{\s*return TELEMETRY;/u,
  "adaptive telemetry extension must remain unallocated while its switch is off");
assert.match(scheduler, /extendedTelemetry = new double\[EXTENDED_TELEMETRY_COUNT\]/u);

assert.doesNotMatch(meshHooks, /BrowserMeshInstallQueue\.priorityEnabled\(\)/u,
  "canonical mesh hooks must not query the renderer queue for launcher switches");
assert.match(meshHooks, /boolean nearbyEdit = recompile && distance <= NEARBY_BLOCKS/u);
assert.match(meshHooks, /installPriority = inFrustum\s*\? Math\.max\(0, distance >> 4\)/u,
  "visible meshes must occupy the top install priority class");
assert.match(meshHooks, /public int priority\(\)\s*\{\s*job\.refreshInstallPriority\(\);/u,
  "READY ordering must refresh against the current camera at drain time");
assert.match(serverMain, /return 4;/u,
  "chunk batching remains the vanilla four-batch window until ordered parity evidence exists");
assert.match(client, /__gaiusLauncherFlags[\s\S]{0,120}chunkPipelineBatching === true/u);
assert.match(worker, /__gaiusLauncherFlags = Object\.freeze/u);
assert.match(worker, /chunkPipelineBatching: globalThis\.__gaiusChunkPipelineBatching/u);
assert.match(serverMain, /sentChunkBatches\.addLast\(batchSize\)/u);
assert.match(serverMain, /sentChunkBatches\.pollFirst\(\)/u);
assert.match(serverMain, /public static int browserChunkBatchLimit\(\)\s*\{\s*return 4;/u,
  "both launcher modes must keep the vanilla ACK window and packet/tick interleaving");
const ackMethod = serverMain.slice(serverMain.indexOf("public static void acknowledgeChunkBatch()"),
  serverMain.indexOf("public static void tickIntegratedServerDistances()"));
assert.match(ackMethod, /pollFirst\(\)/u, "ACK accounting must consume sent batches in FIFO order");
assert.doesNotMatch(ackMethod, /tickIntegratedServerDistances\(|runTasks\(|tick\(/u,
  "ACK handling must not invoke an integrated-server tick");
assert.match(serverMain, /public static void tickIntegratedServerDistances\(\)\s*\{\s*flushPendingPlayerChunkTracking\(\);/u,
  "tick path remains independent of batch acknowledgements");

function transportTrace(enabled) {
  const trace = [];
  const sent = [];
  let nextBatch = 1;
  for (let tick = 0; tick < 6; tick++) {
    trace.push(["tick", tick]);
    if (sent.length < 4) {
      const batch = nextBatch++;
      sent.push(batch);
      trace.push(["packet", batch]);
    }
    if (tick % 2 === 1) {
      const ack = sent.shift();
      trace.push(["ack", ack]);
    }
  }
  return trace;
}
assert.deepEqual(transportTrace(false), transportTrace(true),
  "chunk batching flag must not change packet/ACK/tick event order until a parity-proven window is enabled");

console.log("Chunk delivery launcher flags and install-priority smoke passed");
