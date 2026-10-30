#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {MessageChannel} from "node:worker_threads";
import vm from "node:vm";

const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");

function jsBodyBefore(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf('@JSBody(script = """', markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  const body = source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
  if (marker === "private static native void initBridge();") {
    return "{\n" + body + "\n}\n{\n" +
      jsBodyBefore("private static native void initBridgeTail();") + "\n}";
  }
  return body;
}

const bridgeScript = jsBodyBefore("private static native void initBridge();");
const outboundSchedulerScript = jsBodyBefore(
  "private static native void initOutboundScheduler();",
);
assert.match(source, /maximumOutboundFramesPerTurn = 32/);
assert.match(source, /maximumOutboundBytesPerTurn = 256 \* 1024/);
assert.match(source, /maximumOutboundMillisPerTurn = 2/);
assert.match(source, /webSocketBackpressureRetryMs = 4/);
assert.match(source, /OUTBOUND_BACKPRESSURE_RETRY_DELAY_MILLIS = 4/,
  "Java outbound backpressure retry must align with the bridge 4 ms timer");
assert.match(source,
  /MAX_OUTBOUND_MILLIS_PER_PUMP\)\) \{\s*scheduleOutboundContinuation\(\);\s*return;/s,
  "normal outbound budget exhaustion must keep the low-latency continuation path");
