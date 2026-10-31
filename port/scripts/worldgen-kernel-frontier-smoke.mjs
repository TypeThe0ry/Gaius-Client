#!/usr/bin/env node
/*
 * Browser-free regression for the 26.2 kernel bridge's status-order contract.
 *
 * Kernel callbacks arrive in arbitrary order, while the Java status tasks are
 * registered in one dependency order.  The old bridge used separate BIOMES,
 * NOISE and SURFACE tails; that preserved order only inside one status and let
 * a later status cross an earlier install.  The production bridge now uses one
 * KernelStatusFrontier for all three statuses.  This model exercises the same
 * claim/deliver behavior without starting TeaVM: separate tails produce more
 * than one CARVERS-visible commit digest, while one frontier is stable.
 */
import assert from "node:assert/strict";
import {createHash} from "node:crypto";

const EVENTS = [
  {id: "A-biomes", stage: "BIOMES", chunk: "A", after: null},
  {id: "A-noise", stage: "NOISE", chunk: "A", after: "A-biomes"},
  {id: "B-biomes", stage: "BIOMES", chunk: "B", after: null},
  {id: "B-noise", stage: "NOISE", chunk: "B", after: "B-biomes"},
  {id: "A-surface", stage: "SURFACE", chunk: "A", after: "A-noise"},
  {id: "B-surface", stage: "SURFACE", chunk: "B", after: "B-noise"},
];

function shuffle(values, seed) {
  const out = values.slice();
  let x = seed >>> 0;
  for (let i = out.length - 1; i > 0; i--) {
    x = (Math.imul(x, 1664525) + 1013904223) >>> 0;
    const j = x % (i + 1);
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

function digest(log) {
  return createHash("sha256").update(log.join("|"), "utf8").digest("hex");
}

function run(mode, seed) {
  const byId = new Map(EVENTS.map((event) => [event.id, event]));
  const claimed = new Set();
  const complete = new Set();
  const queues = mode === "unified"
    ? new Map([["ALL", EVENTS.slice()]])
    : new Map(["BIOMES", "NOISE", "SURFACE"].map((stage) => [stage,
      EVENTS.filter((event) => event.stage === stage)]));
  const heads = new Map([...queues.keys()].map((key) => [key, 0]));
  const log = [];

  function ready(event) {
    return event.after === null || complete.has(event.after);
  }

  function drain() {
    let progressed = true;
    while (progressed) {
      progressed = false;
      for (const [queueName, queue] of queues) {
        const index = heads.get(queueName);
        const event = queue[index];
        if (!event || !claimed.has(event.id) || !ready(event)) continue;
        heads.set(queueName, index + 1);
        complete.add(event.id);
        log.push(event.id);
        // CARVERS starts as soon as this chunk's SURFACE status future settles;
        // it sees the global Java-visible commit frontier at that instant.
        if (event.stage === "SURFACE") log.push(`CARVERS@${event.chunk}:${log.join(",")}`);
        progressed = true;
      }
    }
  }

  for (const event of shuffle(EVENTS, seed)) {
    assert.equal(byId.get(event.id), event);
    claimed.add(event.id);
    drain();
  }
  drain();
  assert.equal(complete.size, EVENTS.length, `${mode} left a status ticket pending`);
  return digest(log);
}

const separate = new Set();
const unified = new Set();
for (let seed = 1; seed <= 64; seed++) {
  separate.add(run("separate", seed));
  unified.add(run("unified", seed));
}

assert.ok(separate.size > 1,
  `separate status tails unexpectedly stable: ${[...separate].join(",")}`);
assert.equal(unified.size, 1,
  `unified status frontier changed digest: ${[...unified].join(",")}`);
console.log(`ok   separate status tails: ${separate.size} CARVERS-visible digests over 64 callback orders`);
console.log(`ok   unified status frontier: ${unified.size} CARVERS-visible digest over 64 callback orders`);
console.log(`digest ${[...unified][0]}`);

// Prefetch-only contract: callback latency is arbitrary, but status tasks consume
// in the vanilla order. Completion must remain task ordered and bounded by budget.
function prefetchRun(seed, budget) {
  const tasks = ["TERRAIN:A", "TERRAIN:B", "SURFACE:A", "SURFACE:B"];
  const readyAt = shuffle([0, 1, 2, 3], seed).map((v) => v * 7);
  const completed = [];
  const outcomes = [];
  let now = 0;
  for (let i = 0; i < tasks.length; i++) {
    const wait = Math.max(0, readyAt[i] - now);
    if (wait > budget) {
      outcomes.push(`${tasks[i]}:vanilla`);
      now += budget;
    } else {
      outcomes.push(`${tasks[i]}:kernel`);
      now += wait;
    }
    completed.push(tasks[i]);
  }
  return {completed: completed.join(","), outcomes: outcomes.join(",")};
}
const bounded = new Set();
for (let seed = 1; seed <= 64; seed++) bounded.add(prefetchRun(seed, 8).completed);
assert.equal(bounded.size, 1, `prefetch completion order changed: ${[...bounded].join(";")}`);
const budgeted = prefetchRun(17, 2);
assert.match(budgeted.outcomes, /:vanilla/);
console.log(`ok   prefetch completion order: ${[...bounded][0]}`);
console.log(`ok   short budget fallback: ${budgeted.outcomes}`);
