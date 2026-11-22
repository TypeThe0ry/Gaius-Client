#!/usr/bin/env node
// Usage: node port/scripts/eagler-headed-loopback.mjs --help
import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {createHash, randomUUID} from "node:crypto";
import {createReadStream, createWriteStream} from "node:fs";
import {mkdir, mkdtemp, readFile, rm, stat, writeFile} from "node:fs/promises";
import {resolve, join} from "node:path";
import {setTimeout as sleep} from "node:timers/promises";
import {fileURLToPath, pathToFileURL} from "node:url";
import {chromeLaunchArgs, resolveChromeBinary} from "./chrome-binary.mjs";
import {chromeIsolationArgs, RUNTIME_TELEMETRY_EXPRESSION, runtimeTelemetryFailures} from "./bench/harness-common.mjs";

import {HOST, PROTOCOL, TMP, startEaglerBridge, table} from "../tests/eagler-loopback/bridge.mjs";
import {selfTest, startSyntheticBackend, trackPlay} from "../tests/eagler-loopback/fixture.mjs";

function openCdp(url, onEvent) {
  const ws = new WebSocket(url);
  const pending = new Map();
  let seq = 0;
  ws.addEventListener("message", event => {
    const msg = JSON.parse(event.data);
    if (!msg.id) return onEvent(msg);
    const response = pending.get(msg.id);
    if (!response) return;
    pending.delete(msg.id);
    clearTimeout(response.timer);
    if (msg.error) response.reject(new Error(`${response.method}: ${msg.error.message}`));
    else response.resolve(msg.result);
  });
  ws.addEventListener("close", () => {
    for (const entry of pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(new Error("CDP closed"));
    }
    pending.clear();
  });
  return {ws, async send(method, params = {}) {
    assert.equal(ws.readyState, WebSocket.OPEN, "CDP unavailable");
    const id = ++seq;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`${method}: CDP timeout`));
      }, 15000);
      pending.set(id, {method, resolve, reject, timer});
      ws.send(JSON.stringify({id, method, params}));
    });
  }};
}

async function until(fn, ms, description) {
  const end = performance.now() + ms;
  do {
    const result = await fn();
    if (result) return result;
    await sleep(250);
  } while (performance.now() < end);
  throw new Error(`Timed out waiting for ${description}`);
}

function browserDriver(cdp) {
  const call = cdp.send;
  const ev = async expression => {
    const response = await call("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true});
    if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description || "Page evaluation failed");
    return response.result.value;
  };
  const state = () => ev(`(() => {
    const s = globalThis.__gaiusMinecraftState;
    if (!s) return null;
    const {player, screen, level, loadedChunkCount, screenSize, screenWidgets} = s;
    const canvas = document.querySelector('canvas');
    const r = canvas?.getBoundingClientRect();
    return {screen, level, chunks: loadedChunkCount,
      player: player && {x: player.x, y: player.y, z: player.z},
      widgets: (screenWidgets || []).filter(w => w.visible !== false).map(w => ({text: w.text, type: w.type, active: w.active, value: w.value,
        x: r && screenSize?.width ? r.left + (w.x + w.width / 2) * r.width / screenSize.width : null,
        y: r && screenSize?.height ? r.top + (w.y + w.height / 2) * r.height / screenSize.height : null}))};
  })()`);
  const key = (type, name, code, vk, extra = {}) => call("Input.dispatchKeyEvent", {
    type, key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, ...extra,
  });
  const tap = async (...args) => { await key("rawKeyDown", ...args); await sleep(45); await key("keyUp", ...args); };
  const click = async ({x, y}) => {
    assert(Number.isFinite(x) && Number.isFinite(y), "no canvas coordinates for widget");
    await call("Input.dispatchMouseEvent", {type: "mouseMoved", x, y});
    await call("Input.dispatchMouseEvent", {type: "mousePressed", x, y, button: "left", buttons: 1, clickCount: 1});
    await sleep(50);
    await call("Input.dispatchMouseEvent", {type: "mouseReleased", x, y, button: "left", clickCount: 1});
  };
  const widget = (text, type, index = 0) => until(async () => (await state())?.widgets
    .filter(w => (!text || text.test(w.text)) && (!type || type.test(w.type)))[index], 12000, `widget ${text || type} #${index}`);
  const screen = (re, ms = 20000) => until(async () => {
    const s = await state();
    return re.test(s?.screen ?? "") && s;
  }, ms, re);
  const text = async value => {
    for (const ch of value) {
      const alpha = /^[a-z]$/i.test(ch), digit = /^\d$/.test(ch);
      const [code, vk] = alpha ? [`Key${ch.toUpperCase()}`, ch.toUpperCase().charCodeAt(0)]
        : digit ? [`Digit${ch}`, ch.charCodeAt(0)]
          : ({".": ["Period", 190], ":": ["Semicolon", 186], "/": ["Slash", 191], " ": ["Space", 32]})[ch];
      await key("keyDown", ch, code, vk, {text: ch, modifiers: /[A-Z:]/.test(ch) ? 8 : 0});
      await key("keyUp", ch, code, vk);
    }
  };
  const fill = async (index, value) => {
    const edit = await widget(null, /EditBox/, index);
    await click(edit);
    await tap("a", "KeyA", 65, {modifiers: 2});
    await tap("Backspace", "Backspace", 8);
    await text(value);
    if (edit.value !== undefined) await until(async () => (await widget(null, /EditBox/, index)).value === value, 3000, `address/name input #${index}`);
  };
  return {ev, state, key, tap, click, widget, screen, fill, text};
}

