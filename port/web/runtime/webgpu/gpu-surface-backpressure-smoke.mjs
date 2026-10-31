import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(process.env.GAIUS_WEBGPU_GLUE || new URL("gpu-glue.js", import.meta.url), "utf8");

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return {promise, resolve, reject};
}

function fixture() {
  const context = vm.createContext({console, performance, Promise, Uint8Array, Date});
  const testSource = source.replace(
    "  root.GaiusWebGpu = G;\n",
    "  G.__testSurfaceSubmit=function(){noteSurfaceSubmission();};\n" +
      "  G.__testReleaseSurfaceGate=function(){releaseSurfaceGate();};\n" +
      "  root.GaiusWebGpu = G;\n"
  );
  vm.runInContext(testSource, context, {filename: "gpu-glue.js"});
  const gpu = context.GaiusWebGpu;
  const completions = [];
  let queueSubmits = 0;
  gpu.status = 2;
  gpu.device = {};
  gpu.queue = {
    submit() { queueSubmits++; },
    onSubmittedWorkDone() {
      const completion = deferred();
      completions.push(completion);
      return completion.promise;
    }
  };
  return {gpu, completions, get queueSubmits() { return queueSubmits; }};
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

{
  const {gpu, completions} = fixture();
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "below the cap must not wait");
  gpu.__testSurfaceSubmit();
  gpu.__testSurfaceSubmit();
  gpu.__testSurfaceSubmit();
  assert.equal(gpu.stats.surfaceSubmitPending, 3, "surface submissions are counted");
  const gate = gpu.waitForSurfaceBackpressure();
  assert.ok(gate instanceof Promise, "the fourth frame must wait behind the cap");
  completions.at(-1).resolve();
  await gate;
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 0, "completed gate retires the queued frames");
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "retired work opens the next frame");
}

{
  const {gpu, completions} = fixture();
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  gpu.__testSurfaceSubmit();
  completions.at(-1).resolve();
  await gate;
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 1, "completion retires only work covered by its queue promise");
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "later submissions remain pending without blocking early");
}

{
  const {gpu, completions} = fixture();
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  completions.at(-1).reject(new Error("device lost"));
  await gate;
  await flush();
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "a rejected completion must release the gate");
}

{
  const {gpu} = fixture();
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  gpu.__testReleaseSurfaceGate();
  await gate;
  assert.equal(gpu.stats.surfaceBackpressureWaits, 1, "teardown release does not create a second wait");
}

{
  const {gpu, completions} = fixture();
  gpu.submit();
  assert.equal(gpu.stats.surfaceSubmitPending, 0, "a non-surface submit is not counted");
  assert.equal(completions.length, 0, "a non-surface submit does not arm a surface completion");
}

console.log("WebGPU surface backpressure smoke passed: bounded gate, rejection, teardown release, non-surface submit");
