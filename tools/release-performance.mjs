import {terrainCompleteness} from './terrain-settle.mjs';
import {enableCreativeFlight, holdKeyDuring} from './flight-input.mjs';
import {summarizeFrameTimes} from '../port/scripts/performance-metrics.mjs';
import {BoundedCdpCapture} from '../port/scripts/bench/bounded-cdp-capture.mjs';
import {join} from 'node:path';

// Opt-in release measurements. No game or distribution source is patched.
export const releasePerformance = process.env.RELEASE_PERF === '1';
export const releaseThroughput = releasePerformance && process.env.CLEAN_THROUGHPUT === '1';
export const releaseVsync260 = releasePerformance && (process.env.PROFILE_VSYNC_260 === '1' || process.env.RELEASE_VSYNC === '1');
export const releaseAutoTier = releasePerformance && process.env.RELEASE_AUTO_TIER === '1';
export const framePacingTest = process.env.FRAME_PACING_TEST === '1';
export const framePacingMode = framePacingTest ? process.env.FRAME_PACING_MODE : null;
if (framePacingTest && !releasePerformance) throw new Error('FRAME_PACING_TEST requires RELEASE_PERF=1');
if (framePacingTest && !['vsync', 'uncapped'].includes(framePacingMode))
  throw new Error('FRAME_PACING_MODE must be vsync or uncapped');
if (framePacingTest && releaseAutoTier) throw new Error('FRAME_PACING_TEST does not support RELEASE_AUTO_TIER=1');
export const releaseDistance = releaseAutoTier ? null : Number(process.env.RENDER_DISTANCE || 8);
if (releasePerformance && !releaseAutoTier && (!Number.isInteger(releaseDistance) || releaseDistance < 2 || releaseDistance > 32))
  throw new Error('RENDER_DISTANCE must be an integer from 2 through 32');
export const releaseSimulationDistance = releaseAutoTier ? null : Number(process.env.SIMULATION_DISTANCE || 6);
if (releasePerformance && !releaseAutoTier && (!Number.isInteger(releaseSimulationDistance) || releaseSimulationDistance < 2 || releaseSimulationDistance > 32))
  throw new Error('SIMULATION_DISTANCE must be an integer from 2 through 32');
if (releasePerformance && !releaseThroughput && process.env.PROFILE_60HZ !== '1' && !releaseVsync260 && !framePacingTest)
  throw new Error('RELEASE_PERF requires PROFILE_60HZ=1, PROFILE_VSYNC_260=1, FRAME_PACING_TEST=1, or CLEAN_THROUGHPUT=1');
export function pacingOptionValues(throughput, mode = null, vsync260 = false) {
  return {enableVsync: mode ? mode === 'vsync' : vsync260 || !throughput,
    maxFps: mode ? 260 : throughput || vsync260 ? 260 : 60};
}
const configuredPacing = pacingOptionValues(releaseThroughput, framePacingMode, releaseVsync260);
export const releaseOptions = [
  'graphicsPreset:"fancy"', `renderDistance:${releaseDistance}`, `simulationDistance:${releaseSimulationDistance}`,
  `enableVsync:${configuredPacing.enableVsync}`, `maxFps:${configuredPacing.maxFps}`,
  'renderClouds:"true"', 'particles:0', 'bobView:false', 'guiScale:2',
  'pauseOnLostFocus:false', 'chunkSectionFadeInTime:0.0', 'prioritizeChunkUpdates:1',
  'mipmapLevels:4', 'biomeBlendRadius:2', 'entityShadows:true',
];
const fixedReleaseOptionsExpression = `(async()=>{
  if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady;
  const now=typeof performance==='object'&&typeof performance.now==='function'?()=>performance.now():()=>Date.now();
  const deadline=now()+120000;
  while(typeof globalThis.__gaiusFsPut!=='function'&&now()<deadline)
    await new Promise(resolve=>setTimeout(resolve,25));
  if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady;
  const path='/gaius/options.txt',encoded=${JSON.stringify(Buffer.from(releaseOptions.join('\n')+'\n').toString('base64'))};
  (globalThis.__gaiusPersistentFiles||(globalThis.__gaiusPersistentFiles=Object.create(null)))[path]=encoded;
  if(typeof globalThis.__gaiusFsPut!=='function')throw new Error('options storage unavailable');
  globalThis.__gaiusFsPut(path,encoded);
  if(globalThis.__gaiusFsFlush)await globalThis.__gaiusFsFlush(true);
  globalThis.__gaiusReleaseBoot();return ${JSON.stringify(releaseOptions)};
})()`;

export function autoTierOptionsExpression(throughput) {
  const enableVsync = !throughput;
  const maxFps = throughput ? 260 : 60;
  return `(async()=>{
  if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady;
  const path='/gaius/options.txt';
  const files=globalThis.__gaiusPersistentFiles||(globalThis.__gaiusPersistentFiles=Object.create(null));
  const previous=typeof files[path]==='string'?atob(files[path]):'';
  const originalLines=previous.split('\\n').filter(Boolean);
  const lines=originalLines.filter(line=>!/^enableVsync:|^maxFps:/.test(line));
  if(!originalLines.some(line=>/^graphicsPreset:/.test(line))
      ||!originalLines.some(line=>/^renderDistance:/.test(line)))
    throw new Error('automatic tier defaults were not persisted before the throughput reload');
  lines.push('enableVsync:${enableVsync}','maxFps:${maxFps}');
  const encoded=btoa(lines.join('\\n')+'\\n');
  if(typeof globalThis.__gaiusFsPut!=='function')throw new Error('options storage unavailable');
  files[path]=encoded;globalThis.__gaiusFsPut(path,encoded);
  if(globalThis.__gaiusFsFlush)await globalThis.__gaiusFsFlush(true);
  globalThis.__gaiusReleaseBoot();return {automaticTier:true,options:lines};
})()`;
}

export const releaseOptionsExpression = releaseAutoTier
  ? autoTierOptionsExpression(releaseThroughput) : fixedReleaseOptionsExpression;
const activeBoundedCaptures = new Set();
export async function stopBoundedCdpCaptures(reason = 'harness-finally') {
  const results = await Promise.all([...activeBoundedCaptures].map(async capture => {
    let result = null;
    try { result = await capture.stop(reason); } catch {}
    activeBoundedCaptures.delete(capture);
    return result;
  }));
  return results.filter(Boolean);
}
export async function trackBoundedCdpTarget(target) {
  await Promise.all([...activeBoundedCaptures].map(capture => capture.trackTarget(target)));
}

// Keep the first title responsive during reload; the warm boot selects throughput options.
const coldOptions=releaseOptions.map(line=>line.startsWith('enableVsync:')?'enableVsync:true':line.startsWith('maxFps:')?'maxFps:60':line);
const fixedColdOptionsExpression = fixedReleaseOptionsExpression.replace(
  Buffer.from(releaseOptions.join('\n')+'\n').toString('base64'),
  Buffer.from(coldOptions.join('\n')+'\n').toString('base64'))
  .replace(JSON.stringify(releaseOptions),JSON.stringify(coldOptions));
const automaticColdOptionsExpression = `(async()=>{
  if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady;
  globalThis.__gaiusReleaseBoot();return {automaticTier:true,cold:true};
})()`;
export const releaseColdOptionsExpression = releaseAutoTier
  ? automaticColdOptionsExpression : fixedColdOptionsExpression;

