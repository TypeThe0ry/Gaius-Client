#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");

const pumpStart = source.indexOf("public static boolean pumpAllAndReportProgress()");
const pumpEnd = source.indexOf(
  "/** Returns whether a browser transport has data waiting for the Java pipeline. */",
  pumpStart,
);
assert.ok(pumpStart >= 0 && pumpEnd > pumpStart,
  "global pump method could not be extracted");
const pump = source.slice(pumpStart, pumpEnd);

// Extract the per-channel pump as well as the global ring scanner.  The global model treats a
// channel call as non-preemptible, but this source-level check makes that assumption explicit:
// the first non-empty handoff is allowed to finish before the cooperative time check can run.
const channelPumpStart = source.indexOf("private boolean pump()");
const channelPumpEnd = source.indexOf(
  "private static Int8Array copyBytes",
  channelPumpStart,
);
assert.ok(channelPumpStart >= 0 && channelPumpEnd > channelPumpStart,
  "per-channel pump method could not be extracted");
const channelPump = source.slice(channelPumpStart, channelPumpEnd);

// The per-channel limits are an existing contract. The global scheduler must add a bound,
// never replace or silently enlarge these limits.
assert.match(source, /MAX_CHUNKS_PER_PUMP = 64/);
assert.match(source, /MAX_BYTES_PER_PUMP = 256 \* 1024/);
assert.match(source, /MAX_MILLIS_PER_PUMP = 2\.0/);
assert.match(source, /MAX_TOTAL_MILLIS_PER_PUMP = 4\.0/);
assert.match(source, /private static int nextPumpChannelIndex;/);
assert.match(source, /pumpAllTurns: 0/);
assert.match(source, /pumpAllChannelsVisited: 0/);
assert.match(source, /pumpAllBudgetYields: 0/);
assert.match(source, /recordPumpAllTelemetry\(channelsVisited, elapsed, budgetExhausted\)/);

assert.match(pump, /final double startedAt = monotonicMillis\(\);/);
assert.match(pump, /final int channelCount = channels\.length;/);
assert.match(pump, /int index = nextPumpChannelIndex;/);
assert.match(pump, /while \(channelsVisited < channelCount\)/);
assert.match(pump,
  /channelsVisited > 0[\s\S]*monotonicMillis\(\) - startedAt >= MAX_TOTAL_MILLIS_PER_PUMP/,
  "global pump lacks its aggregate time gate after the first inspected slot");
