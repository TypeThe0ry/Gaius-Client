import assert from "node:assert/strict";
import {readFileSync, readdirSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import {KERNELS_OFF, capturePage, validateProbeReports} from "./tick-browser-capture.mjs";

const fixture = {schema: 1, profile: "test-profile", player: "Parity", seed: "42", ticks: 1,
  teleportAcknowledgement: "id", settle: "quiescent",
  spawn: {x: 0, y: 80, z: 0}, viewDistance: 2, simulationDistance: 2, preloadRadius: 2,
  setupCommands: [], serverboundIds: {}, actions: []};
const profile = {id: fixture.profile, worldVersion: 1,
  storage: {schema: 2, databaseName: "test-db", prefix: "test:", opfsDirectory: "test-opfs"}};

test("capture page sends identical distances and seed without timed input replay", async () => {
  let workerSource;
  let start;
  let worker;
  const context = vm.createContext({location: {origin: "http://127.0.0.1:1234"},
    URL: {createObjectURL: blob => { workerSource = blob.parts.join(""); return "blob:worker"; }},
    Blob: class { constructor(parts) { this.parts = parts; } },
    Worker: class { constructor() { worker = this; } postMessage(message) { start = message; } },
    MessageChannel: class { constructor() { this.port1 = {}; this.port2 = {}; } },
    __tickParityEmit: () => {}, __tickParityControl: () => {}});
  const page = capturePage(profile, fixture);
  vm.runInContext(page.match(/<script>([\s\S]*)<\/script>/)[1], context);
  assert.equal(start.renderDistance, fixture.viewDistance);
  assert.equal(start.simulationDistance, fixture.simulationDistance);
  assert.equal(start.seed, fixture.seed);
  assert.equal(start.serverRuntime, "wasm");
  assert.equal(start.storageDatabaseName, profile.storage.databaseName);
  assert.match(workerSource, /__gaiusTickParityDebug=true/);
  assert.match(workerSource, /__gaiusTickParityScript=/);
  assert.doesNotMatch(page, /setTimeout|setInterval/);
  worker.onmessage({data: {type: "tick-parity-event", json: '{"kind":"complete"}'}});
  assert.equal((await context.__tickParityDone).complete, true);
});

test("capture page rejects profile substitution and escapes script text", () => {
  assert.throws(() => capturePage({...profile, id: "different"}, fixture));
  assert.throws(() => capturePage(profile, {...fixture, profile: "../../config"}));
  const page = capturePage(profile, {...fixture, setupCommands: ["say </script>"]});
  assert.equal(page.match(/<\/script>/g).length, 1);
});

test("all selected profile settings reach the same Worker entry point", () => {
  const profiles = readdirSync(new URL("../../versions/", import.meta.url)).filter(name => name.endsWith(".json"));
  for (const name of profiles) {
    const selected = name.slice(0, -5);
    const definition = JSON.parse(readFileSync(new URL(`../../versions/${selected}.json`, import.meta.url), "utf8"));
    const {moves, ...settings} = definition.tickParity;
    const page = capturePage(definition, {...settings, profile: definition.id, serverboundIds: {}, actions: []});
    assert.match(page, /tick-parity-event/);
    assert.equal(settings.setupCommands.filter(command => command === "gamerule respawn_radius 0").length, 1);
  }
});

test("capture page switches both server kernels off before the Worker bootstrap and posts no kernel port", () => {
  let workerSource;
  const posted = [];
  const context = vm.createContext({location: {origin: "http://127.0.0.1:1234"},
    URL: {createObjectURL: blob => { workerSource = blob.parts.join(""); return "blob:worker"; }},
    Blob: class { constructor(parts) { this.parts = parts; } },
    Worker: class { postMessage(message) { posted.push(message); } },
    MessageChannel: class { constructor() { this.port1 = {}; this.port2 = {}; } },
    __tickParityEmit: () => {}, __tickParityControl: () => {}});
  const page = capturePage(profile, fixture);
  vm.runInContext(page.match(/<script>([\s\S]*)<\/script>/)[1], context);
  const bootstrap = workerSource.indexOf("importScripts(");
  for (const setting of ["GAIUS_LIGHT_KERNEL=false", "__gaiusLightKernelConfig={enabled:false}",
    "__gaiusWorldgenKernelConfig={enabled:false}"]) {
    const index = workerSource.indexOf(setting);
    assert.ok(index >= 0 && index < bootstrap, `${setting} precedes the bootstrap`);
  }
  assert.doesNotMatch(page, /gaius-kernel-port|kernelScripts/);
  assert.deepEqual(posted.map(message => message.type), ["start"]);
  assert.equal(KERNELS_OFF.mode, "off");
  assert.deepEqual(KERNELS_OFF.families, {worldgen: false, light: false});
});

test("capture page records probe reports beside the event rows", () => {
  const controls = [];
  let worker;
  const context = vm.createContext({location: {origin: "http://127.0.0.1:1234"},
    URL: {createObjectURL: () => "blob:worker"}, Blob: class { constructor(parts) { this.parts = parts; } },
    Worker: class { constructor() { worker = this; } postMessage() {} },
    MessageChannel: class { constructor() { this.port1 = {}; this.port2 = {}; } },
    __tickParityEmit: () => { throw new Error("probe report reached the event stream"); },
    __tickParityControl: payload => controls.push(JSON.parse(payload))});
  vm.runInContext(capturePage(profile, fixture).match(/<script>([\s\S]*)<\/script>/)[1], context);
  worker.onmessage({data: {type: "tick-parity-probe", json: '{"phase":"configured"}'}});
  assert.deepEqual(controls, [{type: "tick-parity-probe", json: '{"phase":"configured"}'}]);
});

const report = (phase, fields = {}) => ({type: "tick-parity-probe", json: JSON.stringify({phase, executor: "serial-fifo",
  thread: "Worker-Main-1", queuedTasks: 40, inlineTasks: 9, callerTasks: 120, completedTasks: 39, failedTasks: 0, holds: 30,
  maxInlineDepth: 1, dispatcherTurns: 12, dispatcherRequests: 4, pending: phase === "complete" ? 1 : 0, broken: false,
  ioTasks: 3, ioIdle: true, waits: 7, kernels: {present: false, inFlight: 0, submitted: 0}, dispatcherPumps: 0, ...fields})});
const configured = {type: "tick-parity-probe", json: JSON.stringify({phase: "configured", executor: "serial-fifo", requested: true})};

test("probe reports prove the parity schedule ran with the kernels off", () => {
  const accepted = validateProbeReports([{type: "worker-ready"}, configured, report("started"), report("complete")]);
  assert.equal(accepted.complete.holds, 30);
  const rejects = (messages, pattern) => assert.throws(() => validateProbeReports(messages), pattern);
  rejects([report("started"), report("complete")], /configured: expected one/);
  rejects([{type: "tick-parity-probe", json: JSON.stringify({phase: "configured", executor: "absent", requested: true})},
    report("started"), report("complete")], /without the parity executor schedule/);
  rejects([configured, report("started"), report("complete", {kernels: {present: false, inFlight: 0, submitted: 2}})], /kernels were not off/);
  rejects([configured, report("started", {kernels: {present: true, inFlight: 0, submitted: 0}}), report("complete")], /kernels were not off/);
  rejects([configured, report("started"), report("complete", {dispatcherPumps: 1})], /hopped through host activations/);
  rejects([configured, report("started"), report("complete", {broken: true})], /schedule broken/);
  rejects([configured, report("started"), report("complete", {failedTasks: 1})], /background tasks failed/);
  rejects([configured, report("started"), report("complete", {queuedTasks: 0})], /FIFO schedule unused/);
  rejects([configured, report("started"), report("complete", {pending: 3})], /besides the final hold/);
  rejects([configured, report("started"), report("failed"), report("complete")], /failed capture/);
  rejects([configured, report("started")], /complete: expected one/);
});
