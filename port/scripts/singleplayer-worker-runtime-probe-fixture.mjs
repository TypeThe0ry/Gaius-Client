import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";

const source = fs.readFileSync(new URL("./singleplayer-worker-runtime-smoke.mjs", import.meta.url), "utf8")
  .replace(/\r\n/g, "\n");
// These functions are direct siblings in the source. Keep their actual bodies,
// including timer callbacks, rather than copying the selection algorithm.
function extract(name) {
  const match = new RegExp("^( {0,2})function " + name + "\\(", "m").exec(source);
  assert.ok(match, "missing production function " + name);
  const start = match.index;
  const rest = source.slice(start + match[0].length);
  const next = new RegExp("^" + match[1] + "function ", "m").exec(rest);
  assert.ok(next, "missing sibling after " + name);
  return source.slice(start, start + match[0].length + next.index);
}
const names = [
  "closeTransport", "maybeScheduleMining", "prepareDeterministicDropProbe",
  "probeNextBlock", "failBlockProbe", "rejectProbeDeadline", "startConfirmedBlockAction",
  "completeBlockAction", "sendPlayerAction", "encodePacket", "encodeString", "encodeBlockPos",
  "createBlockCandidates", "sameBlockPos", "chunkKeyForBlock", "encodeVarInt", "concatenateMany", "concatenate",
  "handleTransportControl",
];
const functions = names.map(extract).join("\n");
const sendStart = source.indexOf("  function send(bytes, onSent) {");
const sendEnd = source.indexOf("\n  function sendChatCommand", sendStart);
const flushStart = source.indexOf("  function flushSends() {");
const flushEnd = source.indexOf("\n\n  return state;", flushStart);
assert.ok(sendStart >= 0 && sendEnd > sendStart && flushStart >= 0 && flushEnd > flushStart);
const transportFunctions = source.slice(sendStart, sendEnd) + source.slice(flushStart, flushEnd);
function runtime() {
  let now = 0, nextId = 1, resolved = 0;
  const timers = new Map(), errors = [];
  const pendingSends = [];
  const state = {
    transportClosed: false, probeFailed: false, miningScheduled: false, miningCompleted: false,
    sendEnqueuedCount: 0, sendEnqueuedBytes: 0, sendFlushedCount: 0, sendFlushedBytes: 0,
    sendLastEnqueuedAt: undefined, sendLastFlushedAt: undefined,
    transportCloseRequestedAt: undefined, transportCloseReceivedAt: undefined,
    transportCloseReason: undefined,
    blockActionCandidateConfirmed: false, blockActionCandidates: [], blockActionProbedTargets: [],
    blockActionSequence: 0, blockActionProbeCount: 0, blockActionSentAt: new Map(),
    chunkPackets: 1, uniqueChunkPositions: new Set(), roamSteps: 0, roamCompleted: true,
    playerPosition: {x: 15.5, y: 64, z: 15.5}, targetAirUpdates: 0, blockActionAckSequences: [],
  };
  const options = {skipMining: false, requireBlockDrop: false, blockActionHoldMs: 8000};
  const port = {closed: 0, messages: [], controls: [], postMessage(value) {
    if (value instanceof ArrayBuffer || ArrayBuffer.isView(value)) this.messages.push(value);
    else this.controls.push(value);
  }, close() { this.closed++; }};
  const sent = port.messages;
  const vmContext = {
    state, options, port, pendingSends, remotePaused: false, Date: {now: () => now}, TextEncoder, TextDecoder, Uint8Array, DataView,
    ready: {resolve() { resolved++; }, reject(e) { errors.push(e); }},
    serverboundPlay: {playerAction: 29, chatCommand: 3},
    maybeResolveReady() { resolved++; },
    setTimeout(fn, delay = 0) { const id = nextId++; timers.set(id, {at: now + delay, fn}); return id; },
    clearTimeout(id) { timers.delete(id); },
  };
  vmContext.transportCloseRequested = false;
  const api = vm.runInNewContext(functions + "\n" + transportFunctions + "\n;({" + names.join(",") + ",send,flushSends})", vmContext);
  function until(target) {
    assert.ok(target >= now);
    let count = 0;
    for (;;) {
      let chosen;
      for (const [id, timer] of timers) {
        if (timer.at <= target && (!chosen || timer.at < chosen.timer.at)) chosen = {id, timer};
      }
      if (!chosen) break;
      assert.ok(++count < 10000, "unbounded timer loop");
      timers.delete(chosen.id); now = chosen.timer.at; chosen.timer.fn();
    }
    now = target;
  }
  return {state, options, api, port, pendingSends, timers, sent, errors, until,
    setPaused(value) { vmContext.remotePaused = value; },
    chunk(x, z) { state.uniqueChunkPositions.add(x + "," + z); }, resolved: () => resolved};
}

