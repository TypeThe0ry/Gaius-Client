import test from 'node:test';
import assert from 'node:assert/strict';
import {summarizeTailFrames,attributeTailFrames} from './frame-tail-attribution.mjs';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
test('tail thresholds are strict and retain every interval',()=>{
  const r=summarizeTailFrames({source:'presented-frame-guard',starts:[0,20,70,170],ends:[20,70,170,271]});
  assert.equal(r.frames,4);assert.equal(r.over50,2);assert.equal(r.over100,1);
  assert.deepEqual(r.longFrames.map(f=>f.index),[2,3]);assert.equal(r.maxMs,101);
});
test('incomplete or negative frame intervals fail closed',()=>{
  assert.throws(()=>summarizeTailFrames({starts:[0],ends:[]}));
  assert.throws(()=>summarizeTailFrames({starts:[3],ends:[2]}));
});
test('trace clock alignment excludes worker GC and exposes profiler gaps',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'frame-tail-'));
  try{
    const paths=['frames','trace','profile'].map(n=>join(dir,n+'.json'));
    await writeFile(paths[0],JSON.stringify({starts:[10],ends:[130]}));
    await writeFile(paths[1],JSON.stringify({traceEvents:[
      {name:'gaius-frame-clock:0',ts:1000000,pid:1,tid:2,ph:'R'},
      {name:'V8.GC_SCAVENGER',ts:1020000,dur:5000,pid:1,tid:2,ph:'X'},
      {name:'V8.GC_SCAVENGER',ts:1020000,dur:90000,pid:1,tid:3,ph:'X'},
      {name:'gaius-gl:bufferSubData',ts:1030000,pid:1,tid:2,ph:'b',id:1},
      {name:'gaius-gl:bufferSubData',ts:1040000,pid:1,tid:2,ph:'e',id:1}]}));
    await writeFile(paths[2],JSON.stringify({startTime:1000000,nodes:[{id:1,callFrame:{functionName:'compileShader'}}],samples:[1],timeDeltas:[30000]}));
    const result=await attributeTailFrames(...paths);
    assert.equal(result.traceClockOffsetMs,1000);
    assert.equal(result.longFrames[0].events.length,2);
    assert.equal(result.longFrames[0].events[0].overlapMs,5);
    assert.equal(result.longFrames[0].events[1].overlapMs,10);
    assert.equal(result.longFrames[0].samples.compileShader,1);
    assert.equal(result.longFrames[0].sampleGapsOver10Ms,1);
  }finally{await rm(dir,{recursive:true,force:true});}
});
