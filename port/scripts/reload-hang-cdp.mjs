#!/usr/bin/env node
// User-style reload probe for title and singleplayer world states.
import {spawn} from "node:child_process";
import {createServer} from "node:http";
import {createReadStream, existsSync, statSync} from "node:fs";
import {mkdir, rm, writeFile} from "node:fs/promises";
import {join, resolve, relative, extname} from "node:path";
import {mkdtemp} from "node:fs/promises";
import {chromeIsolationArgs} from "./bench/harness-common.mjs";
import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import {killProcessTree, parseMemoryBudget, processPrivateBytes, reloadMemoryGate} from "./reload-memory-gate.mjs";

const dist = resolve(process.env.DIST);
const out = resolve(process.env.OUT || "reload-hang-out");
const profile = process.env.PROFILE || RENDERPEARL_PROFILE.id;
const seed = String(process.env.SEED || "260315");
const timeoutMs = Number(process.env.TIMEOUT_MS || 180000);
const reloadMemoryBudget = parseMemoryBudget(process.env.RELOAD_MEMORY_BUDGET_BYTES
  || (process.env.RELOAD_MEMORY_BUDGET_GIB ? `${process.env.RELOAD_MEMORY_BUDGET_GIB}GiB` : undefined));
await mkdir(out, {recursive: true});
const events = [];
const pendingRequests = new Map();
const t0 = Date.now();
const stamp = () => Date.now() - t0;
const record = (type, detail = {}) => { const e = {at: stamp(), type, ...detail}; events.push(e); console.log(JSON.stringify(e)); };
const mime = {".html":"text/html", ".js":"text/javascript", ".wasm":"application/wasm", ".gz":"application/gzip", ".json":"application/json", ".png":"image/png", ".br":"application/octet-stream"};
const server = createServer((req, res) => {
  const u = new URL(req.url, "http://127.0.0.1");
  const path = resolve(dist, "." + decodeURIComponent(u.pathname));
  const rel = relative(dist, path);
  if (rel.startsWith("..") || !existsSync(path) || statSync(path).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, {"Content-Type": mime[extname(path)] || "application/octet-stream", "Cache-Control":"no-store"});
  createReadStream(path).pipe(res);
});
await new Promise(ok => server.listen(0, "127.0.0.1", ok));
const pageUrl = `http://127.0.0.1:${server.address().port}/index.html?runtime=wasm&gpu=webgpu`;
const chromeBin = process.env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const profileDir = await mkdtemp(join(out, "chrome-profile-"));
const debugPort = await new Promise((ok, bad) => { const s = createServer(); s.listen(0, "127.0.0.1", () => { const p=s.address().port; s.close(()=>ok(p)); }); s.on("error", bad); });
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${debugPort}`, "--remote-allow-origins=*", `--user-data-dir=${profileDir}`, "--window-size=1280,720", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader", ...chromeIsolationArgs(), "about:blank"], {stdio:"ignore"});
const reloadMemorySeries = [];
let browserCdp;
class Cdp {
  constructor(url) { this.ws = new WebSocket(url); this.id=1; this.pending=new Map(); this.listeners=new Map(); this.ws.addEventListener("message", e => { const m=JSON.parse(e.data); if (m.id && this.pending.has(m.id)) { const p=this.pending.get(m.id); this.pending.delete(m.id); m.error?p.bad(new Error(m.error.message)):p.ok(m.result); } else if (m.method) for (const f of this.listeners.get(m.method)||[]) f(m.params||{},m.sessionId); }); }
  on(name, f) { if (!this.listeners.has(name)) this.listeners.set(name,[]); this.listeners.get(name).push(f); }
  open() { return new Promise((ok,bad)=>{this.ws.addEventListener("open",ok);this.ws.addEventListener("error",bad);}); }
  send(method, params={}, sessionId) { const id=this.id++; return new Promise((ok,bad)=>{this.pending.set(id,{ok,bad});this.ws.send(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})}));}); }
}
async function rendererPrivateMemory() {
  const processInfo = await browserCdp.send("SystemInfo.getProcessInfo").catch(() => null);
  const rendererPids = (processInfo?.processInfo || [])
    .filter(process => String(process.type || "").toLowerCase() === "renderer" && Number.isInteger(Number(process.id)))
    .map(process => Number(process.id));
  const processes = await Promise.all(rendererPids.map(async pid => ({pid, privateBytes: await processPrivateBytes(pid)})));
  const measured = processes.filter(process => Number.isFinite(process.privateBytes));
  return {rendererPids, processes, rendererPrivateBytes: measured.length ? measured.reduce((sum, process) => sum + process.privateBytes, 0) : null};
}
async function waitJson(url, ms=15000) { const end=Date.now()+ms; while(Date.now()<end){ try { const r=await fetch(url); if(r.ok)return r.json(); } catch {} await new Promise(r=>setTimeout(r,100)); } throw new Error(`timeout ${url}`); }
const sleep = ms => new Promise(r=>setTimeout(r,ms));
let cdp;
async function evaluate(expr, timeout=15000) { let timer; try { return await Promise.race([cdp.send("Runtime.evaluate", {expression:expr, awaitPromise:true, returnByValue:true}), new Promise((_,rej)=>timer=setTimeout(()=>rej(new Error("Runtime.evaluate timeout")),timeout))]).then(r=>r.result?.value); } finally { clearTimeout(timer); } }
async function state() { return evaluate(`(()=>{const s=window.__gaiusMinecraftState||{};return {screen:s.screen||null,overlay:s.overlay||null,level:!!s.level,player:!!s.player,loaded:s.loaded||0,boot:window.__gaiusBootTimings||null,lease:!!window.__gaiusRuntimeLeaseHeld,workers:[...(window.__gaiusSingleplayerWorkers?.values?.()||[])].map(w=>({stopped:!!w.__gaiusStopped,terminal:!!w.__gaiusTerminal,stopRequested:!!w.__gaiusStopRequested}))}})()`); }
async function captureDiagnostics(label) {
  const page = await evaluate(`(async()=>{let locks=null,sw=null;try{locks=await navigator.locks?.query?.()}catch(e){locks={error:String(e)}}try{sw=await navigator.serviceWorker?.getRegistrations?.().then(rs=>rs.map(r=>({scope:r.scope,active:r.active?.state||null,waiting:r.waiting?.state||null,installing:r.installing?.state||null})))}catch(e){sw={error:String(e)}}const s=window.__gaiusMinecraftState||{};return {href:location.href,readyState:document.readyState,screen:s.screen||null,overlay:s.overlay||null,level:!!s.level,boot:window.__gaiusBootTimings||null,lease:window.__gaiusRuntimeLeaseHeld===true,locks,sw,fsBackend:window.__gaiusFsBackend||null,fsReady:!!window.__gaiusFsReady,bootObject:window.__gaiusBoot?{runtime:window.__gaiusBoot.runtime,serverRuntime:window.__gaiusBoot.serverRuntime}:null,storageEvents:window.__gaiusReloadDiagnostics||[],events:(window.__gaiusMinecraftEvents||[]).slice(-24)}})()`, 5000).catch(error => ({error:String(error)}));
  const targetList = await waitJson(`http://127.0.0.1:${debugPort}/json/list`, 5000).catch(error => ({error:String(error)}));
  const workers = [];
  for (const target of Array.isArray(targetList) ? targetList.filter(item => item.type === "worker") : []) {
    try {
      const worker = new Cdp(target.webSocketDebuggerUrl); await worker.open();
      const request = worker.send("Runtime.evaluate", {expression:`(()=>({wasm:globalThis.GaiusWasmWorker?.info?.()||null,storage:globalThis.__gaiusStorageStats||null,world:globalThis.__gaiusServerWorldId||null,session:globalThis.__gaiusServerSessionId||null}))()`, returnByValue:true});
      const result = await Promise.race([request, sleep(5000).then(() => { throw new Error("worker Runtime.evaluate timeout"); })]);
      workers.push({title:target.title,url:target.url,info:result.result?.value||null});
      try { worker.ws.close(); } catch (_) {}
    } catch (error) { workers.push({title:target.title,url:target.url,error:String(error)}); }
  }
  record("diagnostics", {label, page, workers, pendingRequests:[...pendingRequests.values()]});
}
async function waitFor(label, pred, ms=timeoutMs) { const end=Date.now()+ms; while(Date.now()<end){ let s; try{s=await state();}catch(e){record("state-error",{label,error:String(e)}); await sleep(250); continue;} record("state",{label,state:s}); if(pred(s))return s; await sleep(500); } throw new Error(`timeout ${label}`); }
async function sampleReloadMemory(cycle, phase) {
  const metricResult = await cdp.send("Performance.getMetrics").catch(() => null);
  const performance = Object.fromEntries((metricResult?.metrics || []).map(metric => [metric.name, Number(metric.value)]));
  const renderer = await rendererPrivateMemory();
  const sample = {cycle, phase, atMs: stamp(), browserPid: chrome.pid,
    rendererPids: renderer.rendererPids, rendererProcesses: renderer.processes,
    rendererPrivateBytes: renderer.rendererPrivateBytes, performance};
  reloadMemorySeries.push(sample);
  record("reload-memory", sample);
  return sample;
}
async function clickWidget(text) { const js=`(()=>{const s=window.__gaiusMinecraftState||{};const ws=s.screenWidgets||[];const w=ws.find(x=>String(x.text||'').toLowerCase().includes(${JSON.stringify(text.toLowerCase())})&&x.visible!==false);const c=document.querySelector('canvas');const r=c?.getBoundingClientRect();const z=s.screenSize;if(!w||!r||!z)return null;return {x:r.left+(w.x+w.width/2)*r.width/z.width,y:r.top+(w.y+w.height/2)*r.height/z.height}})()`; let p=null; const end=Date.now()+60000; while(Date.now()<end&&!p){ try{p=await evaluate(js,5000);}catch{} if(!p)await sleep(250); } if(!p)throw new Error(`widget ${text} not found`); await cdp.send("Input.dispatchMouseEvent",{type:"mousePressed",x:p.x,y:p.y,button:"left",buttons:1,clickCount:1}); await cdp.send("Input.dispatchMouseEvent",{type:"mouseReleased",x:p.x,y:p.y,button:"left",buttons:0,clickCount:1}); }
async function clickFirstWorld() {
  const js = `(()=>{const s=window.__gaiusMinecraftState||{};const f=s.worldSelection?.first;const c=document.querySelector('canvas');const r=c?.getBoundingClientRect();const z=s.screenSize;if(!f||!r||!z)return null;return {x:r.left+(Number(f.x)+Number(f.width)/2)*r.width/Number(z.width),y:r.top+(Number(f.y)+Number(f.height)/2)*r.height/Number(z.height)}})()`;
  let p = null; const end = Date.now() + 20000;
  while (Date.now() < end && !p) { try { p = await evaluate(js, 5000); } catch {} if (!p) await sleep(250); }
  if (!p) throw new Error("world list is empty");
  await cdp.send("Input.dispatchMouseEvent", {type:"mousePressed",x:p.x,y:p.y,button:"left",buttons:1,clickCount:1});
  await cdp.send("Input.dispatchMouseEvent", {type:"mouseReleased",x:p.x,y:p.y,button:"left",buttons:0,clickCount:1});
}
async function keyF5() {
  // Headless Chrome does not route browser accelerators from a normal keyDown.
  // rawKeyDown plus the Reload command exercises the same CDP input path while
  // asking the browser target to perform the F5 navigation.
  await cdp.send("Input.dispatchKeyEvent", {
    type: "rawKeyDown", key: "F5", code: "F5",
    windowsVirtualKeyCode: 116, nativeVirtualKeyCode: 116,
    isSystemKey: true, commands: ["Reload"],
  });
  await cdp.send("Input.dispatchKeyEvent", {
    type: "keyUp", key: "F5", code: "F5",
    windowsVirtualKeyCode: 116, nativeVirtualKeyCode: 116,
    isSystemKey: true,
  });
}
async function reload(kind) { const before=Date.now(); record("reload-start",{kind}); if(kind==="page") await cdp.send("Page.reload",{ignoreCache:false}); else await keyF5(); let title; try { title=await waitFor(`${kind}-title`,s=>/TitleScreen|BrowserProfileScreen/.test(String(s.screen)),timeoutMs); record("reload-title",{kind,elapsedMs:Date.now()-before,state:title}); } catch(e) { record("reload-failed",{kind,elapsedMs:Date.now()-before,error:String(e)}); await captureDiagnostics(`${kind}-failed`); throw e; } }
try {
  const targets=await waitJson(`http://127.0.0.1:${debugPort}/json/list`); cdp=new Cdp(targets.find(x=>x.type==="page").webSocketDebuggerUrl); await cdp.open();
  const browserVersionInfo = await waitJson(`http://127.0.0.1:${debugPort}/json/version`); browserCdp = new Cdp(browserVersionInfo.webSocketDebuggerUrl); await browserCdp.open();
  cdp.on("Runtime.consoleAPICalled",(p,sid)=>record("console",{worker:!!sid,args:(p.args||[]).map(x=>x.value??x.description)}));
  await cdp.send("Page.enable"); await cdp.send("Runtime.enable"); await cdp.send("Network.enable"); await cdp.send("Performance.enable");
  await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source:`(()=>{const root=globalThis;const log=(type,detail)=>{const a=root.__gaiusReloadDiagnostics||(root.__gaiusReloadDiagnostics=[]);a.push({at:performance.now(),type,detail:String(detail||"")});if(a.length>128)a.shift()};try{const original=indexedDB.open.bind(indexedDB);indexedDB.open=(...args)=>{const req=original(...args);log("idb-open",args[0]+"/"+args[1]);req.addEventListener("blocked",()=>log("idb-blocked"));req.addEventListener("error",()=>log("idb-error",req.error));req.addEventListener("upgradeneeded",()=>log("idb-upgradeneeded"));req.addEventListener("success",()=>{log("idb-success");try{req.result.addEventListener("versionchange",()=>log("idb-versionchange"));req.result.addEventListener("close",()=>log("idb-close"))}catch(e){log("idb-listener-error",e)}});return req}}catch(e){log("idb-wrap-error",e)}try{const storage=navigator.storage;const original=storage.getDirectory?.bind(storage);if(original)storage.getDirectory=(...args)=>{log("opfs-get-directory");return original(...args).catch(e=>{log("opfs-get-directory-error",e);throw e})};const proto=globalThis.FileSystemFileHandle?.prototype;if(proto?.createSyncAccessHandle){const create=proto.createSyncAccessHandle;proto.createSyncAccessHandle=function(...args){log("opfs-create-handle");return Promise.resolve(create.apply(this,args)).then(h=>{log("opfs-handle-ready");const close=h.close?.bind(h);if(close)h.close=(...a)=>{log("opfs-handle-close");return close(...a)};return h},e=>{log("opfs-create-error",e);throw e})}}}catch(e){log("opfs-wrap-error",e)}})()`});
  cdp.on("Network.requestWillBeSent", p => pendingRequests.set(p.requestId,{requestId:p.requestId,url:p.request?.url||"",type:p.type||null}));
  cdp.on("Network.loadingFinished", p => pendingRequests.delete(p.requestId));
  cdp.on("Network.loadingFailed", p => pendingRequests.delete(p.requestId));
  await cdp.send("Page.navigate",{url:pageUrl});
  const title = () => waitFor("title", s => /TitleScreen|BrowserProfileScreen/.test(String(s.screen)));
  const world = label => waitFor(label, s => s.level && s.player && !s.screen, timeoutMs);
  const enterExistingWorld = async label => {
    await clickWidget("Singleplayer");
    const screen = await waitFor(`${label}-list`, s => /SelectWorldScreen|CreateWorldScreen/.test(String(s.screen)), 60000);
    if (/CreateWorldScreen/.test(String(screen.screen))) {
      await clickWidget("Create New World");
    } else {
      await clickFirstWorld().catch(() => {});
      await clickWidget("Play Selected World").catch(() => {});
    }
    await world(`${label}-entered`);
  };
  const reloadTitle = async (kind, iteration) => {
    record("iteration-start", {kind, iteration});
    if (kind.endsWith("f5")) await keyF5();
    else await cdp.send("Page.reload", {ignoreCache:false});
    try { await title(); } catch (error) { await captureDiagnostics(`${kind}-${iteration}-failed`); throw error; }
    record("iteration-pass", {kind, iteration});
  };

  try { await title(); } catch (error) { await captureDiagnostics("initial-title-failed"); throw error; }
  await sampleReloadMemory(0, "before-reload");
  for (let i = 1; i <= 10; i++) {
    await reloadTitle(i % 2 ? "title-page-reload" : "title-f5-reload", i);
    await sampleReloadMemory(i, "after-reload");
  }
  const reloadSoak = {cycles: reloadMemorySeries, memorySeries: reloadMemorySeries,
    gate: reloadMemoryGate(reloadMemorySeries, reloadMemoryBudget)};
  if (!reloadSoak.gate.pass) throw new Error(`reload memory gate failed: ${reloadSoak.gate.reason || "unknown reason"}`);
  if (process.env.TITLE_ONLY === "1") {
    await writeFile(join(out,"report.json"),JSON.stringify({profile,dist,pageUrl,events,reloadSoak},null,2));
    record("TITLE_ONLY_PASS", {out});
    process.exitCode = 0;
    throw new Error("__title_only_complete__");
  }

  await clickWidget("Singleplayer");
  await waitFor("world-screen", s => /SelectWorldScreen|CreateWorldScreen/.test(String(s.screen)), 60000);
  if (/SelectWorldScreen/.test(String((await state()).screen))) await clickWidget("Create New World");
  await waitFor("create-screen", s => /CreateWorldScreen/.test(String(s.screen)), 60000);
  await clickWidget("Create New World");
  await world("initial-world");

  for (let i = 1; i <= 10; i++) {
    record("iteration-start", {kind:"world-page-reload", iteration:i});
    if (i % 2) await cdp.send("Page.reload", {ignoreCache:false}); else await keyF5();
    let afterReload;
    try { afterReload = await waitFor(`world-page-reload-${i}-ready`, s => /TitleScreen|BrowserProfileScreen/.test(String(s.screen)) || (s.level && s.player && !s.screen), timeoutMs); }
    catch (error) { await captureDiagnostics(`world-page-reload-${i}-failed`); throw error; }
    if (!(afterReload.level && afterReload.player && !afterReload.screen)) await enterExistingWorld(`world-reload-${i}`);
    record("iteration-pass", {kind:"world-page-reload", iteration:i});
  }

  for (let i = 1; i <= 10; i++) {
    record("iteration-start", {kind:"save-quit-immediate-navigation", iteration:i});
    await cdp.send("Input.dispatchKeyEvent", {type:"keyDown", key:"Escape", code:"Escape", windowsVirtualKeyCode:27, nativeVirtualKeyCode:27});
    await cdp.send("Input.dispatchKeyEvent", {type:"keyUp", key:"Escape", code:"Escape", windowsVirtualKeyCode:27, nativeVirtualKeyCode:27});
    await clickWidget("Save and Quit to Title");
    // Navigate as soon as the click is dispatched; this exercises pagehide while the Worker saves.
    await cdp.send("Page.navigate", {url:pageUrl});
    try { await title(); } catch (error) { await captureDiagnostics(`save-quit-${i}-failed`); throw error; }
    await enterExistingWorld(`save-quit-${i}`);
    record("iteration-pass", {kind:"save-quit-immediate-navigation", iteration:i});
  }

  // Kill the lease-owning tab and prove a fresh successor can acquire it.
  const browserInfo = await (await fetch(`http://127.0.0.1:${debugPort}/json/version`)).json();
  const controlCdp = new Cdp(browserInfo.webSocketDebuggerUrl);
  await controlCdp.open();
  const oldTargetId = targets.find(item => item.type === "page")?.id;
  if (!oldTargetId) throw new Error("lease-owning page target did not appear");
  await controlCdp.send("Target.closeTarget", {targetId:oldTargetId});
  record("killed-old-tab", {targetId:oldTargetId});
  const successor = await controlCdp.send("Target.createTarget", {url:pageUrl});
  await sleep(500);
  const successorTarget = (await waitJson(`http://127.0.0.1:${debugPort}/json/list`)).find(item => item.id === successor.targetId);
  if (!successorTarget) throw new Error("successor target did not appear");
  const successorCdp = new Cdp(successorTarget.webSocketDebuggerUrl);
  await successorCdp.open();
  const previousCdp = cdp;
  cdp = successorCdp;
  try {
    await cdp.send("Page.enable"); await cdp.send("Runtime.enable");
    await title();
    record("successor-pass", {targetId:successor.targetId});
  } catch (error) {
    await captureDiagnostics("successor-failed");
    throw error;
  } finally {
    cdp = previousCdp;
  }

  await writeFile(join(out,"report.json"),JSON.stringify({profile,dist,pageUrl,events,reloadSoak},null,2));
  record("PASS",{out});
} catch (e) {
  if (String(e && e.message) !== "__title_only_complete__") {
    const failedSoak = {cycles: reloadMemorySeries, memorySeries: reloadMemorySeries,
      gate: reloadMemoryGate(reloadMemorySeries,reloadMemoryBudget)};
    await writeFile(join(out,"report.json"),JSON.stringify({profile,dist,pageUrl,error:String(e),events,reloadSoak:failedSoak},null,2));
    record("FAIL",{error:String(e)}); process.exitCode=1;
  }
} finally {
  await killProcessTree(chrome.pid); try{server.close();}catch{}
  for (let attempt = 0; attempt < 12; attempt++) {
    try { await rm(profileDir,{recursive:true,force:true}); break; } catch { await sleep(500); }
  }
}
