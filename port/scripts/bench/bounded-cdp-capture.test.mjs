import assert from "node:assert/strict";
import {test} from "node:test";
import {mkdtemp, readFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {BoundedCdpCapture} from "./bounded-cdp-capture.mjs";

class FakeConn {
  constructor() { this.listeners = []; this.calls = []; this.stream = "trace-stream"; }
  async send(method, params, sessionId) {
    this.calls.push({method, params, sessionId});
    if (method === "Tracing.end") queueMicrotask(() => this.listeners.forEach(fn => fn({method: "Tracing.tracingComplete", params: {stream: this.stream}})));
    if (method === "IO.read") return {data: '{"traceEvents":[]}', eof: true};
    if (method === "Profiler.stop") return {profile: {nodes: [], samples: []}};
    return {};
  }
}

test("bounded capture starts tracing before navigation and records page/worker identities", async () => {
  const out = await mkdtemp(join(tmpdir(), "gaius-capture-"));
  const conn = new FakeConn();
  const capture = new BoundedCdpCapture({conn, out, durationMs: 60});
  await capture.start();
  await capture.trackTarget({sessionId: "page-session", targetId: "page-id", type: "page", url: "about:blank", owner: "A"});
  await capture.trackTarget({sessionId: "worker-session", targetId: "worker-id", type: "worker", url: "blob:server", owner: "A"});
  const result = await capture.stop("test");
  assert.equal(conn.calls[0].method, "Tracing.start");
  assert.deepEqual(conn.calls.filter(x => x.method === "Profiler.start").map(x => x.sessionId), ["page-session", "worker-session"]);
  assert.equal(result.targets.length, 2);
  assert.equal(result.trace.bytes > 0, true);
  const profile = JSON.parse(await readFile(result.profiles[0].file, "utf8"));
  assert.equal(profile.identity.owner, "A");
  assert.equal(conn.calls.some(x => x.method === "Runtime.evaluate"), false);
});

test("bounded capture profiles the root page target", async () => {
  const out = await mkdtemp(join(tmpdir(), "gaius-capture-"));
  const conn = new FakeConn();
  const capture = new BoundedCdpCapture({conn, out});
  await capture.start();
  await capture.trackTarget({targetId: "root-page", type: "page", url: "http://127.0.0.1/", owner: "main"});
  const result = await capture.stop("test");
  assert.deepEqual(conn.calls.filter(x => x.method === "Profiler.start").map(x => x.sessionId), [undefined]);
  assert.equal(result.targets[0].targetId, "root-page");
  assert.equal(result.profiles.length, 1);
});

test("bounded capture sends tracing to a separate browser connection", async () => {
  const out = await mkdtemp(join(tmpdir(), "gaius-capture-"));
  const conn = new FakeConn();
  const traceConn = new FakeConn();
  traceConn.sendRoot = async (method, params) => {
    traceConn.calls.push({method, params, root: true});
    if (method === "Tracing.end") queueMicrotask(() => traceConn.listeners.forEach(fn => fn({method: "Tracing.tracingComplete", params: {stream: traceConn.stream}})));
    if (method === "IO.read") return {data: '{"traceEvents":[]}', eof: true};
    return {};
  };
  const capture = new BoundedCdpCapture({conn, traceConn, out});
  await capture.start();
  await capture.trackTarget({targetId: "page-id", type: "page", url: "about:blank", owner: "main"});
  const result = await capture.stop("test");
  assert.deepEqual(conn.calls.map(x => x.method), ["Profiler.enable", "Profiler.start", "Profiler.stop"]);
  assert.deepEqual(traceConn.calls.map(x => x.method), ["Tracing.start", "Tracing.end", "IO.read", "IO.close"]);
  assert.ok(traceConn.calls.every(x => x.root), "tracing and stream IO must use the browser-root connection");
  assert.equal(traceConn.calls[0].params.options, "record-continuously");
  assert.equal(traceConn.calls[0].params.transferMode, "ReportEvents");
  assert.equal(traceConn.calls[0].params.categories, "devtools.timeline,v8.execute,v8.cpu_profiler,disabled-by-default-v8.cpu_profiler,disabled-by-default-gpu.service,disabled-by-default-devtools.timeline");
  assert.equal(result.trace.bytes > 0, true);
});

test("bounded capture adapts Cdp event callbacks to tracing completion", async () => {
  const out = await mkdtemp(join(tmpdir(), "gaius-capture-"));
  const conn = new FakeConn();
  const traceConn = new FakeConn();
  traceConn.listeners = new Map();
  traceConn.on = (method, listener) => traceConn.listeners.set(method, listener);
  traceConn.off = (method, listener) => {
    if (traceConn.listeners.get(method) === listener) traceConn.listeners.delete(method);
  };
  traceConn.sendRoot = async (method, params) => {
    traceConn.calls.push({method, params, root: true});
    if (method === "Tracing.end") queueMicrotask(() => {
      traceConn.listeners.get("Tracing.dataCollected")?.({value: [{name: "fake"}]}, undefined);
      traceConn.listeners.get("Tracing.tracingComplete")?.({}, undefined);
    });
    if (method === "IO.read") return {data: '{"traceEvents":[]}', eof: true};
    return {};
  };
  const capture = new BoundedCdpCapture({conn, traceConn, out});
  await capture.start();
  const result = await capture.stop("test");
  assert.equal(result.trace.bytes > 0, true);
  assert.equal(traceConn.listeners.size, 0);
});

test("bounded capture accepts tracing events delivered on the page connection", async () => {
  const out = await mkdtemp(join(tmpdir(), "gaius-capture-"));
  const conn = new FakeConn();
  delete conn.listeners;
  const traceConn = new FakeConn();
  const traceListeners = new Map();
  conn.on = (method, listener) => traceListeners.set(method, listener);
  conn.off = (method, listener) => {
    if (traceListeners.get(method) === listener) traceListeners.delete(method);
  };
  traceConn.sendRoot = async method => {
    traceConn.calls.push({method, root: true});
    if (method === "Tracing.end") queueMicrotask(() => {
      traceListeners.get("Tracing.dataCollected")?.({value: [{name: "fallback"}]}, undefined);
      traceListeners.get("Tracing.tracingComplete")?.({}, undefined);
    });
    return {};
  };
  const capture = new BoundedCdpCapture({conn, traceConn, out, commandTimeoutMs: 100});
  await capture.start();
  const result = await capture.stop("test");
  assert.equal(result.trace.bytes > 0, true);
  assert.deepEqual(JSON.parse(await readFile(result.trace.file, "utf8")), {traceEvents: [{name: "fallback"}]});
});

test("bounded capture does not start a profile twice for a racing target attachment", async () => {
  const out = await mkdtemp(join(tmpdir(), "gaius-capture-"));
  const conn = new FakeConn();
  const send = conn.send.bind(conn);
  let releaseStart;
  let started;
  const startReached = new Promise(resolve => { started = resolve; });
  const startGate = new Promise(resolve => { releaseStart = resolve; });
  conn.send = async (method, params, sessionId) => {
    if (method === "Profiler.start") {
      started();
      await startGate;
    }
    return send(method, params, sessionId);
  };
  const capture = new BoundedCdpCapture({conn, out});
  await capture.start();
  const target = {sessionId: "worker-session", targetId: "worker-id", type: "worker", url: "blob:server", owner: "A"};
  const first = capture.trackTarget(target);
  await startReached;
  let duplicateSettled = false;
  const duplicate = capture.trackTarget(target).then(value => { duplicateSettled = true; return value; });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(duplicateSettled, false, "duplicate attachment waits for the in-flight Profiler.start");
  releaseStart();
  assert.deepEqual(await Promise.all([first, duplicate]), [true, true]);
  const result = await capture.stop("test");
  assert.equal(conn.calls.filter(x => x.method === "Profiler.start").length, 1);
  assert.equal(result.targets.length, 1);
});

test("timer stops capture without a Runtime.evaluate dependency", async () => {
  const out = await mkdtemp(join(tmpdir(), "gaius-capture-"));
  const conn = new FakeConn();
  const capture = new BoundedCdpCapture({conn, out, durationMs: 10});
  await capture.start();
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(capture.result.reason, "timer");
  assert.equal(conn.calls.filter(x => x.method === "Tracing.end").length, 1);
});


test("concurrent stop drains a chunked base64 stream exactly once", async () => {
  const out = await mkdtemp(join(tmpdir(), "gaius-capture-"));
  const conn = new FakeConn();
  const original = conn.send.bind(conn);
  let reads = 0;
  conn.send = async (method, params, sessionId) => {
    if (method === "IO.read") {
      assert.equal(params.size, 1024 * 1024);
      reads++;
      return reads === 1 ? {data: Buffer.from('{"traceEvents":').toString('base64'), base64Encoded: true} : {data: '[]}', eof: true};
    }
    return original(method, params, sessionId);
  };
  const capture = new BoundedCdpCapture({conn, out});
  await capture.start();
  const first = capture.stop("first");
  assert.equal(first, capture.stop("second"));
  const result = await first;
  assert.equal(await readFile(result.trace.file, "utf8"), '{"traceEvents":[]}');
  assert.equal(result.reason, "first");
  assert.equal(conn.calls.filter(x => x.method === "Tracing.end").length, 1);
});
