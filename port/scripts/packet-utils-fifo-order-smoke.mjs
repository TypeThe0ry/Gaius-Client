#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import {join} from "node:path";
import {memberText, patcherSource} from "./patcher-sources.mjs";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const source = patcherSource("MinecraftClientPatcher");
const method = memberText(source, "patchClientPacketUtilsBrowserInline");
assert.ok(method, "PacketUtils patch method is missing");
// A polled packet must be handled before the next queue item. Re-adding it at
// the tail under worldgen pressure reorders movement and chunk acknowledgments.
assert.ok(!source.includes('"shouldDeferQueuedPacket"'),
  "PacketProcessor must not requeue a polled movement packet behind later packets");

const commonPackets = Object.freeze([
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
]);

function dispatchModel({
  listener,
  packet,
  queuedPackets,
  processingQueuedPacket = false,
  workerServer = false,
}) {
  const common = commonPackets.includes(packet);
  if (workerServer) {
    if (processingQueuedPacket) return {path: "queued-return", order: []};
    return {path: "schedule", order: [...queuedPackets, packet]};
  }
  if (listener === "configuration") return {path: "inline", order: [packet]};
  if (listener === "play") {
    if (processingQueuedPacket) return {path: "queued-return", order: []};
    if (common && queuedPackets.length === 0) return {path: "inline", order: [packet]};
    if (queuedPackets.length !== 0 || !common) {
      return {path: "schedule", order: [...queuedPackets, packet]};
    }
  }
  if (common) return {path: "inline", order: [packet]};
  return {path: "vanilla", order: [packet]};
}

function oldUnsafeModel({listener, packet, queuedPackets, processingQueuedPacket = false}) {
  const common = commonPackets.includes(packet);
  if (listener === "configuration" || common) return {path: "inline", order: [packet]};
  if (listener === "play" && processingQueuedPacket) return {path: "queued-return", order: []};
  if (listener === "play" && queuedPackets.length !== 0) {
    return {path: "schedule", order: [...queuedPackets, packet]};
  }
  return listener === "play"
    ? {path: "schedule", order: [...queuedPackets, packet]}
    : {path: "vanilla", order: [packet]};
}

const queuedPlay = ["PLAY-A"];
const unsafeCommon = oldUnsafeModel({
  listener: "play", packet: "ClientboundCustomPayloadPacket", queuedPackets: queuedPlay,
});
assert.equal(unsafeCommon.path, "inline", "regression model no longer captures old inline bypass");
assert.deepEqual(unsafeCommon.order, ["ClientboundCustomPayloadPacket"],
  "old model must demonstrate common packet overtaking queued PLAY");

const fixedCommon = dispatchModel({
  listener: "play", packet: "ClientboundCustomPayloadPacket", queuedPackets: queuedPlay,
});
assert.equal(fixedCommon.path, "schedule", "queued PLAY must force common packet through FIFO");
assert.deepEqual(fixedCommon.order, ["PLAY-A", "ClientboundCustomPayloadPacket"],
  "common packet must not overtake queued PLAY");

const emptyCommon = dispatchModel({
  listener: "play", packet: "ClientboundPingPacket", queuedPackets: [],
});
assert.equal(emptyCommon.path, "inline", "empty PLAY queue should keep common fast path");
assert.deepEqual(emptyCommon.order, ["ClientboundPingPacket"]);

const reentrantCommon = dispatchModel({
  listener: "play", packet: "ClientboundPingPacket", queuedPackets: ["PLAY-A"],
  processingQueuedPacket: true,
});
assert.equal(reentrantCommon.path, "queued-return", "queued handler re-entry must not recurse");
assert.deepEqual(reentrantCommon.order, [], "queued handler re-entry must not duplicate a packet");

const transition = dispatchModel({
  listener: "play", packet: "ClientboundLoginPacket", queuedPackets: ["PLAY-A"],
});
assert.equal(transition.path, "schedule", "protocol transition must preserve queued PLAY FIFO");
assert.deepEqual(transition.order, ["PLAY-A", "ClientboundLoginPacket"]);

