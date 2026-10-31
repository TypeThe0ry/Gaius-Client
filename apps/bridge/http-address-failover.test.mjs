import assert from "node:assert/strict";
import test from "node:test";
import {withAddressFailover} from "./dist/http-address-failover.js";

test("pinned HTTP fetch falls back across every validated address in order", async () => {
  const tried = [];
  const result = await withAddressFailover([
    {address: "203.0.113.10", family: 4},
    {address: "2001:db8::10", family: 6},
  ], async (candidate) => {
    tried.push(candidate.address);
    if (candidate.family === 4) throw new Error("connection refused");
    return "second address response";
  });
  assert.equal(result, "second address response");
  assert.deepEqual(tried, ["203.0.113.10", "2001:db8::10"]);
});

test("pinned HTTP fetch reports the final address failure", async () => {
  await assert.rejects(withAddressFailover([{address: "203.0.113.10"}], async () => {
    throw new Error("unreachable");
  }), /unreachable/);
});
