#!/usr/bin/env node
// Cold/warm iterations use separate Chrome processes and one profile per pair.
// GAIUS_STARTUP_CACHE_WAIT_MS (default 120000 with warm mode) bounds post-title
// serialization observation; CACHE_SETTLE_MS (default 15000) is a quiet writeback
// window, not a durable-storage acknowledgement. Title times exclude this wait.

import {spawn, execFileSync} from "node:child_process";
import {createServer} from "node:http";
import {createReadStream} from "node:fs";
import {mkdir, readFile, rm, stat, unlink, writeFile} from "node:fs/promises";
import {extname, resolve, sep} from "node:path";
import {pathToFileURL} from "node:url";
import {analyzeStartupTerrain} from "./wasm-startup-visual.mjs";
import {chromeLaunchArgs, resolveChromeBinary} from "./chrome-binary.mjs";
import {cacheObservation} from "./wasm-cache-evidence.mjs";
import {linuxGpuEnvironment} from "./linux-gpu-env.mjs";

const [siteDirectory, portableFile, outputDirectory] = process.argv.slice(2);
if (!outputDirectory) throw new Error("Usage: wasm-startup-timeline.mjs <site> <portable> <output>");
const output = resolve(outputDirectory);
const site = resolve(siteDirectory);
const count = Number(process.env.GAIUS_STARTUP_RUNS || 3);
const timeout = Number(process.env.GAIUS_STARTUP_TIMEOUT_MS || 45000);
const modes = (process.env.GAIUS_STARTUP_MODES || "cold,warm,file").split(",");
const capture = process.env.GAIUS_STARTUP_CAPTURE !== "0";
const maxLoad = Number(process.env.GAIUS_STARTUP_MAX_LOAD || 0);
const cpuProfile = capture && process.env.GAIUS_STARTUP_CPU_PROFILE !== "0";
if (!Number.isInteger(count) || count < 1 || count > 3) throw new Error("Startup runs must be between 1 and 3");
if (modes.some(mode => !["cold", "warm", "file"].includes(mode))) throw new Error("Unknown startup mode");
const sleep = (milliseconds) => new Promise((done) => setTimeout(done, milliseconds));
await mkdir(output, {recursive: true});

async function machineSnapshot() {
  if (process.platform !== "linux") return null;
  let java = "";
  try { java = execFileSync("ps", ["-C", "java", "-o", "pid=,pcpu=,rss=,etime=,comm="], {encoding: "utf8"}); }
  catch (error) { java = String(error.stdout || ""); }
  return {at: new Date().toISOString(), load: (await readFile("/proc/loadavg", "utf8")).trim(),
    cpu: (await readFile("/proc/stat", "utf8")).split("\n")[0],
    uptime: execFileSync("uptime", [], {encoding: "utf8"}).trim(), java};
}

class Cdp {
  constructor(url) {
    this.socket = new WebSocket(url);
    this.sequence = 0;
    this.pending = new Map();
    this.events = new Map();
  }
  async open() {
    await new Promise((done, fail) => {
      this.socket.addEventListener("open", done, {once: true});
      this.socket.addEventListener("error", fail, {once: true});
    });
    this.socket.addEventListener("message", ({data}) => {
      const message = JSON.parse(String(data));
      if (message.id) {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        clearTimeout(pending.timer);
        this.pending.delete(message.id);
        if (message.error) pending.fail(new Error(`${pending.method}: ${message.error.message}`));
        else pending.done(message.result || {});
      } else {
        for (const listener of this.events.get(message.method) || []) {
          listener(message.params || {}, message.sessionId || null);
        }
      }
    });
    this.socket.addEventListener("close", () => {
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer);
        pending.fail(new Error(`CDP disconnected: ${pending.method}`));
      }
      this.pending.clear();
    });
  }
  on(method, listener) {
    if (!this.events.has(method)) this.events.set(method, []);
    this.events.get(method).push(listener);
  }
  send(method, params = {}, sessionId = null) {
    const id = ++this.sequence;
    return new Promise((done, fail) => {
      const timer = setTimeout(() => { this.pending.delete(id); fail(new Error(`CDP timeout: ${method}`)); }, timeout);
      this.pending.set(id, {done, fail, timer, method});
      this.socket.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}));
    });
  }
  async evaluate(expression) {
    const result = await this.send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
    return result.result?.value;
  }
  close() { this.socket.close(); }
}

