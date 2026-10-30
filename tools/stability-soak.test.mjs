import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {SOAK_PROBE_SOURCE} from './stability-soak.mjs';

function probe(extra={}){
 const frames=[];let observer;
 const context=vm.createContext({requestAnimationFrame:fn=>frames.push(fn),
  PerformanceObserver:class{constructor(fn){observer=fn}observe(){}},...extra});
 vm.runInContext(SOAK_PROBE_SOURCE,context);
 return {context,state:context.__gaiusSoakProbe,frame:t=>frames.shift()(t),tasks:entries=>observer({getEntries:()=>entries})};
}

test('records frame stalls while excluding snapshot suspension',()=>{
 const p=probe();p.frame(100);p.frame(200);p.frame(1501);
 assert.equal(p.state.frameStalls,1);assert.equal(p.state.maxFrameMs,1301);
 p.state.suspended=true;p.frame(9000);p.state.suspended=false;p.frame(9500);p.frame(9600);
 assert.equal(p.state.frameStalls,1);
});

test('excludes boot and snapshot long tasks before the active boundary',()=>{
 const p=probe();p.state.since=100;
 p.tasks([{startTime:10,duration:3000},{startTime:101,duration:1100}]);
 assert.equal(p.state.longTasks,1);assert.equal(p.state.longStalls,1);
 p.state.suspended=true;p.tasks([{startTime:200,duration:5000}]);
 assert.equal(p.state.longTasks,1);
});

test('observes the original GPU device lost promise',async()=>{
 let lose;const device={lost:new Promise(r=>{lose=r})};
 class GPUAdapter{async requestDevice(value){assert.equal(value.label,'soak');return device}}
 const p=probe({GPUAdapter});
 assert.equal(await new GPUAdapter().requestDevice({label:'soak'}),device);
 lose({reason:'unknown',message:'test loss'});await Promise.resolve();
 assert.equal(p.state.deviceLost.length,1);assert.equal(p.state.deviceLost[0].message,'test loss');
});
