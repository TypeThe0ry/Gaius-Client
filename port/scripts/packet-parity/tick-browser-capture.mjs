import {spawn} from "node:child_process";
import {createHash} from "node:crypto";
import {appendFileSync, createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {createServer} from "node:http";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {validateTickCapture, validateTickScript} from "./tick-compare.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const runtimeFiles = ["singleplayer-server.wasm", "gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-worker.js"];
const escapeScript = value => JSON.stringify(value).replaceAll("<", "\\u003c");
const sleep = millis => new Promise(done => setTimeout(done, millis));

// The Rust worldgen and light kernels install their results from new Java threads in the order the
// kernel workers finish, so a capture with them has no single generation order. The capture runs the
// vanilla Java path instead: the page never posts a kernel port (port/web/boot/gaius-boot.js does in
// production), and both kernels are switched off before the Worker bootstrap runs. The Worker's settle
// probe fails the capture if a kernel runtime appears or a kernel job is submitted anyway.
export const KERNELS_OFF = Object.freeze({
  mode: "off",
  families: {worldgen: false, light: false},
  switches: ["no gaius-kernel-port message", "__gaiusWorldgenKernelConfig.enabled=false",
    "__gaiusLightKernelConfig.enabled=false", "GAIUS_LIGHT_KERNEL=false"],
  reason: "kernel results arrive in kernel-worker completion order; the JVM capture runs vanilla Java generation",
});
const KERNEL_OFF_SOURCE = "globalThis.GAIUS_LIGHT_KERNEL=false;globalThis.__gaiusLightKernelConfig={enabled:false};"
  + "globalThis.__gaiusWorldgenKernelConfig={enabled:false};globalThis.__gaiusChunkOrderTelemetryEnabled=true;";

/**
 * The Worker's settle probe reports (tick-parity-probe messages, outside the compared event rows):
 * the parity schedule was installed, the capture started and ended with the kernels off, no kernel
 * job and no dispatcher pump hop happened, and the background schedule was never broken.
 */
export function validateProbeReports(messages) {
  const reports = messages.filter(message => message?.type === "tick-parity-probe").map(message => JSON.parse(message.json));
  const phase = name => {
    const selected = reports.filter(report => report.phase === name);
    if (selected.length !== 1) throw new Error(`probe report ${name}: expected one, found ${selected.length}`);
    return selected[0];
  };
  const configured = phase("configured");
  if (configured.executor !== "serial-fifo" || configured.requested !== true) {
    throw new Error("Worker without the parity executor schedule: " + JSON.stringify(configured));
  }
  if (reports.some(report => report.phase === "failed")) throw new Error("probe reported a failed capture");
  const started = phase("started");
  const ended = phase("complete");
  for (const report of [started, ended]) {
    if (report.executor !== "serial-fifo" || report.broken !== false) throw new Error("background schedule broken: " + JSON.stringify(report));
    if (report.kernels?.present !== false || report.kernels?.inFlight !== 0 || report.kernels?.submitted !== 0) {
      throw new Error("kernels were not off: " + JSON.stringify(report.kernels));
    }
    if (report.dispatcherPumps !== 0) throw new Error("worldgen dispatcher hopped through host activations: " + report.dispatcherPumps);
  }
  if (ended.failedTasks !== 0) throw new Error(`background tasks failed: ${ended.failedTasks}`);
  if (!(ended.queuedTasks > 0) || !(ended.holds > 0)) throw new Error("FIFO schedule unused: " + JSON.stringify(ended));
  // The hold taken by the last settle is still queued or running when the footer is written.
  if (ended.pending !== 1) throw new Error("background work besides the final hold at the end: " + ended.pending);
  return {configured, started, complete: ended};
}

/**
 * `extras.preamble` is experiment-only JavaScript that runs in the Worker before the bootstrap
 * (timer or run-queue instrumentation for isolation runs); a normal capture passes none.
 */
export function capturePage(profile, script, extras = {}) {
  validateTickScript(script);
  if (profile.id !== script.profile || !profile.storage || !Number.isSafeInteger(profile.worldVersion)) {
    throw new Error("script/storage profile mismatch");
  }
  // BrowserIntegratedServerMain validates session IDs as exactly 32 lowercase hex
  // characters before entering Java main. Keep the world label human-readable, but
  // use a deterministic valid session token for the in-process parity peer.
  const settings = {type: "start", launchGeneration: "1", sessionId: "7469636b706172697479323633000000", worldId: "tick-parity",
    profileId: profile.id, worldVersion: profile.worldVersion, storageSchema: profile.storage.schema,
    storageDatabaseName: profile.storage.databaseName, storagePrefix: profile.storage.prefix,
    storageOpfsDirectory: profile.storage.opfsDirectory, seed: script.seed,
    renderDistance: script.viewDistance, simulationDistance: script.simulationDistance,
    // Keep both selectors explicit: the page is a WasmGC server capture, never an
    // automatic or Java fallback.  The bootstrap reports serverRuntime in its
    // startup/ping telemetry, while runtime is consumed by the shared launch
    // contract and makes the intended client side selection reviewable.
    runtime: "wasm", serverRuntime: "wasm"};
  return `<!doctype html><meta charset="utf-8"><title>Tick parity server capture</title><script>
const script = ${escapeScript(script)};
const settings = ${escapeScript(settings)};
const origin = location.origin;
globalThis.__tickParityDone = new Promise((resolve, reject) => {
  const source = ${escapeScript(KERNEL_OFF_SOURCE + (extras.preamble || ""))} + "globalThis.__gaiusTickParityDebug=true;globalThis.__gaiusTickParityScript=" +
    JSON.stringify(JSON.stringify(script)) + ";importScripts(" + JSON.stringify(origin + "/bootstrap.js") + ");";
  const workerUrl = URL.createObjectURL(new Blob([source], {type: "text/javascript"}));
  const worker = new Worker(workerUrl);
  const ports = new MessageChannel();
  globalThis.__tickParityResources = {worker, ports, workerUrl};
  worker.onerror = error => reject(new Error(error.message));
  worker.onmessage = event => {
    const message = event.data;
    if (globalThis.__tickParityDiagnostic) globalThis.__tickParityDiagnostic(message);
    if (message?.type === "tick-parity-event") {
      globalThis.__tickParityEmit(message.json);
      const row = JSON.parse(message.json);
      if (row.kind === "failure") reject(new Error(row.detail));
      if (row.kind === "complete") resolve({complete: true});
    } else {
      globalThis.__tickParityControl(JSON.stringify(message));
      if (["runtime-error", "bootstrap-crash", "storage-flush-failed"].includes(message?.type)) {
        reject(new Error(message.detail || message.type));
      }
    }
  };
  worker.postMessage({...settings, port: ports.port2, serverWasmUrl: origin + "/singleplayer-server.wasm",
    wasmRuntimeScripts: ${escapeScript(runtimeFiles.slice(1))}.map(name => ({name, url: origin + "/" + name}))}, [ports.port2]);
});
globalThis.__tickParityDone.catch(() => {});
</script>`;
}

async function checksum(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

async function connect(url, eventHandler) {
  const socket = new WebSocket(url);
  await new Promise((done, reject) => { socket.onopen = done; socket.onerror = reject; });
  let sequence = 0;
  const requests = new Map();
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const pending = requests.get(message.id);
      requests.delete(message.id);
      if (message.error) pending?.reject(new Error(JSON.stringify(message.error)));
      else pending?.resolve(message.result);
    } else eventHandler(message);
  };
  socket.onclose = () => {
    for (const pending of requests.values()) pending.reject(new Error("Chrome debugger closed"));
    requests.clear();
  };
  return {close: () => socket.close(), call: (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    requests.set(id, {resolve, reject});
    socket.send(JSON.stringify({id, method, params}));
  })};
}

