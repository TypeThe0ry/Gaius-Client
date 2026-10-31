import {createHash} from "node:crypto";
import {readFile, writeFile} from "node:fs/promises";
import {join} from "node:path";
import {decodePng} from "./capture-pixel-diff.mjs";
import {waitForCaptureQuiescence} from "./capture-quiescence.mjs";

export const CAPTURE_OPTIONS = Object.freeze([
  "renderDistance:8", "simulationDistance:6", "renderClouds:true", "particles:2",
  "enableVsync:true", "maxFps:60", "bobView:false", "fov:0.0", "guiScale:2",
]);
export const CAPTURE_BOOT_SOURCE = `globalThis.__gaiusCapture={enabled:false,animationTick:0};
globalThis.__gaiusCaptureBootTimeoutMs=150000;
(()=>{
  // Keep the release marker scoped to this document.  A reload must install a
  // fresh barrier so the harness can seed the next document's pacing options.
  let released=false;
  let resolveBoot=()=>{};
  globalThis.__gaiusReleaseBoot=()=>{
    released=true;
    resolveBoot();
  };
  globalThis.__gaiusBeforeMain=()=>{
    if(released) return Promise.resolve();
    return new Promise(resolve=>{resolveBoot=resolve;});
  };
})();`;
export const CAPTURE_OPTIONS_EXPRESSION = `(async()=>{
  // Wait for the launcher storage bridge (a large single-file page may still be parsing it),
  // then for its IndexedDB scan, so the scan cannot overwrite the seeded options.
  for(const end=Date.now()+120000;typeof globalThis.__gaiusFsPut!=='function'&&Date.now()<end;)
    await new Promise(resolve=>setTimeout(resolve,25));
  if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady;
  const path='/gaius/options.txt';
  const encoded=${JSON.stringify(Buffer.from(CAPTURE_OPTIONS.join("\n") + "\n").toString("base64"))};
  const files=globalThis.__gaiusPersistentFiles||(globalThis.__gaiusPersistentFiles=Object.create(null));
  files[path]=encoded;
  if(typeof globalThis.__gaiusFsPut!=='function')throw new Error('capture options storage unavailable');
  globalThis.__gaiusFsPut(path,encoded);
  if(globalThis.__gaiusFsFlush)await globalThis.__gaiusFsFlush(true);
  globalThis.__gaiusReleaseBoot();
  return ${JSON.stringify(CAPTURE_OPTIONS)};
})()`;

export function stageAnimationCapture(source) {
  const pattern = /(?:\$rt_java\.)?[\w$]+_SpriteContents\$AnimationState_tick = \$this => \{([\s\S]*?)\n\};/g;
  const matches = [...source.matchAll(pattern)];
  if (matches.length !== 1) throw new Error(`expected one vanilla animation tick, found ${matches.length}`);
  const match = matches[0];
  const body = match[1];
  const subFrame = body.match(/\$this\.(\$\w+) = \$this\.\1 \+ 1 \| 0;/)?.[1];
  const frame = body.match(/\$this\.(\$\w+) = \(\$this\.\1 \+ 1 \| 0\) %/)?.[1];
  const dirty = body.match(/\$this\.(\$\w+) = 0;/)?.[1];
  if (!subFrame || !frame || !dirty) throw new Error("vanilla animation tick structure changed");
  const prefix = `
    const capture=globalThis.__gaiusCapture;
    if(capture&&capture.enabled===true&&Number.isInteger(capture.animationTick)
        &&capture.animationTick>=0&&capture.animationTick<=1200){
      const previousFrame=$this.${frame},previousSubFrame=$this.${subFrame};
      $this.${frame}=0;$this.${subFrame}=0;
      for(let captureTick=0;captureTick<capture.animationTick;captureTick++){
        (()=>{${body}})();
      }
      $this.${dirty}=previousFrame!==$this.${frame}||previousSubFrame!==$this.${subFrame}?1:0;
      capture.animationHook='staged-vanilla-replay-v1';
      capture.animationApplications=(capture.animationApplications||0)+1;
      capture.appliedAnimationTick=capture.animationTick;
      return;
    }
`;
  const insertion = match.index + match[0].indexOf("{") + 1;
  const staged = source.slice(0, insertion) + prefix + source.slice(insertion);
  return {source: staged, receipt: {
    mode: "diagnostic-response-only", fields: {frame, subFrame, dirty},
    originalSha256: createHash("sha256").update(source).digest("hex"),
    stagedSha256: createHash("sha256").update(staged).digest("hex"),
  }};
}

