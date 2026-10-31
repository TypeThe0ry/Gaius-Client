import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");

let resolveLost;
const lost = new Promise((resolve) => { resolveLost = resolve; });
let configureCalls = 0;
let submitCalls = 0;
let prompt = null;
const buttons = [];
let reloadCalls = 0;
const context = {
  console: { warn() {}, info() {} },
  navigator: {
    gpu: {
      async requestAdapter() {
        return {
          info: {},
          features: new Set(),
          limits: {},
          async requestDevice() {
            return {
              features: new Set(),
              limits: {},
              queue: {
                submit() { submitCalls++; },
                onSubmittedWorkDone() { return Promise.resolve(); }
              },
              lost,
              destroy() {},
              addEventListener() {}
            };
          }
        };
      },
      getPreferredCanvasFormat() { return "bgra8unorm"; }
    }
  },
  document: {
    body: {
      appendChild(element) { prompt = element; }
    },
    createElement() {
      return {
        id: "",
        style: {},
        textContent: "",
        setAttribute() {},
        appendChild() {},
        addEventListener(type, handler) {
          if (type === "click") buttons.push({ element: this, handler });
        },
        focus() {}
      };
    },
    getElementById(id) { return prompt && prompt.id === id ? prompt : null; },
    exitPointerLock() {}
  },
  location: { href: "https://example.test/", reload() { reloadCalls++; } },
  URL,
  setTimeout,
  clearTimeout,
  Promise,
  Set,
  WeakMap,
  Map,
  Uint8Array,
  Int32Array,
  Math,
  Date
};
const canvas = {
  width: 320,
  height: 200,
  getContext(kind) {
    assert.equal(kind, "webgpu");
    return {
      configure() { configureCalls++; },
      getCurrentTexture() { throw new Error("must not acquire after loss"); }
    };
  }
};
context.__gaiusCanvasHost = { current: () => canvas };
vm.createContext(context);
vm.runInContext(source, context, { filename: "gpu-glue.js" });
const gpu = context.GaiusWebGpu;

assert.equal(await gpu.init(canvas), true, "fake device should initialise");
gpu.ringConfigure(256, 256, 1024);
assert.equal(gpu.configureSurface(320, 200), true);
assert.equal(gpu.acquireSurface(), true);
assert.equal(configureCalls, 1);

resolveLost({ reason: "destroyed", message: "test loss" });
await Promise.resolve();
await Promise.resolve();

assert.equal(gpu.state(), -1, "device.lost must make the glue unavailable");
assert.equal(gpu.deviceUsable(), false, "the shared GPU gate must close after loss");
assert.match(gpu.failure, /WebGPU device lost: destroyed: test loss/);
assert.equal(gpu.configureSurface(640, 400), false, "configure must fail closed after loss");
assert.equal(gpu.acquireSurface(), false, "acquire must fail closed after loss");
assert.equal(gpu.submit(), false, "submit must fail closed after loss");
assert.equal(gpu.present(), false, "present must fail closed after loss");
assert.equal(configureCalls, 1, "loss must not configure the canvas again");
assert.equal(submitCalls, 0, "loss must not submit to the old queue");
assert.ok(prompt && prompt.id === "gaius-gpu-lost", "device loss must show the reload prompt");
assert.deepEqual(buttons.map((button) => button.element.textContent), ["Reload with WebGL 2", "Reload"],
  "loss prompt must expose both recovery actions");
buttons[0].handler();
assert.match(context.location.href, /[?&]gpu=webgl2(?:&|$)/, "WebGL 2 action must select the fallback");
buttons[1].handler();
assert.equal(reloadCalls, 1, "reload action must request a page reload");
assert.equal(gpu.stat("deviceLost"), 1, "an in-use device loss must be counted");
console.log("WebGPU device-loss promise smoke passed");