async function capture(options) {
  if (!options.dist || !options.script || !options.out || !options.chrome) {
    throw new Error("tick-browser-capture.mjs --dist <WasmGC dist> --script <JVM script.json> --chrome <exe> --out <fresh directory>; run it through your slot wrapper if you use one");
  }
  const out = resolve(options.out);
  if (existsSync(out) || out.replaceAll("\\", "/").toLowerCase().includes("/port/work/")) throw new Error("capture needs a fresh non-shared output directory");
  const dist = resolve(options.dist);
  const scriptPath = resolve(options.script);
  const script = validateTickScript(JSON.parse(readFileSync(scriptPath, "utf8")));
  const profile = JSON.parse(readFileSync(join(root, "port/versions", script.profile + ".json"), "utf8"));
  const manifestPath = join(dist, "gaius-site.json");
  const manifest = existsSync(manifestPath) ? JSON.parse(readFileSync(manifestPath, "utf8")) : null;
  const asset = name => join(dist, manifest?.assets?.[name] || name);
  const sources = new Map(runtimeFiles.map(name => ["/" + name, asset(name)]));
  sources.set("/bootstrap.js", join(root, "port/web/singleplayer/server-worker-bootstrap.js"));
  for (const path of [...sources.values(), options.chrome]) if (!existsSync(path)) throw new Error("missing cached input " + path);
  mkdirSync(out);
  const verbose = options.verbose === true || options.verbose === "true";
  const diagnosticPath = join(out, "diagnostic.log");
  const diagnostic = value => {
    if (!verbose) return;
    const line = `[${new Date().toISOString()}] ${typeof value === "string" ? value : JSON.stringify(value)}\n`;
    appendFileSync(diagnosticPath, line);
  };
  if (verbose) writeFileSync(diagnosticPath, "");
  const preamble = options.preamble ? readFileSync(resolve(options.preamble), "utf8") : "";
  const page = capturePage(profile, script, {preamble});
  writeFileSync(join(out, "capture.html"), page);
  writeFileSync(join(out, "capture-plan.json"), JSON.stringify({scriptSha256: await checksum(scriptPath), profile: script.profile,
    runtimeSha256: Object.fromEntries(await Promise.all([...sources].map(async ([name, path]) => [name, await checksum(path)]))),
    method: "tick-exact-v1", peer: "in-process-play", freshChromeProfile: true,
    schedule: "serial FIFO background thread, calling-thread IO, inline dispatcher re-registration",
    kernels: KERNELS_OFF}, null, 2) + "\n");
  const http = createServer((request, response) => {
    response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
    response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
    response.setHeader("Cache-Control", "no-store");
    const path = new URL(request.url, "http://127.0.0.1").pathname;
    if (path === "/") { response.setHeader("Content-Type", "text/html"); response.end(page); return; }
    const source = sources.get(path);
    if (!source) { response.writeHead(404); response.end(); return; }
    response.setHeader("Content-Type", path.endsWith(".wasm") ? "application/wasm" : "text/javascript");
    createReadStream(source).on("error", () => response.destroy()).pipe(response);
  });
  let chrome;
  let debuggerClient;
  let watchdog;
  try {
    await new Promise((done, reject) => { http.once("error", reject); http.listen(0, "127.0.0.1", done); });
    const userData = join(out, "chrome-profile");
    chrome = spawn(resolve(options.chrome), ["--headless=new", "--no-first-run", "--no-default-browser-check",
      "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0", "--user-data-dir=" + userData, "about:blank"],
    {windowsHide: true, stdio: ["ignore", "pipe", "pipe"]});
    let launchError;
    chrome.on("error", error => { launchError = error; });
    const log = createWriteStream(join(out, "chrome.log"));
    chrome.stdout.pipe(log, {end: false});
    chrome.stderr.pipe(log, {end: false});
    chrome.on("close", () => log.end());
    const activePort = join(userData, "DevToolsActivePort");
    const deadline = Date.now() + 30000;
    while (!existsSync(activePort)) {
      if (launchError || chrome.exitCode !== null || Date.now() >= deadline) throw launchError || new Error("Chrome debugger unavailable");
      await sleep(100);
    }
    const port = Number(readFileSync(activePort, "utf8").split("\n")[0]);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("invalid debugger port");
    const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, {method: "PUT"})).json();
    let captureError;
    let totalBytes = 0;
    const events = [];
    const controls = [];
    let loaded;
    const pageLoaded = new Promise(done => { loaded = done; });
    debuggerClient = await connect(target.webSocketDebuggerUrl, message => {
      if (verbose && (message.method === "Runtime.consoleAPICalled" || message.method === "Runtime.exceptionThrown" ||
          message.method === "Log.entryAdded" || message.method === "Page.javascriptDialogOpening")) diagnostic(message);
      if (message.method === "Page.loadEventFired") loaded();
      if (message.method !== "Runtime.bindingCalled") return;
      try {
        const {name, payload} = message.params;
        if (name === "__tickParityEmit") {
          totalBytes += Buffer.byteLength(payload);
          if (events.length >= 200000 || totalBytes > 64 * 1024 * 1024) throw new Error("capture budget exhausted");
          events.push(JSON.parse(payload));
          appendFileSync(join(out, "events.jsonl"), payload + "\n");
        } else if (name === "__tickParityControl") {
          controls.push(JSON.parse(payload));
          appendFileSync(join(out, "worker-control.jsonl"), payload + "\n");
        }
      } catch (error) { captureError = error; }
    });
    await debuggerClient.call("Runtime.enable");
    await debuggerClient.call("Log.enable").catch(error => diagnostic({logEnableError: String(error)}));
    await debuggerClient.call("Runtime.addBinding", {name: "__tickParityEmit"});
    await debuggerClient.call("Runtime.addBinding", {name: "__tickParityControl"});
    await debuggerClient.call("Page.enable");
    const bootTimeoutMs = Number(options.timeoutSeconds || 600) * 1000;
    const expired = new Promise((unused, reject) => {
      watchdog = setTimeout(() => reject(new Error(`Worker capture handshake timeout after ${bootTimeoutMs / 1000}s; see ${diagnosticPath}`)), bootTimeoutMs);
    });
    diagnostic({phase: "navigate", url: `http://127.0.0.1:${http.address().port}/`, profile: script.profile,
      runtime: "wasm", serverRuntime: "wasm"});
    await debuggerClient.call("Page.navigate", {url: `http://127.0.0.1:${http.address().port}/`});
    await Promise.race([pageLoaded, expired]);
    const result = await Promise.race([
      debuggerClient.call("Runtime.evaluate", {expression: "globalThis.__tickParityDone", awaitPromise: true, returnByValue: true}),
      expired,
    ]);
    if (captureError) throw captureError;
    if (result.exceptionDetails || result.result?.value?.complete !== true) throw new Error(JSON.stringify(result));
    const validation = validateTickCapture(events);
    const probe = validateProbeReports(controls);
    writeFileSync(join(out, "capture.json"), JSON.stringify({...validation, eventsSha256: await checksum(join(out, "events.jsonl")),
      kernels: KERNELS_OFF, probe,
      scope: "vanilla PLAY codec/handler boundary, not real client transport or full M2 acceptance"}, null, 2) + "\n");
    console.log(`TICK_WORKER_CAPTURE ticks=${validation.ticks} packets=${validation.packets} inputs=${validation.inputs}`);
  } catch (error) {
    diagnostic({phase: "capture-error", error: String(error), stack: error?.stack});
    throw error;
  } finally {
    clearTimeout(watchdog);
    if (debuggerClient) {
      await Promise.race([debuggerClient.call("Browser.close").catch(() => {}), sleep(2000)]);
      debuggerClient.close();
    }
    chrome?.kill();
    await new Promise(done => http.close(done));
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const values = process.argv.slice(2);
  const options = Object.fromEntries(values.reduce((entries, value, index) => {
    if (value === "--verbose") entries.push(["verbose", true]);
    else if (value.startsWith("--")) entries.push([value.slice(2), values[index + 1]]);
    return entries;
  }, []));
  await capture(options);
}
