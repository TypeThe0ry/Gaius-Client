// Two-client multiplayer acceptance for a Gaius browser build (Minecraft 26.3, any runtime the
// served dist boots). Loopback only: the driver starts its own vanilla server, RelayNode bridge,
// static dist server and ONE headless Chrome with two isolated browser contexts
// (Target.createBrowserContext, so no shared storage/cookies/service workers), one page each.
//
//   DIST=<site dir> SERVER_JAR=<vanilla 26.3 server.jar> OUT=<evidence dir> \
//     node port/scripts/minecraft-263-two-client-cdp.mjs
//
// Run it through your slot wrapper if you use one.
//
// Environment (all optional except DIST and SERVER_JAR):
//   DIST, SERVER_JAR, OUT (default mp-two-out)
//   PLAYER_A / PLAYER_B     names (default GaiusA / GaiusB); injected as localStorage gaius.playerName
//   DIST_PORT / BRIDGE_PORT / SERVER_PORT   defaults 8791 / 8091 / 57791 (all bound to 127.0.0.1)
//   SOAK_MS                 simultaneous movement time (default 60000)
//   BOOT_TIMEOUT_MS / JOIN_TIMEOUT_MS   defaults 600000 / 240000
//   JAVA, GAIUS_CHROME_BIN, SERVER_XMX (default 1500M), SERVER_VIEW_DISTANCE (default 4)
//   LAN_MODE=1              host a Worker world instead of launching a dedicated server
//   CAPTURE_MODE=trace      bounded timeline + CPU profiles from before navigation
//   CAPTURE_MS              capture duration (default 180000)
//   DIALOG_HOLD_MS          diagnostic delay before dismissing legacy modal invites
//   PROBE_PATCHES           not supported here (use the single-client harness)
//
// Checks (report.json "checks"): bothBoot, bothJoin, serverSawJoins, chatAToB, chatBToA, blockPlace,
// blockBreak, movement60s, leaveKeepsB, rejoin, chatAfterRejoin, relayClean, noUncaughtExceptions,
// loopbackOnly, screenshots. Exit 0 only on PASS.
//
// Observation model. The client exposes window.__gaiusMinecraftState (player pose, crosshair hit
// {blockPos, blockState}) and logs received chat as "[CHAT] ..." on the console, so:
//   - chat on the receiving client = its own "[CHAT] <name> text" console line;
//   - a remote block change on B = B's crosshair hit.blockState / hit.blockPos changing when B is
//     aimed at the spot A edits (positions are set up with server console tp, see below).
// Server-console assist (setup only, never the action under test): `fill` builds a platform high above
// the terrain, `give` puts a gold block in A's hand, `tp` places both players with a chosen yaw/pitch so
// that both crosshairs hit the same platform block from opposite sides. The place/break themselves are
// real right/left mouse clicks of A, chat is typed through the real chat screen.
import {spawn, spawnSync} from "node:child_process";
import {createServer as httpServer} from "node:http";
import {createServer as netServer} from "node:net";
import {existsSync, statSync, createReadStream, writeFileSync, copyFileSync, readFileSync} from "node:fs";
import {mkdir, appendFile, writeFile, rm, mkdtemp} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join, resolve, relative, normalize, extname, isAbsolute, dirname} from "node:path";
import {fileURLToPath} from "node:url";
import {BoundedCdpCapture} from "./bench/bounded-cdp-capture.mjs";
import {chromeIsolationArgs} from "./bench/harness-common.mjs";
import {analyzeTerrainPng} from "../../tools/terrain-visual-metrics.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const env = process.env;
const lanMode = env.LAN_MODE === '1' || env.LAN_MODE === 'true';
if (!env.DIST || (!env.SERVER_JAR && !lanMode)) { console.error("DIST=<site dir> and SERVER_JAR=<server.jar> are required unless LAN_MODE=1"); process.exit(2); }
const dist = resolve(env.DIST), serverJar = env.SERVER_JAR ? resolve(env.SERVER_JAR) : null, out = resolve(env.OUT || "mp-two-out");
const names = [env.PLAYER_A || "GaiusA", env.PLAYER_B || "GaiusB"];
const distPort = Number(env.DIST_PORT || 8791), bridgePort = Number(env.BRIDGE_PORT || 8091), serverPort = Number(env.SERVER_PORT || 57791);
const soakMs = Number(env.SOAK_MS || 60000), bootTimeout = Number(env.BOOT_TIMEOUT_MS || 600000), joinTimeout = Number(env.JOIN_TIMEOUT_MS || 240000);
const chromeBin = env.GAIUS_CHROME_BIN || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const javaBin = env.JAVA || "java";
const bridgeUrl = env.BRIDGE_URL || `http://127.0.0.1:${bridgePort}`;
const externalBridge = env.BRIDGE_EXTERNAL === "1";
const serverAddress = `127.0.0.1:${serverPort}`;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const t0 = Date.now();
const stamp = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(7);

await mkdir(out, {recursive: true});
const logFile = join(out, "console.log");
writeFileSync(logFile, "");
const logLines = [];
const log = async (kind, text) => { const line = `${stamp()} ${kind} ${text}`; logLines.push(line); await appendFile(logFile, line + "\n"); };
const report = {startedAt: new Date().toISOString(), mode: lanMode ? 'browser-lan' : 'vanilla-server', dist, players: names, server: lanMode ? null : serverAddress, bridge: bridgeUrl, checks: {}, timeline: [], notes: []};
const pass = (n, d) => { report.checks[n] = {ok: true, detail: d}; log("CHECK", `PASS ${n} ${JSON.stringify(d).slice(0, 600)}`); };
const fail = (n, d) => { report.checks[n] = {ok: false, detail: d}; log("CHECK", `FAIL ${n} ${JSON.stringify(d).slice(0, 600)}`); };
const check = (n, ok, d) => (ok ? pass : fail)(n, d);

// ---------------------------------------------------------------- child processes
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

// Vanilla server: own directory under OUT, stdin kept open for console commands.
const srvDir = join(out, "server");
await mkdir(srvDir, {recursive: true});
if (serverJar) copyFileSync(serverJar, join(srvDir, "server.jar"));
writeFileSync(join(srvDir, "eula.txt"), "eula=true\n");
writeFileSync(join(srvDir, "server.properties"), [
  "online-mode=false", "white-list=false", "enforce-whitelist=false", "server-ip=127.0.0.1", `server-port=${serverPort}`, "gamemode=creative", "force-gamemode=true", "difficulty=peaceful",
  "spawn-monsters=false", "spawn-animals=false", "spawn-npcs=false", "generate-structures=false", "level-seed=1", "level-type=minecraft\\:normal",
  `view-distance=${env.SERVER_VIEW_DISTANCE || 4}`, "simulation-distance=3", "spawn-protection=0", "allow-flight=true", "enforce-secure-profile=false", "max-players=4",
  "pause-when-empty-seconds=0", "enable-status=true", "motd=Gaius two-client acceptance", "network-compression-threshold=256", "sync-chunk-writes=false",
].join("\n") + "\n");
const srvLines = [];
let srvBuf = "";
const srvOut = data => {
  srvBuf += data.toString("utf8");
  const parts = srvBuf.split(/\r?\n/); srvBuf = parts.pop();
  for (const l of parts) { srvLines.push(l); appendFile(join(out, "server.stdout.log"), l + "\n").catch(() => {}); }
};
writeFileSync(join(out, "server.stdout.log"), "");
const srv = serverJar ? spawn(javaBin, [`-Xmx${env.SERVER_XMX || "1500M"}`, "-jar", "server.jar", "nogui"], {cwd: srvDir, stdio: ["pipe", "pipe", "pipe"]}) : null;
children.push(srv);
if (srv) { srv.stdout.on("data", srvOut); srv.stderr.on("data", srvOut); }
async function waitSrv(re, from, ms, what = String(re)) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    for (let i = from; i < srvLines.length; i++) if (re.test(srvLines[i])) return {index: i, line: srvLines[i]};
    if (srv.exitCode !== null) throw new Error(`server exited (${srv.exitCode}) while waiting for ${what}`);
    await sleep(100);
  }
  throw new Error(`server log timeout waiting for ${what}`);
}
async function srvCmd(cmd, waitRe = null, ms = 10000) {
  const from = srvLines.length;
  srv.stdin.write(cmd + "\n");
  await log("server-cmd", cmd);
  return waitRe ? waitSrv(waitRe, from, ms, `reply to "${cmd}"`) : {index: from, line: null};
}
const srvSince = (from, re) => srvLines.slice(from).filter(l => re.test(l));