// Timestamp successful presentation, rather than substituting rAF intervals for game frames.
// The existing frame guard owns backend detection and advances exactly once per presentation.
export const releasePresentSource = `(()=>{
  const G=globalThis.__gaiusGlFrameGuard;if(!G)throw new Error('frame guard unavailable');
  let frames=G.frames;
  Object.defineProperty(G,'frames',{get:()=>frames,set:v=>{
    const probe=globalThis.__gaiusReleasePresent;
    if(probe&&probe.active&&v>frames){const t=performance.now();
      if(probe.last!==null)probe.intervals.push(t-probe.last);else probe.first=t;probe.last=t;}
    const diagnostics=globalThis.__gaiusReleaseProbe;
    if(diagnostics?.active&&v>frames){const t=performance.now();
      if(diagnostics.lastGame!==null)diagnostics.gameIntervals.push(t-diagnostics.lastGame);
      diagnostics.lastGame=t;diagnostics.gamePipelineFrames.push(diagnostics.copyPipeline());
      if(diagnostics.gamePipelineFrames.length>diagnostics.capacity)diagnostics.gamePipelineFrames.shift();}
    frames=v;
  }});
})()`;

export function walkValidity(start,path,distance) {
  let previous={ms:0,player:start};
  const segments=path.map(row=>{
    const seconds=(row.ms-previous.ms)/1000;
    const blocks=Math.hypot(row.player?.x-previous.player?.x,row.player?.z-previous.player?.z);
    const renderDistance=Number(row.dist?.effectiveRenderDistance||row.dist?.rawRenderDistance);
    previous=row;
    return {seconds,blocks,blocksPerSecond:blocks/seconds,renderDistance};
  });
  const sampled=segments.filter(s=>s.seconds>=1);
  const observed=sampled.map(s=>s.renderDistance).filter(Number.isFinite);
  const expected=distance==null ? (observed[0]??null) : distance;
  return {ok:sampled.length>=10&&observed.length===sampled.length
    &&sampled.every(s=>s.blocksPerSecond>=1.5&&s.renderDistance===expected),
    renderDistance:expected,segments};
}

export function optionsValidity(text,distance,throughput,simulationDistance=6,mode=framePacingMode,vsync260=releaseVsync260) {
  const options=Object.fromEntries(String(text||'').split(/\r?\n/).map(line=>line.split(':',2)));
  const actualDistance=Number(options.renderDistance);
  const actualSimulationDistance=Number(options.simulationDistance);
  const expected=pacingOptionValues(throughput,mode,vsync260);
  return {ok:(distance==null ? [6,8,10,12].includes(actualDistance) : actualDistance===distance)
    &&actualSimulationDistance===simulationDistance
    &&options.enableVsync===String(expected.enableVsync)
    &&Number(options.maxFps)===expected.maxFps,options};
}

export function summarizeReleaseIntervals(values, elapsedMs) {
  const intervals = (Array.isArray(values) ? values : [])
    .map(value => Number(value)).filter(value => Number.isFinite(value) && value > 0);
  const countOver = threshold => intervals.filter(value => value > threshold).length;
  const span = Number(elapsedMs);
  const summary = summarizeFrameTimes(intervals, span);
  return {
    sampleCount: summary.sampleCount,
    p50Ms: summary.p50FrameMs, p95Ms: summary.p95FrameMs,
    p99Ms: summary.p99FrameMs, maxFrameMs: summary.longestFrameMs,
    onePercentLowFps: summary.onePercentLowFps,
    zeroPointOnePercentLowFps: summary.zeroPointOnePercentLowFps,
    counts: {over16_7Ms: countOver(16.7), over33Ms: countOver(33),
      over50Ms: countOver(50), over100Ms: countOver(100)},
    atLeast50MsPerMinute: Number.isFinite(span) && span >= 60_000
      ? intervals.filter(value => value >= 50).length * 60_000 / span : null,
  };
}

export function summarizeLatencySamples(values) {
  const samples=(Array.isArray(values)?values:[]).map(Number)
    .filter(value=>Number.isFinite(value)&&value>=0).sort((a,b)=>a-b);
  const q=p=>samples.length?samples[Math.min(samples.length-1,Math.floor(samples.length*p))]:null;
  return {sampleCount:samples.length,p50Ms:q(.5),p95Ms:q(.95),p99Ms:q(.99),maxMs:samples.at(-1)??null};
}

export function summarizeReleasePipelineFrames(rows) {
  const samples = Array.isArray(rows) ? rows.filter(row => row && typeof row === "object") : [];
  const number = (row, key) => Number(row?.[key]) || 0;
  const max = key => samples.reduce((value, row) => Math.max(value, number(row, key)), 0);
  const nonZero = key => samples.filter(row => number(row, key) > 0).length;
  const first = samples[0], last = samples.at(-1);
  const delta = key => first && last ? number(last, key) - number(first, key) : null;
  return {
    sampleCount: samples.length,
    maxCompileBacklog: max("compile"), maxUploadBacklog: max("upload"),
    maxFrameWorkMillis: max("frameWorkMillis"), maxUploadBytesThisFrame: max("uploadBytesThisFrame"),
    framesWithCompileBacklog: nonZero("compile"), framesWithUploadBacklog: nonZero("upload"),
    framesWithMeshResultsReady: nonZero("meshResultsReady"),
    completedTasksDelta: delta("completedTasks"), uploadPassesDelta: delta("uploadPasses"),
    taskMillisDelta: delta("totalTaskMillis"), uploadPassMillisDelta: delta("totalUploadPassMillis"),
    uploadAllocationsDrainedDelta: delta("uploadAllocationsDrained"),
    averageCompletedTasksPerFrame: samples.length ? (delta("completedTasks") || 0) / samples.length : null,
    averageUploadPassesPerFrame: samples.length ? (delta("uploadPasses") || 0) / samples.length : null,
    maxMeshRequestsInFlight: max("meshRequestsInFlight"), maxMeshResultsReady: max("meshResultsReady"),
    last: last || null,
  };
}

