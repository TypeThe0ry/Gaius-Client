#!/usr/bin/env node
/* Firefox BiDi release-candidate smoke/acceptance driver.
 * Env: SITE (staged site directory), PORTABLE (Gaius-<profile>.html), PROFILE, OUT,
 * GAIUS_FIREFOX_BIN, PAGE_URL, SEED.  The driver deliberately uses only WebDriver
 * BiDi so it exercises a real Gecko engine rather than a Chromium compatibility path.
 */
import {gameplay} from "./firefox-bidi-gameplay.mjs";
import {spawn} from "node:child_process";
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from "node:fs/promises";
import {once} from "node:events";
import {fileURLToPath, pathToFileURL} from "node:url";
import {join, resolve} from "node:path";
import {tmpdir} from "node:os";
import {createServer} from "node:net";


const out = resolve(process.env.OUT || "firefox-out");
const profile = process.env.PROFILE || await newestProfile();
const seed = String(process.env.SEED || "20261014");
const firefox = process.env.GAIUS_FIREFOX_BIN || "firefox";
const site = process.env.SITE ? resolve(process.env.SITE) : null;
const portable = process.env.PORTABLE ? resolve(process.env.PORTABLE) : null;
const pageUrl = process.env.PAGE_URL || (portable ? pathToFileURL(portable).href : null);
if (!pageUrl && !site) throw new Error("SITE/PAGE_URL or PORTABLE is required");