// RelayNode bridge (local, private targets allowed because the vanilla server is on loopback).
const bridgeLog = [];
const bridgeProc = externalBridge ? null : spawn(process.execPath, ["dist/main.js"], {
  cwd: resolve(here, "../../apps/bridge"), stdio: ["ignore", "pipe", "pipe"],
  env: {...env, GAIUS_BRIDGE_HOST: "127.0.0.1", GAIUS_BRIDGE_PORT: String(bridgePort), GAIUS_ALLOWED_ORIGINS: `http://127.0.0.1:${distPort}`,
    GAIUS_ALLOWED_HOSTS: "127.0.0.1,*.gaius-local", GAIUS_ALLOW_PRIVATE_TARGETS: "1",
    GAIUS_BRIDGE_PROTOCOL_DIRECTORY: resolve(here, "../../apps/bridge/protocols")},
});
if (bridgeProc) children.push(bridgeProc);
writeFileSync(join(out, "bridge.log"), "");
const bridgeOut = tag => data => { for (const l of data.toString("utf8").split(/\r?\n/)) if (l) { bridgeLog.push(`${stamp()} ${tag} ${l}`); appendFile(join(out, "bridge.log"), `${stamp()} ${tag} ${l}\n`).catch(() => {}); } };
if (bridgeProc) {
  bridgeProc.stdout.on("data", bridgeOut("out")); bridgeProc.stderr.on("data", bridgeOut("err"));
}

async function relayRuntime() {
  try {
    const r = await fetch(`${bridgeUrl}/relay-node/v1.runtime`, {cache: "no-store"});
    if (!r.ok) return {error: `HTTP ${r.status}`};
    const j = await r.json(); return j.runtime || j;
  } catch (e) { return {error: String(e)}; }
}

// Static dist server.
const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json", ".gz": "application/gzip", ".png": "image/png"};
const http = httpServer((req, res) => {
  const u = new URL(req.url, "http://127.0.0.1");
  let p = normalize(join(dist, decodeURIComponent(u.pathname)));
  const rel = relative(dist, p);
  if (rel.startsWith("..") || isAbsolute(rel)) { res.writeHead(403); return res.end(); }
  if (u.pathname === "/") p = join(dist, "index.html");
  if (!existsSync(p) || statSync(p).isDirectory()) { res.writeHead(404); log("http404", u.pathname); return res.end(); }
  res.writeHead(200, {"Content-Type": MIME[extname(p)] || "application/octet-stream", "Content-Length": statSync(p).size, "Cache-Control": "no-store"});
  createReadStream(p).pipe(res);
});
await new Promise((ok, bad) => { http.once("error", bad); http.listen(distPort, "127.0.0.1", ok); });

// ---------------------------------------------------------------- CDP
class Conn {
  constructor(url) { this.ws = new WebSocket(url); this.id = 1; this.pending = new Map(); this.listeners = []; this.ws.addEventListener("message", e => {
    const m = JSON.parse(e.data);
    if (m.id && this.pending.has(m.id)) { const {ok, bad} = this.pending.get(m.id); this.pending.delete(m.id); m.error ? bad(new Error(`${m.error.message}`)) : ok(m.result); }
    else if (m.method) for (const fn of this.listeners) fn(m); }); }
  open() { return new Promise((ok, bad) => { this.ws.addEventListener("open", ok); this.ws.addEventListener("error", bad); }); }
  send(method, params = {}, sessionId) {
    const id = this.id++;
    return new Promise((ok, bad) => { const timer=setTimeout(()=>{this.pending.delete(id);bad(new Error(`CDP ${method} timed out`));},15000); this.pending.set(id, {ok:v=>{clearTimeout(timer);ok(v)}, bad:e=>{clearTimeout(timer);bad(e)}}); this.ws.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})})); });
  }
}

const profileDir = await mkdtemp(join(tmpdir(), "gaius-two-"));
const chromePort = await freePort();
const chrome = spawn(chromeBin, ["--headless=new", `--remote-debugging-port=${chromePort}`, "--remote-allow-origins=*", `--user-data-dir=${profileDir}`,
  "--window-size=1280,720", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader",
  "--disable-background-timer-throttling", "--disable-renderer-backgrounding", "--disable-backgrounding-occluded-windows",
  ...chromeIsolationArgs(), "about:blank"], {stdio: "ignore"});
children.push(chrome);

const sessionOwner = new Map();   // CDP sessionId -> Page
const fmtArgs = args => (args || []).map(a => a.value !== undefined ? (typeof a.value === "string" ? a.value : JSON.stringify(a.value)) : (a.description || a.type)).join(" ");
let conn, capture;

const STATE = `(()=>{const s=window.__gaiusMinecraftState;if(!s)return null;const p=s.player,h=s.hit;
  return {screen:String(s.screen||''),level:!!s.level,chunks:s.loadedChunkCount,at:s.at,
    player:p?{x:p.x,y:p.y,z:p.z,yaw:p.yaw,pitch:p.pitch,mode:p.gameMode,item:p.selectedItem}:null,
    hit:h?{type:h.type,pos:h.blockPos,dir:h.direction,state:h.blockState}:null,
    widgets:(s.screenWidgets||[]).map(w=>({type:String(w.type||'').split('.').pop(),text:String(w.text||''),active:w.active,visible:w.visible}))}})()`;
const isLoopbackUrl = u => { try { if (!/^(https?|wss?):/.test(u)) return true; const host = new URL(u).hostname; return /^(127\.0\.0\.1|localhost|\[::1\])$/.test(host) || host === "::1" || host.endsWith(".gaius-local"); } catch { return true; } };
const blockNums = pos => (String(pos || "").match(/-?\d+/g) || []).map(Number);