export function runProbeFixture() {
  {
    const r = runtime();
    r.setPaused(true);
    r.api.send(new Uint8Array([1, 2, 3]));
    assert.equal(r.pendingSends.length, 1);
    assert.equal(r.port.messages.length, 0);
    assert.equal(r.state.sendEnqueuedCount, 1);
    r.api.handleTransportControl({type: "flow", paused: false});
    assert.equal(r.pendingSends.length, 0);
    assert.equal(r.port.messages.length, 1);
    assert.equal(r.state.sendFlushedCount, 1);
    assert.equal(r.state.sendFlushedBytes, 3);
    r.api.closeTransport();
    const messagesAfterClose = r.port.messages.length;
    r.api.send(new Uint8Array([4]));
    r.setPaused(false);
    r.api.flushSends();
    assert.equal(r.port.messages.length, messagesAfterClose);
    assert.equal(r.state.sendEnqueuedCount, 1);
    assert.equal(r.errors.length, 0);
    r.api.handleTransportControl({type: "close"});
    assert.equal(r.state.transportCloseReason, "local-close-ack");
    assert.equal(r.errors.length, 0);
  }
  {
    const r = runtime();
    r.state.chunkPackets = 1;
    r.api.handleTransportControl({type: "flow", paused: true});
    r.api.send(new Uint8Array([7]));
    assert.equal(r.pendingSends.length, 1);
    for (const [index, field] of [
      "roamHeartbeatTimer", "roamSettleTimer", "roamStepTimer", "blockActionProbeTimer",
      "blockActionProbeDeadlineTimer", "blockActionStopTimer", "blockActionAckTimer",
      "blockActionRetryTimer", "blockDropTimer", "blockReboundTimer",
    ].entries()) {
      const id = index + 1;
      r.state[field] = id;
      r.timers.set(id, {at: id, fn() {}});
    }
    r.api.handleTransportControl({type: "close"});
    assert.equal(r.state.transportClosed, true);
    assert.equal(r.state.probeFailed, true);
    assert.equal(r.errors.length, 1);
    assert.match(r.errors[0].message, /closed after PLAY chunk data/);
    assert.equal(r.timers.size, 0);
    assert.equal(r.port.closed, 1);
    r.api.handleTransportControl({type: "flow", paused: false});
    assert.equal(r.state.sendFlushedCount, 0);
    r.api.send(new Uint8Array([9]));
    assert.equal(r.port.messages.length, 0);
  }
  for (const c of [
    {position: {x: 15.5, y: 64, z: 15.5}, chunk: [1, 0], x: 16, z: 15},
    {position: {x: -0.5, y: 64, z: -0.5}, chunk: [-1, -1], x: -2, z: -1},
  ]) {
    const r = runtime(); r.state.playerPosition = c.position; r.chunk(...c.chunk);
    r.api.maybeScheduleMining(); r.until(3000);
    assert.equal(r.sent.length, 1); assert.equal(r.state.blockActionTarget.x, c.x);
    assert.equal(r.state.blockActionTarget.z, c.z);
  }
  {
    const r = runtime(); r.api.maybeScheduleMining(); r.until(3000);
    assert.equal(r.sent.length, 0); assert.equal(r.state.blockActionProbeWaitReason, "awaiting-candidate-chunk");
    r.chunk(0, 0); r.until(3100);
    assert.equal(r.sent.length, 1); assert.equal(r.state.blockActionProbeDeadlineAt, 30000);
    assert.equal(r.state.blockActionProbeWaitReason, undefined);
  }
  {
    const r = runtime(); r.api.maybeScheduleMining();
    for (const c of r.state.blockActionCandidates) r.state.uniqueChunkPositions.add(r.api.chunkKeyForBlock(c.x, c.z));
    r.until(16000); assert.equal(r.state.blockActionProbeCount, 16);
    assert.equal(r.state.blockActionProbeWaitReason, "all-loaded-candidates-tried");
    assert.equal(r.state.probeFailed, true); assert.equal(r.errors.length, 1); assert.equal(r.timers.size, 0);
  }
  for (const loaded of [false, true]) {
    const r = runtime(); if (loaded) r.chunk(0, 0);
    r.api.maybeScheduleMining(); r.until(30000);
    assert.equal(r.state.probeFailed, true); assert.equal(r.errors.length, 1); assert.equal(r.timers.size, 0);
    assert.equal(r.state.blockActionProbeWaitReason, loaded
      ? "probe-deadline-no-authoritative-confirmation" : "probe-deadline-no-loaded-candidate");
    const sent = r.sent.length; r.chunk(1, 0); r.api.probeNextBlock();
    r.api.startConfirmedBlockAction({x: 16, y: 63, z: 15, stateId: 1}); r.until(60000);
    assert.equal(r.sent.length, sent); assert.equal(r.resolved(), 0);
  }
  {
    const r = runtime(); r.options.requireBlockDrop = true; r.api.maybeScheduleMining(); r.until(500);
    assert.equal(r.sent.length, 0); r.chunk(0, 0); r.until(600);
    assert.equal(r.sent.length, 2);
    assert.match(new TextDecoder().decode(r.sent[1]), /setblock 13 65 15 minecraft:nether_bricks/);
    assert.equal(r.state.blockActionProbeWaitReason, undefined);
    r.until(10600); assert.equal(r.state.probeFailed, true); assert.equal(r.timers.size, 0);
  }
  for (const z of [15, -1]) {
    const r = runtime(); r.options.requireBlockDrop = true;
    r.state.blockActionTarget = {x: 13, y: 65, z};
    r.state.targetAirUpdates = 1; r.state.targetStableAt = 1;
    r.state.blockActionStopSequence = 1; r.state.blockActionAckSequences = [1];
    r.state.blockDropEntity = {entityTypeId: 1};
    r.api.completeBlockAction();
    assert.match(new TextDecoder().decode(r.sent[0]), new RegExp("setblock 13 65 " + (z - 1) + " minecraft:gold_block"));
    assert.equal(r.api.chunkKeyForBlock(13, z - 1), r.api.chunkKeyForBlock(13, z));
  }
  // A confirmed block retains its hold/ACK cycle; retry does not renew discovery time.
  {
    const r = runtime(); r.api.maybeScheduleMining(); r.until(29800); r.chunk(0, 0); r.until(29900);
    r.api.startConfirmedBlockAction({...r.state.blockActionTarget, stateId: 1}); r.until(30000);
    assert.equal(r.errors.length, 0); r.until(42050);
    assert.equal(r.state.blockActionProbeDeadlineAt, 30000); assert.equal(r.state.probeFailed, true);
    assert.equal(r.errors.length, 1); assert.equal(r.timers.size, 0);
  }
  {
    const r = runtime(); r.api.maybeScheduleMining(); const late = [...r.timers.values()].map(t => t.fn);
    r.api.closeTransport(); r.api.closeTransport(); r.chunk(0, 0);
    for (const callback of late) callback();
    r.api.startConfirmedBlockAction({x: 14, y: 63, z: 15, stateId: 1});
    r.state.targetAirUpdates = 1; r.state.targetStableAt = 1; r.api.completeBlockAction(); r.until(60000);
    assert.equal(r.sent.length, 0); assert.equal(r.port.closed, 1); assert.equal(r.timers.size, 0);
    assert.equal(r.errors.length, 0); assert.equal(r.resolved(), 0);
  }
  return {ok: true, productionFunctions: true, signedBoundaries: true, delayedChunk: true,
    fixedClock: true, allCandidates: true, lateConfirmation: true, terminalCancellation: true};
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log("singleplayer worker probe fixture passed: " + JSON.stringify(runProbeFixture()));
}
