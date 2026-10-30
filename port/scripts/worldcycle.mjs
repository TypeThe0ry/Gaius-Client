// Same-document save lifecycle driver. Used by minecraft-263-singleplayer-cdp.mjs.
import {writeFile, open as openFile} from 'node:fs/promises';
import {join} from 'node:path';
import {writeSync} from 'node:fs';
import {gzipSync} from 'node:zlib';

export const storageObserverSource = `(()=>{
 if(globalThis.__worldcycleStorage)return;
 const s=globalThis.__worldcycleStorage={idbOpened:0,idbClosed:0,opfsOpened:0,opfsClosed:0,installedAt:Date.now()};
 const closed=new WeakSet();
 if(globalThis.IDBFactory){const open=IDBFactory.prototype.open;IDBFactory.prototype.open=function(...a){const r=open.apply(this,a);r.addEventListener('success',()=>s.idbOpened++);return r;};
 const close=IDBDatabase.prototype.close;IDBDatabase.prototype.close=function(...a){if(!closed.has(this)){closed.add(this);s.idbClosed++;}return close.apply(this,a);};}
 if(globalThis.FileSystemFileHandle?.prototype.createSyncAccessHandle){const p=FileSystemFileHandle.prototype,o=p.createSyncAccessHandle;p.createSyncAccessHandle=async function(...a){const h=await o.apply(this,a);s.opfsOpened++;return h;};}
 if(globalThis.FileSystemSyncAccessHandle){const p=FileSystemSyncAccessHandle.prototype,o=p.close;p.close=function(...a){if(!closed.has(this)){closed.add(this);s.opfsClosed++;}return o.apply(this,a);};}
})()`;

