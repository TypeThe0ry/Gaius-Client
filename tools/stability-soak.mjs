// Optional long-running, input-driven session for the singleplayer CDP harness.
import {appendFile, writeFile} from 'node:fs/promises';
import {createWriteStream,readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {join} from 'node:path';
import {holdKeyDuring} from './flight-input.mjs';
const moduleSource=readFileSync(new URL(import.meta.url),'utf8');
const moduleSha256=createHash('sha256').update(moduleSource).digest('hex');

export const SOAK_PROBE_SOURCE = `(()=>{
 if(globalThis.__gaiusSoakProbe)return;
 const p=globalThis.__gaiusSoakProbe={frames:0,maxFrameMs:0,frameStalls:0,longTasks:0,longTaskMs:0,maxLongTaskMs:0,longStalls:0,deviceLost:[],lastFrame:null};
 if(typeof requestAnimationFrame==='function')requestAnimationFrame(function tick(t){if(!p.suspended&&p.lastFrame!==null){const d=t-p.lastFrame;p.maxFrameMs=Math.max(p.maxFrameMs,d);if(d>1000)p.frameStalls++;}p.lastFrame=p.suspended?null:t;p.frames++;requestAnimationFrame(tick)});
 try{new PerformanceObserver(list=>{for(const e of list.getEntries()){if(p.suspended||e.startTime<(p.since||0))continue;p.longTasks++;p.longTaskMs+=e.duration;p.maxLongTaskMs=Math.max(p.maxLongTaskMs,e.duration);if(e.duration>1000)p.longStalls++;}}).observe({type:'longtask',buffered:true})}catch{}
 const proto=globalThis.GPUAdapter?.prototype;if(proto&&!proto.__gaiusSoak){proto.__gaiusSoak=true;const orig=proto.requestDevice;proto.requestDevice=async function(...args){const d=await orig.apply(this,args);d.lost.then(info=>p.deviceLost.push({at:Date.now(),reason:info.reason,message:info.message}));return d}}
})();`;

const memoryExpression = `(()=>{const p=globalThis.__gaiusSoakProbe||null;let wasm=null;try{wasm=globalThis.__gaiusWasmMemory?.buffer?.byteLength??null}catch{};return {wasmLinearBytes:wasm,probe:p,gpuLost:globalThis.GaiusWebGpu?.lostReason||null}})()`;

export async function runStabilitySoak(h) {
 const {cdp,evaluate,stateExpr,out,log,durationMs,sleep,keyEvent,clickAt,clickWidget,releaseInput,command,takeOffCreativeFlight,landCreativeFlight,ground,exceptions,logLines}=h;
 const key=async(cdp,k,code,vk,text)=>{await keyEvent(cdp,'keyDown',k,code,vk,text);await sleep(150);await keyEvent(cdp,'keyUp',k,code,vk,'')};
 const rows=[],actions=[],snapshots=[],workerTargets=new Map();
 await writeFile(join(out,'soak-harness-source.mjs'),moduleSource);
 await writeFile(join(out,'soak-harness-sha256.txt'),moduleSha256+'\n');
 let snapshotSink=null;
 cdp.on('HeapProfiler.addHeapSnapshotChunk',(p,sid)=>{if(snapshotSink&&snapshotSink.sessionId===sid)snapshotSink.stream.write(p.chunk)});
 async function snapshot(label){
  await evaluate(cdp,'(__gaiusSoakProbe.suspended=true,true)');
  const targets=[{name:'page',sessionId:undefined},...[...workerTargets.values()].map((w,i)=>({...w,name:(/singleplayer-server|server-worker/i.test(w.url)?'server-':'worker-')+i}))];
  for(const t of targets){const file=join(out,`${label}-${t.name}.heapsnapshot`),stream=createWriteStream(file);const at=Date.now();snapshotSink={stream,sessionId:t.sessionId};
   try{await cdp.send('HeapProfiler.enable',{},t.sessionId);await cdp.send('HeapProfiler.takeHeapSnapshot',{reportProgress:false},t.sessionId);snapshots.push({label,target:t.name,file,seconds:(at-start)/1000,durationMs:Date.now()-at})}catch(e){snapshots.push({label,target:t.name,error:String(e)})}finally{snapshotSink=null;await new Promise((ok,bad)=>{stream.on('error',bad);stream.end(ok)})}
  }
  await writeFile(join(out,'soak-snapshots.json'),JSON.stringify(snapshots,null,2));
  await evaluate(cdp,'(__gaiusSoakProbe.suspended=false,__gaiusSoakProbe.lastFrame=null,__gaiusSoakProbe.since=performance.now(),true)');
 }
 const known=await cdp.send('Target.getTargets');
 // Auto-attached targets are also discoverable here; explicitly attach for a stable sampling session.
 async function attachWorkers(){
  const {targetInfos}=await cdp.send('Target.getTargets');
  for(const t of targetInfos.filter(t=>t.type==='worker'))if(!workerTargets.has(t.targetId)){
   try{const a=await cdp.send('Target.attachToTarget',{targetId:t.targetId,flatten:true});workerTargets.set(t.targetId,{sessionId:a.sessionId,url:t.url});await evaluate(cdp,SOAK_PROBE_SOURCE,a.sessionId)}catch(e){actions.push({type:'worker-attach-error',error:String(e),url:t.url})}
  }
 }
 await attachWorkers();
 const csv=join(out,'soak.csv'),jsonl=join(out,'soak-samples.jsonl');
 await writeFile(csv,'seconds,phase,jsHeapBytes,embedderHeapBytes,backingStorageBytes,wasmLinearBytes,workerHeapBytes,workerWasmLinearBytes,loadedChunks,fps,rafFps,maxFrameMs,frameStalls,longTasks,longTaskMs,maxLongTaskMs,longStalls,deviceLost,consoleErrors,uncaughtErrors,sampleLatencyMs,x,y,z,renderDistance\n');
 await writeFile(jsonl,'');
 let phase='walking',start=Date.now(),lastFrames=null,lastSample=null,nextSample=start;
 async function sample(){
  const at=Date.now();await attachWorkers();
  const [state,heap,memory]=await Promise.all([evaluate(cdp,stateExpr),cdp.send('Runtime.getHeapUsage'),evaluate(cdp,memoryExpression)]);
  const workers=[];
  for(const [targetId,w]of workerTargets){try{const [heap,memory]=await Promise.all([cdp.send('Runtime.getHeapUsage',{},w.sessionId),evaluate(cdp,memoryExpression,w.sessionId)]);workers.push({targetId,...w,heap,memory})}catch(e){workers.push({targetId,...w,error:String(e)})}}
  const p=memory.probe||{},seconds=(at-start)/1000,valid=workers.filter(w=>w.heap);
  const row={seconds,phase,state,heap,memory,workers,sampleLatencyMs:Date.now()-at,consoleErrors:logLines.filter(l=>/console\.error|log\.error/.test(l)).length,uncaughtErrors:exceptions.length,rafFps:lastSample!==null?(p.frames-lastFrames)/((at-lastSample)/1000):null};
  lastSample=at;lastFrames=p.frames;rows.push(row);
  const sum=fn=>{const values=valid.map(fn).filter(Number.isFinite);return values.length?values.reduce((n,v)=>n+v,0):null};
  const vals=[seconds,phase,heap.usedSize,heap.embedderHeapUsedSize,heap.backingStorageSize,memory.wasmLinearBytes,sum(w=>w.heap.usedSize),sum(w=>w.memory.wasmLinearBytes),state.loaded,state.fps?.gameFps??state.fps?.fps,row.rafFps,p.maxFrameMs,p.frameStalls,p.longTasks,p.longTaskMs,p.maxLongTaskMs,p.longStalls,p.deviceLost?.length,row.consoleErrors,row.uncaughtErrors,row.sampleLatencyMs,state.player?.x,state.player?.y,state.player?.z,state.dist?.effectiveRenderDistance];
  await appendFile(csv,vals.map(v=>typeof v==='object'?JSON.stringify(v):v??'').map(v=>`"${String(v).replaceAll('"','""')}"`).join(',')+'\n');
  await appendFile(jsonl,JSON.stringify(row)+'\n');await log('soak-sample',JSON.stringify({seconds,phase,heap:heap.usedSize,workers:sum(w=>w.heap.usedSize),loaded:state.loaded,player:state.player}));
 }
 let samplingError=null;
 async function wait(ms){const end=Date.now()+ms;while(Date.now()<end){if(Date.now()>=nextSample){try{await sample()}catch(e){samplingError=String(e);await log('soak-sample-error',samplingError)}nextSample+=30000}await sleep(Math.min(250,Math.max(1,end-Date.now())))}}
 async function action(type,fn){const a={seconds:(Date.now()-start)/1000,type};try{a.result=await fn()}catch(e){a.error=String(e);await log('soak-action-error',JSON.stringify(a))}actions.push(a);await writeFile(join(out,'soak-actions.json'),JSON.stringify(actions,null,2));}
 async function resume(){for(let i=0;i<5&&(await evaluate(cdp,stateExpr)).screen;i++){await key(cdp,'Escape','Escape',27);await sleep(400)}if((await evaluate(cdp,stateExpr)).screen)throw new Error('could not resume gameplay');await clickAt(cdp,640,360);await sleep(300)}
 // Initial gameplay preparation is recorded, but the timed window starts after flight setup.
 await action('land-for-walking',()=>landCreativeFlight(cdp,ground));
 await action('prepare-walking-loop',async()=>{
  const x=Math.floor(ground.x),y=Math.floor(ground.y),z=Math.floor(ground.z);
  await command(cdp,`/fill ${x-24} ${y-1} ${z-24} ${x+24} ${y-1} ${z+24} minecraft:stone`);
  await command(cdp,`/fill ${x-24} ${y} ${z-24} ${x+24} ${y+3} ${z+24} minecraft:air`);
  await command(cdp,`/tp @s ${ground.x} ${ground.y} ${ground.z} 0 0`);
  return {x,y,z,radius:24};
 });
 await evaluate(cdp,'(()=>{const p=__gaiusSoakProbe;Object.assign(p,{frames:0,maxFrameMs:0,frameStalls:0,longTasks:0,longTaskMs:0,maxLongTaskMs:0,longStalls:0,lastFrame:null,since:performance.now()});return true})()');
 start=Date.now();nextSample=start;await sample();nextSample=start+30000;
 function growthCandidate(){
  const recent=rows.slice(-10);if(recent.length<8)return null;
  const series=new Map([['page',recent.map(r=>(r.heap.usedSize||0)+(r.heap.embedderHeapUsedSize||0))]]);
  for(const id of workerTargets.keys())series.set(id,recent.map(r=>{const w=r.workers.find(w=>w.targetId===id);return w?.heap?(w.heap.usedSize||0)+(w.heap.embedderHeapUsedSize||0):null}));
  for(const [target,values]of series){if(values.some(v=>!Number.isFinite(v)))continue;const rises=values.slice(1).filter((v,i)=>v>values[i]).length/(values.length-1),delta=values.at(-1)-values[0];if(rises>=0.8&&delta>Math.max(16*1024**2,values[0]*0.2))return {target,rises,delta,values}}
  return null;
 }
 let end=start+durationMs;let cycle=0,changed=false,flying=false,baselineTaken=false,growth=null;
 while(Date.now()<end){
  if(end-Date.now()<1000){await wait(end-Date.now());break}
  // Heap snapshots stop the page/Worker event loops for a long time on the
  // high-heap 26.2 path.  That is useful for a normal soak, but it defeats a
  // CPU profile run by timing out CDP before Profiler.stop can be collected.
  // Keep the growth signal in the result and make the expensive snapshot
  // explicitly opt-in for diagnostic runs.
  if(!baselineTaken&&(growth=growthCandidate())){
   await log('soak-growth-candidate',JSON.stringify(growth));
   if(process.env.SOAK_DISABLE_SNAPSHOTS !== '1'){
    const at=Date.now();await action('heap-baseline',()=>snapshot('baseline'));end+=Date.now()-at;
   } else await log('soak-snapshot-skipped','SOAK_DISABLE_SNAPSHOTS=1');
   baselineTaken=true;
  }
  if(!changed&&Date.now()-start>durationMs/2){
   await action('change-render-distance',async()=>{
    const original=await evaluate(cdp,stateExpr);await releaseInput(cdp);
    for(let i=0;i<4&&!/PauseScreen/.test((await evaluate(cdp,stateExpr)).screen);i++){await key(cdp,'Escape','Escape',27);await sleep(1200)}
    await clickWidget(cdp,'Options',10000);await clickWidget(cdp,'Video Settings',10000);
    let before;for(let i=0;i<30;i++){before=await evaluate(cdp,stateExpr);if(/VideoSettingsScreen/.test(before.screen))break;await sleep(100)}
    if(!/VideoSettingsScreen/.test(before.screen))throw new Error('Video Settings did not open');
    const shot=await cdp.send('Page.captureScreenshot',{format:'png'});await writeFile(join(out,'soak-distance-before.png'),Buffer.from(shot.data,'base64'));
    // The top-level telemetry omits nested OptionsList sliders. Click the right half of the nested row so 8 advances to 17. The first-page
    // Quality & Performance row was verified in the rendered 400x300 GUI.
    const pos=await evaluate(cdp,`(()=>{const r=document.querySelector('canvas').getBoundingClientRect();return {x:r.left+r.width*0.70,y:r.top+r.height*0.835}})()`);
    await clickAt(cdp,pos.x,pos.y);await sleep(1000);await clickWidget(cdp,'Done');await clickWidget(cdp,'Done');await key(cdp,'Escape','Escape',27);await sleep(1200);
    const after=await evaluate(cdp,stateExpr);if(after.dist?.effectiveRenderDistance===original.dist?.effectiveRenderDistance)throw new Error('render distance did not change');return {before:original.dist,after:after.dist,position:pos};
   });changed=true;
  }
  phase=cycle<8?'walking':'flying';
  if(phase==='flying'&&!flying){await action('enable-flight',async()=>{await resume();const f=await takeOffCreativeFlight(cdp);let state=await evaluate(cdp,stateExpr);if(!f.enabled||!(state.player?.y>ground.y+20))throw new Error(JSON.stringify({flight:f,player:state.player}));await command(cdp,`/tp @s ${state.player.x} 350 ${state.player.z} 0 25`);state=await evaluate(cdp,stateExpr);if(Math.abs(state.player.y-350)>1)throw new Error('flight cruise altitude not applied');flying=true;return {...f,cruiseAltitude:350}})}
  if(cycle%4===2)await action('inventory',async()=>{await key(cdp,'e','KeyE',69,'e');await wait(1800);const s=await evaluate(cdp,stateExpr);await key(cdp,'Escape','Escape',27);await wait(600);if(!/inventory|container/i.test(s.screen))throw new Error('inventory did not open: '+s.screen);return {screen:s.screen}});
  if(cycle%6===3)await action('pause',async()=>{await releaseInput(cdp);for(let i=0;i<4&&!/PauseScreen/.test((await evaluate(cdp,stateExpr)).screen);i++){await key(cdp,'Escape','Escape',27);await wait(1200)}const s=await evaluate(cdp,stateExpr);await key(cdp,'Escape','Escape',27);await wait(600);if(!/PauseScreen/.test(s.screen))throw new Error('pause did not open: '+s.screen);return {screen:s.screen}});
  await resume();const before=await evaluate(cdp,stateExpr);
  const k=cycle<8?['w','d','s','a'][cycle%4]:'w',code='Key'+k.toUpperCase(),vk=k.toUpperCase().charCodeAt(0);
  const release=await holdKeyDuring({down:()=>keyEvent(cdp,'keyDown',k,code,vk,''),repeat:()=>cdp.send('Input.dispatchKeyEvent',{type:'keyDown',key:k,code,windowsVirtualKeyCode:vk,nativeVirtualKeyCode:vk,autoRepeat:true}),up:()=>keyEvent(cdp,'keyUp',k,code,vk,'')});
  try{await wait(Math.min(cycle<8?4000:25000,Math.max(1,end-Date.now())))}finally{await release()}
  const after=await evaluate(cdp,stateExpr);await action('movement',async()=>({before:before.player,after:after.player,horizontalBlocks:Math.hypot((after.player?.x||0)-(before.player?.x||0),(after.player?.z||0)-(before.player?.z||0)),phase}));cycle++;
 }
 await sample();
 if(baselineTaken&&process.env.SOAK_DISABLE_SNAPSHOTS !== '1')await action('heap-final',()=>snapshot('final'));
 await action('return-to-placement',()=>command(cdp,`/tp @s ${ground.x} ${ground.y} ${ground.z} 0 90`));
 const workloadErrors=actions.filter(a=>a.error||a.type==='movement'&&a.result?.phase==='flying'&&!(a.result?.horizontalBlocks>0.5));
 const result={completed:Date.now()-start>=durationMs&&!samplingError&&workloadErrors.length===0,workloadErrors,growthCandidate:growth,durationMs:Date.now()-start,requestedMs:durationMs,samples:rows.length,samplingError,actions,snapshots,workerTargets:[...workerTargets.values()],initialTargets:known.targetInfos.map(t=>({type:t.type,url:t.url}))};
 await writeFile(join(out,'soak.json'),JSON.stringify(result,null,2));return result;
}