export const releaseDiagnosticSource = `(()=>{
  const old=globalThis.__gaiusReleaseProbe;
  if(old?.rafId)try{cancelAnimationFrame(old.rafId)}catch{}
  const probe=globalThis.__gaiusReleaseProbe={startedAt:performance.now(),lastRaf:null,lastGame:null,
    capacity:100000,active:true,rafIntervals:[],gameIntervals:[],pipelineFrames:[],gamePipelineFrames:[],
    longTasks:[],gcEntries:[],memorySamples:[],gcSupported:false,rafId:0};
  globalThis.__gaiusChunkDrawTelemetryEnabled=${process.env.RELEASE_CHUNK_DRAW === "1"};
  probe.causalTelemetryEnabledBefore=globalThis.__gaiusChunkCausalTelemetryEnabled;
  const causalParam=new URLSearchParams(globalThis.location?.search||'').get('gaiusChunkCausalTelemetry');
  probe.causalTelemetryOwned=${process.env.RELEASE_CHUNK_CAUSAL === "1"}
    || /^(1|true|on)$/i.test(causalParam||'');
  if(probe.causalTelemetryOwned)globalThis.__gaiusChunkCausalTelemetryEnabled=true;
  probe.pipelineTelemetryEveryFrame=globalThis.__gaiusChunkPipelineTelemetryEveryFrame;
  probe.pipelineTelemetryEveryFrameOwned=${process.env.RELEASE_PIPELINE_EVERY_FRAME === "1"};
  if(probe.pipelineTelemetryEveryFrameOwned)globalThis.__gaiusChunkPipelineTelemetryEveryFrame=true;
  const pipeline=()=>globalThis.__gaiusChunkPipelineTelemetry||{};
  probe.copyPipeline=()=>{const p=pipeline();
    return {compile:Number(p.compileBacklog)||0,upload:Number(p.uploadBacklog)||0,
      completedTasks:Number(p.completedTasks)||0,uploadPasses:Number(p.uploadPasses)||0,
      uploadBytesThisFrame:Number(p.uploadBytesThisFrame)||0,
      uploadAllocationsDrained:Number(p.uploadAllocationsDrained)||0,
      totalTaskMillis:Number(p.totalTaskMillis)||0,totalUploadPassMillis:Number(p.totalUploadPassMillis)||0,
      frameWorkMillis:Number(p.frameWorkMillis)||0,meshRequestsInFlight:Number(p.meshRequestsInFlight)||0,
      meshResultsReady:Number(p.meshResultsReady)||0,meshKernelSubmitted:Number(p.meshKernelSubmitted)||0,
      meshKernelMeshed:Number(p.meshKernelMeshed)||0,meshKernelInstallMs:Number(p.meshKernelInstallMs)||0,
      lastTaskMillis:Number(p.lastTaskMillis)||0,lastUploadPassMillis:Number(p.lastUploadPassMillis)||0,
      at:performance.now()};};
  const raf=now=>{if(globalThis.__gaiusReleaseProbe!==probe)return;
    if(probe.lastRaf!==null){probe.rafIntervals.push(now-probe.lastRaf);if(probe.rafIntervals.length>probe.capacity)probe.rafIntervals.shift();}
    probe.lastRaf=now;probe.pipelineFrames.push(probe.copyPipeline());
    if(probe.pipelineFrames.length>probe.capacity)probe.pipelineFrames.shift();probe.rafId=requestAnimationFrame(raf);};
  probe.rafId=requestAnimationFrame(raf);
  const packet=globalThis.__gaiusClientChunkPacketPositions;
  if(globalThis.__gaiusChunkDrawTelemetryEnabled&&packet&&Array.isArray(packet.positions)
      &&typeof packet.positions.push==='function'&&!packet.positions.__gaiusReleaseTap){
    try{
      const positions=packet.positions,originalPush=positions.push;
      Object.defineProperty(positions,'__gaiusReleaseTap',{value:true});
      const wrappedPush=function(...items){const result=originalPush.apply(this,items);
        for(const item of items)if(Array.isArray(item)&&item.length>=2){probe.chunkPackets??=[];
          probe.chunkPackets.push({chunkX:Number(item[0])|0,chunkZ:Number(item[1])|0,atMillis:performance.timeOrigin+performance.now()});
          if(probe.chunkPackets.length>8192)probe.chunkPackets.shift();}
        return result;};
      positions.push=wrappedPush;
      probe.packetCapture={positions,originalPush,wrappedPush};
    }catch(error){probe.packetCaptureError=String(error);}
  }
  try{const o=new PerformanceObserver(list=>{for(const e of list.getEntries()){
    const row={startTime:Number(e.startTime)||0,duration:Number(e.duration)||0};probe.longTasks.push(row);
    if(probe.longTasks.length>4096)probe.longTasks.shift();}});o.observe({type:'longtask',buffered:false});probe.longTaskObserver=o;}catch(error){probe.longTaskError=String(error);}
  try{const supported=PerformanceObserver.supportedEntryTypes||[];probe.gcSupported=supported.includes('gc');
    if(probe.gcSupported){const o=new PerformanceObserver(list=>{for(const e of list.getEntries()){
      probe.gcEntries.push({startTime:Number(e.startTime)||0,duration:Number(e.duration)||0});
      if(probe.gcEntries.length>4096)probe.gcEntries.shift();}});o.observe({type:'gc',buffered:false});probe.gcObserver=o;}}
  catch(error){probe.gcError=String(error);}
  probe.memorySupported=typeof performance.measureUserAgentSpecificMemory==='function';
  const sampleMemory=async()=>{if(!probe.memorySupported||probe.memoryBusy)return;probe.memoryBusy=true;const started=performance.now();
    try{const value=await performance.measureUserAgentSpecificMemory();probe.memorySamples.push({at:performance.now(),durationMs:performance.now()-started,bytes:Number(value.bytes)||null});}
    catch(error){probe.memoryError=String(error);}finally{probe.memoryBusy=false;}};
  void sampleMemory();probe.memoryTimer=setInterval(sampleMemory,15000);
})();`;

async function startReleaseDiagnostics(evaluate) {
  return evaluate(releaseDiagnosticSource);
}

async function finishReleaseDiagnostics(evaluate) {
  return evaluate(`(()=>{const p=globalThis.__gaiusReleaseProbe;if(!p)return null;
    p.active=false;try{cancelAnimationFrame(p.rafId)}catch{};try{p.longTaskObserver?.disconnect()}catch{};try{p.gcObserver?.disconnect()}catch{};
    try{clearInterval(p.memoryTimer)}catch{};
    if(p.packetCapture&&p.packetCapture.positions?.push===p.packetCapture.wrappedPush)
      p.packetCapture.positions.push=p.packetCapture.originalPush;
    if(p.pipelineTelemetryEveryFrameOwned){if(p.pipelineTelemetryEveryFrame===undefined)delete globalThis.__gaiusChunkPipelineTelemetryEveryFrame;
      else globalThis.__gaiusChunkPipelineTelemetryEveryFrame=p.pipelineTelemetryEveryFrame;}
    if(p.causalTelemetryOwned){if(p.causalTelemetryEnabledBefore===undefined)delete globalThis.__gaiusChunkCausalTelemetryEnabled;
      else globalThis.__gaiusChunkCausalTelemetryEnabled=p.causalTelemetryEnabledBefore;}
    const draw=globalThis.__gaiusChunkDrawTelemetry||null;
    const causal=globalThis.__gaiusChunkPipelineTelemetry?.causal||null;
    const causalTelemetry=causal?{schemaVersion:causal.schemaVersion||null,enabled:causal.enabled===true,
      handlerTimestampSource:causal.handlerTimestampSource||null,
      sections:Array.isArray(causal.sections)?causal.sections.map(s=>({...s,events:Array.isArray(s.events)?s.events.slice():[]})):[],
      handlerEntries:Array.isArray(causal.handlerEntries)?causal.handlerEntries.slice():[],
      schedulerSamples:Array.isArray(causal.schedulerSamples)?causal.schedulerSamples.slice():[],
      droppedSections:Number(causal.droppedSections)||0,droppedHandlerEntries:Number(causal.droppedHandlerEntries)||0,
      handlerEntryCount:Number(causal.handlerEntryCount)||0,unmappedDraws:Number(causal.unmappedDraws)||0,
      earlyDraws:Number(causal.earlyDraws)||0,repeatDraws:Number(causal.repeatDraws)||0,
      syntheticDraws:Number(causal.syntheticDraws)||0}:null;
    return {elapsedMs:performance.now()-p.startedAt,rafIntervals:p.rafIntervals,gameIntervals:p.gameIntervals,
      pipelineFrames:p.pipelineFrames,gamePipelineFrames:p.gamePipelineFrames,chunkPackets:p.chunkPackets||[],
      causalTelemetry,
      longTasks:p.longTasks,gcEntries:p.gcEntries,gcSupported:p.gcSupported,gcError:p.gcError||null,
      memorySupported:p.memorySupported,memorySamples:p.memorySamples,memoryError:p.memoryError||null,
      longTaskError:p.longTaskError||null,chunkDraw:draw,
      optInTimeline:globalThis.__gaiusDiagTimeline?.summary?.()||null};})()`);
}

export function summarizeReleaseChunkFirstDraw(draw, packetRows) {
  const events=Array.isArray(draw?.events)?draw.events:[];
  const firstPacket=new Map();
  for(const row of Array.isArray(packetRows)?packetRows:[]){
    const key=`${Number(row.chunkX)|0},${Number(row.chunkZ)|0}`;
    if(!firstPacket.has(key))firstPacket.set(key,Number(row.atMillis));
  }
  const latencies=[];
  for(const event of events){
    const key=`${Number(event.chunkX)|0},${Number(event.chunkZ)|0}`;
    const loadedAt=firstPacket.get(key),drawnAt=Number(event.atMillis);
    if(Number.isFinite(loadedAt)&&Number.isFinite(drawnAt)&&drawnAt>=loadedAt)
      latencies.push({chunkX:event.chunkX,chunkZ:event.chunkZ,loadToFirstDrawMs:drawnAt-loadedAt});
  }
  const values=latencies.map(row=>row.loadToFirstDrawMs).sort((a,b)=>a-b);
  const q=f=>values.length?values[Math.min(values.length-1,Math.floor(values.length*f))]:null;
  return {enabled:draw?.enabled===true,worldSequence:draw?.worldSequence??null,
    firstDrawColumns:draw?.firstDrawColumns??null,packetRows:packetRows?.length??0,
    matchedColumns:latencies.length,droppedEvents:draw?.droppedEvents??null,
    loadToFirstDraw:{p50Ms:q(.5),p95Ms:q(.95),maxMs:values.at(-1)??null,samples:latencies}};
}

