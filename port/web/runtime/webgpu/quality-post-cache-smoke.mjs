#!/usr/bin/env node
// Runs quality-post.js against a recording WebGPU fake and checks what the GPU would execute:
// for every pass at submit time, its pipeline, target, scissor, draw and bound resources, with the
// uniform bytes each binding sees once the queue has applied every write before the submit.
// Checks: the bind groups and layouts of a steady frame are reused (no createBindGroup /
// getBindGroupLayout), the uniform blocks reach the buffer in one writeBuffer per frame, a resize
// rebuilds what it must, and the executed frames match a fresh state (the reference) frame for
// frame. Two post chains recorded into different encoders before one submit (a surface blit
// finishes the encoder; the glue submits at present) must each see their own uniforms, also when
// they outgrow the uniform ring. GAIUS_QUALITY_POST=<path> runs another copy (used to compare against an older version).
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const sourcePath = process.env.GAIUS_QUALITY_POST || new URL("./quality-post.js", import.meta.url);
const source = readFileSync(sourcePath, "utf8");

export function makeHarness() {
  const counts = {createBindGroup: 0, getBindGroupLayout: 0, writeBuffer: 0, createTexture: 0, submit: 0};
  let nextLabel = 0;
  const buffers = [];
  const queueOps = [];
  const executed = [];
  const device = {
    createBuffer(desc) { const b = {label: desc.label, bytes: new Uint8Array(desc.size), destroy() {}}; buffers.push(b); return b; },
    createSampler(desc) { return {label: "sampler:" + JSON.stringify(desc)}; },
    createShaderModule(desc) { return {label: desc.label}; },
    createRenderPipeline(desc) {
      return {label: desc.label, getBindGroupLayout(index) { counts.getBindGroupLayout++; return {label: desc.label + "#layout" + index, n: nextLabel++}; }};
    },
    createTexture(desc) {
      counts.createTexture++;
      const t = {label: desc.label + "@" + desc.size.width + "x" + desc.size.height + "#" + (nextLabel++), format: desc.format,
        destroy() { t.destroyed = true; },
        createView(d) { return {label: "view(" + t.label + (d ? "," + JSON.stringify(d) : "") + ")", texture: t}; }};
      return t;
    },
    createBindGroup(desc) { counts.createBindGroup++; return {layout: desc.layout, entries: desc.entries.map(e => ({binding: e.binding, resource: e.resource}))}; },
    queue: {
      writeBuffer(buffer, offset, data, dataOffset, size) {
        counts.writeBuffer++;
        queueOps.push({type: "write", buffer, offset, bytes: new Uint8Array(data.slice(dataOffset, dataOffset + size))});
      },
      submit(list) { counts.submit++; queueOps.push({type: "submit", list}); },
    },
  };
  function encoder() {
    const passes = [];
    return {passes,
      beginRenderPass(desc) {
        const rp = {target: desc.colorAttachments[0].view.label, load: desc.colorAttachments[0].loadOp};
        passes.push(rp);
        return {setPipeline(p) { rp.pipeline = p.label; }, setBindGroup(i, g) { rp.group = g; },
          setScissorRect(...a) { rp.scissor = a.join(","); }, draw(...a) { rp.draw = a.join(","); }, end() {}};
      },
      finish() { return {passes}; }};
  }
  // Applies the queue in order; a submitted pass reads its uniform ranges from the buffer state at
  // that point, exactly as the GPU would.
  function drain() {
    for (const op of queueOps.splice(0)) {
      if (op.type === "write") op.buffer.bytes.set(op.bytes, op.offset);
      else for (const cb of op.list) for (const rp of cb.passes) {
        executed.push({target: rp.target, load: rp.load, pipeline: rp.pipeline, scissor: rp.scissor, draw: rp.draw,
          layout: rp.group.layout.label,
          bindings: rp.group.entries.map(e => e.binding + "=" + (e.resource.buffer
            ? Buffer.from(e.resource.buffer.bytes.subarray(e.resource.offset, e.resource.offset + e.resource.size)).toString("hex")
            : e.resource.label)).join(" ")});
      }
    }
  }
  let current = null;
  const finished = []; // finished but not submitted (surface parts), in command order
  // Like gpu-glue: submitCount moves when the frame's command buffers reach the queue.
  const gpu = {device, submitCount: 0, externalEncoder() { if (!current) current = encoder(); return current; }};
  const context = vm.createContext({GaiusWebGpu: gpu, performance: {now: () => 0}, Float32Array, Uint8Array, Map, WeakMap, Object, Math, String, Number});
  context.globalThis = context;
  vm.runInContext(source, context, {filename: "quality-post.js"});
  const post = context.GaiusQuality.webgpuPost;
  return {
    counts, executed, post,
    frame(frame, settings) {
      const ran = post.run(frame, settings);
      this.submit();
      return ran;
    },
    // Runs the chain and finishes its encoder without submitting (a blit before the present).
    encodeOnly(frame, settings) {
      const ran = post.run(frame, settings);
      if (current) { finished.push(current.finish()); current = null; }
      return ran;
    },
    submit() {
      if (current) { finished.push(current.finish()); current = null; }
      if (finished.length > 0) device.queue.submit(finished.splice(0));
      gpu.submitCount++;
      drain();
    },
    device,
  };
}

const settings = {enabled: true, fxaa: true, tonemap: true, ssao: true, bloom: true, ssr: false,
  ssaoRadius: 1.5, ssaoIntensity: 0.8, bloomThreshold: 0.7, bloomIntensity: 0.35};
