#!/usr/bin/env node
// The WebGPU post chain with render passes and with its compute path must write the same bytes.
//
//   OUT=<scratch dir> node port/web/runtime/webgpu/quality-post-compute-parity.mjs
//
// Runs quality-post.js in headless Chrome on the real GPU (GAIUS_CHROME_BIN, default Chrome on
// Windows) against a synthetic 1280x720 frame (colour pattern with a water-like region, a depth
// ramp with sky rows and a checker) for three frames each, once with ?gaiusPostCompute=0 and once
// with the compute path, for several stage combinations, and compares the colour texture byte for
// byte. Exits 1 on any difference or failure; prints the passes and dispatches each path recorded.
import {spawn} from "node:child_process";
import {copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

if (!process.env.OUT) {
  console.error("OUT must name a scratch directory");
  process.exit(2);
}
const dir = resolve(process.env.OUT, "quality-post-compute-parity");
mkdirSync(dir, {recursive: true});
copyFileSync(fileURLToPath(new URL("quality-post.js", import.meta.url)), join(dir, "quality-post.js"));
writeFileSync(join(dir, "post.html"), `<!doctype html><script src="quality-post.js"></script><script>
window.go = async (settings, frames) => {
  const adapter = await navigator.gpu.requestAdapter();
  const device = await adapter.requestDevice();
  const W = 1280, H = 720, RW = 1250, RH = 700;
  const color = device.createTexture({size: [W, H], format: "rgba8unorm", usage: 0x01 | 0x02 | 0x04 | 0x10});
  const depth = device.createTexture({size: [W, H], format: "depth16unorm", usage: 0x02 | 0x04 | 0x10});
  const px = new Uint8Array(W * H * 4);
  let seed = 12345;
  const rnd = () => (seed = (seed * 1103515245 + 12345) >>> 0) / 4294967296;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const i = (y * W + x) * 4, water = x > 300 && x < 700 && y > 400;
    px[i] = water ? 40 : Math.floor(128 + 127 * Math.sin(x * 0.05) * Math.cos(y * 0.03));
    px[i + 1] = water ? 60 : Math.floor(255 * rnd());
    px[i + 2] = water ? 200 : ((x ^ y) & 255);
    px[i + 3] = 255;
  }
  device.queue.writeTexture({texture: color}, px, {bytesPerRow: W * 4}, [W, H]);
  const dp = new Uint16Array(W * H);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    dp[y * W + x] = y < 150 ? 65535 : Math.floor(40000 + 25000 * (y / H) + 300 * Math.sin(x * 0.1)
      + (((x >> 4) + (y >> 4)) & 1) * 900);
  }
  device.queue.writeTexture({texture: depth}, dp, {bytesPerRow: W * 2}, [W, H]);
  let submitCount = 0, encoder = null;
  window.GaiusWebGpu = {device, get submitCount() { return submitCount; },
    externalEncoder() { if (!encoder) encoder = device.createCommandEncoder(); return encoder; }};
  const frame = {color, depth, width: W, height: H, rectWidth: RW, rectHeight: RH,
    projection: [0.97, 1.73, 0, 0, -1.0002, -0.2, -1, 0], zeroToOne: true};
  let ok = true;
  for (let f = 0; f < frames; f++) {
    ok = GaiusQuality.webgpuPost.run(frame, settings) && ok;
    device.queue.submit([encoder.finish()]);
    encoder = null;
    submitCount++;
  }
  const buffer = device.createBuffer({size: W * H * 4, usage: 0x01 | 0x08});
  const copy = device.createCommandEncoder();
  copy.copyTextureToBuffer({texture: color}, {buffer, bytesPerRow: W * 4}, [W, H]);
  device.queue.submit([copy.finish()]);
  await buffer.mapAsync(1);
  const out = new Uint8Array(buffer.getMappedRange().slice(0));
  let bin = "";
  for (let i = 0; i < out.length; i += 0x8000) bin += String.fromCharCode.apply(null, out.subarray(i, i + 0x8000));
  return {ok, stats: JSON.parse(JSON.stringify(GaiusQuality.webgpuPost.stats)), data: btoa(bin)};
};
</script>`);

const base = {enabled: true, ssaoRadius: 1.5, ssaoIntensity: 1.0, bloomThreshold: 0.8, bloomIntensity: 0.25};
const CASES = [
  Object.assign({}, base, {fxaa: true, tonemap: true, ssao: true, bloom: true, ssr: true}),
  Object.assign({}, base, {fxaa: false, tonemap: true, ssao: true, bloom: false, ssr: false}),
  Object.assign({}, base, {fxaa: true, tonemap: false, ssao: false, bloom: true, ssr: false, bloomThreshold: 0.5}),
];

const chromeBin = process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const port = 9800 + Math.floor(Math.random() * 150);
const profile = mkdtempSync(join(dir, "profile-"));
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${port}`, "--remote-allow-origins=*",
  `--user-data-dir=${profile}`, "--ignore-gpu-blocklist", "--allow-file-access-from-files", "about:blank"], {stdio: "ignore"});
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
let failures = 0;
try {
  let targets = [];
  for (let i = 0; i < 100 && !targets.length; i++) {
    try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); } catch { /* starting */ }
    if (!targets.length) await sleep(200);
  }
  const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
  await new Promise((done) => { ws.onopen = done; });
  let id = 0;
  const pending = new Map();
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (pending.has(message.id)) { pending.get(message.id)(message); pending.delete(message.id); }
  };
  const send = (method, params = {}) => new Promise((done) => { const i = ++id; pending.set(i, done); ws.send(JSON.stringify({id: i, method, params})); });
  const page = "file:///" + join(dir, "post.html").replace(/\\/g, "/");
  for (const [index, settings] of CASES.entries()) {
    const results = {};
    for (const mode of ["render", "compute"]) {
      await send("Page.navigate", {url: page + (mode === "render" ? "?gaiusPostCompute=0" : "")});
      await sleep(1500);
      const reply = await send("Runtime.evaluate", {expression: `go(${JSON.stringify(settings)}, 3)`, awaitPromise: true, returnByValue: true});
      const value = reply.result && reply.result.result && reply.result.result.value;
      if (!value || !value.ok) {
        console.log(`case ${index} ${mode}: run failed ${JSON.stringify(reply).slice(0, 400)}`);
        failures++;
        continue;
      }
      results[mode] = {stats: value.stats, data: Buffer.from(value.data, "base64")};
    }
    if (!results.render || !results.compute) continue;
    const a = results.render.data, b = results.compute.data;
    let differing = 0;
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) differing++;
    const r = results.render.stats, c = results.compute.stats;
    console.log(`case ${index} (${c.lastStages}): ${differing} differing bytes; render ${r.passes} passes, `
      + `compute ${c.passes} passes + ${c.computePasses} compute passes / ${c.dispatches} dispatches`);
    if (differing !== 0 || c.computePasses === 0) failures++;
  }
} finally {
  chrome.kill();
  await sleep(500);
  try { rmSync(profile, {recursive: true, force: true}); } catch { /* chrome may hold files briefly */ }
}
if (failures) {
  console.log(`quality post compute parity FAILED (${failures})`);
  process.exit(1);
}
console.log("quality post compute parity PASS (byte-identical)");
process.exit(0);
