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
import {walkValidity, optionsValidity} from './release-performance.mjs';
const moving=Array.from({length:12},(_,i)=>({ms:(i+1)*5000,player:{x:0,z:(i+1)*21.5},dist:{effectiveRenderDistance:8}}));
assert.equal(walkValidity({x:0,z:0},moving,8).ok,true);
assert.equal(walkValidity({x:0,z:0},moving.map((x,i)=>({...x,player:{x:0,z:i<2?x.player.z:43}})),8).ok,false);
assert.equal(walkValidity({x:0,z:0},moving,12).ok,false);
assert.equal(optionsValidity('renderDistance:8\nenableVsync:true\nmaxFps:60',8,false).ok,true);
assert.equal(optionsValidity('renderDistance:8\nenableVsync:true\nmaxFps:60',8,true).ok,false);
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
