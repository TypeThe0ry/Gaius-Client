#!/usr/bin/env node
// Touch and small-screen acceptance of a staged Gaius site in headless Chrome with device emulation.
//
//   SITE=<staged site dir> PROFILE=26.3|26.2|1.21.11 DEVICE=phone|tablet ENTRY=index.html OUT=<dir> \
//     node port/scripts/mobile-touch-cdp.mjs
//
// The page is served from 127.0.0.1 and driven with CDP touch events only (Input.dispatchTouchEvent) on
// top of Emulation.setDeviceMetricsOverride (mobile) + setTouchEmulationEnabled + a mobile user agent.
// The flow, one JSON check per step (report.json) and one screenshot per step (NN-name.jpg):
//   1. boot in portrait: touch controls detected, canvas fits the viewport, memory/quality tier
//   2. title screen by touch: tap Singleplayer, rotate to landscape on the world screens, create a
//      creative world (tap Game Mode; seed typed through the virtual keyboard input)
//   3. in the world: controls inside the viewport and not overlapping, hotbar tap, joystick move,
//      look drag, jump, double-tap jump (flight), break (attack button) and place (use button)
//      verified through the hit-result telemetry, chat command through the virtual keyboard
//   4. pause button (PauseScreen), back button, rotation portrait <-> landscape in the world
//   5. Save and Quit by touch, re-enter the world by touch: the canvas must fit the viewport
//      right after the re-entry and after rotating again, and the controls must still work
// Env: SITE, PROFILE, DEVICE (default phone), OUT, DEVICE_MEMORY (default 4 for phone, 8 for tablet; the
// selected Wasm runtime must use the low tier at 4), QUERY (extra page query), BOOT_TIMEOUT_MS (900000), WORLD_TIMEOUT_MS
// (420000), SEED (26300), GAIUS_CHROME_BIN. Run it through slot.sh chrome (it starts its own Chrome).

import {spawn} from "node:child_process";
import {createReadStream, existsSync, readFileSync, statSync} from "node:fs";
import {mkdir, mkdtemp, rm, writeFile, appendFile} from "node:fs/promises";
import {createServer as httpServer} from "node:http";
import {createServer as netServer} from "node:net";
import {extname, isAbsolute, join, normalize, relative, resolve} from "node:path";
import {chromeIsolationArgs} from "./bench/harness-common.mjs";

export const DEVICES = {
  phone: {label: "Pixel 7 class phone", width: 412, height: 915, dpr: 2.625, model: "Pixel 7", memory: 4},
  tablet: {label: "10 inch tablet", width: 820, height: 1180, dpr: 2, model: "SM-X710", memory: 8},
};
const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json",
  ".gz": "application/gzip", ".png": "image/png", ".br": "application/octet-stream"};

const sleep = ms => new Promise(r => setTimeout(r, ms));
const env = process.env;
const deviceName = env.DEVICE || "phone";
const device = DEVICES[deviceName];
if (!device) throw new Error(`unknown DEVICE ${deviceName}`);
const profile = env.PROFILE;
if (!profile) throw new Error("PROFILE is required");
if (!env.SITE) throw new Error("SITE is required");
const siteRoot = resolve(env.SITE);
const entry = String(env.ENTRY || "index.html").replace(/^\/+/, "");
if (entry.split(/[\\/]/).includes("..")) throw new Error("ENTRY must stay inside SITE");
const out = resolve(env.OUT || `mobile-${profile}-${deviceName}`);
const deviceMemory = Number(env.DEVICE_MEMORY || device.memory);
const bootTimeout = Number(env.BOOT_TIMEOUT_MS || 900000);
const worldTimeout = Number(env.WORLD_TIMEOUT_MS || 420000);
const seed = String(env.SEED || "26300");
const chromeBin = env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
await mkdir(out, {recursive: true});
const logFile = join(out, "console.log");
await writeFile(logFile, "");
const t0 = Date.now();
const stamp = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(7);
const logLines = [];
async function log(kind, text) {
  const line = `${stamp()} ${kind} ${text}`;
  logLines.push(line);
  await appendFile(logFile, line + "\n");
}

const report = {profile, device: deviceName, deviceSpec: device, deviceMemory, siteRoot, entry, startedAt: new Date().toISOString(),
  checks: {}, defects: [], measurements: {}, shots: []};
const pass = (name, detail) => { report.checks[name] = {ok: true, detail}; return log("CHECK", `PASS ${name} ${JSON.stringify(detail).slice(0, 500)}`); };
const fail = (name, detail) => { report.checks[name] = {ok: false, detail}; return log("CHECK", `FAIL ${name} ${JSON.stringify(detail).slice(0, 700)}`); };
const verdict = (name, ok, detail) => ok ? pass(name, detail) : fail(name, detail);

// ---------------------------------------------------------------- static server
const siteManifest = (() => { try { return JSON.parse(readFileSync(join(siteRoot, "gaius-site.json"), "utf8")); } catch { return null; } })();
const server = httpServer((req, res) => {
  const u = new URL(req.url, "http://127.0.0.1");
  let p = normalize(join(siteRoot, decodeURIComponent(u.pathname)));
  const rel = relative(siteRoot, p);
  if (rel.startsWith("..") || isAbsolute(rel)) { res.writeHead(403); return res.end(); }
  if (u.pathname === "/") p = join(siteRoot, entry);
  const logical = u.pathname.replace(/^\//, "");
  if (siteManifest?.assets?.[logical]) p = join(siteRoot, siteManifest.assets[logical]);
  if (!existsSync(p) || statSync(p).isDirectory()) { res.writeHead(404); log("http404", u.pathname).catch(() => {}); return res.end(); }
  res.writeHead(200, {"Content-Type": MIME[extname(p)] || "application/octet-stream", "Content-Length": statSync(p).size,
    "Accept-Ranges": "none", "Cache-Control": "no-store", "Cross-Origin-Resource-Policy": "same-origin",
    "Cross-Origin-Embedder-Policy": "require-corp", "Cross-Origin-Opener-Policy": "same-origin"});
  if (req.method === "HEAD") return res.end();
  createReadStream(p).pipe(res);
});
await new Promise(ok => server.listen(0, "127.0.0.1", ok));
let pageUrl = `http://127.0.0.1:${server.address().port}/${entry}`;
if (env.QUERY) pageUrl += (pageUrl.includes("?") ? "&" : "?") + env.QUERY.replace(/^\?/, "");

// ---------------------------------------------------------------- CDP
class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url); this.id = 1; this.pending = new Map(); this.listeners = new Map();
    this.ws.addEventListener("close", () => {
      for (const pending of this.pending.values()) pending.bad(new Error("CDP connection closed"));
      this.pending.clear();
    });
    this.ws.addEventListener("message", e => {
      const m = JSON.parse(e.data);
      if (m.id && this.pending.has(m.id)) {
        const {ok, bad} = this.pending.get(m.id); this.pending.delete(m.id);
        m.error ? bad(new Error(m.error.message)) : ok(m.result);
      } else if (m.method) for (const fn of this.listeners.get(m.method) || []) fn(m.params || {}, m.sessionId);
    });
  }
  on(method, fn) { if (!this.listeners.has(method)) this.listeners.set(method, []); this.listeners.get(method).push(fn); }
  open() { return new Promise((ok, bad) => { this.ws.addEventListener("open", ok); this.ws.addEventListener("error", bad); }); }
  send(method, params = {}, sessionId) {
    if (this.ws.readyState !== WebSocket.OPEN) return Promise.reject(new Error("CDP is not connected"));
    const id = this.id++;
    return new Promise((ok, bad) => {
      const timer = setTimeout(() => { this.pending.delete(id); bad(new Error(`CDP timeout: ${method}`)); }, Number(env.CDP_TIMEOUT_MS || 30000));
      this.pending.set(id, {ok: v => { clearTimeout(timer); ok(v); }, bad: e => { clearTimeout(timer); bad(e); }});
      this.ws.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}));
    });
  }
}
let cdp;
async function evaluate(expression) {
  const r = await cdp.send("Runtime.evaluate", {expression, awaitPromise: true, returnByValue: true});
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text || "evaluate failed");
  return r.result?.value;
}
async function waitFor(expr, ms, label) {
  const end = Date.now() + ms;
  while (Date.now() < end) { try { if (await evaluate(expr)) return true; } catch {} await sleep(250); }
  throw new Error(`timeout: ${label}`);
}
const freePort = async () => {
  const s = netServer(); await new Promise(ok => s.listen(0, "127.0.0.1", ok));
  const p = s.address().port; await new Promise(ok => s.close(ok)); return p;
};

