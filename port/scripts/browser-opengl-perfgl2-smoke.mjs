import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGLPerf.java', import.meta.url), 'utf8');
// The script lives in a text block that installJs compiles with the Function constructor, so the
// JavaScript backend never parses it at build time.
const declaration = source.indexOf('private static final String INSTALL_SOURCE = """');
assert.ok(declaration >= 0, 'missing INSTALL_SOURCE text block');
const start = declaration + 'private static final String INSTALL_SOURCE = """'.length;
const body = source.slice(start, source.indexOf('"""', start));
const ARRAY=0x8892, ELEMENT=0x8893, READ=0x8F36, WRITE=0x8F37, UNIFORM=0x8A11;

function model(query) {
  const bindings = new Map(), storage = new Map(), vaos = new Map(), calls = [];
  const draws = [], textures = new Map(), listeners = {}, feedbacks = new Map(), indexed = new Map();
  let feedback=null,allocationFails=false;
  const feedbackBindings=()=>{if (!feedbacks.has(feedback)) feedbacks.set(feedback,new Map());return feedbacks.get(feedback);};
  let vao=null, program=null, read=null, draw=null, active=0x84C0, serial=0, signaled=false;
  const vertex = () => {
    if (!vaos.has(vao)) vaos.set(vao, {element:null, attribs:new Map(), enabled:new Set()});
    return vaos.get(vao);
  };
  const bound = target => target===ELEMENT ? vertex().element : bindings.get(target);
  const input = (data, offset=0, count=0) => {
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    const width=data.BYTES_PER_ELEMENT || 1;
    return new Uint8Array(data.buffer, data.byteOffset+offset*width, count ? count*width : data.byteLength-offset*width);
  };
  const gl = {
    canvas:{addEventListener(name, callback) {listeners[name]=callback;}},
    createBuffer() {if (allocationFails) {allocationFails=false;return null;}return {id:++serial};}, deleteBuffer(buffer) {calls.push(['delete',buffer]);},
    bindBuffer(target, buffer) {calls.push(['bind',target,buffer]);if (target===ELEMENT) vertex().element=buffer;else bindings.set(target,buffer);},
    bufferData(target, data, usage, offset, count) {storage.set(bound(target),typeof data==='number' ? new Uint8Array(data) : input(data,offset,count).slice());},
    bufferSubData(target, offset, data, sourceOffset, count) {storage.get(bound(target)).set(input(data,sourceOffset,count),offset);},
    getBufferSubData(target, offset, data) {data.set(storage.get(bound(target)).subarray(offset,offset+data.byteLength));},
    copyBufferSubData(readTarget, writeTarget, sourceOffset, destinationOffset, count) {
      storage.get(bound(writeTarget)).set(storage.get(bound(readTarget)).slice(sourceOffset,sourceOffset+count),destinationOffset);
    },
    bindBufferRange(target, slot, buffer) {bindings.set(target,buffer);(target===0x8C8E ? feedbackBindings() : indexed).set(slot,buffer);},
    bindBufferBase(target, slot, buffer) {bindings.set(target,buffer);(target===0x8C8E ? feedbackBindings() : indexed).set(slot,buffer);},
    bindTransformFeedback(target, object) {feedback=object;calls.push(['feedback',object]);}, deleteTransformFeedback(object) {feedbacks.delete(object);},
    getIndexedParameter(parameter, slot) {return (parameter===0x8C8F ? feedbackBindings() : indexed).get(slot) || null;},
    fenceSync() {return {id:++serial};}, clientWaitSync() {return signaled ? 0x911A : 0x911B;}, deleteSync() {},
    createVertexArray() {return {id:++serial};}, deleteVertexArray() {},
    bindVertexArray(object) {vao=object;calls.push(['vao',object]);},
    vertexAttribPointer(index, ...args) {vertex().attribs.set(index,{buffer:bound(ARRAY),args});calls.push(['pointer',index]);},
    vertexAttribIPointer(index, ...args) {vertex().attribs.set(index,{buffer:bound(ARRAY),args});},
    enableVertexAttribArray(index) {vertex().enabled.add(index);}, disableVertexAttribArray(index) {vertex().enabled.delete(index);},
    vertexAttribDivisor() {}, useProgram(object) {program=object;calls.push(['program',object]);},
    activeTexture(unit) {active=unit;}, bindTexture(target, object) {textures.set(active+':'+target,object);}, deleteTexture() {},
    bindFramebuffer(target, object) {calls.push(['fbo',target,object]);if (target!==0x8CA9) read=object;if (target!==0x8CA8) draw=object;},
    deleteFramebuffer(object) {if (read===object) read=null;if (draw===object) draw=null;},
    getParameter(parameter) {
      if (parameter===0x8CA6) return draw;if (parameter===0x8CAA) return read;
      if (parameter===0x8B8D) return program;if (parameter===0x85B5) return vao;if (parameter===0x84E0) return active;
      if (parameter===0x8E25) return feedback;
      return bindings.get(parameter) || null;
    },
    getVertexAttrib(index) {return vertex().attribs.get(index)?.buffer || null;},
    drawArrays() {draws.push({vertex:storage.get(vertex().attribs.get(0)?.buffer)?.slice(),index:storage.get(vertex().element)?.slice(),draw});},
    drawElements() {this.drawArrays();}, clear() {calls.push(['clear',draw]);}, readPixels() {calls.push(['read',read]);},
    blitFramebuffer() {calls.push(['blit',read,draw]);}, framebufferTexture2D() {calls.push(['attach',draw]);},
    texSubImage2D() {}, beginTransformFeedback() {}, endTransformFeedback() {},
  };
  const state={};
  vm.runInNewContext('(function(){'+body+'})()', {window:{__gaiusWebGL:gl,__gaiusGL:state},globalThis:{location:{search:query}},
    location:{search:query}, URLSearchParams,Uint8Array,ArrayBuffer,Array,Map,Set,WeakMap,Number,Object,Error,performance,setTimeout});
  return {gl,state,calls,draws,storage,listeners,feedbacks,failAllocation() {allocationFails=true;},signal() {signaled=true;},unsignal() {signaled=false;}};
}

