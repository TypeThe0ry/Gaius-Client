import assert from 'node:assert/strict';
import vm from 'node:vm';
import {releasePresentSource} from './release-performance.mjs';
const context=vm.createContext({performance:{now:()=>context.clock},clock:0});
vm.runInContext('globalThis.__gaiusGlFrameGuard={frames:0}',context);
vm.runInContext(releasePresentSource,context);
vm.runInContext('globalThis.__gaiusGlFrameGuard.frames=1',context);
assert.equal(context.__gaiusGlFrameGuard.frames,1);
vm.runInContext('globalThis.__gaiusReleasePresent={active:true,last:null,intervals:[]}',context);
for(const stamp of [10,30,65]){
  context.clock=stamp;
  vm.runInContext('globalThis.__gaiusGlFrameGuard.frames++',context);
}
assert.deepEqual(Array.from(context.__gaiusReleasePresent.intervals),[20,35]);
context.clock=75;
vm.runInContext('globalThis.__gaiusGlFrameGuard.frames=globalThis.__gaiusGlFrameGuard.frames',context);
assert.equal(context.__gaiusReleasePresent.intervals.length,2);
vm.runInContext('globalThis.__gaiusReleasePresent.active=false;globalThis.__gaiusGlFrameGuard.frames++',context);
assert.equal(context.__gaiusReleasePresent.intervals.length,2);
assert.equal(context.__gaiusGlFrameGuard.frames,5);
console.log('presentation timing probe PASS');
import {walkValidity, optionsValidity, summarizeReleaseChunkCausal, releaseDiagnosticSource} from './release-performance.mjs';
const moving=Array.from({length:12},(_,i)=>({ms:(i+1)*5000,player:{x:0,z:(i+1)*21.5},dist:{effectiveRenderDistance:8}}));
assert.equal(walkValidity({x:0,z:0},moving,8).ok,true);
assert.equal(walkValidity({x:0,z:0},moving.map((x,i)=>({...x,player:{x:0,z:i<2?x.player.z:43}})),8).ok,false);
assert.equal(walkValidity({x:0,z:0},moving,12).ok,false);
assert.equal(optionsValidity('renderDistance:8\nsimulationDistance:6\nenableVsync:true\nmaxFps:60',8,false).ok,true);
assert.equal(optionsValidity('renderDistance:8\nsimulationDistance:6\nenableVsync:true\nmaxFps:60',8,true).ok,false);
assert.equal(optionsValidity('renderDistance:32\nsimulationDistance:12\nenableVsync:true\nmaxFps:260',32,true,12,null,true).ok,true);
console.log('walk and options gates PASS');

import {releaseColdOptionsExpression} from './release-performance.mjs';
let stored,booted=false;
const bootContext=vm.createContext({
  __gaiusFsPut:(path,value)=>{assert.equal(path,'/gaius/options.txt');stored=Buffer.from(value,'base64').toString();},
  __gaiusReleaseBoot:()=>{booted=true;},
});
const cold=await vm.runInContext(releaseColdOptionsExpression,bootContext);
assert.equal(booted,true);
assert.equal(optionsValidity(stored,8,false).ok,true);
assert.equal(cold.includes('enableVsync:true'),true);
assert.equal(cold.includes('maxFps:60'),true);
console.log('cold boot pacing options PASS');

const square=[];
let x=0,z=0;
for(const [dx,dz] of [[0,1],[-1,0],[0,-1],[1,0]])for(let i=0;i<3;i++){
  x+=dx*21.5;z+=dz*21.5;
  square.push({ms:(square.length+1)*5000,player:{x,z},dist:{effectiveRenderDistance:8}});
}
assert.equal(Math.hypot(x,z),0);
assert.equal(walkValidity({x:0,z:0},square,8).ok,true);
console.log('closed ground route movement gate PASS');

const causalSummary=summarizeReleaseChunkCausal({schemaVersion:'gaius.chunk-causal.v1',enabled:true,
  handlerTimestampSource:'test handler entry',
  sections:[{ticket:7,causalTicket:7,visible:true,chunkX:0,sectionY:4,chunkZ:0,
    packetHandlerAt:100,snapshotStartAt:101,snapshotEndAt:103,workerSubmitAt:104,workerResultAt:108,
    installQueueAt:109,installCompleteAt:110,installSucceeded:true,gpuUploadProxyAt:112,firstDrawAt:112,
    events:[{name:'snapshotStart',atMillis:101},{name:'firstDraw',atMillis:112}]},
    {ticket:8,causalTicket:8,visible:true,chunkX:1,sectionY:4,chunkZ:1,packetHandlerAt:112,
      snapshotStartAt:113,snapshotEndAt:114,workerSubmitAt:null,workerResultAt:null,discardAt:115,discardReason:'superseded',
      events:[{name:'snapshotStart',atMillis:113},{name:'discard',atMillis:115}]}],
  schedulerSamples:[{atMillis:100,compileBacklog:2,uploadBacklog:1,queuedWork:4},
    {atMillis:1100,compileBacklog:5,uploadBacklog:0,queuedWork:7}],
  droppedSections:0,droppedHandlerEntries:0,handlerEntryCount:2,unmappedDraws:0,earlyDraws:0,repeatDraws:1,syntheticDraws:0});
assert.equal(causalSummary.closedLoopRate,0.5);
assert.equal(causalSummary.handlerTimestampSource,'test handler entry');
assert.equal(causalSummary.handlerEntryToFirstDraw.p50Ms,12);
assert.equal(causalSummary.discardReasons.superseded,1);
assert.equal(causalSummary.eventOrderViolations,0);
assert.equal(causalSummary.backlog.compileSlopePerSecond,3);
assert.match(releaseDiagnosticSource, /gaiusChunkCausalTelemetry/);
console.log('causal chunk closure and backlog diagnostics PASS');

// The portable page installs the boot barrier long before its launcher script (and with it
// __gaiusFsPut/__gaiusFsReady) has parsed; the seed must land after the IndexedDB scan.
import {releaseOptionsExpression} from './release-performance.mjs';
{
  const files=Object.create(null);
  let warmBooted=false;
  const lateContext=vm.createContext({setTimeout,performance:{now:()=>Date.now()},__gaiusPersistentFiles:files,
    __gaiusReleaseBoot:()=>{warmBooted=true;}});
  setTimeout(()=>{
    lateContext.__gaiusFsPut=(path,value)=>{files[path]=value;};
    lateContext.__gaiusFsReady=new Promise(resolve=>setTimeout(()=>{
      files['/gaius/options.txt']=Buffer.from('enableVsync:true\nmaxFps:60\n').toString('base64');
      resolve();
    },60));
  },40);
  const expected=await vm.runInContext(releaseOptionsExpression,lateContext);
  assert.equal(warmBooted,true);
  await lateContext.__gaiusFsReady;
  const seeded=Buffer.from(files['/gaius/options.txt'],'base64').toString();
  assert.equal(seeded,`${expected.join(String.fromCharCode(10))}${String.fromCharCode(10)}`);
  console.log('late storage bridge seed PASS');
}
