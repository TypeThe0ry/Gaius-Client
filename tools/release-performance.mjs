import {terrainCompleteness} from './terrain-settle.mjs';
import {enableCreativeFlight, holdKeyDuring} from './flight-input.mjs';
import {summarizeFrameTimes} from '../port/scripts/performance-metrics.mjs';

// Opt-in release measurements. No game or distribution source is patched.
export const releasePerformance = process.env.RELEASE_PERF === '1';
export const releaseThroughput = releasePerformance && process.env.CLEAN_THROUGHPUT === '1';
export const releaseAutoTier = releasePerformance && process.env.RELEASE_AUTO_TIER === '1';
export const releaseDistance = releaseAutoTier ? null : Number(process.env.RENDER_DISTANCE || 8);
if (releasePerformance && !releaseAutoTier && (!Number.isInteger(releaseDistance) || releaseDistance < 2 || releaseDistance > 32))
  throw new Error('RENDER_DISTANCE must be an integer from 2 through 32');
if (releasePerformance && !releaseThroughput && process.env.PROFILE_60HZ !== '1')
  throw new Error('RELEASE_PERF requires PROFILE_60HZ=1 or CLEAN_THROUGHPUT=1');
export const releaseOptions = [
  'graphicsPreset:"fancy"', `renderDistance:${releaseDistance}`, 'simulationDistance:6',
  `enableVsync:${!releaseThroughput}`, `maxFps:${releaseThroughput ? 260 : 60}`,
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
      if(probe.last!==null)probe.intervals.push(t-probe.last);probe.last=t;}
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

