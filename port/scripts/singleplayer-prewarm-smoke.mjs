import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const root = new URL("..", import.meta.url);
const worker = await readFile(new URL("web/singleplayer/server-worker-bootstrap.js", root), "utf8");
const client = await readFile(new URL("src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java", root), "utf8");
const boot = await readFile(new URL("web/boot/gaius-boot.js", root), "utf8");
const serverMain = await readFile(new URL(
  "src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java", root), "utf8");
const wasmWorker = await readFile(new URL(
  "web/runtime/wasmgc/gaius-wasm-worker.js", root), "utf8");

assert.match(worker, /message\.type === "prewarm"/,
  "Worker bootstrap does not accept the prewarm handshake");
assert.match(worker, /prewarmPromise/,
  "Worker bootstrap does not serialize prewarm and start");
assert.match(worker, /prewarmScript\s*\?/,
  "start path does not reuse the prewarmed runtime asset");
assert.match(worker, /const assetReady = prewarmPromise/,
  "start path does not overlap storage setup with prewarm");
assert.doesNotMatch(worker.slice(worker.indexOf('const startupStarted')), /await prewarmPromise/,
  "start path serializes storage behind the optional prewarm");
assert.match(worker, /type: "server-prewarm-ready"/,
  "Worker bootstrap does not publish prewarm readiness");
assert.match(worker, /worldLoader: false/,
  "prewarm readiness must state that world loading has not run");
assert.match(worker, /datapacks: false/,
  "prewarm readiness must not claim datapacks were loaded");
assert.match(worker, /if \(!prewarmRuntimeInstantiated\)/,
  "Wasm start path can instantiate a prewarmed runtime twice");
assert.match(worker, /if \(!prewarmRuntimeImported\) importScripts/,
  "JS start path can import a prewarmed runtime twice");
assert.match(worker, /prewarmRuntimeImported = false/,
  "failed warm-up must invalidate imported runtime state");
assert.match(worker, /prewarmRuntimeInstantiated = false/,
  "failed warm-up must invalidate instantiated runtime state");
assert.match(worker, /prewarmRuntimeFailed = true/,
  "failed warm-up must force the fresh-runtime fallback");
assert.match(worker, /prewarmSucceeded && !prewarmRuntimeFailed && prewarmScript/,
  "start path must only reuse a successful warm-up runtime");
assert.match(worker, /: prepareFreshAsset\(\)/,
  "failed warm-up must prepare a fresh runtime asset");
assert.match(worker, /releaseFailedRuntime\(\)/,
  "failed warm-up must release the old Wasm/JSPI runtime");
assert.match(worker, /runtimeReleaseInProgress/,
  "runtime teardown must be guarded against duplicate release");
assert.match(worker, /__gaiusJspiScheduler[\s\S]*\.close\(\)/,
  "runtime teardown must cancel JSPI scheduler callbacks");
assert.match(worker, /tornDown/,
  "runtime teardown must call only one mutually exclusive runtime finalizer");
assert.match(wasmWorker, /function terminate\(\)/,
  "Wasm worker must expose an idempotent runtime finalizer");
assert.match(wasmWorker, /jspi[\s\S]*\.close\(\)/,
  "Wasm worker finalizer must cancel pending JSPI continuations");
assert.match(worker, /__gaiusServerSeed = String\(message\.worldSeed \|\| message\.seed \|\| ""\)/,
  "start handoff must publish the client WorldStem seed in the world-seed event");
assert.match(client, /worldSeed: String\(worldSeed\)/,
  "start handoff must carry the client WorldStem seed");

