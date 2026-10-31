#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");

// Keep the Java call sites split: normal write-budget yielding is low latency,
// while a bridge-reported sendSocket(false) is retried on the bridge backoff.
assert.match(source, /OUTBOUND_BACKPRESSURE_RETRY_DELAY_MILLIS = 4/);
assert.match(source,
  /MAX_OUTBOUND_MILLIS_PER_PUMP\)\) \{\s*scheduleOutboundContinuation\(\);\s*return;/s);
assert.match(source,
  /if \(!sendSocket\(socketId, chunk\)\) \{[\s\S]{0,900}?scheduleOutboundRetry\(\);/s);
assert.match(source,
  /private void scheduleOutboundContinuation\(\) \{\s*scheduleOutboundTask\(0\);\s*\}/s);
assert.match(source,
  /private void scheduleOutboundRetry\(\) \{\s*scheduleOutboundTask\(OUTBOUND_BACKPRESSURE_RETRY_DELAY_MILLIS\);\s*\}/s);

function simulate({payload, sendResults, writesPerTurn = 2}) {
  let offset = 0;
  let resultIndex = 0;
  let now = 0;
  let pendingTask = null;
  const tasks = [];
  const sent = [];
  const scheduled = [];

  const schedule = (kind, delay) => {
    if (pendingTask !== null) return;
    pendingTask = {kind, at: now + delay};
    tasks.push(pendingTask);
    scheduled.push({kind, delay});
  };

  const flush = () => {
    const task = pendingTask;
    assert.ok(task, "flush must have a scheduled task");
    pendingTask = null;
    now = task.at;
    let writes = 0;
    while (offset < payload.length) {
      if (writes >= writesPerTurn) {
        schedule("continuation", 0);
        return;
      }
      const chunk = payload[offset];
      const accepted = sendResults[Math.min(resultIndex, sendResults.length - 1)];
      resultIndex++;
      if (!accepted) {
        // A rejected write never advances the ByteBuf offset or emits bytes.
        schedule("retry", 4);
        return;
      }
      sent.push(chunk);
      offset++;
      writes++;
    }
  };

  schedule("initial", 0);
  // Calling schedule twice before the task runs must still leave one callback.
  schedule("duplicate", 4);
  assert.equal(tasks.length, 1, "per-channel retry callback was not coalesced");

  while (pendingTask !== null) flush();
  assert.equal(offset, payload.length, "payload did not fully drain");
  assert.deepEqual(sent, payload, "payload bytes/order changed during retries");
  return scheduled;
}

const budgetSchedule = simulate({
  payload: ["a", "b", "c", "d", "e"],
  sendResults: [true, true, true, true, true],
});
assert.ok(
  budgetSchedule.some((entry) => entry.kind === "continuation" && entry.delay === 0),
  "normal write-budget continuation did not remain zero-delay",
);
assert.ok(
  budgetSchedule.every((entry) => entry.kind !== "continuation" || entry.delay === 0),
  "normal continuation unexpectedly used backpressure delay",
);

const backpressureSchedule = simulate({
  payload: ["0", "1", "2", "3"],
  sendResults: [false, false, true, true, true, true],
  writesPerTurn: 4,
});
const retryDelays = backpressureSchedule
  .filter((entry) => entry.kind === "retry")
  .map((entry) => entry.delay);
assert.deepEqual(retryDelays, [4, 4], "sendSocket(false) retry did not use 4 ms backoff");
assert.ok(
  backpressureSchedule.every((entry) => entry.kind !== "retry" || entry.delay > 0),
  "backpressure retry formed a zero-delay chain",
);

console.log(JSON.stringify({
  smoke: "browser-java-outbound-retry",
  normalContinuationDelayMs: 0,
  backpressureRetryDelayMs: 4,
  coalescedPerChannel: true,
  bytesPreserved: true,
  result: "pass",
}));
