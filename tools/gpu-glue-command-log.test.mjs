import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

test("WebGPU glue reuses descriptors without changing command arguments", async () => {
  const source = await readFile(new URL("../port/web/runtime/webgpu/gpu-glue.js", import.meta.url), "utf8");
  assert.match(source, /colorAttachmentPool\s*=\s*\[\[\], \[\], \[\], \[\]\]/);
  assert.match(source, /pass\.setBindGroup\(group, get\(bindGroupHandle\), offsets, 0, count\)/);
  assert.match(source, /var cv = attachment\.clearValue;\s*cv\.r = r; cv\.g = g; cv\.b = b; cv\.a = a;/s);
  const setBindGroup = source.match(/G\.setBindGroup\s*=\s*function[\s\S]*?\n  };\n\n  \/\*\* For pipelines/,)?.[0] || "";
  assert.equal((setBindGroup.match(/pass\.setBindGroup\(/g) || []).length, 2);
  assert.doesNotMatch(setBindGroup, /new (?:Uint32Array|Array)\(/);
});

test("WebGPU pipeline idle work can prewarm before the first present", async () => {
  const source = await readFile(new URL("../port/web/runtime/webgpu/gpu-glue.js", import.meta.url), "utf8");
  const schedule = source.match(/function schedulePipelineIdleWork\(\) \{[\s\S]*?\n  \}/)?.[0] || "";
  assert.match(schedule, /!deviceInUse/);
  assert.doesNotMatch(schedule, /firstPresentAt\s*===\s*0/);
  const deviceUse = source.match(/function markDeviceInUse\(\) \{[\s\S]*?\n  \}/)?.[0] || "";
  assert.match(deviceUse, /gpuIndirectPipeline\("frustum"\)/);
});