const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    const filename = resolve(site, `.${pathname.endsWith("/") ? pathname + "index.html" : pathname}`);
    if (!filename.startsWith(site + sep)) { response.writeHead(403).end(); return; }
    const info = await stat(filename);
    const mime = {".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".wasm": "application/wasm"}[extname(filename)] || "application/octet-stream";
    response.writeHead(200, {"Content-Type": mime, "Content-Length": info.size,
      "Cache-Control": /\.[0-9a-f]{16,64}\./.test(filename) ? "public, max-age=31536000, immutable" : "no-cache"});
    createReadStream(filename).pipe(response);
  } catch { response.writeHead(404).end(); }
});
await new Promise((done) => server.listen(Number(process.env.GAIUS_STARTUP_PORT || 8795), "127.0.0.1", done));
const startupQuery = process.env.GAIUS_STARTUP_QUERY || "?runtime=wasm";
const siteUrl = `http://127.0.0.1:${server.address().port}/${startupQuery.startsWith("?") ? startupQuery : "?" + startupQuery}`;

const probe = `(() => {
  const entries = window.__startupTimeline = [];
  const mark = (name, detail) => {
    entries.push({name, at: performance.now(), detail});
    performance.mark('gaius:' + name);
  };
  window.__startupMark = mark;
  for (const name of ['compileStreaming', 'compile', 'instantiate', 'instantiateStreaming']) {
    const original = WebAssembly[name];
    if (!original) continue;
    WebAssembly[name] = function(...args) {
      const id = name + ':' + entries.length;
      mark(id + ':start', args[0]?.url || args[0]?.byteLength || null);
      return original.apply(this, args).then(value => { mark(id + ':end'); return value; }, error => {
        mark(id + ':error', String(error)); throw error;
      });
    };
  }
  const fetchOriginal = window.fetch;
  window.fetch = function(...args) {
    const url = String(args[0]?.url || args[0]);
    mark('fetch:start', url);
    return fetchOriginal.apply(this, args).then(value => { mark('fetch:headers', url); return value; });
  };
  const WorkerOriginal = window.Worker;
  window.Worker = class extends WorkerOriginal {
    constructor(...args) { mark('worker:start', String(args[0])); super(...args); }
  };
  const shaderOriginal = WebGL2RenderingContext.prototype.compileShader;
  WebGL2RenderingContext.prototype.compileShader = function(...args) {
    mark('shader:start'); const value = shaderOriginal.apply(this, args); mark('shader:end'); return value;
  };
  const linkOriginal = WebGL2RenderingContext.prototype.linkProgram;
  WebGL2RenderingContext.prototype.linkProgram = function(...args) {
    mark('pipeline:start'); const value = linkOriginal.apply(this, args); mark('pipeline:end'); return value;
  };
  let title = false;
  function frame() {
    const state = window.__gaiusMinecraftState;
    const usable = String(state?.screen || '').endsWith('TitleScreen')
      && state.running === true && state.noRender !== true && !state.overlay
      && window.__gaiusInitialBootPending === false;
    if (!title && usable) {
      title = true;
      requestAnimationFrame(() => { mark('title:frame'); window.__startupTitleFrame = performance.now(); });
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
  try { localStorage.setItem('gaius.playerName', 'StartupProbe'); } catch {}
})();`;

