import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");
const context = vm.createContext({ console, performance });
vm.runInContext(source, context, { filename: "gpu-glue.js" });
const gpu = context.GaiusWebGpu;

const configured = [];
function canvas(name) {
  const context2d = {
    configure(descriptor) { configured.push({ name, descriptor }); }
  };
  return {
    name,
    width: 1,
    height: 1,
    getContext(kind) { return kind === "webgpu" ? context2d : null; }
  };
}
let current = canvas("first");
context.__gaiusCanvasHost = { current: () => current };
gpu.device = {};
gpu.queue = {};
gpu.status = 2;
gpu.canvasFormat = "rgba8unorm";

assert.equal(gpu.configureSurface(320, 200), true);
current = canvas("second");
assert.equal(gpu.configureSurface(640, 400), true);
assert.deepEqual(configured.map((entry) => entry.name), ["first", "second"],
  "re-entry must configure the replacement canvas");
assert.equal(gpu.canvas.name, "second");
console.log("WebGPU surface smoke passed: replacement canvas is rebound after re-entry");
