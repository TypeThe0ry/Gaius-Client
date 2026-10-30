#!/usr/bin/env node

import {CANONICAL_PROFILE, LEGACY_PROFILE, profileIdPattern} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {patcherSource as stepSource} from "./patcher-sources.mjs";
import {execFileSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {basename, delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const requestedProfile = process.env.GAIUS_MINECRAFT_VERSION
  || (process.env.GAIUS_VERSION_PROFILE_PATH
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH) : CANONICAL_PROFILE.id);
if (requestedProfile !== CANONICAL_PROFILE.id) {
  throw new Error(`Minecraft ${CANONICAL_PROFILE.id} P1 patcher smoke is ${CANONICAL_PROFILE.id}-only; got ${requestedProfile}`);
}
execFileSync(process.execPath, [join(repositoryRoot,
  "port/scripts/teavm-platform-start-thread-smoke.mjs")], {
  encoding: "utf8",
  stdio: "inherit",
});
const rawClientJar = join(repositoryRoot, "port/work/" + CANONICAL_PROFILE.id + "/client-named.jar");
const raw121ClientJar = join(repositoryRoot, "port/work/" + LEGACY_PROFILE.id + "/client-named.jar");
const toolsSource = join(repositoryRoot, "port/tools/src/main/java/dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");
const asmAnalysis = join(asmRoot, "asm-analysis/9.8/asm-analysis-9.8.jar");
const verifierSource = join(repositoryRoot, "port/scripts/fixtures/GaiusChunkLayerBytecodeVerifier.java");
const forbiddenDeepWorldgenPatchHelpers = Object.freeze([
  "patchFeatureDecorationCooperation",
  "patchCarverCooperation",
  "patchJigsawCooperation",
  "patchTemplatePlacementCooperation",
]);
const synchronousDeepWorldgenClasses = Object.freeze([
  "net.minecraft.world.level.chunk.ChunkGenerator",
  "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
  "net.minecraft.world.level.levelgen.carver.WorldCarver",
  "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement",
  "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate",
  "net.minecraft.world.level.lighting.LightEngine",
  "net.minecraft.world.level.chunk.LevelChunkSection",
  "net.minecraft.world.level.levelgen.NoiseChunk",
  "net.minecraft.world.level.levelgen.SurfaceSystem",
  "net.minecraft.world.level.biome.Climate$RTree$SubTree",
]);

function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    // `jar -version` is not a portable probe (JDK 25 exits non-zero), while
    // the executable itself is perfectly usable.  Prefer an explicit JDK
    // binary whenever it exists and let execFileSync resolve `.exe` on Windows.
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
    try {
      execFileSync(candidate, ["-version"], {encoding: "utf8", stdio: "ignore"});
      return candidate;
    } catch {
      // Try the next configured JDK, then PATH.
    }
  }
  return name;
}

function method(bytecode, signature, nextSignature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = nextSignature ? bytecode.indexOf(nextSignature, start + signature.length) : -1;
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

function assertDeepWorldgenPulseInMethod(bytecode, signature, minimum, label) {
  const body = method(bytecode, signature);
  assert.ok(occurrences(body, "BrowserWorldgenDeepCheckpoint.pulse") >= minimum,
    `${label} must contain at least ${minimum} budget-aware deep worldgen pulses`);
}

function assertDeepWorldgenPulse(bytecode, minimum, label) {
  assert.ok(occurrences(bytecode, "BrowserWorldgenDeepCheckpoint.pulse") >= minimum,
    `${label} must contain at least ${minimum} budget-aware deep worldgen pulses`);
}

function assertRunServerWaitTelemetryOrder(runServer, profileId) {
  const tickCheckpoint = runServer.indexOf("BrowserWorldgenScheduler.checkpoint");
  const tickTelemetryEnd = runServer.indexOf(
    "BrowserWorldgenScheduler.endServerTickTelemetry",
  );
  const waitTelemetryStart = runServer.indexOf(
    "BrowserWorldgenScheduler.beginServerWaitTelemetry",
  );
  const waitCall = runServer.indexOf("Method waitUntilNextTick:()V");
  const waitTelemetryEnd = runServer.indexOf(
    "BrowserWorldgenScheduler.endServerWaitTelemetry",
  );
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.beginServerWaitTelemetry"), 1,
    `${profileId} runServer must begin one opt-in wait-phase measurement per tick`);
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.endServerWaitTelemetry"), 1,
    `${profileId} runServer must end one opt-in wait-phase measurement per tick`);
  assert.ok(tickCheckpoint >= 0 && tickTelemetryEnd > tickCheckpoint
      && waitTelemetryStart > tickTelemetryEnd
      && waitCall > waitTelemetryStart
      && waitTelemetryEnd > waitCall,
    `${profileId} wait telemetry must bracket waitUntilNextTick after tick telemetry`);
}

function occurrences(haystack, needle) {
  return haystack.split(needle).length - 1;
}

function assertDedicatedServerGameTypeContract(bytecode, profile, initSignature) {
  const init = method(bytecode,
    initSignature,
    "public net.minecraft.world.level.GameType gameMode();");
  assert.equal(occurrences(init, "WorldData.setGameType"), 0,
    `${profile} initServer still overwrites the persisted WorldData game type`);
  const gameMode = method(bytecode,
    "public net.minecraft.world.level.GameType gameMode();",
    "public void setGameMode(net.minecraft.world.level.GameType);");
  assert.equal(occurrences(gameMode, "WorldData.setGameType"), 0,
    `${profile} gameMode getter unexpectedly mutates WorldData`);
  const setGameMode = method(bytecode,
    "public void setGameMode(net.minecraft.world.level.GameType);");
  assert.equal(occurrences(setGameMode, "WorldData.setGameType"), 1,
    `${profile} explicit setGameMode mutation was removed`);
}

function assertClientPlayPacketQueueContract(packetUtilsBytecode, profileId) {
  const contract = method(packetUtilsBytecode,
    "void ensureRunningOnSameThread(net.minecraft.network.protocol.Packet<T>, T, net.minecraft.network.PacketProcessor)",
    "public static <T extends net.minecraft.network.PacketListener> net.minecraft.ReportedException");
  const transitionPackets = [
    "ClientboundStartConfigurationPacket",
    "ClientboundLoginPacket",
  ];
  const commonInlinePackets = [
    "ClientboundPingPacket",
    "ClientboundCustomPayloadPacket",
    "ClientboundResourcePackPushPacket",
    "ClientboundResourcePackPopPacket",
    "ClientboundCookieRequestPacket",
    "ClientboundStoreCookiePacket",
    "ClientboundCustomReportDetailsPacket",
    "ClientboundServerLinksPacket",
    "ClientboundShowDialogPacket",
    "ClientboundClearDialogPacket",
    "ClientboundTransferPacket",
  ];
  const instructions = bytecodeInstructions(contract);
  const byOffset = new Map(instructions.map((instruction) =>
    [instruction.offset, instruction.instruction]));
  const indexOfInstruction = (predicate, start = 0) => {
    const index = instructions.findIndex((entry, candidate) => candidate >= start && predicate(entry));
    assert.ok(index >= 0, `${profileId} PacketUtils bytecode instruction is missing`);
    return index;
  };
  const branchAfterInstanceof = (packetName, occurrence) => {
    const matches = [];
    for (let index = 0; index < instructions.length; index++) {
      if (!instructions[index].instruction.includes("instanceof") ||
          !instructions[index].instruction.includes(packetName)) {
        continue;
      }
      const next = instructions[index + 1]?.instruction.match(/^(ifne|ifeq)\s+(\d+)/);
      if (next) matches.push({index, opcode: next[1], target: Number(next[2])});
    }
    assert.equal(matches.length, 2,
      `${profileId} ${packetName} must have one non-PLAY and one guarded PLAY classifier`);
    return matches[occurrence];
  };
  const inlineIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("BrowserWebSocketChannel.recordInlineDecodedPacket"));
  const inlineTarget = instructions[inlineIndex].offset;
  const workerServerIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("BrowserIntegratedServerMain.isWorkerServer"));
  const workerClientBranch = instructions[workerServerIndex + 1]?.instruction.match(/^ifeq\s+(\d+)/);
  assert.ok(workerClientBranch,
    `${profileId} Worker server guard must branch around the force-queue path`);
  const workerDrainIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("BrowserPacketScheduler.isProcessingQueuedPacket"),
  workerServerIndex + 1);
  assert.equal(workerDrainIndex, workerServerIndex + 3,
    `${profileId} Worker server owner guard must precede all listener classification`);
  const workerDrainBranch = instructions[workerDrainIndex + 1]?.instruction.match(/^ifne\s+(\d+)/);
  assert.ok(workerDrainBranch,
    `${profileId} Worker server queued-drain guard branch is missing`);
  const configurationIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("ClientConfigurationPacketListenerImpl"));
  assert.ok(Number(workerClientBranch[1]) < instructions[configurationIndex].offset,
    `${profileId} non-Worker traffic does not reach the client listener dispatch`);
  assert.match(instructions[configurationIndex + 1]?.instruction || "", /^ifne\s+/,
    `${profileId} configuration listener does not use the inline boundary`);
  assert.equal(Number(instructions[configurationIndex + 1].instruction.match(/^(?:ifne)\s+(\d+)/)[1]),
    inlineTarget,
    `${profileId} configuration listener no longer uses the inline boundary`);
  const playIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("instanceof") &&
    entry.instruction.includes("ClientPacketListener") &&
    !entry.instruction.includes("ClientConfigurationPacketListenerImpl"));
  const playBranch = instructions[playIndex + 1]?.instruction.match(/^ifne\s+(\d+)/);
  assert.ok(playBranch, `${profileId} PLAY listener guard must branch to the guarded path`);
  const playTarget = Number(playBranch[1]);
  const playTargetIndex = instructions.findIndex((entry) => entry.offset === playTarget);
  assert.ok(playTargetIndex > playIndex,
    `${profileId} PLAY listener guard must precede common inline classification`);
  const drainIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("BrowserPacketScheduler.isProcessingQueuedPacket"),
  workerDrainIndex + 1);
  assert.ok(drainIndex > playTargetIndex,
    `${profileId} queued-drain guard must execute after entering the PLAY path`);
  const drainBranch = instructions[drainIndex + 1]?.instruction.match(/^ifne\s+(\d+)/);
  assert.ok(drainBranch, `${profileId} PacketUtils queued-drain guard branch is missing`);
  const queuedReturnTarget = Number(drainBranch[1]);
  assert.equal(Number(workerDrainBranch[1]), queuedReturnTarget,
    `${profileId} Worker server and PLAY re-entry guards must share one exact return`);
  assert.equal(byOffset.get(queuedReturnTarget), "return",
    `${profileId} queued PLAY handler re-enters vanilla thread identity instead of returning`);
  const commonBranches = commonInlinePackets.map((packet) => ({
    packet,
    first: branchAfterInstanceof(packet, 0),
    second: branchAfterInstanceof(packet, 1),
  }));
  const commonInlineTargets = new Set(commonBranches.map(({first}) => {
    assert.equal(first.opcode, "ifne");
    return first.target;
  }));
  assert.deepEqual([...commonInlineTargets], [inlineTarget],
    `${profileId} non-PLAY common packets must retain the inline fast path`);
  const commonBacklogTargets = new Set(commonBranches.map(({second}) => {
    assert.equal(second.opcode, "ifne");
    return second.target;
  }));
  assert.equal(commonBacklogTargets.size, 1,
    `${profileId} PLAY common packets must share one backlog gate`);
  const commonBacklogTarget = [...commonBacklogTargets][0];
  assert.ok(commonBacklogTarget > playTarget,
    `${profileId} PLAY common packet gate must be after the owner guard`);
  const transitionBranches = transitionPackets.map((packet) => {
    const index = indexOfInstruction((entry) =>
      entry.instruction.includes("instanceof") && entry.instruction.includes(packet));
    const branch = instructions[index + 1]?.instruction.match(/^ifne\s+(\d+)/);
    assert.ok(branch, `${profileId} transition classifier ${packet} is missing`);
    return {packet, target: Number(branch[1])};
  });
  const transitionBacklogTargets = new Set(transitionBranches.map(({target}) => target));
  assert.equal(transitionBacklogTargets.size, 1,
    `${profileId} transition packets must share one backlog gate`);
  const transitionBacklogTarget = [...transitionBacklogTargets][0];
  const pendingCallIndices = [];
  for (let index = 0; index < instructions.length; index++) {
    if (instructions[index].instruction.includes("BrowserPacketScheduler.hasPendingPackets")) {
      pendingCallIndices.push(index);
    }
  }
  assert.equal(pendingCallIndices.length, 2,
    `${profileId} PacketUtils must have separate common and transition FIFO backlog checks`);
  const assertBacklogBlock = (target, label) => {
    const targetIndex = instructions.findIndex((entry) => entry.offset === target);
    assert.ok(targetIndex >= 0, `${profileId} ${label} backlog target is missing`);
    const pendingIndex = indexOfInstruction((entry) =>
      entry.instruction.includes("BrowserPacketScheduler.hasPendingPackets"), targetIndex);
    assert.ok(pendingIndex > targetIndex,
      `${profileId} ${label} backlog gate is not reachable from its classifier`);
    const branch = instructions[pendingIndex + 1]?.instruction.match(/^ifeq\s+(\d+)/);
    assert.ok(branch, `${profileId} ${label} backlog gate must branch on an empty owner queue`);
    assert.equal(Number(branch[1]), inlineTarget,
      `${profileId} ${label} empty backlog must use the inline boundary`);
    return pendingIndex;
  };
  const commonPendingIndex = assertBacklogBlock(commonBacklogTarget, "common");
  const transitionPendingIndex = assertBacklogBlock(transitionBacklogTarget, "transition");
  assert.ok(commonPendingIndex < transitionPendingIndex,
    `${profileId} common and transition backlog blocks must remain ordered`);
  const forcedSchedule = indexOfInstruction((entry) =>
    entry.instruction.includes("PacketProcessor.scheduleIfPossible"));
  const forcedAbort = indexOfInstruction((entry) =>
    entry.instruction.includes("RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD"),
    forcedSchedule);
  const forcedThrow = indexOfInstruction((entry) => entry.instruction === "athrow", forcedAbort);
  const vanillaThreadCheck = indexOfInstruction((entry) =>
    entry.instruction.includes("PacketProcessor.isSameThread"));
  const vanillaSchedule = indexOfInstruction((entry) =>
    entry.instruction.includes("PacketProcessor.scheduleIfPossible"), forcedSchedule + 1);
  const ordered = [workerServerIndex, workerDrainIndex, configurationIndex, playIndex,
    ...commonBranches.map(({first}) => first.index),
    drainIndex, ...transitionBranches.map(({packet}) => indexOfInstruction((entry) =>
      entry.instruction.includes("instanceof") && entry.instruction.includes(packet), playTargetIndex)),
    ...commonBranches.map(({second}) => second.index), commonPendingIndex, transitionPendingIndex,
    forcedSchedule, forcedAbort, forcedThrow, inlineIndex, vanillaThreadCheck, vanillaSchedule];
  assert.ok(ordered.every((value, index) => value >= 0 &&
    (index === 0 || value > ordered[index - 1])),
  `${profileId} PacketUtils does not force PLAY traffic through PacketProcessor before vanilla same-thread return`);
  assert.equal(occurrences(contract, "PacketProcessor.scheduleIfPossible"), 2,
    `${profileId} PacketUtils must contain one forced PLAY queue and one vanilla foreign-thread queue`);
  assert.equal(occurrences(contract, "BrowserWebSocketChannel.recordInlineDecodedPacket"), 1,
    `${profileId} PacketUtils must retire exactly one shared inline packet boundary`);
  for (const packet of transitionPackets) {
    assert.equal(occurrences(contract, packet), 1,
      `${profileId} PacketUtils must keep one exact ${packet} transition classifier`);
  }
  for (const packet of commonInlinePackets) {
    assert.equal(occurrences(contract, packet), 2,
      `${profileId} PacketUtils must classify ${packet} in both non-PLAY and guarded PLAY paths`);
  }
  assert.equal(occurrences(contract, "ClientPacketListener"), 1,
    `${profileId} PacketUtils must have exactly one PLAY-listener force-queue branch`);
  assert.equal(occurrences(contract, "BrowserIntegratedServerMain.isWorkerServer"), 1,
    `${profileId} PacketUtils must classify the Worker server before listener dispatch`);
  assert.equal(occurrences(contract, "BrowserPacketScheduler.isProcessingQueuedPacket"), 2,
    `${profileId} PacketUtils must owner-guard Worker server and PLAY queued re-entry`);
  assert.equal(occurrences(contract, "BrowserPacketScheduler.hasPendingPackets"), 2,
    `${profileId} PacketUtils must preserve queued PLAY FIFO for common and transition packets`);
}

