import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import {alignCaptureGameTime, captureStableFramesExpression, exactCaptureDifference} from "./deterministic-world-capture.mjs";

test("fixed world time steps only the missing ticks and verifies the frozen clock", async () => {
  let time = 188; const commands = [];
  const result = await alignCaptureGameTime(async text => {
    commands.push(text);
    if(text.startsWith("/tick step")){time += Number(text.split(" ").at(-1));return {ok:true,response:"Stepping"};}
    return {ok:true,response:`[CHAT] The game time is ${time} ticks`};
  }, async () => {});
  assert.equal(result.confirmed.value,600);
  assert.ok(commands.includes("/tick step 412"));
  await assert.rejects(alignCaptureGameTime(async()=>({ok:true,response:"The time is 601"}),async()=>{}),/exceeds target/);
  await assert.rejects(alignCaptureGameTime(async()=>({ok:false,response:[]}),async()=>{}),/reply unavailable/);
});

test("readiness streak counts new game frames and resets on chunk growth or pending work", async () => {
  let frame=0, callbacks=0;
  const state={player:{x:0,y:70,z:0,yaw:0,pitch:0},loadedChunkCount:9};
  const pipeline={compileBacklog:0,uploadBacklog:0,completedTasks:1};
  const context={window:null,__gaiusMinecraftState:state,__gaiusChunkPipelineTelemetry:pipeline,
    __gaiusGlFrameGuard:{frames:0},performance:{now:()=>callbacks*16},Date,
    requestAnimationFrame:fn=>queueMicrotask(()=>{
      callbacks++;if(callbacks%2===0)context.__gaiusGlFrameGuard.frames=++frame;
      if(frame===2)state.loadedChunkCount=10;
      pipeline.uploadBacklog=frame===4?1:0;
      fn();
    })};
  context.window=context;
  const result=await vm.runInNewContext(captureStableFramesExpression([0,70,0,0,0],3),context);
  assert.equal(result.ok,true);assert.equal(result.loaded,10);
  assert.ok(result.frame>=7);assert.ok(callbacks>=14);
});

test("exact temporal gate catches a one-channel one-level change", () => {
  const image={width:1,height:1,pixels:Buffer.from([10,20,30,255])};
  assert.deepEqual(exactCaptureDifference(image,image),{pixels:0,maxDelta:0});
  assert.deepEqual(exactCaptureDifference(image,{...image,pixels:Buffer.from([10,20,31,255])}),{pixels:1,maxDelta:1});
});