async function openWorld(page, label) {
  const deadline = Date.now() + Number(process.env.GAIUS_STARTUP_WORLD_TIMEOUT_MS || 45000);
  const requested = await page.evaluate("performance.now()");
  const steps = [];
  async function click(label) {
    let widget;
    while (!widget && Date.now() < deadline) {
      widget = await page.evaluate(`(() => {
        const state = window.__gaiusMinecraftState || {};
        const found = (state.screenWidgets || []).find(value => value.visible !== false && value.active !== false
          && String(value.text || '').trim().toLowerCase() === ${JSON.stringify(label.toLowerCase())});
        const rect = document.querySelector('canvas')?.getBoundingClientRect();
        const size = state.screenSize;
        if (!found || !rect || !size?.width || !size?.height || state.overlay) return null;
        return {x: rect.left + (found.x + found.width / 2) * rect.width / size.width,
          y: rect.top + (found.y + found.height / 2) * rect.height / size.height,
          at: performance.now(), screen: state.screen};
      })()`);
      if (!widget) await sleep(100);
    }
    if (!widget) throw new Error(`World widget timeout: ${label}`);
    steps.push({label, ...widget});
    await page.send("Input.dispatchMouseEvent", {type: "mouseMoved", x: widget.x, y: widget.y});
    await page.send("Input.dispatchMouseEvent", {type: "mousePressed", button: "left", buttons: 1,
      clickCount: 1, x: widget.x, y: widget.y});
    await page.send("Input.dispatchMouseEvent", {type: "mouseReleased", button: "left", buttons: 0,
      clickCount: 1, x: widget.x, y: widget.y});
  }
  let failure = null;
  let entered = null;
  let visible = null;
  let visual = null;
  let telemetry = null;
  try {
    await click("Singleplayer");
    while (Date.now() < deadline) {
      const screen = await page.evaluate("String(window.__gaiusMinecraftState?.screen || '')");
      if (/CreateWorldScreen/.test(screen)) break;
      if (/SelectWorldScreen/.test(screen)) { await click("Create New World"); break; }
      await sleep(100);
    }
    await click("Create New World");
    while (Date.now() < deadline) {
      const state = await page.evaluate(`({level: !!window.__gaiusMinecraftState?.level,
        screen: window.__gaiusMinecraftState?.screen, overlay: window.__gaiusMinecraftState?.overlay,
        error: String(window.__gaiusThreadError || window.__gaiusBootError || '')})`);
      if (state.level && !state.screen && !state.overlay) { entered = await page.evaluate("performance.now()"); break; }
      if (state.error) throw new Error(state.error);
      await sleep(100);
    }
    if (!entered) throw new Error("In-world frame timeout");
    const renderDeadline = Date.now() + 12000;
    while (Date.now() < renderDeadline) {
      telemetry = await page.evaluate(`(() => {
        const state = window.__gaiusMinecraftState || {};
        const draw = window.__gaiusChunkDrawTelemetry || {};
        const gl = window.__gaiusGL || {};
        return {loadedChunks: Number(state.loadedChunkCount) || 0,
          firstDrawColumns: Number(draw.firstDrawColumns) || 0,
          drawCalls: Number(gl.drawCallsCount) || 0, contextLost: gl.gpuContextLost === true,
          screen: state.screen, overlay: state.overlay};
      })()`);
      const pixels = Buffer.from((await page.send("Page.captureScreenshot", {format: "png"})).data, "base64");
      visual = analyzeStartupTerrain(pixels);
      await writeFile(resolve(output, `${label}-world.png`), pixels);
      if (telemetry.loadedChunks > 0 && !telemetry.screen && !telemetry.overlay
          && !telemetry.contextLost && visual.terrainVisualPass) {
        visible = await page.evaluate("performance.now()");
        break;
      }
      await sleep(1000);
    }
    if (!visible) throw new Error("World state entered, but no textured terrain frame within 12 seconds");
  } catch (error) { failure = String(error.message || error); }
  return {requested, entered, visible, elapsedMs: entered ? entered - requested : null,
    visibleMs: visible ? visible - requested : null, visual, telemetry, steps, failure,
    state: await page.evaluate("window.__gaiusMinecraftState || null")};
}

