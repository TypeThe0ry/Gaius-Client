#!/usr/bin/env node

import {spawn} from "node:child_process";
import {readFileSync} from "node:fs";
import {mkdtemp, mkdir, rm, writeFile} from "node:fs/promises";
import {createServer} from "node:net";
import {tmpdir} from "node:os";
import {basename, dirname, resolve} from "node:path";
import {
  RUNTIME_GUARD_EXPRESSION,
  clinitSuspensionCount,
  runtimeGuardFailures,
} from "../../tools/teavm-runtime-guards.mjs";

const defaultChrome = process.platform === "win32"
  ? "C:/Program Files/Google/Chrome/Application/chrome.exe"
  : process.platform === "darwin"
    ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    : "google-chrome";
const targetUrl = process.argv[2] || "http://127.0.0.1:8781/dist/?targetFps=120";
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
// Without GAIUS_VERSION_PROFILE_PATH the default profile is port/config.json's versionProfile.
const profileId = basename(nativePath(
  process.env.GAIUS_VERSION_PROFILE_PATH
    || JSON.parse(readFileSync(new URL("../config.json", import.meta.url), "utf8")).versionProfile,
).replaceAll("\\", "/")).replace(/\.json$/, "");
const defaultOutputRoot = nativePath(process.env.GAIUS_BUILD_ROOT) ||
  (process.env.GAIUS_VERSION_PROFILE_PATH ? `port/target/${profileId}` : "port/target");
const outputPrefix = resolve(nativePath(process.argv[3] || `${defaultOutputRoot}/client-startup-current`));
const timeoutMillis = Math.max(30_000, Number(process.env.GAIUS_PROFILE_TIMEOUT_MS || "180000"));
const chromeBinary = nativePath(process.env.GAIUS_CHROME_BIN) || defaultChrome;
const playerName = process.env.GAIUS_PROFILE_PLAYER || "GaiusProfile";
const cpuProfiling = process.env.GAIUS_CPU_PROFILE !== "false";

class CdpSession {
  constructor(url) {
    this.socket = new WebSocket(url);
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
  }

  async open() {
    if (this.socket.readyState === WebSocket.OPEN) {
      return;
    }
    await new Promise((resolveOpen, rejectOpen) => {
      const onOpen = () => {
        cleanup();
        resolveOpen();
      };
      const onError = (event) => {
        cleanup();
        rejectOpen(event.error || new Error("Chrome DevTools WebSocket failed"));
      };
      const cleanup = () => {
        this.socket.removeEventListener("open", onOpen);
        this.socket.removeEventListener("error", onError);
      };
      this.socket.addEventListener("open", onOpen);
      this.socket.addEventListener("error", onError);
    });
    this.socket.addEventListener("message", (event) => this.handleMessage(event.data));
    this.socket.addEventListener("close", () => {
      for (const {reject} of this.pending.values()) {
        reject(new Error("Chrome DevTools WebSocket closed"));
      }
      this.pending.clear();
    });
  }

  handleMessage(raw) {
    const message = JSON.parse(String(raw));
    if (message.id != null) {
      const pending = this.pending.get(message.id);
      if (!pending) {
        return;
      }
      this.pending.delete(message.id);
      if (message.error) {
        pending.reject(new Error(`${pending.method}: ${message.error.message}`));
      } else {
        pending.resolve(message.result || {});
      }
      return;
    }
    for (const listener of this.listeners.get(message.method) || []) {
      listener(message.params || {});
    }
  }

  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolveSend, rejectSend) => {
      this.pending.set(id, {method, resolve: resolveSend, reject: rejectSend});
      this.socket.send(JSON.stringify({id, method, params}));
    });
  }

  on(method, listener) {
    const listeners = this.listeners.get(method) || [];
    listeners.push(listener);
    this.listeners.set(method, listeners);
  }

  close() {
    this.socket.close();
  }
}

function sleep(millis) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, millis));
}

async function freePort() {
  const server = createServer();
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const address = server.address();
  await new Promise((resolveClose) => server.close(resolveClose));
  return address.port;
}

async function waitForJson(url, timeout) {
  const deadline = Date.now() + timeout;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        return await response.json();
      }
      lastError = new Error(`${response.status} ${response.statusText}`);
    } catch (error) {
      lastError = error;
    }
    await sleep(100);
  }
  throw new Error(`Timed out waiting for ${url}: ${lastError || "no response"}`);
}

function percentile(values, fraction) {
  if (values.length === 0) {
    return 0;
  }
  const sorted = values.toSorted((left, right) => left - right);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)];
}

function remoteValue(result) {
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.text || "Runtime.evaluate failed");
  }
  return result.result?.value;
}

