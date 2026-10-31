// Runs a render seam harness page in headless Chrome and compares the WebGPU frame with the GL one.
//
//   node run.mjs [--root=DIR] [--out=DIR] [--flags="..."] [case...]
//
// Serves DIR (default: this script's directory) on 127.0.0.1, starts one headless Chrome with its
// own profile and no external network (proxy to a dead port, loopback bypassed), loads each case
// (a query string; default "mode=webgpu" and "mode=webgl2&preserveDrawingBuffer=1"), waits for the
// harness to finish, records __harness.info, the console and a screenshot, and compares the first
// two screenshots pixel by pixel in the browser. Writes OUT/result.json, OUT/<case>.png and
// OUT/diff.png; prints the result. Chrome is killed at the end. Run it inside the machine's Chrome
// slot when the machine has one.
import { spawn } from "node:child_process";
import http from "node:http";
import { readFileSync, mkdtempSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, extname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const found = args.find((a) => a.startsWith(`--${name}=`));
  return found ? found.slice(name.length + 3) : fallback;
};
const here = fileURLToPath(new URL(".", import.meta.url));
const root = resolve(option("root", here));
const out = resolve(option("out", join(root, "result")));
const extraFlags = option("flags", "").split(" ").filter(Boolean);
const cases = args.filter((a) => !a.startsWith("--"));
if (cases.length === 0) cases.push("mode=webgpu", "mode=webgl2&preserveDrawingBuffer=1");
mkdirSync(out, { recursive: true });

const types = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".wasm": "application/wasm",
  ".json": "application/json" };
const server = http.createServer((req, res) => {
  const path = new URL(req.url, "http://x").pathname.replace(/^\/$/, "/index.html");
  try {
    const body = readFileSync(join(root, decodeURIComponent(path)));
    res.writeHead(200, {
      "content-type": types[extname(path)] || "application/octet-stream",
      "cross-origin-opener-policy": "same-origin",
      "cross-origin-embedder-policy": "require-corp",
      "cache-control": "no-store"
    });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end();
  }
}).listen(0, "127.0.0.1");
await new Promise((r) => server.once("listening", r));
const httpPort = server.address().port;

const debugPort = 9300 + Math.floor(Math.random() * 600);
const profile = mkdtempSync(join(tmpdir(), "seam-harness-"));
const chromeArgs = ["--headless=new", `--remote-debugging-port=${debugPort}`, "--remote-allow-origins=*",
  `--user-data-dir=${profile}`, "--no-first-run", "--window-size=1280,720",
  "--proxy-server=http://127.0.0.1:9", "--proxy-bypass-list=127.0.0.1;localhost", ...extraFlags, "about:blank"];
