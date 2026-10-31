import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {createServer} from "node:http";
import {createServer as createSocketServer} from "node:net";
import {mkdir, mkdtemp, rm, writeFile} from "node:fs/promises";
import {isAbsolute, join, relative, resolve, sep} from "node:path";
import {setTimeout as sleep} from "node:timers/promises";
import {chromeIsolationArgs} from "../port/scripts/bench/harness-common.mjs";

async function samplerFixture() {
  const levels = [0, 0.25, 0.5, 0.75, 0.875, 1];
  const canvas = document.createElement("canvas");
  canvas.width = levels.length; canvas.height = 1;
  const gl = canvas.getContext("webgl2", {antialias: false, alpha: false});
  if (!gl) throw new Error("WebGL2 is unavailable");
  const shader = (kind, source) => {
    const result = gl.createShader(kind);
    gl.shaderSource(result, source); gl.compileShader(result);
    if (!gl.getShaderParameter(result, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(result));
    return result;
  };
  const program = gl.createProgram();
  gl.attachShader(program, shader(gl.VERTEX_SHADER, "#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.0-1.0,0.0,1.0);}"));
  gl.attachShader(program, shader(gl.FRAGMENT_SHADER, "#version 300 es\nprecision highp float;uniform sampler2D atlas;uniform float lod;out vec4 result;void main(){vec4 color=textureLod(atlas,vec2(0.5),lod);if(color.a<0.1)discard;result=vec4(color.rgb,1.0);}"));
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
  gl.useProgram(program);
  const texture = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, texture);
  const base = new Uint8Array([255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255]);
  const mip = new Uint8Array([0, 255, 0, 0]);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 2, 2, 0, gl.RGBA, gl.UNSIGNED_BYTE, base);
  gl.texImage2D(gl.TEXTURE_2D, 1, gl.RGBA8, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, mip);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST_MIPMAP_LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAX_LEVEL, 1);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
  for (const [index, level] of levels.entries()) {
    gl.viewport(index, 0, 1, 1);
    gl.uniform1f(gl.getUniformLocation(program, "lod"), level);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
  const reference = new Uint8Array(levels.length * 4);
  gl.readPixels(0, 0, levels.length, 1, gl.RGBA, gl.UNSIGNED_BYTE, reference);
  if (gl.getError() !== gl.NO_ERROR) throw new Error("WebGL2 sampler fixture failed");
  const adapter = await navigator.gpu?.requestAdapter();
  if (!adapter) throw new Error("WebGPU is unavailable");
  const device = await adapter.requestDevice();
  const errors = [];
  device.addEventListener("uncapturederror", event => errors.push(event.error.message));
  device.pushErrorScope("validation");
  const atlas = device.createTexture({size: [2, 2], mipLevelCount: 2, format: "rgba8unorm", usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST});
  device.queue.writeTexture({texture: atlas}, base, {bytesPerRow: 8}, [2, 2]);
  device.queue.writeTexture({texture: atlas, mipLevel: 1}, mip, {bytesPerRow: 4}, [1, 1]);
  const module = device.createShaderModule({code: "@group(0) @binding(0) var atlas:texture_2d<f32>;@group(0) @binding(1) var filtering:sampler;@group(0) @binding(2) var<uniform> lod:f32;@vertex fn vertex(@builtin(vertex_index) index:u32)->@builtin(position) vec4<f32>{let p=vec2<f32>(f32((index<<1u)&2u),f32(index&2u));return vec4<f32>(p*2.0-1.0,0.0,1.0);}@fragment fn fragment()->@location(0) vec4<f32>{let color=textureSampleLevel(atlas,filtering,vec2<f32>(0.5),lod);if(color.a<0.1){discard;}return vec4<f32>(color.rgb,1.0);}"});
  const compilation = await module.getCompilationInfo();
  if (compilation.messages.some(message => message.type === "error")) throw new Error("WGSL fixture compilation failed");
  const pipeline = device.createRenderPipeline({layout: "auto", vertex: {module, entryPoint: "vertex"}, fragment: {module, entryPoint: "fragment", targets: [{format: "rgba8unorm"}]}, primitive: {topology: "triangle-list"}});
  const uniforms = levels.map(level => {
    const buffer = device.createBuffer({size: 16, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST});
    device.queue.writeBuffer(buffer, 0, new Float32Array([level, 0, 0, 0]));
    return buffer;
  });
  const draw = async mipmapFilter => {
    const sampler = device.createSampler({addressModeU: "clamp-to-edge", addressModeV: "clamp-to-edge", minFilter: "nearest", magFilter: "nearest", mipmapFilter, lodMaxClamp: 1, maxAnisotropy: 1});
    const target = device.createTexture({size: [levels.length, 1], format: "rgba8unorm", usage: GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.COPY_SRC});
    const readback = device.createBuffer({size: 256, usage: GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST});
    const encoder = device.createCommandEncoder();
    const pass = encoder.beginRenderPass({colorAttachments: [{view: target.createView(), loadOp: "clear", storeOp: "store", clearValue: {r: 0, g: 0, b: 0, a: 1}}]});
    pass.setPipeline(pipeline);
    for (const [index, buffer] of uniforms.entries()) {
      const bindings = device.createBindGroup({layout: pipeline.getBindGroupLayout(0), entries: [{binding: 0, resource: atlas.createView()}, {binding: 1, resource: sampler}, {binding: 2, resource: {buffer}}]});
      pass.setBindGroup(0, bindings); pass.setViewport(index, 0, 1, 1, 0, 1); pass.draw(3);
    }
    pass.end();
    encoder.copyTextureToBuffer({texture: target}, {buffer: readback, bytesPerRow: 256}, [levels.length, 1]);
    device.queue.submit([encoder.finish()]);
    await readback.mapAsync(GPUMapMode.READ);
    const result = Array.from(new Uint8Array(readback.getMappedRange()).slice(0, reference.length));
    readback.unmap(); readback.destroy(); target.destroy();
    return result;
  };
  const before = await draw("nearest"), after = await draw("linear");
  const validation = await device.popErrorScope();
  if (validation || errors.length) throw new Error(validation?.message || errors.join(";"));
  const compare = values => {
    const delta = values.map((value, index) => Math.abs(value - reference[index]));
    return {maxChannelDelta: Math.max(...delta), differentPixels: levels.filter((_, index) => delta.slice(index * 4, index * 4 + 4).some(value => value > 1)).length};
  };
  const result = {fixture: "CPU mip upload, nearest in-level filtering, linear mip interpolation, alpha discard", levels, reference: Array.from(reference), before, after, beforeComparison: compare(before), afterComparison: compare(after), adapter: {vendor: adapter.info?.vendor, architecture: adapter.info?.architecture}, errors};
  device.destroy();
  return result;
}

