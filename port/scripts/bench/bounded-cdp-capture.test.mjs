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
