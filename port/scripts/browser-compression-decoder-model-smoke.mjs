#!/usr/bin/env node

import assert from "node:assert/strict";

// This is a pure state-machine contract. It intentionally does not import or patch Java,
// TeaVM, Netty, a browser, or a real inflater. A green result proves only that the proposed
// cooperative decoder semantics can be modeled without violating FIFO, ownership, or bounds.
const KIB = 1024;
const MAX_UNCOMPRESSED_BYTES = 8 * 1024 * KIB;
const MAX_COMPRESSED_BYTES = 2 * 1024 * KIB;
const DEFAULT_OUTPUT_QUANTUM_BYTES = 16 * KIB;
const DEFAULT_MAX_QUEUE_FRAMES = 8;
const DEFAULT_MAX_QUEUE_BYTES = 128 * KIB;

function assertInteger(value, label) {
  assert.equal(Number.isInteger(value), true, `${label} must be an integer`);
}

function frame(id, declaredLength, compressedLength, options = {}) {
  return {
    id,
    generation: options.generation ?? 1,
    declaredLength,
    compressedLength,
    fault: options.fault ?? null,
  };
}

/**
 * Small deterministic model of the proposed browser decoder boundary.
 *
 * One queued frame is emitted only after its complete declared output exists. Each turn is
 * bounded by turnBudgetBytes and each simulated inflater call is bounded by outputQuantumBytes.
 * Platform.schedule is represented by a queue of zero-delay tasks; executor.execute is not used.
 */
class CooperativeCompressionDecoderModel {
  constructor({
    generation = 1,
    turnBudgetBytes = 32 * KIB,
    outputQuantumBytes = DEFAULT_OUTPUT_QUANTUM_BYTES,
    maxQueueFrames = DEFAULT_MAX_QUEUE_FRAMES,
    maxQueueBytes = DEFAULT_MAX_QUEUE_BYTES,
  } = {}) {
    assertInteger(generation, "generation");
    assertInteger(turnBudgetBytes, "turnBudgetBytes");
    assertInteger(outputQuantumBytes, "outputQuantumBytes");
    assertInteger(maxQueueFrames, "maxQueueFrames");
    assertInteger(maxQueueBytes, "maxQueueBytes");
    assert.ok(generation > 0, "generation must be positive");
    assert.ok(turnBudgetBytes > 0, "turnBudgetBytes must be positive");
    assert.ok(outputQuantumBytes > 0, "outputQuantumBytes must be positive");
    assert.ok(maxQueueFrames > 0, "maxQueueFrames must be positive");
    assert.ok(maxQueueBytes > 0, "maxQueueBytes must be positive");

    this.generation = generation;
    this.turnBudgetBytes = turnBudgetBytes;
    this.outputQuantumBytes = outputQuantumBytes;
    this.maxQueueFrames = maxQueueFrames;
    this.maxQueueBytes = maxQueueBytes;

    this.closed = false;
    this.failed = false;
    this.failure = null;
    this.queue = [];
    this.active = null;
    this.tasks = [];
    this.scheduledGenerations = new Set();
    this.retainedFrameBytes = 0;
    this.outputPackets = [];
    this.turns = [];
    this.inflateCalls = [];
    this.scheduleTrace = [];
    this.releaseTrace = [];
    this.backpressureTrace = [];
    this.staleTaskCount = 0;
    this.channelReadCompleteCount = 0;
  }

  get queuedFrameCount() {
    return this.queue.length + (this.active == null ? 0 : 1);
  }

  get queuedBytes() {
    return this.retainedFrameBytes;
  }

  get hasPendingWork() {
    return this.active != null || this.queue.length > 0;
  }

