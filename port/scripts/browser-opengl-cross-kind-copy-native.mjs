import fs from 'node:fs';
import path from 'node:path';

// Generate a small browser regression page using the current production JSBody
// implementations. Serve the output over localhost and open it in Chrome.
// This exercises real WebGL buffers, not the full Minecraft renderer.
if (!process.argv[2]) throw Error('Usage: node browser-opengl-cross-kind-copy-native.mjs <output.html>');
const source = fs.readFileSync(new URL('../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java', import.meta.url), 'utf8');
function body(name) {
  const at = source.search(new RegExp('(?:public|private) static native [\\w\\[\\]]+ '+name+'\\('));
  if (at < 0) throw Error('Missing method '+name);
  const annotation = source.lastIndexOf('@JSBody', at);
  const start = source.indexOf('script = """', annotation) + 12;
  const end = source.indexOf('"""', start);
  if (start < annotation || end > at) throw Error('Bad JSBody '+name);
  return source.slice(start,end);
}
const first = source.indexOf('window.__gaiusGL.noteBufferWebglType=function');
const last = source.indexOf('window.__gaiusGL.shadowBufferDataForTarget=function', first);
const markStart=source.indexOf('window.__gaiusGL.markBufferShadowRequired=function');
const markEnd=source.indexOf('window.__gaiusGL.bufferNeedsArrayShadow=function',markStart);
const helpers = source.slice(markStart,markEnd)+source.slice(first,last);
const scripts = {helpers, init:body('initializeJs'), upload:body('bufferSubDataJs'), bind:body('bindBuffer'), copy:body('copyBufferSubData'), named:body('copyNamedBufferSubData')};
const script = `
const scripts=${JSON.stringify(scripts)};
const output=document.querySelector('pre');
const gl=document.createElement('canvas').getContext('webgl2');
if(!gl) throw Error('WebGL2 unavailable');
window.__gaiusWebGL=gl;
window.__gaiusGLStats={};
const vao={elementArrayBuffer:0,elementArrayBufferObject:null};
Function(scripts.init)();
const state=window.__gaiusGL;
Object.assign(state,{
 getVaoEmu:()=>vao,bindPhysicalElementBuffer:(v,b)=>gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,b),replaceVaoBufferRef:()=>{},
 ensureLogicalElementBuffer:()=>gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,vao.elementArrayBufferObject),
 noteBufferUpload:()=>{},noteNamedBufferBindings:()=>{},
 bumpBufferVersion(id){this.bufferVersions.set(id,(this.bufferVersions.get(id)||0)+1);}});
Function(scripts.helpers)();
const bind=Function('target','buffer',scripts.bind);
const copy=Function('sourceTarget','targetTarget','sourceOffset','targetOffset','size',scripts.copy);
const named=Function('sourceBuffer','targetBuffer','sourceOffset','targetOffset','size',scripts.named);
const checks=[];
function check(value,label){if(!value)throw Error(label);checks.push(label);}
function alloc(id,target,data){state.buffers.set(id,gl.createBuffer());bind(target,id);gl.bufferData(target,data,gl.STATIC_DRAW);state.bufferSizes.set(id,typeof data==='number'?data:data.byteLength);}
function bytes(id,offset,length){const previous=state.boundBuffers.get(gl.COPY_READ_BUFFER)||0;bind(gl.COPY_READ_BUFFER,id);const a=new Uint8Array(length);gl.getBufferSubData(gl.COPY_READ_BUFFER,offset,a);bind(gl.COPY_READ_BUFFER,previous);return a;}
function equal(a,b){return a.length===b.length&&a.every((v,i)=>v===b[i]);}
try{
 const input=Uint8Array.from({length:262144},(_,i)=>(i*17+13)&255);
 alloc(1,gl.COPY_READ_BUFFER,input);alloc(2,gl.ELEMENT_ARRAY_BUFFER,262144);bind(gl.COPY_WRITE_BUFFER,2);
 gl.copyBufferSubData(gl.COPY_READ_BUFFER,gl.COPY_WRITE_BUFFER,11232,0,864);
 check(gl.getError()===gl.INVALID_OPERATION,'native cross-kind control rejected');
 check(state.bufferWebglTypes.get(1)===2&&state.bufferWebglTypes.get(2)===1,'production first-bind classification');
 copy(gl.COPY_READ_BUFFER,gl.COPY_WRITE_BUFFER,11232,0,864);
 check(gl.getError()===gl.NO_ERROR,'production generic cross-kind copy has no GL error');
 check(equal(bytes(2,0,864),input.slice(11232,12096)),'production generic bytes exact');
 const scratch=state.bufferCopyScratch;
 copy(gl.COPY_READ_BUFFER,gl.COPY_WRITE_BUFFER,100,1000,64);
 check(state.bufferCopyScratch===scratch,'scratch reused');
 state.bufferBytes.set(1,input.slice());
 const reads=__gaiusGLStats.crossKindBufferCopyReadbacks;
 copy(gl.COPY_READ_BUFFER,gl.COPY_WRITE_BUFFER,200,2000,64);
 check(__gaiusGLStats.crossKindBufferCopyReadbacks===reads,'complete CPU shadow avoids GPU readback');
 alloc(3,gl.COPY_READ_BUFFER,262144);alloc(4,gl.COPY_WRITE_BUFFER,262144);
 const previousRead=gl.getParameter(gl.COPY_READ_BUFFER_BINDING),previousWrite=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
 named(1,2,300,3000,128);
 check(gl.getError()===gl.NO_ERROR,'production named cross-kind copy has no GL error');
 check(gl.getParameter(gl.COPY_READ_BUFFER_BINDING)===previousRead&&gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING)===previousWrite,'named physical bindings restored');
 check(equal(bytes(2,3000,128),input.slice(300,428)),'production named bytes exact');
 bind(gl.COPY_READ_BUFFER,1);bind(gl.COPY_WRITE_BUFFER,3);
 const count=__gaiusGLStats.crossKindBufferCopies;
 copy(gl.COPY_READ_BUFFER,gl.COPY_WRITE_BUFFER,400,0,128);
 check(gl.getError()===gl.NO_ERROR&&__gaiusGLStats.crossKindBufferCopies===count,'same-kind keeps native GPU path');
 check(equal(bytes(3,0,128),input.slice(400,528)),'same-kind bytes exact');
 bind(gl.COPY_READ_BUFFER,1);bind(gl.COPY_WRITE_BUFFER,2);
 state.deleteBufferShadow(1);
 const upload=Function('target','offset','data',scripts.upload);
 upload(gl.COPY_READ_BUFFER,0,input);
 const steadyReads=__gaiusGLStats.crossKindBufferCopyReadbacks;
 for(let i=0;i<100;i++){
   const updated=new Uint8Array(72);updated.fill(i);
   upload(gl.COPY_READ_BUFFER,1000,updated);
   copy(gl.COPY_READ_BUFFER,gl.COPY_WRITE_BUFFER,1000,32,72);
   check(equal(bytes(2,32,72),updated),'updated staging bytes '+i);
 }
 check(__gaiusGLStats.crossKindBufferCopyReadbacks===steadyReads,'100 changing uploads avoid compatibility GPU readbacks');
 check(gl.getError()===gl.NO_ERROR,'repeated native uploads have no GL error');
 window.result={ok:true,checks,stats:window.__gaiusGLStats};
}catch(error){window.result={ok:false,checks,error:String(error),stack:error.stack};}
finally{for(const b of state.buffers.values())gl.deleteBuffer(b);}
output.textContent=JSON.stringify(window.result,null,2);
document.title=window.result.ok?'PASS: native WebGL buffer copy':'FAIL: native WebGL buffer copy';
`;
const target = path.resolve(process.argv[2]);
fs.mkdirSync(path.dirname(target), {recursive:true});
fs.writeFileSync(target, '<!doctype html><meta charset="utf-8"><title>WebGL copy regression</title><pre>Running</pre><script>'+script+'</script>');
console.log(target);
