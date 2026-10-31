#!/usr/bin/env node

/*
 * Blob fallback ordering model/static smoke.
 *
 * BrowserWebSocketChannel deliberately sets binaryType=\"arraybuffer\", so the
 * ArrayBuffer branch is the normal hot path.  Some WebSocket implementations or
 * adapters can still expose a binary message as Blob.  This fixture models the
 * production per-entry Promise chain for that compatibility path and checks the
 * source contract without starting Java, TeaVM, Chrome, a RelayNode, or a public
 * connection.
 */

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const repository = fileURLToPath(new URL("../..", import.meta.url));
const channelPath = `${repository}/port/overrides/libraries/netty-transport/` +
    "src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java";
const source = await readFile(channelPath, "utf8");

function assertStaticContract() {
    assert.match(source, /ws\.binaryType\s*=\s*['"]arraybuffer['"]/u,
        "WebSocket binaryType contract is missing");

    const onmessageStart = source.indexOf("ws.onmessage = function(event)");
    const onmessageEnd = source.indexOf("ws.onerror = function()", onmessageStart);
    assert.ok(onmessageStart >= 0 && onmessageEnd > onmessageStart,
        "remote WebSocket onmessage body is missing");
    const onmessage = source.slice(onmessageStart, onmessageEnd);
    const arrayBufferBranch = onmessage.indexOf("event.data instanceof ArrayBuffer");
    const blobBranch = onmessage.indexOf("typeof event.data.arrayBuffer === 'function'");
    assert.ok(arrayBufferBranch >= 0 && blobBranch > arrayBufferBranch,
        "Blob fallback must remain after the ArrayBuffer hot path");
    assert.match(onmessage, /const blob = event\.data/u,
        "Blob payload must be captured before it is queued");
    assert.match(onmessage, /const previousBlobChain = entry\.blobArrivalChain/u,
        "Blob fallback must use per-entry chain state");
    assert.match(onmessage, /entry\.blobArrivalChain = previousBlobChain/u,
        "Blob fallback must publish the updated per-entry chain");
    assert.match(onmessage, /return blob\.arrayBuffer\(\)/u,
        "Blob conversion must happen inside the serialized chain");
    assert.match(onmessage, /entry\.ws !== ws/u,
        "Blob completion must reject stale WebSocket identities");
    assert.match(onmessage, /generation !== entry\.webSocketGeneration/u,
        "Blob completion must reject stale WebSocket generations");
    assert.match(onmessage, /entry\.closed/u,
        "Blob completion must reject closed entries");
    assert.match(onmessage, /\.catch\(function\(error\)/u,
        "Blob conversion rejection must be consumed by the chain");
    assert.match(onmessage, /fail\(entry, error &&/u,
        "Blob conversion rejection must fail-close the active entry");
    assert.match(onmessage,
        /deliverInbound\(entry, event\.data, binaryArrivalToken\)/u,
        "ArrayBuffer messages must bypass Blob conversion");

    const connectionStart = source.indexOf("entry.ws = ws;");
    const connectionEnd = source.indexOf("ws.binaryType", connectionStart);
    assert.ok(connectionStart >= 0 && connectionEnd > connectionStart,
        "WebSocket generation setup boundary changed");
    assert.match(source.slice(connectionStart, connectionEnd),
        /entry\.blobArrivalChain = Promise\.resolve\(\)/u,
        "a new WebSocket generation must start a fresh Blob chain");
    assert.match(source,
        /webSocketGeneration:\s*0,\s*blobArrivalChain:\s*Promise\.resolve\(\)/u,
        "new entries must initialize an empty Blob chain");
}

function deferredBlob(label) {
    let resolveValue;
    let rejectValue;
    let started = 0;
    const promise = new Promise((resolve, reject) => {
        resolveValue = resolve;
        rejectValue = reject;
    });
    return {
        label,
        arrayBuffer() {
            started++;
            return promise;
        },
        get started() {
            return started;
        },
        resolve(value = label) {
            resolveValue(value);
        },
        reject(error = new Error(label)) {
            rejectValue(error);
        },
    };
}

class BlobArrivalModel {
    constructor() {
        this.ws = {id: "ws-1"};
        this.generation = 1;
        this.closed = false;
        this.chain = Promise.resolve();
        this.delivered = [];
        this.failures = [];
    }

    isCurrent(ws, generation) {
        return !this.closed && this.ws === ws && this.generation === generation;
    }

    fail(error) {
        if (this.closed) return;
        this.failures.push(error);
        this.closed = true;
    }

    enqueue(blob, arrivalToken = null) {
        const ws = this.ws;
        const generation = this.generation;
        const previous = this.chain && typeof this.chain.then === "function"
            ? this.chain
            : Promise.resolve();
        this.chain = previous
            .catch((previousError) => {
                if (this.isCurrent(ws, generation)) this.fail(previousError);
            })
            .then(() => {
                if (!this.isCurrent(ws, generation)) return null;
                return blob.arrayBuffer();
            })
            .then((buffer) => {
                if (buffer == null || !this.isCurrent(ws, generation)) return;
                this.delivered.push({buffer, arrivalToken});
            })
            .catch((error) => {
                if (this.isCurrent(ws, generation)) this.fail(error);
            });
        return this.chain;
    }

    rotate() {
        this.generation++;
        this.ws = {id: `ws-${this.generation}`};
        this.chain = Promise.resolve();
    }

    close() {
        this.closed = true;
    }
}

const tick = () => new Promise((resolve) => queueMicrotask(resolve));

async function testOutOfOrderCompletion() {
    const model = new BlobArrivalModel();
    const first = deferredBlob("A");
    const second = deferredBlob("B");
    const firstDone = model.enqueue(first, {frameSequence: 1});
    const secondDone = model.enqueue(second, {frameSequence: 2});

    await tick();
    assert.equal(first.started, 1, "the first Blob conversion must start");
    assert.equal(second.started, 0,
        "the second Blob conversion must wait for the first conversion");

    // B would be able to complete first in the old parallel implementation.  The
    // serialized fallback does not even start B until A has completed.
    first.resolve("A");
    await firstDone;
    await tick();
    assert.equal(second.started, 1,
        "the next Blob conversion must start after the previous one completes");
    second.resolve("B");
    await secondDone;
    assert.deepEqual(model.delivered.map((item) => item.buffer), ["A", "B"],
        "Blob delivery must preserve WebSocket message order");
    assert.equal(model.failures.length, 0, "ordered Blob delivery must not fail");
}

async function testGenerationIsolation() {
    const model = new BlobArrivalModel();
    const oldBlob = deferredBlob("old");
    const oldDone = model.enqueue(oldBlob, {frameSequence: 11});
    await tick();
    assert.equal(oldBlob.started, 1, "old-generation Blob conversion did not start");

    model.rotate();
    const newBlob = deferredBlob("new");
    const newDone = model.enqueue(newBlob, {frameSequence: 12});
    newBlob.resolve("new");
    await newDone;
    assert.deepEqual(model.delivered.map((item) => item.buffer), ["new"],
        "a new generation must not receive an old Blob");

    oldBlob.resolve("old");
    await oldDone;
    assert.deepEqual(model.delivered.map((item) => item.buffer), ["new"],
        "late old-generation conversion must remain discarded");
}

async function testCloseIsolation() {
    const model = new BlobArrivalModel();
    const blob = deferredBlob("closed");
    const done = model.enqueue(blob);
    await tick();
    model.close();
    blob.resolve("closed");
    await done;
    assert.deepEqual(model.delivered, [],
        "a Blob completing after close must not enter the inbound queue");
    assert.equal(model.failures.length, 0,
        "normal close must not be reported as a conversion failure");
}

async function testRejectedConversionAndChainRecovery() {
    const model = new BlobArrivalModel();
    const rejected = deferredBlob("rejected");
    const rejectedDone = model.enqueue(rejected);
    await tick();
    rejected.reject(new Error("decode failed"));
    await rejectedDone;
    await tick();
    assert.equal(model.closed, true,
        "a current-generation Blob conversion rejection must fail-close the entry");
    assert.equal(model.failures.length, 1,
        "a conversion rejection must be recorded exactly once");

    // The rejected chain itself must settle successfully from the caller's point
    // of view.  A later enqueue must also settle rather than creating an
    // unhandled-rejection tail, even though the entry is already closed.
    const later = deferredBlob("later");
    const laterDone = model.enqueue(later);
    await laterDone;
    assert.equal(later.started, 0,
        "a fail-closed entry must not convert later Blob data");
    assert.equal(model.failures.length, 1,
        "chain recovery must not duplicate the failure");
}

function testArrayBufferBypass() {
    const model = new BlobArrivalModel();
    const chainBefore = model.chain;
    const arrayBuffer = new ArrayBuffer(3);
    const delivered = [];
    const onMessage = (data) => {
        if (data instanceof ArrayBuffer) {
            delivered.push(data);
            return "arraybuffer";
        }
        throw new Error("test only models the binary hot path");
    };
    assert.equal(onMessage(arrayBuffer), "arraybuffer",
        "ArrayBuffer should use the direct hot path");
    assert.deepEqual(delivered, [arrayBuffer],
        "ArrayBuffer must be delivered without Blob conversion");
    assert.equal(model.chain, chainBefore,
        "the ArrayBuffer bypass must not replace the per-entry Blob chain");
}

assertStaticContract();
await testOutOfOrderCompletion();
await testGenerationIsolation();
await testCloseIsolation();
await testRejectedConversionAndChainRecovery();
testArrayBufferBypass();

console.log(JSON.stringify({
    status: "pass",
    schema: "gaius.browser-blob-arrival-order-smoke.v1",
    static: {
        arrayBufferHotPath: true,
        perEntryPromiseChain: true,
        wsGenerationGuard: true,
        closedGuard: true,
        rejectionFailClosed: true,
    },
    model: {
        outOfOrderCompletion: "ordered",
        generationIsolation: "pass",
        closeIsolation: "pass",
        rejectionChainRecovery: "pass",
        arrayBufferBypass: "pass",
    },
}));