export function optionsValidity(text,distance,throughput) {
  const options=Object.fromEntries(String(text||'').split(/\r?\n/).map(line=>line.split(':',2)));
  const actualDistance=Number(options.renderDistance);
  return {ok:(distance==null ? [6,8,10,12].includes(actualDistance) : actualDistance===distance)
    &&options.enableVsync===String(!throughput)
    &&Number(options.maxFps)===(throughput?260:60),options};
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

const RELEASE_DIAGNOSTIC_SOURCE = `(()=>{
  const old=globalThis.__gaiusReleaseProbe;
  if(old?.rafId)try{cancelAnimationFrame(old.rafId)}catch{}
  const probe=globalThis.__gaiusReleaseProbe={startedAt:performance.now(),lastRaf:null,lastGame:null,
    capacity:100000,active:true,rafIntervals:[],gameIntervals:[],pipelineFrames:[],gamePipelineFrames:[],
    longTasks:[],gcEntries:[],memorySamples:[],gcSupported:false,rafId:0};
  globalThis.__gaiusChunkDrawTelemetryEnabled=${process.env.RELEASE_CHUNK_DRAW === "1"};
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
  return evaluate(RELEASE_DIAGNOSTIC_SOURCE);
}

async function finishReleaseDiagnostics(evaluate) {
  return evaluate(`(()=>{const p=globalThis.__gaiusReleaseProbe;if(!p)return null;
    p.active=false;try{cancelAnimationFrame(p.rafId)}catch{};try{p.longTaskObserver?.disconnect()}catch{};try{p.gcObserver?.disconnect()}catch{};
    try{clearInterval(p.memoryTimer)}catch{};
    if(p.packetCapture&&p.packetCapture.positions?.push===p.packetCapture.wrappedPush)
      p.packetCapture.positions.push=p.packetCapture.originalPush;
    if(p.pipelineTelemetryEveryFrameOwned){if(p.pipelineTelemetryEveryFrame===undefined)delete globalThis.__gaiusChunkPipelineTelemetryEveryFrame;
      else globalThis.__gaiusChunkPipelineTelemetryEveryFrame=p.pipelineTelemetryEveryFrame;}
    const draw=globalThis.__gaiusChunkDrawTelemetry||null;
    return {elapsedMs:performance.now()-p.startedAt,rafIntervals:p.rafIntervals,gameIntervals:p.gameIntervals,
      pipelineFrames:p.pipelineFrames,gamePipelineFrames:p.gamePipelineFrames,chunkPackets:p.chunkPackets||[],
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

export async function measureReleaseWorld({cdp,evaluate,stateExpr,sleep,sample,report,createdAt,
  enteredAt,firstTerrainAt,settleMs,out,log,command,clickAt,keyEvent,screenshot,pixelClassify,
  pass,fail,workerTargets}) {
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
  while(Date.now()<deadline){
    const row=await sample('release-settle',result.firstTerrainSeconds===null);
    if(result.firstTerrainSeconds===null&&row.px&&!row.px.error&&row.px.other>0.3)
      result.firstTerrainSeconds=(Date.now()-createdAt)/1000;
    const status=terrainCompleteness(row);
    result.settleStatus=status;
    result.effectiveDistance=row.dist;
    const targetDistance=releaseDistance ?? status.renderDistance;
    if(Number.isInteger(targetDistance)&&targetDistance>=2&&targetDistance<=32)
      result.renderDistance=targetDistance;
    if(status.complete&&status.renderDistance===result.renderDistance){
      stableSince??=Date.now();
      if(Date.now()-stableSince>=5000&&result.firstTerrainSeconds!==null){
        result.fullSettleSeconds=(Date.now()-createdAt)/1000;break;
      }
    }else stableSince=null;
    await sleep(1000);
  }
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
  const lift=async player=>command(`/tp @s ${player.x} ${player.y+100} ${player.z} ${player.yaw||0} ${player.pitch||0}`,/Teleported/i);
  const tapSpace=async()=>{await keyEvent('keyDown',' ','Space',32,' ');await sleep(120);await keyEvent('keyUp',' ','Space',32,'');};
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
  await evaluate(`globalThis.__gaiusReleasePresent={active:true,last:null,intervals:[],started:performance.now(),frames:globalThis.__gaiusGlFrameGuard.frames};true`);
  await startReleaseDiagnostics(evaluate);
  const walkStart=Date.now();
  const path=[];
  const serverTarget=[...workerTargets.values()].find(target=>/singleplayer-server-worker/i.test(String(target.url||'')));
  const serverSamples=[];
  const key='w',code='KeyW',vk=87;
  const release=await holdKeyDuring({
    down:()=>keyEvent('keyDown',key,code,vk,''),
    repeat:()=>cdp.send('Input.dispatchKeyEvent',{type:'keyDown',key,code,windowsVirtualKeyCode:vk,nativeVirtualKeyCode:vk,autoRepeat:true}),
    up:()=>keyEvent('keyUp',key,code,vk,''),
  });
  try {while(Date.now()-walkStart<60000){
    await sleep(Math.min(5000,60000-(Date.now()-walkStart)));
    const s=await evaluate(stateExpr);
    const row={ms:Date.now()-walkStart,player:s.player,loaded:s.loaded,dist:s.dist,pipeline:s.pipeline,audit:s.audit};
    if(serverTarget){
      row.serverWorker=await evaluate(`(()=>{const k=globalThis.GaiusWorldgenKernel?.telemetry?.()||{};
        return {kernel:{jobs:Number(k.jobs)||0,completed:Number(k.completed)||0,queued:Number(k.queued??k.host?.queued)||0,
          inFlight:Number(k.inFlight??k.host?.inFlight)||0,fallbacks:Number(k.fallbacks)||0},
          worldgen:globalThis.__gaiusWorldgenStats||null,messages:globalThis.__gaiusWorkerMessageTelemetry||null};})()`,serverTarget.sessionId).catch(error=>({error:String(error)}));
      serverSamples.push({ms:row.ms,...row.serverWorker});
    }
    if(screenshot){
      const pathName=await screenshot(`release-walk-${String(path.length+1).padStart(3,'0')}.png`);
      row.screenshot=pathName?.split(/[\\/]/).pop()||null;
      row.screenshotPath=pathName||null;
    }
    path.push(row);
  }}finally{await release();}
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
    return {spanMs:span,frames,fps:frames*1000/span,p50Ms:q(.5),p95Ms:q(.95),p99Ms:q(.99),intervals:p.intervals};})()`);
  const diagnostics=await finishReleaseDiagnostics(evaluate);
  const b=await metrics();
  const after=await evaluate(stateExpr);
  result.walk={...pacing,before:before.player,after:after.player,path,
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
      chunkDraw:summarizeReleaseChunkFirstDraw(diagnostics?.chunkDraw,diagnostics?.chunkPackets)} };
  result.walk.diagnostics.optInTimeline=diagnostics?.optInTimeline||null;
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
  result.mode=releaseThroughput?'uncapped':'60hz';
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
  result.optionsReadback=await evaluate(`(()=>{const f=globalThis.__gaiusPersistentFiles?.['/gaius/options.txt'];
    if(typeof f==='string')return atob(f);if(f instanceof Uint8Array)return new TextDecoder().decode(f);return null;})()`);
  const optionCheck=optionsValidity(result.optionsReadback,result.renderDistance??releaseDistance,releaseThroughput);
  (optionCheck.ok?pass:fail)('releaseOptions',optionCheck);
  result.walk.frameTelemetry=frame;
  result.vsyncEvidence={expected:releaseThroughput?0:1,actual:frame?.swapInterval??null};
  (frame?.swapInterval===(releaseThroughput?0:1)?pass:fail)('releaseVsync',result.vsyncEvidence);
  if(!releaseThroughput){
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
