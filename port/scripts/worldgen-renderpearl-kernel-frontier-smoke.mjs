#!/usr/bin/env node
/*
 * Browser-free regression for the 26.3 worldgen status-order contract.
 *
 * The four chunks below are the terrain/feature mismatch witnesses from the
 * 20261014 browser run.  26.3 enters BIOMES and the merged buildTerrain step
 * through one dependency frontier.  Model callback delivery in arbitrary
 * order: separate status queues expose multiple FEATURES-visible commit
 * orders, while one frontier has one order for every delivery permutation.
 */
import assert from "node:assert/strict";
import {createHash} from "node:crypto";

const CHUNKS = ["-34,-11", "-32,-13", "-35,-5", "-35,-10"];
const EVENTS = CHUNKS.flatMap(chunk => [
  {id: `${chunk}:biomes`, stage: "BIOMES", chunk, after: null},
  {id: `${chunk}:terrain`, stage: "TERRAIN", chunk, after: `${chunk}:biomes`},
]);

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
  const byId = new Map(EVENTS.map(event => [event.id, event]));
  const claimed = new Set();
  const complete = new Set();
  const queues = mode === "unified"
    ? new Map([["ALL", EVENTS.slice()]])
    : new Map(["BIOMES", "TERRAIN"].map(stage => [stage,
      EVENTS.filter(event => event.stage === stage)]));
  const heads = new Map([...queues.keys()].map(key => [key, 0]));
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
        if (event.stage === "TERRAIN") {
          log.push(`FEATURES@${event.chunk}:${log.join(",")}`);
        }
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
  `separate status queues unexpectedly stable: ${[...separate].join(",")}`);
assert.equal(unified.size, 1,
  `unified status frontier changed digest: ${[...unified].join(",")}`);
console.log(`ok   chunks: ${CHUNKS.join(" ")}`);
console.log(`ok   separate status queues: ${separate.size} FEATURES-visible digests over 64 callback orders`);
console.log(`ok   unified status frontier: ${unified.size} FEATURES-visible digest over 64 callback orders`);
console.log(`digest ${[...unified][0]}`);