function assertKeepAliveImmediateSendContract(listenerBytecode, profileId) {
  const keepAlive = method(listenerBytecode,
    "public void handleKeepAlive(net.minecraft.network.protocol.common.ClientboundKeepAlivePacket);",
    "public void handlePing(net.minecraft.network.protocol.common.ClientboundPingPacket);");
  const accounting = keepAlive.indexOf("BrowserWebSocketChannel.recordInlineDecodedPacket");
  const packetId = keepAlive.indexOf("ClientboundKeepAlivePacket.getId:()J");
  const response = keepAlive.indexOf("ServerboundKeepAlivePacket.\"<init>\":(J)V");
  const sendWhen = keepAlive.indexOf("Method sendWhen:");
  assert.ok(accounting >= 0 && accounting < packetId && packetId < response &&
    response < sendWhen,
  `${profileId} keepalive does not retire decoder accounting before immediate sendWhen`);
  assert.equal(occurrences(keepAlive, "BrowserWebSocketChannel.recordInlineDecodedPacket"), 1,
    `${profileId} keepalive must retire exactly one inline decoder boundary`);
  assert.equal(occurrences(keepAlive, "Method sendWhen:"), 1,
    `${profileId} keepalive must retain exactly one immediate sendWhen path`);
  assert.equal(occurrences(keepAlive, "PacketUtils.ensureRunningOnSameThread"), 0,
    `${profileId} keepalive unexpectedly entered the ordinary PLAY PacketUtils queue`);

  const predicateSignature = listenerBytecode.match(
    /private static boolean lambda\$handleKeepAlive\$\d+\(\);/)?.[0];
  assert.ok(predicateSignature, `${profileId} keepalive predicate signature is missing`);
  const predicate = method(listenerBytecode, predicateSignature, "private static java.util.List");
  assert.deepEqual(bytecodeInstructions(predicate).map(({instruction}) => instruction),
    ["iconst_1", "ireturn"],
    `${profileId} keepalive predicate is not the exact immediate-send constant`);
  assert.doesNotMatch(predicate, /RenderSystem\.isFrozenAtPollEvents/,
    `${profileId} keepalive predicate still waits for the desktop render poll gate`);

  const sendWhenContract = method(listenerBytecode,
    "private void sendWhen(net.minecraft.network.protocol.Packet<? extends net.minecraft.network.ServerboundPacketListener>, java.util.function.BooleanSupplier, java.time.Duration);",
    "private net.minecraft.client.gui.screens.Screen addOrUpdatePackPrompt");
  const sendWhenInstructions = bytecodeInstructions(sendWhenContract);
  const supplierIndex = sendWhenInstructions.findIndex(({instruction}) =>
    instruction.includes("BooleanSupplier.getAsBoolean:()Z"));
  assert.ok(supplierIndex >= 0 &&
    /^ifeq\s+\d+/.test(sendWhenInstructions[supplierIndex + 1]?.instruction || "") &&
    sendWhenInstructions[supplierIndex + 2]?.instruction === "aload_0" &&
    sendWhenInstructions[supplierIndex + 3]?.instruction === "aload_1" &&
    sendWhenInstructions[supplierIndex + 4]?.instruction.includes(
      "Method send:(Lnet/minecraft/network/protocol/Packet;)V"),
  `${profileId} sendWhen true branch no longer synchronously sends the keepalive response`);
  assert.equal(occurrences(sendWhenContract, "BooleanSupplier.getAsBoolean:()Z"), 1,
    `${profileId} sendWhen must evaluate one keepalive liveness predicate`);
  assert.equal(occurrences(sendWhenContract,
    "Method send:(Lnet/minecraft/network/protocol/Packet;)V"), 1,
  `${profileId} sendWhen must contain one direct send path`);
  assert.equal(occurrences(sendWhenContract, "java/util/List.add:(Ljava/lang/Object;)Z"), 1,
    `${profileId} sendWhen deferred fallback was unexpectedly removed`);
}

function assertPacketProcessorQueueContract(packetProcessorBytecode, profileId) {
  const contract = method(packetProcessorBytecode,
    "public void processQueuedPackets();", "public void close();");
  assert.match(contract, /BrowserPacketScheduler\.beginBatch[^\n]*\(Ljava\/lang\/Object;\)Z/,
    `${profileId} PacketProcessor does not claim an owner-aware batch`);
  assert.match(contract, /BrowserPacketScheduler\.shouldProcessNext[^\n]*\(Ljava\/lang\/Object;\)Z/,
    `${profileId} PacketProcessor does not use owner-aware batch accounting`);
  const packetAccessor = contract.indexOf("ListenerAndPacket.packet");
  const beginGuard = contract.indexOf("BrowserPacketScheduler.beginQueuedPacket");
  const handle = contract.indexOf("ListenerAndPacket.handle");
  assert.ok(packetAccessor >= 0 && packetAccessor < beginGuard && beginGuard < handle,
    `${profileId} PacketProcessor does not bind timing/recursion guard to the exact packet handle`);
  assert.equal(occurrences(contract, "BrowserPacketScheduler.beginQueuedPacket"), 1,
    `${profileId} PacketProcessor must enter one queued packet guard per poll`);
  assert.equal(occurrences(contract, "BrowserPacketScheduler.packetProcessed"), 2,
    `${profileId} PacketProcessor normal/exception exits must each retire one queued packet`);
  assert.match(contract, /Exception table:[\s\S]*any/,
    `${profileId} PacketProcessor handle scope lacks a catch-all retirement path`);
  const instructions = bytecodeInstructions(contract);
  const beginInstruction = instructions.find(({instruction}) =>
    instruction.includes("BrowserPacketScheduler.beginQueuedPacket"));
  assert.ok(beginInstruction?.instruction.includes(
    "(Ljava/lang/Object;Ljava/lang/Object;)V"),
    `${profileId} PacketProcessor does not pass owner + exact Packet object into telemetry`);
  assert.match(packetProcessorBytecode,
    /private void gaius\$vanillaProcessQueuedPackets\(\);/,
    `${profileId} PacketProcessor has no retained vanilla fallback`);
}

function assertPacketProcessorLifecycleContract(packetProcessorBytecode, profileId) {
  const contract = method(packetProcessorBytecode,
    "public net.minecraft.network.PacketProcessor(java.lang.Thread);",
    "public boolean isSameThread();");
  assert.equal(occurrences(contract, "BrowserPacketScheduler.bindPacketProcessorLifecycle"), 1,
    `${profileId} PacketProcessor constructor must bind the explicit lifecycle owner exactly once`);
  assert.match(contract,
    /invokestatic[^\n]*BrowserPacketScheduler\.bindPacketProcessorLifecycle[^\n]*\(Ljava\/lang\/Object;\)Z/,
    `${profileId} PacketProcessor constructor lifecycle bind descriptor changed`);
  assert.match(contract, /pop\s*\n\s*\d+:\s+return/,
    `${profileId} PacketProcessor constructor must discard lifecycle bind status before return`);
}

// Ready.spawn's ServerLevel.waitForEntities runs inside the
// ServerboundFinishConfiguration handler.  On the browser Worker server,
// Preparing must stay in place until the spawn chunk's entities are loaded so
// that wait never enters managedBlock.  Any other server keeps that wait.
// ServerChunkCache.getChunk called off the server thread hands the lookup to the server thread
// and joins it (CompletableFuture.supplyAsync(..., mainThreadProcessor).join()); the browser
// CompletableFuture cannot block, so the join is BrowserFuturePump.joinFromWorker, which yields
// the calling generation thread until the server thread has run the lookup
// (server-offthread-wait-teavm-smoke.mjs runs it). The server thread's own join stays.
function assertOffThreadGetChunkWaitContract(bytecode, profileId) {
  const getChunkClass = method(bytecode,
    "public net.minecraft.world.level.chunk.ChunkAccess getChunk(int, int, "
      + "net.minecraft.world.level.chunk.status.ChunkStatus, boolean);");
  // javap separates methods with a blank line.
  const getChunkEnd = getChunkClass.search(/\r?\n\r?\n/);
  const getChunk = getChunkEnd < 0 ? getChunkClass : getChunkClass.slice(0, getChunkEnd);
  const instructions = bytecodeInstructions(getChunk);
  const supply = instructions.findIndex(({instruction}) =>
    instruction.includes("Method java/util/concurrent/CompletableFuture.supplyAsync:"
      + "(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"));
  assert.ok(supply > 0
      && instructions[supply - 1].instruction.includes("Field mainThreadProcessor:")
      && instructions[supply + 1]?.instruction.includes(
        "Method dev/gaius/browser/BrowserFuturePump.joinFromWorker:"
          + "(Ljava/util/concurrent/CompletableFuture;)Ljava/lang/Object;"),
  `${profileId} ServerChunkCache.getChunk off the server thread must wait through BrowserFuturePump.joinFromWorker`);
  assert.equal(occurrences(getChunk, "BrowserFuturePump.joinFromWorker"), 1,
    `${profileId} ServerChunkCache.getChunk must wait cooperatively exactly once`);
  assert.equal(occurrences(getChunk, "CompletableFuture.join:()Ljava/lang/Object;"), 1,
    `${profileId} ServerChunkCache.getChunk must keep the server thread's own join`);
}

// PlayerSpawnFinder.gaius$fixupLoadedSpawn stands in for the spawn search taken out of
// PlayerSpawnFinder.findSpawn, so only a player without a saved position goes through it:
// PrepareSpawnTask.start sets Preparing.gaius$spawnSearched from SavedPosition.position() right
// after it enters Preparing, and Preparing.tick skips the fixup when it is false, so a returning
// player spawns at the stored position as in vanilla.
function assertSavedSpawnPositionContract(taskBytecode, preparingBytecode, profileId) {
  const start = method(taskBytecode,
    "public void start(java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>>);",
    "public boolean tick();");
  const startInstructions = bytecodeInstructions(start);
  const enter = startInstructions.findIndex(({instruction}) =>
    /^putfield\s.*Field state:Lnet\/minecraft\/server\/network\/config\/PrepareSpawnTask\$State;$/.test(instruction));
  const mark = startInstructions.slice(enter + 1, enter + 10).map(({instruction}) => instruction);
  assert.ok(enter > 0
      && startInstructions[enter - 1].instruction.includes("PrepareSpawnTask$Preparing.\"<init>\"")
      && mark[0] === "aload_0"
      && mark[1]?.includes("Field state:")
      && mark[2]?.includes("class net/minecraft/server/network/config/PrepareSpawnTask$Preparing")
      && /^aload(_\d|\s+\d+)$/.test(mark[3] ?? "")
      && mark[4]?.includes("Method net/minecraft/server/level/ServerPlayer$SavedPosition.position:()Ljava/util/Optional;")
      && mark[5]?.includes("Method java/util/Optional.isPresent:()Z")
      && mark[6] === "iconst_1"
      && mark[7] === "ixor"
      && mark[8]?.includes("PrepareSpawnTask$Preparing.gaius$spawnSearched:Z"),
  `${profileId} PrepareSpawnTask.start must mark a spawn without a saved position as searched`);
  assert.equal(occurrences(start, "gaius$spawnSearched"), 1,
    `${profileId} PrepareSpawnTask.start must mark the spawn exactly once`);

  const tick = method(preparingBytecode,
    "public net.minecraft.server.network.config.PrepareSpawnTask$Ready tick();",
    "private void lambda$tick$0(");
  const tickInstructions = bytecodeInstructions(tick);
  const fixup = tickInstructions.findIndex(({instruction}) =>
    instruction.includes("Method net/minecraft/server/level/PlayerSpawnFinder.gaius$fixupLoadedSpawn:"));
  const ready = tickInstructions.findIndex(({instruction}) =>
    instruction.includes("class net/minecraft/server/network/config/PrepareSpawnTask$Ready"));
  const guard = tickInstructions.slice(fixup - 6, fixup + 2).map(({instruction}) => instruction);
  assert.ok(fixup > 5
      && guard[0] === "aload_0"
      && guard[1]?.includes("Field gaius$spawnSearched:Z")
      && /^ifeq\s+\d+$/.test(guard[2] ?? "")
      && Number(guard[2].match(/\d+$/)[0]) === tickInstructions[ready]?.offset
      && guard[3] === "aload_0"
      && guard[4]?.includes("Field spawnLevel:")
      && guard[5] === "aload_1"
      && guard[7] === "astore_1"
      && fixup + 2 === ready,
  `${profileId} Preparing.tick must skip the spawn fixup for a saved position`);
  assert.equal(occurrences(tick, "gaius$fixupLoadedSpawn"), 1,
    `${profileId} Preparing.tick must call the spawn fixup exactly once`);
}

