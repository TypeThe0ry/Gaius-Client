import {createReadStream} from "node:fs";
import {open, writeFile} from "node:fs/promises";
import {join} from "node:path";

const options = [
  'graphicsPreset:"fancy"', "renderDistance:8", "simulationDistance:6", "maxFps:260",
  "enableVsync:true", 'inactivityFpsLimit:"minimized"', 'renderClouds:"true"', "cloudRange:64",
  "ao:true", "cutoutLeaves:true", "vignette:true", "improvedTransparency:false", "weatherRadius:10",
  "chunkSectionFadeInTime:0.0", "prioritizeChunkUpdates:1", "mipmapLevels:4", "maxAnisotropyBit:1",
  "textureFiltering:1", "biomeBlendRadius:2", "particles:0", "entityShadows:true", "bobView:false",
  "guiScale:2", "pauseOnLostFocus:false", "menuBackgroundBlurriness:5", "entityDistanceScaling:1.0",
];
export const performanceOptionsExpression = `(async()=>{
  // Wait for the launcher storage bridge (a large single-file page may still be parsing it),
  // then for its IndexedDB scan, so the scan cannot overwrite the seeded options.
  for(const end=Date.now()+120000;typeof globalThis.__gaiusFsPut!=='function'&&Date.now()<end;)
    await new Promise(resolve=>setTimeout(resolve,25));
  if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady;
  const path='/gaius/options.txt',encoded=${JSON.stringify(Buffer.from(options.join("\n") + "\n").toString("base64"))};
  (globalThis.__gaiusPersistentFiles||(globalThis.__gaiusPersistentFiles=Object.create(null)))[path]=encoded;
  if(typeof globalThis.__gaiusFsPut!=='function')throw new Error('options storage unavailable');
  globalThis.__gaiusFsPut(path,encoded);
  if(globalThis.__gaiusFsFlush)await globalThis.__gaiusFsFlush(true);
  globalThis.__gaiusReleaseBoot();return ${JSON.stringify(options)};
})()`;