  validateFrame(candidate) {
    if (candidate == null || typeof candidate !== "object") {
      return "FRAME_NOT_OBJECT";
    }
    if (typeof candidate.id !== "string" || candidate.id.length === 0) {
      return "FRAME_ID_INVALID";
    }
    if (!Number.isInteger(candidate.generation) || candidate.generation <= 0) {
      return "FRAME_GENERATION_INVALID";
    }
    if (!Number.isInteger(candidate.declaredLength) || candidate.declaredLength <= 0) {
      return "DECLARED_LENGTH_INVALID";
    }
    if (candidate.declaredLength > MAX_UNCOMPRESSED_BYTES) {
      return "DECLARED_LENGTH_LIMIT";
    }
    if (!Number.isInteger(candidate.compressedLength) || candidate.compressedLength <= 0) {
      return "COMPRESSED_LENGTH_INVALID";
    }
    if (candidate.compressedLength > MAX_COMPRESSED_BYTES) {
      return "COMPRESSED_LENGTH_LIMIT";
    }
    return null;
  }

  enqueue(candidate) {
    if (this.closed || this.failed) {
      this.backpressureTrace.push({
        id: candidate?.id ?? null,
        accepted: false,
        reason: this.closed ? "CLOSED" : "FAILED",
      });
      return false;
    }

    const validationError = this.validateFrame(candidate);
    if (validationError != null) {
      this.failClosed(validationError);
      return false;
    }
    if (candidate.generation !== this.generation) {
      this.releaseTrace.push({
        id: candidate.id,
        generation: candidate.generation,
        reason: "STALE_GENERATION",
        bytes: 0,
      });
      return false;
    }
    if (this.queuedFrameCount >= this.maxQueueFrames
        || this.queuedBytes + candidate.declaredLength > this.maxQueueBytes) {
      this.backpressureTrace.push({
        id: candidate.id,
        accepted: false,
        reason: "QUEUE_LIMIT",
        queuedFrameCount: this.queuedFrameCount,
        queuedBytes: this.queuedBytes,
      });
      return false;
    }

    this.queue.push(candidate);
    this.retainedFrameBytes += candidate.declaredLength;
    this.scheduleForGeneration(this.generation);
    return true;
  }

  scheduleForGeneration(generation) {
    if (this.closed || this.failed || this.scheduledGenerations.has(generation)) {
      return;
    }
    this.scheduledGenerations.add(generation);
    const task = {generation, delay: 0};
    this.tasks.push(task);
    this.scheduleTrace.push({
      generation,
      delay: task.delay,
      pendingTasks: this.tasks.length,
    });
  }

  activateNextFrame() {
    if (this.active != null || this.queue.length === 0) {
      return;
    }
    const candidate = this.queue.shift();
    this.active = {
      frame: candidate,
      produced: 0,
      inputRemaining: candidate.compressedLength,
    };
  }

  inflateStep() {
    assert.notEqual(this.active, null, "inflateStep requires an active frame");
    const state = this.active;
    const candidate = state.frame;
    const remainingOutput = candidate.declaredLength - state.produced;

    if (candidate.fault === "malformed") {
      return {produced: 0, consumed: 0, error: "DATA_FORMAT"};
    }
    if (candidate.fault === "no-progress") {
      return {produced: 0, consumed: 0};
    }
    if (candidate.fault === "overshoot") {
      return {produced: remainingOutput + 1, consumed: 1};
    }

    const produced = Math.min(this.outputQuantumBytes, remainingOutput);
    // Input consumption is only a bounded state-machine signal here. The model deliberately does
    // not pretend to implement DEFLATE; valid streams may have buffered inflater state after all
    // source bytes have been consumed, so output progress is the forward-progress invariant.
    const consumed = remainingOutput === produced
      ? state.inputRemaining
      : Math.min(1, state.inputRemaining);
    return {produced, consumed};
  }

  finishActiveFrame() {
    assert.notEqual(this.active, null, "finishActiveFrame requires an active frame");
    const state = this.active;
    const candidate = state.frame;
    assert.equal(state.produced, candidate.declaredLength,
      `frame ${candidate.id} completed with an incorrect output length`);
    this.outputPackets.push(candidate.id);
    this.releaseFrame(candidate, "FRAME_COMPLETE");
    this.active = null;
  }

  releaseFrame(candidate, reason) {
    const bytes = candidate.declaredLength;
    this.retainedFrameBytes -= bytes;
    assert.ok(this.retainedFrameBytes >= 0, "retained frame bytes underflow");
    this.releaseTrace.push({
      id: candidate.id,
      generation: candidate.generation,
      reason,
      bytes,
    });
  }

