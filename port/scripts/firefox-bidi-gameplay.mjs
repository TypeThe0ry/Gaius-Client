import {enableCreativeFlight} from "../../tools/flight-input.mjs";

// All interaction uses the browser input path; telemetry supplies observations only.
export async function gameplay({evaluate, send, context, screenshot, sleep, report, mark, log,
    seed = "20261014", worldTimeout = 420000}) {
  const ESC = "\uE00C", ENTER = "\uE007", CTRL = "\uE009", END = "\uE010", BACKSPACE = "\uE003";
  const stateExpr = `(()=>{const s=globalThis.__gaiusMinecraftState||{};return {
    screen:String(s.screen||''),screenTitle:s.screenTitle,overlay:s.overlay,level:!!s.level,
    loaded:s.loadedChunkCount||0,player:s.player||null,hit:s.hit||null,pause:s.pause||null,
    widgets:s.screenWidgets||[],worldSelection:s.worldSelection||null,screenSize:s.screenSize||null};})()`;
  const state = () => evaluate(stateExpr);
  let phase = "title";
  const waitFor = async (predicate, timeout, label) => {
    const end = Date.now() + timeout; let last, lastError;
    while (Date.now() < end) {
      try { last = await predicate(); if (last) return last; }
      catch (e) { lastError = e.message; }
      await sleep(250);
    }
    throw new Error(`Timed out waiting for ${label}: ${JSON.stringify({last,lastError,state:await state().catch(()=>null)})}`);
  };
  const key = (value, type) => send("input.performActions", {context, actions:[{
    type:"key", id:"keyboard", actions:[{type,value}]}]});
  const tap = async (value, duration = 100) => {
    await key(value,"keyDown"); await sleep(duration); await key(value,"keyUp");
  };
  const type = async text => { for (const char of String(text)) await tap(char,60); };
  const clickAt = async (x,y,button=0) => send("input.performActions", {context,actions:[{
    type:"pointer",id:"mouse",parameters:{pointerType:"mouse"},actions:[
      {type:"pointerMove",x:Math.round(x),y:Math.round(y),origin:"viewport",duration:0},
      {type:"pointerDown",button},{type:"pause",duration:100},{type:"pointerUp",button}]}]});
  const center = () => evaluate(`(()=>{const r=document.querySelector('canvas')?.getBoundingClientRect();
    return r?{x:r.left+r.width/2,y:r.top+r.height/2}:null;})()`);
  const clickCenter = async button => { const p=await center(); if(!p)throw new Error("Canvas unavailable"); await clickAt(p.x,p.y,button); };
  const widget = async (text, timeout=10000, exact=false) => {
    const expression = `(()=>{const s=globalThis.__gaiusMinecraftState||{},r=document.querySelector('canvas')?.getBoundingClientRect(),z=s.screenSize;
      if(!r||!z||!z.width||!z.height)return null;
      const t=${JSON.stringify(text)}.toLowerCase(),ws=(s.screenWidgets||[]).filter(w=>w.active!==false&&w.visible!==false);
      const w=ws.find(w=>String(w.text||'').trim().toLowerCase()===t)${exact?"":"||ws.find(w=>String(w.text||'').toLowerCase().includes(t))"};
      return w?{text:w.text,type:w.type,focused:w.focused,x:r.left+(Number(w.x)+Number(w.width)/2)*r.width/Number(z.width),y:r.top+(Number(w.y)+Number(w.height)/2)*r.height/Number(z.height)}:null;})()`;
    return waitFor(()=>evaluate(expression),timeout,`widget ${text}`);
  };
  const clickWidget = async (text, timeout=10000, exact=false) => { const w=await widget(text,timeout,exact); await clickAt(w.x,w.y); await sleep(400); return w; };
  const release = async () => {
    await send("input.releaseActions",{context}).catch(()=>{});
    await evaluate("(()=>{document.exitPointerLock?.();return true;})()").catch(()=>{});
    await sleep(200);
  };
  const title = async () => {
    await waitFor(async()=>{
      const gated=await evaluate("document.querySelector('#profile-gate')?.hidden===false").catch(()=>false);
      if(gated)await evaluate(`(()=>{const i=document.querySelector('#profile-name');if(!i)return false;i.value='FirefoxCheck';i.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#profile-submit')?.click();return true;})()`);
      const s=await state();
      if(/BrowserProfileScreen/.test(s.screen)) { await clickWidget("Done",3000).catch(()=>tap(ESC)); return false; }
      return /TitleScreen/.test(s.screen)?s:false;
    },180000,"title screen");
  };
  const command = async text => {
    await tap("t"); await sleep(350);
    await waitFor(async()=>/ChatScreen/.test((await state()).screen),5000,"chat screen");
    await type(text); await tap(ENTER); await sleep(900);
    await waitFor(async()=>!(await state()).screen,5000,"chat closed");
  };
  const entered = async label => {
    const start=Date.now();let firstLoadedChunkSeconds=null,lastSignature=null;
    const result=await waitFor(async()=>{
      const s=await state(),signature=`${s.screen}|${s.overlay}|${s.level}|${!!s.player}`;
      if(signature!==lastSignature){lastSignature=signature;(report.gameplayTimeline ||= []).push({phase:label,seconds:(Date.now()-start)/1000,screen:s.screen,overlay:s.overlay,level:s.level,loaded:s.loaded});log("gameplay-state",label,signature);}
      if(s.loaded>0&&firstLoadedChunkSeconds===null)firstLoadedChunkSeconds=(Date.now()-start)/1000;
      if(/DisconnectedScreen|ErrorScreen|AlertScreen/.test(s.screen))throw new Error(`World entry failed: ${JSON.stringify(s)}`);
      return s.level&&!s.screen&&s.player?s:false;
    },worldTimeout,label);
    return {state:result,seconds:(Date.now()-start)/1000,firstLoadedChunkSeconds};
  };
  const chunks = async () => {
    await waitFor(async()=>{const s=await state();return s.loaded>=9?s:false;},60000,"at least nine loaded chunks").catch(()=>null);
    return state();
  };
  try {
    await title();
    report.titleState=await state();mark("bootsToTitle",true,{screen:report.titleState.screen});
    await screenshot("11-title.png");
    phase="createWorld";
    await clickWidget("Singleplayer");
    await waitFor(async()=>/SelectWorldScreen|CreateWorldScreen/.test((await state()).screen),60000,"world selection");
    report.worldListBefore=(await state()).worldSelection;
    await screenshot("12-select-world.png");
    if(!/CreateWorldScreen/.test((await state()).screen))await clickWidget("Create New World");
    await waitFor(async()=>/CreateWorldScreen/.test((await state()).screen),60000,"create world screen");
    for(let i=0;i<4;i++){
      const mode=await widget("Game Mode");
      if(/creative/i.test(mode.text))break;
      await clickAt(mode.x,mode.y);await sleep(500);
    }
    const mode=await widget("Game Mode");mark("creativeSelected",/creative/i.test(mode.text),mode);
    if(!/creative/i.test(mode.text))throw new Error("Creative mode could not be selected");
    const worldTab=await widget("World",2000,true).catch(()=>null);
    if(worldTab)await clickAt(worldTab.x,worldTab.y);
    else{await key(CTRL,"keyDown");try{await tap("2");}finally{await key(CTRL,"keyUp");}}
    const seedPredicate="w.visible!==false&&w.active!==false&&(String(w.type||'').endsWith('CreateWorldScreen$WorldTab$1')||(/EditBox$/.test(String(w.type||''))&&/seed/i.test(String(w.text||''))))";
    const seedExpr=`(()=>{const s=globalThis.__gaiusMinecraftState||{},ws=(s.screenWidgets||[]).filter(w=>${seedPredicate}),r=document.querySelector('canvas')?.getBoundingClientRect(),z=s.screenSize;if(ws.length!==1||!r||!z)return null;const w=ws[0];return {...w,cx:r.left+(Number(w.x)+Number(w.width)/2)*r.width/z.width,cy:r.top+(Number(w.y)+Number(w.height)/2)*r.height/z.height};})()`;
    const seedWidget=await waitFor(()=>evaluate(seedExpr),10000,"unique seed input");
    await clickAt(seedWidget.cx,seedWidget.cy);
    await waitFor(async()=>(await evaluate(seedExpr))?.focused===true,5000,"seed input focus");
    await tap(END);
    for(let i=0;i<32;i++)await tap(BACKSPACE,35);
    await type(seed);
    report.seedConfiguration={configuredSeed:String(seed),effectiveSeed:null,acceptance:"unverified",widget:await evaluate(seedExpr),usedWorldTabShortcut:!worldTab};
    mark("seedTyped",true,report.seedConfiguration);
    await screenshot("13-create-world-seed.png");
    await clickWidget("Create New World");
    phase="worldEntry";
    const entry=await entered("world entered");report.worldEntry=entry;mark("worldEntered",true,entry);
    await screenshot("20-world-entry.png");
    const spawn=await chunks();mark("chunksLoaded",spawn.loaded>=9,{loaded:spawn.loaded});
    await screenshot("21-spawn.png");
    const p0=spawn.player;
    if(!p0)throw new Error("Spawn player unavailable");
    phase="walk";
    await clickCenter(0);await sleep(300);
    const before=(await state()).player;
    await key("w","keyDown");try{await sleep(1500);}finally{await key("w","keyUp");}
    await sleep(800);
    const after=(await state()).player,distance=after&&before?Math.hypot(after.x-before.x,after.z-before.z):null;
    report.move={before,after,distance};mark("movesWithW",distance!==null&&distance>0.5,report.move);
    await screenshot("23-after-move.png");
    phase="breakPlace";
    const sx=Math.floor(p0.x)+0.5,sz=Math.floor(p0.z)+0.5,groundY=Math.floor(p0.y);
    report.placeFlight=await enableCreativeFlight({tap:()=>tap(" "),readPlayer:async()=>(await state()).player,
      lift:p=>command(`/tp @s ${Math.floor(p.x)+0.5} ${Math.floor(p.y)+100} ${Math.floor(p.z)+0.5} 0 0`),sleep});
    mark("creativeFlight",report.placeFlight.enabled,report.placeFlight);
    await command(`/tp @s ${sx} ${groundY} ${sz} 0 90`);
    await command("/item replace entity @s hotbar.0 with minecraft:stone 1");await tap("1");
    await waitFor(async()=>(await state()).player?.selectedItem?.item==="minecraft:stone",15000,"stone inventory update");
    await sleep(1200);const h0=await state();await screenshot("24-before-break.png");
    await clickCenter(0);
    await waitFor(async()=>{const s=await state();return s.hit&&h0.hit&&(s.hit.blockPos!==h0.hit.blockPos||s.hit.blockState!==h0.hit.blockState)?s:false;},6000,"block breaking").catch(()=>null);
    const h1=await state();await screenshot("25-after-break.png");
    const broke=h0.hit?.type==="BLOCK"&&!!h1.hit&&(h1.hit.blockPos!==h0.hit.blockPos||/air/.test(String(h1.hit.blockState)));
    mark("breaksBlock",broke,{before:h0.hit,after:h1.hit,player:h0.player});
    await clickCenter(2);
    await waitFor(async()=>{const s=await state();return s.hit&&h1.hit&&(s.hit.blockPos!==h1.hit.blockPos||s.hit.blockState!==h1.hit.blockState)?s:false;},6000,"block placement").catch(()=>null);
    const h2=await state();await screenshot("26-after-place.png");
    const placed=!!h2.hit&&/stone/i.test(String(h2.hit.blockState))&&!/stone/i.test(String(h1.hit?.blockState||""));
    mark("placesBlock",placed,{before:h1.hit,after:h2.hit,player:h2.player});
    report.placedBlock={pos:h2.hit?.blockPos,state:h2.hit?.blockState,playerAt:h2.player,placementVerified:placed};
    phase="pause";await release();
    for(let i=0;i<4&&!/PauseScreen/.test((await state()).screen);i++){await tap(ESC);await sleep(1500);}
    await waitFor(async()=>/PauseScreen/.test((await state()).screen),5000,"pause screen");
    const paused=await state();report.pause=paused.widgets;await screenshot("30-pause.png");
    mark("pauseMenu",paused.widgets.some(w=>/Save and Quit/i.test(w.text))&&paused.widgets.some(w=>/LAN/i.test(w.text)),{widgets:paused.widgets.map(w=>w.text)});
    const leavePos=paused.player;phase="saveAndQuit";const quitAt=Date.now();
    await clickWidget("Save and Quit to Title");
    await waitFor(async()=>{const s=await state();return /TitleScreen/.test(s.screen)&&!s.level;},120000,"title after save");
    const titleSeconds=(Date.now()-quitAt)/1000;
    const workerStopped=await waitFor(()=>evaluate("(()=>{const w=globalThis.__gaiusSingleplayerWorkers;return !w||typeof w.values!=='function'||[...w.values()].every(x=>x.__gaiusStopped||x.__gaiusTerminal);})()"),120000,"server worker flush").then(()=>true).catch(()=>false);
    mark("saveAndQuit",workerStopped,{titleSeconds,workerStopped,workerStoppedSeconds:(Date.now()-quitAt)/1000});
    await screenshot("31-title-after-quit.png");
    if(!workerStopped)throw new Error("Server Worker did not stop after save; reload would risk incomplete persistence");
    phase="reopen";
    const url=await evaluate("location.href");
    await send("browsingContext.navigate",{context,url,wait:"none"});
    await title();await clickWidget("Singleplayer");
    await waitFor(async()=>/SelectWorldScreen/.test((await state()).screen),60000,"saved world selection");
    const saved=await waitFor(()=>evaluate(`(()=>{const s=globalThis.__gaiusMinecraftState||{},f=s.worldSelection?.first,r=document.querySelector('canvas')?.getBoundingClientRect(),z=s.screenSize;
      return f&&r&&z?{name:f.name,count:s.worldSelection.count,x:r.left+(Number(f.x)+Number(f.width)/2)*r.width/z.width,y:r.top+(Number(f.y)+Number(f.height)/2)*r.height/z.height}:null;})()`),20000,"saved world entry");
    report.worldListAfter=saved;mark("worldListed",true,saved);await screenshot("32-world-list.png");
    await clickAt(saved.x,saved.y);await sleep(500);
    if(!(await state()).level)await clickWidget("Play Selected World",5000);
    report.reentry=await entered("world reentered");const reopened=await chunks();
    await screenshot("40-reentered.png");mark("chunksReload",reopened.loaded>=9,{loaded:reopened.loaded});
    const delta=leavePos&&reopened.player?Math.hypot(reopened.player.x-leavePos.x,reopened.player.y-leavePos.y,reopened.player.z-leavePos.z):null;
    mark("playerPositionPersisted",delta!==null&&delta<2.5,{leavePos,now:reopened.player,delta});
    await command(`/tp @s ${sx} ${groundY} ${sz} 0 90`);await sleep(1500);
    const persisted=await state();await screenshot("41-placed-block-after-reload.png");
    mark("placedBlockPersisted",placed&&!!persisted.hit&&persisted.hit.blockPos===report.placedBlock.pos&&/stone/i.test(String(persisted.hit.blockState)),{expected:report.placedBlock,got:persisted.hit});
    report.finalGameplay=persisted;
    return persisted;
  } catch(e) {
    mark(phase,false,{error:e.message});report.gameplayError={phase,error:String(e.stack||e)};
    await screenshot(`error-${phase}.png`).catch(()=>{});throw e;
  } finally { await release(); }
}