// releaseFailedRuntime: exactly one finalizer, idempotent until a new runtime lifecycle starts.
{
  const start = worker.indexOf("function releaseFailedRuntime()");
  const end = worker.indexOf("\nasync function prewarmServerRuntime", start);
  assert.ok(start >= 0 && end > start, "releaseFailedRuntime is missing");
  const calls = [];
  const context = {
    __gaiusJspiScheduler: {close() { calls.push("scheduler.close"); }},
    GaiusWasmWorker: {
      terminate() { calls.push("terminate"); },
      close() { calls.push("close"); },
      reset() { calls.push("reset"); },
    },
    TeaVM: {}, GaiusJspi: {}, main() {}, __gaiusStartIntegratedServerPump() {},
  };
  context.root = context;
  vm.createContext(context);
  vm.runInContext("let runtimeReleaseInProgress = false;\n" + worker.slice(start, end) +
    "\nglobalThis.release = releaseFailedRuntime;" +
    "\nglobalThis.newLifecycle = () => { runtimeReleaseInProgress = false; " +
    "root.__gaiusServerRuntimeReleased = false; };", context);
  assert.equal(context.release(), true, "first release did not tear the runtime down");
  assert.deepEqual(calls, ["terminate"], "release must call exactly one runtime finalizer");
  assert.equal(context.release(), false, "second release must be a no-op");
  assert.deepEqual(calls, ["terminate"], "duplicate release called a finalizer again");
  for (const name of ["GaiusWasmWorker", "TeaVM", "GaiusJspi", "__gaiusJspiScheduler",
    "main", "__gaiusStartIntegratedServerPump"]) {
    assert.equal(context[name], undefined, name + " survived the runtime release");
  }
  context.newLifecycle();
  context.__gaiusJspiScheduler = {close() { calls.push("scheduler.close"); }};
  assert.equal(context.release(), false, "release without a runtime finalizer reports no teardown");
  assert.deepEqual(calls, ["terminate", "scheduler.close"],
    "older runtimes must fall back to closing the JSPI scheduler");
}

const prewarmBlockStart = worker.indexOf("async function prewarmServerRuntime");
const prewarmBlockEnd = worker.indexOf("\nroot.onmessage", prewarmBlockStart);
assert.ok(prewarmBlockStart >= 0 && prewarmBlockEnd > prewarmBlockStart,
  "prewarm implementation block is missing");