const query='?gaiusGlLargeBuffers=1&gaiusGlGlue=1&gaiusGlLazyFbo=1&gaiusGlVaoCache=1&gaiusGlVerify=1';
const context=model(query),{gl,state,calls,draws}=context;
const vertex=gl.createBuffer();
gl.bindBuffer(ARRAY,vertex);gl.bufferData(ARRAY,1048576,0x88E8);
gl.bufferSubData(ARRAY,0,new Uint8Array([1,2,3,4]));
gl.vertexAttribPointer(0,2,0x1406,false,0,0);gl.enableVertexAttribArray(0);
gl.drawArrays();gl.bufferSubData(ARRAY,0,new Uint8Array([9,8,7,6]));gl.drawArrays();
assert.deepEqual(Array.from(draws[0].vertex.subarray(0,4)),[1,2,3,4]);
assert.deepEqual(Array.from(draws[1].vertex.subarray(0,4)),[9,8,7,6]);
assert.equal(state.perfGl2.stats.rotations,1);
assert.equal(gl.getVertexAttrib(0,0x889F),vertex);
context.signal();gl.bufferSubData(ARRAY,0,new Uint8Array([3,3,3,3]));
assert.equal(state.perfGl2.stats.reuses,0);
gl.drawArrays();gl.bufferSubData(ARRAY,0,new Uint8Array([4,4,4,4]));
assert.equal(state.perfGl2.stats.reuses,1);
const separate=gl.createBuffer();gl.bindBuffer(ARRAY,separate);gl.bufferData(ARRAY,1048576,0x88E8);
gl.bindBuffer(READ,separate);gl.bindBuffer(WRITE,separate);
gl.copyBufferSubData(READ,WRITE,0,4,4);gl.bufferSubData(ARRAY,0,new Uint8Array([8,8,8,8]));
gl.bindBuffer(ARRAY,vertex);gl.drawArrays();
assert.deepEqual(Array.from(draws.at(-1).vertex.subarray(0,4)),[4,4,4,4]);
assert.equal(gl.getVertexAttrib(0,0x889F),vertex);
gl.bufferSubData(ARRAY,0,new Uint8Array([3,3,3,3]));
const index=gl.createBuffer();gl.bindBuffer(ELEMENT,index);gl.bufferData(ELEMENT,1048576,0x88E8);
gl.bufferSubData(ELEMENT,0,new Uint16Array([0,1,2]));gl.drawElements();
gl.bufferSubData(ELEMENT,0,new Uint16Array([2,1,0]));gl.drawElements();
assert.deepEqual(Array.from(draws.at(-1).index.subarray(0,6)),[2,0,1,0,0,0]);
const other=gl.createVertexArray();gl.bindVertexArray(other);gl.bindBuffer(ELEMENT,index);
gl.bindVertexArray(null);gl.bufferSubData(ELEMENT,0,new Uint16Array([1,0,2]));
gl.bindVertexArray(other);gl.drawElements();
assert.deepEqual(Array.from(draws.at(-1).index.subarray(0,6)),[1,0,0,0,2,0]);
const destination=gl.createBuffer();gl.bindBuffer(WRITE,destination);gl.bufferData(WRITE,1048576,0x88E8);
gl.bindBuffer(READ,vertex);gl.copyBufferSubData(READ,WRITE,0,20,4);
const readback=new Uint8Array(4);gl.getBufferSubData(WRITE,20,readback);
assert.deepEqual(Array.from(readback),[3,3,3,3]);
const feedbackOne={id:'feedback-one'},feedbackTwo={id:'feedback-two'};
gl.bindTransformFeedback(0x8E22,feedbackOne);gl.bindBufferRange(0x8C8E,0,vertex,0,4);
gl.bindTransformFeedback(0x8E22,feedbackTwo);gl.bindBufferBase(0x8C8E,0,index);
gl.bindBuffer(ARRAY,vertex);gl.drawArrays();gl.bufferSubData(ARRAY,0,new Uint8Array([5,5,5,5]));
assert.equal(gl.getParameter(0x8E25),feedbackTwo);
assert.equal(gl.getIndexedParameter(0x8C8F,0),index);
gl.bindTransformFeedback(0x8E22,feedbackOne);
assert.equal(gl.getIndexedParameter(0x8C8F,0),vertex);
const feedbackPhysical=context.feedbacks.get(feedbackOne).get(0);
assert.notEqual(feedbackPhysical,vertex);
assert.deepEqual(Array.from(context.storage.get(feedbackPhysical).subarray(0,4)),[5,5,5,5]);
gl.beginTransformFeedback();const rotations=state.perfGl2.stats.rotations;
gl.drawArrays();gl.bufferSubData(ARRAY,0,new Uint8Array([6,6,6,6]));
assert.equal(state.perfGl2.stats.rotations,rotations);
assert.ok(state.perfGl2.stats.unsafeWrites>0);gl.endTransformFeedback();
gl.bindTransformFeedback(0x8E22,null);
gl.bindVertexArray(null);context.unsignal();gl.drawArrays();context.failAllocation();
gl.bufferSubData(ARRAY,0,new Uint8Array([7,7,7,7]));gl.drawArrays();
assert.deepEqual(Array.from(draws.at(-1).vertex.subarray(0,4)),[7,7,7,7]);
const fb={id:'framebuffer'};gl.bindFramebuffer(0x8D40,fb);gl.bindFramebuffer(0x8D40,null);
const before=calls.filter(call=>call[0]==='fbo').length;gl.clear();
assert.equal(calls.filter(call=>call[0]==='fbo').length,before);
gl.bindFramebuffer(0x8CA8,fb);gl.readPixels();assert.equal(calls.at(-1)[1],fb);
gl.bindFramebuffer(0x8CA9,fb);gl.framebufferTexture2D();assert.equal(calls.at(-1)[1],fb);
gl.bindFramebuffer(0x8CA9,null);gl.blitFramebuffer();assert.deepEqual(calls.at(-1),['blit',fb,null]);
const skippedBefore=state.perfGl2.stats.bindSkips;
gl.useProgram(fb);gl.useProgram(fb);assert.equal(state.perfGl2.stats.bindSkips,skippedBefore+1);
state.perfGl2.setFlags({glue:false,fbo:false,attrib:false,buffers:false});gl.useProgram(fb);
state.perfGl2.check();assert.equal(state.perfGl2.stats.verifyMismatches,0);
assert.equal(state.perfGl2.stats.stateMismatches,0);
state.perfGl2.setFlags({attrib:true,pace:true});
const bindsBefore=calls.filter(call=>call[0]==='bind').length;
gl.bindBuffer(WRITE,destination);gl.bindBuffer(WRITE,null);
assert.equal(calls.filter(call=>call[0]==='bind').length,bindsBefore);
assert.equal(gl.getParameter(WRITE),null);
gl.bindBuffer(WRITE,destination);gl.getBufferSubData(WRITE,20,readback);
assert.deepEqual(Array.from(readback),[3,3,3,3]);
gl.bindBuffer(ARRAY,vertex);assert.equal(state.perfGl2.uploadTarget(vertex),ARRAY);
assert.equal(state.perfGl2.uploadTarget(destination),WRITE);
context.unsignal();let completions=0;
assert.equal(state.perfGl2.deferPresent(()=>completions++),true);
assert.equal(completions,1);
state.perfGl2.deferPresent(()=>completions++);assert.equal(completions,1);
context.signal();await new Promise(resolve=>setTimeout(resolve,5));assert.equal(completions,2);
context.unsignal();state.perfGl2.deferPresent(()=>completions++);
state.perfGl2.deferPresent(()=>completions++);
state.perfGl2.setFlags({pace:false});await new Promise(resolve=>setTimeout(resolve,5));
assert.equal(completions,4);
context.listeners.webglcontextlost();
assert.equal(state.perfGl2.stats.retiredBytes,0);
assert.equal(model('').state.perfGl2,undefined);

