import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import {createContext, runInContext} from "node:vm";

const bridge = readFileSync(new URL("../web/runtime/wasmgc/png-kernel.js", import.meta.url), "utf8");
const boot = readFileSync(new URL("../web/runtime/wasmgc/gaius-wasm-boot.js", import.meta.url), "utf8");
const loaded = () => ({instance: {exports: {memory: new WebAssembly.Memory({initial: 1}),
  gaius_abi_version: () => 1, alloc() {}, dealloc() {}, release() {}, run_png_decode() {}}}});
const turn = () => new Promise((resolve) => setImmediate(resolve));

function browserFixture({search = "", fetchMode = "pending", scriptMode = "present", instantiate} = {}) {
  const counts = {fetches: 0, loads: 0, instantiations: 0, scripts: 0, timers: 0, controllers: 0};
  const timers = new Set();
  let signal;
  const sandbox = {Uint8Array, DataView, URLSearchParams, performance, console,
    AbortController: class extends AbortController {
      constructor() { super(); counts.controllers++; }
    },
    location: {search}, __gaiusWasmSource: new Uint8Array(0), GaiusJspi: {},
    setTimeout(callback, delay) {
      counts.timers++;
      const timer = {callback, delay};
      timers.add(timer);
      return timer;
    },
    clearTimeout(timer) { timers.delete(timer); },
    WebAssembly: {Suspending() {}, promising() {}, Memory: WebAssembly.Memory,
      instantiate(source) {
        counts.instantiations++;
        return instantiate ? instantiate(source) : Promise.resolve(loaded());
      }},
    fetch(url, options) {
      counts.fetches++;
      signal = options.signal;
      if (fetchMode === "reject") return Promise.reject(new Error("fixture load failure"));
      if (fetchMode === "body") return Promise.resolve({ok: true, arrayBuffer: () => new Promise(() => {})});
      return new Promise(() => {});
    },
    TeaVM: {wasmGC: {load: async () => { counts.loads++; return {instance: {exports: {}}}; }}},
    document: {createElement: () => ({}), head: {appendChild(script) {
      counts.scripts++;
      if (scriptMode === "reject") script.onerror();
    }}}
  };
  sandbox.window = sandbox;
  const context = createContext(sandbox);
  if (scriptMode === "present") runInContext(bridge, context);
  return {context, counts, timers, signal: () => signal,
    start: () => runInContext(boot, context),
    expire: () => { for (const timer of [...timers]) timer.callback(); }};
}

for (const search of ["", "?gaiusPng=0"]) {
  test(`PNG disabled (${search || "default"}) does zero extra boot work`, async () => {
    const fixture = browserFixture({search});
    fixture.start();
    await fixture.context.__gaiusClientReady;
    assert.equal(await fixture.context.GaiusPngKernel.initialize(), false);
    assert.deepEqual(fixture.counts,
      {fetches: 0, loads: 1, instantiations: 0, scripts: 0, timers: 0, controllers: 0});
    assert.equal(fixture.context.__gaiusPngReady, undefined);
  });
}

test("site switch loads the PNG module and ?gaiusPng=0 still turns it off", async () => {
  const on = browserFixture({fetchMode: "reject"});
  on.context.__gaiusPngKernelEnabled = true;
  on.context.__gaiusPngWasmUrl = "gaius-png.0123456789abcdef.wasm";
  on.start();
  await on.context.__gaiusClientReady;
  assert.equal(await on.context.__gaiusPngReady, false);
  assert.equal(on.counts.fetches, 1);
  const off = browserFixture({search: "?gaiusPng=0"});
  off.context.__gaiusPngKernelEnabled = true;
  off.start();
  await off.context.__gaiusClientReady;
  assert.equal(off.context.__gaiusPngReady, undefined);
  assert.equal(off.counts.fetches, 0);
});

for (const fetchMode of ["pending", "body"]) {
  test(`PNG unresolved ${fetchMode} never blocks boot and times out to Java`, {timeout: 1000}, async () => {
    const fixture = browserFixture({search: "?gaiusPng=1", fetchMode});
    fixture.start();
    await fixture.context.__gaiusClientReady;
    assert.equal(fixture.counts.fetches, 1);
    assert.equal(fixture.counts.controllers, 1);
    assert.equal(fixture.counts.loads, 1);
    assert.equal(fixture.context.GaiusPngKernel.decode(new Uint8Array(33), 4), null);
    assert.equal([...fixture.timers][0].delay, 5000);
    fixture.expire();
    assert.equal(await fixture.context.__gaiusPngReady, false);
    assert.equal(fixture.signal().aborted, true);
    assert.equal(fixture.timers.size, 0);
    assert.equal(fixture.context.GaiusPngKernel.ready, false);
    assert.equal(fixture.context.GaiusPngKernel.metrics.failures, 1);
    assert.match(fixture.context.__gaiusPngError, /timed out/);
    assert.equal(fixture.context.__gaiusBootError, undefined);
  });
}

for (const scriptMode of ["pending", "reject"]) {
  test(`PNG optional script ${scriptMode} never blocks boot`, {timeout: 1000}, async () => {
    const fixture = browserFixture({search: "?gaiusPng=1", scriptMode});
    fixture.start();
    await fixture.context.__gaiusClientReady;
    assert.equal(fixture.counts.scripts, 1);
    assert.equal(fixture.counts.loads, 1);
    assert.equal(fixture.counts.fetches, 0);
    if (scriptMode === "reject") assert.equal(await fixture.context.__gaiusPngReady, false);
    assert.equal(fixture.context.__gaiusBootError, undefined);
  });
}

test("PNG rejected fetch falls back without delaying client readiness", async () => {
  const fixture = browserFixture({search: "?gaiusPng=1", fetchMode: "reject"});
  fixture.start();
  await fixture.context.__gaiusClientReady;
  assert.equal(await fixture.context.__gaiusPngReady, false);
  assert.equal(fixture.counts.loads, 1);
  assert.equal(fixture.timers.size, 0);
  assert.match(fixture.context.__gaiusPngError, /fixture load failure/);
});

test("late PNG instantiation cannot publish after timeout and initialization can retry", async () => {
  let finish;
  const fixture = browserFixture({instantiate: () => new Promise((resolve) => { finish = resolve; })});
  const kernel = fixture.context.GaiusPngKernel;
  kernel.setEnabled(true);
  const pending = kernel.initialize({bytes: new Uint8Array(0), timeoutMs: 25});
  assert.equal(kernel.initialize(), pending);
  await turn();
  assert.equal(fixture.counts.instantiations, 1);
  assert.equal([...fixture.timers][0].delay, 25);
  fixture.expire();
  assert.equal(await pending, false);
  finish(loaded());
  await turn();
  assert.equal(kernel.ready, false);
  const retry = kernel.initialize({bytes: new Uint8Array(0)});
  await turn();
  finish(loaded());
  assert.equal(await retry, true);
  assert.equal(kernel.ready, true);
  assert.equal(fixture.timers.size, 0);
  assert.equal(kernel.metrics.failures, 1);
});
