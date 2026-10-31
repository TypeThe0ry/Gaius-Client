#!/usr/bin/env node
// Browser side of the worldgen seed-parity harness (v0.4.0 M2 seed parity: JVM vs js vs wasm).
//
// Creates a fresh default world with a fixed seed in a Gaius dist the way a player does (title ->
// Singleplayer -> Create New World, Creative, seed typed on the World tab), lets the integrated
// server generate the area around the spawn, teleports the player far away so that area unloads
// (an unloading chunk is saved at whatever status it reached, also the TERRAIN ring around the
// generated area; "/save-all flush" alone writes FULL chunks only), runs "/save-all flush" and
// copies the saved region files and level.dat out of the server Worker's file store
// (__gaiusPersistentFiles of port/web/singleplayer/server-worker-bootstrap.js). The
// harness never calls into the game: every chunk it returns was generated and saved by the
// build under test (TeaVM JavaScript or WebAssembly GC server, its patches and its kernels).
// worldgen-seed-parity.mjs --saved then hashes these chunks on the JVM with the vanilla codecs
// and compares them with vanilla generation.
//
//   node port/scripts/worldgen-seed-parity/browser-world.mjs --dist <served dist> --seed <long>
//        --out <dir> [--runtime js|wasm] [--server-runtime js|wasm] [--settle-s 60]
//        [--unload-s 30] [--far 30000,30000 | --far none] [--timeout-s 900] [--worker-debug]
//        [--worker-pause uncaught|far|all] [--page-query <k=v&...>] [--trace-throwables]
//        [--require-worldgen-kernels]
//
// --runtime picks the client page (pageForRuntime), --server-runtime wasm adds ?serverRuntime=wasm
// (the WP10 singleplayer-server.wasm Worker), --query appends page switches (worldgenKernel=0
// keeps every chunk on the Java worldgen path, for example). --far none skips the teleport: the
// player stays and "/save-all flush" writes the FULL chunks only (the 1.21.11 JavaScript server
// stops on the far teleport, "Ticking player"). Writes <out>/browser-world.json
// (what ran, timings, the server Worker's runtime, the files it held), <out>/region/r.X.Z.mca (overworld),
// <out>/level.dat and screenshots. --worker-debug logs every console line of the server Worker
// and pauses it on uncaught exceptions only, to record the JavaScript stack (browser-world.json
// workerExceptions). --worker-pause far (implies --worker-debug) also pauses on caught exceptions
// once the player teleported, and all from the start, recording each distinct one (Java class, message
// and JavaScript stack) in caughtExceptions: the cause a ReportedException wraps was thrown and caught
// before. --trace-throwables (implies --worker-debug) serves the JavaScript server Worker
// (singleplayer-server.js and its .gz) with one added statement: TeaVM's Throwable stack capture
// keeps the last 256 Java throwables in globalThis.__gaiusThrowRing, and an uncaught exception
// records them (class, string fields, JavaScript stack) in throwRing, without pausing on every
// caught exception. --page-query appends parameters to the page URL (for example gaiusKernels=0). Chrome runs isolated (bench/harness-common.mjs): no request leaves
// 127.0.0.1. Exit status: 0 files written, 1 failure, 2 usage.
// --require-worldgen-kernels also requires completed server kernel jobs and zero refusal/error
// counters. Kernel telemetry is always copied from both the page and the server Worker.

import {spawn, spawnSync} from "node:child_process";
import {createReadStream, existsSync, readFileSync, statSync} from "node:fs";
import {appendFile, mkdir, mkdtemp, rm, writeFile} from "node:fs/promises";
import {createServer as httpServer} from "node:http";
import {createServer as netServer} from "node:net";
import {tmpdir} from "node:os";
import {gunzipSync, gzipSync, inflateSync} from "node:zlib";
import {extname, isAbsolute, join, normalize, relative, resolve} from "node:path";
import {RUNTIME_TELEMETRY_EXPRESSION, chromeIsolationArgs, pageForRuntime} from "../bench/harness-common.mjs";
import {KERNEL_TELEMETRY_EXPRESSION, kernelActivityFailures} from "./kernel-telemetry.mjs";

function usage(message) {
  console.error(`browser-world: ${message}`);
  process.exit(2);
}

function parseArgs(argv) {
  const options = {runtime: "js", serverRuntime: "js", settleS: 60, timeoutS: 900, saveTimeoutS: 2400, unloadS: 30,
    farX: 30000, farZ: 30000, workerDebug: false, workerPause: "uncaught", pageQuery: "",
    traceThrowables: false};
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    const value = () => argv[++index] ?? usage(`${argument} needs a value`);
    switch (argument) {
      case "--dist": options.dist = resolve(value()); break;
      case "--seed": options.seed = value(); break;
      case "--out": options.out = resolve(value()); break;
      case "--runtime": options.runtime = value(); break;
      case "--server-runtime": options.serverRuntime = value(); break;
      case "--settle-s": options.settleS = Number(value()); break;
      case "--timeout-s": options.timeoutS = Number(value()); break;
      case "--save-timeout-s": options.saveTimeoutS = Number(value()); break;
      case "--unload-s": options.unloadS = Number(value()); break;
      case "--far": {
        const far = value();
        if (far === "none") options.stay = true;
        else [options.farX, options.farZ] = far.split(",").map(Number);
        break;
      }
      case "--worker-debug": options.workerDebug = true; break;
      case "--worker-pause": options.workerPause = value(); options.workerDebug = true; break;
      case "--trace-throwables": options.traceThrowables = true; options.workerDebug = true; break;
      case "--require-worldgen-kernels": options.requireWorldgenKernels = true; break;
      case "--query":
      case "--page-query": options.pageQuery = value().replace(/^[?&]+/, ""); break;
      default: usage(`unknown argument ${argument}`);
    }
  }
  if (!options.dist || !existsSync(join(options.dist, "index.html"))) usage("--dist must name a dist with index.html");
  if (!/^-?\d+$/.test(options.seed || "")) usage("--seed must be a long");
  if (!options.out) usage("--out is required");
  if (!["js", "wasm"].includes(options.runtime)) usage("--runtime must be js or wasm");
  if (!["js", "wasm"].includes(options.serverRuntime)) usage("--server-runtime must be js or wasm");
  if (!(options.settleS > 0) || !(options.timeoutS > 0) || !(options.saveTimeoutS > 0) || !(options.unloadS > 0)) {
    usage("--settle-s, --timeout-s, --save-timeout-s and --unload-s must be positive");
  }
  if (!["uncaught", "far", "all"].includes(options.workerPause)) usage("--worker-pause must be uncaught, far or all");
  if (!Number.isInteger(options.farX) || !Number.isInteger(options.farZ)) usage("--far must be <x>,<z> blocks");
  return options;
}

