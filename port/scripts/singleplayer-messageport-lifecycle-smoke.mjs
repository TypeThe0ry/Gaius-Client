import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {MessageChannel} from "node:worker_threads";
import vm from "node:vm";

const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");
function extractJsBody(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf('@JSBody(script = """', markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `Browser JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}

const bridgeScript = "{\n" + extractJsBody("private static native void initBridge();") + "\n}\n{\n" +
  extractJsBody("private static native void initBridgeTail();") + "\n}";
const outboundSchedulerScript = extractJsBody("private static native void initOutboundScheduler();");
const inboundSchedulerScript = extractJsBody("private static native void initInboundScheduler();");

const sessionA = "0123456789abcdef0123456789abcdef";
const sessionB = "1123456789abcdef0123456789abcdef";
const sessionC = "2123456789abcdef0123456789abcdef";
const sessionD = "3123456789abcdef0123456789abcdef";

function createRuntime() {
  const context = {
    AbortController,
    Array,
    ArrayBuffer,
    Boolean,
    Date,
    Int8Array,
    JSON,
    Map,
    Math,
    Number,
    Object,
    Promise,
    RegExp,
    Set,
    String,
    TextDecoder,
    TextEncoder,
    URL,
    URLSearchParams,
    Uint8Array,
    clearTimeout,
    console,
    fetch: async () => {
      throw new Error("Portable local sessions must not fetch a relay");
    },
    localStorage: {getItem: () => null},
    location: {
      href: "file:///Downloads/Gaius.html",
      hostname: "",
      protocol: "file:",
      search: "",
    },
    performance,
    queueMicrotask,
    setTimeout,
    WebSocket: class UnexpectedWebSocket {
      constructor() {
        throw new Error("Portable local sessions must not open a WebSocket");
      }
    },
    __gaiusLocalPortClaimTimeoutMs: 80,
    __gaiusLocalServerPorts: new Map(),
    __gaiusSingleplayerWorkers: new Map(),
  };
  context.globalThis = context;
  context.window = context;
  vm.runInNewContext(`(function() {${bridgeScript}\n})();`, context);
  vm.runInNewContext(`(function() {${outboundSchedulerScript}\n})();`, context);
  // Match the channel constructor: failed/superseded local claims retire via
  // the inbound scheduler, just like remote channels. Do not stub retirement.
  vm.runInNewContext(`(function() {${inboundSchedulerScript}\n})();`, context);
  return context;
}

function installWorker(runtime, sessionId, launchGeneration = "1", clientPort = null) {
  const worker = {
    __gaiusLaunchGeneration: launchGeneration,
    __gaiusClientPort: clientPort,
    __gaiusClientAttached: false,
    __gaiusTerminal: false,
  };
  runtime.__gaiusSingleplayerWorkers.set(sessionId, worker);
  return worker;
}

async function waitFor(predicate, label, timeoutMillis = 500) {
  const deadline = Date.now() + timeoutMillis;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
}

function localHost(sessionId) {
  return `client-${sessionId}.gaius-local`;
}

{
  const runtime = createRuntime();
  const {port1, port2} = new MessageChannel();
  installWorker(runtime, sessionA, "1", port1);
  port1.__gaiusLaunchGeneration = "1";
  runtime.__gaiusLocalServerPorts.set(sessionA, port1);
  runtime.__gaiusNettyBridge.open(1, localHost(sessionA), 25565);
  assert.equal(runtime.__gaiusNetworkStats.localOpened, 1, "first claim did not connect");
  assert.equal(runtime.__gaiusNettyBridge.pollError(1), null);

  runtime.__gaiusNettyBridge.open(1, localHost(sessionA), 25565);
  assert.equal(runtime.__gaiusNetworkStats.localDuplicateOpens, 1,
    "same-socket duplicate open was not idempotent");
  assert.equal(runtime.__gaiusNetworkStats.localOpened, 1,
    "same-socket duplicate open consumed a second port");

  const inbound = new Uint8Array([1, 2, 3, 4]);
  port2.postMessage(inbound.buffer, [inbound.buffer]);
  await waitFor(() => runtime.__gaiusNettyBridge.hasPendingInbound(1), "local inbound");
  assert.deepEqual(Array.from(runtime.__gaiusNettyBridge.pollInbound(1)), [1, 2, 3, 4]);
  runtime.__gaiusNettyBridge.close(1);
  port2.close();
}

{
  const runtime = createRuntime();
  installWorker(runtime, sessionB, "1");
  runtime.__gaiusNettyBridge.open(2, localHost(sessionB), 25565);
  assert.equal(runtime.__gaiusNetworkStats.localClaimWaits, 1,
    "missing port did not enter the bounded claim wait");
  const {port1, port2} = new MessageChannel();
  port1.__gaiusLaunchGeneration = "1";
  runtime.__gaiusSingleplayerWorkers.get(sessionB).__gaiusClientPort = port1;
  setTimeout(() => runtime.__gaiusLocalServerPorts.set(sessionB, port1), 20);
  // Reproduce an overloaded CI runner: both the 8 ms retry and 20 ms
  // registration timer become overdue, but the retry was queued first. The
  // bridge must let the already-due registration run before its final timeout
  // decision rather than retiring a port that was produced within the window.
  const starvationUntil = Date.now() + 120;
  while (Date.now() < starvationUntil) {}
  await waitFor(() => runtime.__gaiusNetworkStats.localOpened === 1, "late port claim");
  assert.equal(runtime.__gaiusNetworkStats.localClaimTimeouts, 0,
    "overdue retry timed out before the already-due registration ran");
  assert.equal(runtime.__gaiusNettyBridge.pollError(2), null,
    "late port registration produced a transport error");
  runtime.__gaiusNettyBridge.close(2);
  port2.close();
}

{
  const runtime = createRuntime();
  installWorker(runtime, sessionC, "1");
  runtime.__gaiusNettyBridge.open(3, localHost(sessionC), 25565);
  runtime.__gaiusNettyBridge.open(3, localHost(sessionC), 25565);
  const {port1, port2} = new MessageChannel();
  port1.__gaiusLaunchGeneration = "1";
  runtime.__gaiusSingleplayerWorkers.get(sessionC).__gaiusClientPort = port1;
  runtime.__gaiusLocalServerPorts.set(sessionC, port1);
  await waitFor(() => runtime.__gaiusNetworkStats.localOpened === 1,
    "duplicate connect retry claim");
  assert.equal(runtime.__gaiusNettyBridge.pollError(3), null);
  runtime.__gaiusNettyBridge.close(3);
  port2.close();
}

{
  const runtime = createRuntime();
  const localWorker = {
    __gaiusLaunchGeneration: "1",
    __gaiusClientPort: null,
    __gaiusClientAttached: false,
    __gaiusTerminal: false,
  };
  runtime.__gaiusSingleplayerWorkers = new Map([[sessionD, localWorker]]);
  runtime.__gaiusNettyBridge.open(4, localHost(sessionD), 25565);
  runtime.__gaiusNettyBridge.open(5, localHost(sessionD), 25565);
  const superseded = runtime.__gaiusNettyBridge.pollError(4);
  assert.match(superseded, /superseded/,
    "new retry did not retire the older pending claim");
  const {port1, port2} = new MessageChannel();
  port1.__gaiusLaunchGeneration = "1";
  localWorker.__gaiusClientPort = port1;
  runtime.__gaiusLocalServerPorts.set(sessionD, port1);
  await waitFor(() => runtime.__gaiusNetworkStats.localOpened === 1,
    "superseding connect claim");
  assert.equal(runtime.__gaiusNettyBridge.pollError(5), null);
  runtime.__gaiusNettyBridge.failLocalSession(sessionD, "worker stopped", "1");
  assert.equal(runtime.__gaiusNettyBridge.pollError(5), "worker stopped");
  port2.close();
}

{
  const runtime = createRuntime();
  const timeoutSession = "4123456789abcdef0123456789abcdef";
  installWorker(runtime, timeoutSession, "1");
  runtime.__gaiusNettyBridge.open(6, localHost(timeoutSession), 25565);
  // Retirement may run before waitFor's next timer. Retain the actual entry
  // to inspect its diagnostic without racing removal from the channel map.
  const pendingEntry = runtime.__gaiusNettyBridge.channels.get(6);
  await waitFor(() => runtime.__gaiusNetworkStats.localClaimTimeouts === 1,
    "missing port timeout");
  const error = pendingEntry.errors[0];
  assert.match(error, /did not register within 80 ms/);
  assert.doesNotMatch(error, /is unavailable/,
    "legacy immediate MessagePort failure remained active");
  await waitFor(() => !runtime.__gaiusNettyBridge.channels.has(6),
    "timed-out claim retirement");
  assert.equal(pendingEntry.disposed, true,
    "timed-out local claim retained a live transport entry");
}

{
  // A local bridge entry captures generation 1 while it waits.  Replacing the
  // same session key with generation 2 must not let the old entry consume P2
  // or mutate the new Worker/handoff; a fresh generation-2 entry still claims P2.
  const runtime = createRuntime();
  const generationSession = "4123456789abcdef0123456789abcdef";
  const oldChannel = new MessageChannel();
  oldChannel.port1.__gaiusLaunchGeneration = "1";
  const oldWorker = {
    __gaiusLaunchGeneration: "1",
    __gaiusClientPort: oldChannel.port1,
    __gaiusClientAttached: false,
    __gaiusTerminal: false,
  };
  runtime.__gaiusSingleplayerWorkers = new Map([[generationSession, oldWorker]]);
  runtime.__gaiusSingleplayerHandoff = generationSession;
  runtime.__gaiusSingleplayerHandoffGeneration = "1";
  runtime.__gaiusNettyBridge.open(7, localHost(generationSession), 25565);
  const staleEntry = runtime.__gaiusNettyBridge.channels.get(7);

  const newChannel = new MessageChannel();
  newChannel.port1.__gaiusLaunchGeneration = "2";
  const newWorker = {
    __gaiusLaunchGeneration: "2",
    __gaiusClientPort: newChannel.port1,
    __gaiusClientAttached: false,
    __gaiusTerminal: false,
  };
  runtime.__gaiusSingleplayerWorkers.set(generationSession, newWorker);
  runtime.__gaiusLocalServerPorts.set(generationSession, newChannel.port1);
  runtime.__gaiusSingleplayerHandoff = generationSession;
  runtime.__gaiusSingleplayerHandoffGeneration = "2";
  await waitFor(() => runtime.__gaiusNetworkStats.errors > 0,
    "stale generation attach rejection");
  assert.match(staleEntry.errors[0], /generation changed/,
    "stale local entry did not fail on generation mismatch");
  assert.equal(runtime.__gaiusSingleplayerWorkers.get(generationSession), newWorker,
    "stale local entry replaced the new Worker map value");
  assert.equal(newWorker.__gaiusClientAttached, false,
    "stale local entry marked the new Worker attached");
  assert.equal(runtime.__gaiusSingleplayerHandoffGeneration, "2",
    "stale local entry cleared the new handoff generation");
  assert.equal(runtime.__gaiusLocalServerPorts.get(generationSession), newChannel.port1,
    "stale local entry consumed the new Worker port");

  runtime.__gaiusNettyBridge.open(8, localHost(generationSession), 25565);
  assert.equal(newWorker.__gaiusClientAttached, true,
    "current generation did not attach its own local port");
  assert.equal(runtime.__gaiusSingleplayerHandoff, "",
    "current generation did not clear its own handoff");
  assert.equal(runtime.__gaiusSingleplayerHandoffGeneration, "",
    "current generation did not clear its own handoff generation");
  runtime.__gaiusNettyBridge.close(8);
  oldChannel.port2.close();
  newChannel.port2.close();
}

{
  // Strict metadata is fail-closed: a Worker with no generation, a port with
  // no generation, and a synchronous generation mismatch must not consume P2.
  const missingWorkerGeneration = createRuntime();
  const missingWorkerPort = new MessageChannel();
  missingWorkerPort.port1.__gaiusLaunchGeneration = "1";
  missingWorkerGeneration.__gaiusSingleplayerWorkers = new Map([[
    sessionA,
    {
      __gaiusLaunchGeneration: "",
      __gaiusClientPort: missingWorkerPort.port1,
      __gaiusTerminal: false,
    },
  ]]);
  missingWorkerGeneration.__gaiusLocalServerPorts.set(sessionA, missingWorkerPort.port1);
  missingWorkerGeneration.__gaiusNettyBridge.open(11, localHost(sessionA), 25565);
  assert.match(missingWorkerGeneration.__gaiusNettyBridge.pollError(11), /generation/,
    "missing Worker generation did not fail closed");
  assert.equal(missingWorkerGeneration.__gaiusLocalServerPorts.get(sessionA),
    missingWorkerPort.port1,
    "missing Worker generation consumed the legal port");

  const missingPortMetadata = createRuntime();
  const missingPortWorkerPort = new MessageChannel();
  const missingPortWorker = {
    __gaiusLaunchGeneration: "1",
    __gaiusClientPort: missingPortWorkerPort.port1,
    __gaiusTerminal: false,
  };
  missingPortMetadata.__gaiusSingleplayerWorkers = new Map([[sessionB, missingPortWorker]]);
  missingPortMetadata.__gaiusLocalServerPorts.set(sessionB, missingPortWorkerPort.port1);
  missingPortMetadata.__gaiusNettyBridge.open(12, localHost(sessionB), 25565);
  assert.match(missingPortMetadata.__gaiusNettyBridge.pollError(12), /generation/,
    "missing port metadata did not fail closed");
  assert.equal(missingPortMetadata.__gaiusLocalServerPorts.get(sessionB),
    missingPortWorkerPort.port1,
    "missing port metadata consumed the legal port");

  const strictRegistration = createRuntime();
  const unboundPort = new MessageChannel().port1;
  assert.equal(strictRegistration.__gaiusNettyBridge.registerLocalPort(sessionC, unboundPort, ""),
    false,
    "empty local-port registration was accepted");
  assert.equal(strictRegistration.__gaiusLocalServerPorts.has(sessionC), false,
    "empty local-port registration polluted the port map");
  const metadataOnlyPort = new MessageChannel().port1;
  metadataOnlyPort.__gaiusLaunchGeneration = "1";
  assert.equal(strictRegistration.__gaiusNettyBridge.registerLocalPort(
    sessionC, metadataOnlyPort, ""
  ), false, "empty generation reused port metadata");
  assert.equal(strictRegistration.__gaiusLocalServerPorts.has(sessionC), false,
    "empty generation with metadata polluted the port map");
  assert.equal(strictRegistration.__gaiusNettyBridge.failLocalSession(sessionC, "stale", ""),
    false,
    "empty failLocalSession generation wildcarded another generation");
  const frozenPort = new MessageChannel().port1;
  Object.freeze(frozenPort);
  assert.equal(strictRegistration.__gaiusNettyBridge.registerLocalPort(
    sessionC, frozenPort, "1"
  ), false, "unwritable local-port metadata was accepted");
  assert.equal(strictRegistration.__gaiusLocalServerPorts.has(sessionC), false,
    "unwritable local-port metadata polluted the port map");

  const missingActiveMetadata = createRuntime();
  const missingActiveChannel = new MessageChannel();
  const missingActiveWorker = installWorker(
    missingActiveMetadata, sessionC, "2", missingActiveChannel.port1
  );
  missingActiveMetadata.__gaiusLocalServerPorts.set(sessionC, missingActiveChannel.port1);
  assert.equal(missingActiveMetadata.__gaiusNettyBridge.registerLocalPort(
    sessionC, missingActiveChannel.port1, "2"
  ), false, "active owner without port metadata was accepted");
  assert.equal(missingActiveMetadata.__gaiusLocalServerPorts.get(sessionC),
    missingActiveChannel.port1, "missing metadata changed the active port map");
  assert.equal(missingActiveWorker.__gaiusClientAttached, false,
    "missing metadata marked the active Worker attached");

  const frozenActiveMetadata = createRuntime();
  const frozenActiveChannel = new MessageChannel();
  frozenActiveChannel.port1.__gaiusLaunchGeneration = "2";
  Object.freeze(frozenActiveChannel.port1);
  const frozenActiveWorker = installWorker(
    frozenActiveMetadata, sessionD, "2", frozenActiveChannel.port1
  );
  frozenActiveMetadata.__gaiusLocalServerPorts.set(sessionD, frozenActiveChannel.port1);
  assert.equal(frozenActiveMetadata.__gaiusNettyBridge.registerLocalPort(
    sessionD, frozenActiveChannel.port1, "2"
  ), false, "frozen correctly tagged port was accepted");
  assert.equal(frozenActiveMetadata.__gaiusLocalServerPorts.get(sessionD),
    frozenActiveChannel.port1, "frozen metadata changed the active port map");
  assert.equal(frozenActiveWorker.__gaiusClientAttached, false,
    "frozen metadata marked the active Worker attached");
  missingActiveChannel.port2.close();
  frozenActiveChannel.port2.close();

  const staleRegistration = createRuntime();
  const staleSession = "5123456789abcdef0123456789abcdef";
  const staleChannel = new MessageChannel();
  const currentChannel = new MessageChannel();
  staleChannel.port1.__gaiusLaunchGeneration = "1";
  currentChannel.port1.__gaiusLaunchGeneration = "2";
  const currentWorker = installWorker(
    staleRegistration, staleSession, "2", currentChannel.port1
  );
  staleRegistration.__gaiusLocalServerPorts.set(staleSession, currentChannel.port1);
  assert.equal(staleRegistration.__gaiusNettyBridge.registerLocalPort(
    staleSession, staleChannel.port1, "1"
  ), false, "stale registration replaced the active P2 port");
  assert.equal(staleRegistration.__gaiusLocalServerPorts.get(staleSession),
    currentChannel.port1, "stale registration removed the active P2 port");
  assert.equal(currentWorker.__gaiusClientAttached, false,
    "stale registration marked the active Worker attached");
  assert.equal(staleRegistration.__gaiusNettyBridge.registerLocalPort(
    staleSession, currentChannel.port1, "2"
  ), true, "current generation registration was rejected");
  staleChannel.port2.close();
  currentChannel.port2.close();

  const synchronousMismatch = createRuntime();
  const synchronousPort = new MessageChannel();
  synchronousPort.port1.__gaiusLaunchGeneration = "2";
  synchronousMismatch.__gaiusSingleplayerWorkers = new Map([[sessionD, {
    __gaiusLaunchGeneration: "1",
    __gaiusClientPort: synchronousPort.port1,
    __gaiusTerminal: false,
  }]]);
  synchronousMismatch.__gaiusLocalServerPorts.set(sessionD, synchronousPort.port1);
  synchronousMismatch.__gaiusNettyBridge.open(13, localHost(sessionD), 25565);
  assert.match(synchronousMismatch.__gaiusNettyBridge.pollError(13), /generation changed/,
    "synchronous generation mismatch did not fail closed");
  assert.equal(synchronousMismatch.__gaiusLocalServerPorts.get(sessionD),
    synchronousPort.port1,
    "synchronous generation mismatch consumed P2");
  for (const channel of [missingWorkerPort, missingPortWorkerPort, synchronousPort]) {
    channel.port2.close();
  }
}

console.log(JSON.stringify({
  ok: true,
  scenarios: [
    "first-connect",
    "same-socket-duplicate",
    "late-registration",
    "retry-supersede",
    "worker-stop",
    "generation-isolation",
    "bounded-timeout",
    "portable-file-origin",
  ],
}));