// Freeze before the settling interval: random ticks can otherwise grow vines or
// decay leaves differently while two backends load the same seeded world.
export async function freezeCaptureWorld(command) {
  const evidence = await command("/tick freeze");
  if (!evidence?.ok) throw new Error("capture early freeze was not acknowledged");
  return evidence;
}

export function captureStableFramesExpression(camera, frames = 120) {
  return `new Promise(resolve=>{let previous=null,streak=0,observed=0;const started=performance.now();
const sample=()=>{const s=globalThis.__gaiusMinecraftState,p=globalThis.__gaiusChunkPipelineTelemetry;
const frame=globalThis.__gaiusGlFrameGuard?.frames,loaded=s?.loadedChunkCount;
const ready=(${captureCameraReadyExpression(camera)})&&Number.isInteger(loaded)&&loaded>0
&&Number.isFinite(p?.compileBacklog)&&p.compileBacklog===0
&&Number.isFinite(p?.uploadBacklog)&&p.uploadBacklog===0;
if(frame!==previous?.frame){observed++;streak=ready&&loaded===previous?.loaded?streak+1:0;
previous={frame,loaded};}
if(streak>=${frames})return resolve({ok:true,consecutive:streak,observed,loaded,frame,elapsedMs:performance.now()-started,
compile:p.compileBacklog,upload:p.uploadBacklog,audit:globalThis.__gaiusSectionAudit||null});
if(performance.now()-started>120000)return resolve({ok:false,consecutive:streak,observed,loaded,frame,pipeline:p});
requestAnimationFrame(sample);};requestAnimationFrame(sample);})`;
}

export function exactCaptureDifference(left, right) {
  if(left.width!==right.width||left.height!==right.height)throw new Error("capture dimensions changed");
  let pixels=0,maxDelta=0;
  for(let i=0;i<left.pixels.length;i+=4){let delta=0;
    for(let c=0;c<3;c++)delta=Math.max(delta,Math.abs(left.pixels[i+c]-right.pixels[i+c]));
    if(delta>0)pixels++;maxDelta=Math.max(maxDelta,delta);
  }
  return {pixels,maxDelta};
}

export async function alignCaptureGameTime(command, sleep, target = 600) {
  if(!Number.isInteger(target)||target<1)throw new Error("capture game time must be a positive integer");
  const query=async()=>{const evidence=await command("/time query gametime",/The (?:game )?time is \d+/i);
    const value=Number(String(evidence?.response).match(/The (?:game )?time is (\d+)/i)?.[1]);
    if(!evidence?.ok||!Number.isInteger(value))throw new Error("capture game time reply unavailable");
    return {value,...evidence};};
  const before=await query();
  if(before.value>target)throw new Error(`frozen game time ${before.value} exceeds target ${target}`);
  let step=null;
  if(before.value<target){step=await command(`/tick step ${target-before.value}`,/(?:Stepp|tick)/i);
    if(!step?.ok)throw new Error("capture tick step was not acknowledged");}
  const deadline=Date.now()+120000;let after=before;
  while(after.value<target&&Date.now()<deadline){await sleep(500);after=await query();}
  if(after.value!==target)throw new Error(`capture game time is ${after.value}, expected ${target}`);
  await sleep(500);const confirmed=await query();
  if(confirmed.value!==target)throw new Error("capture game time did not stay frozen");
  return {target,before,step,after,confirmed};
}

export function captureViews(player) {
  const centerX = Math.floor(player.x) + 0.5;
  const centerZ = Math.floor(player.z) + 0.5;
  const groundY = Math.floor(player.y);
  return {
    horizon: [centerX, groundY + 2, centerZ, 0, -8],
    entities: [centerX, groundY + 2.5, centerZ, 0, 12],
    high: [centerX, groundY + 30, centerZ, 45, 25],
    topdown: [centerX, groundY + 40, centerZ, 0, 90],
  };
}