const chromeBin = process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const chrome = spawn(chromeBin, chromeArgs, { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ws;
let result = { cases: [] };
try {
  let targets = [];
  for (let i = 0; i < 80 && !targets.length; i++) {
    try {
      targets = (await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json()).filter((t) => t.type === "page");
    } catch {}
    if (!targets.length) await sleep(250);
  }
  ws = new WebSocket(targets[0].webSocketDebuggerUrl);
  await new Promise((r) => (ws.onopen = r));
  let nextId = 1;
  const pending = new Map();
  const consoleLines = [];
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message);
      pending.delete(message.id);
    } else if (message.method === "Runtime.consoleAPICalled") {
      consoleLines.push(message.params.args.map((a) => a.value ?? a.description).join(" "));
    } else if (message.method === "Runtime.exceptionThrown") {
      consoleLines.push("EXCEPTION " + (message.params.exceptionDetails.exception?.description
        || message.params.exceptionDetails.text));
    }
  };
  const send = (method, params = {}) => new Promise((r) => {
    const id = nextId++;
    pending.set(id, r);
    ws.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async (expression) => {
    const reply = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    if (reply.result?.exceptionDetails) return { error: reply.result.exceptionDetails.exception?.description };
    return reply.result?.result?.value;
  };
  await send("Runtime.enable");
  await send("Page.enable");
  await send("Emulation.setDeviceMetricsOverride", { width: 1280, height: 720, deviceScaleFactor: 1, mobile: false });
  result.browser = (await (await fetch(`http://127.0.0.1:${debugPort}/json/version`)).json()).Browser;
  const shots = [];
  for (const query of cases) {
    consoleLines.length = 0;
    const started = Date.now();
    await send("Page.navigate", { url: `http://127.0.0.1:${httpPort}/?${query}` });
    let status = "loading";
    for (let i = 0; i < 600; i++) {
      await sleep(100);
      status = await evaluate("globalThis.__harness ? globalThis.__harness.status : 'no page'");
      if (typeof status === "string" && status !== "loading" && status !== "no page") break;
    }
    await sleep(500);
    const entry = { case: query, status, ms: Date.now() - started };
    entry.info = await evaluate("globalThis.__harness ? globalThis.__harness.info : null");
    entry.adapter = await evaluate("(function(){var g=globalThis.GaiusWebGpu;return g&&g.adapterInfo?g.adapterInfo:null;})()");
    // SHA-256 of the shaderc modules the browser front-end produced (blaze3d seams), to compare
    // with the native corpus.
    entry.modules = await evaluate(`(async function () {
      var out = {}, modules = (globalThis.__harness && globalThis.__harness.modules) || {};
      for (var key of Object.keys(modules)) {
        var bytes = new Uint8Array(modules[key].buffer, modules[key].byteOffset, modules[key].byteLength);
        var digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
        out[key] = Array.from(digest, function (b) { return b.toString(16).padStart(2, "0"); }).join("");
      }
      return out;
    })()`);
    entry.glueMessages = await evaluate("globalThis.GaiusWebGpu && GaiusWebGpu.messages ? GaiusWebGpu.messages.slice(0, 20) : []");
    const shot = await send("Page.captureScreenshot", { format: "png" });
    const name = query.replace(/[^a-z0-9]+/gi, "_") + ".png";
    writeFileSync(join(out, name), Buffer.from(shot.result.data, "base64"));
    entry.screenshot = name;
    entry.console = consoleLines.slice(0, 40);
    shots.push(shot.result.data);
    result.cases.push(entry);
  }
  if (shots.length >= 2) {
    // Decoded by the browser: differing pixels, the largest channel delta, a histogram of deltas
    // and the bounding box of the differences; diff.png shows |a - b| x 16.
    result.comparison = await evaluate(`(async function () {
      function load(src) { return new Promise(function (ok) { var i = new Image(); i.onload = function () { ok(i); }; i.src = src; }); }
      var x = await load("data:image/png;base64,${shots[0]}"), y = await load("data:image/png;base64,${shots[1]}");
      var c = document.createElement("canvas"); c.width = x.width; c.height = x.height;
      var g = c.getContext("2d", { willReadFrequently: true });
      g.drawImage(x, 0, 0); var a = g.getImageData(0, 0, c.width, c.height).data;
      g.clearRect(0, 0, c.width, c.height); g.drawImage(y, 0, 0); var b = g.getImageData(0, 0, c.width, c.height).data;
      var diff = g.createImageData(c.width, c.height);
      var differing = 0, maxDelta = 0, over2 = 0, histogram = {}, x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
      for (var p = 0; p < a.length; p += 4) {
        var d = Math.max(Math.abs(a[p] - b[p]), Math.abs(a[p + 1] - b[p + 1]), Math.abs(a[p + 2] - b[p + 2]));
        diff.data[p] = diff.data[p + 1] = diff.data[p + 2] = Math.min(255, d * 16); diff.data[p + 3] = 255;
        if (d > 0) {
          differing++;
          histogram[d] = (histogram[d] || 0) + 1;
          var px = (p / 4) % c.width, py = Math.floor(p / 4 / c.width);
          x0 = Math.min(x0, px); y0 = Math.min(y0, py); x1 = Math.max(x1, px); y1 = Math.max(y1, py);
        }
        if (d > 2) over2++;
        if (d > maxDelta) maxDelta = d;
      }
      g.putImageData(diff, 0, 0);
      // Guards against a trivial match (two black frames): distinct colours and mean of each frame.
      function summary(d) {
        var colors = new Set(), sum = [0, 0, 0];
        for (var i = 0; i < d.length; i += 4) {
          sum[0] += d[i]; sum[1] += d[i + 1]; sum[2] += d[i + 2];
          if (colors.size < 100000) colors.add((d[i] << 16) | (d[i + 1] << 8) | d[i + 2]);
        }
        var n = d.length / 4;
        return { distinctColors: colors.size, mean: sum.map(function (v) { return Math.round(v / n * 10) / 10; }) };
      }
      return { first: summary(a), second: summary(b), width: c.width, height: c.height, pixels: c.width * c.height,
        differingPixels: differing,
        differingOver2: over2, maxChannelDelta: maxDelta, histogram: histogram,
        box: differing ? [x0, y0, x1, y1] : null, diffPng: c.toDataURL("image/png").slice(22) };
    })()`);
    if (result.comparison && result.comparison.diffPng) {
      writeFileSync(join(out, "diff.png"), Buffer.from(result.comparison.diffPng, "base64"));
      delete result.comparison.diffPng;
    }
  }
} finally {
  try { ws && ws.close(); } catch {}
  chrome.kill();
  server.close();
  await sleep(500);
  try { rmSync(profile, { recursive: true, force: true }); } catch {}
}
writeFileSync(join(out, "result.json"), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