  failClosed(reason) {
    if (this.failed) {
      return;
    }
    this.failed = true;
    this.failure = {reason};
    if (this.active != null) {
      this.releaseFrame(this.active.frame, `FAIL_CLOSED:${reason}`);
      this.active = null;
    }
    while (this.queue.length > 0) {
      this.releaseFrame(this.queue.shift(), `FAIL_CLOSED:${reason}`);
    }
    assert.equal(this.retainedFrameBytes, 0,
      `fail-closed ${reason} must release all retained frames`);
  }

  runTurn(taskGeneration) {
    if (this.closed || this.failed || taskGeneration !== this.generation) {
      this.staleTaskCount++;
      return;
    }

    let turnWork = 0;
    let inflateCallCount = 0;
    const beforePackets = this.outputPackets.length;
    while (turnWork < this.turnBudgetBytes && this.hasPendingWork) {
      this.activateNextFrame();
      const result = this.inflateStep();
      inflateCallCount++;
      this.inflateCalls.push({
        id: this.active.frame.id,
        generation: taskGeneration,
        requested: Math.min(this.outputQuantumBytes,
          this.active.frame.declaredLength - this.active.produced),
        produced: result.produced,
        consumed: result.consumed,
      });

      if (result.error != null) {
        this.failClosed(result.error);
        break;
      }
      if (result.produced < 0 || result.consumed < 0
          || result.produced > this.active.frame.declaredLength - this.active.produced) {
        this.failClosed("OUTPUT_LENGTH_OVERFLOW");
        break;
      }
      if (result.produced === 0 && result.consumed === 0) {
        this.failClosed("NO_PROGRESS");
        break;
      }

      this.active.produced += result.produced;
      this.active.inputRemaining = Math.max(0, this.active.inputRemaining - result.consumed);
      turnWork += result.produced;
      if (this.active.produced === this.active.frame.declaredLength) {
        this.finishActiveFrame();
      }
    }

    this.turns.push({
      generation: taskGeneration,
      workBytes: turnWork,
      inflateCallCount,
      packetsBefore: beforePackets,
      packetsAfter: this.outputPackets.length,
    });

    if (this.failed || this.closed) {
      return;
    }
    if (this.hasPendingWork) {
      this.scheduleForGeneration(taskGeneration);
    } else {
      this.channelReadCompleteCount++;
    }
  }

  runNextTurn() {
    if (this.tasks.length === 0) {
      return false;
    }
    const task = this.tasks.shift();
    this.scheduledGenerations.delete(task.generation);
    this.runTurn(task.generation);
    return true;
  }

  drain() {
    let turns = 0;
    while (this.runNextTurn()) {
      turns++;
      assert.ok(turns < 10000, "model continuation loop did not converge");
    }
    return turns;
  }

  close(reason = "CLOSE") {
    if (this.closed) {
      return;
    }
    this.closed = true;
    if (this.active != null) {
      this.releaseFrame(this.active.frame, reason);
      this.active = null;
    }
    while (this.queue.length > 0) {
      this.releaseFrame(this.queue.shift(), reason);
    }
    assert.equal(this.retainedFrameBytes, 0, "close must release all retained frame bytes");
  }

  advanceGeneration(nextGeneration = this.generation + 1) {
    assertInteger(nextGeneration, "nextGeneration");
    assert.ok(nextGeneration > this.generation, "generation must move forward");
    if (this.active != null) {
      this.releaseFrame(this.active.frame, "GENERATION_CHANGE");
      this.active = null;
    }
    while (this.queue.length > 0) {
      this.releaseFrame(this.queue.shift(), "GENERATION_CHANGE");
    }
    this.generation = nextGeneration;
    assert.equal(this.retainedFrameBytes, 0,
      "generation change must release all old-generation frame bytes");
  }
}