const options = parseArgs(process.argv.slice(2));
const sleep = ms => new Promise(done => setTimeout(done, ms));
const t0 = Date.now();
const seconds = () => +((Date.now() - t0) / 1000).toFixed(1);
await mkdir(join(options.out, "region"), {recursive: true});
const logFile = join(options.out, "browser-world.log");
await writeFile(logFile, "");
async function log(kind, text) {
  const line = `${String(seconds()).padStart(7)} ${kind} ${text}`;
  console.log(line);
  await appendFile(logFile, line + "\n");
}

const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json",
  ".gz": "application/gzip", ".png": "image/png", ".br": "application/octet-stream", ".pack": "application/octet-stream"};
// --trace-throwables: the server Worker script with a throwable ring. TeaVM JavaScript fills the
// stack of every new Throwable through one function (`<name>=ex=>{let javaCause=`); the added
// statement keeps a reference to the Java object.
let tracedServer = null;
function tracedServerScript() {
  if (tracedServer) return tracedServer;
  const source = readFileSync(join(options.dist, "singleplayer-server.js"), "utf8");
  const pattern = /([\w$]+=ex=>\{)(let javaCause=)/g;
  const matches = source.match(pattern) || [];
  if (matches.length !== 1) throw new Error(`--trace-throwables: ${matches.length} TeaVM stack fill functions in singleplayer-server.js`);
  const traced = source.replace(pattern, "$1{const r=globalThis.__gaiusThrowRing||(globalThis.__gaiusThrowRing=[]);"
    + "r.push(ex);if(r.length>256)r.shift();}$2");
  const plain = Buffer.from(traced, "utf8");
  tracedServer = {plain, gzip: gzipSync(plain, {level: 1})};
  return tracedServer;
}
const server = httpServer((request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  if (options.traceThrowables && /^\/singleplayer-server\.js(\.gz)?$/.test(url.pathname)) {
    const body = url.pathname.endsWith(".gz") ? tracedServerScript().gzip : tracedServerScript().plain;
    response.writeHead(200, {"Content-Type": url.pathname.endsWith(".gz") ? "application/gzip" : "text/javascript",
      "Content-Length": body.length, "Cache-Control": "no-store"});
    response.end(body);
    return;
  }
  let path = normalize(join(options.dist, decodeURIComponent(url.pathname)));
  const inside = relative(options.dist, path);
  if (inside.startsWith("..") || isAbsolute(inside)) { response.writeHead(403); response.end(); return; }
  if (url.pathname === "/") path = join(options.dist, "index.html");
  if (!existsSync(path) || statSync(path).isDirectory()) { response.writeHead(404); response.end(); return; }
  response.writeHead(200, {"Content-Type": MIME[extname(path)] || "application/octet-stream",
    "Content-Length": statSync(path).size, "Cache-Control": "no-store",
    "Cross-Origin-Resource-Policy": "same-origin", "Cross-Origin-Embedder-Policy": "require-corp",
    "Cross-Origin-Opener-Policy": "same-origin"});
  createReadStream(path).pipe(response);
});
await new Promise(done => server.listen(0, "127.0.0.1", done));
let page = pageForRuntime(options.dist, options.runtime);
if (options.serverRuntime === "wasm") page += `${page.includes("?") ? "&" : "?"}serverRuntime=wasm`;
if (options.pageQuery) page += `${page.includes("?") ? "&" : "?"}${options.pageQuery}`;
const pageUrl = `http://127.0.0.1:${server.address().port}/${page}`;

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url); this.id = 1; this.pending = new Map(); this.listeners = new Map();
    this.ws.addEventListener("message", event => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const {ok, bad} = this.pending.get(message.id); this.pending.delete(message.id);
        message.error ? bad(new Error(message.error.message)) : ok(message.result);
      } else if (message.method) {
        for (const listener of this.listeners.get(message.method) || []) listener(message.params || {}, message.sessionId);
      }
    });
  }
  on(method, listener) { if (!this.listeners.has(method)) this.listeners.set(method, []); this.listeners.get(method).push(listener); }
  open() { return new Promise((ok, bad) => { this.ws.addEventListener("open", ok); this.ws.addEventListener("error", bad); }); }
  send(method, params = {}, sessionId) {
    const id = this.id++;
    return new Promise((ok, bad) => {
      this.pending.set(id, {ok, bad});
      this.ws.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}));
    });
  }
}
async function evaluate(cdp, expression, sessionId) {
  const result = await cdp.send("Runtime.evaluate", {expression, awaitPromise: true, returnByValue: true}, sessionId);
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text || "evaluate failed");
  }
  return result.result?.value;
}
async function waitFor(cdp, expression, ms, label) {
  const end = Date.now() + ms;
  while (Date.now() < end) { try { if (await evaluate(cdp, expression)) return; } catch {} await sleep(250); }
  throw new Error(`timeout: ${label}`);
}
async function freePort() {
  const probe = netServer(); await new Promise(done => probe.listen(0, "127.0.0.1", done));
  const port = probe.address().port; await new Promise(done => probe.close(done)); return port;
}
async function clickAt(cdp, x, y) {
  for (const [type, buttons] of [["mouseMoved", 0], ["mousePressed", 1], ["mouseReleased", 0]]) {
    await cdp.send("Input.dispatchMouseEvent", {type, x, y, button: type === "mouseMoved" ? "none" : "left",
      buttons, clickCount: type === "mouseMoved" ? 0 : 1});
    await sleep(type === "mouseMoved" ? 50 : 80);
  }
}
// A widget of the current Minecraft screen (__gaiusMinecraftState.screenWidgets) in page pixels.
async function findWidget(cdp, text, ms = 30000) {
  const wanted = JSON.stringify(String(text).toLowerCase());
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const widget = await evaluate(cdp, `(()=>{const s=window.__gaiusMinecraftState||{};const ws=Array.isArray(s.screenWidgets)?s.screenWidgets:[];
      const ok=x=>x&&x.visible!==false&&x.active!==false;const n=${wanted};
      const w=ws.find(x=>ok(x)&&String(x.text||'').trim().toLowerCase()===n)||ws.find(x=>ok(x)&&String(x.text||'').toLowerCase().includes(n));
      const c=document.querySelector('canvas');const r=c&&c.getBoundingClientRect();const z=s.screenSize;if(!w||!r||!z||!z.width)return null;
      return {text:String(w.text||''),x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height)};})()`)
      .catch(() => null);
    if (widget) return widget;
    await sleep(250);
  }
  return null;
}
async function clickWidget(cdp, text, ms) {
  const widget = await findWidget(cdp, text, ms);
  if (!widget) throw new Error(`widget not found: ${text}`);
  await clickAt(cdp, widget.x, widget.y);
  await log("click", widget.text);
  return widget;
}
const KEYS = {" ": ["Space", 32], "/": ["Slash", 191], "-": ["Minus", 189], ".": ["Period", 190], "@": ["Digit2", 50, 8],
  "~": ["Backquote", 192, 8]};
