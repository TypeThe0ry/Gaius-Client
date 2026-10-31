import assert from "node:assert/strict";
import {readFile, writeFile, mkdir} from "node:fs/promises";
import {spawnSync} from "node:child_process";
import {resolve, join} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
assert.ok(process.env.OUT, "OUT must name the measurement output directory");
const out = resolve(process.env.OUT);
await mkdir(out, {recursive: true});
let source = await readFile(new URL("minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
const seam = "  // Phase 4: terrain settle:";
assert.ok(source.includes(seam));
source = source.slice(0, source.indexOf(seam));
// Heap snapshots can take several minutes once WasmGC and the resource graph are live.
// Keep normal harness commands unchanged while allowing this probe to opt into a longer CDP wait.
source = source.replace('Number(process.env.CDP_TIMEOUT_MS || 20000)',
  'Number(process.env.CDP_TIMEOUT_MS || 600000)');
function replaceOnce(pattern, replacement) {
  assert.equal(source.split(pattern).length, 2, `harness seam changed: ${pattern}`);
  source = source.replace(pattern, replacement);
}
replaceOnce('from "./bench/harness-common.mjs"', `from ${JSON.stringify(pathToFileURL(join(root, "port/scripts/bench/harness-common.mjs")).href)}`);
replaceOnce('from "./worldcycle.mjs"', `from ${JSON.stringify(pathToFileURL(join(root, "port/scripts/worldcycle.mjs")).href)}`);
for (const [sourcePath, targetPath] of [
  ["./reload-memory-gate.mjs", "port/scripts/reload-memory-gate.mjs"],
  ["../../tools/release-performance.mjs", "tools/release-performance.mjs"],
  ["../../tools/gl-frame-guard.mjs", "tools/gl-frame-guard.mjs"],
  ["../../tools/configure-browser-world-seed.mjs", "tools/configure-browser-world-seed.mjs"],
  ["../../tools/deterministic-world-capture.mjs", "tools/deterministic-world-capture.mjs"],
  ["../../tools/stage-capture-quiescence.mjs", "tools/stage-capture-quiescence.mjs"],
  ["../../tools/main-thread-profile.mjs", "tools/main-thread-profile.mjs"],
  ["../../tools/backend-consistency.mjs", "tools/backend-consistency.mjs"],
  ["../../tools/terrain-settle.mjs", "tools/terrain-settle.mjs"],
  ["../../tools/flight-input.mjs", "tools/flight-input.mjs"],
  ["../../tools/stability-soak.mjs", "tools/stability-soak.mjs"],
]) {
  const statement = `from ${JSON.stringify(sourcePath)}`;
  if (source.includes(statement)) replaceOnce(statement,
    `from ${JSON.stringify(pathToFileURL(join(root, targetPath)).href)}`);
}
if (source.includes('from "../../tools/gl-frame-guard.mjs"')) replaceOnce('from "../../tools/gl-frame-guard.mjs"', `from ${JSON.stringify(pathToFileURL(join(root, "tools/gl-frame-guard.mjs")).href)}`);
if (source.includes('from "../../tools/configure-browser-world-seed.mjs"')) replaceOnce('from "../../tools/configure-browser-world-seed.mjs"', `from ${JSON.stringify(pathToFileURL(join(root, "tools/configure-browser-world-seed.mjs")).href)}`);
if (source.includes('from "../../tools/deterministic-world-capture.mjs"')) replaceOnce('from "../../tools/deterministic-world-capture.mjs"', `from ${JSON.stringify(pathToFileURL(join(root, "tools/deterministic-world-capture.mjs")).href)}`);
if (source.includes('join(tmpdir(), "gaius-m4-")')) replaceOnce('join(tmpdir(), "gaius-m4-")', 'join(out, "chrome-")');
if (source.includes('import {createReadStream, statSync, existsSync, readFileSync}')) {
  replaceOnce('import {createReadStream, statSync, existsSync, readFileSync}',
    'import {createReadStream, statSync, existsSync, readFileSync, openSync, writeSync, closeSync}');
}
replaceOnce('let cdp;', `let cdp;
const samplingProfile=[];
const memorySamples=[];
const workerSessions=new Map();
const workerMemory=[];
async function sampleWorkers(){
  for(const [session,url]of workerSessions){
    try{
      const heap=await cdp.send("Runtime.getHeapUsage",{},session);
      const linear=await evaluate(cdp,"globalThis.__gaiusWasmMemory?.buffer.byteLength??null",session);
      workerMemory.push({url,heap,linearBytes:linear,
        linearCoverage:linear===null?"not exposed by worker":"exposed memory only"});
    }catch(error){workerMemory.push({url,error:String(error)});}
  }
  await writeFile(join(out,"workers.json"),JSON.stringify(workerMemory,null,2));
}
async function memorySample(label){
  const heap=await cdp.send("Runtime.getHeapUsage");
  const owners=await evaluate(cdp,\`(()=>{
    const gl=globalThis.__gaiusGL,byTarget={};
    if(gl?.bufferBytes)for(const [id,bytes]of gl.bufferBytes){
      const target=String(gl.bufferWebglTypes?.get(id)||"unclassified");
      byTarget[target]=(byTarget[target]||0)+bytes.byteLength;
    }
    return {linearBytes:globalThis.__gaiusWasmMemory?.buffer.byteLength||0,
      browserMemory:globalThis.__gaiusMemoryTelemetry?.browserMemory||null,
      gl:globalThis.__gaiusGLStats||null,shadowBytesByTarget:byTarget,
      quality:globalThis.__gaiusQualitySettings||null,
      memoryTier:globalThis.__gaiusMemoryTier||null,
      experiment:globalThis.__gaiusMemoryExperiment||null,
      clientDistance:globalThis.__gaiusMinecraftState?.clientDistance||null,
      kernels:globalThis.__gaiusKernels?.telemetry?.()||null,
      sectionBuffers:(()=>{const s=globalThis.__gaiusMemoryTelemetry?.sectionBuffers;if(!s)return null;return {...s,maxMemory:Number(s.maxMemory),perPackBytes:Number(s.perPackBytes),totalBytes:Number(s.totalBytes)}})(),
      linearTelemetry:globalThis.__gaiusMemoryTelemetry?.linearMemory||null,
      level:!!globalThis.__gaiusMinecraftState?.level};
  })()\`);
  const sample={label,seconds:Number(stamp()),heap,...owners};
  sample.totalBytes=heap.usedSize+sample.linearBytes;
  memorySamples.push(sample);
  await writeFile(join(out,"memory.json"),JSON.stringify(memorySamples,null,2));
}
`);
replaceOnce('  await cdp.send("Page.navigate", {url: pageUrl});',
  '  report.measurementUrl=pageUrl + (process.env.MEMORY_QUERY ? "?" + process.env.MEMORY_QUERY : "");\n  if(process.env.HEAP_SAMPLING==="1"){await cdp.send("HeapProfiler.enable");await cdp.send("HeapProfiler.startSampling",{samplingInterval:Number(process.env.HEAP_SAMPLING_INTERVAL||16384),includeObjectsCollectedByMajorGC:true,includeObjectsCollectedByMinorGC:false});}\n  await cdp.send("Page.navigate", {url:report.measurementUrl});');
const injectionFile = process.env.MEMORY_INJECT ? await readFile(resolve(process.env.MEMORY_INJECT), "utf8") : "";
const deviceMemory = process.env.MEMORY_DEVICE_GIB ? Number(process.env.MEMORY_DEVICE_GIB) : null;
assert.ok(deviceMemory === null || (Number.isFinite(deviceMemory) && deviceMemory > 0), "MEMORY_DEVICE_GIB must be positive");
const deviceInjection = deviceMemory === null ? "" : `(() => { const value = ${JSON.stringify(deviceMemory)}; try { Object.defineProperty(Navigator.prototype, "deviceMemory", {configurable: true, get: () => value}); } catch {} try { Object.defineProperty(navigator, "deviceMemory", {configurable: true, get: () => value}); } catch {} })();`;
const injection = deviceInjection + injectionFile;
replaceOnce('    await log("target",',
  '    if(p.targetInfo.type==="worker")workerSessions.set(p.sessionId,p.targetInfo.url);\n    await log("target",');
replaceOnce('await log("navigate", pageUrl);', `await cdp.send("Page.addScriptToEvaluateOnNewDocument",{source:${JSON.stringify(injection)}});
  await log("navigate",pageUrl);`);
replaceOnce('  // Phase 2: Singleplayer', '  await memorySample("title");\n  // Phase 2: Singleplayer');
replaceOnce('    await sleep(500);\n  }\n  await screenshot(cdp, "20-world-entry.png");', '    await memorySample("entry");\n    await sleep(2000);\n  }\n  await screenshot(cdp, "20-world-entry.png");');
source += `
  await memorySample("world-entry");
  for(let tick=0;tick<6;tick++){await sleep(2000);await memorySample("steady");}
  await screenshot(cdp,"30-steady.png");
  await sampleWorkers();
  await cdp.send("HeapProfiler.enable");
  await cdp.send("HeapProfiler.collectGarbage");
  await memorySample("post-gc");
  if(process.env.HEAP_SAMPLING==="1"){
    await sleep(Number(process.env.HEAP_SAMPLING_MS||120000));
    const profile=await cdp.send("HeapProfiler.stopSampling");
    function summarizeProfile(root){const rows=[];const stack=[root];while(stack.length){const node=stack.pop();rows.push({functionName:node.callFrame?.functionName||"(anonymous)",url:node.callFrame?.url||"",lineNumber:node.callFrame?.lineNumber??-1,selfSize:node.selfSize||0});for(const child of node.children||[])stack.push(child);}return rows;}
    const rows=summarizeProfile(profile.profile.head).sort((a,b)=>b.selfSize-a.selfSize);
    await writeFile(join(out,"sampling-profile.json"),JSON.stringify({sampledBytes:profile.profile.samples?.reduce((n,row)=>n+(row.size||0),0)||0,top:rows.slice(0,200),samples:profile.profile.samples||[]},null,2));
  }
  if(process.env.HEAP_SNAPSHOT!=="0"){
    const file=openSync(join(out,"world.heapsnapshot"),"w");
    cdp.on("HeapProfiler.addHeapSnapshotChunk",event=>writeSync(file,event.chunk));
    try{await cdp.send("HeapProfiler.enable");await cdp.send("HeapProfiler.takeHeapSnapshot",{reportProgress:false});}
    finally{closeSync(file);}
    await memorySample("post-snapshot");
  }
  report.memory=memorySamples;report.verdict="PASS";
}catch(error){report.error=String(error.stack||error);report.verdict="FAIL";}
finally{
  report.totalSeconds=Math.round((Date.now()-t0)/1000);
  await writeFile(join(out,"report.json"),JSON.stringify(report,null,2));
  try{await Promise.race([cdp?.send("Browser.close"),sleep(2500)]);}catch{}
  try{cdp?.ws.close();}catch{}try{chrome.kill();}catch{}
  if(server)server.close();await sleep(1500);
  await rm(profileDir,{recursive:true,force:true}).catch(()=>{});
  console.log(JSON.stringify({verdict:report.verdict,seconds:report.totalSeconds,
    memory:memorySamples.map(sample=>({label:sample.label,seconds:sample.seconds,
      heapMiB:sample.heap.usedSize/1048576,linearMiB:sample.linearBytes/1048576,totalMiB:sample.totalBytes/1048576})),
    error:report.error?.split("\\n")[0]}));
  process.exit(report.verdict==="PASS"?0:1);
}
`;
const harness = join(out, "memory-run.mjs");
await writeFile(harness, source);
if (process.env.MEMORY_PREPARE_ONLY === "1") {
  console.log(harness);
  process.exit(0);
}
const result = spawnSync(process.execPath, [harness], {stdio: "inherit", env: {
  ...process.env, OUT: out, BOOT_TIMEOUT_MS: process.env.BOOT_TIMEOUT_MS || "180000",
  WORLD_TIMEOUT_MS: process.env.WORLD_TIMEOUT_MS || "180000"
}});
process.exit(result.status ?? 1);
