// Open to LAN end-to-end check through the invite link, the way two people use it.
//
// One headless Chrome, two isolated browser contexts (Target.createBrowserContext: separate
// storage, cookies and service workers, like two users). Context A creates a world and uses
// Open to LAN; context B opens the invite link A was shown (not a typed address). Then:
// chat both ways, both players see each other's movement, A places and B breaks a block
// (real mouse clicks, each side sees the other's change), B leaves with Disconnect and comes
// back through the link, B's tab is closed without leaving and a new tab rejoins, and
// finally A saves and quits while B is still in the world (B must get a disconnect screen,
// not a frozen world).
//
//   PAGE=portable PORTABLE=<Gaius-26.3.html> OUT=<dir> node port/scripts/lan-invite-two-context-cdp.mjs
//   PAGE=site DIST=<site dir> OUT=<dir> node port/scripts/lan-invite-two-context-cdp.mjs
//
// Environment:
//   PAGE            portable (file:// single file) or site (served on 127.0.0.1:SITE_PORT)
//   PORTABLE, DIST  the page under test
//   RELAY           local (default): starts apps/bridge on loopback and Chrome cannot reach
//                   anything else; public: the page's built-in relay list, unchanged, with
//                   network access (the relay must accept the page origin: Chrome sends
//                   "file://" for the portable page, http://127.0.0.1:SITE_PORT for site)
//   RELAY_AS        with RELAY=local, how the page learns the local relay: builtin (default,
//                   window.__gaiusBridgeUrls like a shipped relay list, so nothing is saved in
//                   the player's Relays settings) or saved (a user relay in localStorage)
//   GPU             value of the ?gpu= query (default webgpu)
//   SITE_PORT       default 8781; BRIDGE_PORT default 8094
//   PLAYER_A/B      default LanHost / LanGuest
//   BOOT_TIMEOUT_MS / JOIN_TIMEOUT_MS   defaults 600000 / 240000
//   GAIUS_CHROME_BIN
// Exit 0 only when every check passes; OUT/report.json has the checks and evidence.
import {spawn, spawnSync} from "node:child_process";
import {createServer as httpServer} from "node:http";
import {createServer as netServer} from "node:net";
import {existsSync, statSync, createReadStream, writeFileSync} from "node:fs";
import {mkdir, appendFile, writeFile, rm, mkdtemp} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join, resolve, relative, normalize, extname, isAbsolute, dirname} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {chromeIsolationArgs} from "./bench/harness-common.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const env = process.env;
const pageKind = env.PAGE || (env.PORTABLE ? "portable" : "site");
const relayMode = env.RELAY || "local";
const relayAs = env.RELAY_AS || "builtin";
if (!["portable", "site"].includes(pageKind) || !["local", "public"].includes(relayMode)) {
  console.error("PAGE must be portable|site and RELAY local|public"); process.exit(2);
}
if (pageKind === "portable" ? !env.PORTABLE : !env.DIST) {
  console.error("PORTABLE=<Gaius-<profile>.html> or DIST=<site dir> is required"); process.exit(2);
}
const out = resolve(env.OUT || "lan-invite-out");
const names = [env.PLAYER_A || "LanHost", env.PLAYER_B || "LanGuest"];
const sitePort = Number(env.SITE_PORT || 8781), bridgePort = Number(env.BRIDGE_PORT || 8094);
const bootTimeout = Number(env.BOOT_TIMEOUT_MS || 600000), joinTimeout = Number(env.JOIN_TIMEOUT_MS || 240000);
const chromeBin = env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const gpu = env.GPU || "webgpu";
const localBridge = `http://127.0.0.1:${bridgePort}`;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const t0 = Date.now();
const stamp = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(7);

await mkdir(out, {recursive: true});
const logFile = join(out, "console.log");
writeFileSync(logFile, "");
const log = async (kind, text) => appendFile(logFile, `${stamp()} ${kind} ${text}\n`);
const report = {startedAt: new Date().toISOString(), page: pageKind, relay: relayMode, relayAs: relayMode === "local" ? relayAs : null, gpu, players: names,
  checks: {}, steps: [], notes: []};
const check = (name, ok, detail) => {
  report.checks[name] = {ok: !!ok, detail};
  report.steps.push({t: stamp(), step: name, ok: !!ok});
  log("CHECK", `${ok ? "PASS" : "FAIL"} ${name} ${JSON.stringify(detail).slice(0, 800)}`);
  return !!ok;
};

const children = [];
function killTree(child) {
  if (!child || child.exitCode !== null || !child.pid) return;
  if (process.platform === "win32") spawnSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], {stdio: "ignore"});
  else try { child.kill("SIGKILL"); } catch {}
}
async function freePort() {
  const s = netServer(); await new Promise(ok => s.listen(0, "127.0.0.1", ok));
  const p = s.address().port; await new Promise(ok => s.close(ok)); return p;
}