function keyDefinition(character) {
  if (/^[a-z]$/i.test(character)) {
    return {code: "Key" + character.toUpperCase(), vk: character.toUpperCase().charCodeAt(0),
      modifiers: character !== character.toLowerCase() ? 8 : 0};
  }
  if (/^[0-9]$/.test(character)) return {code: "Digit" + character, vk: character.charCodeAt(0), modifiers: 0};
  const known = KEYS[character];
  if (!known) throw new Error(`no key for ${JSON.stringify(character)}`);
  return {code: known[0], vk: known[1], modifiers: known[2] || 0};
}
async function key(cdp, name, code, vk, text = "", modifiers = 0) {
  await cdp.send("Input.dispatchKeyEvent", {type: "keyDown", key: name, code, windowsVirtualKeyCode: vk,
    nativeVirtualKeyCode: vk, modifiers, ...(text ? {text, unmodifiedText: text} : {})});
  await sleep(30);
  await cdp.send("Input.dispatchKeyEvent", {type: "keyUp", key: name, code, windowsVirtualKeyCode: vk,
    nativeVirtualKeyCode: vk, modifiers});
}
async function typeText(cdp, text) {
  for (const character of text) {
    const definition = keyDefinition(character);
    await key(cdp, character, definition.code, definition.vk, character, definition.modifiers);
    await sleep(35);
  }
}
async function screenshot(cdp, name) {
  try {
    const {data} = await cdp.send("Page.captureScreenshot", {format: "png"});
    await writeFile(join(options.out, name), Buffer.from(data, "base64"));
  } catch (error) {
    await log("screenshot-failed", `${name} ${error}`);
  }
}
const screenExpression = "String(window.__gaiusMinecraftState?.screen||'')";
const stateExpression = `(()=>{const s=window.__gaiusMinecraftState||null;return s?{screen:String(s.screen||''),overlay:s.overlay||null,
  level:s.level||null,loaded:Number(s.loadedChunkCount)||0,player:s.player?{x:s.player.x,y:s.player.y,z:s.player.z}:null,
  distance:s.clientDistance??null,widgets:Array.isArray(s.screenWidgets)?s.screenWidgets.map(w=>[String(w.type||'').split('.').pop(),w.text,w.active,w.focused]):null}:null;})()`;

// The server Worker's view of the world files: one entry per stored path of the active world.
const LIST_FILES = `(()=>{const f=globalThis.__gaiusPersistentFiles;if(!f)return null;const out=[];
  for(const path of Object.keys(f)){if(!/^\\/gaius\\/saves\\//.test(path))continue;let size=null;
    try{const v=f[path];size=v==null?null:typeof v==='string'?-v.length:v.byteLength;}catch(e){size='error:'+e;}out.push({path,size});}
  return {files:out,serverRuntime:typeof serverRuntime==='string'?serverRuntime:null,
    backend:globalThis.__gaiusFsBackend||null,stats:globalThis.__gaiusStorageStats?Object.fromEntries(Object.entries(globalThis.__gaiusStorageStats).filter(([k,v])=>typeof v!=='object')):null};})()`;
// The server Worker's own state: its runtime, a failed Java thread (wasm), the JSPI scheduler
// counters (wasm: class-initializer waits and suspensions) and the storage counters.
const SERVER_TELEMETRY = `(()=>{const g=globalThis;const pick=o=>{if(!o||typeof o!=='object')return null;const r={};
  for(const k of Object.keys(o)){const v=o[k];if(typeof v==='number'||typeof v==='string'||typeof v==='boolean')r[k]=v;}return r;};
  const s=g.__gaiusJspiScheduler;return {serverRuntime:typeof serverRuntime==='string'?serverRuntime:null,
    threadError:g.__gaiusThreadError?String(g.__gaiusThreadError.stack||g.__gaiusThreadError).slice(0,4000):null,
    pumpError:g.__gaiusIntegratedServerPumpError?String(g.__gaiusIntegratedServerPumpError).slice(0,2000):null,
    scheduler:s?{...pick(s),stats:pick(s.stats)}:null,storage:pick(g.__gaiusStorageStats)};})()`;
// The object graph of a thrown Java exception, breadth first from the thrown value (called on it
// with Runtime.callFunctionOn). TeaVM JavaScript throws an Error that holds the Java exception
// under Symbol(javaException) and the Error of its getCause() as `cause` (a ReportedException's
// cause is the exception of its CrashReport); the walk records TeaVM class names from the class
// metadata, string and number fields, and the message and JavaScript stack of every Error, without
// knowing the minified field names.
const JAVA_GRAPH = `function(){
  const metaOf=o=>{if(o===null||typeof o!=='object')return null;const c=o.constructor;if(!c)return null;
    const k=Object.getOwnPropertySymbols(c).find(s=>s.description==='teavm_meta');return k?c[k]:null;};
  const classOf=o=>{const m=metaOf(o);return m?(m.name||('?'+(o.constructor&&o.constructor.name))):(o&&o.constructor&&o.constructor.name)||typeof o;};
  const limit=3000;const ids=new Map([[this,0]]);const queue=[this];const nodes=[];
  const val=v=>{if(v===null)return null;if(v===undefined)return {undef:true};
    if(typeof v==='object'){if(!ids.has(v)){if(ids.size>=limit)return {trunc:true};ids.set(v,ids.size);queue.push(v);}return {ref:ids.get(v)};}
    if(typeof v==='function')return {fn:v.name};if(typeof v==='bigint')return {bigint:String(v)};
    if(typeof v==='string')return v.length>2000?v.slice(0,2000)+'...':v;return v;};
  while(queue.length){const o=queue.shift();const node={id:ids.get(o),cls:classOf(o)};
    if(o instanceof Error){try{node.stack=String(o.stack).slice(0,8000);node.message=String(o.message);}catch(e){}}
    if(ArrayBuffer.isView(o)){node.length=o.length;if(o instanceof Uint16Array&&o.length<=4096)node.text=String.fromCharCode.apply(null,o);}
    else if(Array.isArray(o)){node.length=o.length;if(o.length<=256)node.items=o.map(val);}
    else{node.fields={};for(const key of Object.getOwnPropertyNames(o)){if(o instanceof Error&&(key==='stack'||key==='message'))continue;
        try{node.fields[key]=val(o[key]);}catch(e){}}
      for(const key of Object.getOwnPropertySymbols(o)){try{node.fields['@'+key.description]=val(o[key]);}catch(e){}}}
    nodes.push(node);}
  return {nodes};}`;