class Page {
  constructor(label, name) { this.label = label; this.name = name; this.lines = []; this.exceptions = []; this.sockets = []; this.hosts = new Set(); this.reqUrl = new Map(); this.attempts = []; this.reached = []; this.consoleErrors = []; this.chat = []; this.dialogs = []; this.shots = []; this.samples = []; this.keys = new Set(); }
  async init() {
    const {browserContextId} = await conn.send("Target.createBrowserContext", {disposeOnDetach: true});
    this.contextId = browserContextId;
    const {targetId} = await conn.send("Target.createTarget", {url: "about:blank", browserContextId, newWindow: true});
    this.targetId = targetId;
    const {sessionId} = await conn.send("Target.attachToTarget", {targetId, flatten: true});
    this.sid = sessionId; sessionOwner.set(sessionId, this);
    await capture?.trackTarget({sessionId,targetId,type:"page",url:"about:blank",owner:this.label});
    const s = (m, p) => conn.send(m, p, sessionId);
    await s("Runtime.enable"); await s("Page.enable"); await s("Log.enable"); await s("Network.enable");
    await s("Emulation.setDeviceMetricsOverride", {width: 1280, height: 720, deviceScaleFactor: 1, mobile: false});
    await s("Emulation.setFocusEmulationEnabled", {enabled: true});
    await s("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: true, flatten: true});
    await s("Page.addScriptToEvaluateOnNewDocument", {source: `try{localStorage.setItem('gaius.playerName',${JSON.stringify(this.name)});localStorage.setItem('gaius.bridgeNodes',${JSON.stringify(JSON.stringify([{url: bridgeUrl, use: 'both'}]))})}catch(e){}`});
    await s("Page.navigate", {url: `http://127.0.0.1:${distPort}/index.html?bridge=${encodeURIComponent(bridgeUrl)}&relay=${encodeURIComponent(bridgeUrl)}`});
  }
  send(m, p) { return conn.send(m, p, this.sid); }
  async ev(expr) {
    const request = this.send("Runtime.evaluate", {expression: expr, awaitPromise: true, returnByValue: true});
    const r = await Promise.race([request, sleep(2500).then(() => ({__timeout: true}))]);
    if (r?.__timeout) return null;
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text || "evaluate failed");
    return r.result?.value;
  }
  state() { return this.ev(STATE).catch(() => null); }
  async mouse(type, x, y, button = "none", buttons = 0) { await this.send("Input.dispatchMouseEvent", {type, x, y, button, buttons, clickCount: type === "mouseMoved" ? 0 : 1}); }
  async clickAt(x, y, button = "left", holdMs = 50) {
    const bits = button === "right" ? 2 : 1;
    await this.mouse("mouseMoved", x, y); await sleep(50);
    await this.mouse("mousePressed", x, y, button, bits); await sleep(holdMs);
    await this.mouse("mouseReleased", x, y, button, 0);
  }
  async key(name, code, vk, text, modifiers = 0, holdMs = 0) {
    const base = {key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers};
    let sent = false;
    for (let attempt = 0; attempt < 2 && !sent; attempt++) {
      try { await this.send("Input.dispatchKeyEvent", {type: "keyDown", ...base, ...(text ? {text} : {})}); sent = true; }
      catch (e) { if (attempt) throw e; await sleep(250); }
    }
    if (holdMs) await sleep(holdMs);
    await this.send("Input.dispatchKeyEvent", {type: "keyUp", ...base});
  }
  async holdDown(name, code, vk) { this.keys.add(code); for (let i=0;i<2;i++) try { await this.send("Input.dispatchKeyEvent", {type: "rawKeyDown", key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk}); return; } catch(e) { if(i) throw e; await sleep(250); } }
  async holdUp(name, code, vk) { this.keys.delete(code); for (let i=0;i<2;i++) try { await this.send("Input.dispatchKeyEvent", {type: "keyUp", key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk}); return; } catch(e) { if(i) throw e; await sleep(250); } }
  async typeText(text) {
    for (const ch of text) {
      const up = ch.toUpperCase();
      const code = /[a-z]/i.test(ch) ? "Key" + up : /[0-9]/.test(ch) ? "Digit" + ch : ({".": "Period", ":": "Semicolon", "-": "Minus", " ": "Space", "/": "Slash", "~": "Backquote", "@": "Digit2", "_": "Minus"})[ch] || "";
      const vk = /[a-z0-9]/i.test(ch) ? up.charCodeAt(0) : ({".": 190, ":": 186, "-": 189, " ": 32, "/": 191, "~": 192, "@": 50, "_": 189})[ch] || 0;
      await this.key(ch, code, vk, ch, ch !== ch.toLowerCase() || ch === ":" ? 8 : 0);
      await sleep(40);
    }
  }
  async widget(match, ms = 30000) {
    const m = JSON.stringify(match), end = Date.now() + ms;
    while (Date.now() < end) {
      const v = await this.ev(`(()=>{const s=window.__gaiusMinecraftState||{};const ws=Array.isArray(s.screenWidgets)?s.screenWidgets:[];const m=${m};const ok=x=>x&&x.visible!==false;let w=null;
        if(m.type)w=ws.find(x=>ok(x)&&String(x.type||'').includes(m.type));
        else{const n=String(m.text).toLowerCase();w=ws.find(x=>ok(x)&&String(x.text||'').trim().toLowerCase()===n)||ws.find(x=>ok(x)&&String(x.text||'').toLowerCase().includes(n));if(!w&&n==='multiplayer')w=ws.find(x=>ok(x)&&/multiplayer/i.test(String(x.type||'')));}
        const c=document.querySelector('canvas');const r=c&&c.getBoundingClientRect();const z=s.screenSize;if(!w||!r||!z||!z.width)return null;
        return {text:String(w.text||''),active:w.active!==false,x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height)};})()`).catch(() => null);
      if (v) return v;
      await sleep(250);
    }
    return null;
  }
  async clickWidget(match, ms) {
    const w = await this.widget(typeof match === "string" ? {text: match} : match, ms);
    if (!w) throw new Error(`${this.label}: widget not found ${JSON.stringify(match)} state=${JSON.stringify(await this.state()).slice(0, 400)}`);
    await this.clickAt(w.x, w.y); return w;
  }
  async screen() { return (await this.state())?.screen || ""; }
  async waitScreen(re, ms) { const end = Date.now() + ms; let sc = ""; while (Date.now() < end) { sc = await this.screen(); if (re.test(sc)) return sc; await sleep(700); } return null; }
  async shot(name) {
    try {
      const {data} = await this.send("Page.captureScreenshot", {format: "png"});
      const buf = Buffer.from(data, "base64"); const file = `${this.label}-${name}.png`;
      await writeFile(join(out, file), buf); this.shots.push(file); return buf;
    } catch (e) { await log("shot-fail", `${this.label} ${name} ${e}`); return null; }
  }
  async releaseKeys() { for (const code of [...this.keys]) { const d = KEYS[code]; if (d) await this.holdUp(d.key, code, d.vk).catch(() => {}); } }
  chatSeen(text) { return this.chat.find(c => c.text.includes(text)); }
}
const KEYS = {KeyW: {key: "w", vk: 87}, KeyA: {key: "a", vk: 65}, KeyS: {key: "s", vk: 83}, KeyD: {key: "d", vk: 68}, Space: {key: " ", vk: 32}};

function onEvent(m) {
  const page = sessionOwner.get(m.sessionId);
  if (m.method === "Target.attachedToTarget") {
    const owner = sessionOwner.get(m.sessionId);
    if (!owner) return;
    const child = m.params.sessionId; sessionOwner.set(child, owner);
    log("target", `${owner.label} ${m.params.targetInfo.type} ${m.params.targetInfo.url}`);
    (async () => {
      try {
        await conn.send("Runtime.enable", {}, child); await conn.send("Log.enable", {}, child);
        await conn.send("Target.setAutoAttach", {autoAttach: true, waitForDebuggerOnStart: true, flatten: true}, child);
        await capture?.trackTarget({sessionId:child,targetId:m.params.targetInfo.targetId,type:m.params.targetInfo.type,url:m.params.targetInfo.url,owner:owner.label});
      } catch (e) { log("target-err", String(e)); }
      finally {
        // A diagnostics failure must never leave the game Worker suspended.
        await conn.send("Runtime.runIfWaitingForDebugger", {}, child).catch(e => log("target-resume-err", String(e)));
      }
    })();
    return;
  }
  if (!page) return;
  const worker = m.sessionId !== page.sid ? "[worker]" : "";
  const p = m.params || {};
  switch (m.method) {
    case "Page.javascriptDialogOpening": {
      const d = {type: p.type, message: String(p.message || ""), defaultPrompt: String(p.defaultPrompt || "")};
      page.dialogs.push(d);
      log(`${page.label}.dialog`, `${d.type} ${d.message} ${d.defaultPrompt}`.trim());
      // The LAN provider presents its invite in a prompt.  Accepting it keeps the
      // browser path identical to a real user copying the generated address.
      setTimeout(() => conn.send("Page.handleJavaScriptDialog", {accept: true}, page.sid).catch(() => {}), Number(env.DIALOG_HOLD_MS || (env.CAPTURE_MODE ? 20000 : 0)));
      break;
    }
    case "Runtime.consoleAPICalled": {
      const text = fmtArgs(p.args).slice(0, 4000); page.lines.push(text);
      log(`${page.label}.console.${p.type}${worker}`, text);
      const chat = /\[CHAT\]\s*(.*)$/.exec(text);
      if (chat) page.chat.push({t: Date.now(), text: chat[1]});
      if (p.type === "error") page.consoleErrors.push({t: stamp(), text: text.slice(0, 500)});
      break;
    }
    case "Runtime.exceptionThrown": {
      const d = p.exceptionDetails; const text = (d.exception?.description || d.text || "").slice(0, 2000);
      page.exceptions.push({at: stamp(), worker: !!worker, text}); log(`${page.label}.EXCEPTION${worker}`, text); break;
    }
    case "Log.entryAdded": log(`${page.label}.log.${p.entry.level}${worker}`, `${p.entry.source} ${String(p.entry.text).slice(0, 2000)} ${p.entry.url || ""}`); break;
    case "Network.webSocketCreated": page.sockets.push({t: stamp(), url: p.url}); log(`${page.label}.websocket`, p.url); break;
    case "Network.requestWillBeSent": { try { const u = new URL(p.request.url); if (/^(https?|wss?):$/.test(u.protocol)) { page.hosts.add(u.host); page.reqUrl.set(p.requestId, u.href); } } catch {} break; }
    case "Network.responseReceived": { const u = page.reqUrl.get(p.requestId) || p.response?.url; if (u && !isLoopbackUrl(u)) page.reached.push({url: u, status: p.response.status}); break; }
    case "Network.loadingFailed": { const u = page.reqUrl.get(p.requestId); if (u && !isLoopbackUrl(u)) page.attempts.push({url: u, error: p.errorText}); break; }
    default: break;
  }
}

// ---------------------------------------------------------------- scenario helpers
async function bootToTitle(pg) {
  const end = Date.now() + bootTimeout; let last = "";
  while (Date.now() < end) {
    const s = await pg.state();
    const sig = s ? s.screen : "<no state>";
    if (sig !== last) { last = sig; report.timeline.push({t: stamp(), page: pg.label, screen: sig}); await log("state", `${pg.label} ${sig}`); }
    if (s && /BrowserProfileScreen/.test(s.screen)) { await pg.clickWidget("Done", 10000).catch(() => pg.key("Escape", "Escape", 27)); await sleep(1000); continue; }
    if (s && /TitleScreen/.test(s.screen)) return (Date.now() - t0) / 1000;
    await sleep(500);
  }
  throw new Error(`${pg.label}: title screen not reached: ${JSON.stringify(await pg.state()).slice(0, 400)}`);
}

