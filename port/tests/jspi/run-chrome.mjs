#!/usr/bin/env node
// Runs the JSPI semantic suite (port/tests/jspi/build.sh output) in headless Chrome through the
// DevTools protocol and fails unless every check passed (none is skipped in a browser).
//
//   node port/tests/jspi/run-chrome.mjs <suite-dir> [--json out.json] [--timeout ms]
//
// GAIUS_CHROME_BIN names the browser (default: the Windows Chrome path, else google-chrome).
// The page is served from 127.0.0.1; every other host goes to a closed proxy port, so the run
// never reaches the network.
import {spawn} from "node:child_process";
import {createReadStream, existsSync, statSync, writeFileSync} from "node:fs";
import {mkdtemp, rm} from "node:fs/promises";
import {createServer as httpServer} from "node:http";
import {createServer as netServer} from "node:net";
import {tmpdir} from "node:os";
import {extname, isAbsolute, join, normalize, relative, resolve} from "node:path";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
};
const dir = args.find((arg, index) => !arg.startsWith("--") && !String(args[index - 1]).startsWith("--"));
if (!dir) {
  console.error("usage: run-chrome.mjs <suite-dir> [--json out.json] [--timeout ms]");
  process.exit(2);
}
const root = resolve(dir);
const jsonOut = option("--json", null);
const timeoutMs = Number(option("--timeout", "180000"));
const chromeBin = process.env.GAIUS_CHROME_BIN
  || (process.platform === "win32" ? "C:/Program Files/Google/Chrome/Application/chrome.exe" : "google-chrome");
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm"};

const server = httpServer((request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  const file = normalize(join(root, decodeURIComponent(url.pathname)));
  const inside = relative(root, file);
  if (inside.startsWith("..") || isAbsolute(inside) || !existsSync(file) || statSync(file).isDirectory()) {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, {"Content-Type": MIME[extname(file)] || "application/octet-stream",
    "Cache-Control": "no-store"});
  createReadStream(file).pipe(response);
});
await new Promise((done) => server.listen(0, "127.0.0.1", done));
const pageUrl = `http://127.0.0.1:${server.address().port}/index.html`;
const port = await new Promise((done) => {
  const probe = netServer();
  probe.listen(0, "127.0.0.1", () => {
    const free = probe.address().port;
    probe.close(() => done(free));
  });
});
const profile = await mkdtemp(join(tmpdir(), "gaius-jspi-suite-"));
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${port}`,
  "--remote-allow-origins=*", `--user-data-dir=${profile}`, "--no-first-run",
  "--no-default-browser-check", "--disable-background-networking", "--disable-component-update",
  "--proxy-server=http://127.0.0.1:9", "--proxy-bypass-list=127.0.0.1;localhost", "about:blank"],
{stdio: "ignore"});

let code = 1;
const report = {runner: "chrome", page: pageUrl};
try {
  let targets = [];
  for (let i = 0; i < 120 && !targets.some((target) => target.type === "page"); i++) {
    try {
      targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    } catch {
      // Not up yet.
    }
    await sleep(250);
  }
  const page = targets.find((target) => target.type === "page");
  if (!page) {
    throw new Error("no DevTools page target");
  }
  report.browser = (await (await fetch(`http://127.0.0.1:${port}/json/version`)).json()).Browser;
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((done, fail) => {
    ws.addEventListener("open", done, {once: true});
    ws.addEventListener("error", fail, {once: true});
  });
  let id = 0;
  const pending = new Map();
  const consoleLines = [];
  ws.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message);
      pending.delete(message.id);
    } else if (message.method === "Runtime.consoleAPICalled") {
      consoleLines.push(message.params.args.map((arg) => arg.value ?? arg.description).join(" "));
    } else if (message.method === "Runtime.exceptionThrown") {
      consoleLines.push("exception: " + JSON.stringify(message.params.exceptionDetails).slice(0, 1500));
    }
  });
  const send = (method, params = {}) => new Promise((done) => {
    const messageId = ++id;
    pending.set(messageId, done);
    ws.send(JSON.stringify({id: messageId, method, params}));
  });
  await send("Runtime.enable");
  await send("Page.navigate", {url: pageUrl});
  const started = Date.now();
  let state = null;
  while (Date.now() - started < timeoutMs) {
    await sleep(250);
    const result = await send("Runtime.evaluate", {returnByValue: true, expression:
      "JSON.stringify({suite: globalThis.__jspiSuite || null, errors: globalThis.__jspiSuiteErrors || [],"
      + " stats: globalThis.__gaiusJspiScheduler ? globalThis.__gaiusJspiScheduler.stats : null})"});
    const value = result.result && result.result.result && result.result.result.value;
    if (typeof value === "string") {
      state = JSON.parse(value);
      if ((state.suite && state.suite.done) || state.errors.length) {
        break;
      }
    }
  }
  ws.close();
  const suite = (state && state.suite) || {results: {}, log: []};
  const results = suite.results || {};
  const checks = Object.entries(results).filter(([key]) => /^(semantic|probes|wp2)\./.test(key));
  const failed = checks.filter(([, value]) => !String(value).startsWith("PASS"));
  const errors = state ? state.errors : ["no state"];
  Object.assign(report, {done: !!suite.done, summary: suite.summary || null, elapsedMs: Date.now() - started,
    failed: failed.map(([key, value]) => `${key} ${value}`), errors, results,
    stats: state ? state.stats : null, logTail: (suite.log || []).slice(-20),
    console: consoleLines.slice(-40)});
  const ok = suite.done && errors.length === 0 && failed.length === 0;
  console.log(`JSPI_SUITE ${ok ? "PASS" : "FAIL"} ${report.browser}: ${suite.summary || "not done"}`
    + (failed.length ? `; failed: ${report.failed.join("; ")}` : "")
    + (errors.length ? `; errors: ${errors.join("; ")}` : ""));
  code = ok ? 0 : 1;
} catch (error) {
  report.error = String(error && error.stack || error);
  console.log("JSPI_SUITE FAIL " + report.error);
} finally {
  if (jsonOut) {
    writeFileSync(jsonOut, JSON.stringify(report, null, 1) + "\n");
  }
  chrome.kill();
  server.close();
  await sleep(1000);
  await rm(profile, {recursive: true, force: true}).catch(() => {});
  process.exit(code);
}
