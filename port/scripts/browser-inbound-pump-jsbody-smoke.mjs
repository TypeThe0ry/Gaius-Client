#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const networkPath = path.join(
  repository,
  "port",
  "src",
  "main",
  "java",
  "dev",
  "gaius",
  "browser",
  "BrowserClientNetwork.java",
);
const source = fs.readFileSync(networkPath, "utf8");

function extractBody(startMarker, endMarker) {
  const endMarkerIndex = source.indexOf(endMarker);
  const bodyStart = source.lastIndexOf(startMarker, endMarkerIndex);
  const bodyEnd = source.lastIndexOf('    """)', endMarkerIndex);
  assert.ok(bodyStart >= 0 && endMarkerIndex > bodyStart && bodyEnd > bodyStart,
    `could not isolate JSBody ${startMarker}`);
  const raw = source.slice(bodyStart + startMarker.length, bodyEnd)
    .replace(/^\r?\n/, "")
    .split(/\r?\n/);
  const nonEmpty = raw.filter((line) => line.trim().length > 0);
  const indent = nonEmpty.length === 0
    ? 0
    : Math.min(...nonEmpty.map((line) => line.match(/^ */u)?.[0].length ?? 0));
  return raw.map((line) => line.slice(indent)).join("\n");
}

const installScript = extractBody(
  '@JSBody(params = "callback", script = """',
  '    private static native boolean installInboundPump',
);
const configureScript = extractBody(
  '@JSBody(script = """',
  '    private static native void configureClientPacketDrain',
);

// Execute the production JSBody rather than a hand-written model.  The harness supplies only
// the browser globals the body is allowed to use and keeps all state in a temporary bridge.
const installInboundPump = new Function("callback", installScript);
const configureClientPacketDrain = new Function(configureScript);
const priorBridge = globalThis.__gaiusNettyBridge;
const priorConfiguredSearch = globalThis.__gaiusClientPacketDrainConfiguredSearch;
const priorDrainEnabled = globalThis.__gaiusClientPacketDrainEnabled;
const priorDrainAutoEnabled = globalThis.__gaiusClientPacketDrainAutoEnabled;
const priorRecovery = globalThis.__gaiusMultiplayerRecovery;
const priorLocation = globalThis.location;
const priorURLSearchParams = globalThis.URLSearchParams;

function closeScheduler(scheduler) {
  if (!scheduler) return;
  scheduler.__gaiusRetired = true;
  if (scheduler.pending?.watchdog) {
    clearTimeout(scheduler.pending.watchdog);
    scheduler.pending.watchdog = 0;
  }
  scheduler.pending = null;
  if (scheduler.channel) {
    try { scheduler.channel.port1.onmessage = null; } catch {}
    try { scheduler.channel.port1.close(); } catch {}
    try { scheduler.channel.port2.close(); } catch {}
  }
}