function assertBoundedTurns(model, budget) {
  assert.ok(model.turns.length > 1,
    `a frame crossing ${budget} bytes must require multiple browser turns`);
  assert.ok(model.turns.every((turn) => turn.workBytes <= budget),
    `turn work exceeded ${budget} bytes`);
  assert.ok(model.inflateCalls.every((call) => call.produced <= model.outputQuantumBytes),
    "an inflater call exceeded its output quantum");
  assert.ok(model.scheduleTrace.every((entry) => entry.delay === 0),
    "every continuation must use Platform.schedule(0)");
  assert.ok(model.scheduleTrace.length >= model.turns.length,
    "each continuation turn must have a zero-delay schedule record");
}

function runFifoBudgetCase(turnBudgetBytes) {
  const model = new CooperativeCompressionDecoderModel({turnBudgetBytes});
  assert.equal(model.enqueue(frame("A", 48 * KIB, 12 * KIB)), true);
  assert.equal(model.enqueue(frame("B", 20 * KIB, 8 * KIB)), true);
  assert.equal(model.tasks.length, 1,
    "multiple FIFO frames must coalesce onto one pending continuation");
  const turns = model.drain();

  assert.ok(turns >= 3, "FIFO case must cross multiple bounded turns");
  assert.deepEqual(model.outputPackets, ["A", "B"],
    "complete packet output must remain FIFO");
  assert.equal(model.retainedFrameBytes, 0, "completed frames must release their ownership");
  assert.equal(model.queue.length, 0);
  assert.equal(model.active, null);
  assert.equal(model.failure, null);
  assert.equal(model.channelReadCompleteCount, 1,
    "read-complete must be deferred until the FIFO queue drains");
  assertBoundedTurns(model, turnBudgetBytes);
  return {
    turnBudgetBytes,
    turns: model.turns.length,
    maxTurnWorkBytes: Math.max(...model.turns.map((turn) => turn.workBytes)),
    scheduleCalls: model.scheduleTrace.length,
    outputPackets: model.outputPackets,
  };
}

function runBackpressureCase() {
  const model = new CooperativeCompressionDecoderModel({
    turnBudgetBytes: 16 * KIB,
    maxQueueFrames: 3,
    maxQueueBytes: 64 * KIB,
  });
  assert.equal(model.enqueue(frame("A", 32 * KIB, 4 * KIB)), true);
  assert.equal(model.enqueue(frame("B", 24 * KIB, 4 * KIB)), true);
  assert.equal(model.enqueue(frame("C", 16 * KIB, 4 * KIB)), false,
    "queue byte limit must apply before retaining a frame");
  assert.equal(model.queuedBytes <= 64 * KIB, true);
  assert.equal(model.retainedFrameBytes, 56 * KIB);
  assert.equal(model.backpressureTrace.at(-1).reason, "QUEUE_LIMIT");
  model.drain();
  assert.deepEqual(model.outputPackets, ["A", "B"]);
  assert.equal(model.outputPackets.includes("C"), false,
    "a backpressure-rejected frame must not enter the output stream");
  return {
    maxQueueFrames: model.maxQueueFrames,
    maxQueueBytes: model.maxQueueBytes,
    queuedBytesAtReject: model.backpressureTrace[0].queuedBytes,
    rejectedFrames: model.backpressureTrace.length,
  };
}

function runCloseCase() {
  const model = new CooperativeCompressionDecoderModel({turnBudgetBytes: 16 * KIB});
  assert.equal(model.enqueue(frame("close-A", 48 * KIB, 8 * KIB)), true);
  assert.equal(model.enqueue(frame("close-B", 16 * KIB, 4 * KIB)), true);
  assert.ok(model.retainedFrameBytes > 0);
  model.close("CHANNEL_CLOSE");
  assert.equal(model.closed, true);
  assert.equal(model.retainedFrameBytes, 0);
  assert.equal(model.queue.length, 0);
  assert.equal(model.active, null);
  assert.equal(model.outputPackets.length, 0,
    "close before the scheduled turn must emit no packet");
  model.drain();
  assert.equal(model.outputPackets.length, 0,
    "a stale post-close continuation must be a no-op");
  assert.deepEqual(model.releaseTrace.map((entry) => entry.id), ["close-A", "close-B"]);
  return {
    releasedFrames: model.releaseTrace.length,
    releasedBytes: model.releaseTrace.reduce((sum, entry) => sum + entry.bytes, 0),
    staleTasks: model.staleTaskCount,
  };
}

