#!/usr/bin/env node
import assert from "node:assert/strict";
import {CANONICAL_PROFILE, escapeRegExp} from "./verification-profiles.mjs";

for (const value of [CANONICAL_PROFILE.id, "a+b\\c", "x[0]", "name|alt"]) {
  const pattern = new RegExp(`^${escapeRegExp(value)}$`);
  assert.ok(pattern.test(value), `complete regex escaping preserves ${value}`);
}
assert.ok(!new RegExp(`^${escapeRegExp(CANONICAL_PROFILE.id)}$`).test("26x3"));
console.log("VERIFICATION_PROFILES_ESCAPE_TEST PASS");