// ------------------------------------------------------------------ page origin and relay
let http = null, baseUrl;
if (pageKind === "site") {
  const dist = resolve(env.DIST);
  const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json",
    ".gz": "application/gzip", ".png": "image/png"};
  http = httpServer((req, res) => {
    const u = new URL(req.url, "http://127.0.0.1");
    let p = normalize(join(dist, decodeURIComponent(u.pathname)));
    const rel = relative(dist, p);
    if (rel.startsWith("..") || isAbsolute(rel)) { res.writeHead(403); return res.end(); }
    if (u.pathname === "/") p = join(dist, "index.html");
    if (!existsSync(p) || statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
    res.writeHead(200, {"Content-Type": MIME[extname(p)] || "application/octet-stream", "Content-Length": statSync(p).size,
      "Cache-Control": "no-store"});
    createReadStream(p).pipe(res);
  });
  await new Promise((ok, bad) => { http.once("error", bad); http.listen(sitePort, "127.0.0.1", ok); });
  baseUrl = `http://127.0.0.1:${sitePort}/index.html`;
} else {
  baseUrl = pathToFileURL(resolve(env.PORTABLE)).href;
}
const pageOrigin = pageKind === "site" ? `http://127.0.0.1:${sitePort}` : "null";
const startUrl = `${baseUrl}?gpu=${encodeURIComponent(gpu)}`;

const bridgeLines = [];
let bridgeProc = null;
if (relayMode === "local") {
  writeFileSync(join(out, "bridge.log"), "");
  bridgeProc = spawn(process.execPath, ["dist/main.js"], {
    cwd: resolve(here, "../../apps/bridge"), stdio: ["ignore", "pipe", "pipe"],
    env: {...env, GAIUS_BRIDGE_HOST: "127.0.0.1", GAIUS_BRIDGE_PORT: String(bridgePort), GAIUS_ALLOWED_ORIGINS: pageOrigin, GAIUS_ALLOW_NULL_ORIGIN: pageOrigin === "null" ? "1" : "",
      GAIUS_ALLOWED_HOSTS: "127.0.0.1,*.gaius-local", GAIUS_BRIDGE_PROTOCOL_DIRECTORY: resolve(here, "../../apps/bridge/protocols")},
  });
  children.push(bridgeProc);
  const sink = tag => data => { for (const l of data.toString("utf8").split(/\r?\n/)) if (l) {
    bridgeLines.push(l); appendFile(join(out, "bridge.log"), `${stamp()} ${tag} ${l}\n`).catch(() => {}); } };
  bridgeProc.stdout.on("data", sink("out")); bridgeProc.stderr.on("data", sink("err"));
  let up = false;
  for (let i = 0; i < 80 && !up; i++) { try { up = (await fetch(`${localBridge}/relay-node/v1`)).ok; } catch {} if (!up) await sleep(250); }
  if (!up) { console.error(`local relay did not start (apps/bridge needs its npm dependencies): ${bridgeLines.slice(0, 4).join(" | ")}`); killTree(bridgeProc); http?.close(); process.exit(2); }
}

