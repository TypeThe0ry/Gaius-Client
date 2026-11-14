#!/usr/bin/env node
// installHeapGuard (port/web/boot/gaius-boot.js).
//
//   node port/scripts/heap-guard-smoke.mjs
//
// Checks that the guard collects when the page starts, collects when the heap grows past its
// budget only at moments canCollect() allows, rate limits collections, and does nothing without
// performance.memory.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const url = new URL("../web/boot/gaius-boot.js", import.meta.url);
const source = await readFile(url, "utf8");
const begin = source.indexOf("// BEGIN gaius-heap-guard");
const end = source.indexOf("// END gaius-heap-guard");
assert.ok(begin >= 0 && end > begin, "heap guard markers");
const block = source.slice(begin, end + "// END gaius-heap-guard".length);
assert.match(source, /installHeapGuard\(global, \{\s*\.\.\.heapStartPlan\(heapDebt\), startNeeded: \(\) => heapDebt > 0, startReady: gameBooted, startMinHeapMB: 200,\s*afterCollect: \(\) => clearHeapDebt\(global\), canCollect: \(\) => quietReason\(\) !== null, emergencyMB: 1100\}\);/,
  "the page sweeps the previous document after the game booted, only collects on quiet screens, and takes the start plan from the heap debt");
assert.doesNotMatch(source, /installHeapGuard\(global, \{collectOnStart: true/, "the page does not collect before the title");
assert.match(source, /__gaiusHeapGuard\.stop\(\)/, "the page stops the guard on pagehide");
assert.doesNotMatch(block, /<\/script/i, "the block is inlined into the portable page");
const blocks = {page: block};

const clock = {now: 1_000_000};
function makeRoot({memory = true} = {}) {
  const timers = [];
  const allocations = [];
  const root = {
    Date: {now: () => clock.now},
    Math,
    performance: {now: () => 0, memory: memory ? {usedJSHeapSize: 100 * 1048576} : undefined},
    ArrayBuffer: function FakeArrayBuffer(length) {
      allocations.push(length);
      throw new RangeError("Array buffer allocation failed");
    },
    setTimeout(fn, ms) { timers.push({fn, ms, cleared: false}); return timers.length; },
    clearTimeout(id) { timers[id - 1].cleared = true; },
  };
  return {root, timers, allocations};
}
function load(root) {
  const context = vm.createContext(root);
  vm.runInContext(`${blocks.page}\nthis.installHeapGuard = installHeapGuard; this.heapDebtAtStart = heapDebtAtStart; this.clearHeapDebt = clearHeapDebt; this.heapStartPlan = heapStartPlan;`, context);
  return context;
}
async function withClock(startMs, body) {
  clock.now = startMs;
  return body(ms => { clock.now += ms; });
}
const MB = 1048576;

{
  // Start-up collection: one failing 16 GiB allocation, nothing else.
  const {root, timers, allocations} = makeRoot();
  const context = load(root);
  const state = context.installHeapGuard(context, {collectOnStart: true});
  assert.deepEqual(allocations, [2 ** 34]);
  assert.equal(state.collections, 1);
  assert.equal(state.lastReason, "start");
  assert.equal(timers.length, 1, "one polling timer");
}
{
  // A page without a start-up collection only collects past its budget.
  const {root, timers, allocations} = makeRoot();
  const context = load(root);
  const state = context.installHeapGuard(context, {budgetMB: 200, canCollect: () => true});
  assert.equal(allocations.length, 0);
  await withClock(1_000_000, advance => {
    context.performance.memory.usedJSHeapSize = 250 * MB;
    advance(1000); timers.at(-1).fn();
    assert.equal(allocations.length, 0, "growth under the budget is left to V8");
    context.performance.memory.usedJSHeapSize = 340 * MB;
    advance(1000); timers.at(-1).fn();
    assert.equal(allocations.length, 0, "just under the budget above the low point");
    context.performance.memory.usedJSHeapSize = 320 * MB;
    advance(1000); timers.at(-1).fn();
    context.performance.memory.usedJSHeapSize = 100 * MB;
    advance(1000); timers.at(-1).fn();
    context.performance.memory.usedJSHeapSize = 320 * MB;
    advance(1000); timers.at(-1).fn();
    assert.equal(allocations.length, 1, "heap grew by more than the budget since its low point");
    assert.equal(state.lastReason, "budget");
    // After a collection the low point restarts and collections are rate limited.
    context.performance.memory.usedJSHeapSize = 900 * MB;
    advance(1000); timers.at(-1).fn();
    context.performance.memory.usedJSHeapSize = 1400 * MB;
    advance(1000); timers.at(-1).fn();
    assert.equal(allocations.length, 1, "no second collection within the minimum gap");
    advance(4000); timers.at(-1).fn();
    assert.equal(allocations.length, 2);
  });
  state.stop();
  assert.ok(timers.at(-1).cleared || timers.length > 0);
}
{
  // canCollect() vetoes a collection during gameplay and allows it at the next quiet moment.
  const {root, timers, allocations} = makeRoot();
  const context = load(root);
  let quiet = false;
  context.installHeapGuard(context, {budgetMB: 100, canCollect: () => quiet});
  await withClock(5_000_000, advance => {
    context.performance.memory.usedJSHeapSize = 100 * MB;
    advance(1000); timers.at(-1).fn();
    context.performance.memory.usedJSHeapSize = 400 * MB;
    advance(1000); timers.at(-1).fn();
    advance(10000); timers.at(-1).fn();
    assert.equal(allocations.length, 0, "no collection while gameplay frames are measured");
    quiet = true;
    advance(1000); timers.at(-1).fn();
    assert.equal(allocations.length, 1, "collects at the first quiet moment");
  });
}
{
  // Emergency: a large heap that keeps growing is collected even during gameplay, at most every 30 s.
  const {root, timers, allocations} = makeRoot();
  const context = load(root);
  const state = context.installHeapGuard(context, {budgetMB: 100, canCollect: () => false, emergencyMB: 1000, emergencyGrowthMB: 200});
  await withClock(9_000_000, advance => {
    context.performance.memory.usedJSHeapSize = 700 * MB;
    advance(1000); timers.at(-1).fn();
    context.performance.memory.usedJSHeapSize = 950 * MB;
    advance(1000); timers.at(-1).fn();
    assert.equal(allocations.length, 0, "below the emergency size nothing is collected during gameplay");
    context.performance.memory.usedJSHeapSize = 1100 * MB;
    advance(1000); timers.at(-1).fn();
    assert.equal(allocations.length, 1);
    assert.equal(state.lastReason, "emergency");
    context.performance.memory.usedJSHeapSize = 1000 * MB;
    advance(1000); timers.at(-1).fn();
    context.performance.memory.usedJSHeapSize = 1300 * MB;
    advance(5000); timers.at(-1).fn();
    assert.equal(allocations.length, 1, "emergency collections are 30 s apart");
    advance(30000); timers.at(-1).fn();
    assert.equal(allocations.length, 2);
  });
}
{
  // No performance.memory (other browsers): only the start-up collection, no timer.
  const {root, timers, allocations} = makeRoot({memory: false});
  const context = load(root);
  context.installHeapGuard(context, {collectOnStart: true});
  assert.equal(allocations.length, 1);
  assert.equal(timers.length, 0);
}
{
  // Idle start: nothing at install, one collection once the game booted, stayed ready for startDelayMs and the screen is quiet.
  clock.now = 20_000_000;
  const {root, timers, allocations} = makeRoot();
  const context = load(root);
  let ready = false, quiet = true;
  const state = context.installHeapGuard(context, {collectOnStart: "idle", startReady: () => ready, startDelayMs: 4000, canCollect: () => quiet});
  assert.equal(allocations.length, 0, "no collection while the page boots");
  const poll = () => { const t = timers.filter(x => x.ms === 500 && !x.cleared && !x.fired).at(-1); assert.ok(t, "a poll timer is pending"); t.fired = true; t.fn(); };
  await withClock(20_000_000, advance => {
    advance(500); poll(); advance(500); poll();
    assert.equal(allocations.length, 0, "not before the game booted");
    ready = true;
    advance(500); poll();
    advance(3000); poll();
    assert.equal(allocations.length, 0, "not before startDelayMs after the first ready poll");
    quiet = false;
    advance(1500); poll();
    assert.equal(allocations.length, 0, "not while gameplay frames are measured");
    quiet = true;
    advance(500); poll();
    assert.equal(allocations.length, 1);
    assert.equal(state.lastReason, "start-idle");
    assert.equal(state.log[0][1], "start-idle");
    assert.equal(timers.filter(x => x.ms === 500 && !x.cleared && !x.fired).length, 0, "the start poll ends after the collection");
  });
}
{
  // Idle start gates: no previous document in the tab, a heap known to be small, and a coarse or missing size.
  const run = async (name, settings, used, expectCollections, expectSkipped) => {
    clock.now = 30_000_000;
    const {root, timers, allocations} = makeRoot();
    root.performance.memory.usedJSHeapSize = used;
    const context = load(root);
    const state = context.installHeapGuard(context, {collectOnStart: "idle", startReady: () => true, startDelayMs: 1000, canCollect: () => true, ...settings});
    await withClock(30_000_000, advance => {
      for (let i = 0; i < 6; i++) {
        const t = timers.filter(x => x.ms === 500 && !x.cleared && !x.fired).at(-1);
        if (!t) break;
        t.fired = true; advance(500); t.fn();
      }
    });
    assert.equal(allocations.length, expectCollections, name);
    assert.equal(state.startSkipped, expectSkipped, name + " (reason)");
  };
  await run("no previous document: skipped", {startNeeded: () => false}, 300 * MB + 12345, 0, "no-previous-document");
  await run("previous document: collected", {startNeeded: () => true}, 300 * MB + 12345, 1, null);
  await run("exact small heap: skipped", {startNeeded: () => true, startMinHeapMB: 200}, 120 * MB + 4321, 0, "small-heap");
  await run("exact large heap: collected", {startNeeded: () => true, startMinHeapMB: 200}, 300 * MB + 4321, 1, null);
  await run("coarse size says nothing: collected", {startNeeded: () => true, startMinHeapMB: 200}, 18_200_000, 1, null);
  await run("no heap gate configured: collected", {startNeeded: () => true}, 120 * MB + 4321, 1, null);
}
{
  // Idle start gives up when the game never gets quiet, and stop() cancels it.
  clock.now = 40_000_000;
  const {root, timers, allocations} = makeRoot();
  const context = load(root);
  const state = context.installHeapGuard(context, {collectOnStart: "idle", startReady: () => true, startDelayMs: 0, startGiveUpMs: 5000, canCollect: () => false});
  await withClock(40_000_000, advance => {
    for (let i = 0; i < 20; i++) {
      const t = timers.filter(x => x.ms === 500 && !x.cleared && !x.fired).at(-1);
      if (!t) break;
      t.fired = true; advance(500); t.fn();
    }
  });
  assert.equal(allocations.length, 0);
  assert.equal(state.startSkipped, "not-quiet");
  const second = makeRoot();
  const context2 = load(second.root);
  const state2 = context2.installHeapGuard(context2, {collectOnStart: "idle", startReady: () => true, canCollect: () => true});
  const pending = second.timers.find(x => x.ms === 500);
  state2.stop();
  assert.ok(pending.cleared, "stop() clears the start poll");
}
{
  // No performance.memory and an idle start: the start poll still runs (it needs only setTimeout).
  clock.now = 50_000_000;
  const {root, timers, allocations} = makeRoot({memory: false});
  const context = load(root);
  context.installHeapGuard(context, {collectOnStart: "idle", startReady: () => true, startDelayMs: 0, canCollect: () => true});
  assert.equal(allocations.length, 0);
  await withClock(50_000_000, advance => { advance(500); timers[0].fn(); });
  assert.equal(allocations.length, 1);
}
{
  // afterCollect runs after every collection (the page resets the tab's heap debt there).
  clock.now = 60_000_000;
  const {root} = makeRoot();
  const context = load(root);
  const reasons = [];
  context.installHeapGuard(context, {collectOnStart: true, afterCollect: (reason) => reasons.push(reason)});
  assert.deepEqual(reasons, ["start"]);
}
{
  // Heap debt: earlier documents of the tab whose heap may still wait for a collection.
  const storageOf = (items) => ({getItem: (k) => (k in items ? items[k] : null), setItem: (k, v) => { items[k] = String(v); }});
  const debtFor = (navType, items, {broken = false} = {}) => {
    const {root} = makeRoot();
    root.performance.getEntriesByType = () => [{type: navType}];
    root.sessionStorage = broken ? {getItem() { throw new Error("denied"); }, setItem() { throw new Error("denied"); }} : storageOf(items);
    const context = load(root);
    return context.heapDebtAtStart(context);
  };
  let items = {};
  assert.equal(debtFor("navigate", items), 0, "a fresh tab has nothing to collect");
  assert.equal(items["gaius.heapDebt"], "0");
  assert.equal(debtFor("reload", items), 1, "a reload leaves one heap");
  assert.equal(items["gaius.heapDebt"], "1");
  assert.equal(debtFor("reload", items), 2, "a second reload before any collection counts up");
  assert.equal(debtFor("reload", items), 3);
  items["gaius.heapDebt"] = "0"; // a collection ran
  assert.equal(debtFor("reload", items), 1);
  items = {"gaius.heapDebt": "0"};
  assert.equal(debtFor("navigate", items), 1, "a document of ours earlier in the tab counts without a reload");
  assert.equal(debtFor("reload", {}), 1, "reload without a stamp");
  assert.equal(debtFor("navigate", {}, {broken: true}), 1, "no sessionStorage: assume one earlier heap");
  const {root} = makeRoot();
  root.sessionStorage = storageOf(items);
  const context = load(root);
  context.clearHeapDebt(context);
  assert.equal(items["gaius.heapDebt"], "0");
}
{
  // The start plan and a chain of reloads: a document that reaches a booted game and stays 0.5-4 s sweeps its predecessors and resets the debt, so
  // reloads that wait for the title never collect on the start path; only a chain of interrupted boots does, and only from the third.
  const {root} = makeRoot();
  const context = load(root);
  assert.deepEqual({...context.heapStartPlan(0)}, {collectOnStart: "idle", startDelayMs: 4000});
  assert.deepEqual({...context.heapStartPlan(1)}, {collectOnStart: "idle", startDelayMs: 4000});
  assert.deepEqual({...context.heapStartPlan(2)}, {collectOnStart: "idle", startDelayMs: 500});
  assert.deepEqual({...context.heapStartPlan(3)}, {collectOnStart: true, startDelayMs: 500});
  const items = {};
  const storage = {getItem: (k) => (k in items ? items[k] : null), setItem: (k, v) => { items[k] = String(v); }};
  const startCollections = [];
  // lives: seconds a document stays after its game booted (null: reloaded before it booted)
  const run = (lives, count) => {
    for (const life of lives) {
      const {root: documentRoot} = makeRoot();
      documentRoot.performance.getEntriesByType = () => [{type: "reload"}];
      documentRoot.sessionStorage = storage;
      const documentContext = load(documentRoot);
      const debt = documentContext.heapDebtAtStart(documentContext);
      const plan = documentContext.heapStartPlan(debt);
      if (plan.collectOnStart === true) { startCollections.push(debt); documentContext.clearHeapDebt(documentContext); }
      else if (life !== null && life * 1000 >= plan.startDelayMs) documentContext.clearHeapDebt(documentContext);
    }
  };
  run([6, 6, 6, 6], 4);
  assert.deepEqual(startCollections, [], "reloads that wait for the title never sweep on the start path");
  run([1, 1, 1, 1, 1, 1], 6);
  assert.deepEqual(startCollections, [], "reloads 1 s after the game booted: debt 1 is not collected, debt 2 is, so it never reaches 3");
  run([null, null, null, null, null], 5);
  assert.deepEqual(startCollections, [3], "boots interrupted before the game booted: the third waiting heap is swept first thing");
}
console.log("heap guard smoke passed");
