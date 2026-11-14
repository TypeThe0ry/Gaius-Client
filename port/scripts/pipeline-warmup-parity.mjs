#!/usr/bin/env node
// In-session pixel + program parity of the pipeline warm-up (BrowserPipelineWarmup).
//   node port/scripts/pipeline-warmup-parity.mjs <page.html> <outdir> [--query "?runtime=wasm&gpu=webgl2&gaiusKernels=0"]
// One page, one random world (creative, spectator, clear weather, noon, tick freeze, camera 12 blocks above
// spawn). Five F3+T reloads in the same session, the arm chosen through globalThis.__gaiusPipelineWarmupConfig:
//   R1 vanilla order (gaiusPipelineWarmup=0), R2 deferred (default), R3 vanilla, R4 everything deferred
//   (gaiusPipelineEager=none: most pipelines compile on demand on their first draw), R5 deferred.
// After each reload: wait for the warm-up, 20 s for the chunk sections to re-mesh, three screenshots 1.2 s apart.
// Verdict PASS: the signatures (hash of the shader texts and attribute bindings) of the programs linked by each
// reload are the same multiset as R1's, and every screenshot equals R1's first one outside the noise mask
// (pixels that change between the shots of one arm, grown by 3 px, plus the rows above 200 where the sky and
// the clouds drift with real time even with the tick frozen); the mask must stay under half of the frame.
import {spawn} from "node:child_process";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import {pathToFileURL} from "node:url";
import {chromeLaunchArgs, resolveChromeBinary} from "./chrome-binary.mjs";
import {linuxGpuEnvironment} from "./linux-gpu-env.mjs";
import {decodePng} from "../../tools/terrain-visual-metrics.mjs";

const args = process.argv.slice(2);
const flag = (n, d) => { const i = args.indexOf(n); if (i < 0) return d; const v = args[i + 1]; args.splice(i, 2); return v; };
const query = flag("--query", "?runtime=wasm&gpu=webgl2&gaiusKernels=0");
const [page, outdir] = args;
await mkdir(outdir, {recursive: true});
const url = pathToFileURL(resolve(page)).href + query;
const sleep = (ms) => new Promise((d) => setTimeout(d, ms));
const profile = await mkdtemp(join(tmpdir(), "gaius-parity-"));
const chrome = spawn(resolveChromeBinary(), [...chromeLaunchArgs(), "--headless=new", "--remote-debugging-port=0", "--remote-allow-origins=*",
  `--user-data-dir=${profile}`, "--ignore-gpu-blocklist", "--password-store=basic", "--no-first-run", "--no-default-browser-check",
  "--window-size=960,640", "about:blank"], {stdio: ["ignore", "ignore", "pipe"], env: linuxGpuEnvironment(process.env)});