// Parse one event at a time. A trace never becomes one JavaScript string or array.
export async function* traceEvents(path) {
  let started = false, depth = 0, quoted = false, escaped = false, event = "", prefix = "";
  for await (const chunk of createReadStream(path, {encoding: "utf8", highWaterMark: 65536})) {
    for (const ch of chunk) {
      if (!started) {
        prefix = (prefix + ch).slice(-128);
        if (/"traceEvents"\s*:\s*\[$/.test(prefix)) started = true;
        continue;
      }
      if (!depth) { if (ch !== "{") continue; depth = 1; event = ch; continue; }
      event += ch;
      if (quoted) {
        if (escaped) escaped = false;
        else if (ch === "\\") escaped = true;
        else if (ch === '"') quoted = false;
      } else if (ch === '"') quoted = true;
      else if (ch === "{" || ch === "[") depth++;
      else if (ch === "}" || ch === "]") depth--;
      if (!depth) { yield JSON.parse(event); event = ""; }
    }
  }
  if (!started || depth) throw new Error("Incomplete trace JSON");
}

function category(frame) {
  const name = frame.functionName || "";
  const url = frame.url || "";
  if (/garbage collector|GC|Scavenge/.test(name)) return "GC";
  if (/quality|post-chain|gl-pass|upscaler/.test(url)) return "quality post-processing";
  if (/Audio|Panner|Gain|audio|OpenAL|Input|Mouse/.test(name + url)) return "input/audio";
  if (/RenderScheduler|mesh|upload|idle/i.test(name + url)) return "idle/upload work";
  if (/WebGL|GPUQueue|GPURender|GPUCommand|drawElements|drawArrays|bufferSubData|texSubImage|setBindGroup|writeBuffer|submit/.test(name)) return "GPU API";
  if (/wasm-to-js|js-to-wasm/.test(name)) return "wasm/JS boundary";
  if (/wasm|\.wasm/.test(url) || /wasm-function|^\$.*_|^java\./.test(name)) return "Java/wasm";
  if (/jspi|\(program\)|\(root\)|\(idle\)/.test(name + url)) return name === "(idle)" ? "idle" : "JSPI/task entry";
  return "JS glue/other";
}

export async function summarizeTrace(path, profile, frames, wallMs) {
  const threads = new Map(), processes = new Map();
  for await (const e of traceEvents(path)) {
    if (e.ph === "M" && e.name === "thread_name") threads.set(`${e.pid}:${e.tid}`, e.args.name);
    if (e.ph === "M" && e.name === "process_name") processes.set(e.pid, e.args.name);
  }
  const allowed = new Set([...threads].filter(([, name]) => /CrRendererMain|CrGpuMain/.test(name)).map(([key]) => key));
  const totals = {}, names = {}, retained = await open(path.replace(/\.json$/, ".main-gpu.ndjson"), "w");
  let events = 0;
  try {
    for await (const e of traceEvents(path)) {
      const key = `${e.pid}:${e.tid}`;
      if (!allowed.has(key) && e.ph !== "M") continue;
      await retained.write(JSON.stringify(e) + "\n");
      events++;
      if (e.ph === "X" && e.dur > 0) {
        const label = threads.get(key);
        const n = `${label}/${e.name}`;
        names[n] = (names[n] || 0) + e.dur / 1000;
        if (e.cat?.split(",").includes("toplevel")) totals[label] = (totals[label] || 0) + e.dur / 1000;
      }
    }
  } finally { await retained.close(); }
  const nodes = new Map(profile.nodes.map(n => [n.id, n]));
  const samples = {}, hot = {};
  for (let i = 0; i < (profile.samples || []).length; i++) {
    const node = nodes.get(profile.samples[i]);
    const ms = (profile.timeDeltas[i] || 0) / 1000;
    const c = category(node.callFrame);
    samples[c] = (samples[c] || 0) + ms;
    const label = `${node.callFrame.functionName} @ ${node.callFrame.url}:${node.callFrame.lineNumber}`;
    hot[label] = (hot[label] || 0) + ms;
  }
  return {frames, wallMs, events, threads: Object.fromEntries(threads), processes: Object.fromEntries(processes),
    taskMs: totals, taskMsPerFrame: Object.fromEntries(Object.entries(totals).map(([k,v]) => [k,v/frames])),
    sampledMs: samples, sampledMsPerFrame: Object.fromEntries(Object.entries(samples).map(([k,v]) => [k,v/frames])),
    hot: Object.entries(hot).sort((a,b) => b[1]-a[1]).slice(0,80),
    traceHot: Object.entries(names).sort((a,b) => b[1]-a[1]).slice(0,60)};
}

export async function captureMainThreadWindow({cdp, evaluate, output, mode, durationMs, sleep}) {
  const snapshot = `(()=>({backend:globalThis.__gaiusGpuBackend,player:globalThis.__gaiusMinecraftState?.player,
    frame:globalThis.__gaiusFrameTelemetry,gl:globalThis.__gaiusGLStats,gpu:globalThis.GaiusWebGpu?.stats,
    presented:globalThis.__gaiusGlFrameGuard?.frames,quality:globalThis.GaiusQuality?.stats}))()`;
  await cdp.send("Performance.enable");
  const before = await evaluate(snapshot);
  const metric = async () => Object.fromEntries((await cdp.send("Performance.getMetrics")).metrics.map(x => [x.name,x.value]));
  const a = await metric();
  await cdp.send("Profiler.enable");
  await cdp.send("Profiler.setSamplingInterval", {interval: 500});
  let completed;
  const done = new Promise(resolve => {completed = resolve;});
  cdp.on("Tracing.tracingComplete", p => {if (p.stream) completed(p.stream);});
  await cdp.send("Tracing.start", {transferMode:"ReturnAsStream", traceConfig:{recordMode:"recordAsMuchAsPossible",
    includedCategories:["toplevel","v8","blink","gpu","__metadata"], excludedCategories:["*"]}});
  await cdp.send("Profiler.start");
  if (mode === "turning") await evaluate(`globalThis.__gaiusProfileTurn=setInterval(()=>globalThis.__gaiusInput?.look(0.5,0),16);true`);
  await sleep(durationMs);
  if (mode === "turning") await evaluate("clearInterval(globalThis.__gaiusProfileTurn);true");
  const {profile} = await cdp.send("Profiler.stop");
  const after = await evaluate(snapshot), b = await metric();
  await cdp.send("Tracing.end");
  const stream = await done, path = join(output, `${mode}.trace.json`);
  const file = await open(path,"w");
  try {
    for (;;) {
      const chunk = await cdp.send("IO.read",{handle:stream,size:65536});
      await file.write(chunk.base64Encoded ? Buffer.from(chunk.data,"base64") : chunk.data);
      if (chunk.eof) break;
    }
  } finally { await file.close(); await cdp.send("IO.close",{handle:stream}); }
  const wallMs = (b.Timestamp-a.Timestamp)*1000;
  const frames = after.presented-before.presented;
  await writeFile(join(output,`${mode}.cpuprofile`),JSON.stringify(profile));
  const summary = await summarizeTrace(path,profile,frames,durationMs);
  const result = {mode,before,after,metricsWallMs:wallMs,taskDurationMs:(b.TaskDuration-a.TaskDuration)*1000, ...summary};
  await writeFile(join(output,`${mode}.summary.json`),JSON.stringify(result,null,2));
  return result;
}
