#!/usr/bin/env node

import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import path from "node:path";
import {patcherSource} from "./patcher-sources.mjs";

const scheduler = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserPacketScheduler.java",
  import.meta.url,
), "utf8");
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcher = patcherSource("MinecraftClientPatcher");

const shouldStart = scheduler.indexOf("public static boolean shouldProcessNext()");
const shouldEnd = [
  scheduler.indexOf("public static boolean hasPendingPackets()", shouldStart),
  scheduler.indexOf("public static void packetQueued()", shouldStart),
].filter((index) => index > shouldStart).sort((left, right) => left - right)[0] ?? -1;
assert.ok(shouldStart >= 0 && shouldEnd > shouldStart,
  "BrowserPacketScheduler methods were not found");
const shouldProcessNext = scheduler.slice(shouldStart, shouldEnd);
assert.ok(shouldProcessNext.includes("packetsRemaining--"),
  "per-batch packet execution budget is not bounded");
assert.ok(!shouldProcessNext.includes("queuedPackets"),
  "shouldProcessNext must not pre-decrement the decoded packet queue");
assert.ok(!shouldProcessNext.includes("recordDecodedPacket"),
  "shouldProcessNext must not emit decoded packet completion");
const lifecycleStart = scheduler.indexOf("public static boolean bindPacketProcessorLifecycle(Object owner)");
const lifecycleEnd = scheduler.indexOf("\n    }", lifecycleStart);
assert.ok(lifecycleStart >= 0 && lifecycleEnd > lifecycleStart,
  "PacketProcessor lifecycle bind method is missing");
const lifecycleBody = scheduler.slice(lifecycleStart, lifecycleEnd);
assert.match(lifecycleBody, /return claimPacketProcessorOwner\(owner\);/u,
  "PacketProcessor lifecycle bind must claim the owner");
assert.doesNotMatch(lifecycleBody, /clearRetiredPacketProcessorOwner/u,
  "PacketProcessor lifecycle bind must not resurrect a retired owner");
assert.match(scheduler, /private static int queuedPacketHandleDepth;/,
  "nested queued PLAY packet drain guard state is missing");