try {
  let callbackCalls = 0;
  const stats = {};
  const bridge = {stats};
  globalThis.__gaiusNettyBridge = bridge;
  delete globalThis.__gaiusMultiplayerRecovery;

  const callback = () => {
    callbackCalls++;
    return false;
  };

  assert.equal(installInboundPump(callback), true,
    "production installInboundPump JSBody did not install on a fresh bridge");
  const firstScheduler = bridge.inboundPumpScheduler;
  assert.ok(firstScheduler && firstScheduler.version === 2,
    "fresh bridge did not receive a versioned scheduler");
  assert.strictEqual(bridge.__gaiusInboundPumpInstalledScheduler, firstScheduler,
    "fresh bridge did not publish its scheduler identity marker");

  bridge.inboundPump("old-generation");
  const oldPending = firstScheduler.pending;
  assert.ok(oldPending && typeof oldPending.finish === "function",
    "old bridge pump did not retain a pending callback record");
  assert.equal(installInboundPump(callback), true,
    "same bridge reinstall failed");
  assert.strictEqual(firstScheduler.pending, oldPending,
    "same bridge reinstall cancelled a pending callback");

  // Replace only the scheduler object on the same bridge.  The production guard must retire the
  // old scheduler and create a fresh generation instead of trusting the copied bridge marker.
  const replacementScheduler = {
    version: 2,
    generation: (Number(firstScheduler.generation) || 1) + 1,
    pending: null,
    running: false,
    nextToken: 1,
    reportPending: false,
    reportDirty: false,
    reportStage: "",
    reportGeneration: 0,
    reportHandle: 0,
    __gaiusRetired: false,
  };
  bridge.inboundPumpScheduler = replacementScheduler;
  assert.equal(installInboundPump(callback), true,
    "same bridge scheduler replacement was not repaired");
  const currentScheduler = bridge.inboundPumpScheduler;
  assert.notStrictEqual(currentScheduler, replacementScheduler,
    "externally supplied scheduler was trusted instead of rebuilt");
  assert.notStrictEqual(currentScheduler, firstScheduler,
    "same bridge scheduler replacement reused the retired scheduler");
  assert.equal(firstScheduler.__gaiusRetired, true,
    "old scheduler was not retired after same bridge replacement");
  assert.equal(firstScheduler.pending, null,
    "old scheduler pending callback survived same bridge replacement");
  assert.equal(oldPending.watchdog, 0,
    "old scheduler watchdog survived same bridge replacement");
  oldPending.finish("late-old-message");
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(callbackCalls, 0,
    "late callback from a retired scheduler re-entered the Java callback");
  assert.ok((stats.inboundPumpStaleCallbacks | 0) >= 1,
    "retired scheduler callback was not recorded as stale");

  bridge.inboundPump("current-generation");
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(callbackCalls, 1,
    "current scheduler callback did not reach the installed pump");
  assert.equal(currentScheduler.pending, null,
    "current scheduler pending callback did not settle");

  // Execute configureClientPacketDrain as shipped and count actual URLSearchParams allocations.
  // An unchanged query must be parsed once; an explicit query change must trigger exactly one
  // additional parse without overriding a later remote-session promotion.
  let parseCount = 0;
  const OriginalURLSearchParams = priorURLSearchParams;
  class CountingURLSearchParams extends OriginalURLSearchParams {
    constructor(...args) {
      parseCount++;
      super(...args);
    }
  }
  globalThis.URLSearchParams = CountingURLSearchParams;
  globalThis.location = {search: ""};
  delete globalThis.__gaiusClientPacketDrainEnabled;
  delete globalThis.__gaiusClientPacketDrainAutoEnabled;
  delete globalThis.__gaiusClientPacketDrainConfiguredSearch;
  for (let frame = 0; frame < 1000; frame++) configureClientPacketDrain();
  assert.equal(parseCount, 1,
    "unchanged absent drain query allocated URLSearchParams more than once");
  globalThis.location.search = "?clientPacketDrain=true";
  configureClientPacketDrain();
  assert.equal(parseCount, 2,
    "changed drain query did not trigger one fresh parse");
  assert.equal(globalThis.__gaiusClientPacketDrainEnabled, true,
    "explicit changed drain query did not enable the production flag");
  delete globalThis.__gaiusClientPacketDrainEnabled;
  configureClientPacketDrain();
  assert.equal(parseCount, 2,
    "the same explicit query was reparsed after the flag was cleared");

  console.log(JSON.stringify({
    smoke: "browser-inbound-pump-jsbody",
    result: "pass",
    sameBridgePendingPreserved: true,
    sameBridgeSchedulerReplacementRebuilt: true,
    retiredPendingCleared: true,
    retiredWatchdogCleared: true,
    staleCallbackRejected: true,
    currentCallbackDelivered: true,
    urlSearchParamsParseCount: parseCount,
    urlQueryParseBounded: true,
    teaVmRuntimeProof: false,
    publicRelayRuntimeProof: false,
  }));
} finally {
  closeScheduler(globalThis.__gaiusNettyBridge?.inboundPumpScheduler);
  if (priorBridge === undefined) delete globalThis.__gaiusNettyBridge;
  else globalThis.__gaiusNettyBridge = priorBridge;
  if (priorConfiguredSearch === undefined) delete globalThis.__gaiusClientPacketDrainConfiguredSearch;
  else globalThis.__gaiusClientPacketDrainConfiguredSearch = priorConfiguredSearch;
  if (priorDrainEnabled === undefined) delete globalThis.__gaiusClientPacketDrainEnabled;
  else globalThis.__gaiusClientPacketDrainEnabled = priorDrainEnabled;
  if (priorDrainAutoEnabled === undefined) delete globalThis.__gaiusClientPacketDrainAutoEnabled;
  else globalThis.__gaiusClientPacketDrainAutoEnabled = priorDrainAutoEnabled;
  if (priorRecovery === undefined) delete globalThis.__gaiusMultiplayerRecovery;
  else globalThis.__gaiusMultiplayerRecovery = priorRecovery;
  if (priorLocation === undefined) delete globalThis.location;
  else globalThis.location = priorLocation;
  if (priorURLSearchParams === undefined) delete globalThis.URLSearchParams;
  else globalThis.URLSearchParams = priorURLSearchParams;
}
