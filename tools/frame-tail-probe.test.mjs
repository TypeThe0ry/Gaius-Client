import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {FRAME_TAIL_PROBE_SOURCE,FRAME_TAIL_START_EXPRESSION,FRAME_TAIL_STOP_EXPRESSION} from './frame-tail-probe.mjs';

for(const accessor of [false,true])test(`frame tail preserves ${accessor?'accessor':'data'} guard semantics`,()=>{
  let time=0,value=0,writes=0;
  const guard={frames:0};
  if(accessor)Object.defineProperty(guard,'frames',{configurable:true,enumerable:true,
    get:()=>value,set:v=>{value=v;writes++}});
  const context=vm.createContext({__gaiusGlFrameGuard:guard,
    performance:{now:()=>time,timeOrigin:1000,mark:()=>{}}});
  vm.runInContext(FRAME_TAIL_PROBE_SOURCE,context);
  vm.runInContext(FRAME_TAIL_PROBE_SOURCE,context);
  guard.frames++; // inactive observation must not enter the interval list.
  vm.runInContext(FRAME_TAIL_START_EXPRESSION,context);
  for(const t of [10,26,150]){time=t;guard.frames++}
  guard.frames=guard.frames; // no frame was presented.
  const result=vm.runInContext(FRAME_TAIL_STOP_EXPRESSION,context);
  assert.deepEqual(Array.from(result.starts),[10,26]);
  assert.deepEqual(Array.from(result.ends),[26,150]);
  time=999;guard.frames++;
  assert.equal(result.ends.length,2);
  assert.equal(guard.frames,5);
  if(accessor)assert.equal(writes,6);
});
