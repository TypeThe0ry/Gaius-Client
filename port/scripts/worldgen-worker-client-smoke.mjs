#!/usr/bin/env node
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
const source = readFileSync(new URL('../web/singleplayer/server-worker-bootstrap.js', import.meta.url), 'utf8');
const context = vm.createContext({ArrayBuffer, SharedArrayBuffer, Uint8Array, DataView, console});
context.root = context;
context.GaiusKernelMirror = {allowed: () => true, enabled: () => true};
vm.runInContext(source.slice(source.indexOf('function createKernelClient(port)')), context);
vm.runInContext(readFileSync(new URL('../web/kernels/worldgen-job.js', import.meta.url), 'utf8'), context);
const wasm = process.argv[2];
assert.ok(wasm, 'pass the built worldgen wasm path');
const instance = new WebAssembly.Instance(new WebAssembly.Module(readFileSync(wasm)), {});
function run(buffer) {
  const e = instance.exports, bytes = new Uint8Array(buffer);
  const ptr = e.alloc(bytes.length);
  new Uint8Array(e.memory.buffer, ptr, bytes.length).set(bytes);
  const descriptor = e.run_terrain(ptr, bytes.length);
  const v = new DataView(e.memory.buffer);
  const status = v.getUint32(descriptor, true);
  const result = new Uint8Array(e.memory.buffer, v.getUint32(descriptor+4,true), v.getUint32(descriptor+8,true)).slice();
  e.release(descriptor); e.dealloc(ptr, bytes.length);
  return status ? {status, message: new TextDecoder().decode(result)}
    : {status, bytes:result.length, sha256:createHash('sha256').update(result).digest('hex')};
}
for (const isolated of [false, true]) {
  context.crossOriginIsolated = isolated;
  const job = context.GaiusWorldgenJob.encodeTerrain(context.GaiusWorldgenJob.generator(7, new Uint8Array([1,2,3])), -3, 9, 1, {}, false);
  let captured;
  const port = {start(){}, postMessage(message, transfer) {
    captured = structuredClone(message, {transfer});
  }};
  const client = context.createKernelClient(port);
  port.onmessage({data:{type:'status',status:{enabled:true,kernels:{worldgen:{available:true}}}}});
  const promise = client.submit('terrain', job, {kernel:'worldgen'});
  const expectedLength = 88;
  assert.equal(captured.payload.byteLength, expectedLength, `isolated=${isolated}: input frame lost`);
  const outcome = run(captured.payload);
  // All terrain fields were decoded; only the intentionally absent generator is rejected.
  assert.match(outcome.message, /^generator-missing:/, JSON.stringify(outcome));
  assert.notEqual(outcome.status, 4, 'must not report truncated');
  port.onmessage({data:{type:'result', id:captured.id,result:new ArrayBuffer(0)}});
  await promise;
  console.log(JSON.stringify({isolated, bytes:captured.payload.byteLength,...outcome}));
}
// Reproduce the previous bridge exactly: a raw SAB has no .buffer, .byteOffset.
const shared = new SharedArrayBuffer(88);
const broken = new Uint8Array(shared.buffer, shared.byteOffset, shared.byteLength).slice().buffer;
const failed = run(broken);
assert.equal(broken.byteLength, 0);
assert.equal(failed.message, 'job buffer is truncated');
console.log(JSON.stringify({regression:true,input:88,received:0,...failed}));
for (const path of process.argv.slice(3)) {
  const ir = new Uint8Array(readFileSync(path));
  const gen = context.GaiusWorldgenJob.generator(9, ir);
  for (const [cx,cz] of [[0,0],[-3,9],[8,-8]]) {
    const results = [];
    for (const isolated of [false,true]) {
      context.crossOriginIsolated = isolated;
      const frame = context.GaiusWorldgenJob.encodeTerrain(gen,cx,cz,9,{surface:true,biomes:true},true);
      let captured;
      const port = {start(){},postMessage(m,t){captured=structuredClone(m,{transfer:t});}};
      const client = context.createKernelClient(port);
      port.onmessage({data:{type:'status',status:{enabled:true,kernels:{worldgen:{available:true}}}}});
      const promise = client.submit('terrain',frame,{kernel:'worldgen'});
      results.push(run(captured.payload));
      port.onmessage({data:{type:'result',id:captured.id,result:new ArrayBuffer(0)}});
      await promise;
    }
    assert.equal(results[0].status,0,results[0].message);
    assert.deepEqual(results[1],results[0],`transport parity ${path} ${cx},${cz}`);
    console.log(JSON.stringify({ir:path,cx,cz,parity:true,status:0}));
  }
}