assert.match(pump, /int openChannelsVisited = 0;/);
assert.match(pump, /openChannelsVisited\+\+;/);
assert.match(pump, /budgetExhausted = true;/);
assert.match(pump, /if \(channel == null \|\| !channel\.open\) \{\s*continue;/);
assert.match(pump, /progressed \|= channel\.pump\(\);/);
assert.match(pump, /nextPumpChannelIndex = currentLength == 0 \? 0 : index % currentLength;/);
assert.match(pump, /return progressed \|\| budgetExhausted;/,
  "a budget-shortened scan must return a continuation hint for ready channels later in the ring");
assert.match(source, /public static void pumpAll\(\) \{\s*pumpAllAndReportProgress\(\);\s*\}/,
  "legacy pumpAll entry point no longer uses the bounded global pump");
assert.match(source, /public static boolean hasPumpableInput\(\)/,
  "global pump lost the ready-input predicate used by the follow-up scheduler");
assert.match(source,
  /int bridgeState = aggregatePendingInputState\(\);[\s\S]*?if \(bridgeState >= 0\)[\s\S]*?bridgeState != 0/s,
  "pending-input readiness does not prefer the dense bridge aggregate");
assert.match(source,
  /int bridgeState = aggregatePumpableInputState\(\);[\s\S]*?if \(bridgeState >= 0\)[\s\S]*?bridgeState != 0/s,
  "pumpable-input readiness does not prefer the dense bridge aggregate");
assert.match(source, /private static native int aggregatePendingInputState\(\);/,
  "pending-input aggregate helper is missing");
assert.match(source, /private static native int aggregatePumpableInputState\(\);/,
  "pumpable-input aggregate helper is missing");
assert.match(source,
  /aggregatePendingInputState[\s\S]*?return -1;[\s\S]*?bridge\.channels\.forEach[\s\S]*?pendingInboundHead < entry\.pendingInbound\.length[\s\S]*?private static native int aggregatePendingInputState\(\);/s,
  "pending-input aggregate helper lost the dense-map/fallback contract");
assert.match(source,
  /aggregatePumpableInputState[\s\S]*?return -1;[\s\S]*?bridge\.channels\.forEach[\s\S]*?bridge\.exactPacketQueuePaused !== true[\s\S]*?entry\.inboundHead < entry\.inbound\.length[\s\S]*?private static native int aggregatePumpableInputState\(\);/s,
  "pumpable-input aggregate helper lost the pause-aware dense-map contract");
assert.match(source, /private int channelSlot = -1;/,
  "browser channel registry lost its O(1) lifecycle slot");
assert.match(source, /channels\[index\] = channel;\s*channel\.channelSlot = index;/s,
  "channel registration does not record the assigned slot");
assert.match(source,
  /int slot = channel\.channelSlot;[\s\S]*?channels\[slot\] = null;[\s\S]*?channel\.channelSlot = -1;/,
  "channel removal does not clear the recorded slot in its fast path");
assert.doesNotMatch(pump, /Platform\.schedule|new Thread|CompletableFuture|setTimeout|MessageChannel/,
  "global pump introduced asynchronous/thread work into the bounded Java turn");
assert.doesNotMatch(pump, /for \(BrowserWebSocketChannel channel : channels\)/,
  "global pump reverted to an unbounded full-array foreach");

// Each polled transport frame consumes cooperative work, including a zero-byte frame.  The
// guard intentionally keys off framesPolled rather than decoded chunks so an empty-frame burst
// cannot spin indefinitely before the first timing checkpoint.
const channelBudgetGuard = channelPump.indexOf(
  "if (framesPolled > 0 && monotonicMillis() - pumpStarted >= MAX_MILLIS_PER_PUMP)",
);
const pollInboundIndex = channelPump.indexOf("Int8Array data = pollInbound(socketId)");
const copyToJavaArrayIndex = channelPump.indexOf("byte[] bytes = data.copyToJavaArray()", pollInboundIndex);
const fireChannelReadIndex = channelPump.indexOf("pipeline.fireChannelRead", copyToJavaArrayIndex);
const chunksIncrementIndex = channelPump.indexOf("chunks++", fireChannelReadIndex);
assert.ok(channelBudgetGuard >= 0, "per-channel pump lost its cooperative time guard");
assert.ok(channelBudgetGuard < pollInboundIndex,
  "per-channel time guard must run before the first inbound poll");
const framesIncrementIndex = channelPump.indexOf("framesPolled++", pollInboundIndex);
assert.ok(pollInboundIndex < framesIncrementIndex
  && framesIncrementIndex < copyToJavaArrayIndex,
  "per-channel frame count must increment immediately after poll and before payload inspection");
assert.ok(pollInboundIndex < copyToJavaArrayIndex
  && copyToJavaArrayIndex < fireChannelReadIndex
  && fireChannelReadIndex < chunksIncrementIndex,
"per-channel first-chunk order changed: poll -> copy -> fire -> count is required");
// Diagnostic pipeline timing is intentionally allowed in this window, but the first handoff
// must remain one synchronous operation: no nested inbound poll/pump or asynchronous scheduler
// may run before the current buffer reaches the Netty pipeline.
const firstHandoffWindow = channelPump.slice(copyToJavaArrayIndex, fireChannelReadIndex);
const timingCalls = firstHandoffWindow.match(/monotonicMillis\(\)/g) || [];
assert.ok(timingCalls.length <= 1,
  "first non-empty handoff added more than one timing sample before fireChannelRead");
assert.doesNotMatch(firstHandoffWindow,
  /(?:\bpump(?:AllAndReportProgress)?\s*\(|\bpollInbound\s*\(|\bdeliverInbound\s*\(|Platform\.schedule|setTimeout|setInterval|MessageChannel|new\s+Thread|CompletableFuture)/,
  "first non-empty handoff became re-entrant or asynchronously scheduled");

const MAX_TOTAL_MILLIS = 4;

/**
 * Deterministic model of the Java loop. A channel's one pump call is intentionally treated as
 * non-preemptible; the aggregate gate is checked only between channels, just like the product.
 */
function modelPumpTurns(channelWork, perChannelMillis = 2, capacity = channelWork.length) {
  const remaining = channelWork.slice();
  let cursor = 0;
  const turns = [];
  while (remaining.some((work) => work > 0)) {
    let visited = 0;
    let elapsed = 0;
    const processed = [];
    let budgetYield = false;
    while (visited < capacity) {
      if (visited > 0 && elapsed >= MAX_TOTAL_MILLIS) {
        budgetYield = true;
        break;
      }
      const index = cursor;
      cursor = (cursor + 1) % capacity;
      visited++;
      if (remaining[index] > 0) {
        remaining[index]--;
        processed.push(index);
        elapsed += perChannelMillis;
      }
    }
    turns.push({
      visited,
      elapsed,
      processed,
      budgetYield,
      continuationHint: processed.length > 0 || budgetYield,
    });
    assert.ok(visited > 0, "model produced a zero-slot turn");
    if (turns.length > 10000) throw new Error("model did not drain");
  }
  return turns;
}

// Sixteen busy channels at the old 2 ms per-channel worst case must never run as one 32 ms turn.
// With a 4 ms aggregate budget, two channels are the maximum in a turn and all channels rotate.
const sixteenBusy = modelPumpTurns(new Array(16).fill(1), 2);
assert.equal(sixteenBusy.length, 8, "16-channel model did not drain in eight fair turns");
assert.ok(sixteenBusy.every((turn) => turn.elapsed <= MAX_TOTAL_MILLIS),
  "model exceeded the global aggregate budget");
assert.ok(sixteenBusy.every((turn) => turn.processed.length <= 2),
  "two-millisecond channels exceeded the 4 ms global turn bound");
assert.deepEqual(sixteenBusy.flatMap((turn) => turn.processed),
  [...Array(16).keys()],
  "round-robin cursor did not service every busy channel exactly once");
assert.ok(sixteenBusy.some((turn) => turn.budgetYield),
  "global budget exhaustion was not represented in the model");
assert.ok(sixteenBusy.every((turn) => turn.continuationHint),
  "every budget-shortened busy turn must advertise a continuation hint");

// A sparse ring can spend the first turn visiting idle channels before the ready channel. Model
// the tiny native lookup cost of those idle slots: the first turn may hit the aggregate budget
// with zero slices consumed, but the continuation hint must still make the ready channel retry.
function modelBudgetScan(channelWork, idleMillis, perChannelMillis = 2) {
  const remaining = channelWork.slice();
  let cursor = 0;
  const turns = [];
  while (remaining.some((work) => work > 0)) {
    let visited = 0;
    let elapsed = 0;
    const processed = [];
    let budgetYield = false;
    while (visited < remaining.length) {
      if (visited > 0 && elapsed >= MAX_TOTAL_MILLIS) {
        budgetYield = true;
        break;
      }
      const index = cursor;
      cursor = (cursor + 1) % remaining.length;
      visited++;
      if (remaining[index] > 0) {
        remaining[index]--;
        processed.push(index);
        elapsed += perChannelMillis;
      } else {
        elapsed += idleMillis;
      }
    }
    turns.push({
      visited,
      elapsed,
      processed,
      budgetYield,
      continuationHint: processed.length > 0 || budgetYield,
    });
    if (turns.length > 1000) throw new Error("budget model did not drain");
  }
  return turns;
}
const delayedReady = modelBudgetScan([0, 0, 0, 0, 0, 0, 0, 1], 1);
assert.equal(delayedReady[0].processed.length, 0,
  "delayed-ready model should exercise a budget yield before any slice is consumed");
assert.equal(delayedReady[0].budgetYield, true,
  "idle-slot scan did not hit the aggregate budget");
assert.equal(delayedReady[0].continuationHint, true,
  "budget hit without progress lost the continuation hint");
assert.ok(delayedReady.slice(1).some((turn) => turn.processed.includes(7)),
  "ready channel after idle slots was never serviced");

/**
 * Model the actual backing array shape separately from channel work. A null/closed slot is
 * visited but does not increment openChannelsVisited, while an open-idle channel does. The
 * aggregate budget is keyed to visited slots; slotMillis lets the sparse-capacity case model the
 * non-zero lookup cost of a large hole ring without pretending a null slot is an open channel.
 */
function modelSlotPumpTurns(slots, idleMillis, perChannelMillis = 2, slotMillis = 0) {
  const remaining = slots.map((slot) => slot.work);
  let cursor = 0;
  const turns = [];
  while (remaining.some((work, index) => slots[index].open && work > 0)) {
    let visited = 0;
    let openChannelsVisited = 0;
    let elapsed = 0;
    const processed = [];
    let budgetYield = false;
    while (visited < slots.length) {
      if (visited > 0 && elapsed >= MAX_TOTAL_MILLIS) {
        budgetYield = true;
        break;
      }
      const index = cursor;
      cursor = (cursor + 1) % slots.length;
      visited++;
      const slot = slots[index];
      // A sparse backing-array lookup still consumes a small, non-zero amount of native work.
      // The product budget must therefore be keyed to visited slots, not only open channels.
      elapsed += slotMillis;
      if (!slot.open) continue;
      openChannelsVisited++;
      if (remaining[index] > 0) {
        remaining[index]--;
        processed.push(index);
        elapsed += perChannelMillis;
      } else {
        elapsed += idleMillis;
      }
    }
    turns.push({
      visited,
      openChannelsVisited,
      elapsed,
      processed,
      budgetYield,
      continuationHint: processed.length > 0 || budgetYield,
    });
    if (turns.length > 1000) throw new Error("slot model did not drain");
  }
  return turns;
}

const nullHoleRing = modelSlotPumpTurns([
  {open: false, work: 0},
  {open: false, work: 0},
  {open: false, work: 0},
  {open: false, work: 0},
  {open: false, work: 0},
  {open: false, work: 0},
  {open: true, work: 1},
], 1);
assert.equal(nullHoleRing[0].processed[0], 6,
  "null/closed backing-array holes incorrectly consumed an open-channel budget turn");
assert.equal(nullHoleRing[0].openChannelsVisited, 1,
  "null/closed slots were counted as open channels");

// After enough connect/close churn the Java backing array can be much larger than the number of
// live channels. A simulated per-slot lookup cost must still hit the 4 ms aggregate budget after
// the first inspected slot; otherwise an all-hole ring performs an unbounded O(capacity) scan in
// one browser turn. The ready channel remains reachable through the round-robin continuation.
const sparseCapacityRing = modelSlotPumpTurns([
  ...Array.from({length: 2047}, () => ({open: false, work: 0})),
  {open: true, work: 1},
], 0, 2, 0.25);
assert.equal(sparseCapacityRing[0].processed.length, 0,
  "high-capacity hole scan unexpectedly processed a distant ready channel in one turn");
assert.equal(sparseCapacityRing[0].budgetYield, true,
  "high-capacity hole scan ignored the aggregate budget after visiting slots");
assert.ok(sparseCapacityRing[0].visited > 0 && sparseCapacityRing[0].visited < 2048,
  "high-capacity hole scan did not stop after a bounded number of visited slots");
assert.ok(sparseCapacityRing.slice(1).some((turn) => turn.processed.includes(2047)),
  "ready channel behind a high-capacity hole ring was never serviced");

/**
 * Readiness predicates use the bridge's dense live-channel Map instead of the Java backing array.
 * The model keeps the old array result as an oracle while recording how many entries each path
 * must inspect.  A distant ready channel must remain visible, but historical holes must not turn
 * every continuation into an O(capacity) scan.
 */
function modelAggregateReadiness(slots, exactPacketQueuePaused = false) {
  const backing = slots.slice();
  const live = backing.filter((entry) => entry !== null && !entry.disposed);
  const pending = (entry) => entry.inboundHead < entry.inbound.length ||
    entry.pendingInboundHead < entry.pendingInbound.length;
  const pumpable = (entry) => !entry.disposed && !exactPacketQueuePaused &&
    entry.inboundHead < entry.inbound.length;
  let backingVisits = 0;
  let denseVisits = 0;
  let pendingBacking = false;
  for (const entry of backing) {
    backingVisits++;
    if (entry && !entry.disposed && pending(entry)) {
      pendingBacking = true;
      break;
    }
  }
  let pendingDense = false;
  for (const entry of live) {
    denseVisits++;
    if (pending(entry)) {
      pendingDense = true;
      break;
    }
  }
  let pumpableDense = false;
  let pumpableVisits = 0;
  for (const entry of live) {
    pumpableVisits++;
    if (pumpable(entry)) {
      pumpableDense = true;
      break;
    }
  }
  return {
    pendingBacking,
    pendingDense,
    pumpableDense,
    backingVisits,
    denseVisits,
    pumpableVisits,
    liveCount: live.length,
  };
}

const sparseReadinessSlots = [
  ...Array.from({length: 2047}, () => null),
  {
    disposed: false,
    inbound: [new Uint8Array([1])],
    inboundHead: 0,
    pendingInbound: [],
    pendingInboundHead: 0,
  },
];
const sparseReadiness = modelAggregateReadiness(sparseReadinessSlots);
assert.equal(sparseReadiness.pendingBacking, true,
  "sparse readiness oracle failed to find the distant pending channel");
assert.equal(sparseReadiness.pendingDense, true,
  "dense pending readiness missed the distant live channel");
assert.equal(sparseReadiness.pumpableDense, true,
  "dense pumpable readiness missed the distant live channel");
assert.equal(sparseReadiness.liveCount, 1,
  "sparse readiness model unexpectedly retained historical holes as live entries");
assert.ok(sparseReadiness.backingVisits > 2000,
  "sparse readiness oracle did not exercise historical backing-array capacity");
assert.equal(sparseReadiness.denseVisits, 1,
  "dense pending readiness did not visit only the live Map entry");
assert.equal(sparseReadiness.pumpableVisits, 1,
  "dense pumpable readiness did not visit only the live Map entry");
const pausedReadiness = modelAggregateReadiness(sparseReadinessSlots, true);
assert.equal(pausedReadiness.pendingDense, true,
  "exact queue pause incorrectly hid pending bytes from the pending predicate");
assert.equal(pausedReadiness.pumpableDense, false,
  "exact queue pause failed to suppress pumpable readiness");

const openIdleRing = modelSlotPumpTurns([
  {open: true, work: 0},
  {open: true, work: 0},
  {open: true, work: 0},
  {open: true, work: 0},
  {open: true, work: 0},
  {open: true, work: 0},
  {open: true, work: 0},
  {open: true, work: 1},
], 1);
assert.equal(openIdleRing[0].processed.length, 0,
  "open-idle channels should be able to consume the first aggregate budget turn");
assert.equal(openIdleRing[0].budgetYield, true,
  "open-idle aggregate scan did not advertise a continuation");
assert.ok(openIdleRing.slice(1).some((turn) => turn.processed.includes(7)),
  "ready channel after open-idle slots was never serviced");

// Sustained work must continue rotating: no channel may wait for another channel's entire
// backlog. The largest service gap is bounded by one full 16-channel rotation.
const sustained = modelPumpTurns(new Array(16).fill(4), 2);
const serviceTurns = new Map();
sustained.forEach((turn, turnIndex) => {
  for (const channel of turn.processed) {
    const prior = serviceTurns.get(channel);
    if (prior !== undefined) {
      assert.ok(turnIndex - prior <= 8,
        `channel ${channel} waited more than one fair rotation`);
    }
    serviceTurns.set(channel, turnIndex);
  }
});
assert.equal(serviceTurns.size, 16, "sustained model starved one or more channels");

// Sparse holes and a backing-array growth must not make an active channel permanently invisible.
const sparse = modelPumpTurns(
  [0, 2, 0, 1, 0, 0, 3, 0],
  2,
);
const sparseProcessed = sparse.flatMap((turn) => turn.processed);
for (const index of [1, 3, 6]) {
  assert.ok(sparseProcessed.includes(index), `sparse channel ${index} was starved`);
}
assert.ok(sparse.every((turn) => turn.visited > 0 && turn.visited <= 8));

// A single non-preemptible handoff may itself exceed the aggregate budget. It is still processed
// once (forward progress), then the cursor moves on instead of spinning on the same slot.
const overBudgetSingle = modelPumpTurns([1, 1], 7, 2);
assert.equal(overBudgetSingle[0].processed.length, 1);
assert.equal(overBudgetSingle[0].processed[0], 0);
assert.equal(overBudgetSingle[1].processed[0], 1);
assert.ok(overBudgetSingle[0].elapsed > MAX_TOTAL_MILLIS,
  "the model hid a non-preemptible handoff overshoot");

const nearBudgetOvershoot = modelPumpTurns([1, 1, 1], 1.9, 3);
assert.equal(nearBudgetOvershoot[0].processed.length, 3,
  "near-budget channels unexpectedly yielded before the third handoff");
assert.ok(nearBudgetOvershoot[0].elapsed > MAX_TOTAL_MILLIS,
  "near-budget non-preemptible handoffs were incorrectly treated as hard-preemptible");

/**
 * Model one channel's inner loop.  The guard runs between chunks, never in the middle of a
 * poll/copy/pipeline handoff.  This is deliberately separate from the global ring model above
 * so a future edit cannot make the aggregate test pass while changing the first-chunk boundary.
 */
function modelChannelPump(chunkCosts, perChannelBudget = 2) {
  let elapsed = 0;
  let processed = 0;
  for (const cost of chunkCosts) {
    if (processed > 0 && elapsed >= perChannelBudget) {
      break;
    }
    elapsed += cost;
    processed++;
  }
  return {elapsed, processed};
}

const firstChunkOvershoot = modelChannelPump([7, 1]);
assert.deepEqual(firstChunkOvershoot, {elapsed: 7, processed: 1},
  "a slow first chunk must complete before the per-channel guard yields");
assert.ok(firstChunkOvershoot.elapsed > MAX_TOTAL_MILLIS,
  "first-chunk overshoot was hidden instead of represented");
const secondChunkOvershoot = modelChannelPump([1, 8]);
assert.deepEqual(secondChunkOvershoot, {elapsed: 9, processed: 2},
  "a second chunk may overshoot after the between-chunk guard permits it");

console.log(JSON.stringify({
  smoke: "browser-global-pump-fairness",
  maxTotalMillis: MAX_TOTAL_MILLIS,
  sixteenBusyTurns: sixteenBusy.length,
  sixteenBusyMaxProcessed: Math.max(...sixteenBusy.map((turn) => turn.processed.length)),
  sparseTurns: sparse.length,
  sparseReadinessDenseVisits: sparseReadiness.denseVisits,
  sparseReadinessBackingVisits: sparseReadiness.backingVisits,
  firstChunkOvershootMillis: firstChunkOvershoot.elapsed,
  secondChunkOvershootMillis: secondChunkOvershoot.elapsed,
  result: "pass",
}));
