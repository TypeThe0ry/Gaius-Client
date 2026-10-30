import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const dir = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.resolve(process.argv[2] || path.join(dir, "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java")), "utf8");
function jsBody(method) {
  const d = new RegExp(`(?:private|public)\\s+static\\s+native\\s+[\\w<>\\[\\]]+\\s+${method}\\s*\\(`).exec(source);
  assert.ok(d, `missing ${method}`); const a = source.lastIndexOf("@JSBody", d.index);
  const m = 'script = """'; const s = source.indexOf(m, a) + m.length; const e = source.indexOf('"""', s);
  assert.ok(e > s, `missing body ${method}`); return source.slice(s, e);
}
const C = { ARRAY_BUFFER:0x8892, ELEMENT_ARRAY_BUFFER:0x8893, COPY_READ_BUFFER:0x8f36, COPY_WRITE_BUFFER:0x8f37, UNIFORM_BUFFER:0x8a11 };
const objects = new Map(), sizes = new Map(), bytes = new Map(), physicalBindings = new Map(), glTypes = new Map();
let nativeCopies = 0, nativeErrors = 0, readbacks = 0;
const gl = new Proxy({ ...C, getError:()=>0 }, { get(t,k) {
  if (k in t) return t[k];
  if (k === "bindBuffer") return (target, object) => { if (object) { const old=glTypes.get(object), next=target===C.ELEMENT_ARRAY_BUFFER?1:2; if (old && old!==next && target!==C.COPY_READ_BUFFER && target!==C.COPY_WRITE_BUFFER) { nativeErrors++; return; } if (!old) glTypes.set(object,next); } physicalBindings.set(target,object||null); };
  if (k === "getBufferSubData") return (target,offset,out) => { readbacks++; const o=physicalBindings.get(target); const id=[...objects].find(([,v])=>v===o)?.[0]; assert.ok(id); out.set(bytes.get(id).subarray(offset,offset+out.byteLength)); };
  if (k === "bufferSubData") return (target,offset,data) => { const o=physicalBindings.get(target); const id=[...objects].find(([,v])=>v===o)?.[0]; assert.ok(id); bytes.get(id).set(data,offset); };
  if (k === "copyBufferSubData") return (r,w,ro,wo,n) => { nativeCopies++; const a=physicalBindings.get(r),b=physicalBindings.get(w); const ai=[...objects].find(([,v])=>v===a)?.[0],bi=[...objects].find(([,v])=>v===b)?.[0]; if (glTypes.get(a)!==glTypes.get(b) || ro<0 || wo<0 || ro+n>sizes.get(ai) || wo+n>sizes.get(bi)) { nativeErrors++; return; } bytes.get(bi).set(bytes.get(ai).subarray(ro,ro+n),wo); };
  if (k === "createBuffer") return () => ({}); if (k === "deleteBuffer") return () => {}; if (k === "bindVertexArray") return () => {}; if (k === "getExtension") return () => null; return () => {};
}});
globalThis.window = { __gaiusWebGL: gl, __gaiusGLStats: {}, __gaiusMaxSingleBufferShadowBytes: 16*1024*1024, __gaiusMaxTotalBufferShadowBytes: 64*1024*1024 };
globalThis.performance = { now: () => 1 };
new Function(jsBody("initializeJs"))();
const state = window.__gaiusGL; state.hasUsableBaseVertexExtension=()=>true; state.bumpBufferVersion=()=>{}; state.updateBufferShadowTelemetry=()=>{};
const vao={elementArrayBuffer:0,elementArrayBufferObject:null}; state.getVaoEmu=()=>vao;
state.bindPhysicalElementBuffer=(v,o)=>gl.bindBuffer(C.ELEMENT_ARRAY_BUFFER,o||null);
state.replaceVaoBufferRef=()=>{}; state.ensureLogicalElementBuffer=()=>{};
for (const id of [1,2,3,4]) { const o={id}; objects.set(id,o); state.buffers.set(id,o); sizes.set(id,262144); state.bufferSizes.set(id,262144); bytes.set(id,new Uint8Array(262144)); }
function run(name, params, args) { return new Function(...params, jsBody(name))(...args); }
function bind(target,id) { run("bindBuffer", ["target","buffer"], [target,id]); }
function fill(id) { bytes.get(id).forEach((_,i,a)=>{a[i]=(i*17+id)&255;}); } [1,2,3,4].forEach(fill);

