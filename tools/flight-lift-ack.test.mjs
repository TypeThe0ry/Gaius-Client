import assert from "node:assert/strict";
import test from "node:test";
import {flightLiftAcknowledged, flightLiftPositionApplied, waitForFlightLift} from "./flight-lift-ack.mjs";

const from = {x: -495.5, y: 95, z: -143.5, yaw: 0, pitch: 0};
const target = {...from, y: 195};

async function observe(samples) {
  let clock = 0, index = 0;
  return waitForFlightLift({from, target, readPlayer: async () => samples[Math.min(index++, samples.length - 1)],
    now: () => clock, sleep: async ms => { clock += ms; }, timeoutMs: 500});
}

test("gravity can pass the exact destination before the first client observation", async () => {
  const falling = {...target, y: 194.766};
  assert.equal(Math.hypot(falling.x - target.x, falling.y - target.y, falling.z - target.z) < 0.1, false);
  const result = await observe([from, falling]);
  assert.equal(result.ok, true);
  assert.equal(result.polls, 2);
  assert.equal(result.player.y, 194.766);
});

test("an acknowledged but unapplied teleport still times out", async () => {
  const result = await observe([from]);
  assert.equal(result.ok, false);
  assert.equal(result.elapsedMs, 500);
});

test("wrong destination, heading, stale position and an expired lift fail", async () => {
  for (const player of [{...target, x: target.x + 1}, {...target, z: target.z + 1},
    {...target, yaw: 1}, {...target, pitch: 1}, {...target, y: target.y - 4.01}, from, null]) {
    assert.equal((await observe([player])).ok, false);
  }
});

test("a small ordinary movement cannot masquerade as the flight lift", () => {
  assert.equal(flightLiftPositionApplied(from, {...target, y: from.y + 1}, {...from, y: from.y + 1}), false);
  assert.equal(flightLiftPositionApplied(from, target, {...target, yaw: 360}), true);
});

test("server acknowledgement must name the current lift destination", () => {
  const response = "console.info [INFO] Minecraft: [System] [CHAT] Teleported M4Check to -495.500000, 195.000000, -143.500000";
  assert.equal(flightLiftAcknowledged(response, target), true);
  assert.equal(flightLiftAcknowledged(response.replace("195.000000", "175.000000"), target), false);
  assert.equal(flightLiftAcknowledged(response.replace("-143.500000", "-159.500000"), target), false);
  assert.equal(flightLiftAcknowledged("No entity was found", target), false);
});