async function browserRun(mode, iteration) {
  const label = `${mode}-${iteration}`;
  const profile = resolve(output, "profiles", mode === "warm" ? `cold-${iteration}` : label);
  const existingProfile = await stat(profile).catch(error => {
    if (error.code !== "ENOENT") throw error;
    return null;
  });
  if (mode === "warm" && !existingProfile?.isDirectory()) throw new Error("Warm runs require a preceding cold profile");
  if (mode !== "warm" && existingProfile) throw new Error("Cold/file profile already exists; use a fresh output directory");
  await mkdir(profile, {recursive: true});
  await unlink(resolve(profile, "DevToolsActivePort")).catch((error) => {
    if (error.code !== "ENOENT") throw error;
  });
  const chrome = spawn(resolveChromeBinary(), [...chromeLaunchArgs(),
    "--headless=new", "--remote-debugging-port=0", "--remote-allow-origins=*", `--user-data-dir=${profile}`,
    "--ignore-gpu-blocklist", "--disable-breakpad",
    "--password-store=basic", "--no-first-run", "--no-default-browser-check", "--disable-background-networking", "--disable-component-update",
    "--disable-features=Translate,MediaRouter", "--window-size=960,640", "about:blank",
  ], {stdio: ["ignore", "ignore", "pipe"], env: linuxGpuEnvironment(process.env)});
  let stderr = "";
  chrome.stderr.on("data", (chunk) => { stderr = (stderr + chunk).slice(-10000); });
  let page;
  let browser;
  let tracing = false;
  let traceComplete = false;
  let finishTrace;
  let traceFinished;
  const resetTraceCompletion = () => {
    traceComplete = false;
    traceFinished = new Promise(done => { finishTrace = done; });
  };
  resetTraceCompletion();
  const noteTraceComplete = () => { traceComplete = true; finishTrace(); };
  const trace = [];
  const traceSegments = [];
  const traceCategories = "v8,v8.wasm,devtools.timeline,blink.user_timing,loading,disabled-by-default-devtools.timeline,disabled-by-default-v8.wasm.detailed";
  async function startTrace() {
    resetTraceCompletion();
    await browser.send("Tracing.start", {categories: traceCategories, transferMode: "ReportEvents"});
    tracing = true;
  }
  async function stopTrace() {
    if (!tracing) return;
    await browser.send("Tracing.end");
    let timer;
    await Promise.race([traceFinished, new Promise(done => { timer = setTimeout(done, 30000); })]);
    clearTimeout(timer);
    traceSegments.push({complete: traceComplete, eventCount: trace.length});
    tracing = false;
    if (!traceComplete) throw new Error("Trace segment did not finish; cannot verify cache write");
  }
  const requests = [];
  const workerProfiles = [];
  const requestStarts = new Map();
  const errors = [];
  const result = {mode, iteration, capture, machineBefore: await machineSnapshot(), gpuFlags: chromeLaunchArgs(),
    url: mode === "file" ? pathToFileURL(resolve(portableFile)).href + startupQuery : siteUrl};
  let loadWatch;
  let checkingLoad = false;
  if (maxLoad > 0) loadWatch = setInterval(async () => {
    if (checkingLoad || result.loadLimitExceeded) return;
    checkingLoad = true;
    try {
      const snapshot = await machineSnapshot();
      if (Number(snapshot?.load.split(" ")[0]) > maxLoad) {
        result.loadLimitExceeded = {limit: maxLoad, snapshot};
        await browser?.send("Browser.close").catch(() => {});
      }
    } finally { checkingLoad = false; }
  }, 1000);
  try {
    let endpoint;
    const deadline = Date.now() + 15000;
    while (!endpoint && Date.now() < deadline) {
      try {
        const [port, path] = (await readFile(resolve(profile, "DevToolsActivePort"), "utf8")).trim().split(/\r?\n/);
        endpoint = {port, path};
      } catch { await sleep(100); }
    }
    if (!endpoint) throw new Error("Chrome debugger did not start");
    browser = new Cdp(`ws://127.0.0.1:${endpoint.port}${endpoint.path}`);
    await browser.open();
    result.browser = (await browser.send("Browser.getVersion")).product;
    browser.on("Tracing.dataCollected", ({value}) => trace.push(...value));
    browser.on("Tracing.tracingComplete", noteTraceComplete);
    const attachWorker = async ({sessionId, targetInfo}) => {
      if (targetInfo.type !== "worker") return;
      const worker = {url: targetInfo.url, targetId: targetInfo.targetId, sessionId, attachedAt: Date.now()};
      workerProfiles.push(worker);
      try {
        await page.send("Runtime.enable", {}, sessionId);
        await page.send("Network.enable", {}, sessionId);
        if (cpuProfile) {
          await page.send("Profiler.enable", {}, sessionId);
          await page.send("Profiler.setSamplingInterval", {interval: 1000}, sessionId);
          await page.send("Profiler.start", {}, sessionId);
          worker.started = true;
        }
      } catch (error) { worker.profileError = String(error.message || error); }
      await page.send("Runtime.runIfWaitingForDebugger", {}, sessionId).catch(() => {});
    };
    const targets = await (await fetch(`http://127.0.0.1:${endpoint.port}/json/list`)).json();
    page = new Cdp(targets.find((target) => target.type === "page").webSocketDebuggerUrl);
    await page.open();
    page.on("Tracing.dataCollected", ({value}) => trace.push(...value));
    page.on("Tracing.tracingComplete", noteTraceComplete);
    page.on("Target.attachedToTarget", attachWorker);
    if (capture) {
      await page.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: cpuProfile,
        flatten: true, filter: [{type: "worker", exclude: false}, {exclude: true}]});
    }
    if (capture) {
      await startTrace();
    }
    await page.send("Page.enable");
    await page.send("Runtime.enable");
    await page.send("Network.enable");
    await page.send("Performance.enable");
    if (cpuProfile) {
      await page.send("Profiler.enable");
      await page.send("Profiler.setSamplingInterval", {interval: 1000});
      await page.send("Profiler.start");
    }
    page.on("Network.requestWillBeSent", ({requestId, request, timestamp, wallTime, initiator}, sessionId) => {
      // Inline decoded images are not network traffic. Their base64 URLs can dwarf the trace.
      if (request.url.startsWith("data:")) return;
      requestStarts.set(`${sessionId || "page"}:${requestId}`, {
        sessionId: sessionId || null, requestId, url: request.url, timestamp, wallTime, initiator, headers: request.headers, method: request.method
      });
    });
    page.on("Network.loadingFinished", ({requestId, timestamp, encodedDataLength}, sessionId) => {
      const start = requestStarts.get(`${sessionId || "page"}:${requestId}`);
      if (start) start.finished = {timestamp, encodedDataLength};
    });
    page.on("Network.responseReceived", ({requestId, response, timestamp}, sessionId) => {
      if (response.url.startsWith("data:")) return;
      requests.push({sessionId: sessionId || null,
      requestId, url: response.url, timestamp,
      mimeType: response.mimeType, headers: response.headers,
      status: response.status, fromServiceWorker: response.fromServiceWorker, fromDiskCache: response.fromDiskCache,
      fromPrefetchCache: response.fromPrefetchCache, timing: response.timing,
      request: requestStarts.get(`${sessionId || "page"}:${requestId}`) || null});
    });
    page.on("Runtime.exceptionThrown", ({exceptionDetails}) => errors.push(exceptionDetails.exception?.description || exceptionDetails.text));
    await page.send("Page.addScriptToEvaluateOnNewDocument", {source: probe});
    await page.send("Page.navigate", {url: result.url});
    const end = Date.now() + timeout;
    let state;
    while (Date.now() < end) {
      state = await page.evaluate(`({titleFrame: window.__startupTitleFrame || null,
        screen: window.__gaiusMinecraftState?.screen || null,
        error: String(window.__gaiusBootError || window.__gaiusThreadError || '')})`);
      if (state.titleFrame || state.error) break;
      await sleep(100);
    }
    if (state?.titleFrame) await sleep(Number(process.env.GAIUS_STARTUP_CACHE_DWELL_MS || 3000));
    // A returning user closes a populated cache, not a browser killed immediately after
    // the first title frame. Trace serialization can precede digest/IPC/disk writeback.
    // Drain trace in bounded segments, then allow a quiet writeback window. This changes
    // only post-title observation; title/compile timestamps remain the measured values.
    const cacheWaitMs = Number(process.env.GAIUS_STARTUP_CACHE_WAIT_MS ?? (modes.includes("warm") ? 120000 : 0));
    if (state?.titleFrame && mode === "cold" && cacheWaitMs > 0) {
      if (!capture) throw new Error("Cache write observation requires tracing");
      const settleMs = Number(process.env.GAIUS_STARTUP_CACHE_SETTLE_MS || 15000);
      const waitStarted = Date.now();
      let observation;
      while (true) {
        const clock = await page.send("Performance.getMetrics").catch(() => null);
        const now = clock?.metrics?.find(value => value.name === "Timestamp")?.value;
        await stopTrace();
        // User timing markers share the trace clock when Performance metrics are absent.
        const clockUs = Number.isFinite(now) ? now * 1e6 : Math.max(0, ...trace.slice(-1000).map(e => e.ts || 0));
        observation = cacheObservation(trace, clockUs, settleMs);
        if (observation.ready || Date.now() - waitStarted >= cacheWaitMs) break;
        await startTrace();
        await sleep(Math.min(10000, cacheWaitMs - (Date.now() - waitStarted)));
      }
      result.cachePreparation = {...observation, waitedMs: Date.now() - waitStarted,
        limitMs: cacheWaitMs, settleMs, durableWriteAcknowledged: false};
      // Keep observing until shutdown so late writes are not silently dropped.
      await startTrace();
    }
    result.completed = !!state?.titleFrame;
    result.failure = state?.error || (result.completed ? null : "Title frame timeout");
    result.page = await page.evaluate(`({timeOrigin: performance.timeOrigin, titleFrame: window.__startupTitleFrame || null,
      timeline: window.__startupTimeline || [], boot: window.__gaiusBootTimings || null, wasm: window.__gaiusWasmTimings || null,
      state: window.__gaiusMinecraftState || null, progress: window.__gaiusClientStartupProgress || [],
      reloadTimings: window.__gaiusResourceReloadTimings || [],
      reloadSections: window.__gaiusResourceReloadSections || [],
      minecraftCounters: window.__gaiusMinecraftCounters || {},
      minecraftEvents: window.__gaiusMinecraftEvents || [],
      jspiScheduler: window.__gaiusWasmScheduler || null,
      jspiFrame: window.__gaiusFrameTelemetry || null,
      longTasks: performance.getEntriesByType('longtask').map(entry => ({start: entry.startTime,
        duration: entry.duration})),
      bootEvents: window.__gaiusBoot?.events || window.__gaiusBootEvents || [],
      sounds: window.__gaiusBoot?.sounds?.() || null, body: document.body.innerText.slice(0, 1500),
      resources: performance.getEntriesByType('resource').map(entry => entry.toJSON()),
      controlled: !!navigator.serviceWorker?.controller, isolated: crossOriginIsolated,
      runtime: window.__gaiusBoot?.runtime || null, compile: window.__gaiusWasmCompileTimings || null,
      moduleCache: window.__gaiusWasmModuleCache || null,
      shaders: window.__gaiusShaderToolchain?.stats?.() || null,
      gpu: window.__gaiusGpuSelection || null})`);
    if (result.page.runtime?.selected !== "wasm") {
      result.completed = false;
      result.failure = "The selected client fell back from wasm";
    }
    await writeFile(resolve(output, `${label}.png`), Buffer.from((await page.send("Page.captureScreenshot", {format: "png"})).data, "base64"));
    if (cpuProfile) {
      await sleep(400);
      const {profile: mainProfile} = await page.send("Profiler.stop");
      await writeFile(resolve(output, `${label}.title.main.cpuprofile`), JSON.stringify(mainProfile));
      result.mainProfile = `${label}.title.main.cpuprofile`;
    }
    if (result.completed && process.env.GAIUS_STARTUP_WORLD === label) {
      if (cpuProfile) await page.send("Profiler.start");
      result.world = await openWorld(page, label);
      if (cpuProfile) {
        const {profile: worldProfile} = await page.send("Profiler.stop");
        await writeFile(resolve(output, `${label}.world.main.cpuprofile`), JSON.stringify(worldProfile));
        result.world.mainProfile = `${label}.world.main.cpuprofile`;
      }
    }
    for (const [index, worker] of workerProfiles.entries()) {
      try {
        if (worker.started) {
          const {profile} = await page.send("Profiler.stop", {}, worker.sessionId);
          worker.profile = `${label}.worker-${index}.cpuprofile`;
          await writeFile(resolve(output, worker.profile), JSON.stringify(profile));
        }
        const snapshot = await page.send("Runtime.evaluate", {
          expression: `JSON.stringify({timeOrigin:performance.timeOrigin,runtime:globalThis.GaiusWasmWorker&&GaiusWasmWorker.info() || null})`, returnByValue: true,
        }, worker.sessionId).catch(() => null);
        worker.runtime = snapshot?.result?.value ? JSON.parse(snapshot.result.value) : null;
      } catch (error) { worker.stopError = String(error.message || error); }
    }
    result.workers = workerProfiles.map(({sessionId, ...worker}) => worker);
  } catch (error) { result.completed = false; result.failure = String(error.stack || error); }
  finally {
    clearInterval(loadWatch);
    if (result.loadLimitExceeded) { result.completed = false; result.failure = "Machine load exceeded measurement limit"; }
    if (tracing) {
      await stopTrace().catch(error => { result.traceError = String(error.message || error); });
    }
    result.traceSegments = traceSegments;
    result.traceComplete = capture ? traceSegments.length > 0 && traceSegments.every(segment => segment.complete) : null;
    result.traceEventCount = trace.length;
    result.traceCounts = {};
    for (const event of trace) if (/wasm|cache|decompress/i.test(event.name)) result.traceCounts[event.name] = (result.traceCounts[event.name] || 0) + 1;
    result.requests = requests;
    result.requestStarts = [...requestStarts.values()];
    result.errors = errors;
    const navigation = trace.find(event => event.name === "navigationStart"
      && (event.args?.data?.documentLoaderURL === result.url
        || event.args?.data?.url === result.url))
      || trace.find(event => event.name === "navigationStart")
      || null;
    const title = trace.find(event => event.name === "gaius:title:frame");
    // Chrome can flush the navigation event after the page target is closed. The page
    // probe's performance clock starts at navigation, so titleFrame is a safe fallback
    // when the trace contains the title mark but no navigationStart record.
    result.titleMs = navigation && title ? (title.ts - navigation.ts) / 1000
      : (result.completed && Number.isFinite(result.page?.titleFrame) ? result.page.titleFrame : null);
    if (result.completed && result.titleMs === null) {
      result.completed = false;
      result.failure = "Navigation/title trace evidence is incomplete";
    }
    result.stderr = result.failure ? stderr : null;
    result.machineAfter = await machineSnapshot();
    await writeFile(resolve(output, `${label}.trace.json`), JSON.stringify({traceEvents: trace}));
    await writeFile(resolve(output, `${label}.json`), JSON.stringify(result, null, 2));
    const shutdownStarted = Date.now();
    const exited = new Promise(done => chrome.once("exit", done));
    await browser?.send("Browser.close").catch(() => {});
    page?.close();
    browser?.close();
    let exitTimer;
    if (chrome.exitCode === null) await Promise.race([exited,
      new Promise(done => { exitTimer = setTimeout(done, 90000); })]);
    clearTimeout(exitTimer);
    result.shutdown = {elapsedMs: Date.now() - shutdownStarted, forced: chrome.exitCode === null};
    if (chrome.exitCode === null) { chrome.kill(); await Promise.race([exited, sleep(5000)]); }
    await writeFile(resolve(output, `${label}.json`), JSON.stringify(result, null, 2));
  }
  console.log(JSON.stringify({label, completed: result.completed, titleMs: result.titleMs,
    worldMs: result.world?.elapsedMs, worldFailure: result.world?.failure, failure: result.failure}));
  return result;
}

const results = [];
try {
  for (let iteration = 1; iteration <= count; iteration++) {
    for (const mode of modes) {
      if (maxLoad > 0) {
        const snapshot = await machineSnapshot();
        if (Number(snapshot?.load.split(" ")[0]) > maxLoad) {
          await writeFile(resolve(output, "load-limit.json"), JSON.stringify({limit: maxLoad, snapshot}, null, 2));
          throw new Error("Machine load exceeds measurement limit before run");
        }
      }
      const result = await browserRun(mode, iteration);
      results.push(result);
      if (result.loadLimitExceeded) throw new Error("Machine load exceeded measurement limit during run");
    }
  }
  await writeFile(resolve(output, "summary.json"), JSON.stringify(results.map(({page, traceCounts, ...result}) => ({...result,
    boot: page?.boot, wasm: page?.wasm, traceCounts})), null, 2));
} finally {
  await new Promise((done) => server.close(done));
  if (process.env.GAIUS_STARTUP_KEEP_PROFILES !== "1") {
    await rm(resolve(output, "profiles"), {recursive: true, force: true});
  }
}
if (results.some((result) => !result.completed || result.world?.failure)) process.exitCode = 1;