for (const contract of [
  "packetProcessorOwner",
  "packetProcessorGeneration",
  "packetProcessorOwnerConflict",
  "packetProcessorAccountingValid",
  "public static boolean bindPacketProcessor(Object owner)",
  "public static boolean bindPacketProcessorLifecycle(Object owner)",
  "public static boolean beginBatch(Object owner)",
  "public static boolean shouldProcessNext(Object owner)",
  "public static void packetQueued(Object owner)",
  "public static void packetProcessed(Object owner)",
  "public static void reset(Object owner)",
]) {
  assert.ok(scheduler.includes(contract), "missing owner accounting contract: " + contract);
}
assert.match(scheduler,
  /public static boolean isProcessingQueuedPacket\(\) \{\s*return queuedPacketHandleDepth > 0;/,
  "queued PLAY packet guard does not cover the complete nested handle scope");
assert.match(scheduler,
  /public static void beginQueuedPacket\(Object packet\) \{[\s\S]*queuedPacketHandleDepth\+\+;/,
  "queued PLAY packet handle entry does not increment its nesting depth");
assert.match(scheduler,
  /public static void packetProcessed\(\) \{[\s\S]*queuedPacketHandleDepth--;[\s\S]*if \(queuedPackets > 0\)/,
  "queued PLAY packet guard is not retired before completion accounting");
assert.match(scheduler,
  /public static void reset\(\) \{[\s\S]*queuedPacketHandleDepth = 0;[\s\S]*clientPacketDrainActive = false;[\s\S]*clientPacketDrainCritical = false;/,
  "PacketProcessor close/reset can leave the queued PLAY guard active");
assert.match(scheduler, /queuedPacketHandleStartedNanos = System\.nanoTime\(\)/,
  "queued PLAY packet handler timing does not start at the exact drain scope");
assert.match(scheduler, /elapsedNanos >= 50_000_000L/,
  "slow queued PLAY packet classification threshold changed");
assert.match(scheduler,
  /recordDecodedPacketQueue\([\s\S]*handleMillis, handleType\);/,
  "queued PLAY packet handler latency/type is not emitted with completion accounting");

let nestedHandleDepth = 0;
const beginHandle = () => { nestedHandleDepth++; };
const endHandle = () => { if (nestedHandleDepth > 0) nestedHandleDepth--; };
beginHandle();
beginHandle();
endHandle();
assert.equal(nestedHandleDepth > 0, true,
  "nested queued handle cleared the outer PacketUtils drain guard");
endHandle();
assert.equal(nestedHandleDepth, 0, "queued handle depth did not return to zero");

for (const contract of [
  '"packetQueued"',
  '"packetProcessed"',
  '"reset"',
  "Queue.add result is no longer discarded",
  "method.tryCatchBlocks.add(new TryCatchBlockNode",
  "packetsToBeHandled",
  '"clear"',
  '"beginQueuedPacket"',
  '"bindPacketProcessorLifecycle"',
  '"isProcessingQueuedPacket"',
  "ClientPacketListener",
  "ClientboundResourcePackPushPacket",
]) {
  assert.ok(patcher.includes(contract), "missing PacketProcessor patch contract: " + contract);
}

const repository = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => path.basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const overlayProfileId = process.env.GAIUS_OVERLAY_DIRECTORY
  ? profileIdFromPath(process.env.GAIUS_OVERLAY_DIRECTORY) : "";
const profileId = process.env.GAIUS_MINECRAFT_VERSION
  || (process.env.GAIUS_VERSION_PROFILE_PATH
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH)
    : (/^\d+(?:\.\d+)+$/.test(overlayProfileId) ? overlayProfileId : CANONICAL_PROFILE.id));
// PacketProcessor and patchPacketProcessorBrowserSlice are identical for 26.2 and 26.3
// (the 26.3 class is byte-identical modulo the constant pool); 1.21.11 is not covered.
if (![CANONICAL_PROFILE.id, RENDERPEARL_PROFILE.id].includes(profileId)) {
  throw new Error(`PacketProcessor accounting smoke covers ${CANONICAL_PROFILE.id} and ${RENDERPEARL_PROFILE.id}; got profile ${profileId}`);
}
const overlayRoot = nativePath(process.env.GAIUS_OVERLAY_DIRECTORY ||
  `${repository}/port/work/overlays/${profileId}`);
const patchedClasses = `${overlayRoot}/client-patches`;
function javaTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = path.join(home, "bin", name);
    try {
      execFileSync(candidate, ["-version"], {encoding: "utf8", stdio: "ignore"});
      return candidate;
    } catch {
      // Try the next configured JDK, then the process PATH below.
    }
  }
  return name;
}
const javap = javaTool("javap");
let bytecode;
try {
  bytecode = execFileSync(javap, [
    "-classpath",
    patchedClasses,
    "-p",
    "-c",
    "-v",
    "net.minecraft.network.PacketProcessor",
  ], {encoding: "utf8"});
} catch (error) {
  throw new Error(
    "PacketProcessor patched bytecode is unavailable; run port/scripts/build-overlays.sh first",
    {cause: error},
  );
}

const scheduleStart = bytecode.indexOf("scheduleIfPossible(");
const processStart = bytecode.indexOf("public void processQueuedPackets();", scheduleStart);
const closeStart = bytecode.indexOf("public void close();", processStart);
assert.ok(scheduleStart >= 0 && processStart > scheduleStart && closeStart > processStart,
  "PacketProcessor javap method boundaries were not found");
const constructorStart = bytecode.indexOf(
  "public net.minecraft.network.PacketProcessor(java.lang.Thread);",
);
const constructorEnd = bytecode.indexOf(
  "public boolean isSameThread();",
  constructorStart,
);
assert.ok(constructorStart >= 0 && constructorEnd > constructorStart,
  "PacketProcessor constructor javap boundary was not found");
const constructorTrace = bytecode
  .slice(constructorStart, constructorEnd)
  .replace(/\s+/g, " ")
  .replace(/([A-Za-z])\s+([A-Za-z])/g, "$1$2");
assert.match(
  constructorTrace,
  /BrowserPacketScheduler\.bindPacketProcessorLifecycle:\(Ljava\/lang\/Object;\)Z.*?pop.*?return/u,
  "PacketProcessor constructor does not bind lifecycle owner before return",
);
const scheduleBytecode = bytecode.slice(scheduleStart, processStart);
const processBytecode = bytecode.slice(processStart, closeStart);
const closeBytecode = bytecode.slice(closeStart);
// JDK javap may wrap long method comments at the console width.  Normalize
// whitespace before checking the exact queued-packet call order so wrapping
// cannot turn a valid bytecode trace into a false negative.
const processTrace = processBytecode
  .replace(/\s+/g, " ")
  .replace(/([A-Za-z])\s+([A-Za-z])/g, "$1$2");

assert.match(scheduleBytecode,
  /Queue\.add[\s\S]{0,400}?\n\s*\d+:\s+dup[\s\S]{0,120}?\n\s*\d+:\s+ifeq[\s\S]{0,160}?\n\s*\d+:\s+aload_0[\s\S]{0,120}?\n\s*\d+:\s+invokestatic[\s\S]{0,400}?BrowserPacketScheduler\.packetQueued[\s\S]{0,160}?\(Ljava\/lang\/Object;\)V/,
  "packetQueued is not owner-aware and conditional on successful Queue.add");
assert.equal(
  (processBytecode.match(/BrowserPacketScheduler\.packetProcessed/g) || []).length,
  2,
  "handle normal and exceptional paths must each retire one decoded packet",
);
assert.match(
  processTrace,
  /ListenerAndPacket\.packet\s*:[\s\S]{0,500}?BrowserPacketScheduler\.beginQueuedPacket\s*:[\s\S]{0,500}?ListenerAndPacket\.handle\s*:/,
  "queued drain guard does not capture owner + exact packet immediately before handle",
);
assert.match(processBytecode,
  /BrowserPacketScheduler\.beginBatch[\s\S]{0,240}?\(Ljava\/lang\/Object;\)Z/,
  "processQueuedPackets does not claim an owner-aware batch");
assert.match(processBytecode,
  /BrowserPacketScheduler\.shouldProcessNext[\s\S]{0,240}?\(Ljava\/lang\/Object;\)Z/,
  "processQueuedPackets does not use owner-aware batch budget");
assert.match(processBytecode,
  /BrowserPacketScheduler\.beginQueuedPacket[\s\S]{0,240}?\(Ljava\/lang\/Object;Ljava\/lang\/Object;\)V/,
  "processQueuedPackets does not pass owner + packet");
assert.match(bytecode,
  /private void gaius\$vanillaProcessQueuedPackets\(\);|gaius\$vanillaProcessQueuedPackets/,
  "PacketProcessor does not retain a vanilla fallback method");
assert.match(processBytecode, /Exception table:[\s\S]*any/,
  "packetProcessed is not protected by a catch-all finally path");
assert.ok(
  closeBytecode.indexOf("Queue.clear") >= 0
    && closeBytecode.indexOf("Queue.clear") < closeBytecode.indexOf("BrowserPacketScheduler.reset"),
  "close must clear PacketProcessor's real queue before resetting accounting",
);
assert.match(closeBytecode,
  /BrowserPacketScheduler\.reset[\s\S]{0,160}?\(Ljava\/lang\/Object;\)V/,
  "close reset does not carry the PacketProcessor owner");

// A packet stays counted until its handler returns, and processQueuedPackets returns
// immediately while that owner's handler runs. Input scheduling must therefore only count
// owners that can start a packet now; otherwise a finish-configuration handler waiting for
// spawn chunks made the integrated server spin its input task and never enter PLAY.
const actionableStart = scheduler.indexOf("public static boolean hasActionablePendingPackets()");
const actionableEnd = scheduler.indexOf("\n    }\n", actionableStart);
assert.ok(actionableStart >= 0 && actionableEnd > actionableStart,
  "hasActionablePendingPackets is missing");
const actionable = scheduler.slice(actionableStart, actionableEnd);
assert.match(actionable, /ledger\.queuedPackets > 0 && ledger\.queuedPacketHandleDepth == 0/u,
  "an owner with a running handler must not count as actionable input");
const server = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
  import.meta.url,
), "utf8");
const pendingStart = server.indexOf("private static boolean hasPendingNetworkInput()");
const pendingBody = server.slice(pendingStart, server.indexOf("\n    }\n", pendingStart));
assert.match(pendingBody, /BrowserPacketScheduler\.hasActionablePendingPackets\(\)/u,
  "integrated server input scheduling must use actionable packets");
assert.doesNotMatch(server, /BrowserPacketScheduler\.hasPendingPackets\(\)/u,
  "integrated server must not treat a running handler as pending input");

console.log("PacketProcessor decoded-packet accounting smoke passed");