export function summarizeReleaseChunkCausal(causal) {
  const sections=Array.isArray(causal?.sections)?causal.sections.filter(row=>row&&typeof row==="object"):[];
  const scheduler=Array.isArray(causal?.schedulerSamples)?causal.schedulerSamples.filter(row=>row&&typeof row==="object"):[];
  const finite=(value)=>value!=null&&value!==""&&Number.isFinite(Number(value));
  const stageValues={
    handlerEntryToSnapshot:[],snapshot:[],snapshotToSubmit:[],worker:[],resultToInstallQueue:[],installQueue:[],
    installToFirstDraw:[],handlerEntryToFirstDraw:[],
  };
  const stage=(bucket,start,end)=>{
    if(finite(start)&&finite(end)&&Number(end)>=Number(start))stageValues[bucket].push(Number(end)-Number(start));
  };
  const order=["packetHandlerAt","snapshotStartAt","snapshotEndAt","workerSubmitAt","workerResultAt",
    "installQueueAt","installCompleteAt","firstDrawAt"];
  let eventOrderViolations=0;
  const visible=sections.filter(row=>row.visible===true||finite(row.firstVisibleDrawAt)||finite(row.firstDrawAt));
  const handlerMatched=visible.filter(row=>finite(row.packetHandlerAt));
  const visibleTickets=visible.filter(row=>row.causalTicket!=null);
  const closed=visibleTickets.filter(row=>row.installSucceeded===true&&[
    row.packetHandlerAt,row.snapshotStartAt,row.snapshotEndAt,row.workerSubmitAt,row.workerResultAt,
    row.installQueueAt,row.installCompleteAt,row.gpuUploadProxyAt,row.firstDrawAt,
  ].every(finite));
  const discardReasons=Object.create(null);
  for(const row of sections){
    stage("handlerEntryToSnapshot",row.packetHandlerAt,row.snapshotStartAt);
    stage("snapshot",row.snapshotStartAt,row.snapshotEndAt);
    stage("snapshotToSubmit",row.snapshotEndAt,row.workerSubmitAt);
    stage("worker",row.workerSubmitAt,row.workerResultAt);
    stage("resultToInstallQueue",row.workerResultAt,row.installQueueAt);
    stage("installQueue",row.installQueueAt,row.installCompleteAt);
    stage("installToFirstDraw",row.installCompleteAt,row.firstDrawAt);
    stage("handlerEntryToFirstDraw",row.packetHandlerAt,row.firstDrawAt);
    let previous=-Infinity;
    for(const key of order){
      if(!finite(row[key]))continue;
      const value=Number(row[key]);
      if(value<previous)eventOrderViolations++;
      previous=value;
    }
    if(row.discardReason)discardReasons[row.discardReason]=(discardReasons[row.discardReason]||0)+1;
  }
  const quantiles=values=>{
    const sorted=values.slice().sort((a,b)=>a-b);
    const q=f=>sorted.length?sorted[Math.min(sorted.length-1,Math.floor(sorted.length*f))]:null;
    return {samples:sorted.length,p50Ms:q(.5),p95Ms:q(.95),p99Ms:q(.99),maxMs:sorted.at(-1)??null};
  };
  const slope=key=>{
    if(scheduler.length<2)return null;
    const first=scheduler[0],last=scheduler.at(-1),elapsed=Number(last.atMillis)-Number(first.atMillis);
    return Number.isFinite(elapsed)&&elapsed>0
      ? (Number(last[key]||0)-Number(first[key]||0))*1000/elapsed:null;
  };
  return {
    enabled:causal?.enabled===true,schemaVersion:causal?.schemaVersion??null,
    handlerTimestampSource:causal?.handlerTimestampSource||"handler entry after decode; wire arrival not observed",
    eventCapacity:{sections:32768,handlerEntries:8192,schedulerSamples:512},
    visibleSectionsObserved:visible.length,syntheticVisibleSections:visible.filter(row=>row.causalTicket==null).length,
    handlerMatchedVisibleSections:handlerMatched.length,causalVisibleSections:visibleTickets.length,
    closedVisibleSections:closed.length,closedLoopRate:visibleTickets.length?closed.length/visibleTickets.length:null,
    gpuUploadTimestamp:"first valid draw proxy; GPU upload completion is not observed independently",
    workerTickets:sections.filter(row=>row.causalTicket!=null).length,
    completedWorkerTickets:sections.filter(row=>row.causalTicket!=null&&finite(row.firstDrawAt)
      &&row.snapshotStartAt!=null&&row.snapshotEndAt!=null&&row.workerSubmitAt!=null&&row.workerResultAt!=null
      &&row.installQueueAt!=null&&row.installCompleteAt!=null&&row.installSucceeded===true).length,
    unmappedDraws:Number(causal?.unmappedDraws)||0,earlyDraws:Number(causal?.earlyDraws)||0,
    repeatDraws:Number(causal?.repeatDraws)||0,syntheticDraws:Number(causal?.syntheticDraws)||0,
    droppedSections:Number(causal?.droppedSections)||0,
    droppedHandlerEntries:Number(causal?.droppedHandlerEntries)||0,
    handlerEntryCount:Number(causal?.handlerEntryCount)||0,
    discardCount:Object.values(discardReasons).reduce((sum,n)=>sum+n,0),
    discardReasons,eventOrderViolations,stages:Object.fromEntries(Object.entries(stageValues).map(([name,values])=>[name,quantiles(values)])),
    handlerEntryToFirstDraw:{...quantiles(stageValues.handlerEntryToFirstDraw),
      sampleCount:stageValues.handlerEntryToFirstDraw.length,
      sampleRows:sections.filter(row=>finite(row.packetHandlerAt)
      &&finite(row.firstDrawAt)&&Number(row.firstDrawAt)>=Number(row.packetHandlerAt)).map(row=>({
        ticket:row.ticket,chunkX:row.chunkX,sectionY:row.sectionY,chunkZ:row.chunkZ,
        packetHandlerAt:row.packetHandlerAt,firstDrawAt:row.firstDrawAt,
        latencyMs:Number(row.firstDrawAt)-Number(row.packetHandlerAt),source:row.source||"worker-ticket"}))},
    backlog:{sampleCount:scheduler.length,compileSlopePerSecond:slope("compileBacklog"),
      uploadSlopePerSecond:slope("uploadBacklog"),queuedWorkSlopePerSecond:slope("queuedWork"),
      first:scheduler[0]||null,last:scheduler.at(-1)||null},
    records:sections,
  };
}

