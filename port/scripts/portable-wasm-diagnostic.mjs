import {spawn} from "node:child_process";
import {mkdir,writeFile,mkdtemp} from "node:fs/promises";
import {tmpdir} from "node:os";
import {resolve} from "node:path";

const artifact=resolve(process.env.GAIUS_FILE_ARTIFACT);
const output=resolve(process.env.GAIUS_FILE_OUTPUT);
const chromeBin=process.env.GAIUS_CHROME_BIN||"C:/Program Files/Google/Chrome/Application/chrome.exe";
const port=Number(process.env.GAIUS_DIAG_PORT||9229);
const profile=await mkdtemp(`${tmpdir()}/gaius-diag-`);
const child=spawn(chromeBin,["--headless=new",`--remote-debugging-port=${port}`,"--remote-allow-origins=*",`--user-data-dir=${profile}`,"--disable-background-networking","about:blank"],{stdio:["ignore","pipe","pipe"],windowsHide:true});
const events=[]; const errors=[]; const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function getJson(url){for(let i=0;i<100;i++){try{const r=await fetch(url);if(r.ok)return r.json();}catch{}await sleep(100);}throw Error(`cannot connect ${url}`);}
let target;
for(let i=0;i<100&&!target;i++){target=(await getJson(`http://127.0.0.1:${port}/json/list`)).find(x=>x.type==="page");if(!target)await sleep(100);}
if(!target) throw Error("no page target");
const ws=new WebSocket(target.webSocketDebuggerUrl); await new Promise((ok,bad)=>{ws.onopen=ok;ws.onerror=bad;});
let id=0; const pending=new Map(); ws.onmessage=e=>{const m=JSON.parse(e.data); if(m.id&&pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);return;} events.push({at:Date.now(),method:m.method,params:m.params});};
function send(method,params={}){const n=++id;return new Promise((ok,bad)=>{pending.set(n,m=>m.error?bad(Error(m.error.message)):ok(m.result||{}));ws.send(JSON.stringify({id:n,method,params}));});}
for(const domain of ["Runtime","Log","Page","Network","Performance"]) await send(`${domain}.enable`);
await send("Page.addScriptToEvaluateOnNewDocument", {source: `(()=>{
  const emit=(kind,args,err)=>{try{console.debug('GAIUS_FETCH_TRACE',JSON.stringify({kind,args:args.map(a=>({type:typeof a,text:String(a).slice(0,200)})),stack:err&&err.stack||null}))}catch(_){}};
  const f=window.fetch; window.fetch=function(...a){const e=new Error();emit('fetch',a,e); let p; try{p=f.apply(this,a)}catch(x){emit('fetch-throw',a,x);throw x} return Promise.resolve(p).catch(x=>{emit('fetch-reject',a,x);throw x})};
  for(const n of ['compileStreaming','instantiateStreaming']) if(window.WebAssembly[n]){const o=window.WebAssembly[n];window.WebAssembly[n]=function(...a){const e=new Error();emit('wasm.'+n,a,e);return o.apply(this,a)}}
  const xo=XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(...a){emit('xhr.open',a,new Error());return xo.apply(this,a)};
})();`});
const url=`file:///${artifact.replaceAll("\\","/")}?runtime=wasm&gpu=webgl2&diag=${Date.now()}`;
await send("Page.navigate",{url}); const start=Date.now(); const samples=[];
while(Date.now()-start<295000){
  const expr=`(()=>({readyState:document.readyState,scripts:document.scripts.length,payloadType:typeof window.__gaiusFilePayloadData,payloadCount:Object.keys(window.__gaiusFilePayloadData||{}).length,wasmSource:typeof window.__gaiusWasmSource,portableError:window.__gaiusPortableBootError||null,boot:Object.keys(window).filter(k=>/^__gaius(Boot|Runtime|Wasm)/.test(k)).slice(0,40),marks:performance.getEntriesByType("mark").map(m=>m.name),screen:window.__gaiusMinecraftState?.screen||null}))()`
  try{const r=await send("Runtime.evaluate",{expression:expr,returnByValue:true});samples.push({at:Date.now()-start,value:r.result?.value||null,exception:r.exceptionDetails||null});}catch(e){samples.push({at:Date.now()-start,error:String(e)});}
  if(samples.at(-1)?.value?.screen?.includes?.("TitleScreen"))break;
  await sleep(5000);
}
const assetExpr=`(async()=>{const names=['gaius-shaderc.wasm','gaius-spvc.wasm','gaius-hotpath.wasm'];const out={};for(const n of names){const b=window.__gaiusPortablePayloadBytes?.[n];const r={cached:!!b,bytes:b?.byteLength||0,first:b?[...new Uint8Array(b).slice(0,16)]:[],sum:b?Array.from(new Uint8Array(b)).reduce((x,y)=>(x+y)>>>0,0):0};try{if(b){const z=await new Response(new Blob([b]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer();r.inflate=z.byteLength}}catch(e){r.error={name:e.name,message:e.message}}out[n]=r}return out})()`;
try { const t=await send("Runtime.evaluate",{expression:assetExpr,returnByValue:true,awaitPromise:true}); events.push({at:Date.now(),method:"PortableAssetTest",params:t.result?.value||t.exceptionDetails||null}); } catch(e) { events.push({at:Date.now(),method:"PortableAssetTestError",params:{error:String(e)}}); }
await writeFile(output,JSON.stringify({artifact,url,startedAt:new Date(start).toISOString(),samples,events,childPid:child.pid},null,2));
try{ws.close();child.kill();}catch{}
console.log(`diagnostic written: ${output}`);