// ---------------------------------------------------------------- page-side expressions
const STATE = `(()=>{const s=window.__gaiusMinecraftState||null;const t=window.__gaiusTouch||null;
  const c=document.getElementById('mc-canvas')||document.querySelector('canvas');const r=c&&c.getBoundingClientRect();const z=s&&s.screenSize;
  const widgets=s&&Array.isArray(s.screenWidgets)?s.screenWidgets.filter(w=>w&&w.visible!==false).map(w=>({type:String(w.type||'').split('.').pop(),text:String(w.text||''),...(Object.prototype.hasOwnProperty.call(w,'value')?{value:String(w.value||'')}:{ }),active:w.active!==false,focused:w.focused===true,
    x:r&&z&&z.width?r.left+Number(w.x)*r.width/z.width:null,y:r&&z&&z.height?r.top+Number(w.y)*r.height/z.height:null,
    w:r&&z&&z.width?Number(w.width)*r.width/z.width:null,h:r&&z&&z.height?Number(w.height)*r.height/z.height:null})):[];
  return {screen:s?String(s.screen||''):null,level:s?s.level:null,loaded:s?s.loadedChunkCount:null,
    player:s&&s.player?{x:s.player.x,y:s.player.y,z:s.player.z,yaw:s.player.yaw,pitch:s.player.pitch,mode:s.player.gameMode,item:s.player.selectedItem}:null,
    hit:s?s.hit:null,screenSize:z||null,worldSelection:s?s.worldSelection:null,widgets,
    touch:t?{enabled:t.enabled,mode:t.mode,reason:t.reason||null,override:t.override}:null};})()`;
const screenExpr = "String(window.__gaiusMinecraftState?.screen||'')";
const inWorldExpr = "!!window.__gaiusMinecraftState?.level&&!window.__gaiusMinecraftState?.screen&&!!window.__gaiusMinecraftState?.player";
const FIT = `(()=>{const c=document.getElementById('mc-canvas')||document.querySelector('canvas');const r=c?c.getBoundingClientRect():null;
  const d=window.__gaiusDisplay||null;const vv=window.visualViewport;const de=document.documentElement;
  return {vw:innerWidth,vh:innerHeight,vvw:vv?vv.width:null,vvh:vv?vv.height:null,dpr:devicePixelRatio,scrollW:de.scrollWidth,scrollH:de.scrollHeight,
    bodyScrollW:document.body?document.body.scrollWidth:null,scrollX:scrollX,scrollY:scrollY,
    canvas:r?{left:r.left,top:r.top,width:r.width,height:r.height,right:r.right,bottom:r.bottom,bufW:c.width,bufH:c.height}:null,
    display:d?{cssWidth:d.cssWidth,cssHeight:d.cssHeight,fbW:d.framebufferWidth,fbH:d.framebufferHeight,pr:d.pixelRatio,maxDpr:d.maxDpr}:null};})()`;
const CONTROLS = `(()=>{const root=document.getElementById('gaius-touch');if(!root)return null;
  const rootStyle=getComputedStyle(root);const rr=root.getBoundingClientRect();
  const nodes=[...root.querySelectorAll('.gt-ctl,.gt-stick')];
  const items=nodes.map(n=>{const cs=getComputedStyle(n);const b=n.getBoundingClientRect();
    return {id:n.getAttribute('data-control')||'stick',display:cs.display,visible:cs.display!=='none'&&cs.visibility!=='hidden'&&b.width>0,
      x:b.left,y:b.top,w:b.width,h:b.height};}).filter(i=>i.visible);
  const rot=root.querySelector('.gt-rotate');
  return {mode:root.getAttribute('data-mode'),rootDisplay:rootStyle.display,root:{w:rr.width,h:rr.height},items,rotateHint:rot&&!rot.hidden&&getComputedStyle(rot).display!=='none'};})()`;

const fitVerdict = f => {
  const problems = [];
  if (!f?.canvas) return ["no canvas"];
  const tol = 2;
  const c = f.canvas;
  if (c.left < -tol || c.top < -tol) problems.push(`canvas starts at ${c.left},${c.top}`);
  if (c.right > f.vw + tol) problems.push(`canvas right ${c.right.toFixed(1)} > viewport ${f.vw}`);
  if (c.bottom > f.vh + tol) problems.push(`canvas bottom ${c.bottom.toFixed(1)} > viewport ${f.vh}`);
  if (Math.abs(c.width - f.vw) > tol || Math.abs(c.height - f.vh) > tol) problems.push(`canvas ${c.width.toFixed(1)}x${c.height.toFixed(1)} != viewport ${f.vw}x${f.vh}`);
  if (f.scrollW > f.vw + tol || f.scrollH > f.vh + tol) problems.push(`document scroll ${f.scrollW}x${f.scrollH} > viewport ${f.vw}x${f.vh}`);
  if (f.scrollX || f.scrollY) problems.push(`page scrolled ${f.scrollX},${f.scrollY}`);
  if (f.display) {
    if (Math.abs(f.display.cssWidth - f.vw) > tol || Math.abs(f.display.cssHeight - f.vh) > tol) {
      problems.push(`game window ${f.display.cssWidth}x${f.display.cssHeight} css != viewport ${f.vw}x${f.vh}`);
    }
    const expectW = Math.round(f.display.cssWidth * f.display.pr), expectH = Math.round(f.display.cssHeight * f.display.pr);
    if (Math.abs(c.bufW - expectW) > 2 || Math.abs(c.bufH - expectH) > 2) problems.push(`framebuffer ${c.bufW}x${c.bufH} != css*pixelRatio ${expectW}x${expectH}`);
  }
  return problems;
};