function assertPrepareSpawnEntityGateContract(preparingBytecode, readyBytecode,
  serverLevelBytecode, profileId, chunkKeyCall) {
  const tick = method(preparingBytecode,
    "public net.minecraft.server.network.config.PrepareSpawnTask$Ready tick();",
    "private void lambda$tick$0(");
  const tickInstructions = bytecodeInstructions(tick);
  const chunkDone = tickInstructions.findIndex(({instruction}, index) =>
    instruction.includes("Field chunkLoadFuture:")
      && tickInstructions[index + 1]?.instruction.includes("CompletableFuture.isDone:()Z")
      && /^ifne\s+\d+$/.test(tickInstructions[index + 2]?.instruction ?? ""));
  assert.ok(chunkDone >= 0, `${profileId} Preparing.tick lost its chunkLoadFuture.isDone check`);
  const chunkDoneTarget = Number(tickInstructions[chunkDone + 2].instruction.match(/\d+$/)[0]);
  const gate = tickInstructions.findIndex(({offset}) => offset === chunkDoneTarget);
  const gateShape = tickInstructions.slice(gate, gate + 8).map(({instruction}) => instruction);
  assert.ok(gate > chunkDone
      && gateShape[0] === "aload_0"
      && gateShape[1]?.includes("Field spawnLevel:")
      && gateShape[2] === "aload_1"
      && gateShape[3]?.includes("Method gaius$spawnEntitiesLoaded:"
        + "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;)Z")
      && /^ifne\s+\d+$/.test(gateShape[4] ?? "")
      && gateShape[5] === "aconst_null"
      && gateShape[6] === "areturn"
      && Number(gateShape[4].match(/\d+$/)[0]) === tickInstructions[gate + 7]?.offset,
  `${profileId} Preparing.tick must return null until the spawn chunk's entities load`);
  const gateCall = tick.indexOf("Method gaius$spawnEntitiesLoaded:");
  const finish = tick.indexOf("LevelLoadListener.finish:");
  const fixup = tick.indexOf("PlayerSpawnFinder.gaius$fixupLoadedSpawn:");
  const ready = tick.indexOf("class net/minecraft/server/network/config/PrepareSpawnTask$Ready");
  assert.ok(gateCall >= 0 && gateCall < finish && finish < fixup && fixup < ready,
    `${profileId} entity gate must precede LOAD_PLAYER_CHUNKS finish, spawn fixup and Ready`);
  assert.equal(occurrences(tick, "Method gaius$spawnEntitiesLoaded:"), 1,
    `${profileId} Preparing.tick must gate Ready exactly once`);
  assert.doesNotMatch(tick, /waitForEntities|managedBlock/,
    `${profileId} Preparing.tick must not block the server thread`);

  const helper = method(preparingBytecode,
    "private static boolean gaius$spawnEntitiesLoaded(net.minecraft.server.level.ServerLevel, "
      + "net.minecraft.world.phys.Vec3);");
  const helperInstructions = bytecodeInstructions(helper);
  // Vanilla IntegratedServer (the fallback when the Worker cannot start) keeps
  // its handler wait: the gate passes at once unless this is the Worker server.
  assert.ok(helperInstructions[0]?.instruction.includes(
    "Method dev/gaius/browser/BrowserIntegratedServerMain.isWorkerServer:()Z")
      && /^ifne\s+\d+$/.test(helperInstructions[1]?.instruction ?? "")
      && helperInstructions[2]?.instruction === "iconst_1"
      && helperInstructions[3]?.instruction === "ireturn"
      && Number(helperInstructions[1].instruction.match(/\d+$/)[0])
        === helperInstructions[4]?.offset,
  `${profileId} spawn entity gate must pass at once on any server but the browser Worker`);
  assert.equal(occurrences(helper, "BrowserIntegratedServerMain.isWorkerServer:()Z"), 1,
    `${profileId} spawn entity gate must check for the Worker server exactly once`);
  const loadedCheck = helperInstructions.findIndex(({instruction}) =>
    instruction.includes("ServerLevel.areEntitiesLoaded:(J)Z"));
  assert.ok(loadedCheck > 0
      && helperInstructions[loadedCheck - 1].instruction.includes(chunkKeyCall)
      && /^ifeq\s+\d+$/.test(helperInstructions[loadedCheck + 1]?.instruction ?? ""),
  `${profileId} spawn entity gate must key ServerLevel.areEntitiesLoaded with ${chunkKeyCall}`);
  // A paused server (vanilla IntegratedServer with no players yet) never runs
  // ServerLevel.tick, the only other drain of finished entity reads.  Mirror
  // waitForEntities' predicate: processPendingLoads, then areEntitiesLoaded.
  const drain = helperInstructions.findIndex(({instruction}) => instruction.includes(
    "Method net/minecraft/server/level/ServerLevel.gaius$processPendingEntityLoads:()V"));
  assert.ok(drain > 4
      && helperInstructions[drain - 1].instruction === "aload_0"
      && helperInstructions[drain + 1]?.instruction === "aload_0"
      && loadedCheck === drain + 4,
  `${profileId} spawn entity gate must drain pending entity loads right before checking them`);
  assert.equal(occurrences(helper, "gaius$processPendingEntityLoads"), 1,
    `${profileId} spawn entity gate must drain pending entity loads exactly once per check`);
  const accessorStart = serverLevelBytecode.indexOf(
    "public void gaius$processPendingEntityLoads();");
  assert.ok(accessorStart >= 0,
    `${profileId} patched ServerLevel lost its pending entity load accessor`);
  const accessor = serverLevelBytecode.slice(accessorStart).split(/\r?\n\s*\r?\n/)[0];
  assert.deepEqual(bytecodeInstructions(accessor)
    .map(({instruction}) => instruction.replace(/\s+#\d+\s+\/\/\s+/, " ")), [
    "aload_0",
    "getfield Field entityManager:Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;",
    "invokevirtual Method net/minecraft/world/level/entity/PersistentEntitySectionManager"
      + ".processPendingLoads:()V",
    "return",
  ], `${profileId} ServerLevel accessor must run the entity manager's processPendingLoads`);
  const waitingTarget = Number(helperInstructions[loadedCheck + 1].instruction.match(/\d+$/)[0]);
  const waiting = helperInstructions.findIndex(({offset}) => offset === waitingTarget);
  const loadedPath = helperInstructions.slice(loadedCheck + 2, waiting)
    .map(({instruction}) => instruction);
  assert.ok(loadedPath.some(instruction => instruction.includes(
    "BrowserIntegratedServerMain.markSpawnEntitiesLoaded:(II)V"))
      && loadedPath.at(-2) === "iconst_1" && loadedPath.at(-1) === "ireturn",
  `${profileId} loaded spawn entities must report once and let Preparing turn Ready`);
  const waitingPath = helperInstructions.slice(waiting).map(({instruction}) => instruction);
  const refresh = waitingPath.findIndex(instruction =>
    instruction.includes("ServerChunkCache.addTicketWithRadius:"));
  assert.ok(waitingPath.some(instruction => instruction.includes("TicketType.PLAYER_SPAWN"))
      && refresh > 0 && waitingPath[refresh - 1] === "iconst_1"
      && waitingPath.at(-2) === "iconst_0" && waitingPath.at(-1) === "ireturn",
  `${profileId} waiting spawn gate must refresh the radius-1 PLAYER_SPAWN ticket`);
  assert.ok(waitingPath.some(instruction => instruction.includes(
    "BrowserIntegratedServerMain.markSpawnEntitiesWaiting:(II)V")),
  `${profileId} waiting spawn gate must count its wait so a stalled join is reported`);
  assert.ok(helper.includes("BlockPos.containing:(Lnet/minecraft/core/Position;)")
      && occurrences(helper, "ishr") === 2,
  `${profileId} spawn entity gate must use the spawn position's own chunk`);
  assert.doesNotMatch(helper, /waitForEntities|managedBlock/,
    `${profileId} spawn entity gate must poll without blocking`);

  const spawn = method(readyBytecode,
    "public net.minecraft.server.level.ServerPlayer spawn(net.minecraft.network.Connection, "
      + "net.minecraft.server.network.CommonListenerCookie);");
  const spawnInstructions = bytecodeInstructions(spawn);
  const waitForEntities = spawnInstructions.findIndex(({instruction}) =>
    instruction.includes("ServerLevel.waitForEntities:"));
  assert.equal(spawnInstructions[waitForEntities - 1]?.instruction, "iconst_0",
    `${profileId} Ready.spawn must keep its radius-0 entity wait as a no-op safety net`);
}

function bytecodeInstructions(methodBytecode) {
  return methodBytecode.split(/\r?\n/).flatMap(line => {
    const match = line.match(/^\s*(\d+):\s+(.*)$/);
    return match ? [{offset: Number(match[1]), instruction: match[2]}] : [];
  });
}

function constructorBytecode(bytecode, signature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing Options constructor: ${signature}`);
  const codeStart = bytecode.indexOf("\n    Code:", start);
  assert.ok(codeStart >= 0, `missing Code block for Options constructor: ${signature}`);
  const tail = bytecode.slice(codeStart + "\n    Code:".length);
  const nextMethod = tail.search(/\n  (?=(?:public|private|protected|static|final|native|synchronized)\s)/);
  return bytecode.slice(start, codeStart + "\n    Code:".length
    + (nextMethod < 0 ? tail.length : nextMethod));
}

function assertDefaultSprintKeyMapping(optionsBytecode, profileId, expectedKeyCode) {
  const constructor = constructorBytecode(optionsBytecode,
    "public net.minecraft.client.Options(net.minecraft.client.Minecraft, java.io.File);");
  const instructions = bytecodeInstructions(constructor);
  const sprintLiteral = /String key\.sprint/;
  const sprintMappings = instructions.flatMap((entry, index) => {
    if (!sprintLiteral.test(entry.instruction.trim())) return [];
    const following = instructions.slice(index + 1, index + 5);
    return following.some(({instruction}) =>
      new RegExp(`^(?:sipush|bipush)\\s+${expectedKeyCode}$`).test(instruction.trim())) ? [index] : [];
  });
  assert.equal(sprintMappings.length, 1,
    `${profileId} Options constructor must create exactly one key.sprint mapping for ${expectedKeyCode}`);
  const sprintIndex = sprintMappings[0];
  const sprint = instructions.slice(sprintIndex, sprintIndex + 16);
  const indexOf = (pattern, label) => {
    const index = sprint.findIndex(({instruction}) => pattern.test(instruction));
    assert.ok(index >= 0, `${profileId} key.sprint mapping is missing ${label}`);
    return index;
  };
  const keyCodeIndex = indexOf(new RegExp(`^(?:sipush|bipush)\\s+${expectedKeyCode}$`),
    `expected keycode ${expectedKeyCode}`);
  const categoryIndex = indexOf(/KeyMapping\$Category\.MOVEMENT/, "MOVEMENT category");
  const toggleOptionIndex = indexOf(/getfield .*toggleSprint:/,
    "toggleSprint option supplier");
  const supplierIndex = indexOf(/invokedynamic .*getAsBoolean:/, "toggle supplier");
  const toggleConstructorIndex = indexOf(/invokespecial .*ToggleKeyMapping\.\"<init>\"/,
    "ToggleKeyMapping constructor");
  const fieldIndex = indexOf(/putfield .*keySprint:/, "keySprint field store");
  assert.ok(keyCodeIndex < categoryIndex && categoryIndex < toggleOptionIndex
      && toggleOptionIndex < supplierIndex && supplierIndex < toggleConstructorIndex
      && toggleConstructorIndex < fieldIndex,
  `${profileId} key.sprint constructor argument order changed`);
}

function graphicsPresetApply(bytecode) {
  return method(bytecode,
    "public void apply(net.minecraft.client.Minecraft);",
    "private static <T> void set(");
}

function graphicsPresetDistanceConstants(applyBytecode, getter) {
  const instructions = bytecodeInstructions(applyBytecode);
  const matches = instructions.flatMap((entry, index) =>
    entry.instruction.includes(`Options.${getter}:`) ? [index] : []);
  assert.equal(matches.length, 3,
    `GraphicsPreset.apply must have three ${getter} setter calls`);
  return matches.map(index => (instructions[index + 1]?.instruction ?? "").replace(/\s+/g, " "));
}

function graphicsPresetCustomReturn(applyBytecode) {
  const target = applyBytecode.match(/default:\s+(\d+)/);
  assert.ok(target, "GraphicsPreset.apply ordinal switch has no CUSTOM default");
  const entry = bytecodeInstructions(applyBytecode)
    .find(instruction => instruction.offset === Number(target[1]));
  assert.equal(entry?.instruction, "return",
    "GraphicsPreset.apply CUSTOM arm is no longer the default return arm");
  return entry.instruction;
}

// Maps each GraphicsPreset.apply bytecode offset to its ordinal switch arm.
function graphicsPresetArmAt(applyBytecode) {
  const table = applyBytecode.match(
    /tableswitch\s*\{[^}]*?\n\s*0:\s*(\d+)\s*\n\s*1:\s*(\d+)\s*\n\s*2:\s*(\d+)\s*\n\s*default:\s*(\d+)/);
  assert.ok(table, "GraphicsPreset.apply FAST/FANCY/FABULOUS ordinal switch shape changed");
  const [fast, fancy, fabulous, custom] = table.slice(1).map(Number);
  assert.ok(fast < fancy && fancy < fabulous && fabulous < custom,
    "GraphicsPreset.apply preset arms are no longer laid out in ordinal order");
  return offset => offset >= custom ? "CUSTOM" : offset >= fabulous ? "FABULOUS"
    : offset >= fancy ? "FANCY" : offset >= fast ? "FAST" : "prologue";
}

function graphicsPresetArmValue(applyBytecode, arm, getter) {
  const armAt = graphicsPresetArmAt(applyBytecode);
  const instructions = bytecodeInstructions(applyBytecode);
  const matches = instructions.flatMap((entry, index) =>
    armAt(entry.offset) === arm && entry.instruction.includes(`Options.${getter}:`)
      ? [index] : []);
  assert.equal(matches.length, 1, `GraphicsPreset.apply ${arm} must set ${getter} once`);
  return (instructions[matches[0] + 1]?.instruction ?? "").replace(/\s+/g, " ");
}

// Lists every instruction the patch changed, ignoring constant-pool renumbering.
function graphicsPresetPatchedValues(rawApplyBytecode, patchedApplyBytecode) {
  const normalize = applyBytecode => bytecodeInstructions(applyBytecode)
    .map(({offset, instruction}) => ({
      offset,
      instruction: instruction.replace(/#\d+(?:,\s*\d+)?/g, "").replace(/\s+/g, " ").trim(),
    }));
  const raw = normalize(rawApplyBytecode);
  const patched = normalize(patchedApplyBytecode);
  assert.deepEqual(patched.map(({offset}) => offset), raw.map(({offset}) => offset),
    "GraphicsPreset.apply instruction layout changed");
  const armAt = graphicsPresetArmAt(rawApplyBytecode);
  return raw.flatMap((entry, index) => {
    if (entry.instruction === patched[index].instruction) return [];
    const getter = raw[index - 1]?.instruction.match(/Options\.(\w+):/)?.[1] ?? "?";
    return [`${armAt(entry.offset)} ${getter} ${entry.instruction} -> ${patched[index].instruction}`];
  });
}

const HOLDERS_PER_TURN = 16;

function cursorModel(radius, stop = null, batchLimit = HOLDERS_PER_TURN) {
  const visits = [];
  const batches = [];
  let batch = [];
  let active = true;
  let yieldPending = false;
  for (let x = -radius; x <= radius && active; x++) {
    for (let z = -radius; z <= radius && active; z++) {
      const index = visits.length;
      const outcome = stop?.index === index ? stop.kind : "success";
      const visit = {x, z, outcome};
      visits.push(visit);
      if (outcome !== "success") {
        active = false;
        yieldPending = false;
        if (batch.length) batches.push(batch);
        continue;
      }
      batch.push(visit);
      if (x === radius && z === radius) {
        active = false;
      }
      if (batch.length === batchLimit || !active) {
        batches.push(batch);
        batch = [];
        // One zero-delay continuation separates bounded batches.  The final
        // batch also crosses this barrier before scheduled futures are drained.
        yieldPending = true;
        yieldPending = false;
      }
    }
  }
  return {visits, batches, active, yieldPending};
}

function pendingDependencyLayerModel(
  turns,
  dependencyOf = new Map(),
  {drainAfterEachTurn = false} = {},
) {
  const submitted = [];
  const submittedSet = new Set();
  const completed = [];
  const completedSet = new Set();
  const pending = [];
  // Keep the planned turn shape separate from the turns actually submitted.
  // The old one-holder await stops after turn 0, but its intended schedule is
  // still [1, ...] and must be visible to the regression assertion.
  const batches = turns.map(turn => [...turn]);
  const submittedBatches = [];
  const drains = [];

  function drainPending(stage) {
    let progress = true;
    const drained = [];
    while (progress) {
      progress = false;
      const remaining = [];
      for (const item of pending) {
        const dependency = item.dependsOn;
        if (dependency == null || completedSet.has(dependency)) {
          completed.push(item.holder);
          completedSet.add(item.holder);
          drained.push(item.holder);
          progress = true;
        } else {
          remaining.push(item);
        }
      }
      pending.splice(0, pending.length, ...remaining);
    }
    drains.push({stage, drained, pending: pending.map(item => item.holder)});
  }

  for (const [turnIndex, turn] of turns.entries()) {
    const submittedBatch = [];
    for (const holder of turn) {
      assert.equal(submittedSet.has(holder), false,
        `layer model submitted holder ${holder} more than once`);
      submittedSet.add(holder);
      submitted.push(holder);
      submittedBatch.push(holder);
      pending.push({holder, dependsOn: dependencyOf.get(holder) ?? null});
    }
    submittedBatches.push(submittedBatch);
    if (drainAfterEachTurn) {
      drainPending(`turn-${turnIndex}`);
      if (pending.length) {
        return {
          blocked: true,
          blockedAtTurn: turnIndex,
          waitedAt: submitted.length,
          submitted,
          completed,
          pending: pending.map(item => ({...item})),
          batches,
          submittedBatches,
          drains,
        };
      }
    }
  }

  // The fixed barrier drains only after every holder in the layer was submitted.
  drainPending("final-layer-barrier");
  return {
    blocked: pending.length !== 0,
    blockedAtTurn: null,
    waitedAt: submitted.length,
    submitted,
    completed,
    pending: pending.map(item => ({...item})),
    batches,
    submittedBatches,
    drains,
  };
}

function boundedLayerTurns(holderCount, batchLimit) {
  const turns = [];
  for (let holder = 0; holder < holderCount; holder += batchLimit) {
    turns.push(Array.from(
      {length: Math.min(batchLimit, holderCount - holder)},
      (_, offset) => holder + offset,
    ));
  }
  return turns;
}

function oneHolderTurns(holderCount) {
  return boundedLayerTurns(holderCount, 1);
}

function layerBarrierSafetyModel(holderCount, batchLimit, waitBetweenBatches) {
  const run = pendingDependencyLayerModel(
    boundedLayerTurns(holderCount, batchLimit),
    new Map([[0, holderCount - 1]]),
    {drainAfterEachTurn: waitBetweenBatches},
  );
  return {
    blocked: run.blocked,
    waitedAt: run.waitedAt,
    scheduled: run.submitted,
    batches: run.batches,
    submittedBatches: run.submittedBatches,
    completed: run.completed,
    pending: run.pending,
    drains: run.drains,
  };
}

for (const radius of [0, 1, 2]) {
  const run = cursorModel(radius);
  const expected = (radius * 2 + 1) ** 2;
  assert.equal(run.visits.length, expected,
    `cursor radius ${radius} must visit every holder exactly once`);
  assert.equal(run.active, false,
    `cursor radius ${radius} must close after its final holder`);
  assert.equal(new Set(run.visits.map(({x, z}) => `${x},${z}`)).size, expected,
    `cursor radius ${radius} must not duplicate holders`);
  assert.equal(run.visits.at(-1)?.outcome, "success",
    `cursor radius ${radius} must process its final holder before closing`);
  assert.equal(run.batches.length, Math.ceil(expected / HOLDERS_PER_TURN),
    `cursor radius ${radius} must use the minimum bounded continuation count`);
  assert.ok(run.batches.every(batch => batch.length > 0 && batch.length <= HOLDERS_PER_TURN),
    `cursor radius ${radius} exceeded the holder batch limit`);
}
const cancelledCursor = cursorModel(2, {index: 3, kind: "cancel"});
assert.equal(cancelledCursor.visits.length, 4,
  "cursor cancellation must stop at the cancellation holder");
assert.equal(cancelledCursor.visits.at(-1)?.outcome, "cancel",
  "cursor cancellation outcome must be observable at the holder boundary");
assert.equal(cancelledCursor.active, false,
  "cursor cancellation must clear active state");
assert.equal(cancelledCursor.yieldPending, false,
  "cursor cancellation must not retain a continuation future");
const failedCursor = cursorModel(2, {index: 4, kind: "failure"});
assert.equal(failedCursor.visits.length, 5,
  "cursor failure must stop at the failed holder");
assert.equal(failedCursor.visits.at(-1)?.outcome, "failure",
  "cursor failure outcome must be observable at the holder boundary");
assert.equal(failedCursor.active, false,
  "cursor failure must clear active state");
assert.equal(failedCursor.yieldPending, false,
  "cursor failure must not retain a continuation future");

const unsafeLayerOrder = layerBarrierSafetyModel(17, HOLDERS_PER_TURN, true);
assert.equal(unsafeLayerOrder.blocked, true,
  "the dependency fixture must expose per-batch await starvation");
assert.equal(unsafeLayerOrder.waitedAt, HOLDERS_PER_TURN,
  "the unsafe model must wait before the final holder is submitted");
const fixedLayerOrder = layerBarrierSafetyModel(17, HOLDERS_PER_TURN, false);
assert.equal(fixedLayerOrder.blocked, false,
  "the active cursor must submit the complete layer before waiting");
assert.deepEqual(fixedLayerOrder.batches.map(batch => batch.length), [16, 1],
  "a 17-holder layer must span two bounded turns without an intermediate layer wait");
assert.deepEqual(fixedLayerOrder.submittedBatches.map(batch => batch.length), [16, 1],
  "the fixed barrier must submit both bounded turns before draining");
assert.deepEqual(fixedLayerOrder.scheduled,
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed barrier must submit all 17 holders before waiting");
assert.equal(fixedLayerOrder.waitedAt, 17,
  "the fixed model must reach the layer barrier only after every holder is submitted");
assert.equal(fixedLayerOrder.pending.length, 0,
  "the fixed layer barrier must drain every pending holder future");
assert.deepEqual(fixedLayerOrder.completed.sort((a, b) => a - b),
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed layer barrier must complete all holders after the final batch");
assert.equal(fixedLayerOrder.drains.length, 1,
  "the fixed model must not drain any holder batch before the final barrier");
assert.equal(fixedLayerOrder.drains.at(-1)?.stage, "final-layer-barrier",
  "the fixed layer must drain pending futures only at its final barrier");

const oldOneHolderOrder = pendingDependencyLayerModel(
  oneHolderTurns(17),
  new Map([[0, 16]]),
  {drainAfterEachTurn: true},
);
assert.equal(oldOneHolderOrder.blocked, true,
  "the old one-holder await order must block on a same-layer dependency");
assert.equal(oldOneHolderOrder.blockedAtTurn, 0,
  "the old order must await holder 0 before submitting its dependency holder");
assert.deepEqual(oldOneHolderOrder.batches.map(batch => batch.length),
  Array.from({length: 17}, () => 1),
  "the old regression model must use one holder per browser turn");
assert.deepEqual(oldOneHolderOrder.pending, [{holder: 0, dependsOn: 16}],
  "the old blocked order must retain holder 0 pending on holder 16");
assert.deepEqual(oldOneHolderOrder.submitted, [0],
  "the old await model must not submit the dependency holder after blocking");

await Promise.all([
  access(rawClientJar),
  access(raw121ClientJar),
  access(asm),
  access(asmTree),
  access(asmAnalysis),
  access(verifierSource),
]);
// MinecraftServerWorkerPatcher runs for both profiles. Exercise its budgeted worldgen
// dispatcher turns against each fetched client's real PriorityConsecutiveExecutor/StrictQueue.
for (const clientJar of [rawClientJar, raw121ClientJar]) {
  execFileSync(process.execPath, [
    join(repositoryRoot, "port/scripts/worldgen-priority-jvm-smoke.mjs"),
    "--raw-jar", clientJar,
  ], {encoding: "utf8", stdio: "inherit", timeout: 300_000});
}
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const browserPatcherSource = stepSource("Minecraft262BrowserPatcher");
assert.match(browserPatcherSource, /BROWSER_HOLDERS_PER_TURN = 16/,
  "26.2 holder batching must retain the reviewed 16-holder upper bound");
assert.match(browserPatcherSource,
  /Each holder gets a fresh token[\s\S]*?DSTORE, 10[\s\S]*?GETFIELD, owner, "markedForCancellation"/,
  "26.2 holder probe must reset its token before every cancellation check");
assert.match(browserPatcherSource,
  /scheduleChunkInLayer[\s\S]*?Opcodes\.IFEQ, cancel[\s\S]*?ICONST_1[\s\S]*?endChunkHolderProbe/,
  "26.2 accepted holder probe path is missing");
assert.match(browserPatcherSource,
  /code\.add\(cancel\);[\s\S]*?ICONST_0[\s\S]*?endChunkHolderProbe/,
  "26.2 rejected/cancelled holder probe path is missing");
for (const required of [
  "BROWSER_LAYER_YIELD",
  "CHUNK_GENERATION_YIELD",
  "BrowserChunkGenerationYield",
  "browserLayerYield",
  '"startThread"',
  '"(Lorg/teavm/platform/PlatformRunnable;)V"',
  "writeChunkGenerationYieldHelper",
]) {
  assert.equal(browserPatcherSource.includes(required), true,
    `${CANONICAL_PROFILE.id} patcher must return a pending batch continuation: ${required}`);
}
const clientPatcherSource = stepSource("MinecraftClientPatcher");
const schedulerSource = await readFile(
  join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java"),
  "utf8",
);
for (const contract of [
  "patchMobBrowserAiCooperation(args[0], root.resolve(",
  '"net/minecraft/world/entity/Mob.class"',
  "patchDebugScreenOverlayBrowserNoChunk(args[0], root.resolve(",
  '"net/minecraft/client/gui/components/DebugScreenOverlay.class"',
  "browserWorldgenMobAiPulse()",
  "browserWorldgenMobEntityPulse()",
  '"Mob.serverAiStep AI stage shape changed: "',
]) {
  assert.ok(clientPatcherSource.includes(contract),
    `missing Mob.serverAiStep cooperation contract: ${contract}`);
}
assert.equal(clientPatcherSource.includes("patchServerLevelBrowserSafeDefaults"), false,
  "the old hostile-mob suppression helper must stay removed");
assert.match(schedulerSource, /public static void mobAiPulse\(\)/,
  "scheduler is missing the Mob AI cooperative checkpoint");
assert.match(schedulerSource, /public static void mobEntityPulse\(Object entity\)/,
  "scheduler is missing the LivingEntity Mob fallback checkpoint");
assert.match(schedulerSource, /__gaiusMobAiTelemetry/,
  "Mob AI telemetry must remain opt-in and bounded");
for (const helper of forbiddenDeepWorldgenPatchHelpers) {
  assert.equal(browserPatcherSource.includes(helper), false,
    `${helper} must not exist under the synchronous deep-worldgen policy`);
}
for (const contract of [
  "browserWorldgenBeginServerWorkTurn()",
  "method.instructions.insertBefore(instruction, browserWorldgenBeginServerWorkTurn())",
  "method.instructions.insert(instruction, browserWorldgenCheckpoint())",
  "browserWorldgenBeginTaskWork()",
  "browserWorldgenEndTaskWork()",
  "instrumentBrowserTaskScope(",
  '"MinecraftServer.pollTask"',
  '"beginTaskWork",\n                "(Ljava/lang/String;)I"',
  '"endTaskWork",\n                "(I)V"',
]) {
  assert.ok(clientPatcherSource.includes(contract),
    `missing server tick scheduler boundary contract: ${contract}`);
}
assert.ok(browserPatcherSource.includes("requireNoServerWorkTurnReset"),
  "26.2 task patcher does not guard the shared server-work clock from per-task resets");
for (const contract of [
  '"beginTaskWork",\n                "(Ljava/lang/String;)I"',
  '"endTaskWork",\n                "(I)V"',
]) {
  assert.ok(browserPatcherSource.includes(contract),
    `missing ${CANONICAL_PROFILE.id} task-scope token contract: ${contract}`);
}
assert.ok(browserPatcherSource.includes(
  'writeComputeFrames(node, root.resolve(owner + ".class"))',
), "26.2 task patcher does not recompute frames after task-scope instrumentation");
const root = await mkdtemp(join(tmpdir(), "gaius-mc262-p1-"));
try {
  const classes = join(root, "classes");
  const clientPatches = join(root, "client-patches");
  const browserPatches = join(root, "browser-patches");
  const generic121Patches = join(root, "generic-121-patches");
  const clientJar = join(root, "client.jar");
  const generic121Jar = join(root, "client-1.21.11.jar");
  await Promise.all([
    mkdir(classes, {recursive: true}),
    mkdir(clientPatches, {recursive: true}),
    mkdir(browserPatches, {recursive: true}),
    mkdir(generic121Patches, {recursive: true}),
    copyFile(rawClientJar, clientJar),
    copyFile(raw121ClientJar, generic121Jar),
  ]);

  const javac = jdkTool("javac");
  const java = jdkTool("java");
  const jar = jdkTool("jar");
  const javap = jdkTool("javap");
  // Java's classpath separator follows the host OS, not the path separator
  // used to compose the individual jar paths.  The smoke is also run from
  // PowerShell on Windows, where ':' silently turns the second jar into an
  // invalid drive-qualified classpath entry.
  const classpath = [asm, asmTree].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", classpath,
    "-sourcepath", join(repositoryRoot, "port/tools/src/main/java"), "-d", classes,
    join(toolsSource, "MinecraftClientPatcher.java"),
    join(toolsSource, "Minecraft262BrowserPatcher.java"),
  ], {encoding: "utf8", timeout: 30_000});
  // The REQUIRED class-initialization patch rewrites a Gaius source class that
  // the overlay build compiles into the client jar; seed it as patchkit does.
  const seedClasses = join(root, "client-source-classes");
  execFileSync(javac, ["--release", "21", "-proc:none", "-d", seedClasses,
    join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserClassInitialization.java"),
  ], {encoding: "utf8", timeout: 30_000});
  for (const target of [clientJar, generic121Jar]) {
    execFileSync(jar, ["--update", "--file", target, "-C", seedClasses,
      "dev/gaius/browser/BrowserClassInitialization.class"], {encoding: "utf8", timeout: 30_000});
  }
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.MinecraftClientPatcher", clientJar, clientPatches, CANONICAL_PROFILE.id], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(jar, ["--update", "--file", clientJar, "-C", clientPatches, "."], {
    encoding: "utf8", timeout: 30_000,
  });

  const rawOptions262 = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.client.Options"], {
      encoding: "utf8", maxBuffer: 24 * 1024 * 1024, timeout: 30_000,
    });
  const patchedOptions262 = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.Options"], {
    encoding: "utf8", maxBuffer: 24 * 1024 * 1024, timeout: 30_000,
  });
  assertDefaultSprintKeyMapping(rawOptions262, "26.2 raw", 341);
  assertDefaultSprintKeyMapping(patchedOptions262, "26.2 patched", 341);

  const patchedScreen262 = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.gui.screens.Screen"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  const panorama262 = method(patchedScreen262,
    "protected void extractPanorama(net.minecraft.client.gui.GuiGraphicsExtractor, float);",
    "protected void extractMenuBackground(net.minecraft.client.gui.GuiGraphicsExtractor);");
  assert.match(panorama262, /GameRenderer\.panorama/,
    "26.2 menu panorama must keep the vanilla animated renderer");
  assert.match(panorama262, /Panorama\.extractRenderState/,
    "26.2 menu panorama extraction call was removed");
  const menuBackground262 = method(patchedScreen262,
    "protected void extractMenuBackground(net.minecraft.client.gui.GuiGraphicsExtractor, int, int, int, int);",
    "public static void extractMenuBackgroundTexture");
  assert.match(menuBackground262, /extractMenuBackgroundTexture/,
    "26.2 menu background must keep its texture draw path");
  const patchedNaturalSpawner262 = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.NaturalSpawner"], {
      encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
    });
  const spawnGeneration262 = method(patchedNaturalSpawner262,
    "public static void spawnMobsForChunkGeneration(net.minecraft.world.level.ServerLevelAccessor, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, net.minecraft.world.level.ChunkPos, net.minecraft.util.RandomSource);",
    "private static net.minecraft.core.BlockPos getTopNonCollidingPos");
  assert.ok(bytecodeInstructions(spawnGeneration262).length > 1,
    "26.2 generation-time spawning must retain the vanilla implementation");
  for (const operation of ["getCreatureProbability", "finalizeSpawn", "addFreshEntityWithPassengers"]) {
    assert.ok(spawnGeneration262.includes(operation),
      "26.2 generation-time spawning lost " + operation);
  }
  for (const operation of ["BrowserGenerationSpawnTelemetry.begin",
    "BrowserGenerationSpawnTelemetry.entityAdded",
    "BrowserGenerationSpawnTelemetry.complete",
    "BrowserGenerationSpawnTelemetry.failed"]) {
    assert.ok(spawnGeneration262.includes(operation),
      "26.2 generation-spawn telemetry lost " + operation);
  }
  const patchedServerLevel262 = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.server.level.ServerLevel"], {
      encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
    });
  const spawningMonsters262 = method(patchedServerLevel262,
    "public boolean isSpawningMonsters();", "public void close() throws java.io.IOException;");
  assert.match(spawningMonsters262, /SPAWN_MONSTERS/,
    "26.2 hostile-mob gamerule filtering was removed");
  assert.doesNotMatch(spawningMonsters262, /Code:\s*0:\s*iconst_0\s+1:\s*ireturn/,
    "26.2 ServerLevel.isSpawningMonsters must not be hard-disabled");
  const patchedDebugScreenOverlay262 = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.gui.components.DebugScreenOverlay"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  const serverChunk262 = method(patchedDebugScreenOverlay262,
    "private net.minecraft.world.level.chunk.LevelChunk getServerChunk();",
    "private net.minecraft.world.level.chunk.LevelChunk getClientChunk();");
  assert.deepEqual(bytecodeInstructions(serverChunk262).map(({instruction}) => instruction),
    ["aconst_null", "areturn"],
    "26.2 F3 server-chunk lookup must not synchronously resume world generation");
  const clientChunk262 = method(patchedDebugScreenOverlay262,
    "private net.minecraft.world.level.chunk.LevelChunk getClientChunk();",
    "public boolean showDebugScreen();");
  assert.notDeepEqual(bytecodeInstructions(clientChunk262).map(({instruction}) => instruction),
    ["aconst_null", "areturn"],
    "26.2 F3 client-chunk lookup must remain available to vanilla debug entries");

  // The canonical deep-worldgen path uses the adapter only at the synchronous
  // loops that can outrun holder-boundary pulses.  Assert the actual patched
  // bytecode so a source-only marker cannot mask a missing runtime callsite.
  const patchedChunkGenerator = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.chunk.ChunkGenerator"], {
    encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
  });
  const patchedClimateTree = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.biome.Climate$RTree$SubTree"], {
    encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
  });
  const patchedJigsawPlacer = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer"], {
    encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
  });
  const patchedNoiseGenerator = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator"], {
    encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
  });
  const patchedSurfaceSystem = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.levelgen.SurfaceSystem"], {
    encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
  });
  const patchedWorldCarver = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.levelgen.carver.WorldCarver"], {
    encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
  });
  const patchedLightEngine = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.lighting.LightEngine"], {
    encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
  });
  const patchedChunkSection = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.level.chunk.LevelChunkSection"], {
    encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
  });
  assertDeepWorldgenPulseInMethod(patchedChunkGenerator,
    "public void createStructures(", 1, "26.2 ChunkGenerator.createStructures");
  assertDeepWorldgenPulseInMethod(patchedClimateTree,
    "protected net.minecraft.world.level.biome.Climate$RTree$Leaf<T> search(",
    1, "26.2 Climate.RTree.SubTree.search");
  assert.ok(occurrences(patchedJigsawPlacer, "BrowserWorldgenDeepCheckpoint.pulse") >= 1,
    "26.2 JigsawPlacement.Placer must contain a budget-aware deep worldgen pulse");
  assert.doesNotMatch(patchedChunkGenerator, /BrowserWorldgenDeepCheckpoint\.checkpoint/,
    "26.2 ChunkGenerator must not retain unconditional deep checkpoints");
  assert.doesNotMatch(patchedClimateTree, /BrowserWorldgenDeepCheckpoint\.checkpoint/,
    "26.2 Climate.RTree.SubTree must not retain unconditional deep checkpoints");
  assert.doesNotMatch(patchedJigsawPlacer, /BrowserWorldgenDeepCheckpoint\.checkpoint/,
    "26.2 JigsawPlacement.Placer must not retain unconditional deep checkpoints");
  assertDeepWorldgenPulse(patchedNoiseGenerator, 4,
    "26.2 NoiseBasedChunkGenerator");
  assertDeepWorldgenPulse(patchedSurfaceSystem, 1, "26.2 SurfaceSystem");
  assertDeepWorldgenPulse(patchedWorldCarver, 1, "26.2 WorldCarver");
  assertDeepWorldgenPulse(patchedLightEngine, 2, "26.2 LightEngine");
  assertDeepWorldgenPulse(patchedChunkSection, 1, "26.2 LevelChunkSection");
  const rawDedicatedServer = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.server.dedicated.DedicatedServer"], {
      encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
    });
  const patchedDedicatedServer = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.server.dedicated.DedicatedServer"], {
      encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
    });
  const rawInit = method(rawDedicatedServer,
    "protected boolean initServer() throws java.io.IOException;",
    "public net.minecraft.world.level.GameType gameMode();");
  assert.equal(occurrences(rawInit, "WorldData.setGameType"), 1,
    "26.2 raw initServer game type override shape changed");
  assertDedicatedServerGameTypeContract(patchedDedicatedServer, CANONICAL_PROFILE.id,
    "protected boolean initServer() throws java.io.IOException;");
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.Minecraft262BrowserPatcher", clientJar, browserPatches, requestedProfile], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(jar, ["--update", "--file", clientJar, "-C", browserPatches, "."], {
    encoding: "utf8", timeout: 30_000,
  });

  const patchedSpriteContents = execFileSync(javap, ["-classpath", clientJar,
    "-p", "-c", "net.minecraft.client.renderer.texture.SpriteContents"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  const staticImageRelease = method(patchedSpriteContents,
    "public void browserReleaseStaticImagesAfterUpload();",
    "public void browserReleaseAllImagesBeforeReload();");
  assert.equal(occurrences(staticImageRelease,
    "NativeImage.close"), 1,
  "26.2 static sprite release must close every retained native mip image");
  assert.match(staticImageRelease, /Field animatedTexture/,
    "26.2 static sprite release lost its animation guard");
  assert.match(staticImageRelease, /anewarray\s+#[0-9]+\s+\/\/ class com\/mojang\/blaze3d\/platform\/NativeImage/,
    "26.2 static sprite release must detach the closed mip array");
  const allImageRelease = method(patchedSpriteContents,
    "public void browserReleaseAllImagesBeforeReload();",
    "public java.lang.String toString();");
  assert.equal(occurrences(allImageRelease,
    "NativeImage.close"), 1,
  "26.2 pre-reload sprite release must close every retained native mip image");
  assert.doesNotMatch(allImageRelease, /Field animatedTexture/,
    "26.2 pre-reload sprite release must include animated sprites");
  assert.match(allImageRelease, /anewarray\s+#[0-9]+\s+\/\/ class com\/mojang\/blaze3d\/platform\/NativeImage/,
    "26.2 pre-reload sprite release must detach the closed mip array");

  const patchedTextureAtlas = execFileSync(javap, ["-classpath", clientJar,
    "-p", "-c", "net.minecraft.client.renderer.texture.TextureAtlas"], {
      encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
    });
  const atlasInitialUpload = method(patchedTextureAtlas,
    "private void uploadInitialContents();",
    "public void dumpContents(");
  assert.equal(occurrences(atlasInitialUpload,
    "browserReleaseStaticSpriteImages"), 1,
  "26.2 initial atlas upload must release static CPU images exactly once");
  assert.ok(atlasInitialUpload.lastIndexOf("browserReleaseStaticSpriteImages")
      > atlasInitialUpload.indexOf("Method uploadAnimationFrames:()V"),
  "26.2 atlas CPU-image release must run after the final initial texture upload");
  const atlasStaticRelease = method(patchedTextureAtlas,
    "private void browserReleaseStaticSpriteImages();");
  assert.equal(occurrences(atlasStaticRelease,
    "SpriteContents.browserReleaseStaticImagesAfterUpload"), 1,
  "26.2 atlas static release helper must visit every sprite contents object");
  const atlasUpload = method(patchedTextureAtlas,
    "public void upload(net.minecraft.client.renderer.texture.SpriteLoader$Preparations);",
    "private void uploadInitialContents();");
  assert.equal(occurrences(atlasUpload,
    "browserReleaseOldSpriteImagesBeforeReload"), 1,
  "26.2 atlas reload must release old sprite images exactly once");
  assert.ok(atlasUpload.indexOf("browserReleaseOldSpriteImagesBeforeReload")
      < atlasUpload.indexOf("Method createTexture:(III)V"),
  "26.2 atlas reload must release old sprite images before allocating the replacement atlas");
  const atlasOldRelease = method(patchedTextureAtlas,
    "private void browserReleaseOldSpriteImagesBeforeReload();",
    "public void browserPrepareForSpriteReload();");
  assert.equal(occurrences(atlasOldRelease,
    "SpriteContents.browserReleaseAllImagesBeforeReload"), 1,
  "26.2 old atlas release helper must visit every previous sprite contents object");
  const atlasPrepareForReload = method(patchedTextureAtlas,
    "public void browserPrepareForSpriteReload();");
  assert.equal(occurrences(atlasPrepareForReload,
    "SpriteContents$AnimationState.close"), 1,
  "26.2 pre-reload helper must close every detached animation state");
  assert.equal(occurrences(atlasPrepareForReload,
    "GpuBuffer.close"), 1,
  "26.2 pre-reload helper must close the old animated-sprite UBO");
  assert.equal(occurrences(atlasPrepareForReload,
    "browserReleaseOldSpriteImagesBeforeReload"), 1,
  "26.2 pre-reload helper must release old sprite CPU images exactly once");
  const detachAnimationStates = atlasPrepareForReload.lastIndexOf(
    "Field animatedTexturesStates:Ljava/util/List;");
  const closeAnimationStates = atlasPrepareForReload.indexOf(
    "SpriteContents$AnimationState.close");
  const closeSpriteUbo = atlasPrepareForReload.indexOf("GpuBuffer.close");
  const nullSpriteUbo = atlasPrepareForReload.indexOf(
    "Field spriteUbos:Lcom/mojang/blaze3d/buffers/GpuBuffer;", closeSpriteUbo + 1);
  const releaseOldSpriteImages = atlasPrepareForReload.indexOf(
    "browserReleaseOldSpriteImagesBeforeReload");
  assert.ok(detachAnimationStates >= 0
      && closeAnimationStates > detachAnimationStates
      && closeSpriteUbo > closeAnimationStates
      && nullSpriteUbo > closeSpriteUbo
      && releaseOldSpriteImages > nullSpriteUbo,
  "26.2 pre-reload helper must detach animation ticks, close animation GPU state, "
      + "null the sprite UBO, then release old CPU images");
  assert.match(atlasPrepareForReload,
    /aconst_null\s+\d+: putfield\s+#[0-9]+\s+\/\/ Field spriteUbos:Lcom\/mojang\/blaze3d\/buffers\/GpuBuffer;/,
  "26.2 pre-reload helper must null the closed sprite UBO");

  const patchedAtlasManager = execFileSync(javap, ["-classpath", clientJar,
    "-p", "-c", "net.minecraft.client.resources.model.sprite.AtlasManager"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  const atlasManagerConstructor = method(patchedAtlasManager,
    "public net.minecraft.client.resources.model.sprite.AtlasManager(net.minecraft.client.renderer.texture.TextureManager, int);",
    "public net.minecraft.client.renderer.texture.TextureAtlas getAtlasOrThrow");
  assert.match(atlasManagerConstructor,
    /iload_2\s+\d+: putfield\s+#[0-9]+\s+\/\/ Field maxMipmapLevels:I/,
    "26.2 browser AtlasManager constructor must preserve configured mipmaps");
  const updateMaxMipLevel = method(patchedAtlasManager,
    "public void updateMaxMipLevel(int);",
    "public void close();");
  assert.match(updateMaxMipLevel,
    /iload_1\s+\d+: putfield\s+#[0-9]+\s+\/\/ Field maxMipmapLevels:I/,
    "26.2 browser AtlasManager updates must preserve the configured mipmap level");
  const atlasManagerReload = method(patchedAtlasManager,
    "public java.util.concurrent.CompletableFuture<java.lang.Void> reload(",
    "private void updateSpriteMaps(");
  assert.equal(occurrences(atlasManagerReload,
    "browserReleaseAtlasImagesBeforeReload"), 1,
  "26.2 atlas manager must release retained images once before preparing replacements");
  const atlasManagerReloadInstructions = bytecodeInstructions(atlasManagerReload);
  assert.ok(atlasManagerReloadInstructions[0]?.instruction === "aload_0"
      && atlasManagerReloadInstructions[1]?.instruction
        .includes("browserReleaseAtlasImagesBeforeReload"),
  "26.2 atlas manager must release old images before any replacement atlas load is scheduled");
  const atlasManagerRelease = method(patchedAtlasManager,
    "private void browserReleaseAtlasImagesBeforeReload();");
  assert.equal(occurrences(atlasManagerRelease,
    "TextureAtlas.browserPrepareForSpriteReload"), 1,
  "26.2 atlas manager release helper must visit every existing atlas");
  const patchedPendingStitches = execFileSync(javap, ["-classpath", clientJar,
    "-p", "-c",
    "net.minecraft.client.resources.model.sprite.AtlasManager$PendingStitchResults"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  const pendingJoinAndUpload = method(patchedPendingStitches,
    "public java.util.Map<net.minecraft.client.resources.model.sprite.SpriteId, net.minecraft.client.renderer.texture.TextureAtlasSprite> joinAndUpload();",
    "public java.util.concurrent.CompletableFuture<net.minecraft.client.renderer.texture.SpriteLoader$Preparations> get(");
  assert.equal(occurrences(pendingJoinAndUpload, "java/util/List.clear:()V"), 0,
    "26.2 atlas upload must not invalidate shared reload state before all listeners finish");
  assert.equal(occurrences(pendingJoinAndUpload, "java/util/Map.clear:()V"), 0,
    "26.2 atlas upload must retain futures queried through PendingStitchResults.get");

  // Verify the client crack overlay probe was inserted into the actual ASM
  // methods, rather than merely checking source names or patcher strings.
  const patchedClientLevel = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.multiplayer.ClientLevel"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  const patchedLevelExtractor = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.renderer.extract.LevelExtractor"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  const patchedLevelRenderer = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.renderer.LevelRenderer"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  assert.equal(occurrences(method(patchedClientLevel,
    "public void destroyBlockProgress(int, net.minecraft.core.BlockPos, int);",
    "private void"), "BrowserBlockBreakingTelemetry.recordDestroyProgress"), 1,
  "ASM patch did not instrument ClientLevel.destroyBlockProgress");
  if (patchedLevelExtractor.includes("private void extractBlockDestroyAnimation(")) {
    assert.equal(occurrences(method(patchedLevelExtractor,
      "private void extractBlockDestroyAnimation(", "private void extractBlockOutline("),
      "BrowserBlockBreakingTelemetry.recordExtraction"), 1,
    "ASM patch did not instrument LevelExtractor extraction");
    assert.equal(occurrences(method(patchedLevelExtractor,
      "private void extractBlockDestroyAnimation(", "private void extractBlockOutline("),
      "BrowserBlockBreakingTelemetry.recordEmitted"), 1,
    "ASM patch did not instrument breaking-state emission");
  }
  const breakingMethod = patchedLevelRenderer.includes(
    "private void submitBlockDestroyAnimation(")
    ? "private void submitBlockDestroyAnimation("
    : "public void destroyBlockProgress(";
  assert.ok(patchedLevelRenderer.includes(breakingMethod),
    "patched LevelRenderer has no block-breaking render method");
  const patchedBreakingMethod = method(patchedLevelRenderer, breakingMethod,
    "private void submitBlockOutline(");
  assert.equal(occurrences(patchedBreakingMethod,
    "BrowserBlockBreakingTelemetry.recordSubmitPass"), 1,
  "ASM patch did not instrument LevelRenderer submit pass");
  if (breakingMethod.startsWith("private void submitBlockDestroyAnimation(")) {
    assert.equal(occurrences(patchedBreakingMethod,
      "SubmitNodeCollector.submitBreakingBlockModel"), 1,
    "LevelRenderer breaking-model callsite is missing");
    assert.equal(occurrences(patchedBreakingMethod,
      "BrowserBlockBreakingTelemetry.recordActualSubmit"), 1,
    "ASM patch did not instrument actual breaking-model submission");
  }

  // Section rebuild requests must survive skipped world renders, upload timeouts and
  // out-of-order mesh uploads (terrain holes / black sections).
  assert.equal(occurrences(method(patchedLevelExtractor, "public void extract(", "private void"),
    "BrowserSectionAudit.requeueUnconsumed"), 1,
  "LevelExtractor.extract does not requeue unconsumed section updates");
  assert.equal(occurrences(method(patchedLevelRenderer, "private void compileSections(",
    "private void checkPoseStack("), "java/util/List.clear"), 1,
  "LevelRenderer.compileSections does not mark extracted updates consumed");
  // The late visible-section refresh must replace the lists, not append duplicates.
  const patchedRender = method(patchedLevelRenderer,
    "public void render(com.mojang.blaze3d.resource.GraphicsResourceAllocator", "\n  p");
  const clearVisibleAt = patchedRender.lastIndexOf("clearVisibleSections:()V");
  const addVisibleAt = patchedRender.lastIndexOf("SectionOcclusionGraph.addSectionsInFrustum");
  assert.ok(addVisibleAt >= 0 && clearVisibleAt >= 0 && clearVisibleAt < addVisibleAt,
    "LevelRenderer.render visible-section refresh does not clear the lists before refilling");
  const javapClass = name => execFileSync(javap, ["-classpath", clientJar, "-p", "-c", name], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patchedRenderSection = javapClass(
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection");
  assert.ok(patchedRenderSection.includes("public java.lang.Object gaius$latestMesh;"),
    "RenderSection latest-mesh field is missing");
  assert.equal(occurrences(method(patchedRenderSection, "private void checkSectionMesh(",
    "private void vertexBufferUploadCallback("), "BrowserSectionAudit.staleMeshRejected"), 1,
  "RenderSection.checkSectionMesh does not reject superseded meshes");
  assert.equal(occurrences(method(patchedRenderSection, "public void reset(",
    "public net.minecraft.core.BlockPos getRenderOrigin("), "Field gaius$latestMesh"), 1,
  "RenderSection.reset does not clear the latest mesh");
  const patchedCompileTask = javapClass(
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask");
  assert.equal(occurrences(patchedCompileTask,
    "BrowserSectionAudit.requeueAfterUploadTimeout"), 1,
  "CompileTask upload timeout does not requeue its section");
  assert.equal(occurrences(patchedCompileTask, "RenderSection.gaius$latestMesh"), 1,
  "CompileTask does not record its mesh as the section's latest");

  const verifierClasspath = [asm, asmTree, asmAnalysis].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", verifierClasspath,
    "-sourcepath", join(repositoryRoot, "port/tools/src/main/java"), "-d", classes, verifierSource,
  ], {encoding: "utf8", timeout: 30_000});
  const verifierOutput = execFileSync(java, [
    "-classpath", [classes, verifierClasspath].join(delimiter),
    "GaiusChunkLayerBytecodeVerifier", clientJar, CANONICAL_PROFILE.id,
  ], {encoding: "utf8", timeout: 30_000});
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*ChunkGenerationTask\.class/,
    "ASM BasicVerifier did not validate ChunkGenerationTask");
  assert.match(verifierOutput, /PENDING_LAYER_YIELD_OK .*ChunkGenerationTask/,
    "ASM CFG verifier did not validate the pending batch continuation");
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*BrowserChunkGenerationYield\.class/,
    `${CANONICAL_PROFILE.id} continuation helper was not verified`);
  assert.match(verifierOutput, /PENDING_HELPER_JVM_OK normalCompletion=1 duplicateContinuation=0/,
    `${CANONICAL_PROFILE.id} emitted continuation helper was not executed on the JVM`);
  assert.match(verifierOutput, /CFG_VERIFIER_OK net\/minecraft\/server\/level\/ChunkGenerationTask/,
    "ASM CFG verifier did not validate the chunk layer barrier");
  assert.match(verifierOutput,
    new RegExp(String.raw`PROFILE_CFG_OK ${profileIdPattern(CANONICAL_PROFILE)} net\/minecraft\/server\/level\/ChunkGenerationTask`),
    `ASM profile verifier did not validate ${CANONICAL_PROFILE.id} holder pulse paths`);
  process.stdout.write(verifierOutput);

  const rawGraphics = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const patchedGraphics = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const rawGraphicsApply = graphicsPresetApply(rawGraphics);
  const patchedGraphicsApply = graphicsPresetApply(patchedGraphics);
  // Applying a preset (Video Settings, or Minecraft.<init> with ?gaiusPresetReplay=1) writes its
  // distances over the player's options, so FANCY and FABULOUS carry the 8/6 contract too.
  assert.deepEqual(graphicsPresetDistanceConstants(patchedGraphicsApply, "renderDistance"),
    ["bipush 8", "bipush 8", "bipush 8"],
    "" + CANONICAL_PROFILE.id + " FAST/FANCY/FABULOUS render distance is not the browser 8 contract");
  assert.deepEqual(graphicsPresetDistanceConstants(patchedGraphicsApply, "simulationDistance"),
    ["bipush 6", "bipush 6", "bipush 6"],
    "" + CANONICAL_PROFILE.id + " FAST/FANCY/FABULOUS simulation distance is not the browser 6 contract");
  assert.deepEqual(graphicsPresetDistanceConstants(rawGraphicsApply, "renderDistance"),
    ["bipush 8", "bipush 16", "bipush 32"],
    "" + CANONICAL_PROFILE.id + " raw FAST/FANCY render distance shape changed");
  assert.deepEqual(graphicsPresetDistanceConstants(rawGraphicsApply, "simulationDistance"),
    ["bipush 6", "bipush 12", "bipush 12"],
    "" + CANONICAL_PROFILE.id + " raw FAST/FANCY simulation distance shape changed");
  const presetDistances = (applyBytecode, arm) => ["renderDistance", "simulationDistance"]
    .map(getter => graphicsPresetArmValue(applyBytecode, arm, getter).replace(/^bipush /, ""))
    .join("/");
  assert.equal(presetDistances(patchedGraphicsApply, "FAST"), "8/6",
    "" + CANONICAL_PROFILE.id + " FAST graphics preset distances changed from vanilla 8/6");
  assert.equal(presetDistances(patchedGraphicsApply, "FANCY"), "8/6",
    "" + CANONICAL_PROFILE.id + " FANCY graphics preset still overwrites the browser 8/6 distances");
  assert.equal(presetDistances(rawGraphicsApply, "FANCY"), "16/12",
    "" + CANONICAL_PROFILE.id + " raw FANCY graphics preset distance shape changed");
  assert.equal(presetDistances(patchedGraphicsApply, "FABULOUS"), "8/6",
    "" + CANONICAL_PROFILE.id + " FABULOUS graphics preset still overwrites the browser 8/6 distances");
  assert.equal(presetDistances(rawGraphicsApply, "FABULOUS"), "32/12",
    "" + CANONICAL_PROFILE.id + " raw FABULOUS graphics preset distance shape changed");
  assert.equal(graphicsPresetArmValue(patchedGraphicsApply, "FANCY", "mipmapLevels"), "iconst_4",
    "" + CANONICAL_PROFILE.id + " FANCY graphics preset lost vanilla mipmapLevels 4");
  assert.deepEqual(graphicsPresetPatchedValues(rawGraphicsApply, patchedGraphicsApply), [
    "FANCY renderDistance bipush 16 -> bipush 8",
    "FANCY simulationDistance bipush 12 -> bipush 6",
    "FABULOUS renderDistance bipush 32 -> bipush 8",
    "FABULOUS simulationDistance bipush 12 -> bipush 6",
  ], "" + CANONICAL_PROFILE.id + " graphics preset patch must change only the FANCY/FABULOUS render/simulation distances");
  assert.equal(graphicsPresetCustomReturn(patchedGraphicsApply),
    graphicsPresetCustomReturn(rawGraphicsApply),
    "" + CANONICAL_PROFILE.id + " CUSTOM graphics preset arm changed");

  // The generic 1.21.11 path never invokes Minecraft262BrowserPatcher.  Run it on
  // a disposable 1.21 jar and verify its FAST values remain vanilla 8/6.
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.MinecraftClientPatcher", generic121Jar, generic121Patches,
    LEGACY_PROFILE.id], {encoding: "utf8", timeout: 60_000});
  execFileSync(jar, ["--update", "--file", generic121Jar, "-C", generic121Patches, "."], {
    encoding: "utf8", timeout: 30_000,
  });
  const rawOptions121 = execFileSync(javap, ["-classpath", raw121ClientJar, "-p", "-c",
    "net.minecraft.client.Options"], {
      encoding: "utf8", maxBuffer: 24 * 1024 * 1024, timeout: 30_000,
    });
  const patchedOptions121 = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.client.Options"], {
      encoding: "utf8", maxBuffer: 24 * 1024 * 1024, timeout: 30_000,
    });
  assertDefaultSprintKeyMapping(rawOptions121, "" + LEGACY_PROFILE.id + " raw", 341);
  assertDefaultSprintKeyMapping(patchedOptions121, "" + LEGACY_PROFILE.id + " patched", 341);
  const patched121Screen = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.client.gui.screens.Screen"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
  const panorama121 = method(patched121Screen,
    "protected void renderPanorama(net.minecraft.client.gui.GuiGraphics, float);",
    "protected void renderMenuBackground(net.minecraft.client.gui.GuiGraphics);");
  assert.match(panorama121, /GameRenderer\.getPanorama/,
    "" + LEGACY_PROFILE.id + " menu panorama must keep the vanilla animated renderer");
  assert.match(panorama121, /PanoramaRenderer\.render/,
    "" + LEGACY_PROFILE.id + " menu panorama render call was removed");
  const menuBackground121 = method(patched121Screen,
    "protected void renderMenuBackground(net.minecraft.client.gui.GuiGraphics, int, int, int, int);",
    "public static void renderMenuBackgroundTexture");
  assert.match(menuBackground121, /renderMenuBackgroundTexture/,
    "" + LEGACY_PROFILE.id + " menu background must keep its texture draw path");
  const patched121NaturalSpawner = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.world.level.NaturalSpawner"], {
      encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
    });
  const spawnGeneration121 = method(patched121NaturalSpawner,
    "public static void spawnMobsForChunkGeneration(net.minecraft.world.level.ServerLevelAccessor, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, net.minecraft.world.level.ChunkPos, net.minecraft.util.RandomSource);",
    "private static net.minecraft.core.BlockPos getTopNonCollidingPos");
  assert.ok(bytecodeInstructions(spawnGeneration121).length > 1,
    "" + LEGACY_PROFILE.id + " generation-time spawning must retain the vanilla implementation");
  for (const operation of ["getCreatureProbability", "finalizeSpawn", "addFreshEntityWithPassengers"]) {
    assert.ok(spawnGeneration121.includes(operation),
      "" + LEGACY_PROFILE.id + " generation-time spawning lost " + operation);
  }
  for (const operation of ["BrowserGenerationSpawnTelemetry.begin",
    "BrowserGenerationSpawnTelemetry.entityAdded",
    "BrowserGenerationSpawnTelemetry.complete",
    "BrowserGenerationSpawnTelemetry.failed"]) {
    assert.ok(spawnGeneration121.includes(operation),
      "" + LEGACY_PROFILE.id + " generation-spawn telemetry lost " + operation);
  }
  const patched121ServerLevel = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.server.level.ServerLevel"], {
      encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
    });
  const spawningMonsters121 = method(patched121ServerLevel,
    "public boolean isSpawningMonsters();", "public void close() throws java.io.IOException;");
  assert.match(spawningMonsters121, /SPAWN_MONSTERS/,
    "" + LEGACY_PROFILE.id + " hostile-mob gamerule filtering was removed");
  assert.doesNotMatch(spawningMonsters121, /Code:\s*0:\s*iconst_0\s+1:\s*ireturn/,
    "" + LEGACY_PROFILE.id + " ServerLevel.isSpawningMonsters must not be hard-disabled");
  const patched121ChunkGenerator = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.world.level.chunk.ChunkGenerator"], {
    encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
  });
  const patched121ClimateTree = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.world.level.biome.Climate$RTree$SubTree"], {
    encoding: "utf8", maxBuffer: 12 * 1024 * 1024, timeout: 30_000,
  });
  assert.doesNotMatch(patched121ChunkGenerator, /BrowserWorldgenDeepCheckpoint\.checkpoint/,
    "" + LEGACY_PROFILE.id + " ChunkGenerator must keep the " + CANONICAL_PROFILE.id + " deep checkpoint path isolated");
  assert.doesNotMatch(patched121ClimateTree, /BrowserWorldgenDeepCheckpoint\.checkpoint/,
    "" + LEGACY_PROFILE.id + " Climate.RTree.SubTree must keep the " + CANONICAL_PROFILE.id + " deep checkpoint path isolated");
  const raw121DedicatedServer = execFileSync(javap, ["-classpath", raw121ClientJar, "-p", "-c",
    "net.minecraft.server.dedicated.DedicatedServer"], {
      encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
    });
  const patched121DedicatedServer = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.server.dedicated.DedicatedServer"], {
      encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
    });
  const raw121Init = method(raw121DedicatedServer,
    "public boolean initServer() throws java.io.IOException;",
    "public net.minecraft.world.level.GameType gameMode();");
  assert.equal(occurrences(raw121Init, "WorldData.setGameType"), 1,
    "" + LEGACY_PROFILE.id + " raw initServer game type override shape changed");
  assertDedicatedServerGameTypeContract(patched121DedicatedServer, LEGACY_PROFILE.id,
    "public boolean initServer() throws java.io.IOException;");
  const generic121Graphics = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const generic121MinecraftServer = execFileSync(javap, [
    "-classpath", generic121Jar, "-p", "-c", "net.minecraft.server.MinecraftServer",
  ], {encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000});
  const generic121RunServer = method(
    generic121MinecraftServer, "protected void runServer();",
  );
  assertRunServerWaitTelemetryOrder(generic121RunServer, LEGACY_PROFILE.id);
  const generic121Apply = graphicsPresetApply(generic121Graphics);
  assert.deepEqual(graphicsPresetDistanceConstants(generic121Apply, "renderDistance"),
    ["bipush 8", "bipush 16", "bipush 32"],
    "" + LEGACY_PROFILE.id + " FAST render distance was changed by the " + CANONICAL_PROFILE.id + " overlay path");
  assert.deepEqual(graphicsPresetDistanceConstants(generic121Apply, "simulationDistance"),
    ["bipush 6", "bipush 12", "bipush 12"],
    "" + LEGACY_PROFILE.id + " FAST simulation distance was changed by the " + CANONICAL_PROFILE.id + " overlay path");

  // The spawn entity gate adds a branch to Preparing.tick, so its rebuilt
  // stack map frames must also pass the JVM verifier on both profiles.
  const classInitVerifier = join(root, "GaiusClassInitVerifier.java");
  await writeFile(classInitVerifier, [
    "public class GaiusClassInitVerifier {",
    "  public static void main(String[] args) throws Exception {",
    "    for (String name : args) {",
    "      Class.forName(name, true, GaiusClassInitVerifier.class.getClassLoader());",
    "      System.out.println(\"CLASS_INIT_VERIFIED \" + name);",
    "    }",
    "  }",
    "}",
    "",
  ].join("\n"));
  execFileSync(javac, ["--release", "21", "-proc:none", "-d", classes, classInitVerifier], {
    encoding: "utf8", timeout: 30_000,
  });
  // Run the gate itself on the JVM with no level ticking.  A stub stands in
  // for the TeaVM-only BrowserIntegratedServerMain; isWorkerServer() is its
  // static `worker` field.
  const spawnGateClasses = join(root, "spawn-gate-classes");
  const spawnGateStub = join(root, "spawn-gate-stub", "dev", "gaius", "browser",
    "BrowserIntegratedServerMain.java");
  await mkdir(join(root, "spawn-gate-stub", "dev", "gaius", "browser"), {recursive: true});
  await writeFile(spawnGateStub, [
    "package dev.gaius.browser;",
    "",
    "public final class BrowserIntegratedServerMain {",
    "  public static boolean worker;",
    "  public static String loaded = \"\";",
    "  public static String waiting = \"\";",
    "  public static boolean isWorkerServer() { return worker; }",
    "  public static void markSpawnEntitiesLoaded(int x, int z) {",
    "    loaded += x + \",\" + z + \";\";",
    "  }",
    "  public static void markSpawnEntitiesWaiting(int x, int z) {",
    "    waiting += x + \",\" + z + \";\";",
    "  }",
    "}",
    "",
  ].join("\n"));
  execFileSync(javac, ["--release", "21", "-proc:none", "-d", spawnGateClasses, spawnGateStub,
    join(repositoryRoot, "port/scripts/fixtures/GaiusSpawnEntityGateFixture.java")], {
    encoding: "utf8", timeout: 30_000,
  });
  for (const [profileId, patchedJar, rawJar, chunkKeyCall] of [
    [CANONICAL_PROFILE.id, clientJar, rawClientJar, "ChunkPos.pack:()J"],
    [LEGACY_PROFILE.id, generic121Jar, raw121ClientJar, "ChunkPos.toLong:()J"],
  ]) {
    const preparing = execFileSync(javap, ["-classpath", patchedJar, "-p", "-c",
      "net.minecraft.server.network.config.PrepareSpawnTask$Preparing"], {
      encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
    });
    const ready = execFileSync(javap, ["-classpath", patchedJar, "-p", "-c",
      "net.minecraft.server.network.config.PrepareSpawnTask$Ready"], {
      encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
    });
    const serverLevel = execFileSync(javap, ["-classpath", patchedJar, "-p", "-c",
      "net.minecraft.server.level.ServerLevel"], {
      encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
    });
    assertPrepareSpawnEntityGateContract(preparing, ready, serverLevel, profileId, chunkKeyCall);
    const prepareSpawnTask = execFileSync(javap, ["-classpath", patchedJar, "-p", "-c",
      "net.minecraft.server.network.config.PrepareSpawnTask"], {
      encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
    });
    assertSavedSpawnPositionContract(prepareSpawnTask, preparing, profileId);
    const serverChunkCache = execFileSync(javap, ["-classpath", patchedJar, "-p", "-c",
      "net.minecraft.server.level.ServerChunkCache"], {
      encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
    });
    assertOffThreadGetChunkWaitContract(serverChunkCache, profileId);
    const verified = execFileSync(java, ["-Xverify:all", "-classpath",
      [classes, patchedJar].join(delimiter), "GaiusClassInitVerifier",
      "net.minecraft.server.network.config.PrepareSpawnTask$Preparing"], {
      encoding: "utf8", timeout: 30_000,
    });
    assert.match(verified,
      /CLASS_INIT_VERIFIED net\.minecraft\.server\.network\.config\.PrepareSpawnTask\$Preparing/,
      `${profileId} patched PrepareSpawnTask$Preparing failed JVM verification`);

    // Only these patched classes go in front of the vanilla jar: the rest of
    // the overlay calls Gaius browser classes that are absent on the JVM.
    const gateOverlay = join(root, `spawn-gate-overlay-${profileId}`);
    await mkdir(gateOverlay, {recursive: true});
    execFileSync(jar, ["--extract", "--file", patchedJar,
      "net/minecraft/server/network/config/PrepareSpawnTask$Preparing.class",
      "net/minecraft/server/level/PlayerSpawnFinder.class",
      "net/minecraft/server/level/ServerLevel.class"], {
      cwd: gateOverlay, encoding: "utf8", timeout: 30_000,
    });
    const librariesRoot = join(repositoryRoot, "port/work", profileId, "libraries");
    const libraries = (await readdir(librariesRoot, {recursive: true}))
      .filter(entry => entry.endsWith(".jar"))
      .sort()
      .map(entry => join(librariesRoot, entry));
    assert.ok(libraries.length > 0, `${profileId} spawn gate fixture needs ${librariesRoot}`);
    // An argument file keeps the long library classpath clear of the Windows
    // command-line limit.
    const gateArguments = join(root, `spawn-gate-${profileId}.args`);
    const gateClasspath = [spawnGateClasses, gateOverlay, rawJar, ...libraries]
      .join(delimiter).replaceAll("\\", "/");
    await writeFile(gateArguments, `-classpath\n"${gateClasspath}"\n`);
    // Output is kept for the failure message only: vanilla libraries print JDK
    // deprecation warnings on every run, and Bootstrap routes the fixture's
    // own stack traces through its stdout logger.
    let gate;
    try {
      gate = execFileSync(java, ["-Xverify:all", `@${gateArguments}`,
        "GaiusSpawnEntityGateFixture"], {
        encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 120_000,
        stdio: ["ignore", "pipe", "pipe"],
      });
    } catch (error) {
      throw new Error(`${profileId} spawn entity gate fixture failed:\n`
        + `${error.stdout ?? ""}${error.stderr ?? ""}`, {cause: error});
    }
    for (const marker of [
      "SPAWN_ENTITY_GATE_NON_WORKER_PASSES",
      "SPAWN_ENTITY_GATE_WAITS_WITH_TICKET",
      "SPAWN_ENTITY_GATE_DRAINS_PENDING_LOADS",
      "SPAWN_FIXUP_ONLY_FOR_SEARCHED_SPAWN",
    ]) {
      assert.ok(gate.includes(marker), `${profileId} spawn entity gate fixture missed ${marker}`);
    }
  }

  const patchedPacketUtils = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.network.protocol.PacketUtils"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const patched121PacketUtils = execFileSync(javap, [
    "-classpath", generic121Jar, "-p", "-c", "net.minecraft.network.protocol.PacketUtils",
  ], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  assertClientPlayPacketQueueContract(patchedPacketUtils, CANONICAL_PROFILE.id);
  assertClientPlayPacketQueueContract(patched121PacketUtils, LEGACY_PROFILE.id);
  const patchedCommonListener = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl",
  ], {encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000});
  const patched121CommonListener = execFileSync(javap, [
    "-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl",
  ], {encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000});
  assertKeepAliveImmediateSendContract(patchedCommonListener, CANONICAL_PROFILE.id);
  assertKeepAliveImmediateSendContract(patched121CommonListener, LEGACY_PROFILE.id);
  const patchedPacketProcessor = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c", "net.minecraft.network.PacketProcessor",
  ], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000});
  const patched121PacketProcessor = execFileSync(javap, [
    "-classpath", generic121Jar, "-p", "-c", "net.minecraft.network.PacketProcessor",
  ], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000});
  const patchedBlockableEventLoop = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c", "net.minecraft.util.thread.BlockableEventLoop",
  ], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000});
  const patched121BlockableEventLoop = execFileSync(javap, [
    "-classpath", generic121Jar, "-p", "-c", "net.minecraft.util.thread.BlockableEventLoop",
  ], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000});
  for (const [profileId, blockableEventLoop] of [
    [CANONICAL_PROFILE.id, patchedBlockableEventLoop],
    [LEGACY_PROFILE.id, patched121BlockableEventLoop],
  ]) {
    const doRunTask = method(blockableEventLoop,
      "protected void doRunTask(R);",
      "public java.util.List<net.minecraft.util.profiling.metrics.MetricSampler> profiledMetrics();");
    assert.equal(occurrences(doRunTask, "java/lang/Runnable.run"), 1,
      `${profileId} doRunTask must retain one vanilla Runnable.run dispatch`);
    assert.match(doRunTask,
      /BrowserIntegratedServerMain\.beginScheduledNetworkInputTask/,
      `${profileId} doRunTask must register scheduled network input`);
    assert.match(doRunTask,
      /BrowserIntegratedServerMain\.endScheduledNetworkInputTask/,
      `${profileId} doRunTask must close scheduled network input`);
  }
  assertPacketProcessorQueueContract(patchedPacketProcessor, CANONICAL_PROFILE.id);
  assertPacketProcessorQueueContract(patched121PacketProcessor, LEGACY_PROFILE.id);
  assertPacketProcessorLifecycleContract(patchedPacketProcessor, CANONICAL_PROFILE.id);
  assertPacketProcessorLifecycleContract(patched121PacketProcessor, LEGACY_PROFILE.id);

  const rawMob = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.world.entity.Mob"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patchedMob = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.entity.Mob"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patched121Mob = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.world.entity.Mob"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const rawMobAi = method(rawMob,
    "protected final void serverAiStep();", "protected void customServerAiStep");
  const patchedMobAi = method(patchedMob,
    "protected final void serverAiStep();", "protected void customServerAiStep");
  const patched121MobAi = method(patched121Mob,
    "protected final void serverAiStep();", "protected void customServerAiStep");
  const rawMobAiStep = method(rawMob,
    "public void aiStep();", "protected final void serverAiStep");
  const patchedMobAiStep = method(patchedMob,
    "public void aiStep();", "protected final void serverAiStep");
  const patched121MobAiStep = method(patched121Mob,
    "public void aiStep();", "protected final void serverAiStep");
  const rawMobTick = method(rawMob,
    "public void tick();", "protected void updateControlFlags");
  const patchedMobTick = method(patchedMob,
    "public void tick();", "protected void updateControlFlags");
  const patched121MobTick = method(patched121Mob,
    "public void tick();", "protected void updateControlFlags");
  const rawLivingEntity = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.world.entity.LivingEntity"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patchedLivingEntity = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.entity.LivingEntity"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patched121LivingEntity = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.world.entity.LivingEntity"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const rawLivingTick = method(rawLivingEntity,
    "public void tick();", "protected void updateFallFlying");
  const patchedLivingTick = method(patchedLivingEntity,
    "public void tick();", "protected void updateFallFlying");
  const patched121LivingTick = method(patched121LivingEntity,
    "public void tick();", "protected void updateFallFlying");
  assert.doesNotMatch(rawMobAi, /BrowserWorldgenScheduler\.mobAiPulse/,
    "raw Mob.serverAiStep unexpectedly contains browser scheduler hooks");
  assert.doesNotMatch(rawMobAiStep, /BrowserWorldgenScheduler\.mobAiPulse/,
    "raw Mob.aiStep unexpectedly contains browser scheduler hooks");
  assert.doesNotMatch(rawMobTick, /BrowserWorldgenScheduler\.mobAiPulse/,
    "raw Mob.tick unexpectedly contains browser scheduler hooks");
  assert.doesNotMatch(rawLivingTick, /BrowserWorldgenScheduler\.mobEntityPulse/,
    "raw LivingEntity.tick unexpectedly contains browser scheduler hooks");
  assert.equal(occurrences(patchedMobAi, "BrowserWorldgenScheduler.mobAiPulse"), 7,
    "" + CANONICAL_PROFILE.id + " Mob.serverAiStep must checkpoint all seven vanilla AI stage boundaries");
  assert.equal(occurrences(patched121MobAi, "BrowserWorldgenScheduler.mobAiPulse"), 7,
    "" + LEGACY_PROFILE.id + " Mob.serverAiStep must checkpoint all seven vanilla AI stage boundaries");
  assert.equal(occurrences(patchedMobAiStep, "BrowserWorldgenScheduler.mobAiPulse"), 2,
    "" + CANONICAL_PROFILE.id + " Mob.aiStep must retain a cooperative boundary around the vanilla super call");
  assert.equal(occurrences(patched121MobAiStep, "BrowserWorldgenScheduler.mobAiPulse"), 2,
    "" + LEGACY_PROFILE.id + " Mob.aiStep must retain a cooperative boundary around the vanilla super call");
  assert.equal(occurrences(patchedMobTick, "BrowserWorldgenScheduler.mobAiPulse"), 0,
    "" + CANONICAL_PROFILE.id + " Mob.tick must remain unmodified after moving the fallback to LivingEntity");
  assert.equal(occurrences(patched121MobTick, "BrowserWorldgenScheduler.mobAiPulse"), 0,
    "" + LEGACY_PROFILE.id + " Mob.tick must remain unmodified after moving the fallback to LivingEntity");
  assert.equal(occurrences(patchedLivingTick, "BrowserWorldgenScheduler.mobEntityPulse"), 1,
    "" + CANONICAL_PROFILE.id + " LivingEntity.tick must retain the profile-generic Mob fallback boundary");
  assert.equal(occurrences(patched121LivingTick, "BrowserWorldgenScheduler.mobEntityPulse"), 1,
    "" + LEGACY_PROFILE.id + " LivingEntity.tick must retain the profile-generic Mob fallback boundary");
  assert.equal(occurrences(patchedMobAi, "BrowserWorldgenScheduler.mobAiPulse"),
    occurrences(patched121MobAi, "BrowserWorldgenScheduler.mobAiPulse"),
    "Mob AI cooperation must remain profile-generic");

  const deepWorldgenBytecode = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c", ...synchronousDeepWorldgenClasses,
  ], {
    encoding: "utf8", maxBuffer: 32 * 1024 * 1024, timeout: 30_000,
  });
  assert.doesNotMatch(deepWorldgenBytecode, /BrowserWorldgenScheduler/,
    "deep worldgen bytecode must contain zero scheduler calls");
  const noiseChunkBytecode = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c", "net.minecraft.world.level.levelgen.NoiseChunk",
  ], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000});
  assert.equal(occurrences(noiseChunkBytecode, "BrowserNoiseGraphMapper.mapRouter:"), 1,
    "NoiseChunk router must use one per-invocation graph mapper");
  assert.equal(occurrences(noiseChunkBytecode, "BrowserNoiseGraphMapper.map:"), 7,
    "NoiseChunk density and climate wrapping must deduplicate before recursion");
  assert.doesNotMatch(noiseChunkBytecode, /(?:DensityFunction|NoiseRouter)\.mapAll:/,
    "NoiseChunk wrapping still uses repeated recursive traversal");

  const bytecode = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.server.level.ChunkGenerationTask",
    "net.minecraft.server.MinecraftServer",
    "net.minecraft.server.level.DistanceManager",
    "net.minecraft.server.level.LoadingChunkTracker",
    "net.minecraft.server.level.ServerChunkCache",
    "com.mojang.blaze3d.vertex.UberGpuBuffer",
    "net.minecraft.client.renderer.GameRenderer"], {
    encoding: "utf8", maxBuffer: 32 * 1024 * 1024, timeout: 30_000,
  });
  const generationWait = method(bytecode,
    "private java.util.concurrent.CompletableFuture<?> waitForScheduledLayer();",
    "private static net.minecraft.server.level.GenerationChunkHolder lambda$create$0");
  const generationWaitInstructions = bytecodeInstructions(generationWait);
  const generationWaitBackedges = generationWaitInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset ? [{entry, index}] : [];
  });
  assert.equal(generationWaitBackedges.length, 1,
    "ChunkGenerationTask.waitForScheduledLayer loop topology changed");
  for (const {entry, index} of generationWaitBackedges) {
    const previous = generationWaitInstructions[index - 1]?.instruction ?? "";
    const beforePrevious = generationWaitInstructions[index - 2]?.instruction ?? "";
    assert.match(previous, /BrowserWorldgenScheduler\.pulse/,
      `ChunkGenerationTask.waitForScheduledLayer backedge ${entry.offset} is missing its cooperative pulse`);
    assert.doesNotMatch(beforePrevious, /BrowserWorldgenScheduler\.pulse/,
      `ChunkGenerationTask.waitForScheduledLayer backedge ${entry.offset} received a duplicate cooperative pulse`);
  }
  assert.equal(occurrences(generationWait, "BrowserWorldgenScheduler.pulse"), 1,
    "waitForScheduledLayer must pulse once per completed-future drain backedge");
  assert.doesNotMatch(generationWait, /BrowserWorldgenScheduler\.checkpoint/,
    "waitForScheduledLayer must use bounded pulses rather than unconditional checkpoints");
  const runUntilWait = method(bytecode,
    "public java.util.concurrent.CompletableFuture<?> runUntilWait();",
    "private void scheduleNextLayer");
  const scheduleLayer = method(bytecode,
    "private void scheduleLayer(net.minecraft.world.level.chunk.status.ChunkStatus, boolean);",
    "private int getRadiusForLayer");
  const runUntilWaitInstructions = bytecodeInstructions(runUntilWait);
  // Vanilla returns null after releaseClaim(); the return hook must not dereference it
  // (null.isDone() traps the integrated server thread on the Wasm GC target).
  runUntilWaitInstructions.forEach((entry, index) => {
    if (!/^areturn\b/.test(entry.instruction)) return;
    const hook = runUntilWaitInstructions.slice(Math.max(0, index - 6), index)
      .map((item) => item.instruction).join(" | ");
    assert.match(hook, /BrowserWorldgenScheduler\.recordGenerationResult/,
      `runUntilWait return ${entry.offset} lost its null-safe generation result hook`);
    assert.doesNotMatch(hook, /CompletableFuture\.isDone/,
      `runUntilWait return ${entry.offset} dereferences a possibly null result`);
  });
  const runUntilWaitBackedges = runUntilWaitInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(runUntilWaitBackedges.length, 1,
    "ChunkGenerationTask.runUntilWait must retain one loop backedge");
  assert.match(runUntilWait, /Field browserLayerYield/,
    "runUntilWait must return its pending batch future to the server dispatcher");
  assert.match(runUntilWait, /CompletableFuture.isDone/,
    "runUntilWait must wait for the batch macrotask before resuming");
  const activeFieldIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Field browserLayerActive"));
  const firstScheduleNextIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Method scheduleNextLayer:()V"));
  const firstLayerWaitIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Method waitForScheduledLayer:"));
  const activePendingReturn = runUntilWaitInstructions.findIndex((entry, index) =>
    index > firstScheduleNextIndex && index < firstLayerWaitIndex &&
      entry.instruction === "areturn");
  assert.ok(activePendingReturn >= 0 && activeFieldIndex >= 0
      && activeFieldIndex < firstScheduleNextIndex
      && firstScheduleNextIndex < activePendingReturn,
  "active cursor must return its pending future before waiting on layer dependencies");
  assert.match(runUntilWaitInstructions[runUntilWaitBackedges[0].index - 1].instruction,
    /BrowserWorldgenScheduler\.pulse/,
    "original runUntilWait backedge lost its scheduler pulse");
  assert.equal(occurrences(runUntilWait, "Method scheduleNextLayer:()V"), 2,
    "runUntilWait must retain active-resume and vanilla fresh-layer scheduling paths");
  assert.equal(occurrences(runUntilWait, "BrowserWorldgenScheduler.beginServerWorkTurn"), 0,
    "runUntilWait must not reset the shared tick budget per task invocation");
  assert.equal(occurrences(runUntilWait, "BrowserWorldgenScheduler.beginTaskWork"), 1,
    "runUntilWait must enter one active-work task scope");
  assert.match(runUntilWait,
    /String ChunkGenerationTask\.runUntilWait[\s\S]*BrowserWorldgenScheduler\.beginTaskWork:\(Ljava\/lang\/String;\)I/,
    "runUntilWait must label and store the integer task-scope token");
  assert.match(runUntilWait, /BrowserWorldgenScheduler\.endTaskWork:\(I\)V/,
    "runUntilWait must close task scopes with the integer token");
  assert.ok(occurrences(runUntilWait, "BrowserWorldgenScheduler.endTaskWork") >= 3,
    "runUntilWait must close its scope on returns and exceptions");
  assert.match(runUntilWait, /Exception table:[\s\S]*Throwable/,
    "runUntilWait task scope has no catch-all exception cleanup");
  assert.match(scheduleLayer, /Exception table:[\s\S]*Throwable/,
    "scheduleLayer must clean cursor state when holder work throws");
  const scheduleLayerExceptionCleanup = scheduleLayer.slice(scheduleLayer.lastIndexOf("astore"));
  assert.match(scheduleLayerExceptionCleanup, /Field browserLayerActive/,
    "scheduleLayer exception path must clear active cursor state");
  assert.match(scheduleLayerExceptionCleanup, /athrow/,
    "scheduleLayer must propagate holder or scheduling failures");
  const scheduleLayerInstructions = bytecodeInstructions(scheduleLayer);
  const batchBackedges = scheduleLayerInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^if_icmplt\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(batchBackedges.length, 1,
    "scheduleLayer must retain one bounded holder-batch backedge");
  assert.match(scheduleLayerInstructions[batchBackedges[0].index - 1]?.instruction ?? "",
    /(?:bipush\s+16|ldc(?:_w)?\s+.*\/\/ int 16)/,
    "scheduleLayer batch backedge must enforce the 16-holder upper bound");
  assert.match(scheduleLayerInstructions[batchBackedges[0].index + 1]?.instruction ?? "",
    /^new\s+.*CompletableFuture/,
    "scheduleLayer full batch must create a future and return to its dispatcher");
  assert.equal(occurrences(scheduleLayer, "Platform.startThread"), 1,
    "scheduleLayer must bootstrap exactly one asynchronous TeaVM thread per batch");
  const runServer = method(bytecode, "protected void runServer", "private void");
  const tickStart = runServer.indexOf("BrowserWorldgenScheduler.beginServerWorkTurn");
  const tickTelemetryStart = runServer.indexOf(
    "BrowserWorldgenScheduler.beginServerTickTelemetry",
  );
  const processTick = runServer.indexOf("processPacketsAndTick");
  const tickTelemetryEnd = runServer.indexOf(
    "BrowserWorldgenScheduler.endServerTickTelemetry",
  );
  const tickCheckpoint = runServer.indexOf("BrowserWorldgenScheduler.checkpoint");
  const waitTelemetryStart = runServer.indexOf(
    "BrowserWorldgenScheduler.beginServerWaitTelemetry",
  );
  const waitCall = runServer.indexOf("Method waitUntilNextTick:()V");
  const waitTelemetryEnd = runServer.indexOf(
    "BrowserWorldgenScheduler.endServerWaitTelemetry",
  );
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.beginServerWorkTurn"), 1,
    "runServer must reset the scheduler clock exactly once per tick");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.checkpoint"), 1,
    "runServer must checkpoint exactly once per tick");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.beginServerTickTelemetry"), 1,
    "runServer must begin one opt-in server tick measurement per tick");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.endServerTickTelemetry"), 1,
    "runServer must end one opt-in server tick measurement per tick");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.beginServerWaitTelemetry"), 1,
    "runServer must begin one opt-in wait-phase measurement per tick");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.endServerWaitTelemetry"), 1,
    "runServer must end one opt-in wait-phase measurement per tick");
  assert.ok(tickStart >= 0 && processTick > tickStart && tickCheckpoint > processTick,
    "server tick must reset before processPacketsAndTick and checkpoint after it");
  assert.ok(tickTelemetryStart >= 0 && tickTelemetryStart < processTick
      && tickTelemetryEnd > processTick,
    "server tick telemetry must bracket processPacketsAndTick");
  assert.ok(waitTelemetryStart >= 0 && waitCall > waitTelemetryStart
      && waitTelemetryEnd > waitCall,
    "wait telemetry must bracket the actual waitUntilNextTick call");
  assert.ok(tickCheckpoint < tickTelemetryEnd && tickTelemetryEnd < waitTelemetryStart,
    "wait telemetry must start after the tick checkpoint/telemetry end boundary");
  assertRunServerWaitTelemetryOrder(runServer, CANONICAL_PROFILE.id);
  const pollTask = method(bytecode, "protected boolean pollTask();", "private boolean pollTaskInternal");
  assert.equal(occurrences(pollTask, "BrowserWorldgenScheduler.beginTaskWork"), 1,
    "MinecraftServer.pollTask must enter one active-work task scope");
  assert.match(pollTask,
    /String MinecraftServer\.pollTask[\s\S]*BrowserWorldgenScheduler\.beginTaskWork:\(Ljava\/lang\/String;\)I/,
    "MinecraftServer.pollTask must label and store the integer task-scope token");
  assert.match(pollTask, /BrowserWorldgenScheduler\.endTaskWork:\(I\)V/,
    "MinecraftServer.pollTask must close task scopes with the integer token");
  const pollBegin = pollTask.indexOf(
    "BrowserWorldgenScheduler.beginTaskWork:(Ljava/lang/String;)I",
  );
  const pollPump = pollTask.indexOf("BrowserIntegratedServerMain.pumpUrgentPacketsIfPending");
  assert.ok(pollBegin >= 0 && pollPump > pollBegin,
    "MinecraftServer.pollTask must pump urgent packets after token initialization");
  assert.ok(occurrences(pollTask, "BrowserWorldgenScheduler.endTaskWork") >= 2,
    "MinecraftServer.pollTask must close its scope on returns and exceptions");
  const distance = method(bytecode, "public boolean runAllUpdates", "public void addPlayer");
  assert.equal(occurrences(distance, "int 2147483647"), 2,
    "DistanceManager no longer preserves vanilla full-propagation semantics");
  assert.equal(occurrences(distance, "distanceManagerUpdateBudget"), 0,
    "DistanceManager still returns between partial ticket-propagation batches");
  assert.equal(occurrences(distance, "pulseDistanceManager"), 3,
    "DistanceManager futures/ticket loops are not each cooperatively bounded");
  const distanceInstructions = bytecodeInstructions(distance);
  const backwardGotos = distanceInstructions.flatMap((entry, index) => {
    // ASM may widen a branch after the browser patch set grows the method;
    // javap prints that form as `goto_w` and keeps alignment spaces.
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset ? [{entry, index}] : [];
  });
  assert.equal(backwardGotos.length, 3,
    "DistanceManager loop topology changed; update the one-pulse-per-backedge contract");
  for (const {entry, index} of backwardGotos) {
    const previous = distanceInstructions[index - 1]?.instruction ?? "";
    const beforePrevious = distanceInstructions[index - 2]?.instruction ?? "";
    assert.match(previous, /BrowserWorldgenScheduler\.pulseDistanceManager/,
      `DistanceManager backedge ${entry.offset} is missing its cooperative pulse`);
    assert.doesNotMatch(beforePrevious, /BrowserWorldgenScheduler\.pulseDistanceManager/,
      `DistanceManager backedge ${entry.offset} received a duplicate cooperative pulse`);
  }
  assert.equal(occurrences(distance, "InterfaceMethod java/util/Set.clear"), 1,
    "DistanceManager can clear newly queued future work after its snapshot");
  assert.equal(occurrences(distance, "InterfaceMethod it/unimi/dsi/fastutil/longs/LongSet.clear"), 1,
    "DistanceManager can clear newly queued ticket work after its snapshot");
  assert.ok(distance.indexOf("ArrayList.\"<init>\"") <
      distance.indexOf("InterfaceMethod java/util/Set.clear") &&
      distance.indexOf("InterfaceMethod java/util/Set.clear") <
        distance.indexOf("InterfaceMethod java/util/List.iterator"),
  "DistanceManager futures retain a live set until after the cooperative iterator starts");
  assert.ok(distance.indexOf("LongSet.toLongArray") <
      distance.indexOf("LongSet.clear"),
  "DistanceManager tickets are cleared before their snapshot is retained");
  assert.ok(distance.indexOf("LongSet.clear") < distance.indexOf("laload"),
    "DistanceManager can hold a live ticket iterator across a cooperative pulse");

  const loading = method(bytecode, "public int runDistanceUpdates", "static {}");
  assert.equal(occurrences(loading, "distanceManagerUpdateBudget"), 1,
    "LoadingChunkTracker does not bound its internal propagation batch");
  assert.equal(occurrences(loading, "recordDistanceManagerUpdates"), 1,
    "LoadingChunkTracker propagation telemetry is missing");
  assert.equal(occurrences(loading, "pulseDistanceManager"), 1,
    "LoadingChunkTracker cannot yield between internal propagation batches");
  assert.ok(loading.indexOf("Math.min") < loading.indexOf("runUpdates") &&
      loading.indexOf("runUpdates") < loading.indexOf("recordDistanceManagerUpdates") &&
      loading.indexOf("recordDistanceManagerUpdates") <
        loading.indexOf("pulseDistanceManager"),
  "LoadingChunkTracker lost batch-run-record-yield ordering");

  const broadcast = method(bytecode, "private void broadcastChangedChunks", "private void tickChunks");
  assert.ok(broadcast.includes("Set.toArray") && broadcast.includes("Set.clear"),
    "changed-chunk broadcast is not snapshot-backed");
  assert.equal(occurrences(broadcast, "Set.iterator"), 0,
    "changed-chunk broadcast can retain a live iterator across a pulse");
  assert.ok(broadcast.includes(
    "ChunkHolder.broadcastChanges:(Lnet/minecraft/world/level/chunk/LevelChunk;)V"),
  "changed-chunk broadcast uses an invalid LevelChunk method descriptor");
  assert.ok(broadcast.indexOf("Set.toArray") < broadcast.indexOf("Set.clear") &&
      broadcast.indexOf("Set.clear") < broadcast.indexOf("beginChunkBroadcast") &&
      broadcast.indexOf("beginChunkBroadcast") < broadcast.indexOf("pulseChunkBroadcast") &&
      broadcast.indexOf("pulseChunkBroadcast") < broadcast.indexOf("finishChunkBroadcast"),
  "changed-chunk broadcast no longer has snapshot-to-completion ordering");

  const upload = method(bytecode, "public boolean uploadStagedAllocations",
    "private static <T, U extends T> void runCallbackUnchecked");
  const cleanup = upload.slice(upload.indexOf("finishUploadBuffer"));
  assert.ok(cleanup.includes("beginUberNodeCleanup") && cleanup.includes("shouldCleanUberNode") &&
      cleanup.includes("finishUberNodeCleanup"),
  "UberGpuBuffer cleanup is not cursor-budgeted");
  assert.ok(cleanup.includes("List.remove:(I)Ljava/lang/Object;") &&
      !cleanup.includes("List.iterator"),
  "UberGpuBuffer cleanup still retains an unbounded iterator");
  assert.ok(cleanup.indexOf("beginUberNodeCleanup") < cleanup.indexOf("shouldCleanUberNode") &&
      cleanup.indexOf("shouldCleanUberNode") < cleanup.indexOf("List.remove:(I)") &&
      cleanup.indexOf("List.remove:(I)") < cleanup.indexOf("finishUberNodeCleanup"),
  "UberGpuBuffer cleanup cursor lifecycle order changed");

  const gameRenderer = method(bytecode,
    "public void extract(net.minecraft.client.DeltaTracker, boolean);",
    "private void extractCamera(net.minecraft.client.DeltaTracker, float, float);");
  const cameraExtract = gameRenderer.indexOf("Method extractCamera:");
  const targetingRefresh = gameRenderer.indexOf("BrowserTargeting.refreshFramePick");
  const levelExtract = gameRenderer.indexOf("LevelExtractor.extract");
  assert.ok(cameraExtract >= 0 && targetingRefresh > cameraExtract &&
      levelExtract > targetingRefresh,
  "frame targeting is not refreshed between camera and level extraction");
  const targetingInstructions = bytecodeInstructions(gameRenderer);
  const refreshInstructionIndex = targetingInstructions.findIndex(entry =>
    entry.instruction.includes("BrowserTargeting.refreshFramePick"));
  assert.ok(refreshInstructionIndex >= 0,
    "frame targeting refresh instruction is missing");
  assert.match(targetingInstructions[refreshInstructionIndex - 1]?.instruction ?? "",
    /^fload\s+6$/,
    "frame targeting does not use Camera.getCameraEntityPartialTicks");
  assert.doesNotMatch(targetingInstructions[refreshInstructionIndex - 1]?.instruction ?? "",
    /^fload\s+5$/,
    "frame targeting still uses the world partial tick and can drift");

  console.log("Minecraft " + CANONICAL_PROFILE.id + " P1 patcher smoke passed", JSON.stringify({
    scheduledLayerPulses: occurrences(generationWait, "BrowserWorldgenScheduler.pulse"),
    distancePulses: occurrences(distance, "pulseDistanceManager"),
    graphicsPresetDistances: {
      fast: presetDistances(patchedGraphicsApply, "FAST"),
      fancy: presetDistances(patchedGraphicsApply, "FANCY"),
      fabulous: presetDistances(patchedGraphicsApply, "FABULOUS"),
    },
    oneTwentyOneFastDistances: "8/6",
    holderBatchLimit: HOLDERS_PER_TURN,
    layerBarrier: true,
    broadcastSnapshot: true,
    uberCleanupCursor: true,
    targetingPartialTickLocal: 6,
  }));
} finally {
  await rm(root, {recursive: true, force: true});
}