const glue=model('?gaiusGlGlue=1'),glueGl=glue.gl,glueState=glue.state;
const gameProgram={id:'game-program'},foreignProgram={id:'foreign-program'},gameVao={id:'game-vao'},gameTexture={id:'game-texture'};
Object.assign(glueState,{currentProgram:1,currentVaoId:2,activeTextureUnit:0,
  programs:new Map([[1,gameProgram]]),vaos:new Map([[2,gameVao]]),textures:new Map([[3,gameTexture]]),
  textureBindings:new Map([[0x0DE1,3]]),textureBufferDefaults:new Set(),textureBufferUnits:new Set(),applySamplerBinding(){},boundBuffers:new Map(),
  vaoEmu:new Map([[2,{elementArrayBuffer:0}]]),bumpDrawProgramGeneration(){},getVaoEmu(){return this.vaoEmu.get(2);}});
Object.assign(glueGl,{TEXTURE_2D:0x0DE1,ELEMENT_ARRAY_BUFFER:ELEMENT});
const invalidations=[];
glueState.perfGl2.invalidate=kind=>invalidations.push(kind);
const glueSource=readFileSync(new URL('../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java',import.meta.url),'utf8');
function invokeGlue(method,params,args) {
  const declaration=glueSource.indexOf('private static native void '+method+'(');
  const annotation=glueSource.lastIndexOf('@JSBody',declaration);
  const start=glueSource.indexOf('script = """',annotation)+'script = """'.length;
  const body=glueSource.slice(start,glueSource.indexOf('"""',start));
  const call=vm.runInNewContext('(function('+params.join(',')+'){'+body+'})',
    {window:{__gaiusWebGL:glueGl,__gaiusGL:glueState},Map,Set});
  call(...args);
}
glueGl.useProgram(foreignProgram);assert.equal(glueState.perfGl2.programDirty,true);
invokeGlue('useProgramJs',['program'],[1]);assert.equal(glueGl.getParameter(0x8B8D),gameProgram);
assert.equal(glueState.perfGl2.programDirty,false);
assert.deepEqual(invalidations,[1]);
glueGl.bindVertexArray(null);assert.equal(glueState.perfGl2.vaoDirty,true);
invokeGlue('bindVertexArrayJs',['array'],[2]);assert.equal(glueGl.getParameter(0x85B5),gameVao);
assert.deepEqual(invalidations,[1,2]);
glueGl.activeTexture(0x84C1);assert.equal(glueState.perfGl2.unitDirty,true);
invokeGlue('activeTextureJs',['unit'],[0x84C0]);assert.equal(glueGl.getParameter(0x84E0),0x84C0);
assert.deepEqual(invalidations,[1,2,3]);
glueGl.bindTexture(0x0DE1,null);assert.equal(glueState.perfGl2.textureDirty.has(0x0DE1),true);
invokeGlue('bindTextureJs',['target','texture'],[0x0DE1,3]);
assert.equal(glueState.perfGl2.textureDirty.has(0x0DE1),false);
assert.deepEqual(invalidations,[1,2,3,3]);