export async function runWorldCycles(h) {
 const {cdp,evaluate,waitFor,sleep,clickWidget,clickAt,key,command,releaseInput,stateExpr,screenExpr,out,log,report,pass,fail,worldTimeout,targets}=h;
 const result=report.worldcycle={cycles:[],samples:[],snapshots:[],playMs:60000,documentReloads:0,storageScope:'Observed opens and explicit closes in instrumented isolates; implicit IDB closure and unexposed handles cannot be counted. WasmGC objects are included in CDP heap usage; exposed linear memories only.'};
 let loaderId=(await cdp.send('Page.getFrameTree')).frameTree.frame.loaderId;
 cdp.on('Page.frameNavigated',p=>{if(!p.frame.parentId&&p.frame.loaderId!==loaderId){loaderId=p.frame.loaderId;result.documentReloads++;}});
 const save=()=>writeFile(join(out,'worldcycle.json'),JSON.stringify(result,null,2));
 async function sample(label,gc=false){
  let targetInfos=[];try{targetInfos=(await cdp.send('Target.getTargets')).targetInfos.filter(t=>t.type==='worker').map(t=>({id:t.targetId,url:t.url}));}catch(e){await log('worldcycle-targets-error',String(e));}
  const row={label,at:Date.now(),isolates:[],targets:targetInfos};
  // Detached Worker sessions can remain in the map until the CDP event loop
  // delivers Target.detachedFromTarget. Snapshot the map and tolerate a
  // detach between the check and the command; worker GC is not required for
  // the lifecycle table and is rejected by some Chrome Worker targets.
  const isolates=[{sessionId:undefined,type:'page'},...targets.values()];
  for(const t of isolates){
   try{if(gc&&!t.sessionId)await cdp.send('HeapProfiler.collectGarbage');
    row.isolates.push({...t,heap:await cdp.send('Runtime.getHeapUsage',{},t.sessionId),runtime:await evaluate(cdp,`({linearMemoryBytes:globalThis.__gaiusWasmMemory?.buffer?.byteLength??null,storage:globalThis.__worldcycleStorage||null,workers:globalThis.__gaiusSingleplayerWorkers?.size??null,ports:globalThis.__gaiusLocalServerPorts?.size??null,channels:globalThis.__gaiusNettyBridge?.channels?.size??null})`,t.sessionId)});
   }catch(e){row.isolates.push({...t,error:String(e)});}
  }
  result.samples.push(row);await log('worldcycle-memory',JSON.stringify(row));await save();return row;
 }
 async function snapshot(name){
  // Snapshot chunks can exceed V8's maximum string length. Stream them directly
  // to disk and capture only the page isolate; Worker heaps are sampled above.
  const filename=name+'.heapsnapshot.gz';
  const file=await openFile(join(out,filename),'wx');let active=true,writeError=null;
  // Each CDP chunk is an independent gzip member. Synchronous writes keep
  // memory bounded and finish before the CDP completion response is handled.
  const listener=(p,sid)=>{if(active&&!sid&&!writeError){try{const b=gzipSync(p.chunk,{level:1});let n=0;while(n<b.length)n+=writeSync(file.fd,b,n,b.length-n);}catch(e){writeError=e;}}};
  cdp.on('HeapProfiler.addHeapSnapshotChunk',listener);
  try{await log('worldcycle-snapshot',name+' started');await cdp.send('HeapProfiler.takeHeapSnapshot',{reportProgress:false});if(writeError)throw writeError;await file.sync();result.snapshots.push(filename);await log('worldcycle-snapshot',name+' finished');}
  finally{active=false;await file.close();}
 }
 async function quit(){
  await releaseInput(cdp);for(let i=0;i<4&&!/PauseScreen/.test(await evaluate(cdp,screenExpr));i++){await key(cdp,'Escape','Escape',27);await sleep(1000);}
  const before=await evaluate(cdp,stateExpr);await clickWidget(cdp,'Save and Quit to Title',10000);
  await waitFor(cdp,`${screenExpr}.endsWith('TitleScreen')&&!window.__gaiusMinecraftState?.level`,120000,'cycle title');
  await waitFor(cdp,'!globalThis.__gaiusSingleplayerWorkers?.size',120000,'cycle Worker registry empty');await sleep(2000);return before.player;
 }
 async function list(){await clickWidget(cdp,'Singleplayer',10000);await waitFor(cdp,`${screenExpr}.includes('SelectWorldScreen')`,60000,'cycle world list');await waitFor(cdp,'!!globalThis.__gaiusMinecraftState?.worldSelection',60000,'cycle list model');await waitFor(cdp,'!!globalThis.__gaiusMinecraftState?.worldSelection?.first',60000,'cycle entry');return (await evaluate(cdp,stateExpr)).worldSelection;}
 async function select(){return evaluate(cdp,`(()=>{const s=globalThis.__gaiusMinecraftState,f=s.worldSelection?.first;if(!f)return null;const r=document.querySelector('canvas').getBoundingClientRect(),z=s.screenSize;const x=r.left+(Number(f.x)+Number(f.width)/2)*r.width/Number(z.width),y=r.top+(Number(f.y)+Number(f.height)/2)*r.height/Number(z.height);return Number.isFinite(x)&&Number.isFinite(y)?{name:f.name,x,y}:null;})()`);}
 async function open(entry=null){const e=entry||await select();if(!e)throw new Error('cycle world entry disappeared');await clickAt(cdp,e.x,e.y);await clickWidget(cdp,'Play Selected World',5000);await waitFor(cdp,'!!globalThis.__gaiusMinecraftState?.level&&!globalThis.__gaiusMinecraftState?.screen&&!!globalThis.__gaiusMinecraftState?.player',worldTimeout,'cycle reentry');return e;}
 const anchor=await evaluate(cdp,stateExpr);result.anchor={player:anchor.player,hit:anchor.hit};
 const marker=String(anchor.hit?.blockPos||'').match(/x=(-?\d+), y=(-?\d+), z=(-?\d+)/);
 if(!marker)throw new Error('worldcycle cannot establish marker: initial block position unavailable');
 await command(cdp,`/setblock ${marker[1]} ${marker[2]} ${marker[3]} minecraft:stone`);
 await waitFor(cdp,`String(globalThis.__gaiusMinecraftState?.hit?.blockState||'').includes('minecraft:stone')`,10000,'worldcycle stone marker');
 anchor.hit=(await evaluate(cdp,stateExpr)).hit;
 if(!String(anchor.hit?.blockState||'').includes('minecraft:stone'))throw new Error('worldcycle marker verification failed');
 result.marker={pos:anchor.hit.blockPos,state:anchor.hit.blockState,command:`/setblock ${marker[1]} ${marker[2]} ${marker[3]} minecraft:stone`,verified:true};
 async function check(label,leave){
  await sleep(2000);const s=await evaluate(cdp,stateExpr);const delta=s.player&&leave?Math.hypot(s.player.x-leave.x,s.player.y-leave.y,s.player.z-leave.z):null;
  await command(cdp,`/tp @s ${anchor.player.x} ${anchor.player.y} ${anchor.player.z} 0 90`);await sleep(1200);const hit=(await evaluate(cdp,stateExpr)).hit;
  const block=hit?.blockPos===anchor.hit?.blockPos&&hit?.blockState===anchor.hit?.blockState;
  const item={label,delta,block,hit,player:s.player};(delta!==null&&delta<2.5&&block?pass:fail)('worldcycle-'+label,item);return item;
 }
 await sample('initial-world',true);
 for(let i=1;i<=8;i++){
  const started=Date.now();await log('worldcycle',`cycle ${i}: play 60s`);
  // Keep the marker in reach while exercising real player input away from it.
  await command(cdp,'/gamemode spectator');await key(cdp,'w','KeyW',87);await command(cdp,'/gamemode creative');
  const deadline=Date.now()+60000;while(Date.now()<deadline){await sleep(Math.min(10000,deadline-Date.now()));await sample(`cycle-${i}-play`);}
  const leave=await quit();await sample(`cycle-${i}-title`,true);
  if(process.env.WORLD_CYCLE_SNAPSHOTS==='1'&&(i===1||i===8))await snapshot(`title-cycle-${i}`);
  const listed=await list(),at=Date.now();const listedEntry=await select();await open(listedEntry);const seconds=(Date.now()-at)/1000;const persistence=await check(String(i),leave);
  result.cycles.push({cycle:i,seconds,listed,leavePlayer:leave,persistence,totalMs:Date.now()-started});await sample(`cycle-${i}-reopened`,true);await save();
 }
 await quit();await sample('title-cycle-8',true);await list();
 await clickWidget(cdp,'Create New World',15000);await waitFor(cdp,`${screenExpr}.includes('CreateWorldScreen')`,60000,'second create');
 await clickWidget(cdp,'Create New World',15000);await waitFor(cdp,'!!globalThis.__gaiusMinecraftState?.level&&!globalThis.__gaiusMinecraftState?.screen&&!!globalThis.__gaiusMinecraftState?.player',worldTimeout,'second world');
 await sleep(3000);await quit();const two=await list();result.secondWorld={beforeDelete:two};if(two.count!==2)throw new Error('Expected two worlds before deletion');
 const second=await select();await clickAt(cdp,second.x,second.y);await clickWidget(cdp,'Delete',10000);await sleep(1000);result.secondWorld.confirm=await evaluate(cdp,stateExpr);await clickWidget(cdp,'Delete',10000);
 await waitFor(cdp,'globalThis.__gaiusMinecraftState?.worldSelection?.count===1',30000,'second world deletion');result.secondWorld.afterDelete=(await evaluate(cdp,stateExpr)).worldSelection;
 const finalAt=Date.now();const finalEntry=await select();await open(finalEntry);result.final={seconds:(Date.now()-finalAt)/1000,persistence:await check('final',anchor.player)};await sample('final-world',true);await quit();await sample('final-title',true);result.completed=true;await save();(result.documentReloads===0?pass:fail)('worldcycleComplete',{cycles:8,secondWorldDeleted:true,documentReloads:result.documentReloads});
}