async function hostSingleplayer(pg) {
  await pg.clickWidget('Singleplayer', 20000); await pg.waitScreen(/SelectWorldScreen/, 20000);
  await pg.clickWidget({text:'Create New World'}, 10000).catch(() => pg.clickWidget({text:'Create New'}, 10000));
  if (!(await pg.waitScreen(/CreateWorldScreen/, 5000))) {
    const already = await pg.state(); if (already?.level && already.player && !already.screen) return already;
  } else {
    // LAN block/chat checks use server commands for deterministic setup.  Enable cheats
    // through the normal Create World screen when that toggle is exposed.
    await pg.clickWidget({text:'Allow Cheats'}, 2500).catch(() =>
      pg.clickWidget({text:'Allow Cheats: OFF'}, 2500).catch(() =>
        pg.clickWidget({text:'Allow Commands'}, 2500).catch(() => {})));
    await pg.clickWidget({text:'Create New World'}, 10000).catch(async () => {
      const state = await pg.state();
      if (state?.level && state.player && !state.screen) return;
      return pg.clickWidget({text:'Create'}, 10000);
    });
  }
  const end = Date.now() + 240000; while (Date.now() < end) { const s = await pg.state(); if (s?.level && s.player && !s.screen) return s; await sleep(500); }
  throw new Error('host singleplayer world timeout');
}
async function openLan(pg) {
  // The host already logs a startup client endpoint before the LAN broker exists.
  // Capture those IDs so the invite must come from the new lan-server session opened
  // by the Start LAN World action below.
  const clientIdPattern = /client-[0-9a-f]{32}\.gaius-local/g;
  const startupClientIds = new Set();
  for (const line of pg.lines) {
    for (const match of String(line).matchAll(clientIdPattern)) startupClientIds.add(match[0]);
  }
  await pg.send('Page.bringToFront').catch(() => {});
  const canvas = await pg.ev("(()=>{const r=document.querySelector('canvas')?.getBoundingClientRect();return r?{x:r.left+r.width/2,y:r.top+r.height/2}:null})()").catch(() => null);
  // Give the game canvas real focus before dispatching Escape.  On fresh profiles
  // the tutorial overlay can consume keyboard events while the page itself still
  // reports a loaded world; a click also makes the subsequent pause transition
  // deterministic in headless Chrome.
  if (canvas) {
    await pg.mouse('mouseMoved', canvas.x, canvas.y).catch(() => {});
    await pg.clickAt(canvas.x, canvas.y, 'left', 30).catch(() => {});
  }
  let pauseScreen = null;
  for (let i=0;i<4;i++) {
    await pg.key('Escape','Escape',27);
    pauseScreen = await pg.waitScreen(/PauseScreen/,3000);
    if (pauseScreen) break;
  }
  if (!pauseScreen) throw new Error('LAN pause screen not reached');
  await pg.clickWidget('Open to LAN', 10000);
  await sleep(1000);
  // Worker worlds and vanilla integrated worlds both open a share screen first.
  if (/ShareToLanScreen/.test(await pg.screen())) {
    await pg.shot('share-lan');
    const cheats = await pg.widget({text:'Allow Commands: OFF'}, 500) || await pg.widget({text:'Allow Cheats: OFF'}, 500);
    if (cheats) await pg.clickAt(cheats.x, cheats.y);
    await pg.clickWidget({text:'Start LAN World'}, 10000);
  }
  const end=Date.now()+30000;
  while (Date.now() < end) {
    // Native ShareToLanScreen may surface the broker result as a JS prompt/event
    // instead of a console line.  Observe all three supported publication paths.
    for (const dialog of pg.dialogs) {
      const values = [dialog.defaultPrompt, dialog.message];
      for (const value of values) {
        const m = String(value || '').match(clientIdPattern);
        if (m) return m[0];
        const u = String(value || '').match(/https?:[^\s]+[?&]server=(client-[0-9a-f]{32}\.gaius-local(?::\d+)?)/i);
        if (u) return u[1];
      }
    }
    for (const line of pg.lines) {
      for (const match of String(line).matchAll(clientIdPattern)) {
        if (!startupClientIds.has(match[0])) return match[0];
      }
    }
    const signals = await pg.ev(`(()=>{const es=Array.isArray(window.__gaiusMinecraftEvents)?window.__gaiusMinecraftEvents:[];return es.slice(-40).map(e=>({event:e?.event,detail:e?.detail}));})()`).catch(() => null);
    for (const e of signals || []) {
      const text = JSON.stringify(e);
      const m = text.match(clientIdPattern);
      if (m && !startupClientIds.has(m[0])) return m[0];
    }
    await sleep(250);
  }
  throw new Error(`LAN invite client host not observed (dialogs=${JSON.stringify(pg.dialogs).slice(0,800)}, events=${JSON.stringify(await pg.ev('window.__gaiusMinecraftEvents?.slice(-8)')).slice(0,1200)})`);
}

async function resumeLanHost(pg) {
  // Dismiss the browser invitation through its visible control, then resume Minecraft.
  const close = await pg.ev("(()=>{const panel=document.getElementById('gaius-lan-invite');const b=panel&&[...panel.querySelectorAll('button')].find(x=>x.textContent==='Close');if(!b)return null;const r=b.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()");
  if (close) { await pg.shot('lan-invite'); await pg.clickAt(close.x,close.y); }
  // Worker worlds show the invite on the in-game Open to LAN screen; Done returns to the pause menu.
  if (/ShareToLanScreen/.test(await pg.screen())) {
    await pg.shot('lan-invite');
    await pg.clickWidget('Done',3000).catch(()=>pg.key('Escape','Escape',27));
    await sleep(300);
  }
  if (/PauseScreen/.test(await pg.screen())) await pg.clickWidget('Back to Game',1000).catch(()=>pg.clickWidget('Return to Game',1000)).catch(()=>pg.key('Escape','Escape',27));
  await sleep(500);
}

async function saveLanHost(pg) {
  // Host save is the final LAN action.  Guest rejoin/chat has already completed,
  // so ending the host world cannot mask any earlier LAN result.
  for (let i = 0; i < 4; i++) {
    if (/PauseScreen/.test(await pg.screen())) break;
    await pg.key("Escape", "Escape", 27);
    await sleep(400);
  }
  if (!await pg.waitScreen(/PauseScreen/, 5000)) throw new Error(`${pg.label}: pause screen not reached for host save`);
  await pg.clickWidget("Save and Quit to Title", 10000);
  const end = Date.now() + 120000;
  while (Date.now() < end) {
    const s = await pg.state();
    if (s && /TitleScreen/.test(s.screen) && !s.level) break;
    await sleep(500);
  }
  const after = await pg.state();
  if (!after || !/TitleScreen/.test(after.screen) || after.level) throw new Error(`${pg.label}: title screen not reached after host save`);
  const workerEnd = Date.now() + 120000;
  let workersStopped = false;
  while (Date.now() < workerEnd) {
    workersStopped = await pg.ev("(()=>{const w=globalThis.__gaiusSingleplayerWorkers;if(!w||typeof w.values!=='function')return true;return [...w.values()].every(x=>x.__gaiusStopped||x.__gaiusTerminal);})()").catch(() => false);
    if (workersStopped) break;
    await sleep(500);
  }
  await pg.shot("host-saved");
  return {titleScreen: /TitleScreen/.test(after.screen) && !after.level, workersStopped, screen: after.screen};
}

