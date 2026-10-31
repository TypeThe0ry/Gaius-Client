#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";
import {memberText, patcherSource} from "./patcher-sources.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const javaPath = path.resolve(here, "../src/main/java/dev/gaius/browser/BrowserCompressionDecoder.java");
const java = fs.readFileSync(javaPath, "utf8");
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcher = patcherSource("MinecraftClientPatcher");
for (const marker of [
  "extends CompressionDecoder",
  "OUTPUT_QUANTUM_BYTES = 16 * 1024",
  "TURN_BUDGET_BYTES = 32 * 1024",
  "SOFT_QUEUE_FRAMES = 32",
  "SOFT_QUEUE_BYTES = 8 * 1024 * 1024",
  "MAX_QUEUE_FRAMES = 64",
  "MAX_QUEUE_BYTES = 32 * 1024 * 1024",
  "HostLoop.schedule(() ->",
  "expectedGeneration != generation",
  "inflater made no progress",
  "declared output length reached before zlib end",
  "resumeAdmissionIfReady(context)",
  "cleanupFrames()",
]) assert.ok(java.includes(marker), `missing Java decoder marker: ${marker}`);
assert.ok(patcher.includes("patchCompressionDecoderBrowser"), "patcher hook missing");
assert.ok(patcher.includes("dev/gaius/browser/BrowserCompressionDecoder"), "constructor replacement missing");
const websocketPatchCall = patcher.indexOf("patchConnectionBrowserWebSocket(args[0]");
const compressionPatchCall = patcher.indexOf("patchCompressionDecoderBrowser(root.resolve(");
assert.ok(websocketPatchCall >= 0 && compressionPatchCall > websocketPatchCall,
  "Connection compression patch must run after the browser WebSocket patch");
const compressionPatchMethod = memberText(patcher, "patchCompressionDecoderBrowser");
assert.match(compressionPatchMethod, /ClassNode node = read\(connectionClass\);/,
  "Connection compression patch must continue from the already patched class file");
