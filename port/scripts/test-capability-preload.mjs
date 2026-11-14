// Run actual emitted inline preload and boot together in a mocked page.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const [indexPath, descriptorPath, bootPath, variant] = process.argv.slice(2);
const index = readFileSync(indexPath, 'utf8');
const inline = index.match(/<script data-gaius-preload="wasm">([\s\S]*?)<\/script>/)[1];
const descriptor = JSON.parse(index.match(/window\.__gaiusSite = (.*);/)[1]);
assert.deepEqual(descriptor, JSON.parse(readFileSync(descriptorPath, 'utf8')));
const boot = readFileSync(bootPath, 'utf8');
const tests = [['explicit','?runtime=js','ok'],['uppercase','?runtime=JS','ok'],['encoded','?runtime=%6a%73','ok'],['duplicates','?runtime=wasm&runtime=js','ok'],['odd','?runtime=%FFjs','ok'],['empty','?runtime=','ok'],['invalid','?runtime=bogus','ok'],['auto','?runtime=auto','ok'],['wasm','?runtime=wasm','ok'],['parser','?runtime=js','parser'],['no-wasm','?runtime=auto','no-wasm'],['no-validate','?runtime=auto','no-validate'],['no-gc','?runtime=auto','no-gc'],['no-try','?runtime=auto','no-try'],['no-suspending','?runtime=auto','no-suspending'],['no-promising','?runtime=auto','no-promising'],['throw','?runtime=auto','throw'],['nonboolean','?runtime=auto','nonboolean'],['no-decompression','?runtime=js','no-decompression'],['sw-no-decompression','?runtime=js','sw-no-decompression']];
for (const [name, search, mode] of tests) {
  let probe = 0;
  const wasm = {validate() { probe++; if (mode === 'throw') throw Error('probe'); if (mode === 'nonboolean') return 1; return !(mode === 'no-gc' && probe % 2 === 1 || mode === 'no-try' && probe % 2 === 0); }, Suspending(){}, promising(){}};
  if (mode === 'no-validate') delete wasm.validate;
  if (mode === 'no-suspending') delete wasm.Suspending;
  if (mode === 'no-promising') delete wasm.promising;
  const requests = [];
  const response = {ok:true,status:200,body:{},headers:{get:()=> 'application/wasm'},clone(){return this;}};
  const head = {setAttribute(){},appendChild(){}};
  const location = {protocol:'https:',search,href:'https://fixture.invalid/index.html'+search};
  const context = {location,navigator:mode === 'sw-no-decompression' ? {serviceWorker:{controller:{}}} : {},
    document:{readyState:'loading',head,documentElement:head,createElement:()=>({setAttribute(){},addEventListener(){}}),getElementById:()=>null,querySelector:()=>null},
    localStorage:{getItem:()=>null,setItem(){}},performance:{now:()=>1,mark(){}},setTimeout:()=>0,clearTimeout(){},setInterval:()=>0,clearInterval(){},addEventListener(){},
    fetch: url=>{requests.push(String(url));return String(url).endsWith('.gz') ? new Promise(()=>{}) : Promise.resolve(response);},WebAssembly:mode === 'no-wasm' ? undefined : wasm,
    DecompressionStream:mode.includes('no-decompression') ? undefined : function(){},URL,URLSearchParams:mode === 'parser' ? class {constructor(){throw Error('bad query')}} : URLSearchParams,Uint8Array,TextDecoder,Blob,Response,Promise,console:{log(){},warn(){},error(){}}};
  context.window=context; vm.createContext(context); vm.runInContext(inline,context);
  const preloads=requests.length, preloaded=context.__gaiusWasmHttpPreload;
  probe=0; context.__gaiusSite=descriptor; vm.runInContext(boot,context);
  const selected=context.__gaiusRuntimeSelection.selected;
  const explicit=['explicit','uppercase','encoded','no-decompression','sw-no-decompression'].includes(name);
  const jsAvailable=variant === 'raw' || variant === 'gzip' && !mode.includes('no-decompression');
  const missing=mode.startsWith('no-') && mode !== 'no-decompression' || mode === 'throw' || mode === 'nonboolean' || mode === 'no-decompression' && !!descriptor.inflate;
  const expected=explicit && jsAvailable ? 'js' : missing ? (jsAvailable ? 'js' : 'unsupported') : 'wasm';
  assert.equal(selected,expected,`${variant} ${name} selection`);
  assert.equal(preloads,selected === 'wasm' ? 1 : 0,`${variant} ${name} preloads`);
  if (selected === 'wasm') { await context.__gaiusWasmSource; assert.equal(requests.filter(url=>url === preloaded.url).length,1,`${variant} ${name} reuses response`); assert.equal(context.__gaiusWasmHttpPreload,null); assert.ok(preloaded); }
}
console.log(`${variant}: ${tests.length} emitted-index/boot cases passed`);