assert.match(source,
  /if \(!sendSocket\(socketId, chunk\)\) \{[\s\S]{0,900}?scheduleOutboundRetry\(\);/s,
  "sendSocket(false) must use the backpressured retry path");
assert.match(source,
  /private void scheduleOutboundContinuation\(\) \{\s*scheduleOutboundTask\(0\);\s*\}/s,
  "normal outbound continuation must remain zero-delay");
assert.match(source,
  /private void scheduleOutboundRetry\(\) \{\s*scheduleOutboundTask\(OUTBOUND_BACKPRESSURE_RETRY_DELAY_MILLIS\);\s*\}/s,
  "backpressured outbound retry must use the bounded delay");
assert.match(outboundSchedulerScript, /queueMicrotask\(run\)/,
  "initial outbound flush must avoid the zero-delay timer clamp");
assert.match(outboundSchedulerScript, /requestFlush\(entry, 0, true\)/,
  "budget continuation must retain a macrotask boundary");
assert.match(outboundSchedulerScript, /const outboundContinuationScheduler/,
  "budget continuation must have a low-latency MessageChannel scheduler");
assert.match(outboundSchedulerScript, /channel\.port2\.postMessage\(0\)/,
  "MessageChannel continuation must post a separate browser task");
assert.match(outboundSchedulerScript, /outboundMessageChannelCallbacks\+\+/,
  "MessageChannel continuation execution must remain observable");
assert.match(outboundSchedulerScript, /!hasFlushableOutbound\(entry\)/,
  "idle inbound polling must not manufacture empty outbound turns");
assert.match(outboundSchedulerScript, /outboundFlushGeneration/,
  "microtask cancellation must be generation guarded");

function delay(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

async function waitFor(predicate, label, timeoutMillis = 3000) {
  const deadline = Date.now() + timeoutMillis;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await delay(1);
  }
}

function createContext(WebSocketImpl, localPorts = new Map(), localWorkers = new Map()) {
  const context = {
    AbortController,
    Array,
    ArrayBuffer,
    Boolean,
    Date,
    Int8Array,
    JSON,
    Map,
    Math,
    Number,
    Object,
    Promise,
    RegExp,
    Set,
    String,
    TextDecoder,
    TextEncoder,
    URL,
    URLSearchParams,
    Uint8Array,
    clearTimeout,
    console,
    fetch: async () => {
      throw new Error("Outbound smoke must not query a relay registry");
    },
    localStorage: {getItem: () => null},
    location: {
      href: "file:///Downloads/Gaius.html",
      hostname: "",
      protocol: "file:",
      search: "",
    },
    performance,
    MessageChannel,
    queueMicrotask,
    setTimeout,
    WebSocket: WebSocketImpl,
    __gaiusLocalServerPorts: localPorts,
    __gaiusSingleplayerWorkers: localWorkers,
  };
  context.globalThis = context;
  context.window = context;
  vm.runInNewContext(`(function() {${bridgeScript}\n})();`, context);
  vm.runInNewContext(`(function() {${outboundSchedulerScript}\n})();`, context);
  return context;
}

function assertBounded(stats, label) {
  assert.ok(stats.outboundTurns > 0, `${label} did not execute an outbound turn`);
  assert.ok(stats.maxOutboundTurnFrames <= stats.outboundFrameLimit,
    `${label} exceeded its frame budget`);
  assert.ok(stats.maxOutboundTurnBytes <= stats.outboundByteLimit,
    `${label} exceeded its byte budget`);
  assert.ok(Number.isFinite(stats.maxOutboundTurnMillis) && stats.maxOutboundTurnMillis < 100,
    `${label} produced an unbounded outbound callback`);
}

async function runLocalMessagePortSmoke() {
  class UnexpectedWebSocket {
    static OPEN = 1;
    constructor() {
      throw new Error("Local outbound smoke must not open a WebSocket");
    }
  }

  const sessionId = "6123456789abcdef0123456789abcdef";
  const socketId = 101;
  const {port1, port2} = new MessageChannel();
  const launchGeneration = "1";
  port1.__gaiusLaunchGeneration = launchGeneration;
  const localWorker = {
    __gaiusTerminal: false,
    __gaiusLaunchGeneration: launchGeneration,
    __gaiusClientPort: port1,
  };
  const context = createContext(
    UnexpectedWebSocket,
    new Map([[sessionId, port1]]),
    new Map([[sessionId, localWorker]]),
  );
  const bridge = context.__gaiusNettyBridge;
  const stats = context.__gaiusNetworkStats;
  const received = [];
  const controls = [];
  port2.on("message", (message) => {
    if (message instanceof ArrayBuffer || ArrayBuffer.isView(message)) {
      const bytes = message instanceof ArrayBuffer
        ? new Uint8Array(message)
        : new Uint8Array(message.buffer, message.byteOffset || 0, message.byteLength || 0);
      received.push(Uint8Array.from(bytes));
    } else {
      controls.push(message);
    }
  });

  bridge.open(socketId, `client-${sessionId}.gaius-local`, 25565);
  const entry = bridge.channels.get(socketId);
  port2.postMessage({type: "flow", paused: true});
  await waitFor(() => entry.remotePaused, "local flow pause");
  await delay(10);

  const frameCount = 96;
  const frameBytes = 4096;
  const largeFrameBytes = 300 * 1024;
  const expected = new Uint8Array(frameCount * frameBytes + largeFrameBytes);
  const turnsBeforeSend = stats.outboundTurns;
  for (let frameIndex = 0; frameIndex < frameCount; frameIndex++) {
    const frame = new Uint8Array(frameBytes);
    for (let index = 0; index < frame.length; index++) {
      frame[index] = (frameIndex * 17 + index * 3) & 0xff;
    }
    expected.set(frame, frameIndex * frameBytes);
    assert.equal(bridge.send(socketId, frame), true);
  }
  const largeFrame = new Uint8Array(largeFrameBytes);
  for (let index = 0; index < largeFrame.length; index++) {
    largeFrame[index] = (index * 11 + 5) & 0xff;
  }
  expected.set(largeFrame, frameCount * frameBytes);
  assert.equal(bridge.send(socketId, largeFrame), true);
  assert.equal(stats.outboundTurns, turnsBeforeSend,
    "local send ran synchronously instead of scheduling an asynchronous flush");
  await delay(20);
  assert.equal(received.length, 0, "remote flow pause did not stop local data sends");
  assert.equal(entry.queuedBytes, expected.byteLength, "paused local queue lost bytes");

  port2.postMessage({type: "flow", paused: false});
  await waitFor(() => entry.queuedBytes === 0, "local outbound queue drain");
  await waitFor(() => received.reduce((sum, part) => sum + part.byteLength, 0) === expected.byteLength,
    "local MessagePort delivery");

  const actual = new Uint8Array(expected.byteLength);
  let offset = 0;
  for (const part of received) {
    actual.set(part, offset);
    offset += part.byteLength;
  }
  assert.deepEqual(actual, expected, "local MessagePort byte ordering changed");
  assert.equal(entry.outbound.length, 0, "local outbound array retained processed frames");
  assert.equal(entry.outboundHead, 0, "local outbound head was not reset");
  assert.equal(stats.queuedBytes, 0, "global local outbound byte count did not drain");
  assert.ok(stats.outboundYields >= 2, "local burst did not yield across macrotasks");
  assert.ok(stats.localMessagePortSends > 0, "local send telemetry was not recorded");
  assertBounded(stats, "local MessagePort");
  assert.equal(stats.maxOutboundTurnBytes, stats.outboundByteLimit,
    "oversized local write was not segmented at the strict turn byte limit");

  const turnsBeforeIdlePolls = stats.outboundTurns;
  for (let index = 0; index < 32; index++) {
    assert.equal(bridge.pollInbound(socketId), null);
    await delay(1);
  }
  assert.equal(stats.outboundTurns, turnsBeforeIdlePolls,
    "idle inbound polling scheduled empty outbound callbacks");

  bridge.close(socketId);
  await waitFor(() => controls.some((message) => message && message.type === "close"),
    "scheduled local close control");
  assert.equal(entry.outboundControls.length, 0,
    "local control array retained processed controls");
  port2.close();
  return {
    turns: stats.outboundTurns,
    yields: stats.outboundYields,
    maxFrames: stats.maxOutboundTurnFrames,
    maxBytes: stats.maxOutboundTurnBytes,
    maxMillis: stats.maxOutboundTurnMillis,
    physicalSends: stats.localMessagePortSends,
  };
}

async function runWebSocketSmoke() {
  const sockets = [];
  class FakeWebSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 3;

    constructor(url) {
      this.url = url;
      this.readyState = FakeWebSocket.CONNECTING;
      this.bufferedAmount = 4 * 1024 * 1024;
      this.data = [];
      sockets.push(this);
      setTimeout(() => {
        this.readyState = FakeWebSocket.OPEN;
        if (this.onopen) this.onopen();
      }, 0);
    }

    send(payload) {
      if (typeof payload === "string") {
        const message = JSON.parse(payload);
        assert.equal(message.type, "connect", "WebSocket control ordering changed");
        setTimeout(() => {
          if (this.onmessage) {
            this.onmessage({data: JSON.stringify({
              type: "connected",
              host: message.host,
              port: message.port,
            })});
          }
        }, 0);
        return;
      }
      this.data.push(Uint8Array.from(payload));
    }

    close() {
      this.readyState = FakeWebSocket.CLOSED;
    }
  }

  const socketId = 202;
  const context = createContext(FakeWebSocket);
  const bridge = context.__gaiusNettyBridge;
  const stats = context.__gaiusNetworkStats;
  bridge.open(socketId, "outbound-smoke.invalid", 25565);
  const entry = bridge.channels.get(socketId);
  const socket = sockets[0];
  await waitFor(() => stats.webSocketBackpressureWaits >= 1,
    "WebSocket handshake backpressure wait");
  assert.ok(stats.webSocketBackpressureWaits >= 1,
    "WebSocket handshake did not self-reschedule behind bufferedAmount");
  socket.bufferedAmount = 0;
  await waitFor(() => entry.connected, "fake WebSocket connection");

  const immediateProbe = new Uint8Array([0x26, 0x02, 0x77, 0x06]);
  const turnsBeforeImmediateProbe = stats.outboundTurns;
  const immediateFlushesBeforeProbe = stats.outboundImmediateFlushes;
  assert.equal(bridge.send(socketId, immediateProbe), true);
  assert.equal(stats.outboundTurns, turnsBeforeImmediateProbe,
    "WebSocket send flushed synchronously");
  await Promise.resolve();
  assert.equal(stats.outboundTurns, turnsBeforeImmediateProbe + 1,
    "initial WebSocket flush did not run in the first microtask checkpoint");
  assert.equal(stats.outboundImmediateFlushes, immediateFlushesBeforeProbe + 1,
    "initial WebSocket flush was not classified as immediate");
  assert.equal(entry.queuedBytes, 0, "initial microtask did not drain a small write");
  assert.deepEqual(socket.data.at(-1), immediateProbe,
    "initial microtask changed WebSocket payload bytes");
  socket.data = [];

  const continuationFrameCount = 40;
  const continuationFrameBytes = 4096;
  const continuationMacrotasksBeforeBurst = stats.outboundContinuationMacrotasks;
  const messageChannelFlushesBeforeBurst = stats.outboundMessageChannelFlushes;
  for (let frameIndex = 0; frameIndex < continuationFrameCount; frameIndex++) {
    const frame = new Uint8Array(continuationFrameBytes);
    frame.fill((0x80 + frameIndex) & 0xff);
    assert.equal(bridge.send(socketId, frame), true);
  }
  await Promise.resolve();
  assert.equal(socket.data.length, stats.outboundFrameLimit,
    "initial microtask did not stop at the strict frame budget");
  assert.equal(entry.queuedFrames,
    continuationFrameCount - stats.outboundFrameLimit,
    "initial microtask did not retain the budget remainder");
  assert.equal(stats.outboundContinuationMacrotasks,
    continuationMacrotasksBeforeBurst + 1,
    "budget remainder did not schedule exactly one macrotask continuation");
  assert.equal(stats.outboundMessageChannelFlushes,
    messageChannelFlushesBeforeBurst + 1,
    "budget remainder did not use the low-latency MessageChannel continuation");
  assert.equal(stats.outboundContinuationTimers, 0,
    "non-backpressured budget continuation regressed to a clamped timer");
  await Promise.resolve();
  assert.equal(entry.queuedFrames,
    continuationFrameCount - stats.outboundFrameLimit,
    "budget continuation formed a microtask chain");
  await waitFor(() => entry.queuedBytes === 0,
    "non-backpressured MessageChannel continuation drain");
  assert.equal(socket.data.length, continuationFrameCount,
    "MessageChannel continuation lost WebSocket frames");
  socket.data = [];

  socket.bufferedAmount = 4 * 1024 * 1024;
  const waitsBeforeData = stats.webSocketBackpressureWaits;
  const turnsBeforeBurst = stats.outboundTurns;
  const frameCount = 80;
  const frameBytes = 4096;
  for (let frameIndex = 0; frameIndex < frameCount; frameIndex++) {
    const frame = new Uint8Array(frameBytes);
    frame.fill(frameIndex & 0xff);
    assert.equal(bridge.send(socketId, frame), true);
  }
  assert.equal(stats.outboundTurns, turnsBeforeBurst,
    "burst send flushed synchronously");
  await Promise.resolve();
  assert.equal(stats.outboundTurns, turnsBeforeBurst + 1,
    "burst did not execute exactly one initial microtask turn");
  assert.equal(entry.queuedFrames, frameCount,
    "backpressured initial microtask consumed queued frames");
  await waitFor(() => stats.webSocketBackpressureWaits > waitsBeforeData,
    "WebSocket data backpressure wait");
  assert.ok(stats.webSocketBackpressureWaits > waitsBeforeData,
    "WebSocket data backpressure did not self-reschedule");
  socket.bufferedAmount = 0;
  await waitFor(() => entry.queuedBytes === 0, "WebSocket outbound queue drain");
  assert.equal(socket.data.length, frameCount, "WebSocket queue lost or merged data frames");
  for (let frameIndex = 0; frameIndex < socket.data.length; frameIndex++) {
    const frame = socket.data[frameIndex];
    assert.equal(frame.byteLength, frameBytes);
    assert.equal(frame[0], frameIndex & 0xff, `WebSocket ordering changed at frame ${frameIndex}`);
    assert.equal(frame[frame.length - 1], frameIndex & 0xff,
      `WebSocket frame ${frameIndex} was truncated`);
  }
  assert.equal(entry.outbound.length, 0, "WebSocket outbound array retained processed frames");
  assert.equal(entry.outboundHead, 0, "WebSocket outbound head was not reset");
  assert.equal(stats.queuedBytes, 0, "global WebSocket outbound byte count did not drain");
  assert.ok(stats.outboundYields >= 2, "WebSocket burst did not yield across macrotasks");
  assert.ok(stats.webSocketSends >= frameCount + 1,
    "WebSocket send telemetry omitted control or data frames");
  assert.ok(stats.outboundContinuationMacrotasks >= 2,
    "WebSocket burst continuation did not yield through macrotasks");
  assert.ok(stats.outboundMessageChannelFlushes >= 2,
    "WebSocket burst continuation did not use MessageChannel tasks");
  assert.equal(stats.outboundContinuationTimers, 0,
    "budget continuations unexpectedly used clamped timers");
  assert.ok(stats.outboundTimerFlushes > 0,
    "WebSocket backpressure did not use a timer retry");
  assert.equal(stats.outboundFlushWaitSamples,
    stats.outboundImmediateFlushes + stats.outboundTimerFlushes +
      stats.outboundMessageChannelCallbacks,
    "outbound flush wait accounting lost a scheduled callback");
  assertBounded(stats, "WebSocket");

  const sentBeforeCloseRace = socket.data.length;
  assert.equal(bridge.send(socketId, new Uint8Array([0xde, 0xad, 0xbe, 0xef])), true);
  assert.equal(entry.outboundFlushKind, "microtask",
    "close-race fixture did not have a pending initial microtask");
  const generationBeforeClose = entry.outboundFlushGeneration;
  bridge.close(socketId);
  await Promise.resolve();
  assert.ok(entry.outboundFlushGeneration > generationBeforeClose,
    "close did not invalidate the pending microtask generation");
  assert.equal(entry.outboundFlushScheduled, false,
    "stale close-race microtask remained scheduled");
  assert.equal(socket.data.length, sentBeforeCloseRace,
    "stale close-race microtask sent payload bytes after close");
  return {
    turns: stats.outboundTurns,
    yields: stats.outboundYields,
    backpressureWaits: stats.webSocketBackpressureWaits,
    maxFrames: stats.maxOutboundTurnFrames,
    maxBytes: stats.maxOutboundTurnBytes,
    maxMillis: stats.maxOutboundTurnMillis,
    physicalSends: stats.webSocketSends,
  };
}

const local = await runLocalMessagePortSmoke();
const webSocket = await runWebSocketSmoke();
await delay(20);
console.log(JSON.stringify({ok: true, local, webSocket}));
