#!/usr/bin/env node
// Runs the portable page's local-bridge patch/pump (build-portable-html.py) in a VM: the 10 ms pump
// must stop once the bridge is patched and no local port is pending, a replacement bridge must
// still be wrapped (by the accessor), a newly pending port must re-arm the pump, and an idle pump
// must not flood the diagnostic trace.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("./build-portable-html.py", import.meta.url), "utf8");
const start = source.indexOf("      const portableBridgeTrace = ");
const endMarker = "\n      armPortableBridgePump();\n";
const end = source.indexOf(endMarker, start);
assert.ok(start > 0 && end > start, "bridge pump section not found in build-portable-html.py");
const snippet = source.slice(start, end + endMarker.length) +
  "\nwindow.__testArmPump = armPortableBridgePump;\n";

const timers = new Map();
let nextTimer = 1;
const window = {};
const context = vm.createContext({
  window, Date, Map, Object, String, Number, Array,
  setInterval: (fn, ms) => { const id = nextTimer++; timers.set(id, {fn, ms}); return id; },
  clearInterval: (id) => { timers.delete(id); },
});
window.window = window;
vm.runInContext(`(function(){${snippet}})()`, context);
const tick = (n = 1) => { for (let i = 0; i < n; i++) for (const t of [...timers.values()]) t.fn(); };

assert.equal(timers.size, 1, "pump must be armed while no bridge exists");
assert.equal(window.__gaiusPortableBridgeAccessorInstalled, true, "accessor must own __gaiusNettyBridge");
tick(5);
assert.equal(timers.size, 1, "pump must keep running until a bridge is patched");

const opened = [];
const makeBridge = (registered) => ({
  open(id, host, port) { opened.push([id, host, port]); return true; },
  registerLocalPort(session, port, generation) { registered.push([session, generation]); return true; },
});
const registered = [];
window.__gaiusNettyBridge = makeBridge(registered);
assert.equal(window.__gaiusNettyBridge.__gaiusLocalHostPatch, true, "assigned bridge must be wrapped by the accessor");
tick();
assert.equal(timers.size, 0, "pump must stop once the bridge is patched and no port is pending");
const inits = window.__gaiusPortableBridgeTrace.filter((e) => e.event === "bridge-init").length;
assert.equal(inits, 1, "a patched bridge is traced once");

const session = "0123456789abcdef0123456789abcdef";
window.__gaiusServerSessionId = session;
window.__gaiusNettyBridge.open(7, "0.0.0.0", 25565);
assert.deepEqual(opened.at(-1), [7, `client-${session}.gaius-local`, 25565], "wildcard host must be normalized");

// A replacement bridge (reconnect / worker handoff) is wrapped without the pump.
const registered2 = [];
window.__gaiusNettyBridge = makeBridge(registered2);
assert.equal(window.__gaiusNettyBridge.__gaiusLocalHostPatch, true, "replacement bridge must be wrapped");
window.__gaiusNettyBridge.open(8, "localhost", 25565);
assert.equal(opened.at(-1)[1], `client-${session}.gaius-local`, "replacement bridge must normalize the host");

// An integrated-server start that cannot register yet re-arms the pump until it can.
const failing = {open() { return true; }, registerLocalPort() { return false; }};
window.__gaiusNettyBridge = failing;
window.__gaiusPortablePendingLocalPorts.set(session, {port: {}, launchGeneration: "2"});
window.__testArmPump();
assert.equal(timers.size, 1, "pending port must re-arm the pump");
tick(3);
assert.equal(timers.size, 1, "pump must keep running while the port is still pending");
const before = window.__gaiusPortableBridgeTrace.length;
failing.registerLocalPort = (s, p, g) => { registered2.push([s, g]); return true; };
tick();
assert.equal(window.__gaiusPortablePendingLocalPorts.size, 0, "registered port must leave the pending map");
assert.deepEqual(registered2.at(-1), [session, "2"]);
assert.equal(timers.size, 0, "pump must stop again once the port is registered");
assert.ok(window.__gaiusPortableBridgeTrace.length - before <= 2, "an idle pump must not flood the trace");
console.log("portable bridge pump smoke PASS");