async function enterServer(ui, url, shot) {
  await ui.click(await ui.widget(/^Multiplayer$/i));
  const screen = await ui.screen(/JoinMultiplayerScreen|MultiplayerWarningScreen|SafetyScreen/);
  if (/Warning|Safety/.test(screen.screen)) {
    await ui.click(await ui.widget(/^(Proceed|Continue)$/i));
    await ui.screen(/JoinMultiplayerScreen/);
  }
  await ui.click(await ui.widget(/^Add Server$/i));
  await ui.screen(/(?:ManageServer|EditServer)Screen/);
  await ui.fill(0, "Eagler Loopback");
  for (let i = 0; i < 3; i++) {
    const protocol = await ui.widget(/Protocol/i, /CycleButton/);
    assert.match(protocol.text, /^Connection protocol: /, "protocol selector is not localized");
    if (protocol.text === "Connection protocol: Eagler") break;
    await ui.click(protocol);
    if (i === 2) throw new Error("Eagler mode missing from protocol selector");
    await sleep(250);
  }
  await shot("protocol");
  await ui.fill(1, url);
  await ui.click(await ui.widget(/^(Done|Add Server)$/i));
  await ui.screen(/JoinMultiplayerScreen/);
  // The clean profile contains exactly one saved entry. Down selects it when
  // the list has focus. Tab moves through the vanilla focus chain as fallback.
  await ui.tap("ArrowDown", "ArrowDown", 40);
  let join = await ui.widget(/^Join Server$/i);
  for (let tries = 0; join.active === false && tries < 12; tries++) {
    await ui.tap("Tab", "Tab", 9);
    await ui.tap("ArrowDown", "ArrowDown", 40);
    join = await ui.widget(/^Join Server$/i);
  }
  assert(join.active !== false, "could not select saved Eagler server");
  await ui.click(join);
  if (/ConnectScreen/.test((await ui.state())?.screen || "")) await shot("connecting");
}

const coordinates = player => player && [player.x, player.y, player.z];
const distance = (a, b) => Math.hypot(...a.map((value, i) => value - b[i]));
const relayUrl = url => /relay|\/tunnel(?:[/?]|$)|\/health(?:[/?]|$)/i.test(url);
const LOAD_COUNTERS = `Object.fromEntries(Object.entries(globalThis.__gaiusMinecraftCounters || {})
  .filter(([key]) => /^client\.(?:startWaitingForNewLevel|tickClientLoad|levelReady|notifyPlayerLoaded|handleChunkBatch|loadingPacketsReceived)/.test(key)))`;
