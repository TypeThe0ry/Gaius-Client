#!/usr/bin/env node

import assert from "node:assert/strict";
import vm from "node:vm";
import {readFile} from "node:fs/promises";

const source=await readFile(new URL("../src/main/java/dev/gaius/browser/BrowserChunkPipelineTelemetry.java",import.meta.url),"utf8");
function body(declaration){
  const at=source.indexOf(declaration);assert.notEqual(at,-1,`missing ${declaration}`);
  const marker='script = """';const start=source.lastIndexOf(marker,at)+marker.length;
  const end=source.indexOf('""")',start);assert.ok(start>=marker.length&&end>start);
  return source.slice(start,end);
}
const context=vm.createContext({
  URLSearchParams,Date,Map,Number,Math,performance:{timeOrigin:1000,now:()=>10},
  location:{search:""},globalThis:null,
});
context.globalThis=context;
const enabled="return globalThis.__gaiusChunkCausalTelemetryEnabled === true;";
const init=body("private static native void initializeEnabledStateJs();");
assert.match(init, /\bconst root=/,
  "initializer JSBody root declaration drifted");
assert.match(init, /\bconst causal=/,
  "initializer JSBody state declaration drifted");
assert.throws(() => new vm.Script("(b,c)=>{const c=globalThis.value;}"),
  /Identifier 'c'|already been declared/,
  "the released short-name wrapper shape reproduces the local/parameter collision");
assert.doesNotThrow(() => new vm.Script(`(b,c)=>{${init}\n}`),
  "initializer declarations remain legal beside TeaVM's short wrapper parameters");
const epochs=body("private static native void setEpochsJs");
const packet=body("private static native void packetHandlerEnterJs");
const uniform=body("private static native void registerDrawSectionJs");
const draw=body("private static native void firstDrawJs");
const start=body("private static native void snapshotStartJs");
const end=body("private static native void snapshotEndJs");
const submit=body("private static native void workerSubmitJs");
const result=body("private static native void workerResultJs");
const queue=body("private static native void installQueueEnterJs");
const complete=body("private static native void installCompleteJs");
const published=body("private static native void meshPublishedJs");
const discard=body("private static native void discardJs");
const run=(code,params=[],values=[])=>{
  context.__source=`(function(${params.join(",")}){${code}\n})`;
  return vm.runInContext(context.__source,context)(...values);
};

context.__gaiusChunkCausalTelemetryEnabled=false;
assert.equal(run(enabled),false);
assert.equal(context.__gaiusChunkPipelineTelemetry,undefined,"disabled path allocated telemetry state");
assert.ok(!source.includes("URLSearchParams"),"Java telemetry class must not parse launcher parameters");

context.__gaiusChunkCausalTelemetryEnabled=true;
assert.equal(run(enabled),true);
run(init);
run(packet,["chunkX","chunkZ","atMillis"],[0,0,1000]);
run(start,["ticket","sectionX","sectionY","sectionZ","atMillis"],[1,0,4,0,1001]);
run(end,["ticket","succeeded","atMillis"],[1,true,1001.5]);
run(submit,["ticket","requestSeq","atMillis"],[1,7,1002]);
run(result,["ticket","status","atMillis"],[1,1,1003]);
run(queue,["ticket","sectionNode","requestSeq","bytes","atMillis"],[1,"node",7,28,1004]);
run(complete,["ticket","succeeded","atMillis"],[1,true,1005]);
run(published,["ticket","atMillis"],[1,1006]);
run(uniform,["uniformIndex","sectionX","sectionY","sectionZ"],[11,0,4,0]);
run(draw,["uniformIndex","indexCount","atMillis"],[11,6,1010]);
const causal=context.__gaiusChunkPipelineTelemetry.causal;
const first=causal.sections.find(row=>row.ticket===1);
assert.ok(first,"ticket record missing");
assert.equal(first.installSucceeded,true);
assert.deepEqual(Array.from(first.events.map(row=>row.name)),[
  "snapshotStart","snapshotEnd","workerSubmit","workerResult","installQueue","installComplete","meshPublished","gpuUploadProxy","firstDraw"]);
assert.equal(first.firstDrawAt,1010);
assert.equal(first.gpuUploadProxyAt,1010,"first draw is only the upload timestamp proxy");
run(draw,["uniformIndex","indexCount","atMillis"],[11,6,1011]);
assert.equal(first.repeatDraws,1,"repeat draw created a second first-draw event");

run(start,["ticket","sectionX","sectionY","sectionZ","atMillis"],[2,1,4,1,1020]);
run(uniform,["uniformIndex","sectionX","sectionY","sectionZ"],[12,1,4,1]);
run(draw,["uniformIndex","indexCount","atMillis"],[12,6,1021]);
const premature=causal.sections.find(row=>row.ticket===2);
assert.equal(premature.firstDrawAt,null,"old mesh draw was attributed before install");
assert.equal(premature.prematureDraws,1);
run(complete,["ticket","succeeded","atMillis"],[2,true,1022]);
run(published,["ticket","atMillis"],[2,1022.5]);
run(draw,["uniformIndex","indexCount","atMillis"],[12,6,1023]);
assert.equal(premature.firstDrawAt,1023);

// A reused coordinate after a world/resource epoch change must get a fresh section row.
run(epochs,["levelEpoch","resourceEpoch"],[2,3]);
run(uniform,["uniformIndex","sectionX","sectionY","sectionZ"],[13,0,4,0]);
run(draw,["uniformIndex","indexCount","atMillis"],[13,6,1024]);
const epochRow=causal.sections.find(row=>row.firstDrawAt===1024);
assert.ok(epochRow,"epoch draw row missing");
assert.equal(epochRow.repeatDraws,undefined,"old epoch row was counted as a repeat");
assert.equal(epochRow.levelEpoch,2);
assert.equal(epochRow.resourceEpoch,3);
assert.equal(causal.latestBySection.size,1,"epoch change left stale section index entries");

run(start,["ticket","sectionX","sectionY","sectionZ","atMillis"],[3,2,4,2,1030]);
run(discard,["ticket","reason","atMillis"],[3,1,1031]);
const discarded=causal.sections.find(row=>row.ticket===3);
assert.equal(discarded.discardReason,"superseded");
assert.equal(causal.unmappedDraws,0);
assert.equal(causal.syntheticDraws,1,"new epoch first draw should create a fresh visual row");
assert.ok(source.includes("sectionCapacity:32768")&&source.includes("handlerCapacity:8192"));
console.log("chunk-causal-telemetry-smoke: PASS");