async function newestProfile() {
  const root = fileURLToPath(new URL("../..", import.meta.url));
  const entries = await Promise.all((await readdir(join(root, "port/versions")))
    .filter(name => name.endsWith(".json"))
    .map(async name => JSON.parse(await readFile(join(root, "port/versions", name), "utf8"))));
  return entries.sort((left, right) => String(right.official?.releaseTime ?? "")
    .localeCompare(String(left.official?.releaseTime ?? "")))[0].id;
}
await mkdir(out, {recursive: true});
const logLines = [];
const log = (...x) => { const line = `${new Date().toISOString()} ${x.join(" ")}`; logLines.push(line); console.error(line); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function freePort() { const s = createServer(); await new Promise(ok => s.listen(0, "127.0.0.1", ok)); const p=s.address().port; await new Promise(ok=>s.close(ok)); return p; }

class BiDi {
  constructor(url) { this.url=url; this.id=0; this.pending=new Map(); this.events=[]; }
  async open() {
    const WS = globalThis.WebSocket;
    if (!WS) throw new Error("Node WebSocket is unavailable; use Node 22+");
    this.ws = new WS(this.url);
    this.ws.addEventListener("message", e => { const m=JSON.parse(e.data); if (m.id && this.pending.has(m.id)) { const p=this.pending.get(m.id); this.pending.delete(m.id); m.type === "error" ? p.reject(new Error(`${m.error}: ${m.message||""}`)) : p.resolve(m); } else if (m.method) this.events.push(m); });
    await new Promise((ok,bad)=>{this.ws.addEventListener("open",ok);this.ws.addEventListener("error",bad);});
  }
  send(method, params={}) { const id=++this.id; return new Promise((resolve,reject)=>{ const t=setTimeout(()=>{this.pending.delete(id);reject(new Error(`BiDi timeout ${method}`));},30000); this.pending.set(id,{resolve:v=>{clearTimeout(t);resolve(v)},reject:e=>{clearTimeout(t);reject(e)}}); this.ws.send(JSON.stringify({id,method,params})); }); }
  async close(){try{await this.send("session.end",{})}catch{} try{this.ws.close()}catch{}}
}
const resultValue = r => r?.result?.result?.value ?? r?.result?.value;
async function run() {
  const report={profile,seed,pageUrl,startedAt:new Date().toISOString(),checks:{},events:[],runtime:null,console:[],screenshots:[],portable:Boolean(portable)};
  let server=null, browser=null, bidi=null, context=null, prof=null;
  const mark=(name,ok,detail)=>{report.checks[name]={ok,detail};log(ok?"PASS":"FAIL",name,JSON.stringify(detail||{}).slice(0,500));};
  try {
    let targetUrl=pageUrl;
    if (site) { const port=await freePort(); const py=process.env.PYTHON||"python"; server=spawn(py,[fileURLToPath(new URL("./serve-dist.py", import.meta.url)),"--host","127.0.0.1","--port",String(port),"--directory",site],{stdio:["ignore","pipe","pipe"]}); await sleep(800); targetUrl=`http://127.0.0.1:${port}/`; }
    const port=await freePort(); prof=await mkdtemp(join(out,"firefox-profile-"));
    await writeFile(join(prof,"user.js"), 'user_pref("browser.shell.checkDefaultBrowser", false);\nuser_pref("browser.aboutwelcome.enabled", false);\nuser_pref("browser.startup.homepage_override.mstone", "ignore");\n');
    browser=spawn(firefox,["--headless","--width=1280","--height=720","--remote-debugging-port="+port,"--profile",prof,"--no-remote","about:blank"],{stdio:["ignore","pipe","pipe"]});
    browser.stderr.on("data",b=>log("firefox",String(b).trim().slice(0,1000)));
    const wsUrl=`ws://127.0.0.1:${port}/session`;
    for(let i=0;i<80;i++){ try { bidi=new BiDi(wsUrl); await bidi.open(); break; } catch(e) {try{bidi.ws.close()}catch{};if(i===79)throw e; await sleep(250);}}
    let s=await bidi.send("session.new",{capabilities:{alwaysMatch:{}}}); report.session=s.result||null;
    await bidi.send("session.subscribe",{events:["log.entryAdded","browsingContext.domContentLoaded","browsingContext.load"]});
    const tree=await bidi.send("browsingContext.getTree",{}); context=tree.result?.contexts?.[0]?.context; if(!context) throw new Error("no browsing context");
    report.pageUrl=targetUrl;
    await bidi.send("browsingContext.navigate",{context,url:targetUrl,wait:"none"});
    const evalRaw=async expression=>{const r=await bidi.send("script.evaluate",{target:{context},expression:`(async()=>JSON.stringify(await (${expression})))()`,awaitPromise:true,resultOwnership:"none"}); if(r.result.type === "exception") throw new Error(r.result.exceptionDetails?.text || "evaluate exception"); const v=resultValue(r); return v===undefined ? undefined : JSON.parse(v);};
    const state=()=>evalRaw(`(()=>{const s=window.__gaiusMinecraftState||{};const r=window.__gaiusRuntimeSelection||null,g=window.__gaiusGpuSelection||null,b=window.__gaiusBoot||{};return {screen:s.screen||null,screenTitle:s.screenTitle||null,overlay:s.overlay||null,level:!!s.level,loaded:s.loadedChunkCount||0,player:s.player||null,hit:s.hit||null,pause:s.pause||null,widgets:(s.screenWidgets||[]).map(w=>({type:w.type,text:w.text,x:w.x,y:w.y,width:w.width,height:w.height,active:w.active,visible:w.visible})),runtime:r,gpu:g,boot:b,crossOriginIsolated:globalThis.crossOriginIsolated,wasm:typeof WebAssembly!=="undefined",jspi:typeof WebAssembly.Suspending!=="undefined"}})()`);
    const screenshot=async name=>{try{const r=await bidi.send("browsingContext.captureScreenshot",{context,origin:"viewport",format:{type:"image/png"}});const p=join(out,name);await writeFile(p,Buffer.from(r.result.data,"base64"));report.screenshots.push(name);return p}catch(e){log("screenshot-error",e.message);return null;}};
    const waitState=async(pred,ms=900000)=>{const end=Date.now()+ms;let last,signature;while(Date.now()<end){try{last=await state();const next=JSON.stringify([last?.screen,last?.runtime,last?.gpu]);if(next!==signature){signature=next;log("state",next);}if(pred(last))return last}catch(e){log("state-error",e.message)}await sleep(1000)}return last;};
    // GenericMessageScreen/Loading Minecraft is a normal startup intermediate state. Wait until
    // the client reaches an interactive title/profile screen before declaring the boot result.
    const initial=await waitState(s=>/TitleScreen|BrowserProfileScreen/.test(s?.screen||""),Number(process.env.BOOT_TIMEOUT_MS||180000)); report.initial=initial; await screenshot("10-first-screen.png");
    if(initial?.screen && /TitleScreen|BrowserProfileScreen/.test(initial.screen)){mark("bootsToTitle",true,{screen:initial.screen,crossOriginIsolated:initial.crossOriginIsolated});await screenshot("11-title.png");} else mark("bootsToTitle",false,initial);
    report.runtime=initial?.runtime||null; report.gpu=initial?.gpu||null;
    if(initial?.runtime) mark("runtimeObserved",true,initial.runtime); else mark("runtimeObserved",false,{reason:"runtime telemetry unavailable"});
    if (initial?.screen && /TitleScreen|BrowserProfileScreen/.test(initial.screen) && process.env.MODE !== "boot") {
      await gameplay({evaluate:evalRaw,send:(m,p)=>bidi.send(m,p),context,screenshot,sleep,report,mark,log,seed,worldTimeout:Number(process.env.WORLD_TIMEOUT_MS||420000)}).catch(e=>{report.gameplayError ||= {error:e.stack};});
    }
    const endState=await state().catch(e=>({error:e.message})); report.final=endState; await screenshot("90-final.png");
    const ev=bidi.events.filter(e=>e.method==="log.entryAdded"); report.events=ev.slice(-200); report.console=ev.map(e=>e.params?.text||e.params?.entry?.text||e.params?.entry?.message||JSON.stringify(e.params)).slice(-200);
    if(process.env.MODE !== "boot") mark("worldVisible",!!endState?.level,{screen:endState?.screen,loaded:endState?.loaded});
    report.ok=Object.values(report.checks).every(x=>x.ok);
    await writeFile(join(out,"report.json"),JSON.stringify(report,null,2)); await writeFile(join(out,"console.log"),logLines.concat(report.console).join("\n")+"\n");
  } catch(e) { report.error=String(e.stack||e); await writeFile(join(out,"report.json"),JSON.stringify(report,null,2)); await writeFile(join(out,"console.log"),logLines.concat(String(e.stack||e)).join("\n")+"\n"); log("ERROR",String(e.stack||e)); }
  finally { if(bidi){report.events=bidi.events;report.consoleErrors=bidi.events.filter(e=>e.method==="log.entryAdded"&&e.params.level==="error");}
    report.ok=!report.error&&Object.values(report.checks).every(x=>x.ok);
    await writeFile(join(out,"report.json"),JSON.stringify(report,null,2));
    await writeFile(join(out,"console.log"),logLines.concat((bidi?.events||[]).filter(e=>e.method==="log.entryAdded").map(e=>JSON.stringify(e.params))).join("\n")+"\n");
    await bidi?.close();
    if(browser && browser.exitCode===null){if(process.platform==="win32"){await new Promise(ok=>{const k=spawn("taskkill",["/F","/T","/PID",String(browser.pid)],{stdio:"ignore"}); k.on("exit",ok);});}else browser.kill();}
    if(server){server.kill(); if(server.exitCode===null)await Promise.race([once(server,"exit"),sleep(2000)]);}
    if(prof)await rm(prof,{recursive:true,force:true,maxRetries:10,retryDelay:500}).catch(e=>log("profile-cleanup",e.message)); }
}
await run();
