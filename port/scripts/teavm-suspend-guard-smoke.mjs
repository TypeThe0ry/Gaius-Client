#!/usr/bin/env node
// Runs the TeaVMThread.prototype.suspend replacement that
// TeaVMCoreBrowserPatcher writes into thread.js, and the acceptance helpers in
// tools/teavm-runtime-guards.mjs that read its counters.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";
import {
  CLINIT_SUSPENSION_TEXT,
  RUNTIME_GUARD_EXPRESSION,
  RUNTIME_GUARD_FAILURE_TEXTS,
  clinitSuspensionCount,
  runtimeGuardFailures,
} from "../../tools/teavm-runtime-guards.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const patcher = readFileSync(resolve(root,
  "port/tools/src/main/java/dev/gaius/tools/TeaVMCoreBrowserPatcher.java"), "utf8");

function textBlock(name) {
  const start = patcher.indexOf(`static final String ${name} = """`);
  assert.ok(start >= 0, `${name} text block not found`);
  const bodyStart = patcher.indexOf("\n", start) + 1;
  const end = patcher.indexOf('""";', bodyStart);
  const lines = patcher.slice(bodyStart, end).split("\n");
  // Java strips the common indentation; the closing delimiter shares the
  // indentation of the last line here.
  const indent = Math.min(...lines.filter((line) => line.trim()).map((line) => line.match(/^ */)[0].length));
  return lines.map((line) => line.slice(indent)).join("\n");
}

const guarded = textBlock("THREAD_SUSPEND_GUARDED");
const logged = [];
function freshContext() {
  logged.length = 0;
  const context = vm.createContext({
    console: {
      error: (value) => logged.push(["error", String(value && value.message || value)]),
      warn: (value) => logged.push(["warn", String(value && value.message || value)]),
    },
  });
  context.teavm_globals = context;
  vm.runInContext("this.Error = Error; this.String = String; function TeaVMThread() {"
    + " this.status = 0; this.suspendCallback = null; }", context);
  vm.runInContext(guarded, context);
  return context;
}

// No guard active: the vanilla suspension.
let context = freshContext();
vm.runInContext("var thread = new TeaVMThread(); thread.suspend(function() {});", context);
assert.equal(vm.runInContext("thread.status", context), 1);
assert.equal(vm.runInContext("typeof thread.suspendCallback", context), "function");
assert.deepEqual(logged, []);

// Inside a method the role options compiled synchronously: throws, counts and logs.
context = freshContext();
const thrown = vm.runInContext(`
  globalThis.__gaiusNoSuspendDepth = 1;
  var thread = new TeaVMThread();
  var caught = null;
  for (var i = 0; i < 10; i++) {
    try { thread.suspend(function() {}); } catch (error) { caught = error; }
  }
  [String(caught && caught.message), thread.status]`, context);
assert.match(thrown[0], new RegExp(RUNTIME_GUARD_FAILURE_TEXTS[0]));
assert.equal(thrown[1], 0, "a rejected suspension must not mark the thread suspending");
let snapshot = vm.runInContext(RUNTIME_GUARD_EXPRESSION, context);
assert.equal(snapshot.noSuspendViolations, 10);
assert.match(snapshot.lastNoSuspendViolation, /suspension was reached inside a method/);
assert.equal(logged.filter(([level]) => level === "error").length, 8, "console logging is capped");
assert.equal(runtimeGuardFailures({snapshots: [snapshot]}).length, 1);

// Inside a class initializer: counted, warned once, still suspends.
context = freshContext();
vm.runInContext(`
  globalThis.__gaiusClinitDepth = 2;
  var thread = new TeaVMThread();
  thread.suspend(function() {});
  thread.suspend(function() {});`, context);
assert.equal(vm.runInContext("thread.status", context), 1);
snapshot = vm.runInContext(RUNTIME_GUARD_EXPRESSION, context);
assert.equal(snapshot.noSuspendViolations, 0);
assert.equal(snapshot.clinitSuspensions, 2);
assert.match(snapshot.firstClinitSuspension, new RegExp(CLINIT_SUSPENSION_TEXT));
assert.deepEqual(logged.map(([level]) => level), ["warn"]);
assert.deepEqual(runtimeGuardFailures({snapshots: [snapshot], entries: [{text: logged[0][1]}]}), [],
  "a class initializer suspension is diagnostic only");
assert.equal(clinitSuspensionCount([null, snapshot, {clinitSuspensions: 1}]), 2);

// Console and exception shapes recorded by the CDP scripts.
const failures = runtimeGuardFailures({
  label: "client 1",
  snapshots: [null, undefined, {noSuspendViolations: 0}],
  entries: [
    {type: "error", text: "Error: Gaius: a TeaVM suspension was reached inside a method that ..."},
    {at: "t", type: "error", args: ["java.lang.IllegalStateException: Can't enter monitor from another thread synchronously"]},
    {description: "Error: Gaius: a TeaVM suspension was reached inside a method that ..."},
    "unrelated warning",
    {text: "(JavaScript) Error: Gaius: a TeaVM suspension was reached inside a method that ..."},
  ],
});
assert.equal(failures.length, 3, failures.join("\n"));
assert.ok(failures.every((failure) => failure.startsWith("client 1: ")));
const flood = runtimeGuardFailures({entries: Array.from({length: 40}, (_, index) =>
  `Can't enter monitor from another thread synchronously #${index}`)});
assert.equal(flood.length, 17);
assert.equal(flood.at(-1), "24 more");
assert.deepEqual(runtimeGuardFailures(), []);

console.log("teavm-suspend-guard-smoke: ok");