bind(C.COPY_READ_BUFFER,1); bind(C.ELEMENT_ARRAY_BUFFER,2);
bind(C.COPY_WRITE_BUFFER,2);
assert.equal(state.bufferWebglTypes.get(2),1); assert.equal(state.bufferWebglTypes.get(1),2);
assert.equal(glTypes.get(objects.get(2)),1, 'physical element bind must classify the actual target');
gl.copyBufferSubData(C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,11232,0,864);
assert.equal(nativeErrors,1, 'negative control must reject cross-kind GPU copy');
const expected=bytes.get(1).slice(11232,12096);
run("copyBufferSubData", ["sourceTarget","targetTarget","sourceOffset","targetOffset","size"], [C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,11232,0,864]);
assert.deepEqual(bytes.get(2).slice(0,864),expected); assert.equal(readbacks,1); assert.equal(nativeErrors,1);
const scratch = state.bufferCopyScratch;
run("copyBufferSubData", ["sourceTarget","targetTarget","sourceOffset","targetOffset","size"], [C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,200,2000,32]);
assert.equal(state.bufferCopyScratch,scratch,'small copies reuse scratch');
// Chrome mining reproduction: 36 unsigned-short indices were staged at a
// nonzero ring-buffer offset. The rejected 72-byte copy left no visible cracks.
bytes.get(2).fill(0,0,72);
const miningIndices=bytes.get(1).slice(15888,15960);
const miningErrorsBefore=nativeErrors;
gl.copyBufferSubData(C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,15888,0,72);
assert.equal(nativeErrors,miningErrorsBefore+1);
assert.deepEqual(bytes.get(2).slice(0,72),new Uint8Array(72));
run("copyBufferSubData", ["sourceTarget","targetTarget","sourceOffset","targetOffset","size"], [C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,15888,0,72]);
assert.deepEqual(bytes.get(2).slice(0,72),miningIndices,'mining overlay indices must reach the element buffer');
assert.equal(nativeErrors,miningErrorsBefore+1,'mining copy must avoid another invalid native transfer');
assert.equal(state.bufferCopyScratch,scratch,'mining copy must reuse bounded scratch');
state.bufferBytes.set(1,bytes.get(1).slice()); state.bufferShadowTotalBytes=262144; const before=readbacks;
run("copyBufferSubData", ["sourceTarget","targetTarget","sourceOffset","targetOffset","size"], [C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,12000,1000,64]); assert.equal(readbacks,before);
assert.deepEqual(bytes.get(2).slice(1000,1064),bytes.get(1).slice(12000,12064));
state.bufferBytes.set(1,bytes.get(1).slice(0,1000));
run("copyBufferSubData", ["sourceTarget","targetTarget","sourceOffset","targetOffset","size"], [C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,100,3000,64]);
assert.equal(readbacks,before+1,'partial source shadow must use GPU readback');
assert.deepEqual(bytes.get(2).slice(3000,3064),bytes.get(1).slice(100,164));
bind(C.COPY_READ_BUFFER,3); bind(C.COPY_WRITE_BUFFER,4); const nativeBefore=nativeCopies;
run("copyBufferSubData", ["sourceTarget","targetTarget","sourceOffset","targetOffset","size"], [C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,4,8,32]); assert.equal(nativeCopies,nativeBefore+1);
const big=8*1024*1024+64; sizes.set(1,big); sizes.set(2,big); bytes.set(1,new Uint8Array(big)); bytes.set(2,new Uint8Array(big));
state.bufferSizes.set(1,big); state.bufferSizes.set(2,big);
for (let i=0;i<big;i+=4099) bytes.get(1)[i]=(i*13)&255;
state.bufferBytes.delete(1); state.bufferBytes.delete(2); bind(C.COPY_READ_BUFFER,1); bind(C.COPY_WRITE_BUFFER,2);
const rbBefore=readbacks;
run("copyBufferSubData", ["sourceTarget","targetTarget","sourceOffset","targetOffset","size"], [C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,0,0,big]);
assert.equal(readbacks,rbBefore+2); assert.deepEqual(bytes.get(2),bytes.get(1));
assert.equal(state.bufferCopyScratch.byteLength,8*1024*1024,'scratch is capped at 8 MiB');
bind(C.COPY_READ_BUFFER,3); bind(C.COPY_WRITE_BUFFER,4); run("copyNamedBufferSubData", ["sourceBuffer","targetBuffer","sourceOffset","targetOffset","size"], [1,2,0,32,16]);
assert.equal(state.boundBuffers.get(C.COPY_READ_BUFFER),3); assert.equal(state.boundBuffers.get(C.COPY_WRITE_BUFFER),4);
assert.equal(physicalBindings.get(C.COPY_READ_BUFFER),objects.get(3));
assert.equal(physicalBindings.get(C.COPY_WRITE_BUFFER),objects.get(4));
assert.deepEqual(bytes.get(2).slice(32,48),bytes.get(1).slice(0,16));
const errBefore=nativeErrors; run("copyBufferSubData", ["sourceTarget","targetTarget","sourceOffset","targetOffset","size"], [C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,262140,0,32]); assert.equal(nativeErrors,errBefore+1);
console.log("Browser OpenGL cross-kind copy VM smoke passed",JSON.stringify({crossKindBytes:864,localReadbacks:readbacks,nativeCopies,nativeErrors,sourceType:state.bufferWebglTypes.get(1),elementType:state.bufferWebglTypes.get(2)}));

