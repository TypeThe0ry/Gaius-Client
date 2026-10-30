/*
 * Server-free regression smoke for the browser WebSocket inbound budgets.
 *
 * Empty binary frames carry no Minecraft bytes, but they still occupy a bridge
 * queue slot and execute JavaScript/TeaVM work.  This model keeps the strict
 * latency gates unchanged while proving that empty-frame processing is bounded
 * per turn, that queued frame count has an independent cap, and that Java can
 * request a continuation after an empty-only transport turn.
 */

import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const channelPath = fileURLToPath(new URL(
    "../../port/overrides/libraries/netty-transport/src/main/java/" +
        "io/netty/channel/browser/BrowserWebSocketChannel.java",
    import.meta.url));
const source = await readFile(channelPath, "utf8");

const javaPumpStart = source.indexOf("private boolean pump()");
const javaPumpEnd = source.indexOf("\n    private static Int8Array copyBytes", javaPumpStart);
assert.ok(javaPumpStart >= 0 && javaPumpEnd > javaPumpStart,
    "Java pump boundaries changed; inspect the budget contract before updating this smoke");
const javaPump = source.slice(javaPumpStart, javaPumpEnd);

const jsPumpStart = source.indexOf("function pumpSlices(entry)");
const jsPumpEnd = source.indexOf("\n            function releaseDecoderCumulation", jsPumpStart);
assert.ok(jsPumpStart >= 0 && jsPumpEnd > jsPumpStart,
    "JS pumpSlices boundaries changed; inspect the budget contract before updating this smoke");
const jsPump = source.slice(jsPumpStart, jsPumpEnd);

// Java must count every non-null bridge frame before looking at payload length.
assert.match(source, /MAX_FRAMES_POLLED_PER_PUMP\s*=\s*64/u,
    "Java empty-frame poll cap is missing");
assert.match(javaPump,
    /while\s*\(framesPolled\s*<\s*MAX_FRAMES_POLLED_PER_PUMP[\s\S]*?\n\s*&&\s*chunks\s*<\s*MAX_CHUNKS_PER_PUMP/u,
    "Java pump must apply the frame cap before chunk/byte work");
assert.match(javaPump, /if\s*\(framesPolled\s*>\s*0\s*&&\s*monotonicMillis()/u,
    "Java elapsed budget must be checked after the first polled frame");
assert.match(javaPump,
    /if\s*\(data\s*==\s*null\)\s*\{[\s\S]*?\}[\s\S]*?framesPolled\+\+;[\s\S]*?byte\[\]\s+bytes\s*=\s*data\.copyToJavaArray()/u,
    "Java must increment framesPolled before inspecting an empty payload");
assert.match(javaPump,
    /return\s+chunks\s*>\s*0\s*\|\|\s*\(framesPolled\s*>\s*0\s*&&\s*hasPendingInbound\(socketId\)\)/u,
    "Java empty-only turns must retain a continuation hint while input remains");

// JS must independently bound work items and queued frame objects.  The strict
// 2 ms budget remains the same; this closes the zero-byte bypass rather than
// relaxing any existing gate.
assert.match(source, /const\s+maximumInboundFramesPerPump\s*=\s*256/u,
    "JS inbound per-turn frame cap is missing");
assert.match(source, /const\s+maximumInboundQueueFrames\s*=\s*4096/u,
    "JS inbound queued-frame cap is missing");
assert.match(source, /const\s+fallbackInboundQueueFrames\s*=\s*4096/u,
    "bootstrap fallback queued-frame cap is missing");
assert.match(jsPump,
    /let\s+framesProcessed\s*=\s*0[\s\S]*?framesProcessed\s*<\s*maximumInboundFramesPerPump/u,
    "JS pump must cap frame/slice work items per turn");
assert.match(jsPump,
    /if\s*\(framesProcessed\s*>\s*0\s*&&\s*now\(\)\s*-\s*startedAt\s*>=\s*inboundSliceBudgetMillis\)/u,
    "JS elapsed budget must run after the first frame, including empty frames");
assert.match(jsPump,
    /const\s+frame\s*=\s*entry\.pendingInbound\[entry\.pendingInboundHead\];[\s\S]*?framesProcessed\+\+;[\s\S]*?const\s+remaining\s*=/u,
    "JS must increment framesProcessed before the empty-frame fast path");
assert.match(source, /function\s+queuedFrameCount\(entry\)/u,
    "JS queued-frame accounting helper is missing");
assert.match(source,
    /if\s*\(queuedFrameCount\(entry\)\s*>=\s*maximumInboundQueueFrames\)[\s\S]*?inbound queue exceeded frame limit/u,
    "JS must fail closed before zero-byte frames bypass the byte watermark");
assert.match(source, /const\s+inboundSliceBudgetMillis\s*=\s*2\.0/u,
    "strict inbound slice budget changed unexpectedly");

function boundedTurns(frameCount, perTurnLimit) {
    let remaining = frameCount;
    let turns = 0;
    let maximumProcessed = 0;
    while (remaining > 0) {
        turns++;
        const processed = Math.min(remaining, perTurnLimit);
        remaining -= processed;
        maximumProcessed = Math.max(maximumProcessed, processed);
    }
    return { turns, maximumProcessed, remaining };
}

const emptyBurst = boundedTurns(1000, 256);
assert.deepEqual(emptyBurst, { turns: 4, maximumProcessed: 256, remaining: 0 },
    "1000 empty frames must require bounded continuation turns");
const largerBurst = boundedTurns(100000, 256);
assert.equal(largerBurst.remaining, 0);
assert.ok(largerBurst.turns >= Math.ceil(100000 / 256));
assert.ok(largerBurst.maximumProcessed <= 256);

function queueAdmission(frameCount, limit) {
    let accepted = 0;
    let rejected = false;
    for (let i = 0; i < frameCount; i++) {
        if (accepted >= limit) {
            rejected = true;
            break;
        }
        accepted++;
    }
    return { accepted, rejected };
}

assert.deepEqual(queueAdmission(4096, 4096), { accepted: 4096, rejected: false });
assert.deepEqual(queueAdmission(4097, 4096), { accepted: 4096, rejected: true },
    "zero-byte frames must not grow the pending object queue without bound");

function javaContinuationHint({ framesPolled, chunks, pendingAfter }) {
    return chunks > 0 || (framesPolled > 0 && pendingAfter);
}

assert.equal(javaContinuationHint({ framesPolled: 64, chunks: 0, pendingAfter: true }), true,
    "empty-only Java turn with queued input must continue");
assert.equal(javaContinuationHint({ framesPolled: 64, chunks: 0, pendingAfter: false }), false,
    "exhausted empty-only Java tail must not create an idle loop");
assert.equal(javaContinuationHint({ framesPolled: 1, chunks: 1, pendingAfter: false }), true);

console.log(JSON.stringify({
    ok: true,
    javaFramesPerPump: 64,
    javascriptFramesPerPump: 256,
    queuedFrameLimit: 4096,
    emptyBurstFrames: 1000,
    emptyBurstTurns: emptyBurst.turns,
    strictInboundSliceBudgetMillis: 2.0,
    strictGatesChanged: false,
    productionRuntimeProof: false
}));