function runGenerationCase() {
  const model = new CooperativeCompressionDecoderModel({generation: 1, turnBudgetBytes: 16 * KIB});
  assert.equal(model.enqueue(frame("old", 48 * KIB, 8 * KIB, {generation: 1})), true);
  model.advanceGeneration(2);
  assert.equal(model.enqueue(frame("new", 20 * KIB, 4 * KIB, {generation: 2})), true);
  model.drain();
  assert.deepEqual(model.outputPackets, ["new"],
    "a stale generation must never reach the new connection");
  assert.equal(model.staleTaskCount, 1,
    "the old scheduled continuation must be observed and ignored");
  assert.equal(model.retainedFrameBytes, 0);
  assert.equal(model.releaseTrace[0].reason, "GENERATION_CHANGE");
  return {
    oldGenerationReleased: model.releaseTrace[0].id,
    outputPackets: model.outputPackets,
    staleTasks: model.staleTaskCount,
  };
}

function runFailClosedCase(name, candidate, expectedReason, runTurn = true, expectedAccepted = true) {
  const model = new CooperativeCompressionDecoderModel({turnBudgetBytes: 16 * KIB});
  assert.equal(model.enqueue(candidate), expectedAccepted,
    `${name} must have the expected admission result`);
  if (runTurn) {
    model.drain();
  }
  assert.equal(model.failed, true, `${name} must enter fail-closed state`);
  assert.equal(model.failure.reason, expectedReason,
    `${name} must use the expected fail-closed reason`);
  assert.equal(model.retainedFrameBytes, 0, `${name} must release retained bytes`);
  assert.equal(model.outputPackets.length, 0, `${name} must emit no packet`);
  return {name, reason: model.failure.reason, releaseCount: model.releaseTrace.length};
}

function runMalformedCases() {
  const cases = [
    ["uncompressed-limit", frame("too-large", MAX_UNCOMPRESSED_BYTES + 1, 1),
      "DECLARED_LENGTH_LIMIT", false, false],
    ["compressed-limit", frame("compressed-too-large", 1, MAX_COMPRESSED_BYTES + 1),
      "COMPRESSED_LENGTH_LIMIT", false, false],
    ["no-progress", frame("stuck", 20 * KIB, 4 * KIB, {fault: "no-progress"}),
      "NO_PROGRESS", true],
    ["malformed-stream", frame("bad-zlib", 20 * KIB, 4 * KIB, {fault: "malformed"}),
      "DATA_FORMAT", true],
    ["output-overshoot", frame("overshoot", 20 * KIB, 4 * KIB, {fault: "overshoot"}),
      "OUTPUT_LENGTH_OVERFLOW", true],
  ];
  return cases.map(([name, candidate, reason, runTurn, expectedAccepted]) =>
    runFailClosedCase(name, candidate, reason, runTurn, expectedAccepted));
}

const fifoCases = [runFifoBudgetCase(16 * KIB), runFifoBudgetCase(32 * KIB)];
const backpressure = runBackpressureCase();
const close = runCloseCase();
const generation = runGenerationCase();
const failClosed = runMalformedCases();

const result = {
  modelOnly: true,
  productCooperativeDecodeImplemented: false,
  strictGateChanged: false,
  browserSchedulingPrimitive: "Platform.schedule(0)",
  maxUncompressedBytes: MAX_UNCOMPRESSED_BYTES,
  maxCompressedBytes: MAX_COMPRESSED_BYTES,
  outputQuantumBytes: DEFAULT_OUTPUT_QUANTUM_BYTES,
  fifoBudgetCases: fifoCases,
  backpressure,
  close,
  generation,
  failClosed,
};

console.log("Browser compression decoder model smoke: contract OK (model only)");
console.log(JSON.stringify(result, null, 2));