const GPU_STATE = `(() => {
  const g = globalThis, w = g.GaiusWebGpu, s = w?.stats;
  return {selection: g.__gaiusGpuSelection ?? null, backend: g.__gaiusGpuBackend ?? null,
    active: w?.active === true, failure: w?.failure || w?.lostReason || null,
    presents: s?.presents ?? null, translateErrors: s?.translateErrors ?? null,
    errors: s?.errors ?? null, pipelineFailures: s?.pipelineFailures ?? null, skippedDraws: s?.skippedDraws ?? null,
    messages: Array.isArray(w?.messages) ? w.messages.slice(-40) : []};
})()`;
const TITLE_BRIDGE = `(() => {
  const g = globalThis;
  const bridge = g.__gaiusNettyBridge;
  const stats = bridge?.stats ?? g.__gaiusNettyBridgeBootstrapState?.stats;
  const counters = ['opened', 'directAttempts', 'relayAttempts', 'relayFailovers',
    'relayPreflights', 'relayRegistryRequests', 'relayParallelPreparations'];
  return {
    bridgePresent: !!bridge,
    bootstrapPresent: !!g.__gaiusNettyBridgeBootstrapState,
    bridgeCreated: stats?.created ?? null,
    initTrace: (g.__gaiusNettyBridgeInitTrace || []).map(({phase}) => phase),
    initError: g.__gaiusNettyBridgeInitError ?? null,
    counters: stats ? Object.fromEntries(counters.map(key => [key, stats[key] ?? null])) : null,
    connectPhases: stats?.connectPhases?.map(({phase}) => phase) ?? null,
    resourceHints: Array.from(document.querySelectorAll('link[rel=preconnect],link[rel=dns-prefetch]'),
      link => ({rel: link.rel, url: link.href})),
  };
})()`;

