import assert from "node:assert/strict";
import vm from "node:vm";
import {KERNEL_TELEMETRY_EXPRESSION, kernelActivityFailures} from "./kernel-telemetry.mjs";

assert.deepEqual(kernelActivityFailures(null), ["No completed worldgen kernel jobs"]);
const kernel = {jobs: 12, completed: 12, fallbacks: 0, transient: 0, timeouts: 0, failures: 0, ringFallbacks: 0};
assert.deepEqual(kernelActivityFailures({worldgen: {kernel}}), []);
assert.deepEqual(kernelActivityFailures({worldgen: {kernel: {...kernel, transient: 1, ringFallbacks: 1,
  kinds: {BIOMES: {submitted: 1, fallback: 1, transient: 1}}}}}), []);
assert.equal(kernelActivityFailures({worldgen: {kernel: {...kernel, transient: 1}}}).length, 1);
assert.equal(kernelActivityFailures({worldgen: {kernel: {...kernel, completed: 0}}}).length, 1);
for (const name of ["fallbacks", "transient", "timeouts", "failures", "ringFallbacks"]) {
  assert.equal(kernelActivityFailures({worldgen: {kernel: {...kernel, [name]: 1}}}).length, 1);
  assert.equal(kernelActivityFailures({worldgen: {kernel: {...kernel, [name]: undefined}}}).length, 0);
}
const order = [{seq: 1, x: 7, z: -3, status: "terrain"}];
const snapshot = vm.runInNewContext(KERNEL_TELEMETRY_EXPRESSION, {
  GaiusWorldgenKernel: {snapshot: () => ({kernel, worldgen: {chunkGenerationOrder: order}})},
  __gaiusKernels: {telemetry: () => ({completed: 12})}, __gaiusKernelSemantics: {profile: "test"},
});
assert.equal(snapshot.worldgen.worldgen.chunkGenerationOrder, order);
assert.equal(snapshot.runtime.completed, 12);
assert.equal(vm.runInNewContext(KERNEL_TELEMETRY_EXPRESSION).worldgen, null);
console.log("kernel telemetry: activity, fallback, missing host and generation order checks passed");