// A short description of a thrown value (called on it with Runtime.callFunctionOn): for every Error
// of its cause chain the JavaScript stack, the Java exception it carries (Symbol(javaException)) with
// its TeaVM class name and its string fields (java.lang.String objects read through their native
// string), and the own property keys (to see what a runtime other than TeaVM JavaScript threw).
const EXCEPTION_BRIEF = `function(){
  const sym=(o,d)=>{if(o===null||(typeof o!=='object'&&typeof o!=='function'))return undefined;
    const k=Object.getOwnPropertySymbols(o).find(s=>s.description===d);return k?o[k]:undefined;};
  const cls=o=>{const m=o&&o.constructor?sym(o.constructor,'teavm_meta'):undefined;return m&&m.name?m.name:null;};
  const jstr=v=>{if(typeof v==='string')return v;if(v&&typeof v==='object'&&/(^|[.])String$/.test(cls(v)||'')){
      for(const k of Object.getOwnPropertyNames(v)){if(typeof v[k]==='string')return v[k];}}return undefined;};
  const fields=o=>{const out={};if(!o||typeof o!=='object')return out;for(const k of Object.getOwnPropertyNames(o)){
      try{const v=o[k];const t=jstr(v);if(t!==undefined)out[k]=t.slice(0,600);else if(typeof v==='number'||typeof v==='boolean')out[k]=v;
        else if(v&&typeof v==='object'&&cls(v))out[k]='<'+cls(v)+'>';}catch(e){}}return out;};
  const chain=[];let e=this;const seen=new Set();
  while(e&&typeof e==='object'&&!seen.has(e)&&chain.length<6){seen.add(e);const java=sym(e,'javaException');
    chain.push({js:e.constructor&&e.constructor.name,stack:e instanceof Error?String(e.stack).split('\\n').slice(0,40).join('\\n'):null,
      keys:Object.getOwnPropertyNames(e).concat(Object.getOwnPropertySymbols(e).map(String)),
      java:java?{cls:cls(java),fields:fields(java)}:null,
      report:java?Object.getOwnPropertyNames(java).map(k=>java[k]).filter(v=>v&&typeof v==='object'&&/(^|[.])CrashReport$/.test(cls(v)||'')).map(r=>({cls:'CrashReport',fields:fields(r)}))[0]||null:null});
    e=e.cause;}
  return chain;}`;
// The throwable ring of --trace-throwables, newest last: class, string and number fields (a
// java.lang.String read through its native string, other objects by class) and the JavaScript
// stack, followed one level into object fields that are throwables or crash reports.
const THROW_RING = `(()=>{const ring=globalThis.__gaiusThrowRing||[];
  const sym=(o,d)=>{if(o===null||(typeof o!=='object'&&typeof o!=='function'))return undefined;
    const k=Object.getOwnPropertySymbols(o).find(s=>s.description===d);return k?o[k]:undefined;};
  const cls=o=>{const m=o&&o.constructor?sym(o.constructor,'teavm_meta'):undefined;return m&&m.name?m.name:null;};
  const jstr=v=>{if(typeof v==='string')return v;if(v&&typeof v==='object'&&/(^|[.])String$/.test(cls(v)||'')){
      for(const k of Object.getOwnPropertyNames(v)){if(typeof v[k]==='string')return v[k];}}return undefined;};
  const describe=(o,depth)=>{const out={cls:cls(o),fields:{}};
    for(const k of Object.getOwnPropertyNames(o)){if(k==='$jsException')continue;try{const v=o[k];const t=jstr(v);
      if(t!==undefined)out.fields[k]=t.slice(0,600);else if(typeof v==='number'||typeof v==='boolean')out.fields[k]=v;
      else if(v&&typeof v==='object'&&cls(v)){const c=cls(v);
        out.fields[k]=depth>0&&/(Exception|Error|Throwable|CrashReport|CrashReportCategory)$/.test(c)?describe(v,depth-1):'<'+c+'>';}}catch(e){}}
    const js=o.$jsException;if(js&&js.stack)out.stack=String(js.stack).split('\\n').slice(1,120).map(l=>l.trim()).join(' | ');
    return out;};
  return ring.slice(-60).map(o=>describe(o,2));})()`;
// Readable lines of a JAVA_GRAPH result: every Throwable with its string fields and the head of its
// stack, then every other object that holds strings (crash report categories and entries).
function javaSummary(java) {
  if (!java?.nodes) return java?.error ? [`graph failed: ${java.error}`] : [];
  const lines = [];
  const strings = node => Object.entries(node.fields || {}).filter(([, value]) => typeof value === "string")
    .map(([key, value]) => `${key}=${JSON.stringify(value.slice(0, 300))}`).join(" ");
  for (const node of java.nodes) {
    if (/(Exception|Error|Throwable)$/.test(node.cls) || node.stack) {
      const links = Object.entries(node.fields || {}).filter(([key, value]) => /^(@|cause$)/.test(key) && value?.ref !== undefined)
        .map(([key, value]) => `${key}->#${value.ref}`).join(" ");
      lines.push(`#${node.id} ${node.cls}${node.message !== undefined ? ` message=${JSON.stringify(node.message.slice(0, 500))}` : ""} ${links} ${strings(node)}`);
      if (node.stack) lines.push(...node.stack.split("\n").slice(0, 30).map(line => `    ${line.trim()}`));
    }
  }
  for (const node of java.nodes) {
    if (!(/(Exception|Error|Throwable)$/.test(node.cls) || node.stack) && strings(node)) lines.push(`#${node.id} ${node.cls} ${strings(node)}`);
  }
  return lines.slice(0, 400);
}
// Java stores region files as bytes and every other file as a base64 string (BrowserFilePersistence).
const READ_FILE = path => `(()=>{const v=globalThis.__gaiusPersistentFiles[${JSON.stringify(path)}];
  if(v==null)return null;if(typeof v==='string')return {base64:v};
  const b=v instanceof Uint8Array?v:new Uint8Array(v.buffer||v,v.byteOffset||0,v.byteLength);
  if(typeof b.toBase64==='function')return {base64:b.toBase64()};
  let s='';for(let i=0;i<b.length;i+=32768)s+=String.fromCharCode.apply(null,b.subarray(i,i+32768));return {base64:btoa(s)};})()`;

// Read only the persisted status from an MCA chunk. This deliberately parses the NBT
// structure instead of treating a region file's presence as proof that its chunks are FULL.
function nbtStatus(data) {
  // Status is a string tag named exactly "Status" in every supported Anvil chunk.
  // The bounded signature avoids walking large block-state lists during each poll.
  const marker = Buffer.from([8, 0, 6, 83, 116, 97, 116, 117, 115]);
  for (let at = 0; at + marker.length + 2 <= data.length; at++) {
    if (!data.subarray(at, at + marker.length).equals(marker)) continue;
    const length = data.readUInt16BE(at + marker.length);
    if (length > 256 || at + marker.length + 2 + length > data.length) continue;
    return data.toString("utf8", at + marker.length + 2, at + marker.length + 2 + length);
  }
  return null;
}