async function joinServer(pg, label, address = serverAddress) {
  const t = Date.now();
  let sc = await pg.screen();
  if (/TitleScreen/.test(sc)) await pg.clickWidget("Multiplayer", 15000);
  const end0 = Date.now() + 20000;
  while (Date.now() < end0 && !/JoinMultiplayerScreen|MultiplayerWarningScreen|SafetyScreen/.test(sc = await pg.screen())) await sleep(250);
  if (/Warning|Safety/.test(sc)) {
    await pg.clickWidget("Proceed", 5000).catch(() => pg.clickWidget("Continue", 5000));
    const e = Date.now() + 15000; while (Date.now() < e && !/JoinMultiplayerScreen/.test(sc = await pg.screen())) await sleep(250);
  }
  await sleep(800);
  await pg.clickWidget("Direct Connect", 15000);
  if (!await pg.waitScreen(/DirectJoinServerScreen/, 15000)) throw new Error(`${pg.label}: DirectJoinServerScreen not reached`);
  await sleep(600);
  const edit = await pg.widget({type: "EditBox"}, 5000);
  if (!edit) throw new Error(`${pg.label}: no address EditBox`);
  await pg.clickAt(edit.x, edit.y); await sleep(200);
  await pg.key("a", "KeyA", 65, "", 2); await pg.key("Backspace", "Backspace", 8);
  await pg.typeText(address);
  await sleep(600);
  const st = await pg.state();
  const joinBtn = (st?.widgets || []).find(w => /Join Server/i.test(w.text));
  if (!joinBtn || joinBtn.active === false) throw new Error(`${pg.label}: Join Server inactive: ${JSON.stringify(st?.widgets)}`);
  await pg.clickWidget("Join Server", 5000);
  const end = Date.now() + joinTimeout; let last = "";
  while (Date.now() < end) {
    const s = await pg.state();
    const sig = s ? JSON.stringify([s.screen, s.level, !!s.player, s.chunks]) : "<no state>";
    if (sig !== last) { last = sig; await log("state", `${pg.label} ${label} ${sig}`); }
    // The level flag is published before the first server chunk/player update.  Do not
    // declare PLAY until both are populated; otherwise the two-client checks race the
    // initial join burst and observe a zero pose/empty world.
    const p = s?.player;
    const poseReady = p && [p.x, p.y, p.z].every(Number.isFinite) && Number(p.y) > 1;
    if (s?.level && poseReady && !s.screen) return {seconds: (Date.now() - t) / 1000, state: s};
    if (/DisconnectedScreen/.test(s?.screen || "")) throw new Error(`${pg.label}: disconnected: ${JSON.stringify(s.widgets.map(w => w.text))}`);
    if (/ConfirmScreen|PackConfirm|ServerPack|CodeOfConduct/i.test(s?.screen || "")) {
      const yes = await pg.widget({text: "Yes"}, 500) || await pg.widget({text: "Proceed"}, 500) || await pg.widget({text: "Accept"}, 500);
      if (yes) await pg.clickAt(yes.x, yes.y);
    }
    await sleep(500);
  }
  throw new Error(`${pg.label}: join timeout: ${JSON.stringify(await pg.state()).slice(0, 500)}`);
}

async function sendChat(pg, text) {
  // SDL-based 26.3 can consume a synthetic keyDown's text payload as text
  // input without dispatching the gameplay key binding. Try the normal DOM
  // key event first, then a raw SDL-style key transition, and finally slash
  // (Minecraft's alternate chat binding) before declaring the screen missing.
  await pg.key("t", "KeyT", 84, "");
  if (!await pg.waitScreen(/ChatScreen/, 900)) {
    await pg.holdDown("t", "KeyT", 84); await sleep(40); await pg.holdUp("t", "KeyT", 84);
  }
  if (!await pg.waitScreen(/ChatScreen/, 900)) await pg.key("/", "Slash", 191, "");
  if (!await pg.waitScreen(/ChatScreen/, 5000)) throw new Error(`${pg.label}: ChatScreen did not open`);
  await sleep(150);
  await pg.typeText(text); await sleep(150);
  await pg.key("Enter", "Enter", 13);
  const end = Date.now() + 5000;
  while (Date.now() < end && /ChatScreen/.test(await pg.screen())) await sleep(100);
  await log("chat-sent", `${pg.label} ${text}`);
}
async function sendCommand(pg, command) {
  const cmd = String(command).startsWith('/') ? String(command) : `/${command}`;
  await sendChat(pg, cmd);
  await sleep(700);
}
async function waitChat(pg, token, ms = 15000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { const c = pg.chatSeen(token); if (c) return c; await sleep(100); }
  return null;
}

async function waitFor(fn, ms, every = 100) {
  const end = Date.now() + ms;
  while (Date.now() < end) { const v = await fn().catch(() => null); if (v) return v; await sleep(every); }
  return null;
}

