import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

// A pass that repeats the previous clear-only pass is folded (not begun). Commands that need the
// pass must still find one, a suspend/resume must continue it, and the WasmGC import table must
// keep the device-loss policy of the JSBody entries.
const source = readFileSync(process.env.GAIUS_WEBGPU_GLUE || new URL("gpu-glue.js", import.meta.url), "utf8");

function fixture() {
  const context = vm.createContext({ console, performance, Uint8Array, Int8Array });
  // Test-only seam: create handles and pipelines without a real device.
  const testSource = source.replace("  root.GaiusWebGpu = G;\n",
    "  G.__testPut=function(o){return put(o);};\n"
    + "  G.__testPipeline=function(label){return put({label:label,pipeline:{},valid:true});};\n"
    + "  G.__testViews=function(w,h){var c={label:'c'},d={label:'d'};viewExtents.set(c,{width:w,height:h});"
    + "viewExtents.set(d,{width:w,height:h});return {color:put(c),depth:put(d)};};\n"
    + "  root.GaiusWebGpu = G;\n");
  vm.runInContext(testSource, context, { filename: "gpu-glue.js" });
  const gpu = context.GaiusWebGpu;
  const begun = [];
  const commands = [];
  gpu.queue = { submit() {}, onSubmittedWorkDone() { return Promise.resolve(); } };
  gpu.device = {
    limits: {},
    createCommandEncoder() {
      return {
        beginRenderPass(descriptor) {
          begun.push(descriptor);
          return {
            setPipeline() { commands.push("pipeline"); },
            setScissorRect(x, y, w, h) {
              commands.push(x === 0 && y === 0 && w === 4 && h === 4 ? "scissor" : "scissor:" + [x, y, w, h].join(","));
            },
            draw() { commands.push("draw"); },
            drawIndexed() { commands.push("drawIndexed"); },
            end() { commands.push("end"); }
          };
        },
        copyBufferToBuffer() {},
        finish() { return {}; }
      };
    }
  };
  const color = gpu.__testPut({ label: "color" });
  const depth = gpu.__testPut({ label: "depth" });
  function clearPass(depthValue = 1) {
    gpu.passColor(0, color, true, 0, 0, 0, 1);
    gpu.passDepth(depth, true, depthValue, false);
    gpu.passBegin(1);
  }
  return { gpu, begun, commands, clearPass };
}

{
  const { gpu, begun, commands, clearPass } = fixture();
  clearPass();
  gpu.passEnd();
  clearPass();
  assert.equal(begun.length, 1, "an identical clear-only pass is folded");
  assert.equal(gpu.stat("clearFolds"), 1);
  gpu.setPipeline(gpu.__testPipeline("p"));
  gpu.setScissor(0, 0, 4, 4);
  gpu.draw(3, 1, 0, 0);
  gpu.passEnd();
  assert.notEqual(gpu.externalEncoder(), null, "a held pass ends for page-side encoders");
  assert.equal(begun.length, 2, "a command on a folded pass begins it after all");
  assert.equal(begun[1].colorAttachments[0].loadOp, "clear", "the opened pass keeps its own clear");
  assert.deepEqual(commands, ["end", "pipeline", "scissor", "draw", "end"]);
  assert.equal(gpu.stat("clearFoldsOpened"), 1);
}

{
  const { gpu, begun, commands, clearPass } = fixture();
  clearPass();
  gpu.passEnd();
  clearPass();
  gpu.passEnd();
  clearPass();
  gpu.passEnd();
  assert.equal(begun.length, 1, "folded passes that stay empty never reach the encoder");
  assert.deepEqual(commands, ["end"]);
}