assert.ok(process.argv[2], "An explicit scratch output directory is required; run it through your slot wrapper if you use one");
const output = resolve(process.argv[2]);
await mkdir(output, {recursive: true});
const profile = await mkdtemp(join(output, "chrome-profile-"));
const server = createServer((request, response) => { response.writeHead(200, {"Content-Type": "text/html"}); response.end("<!doctype html><title>Sampler contract</title>"); });
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const debugSocket = createSocketServer();
await new Promise(resolve => debugSocket.listen(0, "127.0.0.1", resolve));
const debugPort = debugSocket.address().port;
await new Promise(resolve => debugSocket.close(resolve));
const chrome = spawn(process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe", ["--headless=new", "--remote-debugging-port=" + debugPort, "--remote-allow-origins=*", "--user-data-dir=" + profile, "--ignore-gpu-blocklist", ...chromeIsolationArgs(), "about:blank"], {stdio: "ignore", windowsHide: true});
let socket;
const waiting = new Map();
try {
  let target;
  for (let attempt = 0; attempt < 80; attempt++) {
    try { target = (await (await fetch("http://127.0.0.1:" + debugPort + "/json/list")).json()).find(page => page.type === "page"); } catch {}
    if (target) break;
    await sleep(100);
  }
  assert.ok(target, "Chrome CDP page did not start");
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, {once: true}); socket.addEventListener("error", reject, {once: true}); });
  let sequence = 0;
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data), request = waiting.get(message.id);
    if (!request) return;
    waiting.delete(message.id); clearTimeout(request.timer);
    if (message.error) request.reject(new Error(JSON.stringify(message.error)));
    else request.resolve(message.result);
  });
  const command = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { waiting.delete(id); reject(new Error("CDP timed out: " + method)); }, 20000);
    waiting.set(id, {resolve, reject, timer}); socket.send(JSON.stringify({id, method, params}));
  });
  const version = await command("Browser.getVersion");
  await command("Page.navigate", {url: "http://127.0.0.1:" + server.address().port + "/"});
  await sleep(300);
  const evaluated = await command("Runtime.evaluate", {expression: "(" + samplerFixture.toString() + ")()", awaitPromise: true, returnByValue: true});
  if (evaluated.exceptionDetails) throw new Error(JSON.stringify(evaluated.exceptionDetails));
  const result = {...evaluated.result.value, browser: version.product};
  result.verdict = result.beforeComparison.differentPixels >= 2 && result.afterComparison.differentPixels === 0 ? "PASS" : "FAIL";
  await writeFile(join(output, "sampler-browser.json"), JSON.stringify(result, null, 2) + "\n");
  console.log(JSON.stringify(result));
  assert.equal(result.verdict, "PASS", "sampler regression requires the old descriptor to differ and the corrected descriptor to match GL");
} finally {
  for (const request of waiting.values()) { clearTimeout(request.timer); request.reject(new Error("CDP closed")); }
  socket?.close(); chrome.kill(); server.close();
  await sleep(1000);
  const profileRelative = relative(output, resolve(profile));
  assert.ok(profileRelative && !isAbsolute(profileRelative) && profileRelative !== ".." && !profileRelative.startsWith(".." + sep), "Chrome profile must stay inside its output directory");
  await rm(profile, {recursive: true, force: true});
}
