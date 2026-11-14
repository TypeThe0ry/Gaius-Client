import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";
import {VERSION_PROFILES} from "./verification-profiles.mjs";

const source = readFileSync(process.env.INIT_SNAPSHOT_SOURCE || new URL("../src/main/java/dev/gaius/browser/InitSnapshotAdoption.java", import.meta.url), "utf8");
const script = /@JSBody\(script = """([\s\S]*?)"""\)\s*private static native boolean browserSnapshotVerified/.exec(source)?.[1];
assert.ok(script, "exercise the production Java-to-browser gate");
function verified(state, profile) {
  return vm.runInNewContext(`(function(){${script}})()`, {
    __gaiusInitSnapshotState: state, __gaiusProfileId: profile,
  });
}

for (const profile of VERSION_PROFILES) {
  test(`snapshot adoption matches the active profile ${profile.id}`, () => {
    assert.equal(verified({status: "verified", profile: profile.id}, profile.id), true);
    for (const other of VERSION_PROFILES.filter(other => other.id !== profile.id)) {
      assert.equal(verified({status: "verified", profile: other.id}, profile.id), false);
    }
    for (const status of ["absent", "loading", "decoded", "invalid", "unverified", "mismatch"]) {
      assert.equal(verified({status, profile: profile.id}, profile.id), false);
    }
    for (const missing of [undefined, null, "", 0]) {
      assert.equal(verified({status: "verified", profile: profile.id}, missing), false);
    }
    assert.equal(verified(null, profile.id), false);
    assert.equal(verified({status: "verified"}, profile.id), false);
  });
}