// Exercise real upload/shadow helpers after the first compatibility copy. The
// staging ring changes between copies: skipping a readback must not reuse stale
// indices, and eviction must retain a correct bounded fallback.
const capacity=262144;
state.noteBufferUpload=()=>{};
sizes.set(1,capacity); state.bufferSizes.set(1,capacity);
bytes.set(1,new Uint8Array(capacity));
state.deleteBufferShadow(1);
bind(C.COPY_READ_BUFFER,1); bind(C.COPY_WRITE_BUFFER,2);
const upload=new Uint8Array(capacity); upload.fill(31);
run('bufferSubDataJs',['target','offset','data'],[C.COPY_READ_BUFFER,0,upload]);
const warmReads=readbacks;
for(let i=0;i<100;i++) {
  const data=new Uint8Array(72); data.fill(i);
  run('bufferSubDataJs',['target','offset','data'],[C.COPY_READ_BUFFER,1000,data]);
  run('copyBufferSubData',['sourceTarget','targetTarget','sourceOffset','targetOffset','size'],[C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,1000,32,72]);
  assert.deepEqual(bytes.get(2).slice(32,104),data,'updated staging bytes reach destination');
}
assert.equal(readbacks,warmReads,'100 changing staging uploads need no GPU readbacks');
state.deleteBufferShadow(1);
const coldReads=readbacks;
run('copyBufferSubData',['sourceTarget','targetTarget','sourceOffset','targetOffset','size'],[C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,1000,32,72]);
assert.equal(readbacks,coldReads+1,'eviction preserves GPU fallback');
assert.deepEqual(bytes.get(2).slice(32,104),bytes.get(1).slice(1000,1072));
globalThis.window.__gaiusMaxSingleBufferShadowBytes=0;
run('bufferSubDataJs',['target','offset','data'],[C.COPY_READ_BUFFER,0,upload]);
assert.equal(state.bufferBytes.has(1),false,'shadow budget remains enforced');
const cappedReads=readbacks;
run('copyBufferSubData',['sourceTarget','targetTarget','sourceOffset','targetOffset','size'],[C.COPY_READ_BUFFER,C.COPY_WRITE_BUFFER,1000,32,72]);
assert.equal(readbacks,cappedReads+1,'zero cache budget preserves correct fallback');
assert.deepEqual(bytes.get(2).slice(32,104),upload.slice(1000,1072));
console.log('CROSS_KIND_REUSE_OK uploads=100 steadyStateReadbacks=0 evictionFallback=true budgetFallback=true');