const report = {url, stages: {}, shots: {}, verdict: null};
let socket;
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
try {
  let endpoint;
  for (let i = 0; i < 150 && !endpoint; i++) {
    try { const [port, path] = (await readFile(join(profile, "DevToolsActivePort"), "utf8")).trim().split(/\r?\n/); endpoint = {port, path}; } catch { await sleep(100); }
  }
  const targets = await (await fetch(`http://127.0.0.1:${endpoint.port}/json/list`)).json();
  socket = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
  await new Promise((d, f) => { socket.addEventListener("open", d, {once: true}); socket.addEventListener("error", f, {once: true}); });
  let seq = 0; const pending = new Map();
  socket.addEventListener("message", ({data}) => { const m = JSON.parse(String(data)); const w = m.id && pending.get(m.id); if (w) { pending.delete(m.id); m.error ? w.fail(new Error(m.error.message)) : w.done(m.result || {}); } });
  const send = (method, params = {}) => new Promise((done, fail) => { const id = ++seq; pending.set(id, {done, fail}); socket.send(JSON.stringify({id, method, params})); });
  const evaluate = async (expression) => { const r = await send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true}); if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + " " + (r.exceptionDetails.exception?.description || "")); return r.result?.value; };
  const waitFor = async (expr, ms, what) => { const end = Date.now() + ms; while (Date.now() < end) { const v = await evaluate(expr).catch(() => null); if (v) return v; await sleep(250); } throw new Error("timeout: " + what); };
  const state = `(window.__gaiusMinecraftState || {})`;
  const keyEv = (type, key, code, vk, text, modifiers = 0) => send("Input.dispatchKeyEvent", {type, key, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers, ...(text && type === "keyDown" ? {text, unmodifiedText: text} : {})});
  const press = async (key, code, vk, text, modifiers = 0) => { await keyEv("keyDown", key, code, vk, text, modifiers); await sleep(40); await keyEv("keyUp", key, code, vk, "", modifiers); };
  const KEYS = {" ": ["Space", 32], "/": ["Slash", 191], "-": ["Minus", 189], ".": ["Period", 190], "@": ["Digit2", 50, 8], "_": ["Minus", 189, 8], "~": ["Backquote", 192, 8]};
  const keyDef = (ch) => /^[a-z]$/i.test(ch) ? {code: "Key" + ch.toUpperCase(), vk: ch.toUpperCase().charCodeAt(0), mod: ch !== ch.toLowerCase() ? 8 : 0}
    : /^[0-9]$/.test(ch) ? {code: "Digit" + ch, vk: ch.charCodeAt(0), mod: 0} : (KEYS[ch] ? {code: KEYS[ch][0], vk: KEYS[ch][1], mod: KEYS[ch][2] || 0} : {code: "Unidentified", vk: 0, mod: 0});
  const mouse = async (x, y) => { await send("Input.dispatchMouseEvent", {type: "mouseMoved", x, y}); await send("Input.dispatchMouseEvent", {type: "mousePressed", button: "left", buttons: 1, clickCount: 1, x, y}); await send("Input.dispatchMouseEvent", {type: "mouseReleased", button: "left", buttons: 0, clickCount: 1, x, y}); };
  const click = async (label) => {
    const w = await waitFor(`(() => { const s = ${state}; const f = (s.screenWidgets || []).find(v => v.visible !== false && v.active !== false && String(v.text || '').trim().toLowerCase() === ${JSON.stringify(label.toLowerCase())}); const r = document.querySelector('canvas')?.getBoundingClientRect(); const z = s.screenSize; if (!f || !r || !z?.width || s.overlay) return null; return {x: r.left + (f.x + f.width / 2) * r.width / z.width, y: r.top + (f.y + f.height / 2) * r.height / z.height}; })()`, 60000, "widget " + label);
    await mouse(w.x, w.y);
  };
  const command = async (text) => {
    await press("t", "KeyT", 84, "t");
    await waitFor(`/ChatScreen/.test(String(${state}.screen||''))`, 8000, "chat");
    await sleep(200);
    for (const ch of text) { const d = keyDef(ch); await press(ch, d.code, d.vk, ch, d.mod); await sleep(35); }
    await sleep(150); await press("Enter", "Enter", 13);
    await waitFor(`!/ChatScreen/.test(String(${state}.screen||''))`, 8000, "chat closed");
    await sleep(500);
  };
  const shot = async (name) => {
    const buf = Buffer.from((await send("Page.captureScreenshot", {format: "png"})).data, "base64");
    await writeFile(join(outdir, name + ".png"), buf);
    report.shots[name] = decodePng(buf);
    return name;
  };
  const sameImg = (a, b) => a.width === b.width && a.height === b.height && Buffer.compare(Buffer.from(a.rgba), Buffer.from(b.rgba)) === 0;
  // The first screenshot that equals the one before it (1.5 s apart): the frame has settled (chunk sections re-meshed, toasts gone).
  const stableShot = async (name) => {
    let previous = null;
    const t = Date.now();
    for (let i = 0; i < 60; i++) {
      const buf = Buffer.from((await send("Page.captureScreenshot", {format: "png"})).data, "base64");
      const img = decodePng(buf);
      if (previous && sameImg(previous, img)) { await writeFile(join(outdir, name + ".png"), buf); report.shots[name] = img; report.settleMs = report.settleMs || {}; report.settleMs[name] = Date.now() - t; return name; }
      if (previous && i % 10 === 9) {
        let n = 0, x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
        for (let y = 0; y < img.height; y++) for (let x = 0; x < img.width; x++) { const k = (y * img.width + x) * 4; if (previous.rgba[k] !== img.rgba[k] || previous.rgba[k + 1] !== img.rgba[k + 1] || previous.rgba[k + 2] !== img.rgba[k + 2]) { n++; x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); } }
        log("unsettled", name, i, JSON.stringify({n, x0, y0, x1, y1}));
        await writeFile(join(outdir, name + `-unsettled${i}.png`), buf);
      }
      previous = img;
      await sleep(1500);
    }
    throw new Error("frame never settled: " + name);
  };
  const warm = () => evaluate("JSON.stringify(Object.assign({}, window.__gaiusPipelineWarmup || {}, {misses: (window.__gaiusPipelineWarmup||{}).misses ? window.__gaiusPipelineWarmup.misses.length : 0}))").then((s) => JSON.parse(s));

  await send("Page.enable"); await send("Runtime.enable");
  const probe = (await readFile(new URL("./pipeline-warmup-probe.js", import.meta.url), "utf8"));
  await send("Page.addScriptToEvaluateOnNewDocument", {source: probe + "\nglobalThis.__gaiusCapture = {enabled: true, animationTick: 100, optionsObserved: {}};\ntry { localStorage.setItem('gaius.playerName', 'ParityProbe'); } catch (e) {}"});
  await send("Page.navigate", {url});
  await waitFor(`/TitleScreen$/.test(String(${state}.screen||'')) && !${state}.overlay`, 120000, "title");
  log("title");
  const t0 = await evaluate("window.__sig.programs.length");
  await click("Singleplayer");
  await waitFor(`/(SelectWorldScreen|CreateWorldScreen)$/.test(String(${state}.screen||''))`, 60000, "world select");
  if (/SelectWorldScreen/.test(await evaluate(`String(${state}.screen||'')`))) await click("Create New World");
  await waitFor(`/CreateWorldScreen$/.test(String(${state}.screen||''))`, 30000, "create world");
  // Game Mode -> Creative (allows commands): the button cycles Survival / Hardcore / Creative.
  for (let i = 0; i < 4; i++) {
    const w = await evaluate(`(() => { const s = ${state}; const f = (s.screenWidgets || []).find(v => /game mode/i.test(String(v.text || ''))); const r = document.querySelector('canvas')?.getBoundingClientRect(); const z = s.screenSize; if (!f || !r || !z?.width) return null; return {text: f.text, x: r.left + (f.x + f.width / 2) * r.width / z.width, y: r.top + (f.y + f.height / 2) * r.height / z.height}; })()`);
    if (!w || /creative/i.test(w.text)) { report.gameMode = w && w.text; break; }
    await mouse(w.x, w.y); await sleep(500);
  }
  await click("Create New World");
  await waitFor(`${state}.level && !${state}.screen && !${state}.overlay`, 180000, "in world");
  log("in world");
  for (const c of ["/gamemode spectator", "/gamerule advance_time false", "/time set 6000", "/weather clear", "/tp @s ~ ~12 ~ 20 45"]) { await command(c); log("cmd", c); }
  await command("/tick freeze");
  // settle: loaded chunks stable for 8 s
  let last = -1, stable = 0;
  for (let i = 0; i < 120 && stable < 8; i++) { const n = await evaluate(`${state}.loadedChunkCount || 0`); stable = n === last ? stable + 1 : 0; last = n; await sleep(1000); }
  report.loadedChunks = last; log("chunks", last);
  await sleep(15000);
  const waitWarm = () => waitFor("(() => { const w = window.__gaiusPipelineWarmup; return !w || w.doneAt || w.reason !== 'deferred' || w.queued === 0; })()", 120000, "warm-up done");
  await waitWarm();
  log("initial boot warm-up", JSON.stringify(await warm()));
  const reload = async (config) => {
    await evaluate(`window.__gaiusPipelineWarmupConfig = ${JSON.stringify(config)}; window.__sig.mark('reload'); window.__reloadsBefore = (window.__gaiusResourceReloadTimings || []).length; true`);
    const mark = await evaluate("window.__sig.programs.length");
    await keyEv("keyDown", "F3", "F3", 114, ""); await sleep(60);
    await press("t", "KeyT", 84, "t"); await sleep(60); await keyEv("keyUp", "F3", "F3", 114, "");
    await waitFor("(window.__gaiusResourceReloadTimings || []).length > window.__reloadsBefore", 30000, "reload started");
    // finished: the listener entries stop growing for 4 s and no overlay is reported
    let lastCount = -1, quiet = 0;
    for (let i = 0; i < 240 && quiet < 8; i++) {
      const n = await evaluate("(window.__gaiusResourceReloadTimings || []).length");
      const overlay = await evaluate(`!!${state}.overlay`);
      quiet = (n === lastCount && !overlay) ? quiet + 1 : 0; lastCount = n; await sleep(500);
    }
    if (quiet < 8) throw new Error("reload did not finish");
    return mark;
  };
  // Every reload: same settle procedure (warm-up done, 20 s for the chunk sections to re-mesh, then the first stable frame).
  const sigs = {};
  const arms = [["R1vanilla", {gaiusPipelineWarmup: "0"}], ["R2deferred", {}], ["R3vanilla", {gaiusPipelineWarmup: "0"}], ["R4none", {gaiusPipelineEager: "none"}], ["R5deferred", {}]];
  for (const [name, config] of arms) {
    const m = await reload(config);
    report.stages[name + "-immediate"] = {warm: await warm()};
    await waitWarm();
    await sleep(20000);
    report.stages[name] = {warm: await warm(), shots: ["a", "b", "c"]};
    for (const k of ["a", "b", "c"]) { await shot(`${name}-${k}`); await sleep(1200); }
    sigs[name] = await evaluate(`window.__sig.since(${m})`);
    log("stage", name, JSON.stringify(report.stages[name].warm), "immediate", JSON.stringify(report.stages[name + "-immediate"].warm));
  }
  const sorted = (l) => [...l].sort();
  const sameList = (x, y) => JSON.stringify(sorted(x)) === JSON.stringify(sorted(y));
  report.programs = Object.fromEntries(Object.entries(sigs).map(([k, v]) => [k, v.length]));
  report.programsEqualToR1 = Object.fromEntries(Object.entries(sigs).map(([k, v]) => [k, sameList(sigs.R1vanilla, v)]));
  const SKY_ROWS = 200; // clouds drift with real time even with the tick frozen: rows above this hold the sky of the pitch-50 camera
  const diff = (a, b) => { if (a.width !== b.width || a.height !== b.height) return -1; let n = 0, max = 0, below = 0, belowMax = 0; for (let i = 0; i < a.rgba.length; i += 4) { const d = Math.max(Math.abs(a.rgba[i] - b.rgba[i]), Math.abs(a.rgba[i + 1] - b.rgba[i + 1]), Math.abs(a.rgba[i + 2] - b.rgba[i + 2])); if (d) { n++; if (d > max) max = d; if (Math.floor(i / 4 / a.width) >= SKY_ROWS) { below++; if (d > belowMax) belowMax = d; } } } return {pixels: n, maxDelta: max, pixelsBelowSky: below, maxDeltaBelowSky: belowMax}; };
  const ref = report.shots["R1vanilla-a"];
  // Noise mask: pixels that change between the three shots of one arm (water, drifting clouds, ...), grown by 3 px.
  const W = ref.width, H = ref.height;
  const mask = new Uint8Array(W * H);
  const armNames = Object.keys(sigs);
  for (const arm of armNames) {
    const [a, b, c] = ["a", "b", "c"].map((k) => report.shots[`${arm}-${k}`]);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const i = (y * W + x) * 4;
      if (a.rgba[i] !== b.rgba[i] || a.rgba[i + 1] !== b.rgba[i + 1] || a.rgba[i + 2] !== b.rgba[i + 2]
        || a.rgba[i] !== c.rgba[i] || a.rgba[i + 1] !== c.rgba[i + 1] || a.rgba[i + 2] !== c.rgba[i + 2]) mask[y * W + x] = 1;
    }
  }
  const grown = new Uint8Array(W * H);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (mask[y * W + x]) for (let dy = -3; dy <= 3; dy++) for (let dx = -3; dx <= 3; dx++) { const yy = y + dy, xx = x + dx; if (yy >= 0 && yy < H && xx >= 0 && xx < W) grown[yy * W + xx] = 1; }
  for (let y = 0; y < SKY_ROWS; y++) grown.fill(1, y * W, (y + 1) * W);
  let masked = 0; for (let k = 0; k < W * H; k++) masked += grown[k];
  report.maskedRatio = masked / (W * H);
  const diffOutside = (a, b) => { let n = 0, max = 0, inside = 0; for (let k = 0; k < W * H; k++) { const i = k * 4; const d = Math.max(Math.abs(a.rgba[i] - b.rgba[i]), Math.abs(a.rgba[i + 1] - b.rgba[i + 1]), Math.abs(a.rgba[i + 2] - b.rgba[i + 2])); if (!d) continue; if (grown[k]) inside++; else { n++; if (d > max) max = d; } } return {pixelsOutsideMask: n, maxDeltaOutside: max, pixelsInsideMask: inside}; };
  report.pixelDiffVsR1a = {};
  for (const [name, img] of Object.entries(report.shots)) if (name.startsWith("R")) report.pixelDiffVsR1a[name] = diffOutside(ref, img);
  const nonBlack = (img) => { let n = 0; for (let i = 0; i < img.rgba.length; i += 4) if (img.rgba[i] | img.rgba[i + 1] | img.rgba[i + 2]) n++; return n / (img.rgba.length / 4); };
  report.nonBlackRatio = nonBlack(ref);
  report.verdict = Object.values(report.pixelDiffVsR1a).every((d) => d.pixelsOutsideMask === 0) && report.maskedRatio < 0.5 && Object.values(report.programsEqualToR1).every(Boolean) && report.nonBlackRatio > 0.3 ? "PASS" : "FAIL";
  for (const k of Object.keys(report.shots)) delete report.shots[k];
  await writeFile(join(outdir, "parity.json"), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({verdict: report.verdict, programs: report.programs, programsEqualToR1: report.programsEqualToR1, pixelDiffVsR1a: report.pixelDiffVsR1a, maskedRatio: report.maskedRatio, nonBlackRatio: report.nonBlackRatio, stages: Object.fromEntries(Object.entries(report.stages).map(([k, v]) => [k, v.warm]))}));
} catch (error) {
  console.error("PARITY ERROR", error.stack || error);
  await writeFile(join(outdir, "parity-error.json"), JSON.stringify({error: String(error.stack || error), report}, null, 2)).catch(() => {});
  process.exitCode = 1;
} finally {
  try { socket?.close(); } catch {}
  chrome.kill();
  await rm(profile, {recursive: true, force: true}).catch(() => {});
}