function frameAt(device, color, depth, width, height, k) {
  return {color, depth, width, height, rectWidth: width - 7, rectHeight: height - 3, zeroToOne: true,
    projection: [1.2 + k / 100, 1.7, 0.01 * k, -0.02, -1.0001, -0.1 - k / 1000, -1, 0]};
}

const isMain = /quality-post-cache-smoke\.mjs$/.test(process.argv[1] || "");
if (isMain) {
  const h = makeHarness();
  const color = h.device.createTexture({label: "color", size: {width: 1280, height: 720}, format: "rgba8unorm"});
  const depth = h.device.createTexture({label: "depth", size: {width: 1280, height: 720}, format: "depth32float"});
  assert.equal(h.frame(frameAt(h.device, color, depth, 1280, 720, 0), settings), true);
  const firstGroups = h.counts.createBindGroup, firstLayouts = h.counts.getBindGroupLayout;
  const writesBefore = h.counts.writeBuffer;
  assert.equal(h.frame(frameAt(h.device, color, depth, 1280, 720, 1), settings), true);
  assert.equal(h.counts.createBindGroup, firstGroups, "a steady frame must reuse every bind group");
  assert.equal(h.counts.getBindGroupLayout, firstLayouts, "a steady frame must reuse every layout");
  assert.equal(h.counts.writeBuffer - writesBefore, 1, "a frame's uniform blocks must reach the buffer in one write");
  const perFrame = h.executed.length / 2;
  // Executed passes match a fresh state that encodes the same frame from scratch.
  for (const [k, w, hgt] of [[1, 1280, 720], [2, 1280, 720], [3, 1024, 600], [4, 1024, 600]]) {
    const ref = makeHarness();
    const c2 = k >= 3 ? h.device.createTexture({label: "color", size: {width: w, height: hgt}, format: "rgba8unorm"}) : color;
    const d2 = k >= 3 ? h.device.createTexture({label: "depth", size: {width: w, height: hgt}, format: "depth32float"}) : depth;
    if (k >= 3) {
      assert.equal(h.frame(frameAt(h.device, c2, d2, w, hgt, k), settings), true);
    } else if (k === 2) {
      assert.equal(h.frame(frameAt(h.device, color, depth, w, hgt, k), settings), true);
    }
    const got = h.executed.slice(-perFrame);
    const rc = ref.device.createTexture({label: "x", size: {width: w, height: hgt}, format: "rgba8unorm"});
    const rd = ref.device.createTexture({label: "y", size: {width: w, height: hgt}, format: "depth32float"});
    assert.equal(ref.frame(frameAt(ref.device, rc, rd, w, hgt, k), settings), true);
    const strip = rows => rows.map(r => ({...r, target: r.target.replace(/#\d+/g, "#"), bindings: r.bindings.replace(/#\d+/g, "#"),
      layout: r.layout.replace(/#\d+$/, "")}));
    const norm = rows => strip(rows).map(r => JSON.stringify(r).replace(/(color|depth|x|y)@/g, "frame@"));
    assert.deepEqual(norm(got), norm(ref.executed), `frame ${k} must execute what a fresh state executes`);
  }
  // SSR adds the depth binding to the composite and a separate pipeline.
  const ssr = {...settings, ssr: true};
  assert.equal(h.frame(frameAt(h.device, color, depth, 1280, 720, 5), ssr), true);
  // Two chains in different encoders before one submit (and then enough of them to outgrow the
  // ring): every chain executes what a fresh state executes for its frame, in order.
  for (const count of [2, 40]) {
    const t = makeHarness();
    const c = t.device.createTexture({label: "color", size: {width: 1280, height: 720}, format: "rgba8unorm"});
    const d = t.device.createTexture({label: "depth", size: {width: 1280, height: 720}, format: "depth32float"});
    assert.equal(t.frame(frameAt(t.device, c, d, 1280, 720, 0), settings), true);
    const start = t.executed.length;
    for (let k = 1; k <= count; k++) assert.equal(t.encodeOnly(frameAt(t.device, c, d, 1280, 720, 10 + k), settings), true);
    t.submit();
    const got = t.executed.slice(start);
    assert.equal(got.length, perFrame * count);
    if (count * perFrame > 256) assert.ok(t.post.stats.uniformGrows > 0, "the chains must have outgrown the ring");
    for (let k = 1; k <= count; k++) {
      const ref = makeHarness();
      const rc = ref.device.createTexture({label: "x", size: {width: 1280, height: 720}, format: "rgba8unorm"});
      const rd = ref.device.createTexture({label: "y", size: {width: 1280, height: 720}, format: "depth32float"});
      assert.equal(ref.frame(frameAt(ref.device, rc, rd, 1280, 720, 10 + k), settings), true);
      const strip = rows => rows.map(r => ({...r, target: r.target.replace(/#\d+/g, "#"), bindings: r.bindings.replace(/#\d+/g, "#"),
        layout: r.layout.replace(/#\d+$/, "")}));
      const norm = rows => strip(rows).map(r => JSON.stringify(r).replace(/(color|depth|x|y)@/g, "frame@"));
      assert.deepEqual(norm(got.slice((k - 1) * perFrame, k * perFrame)), norm(ref.executed),
        `chain ${k} of ${count} recorded before one submit must execute with its own uniforms`);
    }
    // After the submit the ring restarts: a steady frame again reuses its bind groups.
    t.frame(frameAt(t.device, c, d, 1280, 720, 99), settings);
    const groups = t.counts.createBindGroup;
    t.frame(frameAt(t.device, c, d, 1280, 720, 98), settings);
    assert.equal(t.counts.createBindGroup, groups, `after ${count} chains a steady frame must reuse its bind groups`);
  }
  console.log(`quality post cache smoke PASS (${perFrame} passes/frame, ${firstGroups} groups built once)`);
}