function lz4Block(data) {
  const out = [];
  let at = 0;
  const copy = (start, length) => {
    for (let i = 0; i < length; i++) out.push(out[start + i]);
  };
  while (at < data.length) {
    const token = data[at++];
    let literalLength = token >>> 4;
    if (literalLength === 15) {
      let extra;
      do { if (at >= data.length) throw new Error("truncated LZ4 literal length"); extra = data[at++]; literalLength += extra; } while (extra === 255);
    }
    if (at + literalLength > data.length) throw new Error("truncated LZ4 literals");
    for (let i = 0; i < literalLength; i++) out.push(data[at++]);
    if (at === data.length) break;
    if (at + 2 > data.length) throw new Error("truncated LZ4 match offset");
    const offset = data[at] | (data[at + 1] << 8); at += 2;
    if (!offset || offset > out.length) throw new Error("invalid LZ4 match offset");
    let matchLength = (token & 15) + 4;
    if ((token & 15) === 15) {
      let extra;
      do { if (at >= data.length) throw new Error("truncated LZ4 match length"); extra = data[at++]; matchLength += extra; } while (extra === 255);
    }
    const start = out.length - offset;
    for (let i = 0; i < matchLength; i++) out.push(out[start + i]);
  }
  return Buffer.from(out);
}

function decodeLz4(data) {
  // LZ4 frame format (magic 04 22 4D 18), with independent or linked blocks.
  if (data.length >= 4 && data.readUInt32LE(0) === 0x184d2204) {
    let at = 4;
    if (at + 2 > data.length) throw new Error("truncated LZ4 frame header");
    const flags = data[at++];
    if (at >= data.length) throw new Error("truncated LZ4 frame BD");
    const blockDescriptor = data[at++];
    const blockMaximum = [0, 64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024][(blockDescriptor >>> 4) & 7];
    if (!blockMaximum) throw new Error("invalid LZ4 block size");
    if (flags & 8) { if (at + 8 > data.length) throw new Error("truncated LZ4 content size"); at += 8; }
    if (flags & 1) { if (at + 4 > data.length) throw new Error("truncated LZ4 dictionary id"); at += 4; }
    if (at >= data.length) throw new Error("truncated LZ4 descriptor checksum");
    at += 1; // descriptor checksum (content checksum is checked by the JVM parity path)
    const output = [];
    while (at + 4 <= data.length) {
      const size = data.readUInt32LE(at); at += 4;
      if (size === 0) return Buffer.from(output);
      const uncompressed = (size & 0x80000000) !== 0;
      const length = size & 0x7fffffff;
      if (length > blockMaximum || at + length > data.length) throw new Error("invalid LZ4 frame block");
      const block = data.subarray(at, at + length); at += length;
      const decoded = uncompressed ? block : lz4Block(block);
      for (const byte of decoded) output.push(byte);
    }
    throw new Error("truncated LZ4 frame");
  }
  return lz4Block(data);
}

function persistedChunkStatuses(regionBytes, targets) {
  const result = {};
  for (const [chunkX, chunkZ] of targets) {
    const localX = ((chunkX % 32) + 32) % 32, localZ = ((chunkZ % 32) + 32) % 32;
    const header = (localX + localZ * 32) * 4;
    if (header + 4 > regionBytes.length) continue;
    const location = regionBytes.readUInt32BE(header);
    const sector = location >>> 8, sectors = location & 0xff;
    if (!sector || !sectors || sector * 4096 + 5 > regionBytes.length) continue;
    const offset = sector * 4096, length = regionBytes.readUInt32BE(offset);
    if (length < 2 || offset + 4 + length > regionBytes.length) continue;
    const compression = regionBytes[offset + 4];
    const payload = regionBytes.subarray(offset + 5, offset + 4 + length);
    let nbt;
    try {
      // RegionFileVersion.VERSION_NONE is used by the browser storage patch.  Vanilla's
      // Anvil ids are 1=gzip, 2=zlib, 3=none and 4=LZ4.  Keep this parser in lockstep with
      // the chunk header instead of treating non-deflate chunks as an absent status.
      nbt = compression === 2 ? inflateSync(payload)
        : compression === 1 ? gunzipSync(payload)
          : compression === 3 ? payload
            : compression === 4 ? decodeLz4(payload)
              : null;
    } catch { nbt = null; }
    const status = nbt ? nbtStatus(nbt) : null;
    result[`${chunkX},${chunkZ}`] = status;
  }
  return result;
}

const report = {tool: "worldgen-seed-parity/browser-world", seed: options.seed, dist: options.dist, page,
  requestedRuntime: options.runtime, requestedServerRuntime: options.serverRuntime, far: options.stay ? null : [options.farX, options.farZ],
  startedAt: new Date().toISOString(),
  phases: {}, workers: [], files: [], regions: [], error: null};