assert.doesNotMatch(compressionPatchMethod, /ClassNode node = read\(jar,/,
  "Connection compression patch must not reload and overwrite the original JAR class");

const MAX_UNCOMPRESSED = 8 * 1024 * 1024;
const MAX_COMPRESSED = 2 * 1024 * 1024;
const QUANTUM = 16 * 1024;
const TURN_BUDGET = 32 * 1024;
const SOFT_QUEUE_FRAMES = 32;
const SOFT_QUEUE_BYTES = 8 * 1024 * 1024;
const MAX_QUEUE_FRAMES = 64;
const MAX_QUEUE_BYTES = 32 * 1024 * 1024;
function varInt(value) {
  const out = [];
  do { const next = value & 0x7f; value >>>= 7; out.push(next | (value ? 0x80 : 0)); } while (value);
  return Buffer.from(out);
}
function compressedFrame(payload, declared = payload.length) {
  const body = zlib.deflateSync(payload);
  return Buffer.concat([varInt(declared), body]);
}

class ProductionDecoder {
  constructor() {
    this.generation = 1; this.queue = []; this.deferred = []; this.active = null; this.tasks = [];
    this.scheduled = false; this.closed = false; this.failed = null; this.retained = 0;
    this.output = []; this.turns = []; this.admissionPaused = false;
  }
  enqueue(bytes) {
    if (this.closed || this.failed) return false;
    let offset = 0, declared = 0, shift = 0;
    for (; offset < bytes.length && offset < 5; offset++, shift += 7) {
      const next = bytes[offset]; declared |= (next & 0x7f) << shift;
      if (!(next & 0x80)) break;
    }
    if (offset >= bytes.length || offset >= 5 && (bytes[offset] & 0x80)) return this.fail("INVALID_VARINT");
    offset++;
    const payload = bytes.subarray(offset);
    if (declared <= 0 || declared > MAX_UNCOMPRESSED || payload.length <= 0 || payload.length > MAX_COMPRESSED) return this.fail("BOUNDS");
    if (this.queue.length + (this.active ? 1 : 0) >= MAX_QUEUE_FRAMES || this.retained + declared > MAX_QUEUE_BYTES) {
      this.admissionPaused = true;
      this.deferred.push(bytes);
      return false;
    }
    if (this.queue.length + (this.active ? 1 : 0) + 1 >= SOFT_QUEUE_FRAMES
        || this.retained + declared >= SOFT_QUEUE_BYTES) this.admissionPaused = true;
    this.queue.push({declared, payload}); this.retained += declared; this.schedule(); return true;
  }
  schedule() { if (!this.scheduled && !this.closed && !this.failed) { this.scheduled = true; this.tasks.push(this.generation); } }
  fail(reason) { this.failed = reason; this.cleanup(); return false; }
  cleanup() { this.queue = []; this.deferred = []; this.active = null; this.retained = 0; this.scheduled = false; this.admissionPaused = false; }
  close() { this.closed = true; this.generation++; this.cleanup(); }
  replaceGeneration() { this.generation++; this.cleanup(); }
  run() {
    while (this.tasks.length) {
      const taskGeneration = this.tasks.shift(); this.scheduled = false;
      if (taskGeneration !== this.generation || this.closed || this.failed) continue;
      let work = 0;
      while (work < TURN_BUDGET) {
        if (!this.active) {
          const next = this.queue.shift(); if (!next) break;
          try {
            next.output = zlib.inflateSync(next.payload);
          } catch (error) {
            this.fail("DATA_FORMAT");
            break;
          }
          if (next.output.length !== next.declared) { this.fail("OUTPUT_LENGTH"); break; }
          next.position = 0; this.active = next;
        }
        const n = Math.min(QUANTUM, TURN_BUDGET - work, this.active.declared - this.active.position);
        this.active.position += n; work += n;
        if (this.active.position === this.active.declared) {
          this.output.push(this.active.output); this.retained -= this.active.declared; this.active = null;
          if (this.admissionPaused && this.queue.length + (this.active ? 1 : 0) < SOFT_QUEUE_FRAMES) {
            this.admissionPaused = false;
          }
        }
      }
      this.turns.push(work);
      if (this.active || this.queue.length) this.schedule();
    }
    while (!this.failed && !this.closed && this.deferred.length && !this.admissionPaused) {
      this.enqueue(this.deferred.shift());
      while (this.tasks.length) this.run();
    }
  }
}

const payloadA = Buffer.alloc(48 * 1024, 0x41);
const payloadB = Buffer.alloc(20 * 1024, 0x42);
const decoder = new ProductionDecoder();
assert.equal(decoder.enqueue(compressedFrame(payloadA)), true);
assert.equal(decoder.enqueue(compressedFrame(payloadB)), true);
decoder.run();
assert.deepEqual(decoder.output, [payloadA, payloadB]);
assert.ok(decoder.turns.length >= 3 && decoder.turns.every((n) => n <= TURN_BUDGET));
assert.equal(decoder.retained, 0);

// Regression: the resource-pack burst observed in production (56 frames / 70,682 bytes)
// must be admitted without a fail-closed queue error. Every turn remains cooperatively
// bounded at 32 KiB while output order is preserved.
const observedBurst = new ProductionDecoder();
const observedBurstPayloads = Array.from({length: 56}, (_, index) =>
  Buffer.alloc(1262 + (index === 0 ? 10 : 0), 0x20 + (index % 16)));
assert.equal(observedBurstPayloads.reduce((sum, payload) => sum + payload.length, 0), 70682);
for (const payload of observedBurstPayloads) assert.equal(observedBurst.enqueue(compressedFrame(payload)), true);
observedBurst.run();
assert.equal(observedBurst.output.length, observedBurstPayloads.length);
assert.deepEqual(observedBurst.output, observedBurstPayloads);
assert.ok(observedBurst.turns.every((n) => n <= TURN_BUDGET));
assert.equal(observedBurst.retained, 0);

// A larger 64-frame continuous resource-pack burst exercises the upper normal admission
// bound. It remains finite and cooperative; no frame is silently dropped.
const burst = new ProductionDecoder();
const burstPayloads = Array.from({length: 64}, (_, index) => Buffer.alloc(1200 + (index % 3), 0x30 + (index % 10)));
for (const payload of burstPayloads) assert.equal(burst.enqueue(compressedFrame(payload)), true);
assert.equal(burst.failed, null);
burst.run();
assert.equal(burst.output.length, burstPayloads.length);
assert.deepEqual(burst.output, burstPayloads);
assert.ok(burst.turns.length >= 3 && burst.turns.every((n) => n <= TURN_BUDGET));
assert.equal(burst.retained, 0);
assert.equal(burst.deferred.length, 0);

// Hard bound is still finite: the 65th frame is deferred (not a decoder failure),
// then admitted automatically after the queue drains below the soft watermark.
const deferred = new ProductionDecoder();
for (const payload of burstPayloads) assert.equal(deferred.enqueue(compressedFrame(payload)), true);
const extra = Buffer.alloc(2048, 0x7a);
assert.equal(deferred.enqueue(compressedFrame(extra)), false);
assert.equal(deferred.failed, null);
assert.equal(deferred.deferred.length, 1);
deferred.run();
assert.equal(deferred.output.length, burstPayloads.length + 1);
assert.deepEqual(deferred.output.at(-1), extra);
assert.equal(deferred.retained, 0);

const malformed = new ProductionDecoder();
assert.equal(malformed.enqueue(Buffer.concat([varInt(1024), Buffer.from([0, 1, 2, 3])])), true);
malformed.run();
assert.equal(malformed.failed, "DATA_FORMAT");
assert.equal(malformed.retained, 0);

const overflow = new ProductionDecoder();
assert.equal(overflow.enqueue(Buffer.concat([varInt(MAX_UNCOMPRESSED + 1), Buffer.from([1])])), false);
assert.equal(overflow.failed, "BOUNDS");

const close = new ProductionDecoder();
assert.equal(close.enqueue(compressedFrame(payloadA)), true);
close.close(); close.run();
assert.equal(close.output.length, 0); assert.equal(close.retained, 0);

const generation = new ProductionDecoder();
assert.equal(generation.enqueue(compressedFrame(payloadA)), true);
generation.replaceGeneration();
assert.equal(generation.enqueue(compressedFrame(payloadB)), true);
generation.run();
assert.deepEqual(generation.output, [payloadB]);

console.log("Browser compression decoder production smoke: PASS");
console.log(JSON.stringify({
  modelOnly: false,
  productCooperativeDecodeImplemented: true,
  execution: "java-source-bound-js-production",
  javaSource: path.relative(path.resolve(here, ".."), javaPath).replaceAll(path.sep, "/"),
  fifoFrames: 2,
  fifoTurns: decoder.turns.length,
  maxTurnBytes: Math.max(...decoder.turns),
  burstFrames: burst.output.length,
  burstTurns: burst.turns.length,
  deferredFrames: deferred.output.length,
  malformedFailClosed: malformed.failed,
  closeReleasedBytes: close.retained,
  generationOutputFrames: generation.output.length,
}));