{
  const { gpu, begun, clearPass } = fixture();
  clearPass();
  gpu.passEnd();
  clearPass();
  gpu.passSuspend();
  gpu.passResume();
  gpu.drawIndexed(3, 1, 0, 0, 0);
  gpu.passEnd();
  assert.equal(begun.length, 2, "suspend/resume of a folded pass begins one pass");
  assert.equal(begun[1].colorAttachments[0].loadOp, "load", "the resumed pass loads the cleared target");
  assert.equal(begun[1].depthStencilAttachment.depthLoadOp, "load");
}

{
  const { gpu, begun, clearPass } = fixture();
  clearPass(1);
  gpu.passEnd();
  clearPass(0.5);
  gpu.passEnd();
  assert.equal(begun.length, 2, "a different depth clear value is not folded");
}

{
  const { gpu, begun, clearPass } = fixture();
  clearPass();
  gpu.draw(3, 1, 0, 0);
  gpu.passEnd();
  clearPass();
  gpu.passEnd();
  assert.equal(begun.length, 2, "a pass after one that drew is not folded");
}

{
  const { gpu, begun, clearPass } = fixture();
  clearPass();
  gpu.passEnd();
  clearPass();
  assert.equal(gpu.externalEncoder(), null, "a folded pass counts as an open pass for page-side encoders");
  gpu.submit();
  clearPass();
  gpu.passEnd();
  assert.equal(begun.length, 2, "a clear is not folded across a submit");
}

{
  // A pass that drew is held at passEnd: the next pass on the same views with load operations
  // continues it (scissor back to the whole view), anything else ends it first.
  const { gpu, begun, commands } = fixture();
  const views = gpu.__testViews(64, 32);
  const loadPass = () => {
    gpu.passColor(0, views.color, false, 0, 0, 0, 1);
    gpu.passDepth(views.depth, false, 1, false);
    gpu.passBegin(1);
  };
  loadPass();
  gpu.setPipeline(gpu.__testPipeline("p"));
  gpu.setScissor(1, 2, 3, 4);
  gpu.draw(3, 1, 0, 0);
  gpu.passEnd();
  loadPass();
  gpu.setPipeline(gpu.__testPipeline("q"));
  gpu.draw(3, 1, 0, 0);
  gpu.passEnd();
  assert.equal(begun.length, 1, "the second stage continues the first pass");
  assert.equal(gpu.stat("passesMerged"), 1);
  assert.deepEqual(commands, ["pipeline", "scissor:1,2,3,4", "draw", "scissor:0,0,64,32", "pipeline", "draw"]);
  gpu.passColor(0, views.color, true, 0, 0, 0, 1);
  gpu.passBegin(1);
  assert.equal(begun.length, 2, "a clear begins a new pass");
  assert.equal(commands[commands.length - 1], "end", "after ending the held one");
}

{
  const { gpu, begun, commands } = fixture();
  const table = {};
  gpu.installImports(table);
  const imports = table.gaiusGpu;
  const color = gpu.__testPut({ label: "color" });
  gpu.lostReason = "test loss";
  assert.doesNotThrow(() => {
    imports.passColor(0, color, 1, 0, 0, 0, 1);
    imports.passBegin(1);
    imports.setPipeline(1);
    imports.draw(3, 1, 0, 0);
    imports.passSuspend();
    imports.passResume();
    imports.passEnd();
    imports.bindBuffer(0, 1, 0, 16);
    imports.copyBufferToBuffer(1, 0, 2, 0, 16);
    imports.blitToSurface(color);
  }, "imports unwind after device loss instead of throwing");
  assert.equal(imports.createBindGroup(1, 0), 0, "createBindGroup import returns its loss default");
  assert.equal(imports.createFence(), 0, "createFence import returns its loss default");
  assert.equal(imports.submit(), false, "submit import fails closed");
  assert.equal(imports.configureSurface(4, 4), 0, "configureSurface import fails closed");
  assert.equal(imports.acquireSurface(), 0, "acquireSurface import fails closed");
  assert.equal(begun.length, 0, "no pass begins on a lost device");
  assert.deepEqual(commands, []);
}

console.log("WebGPU clear-fold and import loss smoke passed");