export async function measureReleaseWorld({cdp,evaluate,stateExpr,sleep,sample,report,createdAt,
  enteredAt,firstTerrainAt,settleMs,out,log,command,clickAt,keyEvent,screenshot,pixelClassify,
  pass,fail,workerTargets,memorySample,pageTargetId,memoryInfraCapture}) {
  const result=report.releasePerformance;
  result.clickToWorldSeconds=enteredAt;
  result.firstTerrainSeconds=firstTerrainAt;
  result.quality=await evaluate(`(()=>{const caps=globalThis.__gaiusGpuCaps||null;
    const settings=globalThis.__gaiusQualitySettings||null;const tier=globalThis.__gaiusMemoryTier||null;
    return {gpuCaps:caps?{tier:caps.tier,kind:caps.kind,renderer:caps.renderer,vendor:caps.vendor,score:caps.score,source:caps.source}:null,
      settings:settings?{tier:settings.tier,postEnabled:settings.postEnabled,ssao:settings.ssao,bloom:settings.bloom,
        oit:settings.oit,maxDpr:settings.maxDpr,renderScale:settings.renderScale}:null,
      memoryTier:tier?{chosenTier:tier.chosenTier,gpuTier:tier.gpuTier,reason:tier.reason,lowMemory:tier.lowMemory}:null};})()`);
  const deadline=Date.now()+settleMs;
  let stableSince=null;
  let lastMemorySampleAt = -Infinity;
  let previousSettleSample=null;
  let previousWorkerCpu=null;
  let lastSettleSampleAt=-Infinity;
  let lastStageTimelineAt=-Infinity;
  let previousStageTimeline=null;
  const workerPerfEnabled=new Set();
  const summarizePresentation=async()=>await evaluate(`(()=>{
    const p=globalThis.__gaiusReleasePresent||{};
    const values=(p.intervals||[]).filter(x=>Number.isFinite(x)&&x>0).sort((a,b)=>a-b);
    const q=f=>values.length?values[Math.min(values.length-1,Math.floor(values.length*f))]:null;
    const span=Number(p.started)>0?Math.max(0,performance.now()-p.started):0;
    const frames=Number(p.framesAtStart)>=0?Math.max(0,(globalThis.__gaiusGlFrameGuard?.frames||0)-p.framesAtStart):values.length;
    return {spanMs:span,frames,fps:span>0?frames*1000/span:null,sampleCount:values.length,
      p50Ms:q(.5),p95Ms:q(.95),p99Ms:q(.99),maxFrameMs:values.at(-1)||null,
      counts:{over16_7Ms:values.filter(x=>x>16.7).length,over33Ms:values.filter(x=>x>33).length,
        over50Ms:values.filter(x=>x>50).length},atLeast50MsPerMinute:span>=60000?values.filter(x=>x>=50).length*60000/span:null};
  })()`);
  result.loadingPresentation=null;
  await evaluate(`globalThis.__gaiusReleasePresent={active:true,last:null,intervals:[],started:performance.now(),framesAtStart:globalThis.__gaiusGlFrameGuard?.frames||0};true`);
  result.simulationDistance=releaseSimulationDistance;
  result.settleSamples=[];
  result.stageTimeline=[];
  while(Date.now()<deadline){
    const row=await sample('release-settle',result.firstTerrainSeconds===null);
    const settleAtMs=Date.now()-createdAt;
    if(result.firstTerrainSeconds===null&&row.px&&!row.px.error&&row.px.other>0.3)
      result.firstTerrainSeconds=(Date.now()-createdAt)/1000;
    const status=terrainCompleteness(row);
    result.settleStatus=status;
    result.effectiveDistance=row.dist;
    const targetDistance=releaseDistance ?? status.renderDistance;
    if(Number.isInteger(targetDistance)&&targetDistance>=2&&targetDistance<=32)
      result.renderDistance=targetDistance;
    if (memoryInfraCapture) await memoryInfraCapture(Number(row.loaded) || 0, settleAtMs);
    if(status.complete&&status.renderDistance===result.renderDistance){
      stableSince??=Date.now();
      if(Date.now()-stableSince>=5000&&result.firstTerrainSeconds!==null){
        result.fullSettleSeconds=(Date.now()-createdAt)/1000;break;
      }
    }else stableSince=null;
    if(settleAtMs-lastSettleSampleAt>=5000){
      lastSettleSampleAt=settleAtMs;
      const serverTarget=[...workerTargets.values()].find(target=>/singleplayer-server-worker/i.test(String(target.url||'')));
      let worker=null;
      if(serverTarget){
        const sessionId=serverTarget.sessionId;
        if(!workerPerfEnabled.has(sessionId)){
          await cdp.send('Performance.enable',{},sessionId).catch(()=>{});
          workerPerfEnabled.add(sessionId);
        }
        const metrics=await cdp.send('Performance.getMetrics',{},sessionId).catch(()=>null);
        const current=Object.fromEntries((metrics?.metrics||[]).map(value=>[value.name,value.value]));
        const taskDuration=Number(current.TaskDuration),timestamp=Number(current.Timestamp);
        const cpu=Number.isFinite(taskDuration)&&Number.isFinite(timestamp)&&previousWorkerCpu
          &&timestamp>previousWorkerCpu.timestamp
          ?Math.max(0,(taskDuration-previousWorkerCpu.taskDuration)/(timestamp-previousWorkerCpu.timestamp)*100):null;
        if(Number.isFinite(taskDuration)&&Number.isFinite(timestamp))previousWorkerCpu={taskDuration,timestamp};
        const detail=await evaluate(`(()=>{
          const numeric=value=>Object.fromEntries(Object.entries(value||{}).filter(([,entry])=>typeof entry==='number'||typeof entry==='boolean'||typeof entry==='string'));
          const kernel=globalThis.GaiusWorldgenKernel?.telemetry?.()||globalThis.__gaiusKernels?.telemetry?.()||null;
          const worldgen=numeric(globalThis.__gaiusWorldgenStats);
          const tick=numeric(globalThis.__gaiusServerTickTelemetry);
          const light=globalThis.GaiusLightKernel?.telemetry?.()||globalThis.__gaiusLightKernel?.telemetry?.()||null;
          const wake=numeric(globalThis.__gaiusServerWake);
          return {kernel,worldgen,tick,light,wake};
        })()`,sessionId).catch(error=>({error:String(error)}));
        worker={url:serverTarget.url,taskDurationSeconds:Number.isFinite(taskDuration)?taskDuration:null,
          timestampSeconds:Number.isFinite(timestamp)?timestamp:null,cpuPercent:cpu,detail};
      }
      const currentSample={atMs:settleAtMs,loaded:Number(row.loaded)||0,visible:row.audit?.visible??null,
        sections:row.audit?.sections??null,uncompiled:row.audit?.uncompiled??null,lost:row.audit?.lost??null,
        renderDistance:row.dist?.effectiveRenderDistance??row.dist?.rawRenderDistance??null,
        simulationDistance:row.dist?.effectiveSimulationDistance??row.dist?.rawSimulationDistance??releaseSimulationDistance,
        clientPipeline:row.pipeline||null,worker};
      const delivery=await evaluate(`(()=>{const p=window.__gaiusChunkPipelineTelemetry||{};
        const telemetry=window.__gaiusWorkerMessageTelemetry||{};
        const worldgen=telemetry.worldgen||{};const distance=telemetry.serverDistance||{};
        return {receivedFrames:Number(window.__gaiusNetworkStats?.receivedFrames)||0,
          workerMessagesReceived:Number(telemetry.received)||0,
          sentChunkQueueEntries:Number(distance.sentQueueEntries)||0,acknowledgedChunks:Number(distance.acknowledgedChunkCount)||0,
          readySelectedChunks:Number(worldgen.chunkSender?.readySelectedChunks)||0,
          sentBatches:Number(worldgen.chunkSender?.batchCount)||0,
          chunkBroadcastItems:Number(worldgen.chunkBroadcastItems)||0,
          meshSubmitted:Number(p.meshKernelSubmitted)||0,meshed:Number(p.meshKernelMeshed)||0,
          uploadQueued:Number(p.uploadAllocationsQueued)||0,uploaded:Number(p.uploadAllocationsDrained)||0,
          uploadPasses:Number(p.uploadPasses)||0};})()`).catch(error=>({error:String(error)}));
      currentSample.delivery=delivery;
      if(settleAtMs-lastStageTimelineAt>=10000){
        const counts=delivery&&typeof delivery==='object'?delivery:{};
        counts.terrainKernelCompleted=Number(currentSample.worker?.detail?.kernel?.completed)||0;
        counts.terrainKernelJobs=Number(currentSample.worker?.detail?.kernel?.jobs)||0;
        counts.distanceManagerUpdates=Number(currentSample.worker?.detail?.worldgen?.totalDistanceManagerUpdates)||0;
        counts.distanceManagerBudgetExhaustions=Number(currentSample.worker?.detail?.worldgen?.distanceManagerBudgetExhaustions)||0;
        const deltas={};
        if(previousStageTimeline){
          const elapsed=(settleAtMs-previousStageTimeline.atMs)/1000;
          for(const key of ['loaded','receivedFrames','workerMessagesReceived','sentChunkQueueEntries','acknowledgedChunks',
            'readySelectedChunks','sentBatches','chunkBroadcastItems','terrainKernelCompleted','terrainKernelJobs',
            'distanceManagerUpdates','distanceManagerBudgetExhaustions','meshSubmitted','meshed','uploadQueued','uploaded','uploadPasses']){
            const now=key==='loaded'?currentSample.loaded:Number(counts[key])||0;
            const before=key==='loaded'?previousStageTimeline.loaded:Number(previousStageTimeline.counters[key])||0;
            deltas[key]=now-before;
          }
          deltas.elapsedSeconds=(settleAtMs-previousStageTimeline.atMs)/1000;
          deltas.loadedChunksPerSecond=deltas.elapsedSeconds>0?deltas.loaded/deltas.elapsedSeconds:null;
        }
        result.stageTimeline.push({atMs:settleAtMs,loaded:currentSample.loaded,counters:counts,deltas});
        previousStageTimeline={atMs:settleAtMs,loaded:currentSample.loaded,counters:counts};
        lastStageTimelineAt=settleAtMs;
      }
      if(previousSettleSample){
        const elapsed=(currentSample.atMs-previousSettleSample.atMs)/1000;
        currentSample.chunkDelta=currentSample.loaded-previousSettleSample.loaded;
        currentSample.chunksPerSecond=elapsed>0?currentSample.chunkDelta/elapsed:null;
      }else{currentSample.chunkDelta=null;currentSample.chunksPerSecond=null;}
      result.settleSamples.push(currentSample);
      previousSettleSample=currentSample;
    }
    if (memorySample && settleAtMs - lastMemorySampleAt >= 5000) {
      lastMemorySampleAt = settleAtMs;
      result.lowendMemorySamples ||= [];
      result.lowendMemorySamples.push({phase:'settle',atMs:settleAtMs,memory:await memorySample()});
    }
    await sleep(1000);
  }
  result.loadingPresentation=await summarizePresentation();
  await evaluate(`if(globalThis.__gaiusReleasePresent)globalThis.__gaiusReleasePresent.active=false;true`);
  (result.fullSettleSeconds!=null?pass:fail)('releaseFullSettle',result.settleStatus);
  const seedEvidence=await command('/seed',/(?:seed[^-+0-9]*)(-?\d{1,20})/i);
  result.seedEvidence=seedEvidence;
  const seedText=Array.isArray(seedEvidence.response)?seedEvidence.response.join(' '):String(seedEvidence.response||'');
  const actualSeed=/\bseed\b[^-+0-9]*(-?\d{1,20})/i.exec(seedText)?.[1];
  (actualSeed===report.seed?pass:fail)('releaseSeed',{requested:report.seed,actual:actualSeed});
  result.environmentCommands=[];
  for(const text of ['/time set 6000','/gamerule advance_time false','/weather clear'])
    result.environmentCommands.push({text,...await command(text)});
  (result.environmentCommands.every(c=>c.ok)?pass:fail)('releaseEnvironment',result.environmentCommands);
  const origin=(await evaluate(stateExpr)).player;
  // Give the double-tap enough air time to register before gravity brings the player back to the
  // terrain. A short lift can look stable on the ground and silently turns this into a blocked walk.
  const lift=async player=>{
    const result=await command(`/tp @s ${player.x} ${player.y+100} ${player.z} ${player.yaw||0} ${player.pitch||0}`,null,true);
    // Chat closes after /tp and steals focus. Re-focus the canvas for every retry immediately
    // before the vanilla Space double-tap, otherwise the key edges never reach Minecraft.
    await clickAt(640,360); await sleep(25);
    return result;
  };
  const tapSpace=async()=>{await keyEvent('keyDown',' ','Space',32,' ');await sleep(120);await keyEvent('keyUp',' ','Space',32,'');};
  await clickAt(640,360); await sleep(300);
  const flight=await enableCreativeFlight({tap:tapSpace,readPlayer:async()=>{
    const player=(await evaluate(stateExpr)).player;return player;
  },lift,sleep});
  result.route={mode:'creative-flight',origin:{x:origin.x,y:origin.y,z:origin.z,yaw:origin.yaw,pitch:origin.pitch},
    heading:{yaw:origin.yaw,pitch:origin.pitch},key:'w',durationMs:60000,sampleIntervalMs:5000,
    worldBlocksModified:false,flight};
  (flight.enabled?pass:fail)('releaseRoute',result.route);
  if(!flight.enabled)throw new Error('creative flight could not be verified');
  await sleep(5000);
  await clickAt(640,360);await sleep(300);
  await evaluate('clearInterval(window.__m3drainTimer);true');
  await evaluate('(globalThis.__gaiusFrameTelemetry||(globalThis.__gaiusFrameTelemetry={})).enabled=true;true');
  await cdp.send('Performance.enable');
  const metrics=async()=>Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(x=>[x.name,x.value]));
  const before=await evaluate(stateExpr);
  result.viewport=await evaluate(`(()=>{const c=document.querySelector('canvas');return {width:innerWidth,height:innerHeight,dpr:devicePixelRatio,canvas:c?{width:c.width,height:c.height,cssWidth:c.clientWidth,cssHeight:c.clientHeight}:null};})()`);
  const a=await metrics();
  await evaluate(`globalThis.__gaiusReleasePresent={active:true,last:null,intervals:[],started:performance.now(),frames:globalThis.__gaiusGlFrameGuard.frames,framesAtStart:globalThis.__gaiusGlFrameGuard.frames};true`);
  await evaluate(`globalThis.GaiusWebGpu?.surfaceGateReset?.();true`).catch(()=>{});
  await startReleaseDiagnostics(evaluate);
  const cameraTurn = framePacingTest
    ? await evaluate('globalThis.__gaiusFramePacingCamera?.start?.()===true') : null;
  let cameraEvents=0,cameraErrors=0,cameraPending=false,cameraIndex=0;
  const cameraPattern=[620,640,660,680,660,640,620,600];
  const cameraTimer=framePacingTest?setInterval(()=>{
    if(cameraPending)return;
    cameraPending=true;
    cdp.send('Input.dispatchMouseEvent',{type:'mouseMoved',x:cameraPattern[cameraIndex++%cameraPattern.length],y:360,
      button:'none',buttons:0}).then(()=>cameraEvents++,()=>cameraErrors++).finally(()=>{cameraPending=false;});
  },100):null;
  cameraTimer?.unref?.();
  const captureHandlers=new Map();
  const boundedCdpConnection=framePacingTest?{
    send:(method,params,sessionId)=>cdp.send(method,params,sessionId),
    on:(method,listener)=>{
      const handler=(params,sessionId)=>listener({method,params,sessionId});
      captureHandlers.set(listener,{method,handler});cdp.on(method,handler);
    },
    off:(method,listener)=>{
      const entry=captureHandlers.get(listener);
      if(entry?.method===method)cdp.off(method,entry.handler);
      captureHandlers.delete(listener);
    },
  }:null;
  const boundedCapture=framePacingTest?new BoundedCdpCapture({
    conn:boundedCdpConnection,
    traceConn:boundedCdpConnection,
    out:join(out,`frame-pacing-${framePacingMode}`),durationMs:65000,commandTimeoutMs:5000,
    log:(kind,text)=>log(kind,text)}):null;
  let release=async()=>{};
  const path=[];
  const serverTarget=[...workerTargets.values()].find(target=>/singleplayer-server-worker/i.test(String(target.url||'')));
  const serverSamples=[];
  const key='w',code='KeyW',vk=87;
  let walkStart=Date.now();
  try {
    if(boundedCapture){
      activeBoundedCaptures.add(boundedCapture);
      await boundedCapture.start();
      await boundedCapture.trackTarget({sessionId:undefined,targetId:pageTargetId,type:'page',url:report.pageUrl,owner:'main'});
      await Promise.all([...workerTargets.values()].map(target=>boundedCapture.trackTarget({
        ...target,targetId:target.targetId||target.sessionId,owner:'main'})));
    }
    release=await holdKeyDuring({
      down:()=>keyEvent('keyDown',key,code,vk,''),
      repeat:()=>cdp.send('Input.dispatchKeyEvent',{type:'keyDown',key,code,windowsVirtualKeyCode:vk,nativeVirtualKeyCode:vk,autoRepeat:true}),
      up:()=>keyEvent('keyUp',key,code,vk,''),
    });
    walkStart=Date.now();
    while(Date.now()-walkStart<60000){
    await sleep(Math.min(5000,60000-(Date.now()-walkStart)));
    const pageEvaluateStarted=performance.now();
    const s=await evaluate(stateExpr);
    const row={ms:Date.now()-walkStart,cdpEvaluateMs:performance.now()-pageEvaluateStarted,
      player:s.player,loaded:s.loaded,dist:s.dist,pipeline:s.pipeline,audit:s.audit};
    if(serverTarget){
      const workerEvaluateStarted=performance.now();
      row.serverWorker=await evaluate(`(()=>{const k=globalThis.GaiusWorldgenKernel?.telemetry?.()||{};
        return {kernel:{jobs:Number(k.jobs)||0,completed:Number(k.completed)||0,queued:Number(k.queued??k.host?.queued)||0,
          inFlight:Number(k.inFlight??k.host?.inFlight)||0,fallbacks:Number(k.fallbacks)||0},
          worldgen:globalThis.__gaiusWorldgenStats||null,messages:globalThis.__gaiusWorkerMessageTelemetry||null};})()`,serverTarget.sessionId).catch(error=>({error:String(error)}));
      row.serverWorkerCdpMs=performance.now()-workerEvaluateStarted;
      serverSamples.push({ms:row.ms,cdpEvaluateMs:row.serverWorkerCdpMs,...row.serverWorker});
    }
    if(screenshot){
      const pathName=await screenshot(`release-walk-${String(path.length+1).padStart(3,'0')}.png`);
      row.screenshot=pathName?.split(/[\\/]/).pop()||null;
      row.screenshotPath=pathName||null;
    }
      path.push(row);
      if (memorySample) {
        const memory = await memorySample();
        result.lowendMemorySamples ||= [];
        result.lowendMemorySamples.push({atMs: row.ms, memory});
      }
    }
  } catch (error) {
    clearInterval(cameraTimer);
    await release().catch(()=>{});
    await evaluate('globalThis.__gaiusFramePacingCamera?.stop?.()', undefined).catch(()=>{});
    if(boundedCapture){
      const captured=await boundedCapture.stop('measurement-error').catch(()=>null);
      if(captured){
        result.traceCapture={reason:captured.reason,elapsedMs:captured.elapsedMs,categories:captured.categories,
          trace:captured.trace,profiles:captured.profiles,valid:false};
        fail('framePacingTraceCapture',result.traceCapture);
      }
      activeBoundedCaptures.delete(boundedCapture);
    }
    throw error;
  }
  clearInterval(cameraTimer);
  await release();
  if(serverSamples.length>1){const first=serverSamples[0],last=serverSamples.at(-1);
    result.walkServerTelemetry={samples:serverSamples,
      kernelDelta:{jobs:(last.kernel?.jobs||0)-(first.kernel?.jobs||0),
        completed:(last.kernel?.completed||0)-(first.kernel?.completed||0),
        fallbacks:(last.kernel?.fallbacks||0)-(first.kernel?.fallbacks||0),
        queued:last.kernel?.queued||0,inFlight:last.kernel?.inFlight||0}};
  }else result.walkServerTelemetry={supported:false,reason:'singleplayer server Worker target unavailable'};
  const pacing=await evaluate(`(()=>{const p=globalThis.__gaiusReleasePresent;p.active=false;
    const s=p.intervals.slice().sort((a,b)=>a-b),q=x=>s.length?s[Math.min(s.length-1,Math.floor(s.length*x))]:null;
    const span=performance.now()-p.started,frames=globalThis.__gaiusGlFrameGuard.frames-p.frames;
    return {spanMs:span,frames,fps:frames*1000/span,p50Ms:q(.5),p95Ms:q(.95),p99Ms:q(.99),intervals:p.intervals,firstAt:p.first??null};})()`);
  Object.assign(pacing,summarizeReleaseIntervals(pacing.intervals,pacing.spanMs));
  const cameraEvidence=framePacingTest?await evaluate(`(()=>{const c=globalThis.__gaiusFramePacingCamera;
    return c?.stop?{...c.stop(),started:${cameraTurn===true},cdpEvents:${cameraEvents},cdpErrors:${cameraErrors}}:null;})()`):null;
  result.cameraTurn=cameraEvidence;
  if(framePacingTest){
    const valid=cameraEvidence?.started===true&&cameraEvidence.cdpEvents>=8
      &&Number(cameraEvidence.yawSamples)>=8&&Number(cameraEvidence.yawTravelDegrees)>=2;
    (valid?pass:fail)('framePacingCameraTurn',cameraEvidence);
  }
  if(boundedCapture){
    const captured=await boundedCapture.stop('measurement-complete');
    const traceValid=Number(captured.trace?.bytes)>0&&captured.trace?.truncated!==true;
    const profileResults=Array.isArray(captured.profiles)?captured.profiles:[];
    const profileSucceeded=profile=>Boolean(profile?.file&&!profile?.error);
    const mainProfile=profileResults.find(profile=>profile?.identity?.type==='page'
      &&profile.identity.targetId===String(pageTargetId));
    const serverProfile=serverTarget?profileResults.find(profile=>profile?.identity?.type==='worker'
      &&/singleplayer-server-worker/i.test(String(profile.identity.url||''))):null;
    const profilesValid=profileSucceeded(mainProfile)&&(!serverTarget||profileSucceeded(serverProfile));
    result.traceCapture={reason:captured.reason,elapsedMs:captured.elapsedMs,categories:captured.categories,
      trace:captured.trace,profiles:profileResults,
      expectedProfiles:{main:mainProfile?.identity||null,server:serverProfile?.identity||null},
      valid:traceValid&&profilesValid};
    (traceValid&&profilesValid?pass:fail)('framePacingTraceCapture',result.traceCapture);
    activeBoundedCaptures.delete(boundedCapture);
  }
  const diagnostics=await finishReleaseDiagnostics(evaluate);
  const b=await metrics();
  const after=await evaluate(stateExpr);
  const surfaceGate=await evaluate(`globalThis.GaiusWebGpu?.surfaceGateSnapshot?.()||null`).catch(()=>null);
  result.walk={...pacing,surfaceGate,before:before.player,after:after.player,path,
    cdpResponsiveness:{page:summarizeLatencySamples(path.map(row=>row.cdpEvaluateMs)),
      worker:summarizeLatencySamples(serverSamples.map(row=>row.cdpEvaluateMs)),
      workerSamples:serverSamples.length,workerErrors:serverSamples.filter(row=>row.error).map(row=>row.error)},
    metricsWallMs:(b.Timestamp-a.Timestamp)*1000,
    mainThreadBusyFraction:(b.TaskDuration-a.TaskDuration)/(b.Timestamp-a.Timestamp),
    frameTelemetry:null,
    diagnostics:{rAF:summarizeReleaseIntervals(diagnostics?.rafIntervals,diagnostics?.elapsedMs),
      game:summarizeReleaseIntervals(diagnostics?.gameIntervals,pacing.spanMs),
      longTasks:{supported:Boolean(diagnostics)&&!diagnostics.longTaskError,count:diagnostics?.longTasks?.length||0,
        longestMs:Math.max(0,...(diagnostics?.longTasks||[]).map(x=>Number(x.duration)||0)),
        atLeast16_7Ms:(diagnostics?.longTasks||[]).filter(x=>Number(x.duration)>=16.7).length,
        atLeast50Ms:(diagnostics?.longTasks||[]).filter(x=>Number(x.duration)>=50).length,
        entries:diagnostics?.longTasks||[]},
      gc:{supported:diagnostics?.gcSupported===true,count:diagnostics?.gcEntries?.length||0,
        totalMs:(diagnostics?.gcEntries||[]).reduce((sum,x)=>sum+(Number(x.duration)||0),0),
        entries:diagnostics?.gcEntries||[],error:diagnostics?.gcError||null},
      memory:{supported:diagnostics?.memorySupported===true,samples:diagnostics?.memorySamples||[],error:diagnostics?.memoryError||null},
      pipeline:{raf:summarizeReleasePipelineFrames(diagnostics?.pipelineFrames),
        game:summarizeReleasePipelineFrames(diagnostics?.gamePipelineFrames)},
      chunkDraw:summarizeReleaseChunkFirstDraw(diagnostics?.chunkDraw,diagnostics?.chunkPackets),
      chunkCausal:summarizeReleaseChunkCausal(diagnostics?.causalTelemetry)} };
  result.walk.diagnostics.optInTimeline=diagnostics?.optInTimeline||null;
  if(framePacingTest){
    const pageLatency=result.walk.cdpResponsiveness.page;
    const pageResponsive=pageLatency.sampleCount>=10&&Number(pageLatency.maxMs)<=1000;
    (pageResponsive?pass:fail)('framePacingCdpResponsiveness',{
      page:pageLatency,minimumSamples:10,maximumSampleMs:1000});
    const first=serverSamples[0],last=serverSamples.at(-1);
    const kernelDelta={jobs:(last?.kernel?.jobs||0)-(first?.kernel?.jobs||0),
      completed:(last?.kernel?.completed||0)-(first?.kernel?.completed||0)};
    const workerLatency=result.walk.cdpResponsiveness.worker;
    const workerResponsive=Boolean(serverTarget)&&serverSamples.length>=10
      &&serverSamples.every(row=>!row.error&&Number.isFinite(row.cdpEvaluateMs)&&row.cdpEvaluateMs<=1000);
    const kernelDelivered=kernelDelta.jobs>0&&kernelDelta.completed>0
      &&Number(last?.kernel?.queued)===0&&Number(last?.kernel?.inFlight)===0;
    const delivery={workerTarget:serverTarget?{targetId:serverTarget.targetId,url:serverTarget.url}:null,
      samples:serverSamples.length,latency:workerLatency,maximumSampleMs:1000,kernelDelta,
      lastKernel:last?.kernel||null,kernelDelivered};
    (workerResponsive&&kernelDelivered?pass:fail)('framePacingWorkerDelivery',delivery);
  }
  if(pixelClassify){
    for(const row of path)if(row.screenshotPath){
      row.skyBelowHorizon=pixelClassify(row.screenshotPath,"0.05,0.05,0.95,0.95");
      delete row.screenshotPath;
    }
  }
  result.walk.horizontalBlocks=Math.hypot(after.player.x-before.player.x,after.player.z-before.player.z);
  const validity=walkValidity(before.player,path,result.renderDistance);
  result.walk.validity=validity;
  (validity.ok?pass:fail)('releaseWalkMoves',{horizontalBlocks:result.walk.horizontalBlocks,...validity});
  result.mode=framePacingTest?framePacingMode:releaseThroughput?'uncapped':'60hz';
  const presentValid=Number.isFinite(pacing.fps)&&pacing.fps>0&&Number.isFinite(pacing.p95Ms)&&pacing.intervals.length>100&&pacing.spanMs>=60000&&pacing.spanMs<65000;
  (presentValid?pass:fail)('releasePresentation',{fps:pacing.fps,p95Ms:pacing.p95Ms,intervals:pacing.intervals.length,spanMs:pacing.spanMs});
  const frameTelemetryRaw=await evaluate('globalThis.__gaiusFrameTelemetry||null');
  const frame=frameTelemetryRaw?{
    enabled:frameTelemetryRaw.enabled===true,swapInterval:frameTelemetryRaw.swapInterval??null,
    frameCount:frameTelemetryRaw.frameCount??null,totalFrameMillis:frameTelemetryRaw.totalFrameMillis??null,
    longestFrameMillis:frameTelemetryRaw.longestFrameMillis??null,sampleCount:frameTelemetryRaw.sampleCount??null,
    jspiSlowActivationCount:frameTelemetryRaw.jspiSlowActivationCount??null,
  }:null;
  result.vsyncTelemetry={__gaiusFrameTelemetry:frame};
  if(framePacingTest){
    // A long activation can still cross the diagnostic threshold after several fair host
    // yields.  Its count is evidence to report, not a failure by itself.  Require an active,
    // numeric counter so an absent telemetry field cannot be mistaken for zero.
    const slowActivations=frame?.jspiSlowActivationCount;
    const telemetryEnabled=frame?.enabled===true;
    const slowCountValid=Number.isFinite(slowActivations);
    (telemetryEnabled&&slowCountValid?pass:fail)('framePacingJsPiSuspension',{
      telemetryEnabled,slowActivations,source:'__gaiusFrameTelemetry.jspiSlowActivationCount',
      interpretation:'diagnostic count; host fairness is checked by CDP and worker delivery'});
  }
  result.optionsReadback=await evaluate(`(()=>{const f=globalThis.__gaiusPersistentFiles?.['/gaius/options.txt'];
    if(typeof f==='string')return atob(f);if(f instanceof Uint8Array)return new TextDecoder().decode(f);return null;})()`);
  const optionCheck=optionsValidity(result.optionsReadback,result.renderDistance??releaseDistance,releaseThroughput,
    result.simulationDistance??releaseSimulationDistance,framePacingMode,releaseVsync260);
  (optionCheck.ok?pass:fail)('releaseOptions',optionCheck);
  result.walk.frameTelemetry=frame;
  const expectedSwapInterval=framePacingTest?(framePacingMode==='vsync'?1:0):(releaseVsync260||!releaseThroughput?1:0);
  result.vsyncEvidence={expected:expectedSwapInterval,actual:frame?.swapInterval??null};
  (frame?.swapInterval===expectedSwapInterval?pass:fail)('releaseVsync',result.vsyncEvidence);
  if(!releaseThroughput&&!framePacingTest){
    const wait=Math.max(0,createdAt+enteredAt*1000+300000-Date.now());
    await log('release-memory-wait',JSON.stringify({waitMs:wait}));
    await sleep(wait);
    result.memory={afterWorldMs:Date.now()-(createdAt+enteredAt*1000),isolates:[]};
    for(const target of [{sessionId:undefined,type:'page',url:report.pageUrl},...workerTargets.values()]){
      try{
        const heap=await cdp.send('Runtime.getHeapUsage',{},target.sessionId);
        const linear=await evaluate(`(()=>{const m=globalThis.__gaiusWasmMemory;return {linearMemoryBytes:m?.buffer?.byteLength??null,memoryTelemetry:globalThis.__gaiusMemoryTelemetry||null,kernels:globalThis.__gaiusKernels?.telemetry?.()||null,worldgen:globalThis.GaiusWorldgenKernel?.telemetry?.()||null};})()`,target.sessionId);
        result.memory.isolates.push({...target,heap,...linear});
      }catch(error){result.memory.isolates.push({...target,error:String(error)});}
    }
    result.memory.completedAfterWorldMs=Date.now()-(createdAt+enteredAt*1000);
    result.memory.scope='Exposed linear memory references only; kernel closure memories may be unavailable. No heap-wide object enumeration.';
  }
  await log('release-performance',JSON.stringify({mode:result.mode,renderDistance:result.renderDistance,fullSettleSeconds:result.fullSettleSeconds,walkValid:validity.ok,memoryIsolates:result.memory?.isolates.length}));
  return result;
}