// ------------------------------------------------------------------ CDP
class Conn {
  constructor(url) {
    this.ws = new WebSocket(url); this.id = 1; this.pending = new Map(); this.listeners = [];
    this.ws.addEventListener("message", e => {
      const m = JSON.parse(e.data);
      if (m.id && this.pending.has(m.id)) { const {ok, bad} = this.pending.get(m.id); this.pending.delete(m.id); m.error ? bad(new Error(m.error.message)) : ok(m.result); }
      else if (m.method) for (const fn of this.listeners) fn(m);
    });
  }
  open() { return new Promise((ok, bad) => { this.ws.addEventListener("open", ok); this.ws.addEventListener("error", bad); }); }
  send(method, params = {}, sessionId) {
    const id = this.id++;
    return new Promise((ok, bad) => {
      const timer = setTimeout(() => { this.pending.delete(id); bad(new Error(`CDP ${method} timed out`)); }, 15000);
      this.pending.set(id, {ok: v => { clearTimeout(timer); ok(v); }, bad: e => { clearTimeout(timer); bad(e); }});
      this.ws.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}));
    });
  }
}
const profileDir = await mkdtemp(join(tmpdir(), "gaius-lan-"));
const chromePort = await freePort();
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${chromePort}`, "--remote-allow-origins=*",
  `--user-data-dir=${profileDir}`, "--window-size=1280,720", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader",
  "--enable-unsafe-webgpu", "--allow-file-access-from-files",
  "--disable-background-timer-throttling", "--disable-renderer-backgrounding", "--disable-backgrounding-occluded-windows",
  ...chromeIsolationArgs({allowNetwork: relayMode === "public"}), "about:blank"], {stdio: "ignore"});
children.push(chrome);
let conn;
const owner = new Map();
const fmtArgs = args => (args || []).map(a => a.value !== undefined ? (typeof a.value === "string" ? a.value : JSON.stringify(a.value)) : (a.description || a.type)).join(" ");

const STATE = `(()=>{const s=window.__gaiusMinecraftState;if(!s)return null;const p=s.player,h=s.hit;
  return {screen:String(s.screen||''),level:!!s.level,chunks:s.loadedChunkCount,
    player:p?{x:p.x,y:p.y,z:p.z,yaw:p.yaw,pitch:p.pitch,item:p.selectedItem}:null,
    hit:h?{type:h.type,pos:h.blockPos,state:h.blockState}:null,
    widgets:(s.screenWidgets||[]).map(w=>({type:String(w.type||'').split('.').pop(),text:String(w.text||''),active:w.active,visible:w.visible}))}})()`;
const blockNums = pos => (String(pos || "").match(/-?\d+/g) || []).map(Number);

class Page {
  constructor(label, name) { this.label = label; this.name = name; this.lines = []; this.chat = []; this.exceptions = []; this.sockets = []; this.keys = new Set(); }
  async init() {
    const {browserContextId} = await conn.send("Target.createBrowserContext", {disposeOnDetach: true});
    this.contextId = browserContextId;
    if (relayMode === "local" && relayAs === "saved") {
      // A saved user relay is what a person sets in Relay settings; both contexts use it.
      this.preload = `try{localStorage.setItem('gaius.playerName',${JSON.stringify(this.name)});localStorage.setItem('gaius.bridgeNodes',${JSON.stringify(JSON.stringify([{url: localBridge, use: "both"}]))})}catch(e){}`;
    } else if (relayMode === "local") {
      // Stands in for the relay list a build ships with; the player configures nothing.
      this.preload = `window.__gaiusBridgeUrls=[{id:'local-test',name:'Local test relay',url:${JSON.stringify(localBridge)},priority:1000}];try{localStorage.setItem('gaius.playerName',${JSON.stringify(this.name)})}catch(e){}`;
    } else {
      this.preload = `try{localStorage.setItem('gaius.playerName',${JSON.stringify(this.name)})}catch(e){}`;
    }
    await this.openTab("about:blank");
  }
  async openTab(url) {
    const {targetId} = await conn.send("Target.createTarget", {url: "about:blank", browserContextId: this.contextId, newWindow: true});
    this.targetId = targetId;
    const {sessionId} = await conn.send("Target.attachToTarget", {targetId, flatten: true});
    this.sid = sessionId; owner.set(sessionId, this);
    const s = (m, p) => conn.send(m, p, sessionId);
    await s("Runtime.enable"); await s("Page.enable"); await s("Log.enable"); await s("Network.enable");
    await s("Emulation.setDeviceMetricsOverride", {width: 1280, height: 720, deviceScaleFactor: 1, mobile: false});
    await s("Emulation.setFocusEmulationEnabled", {enabled: true});
    await s("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: true, flatten: true});
    await s("Page.addScriptToEvaluateOnNewDocument", {source: this.preload});
    if (url !== "about:blank") await this.navigate(url);
  }
  async navigate(url) { await log("navigate", `${this.label} ${url}`); await this.send("Page.navigate", {url}); }
  async closeTab() { const id = this.targetId; this.sid = null; await conn.send("Target.closeTarget", {targetId: id}).catch(() => {}); }
  send(m, p) { return conn.send(m, p, this.sid); }
  async ev(expr) {
    const r = await Promise.race([this.send("Runtime.evaluate", {expression: expr, awaitPromise: true, returnByValue: true}), sleep(2500).then(() => null)]);
    if (!r) return null;
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result?.value;
  }
  state() { return this.ev(STATE).catch(() => null); }
  async mouse(type, x, y, button = "none", buttons = 0) { await this.send("Input.dispatchMouseEvent", {type, x, y, button, buttons, clickCount: type === "mouseMoved" ? 0 : 1}); }
  async clickAt(x, y, button = "left", holdMs = 50) {
    await this.mouse("mouseMoved", x, y); await sleep(50);
    await this.mouse("mousePressed", x, y, button, button === "right" ? 2 : 1); await sleep(holdMs);
    await this.mouse("mouseReleased", x, y, button, 0);
  }
  async key(name, code, vk, text, modifiers = 0) {
    const base = {key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers};
    await this.send("Input.dispatchKeyEvent", {type: "keyDown", ...base, ...(text ? {text} : {})});
    await this.send("Input.dispatchKeyEvent", {type: "keyUp", ...base});
  }
  async hold(name, code, vk, ms) {
    this.keys.add(code);
    await this.send("Input.dispatchKeyEvent", {type: "rawKeyDown", key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk});
    await sleep(ms);
    await this.send("Input.dispatchKeyEvent", {type: "keyUp", key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk});
    this.keys.delete(code);
  }
  async typeText(text) {
    for (const ch of text) {
      const up = ch.toUpperCase();
      const code = /[a-z]/i.test(ch) ? "Key" + up : /[0-9]/.test(ch) ? "Digit" + ch : ({".": "Period", "-": "Minus", " ": "Space", "/": "Slash", "~": "Backquote", "_": "Minus", "@": "Digit2", "[": "BracketLeft", "]": "BracketRight", "=": "Equal"})[ch] || "";
      const vk = /[a-z0-9]/i.test(ch) ? up.charCodeAt(0) : ({".": 190, "-": 189, " ": 32, "/": 191, "~": 192, "_": 189, "@": 50, "[": 219, "]": 221, "=": 187})[ch] || 0;
      await this.key(ch, code, vk, ch, ch !== ch.toLowerCase() || "_@".includes(ch) ? 8 : 0);
      await sleep(35);
    }
  }
  async widget(match, ms = 30000) {
    const m = JSON.stringify(match), end = Date.now() + ms;
    while (Date.now() < end) {
      const v = await this.ev(`(()=>{const s=window.__gaiusMinecraftState||{};const ws=Array.isArray(s.screenWidgets)?s.screenWidgets:[];const m=${m};const ok=x=>x&&x.visible!==false;let w=null;
        if(m.type)w=ws.find(x=>ok(x)&&String(x.type||'').includes(m.type));
        else{const n=String(m.text).toLowerCase();w=ws.find(x=>ok(x)&&String(x.text||'').trim().toLowerCase()===n)||ws.find(x=>ok(x)&&String(x.text||'').toLowerCase().includes(n));}
        const c=document.querySelector('canvas');const r=c&&c.getBoundingClientRect();const z=s.screenSize;if(!w||!r||!z||!z.width)return null;
        return {text:String(w.text||''),active:w.active!==false,x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height)};})()`).catch(() => null);
      if (v) return v;
      await sleep(250);
    }
    return null;
  }
  async clickWidget(match, ms) {
    const w = await this.widget(typeof match === "string" ? {text: match} : match, ms);
    if (!w) throw new Error(`${this.label}: widget ${JSON.stringify(match)} not found; state=${JSON.stringify(await this.state()).slice(0, 400)}`);
    await this.clickAt(w.x, w.y); return w;
  }
  async screen() { return (await this.state())?.screen || ""; }
  async waitScreen(re, ms) { const end = Date.now() + ms; while (Date.now() < end) { const sc = await this.screen(); if (re.test(sc)) return sc; await sleep(500); } return null; }
  async shot(name) {
    try { const {data} = await this.send("Page.captureScreenshot", {format: "png"}); await writeFile(join(out, `${this.label}-${name}.png`), Buffer.from(data, "base64")); }
    catch (e) { await log("shot-fail", `${this.label} ${name} ${e}`); }
  }
  inWorld(s) { const p = s?.player; return !!(s?.level && p && [p.x, p.y, p.z].every(Number.isFinite) && !s.screen); }
}

function onEvent(m) {
  if (m.method === "Target.attachedToTarget") {
    const pg = owner.get(m.sessionId); if (!pg) return;
    const child = m.params.sessionId; owner.set(child, pg);
    log("target", `${pg.label} ${m.params.targetInfo.type} ${m.params.targetInfo.url}`);
    (async () => {
      try {
        await conn.send("Runtime.enable", {}, child); await conn.send("Network.enable", {}, child).catch(() => {});
        await conn.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: true, flatten: true}, child);
      } catch (e) { log("target-err", String(e)); }
      finally { await conn.send("Runtime.runIfWaitingForDebugger", {}, child).catch(() => {}); }
    })();
    return;
  }
  const pg = owner.get(m.sessionId); if (!pg) return;
  const where = m.sessionId !== pg.sid ? "[worker]" : "";
  const p = m.params || {};
  switch (m.method) {
    case "Runtime.consoleAPICalled": {
      const text = fmtArgs(p.args).slice(0, 3000); pg.lines.push(text);
      log(`${pg.label}.console.${p.type}${where}`, text);
      const chat = /\[CHAT\]\s*(.*)$/.exec(text); if (chat) pg.chat.push({t: Date.now(), text: chat[1]});
      break;
    }
    case "Runtime.exceptionThrown": {
      const d = p.exceptionDetails; const text = (d.exception?.description || d.text || "").slice(0, 1500);
      pg.exceptions.push({t: stamp(), worker: !!where, text}); log(`${pg.label}.EXCEPTION${where}`, text); break;
    }
    case "Log.entryAdded": log(`${pg.label}.log.${p.entry.level}${where}`, `${String(p.entry.text).slice(0, 1500)} ${p.entry.url || ""}`); break;
    case "Page.javascriptDialogOpening":
      log(`${pg.label}.dialog`, `${p.type} ${p.message}`);
      conn.send("Page.handleJavaScriptDialog", {accept: true}, m.sessionId).catch(() => {});
      break;
    case "Network.webSocketCreated": pg.sockets.push({t: stamp(), id: p.requestId, url: p.url, worker: !!where}); log(`${pg.label}.ws.open${where}`, p.url); break;
    case "Network.webSocketHandshakeResponseReceived": log(`${pg.label}.ws.handshake${where}`, `${p.response?.status} ${p.requestId}`); break;
    case "Network.webSocketClosed": log(`${pg.label}.ws.closed${where}`, p.requestId); break;
    case "Network.webSocketFrameError": log(`${pg.label}.ws.error${where}`, `${p.requestId} ${p.errorMessage}`); break;
    default: break;
  }
}

// ------------------------------------------------------------------ scenario helpers
async function bootToTitle(pg) {
  const end = Date.now() + bootTimeout; let last = "";
  while (Date.now() < end) {
    const s = await pg.state(); const sig = s ? s.screen : "<no state>";
    if (sig !== last) { last = sig; await log("state", `${pg.label} ${sig}`); }
    if (s && /BrowserProfileScreen/.test(s.screen)) { await pg.clickWidget("Done", 10000).catch(() => pg.key("Escape", "Escape", 27)); await sleep(1000); continue; }
    if (s && /TitleScreen/.test(s.screen)) return (Date.now() - t0) / 1000;
    await sleep(500);
  }
  throw new Error(`${pg.label}: title screen not reached`);
}
async function hostWorld(pg) {
  await pg.clickWidget("Singleplayer", 20000); await pg.waitScreen(/SelectWorldScreen|CreateWorldScreen/, 20000);
  if (/SelectWorldScreen/.test(await pg.screen())) await pg.clickWidget({text: "Create New World"}, 10000);
  if (await pg.waitScreen(/CreateWorldScreen/, 10000)) {
    await pg.clickWidget({text: "Allow Commands"}, 2500).catch(() => pg.clickWidget({text: "Allow Cheats"}, 2500).catch(() => {}));
    await pg.clickWidget({text: "Create New World"}, 10000).catch(() => pg.clickWidget({text: "Create"}, 10000));
  }
  const end = Date.now() + 300000;
  while (Date.now() < end) { const s = await pg.state(); if (pg.inWorld(s)) return s; await sleep(500); }
  throw new Error("host world did not load");
}
async function pause(pg) {
  const c = await pg.ev("(()=>{const r=document.querySelector('canvas')?.getBoundingClientRect();return r?{x:r.left+r.width/2,y:r.top+r.height/2}:null})()").catch(() => null);
  for (let i = 0; i < 4; i++) {
    if (/PauseScreen/.test(await pg.screen())) return true;
    if (c && i === 0) await pg.clickAt(c.x, c.y, "left", 30).catch(() => {});
    await pg.key("Escape", "Escape", 27);
    if (await pg.waitScreen(/PauseScreen/, 3000)) return true;
  }
  return false;
}
async function openToLan(pg) {
  await pg.send("Page.bringToFront").catch(() => {});
  if (!await pause(pg)) throw new Error("pause screen not reached");
  await pg.shot("pause-menu");
  await pg.clickWidget("Open to LAN", 10000);
  await sleep(800);
  if (/ShareToLanScreen/.test(await pg.screen())) {
    await pg.shot("share-to-lan");
    await pg.clickWidget({text: "Start LAN World"}, 10000);
  }
  const end = Date.now() + 30000;
  while (Date.now() < end) {
    const found = await pg.ev(`(()=>{const i=document.querySelector('#gaius-lan-invite input');if(i&&i.value)return {url:i.value,source:'panel'};
      const es=Array.isArray(window.__gaiusMinecraftEvents)?window.__gaiusMinecraftEvents:[];const e=es.slice().reverse().find(x=>x&&x.event==='lan:opened');
      if(e&&e.detail&&e.detail.url)return {url:String(e.detail.url),source:'event'};
      const u=es.slice().reverse().find(x=>x&&x.event==='lan:unavailable');return u?{error:String(u.detail)}:null;})()`).catch(() => null);
    if (found?.url) { await pg.shot("lan-invite"); return found; }
    if (found?.error) throw new Error(`Open to LAN unavailable: ${found.error}`);
    await sleep(250);
  }
  throw new Error(`no invite after Open to LAN; screen=${await pg.screen()} events=${JSON.stringify(await pg.ev("window.__gaiusMinecraftEvents?.slice(-6)")).slice(0, 600)}`);
}
async function resume(pg) {
  const close = await pg.ev("(()=>{const b=[...document.querySelectorAll('#gaius-lan-invite button')].find(x=>/close|done/i.test(x.textContent));if(!b)return null;const r=b.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()").catch(() => null);
  if (close) await pg.clickAt(close.x, close.y);
  for (let i = 0; i < 3; i++) {
    const sc = await pg.screen(); if (!sc) break;
    if (/PauseScreen/.test(sc)) await pg.clickWidget("Back to Game", 2000).catch(() => pg.key("Escape", "Escape", 27));
    else await pg.key("Escape", "Escape", 27);
    await sleep(500);
  }
}
async function waitInWorld(pg, label, ms = joinTimeout) {
  const end = Date.now() + ms; let last = "", t = Date.now();
  while (Date.now() < end) {
    const s = await pg.state();
    const sig = s ? JSON.stringify([s.screen, s.level, !!s.player]) : "<no state>";
    if (sig !== last) { last = sig; await log("state", `${pg.label} ${label} ${sig}`); }
    if (pg.inWorld(s) && Number(s.player.y) > -64) return {seconds: (Date.now() - t) / 1000, state: s};
    if (/DisconnectedScreen/.test(s?.screen || "")) return {disconnected: (s.widgets || []).map(w => w.text), state: s};
    await sleep(500);
  }
  return {timeout: true, state: await pg.state()};
}
async function joinDirect(pg, address) {
  for (let i = 0; i < 6 && !/TitleScreen|JoinMultiplayerScreen/.test(await pg.screen()); i++) {
    const back = await pg.widget({text: "Back to Server List"}, 500) || await pg.widget({text: "Back to Title Screen"}, 500) || await pg.widget({text: "Back"}, 500);
    if (back) await pg.clickAt(back.x, back.y); else await pg.key("Escape", "Escape", 27);
    await sleep(600);
  }
  if (/TitleScreen/.test(await pg.screen())) await pg.clickWidget("Multiplayer", 15000);
  let sc = await pg.waitScreen(/JoinMultiplayerScreen|Warning|Safety/, 20000) || "";
  if (/Warning|Safety/.test(sc)) { await pg.clickWidget("Proceed", 5000).catch(() => pg.clickWidget("Continue", 5000)); await pg.waitScreen(/JoinMultiplayerScreen/, 15000); }
  await sleep(600);
  await pg.clickWidget("Direct Connect", 15000);
  if (!await pg.waitScreen(/DirectJoinServerScreen/, 15000)) throw new Error(`${pg.label}: Direct Connect screen not reached`);
  await sleep(500);
  const edit = await pg.widget({type: "EditBox"}, 5000);
  if (!edit) throw new Error(`${pg.label}: no address box`);
  await pg.clickAt(edit.x, edit.y); await sleep(200);
  await pg.key("a", "KeyA", 65, "", 2); await pg.key("Backspace", "Backspace", 8);
  await pg.typeText(address); await sleep(500);
  await pg.clickWidget("Join Server", 5000);
}
async function sendChat(pg, text) {
  await pg.key("t", "KeyT", 84, "");
  if (!await pg.waitScreen(/ChatScreen/, 1500)) { await pg.hold("t", "KeyT", 84, 40); }
  if (!await pg.waitScreen(/ChatScreen/, 3000)) throw new Error(`${pg.label}: chat did not open`);
  await sleep(150); await pg.typeText(text); await sleep(150);
  await pg.key("Enter", "Enter", 13);
  const end = Date.now() + 4000; while (Date.now() < end && /ChatScreen/.test(await pg.screen())) await sleep(100);
}
async function waitChat(pg, re, from = 0, ms = 15000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { const c = pg.chat.slice(from).find(x => re.test(x.text)); if (c) return c; await sleep(100); }
  return null;
}
async function command(pg, cmd, re, ms = 10000) {
  const from = pg.chat.length; await sendChat(pg, "/" + cmd); await sleep(300);
  return re ? waitChat(pg, re, from, ms) : null;
}
async function waitFor(fn, ms, every = 150) {
  const end = Date.now() + ms;
  while (Date.now() < end) { const v = await fn().catch(() => null); if (v) return v; await sleep(every); }
  return null;
}
const posOf = text => { const m = /\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/.exec(text || ""); return m ? m.slice(1).map(Number) : null; };
const near = (a, b, tol = 1.5) => a && b && Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]) <= tol;

// ------------------------------------------------------------------ run
let A, B, inviteUrl = null;
const pages = [];
async function cleanup() {
  for (const pg of pages) for (const code of [...pg.keys]) await pg.send("Input.dispatchKeyEvent", {type: "keyUp", code, key: code}).catch(() => {});
  try { conn?.ws.close(); } catch {}
  for (const c of children) killTree(c);
  http?.close();
  await sleep(1500);
  await rm(profileDir, {recursive: true, force: true, maxRetries: 10, retryDelay: 500}).catch(() => {});
}
try {
  for (let i = 0; i < 80 && !conn; i++) {
    try { const j = await (await fetch(`http://127.0.0.1:${chromePort}/json/version`)).json(); conn = new Conn(j.webSocketDebuggerUrl); await conn.open(); }
    catch { conn = null; await sleep(250); }
  }
  if (!conn) throw new Error("cannot connect to Chrome");
  conn.listeners.push(onEvent);
  A = new Page("A", names[0]); B = new Page("B", names[1]); pages.push(A, B);
  await A.init(); await B.init();
  report.contexts = {distinct: A.contextId !== B.contextId};

  // 1. host boots, creates a world, opens it to LAN
  await A.navigate(startUrl);
  report.bootSecondsA = await bootToTitle(A);
  report.hostWorld = (await hostWorld(A)).player;
  await A.shot("host-world");
  const invite = await openToLan(A);
  inviteUrl = invite.url;
  report.invite = invite;
  const inv = new URL(inviteUrl);
  report.workerRelaySockets = A.sockets.filter(x => x.worker).map(x => x.url);
  check("hostInvite", /^client-[a-f0-9]{32}\.gaius-local:25565$/.test(inv.searchParams.get("server") || "") && inv.searchParams.get("gpu") === gpu,
    {invite: inviteUrl, source: invite.source, relay: inv.searchParams.get("relay")});
  await resume(A);

  // 2. guest opens the invite link in its own context
  await B.navigate(inviteUrl);
  const join = await waitInWorld(B, "join", bootTimeout);
  await Promise.all([A.shot("guest-joined"), B.shot("guest-joined")]);
  if (!check("guestJoinsByLink", !join.timeout && !join.disconnected, {seconds: join.seconds, disconnected: join.disconnected, screen: join.state?.screen})) throw new Error("guest did not join");

  // 3. chat both ways
  const ta = `hostsays${Date.now().toString(36)}`, tb = `guestsays${Date.now().toString(36)}`;
  await sendChat(A, ta); const gotB = await waitChat(B, new RegExp(ta));
  await sendChat(B, tb); const gotA = await waitChat(A, new RegExp(tb));
  check("chatBothWays", gotA && gotB, {hostToGuest: gotB?.text || null, guestToHost: gotA?.text || null});

  // 4. A places a block, B breaks it, each with a real click; each side sees the other's change
  const base = (await A.state()).player; const cx = Math.floor(base.x), cz = Math.floor(base.z), py = 200;
  const target = [cx, py, cz + 3], placed = [cx, py + 1, cz + 3];
  const pitch = (Math.atan2(1.62, 3) * 180 / Math.PI).toFixed(1);
  await command(A, "gamemode creative"); await command(B, "gamemode creative");
  await command(A, `fill ${cx - 8} ${py} ${cz - 8} ${cx + 8} ${py} ${cz + 8} minecraft:smooth_stone`, /fill|Fill/);
  await command(A, `give ${names[0]} minecraft:gold_block 8`, /Gave|gave/);
  await command(A, `tp ${names[0]} ${cx + 0.5} ${py + 1} ${cz + 0.5} 0 ${pitch}`, /Teleport/);
  await command(A, `tp ${names[1]} ${cx + 0.5} ${py + 1} ${cz + 6.5} 180 ${pitch}`, /Teleport/);
  const aimed = s => s?.hit && blockNums(s.hit.pos).join() === target.join();
  const posed = await waitFor(async () => { const [a, b] = [await A.state(), await B.state()]; return aimed(a) && aimed(b) ? {a, b} : null; }, 30000, 250);
  if (!posed) report.notes.push("scene: crosshairs not both on the target block");
  await sleep(1000);
  await Promise.all([A.shot("face-to-face"), B.shot("face-to-face")]);
  const center = pg => pg.ev("(()=>{const r=document.querySelector('canvas').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})()");
  const isPlaced = s => s?.hit && blockNums(s.hit.pos).join() === placed.join() && /gold_block/.test(String(s.hit.state));
  const isGone = s => s?.hit && blockNums(s.hit.pos).join() === target.join() && /smooth_stone/.test(String(s.hit.state));
  const cA = await center(A); await A.clickAt(cA.x, cA.y, "right", 80);
  const placeA = await waitFor(async () => isPlaced(await A.state()), 8000, 50), placeB = await waitFor(async () => isPlaced(await B.state()), 12000, 50);
  await Promise.all([A.shot("block-placed"), B.shot("block-placed")]);
  check("hostBlockSeenByGuest", placeA && placeB, {placed, host: !!placeA, guest: !!placeB});
  const cB = await center(B); await B.clickAt(cB.x, cB.y, "left", 150);
  const goneB = await waitFor(async () => isGone(await B.state()), 8000, 50), goneA = await waitFor(async () => isGone(await A.state()), 12000, 50);
  await Promise.all([A.shot("block-broken"), B.shot("block-broken")]);
  check("guestBlockSeenByHost", goneA && goneB, {guest: !!goneB, host: !!goneA});

  // 5. movement on the flat platform: each side strafes; the other side's server view must follow
  const moveSeen = async (walker, viewer) => {
    const before = (await walker.state())?.player;
    await walker.hold("d", "KeyD", 68, 1200); await sleep(1200);
    const after = (await walker.state())?.player;
    const reply = await command(viewer, `data get entity ${walker.name} Pos`, new RegExp(`${walker.name}.*\\[`));
    const seen = posOf(reply?.text);
    const self = after ? [after.x, after.y, after.z] : null;
    return {moved: before && after ? Math.hypot(after.x - before.x, after.z - before.z) : null, self, seenByOther: seen, agree: near(self, seen)};
  };
  const mB = await moveSeen(B, A), mA = await moveSeen(A, B);
  check("movementVisible", mA.agree && mB.agree && mA.moved > 0.5 && mB.moved > 0.5, {host: mA, guest: mB});

  // Steps 6 and 7 record their failure and carry on, so step 8 still runs.
  try {
  // 6. guest leaves with Disconnect, then comes back with Multiplayer > Direct Connect and the
  //    invite's address (what a player on another computer types into their own copy)
  const leaveFrom = A.chat.length;
  await pause(B); await B.clickWidget("Disconnect", 8000);
  const left = await waitFor(async () => { const s = await B.state(); return s && !s.level ? s : null; }, 30000, 250);
  const leftMsg = await waitChat(A, new RegExp(`${names[1]} left the game`), leaveFrom, 15000);
  const hostAfterLeave = await A.state();
  await Promise.all([A.shot("guest-left"), B.shot("guest-left")]);
  check("guestDisconnect", left && leftMsg && hostAfterLeave?.level, {guestScreen: left?.screen, hostSaw: leftMsg?.text || null, hostInWorld: !!hostAfterLeave?.level});
  const rejoinFrom = A.chat.length;
  await joinDirect(B, inv.searchParams.get("server"));
  const rejoin = await waitInWorld(B, "rejoin-direct-connect", joinTimeout);
  const rejoinMsg = await waitChat(A, new RegExp(`${names[1]} joined the game`), rejoinFrom, 20000);
  await Promise.all([A.shot("guest-rejoined"), B.shot("guest-rejoined")]);
  check("guestRejoinAfterDisconnect", !rejoin.timeout && !rejoin.disconnected && rejoinMsg,
    {seconds: rejoin.seconds, disconnected: rejoin.disconnected, screen: rejoin.state?.screen, hostSaw: rejoinMsg?.text || null});

  // 7. guest tab closes without leaving; a new tab in the same context rejoins through the link
  const dropFrom = A.chat.length;
  await B.closeTab();
  const dropMsg = await waitChat(A, new RegExp(`${names[1]} left the game`), dropFrom, 45000);
  check("guestTabClosedHostNotices", dropMsg && (await A.state())?.level, {hostSaw: dropMsg?.text || null});
  const reopenFrom = A.chat.length;
  await B.openTab(inviteUrl);
  const reopen = await waitInWorld(B, "rejoin-new-tab", bootTimeout);
  const reopenMsg = await waitChat(A, new RegExp(`${names[1]} joined the game`), reopenFrom, 20000);
  await Promise.all([A.shot("guest-new-tab"), B.shot("guest-new-tab")]);
  check("guestRejoinNewTab", !reopen.timeout && !reopen.disconnected && reopenMsg,
    {seconds: reopen.seconds, disconnected: reopen.disconnected, screen: reopen.state?.screen, hostSaw: reopenMsg?.text || null});
  const tc = `again${Date.now().toString(36)}`; await sendChat(B, tc);
  check("chatAfterRejoin", await waitChat(A, new RegExp(tc)), {token: tc});
  } catch (e) {
    report.rejoinError = String(e?.stack || e);
    await log("rejoin-error", report.rejoinError);
    if (!report.checks.chatAfterRejoin) check("chatAfterRejoin", false, {error: String(e).slice(0, 300)});
  }

  // 8. host saves and quits while the guest plays: the guest must be told, not left in a dead world
  if (!(await A.state())?.level) throw new Error("host is no longer in its world before the quit step");
  if (!B.sid) await B.openTab(inviteUrl);
  if (!B.inWorld(await B.state())) {
    // A guest that could not rejoin above gets one more try, so the quit step has a guest to tell.
    await B.navigate(inviteUrl); await waitInWorld(B, "before-host-quit", joinTimeout);
  }
  report.guestInWorldBeforeQuit = B.inWorld(await B.state());
  await pause(A); await A.clickWidget("Save and Quit to Title", 8000);
  const tQuit = Date.now();
  const guestEnd = await waitFor(async () => { const s = await B.state(); return s && (/DisconnectedScreen/.test(s.screen) || !s.level) ? s : null; }, 60000, 250);
  const hostTitle = await waitFor(async () => { const s = await A.state(); return s && /TitleScreen/.test(s.screen) && !s.level ? s : null; }, 120000, 500);
  await Promise.all([A.shot("host-quit"), B.shot("host-quit")]);
  check("hostQuitGuestNotified", guestEnd && hostTitle, {guestScreen: guestEnd?.screen || (await B.state())?.screen, guestText: (guestEnd?.widgets || []).map(w => w.text),
    seconds: guestEnd ? (Date.now() - tQuit) / 1000 : null, hostAtTitle: !!hostTitle});
} catch (e) {
  report.error = String(e?.stack || e);
  await log("ERROR", report.error);
  for (const pg of pages) if (pg.sid) await pg.shot("error");
} finally {
  report.exceptions = Object.fromEntries(pages.map(p => [p.label, p.exceptions.slice(0, 20)]));
  report.webSockets = Object.fromEntries(pages.map(p => [p.label, p.sockets.map(s => `${s.t} ${s.worker ? "worker " : ""}${s.url}`)]));
  report.relayLog = bridgeLines.filter(l => /error|close|tunnel|lan/i.test(l)).slice(-60);
  const failed = Object.entries(report.checks).filter(([, v]) => !v.ok).map(([k]) => k);
  report.result = !report.error && failed.length === 0 && Object.keys(report.checks).length >= 11 ? "PASS" : "FAIL";
  report.failed = failed;
  await writeFile(join(out, "report.json"), JSON.stringify(report, null, 2));
  console.log(`${report.result} ${JSON.stringify(Object.fromEntries(Object.entries(report.checks).map(([k, v]) => [k, v.ok])))}${report.error ? " error=" + report.error.split("\n")[0] : ""}`);
  await cleanup();
  process.exit(report.result === "PASS" ? 0 : 1);
}
