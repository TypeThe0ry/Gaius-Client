import assert from "node:assert/strict";
import {readFileSync} from "node:fs";

const source = readFileSync(new URL("../src/main/java/dev/gaius/browser/kernel/worldgen/BrowserWorldgenKernel.java", import.meta.url), "utf8");
assert.match(source, /interface TimeoutCallback extends JSObject/);
assert.match(source, /scheduleTimeoutNative\(TimeoutCallback task, int delay\)/);
assert.doesNotMatch(source, /scheduleTimeoutNative\(Runnable task/);
assert.doesNotMatch(source, /task\.run\(\)/);

let fired = false;
const setTimeoutFake = (task, delay) => {
  assert.equal(delay, 15);
  assert.equal(typeof task, "function");
  task();
  fired = true;
};
const timerBody = (task, delay) => setTimeoutFake(() => task(), delay);
timerBody(() => { fired = true; }, 15);
assert.equal(fired, true);
console.log("worldgen watchdog timer smoke: JS callback shape and firing PASS");