async function headed() {
  const portable = resolve(process.env.PORTABLE || "");
  assert(process.env.PORTABLE && (await stat(portable)).isFile(), "set PORTABLE to an existing WASM-only HTML");
  assert(process.env.DISPLAY, "set DISPLAY or run under xvfb-run");
  const externalPort = process.env.BACKEND_PORT === undefined ? null : Number(process.env.BACKEND_PORT);
  const chromeBin = resolveChromeBinary();
  const out = resolve(process.env.OUT || await mkdtemp(join(TMP, "evidence-")));
  await mkdir(out, {recursive: true});
  const work = await mkdtemp(join(TMP, "run-"));
  const report = {startedAt: new Date().toISOString(), portable, headed: true, protocol: PROTOCOL,
    backend: externalPort === null ? "synthetic TCP" : "existing loopback TCP", connections: [],
    samples: [], exceptions: [], blockedRequests: [], milestones: {}};
  const log = createWriteStream(join(out, "browser.log"));
  console.log(`Eagler loopback evidence in ${out}`);
  const note = (kind, text) => log.write(`${new Date().toISOString()} ${kind} ${text}\n`);
  let backend, bridge, chrome, cdp, chromeExit, shot;
  const chatToken = `loopback ${randomUUID().slice(0, 8)}`;
  report.needsVanilla = [];
  const checkKernel = text => { if (/NEEDS_VANILLA/.test(text)) report.needsVanilla.push(text); };
  const metrics = trackPlay(chatToken);
  const abort = new AbortController();
  const interrupt = () => { abort.abort(); cdp?.ws.close(); chrome?.kill(); };
  process.once("SIGINT", interrupt);
  process.once("SIGTERM", interrupt);
  try {
    const hash = createHash("sha256");
    for await (const chunk of createReadStream(portable)) hash.update(chunk);
    report.portableSha256 = hash.digest("hex");
    if (externalPort === null) backend = await startSyntheticBackend(chatToken);
    report.gatewayPlay = metrics.evidence;
    const play = report.play = backend?.playEvidence ?? metrics.evidence;
    bridge = await startEaglerBridge(externalPort ?? backend.port, report.connections, metrics.observe);
    report.endpoint = bridge.url;
    chrome = spawn(chromeBin, [
      ...chromeLaunchArgs(), ...chromeIsolationArgs({allowNetwork: false}), "--remote-debugging-address=127.0.0.1",
      "--remote-debugging-port=0", `--user-data-dir=${work}`, "--window-size=1280,800", "--lang=en-US",
      "--allow-file-access-from-files", "--ignore-gpu-blocklist", "--disable-dev-shm-usage", "about:blank",
    ], {env: {...process.env, TMPDIR: TMP}, stdio: ["ignore", "pipe", "pipe"]});
    chromeExit = new Promise(done => { chrome.once("exit", done); chrome.once("error", done); });
    chrome.stdout.on("data", data => note("chrome", data));
    chrome.stderr.on("data", data => note("chrome", data));
    const debugPort = await until(async () => {
      abort.signal.throwIfAborted();
      assert(chrome.pid && chrome.exitCode === null, "Chrome exited before CDP startup");
      const data = await readFile(join(work, "DevToolsActivePort"), "utf8").catch(() => "");
      return /^\d+/.exec(data)?.[0];
    }, 20000, "Chrome CDP port");
    const [target] = (await (await fetch(`http://${HOST}:${debugPort}/json/list`, {signal: AbortSignal.timeout(5000)})).json())
      .filter(entry => entry.type === "page");
    let clientChat;
    let titleNetwork = {httpAttempts: [], webSockets: [], otherRequestCount: 0};
    cdp = openCdp(target.webSocketDebuggerUrl, ({method, params}) => {
      if (method === "Runtime.consoleAPICalled") {
        const message = params.args.map(a => a.value ?? a.description ?? "").join(" ");
        note(`console.${params.type}`, message);
        checkKernel(message);
        if (/\[CHAT\]/.test(message) && message.includes(chatToken)) clientChat = message;
      } else if (method === "Runtime.exceptionThrown" || method === "Inspector.targetCrashed") {
        report.exceptions.push(params?.exceptionDetails?.exception?.description || params?.exceptionDetails?.text || method);
      } else if (method === "Network.requestWillBeSent" || method === "Network.webSocketCreated") {
        const url = URL.parse(params.request?.url || params.url);
        const remote = url && /^(https?|wss?):$/.test(url.protocol);
        if (titleNetwork) {
          if (method === "Network.webSocketCreated") titleNetwork.webSockets.push(params.url);
          else if (remote) titleNetwork.httpAttempts.push({method: params.request.method, url: url.href});
          else titleNetwork.otherRequestCount++;
        }
        if (remote && url.hostname !== HOST && !report.blockedRequests.includes(url.href)) {
          report.blockedRequests.push(url.href); // CDP attempt, not evidence that the remote host was reached
        }
      }
    });
    await until(() => {
      assert(cdp.ws.readyState < WebSocket.CLOSING, "CDP connection closed");
      return cdp.ws.readyState === WebSocket.OPEN;
    }, 5000, "Chrome CDP connection");
    await Promise.all(["Page.enable", "Runtime.enable", "Network.enable", "Inspector.enable", "Page.bringToFront"].map(name => cdp.send(name)));
    await cdp.send("Emulation.setFocusEmulationEnabled", {enabled: true});
    await cdp.send("Page.addScriptToEvaluateOnNewDocument", {
      source: "try { localStorage.setItem('gaius.playerName', 'EaglerGuest'); } catch {}",
    });
    const page = pathToFileURL(portable);
    if (process.env.GPU) page.searchParams.set("gpu", process.env.GPU);
    const navigation = await cdp.send("Page.navigate", {url: page.href});
    assert(!navigation.errorText, navigation.errorText);
    const ui = browserDriver(cdp);
    shot = async name => {
      const {data} = await cdp.send("Page.captureScreenshot", {format: "png"});
      await writeFile(join(out, `${name}.png`), Buffer.from(data, "base64"));
    };
    const assertHealthy = () => {
      abort.signal.throwIfAborted();
      assert.deepEqual(report.exceptions, [], "uncaught browser errors");
      assert.deepEqual(report.needsVanilla, [], "native kernel fallback requested");
      const issue = report.connections.find(connection => connection.error);
      assert(!issue, issue?.error);
      assert(!backend?.errors.length, backend?.errors.join("\n"));
    };
    await until(async () => {
      assertHealthy();
      const s = await ui.state();
      if (/BrowserProfileScreen/.test(s?.screen || "")) await ui.click(await ui.widget(/^Done$/i));
      return /TitleScreen/.test(s?.screen || "");
    }, Number(process.env.BOOT_TIMEOUT_MS || 240000), "WASM title screen");
    report.runtime = await ui.ev(RUNTIME_TELEMETRY_EXPRESSION);
    report.gpuAtTitle = await ui.ev(GPU_STATE);
    assert.equal(report.runtime?.runtime, "wasm", "expected WASM runtime");
    assertHealthy();
    await shot("title");
    report.milestones.title = new Date().toISOString();
    const bridgeAtTitle = await ui.ev(TITLE_BRIDGE);
    const relayHttpAttempts = titleNetwork.httpAttempts.filter(request => relayUrl(request.url));
    const relayResourceHints = bridgeAtTitle.resourceHints.filter(hint => relayUrl(hint.url));
    report.titleRelay = {...bridgeAtTitle, ...titleNetwork, relayHttpAttempts, relayResourceHints,
      fakeConnections: report.connections.length};
    titleNetwork = null;
    const startup = report.titleRelay;
    startup.zeroObservedWork = !startup.bridgePresent && !startup.bootstrapPresent && startup.bridgeCreated !== true &&
      startup.initTrace.length === 0 && !startup.initError && !(startup.connectPhases?.length) &&
      Object.values(startup.counters ?? {}).every(value => value === null || value === 0) &&
      startup.fakeConnections === 0 && startup.webSockets.length === 0 &&
      startup.relayHttpAttempts.length === 0 && startup.relayResourceHints.length === 0;
    assert(startup.zeroObservedWork, `unexpected title-time relay work: ${JSON.stringify(startup)}`);
    await enterServer(ui, bridge.url, shot);
    const first = await until(async () => {
      assertHealthy();
      const s = await ui.state();
      assert(!/DisconnectedScreen/.test(s?.screen || ""), `client disconnected: ${JSON.stringify(s)}`);
      return report.connections.some(connection => connection.playLogin && !connection.close) && s?.level && s.chunks > 0 && !s.screen &&
        coordinates(s.player)?.every(Number.isFinite) && s;
    }, Number(process.env.JOIN_TIMEOUT_MS || 120000), "Eagler world entry");
    report.milestones.world = first;
    report.gpuAtWorld = await ui.ev(GPU_STATE);
    await shot("world");
    await ui.tap("t", "KeyT", 84);
    await ui.screen(/ChatScreen/);
    await ui.text(chatToken);
    await ui.tap("Enter", "Enter", 13);
    await until(() => { assertHealthy(); return play.chatSent && clientChat; }, 15000, "TCP chat and its on-client echo");
    report.milestones.chat = clientChat;
    const start = performance.now();
    const wireStart = play.moves;
    const moveKeys = [["w", "KeyW", 87], ["d", "KeyD", 68], ["s", "KeyS", 83], ["a", "KeyA", 65]];
    for (let i = 0; performance.now() - start < 60000; i++) {
      const key = moveKeys[i % moveKeys.length];
      const before = coordinates((await ui.state())?.player);
      assert(before?.every(Number.isFinite), "missing player position before movement input");
      const wireBefore = play.moves;
      const wirePositionBefore = play.lastPosition;
      await ui.key("rawKeyDown", ...key);
      try { await sleep(Math.max(0, Math.min(4000, 60000 - (performance.now() - start))), null, {signal: abort.signal}); }
      finally { await ui.key("keyUp", ...key).catch(() => {}); }
      const s = await ui.state();
      const position = coordinates(s?.player);
      assertHealthy();
      assert(s?.level && !s.screen && position?.every(Number.isFinite), `lost world: ${JSON.stringify(s)}`);
      const connection = report.connections.find(entry => entry.playLogin);
      assert(connection && !connection.close && !connection.backendEnded, "play connection closed during soak");
      report.samples.push({ms: Math.round(performance.now() - start), position, moved: distance(before, position),
        movementPackets: play.moves - wireBefore, wirePosition: play.lastPosition,
        wireMoved: wirePositionBefore && play.lastPosition ? distance(wirePositionBefore, play.lastPosition) : null});
    }
    report.soakMs = Math.round(performance.now() - start);
    report.runtimeEnd = await ui.ev(RUNTIME_TELEMETRY_EXPRESSION);
    report.loadCounters = await ui.ev(LOAD_COUNTERS);
    assert.deepEqual(runtimeTelemetryFailures(report.runtimeEnd), []);
    report.gpu = await ui.ev(GPU_STATE);
    if (process.env.GPU === "webgpu") {
      assert.equal(report.gpu.backend, "webgpu");
      assert.equal(report.gpu.selection?.selected, "webgpu");
      assert.equal(report.gpu.selection?.fallback, false);
      assert(report.gpu.active && !report.gpu.failure && report.gpu.presents > 0, "WebGPU did not render successfully");
      assert.equal(report.gpu.translateErrors, 0);
      assert.equal(report.gpu.errors, 0);
      assert.equal(report.gpu.pipelineFailures, 0);
      checkKernel(JSON.stringify(report.gpu.messages));
    }
    const moved = report.samples.filter(sample => sample.moved > 0.15 && sample.movementPackets > 0 && sample.wireMoved > 0.15);
    assert(moved.length >= 3 && moved.at(-1).ms >= 50000 && moved[0].ms <= 15000 && play.moves > wireStart,
      "PLAY movement was not observed throughout the soak");
    await shot("soak");
    assertHealthy();
    report.status = "PASS";
  } catch (error) {
    report.status = "FAIL";
    report.error = error.stack;
    if (cdp?.ws.readyState === WebSocket.OPEN) {
      await browserDriver(cdp).state().then(s => { report.failureState = s; }).catch(() => {});
      await shot?.("failure").catch(() => {});
    }
    throw error;
  } finally {
    process.removeListener("SIGINT", interrupt);
    process.removeListener("SIGTERM", interrupt);
    for (const connection of report.connections) {
      if (!connection.close) connection.stillOpenAtCleanup = true;
    }
    cdp?.ws.close();
    if (chrome && chrome.exitCode === null && chrome.signalCode === null) {
      chrome.kill("SIGTERM");
      await Promise.race([chromeExit, sleep(3000)]);
      if (chrome.exitCode === null && chrome.signalCode === null) chrome.kill("SIGKILL");
      await chromeExit;
    }
    await bridge?.close();
    await backend?.close();
    await new Promise(done => log.end(done));
    await rm(work, {recursive: true, force: true, maxRetries: 4, retryDelay: 250});
    report.finishedAt = new Date().toISOString();
    await writeFile(join(out, "report.json"), JSON.stringify(report, null, 2) + "\n");
  }
}

async function main() {
  if (process.argv.includes("--help")) {
    console.log(`Usage:
  TMPDIR=${TMP} node port/scripts/eagler-headed-loopback.mjs --self-test
  TMPDIR=${TMP} PORTABLE=/path/to/Gaius.html xvfb-run -a node port/scripts/eagler-headed-loopback.mjs

Uses a local synthetic Minecraft ${table.name} (${PROTOCOL}) TCP backend by default.
MC_SERVER_DIR selects cached ${table.name} versions/ and libraries/ for the synthetic world.
BACKEND_PORT selects an already running, authorized, offline-mode loopback backend.
OUT selects an evidence directory. GAIUS_CHROME_BIN selects installed Chrome.
EAGLER_WS_MODULE can point to an installed ws package. GPU selects the portable renderer.`);
    return;
  }
  await mkdir(TMP, {recursive: true, mode: 0o700});
  if (process.argv.includes("--self-test")) {
    await selfTest();
    return;
  }
  await headed();
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { await main(); } catch (error) { console.error(error); process.exitCode = 1; }
}
