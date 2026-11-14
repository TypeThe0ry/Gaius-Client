#!/usr/bin/env node
// Records the shaderc results of one cold start and writes them as a baked set
// (gaius-shader-prebake.bin, gzip) for the toolchain loader's primeCache.
//
//   node port/scripts/bake-shader-cache.mjs <page.html | http(s)://url/> <out.bin> [--query "?runtime=wasm"] [--timeout-ms 120000]
//
// The page is opened in a fresh headless Chrome profile and left running until the title screen
// is up and the deferred render pipelines have been compiled (window.__gaiusPipelineWarmup, or a
// quiet period for a build without the warm-up). Then window.__gaiusShaderToolchain.exportCache()
// is written. The entries are keyed by every shaderc job input plus the toolchain version of the
// page, so a set can only be used by the page form it was recorded on (primeCache ignores another
// version) and never changes a compilation result: a job whose inputs differ misses and compiles.
// Stage the file next to the other payloads as gaius-shader-prebake.bin and rebuild the page.

import {spawn} from "node:child_process";
import {gzipSync} from "node:zlib";
import {mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import {pathToFileURL} from "node:url";
import {chromeLaunchArgs, resolveChromeBinary} from "./chrome-binary.mjs";
import {linuxGpuEnvironment} from "./linux-gpu-env.mjs";

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const at = args.indexOf(name);
  if (at < 0) return fallback;
  const value = args[at + 1];
  args.splice(at, 2);
  return value;
};
const query = flag("--query", "?runtime=wasm");
const timeoutMs = Number(flag("--timeout-ms", "120000"));
const [source, output] = args;
if (!source || !output) {
  console.error("usage: bake-shader-cache.mjs <page.html|url> <out.bin> [--query ?q] [--timeout-ms n]");
  process.exit(2);
}
const url = /^https?:\/\//.test(source) ? source : pathToFileURL(resolve(source)).href + query;
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
const profile = await mkdtemp(join(tmpdir(), "gaius-bake-"));
const chrome = spawn(resolveChromeBinary(), [...chromeLaunchArgs(), "--headless=new", "--remote-debugging-port=0",
  "--remote-allow-origins=*", `--user-data-dir=${profile}`, "--ignore-gpu-blocklist", "--password-store=basic",
  "--no-first-run", "--no-default-browser-check", "--window-size=960,640", "about:blank"],
{stdio: ["ignore", "ignore", "pipe"], env: linuxGpuEnvironment(process.env)});
let socket;
try {
  let endpoint;
  for (let i = 0; i < 150 && !endpoint; i++) {
    try {
      const [port, path] = (await readFile(join(profile, "DevToolsActivePort"), "utf8")).trim().split(/\r?\n/);
      endpoint = {port, path};
    } catch { await sleep(100); }
  }
  if (!endpoint) throw new Error("Chrome debugger did not start");
  const targets = await (await fetch(`http://127.0.0.1:${endpoint.port}/json/list`)).json();
  socket = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
  await new Promise((done, fail) => { socket.addEventListener("open", done, {once: true}); socket.addEventListener("error", fail, {once: true}); });
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener("message", ({data}) => {
    const message = JSON.parse(String(data));
    const waiter = message.id && pending.get(message.id);
    if (waiter) { pending.delete(message.id); message.error ? waiter.fail(new Error(message.error.message)) : waiter.done(message.result || {}); }
  });
  const send = (method, params = {}) => new Promise((done, fail) => {
    const id = ++sequence;
    pending.set(id, {done, fail});
    socket.send(JSON.stringify({id, method, params}));
  });
  const evaluate = async (expression) => {
    const result = await send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
    return result.result?.value;
  };
  await send("Page.enable");
  await send("Runtime.enable");
  // A fresh profile shows the profile screen first: name the player like the startup harness does.
  await send("Page.addScriptToEvaluateOnNewDocument", {source: "try { localStorage.setItem('gaius.playerName', 'BakeProbe'); } catch (e) {}"});
  await send("Page.navigate", {url});
  const deadline = Date.now() + timeoutMs;
  let quietSince = 0;
  let lastEntries = -1;
  let state;
  while (Date.now() < deadline) {
    state = await evaluate(`(() => {
      const s = window.__gaiusMinecraftState || {};
      const w = window.__gaiusPipelineWarmup || null;
      const t = window.__gaiusShaderToolchain && window.__gaiusShaderToolchain.stats ? window.__gaiusShaderToolchain.stats() : null;
      return {screen: String(s.screen || ''), overlay: s.overlay || null, title: String(s.screen || '').endsWith('TitleScreen') && !s.overlay, warmup: w ? {done: !!w.doneAt, reason: w.reason, queued: w.queued} : null,
        entries: t && t.cache ? t.cache.entries : -1, error: String(window.__gaiusBootError || window.__gaiusThreadError || '')};
    })()`).catch(() => null);
    if (state?.error) throw new Error(state.error);
    if (state?.title) {
      const finished = state.warmup ? (state.warmup.done || state.warmup.queued === 0 || state.warmup.reason !== "deferred") : false;
      if (state.entries !== lastEntries) { lastEntries = state.entries; quietSince = Date.now(); }
      // Without the warm-up global the build compiles everything in the reload: a quiet period after the title is enough.
      if (finished && Date.now() - quietSince > 2000) break;
      if (!state.warmup && Date.now() - quietSince > 8000) break;
    }
    await sleep(250);
  }
  if (!state?.title) throw new Error("Title screen did not appear: " + JSON.stringify(state));
  const base64 = await evaluate(`(() => {
    const bytes = window.__gaiusShaderToolchain.exportCache();
    let text = '';
    for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(text);
  })()`);
  const stats = await evaluate("JSON.stringify(window.__gaiusShaderToolchain.stats().cache)");
  // Stored gzipped (a third of the size); the loader inflates it before priming.
  const bytes = gzipSync(Buffer.from(base64, "base64"), {level: 9});
  await writeFile(output, bytes);
  console.log(JSON.stringify({output, bytes: bytes.length, cache: JSON.parse(stats), warmup: state.warmup}));
} finally {
  try { socket?.close(); } catch { /* ignore */ }
  chrome.kill();
  await rm(profile, {recursive: true, force: true}).catch(() => {});
}