const prewarmBlock = worker.slice(prewarmBlockStart, prewarmBlockEnd);
assert.doesNotMatch(prewarmBlock, /startMain\s*\(/,
  "prewarm must not dispatch Minecraft Main before the start handshake");
assert.doesNotMatch(prewarmBlock, /__gaiusServerClientPort/,
  "prewarm must not claim the client MessagePort");

const prewarmSend = client.indexOf("const prewarm = Object.assign({}, init, {type: 'prewarm'})");
const startSend = client.indexOf("worker.postMessage(init, [channel.port2])");
assert.ok(prewarmSend >= 0 && startSend > prewarmSend,
  "client must queue prewarm before the transferred start port");
assert.match(client.slice(prewarmSend, startSend), /delete prewarm\.port/,
  "prewarm must not transfer the client port");
assert.match(boot, /message\.type === "start" \|\| message\.type === "prewarm"/,
  "page bootstrap does not inject the compiled server module into prewarm");
assert.match(client, /__gaiusIdleSingleplayerWorker/,
  "click path must consume the title-screen idle Worker");
assert.match(boot, /createIdleSingleplayerWorker/,
  "title screen must create the long-lived idle Worker");
assert.match(boot, /__gaiusCancelIdleSingleplayerWorker/,
  "multiplayer transition must have an idle Worker cancellation hook");
assert.match(boot, /singleplayer-worker-idle-timeout/,
  "idle Worker must have a bounded lifetime");
assert.match(boot, /__gaiusMultiplayerOnly === true/,
  "multiplayer-only pages must skip idle Worker creation");
assert.match(boot, /isPortable\(\)/,
  "idle Worker policy must handle portable pages explicitly");
assert.match(boot, /gaius-worker-bootstrap/,
  "portable idle Worker must use the embedded file-payload loader");
assert.match(boot, /filePayload\(name\)/,
  "portable idle Worker must load embedded server payloads");
assert.match(boot, /new global\.Worker\(String\(workerUrl\)/,
  "portable idle Worker must use the Blob URL built from embedded payloads");
assert.doesNotMatch(boot, /new global\.Worker\(String\(configured\)/,
  "portable idle Worker must not ignore its constructed Blob URL");
assert.doesNotMatch(boot, /function createIdleSingleplayerWorker\(\) \{\n\s*if \(isPortable\(\)/,
  "portable pages must not be unconditionally excluded from idle prewarm");
assert.doesNotMatch(serverMain, /prewarmBrowserServerBootstrap|Bootstrap\.bootStrap\(\)|Bootstrap\.validate\(\)/,
  "Bootstrap may only run inside vanilla Main, never from a separate server entry point");
// Bootstrap during a prewarm only ever runs inside vanilla Main itself (parked Main): the
// prewarm reports whether that Main was dispatched, never calls a Java Bootstrap entry point, and
// dispatches Main only for an explicit idle request before any start, on a runtime that has the
// parking hook.
assert.match(prewarmBlock, /const bootstrap = dispatchParkedMain\(message, script\)/,
  "prewarm readiness must report whether the parked vanilla Main was dispatched");
{
  const start = worker.indexOf("function dispatchParkedMain(");
  const end = worker.indexOf("\nfunction parkedMainFailed", start);
  assert.ok(start >= 0 && end > start, "dispatchParkedMain is missing");
  const dispatch = worker.slice(start, end);
  assert.match(dispatch, /message\.parkMain !== true \|\| startAccepted \|\| stopRequested/,
    "parked Main must need an explicit idle request and no accepted start");
  assert.match(dispatch, /integratedServerParkSupported/,
    "parked Main must need the Java parking entry point");
  assert.doesNotMatch(dispatch, /__gaiusServerClientPort|__gaiusServerWorldId|configureStorage|installPersistentFileSystem/,
    "parked Main must not get the client port, the world or storage");
}
const startDispatch = worker.slice(worker.indexOf("if (parkedMainDispatched) {"));
assert.match(startDispatch.slice(0, 600), /__gaiusServerParkReleased = true/,
  "start must release the parked Main instead of dispatching a second one");
assert.match(worker, /parkedMainFailed\(\) \? discardFailedParkedRuntime\(\) : prewarmScript/,
  "a failed parked Main must force a fresh runtime");
{
  const mainBody = serverMain.slice(serverMain.indexOf("public static void main(String[] args)"));
  const park = mainBody.indexOf("parkedStart = true;");
  const unavailable = mainBody.indexOf("markParkedStartUnavailable();");
  assert.ok(unavailable > 0 && unavailable < park,
    "main must report an unavailable park before it runs a parked vanilla Main");
}
assert.match(worker, /__gaiusServerParkedState === "unavailable"/,
  "the Worker bootstrap must fall back to the compile-only prewarm when Java cannot park");
assert.match(serverMain, /public static OptionSet resumeAfterBootstrap\(OptionParser parser, OptionSet options\)/,
  "vanilla Main must park through resumeAfterBootstrap");
{
  const resume = serverMain.slice(serverMain.indexOf("public static OptionSet resumeAfterBootstrap"));
  const wait = resume.indexOf("while (!parkedStartReleased())");
  const prepare = resume.indexOf("prepareWorldStart();");
  const guard = resume.indexOf("if (!canParkMainThread())");
  assert.ok(guard > 0 && guard < wait,
    "a parked Main must not poll the release where its yield cannot suspend (busy wait)");
  assert.ok(wait > 0 && prepare > wait,
    "a parked Main must prepare storage and the world only after the start release");
  assert.match(resume.slice(0, prepare + 200), /parser\.parse\(serverArguments\(workerWorldId\(\)\)\)/,
    "a parked Main must re-parse the real world arguments with vanilla's parser");
}

console.log("singleplayer prewarm smoke passed");