// ---------------------------------------------------------------- device and input
let orientation = "portrait";
function viewportSize(o = orientation) {
  return o === "portrait" ? {w: device.width, h: device.height} : {w: device.height, h: device.width};
}
async function setOrientation(o) {
  orientation = o;
  const v = viewportSize(o);
  await cdp.send("Emulation.setDeviceMetricsOverride", {width: v.w, height: v.h, deviceScaleFactor: device.dpr, mobile: true,
    screenOrientation: {type: o === "portrait" ? "portraitPrimary" : "landscapePrimary", angle: o === "portrait" ? 0 : 90}});
  await log("rotate", `${o} ${v.w}x${v.h}`);
}
const touches = new Map();
async function sendTouch(type) {
  await cdp.send("Input.dispatchTouchEvent", {type, touchPoints: [...touches.entries()].map(([id, p]) => ({x: p.x, y: p.y, id, radiusX: 8, radiusY: 8, force: 1}))});
}
const touchDown = async (id, x, y) => { touches.set(id, {x, y}); await sendTouch("touchStart"); };
const touchMove = async (id, x, y) => { touches.set(id, {x, y}); await sendTouch("touchMove"); };
const touchUp = async id => { touches.delete(id); await sendTouch("touchEnd"); };
async function tapAt(x, y, holdMs = 70) {
  await touchDown(1, x, y); await sleep(holdMs); await touchUp(1);
}
async function dragPath(id, from, to, steps, stepMs = 16) {
  await touchDown(id, from.x, from.y);
  for (let i = 1; i <= steps; i++) { await touchMove(id, from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps); await sleep(stepMs); }
}
const center = r => ({x: r.x + r.w / 2, y: r.y + r.h / 2});
async function key(name, code, vk) {
  const p = {key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk};
  await cdp.send("Input.dispatchKeyEvent", {type: "keyDown", ...p}); await sleep(30);
  await cdp.send("Input.dispatchKeyEvent", {type: "keyUp", ...p});
}
const getState = () => evaluate(STATE).catch(() => null);
async function controlRect(id) {
  const c = await evaluate(CONTROLS);
  return c?.items.find(i => i.id === id) || null;
}
async function waitControl(id, ms = 4000) {
  let r = null;
  for (const end = Date.now() + ms; Date.now() < end;) { r = await controlRect(id); if (r) return r; await sleep(100); }
  return null;
}
async function tapControl(id, holdMs = 90) {
  const r = await waitControl(id);
  if (!r) throw new Error(`control not visible: ${id}`);
  const p = center(r);
  await tapAt(p.x, p.y, holdMs);
  return r;
}
async function holdControl(id, ms) {
  const r = await waitControl(id);
  if (!r) throw new Error(`control not visible: ${id}`);
  const p = center(r);
  await touchDown(2, p.x, p.y); await sleep(ms); await touchUp(2);
}
async function findWidget(match, ms = 20000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const s = await getState();
    const n = String(match.text).toLowerCase();
    const w = s?.widgets.find(x => x.active && x.x !== null && x.text.trim().toLowerCase() === n)
      || (match.exact ? null : s?.widgets.find(x => x.active && x.x !== null && x.text.toLowerCase().includes(n)));
    if (w) return w;
    await sleep(250);
  }
  return null;
}
async function tapWidget(text, ms) {
  const w = await findWidget({text}, ms);
  if (!w) throw new Error(`widget not found: ${text}`);
  const p = {x: w.x + w.w / 2, y: w.y + w.h / 2};
  await tapAt(p.x, p.y);
  await log("tap", `${w.text} (${w.type}) at ${Math.round(p.x)},${Math.round(p.y)}`);
  return w;
}
let shotN = 0;
async function snap(name) {
  try {
    const {data} = await cdp.send("Page.captureScreenshot", {format: "jpeg", quality: 82});
    const file = `${String(++shotN).padStart(2, "0")}-${name}.jpg`;
    await writeFile(join(out, file), Buffer.from(data, "base64"));
    report.shots.push(file);
    return file;
  } catch (e) { await log("shot-fail", `${name} ${e}`); return null; }
}

// ---------------------------------------------------------------- reusable checks
async function checkFit(tag, {settleMs = 0} = {}) {
  if (settleMs) await sleep(settleMs);
  const f = await evaluate(FIT);
  const problems = fitVerdict(f);
  report.measurements[`fit:${tag}`] = {fit: f, problems};
  verdict(`canvasFits:${tag}`, problems.length === 0, problems.length ? {problems, fit: f} : {viewport: `${f.vw}x${f.vh}`, display: f.display && `${f.display.cssWidth}x${f.display.cssHeight}@${f.display.pr}`});
  return problems;
}
// Wait up to ms for the canvas to fit; record how long it took (a resize that is only handled late is a defect too).
async function fitAfterResize(tag, ms = 6000) {
  const start = Date.now();
  let f = null, problems = ["not measured"];
  while (Date.now() - start < ms) {
    f = await evaluate(FIT).catch(() => null);
    problems = fitVerdict(f);
    if (!problems.length) break;
    await sleep(100);
  }
  const took = Date.now() - start;
  report.measurements[`fit:${tag}`] = {fit: f, problems, settleMs: took};
  verdict(`canvasFits:${tag}`, problems.length === 0, problems.length ? {problems, fit: f, waitedMs: took} : {settleMs: took, viewport: `${f.vw}x${f.vh}`, display: f.display && `${f.display.cssWidth}x${f.display.cssHeight}@${f.display.pr}`});
  return problems;
}
async function checkControls(tag, expectIds) {
  const c = await evaluate(CONTROLS);
  const v = await evaluate("({w:innerWidth,h:innerHeight})");
  const problems = [];
  if (!c) { verdict(`controlsLayout:${tag}`, false, "no #gaius-touch overlay"); return; }
  if (c.rootDisplay === "none") problems.push(`overlay hidden (mode ${c.mode})`);
  const ids = c.items.map(i => i.id);
  for (const id of expectIds) if (!ids.includes(id)) problems.push(`missing control ${id}`);
  for (const i of c.items) {
    if (i.x < -1 || i.y < -1 || i.x + i.w > v.w + 1 || i.y + i.h > v.h + 1) problems.push(`${i.id} outside viewport (${Math.round(i.x)},${Math.round(i.y)} ${Math.round(i.w)}x${Math.round(i.h)})`);
    if (Math.min(i.w, i.h) < 36) problems.push(`${i.id} touch target ${Math.round(Math.min(i.w, i.h))}px < 36px`);
  }
  for (let a = 0; a < c.items.length; a++) for (let b = a + 1; b < c.items.length; b++) {
    const p = c.items[a], q = c.items[b];
    if (p.id === "stick" || q.id === "stick") continue;
    const ox = Math.min(p.x + p.w, q.x + q.w) - Math.max(p.x, q.x), oy = Math.min(p.y + p.h, q.y + q.h) - Math.max(p.y, q.y);
    if (ox > 2 && oy > 2) problems.push(`${p.id} overlaps ${q.id} by ${Math.round(ox)}x${Math.round(oy)}`);
  }
  const hitTargets = await evaluate(`(()=>[...document.querySelectorAll('#gaius-touch .gt-ctl,#gaius-touch .gt-stick')].filter(n=>{const b=n.getBoundingClientRect();return b.width>0&&b.height>0}).map(n=>{const b=n.getBoundingClientRect();const h=document.elementFromPoint(b.left+b.width/2,b.top+b.height/2);return {id:n.getAttribute('data-control')||'stick',target:h?{tag:h.tagName,id:h.id||'',className:String(h.className||'').slice(0,120),pointerEvents:getComputedStyle(h).pointerEvents}:null};}))()`).catch(() => []);
  report.measurements[`controls:${tag}`] = {mode: c.mode, rotateHint: c.rotateHint, items: c.items.map(i => ({...i, x: Math.round(i.x), y: Math.round(i.y), w: Math.round(i.w), h: Math.round(i.h)})), hitTargets, problems};
  verdict(`controlsLayout:${tag}`, problems.length === 0, problems.length ? problems : {mode: c.mode, controls: ids, rotateHint: c.rotateHint});
}
async function checkScreenReachable(tag) {
  const s = await getState();
  const v = await evaluate("({w:innerWidth,h:innerHeight})");
  const off = (s?.widgets || []).filter(w => w.active && w.x !== null && w.w > 0 &&
    (w.x + w.w / 2 < 0 || w.x + w.w / 2 > v.w || w.y + w.h / 2 < 0 || w.y + w.h / 2 > v.h) && /Button|Edit/i.test(w.type));
  report.measurements[`widgets:${tag}`] = {screen: s?.screen, count: s?.widgets.length, off: off.map(w => w.text)};
  verdict(`widgetsOnScreen:${tag}`, off.length === 0, off.length ? off.map(w => `${w.text} at ${Math.round(w.x)},${Math.round(w.y)}`) : {screen: s?.screen, widgets: s?.widgets.length});
  // A widget whose centre lies under a touch button cannot be tapped (the overlay takes the touch).
  const c = await evaluate(CONTROLS);
  const covered = !c ? [] : (s?.widgets || []).filter(w => w.active && w.x !== null && w.w > 0 && w.w < v.w * 0.35 && c.items.some(i => i.id !== "stick" &&
    w.x + w.w / 2 >= i.x && w.x + w.w / 2 <= i.x + i.w && w.y + w.h / 2 >= i.y && w.y + w.h / 2 <= i.y + i.h));
  const overlapped = !c ? [] : (s?.widgets || []).filter(w => w.active && w.x !== null && w.w > 0 && w.w < v.w * 0.35 && c.items.some(i => i.id !== "stick" &&
    Math.min(w.x + w.w, i.x + i.w) - Math.max(w.x, i.x) > 4 && Math.min(w.y + w.h, i.y + i.h) - Math.max(w.y, i.y) > 4));
  report.measurements[`widgetsCovered:${tag}`] = {covered: covered.map(w => w.text), overlapped: overlapped.map(w => w.text)};
  verdict(`widgetsNotUnderTouchButtons:${tag}`, overlapped.length === 0, overlapped.map(w => `${w.type}:${w.text} at ${Math.round(w.x)},${Math.round(w.y)} ${Math.round(w.w)}x${Math.round(w.h)}`));
}
async function waitForScreen(pattern, ms, label) {
  await waitFor(`${pattern}.test(${screenExpr})`, ms, label);
}

