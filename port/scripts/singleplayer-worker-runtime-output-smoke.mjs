import assert from "node:assert/strict";
import {EventEmitter} from "node:events";
import {
  effectiveExitCode,
  writeChunkAndDrain,
} from "./singleplayer-worker-runtime-output.mjs";

class DelayedBackpressureStream extends EventEmitter {
  constructor() {
    super();
    this.chunks = [];
    this.callback = undefined;
  }

  write(chunk, callback) {
    this.chunks.push(Buffer.from(chunk));
    this.callback = callback;
    return false;
  }

  releaseDrain() {
    this.emit("drain");
  }

  completeWrite() {
    this.callback?.();
  }
}

class ErrorStream extends EventEmitter {
  write(_chunk, callback) {
    queueMicrotask(() => callback(new Error("simulated stdout failure")));
    return true;
  }
}

const finalJson = JSON.stringify({events: [{type: "protocol-final"}], tail: "complete"}) + "\n";
const stream = new DelayedBackpressureStream();
let settled = false;
const writePromise = writeChunkAndDrain(stream, finalJson, {timeoutMs: 500});
writePromise.then(() => { settled = true; });
await new Promise((resolve) => setTimeout(resolve, 10));
assert.equal(settled, false, "final output settled before stdout backpressure released");
stream.releaseDrain();
await new Promise((resolve) => setTimeout(resolve, 10));
assert.equal(settled, false, "final output settled before the Writable callback completed");
stream.completeWrite();
await writePromise;
assert.equal(Buffer.concat(stream.chunks).toString(), finalJson,
  "final JSON tail was not fully written before completion");

await assert.rejects(
  writeChunkAndDrain(new ErrorStream(), finalJson, {timeoutMs: 500}),
  /simulated stdout failure/,
  "stdout write callback errors must fail closed",
);
assert.equal(effectiveExitCode(0, true), 1,
  "a successful requested exit must become nonzero when final output fails");
assert.equal(effectiveExitCode(2, true), 2,
  "an existing nonzero exit reason must be preserved after output failure");
assert.equal(effectiveExitCode(0, false), 0,
  "a successful complete output must preserve zero");

console.log("singleplayer-worker-runtime-output-smoke: PASS");