const quality={};vm.runInNewContext(readFileSync(new URL('../web/runtime/quality/gl-pass.js',import.meta.url),'utf8'),quality);
let framebufferCount=0,complete=true;
const framebufferGl={DRAW_FRAMEBUFFER:0x8CA9,COLOR_ATTACHMENT0:0x8CE0,COLOR_ATTACHMENT15:0x8CEF,
  TEXTURE_2D:0x0DE1,NONE:0,FRAMEBUFFER_COMPLETE:0x8CD5,
  createFramebuffer(){return {id:++framebufferCount};},bindFramebuffer(){},framebufferTexture2D(){},
  framebufferTextureLayer(){},drawBuffers(){},deleteFramebuffer(){},
  checkFramebufferStatus(){return complete ? this.FRAMEBUFFER_COMPLETE : 0;}};
const cache=new quality.GaiusQuality.gl.ExternalFramebuffers(),firstTexture={},secondTexture={};
const attachments=[{point:0x8CE0,texture:firstTexture,target:0x0DE1,level:0},
  {point:0x8CE1,texture:secondTexture,target:0x0DE1,level:0}];
const framebuffer=cache.getAttachments(framebufferGl,attachments);
assert.equal(cache.getAttachments(framebufferGl,attachments.slice().reverse()),framebuffer);
assert.notEqual(cache.getAttachments(framebufferGl,[attachments[0]]),framebuffer);
assert.notEqual(cache.getAttachments(framebufferGl,[{...attachments[0],level:1}]),framebuffer);
assert.notEqual(cache.getAttachments(framebufferGl,[{...attachments[0],layer:0}]),framebuffer);
assert.throws(()=>cache.getAttachments(framebufferGl,[attachments[0],attachments[0]]),/Invalid/);
assert.throws(()=>cache.getAttachments(framebufferGl,[]),/Empty/);
complete=false;cache.forget();
assert.throws(()=>cache.getAttachments(framebufferGl,attachments),/attachment set/);
complete=true;assert.notEqual(cache.getAttachments(framebufferGl,attachments),framebuffer);
console.log('perfgl2 smoke: generations, fence reuse, VAOs, lazy uploads/FBOs, attachment sets, pacing cancellation PASS');
