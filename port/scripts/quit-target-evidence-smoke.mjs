#!/usr/bin/env node
import assert from "node:assert/strict";
import {quitTargetDisappearance} from "./quit-target-evidence.mjs";

assert.deepEqual(quitTargetDisappearance({closedTarget: true, barrierComplete: true}), {
  accepted: true, reason: "quit barrier completion observed before target disappearance"
});
assert.deepEqual(quitTargetDisappearance({closedTarget: true, barrierComplete: true, crash: "renderer process gone"}), {
  accepted: false, reason: "renderer crashed: renderer process gone"
});
assert.deepEqual(quitTargetDisappearance({closedTarget: false, crash: "STATUS_CRASHED"}), {
  accepted: false, reason: "renderer crashed: STATUS_CRASHED"
});
assert.deepEqual(quitTargetDisappearance({closedTarget: true}), {
  accepted: false, reason: "target disappeared without quit completion or stopped-page evidence"
});
assert.deepEqual(quitTargetDisappearance({closedTarget: true, stoppedPageObserved: true}), {
  accepted: true, reason: "stopped page observed before target disappearance"
});

console.log("quit target evidence smoke passed");