// ---------------------------------------------------------------- main
let finished = false;
async function cleanup() {
  if (finished) return; finished = true;
  await capture?.stop("cleanup");
  try { await Promise.all(pages.map(p => p.releaseKeys())); } catch {}
  try { report.relay.final = await relayRuntime(); } catch {}
  try { conn?.ws.close(); } catch {}
  killTree(chrome);
  try { if (srv && srv.exitCode === null) { srv.stdin.write("stop\n"); const e = Date.now() + 25000; while (srv.exitCode === null && Date.now() < e) await sleep(250); } } catch {}
  for (const c of children) killTree(c);
  http.close();
  await sleep(1500);
  await rm(profileDir, {recursive: true, force: true, maxRetries: 10, retryDelay: 500}).catch(() => {});
  for (const d of ["world", "libraries", "versions"]) await rm(join(srvDir, d), {recursive: true, force: true, maxRetries: 5, retryDelay: 500}).catch(() => {});
}
const pages = [];
report.relay = {};
try {
  if (!lanMode) { await waitSrv(/Done \(/, 0, 180000, "server start"); await log("server", "vanilla server up"); }
  for (let i = 0; i < 80; i++) { try { if ((await fetch(`${bridgeUrl}/relay-node/v1.runtime`)).ok) break; } catch {} await sleep(250); }
  report.relay.start = await relayRuntime();

  for (let i = 0; i < 80 && !conn; i++) {
    try { const j = await (await fetch(`http://127.0.0.1:${chromePort}/json/version`)).json(); conn = new Conn(j.webSocketDebuggerUrl); await conn.open(); } catch { conn = null; await sleep(250); }
  }
  if (!conn) throw new Error("cannot connect to Chrome CDP");
  conn.listeners.push(onEvent);
  if (env.CAPTURE_MODE) { capture = new BoundedCdpCapture({conn,out:join(out,"capture"),durationMs:Number(env.CAPTURE_MS||180000),log}); await capture.start(); }
  const A = new Page("A", names[0]), B = new Page("B", names[1]);
  pages.push(A, B);
  await Promise.all([A.init(), B.init()]);
  report.contexts = {A: A.contextId, B: B.contextId, distinct: A.contextId !== B.contextId};

  // 1. boot both pages
  const boots = await Promise.all([bootToTitle(A), bootToTitle(B)]);
  report.bootSeconds = boots;
  await Promise.all([A.shot("title"), B.shot("title")]);
  pass("bothBoot", {bootSeconds: boots, distinctContexts: report.contexts.distinct});

  // 2. join (sequentially, A first) with different names
  const srvJoinFrom = srvLines.length; report.join = {}; let lanInvite = null;
  if (lanMode) { report.host = await hostSingleplayer(A); report.join.A={seconds:0,state:report.host}; lanInvite = await openLan(A); report.invite = lanInvite; await resumeLanHost(A); report.join.B = await joinServer(B, 'lan-join', lanInvite.replace(/:\d+$/, '') + ':25565'); }
  else { report.join.A = await joinServer(A, 'join1'); report.join.B = await joinServer(B, 'join1'); }
  await sleep(4000);
  await Promise.all([A.shot("joined"), B.shot("joined")]);
  const [sa, sb] = await Promise.all([A.state(), B.state()]);
  const joinOk = sa?.level && sa.player && sb?.level && sb.player;
  const relayJoined = await relayRuntime();
  check("bothJoin", !!joinOk && Number(relayJoined.activeTunnelLeases) === 2, {A: {seconds: report.join.A.seconds, chunks: sa?.chunks, pos: sa?.player}, B: {seconds: report.join.B.seconds, chunks: sb?.chunks, pos: sb?.player}, relayActiveTunnelLeases: relayJoined.activeTunnelLeases, relayProfilesSelected777: relayJoined.profilesSelected777});
  if (!joinOk) throw new Error('not both in PLAY');
  if (lanMode) {
    check('hostOpensLan', !!lanInvite, {invite: lanInvite}); check('joinerInWorld', !!sb?.level && !!sb.player && !sb.screen, {screen: sb?.screen, chunks: sb?.chunks});
    const t1=`lan-a2b-${Date.now().toString(36)}`, t2=`lan-b2a-${Date.now().toString(36)}`; await sendChat(A,t1); const b1=await waitChat(B,t1); await sendChat(B,t2); const a2=await waitChat(A,t2);
    check('chatAToB',!!b1,{token:t1,received:b1?.text||null}); check('chatBToA',!!a2,{token:t2,received:a2?.text||null});
    // Commands only establish/inspect the scene; editing uses real mouse input.
    const lanCmd = async (command, re, ms=15000) => {
      const from=A.chat.length;
      await sendCommand(A,command);
      const reply=await waitFor(async()=>A.chat.slice(from).find(c=>re.test(c.text)),ms,100);
      if (!reply) throw new Error(`LAN command reply missing: ${command}; chat=${JSON.stringify(A.chat.slice(from))}`);
      return {line:reply.text};
    };
    await sendCommand(A,'gamemode creative');
  const base = sa.player; const cx = Math.floor(base.x), cz = Math.floor(base.z), py = 200;
  const target = {x: cx, y: py, z: cz + 3}, placed = {x: cx, y: py + 1, z: cz + 3};
  await lanCmd(`fill ${cx - 15} ${py} ${cz - 15} ${cx + 16} ${py} ${cz + 16} minecraft:smooth_stone`, /Successfully filled|Filled|filled/, 15000);
  await lanCmd(`give ${names[0]} minecraft:gold_block 64`, /Gave|gave/, 5000);
  const pitch = (Math.atan2(1.62, 3) * 180 / Math.PI).toFixed(1);
  await lanCmd(`tp ${names[0]} ${cx + 0.5} ${py + 1} ${cz + 0.5} 0 ${pitch}`, /Teleported/, 5000);
  await lanCmd(`tp ${names[1]} ${cx + 0.5} ${py + 1} ${cz + 6.5} 180 ${pitch}`, /Teleported/, 5000);
  const posed = await waitFor(async () => {
    const [a, b] = await Promise.all([A.state(), B.state()]);
    const near = (s, z) => s?.player && Math.abs(s.player.x - (cx + 0.5)) < 0.3 && Math.abs(s.player.z - (cz + z)) < 0.3 && Math.abs(s.player.y - (py + 1)) < 0.3;
    const aimed = (s, tz) => s?.hit?.type && blockNums(s.hit.pos).join() === [target.x, target.y, target.z].join();
    return near(a, 0.5) && near(b, 6.5) && aimed(a) && aimed(b) ? {a, b} : null;
  }, 30000, 250);
  report.scene = {platform: {y: py, center: [cx, cz]}, target, placed, pitch, posed: posed ? {A: posed.a.player, hitA: posed.a.hit, B: posed.b.player, hitB: posed.b.hit} : null};
  if (!posed) throw new Error(`scene not established: A=${JSON.stringify(await A.state())} B=${JSON.stringify(await B.state())}`);
  const heldItem = posed.a.player.item;
  await sleep(1500);
  await Promise.all([A.shot("scene-before"), B.shot("scene-before")]);
  const canvasCenter = async pg => pg.ev("(()=>{const r=document.querySelector('canvas').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})()");

  // 5. A places the block (right click), B must see it at the same position
  const cA = await canvasCenter(A);
  const srvPlaceFrom = srvLines.length;
  const tPlace = Date.now();
  await A.clickAt(cA.x, cA.y, "right", 80);
  const isPlacedHit = s => s?.hit && blockNums(s.hit.pos).join() === [placed.x, placed.y, placed.z].join() && /gold_block/.test(String(s.hit.state));
  const seenA = await waitFor(async () => { const s = await A.state(); return isPlacedHit(s) ? s : null; }, 10000, 50);
  const placeMsA = seenA ? Date.now() - tPlace : null;
  const seenB = await waitFor(async () => { const s = await B.state(); return isPlacedHit(s) ? s : null; }, 15000, 50);
  const placeMsB = seenB ? Date.now() - tPlace : null;
  const srvPlace = await lanCmd(`execute if block ${placed.x} ${placed.y} ${placed.z} minecraft:gold_block`, /Test (passed|failed)/, 5000).catch(e => ({line: String(e)}));
  await sleep(500);
  await Promise.all([A.shot("block-placed"), B.shot("block-placed")]);
  check("blockPlace", !!seenA && !!seenB && /Test passed/.test(srvPlace.line || ""), {heldItem, placedAt: placed, aSawMs: placeMsA, bSawMs: placeMsB, aHit: seenA?.hit, bHit: seenB?.hit, serverCheck: srvPlace.line});

  // 6. A breaks it (left click), B must see the platform again
  const isRestoredHit = s => s?.hit && blockNums(s.hit.pos).join() === [target.x, target.y, target.z].join() && /smooth_stone/.test(String(s.hit.state));
  const tBreak = Date.now();
  await A.clickAt(cA.x, cA.y, "left", 150);
  const brokenA = await waitFor(async () => { const s = await A.state(); return isRestoredHit(s) ? s : null; }, 10000, 50);
  const breakMsA = brokenA ? Date.now() - tBreak : null;
  const brokenB = await waitFor(async () => { const s = await B.state(); return isRestoredHit(s) ? s : null; }, 15000, 50);
  const breakMsB = brokenB ? Date.now() - tBreak : null;
  const srvBreak = await lanCmd(`execute if block ${placed.x} ${placed.y} ${placed.z} minecraft:air`, /Test (passed|failed)/, 5000).catch(e => ({line: String(e)}));
  await sleep(500);
  await Promise.all([A.shot("block-broken"), B.shot("block-broken")]);
  check("blockBreak", !!brokenA && !!brokenB && /Test passed/.test(srvBreak.line || ""), {aSawMs: breakMsA, bSawMs: breakMsB, aHit: brokenA?.hit, bHit: brokenB?.hit, serverCheck: srvBreak.line});

    for(let i=0;i<4;i++){await B.key('Escape','Escape',27);if(await B.waitScreen(/PauseScreen/,3000)){await B.clickWidget('Disconnect',5000);break;}}
    const left=await waitFor(async()=>{const s=await B.state();return s&&!s.level?s:null;},30000,250);
    const hostStill=await A.state();
    check('leaveRejoin',!!left&&!!hostStill?.level,{joinerAfterLeave:left?.screen||null,hostStillInWorld:!!hostStill?.level});
    for(let i=0;i<5;i++){if(/TitleScreen|JoinMultiplayerScreen/.test(await B.screen()))break;const b=await B.widget({text:'Back to Server List'},500)||await B.widget({text:'Back'},500)||await B.widget({text:'Cancel'},500);if(b)await B.clickAt(b.x,b.y);else await B.key('Escape','Escape',27);await sleep(500);}
    const rj=await joinServer(B,'lan-rejoin',lanInvite.replace(/:\d+$/, '')+':25565'); check('rejoin',!!rj,{seconds:rj.seconds});
    const token=`lan-rejoin-${Date.now().toString(36)}`;await sendChat(B,token);const after=await waitChat(A,token);check('chatAfterRejoin',!!after,{received:after?.text});
    await Promise.all([A.shot('lan-final'),B.shot('lan-final')]);
    const hostSave = await saveLanHost(A);
    check('hostSave', hostSave.titleScreen && hostSave.workersStopped, hostSave);
  } else {
  const joinedLines = srvSince(srvJoinFrom, /joined the game/);
  check("serverSawJoins", names.every(n => joinedLines.some(l => l.includes(`${n} joined the game`))), {joinedLines});
  report.relay.bothJoined = await relayRuntime();
  let visual = null;
  try { visual = analyzeTerrainPng(await A.shot("terrain")); } catch (e) { visual = {error: String(e)}; }
  report.terrainVisualA = visual && {nonBlackRatio: visual.nonBlackRatio, colorBuckets: visual.colorBuckets, luminanceStdDev: visual.luminanceStdDev, error: visual.error};

  // 3. chat A -> B and B -> A
  const tok1 = `a2b-${Date.now().toString(36)}`, tok2 = `b2a-${Date.now().toString(36)}`;
  await sendChat(A, tok1);
  const rxB = await waitChat(B, tok1);
  const rxA0 = A.chatSeen(tok1);
  const srvA = srvSince(0, new RegExp(`<${names[0]}> ${tok1}`));
  check("chatAToB", !!rxB && srvA.length === 1, {token: tok1, receivedOnB: rxB?.text || null, echoOnA: rxA0?.text || null, serverLog: srvA});
  await sleep(1000);
  await sendChat(B, tok2);
  const rxA = await waitChat(A, tok2);
  const srvB = srvSince(0, new RegExp(`<${names[1]}> ${tok2}`));
  check("chatBToA", !!rxA && srvB.length === 1, {token: tok2, receivedOnA: rxA?.text || null, serverLog: srvB});
  await Promise.all([A.shot("chat"), B.shot("chat")]);

  // 4. scene setup via server console: platform, gold block in A's hand, opposed crosshairs
  const base = sa.player; const cx = Math.floor(base.x), cz = Math.floor(base.z), py = 200;
  const target = {x: cx, y: py, z: cz + 3}, placed = {x: cx, y: py + 1, z: cz + 3};
  await srvCmd(`fill ${cx - 15} ${py} ${cz - 15} ${cx + 16} ${py} ${cz + 16} minecraft:smooth_stone`, /Successfully filled|Filled|filled/, 15000);
  await srvCmd(`give ${names[0]} minecraft:gold_block 64`, /Gave|gave/, 5000);
  const pitch = (Math.atan2(1.62, 3) * 180 / Math.PI).toFixed(1);
  await srvCmd(`tp ${names[0]} ${cx + 0.5} ${py + 1} ${cz + 0.5} 0 ${pitch}`, /Teleported/, 5000);
  await srvCmd(`tp ${names[1]} ${cx + 0.5} ${py + 1} ${cz + 6.5} 180 ${pitch}`, /Teleported/, 5000);
  const posed = await waitFor(async () => {
    const [a, b] = await Promise.all([A.state(), B.state()]);
    const near = (s, z) => s?.player && Math.abs(s.player.x - (cx + 0.5)) < 0.3 && Math.abs(s.player.z - (cz + z)) < 0.3 && Math.abs(s.player.y - (py + 1)) < 0.3;
    const aimed = (s, tz) => s?.hit?.type && blockNums(s.hit.pos).join() === [target.x, target.y, target.z].join();
    return near(a, 0.5) && near(b, 6.5) && aimed(a) && aimed(b) ? {a, b} : null;
  }, 30000, 250);
  report.scene = {platform: {y: py, center: [cx, cz]}, target, placed, pitch, posed: posed ? {A: posed.a.player, hitA: posed.a.hit, B: posed.b.player, hitB: posed.b.hit} : null};
  if (!posed) throw new Error(`scene not established: A=${JSON.stringify(await A.state())} B=${JSON.stringify(await B.state())}`);
  const heldItem = posed.a.player.item;
  await sleep(1500);
  await Promise.all([A.shot("scene-before"), B.shot("scene-before")]);
  const canvasCenter = async pg => pg.ev("(()=>{const r=document.querySelector('canvas').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})()");

  // 5. A places the block (right click), B must see it at the same position
  const cA = await canvasCenter(A);
  const srvPlaceFrom = srvLines.length;
  const tPlace = Date.now();
  await A.clickAt(cA.x, cA.y, "right", 80);
  const isPlacedHit = s => s?.hit && blockNums(s.hit.pos).join() === [placed.x, placed.y, placed.z].join() && /gold_block/.test(String(s.hit.state));
  const seenA = await waitFor(async () => { const s = await A.state(); return isPlacedHit(s) ? s : null; }, 10000, 50);
  const placeMsA = seenA ? Date.now() - tPlace : null;
  const seenB = await waitFor(async () => { const s = await B.state(); return isPlacedHit(s) ? s : null; }, 15000, 50);
  const placeMsB = seenB ? Date.now() - tPlace : null;
  const srvPlace = await srvCmd(`execute if block ${placed.x} ${placed.y} ${placed.z} minecraft:gold_block`, /Test (passed|failed)/, 5000).catch(e => ({line: String(e)}));
  await sleep(500);
  await Promise.all([A.shot("block-placed"), B.shot("block-placed")]);
  check("blockPlace", !!seenA && !!seenB && /Test passed/.test(srvPlace.line || ""), {heldItem, placedAt: placed, aSawMs: placeMsA, bSawMs: placeMsB, aHit: seenA?.hit, bHit: seenB?.hit, serverCheck: srvPlace.line});

  // 6. A breaks it (left click), B must see the platform again
  const isRestoredHit = s => s?.hit && blockNums(s.hit.pos).join() === [target.x, target.y, target.z].join() && /smooth_stone/.test(String(s.hit.state));
  const tBreak = Date.now();
  await A.clickAt(cA.x, cA.y, "left", 150);
  const brokenA = await waitFor(async () => { const s = await A.state(); return isRestoredHit(s) ? s : null; }, 10000, 50);
  const breakMsA = brokenA ? Date.now() - tBreak : null;
  const brokenB = await waitFor(async () => { const s = await B.state(); return isRestoredHit(s) ? s : null; }, 15000, 50);
  const breakMsB = brokenB ? Date.now() - tBreak : null;
  const srvBreak = await srvCmd(`execute if block ${placed.x} ${placed.y} ${placed.z} minecraft:air`, /Test (passed|failed)/, 5000).catch(e => ({line: String(e)}));
  await sleep(500);
  await Promise.all([A.shot("block-broken"), B.shot("block-broken")]);
  check("blockBreak", !!brokenA && !!brokenB && /Test passed/.test(srvBreak.line || ""), {aSawMs: breakMsA, bSawMs: breakMsB, aHit: brokenA?.hit, bHit: brokenB?.hit, serverCheck: srvBreak.line});

  // 7. both move for SOAK_MS: strafe/forward-back patterns (kept on the platform), sampled every second
  report.relay.soakStart = await relayRuntime();
  const mv = {A: [], B: []};
  const patterns = {A: ["KeyD", "KeyA", "KeyW", "KeyS"], B: ["KeyA", "KeyD", "KeyS", "KeyW"]};
  const soakStart = Date.now();
  let step = 0, lastSample = 0;
  const jump = async pg => { await pg.holdDown(" ", "Space", 32); await sleep(60); await pg.holdUp(" ", "Space", 32); };
  while (Date.now() - soakStart < soakMs) {
    const code = c => patterns[c][step % 4];
    const keyA = KEYS[code("A")], keyB = KEYS[code("B")];
    await Promise.all([A.holdDown(keyA.key, code("A"), keyA.vk), B.holdDown(keyB.key, code("B"), keyB.vk)]);
    const holdEnd = Date.now() + 700;
    while (Date.now() < holdEnd) {
      if (Date.now() - lastSample >= 1000) {
        lastSample = Date.now();
        const [a, b] = await Promise.all([A.state(), B.state()]);
        mv.A.push({t: (Date.now() - soakStart) / 1000, screen: a?.screen, level: a?.level, chunks: a?.chunks, p: a?.player && [a.player.x, a.player.y, a.player.z]});
        mv.B.push({t: (Date.now() - soakStart) / 1000, screen: b?.screen, level: b?.level, chunks: b?.chunks, p: b?.player && [b.player.x, b.player.y, b.player.z]});
      }
      await sleep(100);
    }
    await Promise.all([A.holdUp(keyA.key, code("A"), keyA.vk), B.holdUp(keyB.key, code("B"), keyB.vk)]);
    step++;
    if (step % 6 === 0) await Promise.all([jump(A), jump(B)]);
    await sleep(250);
  }
  report.relay.soakEnd = await relayRuntime();
  report.movement = mv;
  const pathLen = pts => pts.filter(Boolean).reduce((acc, p, i, a) => i ? acc + Math.hypot(p[0] - a[i - 1][0], p[2] - a[i - 1][2]) : 0, 0);
  const span = pts => { const xs = pts.filter(Boolean); return xs.length ? {x: Math.max(...xs.map(p => p[0])) - Math.min(...xs.map(p => p[0])), z: Math.max(...xs.map(p => p[2])) - Math.min(...xs.map(p => p[2]))} : null; };
  const pathA = pathLen(mv.A.map(s => s.p)), pathB = pathLen(mv.B.map(s => s.p));
  const stayed = side => mv[side].every(s => s.level && !/Disconnected/.test(s.screen));
  const [ma, mb] = await Promise.all([A.state(), B.state()]);
  // Server-authoritative position of both players must match what the clients show (moves crossed the relay).
  const readPos = async name => {
    const r = await srvCmd(`data get entity ${name} Pos`, new RegExp(`${name} has the following entity data: \\[(-?[\\d.]+)d, (-?[\\d.]+)d, (-?[\\d.]+)d\\]`), 5000);
    const m = new RegExp(`\\[(-?[\\d.]+)d, (-?[\\d.]+)d, (-?[\\d.]+)d\\]`).exec(r.line); return m ? m.slice(1).map(Number) : null;
  };
  await sleep(1000);
  const [spA, spB] = [await readPos(names[0]), await readPos(names[1])];
  const dist = (p, q) => p && q ? Math.hypot(p[0] - q[0], p[2] - q[2]) : null;
  const dA = dist(spA, ma?.player && [ma.player.x, 0, ma.player.z]), dB = dist(spB, mb?.player && [mb.player.x, 0, mb.player.z]);
  await Promise.all([A.shot("after-soak"), B.shot("after-soak")]);
  check("movement60s", soakMs >= 60000 && pathA > 10 && pathB > 10 && stayed("A") && stayed("B") && dA !== null && dA < 2 && dB !== null && dB < 2,
    {soakMs, samples: mv.A.length, pathA, pathB, spanA: span(mv.A.map(s => s.p)), spanB: span(mv.B.map(s => s.p)), serverPosA: spA, serverPosB: spB, clientVsServerA: dA, clientVsServerB: dB,
      minChunksA: Math.min(...mv.A.map(s => s.chunks || 0)), minChunksB: Math.min(...mv.B.map(s => s.chunks || 0))});

  // 8. A leaves, B stays, A rejoins
  const srvLeaveFrom = srvLines.length;
  // Chrome may use the first Escape to release the pointer lock the game's canvas holds after the mouse
  // clicks of the block test, so press until the pause menu opens and record how many presses it took.
  let escapes = 0;
  while (escapes < 4) {
    escapes++; await A.key("Escape", "Escape", 27);
    const sc = await A.waitScreen(/PauseScreen/, 3000);
    await log("escape", `A press ${escapes} -> ${sc || JSON.stringify((await A.state())?.screen)}`);
    if (sc) break;
  }
  report.escapePressesToPause = escapes;
  if (!/PauseScreen/.test(await A.screen())) throw new Error("A: PauseScreen did not open");
  await sleep(500); await A.shot("pause");
  await A.clickWidget("Disconnect", 5000);
  const leftState = await waitFor(async () => { const s = await A.state(); return s && !s.level && /JoinMultiplayerScreen|TitleScreen|DisconnectedScreen/.test(s.screen) ? s : null; }, 30000, 250);
  const leftLine = await waitSrv(new RegExp(`${names[0]} left the game`), srvLeaveFrom, 15000, "A left the game").catch(() => null);
  // B must stay connected for a while after A is gone.
  const bGone = [];
  for (let i = 0; i < 8; i++) { const s = await B.state(); bGone.push({level: s?.level, screen: s?.screen, chunks: s?.chunks, p: s?.player && [s.player.x, s.player.z]}); await sleep(1000); }
  await B.shot("while-A-away");
  const relayDuringAway = await relayRuntime();
  const bLeftLine = srvSince(srvLeaveFrom, new RegExp(`${names[1]} (left the game|lost connection)`));
  check("leaveKeepsB", !!leftState && !!leftLine && bGone.every(s => s.level && !/Disconnected/.test(s.screen)) && bLeftLine.length === 0 && Number(relayDuringAway.activeTunnelLeases) === 1,
    {aScreenAfterLeave: leftState?.screen, serverLeaveLine: leftLine?.line, bSamples: bGone, bLeftLines: bLeftLine, activeTunnelLeases: relayDuringAway.activeTunnelLeases});
  // back to the server list and rejoin
  for (let i = 0; i < 6; i++) {
    const sc = await A.screen();
    if (/JoinMultiplayerScreen/.test(sc)) break;
    const b = await A.widget({text: "Back to Server List"}, 500) || await A.widget({text: "Back"}, 500) || await A.widget({text: "Cancel"}, 500) || await A.widget({text: "Done"}, 500);
    if (b) await A.clickAt(b.x, b.y); else await A.key("Escape", "Escape", 27);
    await sleep(1500);
  }
  const srvRejoinFrom = srvLines.length;
  const rj = await joinServer(A, "join2");
  await sleep(4000);
  const [ra, rb] = await Promise.all([A.state(), B.state()]);
  const rejoinedLine = srvSince(srvRejoinFrom, new RegExp(`${names[0]} joined the game`));
  const relayRejoin = await relayRuntime();
  await Promise.all([A.shot("rejoined"), B.shot("rejoined")]);
  check("rejoin", !!ra?.level && !!ra.player && !ra.screen && !!rb?.level && !!rb.player && rejoinedLine.length === 1 && Number(relayRejoin.activeTunnelLeases) === 2,
    {seconds: rj.seconds, aChunks: ra?.chunks, bStillPlay: !!rb?.level, serverLine: rejoinedLine, activeTunnelLeases: relayRejoin.activeTunnelLeases, aPos: ra?.player});
  // chat after rejoin, A -> B
  const tok3 = `rj-${Date.now().toString(36)}`;
  await sendChat(A, tok3);
  const rxB3 = await waitChat(B, tok3);
  check("chatAfterRejoin", !!rxB3, {token: tok3, receivedOnB: rxB3?.text || null});
  await Promise.all([A.shot("final"), B.shot("final")]);
  }
} catch (e) {
  await capture?.stop("scenario-error");
  report.error = String(e.stack || e);
  await log("HARNESS-ERROR", report.error);
  for (const pg of pages) { try { report[`errorState${pg.label}`] = await pg.state(); await pg.shot("error"); } catch {} }
} finally {
  report.relay.final = await relayRuntime();
  const rt = report.relay.final;
  const bridgeErrLines = bridgeLog.filter(l => !/GAIUS_BRIDGE_TOKEN is unset/.test(l) && (/ err /.test(l) || /error|EPIPE|ECONNRESET|write after end/i.test(l)));
  const unexpectedOpaque = Math.max(0, Number(rt.keepAliveProxyOpaqueTransitions || 0) - Number(rt.keepAliveProxyEncryptionOpaqueTransitions || 0));
  check("relayClean", !rt.error && Number(rt.keepAliveProxyWriteErrors) === 0 && Number(rt.serverFrameSendErrors || 0) === 0 && unexpectedOpaque === 0 && bridgeErrLines.length === 0,
    {keepAliveProxyWriteErrors: rt.keepAliveProxyWriteErrors, serverFrameSendErrors: rt.serverFrameSendErrors, serverFrameSendErrorsAfterClose: rt.serverFrameSendErrorsAfterClose, opaque: rt.keepAliveProxyOpaqueTransitions,
      encryptionOpaque: rt.keepAliveProxyEncryptionOpaqueTransitions, unexpectedOpaque,
      keepAliveProxyWriteBackpressure: rt.keepAliveProxyWriteBackpressure, proxiedKeepAlives: rt.proxiedKeepAlives, maxGapMillis: rt.proxiedKeepAliveMaxGapMillis, profilesSelected777: rt.profilesSelected777,
      activeTunnelLeases: rt.activeTunnelLeases, bridgeErrLines: bridgeErrLines.slice(0, 20)});
  const allExc = pages.flatMap(p => p.exceptions.map(x => ({page: p.label, ...x})));
  check("noUncaughtExceptions", allExc.length === 0, allExc.slice(0, 10));
  report.consoleErrors = Object.fromEntries(pages.map(p => [p.label, p.consoleErrors.slice(0, 20)]));
  // Chrome runs with a closed proxy (chromeIsolationArgs), so a request to a non-loopback host can only
  // fail. The product still ATTEMPTS some (relay registry discovery); the acceptance requires that none
  // of them got a response and that every WebSocket went to loopback.
  const attempts = pages.flatMap(p => p.attempts.map(a => ({page: p.label, ...a})));
  const reached = pages.flatMap(p => p.reached.map(a => ({page: p.label, ...a})));
  const wsUrls = pages.flatMap(p => p.sockets.map(s => `${p.label}:${s.url}`));
  const wsNonLoop = wsUrls.filter(u => !isLoopbackUrl(u.slice(2)));
  const nonLoopHosts = pages.flatMap(p => [...p.hosts].filter(h => !isLoopbackUrl("http://" + h)).map(h => `${p.label}:${h}`));
  const unaccounted = nonLoopHosts.filter(h => !attempts.some(a => `${a.page}:${new URL(a.url).host}` === h));
  check("loopbackOnly", reached.length === 0 && wsNonLoop.length === 0 && unaccounted.length === 0,
    {nonLoopbackResponsesReceived: reached, nonLoopbackSockets: wsNonLoop, blockedNonLoopbackAttempts: attempts, hostsWithoutFailure: unaccounted, webSockets: wsUrls});
  const shots = Object.fromEntries(pages.map(p => [p.label, p.shots]));
  const needed = lanMode ? ["title", "joined", "scene-before", "block-placed", "block-broken", "lan-final"] : ["joined", "scene-before", "block-placed", "block-broken", "after-soak", "final"];
  check("screenshots", pages.length === 2 && pages.every(p => needed.every(n => p.shots.includes(`${p.label}-${n}.png`))), shots);
  report.chat = Object.fromEntries(pages.map(p => [p.label, p.chat.slice(0, 40)]));
  report.serverLogTail = srvLines.slice(-40);
  const failed = Object.entries(report.checks).filter(([, v]) => !v.ok).map(([k]) => k);
  const required = lanMode ? ['bothBoot','bothJoin','hostOpensLan','joinerInWorld','chatAToB','chatBToA','blockPlace','blockBreak','leaveRejoin','rejoin','chatAfterRejoin','hostSave'] : ["bothBoot", "bothJoin", "serverSawJoins", "chatAToB", "chatBToA", "blockPlace", "blockBreak", "movement60s", "leaveKeepsB", "rejoin", "chatAfterRejoin"];
  const missing = required.filter(k => !report.checks[k]);
  report.failed = failed; report.missing = missing;
  report.verdict = !report.error && failed.length === 0 && missing.length === 0 ? "PASS" : "FAIL";
  await writeFile(join(out, "report.json"), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({verdict: report.verdict, failed, missing, error: report.error?.split("\n")[0], checks: Object.fromEntries(Object.entries(report.checks).map(([k, v]) => [k, v.ok]))}));
  await cleanup();
  process.exit(report.verdict === "PASS" ? 0 : 1);
}