const configuration = dispatchModel({
  listener: "configuration", packet: "ClientboundCustomPayloadPacket", queuedPackets: ["PLAY-A"],
});
assert.equal(configuration.path, "inline", "configuration listener bypass must remain unchanged");

const workerServerPacket = dispatchModel({
  listener: "server-play",
  packet: "ServerboundMovePlayerPacket",
  queuedPackets: ["SERVER-A"],
  workerServer: true,
});
assert.equal(workerServerPacket.path, "schedule",
  "Worker server packets must not execute inside the raw decoder");
assert.deepEqual(workerServerPacket.order, ["SERVER-A", "ServerboundMovePlayerPacket"],
  "Worker server force-queue must retain exact FIFO order");

const workerServerReentry = dispatchModel({
  listener: "server-play",
  packet: "ServerboundMovePlayerPacket",
  queuedPackets: ["SERVER-B"],
  processingQueuedPacket: true,
  workerServer: true,
});
assert.equal(workerServerReentry.path, "queued-return",
  "Worker server queued-handler re-entry must return for the same owner");
assert.deepEqual(workerServerReentry.order, [],
  "Worker server queued-handler re-entry must not append a duplicate packet");

const nonWorkerServerPacket = dispatchModel({
  listener: "server-play",
  packet: "ServerboundMovePlayerPacket",
  queuedPackets: [],
});
assert.equal(nonWorkerServerPacket.path, "vanilla",
  "non-Worker server packets must retain vanilla thread dispatch");

// Source-level guard: every common classifier must have a guarded PLAY target
// (commonBacklogCheck) as well as the non-PLAY inline target.  The generated
// bytecode smoke verifies the actual CFG and target offsets for both profiles.
assert.match(method, /LabelNode playListener = new LabelNode\(\);/);
assert.match(method, /LabelNode clientDispatch = new LabelNode\(\);/);
assert.match(method, /LabelNode workerServerQueueGuard = new LabelNode\(\);/);
assert.match(method,
  /BrowserIntegratedServerMain",\s*"isWorkerServer",\s*"\(\)Z"[\s\S]*?Opcodes\.IFEQ, clientDispatch[\s\S]*?workerServerQueueGuard[\s\S]*?"isProcessingQueuedPacket"[\s\S]*?Opcodes\.IFNE, queuedHandleReturn[\s\S]*?Opcodes\.GOTO, forcedPlayQueue/,
  "Worker server dispatch must use the owner guard before the shared FIFO schedule block");
assert.match(method, /LabelNode commonBacklogCheck = new LabelNode\(\);/);
assert.match(method, /code\.add\(new JumpInsnNode\(Opcodes\.IFNE, playListener\)\);/);
assert.match(method, /code\.add\(new MethodInsnNode\([\s\S]*?"isProcessingQueuedPacket"[\s\S]*?\)\);/);
assert.match(method, /code\.add\(new JumpInsnNode\(Opcodes\.IFNE, commonBacklogCheck\)\);/);
assert.match(method, /int commonBacklogChecks = 0;/);
assert.match(method, /commonPlayPacketBranches/);
assert.match(method, /commonBacklogChecks != 1/);
assert.match(method, /workerServerChecks != 1/);
assert.match(method, /workerServerDrainGuardCalls != 1/);
assert.equal((method.match(/"hasPendingPackets"/g) || []).length, 3,
  "the patch must keep separate common and transition backlog gates plus one verifier");

const result = {
  schema: "gaius.packet-utils-fifo-order-smoke.v1",
  status: "pass",
  oldOrderBlocked: true,
  queuedCommon: fixedCommon.order,
  emptyCommon: emptyCommon.order,
  reentrantPath: reentrantCommon.path,
  transitionOrder: transition.order,
  configurationPath: configuration.path,
  workerServerOrder: workerServerPacket.order,
  workerServerReentryPath: workerServerReentry.path,
  nonWorkerServerPath: nonWorkerServerPacket.path,
  commonPacketCount: commonPackets.length,
  sourceGuard: "play-owner-before-common-inline",
};
process.stdout.write(`${JSON.stringify(result)}\n`);