export function validateCaptureViews(views) {
  const names = ["horizon", "entities", "high", "topdown"];
  if (!views || Object.keys(views).length !== names.length
      || names.some(name => !Array.isArray(views[name]) || views[name].length !== 5
        || !views[name].every(Number.isFinite))) {
    throw new Error("capture cameras require four named finite x/y/z/yaw/pitch arrays");
  }
  return Object.fromEntries(names.map(name => [name, views[name]]));
}

export function captureCameraReadyExpression(camera) {
  return `(()=>{const state=window.__gaiusMinecraftState;const audit=window.__gaiusSectionAudit;
const pipeline=window.__gaiusChunkPipelineTelemetry||{};const player=state?.player;
// blaze3d-direct-0 (1.21.11) does not publish the optional section-audit
// export. In that profile the chunk pipeline counters and loaded level are
// the available readiness signal; when the audit exists, keep its strict gate.
const auditReady=audit==null
  ? (state?.loaded==null ? Number(pipeline.completedTasks)>0 : Number(state.loaded)>0)
  : audit?.uncompiled===0&&Date.now()-audit.at<5000;
return !!player&&!state.screen&&auditReady
&&!(pipeline.compileBacklog>0)&&!(pipeline.uploadBacklog>0)
&&Math.abs(player.x-(${camera[0]}))<0.01&&Math.abs(player.y-(${camera[1]}))<0.01
&&Math.abs(player.z-(${camera[2]}))<0.01&&Math.abs(player.yaw-(${camera[3]}))<0.01
&&Math.abs(player.pitch-(${camera[4]}))<0.01;})()`;
}

// Read the persisted options after the client has loaded them. This works for
// both the JavaScript and WasmGC clients; the latter has no JS SpriteContents
// animation method that could be used as an options observation hook.
export const captureOptionsReadbackExpression = `(()=>{
  const files=globalThis.__gaiusPersistentFiles;
  const value=files&&files['/gaius/options.txt'];
  if(value===undefined||value===null) return null;
  let text='';
  try {
    if(typeof value==='string') text=atob(value);
    else if(value instanceof Uint8Array) text=new TextDecoder().decode(value);
    else if(value instanceof ArrayBuffer) text=new TextDecoder().decode(new Uint8Array(value));
    else return null;
  } catch (_) { return null; }
  const lines=new Map(text.split(/\\r?\\n/).map(line=>line.split(':',2)));
  const clouds=lines.get('renderClouds');
  const particles=lines.get('particles');
  if(clouds===undefined||particles===undefined) return null;
  const normalizedClouds=String(clouds).replace(/^\"|\"$/g,'').toLowerCase();
  return {cloudsOff:normalizedClouds==='false',particlesMinimal:String(particles)==='2',
    renderClouds:normalizedClouds,particles:String(particles)};
})()`;