const profileDir = await mkdtemp(join(tmpdir(), "gaius-seed-parity-"));
const port = await freePort();
const chromeBin = process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${port}`, "--remote-allow-origins=*",
  `--user-data-dir=${profileDir}`, "--window-size=1280,720", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader",
  ...chromeIsolationArgs(), "about:blank"], {stdio: "ignore"});
const workers = new Map();
let exitCode = 0;
let cdp;
try {
  let targets = [];
  for (let attempt = 0; attempt < 80 && !targets.length; attempt++) {
    try { targets = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).filter(t => t.type === "page"); } catch {}
    if (!targets.length) await sleep(250);
  }
  if (!targets.length) throw new Error("Chrome did not open a page target");
  cdp = new Cdp(targets[0].webSocketDebuggerUrl);
  await cdp.open();
  cdp.on("Target.attachedToTarget", async (params) => {
    const info = params.targetInfo;
    workers.set(params.sessionId, {type: info.type, url: info.url});
    report.workers.push({type: info.type, url: info.url.replace(/^.*\//, ""), at: seconds()});
    try {
      await cdp.send("Runtime.enable", {}, params.sessionId);
      if (options.workerDebug && /singleplayer-server-worker/.test(info.url)) {
        await cdp.send("Debugger.enable", {}, params.sessionId);
        await cdp.send("Debugger.setPauseOnExceptions", {state: options.workerPause === "all" ? "all" : "uncaught"},
          params.sessionId);
      }
      await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true}, params.sessionId);
      await cdp.send("Runtime.runIfWaitingForDebugger", {}, params.sessionId);
    } catch {}
  });
  report.workerExceptions = [];
  report.caughtExceptions = [];
  const caught = new Map();
  cdp.on("Debugger.paused", async (params, sessionId) => {
    try {
      const data = params.data || {};
      if ((params.reason === "exception" || params.reason === "promiseRejection") && data.uncaught === false) {
        // A caught exception (--worker-pause far/all): one record per Java class and throw site.
        const brief = data.objectId ? await cdp.send("Runtime.callFunctionOn", {objectId: data.objectId,
          functionDeclaration: EXCEPTION_BRIEF, returnByValue: true}, sessionId)
          .then(result => result.result?.value ?? null, error => [{error: String(error)}]) : null;
        const top = (params.callFrames || []).slice(0, 6).map(frame => `${frame.functionName}:${frame.location.lineNumber + 1}`).join("<");
        const signature = `${brief?.[0]?.java?.cls || data.className}|${top}`;
        const known = caught.get(signature);
        if (known) {
          known.count++;
        } else if (caught.size < 400) {
          const entry = {at: seconds(), count: 1, className: data.className || null, top, brief};
          caught.set(signature, entry);
          report.caughtExceptions.push(entry);
          const java = brief?.[0]?.java;
          await log("worker-caught", `${java?.cls || data.className} ${JSON.stringify(java?.fields || {}).slice(0, 600)} @ ${top}`);
        }
      } else if (params.reason === "exception" || params.reason === "promiseRejection") {
        const frames = (params.callFrames || []).slice(0, 40).map(frame => ({fn: frame.functionName,
          url: frame.url.replace(/^.*\//, ""), line: frame.location.lineNumber + 1, column: frame.location.columnNumber}));
        let java = null;
        if (data.objectId) {
          java = await cdp.send("Runtime.callFunctionOn", {objectId: data.objectId, functionDeclaration: JAVA_GRAPH,
            returnByValue: true}, sessionId).then(result => result.result?.value ?? null, error => ({error: String(error)}));
        }
        const brief = data.objectId ? await cdp.send("Runtime.callFunctionOn", {objectId: data.objectId,
          functionDeclaration: EXCEPTION_BRIEF, returnByValue: true}, sessionId)
          .then(result => result.result?.value ?? null, error => [{error: String(error)}]) : null;
        report.workerExceptions.push({at: seconds(), reason: params.reason, className: data.className || null,
          description: String(data.description || data.value || "").slice(0, 4000), frames, java, brief});
        for (const line of javaSummary(java)) await log("worker-exception-java", line);
        if (options.traceThrowables) {
          // The Worker is paused: Runtime.evaluate would wait for it to resume, so the ring is
          // read on the paused top frame.
          const ring = params.callFrames?.[0] ? await cdp.send("Debugger.evaluateOnCallFrame",
            {callFrameId: params.callFrames[0].callFrameId, expression: THROW_RING, returnByValue: true}, sessionId)
            .then(result => result.result?.value ?? [{error: JSON.stringify(result.exceptionDetails || result).slice(0, 400)}],
              error => [{error: String(error)}]) : [{error: "no call frame"}];
          report.throwRing = ring;
          for (const entry of (ring || []).slice(-12)) {
            await log("throw-ring", JSON.stringify(entry).slice(0, 4000));
          }
        }
        for (const [index, link] of (brief || []).entries()) {
          await log("worker-exception-brief", `[${index}] ${link.js} keys=${JSON.stringify(link.keys)} java=${JSON.stringify(link.java)} report=${JSON.stringify(link.report)}`.slice(0, 3000));
        }
        await log("worker-exception", `${String(data.description || data.value || "").split("\n")[0].slice(0, 400)} @ `
          + frames.slice(0, 12).map(frame => `${frame.fn || "?"}:${frame.line}:${frame.column}`).join(" < "));
      }
    } catch (error) {
      await log("worker-exception-failed", String(error));
    }
    try { await cdp.send("Debugger.resume", {}, sessionId); } catch {}
  });
  cdp.on("Runtime.exceptionThrown", (params, sessionId) => {
    const detail = params.exceptionDetails;
    log(`exception${sessionId ? "[worker]" : ""}`, String(detail.exception?.description || detail.text || "").slice(0, 2000));
  });
  // Console errors and warnings of the page and the Workers (a failing server Worker reports there).
  cdp.on("Runtime.consoleAPICalled", (params, sessionId) => {
    const server = sessionId && /singleplayer-server-worker/.test(workers.get(sessionId)?.url || "");
    if (!(options.workerDebug && server) && params.type !== "error" && params.type !== "warning" && params.type !== "assert") {
      return;
    }
    const text = (params.args || []).map(arg => arg.value !== undefined ? String(arg.value) : (arg.description || arg.type)).join(" ");
    log(`console.${params.type}${sessionId ? `[${workers.get(sessionId)?.url.replace(/^.*\//, "").replace(/\?.*$/, "") || "worker"}]` : ""}`,
      text.slice(0, 4000));
  });
  cdp.on("Target.detachedFromTarget", (params) => {
    const worker = workers.get(params.sessionId);
    if (worker) log("detached", worker.url.replace(/^.*\//, ""));
  });
  await cdp.send("Runtime.enable");
  await cdp.send("Page.enable");
  await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true});
  await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: "try{localStorage.setItem('gaius.playerName','SeedParity')}catch(e){}"});
  await log("navigate", pageUrl);
  await cdp.send("Page.navigate", {url: pageUrl});

  const timeoutAt = t0 + options.timeoutS * 1000;
  await waitFor(cdp, `/TitleScreen|BrowserProfileScreen/.test(${screenExpression})`, timeoutAt - Date.now(), "title screen");
  report.phases.firstMenuSeconds = seconds();
  if ((await evaluate(cdp, screenExpression)).endsWith("BrowserProfileScreen")) {
    await clickWidget(cdp, "Done", 15000);
    await waitFor(cdp, `${screenExpression}.endsWith('TitleScreen')`, 30000, "title after profile");
  }
  await sleep(1500);
  await clickWidget(cdp, "Singleplayer", 15000);
  await waitFor(cdp, `/SelectWorldScreen|CreateWorldScreen/.test(${screenExpression})`, 120000, "world screen");
  if ((await evaluate(cdp, screenExpression)).includes("SelectWorldScreen")) await clickWidget(cdp, "Create New World", 15000);
  await waitFor(cdp, `${screenExpression}.includes('CreateWorldScreen')`, 120000, "create world screen");
  await sleep(800);
  // Creative: the save keeps the same chunks, and the player can run /save-all.
  for (let attempt = 0; attempt < 4; attempt++) {
    const mode = await findWidget(cdp, "Game Mode", 5000);
    if (!mode || /creative/i.test(mode.text)) break;
    await clickAt(cdp, mode.x, mode.y);
    await sleep(400);
  }
  report.gameMode = (await findWidget(cdp, "Game Mode", 3000))?.text || null;
  // The World tab; its button text is not always reported, so Ctrl+2 (TabNavigationBar) otherwise.
  const worldTab = await findWidget(cdp, "World", 5000);
  if (worldTab && worldTab.text.trim().toLowerCase() === "world") {
    await clickAt(cdp, worldTab.x, worldTab.y);
  } else {
    await cdp.send("Input.dispatchKeyEvent", {type: "keyDown", key: "Control", code: "ControlLeft",
      windowsVirtualKeyCode: 17, nativeVirtualKeyCode: 17, modifiers: 2});
    await key(cdp, "2", "Digit2", 50, "", 2);
    await cdp.send("Input.dispatchKeyEvent", {type: "keyUp", key: "Control", code: "ControlLeft",
      windowsVirtualKeyCode: 17, nativeVirtualKeyCode: 17, modifiers: 0});
  }
  await sleep(600);
  const seedPredicate = `w=>w&&w.visible!==false&&w.active!==false&&(String(w.type||'').endsWith('CreateWorldScreen$WorldTab$1')||(/EditBox$/.test(String(w.type||''))&&/seed/i.test(String(w.text||''))))`;
  await waitFor(cdp, `(window.__gaiusMinecraftState?.screenWidgets||[]).filter(${seedPredicate}).length===1`, 10000, "seed edit box");
  const seedBox = await evaluate(cdp, `(()=>{const s=window.__gaiusMinecraftState||{};const w=(s.screenWidgets||[]).filter(${seedPredicate})[0];
    const c=document.querySelector('canvas');const r=c.getBoundingClientRect();const z=s.screenSize;
    return {x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height)};})()`);
  await clickAt(cdp, seedBox.x, seedBox.y);
  await sleep(300);
  await key(cdp, "End", "End", 35);
  for (let index = 0; index < 32; index++) await key(cdp, "Backspace", "Backspace", 8);
  await typeText(cdp, options.seed);
  await sleep(500);
  await screenshot(cdp, "create-world.png");
  const createdAt = Date.now();
  await clickWidget(cdp, "Create New World", 15000);

  await waitFor(cdp, "!!window.__gaiusMinecraftState?.level&&!window.__gaiusMinecraftState?.screen&&!!window.__gaiusMinecraftState?.player",
    timeoutAt - Date.now(), "world entry");
  report.phases.worldEntrySeconds = +((Date.now() - createdAt) / 1000).toFixed(1);
  await log("world", `entered after ${report.phases.worldEntrySeconds} s`);

  // Settle: the client's loaded chunk count stops growing for 10 s (at most --settle-s).
  let previous = -1, stableSince = Date.now();
  const settleEnd = Date.now() + options.settleS * 1000;
  while (Date.now() < settleEnd) {
    const state = await evaluate(cdp, stateExpression).catch(() => null);
    const loaded = state?.loaded ?? -1;
    if (loaded !== previous) { previous = loaded; stableSince = Date.now(); }
    else if (Date.now() - stableSince >= 10000 && loaded > 0) break;
    await sleep(1000);
  }
  report.client = await evaluate(cdp, stateExpression).catch(() => null);
  report.runtime = await evaluate(cdp, RUNTIME_TELEMETRY_EXPRESSION).catch(() => null);
  report.crossOriginIsolated = await evaluate(cdp, "globalThis.crossOriginIsolated === true").catch(() => false);
  if (!report.crossOriginIsolated) {
    throw new Error("cross-origin isolation is not enabled; WasmGC/JSPI capture requires COOP/COEP");
  }
  if (report.runtime?.runtime !== options.runtime) {
    throw new Error(`client runtime telemetry is ${report.runtime?.runtime ?? "missing"}, expected ${options.runtime}`);
  }
  await log("settled", JSON.stringify(report.client));
  await screenshot(cdp, "in-world.png");

  const command = async (text) => {
    await key(cdp, "t", "KeyT", 84, "t");
    await waitFor(cdp, `/ChatScreen/.test(${screenExpression})`, 5000, "chat screen");
    await sleep(200);
    await typeText(cdp, text);
    await sleep(150);
    await key(cdp, "Enter", "Enter", 13);
    await log("command", text);
    await sleep(500);
  };
  // "/save-all flush" writes only FULL chunks (ChunkMap.saveAllChunks(true)); a proto-chunk, e.g.
  // the TERRAIN ring around the generated area, is written when it unloads. So the player leaves
  // the area first (--far, in blocks) and every chunk of the spawn area unloads and is
  // saved; the area around the far position adds FULL chunks.
  const spawnState = report.client;
  // Spectators do not fall: a player at a far position whose chunks are still generating would
  // otherwise drop below the world and die, and the respawn screen keeps the old area loaded.
  if (!options.stay) {
    await command("/gamemode spectator");
    if (options.workerPause === "far") {
      for (const [sessionId, worker] of workers) {
        if (/singleplayer-server-worker/.test(worker.url)) {
          await cdp.send("Debugger.setPauseOnExceptions", {state: "all"}, sessionId).catch(() => {});
        }
      }
      await log("worker-pause", "caught exceptions too from here on");
    }
    await command(`/tp ${options.farX} 200 ${options.farZ}`);
    await sleep(options.unloadS * 1000);
  }
  report.farClient = await evaluate(cdp, stateExpression).catch(() => null);
  await command("/save-all flush");
  report.spawnArea = spawnState?.player || null;
  const spawnChunkX = Math.floor(Number(spawnState?.player?.x || 0) / 16);
  const spawnChunkZ = Math.floor(Number(spawnState?.player?.z || 0) / 16);
  const fixedChunks = [];
  for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) fixedChunks.push([spawnChunkX + dx, spawnChunkZ + dz]);
  report.fixedChunks = fixedChunks.map(([x, z]) => `${x},${z}`);
  const savedAt = Date.now();

  const serverSession = () => [...workers.entries()].find(([, worker]) => worker.type === "worker"
    && /singleplayer-server-worker/.test(worker.url))?.[0];
  // A region file can exist while its chunks are only TERRAIN. Keep polling until the
  // fixed 3x3 comparison set is persisted as FULL and the files remain unchanged.
  let listing = null, signature = "", stableAt = Date.now(), fixedStatuses = {};
  const statusSamples = [];
  let lastStatusLogAt = savedAt - 60000;
  const saveTimeoutMs = Math.max(120000, (options.saveTimeoutS * 1000));
  while (Date.now() - savedAt < saveTimeoutMs) {
    await sleep(1000);
    const sessionId = serverSession();
    if (!sessionId) continue;
    listing = await evaluate(cdp, LIST_FILES, sessionId).catch(error => ({error: String(error)}));
    const regionEntries = listing?.files?.filter(file => /\.mca$/.test(file.path)) || [];
    const next = JSON.stringify(listing?.files?.filter(file => /\.mca$|level\.dat$/.test(file.path)) || []);
    if (next !== signature) { signature = next; stableAt = Date.now(); }
    const byRegion = new Map();
    for (const entry of regionEntries) {
      const match = entry.path.match(/region\/(r\.-?\d+\.-?\d+\.mca)$/);
      if (match) byRegion.set(match[1], entry.path);
    }
    fixedStatuses = {};
    const regionBytes = new Map();
    for (const [chunkX, chunkZ] of fixedChunks) {
      const regionName = `r.${Math.floor(chunkX / 32)}.${Math.floor(chunkZ / 32)}.mca`;
      const path = byRegion.get(regionName);
      if (!path) continue;
      let bytes = regionBytes.get(path);
      if (!bytes) {
        const value = await evaluate(cdp, READ_FILE(path), sessionId).catch(() => null);
        if (value?.base64) { bytes = Buffer.from(value.base64, "base64"); regionBytes.set(path, bytes); }
      }
      if (bytes) Object.assign(fixedStatuses, persistedChunkStatuses(bytes, [[chunkX, chunkZ]]));
    }
    const allFull = fixedChunks.every(([x, z]) => /(?:^|:)full$/i.test(String(fixedStatuses[`${x},${z}`] || "")));
    if (Date.now() - lastStatusLogAt >= 60000) {
      lastStatusLogAt = Date.now();
      const sample = {at: seconds(), loaded: (await evaluate(cdp, stateExpression).catch(() => null))?.loaded ?? null,
        statuses: {...fixedStatuses}, regionFiles: regionEntries.map(file => ({path: file.path, size: file.size}))};
      statusSamples.push(sample);
      await log("fixed-status", JSON.stringify(sample));
    }
    if (Date.now() - stableAt >= 5000 && allFull) break;
  }
  report.fixedChunkStatuses = fixedStatuses;
  report.fixedChunkStatusSamples = statusSamples;
  if (!listing?.files) throw new Error(`no server Worker file store: ${JSON.stringify(listing).slice(0, 400)}`);
  const session = serverSession();
  if (!session) throw new Error("server Worker disappeared before telemetry capture");
  report.phases.saveSettledSeconds = +((Date.now() - savedAt) / 1000).toFixed(1);
  report.serverRuntime = listing.serverRuntime;
  if (report.serverRuntime !== options.serverRuntime) {
    throw new Error(`server Worker runtime telemetry is ${report.serverRuntime ?? "missing"}, expected ${options.serverRuntime}`);
  }
  report.serverTelemetry = await evaluate(cdp, SERVER_TELEMETRY, session).catch(error => ({error: String(error)}));
  if (report.serverTelemetry?.serverRuntime !== options.serverRuntime) {
    throw new Error(`server Worker telemetry is ${report.serverTelemetry?.serverRuntime ?? "missing"}, expected ${options.serverRuntime}`);
  }
  report.storageBackend = listing.backend;
  if (!fixedChunks.every(([x, z]) => /(?:^|:)full$/i.test(String(fixedStatuses[`${x},${z}`] || "")))) {
    throw new Error(`fixed chunks were not persisted FULL before timeout: ${JSON.stringify(fixedStatuses)}`);
  }
  report.storageStats = listing.stats;
  report.files = listing.files;
  const pattern = /^\/gaius\/saves\/([^/]+)\/(.*?)region\/(r\.-?\d+\.-?\d+\.mca)$/;
  const regions = listing.files.filter(file => pattern.test(file.path));
  const groups = new Set(regions.map(file => file.path.match(pattern)[2]));
  // The overworld's region directory: <world>/region (or a dimensions/minecraft/overworld/ form).
  const overworld = [...groups].find(group => group === "" || /(^|\/)overworld\/$/.test(group));
  if (overworld === undefined) throw new Error(`no overworld region directory among ${JSON.stringify([...groups])}`);
  for (const file of regions.filter(entry => entry.path.match(pattern)[2] === overworld)) {
    const value = await evaluate(cdp, READ_FILE(file.path), session);
    if (!value?.base64) throw new Error(`could not read ${file.path}: ${JSON.stringify(value).slice(0, 200)}`);
    const bytes = Buffer.from(value.base64, "base64");
    const name = file.path.match(pattern)[3];
    await writeFile(join(options.out, "region", name), bytes);
    report.regions.push({name, bytes: bytes.length});
  }
  const levelDat = listing.files.find(file => /^\/gaius\/saves\/[^/]+\/level\.dat$/.test(file.path));
  if (levelDat) {
    const value = await evaluate(cdp, READ_FILE(levelDat.path), session);
    if (!value?.base64) throw new Error(`could not read ${levelDat.path}`);
    await writeFile(join(options.out, "level.dat"), Buffer.from(value.base64, "base64"));
    report.levelDat = levelDat.path;
  }
  report.world = regions[0]?.path.match(pattern)[1] || null;
  await log("saved", `${report.regions.length} overworld region file(s), ${report.regions.reduce((sum, r) => sum + r.bytes, 0)} bytes, serverRuntime=${report.serverRuntime}, backend=${report.storageBackend}`);
} catch (error) {
  report.error = String(error?.stack || error);
  exitCode = 1;
  await log("error", report.error.split("\n")[0]);
  if (cdp) {
    report.lastState = await evaluate(cdp, stateExpression).catch(() => null);
    report.runtime = report.runtime || await evaluate(cdp, RUNTIME_TELEMETRY_EXPRESSION).catch(() => null);
    const server = [...workers.entries()].find(([, worker]) => /singleplayer-server-worker/.test(worker.url))?.[0];
    if (server) report.serverTelemetry = await evaluate(cdp, SERVER_TELEMETRY, server).catch(error => ({error: String(error)}));
    await screenshot(cdp, "error.png");
  }
} finally {
  if (cdp) {
    report.pageKernels = await evaluate(cdp, KERNEL_TELEMETRY_EXPRESSION).catch(error => ({error: String(error)}));
    const worker = [...workers.entries()].find(([, value]) => /singleplayer-server-worker/.test(value.url))?.[0];
    if (worker) {
      report.serverKernels = await evaluate(cdp, KERNEL_TELEMETRY_EXPRESSION, worker).catch(error => ({error: String(error)}));
      report.serverTelemetry = await evaluate(cdp, SERVER_TELEMETRY, worker).catch(error => ({error: String(error)}));
    }
  }
  if (options.requireWorldgenKernels) {
    report.kernelActivityFailures = kernelActivityFailures(report.serverKernels);
    if (report.kernelActivityFailures.length) {
      exitCode = 1;
      await log("kernel-activity-fail", report.kernelActivityFailures.join("; "));
    }
  }
  report.finishedAt = new Date().toISOString();
  report.seconds = seconds();
  await writeFile(join(options.out, "browser-world.json"), `${JSON.stringify(report, null, 2)}\n`);
  try { cdp?.ws.close(); } catch {}
  if (process.platform === "win32") spawnSync("taskkill", ["/PID", String(chrome.pid), "/T", "/F"], {stdio: "ignore"});
  else chrome.kill("SIGKILL");
  server.close();
  await sleep(1500);
  await rm(profileDir, {recursive: true, force: true}).catch(() => {});
}
console.log(`BROWSER_WORLD_${exitCode === 0 ? "OK" : "FAIL"} ${join(options.out, "browser-world.json")}`);
process.exit(exitCode);
