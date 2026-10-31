import assert from "node:assert/strict";
import {test} from "node:test";
import {DEFAULT_RELOAD_MEMORY_BUDGET_BYTES, parseMemoryBudget, reloadMemoryGate} from "./reload-memory-gate.mjs";

test("reload memory budget parsing uses byte units and the 0.75 GiB default", () => {
  assert.equal(parseMemoryBudget(), DEFAULT_RELOAD_MEMORY_BUDGET_BYTES);
  assert.equal(parseMemoryBudget("0.75GiB"), 0.75 * 1024 ** 3);
  assert.equal(parseMemoryBudget("512MiB"), 512 * 1024 ** 2);
  assert.equal(parseMemoryBudget("1000000"), 1000000);
  assert.throws(() => parseMemoryBudget("-1GiB"), /invalid reload memory budget/);
});

test("reload memory gate compares the first and last reload cycle", () => {
  const samples = [{cycle: 0, rendererPrivateBytes: 100}, {cycle: 1, rendererPrivateBytes: 150}, {cycle: 2, rendererPrivateBytes: 180}];
  assert.equal(reloadMemoryGate(samples, 80).pass, true);
  assert.equal(reloadMemoryGate(samples, 79).pass, false);
  assert.equal(reloadMemoryGate(samples, 79).growthBytes, 80);
});

test("reload memory gate fails when either endpoint lacks private bytes", () => {
  const gate = reloadMemoryGate([{cycle: 0, rendererPrivateBytes: null}, {cycle: 1, rendererPrivateBytes: 10}], 100);
  assert.equal(gate.pass, false);
  assert.equal(gate.growthBytes, null);
  assert.match(gate.reason, /unavailable/);
});