async function evaluate(session, expression) {
  return remoteValue(await session.send("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  }));
}

async function waitFor(session, expression, timeout, label) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      if (await evaluate(session, expression)) {
        return;
      }
    } catch (error) {
      if (Date.now() >= deadline) {
        throw error;
      }
    }
    await sleep(100);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

const profileDirectory = await mkdtemp(`${tmpdir()}/gaius-startup-profile-`);
const debuggingPort = await freePort();
const chromeOutput = [];
const chrome = spawn(chromeBinary, [
  "--headless=new",
  `--remote-debugging-port=${debuggingPort}`,
  "--remote-allow-origins=*",
  `--user-data-dir=${profileDirectory}`,
  "--no-first-run",
  "--no-default-browser-check",
  "--disable-background-networking",
  "--disable-component-update",
  "--disable-domain-reliability",
  "--disable-features=Translate,MediaRouter",
  "about:blank",
], {stdio: ["ignore", "pipe", "pipe"]});
chrome.stdout.on("data", (chunk) => chromeOutput.push(String(chunk)));
chrome.stderr.on("data", (chunk) => chromeOutput.push(String(chunk)));

let session;
let profileStopped = false;
try {
  await waitForJson(`http://127.0.0.1:${debuggingPort}/json/version`, 15_000);
  const targets = await waitForJson(`http://127.0.0.1:${debuggingPort}/json/list`, 15_000);
  const page = targets.find((target) => target.type === "page");
  if (!page?.webSocketDebuggerUrl) {
    throw new Error("Chrome did not expose a page target");
  }

  session = new CdpSession(page.webSocketDebuggerUrl);
  await session.open();
  const consoleMessages = [];
  const exceptions = [];
  session.on("Runtime.consoleAPICalled", (event) => {
    consoleMessages.push({
      type: event.type,
      timestamp: event.timestamp,
      text: (event.args || []).map((value) => value.value ?? value.description ?? "").join(" "),
    });
  });
  session.on("Runtime.exceptionThrown", (event) => {
    exceptions.push({
      timestamp: event.timestamp,
      text: event.exceptionDetails?.exception?.description
        || event.exceptionDetails?.text
        || "Unknown exception",
    });
  });

  const enabledDomains = [
    session.send("Page.enable"),
    session.send("Runtime.enable"),
    session.send("Performance.enable"),
  ];
  if (cpuProfiling) {
    enabledDomains.push(session.send("Profiler.enable"));
  }
  await Promise.all(enabledDomains);
  if (cpuProfiling) {
    await session.send("Profiler.setSamplingInterval", {interval: 1000});
  }
  await session.send("Page.addScriptToEvaluateOnNewDocument", {source: `
    globalThis.__gaiusStartupProbe = {
      installedAt: performance.now(),
      longTasks: [],
      events: []
    };
    const recordEvent = (type) => globalThis.__gaiusStartupProbe.events.push({
      type,
      at: performance.now()
    });
    addEventListener('DOMContentLoaded', () => recordEvent('dom-content-loaded'), {once: true});
    addEventListener('load', () => recordEvent('load'), {once: true});
    try {
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          globalThis.__gaiusStartupProbe.longTasks.push({
            startTime: entry.startTime,
            duration: entry.duration,
            name: entry.name
          });
        }
      }).observe({type: 'longtask', buffered: true});
    } catch (ignored) {}
    // No HTML name gate any more: preset the remembered name and hold the boot
    // at the launcher's pre-main hook so timing still starts at "submit".
    try { localStorage.setItem('gaius.playerName', ${JSON.stringify(playerName)}); } catch (ignored) {}
    globalThis.__gaiusBeforeMain = () => new Promise((resolve) => {
      globalThis.__gaiusReleaseBoot = resolve;
    });
  `});
  if (cpuProfiling) {
    await session.send("Profiler.start");
  }
  const navigation = await session.send("Page.navigate", {url: targetUrl});
  if (navigation.errorText) {
    throw new Error(`Chrome navigation failed: ${navigation.errorText}`);
  }

  try {
    await waitFor(
      session,
      "typeof globalThis.__gaiusReleaseBoot === 'function'",
      30_000,
      "the launcher pre-main boot hold",
    );
  } catch (error) {
    const pageDiagnostic = await evaluate(session, `(() => ({
      href: location.href,
      readyState: document.readyState,
      title: document.title,
      bootHeld: typeof globalThis.__gaiusReleaseBoot === 'function',
      bodyText: (document.body?.innerText || '').slice(0, 2000),
      html: (document.documentElement?.outerHTML || '').slice(0, 2000)
    }))()`).catch((diagnosticError) => ({error: String(diagnosticError)}));
    throw new Error(`${error.message}; page=${JSON.stringify(pageDiagnostic)}; `
      + `chrome=${chromeOutput.join("").slice(-4000)}`);
  }
  await evaluate(session, `(() => {
    globalThis.__gaiusReleaseBoot();
    return true;
  })()`);

  const startedAt = Date.now();
  const responsiveness = [];
  let monitorDone = false;
  const responsivenessMonitor = (async () => {
    while (!monitorDone) {
      const pingStartedAt = Date.now();
      try {
        await evaluate(session, "performance.now()");
        responsiveness.push({
          startedAfterMs: pingStartedAt - startedAt,
          latencyMs: Date.now() - pingStartedAt,
        });
      } catch (error) {
        responsiveness.push({
          startedAfterMs: pingStartedAt - startedAt,
          latencyMs: Date.now() - pingStartedAt,
          error: String(error),
        });
      }
      await sleep(50);
    }
  })();

  let titleError = null;
  try {
    await waitFor(
      session,
      "String(window.__gaiusMinecraftState?.screen || '').endsWith('TitleScreen') || "
        + "(/Singleplayer/.test(document.body?.innerText || '') && /Multiplayer/.test(document.body?.innerText || ''))",
      timeoutMillis,
      "the Minecraft title screen",
    );
    await sleep(750);
  } catch (error) {
    titleError = error;
  }
  monitorDone = true;
  await responsivenessMonitor;
  const titleReadyAfterMs = titleError ? null : Date.now() - startedAt;

  const finalState = await evaluate(session, `(() => ({
    title: document.title,
    bodyText: (document.body?.innerText || '').slice(0, 4000),
    minecraftState: window.__gaiusMinecraftState || null,
    bootTimings: window.__gaiusBootTimings || null,
    clientStartupProgress: window.__gaiusClientStartupProgress || [],
    startupProbe: window.__gaiusStartupProbe || null,
    audioStats: window.__gaiusAudioStats || null,
    runtimeGuards: ${RUNTIME_GUARD_EXPRESSION},
    resourceEntries: performance.getEntriesByType('resource').map((entry) => ({
      name: entry.name,
      startTime: entry.startTime,
      duration: entry.duration,
      transferSize: entry.transferSize,
      encodedBodySize: entry.encodedBodySize,
      decodedBodySize: entry.decodedBodySize
    }))
  }))()`);
  const metrics = await session.send("Performance.getMetrics");
  let profile = null;
  if (cpuProfiling) {
    ({profile} = await session.send("Profiler.stop"));
    profileStopped = true;
  }

  const latencyValues = responsiveness
    .filter((sample) => !sample.error)
    .map((sample) => sample.latencyMs);
  const summary = {
    url: targetUrl,
    cpuProfiling,
    completed: titleError == null,
    failure: titleError ? String(titleError) : null,
    titleReadyAfterMs,
    responsiveness: {
      samples: responsiveness.length,
      p50Ms: percentile(latencyValues, 0.50),
      p95Ms: percentile(latencyValues, 0.95),
      p99Ms: percentile(latencyValues, 0.99),
      maxMs: Math.max(0, ...latencyValues),
      timeline: responsiveness,
    },
    state: finalState,
    performanceMetrics: metrics.metrics || [],
    consoleMessages,
    exceptions,
    runtimeGuardFailures: runtimeGuardFailures({
      snapshots: [finalState.runtimeGuards],
      entries: [...consoleMessages, ...exceptions],
    }),
    clinitSuspensions: clinitSuspensionCount([finalState.runtimeGuards]),
    chromeOutput: chromeOutput.join("").slice(-20_000),
  };

  await mkdir(dirname(outputPrefix), {recursive: true});
  const outputWrites = [
    writeFile(`${outputPrefix}.json`, `${JSON.stringify(summary, null, 2)}\n`),
  ];
  if (profile) {
    outputWrites.push(writeFile(`${outputPrefix}.cpuprofile`, `${JSON.stringify(profile)}\n`));
  }
  await Promise.all(outputWrites);
  console.log(JSON.stringify({
    profile: profile ? `${outputPrefix}.cpuprofile` : null,
    summary: `${outputPrefix}.json`,
    titleReadyAfterMs,
    responsiveness: {
      samples: summary.responsiveness.samples,
      p50Ms: summary.responsiveness.p50Ms,
      p95Ms: summary.responsiveness.p95Ms,
      p99Ms: summary.responsiveness.p99Ms,
      maxMs: summary.responsiveness.maxMs,
    },
    bootTimings: finalState.bootTimings,
    longTasks: finalState.startupProbe?.longTasks || [],
    exceptions,
    runtimeGuardFailures: summary.runtimeGuardFailures,
    clinitSuspensions: summary.clinitSuspensions,
  }, null, 2));
  if (titleError) {
    const partialOutput = profile
      ? `${outputPrefix}.cpuprofile and ${outputPrefix}.json`
      : `${outputPrefix}.json`;
    throw new Error(`${titleError.message}; partial results saved to ${partialOutput}`);
  }
  if (summary.runtimeGuardFailures.length > 0) {
    throw new Error("TeaVM runtime guard failures (see runtimeGuardFailures in "
      + `${outputPrefix}.json): ${summary.runtimeGuardFailures[0]}`);
  }
} finally {
  if (cpuProfiling && session && !profileStopped) {
    await session.send("Profiler.stop").catch(() => {});
  }
  session?.close();
  chrome.kill("SIGTERM");
  await Promise.race([
    new Promise((resolveExit) => chrome.once("exit", resolveExit)),
    sleep(2_000),
  ]);
  if (chrome.exitCode == null) {
    chrome.kill("SIGKILL");
  }
  await rm(profileDirectory, {recursive: true, force: true});
}