// ---------------------------------------------------------------- run
const profileDir = await mkdtemp(join(out, "chrome-profile-"));
const port = await freePort();
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${port}`, "--remote-allow-origins=*",
  `--user-data-dir=${profileDir}`, `--window-size=${device.width},${device.height}`, "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader",
  ...chromeIsolationArgs(), "about:blank"], {stdio: "ignore"});
const exceptions = [];
let fatal = null;
const step = async (name, fn, {critical = false} = {}) => {
  await log("step", name);
  try { return await fn(); } catch (e) {
    await fail(`step:${name}`, String(e.message || e).slice(0, 600));
    await snap(`failed-${name.replace(/\W+/g, "-")}`);
    if (critical) { fatal = e; throw e; }
    return undefined;
  }
};
try {
  let targets;
  for (let i = 0; i < 80; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); if (targets.length) break; } catch {} await sleep(250); }
  cdp = new Cdp(targets.find(t => t.type === "page").webSocketDebuggerUrl); await cdp.open();
  const fmt = args => (args || []).map(a => a.value !== undefined ? (typeof a.value === "string" ? a.value : JSON.stringify(a.value)) : (a.description || a.type)).join(" ");
  cdp.on("Runtime.consoleAPICalled", (p, sid) => log(`console.${p.type}${sid ? "[worker]" : ""}`, fmt(p.args).slice(0, 3000)));
  cdp.on("Runtime.exceptionThrown", (p, sid) => { const d = p.exceptionDetails; const t = (d.exception?.description || d.text || "").slice(0, 3000); exceptions.push({worker: !!sid, t}); log(`EXCEPTION${sid ? "[worker]" : ""}`, t); });
  cdp.on("Log.entryAdded", (p, sid) => log(`log.${p.entry.level}${sid ? "[worker]" : ""}`, `${p.entry.source} ${String(p.entry.text).slice(0, 1500)} ${p.entry.url || ""}`));
  cdp.on("Target.attachedToTarget", async p => {
    try { await cdp.send("Runtime.runIfWaitingForDebugger", {}, p.sessionId); } catch {}
    try { await cdp.send("Runtime.enable", {}, p.sessionId); await cdp.send("Log.enable", {}, p.sessionId);
      await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true}, p.sessionId); } catch {}
  });
  await cdp.send("Runtime.enable"); await cdp.send("Page.enable"); await cdp.send("Log.enable");
  await cdp.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: false, flatten: true});

  // Device: metrics, touch, Android user agent, navigator.deviceMemory.
  const version = await cdp.send("Browser.getVersion");
  const chromeVersion = /Chrome\/([\d.]+)/.exec(version.userAgent)?.[1] || ["130", "0", "0", "0"].join(".");
  const major = chromeVersion.split(".")[0];
  const ua = `Mozilla/5.0 (Linux; Android 14; ${device.model}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeVersion} ${deviceName === "phone" ? "Mobile " : ""}Safari/537.36`;
  await cdp.send("Emulation.setUserAgentOverride", {userAgent: ua, platform: "Linux armv81", userAgentMetadata: {
    brands: [{brand: "Chromium", version: major}, {brand: "Google Chrome", version: major}], fullVersion: chromeVersion,
    platform: "Android", platformVersion: "14.0.0", architecture: "", model: device.model, mobile: deviceName === "phone"}});
  await setOrientation("portrait");
  await cdp.send("Emulation.setTouchEmulationEnabled", {enabled: true, maxTouchPoints: 5});
  await cdp.send("Page.addScriptToEvaluateOnNewDocument", {source: `(()=>{try{Object.defineProperty(Navigator.prototype,'deviceMemory',{configurable:true,get(){return ${deviceMemory}}});}catch(e){}
    try{localStorage.setItem('gaius.playerName','MobileCheck');}catch(e){} })();`});
  report.userAgent = ua;
  await log("navigate", pageUrl);
  await cdp.send("Page.navigate", {url: pageUrl});

  // ---- 1. boot (portrait)
  await step("boot", async () => {
    const end = Date.now() + bootTimeout;
    let s = null;
    while (Date.now() < end) {
      s = await getState();
      if (s?.screen && /TitleScreen|BrowserProfileScreen/.test(s.screen)) break;
      if (logLines.some(l => l.includes("Game crashed!"))) throw new Error("Game crashed during boot");
      await sleep(1000);
    }
    if (!s?.screen) throw new Error("title screen not reached");
    if (/BrowserProfileScreen/.test(s.screen)) { await tapWidget("Done", 10000).catch(() => key("Escape", "Escape", 27)); await waitForScreen(/TitleScreen/, 20000, "title after profile"); }
    // The title screen exists before the launcher's boot overlay (resource loading) goes away; touch
    // controls and the viewport fit only start once the shell shows the canvas.
    await waitFor(`(()=>{const v=document.documentElement.dataset.gaiusShellView;if(v)return v==='canvas';const b=document.getElementById('boot-screen');return !b||b.hidden||getComputedStyle(b).display==='none';})()`, bootTimeout, "boot overlay gone");
    await sleep(2500);
    pass("bootsToTitle", {seconds: Math.round((Date.now() - t0) / 1000)});
  }, {critical: true});

  await step("title-portrait", async () => {
    const s = await getState();
    report.measurements.touchAtTitle = s.touch;
    await snap("title-portrait");
    verdict("touchControlsDetected", !!s.touch?.enabled, {touch: s.touch, note: "auto detection at load (coarse pointer) or by the first touch"});
    const ua2 = await evaluate("({maxTouchPoints:navigator.maxTouchPoints,coarse:matchMedia('(pointer: coarse)').matches,hover:matchMedia('(hover: none)').matches,deviceMemory:navigator.deviceMemory,mobile:navigator.userAgentData?.mobile,viewport:innerWidth+'x'+innerHeight,dpr:devicePixelRatio})");
    report.measurements.emulation = ua2;
    await checkFit("title-portrait");
    await checkScreenReachable("title-portrait");
    const c = await evaluate(CONTROLS);
    report.measurements.titleControls = c && {mode: c.mode, ids: c.items.map(i => i.id), rotateHint: c.rotateHint};
    verdict("controlsOverlayAtTitle", !!c && c.rootDisplay !== "none" && c.mode === "gui", c && {mode: c.mode, ids: c.items.map(i => i.id), rotateHint: c.rotateHint});
    await checkControls("title-portrait", ["back", "settings"]);
    const tier = await evaluate("({memoryTier:window.__gaiusMemoryTier||null,quality:window.__gaiusQualitySettings?{tier:window.__gaiusQualitySettings.tier,postTier:window.__gaiusQualitySettings.postTier,maxDpr:window.__gaiusQualitySettings.maxDpr,renderScale:window.__gaiusQualitySettings.renderScale,renderScaleMode:window.__gaiusQualitySettings.renderScaleMode}:null,runtime:window.__gaiusRuntimeSelection?{selected:window.__gaiusRuntimeSelection.selected,reason:window.__gaiusRuntimeSelection.reason}:null,gpu:window.__gaiusGpuSelection?window.__gaiusGpuSelection.selected:null,caps:window.__gaiusGpuCaps?{tier:window.__gaiusGpuCaps.tier,source:window.__gaiusGpuCaps.source,renderer:window.__gaiusGpuCaps.renderer,mobile:window.__gaiusGpuCaps.mobile,deviceMemory:window.__gaiusGpuCaps.deviceMemory}:null})");
    report.measurements.quality = tier;
    if (deviceMemory <= 4 && tier.runtime?.selected === "wasm") {
      const low = tier.quality?.tier === "low" && tier.memoryTier?.chosenTier === "low";
      verdict("lowMemoryDefaultTierIsLow", low, tier);
    } else if (deviceMemory <= 4 && tier.runtime?.selected === "js") {
      const gpuTier = tier.memoryTier?.gpuTier;
      const jsFallback = tier.quality?.tier === gpuTier && tier.memoryTier?.chosenTier === gpuTier
        && tier.memoryTier?.lowMemory === false;
      verdict("jsFallbackKeepsGpuTier", jsFallback, tier);
    } else {
      pass("qualityTierRecorded", tier);
    }
  });

  // ---- 2. title by touch
  await step("singleplayer-portrait", async () => {
    await tapWidget("Singleplayer", 10000);
    await waitForScreen(/SelectWorldScreen|CreateWorldScreen/, 60000, "world screen");
    await sleep(900);
    await snap("select-world-portrait");
    pass("tapSingleplayer", {screen: (await getState()).screen});
    await checkScreenReachable("select-world-portrait");
  }, {critical: true});

  await step("rotate-landscape-menu", async () => {
    await setOrientation("landscape");
    await fitAfterResize("menu-landscape");
    await sleep(500);
    await snap("select-world-landscape");
    await checkScreenReachable("select-world-landscape");
    await checkControls("menu-landscape", ["back", "settings"]);
  });

  await step("create-world", async () => {
    let s = await getState();
    if (/SelectWorldScreen/.test(s.screen)) {
      await tapWidget("Create New World", 15000);
      await waitForScreen(/CreateWorldScreen/, 60000, "create world screen");
    }
    await sleep(900);
    await snap("create-world");
    await checkScreenReachable("create-world");
    let modeText = null;
    for (let i = 0; i < 4; i++) {
      const w = await findWidget({text: "Game Mode"}, 3000); modeText = w?.text;
      if (!w || /creative/i.test(w.text)) break;
      await tapAt(w.x + w.w / 2, w.y + w.h / 2); await sleep(500);
    }
    verdict("tapSelectsCreative", /creative/i.test(modeText || "") || /creative/i.test((await findWidget({text: "Game Mode"}, 2000))?.text || ""), {modeText});
    // Seed through the virtual keyboard: World tab, tap the seed field, insertText (what a soft keyboard sends).
    let seedOk = false;
    try {
      report.measurements.createScreenWidgets = (await getState()).widgets.map(w => `${w.type}:${w.text}@${Math.round(w.x)},${Math.round(w.y)} ${Math.round(w.w)}x${Math.round(w.h)}`);
      const tab = await findWidget({text: "World", exact: true}, 3000);
      if (tab) { await tapAt(tab.x + tab.w / 2, tab.y + tab.h / 2); await sleep(600); }
      else { const v = viewportSize(); await tapAt(v.w * 0.5, v.h * 0.034); report.measurements.worldTabByPixel = true; await sleep(600); }
      const seedBox = w => w.active && (/EditBox$/.test(w.type) || /WorldTab\$1$/.test(w.type));
      await sleep(400);
      const edit = (await getState()).widgets.find(seedBox);
      if (edit) {
        await tapAt(edit.x + edit.w / 2, edit.y + edit.h / 2); await sleep(500);
        const kb = await evaluate("({active:document.activeElement&&document.activeElement.id,focusedWidget:(window.__gaiusMinecraftState?.screenWidgets||[]).some(w=>w.focused)})");
        report.measurements.seedKeyboard = kb;
        await cdp.send("Input.insertText", {text: seed}); await sleep(600);
        const after = (await getState()).widgets.find(seedBox);
        report.measurements.seedField = after?.value ?? after?.text;
        seedOk = !!after && Object.prototype.hasOwnProperty.call(after, "value") && after.value === seed;
      }
      await snap("create-world-seed");
    } catch (e) { report.measurements.seedError = String(e); }
    verdict("virtualKeyboardTypesIntoField", seedOk, {field: report.measurements.seedField, keyboard: report.measurements.seedKeyboard, error: report.measurements.seedError ?? null});
    // Back to the Game tab for the Create button (tabs hide it on none; the button is global in 26.x).
    const gameTab = await findWidget({text: "Game", exact: true}, 2000);
    if (gameTab) { await tapAt(gameTab.x + gameTab.w / 2, gameTab.y + gameTab.h / 2); await sleep(500); }
    else if (report.measurements.worldTabByPixel) { const v = viewportSize(); await tapAt(v.w * 0.365, v.h * 0.034); await sleep(500); }
    await tapWidget("Create New World", 10000);
    pass("tapCreateWorld", true);
  }, {critical: true});

  await step("enter-world", async () => {
    const end = Date.now() + worldTimeout;
    let lastScreen = "";
    while (Date.now() < end) {
      const s = await getState();
      const sig = `${s?.screen}|${s?.level}|${!!s?.player}`;
      if (sig !== lastScreen) { lastScreen = sig; await log("state", sig); }
      if (s?.level && !s?.screen && s?.player) break;
      if (logLines.some(l => l.includes("Game crashed!"))) throw new Error("Game crashed on world entry");
      if (/DisconnectedScreen|ErrorScreen|AlertScreen/.test(String(s?.screen))) throw new Error("error screen " + s.screen);
      await sleep(500);
    }
    if (!(await evaluate(inWorldExpr))) throw new Error("world not entered");
    await waitFor("(window.__gaiusMinecraftState?.loadedChunkCount||0)>=9", 180000, "chunks").catch(() => {});
    await sleep(4000);
    await snap("world-landscape");
    pass("worldEntered", {state: (await getState()).touch});
  }, {critical: true});

  // ---- 3. in the world
  await step("world-layout", async () => {
    await checkFit("world-landscape");
    await checkControls("world-landscape", ["jump", "sneak", "attack", "use", "pause", "chat", "inventory", "stick"]);
    const st = await getState();
    verdict("touchModeWorld", st.touch?.mode === "world", st.touch);
    const hb = await evaluate("(()=>{const t=window.__gaiusTouch;const h=t&&t.hotbar&&t.hotbar();const v={w:innerWidth,h:innerHeight};return {hotbar:h,view:v};})()");
    report.measurements.hotbar = hb;
    verdict("hotbarOnScreen", !!hb.hotbar && hb.hotbar.x >= 0 && hb.hotbar.x + hb.hotbar.w <= hb.view.w + 1 && hb.hotbar.y + hb.hotbar.h <= hb.view.h + 1, hb);
  });

  const typeCommand = async text => {
    const before = logLines.length;
    await tapControl("chat");
    await waitForScreen(/ChatScreen/, 6000, "chat screen");
    await sleep(250);
    const kb = await evaluate("document.activeElement&&document.activeElement.id");
    await cdp.send("Input.insertText", {text}); await sleep(300);
    await key("Enter", "Enter", 13);
    const end = Date.now() + 6000;
    while (Date.now() < end && /ChatScreen/.test(await evaluate(screenExpr))) await sleep(100);
    await sleep(500);
    return {focused: kb, closed: !/ChatScreen/.test(await evaluate(screenExpr)), lines: logLines.slice(before).filter(l => /CHAT/.test(l)).slice(0, 3)};
  };
  await step("chat-commands", async () => {
    const r1 = await typeCommand("/item replace entity @s hotbar.0 with minecraft:stone 64");
    const r2 = await typeCommand("/item replace entity @s hotbar.1 with minecraft:dirt 64");
    report.measurements.chat = {r1, r2};
    verdict("chatThroughVirtualKeyboard", r1.closed && r2.closed && r1.focused === "gaius-touch-text", {r1, r2});
    await snap("after-chat-commands");
  });

  await step("hotbar-tap", async () => {
    const hb = await evaluate("(()=>{const h=window.__gaiusTouch.hotbar();return h?{x:h.x,y:h.y,w:h.w,h:h.h}:null;})()");
    const slot = i => ({x: hb.x + (1 + 20 * i + 10) * hb.w / 182, y: hb.y + hb.h / 2});
    await tapAt(slot(1).x, slot(1).y); await sleep(700);
    const a = (await getState()).player?.item;
    await snap("hotbar-slot-2");
    await tapAt(slot(0).x, slot(0).y); await sleep(700);
    const b = (await getState()).player?.item;
    verdict("hotbarTapSelectsSlot", /dirt/.test(JSON.stringify(a)) && /stone/.test(JSON.stringify(b)), {slot2: a, slot1: b});
  });

  await step("inventory-button", async () => {
    await tapControl("inventory");
    await waitForScreen(/(?:CreativeMode)?InventoryScreen$/, 8000, "inventory screen");
    await sleep(300);
    const opened = await getState();
    verdict("inventoryButtonOpensInventory", /(?:CreativeMode)?InventoryScreen$/.test(opened?.screen || ""), {screen: opened?.screen});
    await snap("inventory-open");
    await checkControls("inventory-open", ["back", "settings"]);
    await tapControl("back");
    await waitFor(inWorldExpr, 8000, "world after inventory");
    pass("inventoryBackButtonClosesScreen", true);
  });

  const play = {};
  const stickLayout = () => evaluate("(()=>{const l=window.__gaiusTouch.layout();return {cx:l.stick.cx,cy:l.stick.cy,r:l.stick.r};})()");
  // Push the joystick in a direction for ms and return the horizontal distance and the key set the game saw.
  const stickRun = async (dir, ms) => {
    const lay = await stickLayout();
    const dx = {up: 0, down: 0, left: -1, right: 1}[dir], dy = {up: -1, down: 1, left: 0, right: 0}[dir];
    const p0 = (await getState()).player;
    await touchDown(3, lay.cx, lay.cy);
    for (let i = 1; i <= 6; i++) { await touchMove(3, lay.cx + dx * lay.r * 0.75 * i / 6, lay.cy + dy * lay.r * 0.75 * i / 6); await sleep(30); }
    await sleep(ms);
    const mid = await evaluate("(()=>{const i=window.__gaiusInput;return i&&i.state?i.state():null;})()").catch(() => null);
    await touchUp(3); await sleep(500);
    const p1 = (await getState()).player;
    return {dir, distance: +Math.hypot(p1.x - p0.x, p1.z - p0.z).toFixed(2), from: [p0.x, p0.y, p0.z], to: [p1.x, p1.y, p1.z], input: mid};
  };
  // Teleport to y=200 (open air: no tree or wall can block the walk) and run all four directions.
  const skyMove = async (tag, dirs = ["up", "down", "left", "right"]) => {
    const runs = [];
    let r = null;
    for (const d of dirs) {
      // A fresh teleport per direction: the fall from the previous one must not reach a hill or a hole.
      r = await typeCommand("/tp @s ~ 200 ~");
      await sleep(900);
      runs.push(await stickRun(d, 900));
    }
    report.measurements[`stick:${tag}`] = {tp: r, runs};
    verdict(`joystickMovesPlayer:${tag}`, runs.every(x => x.distance > 0.8), runs.map(x => `${x.dir}=${x.distance}`));
  };
  await step("joystick-move", async () => {
    await skyMove("landscape");
    await snap("joystick-sky");
    // Fall back to the ground before the next steps.
    await sleep(6000);
  });

  await step("look-drag", async () => {
    const v = await evaluate("({w:innerWidth,h:innerHeight})");
    const a = (await getState()).player;
    await dragPath(4, {x: v.w * 0.55, y: v.h * 0.4}, {x: v.w * 0.55 + 140, y: v.h * 0.4}, 14);
    await touchUp(4); await sleep(500);
    const b = (await getState()).player;
    const dyaw = Math.abs(((b.yaw - a.yaw + 540) % 360) - 180);
    verdict("lookDragTurnsCamera", Math.abs(b.yaw - a.yaw) > 5, {yawBefore: a.yaw, yawAfter: b.yaw, pitch: b.pitch});
    await snap("after-look");
  });

  await step("multitouch-move-look", async () => {
    const lay = await stickLayout();
    const v = await evaluate("({w:innerWidth,h:innerHeight})");
    await typeCommand("/tp @s ~ 200 ~");
    await sleep(700);
    const before = (await getState()).player;
    const look = {x: v.w * 0.55, y: v.h * 0.42};
    await touchDown(7, lay.cx, lay.cy);
    await touchDown(8, look.x, look.y);
    for (let i = 1; i <= 8; i++) {
      await touchMove(7, lay.cx, lay.cy - lay.r * 0.7 * i / 8);
      await touchMove(8, look.x + 96 * i / 8, look.y);
      await sleep(30);
    }
    await sleep(850);
    await touchUp(8);
    await touchUp(7);
    await sleep(500);
    const after = (await getState()).player;
    const distance = Math.hypot(after.x - before.x, after.z - before.z);
    const turn = Math.abs(((after.yaw - before.yaw + 540) % 360) - 180);
    verdict("multitouchMovesAndLooks", distance > 0.8 && turn > 5,
      {distance: +distance.toFixed(2), yawBefore: before.yaw, yawAfter: after.yaw, turn: +turn.toFixed(1)});
    await snap("multitouch-move-look");
  });

  await step("jump", async () => {
    const a = (await getState()).player;
    let maxY = a.y;
    const r = await controlRect("jump"); const p = center(r);
    await touchDown(5, p.x, p.y);
    const end = Date.now() + 700;
    while (Date.now() < end) { const s = await getState(); if (s?.player) maxY = Math.max(maxY, s.player.y); await sleep(40); }
    await touchUp(5); await sleep(900);
    verdict("jumpButtonJumps", maxY - a.y > 0.4, {groundY: a.y, maxY, rise: +(maxY - a.y).toFixed(2)});
  });

  await step("fly-break-place", async () => {
    // Double tap on jump toggles creative flight; hover, look straight down, break and place.
    // Flying: holding jump keeps climbing (2+ blocks in 0.8 s); standing, a held jump is one 1.25 block hop.
    // A failed attempt is repeated once (a stalled frame can swallow the double tap).
    const attempts = [];
    let flying = false;
    // Timings (press ms, gap ms): the software-rendered game can run below 10 frames/s, and a press that
    // is released between two frames is never seen by the game.
    const timings = [[90, 120], [160, 120], [240, 80]];
    for (let attempt = 0; attempt < timings.length && !flying; attempt++) {
      if (attempt > 0) await sleep(2000);
      const [press, gap] = timings[attempt];
      await tapControl("jump", press); await sleep(gap); await tapControl("jump", press);
      await sleep(1500);
      const y0 = (await getState()).player?.y;
      await holdControl("jump", 800);
      const y1 = (await getState()).player?.y;
      await sleep(1500);
      const y2 = (await getState()).player?.y;
      await sleep(600);
      const y3 = (await getState()).player?.y;
      flying = y1 - y0 > 2 && Math.abs(y3 - y2) < 0.15;
      attempts.push({press, gap, y0, afterHold: y1, settling: y2, hovering: y3, fps: await evaluate('window.__gaiusFps?window.__gaiusFps.fps:null').catch(() => null)});
    }
    verdict("doubleTapJumpFlies", flying, attempts);
    const v = await evaluate("({w:innerWidth,h:innerHeight})");
    for (let i = 0; i < 4; i++) {
      const pitch = (await getState()).player?.pitch;
      if (pitch >= 80) break;
      await dragPath(6, {x: v.w * 0.55, y: v.h * 0.2}, {x: v.w * 0.55, y: v.h * 0.55}, 14); await touchUp(6); await sleep(400);
    }
    // A full dirt block two blocks under the hovering player, so the crosshair rests on a reachable full block.
    // The target is computed from the current view ray (4.4 blocks from the eye, so the stone placed on top clears the player body), not from the feet column.
    for (let attempt = 0; attempt < 3; attempt++) {
      const p = (await getState()).player;
      const rad = Math.PI / 180, yaw = p.yaw * rad, pitch = p.pitch * rad;
      const eye = {x: p.x, y: p.y + 1.62, z: p.z};
      const t = {x: Math.floor(eye.x - Math.sin(yaw) * Math.cos(pitch) * 4.4), y: Math.floor(eye.y - Math.sin(pitch) * 4.4), z: Math.floor(eye.z + Math.cos(yaw) * Math.cos(pitch) * 4.4)};
      await typeCommand(`/setblock ${t.x} ${t.y} ${t.z} minecraft:dirt`);
      await sleep(1000);
      const hit = (await getState()).hit;
      report.measurements[`dirtTarget${attempt}`] = {target: t, player: p, hit};
      if (hit?.type === "BLOCK" && /dirt/.test(String(hit.blockState))) break;
    }
    await sleep(1200);
    const h0 = await getState();
    await snap("before-break");
    play.pitch = h0.player?.pitch;
    // Place first: the block under the player is reachable, a placed stone sits on top of it; then break it again.
    let h1 = null;
    const useTaps = [];
    for (const hold of [90, 160, 240]) {
      await tapControl("use", hold);
      for (const end = Date.now() + 4000; Date.now() < end;) { h1 = await getState(); if (h1.hit && h0.hit && (h1.hit.blockPos !== h0.hit.blockPos || h1.hit.blockState !== h0.hit.blockState)) break; await sleep(150); }
      useTaps.push(hold);
      if (h1?.hit && h0.hit && (h1.hit.blockPos !== h0.hit.blockPos || h1.hit.blockState !== h0.hit.blockState)) break;
    }
    report.measurements.useTaps = useTaps;
    await snap("after-place");
    const placed = h0.hit?.type === "BLOCK" && h1?.hit && /stone/i.test(String(h1.hit.blockState)) && !/stone/i.test(String(h0.hit.blockState));
    verdict("useButtonPlacesBlock", !!placed, {pitch: h0.player?.pitch, before: h0.hit, after: h1?.hit, item: h1?.player?.item});
    const breakPoint = {x: v.w * 0.55, y: v.h * 0.6};
    await touchDown(9, breakPoint.x, breakPoint.y);
    await sleep(900);
    await touchUp(9);
    let h2 = null;
    for (const end = Date.now() + 6000; Date.now() < end;) { h2 = await getState(); if (h2.hit && h1?.hit && (h2.hit.blockPos !== h1.hit.blockPos || h2.hit.blockState !== h1.hit.blockState)) break; await sleep(150); }
    await snap("after-break");
    const broke = h1?.hit?.type === "BLOCK" && h2?.hit && !/stone/i.test(String(h2.hit.blockState));
    verdict("longPressBreaksBlock", !!broke, {before: h1?.hit, after: h2?.hit});
    // Look back to the horizon and land (double tap again) for the rest of the run.
    for (let i = 0; i < 4; i++) {
      const pitch = (await getState()).player?.pitch;
      if (pitch <= 15) break;
      await dragPath(6, {x: v.w * 0.55, y: v.h * 0.6}, {x: v.w * 0.55, y: v.h * 0.2}, 14); await touchUp(6); await sleep(400);
    }
  });

  // ---- 4. pause, back, rotation in the world
  await step("pause-button", async () => {
    await tapControl("pause");
    await waitForScreen(/PauseScreen/, 8000, "pause screen");
    await sleep(900);
    await snap("pause-landscape");
    const s = await getState();
    pass("pauseButtonOpensPauseScreen", {screen: s.screen, widgets: s.widgets.map(w => w.text).filter(Boolean)});
    await checkScreenReachable("pause-landscape");
    await checkControls("pause-landscape", ["back", "settings"]);
    await tapControl("back");
    await waitFor(inWorldExpr, 8000, "world after back");
    verdict("backButtonClosesPause", true, {screen: (await getState()).screen});
    await snap("after-back");
  });

  await step("rotate-in-world", async () => {
    await setOrientation("portrait");
    await fitAfterResize("world-portrait");
    await sleep(700);
    await snap("world-portrait");
    await checkControls("world-portrait", ["jump", "attack", "use", "pause", "stick"]);
    const c = await evaluate(CONTROLS);
    verdict("rotateHintShownInPortraitPhone", deviceName === "phone" ? c.rotateHint === true : c.rotateHint === false, {rotateHint: c.rotateHint, device: deviceName});
    await skyMove("portrait", ["up", "right"]);
    await setOrientation("landscape");
    await fitAfterResize("world-landscape-2");
    await sleep(700);
    await snap("world-landscape-2");
    await checkControls("world-landscape-2", ["jump", "attack", "use", "pause", "stick"]);
    await setOrientation("portrait"); await fitAfterResize("world-portrait-2");
    await setOrientation("landscape"); await fitAfterResize("world-landscape-3");
    await sleep(800); // the controls follow the resize event; nobody taps within a frame of rotating
  });

  // ---- 5. save and quit, re-enter
  await step("save-and-quit", async () => {
    await tapControl("pause");
    await waitForScreen(/PauseScreen/, 8000, "pause screen");
    await sleep(700);
    await tapWidget("Save and Quit to Title", 10000);
    await waitFor(`${screenExpr}.endsWith('TitleScreen')&&!window.__gaiusMinecraftState?.level`, 120000, "title after save");
    await waitFor("(()=>{const w=globalThis.__gaiusSingleplayerWorkers;if(!w||typeof w.values!=='function')return true;return [...w.values()].every(x=>x.__gaiusStopped||x.__gaiusTerminal);})()", 120000, "worker stopped").catch(() => {});
    await sleep(1500);
    await snap("title-landscape");
    pass("saveAndQuitByTouch", true);
    await fitAfterResize("title-landscape-after-quit");
    await checkScreenReachable("title-landscape");
  }, {critical: true});

  await step("re-enter", async () => {
    await tapWidget("Singleplayer", 10000);
    await waitForScreen(/SelectWorldScreen/, 60000, "select world");
    let entry = null;
    for (const end = Date.now() + 20000; Date.now() < end;) {
      entry = await evaluate(`(()=>{const s=window.__gaiusMinecraftState||{};const f=s.worldSelection&&s.worldSelection.first;const c=document.querySelector('canvas');const r=c&&c.getBoundingClientRect();const z=s.screenSize;if(!f||!r||!z)return null;
        return {name:f.name,count:s.worldSelection.count,x:r.left+(Number(f.x)+Number(f.width)/2)*r.width/Number(z.width),y:r.top+(Number(f.y)+Number(f.height)/2)*r.height/Number(z.height)};})()`);
      if (entry) break; await sleep(250);
    }
    await sleep(500);
    await snap("world-list");
    if (!entry) throw new Error("world list is empty after Save and Quit");
    pass("worldListedAfterQuit", entry);
    await tapAt(entry.x, entry.y); await sleep(500);
    if (!(await evaluate("!!window.__gaiusMinecraftState?.level"))) await tapWidget("Play Selected World", 5000);
    const start = Date.now();
    for (const end = Date.now() + worldTimeout; Date.now() < end;) {
      const s = await getState();
      if (s?.level && !s?.screen && s?.player) break;
      if (logLines.some(l => l.includes("Game crashed!"))) throw new Error("Game crashed on re-entry");
      if (/DisconnectedScreen|ErrorScreen|AlertScreen/.test(String(s?.screen))) throw new Error("error screen " + s.screen);
      await sleep(250);
    }
    if (!(await evaluate(inWorldExpr))) throw new Error("world not re-entered");
    report.measurements.reenterSeconds = (Date.now() - start) / 1000;
    // The recent bug: the game restored its startup window size without a browser resize event.
    for (const [tag, wait] of [["reenter+0s", 0], ["reenter+1s", 1000], ["reenter+3s", 2000], ["reenter+8s", 5000]]) {
      await sleep(wait);
      const f = await evaluate(FIT); const problems = fitVerdict(f);
      report.measurements[`fit:${tag}`] = {fit: f, problems};
      await log("fit", `${tag} ${problems.length ? problems.join("; ") : "ok"}`);
      if (tag === "reenter+1s") await snap("reentered-landscape");
    }
    const bad = ["reenter+0s", "reenter+1s", "reenter+3s", "reenter+8s"].filter(t => report.measurements[`fit:${t}`].problems.length);
    verdict("canvasFitsAfterReentry", bad.length === 0, bad.length ? bad.map(t => ({at: t, problems: report.measurements[`fit:${t}`].problems})) : "fits at +0/1/3/8 s");
    await checkControls("reentered-landscape", ["jump", "attack", "use", "pause", "stick"]);
    await waitFor("(window.__gaiusMinecraftState?.loadedChunkCount||0)>=9", 120000, "chunks after re-entry").catch(() => {});
    await sleep(2000);
    await skyMove("after-reentry", ["up", "left"]);
    await tapControl("pause"); await waitForScreen(/PauseScreen/, 8000, "pause after re-entry");
    pass("pauseButtonAfterReentry", true);
    await tapControl("back"); await waitFor(inWorldExpr, 8000, "world after back");
  }, {critical: true});

  await step("rotate-after-reentry", async () => {
    await setOrientation("portrait");
    await fitAfterResize("reentered-portrait");
    await sleep(600);
    await snap("reentered-portrait");
    await checkControls("reentered-portrait", ["jump", "attack", "use", "pause", "stick"]);
    await setOrientation("landscape");
    await fitAfterResize("reentered-landscape-2");
    await sleep(800);
    await snap("reentered-landscape-2");
    const q = await evaluate("({memoryTier:window.__gaiusMemoryTier&&{chosenTier:window.__gaiusMemoryTier.chosenTier,gpuTier:window.__gaiusMemoryTier.gpuTier,reason:window.__gaiusMemoryTier.reason,lowMemory:window.__gaiusMemoryTier.lowMemory},tier:window.__gaiusQualitySettings&&window.__gaiusQualitySettings.tier,stats:window.__gaiusQualityStats?{fps:window.__gaiusQualityStats.fps,renderScale:window.__gaiusQualityStats.renderScale,dpr:window.__gaiusQualityStats.dpr}:null})");
    report.measurements.qualityInWorld = q;
    const selected = await evaluate("window.__gaiusRuntimeSelection?.selected || null");
    if (deviceMemory <= 4 && selected === "wasm") {
      verdict("lowMemoryTierStaysLowInWorld", q.tier === "low", q);
    } else if (deviceMemory <= 4 && selected === "js") {
      const jsFallback = q.memoryTier?.chosenTier === q.memoryTier?.gpuTier
        && q.memoryTier?.lowMemory === false && q.tier === q.memoryTier?.gpuTier;
      verdict("jsFallbackKeepsGpuTierInWorld", jsFallback, q);
    }
  });
} catch (e) {
  report.fatal = String(e.stack || e).slice(0, 1500);
  await log("FATAL", report.fatal);
} finally {
  report.exceptions = exceptions.slice(0, 20);
  const failed = Object.entries(report.checks).filter(([, c]) => !c.ok).map(([n]) => n);
  report.summary = {passed: Object.values(report.checks).filter(c => c.ok).length, failed: failed.length, failedChecks: failed, fatal: report.fatal || null};
  report.finishedAt = new Date().toISOString();
  await writeFile(join(out, "report.json"), JSON.stringify(report, null, 2));
  try { cdp?.ws.close(); } catch {}
  try { chrome.kill(); } catch {}
  server.close();
  await sleep(1500);
  await rm(profileDir, {recursive: true, force: true, maxRetries: 5}).catch(() => {});
  console.log(JSON.stringify(report.summary));
  process.exit(failed.length || report.fatal ? 1 : 0);
}