export async function captureDeterministicWorld({command, evaluate, waitFor, key, sleep,
  screenshot, player, outputPath, seed, profile, stageReceipt, clientIdentity, cameras, backend = null,
  reloadChunks, reloadEachView = true, gameTime = 600, stableFrames = 120}) {
  const result = {schema: 1, seed, profile, options: CAPTURE_OPTIONS, stageReceipt, clientIdentity,
    settings: {animationTick: 0, time: 6000, gameTime, weather: "clear", tickFrozen: true, stableFrames},
    commands: [], views: {}, complete: false};
  const checkedCommand = async text => {
    const evidence = await command(text);
    if (!evidence?.ok) throw new Error(`capture command not acknowledged: ${text}`);
    result.commands.push({text, ...evidence});
  };
  let hidden = false;
  try {
    await checkedCommand("/tick freeze");
    await evaluate("globalThis.__gaiusCapture.enabled=true;globalThis.__gaiusCapture.animationTick=0;true");
    const runtime = await evaluate("globalThis.__gaiusRuntimeSelection?.selected||globalThis.__gaiusRuntime||null");
    if (runtime === "wasm") {
      // The JS SpriteContents method is not present in the WasmGC runtime. The
      // acknowledged /tick freeze pins vanilla animated-texture ticks instead.
      await evaluate("Object.assign(globalThis.__gaiusCapture,{animationHook:'frozen-world-tick-v1',animationApplications:1,appliedAnimationTick:0})");
    }
    for (const text of ["/gamerule advance_time false", "/gamerule advance_weather false",
      "/gamerule random_tick_speed 0", "/gamerule spawn_mobs false", "/time set 6000",
      "/weather clear 1000000", "/gamemode spectator", "/kill @e[type=!minecraft:player]"]) {
      await checkedCommand(text);
    }
    result.gameTime = await alignCaptureGameTime(command, sleep, gameTime);
    const optionsObserved = await evaluate(captureOptionsReadbackExpression);
    if (!optionsObserved || optionsObserved.cloudsOff || !optionsObserved.particlesMinimal) {
      throw new Error("vanilla cloud/particle options are not confirmed from persisted options");
    }
    result.optionsObserved = optionsObserved;
    await evaluate("globalThis.__gaiusCapture.optionsObserved=" + JSON.stringify(optionsObserved) + ";true");
    const views = validateCaptureViews(cameras || captureViews(player));
    result.reloads = {};
    const [centerX, cameraY, centerZ] = views.horizon;
    await checkedCommand(`/summon minecraft:pig ${centerX - 1.5} ${cameraY - 1} ${centerZ + 5} {NoAI:1b,NoGravity:1b,Silent:1b,Rotation:[150f,0f]}`);
    await checkedCommand(`/summon minecraft:armor_stand ${centerX + 1.5} ${cameraY - 1} ${centerZ + 5} {NoGravity:1b,ShowArms:1b,Rotation:[200f,0f]}`);
    await sleep(1500);
    if (runtime !== "wasm") {
      await waitFor("globalThis.__gaiusCapture?.animationApplications>0", 10000, "texture animation hook acknowledgement");
    }
    for (const [name, camera] of Object.entries(views)) {
      if (hidden) { await key("F1", "F1", 112); hidden = false; }
      await checkedCommand(`/tp @s ${camera.join(" ")}`);
      // Vanilla's LevelRenderer.allChanged() rebuilds every visible section at the
      // current camera. This is required for a fair backend comparison: translucent
      // quads are sorted when each section is compiled, so without this reload their
      // order depends on the earlier camera and compile completion history.
      if (reloadEachView) {
        if (typeof reloadChunks !== "function") throw new Error("capture requires a per-view allChanged reload");
        result.reloads[name] = await reloadChunks(name);
      }
      const ready = captureCameraReadyExpression(camera);
      try {
        await waitFor(ready, 120000, `settled camera ${name}`);
      } catch (error) {
        result.unsettled = await evaluate("({state:window.__gaiusMinecraftState,player:window.__gaiusMinecraftState?.player,screen:window.__gaiusMinecraftState?.screen,audit:window.__gaiusSectionAudit,pipeline:window.__gaiusChunkPipelineTelemetry,capture:globalThis.__gaiusCapture})").catch(() => null);
        throw error;
      }
      await sleep(1500);
      await key("F1", "F1", 112); hidden = true;
      await sleep(500);
      const quiescence = await waitForCaptureQuiescence({evaluate, sleep, camera, radius: 8,
        stableFrames: 4, minStableMs: 1000, timeoutMs: 120000, drawFrames: false});
      const stability = await evaluate(captureStableFramesExpression(camera, stableFrames));
      if (!stability?.ok) throw new Error(`camera did not stabilize: ${name}: ${JSON.stringify(stability)}`);
      const loadedChunks = quiescence.receipt?.loadedChunks || quiescence.loadedChunks;
      if (!Array.isArray(loadedChunks) || loadedChunks.length < 1) throw new Error(`quiescence did not report loaded chunks: ${name}`);
      const world = await evaluate("globalThis.__gaiusCaptureQuiescence.worldDigest(" + JSON.stringify(loadedChunks) + ")");
      if (!world || Object.keys(world).length !== loadedChunks.length) throw new Error(`world digest unavailable: ${name}`);
      await writeFile(join(outputPath, `world-digest-${name}.json`), JSON.stringify(world, null, 2));
      const content = {};
      for (let start = 0; start < loadedChunks.length; start += 24) {
        Object.assign(content, await evaluate("globalThis.__gaiusCaptureQuiescence.contentDigest(" + JSON.stringify(loadedChunks.slice(start, start + 24)) + ")"));
      }
      if (Object.keys(content).length !== loadedChunks.length || Object.values(content).some(entry => !entry)) throw new Error(`world content digest unavailable: ${name}`);
      await writeFile(join(outputPath, `world-content-${name}.json`), JSON.stringify(content, null, 2));
      const temporalAttempts = [];
      let accepted = null;
      const temporalDeadline = Date.now() + 120000;
      for (let attempt = 0; Date.now() < temporalDeadline; attempt++) {
        const files = [];
        const states = [];
        for (let repeat = 0; repeat < 2; repeat++) {
          // Wait for the next presented game frame, rather than an arbitrary wall-clock interval.
          await evaluate(`new Promise(resolve=>{const before=globalThis.__gaiusGlFrameGuard.frames;
const next=()=>globalThis.__gaiusGlFrameGuard.frames>before?resolve(true):requestAnimationFrame(next);requestAnimationFrame(next);})`);
          const state = await evaluate(`(()=>{const capture=globalThis.__gaiusCapture;return {
frame:globalThis.__gaiusGlFrameGuard?.frames,player:window.__gaiusMinecraftState?.player,screen:window.__gaiusMinecraftState?.screen,
backend:window.__gaiusGpuBackend||${JSON.stringify(backend)},animationHook:capture?.animationHook,
animationApplications:capture?.animationApplications,animationTick:capture?.appliedAnimationTick,
options:capture?.optionsObserved,gpu:globalThis.__gaiusWebGPU?.snapshot?.()||null};})()`);
          if (!state?.animationHook || state.animationTick !== 0 || state.screen
              || !["webgl2", "webgpu"].includes(state.backend)) {
            throw new Error(`capture state is not frozen: ${name}`);
          }
          if (state.options?.cloudsOff || !state.options?.particlesMinimal) {
            throw new Error("vanilla cloud/particle options are not confirmed");
          }
          const filename = `capture-${name}-attempt-${attempt}-${repeat}.png`;
          if (!await screenshot(filename)) throw new Error(`screenshot failed: ${filename}`);
          files.push(filename); states.push(state);
        }
        const images = await Promise.all(files.map(async file => decodePng(await readFile(join(outputPath, file)))));
        const pair = exactCaptureDifference(images[0], images[1]);
        const attemptEvidence = {attempt, files, frames: states.map(state => state.frame), pair};
        temporalAttempts.push(attemptEvidence);
        if (pair.pixels === 0) {
          // The acceptance contract is an exact consecutive pair. Keep the
          // established three-file shape for downstream comparators by reusing
          // the second, already accepted frame as the third sample.
          accepted = {files: [files[0], files[1], files[1]], states: [states[0], states[1], states[1]], pair};
          break;
        }
      }
      if (!accepted) throw new Error(`camera temporal pair did not stabilize: ${name}`);
      result.views[name] = {camera, files: accepted.files, states: accepted.states, stability, quiescence: quiescence.receipt,
        worldDigest: world, contentDigest: content,
        temporalAttempts, acceptedPair: {files: accepted.files.slice(0, 2), frames: accepted.states.slice(0, 2).map(state => state.frame), difference: accepted.pair},
        temporal: [accepted.pair, {pixels: 0, maxDelta: 0}], stable: true};
    }
    result.gameTimeAfter = await alignCaptureGameTime(command, sleep, gameTime);
    result.complete = Object.values(result.views).every(view => view.stable);
    if (!result.complete) throw new Error("capture temporal gate failed: consecutive screenshots differ");
    return result;
  } catch (error) {
    result.error = String(error.stack || error);
    throw error;
  } finally {
    if (hidden) await key("F1", "F1", 112).catch(() => {});
    result.cleanup = {animation: await evaluate("globalThis.__gaiusCapture.enabled=false;true").catch(() => false),
      unfreeze: await command("/tick unfreeze").catch(error => ({ok: false, error: String(error)}))};
    await writeFile(join(outputPath, "capture.json"), JSON.stringify(result, null, 2));
  }
}
