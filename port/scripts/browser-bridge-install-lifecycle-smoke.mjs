#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {patcherSource} from "./patcher-sources.mjs";

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
const network = fs.readFileSync(networkPath, "utf8");
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcher = patcherSource("MinecraftClientPatcher");

const installMethodStart = network.indexOf("public static void install() {");
const installMethodEnd = network.indexOf("    /**", installMethodStart + 1);
assert.ok(installMethodStart >= 0 && installMethodEnd > installMethodStart,
  "BrowserClientNetwork.install() could not be isolated");
const installMethod = network.slice(installMethodStart, installMethodEnd);
assert.doesNotMatch(installMethod, /if\s*\(installed\)\s*\{\s*return;/,
  "install() must not permanently bind the first bridge object");
assert.match(installMethod,
  /configureClientPacketDrain\(\);[\s\S]*installed\s*=\s*installInboundPump\(PUMP_CALLBACK\)/,
  "install() must resolve drain configuration before bridge installation");
assert.match(network,
  /private static final BrowserPumpCallback PUMP_CALLBACK = BrowserClientNetwork::pumpInbound;/,
  "per-frame bridge retries must reuse one cached JSFunctor");

const jsBodyStart = network.indexOf("@JSBody(params = \"callback\", script = \"\"\"");
const jsBodyEnd = network.indexOf("    private static native boolean installInboundPump", jsBodyStart);
assert.ok(jsBodyStart >= 0 && jsBodyEnd > jsBodyStart,
  "installInboundPump() JSBody could not be isolated");
const installScript = network.slice(jsBodyStart, jsBodyEnd);
assert.match(installScript, /installedForThisBridge/,
  "bridge installation has no same-object idempotence guard");
assert.match(installScript,
  /__gaiusInboundPumpInstalledBy\s*===\s*bridge/,
  "bridge installation guard is not bound to object identity");
assert.match(installScript,
  /typeof bridge\.inboundPump === 'function'[\s\S]*typeof bridge\.clientPacketDrain === 'function'/,
  "same-object guard does not verify the required bridge hooks");
assert.match(installScript,
  /bridge\.inboundPumpScheduler\.version === 2[\s\S]*__gaiusRetired !== true/,
  "same-object guard does not verify the scheduler generation state");
assert.match(installScript,
  /schedulerIdentityDrift[\s\S]*retireScheduler\(installedScheduler\)/,
  "same-object scheduler replacement does not retire the previously installed scheduler");
assert.match(installScript,
  /__gaiusInboundPumpInstalledScheduler\s*===\s*bridge\.inboundPumpScheduler/,
  "same-object guard does not verify scheduler object identity");
assert.match(installScript, /bridge\.__gaiusInboundPumpInstalledBy\s*=\s*bridge/,
  "successful installation does not publish its identity marker");
assert.match(installScript,
  /bridge\.__gaiusInboundPumpInstalledScheduler\s*=\s*scheduler/,
  "successful installation does not publish its scheduler identity marker");

const configureStart = network.indexOf("private static native void configureClientPacketDrain");
const configureScriptStart = network.lastIndexOf('@JSBody(script = """', configureStart);
assert.ok(configureScriptStart >= 0 && configureStart > configureScriptStart,
  "configureClientPacketDrain() JSBody could not be isolated");
const configureScript = network.slice(configureScriptStart, configureStart);
assert.match(configureScript,
  /__gaiusClientPacketDrainConfiguredSearch\s*===\s*search[\s\S]*return;/,
  "URL drain configuration reparses an unchanged query on every frame");
assert.match(configureScript,
  /new URLSearchParams\(search\)[\s\S]*__gaiusClientPacketDrainConfiguredSearch\s*=\s*search/,
  "URL drain configuration does not bind the parse sentinel to the observed query");

const runTickHookStart = patcher.indexOf('method.name.equals("runTick") && method.desc.equals("(Z)V")');
const runTickHookEnd = patcher.indexOf('} else if (method.name.equals("debugClientMetricsStart")', runTickHookStart);
assert.ok(runTickHookStart >= 0 && runTickHookEnd > runTickHookStart,
  "Minecraft.runTick hook could not be isolated");
const runTickHook = patcher.slice(runTickHookStart, runTickHookEnd);
assert.match(runTickHook, /"dev\/gaius\/browser\/BrowserClientNetwork",\s*\n\s*"install"/,
  "Minecraft.runTick no longer retries bridge installation each frame");

// Keep this model deliberately equivalent to the bounded JS guard above.  It checks the
// lifecycle semantics without launching Chrome or entering Java/TeaVM: one healthy bridge is
// reused, a replacement bridge is installed, and a retired scheduler is rebuilt.
function hook(name) {
  return function bridgeHook() { return name; };
}
function ensureInstalled(bridge, stats) {
  if (!bridge) return false;
  const scheduler = bridge.inboundPumpScheduler;
  const installedScheduler = bridge.__gaiusInboundPumpInstalledScheduler;
  const installedForThisBridge = bridge.__gaiusInboundPumpInstalledBy === bridge &&
    installedScheduler === scheduler &&
    typeof bridge.inboundPump === "function" &&
    typeof bridge.clientPacketDrain === "function" &&
    typeof bridge.invalidateClientPacketDrain === "function" &&
    scheduler && scheduler.version === 2 && scheduler.__gaiusRetired !== true;
  if (installedForThisBridge) return true;
  if (installedScheduler && installedScheduler !== scheduler) {
    installedScheduler.__gaiusRetired = true;
    installedScheduler.pending = null;
  }
  if (scheduler && scheduler.__gaiusRetired === true) scheduler.pending = null;
  bridge.inboundPumpScheduler = {
    version: 2,
    generation: (Number(scheduler?.generation) || 0) + 1,
    pending: null,
    __gaiusRetired: false,
  };
  bridge.inboundPump = hook("inbound");
  bridge.clientPacketDrain = hook("drain");
  bridge.invalidateClientPacketDrain = hook("invalidate");
  bridge.__gaiusInboundPumpInstalledBy = bridge;
  bridge.__gaiusInboundPumpInstalledScheduler = bridge.inboundPumpScheduler;
  stats.inboundPumpInstalled++;
  return true;
}

const stats = {inboundPumpInstalled: 0};
const firstBridge = {stats};
assert.equal(ensureInstalled(firstBridge, stats), true);
assert.equal(stats.inboundPumpInstalled, 1);
const pending = {token: 7};
firstBridge.inboundPumpScheduler.pending = pending;
assert.equal(ensureInstalled(firstBridge, stats), true,
  "reinstall on the same bridge should remain a no-op");
assert.equal(stats.inboundPumpInstalled, 1,
  "same-bridge install recreated the scheduler");
assert.equal(firstBridge.inboundPumpScheduler.pending, pending,
  "same-bridge install cancelled a pending callback");

// A same-object scheduler replacement must not pass the cheap bridge marker guard.  Rebuilding
// here retires the old closure/generation and prevents a copied marker from suppressing the
// current bridge's pump after an embedding page swaps only its scheduler.
const replacedScheduler = {
  version: 2,
  generation: firstBridge.inboundPumpScheduler.generation + 1,
  pending: null,
  __gaiusRetired: false,
};
const previousScheduler = firstBridge.__gaiusInboundPumpInstalledScheduler;
firstBridge.inboundPumpScheduler = replacedScheduler;
const installationCountBeforeSchedulerReplacement = stats.inboundPumpInstalled;
assert.equal(ensureInstalled(firstBridge, stats), true,
  "same-bridge scheduler replacement was not repaired");
assert.equal(stats.inboundPumpInstalled, installationCountBeforeSchedulerReplacement + 1,
  "same-bridge scheduler replacement silently reused the stale installation");
assert.equal(firstBridge.__gaiusInboundPumpInstalledScheduler,
  firstBridge.inboundPumpScheduler,
  "scheduler replacement did not publish the repaired scheduler marker");
assert.equal(previousScheduler.__gaiusRetired, true,
  "same-bridge scheduler replacement left the old scheduler live");
assert.equal(previousScheduler.pending, null,
  "same-bridge scheduler replacement left a stale pending callback");
assert.equal(replacedScheduler.__gaiusRetired, false,
  "the replacement scheduler was incorrectly retired while being installed");

const secondBridge = {stats};
assert.equal(ensureInstalled(secondBridge, stats), true,
  "replacement bridge was not installed");
assert.equal(stats.inboundPumpInstalled, 3,
  "replacement bridge did not create exactly one new installation");
assert.notEqual(secondBridge.inboundPumpScheduler, firstBridge.inboundPumpScheduler,
  "replacement bridge reused the retired scheduler");
assert.equal(firstBridge.__gaiusInboundPumpInstalledBy, firstBridge);
assert.equal(secondBridge.__gaiusInboundPumpInstalledBy, secondBridge);

// A copied marker must not authorize closures from the old bridge.
const copiedMarkerBridge = {
  stats,
  __gaiusInboundPumpInstalledBy: firstBridge,
  inboundPump: firstBridge.inboundPump,
  clientPacketDrain: firstBridge.clientPacketDrain,
  invalidateClientPacketDrain: firstBridge.invalidateClientPacketDrain,
  inboundPumpScheduler: firstBridge.inboundPumpScheduler,
};
assert.equal(ensureInstalled(copiedMarkerBridge, stats), true);
assert.equal(stats.inboundPumpInstalled, 4,
  "copied bridge marker incorrectly skipped replacement installation");

secondBridge.inboundPumpScheduler.__gaiusRetired = true;
const installationCountBeforeRetired = stats.inboundPumpInstalled;
assert.equal(ensureInstalled(secondBridge, stats), true,
  "retired scheduler was not rebuilt");
assert.equal(stats.inboundPumpInstalled, installationCountBeforeRetired + 1,
  "retired scheduler rebuild did not produce one replacement");
assert.equal(secondBridge.inboundPumpScheduler.__gaiusRetired, false);

// Model the per-frame URL configuration path as well.  An absent query is parsed once for a
// stable page, while an explicit embedder boolean still wins and a changed search string gets a
// single re-parse.  Remote-session promotion remains possible after the absent-query sentinel.
function configureDrainModel(state, search, queryValue) {
  if (typeof state.enabled === "boolean") return;
  if (state.configuredSearch === search) return;
  state.parseCount++;
  state.configuredSearch = search;
  if (queryValue === "1" || queryValue === "true" || queryValue === "on") {
    state.enabled = true;
    state.autoEnabled = false;
  } else if (queryValue === "0" || queryValue === "false" || queryValue === "off") {
    state.enabled = false;
    state.autoEnabled = false;
  }
}
const drainConfig = {parseCount: 0, configuredSearch: undefined};
for (let frame = 0; frame < 1000; frame++) configureDrainModel(drainConfig, "", null);
assert.equal(drainConfig.parseCount, 1,
  "unchanged absent drain query was parsed more than once across 1000 frames");
configureDrainModel(drainConfig, "?clientPacketDrain=true", "true");
assert.equal(drainConfig.parseCount, 2,
  "changed drain query did not trigger one re-parse");
assert.equal(drainConfig.enabled, true,
  "changed explicit drain query did not enable the flag");
drainConfig.enabled = undefined;
configureDrainModel(drainConfig, "?clientPacketDrain=true", "true");
assert.equal(drainConfig.parseCount, 2,
  "the same explicit drain query was parsed again after the flag was cleared");
drainConfig.enabled = undefined;
drainConfig.configuredSearch = "";
configureDrainModel(drainConfig, "", null);
drainConfig.enabled = true;
assert.equal(drainConfig.enabled, true,
  "remote-session promotion was not allowed after absent-query configuration");

console.log(JSON.stringify({
  smoke: "browser-bridge-install-lifecycle",
  result: "pass",
  sameBridgeIdempotent: true,
  pendingCallbackPreserved: true,
  replacementBridgeInstalled: true,
  copiedMarkerRejected: true,
  retiredSchedulerRebuilt: true,
  schedulerReplacementRepaired: true,
  urlQueryParseBounded: true,
  installations: stats.inboundPumpInstalled,
  teaVmRuntimeProof: false,
  publicRelayRuntimeProof: false,
}));
