import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {resolve} from "node:path";
import {createServer} from "node:http";
import {runInNewContext} from "node:vm";
import {extractJsBody, loadBridgeSmokeSource} from "./browser-bridge-smoke-source.mjs";

const require = createRequire(new URL("../../apps/bridge/package.json", import.meta.url));
const wsRequire = process.env.GAIUS_WS_MODULE_PATH
  ? createRequire(resolve(process.env.GAIUS_WS_MODULE_PATH, "package.json")) : require;
const {WebSocket, WebSocketServer} = wsRequire("ws");
const publicTarget = "race-target.example";
const localTunnel = "client-0123456789abcdef0123456789abcdef.gaius-local";
const {source, bridgeScript} = await loadBridgeSmokeSource();
const budgetSource = source.match(/const relayOpenRtt = [\s\S]*?const pluginTimeout = [\s\S]*?;/);
assert.ok(budgetSource, "adaptive plugin budget calculation is missing");
const pluginBudget = new Function("preparedOpen", "relayOpenAverage",
  `${budgetSource[0]} return pluginTimeout;`);
assert.equal(pluginBudget(undefined, undefined), 350,
  "an unmeasured relay must use the 350 ms plugin budget");
assert.equal(pluginBudget(0, NaN), 250,
  "a valid zero-ms open measurement must clamp to the 250 ms floor");
assert.equal(pluginBudget(40, undefined), 250);
assert.equal(pluginBudget(undefined, 160), 280);
assert.equal(pluginBudget(163, undefined), 285);
assert.equal(pluginBudget(300, 40), 490);
assert.equal(pluginBudget(undefined, 900), 600);
const rearmSource = source.match(/entry\.rearmDirectProbeTimeout = function\(relayOpenMillis\) \{[\s\S]*?\n\s+\};/);
assert.ok(rearmSource, "measured relay RTT timer rearm is missing");
const budgetEntry = {closed:false, connected:false, webSocketGeneration:7, directProbeStartedAt:1000};
let rearmedBudget = null;
new Function("entry", "generation", "armCandidateTimeout", "Date", rearmSource[0])(
  budgetEntry, 7, (millis) => { rearmedBudget = millis; }, {now:() => 1000});
budgetEntry.rearmDirectProbeTimeout(40);
assert.equal(rearmedBudget, 250,
  "a measured 40 ms relay open must rearm the initial 600 ms budget down to 250 ms");
const outboundScript = extractJsBody(source, "private static native void initOutboundScheduler();");
const inboundScript = extractJsBody(source, "private static native void initInboundScheduler();");
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function listen(server) {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return server.address().port;
}
function relayManifestResponse(req, res, activeConnections = 0) {
  if (req.url === "/health") {
    relayHealthRequests++;
    const respond = () => {
      res.setHeader("content-type", "application/json");
      const capabilities = relayUrlConnectCapability ? ["url-connect"] : ["target-attestation"];
      if (rsMock?.capable) capabilities.push("target-prewarm");
      res.end(JSON.stringify({ok:true, requiresToken:relayHealthRequiresToken, capabilities}));
    };
    if (stallRelayHealth) res.once("close", () => relayHealthAborts++);
    else respond();
  } else if (req.url.startsWith("/relay-node/v1")) {
    const respond = () => {
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({kind:"gaius-relay-node", protocolVersion:1,
        capabilities:["target-attestation"], availableConnections:20,
        targetConnectTimeoutMs:1500, targetAffinityMs:300000,
        target:{activeConnections, recentlyReachable:false}}));
    };
    if (relayManifestDelay) setTimeout(respond, relayManifestDelay);
    else respond();
  } else { res.statusCode = 404; res.end(); }
}
let relayUrlConnectCapability = false;
let relayHealthRequiresToken = false;
let urlConnectReply = "accept";
let stallRelayHealth = false;
let relayHealthRequests = 0;
let relayHealthAborts = 0;
let rsMock = null;
const relayHttp = createServer(relayManifestResponse);
let relayManifestDelay = 0;
let releaseRelayBManifest = null;
const relayBHttp = createServer((req, res) => {
  const respond = () => relayManifestResponse(req, res, 1);
  if (relayMode === "reorder") releaseRelayBManifest = respond;
  else respond();
});
const originalFetch = globalThis.fetch;
const pluginHttp = createServer();
const relayPort = await listen(relayHttp);
const relayBPort = await listen(relayBHttp);
const pluginPort = await listen(pluginHttp);
const primaryRelayUrl = `ws://primary-relay.example:${relayPort}/tunnel`;
const loopbackRelayUrl = `ws://127.0.0.1:${relayPort}/tunnel`;

function loopbackFetch(input, options) {
  const url = new URL(input);
  if (Number(url.port) === relayPort && url.hostname === "primary-relay.example") {
    url.hostname = "127.0.0.1";
  }
  if (url.hostname !== "127.0.0.1") return Promise.reject(new Error("Not a loopback fixture"));
  return originalFetch(url, options);
}
let raceWitness = null;
const raceEvidence = {};
function witness(side, event) {
  raceWitness?.events.push({side, event});
}
function answerConnect(side, socket, message) {
  const acknowledge = (reply = {}) => {
    if (socket.readyState !== WebSocket.OPEN) return;
    witness(side, "connected");
    socket.send(JSON.stringify({type:"connected", host:message.host, port:message.port,
      protocolTable:message.protocolTable && {formatVersion:message.protocolTable.formatVersion,
        sha256:message.protocolTable.sha256}, ...reply}));
  };
  if (raceWitness) {
    witness(side, "connect");
    raceWitness.acknowledge.set(side, acknowledge);
  }
  return acknowledge;
}

class LoopbackWebSocket extends WebSocket {
  constructor(url, protocols) {
    const mapped = new URL(url);
    // Exercise the production address policy with public-looking names, but dial only loopback.
    if (Number(mapped.port) === pluginPort ||
        (Number(mapped.port) === relayPort && mapped.hostname === "primary-relay.example")) {
      mapped.hostname = "127.0.0.1";
      mapped.protocol = "ws:";
    }
    assert.equal(mapped.hostname, "127.0.0.1", "WebSocket must dial a loopback fixture");
    super(mapped, protocols);
    this.fixtureUrl = String(url);
    this.fixtureSide = Number(mapped.port) === pluginPort ? "plugin" : "relay";
  }
  get url() { return this.fixtureUrl ?? super.url; }
  close(...args) {
    witness(this.fixtureSide, "client-close");
    super.close(...args);
  }
  send(data, ...args) {
    if (typeof data !== "string") witness(this.fixtureSide, "mc-send");
    super.send(data, ...args);
  }
}
const relayHandshake = (info, done) => {
  if (rsMock?.holdUpgrade(info.req.socket)) return;
  if (relayMode === "delay-open") setTimeout(() => done(true), 163);
  else done(true);
};
const relayWss = new WebSocketServer({server:relayHttp, path:"/tunnel", verifyClient:relayHandshake});
const relayBWss = new WebSocketServer({server:relayBHttp, path:"/tunnel", verifyClient:relayHandshake});
const filteredSockets = new Set();
const pluginWss = new WebSocketServer({server:pluginHttp, path:"/tunnel",
  verifyClient(info, done) {
    if (pluginMode === "filtered") {
      filteredSockets.add(info.req.socket);
      info.req.socket.once("close", () => filteredSockets.delete(info.req.socket));
      return;
    }
    done(true);
  },
});
const relaySockets = new Set();
const relayDialUrls = [];
const pluginSockets = new Set();
let pluginMode = "success";
let relayMode = "success";
let connectCount = 0;
const relayConnectMessages = [];
const relayUrlConnectRequests = [];
let relayBinaryWrites = 0;
let pluginBinaryWrites = 0;
let pluginConnectionCount = 0;
let relayConnectionCount = 0;
let relayBConnectionCount = 0;
function relayConnected(socket, isRelayB = false, request = null) {
  relayConnectionCount++;
  if (isRelayB) relayBConnectionCount++;
  relaySockets.add(socket);
  socket.once("close", () => { relaySockets.delete(socket); witness("relay", "server-close"); });
  if (rsMock) return rsMock.accept(socket, request);
  if (relayMode === "predial-fail") return socket.close(1011, "predial fixture failure");
  const connect = (msg) => {
    if (relayMode === "fail" || (relayMode === "fail-preferred" && !isRelayB)) {
      return socket.close(1011, "fixture failure");
    }
    const acknowledge = answerConnect("relay", socket, msg);
    if (raceWitness) return;
    if (relayMode === "delay-open") setTimeout(acknowledge, 50);
    else if (relayMode === "delay-connected") setTimeout(acknowledge, 400);
    else if (relayMode === "delay-briefly") setTimeout(acknowledge, 40);
    else acknowledge();
  };
  const encodedConnect = request && new URL(request.url, "http://loopback").searchParams.get("c");
  if (encodedConnect) {
    relayUrlConnectRequests.push(request.url);
    if (urlConnectReply === "close") socket.close(1008, "URL connect unavailable");
    else if (urlConnectReply === "error") socket.send(JSON.stringify({type:"error", error:"URL connect unavailable"}));
    else if (urlConnectReply === "accept") connect(JSON.parse(Buffer.from(encodedConnect, "base64url").toString("utf8")));
  }
  socket.on("message", (data, isBinary) => {
    if (isBinary) { relayBinaryWrites++; witness("relay", "mc-received"); return; }
    const msg = JSON.parse(String(data));
    if (msg.type === "connect") {
      connectCount++;
      relayConnectMessages.push(msg);
      connect(msg);
    }
  });
}
relayWss.on("connection", (socket, request) => relayConnected(socket, false, request));
relayBWss.on("connection", (socket, request) => relayConnected(socket, true, request));
pluginWss.on("connection", (socket) => {
  pluginConnectionCount++;
  pluginSockets.add(socket);
  socket.once("close", () => { pluginSockets.delete(socket); witness("plugin", "server-close"); });
  if (pluginMode === "fail") return socket.close(1011, "fixture failure");
  socket.on("message", (data, isBinary) => {
    if (isBinary) { pluginBinaryWrites++; witness("plugin", "mc-received"); return; }
    const msg = JSON.parse(String(data));
    if (raceWitness && msg.type === "connect") {
      if (raceWitness.pluginNegotiating) socket.send(JSON.stringify({type:"connecting", host:msg.host, port:msg.port}));
      answerConnect("plugin", socket, msg);
      return;
    }
    if (msg.type === "connect" && ["success", "medium-success", "slow-success"].includes(pluginMode)) {
      const respond = () => socket.readyState === WebSocket.OPEN &&
        socket.send(JSON.stringify({type:"connected", host:msg.host, port:msg.port}));
      if (pluginMode === "slow-success") setTimeout(respond, 300);
      else if (pluginMode === "medium-success") setTimeout(respond, 50);
      else respond();
    }
  });
});

globalThis.window = globalThis;
Object.defineProperty(globalThis, "location", {configurable:true, value:{
  href:"http://127.0.0.1/Gaius.html", hostname:"127.0.0.1", protocol:"http:",
  origin:"http://127.0.0.1", search:"",
}});
const localValues = new Map();
const startupRelayWork = {fetch:0, WebSocket:0, setTimeout:0, setInterval:0, localStorage:0};
let recordingStartup = true;
const realSetTimeout = globalThis.setTimeout;
const realSetInterval = globalThis.setInterval;
globalThis.fetch = (...args) => { if (recordingStartup) startupRelayWork.fetch++; return loopbackFetch(...args); };
globalThis.setTimeout = (...args) => { if (recordingStartup) startupRelayWork.setTimeout++; return realSetTimeout(...args); };
globalThis.setInterval = (...args) => { if (recordingStartup) startupRelayWork.setInterval++; return realSetInterval(...args); };
let preserveRelayPreferenceForNextOpen = false;
globalThis.localStorage = {
  getItem(key) { if (recordingStartup) startupRelayWork.localStorage++; return localValues.get(key) || null; },
  setItem(key, value) { if (recordingStartup) startupRelayWork.localStorage++; localValues.set(key, value); },
  removeItem(key) { if (recordingStartup) startupRelayWork.localStorage++; localValues.delete(key); },
};
globalThis.WebSocket = class StartupInstrumentedWebSocket extends LoopbackWebSocket {
  constructor(...args) { if (recordingStartup) startupRelayWork.WebSocket++; super(...args); }
};
globalThis.__gaiusDirectPluginPort = pluginPort;
globalThis.__gaiusDefaultRelayRegistries = false;
globalThis.__gaiusRelayRegistryUrls = [];
globalThis.__gaiusBridgeUrl = primaryRelayUrl;
globalThis.__gaiusBridgeUrls = [{name:"primary fixture", url:primaryRelayUrl, priority:100}];
let relayConfirmCalls = 0;
globalThis.__gaiusConfirmRelay = () => { relayConfirmCalls++; return true; };
new Function(bridgeScript)(); new Function(outboundScript)(); new Function(inboundScript)();
// This measures bridge initialization only. Launcher and title-page boot have separate tests.
assert.deepEqual(startupRelayWork, {fetch:0, WebSocket:0, setTimeout:0, setInterval:0, localStorage:0},
  "bridge initialization performed relay work");
recordingStartup = false;
globalThis.fetch = loopbackFetch;
globalThis.WebSocket = LoopbackWebSocket;
globalThis.setTimeout = realSetTimeout;
globalThis.setInterval = realSetInterval;
const bridge = globalThis.__gaiusNettyBridge;
const stats = globalThis.__gaiusNetworkStats;
const deadline = async (predicate, label, ms=2500) => {
  const end = Date.now() + ms;
  while (!predicate()) { if (Date.now() >= end) throw new Error(`Timed out: ${label}`); await wait(5); }
};
function clearRelayPreference() {
  localValues.delete("gaius.lastSuccessfulRelay");
  bridge.lastSuccessfulRelayUrl = null;
  bridge.lastSuccessfulRelayCandidate = null;
}
async function open(id, host, port = 25565, attempt = 0) {
  if (preserveRelayPreferenceForNextOpen) preserveRelayPreferenceForNextOpen = false;
  else clearRelayPreference();
  const baseline = stats.connected;
  let error = null;
  bridge.open(id, host, port, attempt);
  try {
    await deadline(() => stats.connected > baseline || (error = bridge.pollError(id)), `connect ${id}`);
  } catch (cause) {
    throw new Error(`Connect ${id} phases: ${JSON.stringify(stats.connectPhases.filter((p) => p.id === id))}`, {cause});
  }
  if (error) throw new Error(error);
  return stats.connectPhases.filter((p) => p.id === id);
}
const measured = (phases, phase) => {
  const item = phases.find((value) => value.phase === phase);
  assert.ok(item, `missing ${phase} phase: ${JSON.stringify(phases)}`);
  return item.elapsedMillis;
};
const timing = {};
const assertRelayBudget = (label, millis, budget) => {
  timing[label] = millis;
  assert.ok(Number.isFinite(millis) && millis <= budget,
    `${label} took ${millis} ms (required <= ${budget} ms)`);
};
async function verifyRaceOrder(id, winner) {
  await deadline(() => pluginSockets.size === 0 && relaySockets.size === 0, "race witness isolation");
  const WebSocketBefore = globalThis.WebSocket;
  const loser = winner === "plugin" ? "relay" : "plugin";
  const connectedBefore = stats.connected;
  const misses = [...bridge.directPluginMisses];
  const storedMisses = () => [...localValues].filter(([key]) => key.startsWith("gaius.directPluginMiss:"));
  const persistedMisses = storedMisses();
  const pluginRtts = () => [...(bridge.directPluginRtts || [])];
  const rtts = pluginRtts();
  clearRelayPreference();
  raceWitness = {events:[], acknowledge:new Map()};
  globalThis.WebSocket = LoopbackWebSocket;
  try {
    bridge.open(id, publicTarget, 25700 + id);
    // Queue MC bytes while both transports are still handshaking. If winner selection did
    // not close the loser before releasing that queue, the client events expose the race.
    assert.equal(bridge.send(id, Uint8Array.from([0x01, 0x02, 0x03])), true);
    await deadline(() => raceWitness.acknowledge.size === 2, "both race connect requests reached loopback");
    assert.equal(stats.connected, connectedBefore);
    assert.ok(!raceWitness.events.some(({event}) => event.startsWith("mc-")), "MC bytes escaped before connected");
    raceWitness.acknowledge.get(winner)();
    await deadline(() => raceWitness.events.some(({event}) => event === "mc-received") &&
      raceWitness.events.some(({side, event}) => side === loser && event === "server-close"),
      "race winner receives MC bytes and loser closes");
    raceWitness.acknowledge.get(loser)();
    const {events} = raceWitness;
    assert.deepEqual(events.filter(({event}) => event === "connect").map(({side}) => side).sort(),
      ["plugin", "relay"], "each candidate must send exactly one connect");
    const loserClosed = events.findIndex(({side, event}) => side === loser && event === "client-close");
    const firstWrite = events.findIndex(({event}) => event === "mc-send");
    assert.ok(loserClosed >= 0 && loserClosed < firstWrite,
      `loser close must precede the first MC write: ${JSON.stringify(events)}`);
    assert.deepEqual(events.filter(({event}) => event === "mc-received"), [{side:winner, event:"mc-received"}]);
    assert.equal(stats.connected, connectedBefore + 1, "race published more than one connected session");
    const phases = stats.connectPhases.filter((phase) => phase.id === id).map(({phase}) => phase);
    assert.ok(phases.includes("relay-race-start") && phases.includes(`race-winner=${winner}`));
    if (winner === "relay") {
      assert.deepEqual([...bridge.directPluginMisses], misses);
      assert.deepEqual(storedMisses(), persistedMisses);
      assert.deepEqual(pluginRtts(), rtts);
      assert.ok(phases.includes("direct-cancelled"));
      assert.ok(!phases.includes("direct-failed"));
    }
    raceEvidence[winner] = {events, phases, connectedSessions:stats.connected - connectedBefore};
  } finally {
    raceWitness = null;
    bridge.close(id);
    globalThis.WebSocket = WebSocketBefore;
  }
}
async function verifyInvalidRelayReplies() {
  const table = {formatVersion:1, sha256:"a".repeat(64)};
  const cases = [
    ["wrong-host", {host:"wrong.example"}],
    ["wrong-port", {port:25564}],
    ["wrapped-port", {port:2 ** 32 + 25900}],
    ["string-port", {port:"25900"}],
    ["missing-host", {host:undefined}],
    ["missing-port", {port:undefined}],
    ["wrong-table", {protocolTable:{...table, sha256:"b".repeat(64)}}],
    ["missing-table", {protocolTable:undefined}],
    ["non-connected", {type:"connecting"}],
    ["wrong-socket", null],
    ["stale-predial", null],
  ];
  const id = 115;
  const port = 25900;
  globalThis.__gaiusRelayProtocolDeclaration = table;
  try {
    for (const [name, reply] of cases) {
      await deadline(() => pluginSockets.size === 0 && relaySockets.size === 0, `invalid reply isolation: ${name}`);
      clearRelayPreference();
      const connectedBefore = stats.connected;
      raceWitness = {events:[], acknowledge:new Map(), pluginNegotiating:true};
      try {
        bridge.open(id, publicTarget, port);
        const entry = bridge.channels.get(id);
        await deadline(() => raceWitness.acknowledge.size === 2 && entry.directNegotiating,
          `both legs negotiated: ${name}`);
        const plugin = entry.ws;
        const generation = entry.webSocketGeneration;
        const pending = entry.preconnectedRelay;
        assert.ok(pending?.controlSent);
        if (!reply) {
          const data = JSON.stringify({type:"connected", host:publicTarget, port, protocolTable:table});
          if (name === "wrong-socket") pending.socket.onmessage({target:plugin, data});
          else {
            // Simulate a late callback from a socket replaced by a new pre-dial on this entry.
            entry.preconnectedRelay = {...pending, socket:plugin};
            try { pending.socket.onmessage({target:pending.socket, data}); }
            finally { entry.preconnectedRelay = pending; }
          }
        } else {
          let delivered = false;
          pending.socket.addEventListener("message", () => { delivered = true; }, {once:true});
          raceWitness.acknowledge.get("relay")(reply);
          await deadline(() => delivered, `delivery: ${name}`);
        }
        assert.equal(stats.connected, connectedBefore, name);
        assert.equal(entry.ws, plugin, name);
        assert.equal(entry.webSocketGeneration, generation, name);
        assert.equal(plugin.readyState, WebSocket.OPEN, name);
        assert.ok(entry.candidateTimeout, `plugin timeout was cleared: ${name}`);
        raceWitness.acknowledge.get("plugin")();
        await deadline(() => stats.connected === connectedBefore + 1, `healthy plugin wins: ${name}`);
        assert.equal(entry.currentCandidate.direct, true, name);
        assert.equal(entry.preconnectedRelay, null, name);
      } finally {
        raceWitness = null;
        bridge.close(id);
      }
    }
  } finally {
    delete globalThis.__gaiusRelayProtocolDeclaration;
  }
  return cases.map(([name]) => name);
}

try {
  globalThis.location.search = "?relayWarm=0";
  const warmDisabledBefore = relayConnectionCount;
  const warmPhases = await open(101, publicTarget, 25601);
  assert.equal(relayConnectionCount, warmDisabledBefore, "relayWarm=0 opened a speculative relay socket");
  assert.ok(warmPhases.some((phase) => phase.phase === "direct-connected"),
    "relayWarm=0 did not preserve direct plugin connection");
  bridge.close(101);

  globalThis.location.search = "?relayRace=0";
  const raceDisabledBefore = relayConnectionCount;
  pluginMode = "success"; relayMode = "success";
  const racePhases = await open(102, publicTarget, 25602);
  assert.equal(relayConnectionCount, raceDisabledBefore, "relayRace=0 ran relay/plugin parallel race");
  assert.ok(racePhases.some((phase) => phase.phase === "direct-connected"),
    "relayRace=0 did not retain plugin-first serial behavior");
  bridge.close(102);
  globalThis.location.search = "";

  globalThis.location.search = "?relayWarm=0";
  const disabledWarmDials = relayConnectionCount;
  let disabledOriginWarmCalls = 0;
  const originWarmCallback = globalThis.__gaiusWarmRelayOrigins;
  globalThis.__gaiusWarmRelayOrigins = () => { disabledOriginWarmCalls++; };
  bridge.warmServerList(true);
  assert.equal(bridge.serverListWarmupEntry ?? null, null,
    "relayWarm=0 created a list warmup entry");
  assert.equal(relayConnectionCount, disabledWarmDials, "relayWarm=0 dialed a relay");
  assert.equal(disabledOriginWarmCalls, 0, "relayWarm=0 invoked origin warming");
  globalThis.__gaiusWarmRelayOrigins = originWarmCallback;

  globalThis.location.search = "?relayRace=0";
  pluginMode = "slow-success"; relayMode = "success";
  const serialRelayDials = relayConnectionCount;
  let switchPhases = await open(90, publicTarget, 25690);
  assert.ok(switchPhases.some((p) => p.phase === "direct-connected"),
    "relayRace=0 failed to prefer the plugin serially");
  assert.equal(relayConnectionCount, serialRelayDials,
    "relayRace=0 raced a relay against a successful plugin");
  assert.ok(!switchPhases.some((p) => p.phase === "relay-predial-start"),
    "relayRace=0 performed speculative relay predial");
  bridge.close(90);
  pluginMode = "fail";
  switchPhases = await open(91, publicTarget, 25691);
  assert.ok(switchPhases.some((p) => p.phase === "relay-connected"),
    "relayRace=0 did not fall back after plugin failure");
  assert.ok(!switchPhases.some((p) => p.phase === "relay-predial-start"),
    "serial fallback unexpectedly pre-dialed the relay");
  bridge.close(91);
  const relayUrl = primaryRelayUrl;
  relayUrlConnectCapability = true;
  bridge.urlConnectCapabilities?.delete(relayUrl);
  globalThis.location.search = "?relayRace=0&relayWarm=0";
  stallRelayHealth = true;
  const controlsBeforeUnknown = connectCount;
  const healthBeforeUnknown = relayHealthRequests;
  const abortsBeforeUnknown = relayHealthAborts;
  switchPhases = await open(94, publicTarget, 25694);
  assert.ok(switchPhases.some((p) => p.phase === "url-connect=false"));
  assert.equal(connectCount, controlsBeforeUnknown + 1, "unknown capability must send one control");
  bridge.close(94);
  await deadline(() => relayHealthAborts > abortsBeforeUnknown, "stalled health was aborted");
  assert.equal(relayHealthRequests, healthBeforeUnknown + 1, "stalled health started more than one probe");
  stallRelayHealth = false;
  bridge.urlConnectCapabilities.delete(relayUrl);
  const helper = globalThis.__gaiusRelayRaceHelpers;
  const validControl = {host:"example.test", port:25565};
  assert.equal(helper.urlConnect(relayUrl, validControl, false), null,
    "unknown capability returned an awaitable or URL instead of selecting control synchronously");
  assert.equal(helper.urlConnect(relayUrl, validControl, false), null,
    "an in-flight capability probe must not delay concurrent joins");
  await deadline(() => bridge.urlConnectCapabilities.get(relayUrl)?.enabled, "async health discovery");
  assert.equal(relayHealthRequests, healthBeforeUnknown + 2, "in-flight capability probes were not deduplicated");
  const urlRequestsBefore = relayUrlConnectRequests.length;
  const controlsBeforeUrlConnect = connectCount;
  switchPhases = await open(92, publicTarget, 25692);
  assert.ok(switchPhases.some((p) => p.phase === "url-connect=true"),
    `capable cold relay did not select URL connect: ${JSON.stringify(switchPhases)}`);
  assert.equal(relayUrlConnectRequests.length, urlRequestsBefore + 1,
    "relay WebSocket URL did not carry c=");
  assert.equal(connectCount, controlsBeforeUrlConnect,
    "URL connect also sent a control connect message");
  assert.ok(switchPhases.some((p) => p.phase === "relay-connected"));
  bridge.close(92);
  bridge.urlConnectCapabilities.set(relayUrl, {enabled:true, expiresAt:Date.now() + 60000});
  for (const [label, control] of [
    ["token", {...validControl, token:"secret"}],
    ["local tunnel", {...validControl, host:"client-0123456789abcdef0123456789abcdef.gaius-local"}],
    ["skin descriptor", {...validControl, skinDescriptor:{value:"x"}}],
    ["oversized decoded JSON", {host:"x".repeat(1100), port:25565}],
    ["oversized encoded c", {host:"x".repeat(800), port:25565}],
  ]) {
    assert.equal(helper.urlConnect(relayUrl, control, false), null,
      `${label} did not fall back to control-message mode`);
  }
  assert.equal(helper.urlConnect(relayUrl, validControl, true), null,
    "hot socket did not retain control-message mode");
  const oversizedDeclaration = {formatVersion:1, sha256:"a".repeat(64), table:"x".repeat(1100)};
  globalThis.__gaiusRelayProtocolDeclaration = oversizedDeclaration;
  try {
    const urlBefore = relayUrlConnectRequests.length;
    const controlBefore = connectCount;
    const phases = await open(98, publicTarget, 25996);
    assert.ok(phases.some(({phase}) => phase === "url-connect=false"));
    assert.equal(relayUrlConnectRequests.length, urlBefore);
    assert.equal(connectCount, controlBefore + 1);
    assert.deepEqual(relayConnectMessages.at(-1).protocolTable, oversizedDeclaration);
    bridge.close(98);
  } finally {
    delete globalThis.__gaiusRelayProtocolDeclaration;
  }
  globalThis.location.search = "?relayRace=0&relayWarm=0&relayUrl=0";
  const controlsBeforeUrlDisabled = connectCount;
  const healthBeforeUrlDisabled = relayHealthRequests;
  switchPhases = await open(93, publicTarget, 25693);
  assert.ok(switchPhases.some((p) => p.phase === "url-connect=false"),
    "relayUrl=0 did not disable URL connect");
  assert.equal(connectCount, controlsBeforeUrlDisabled + 1,
    "relayUrl=0 did not retain exactly one control connect");
  assert.equal(relayHealthRequests, healthBeforeUrlDisabled, "relayUrl=0 queried capability");
  bridge.close(93);
  globalThis.location.search = "";
  pluginMode = "slow-success";
  const raceUrlCount = relayUrlConnectRequests.length;
  const raceControlCount = connectCount;
  switchPhases = await open(95, publicTarget, 25695);
  assert.ok(switchPhases.some((p) => p.phase === "url-connect=true"));
  assert.ok(switchPhases.some((p) => p.phase === "relay-race-start"));
  assert.ok(switchPhases.some((p) => p.phase === "race-winner=relay"));
  assert.ok(!switchPhases.some((p) => p.phase === "direct-failed"));
  assert.equal(relayUrlConnectRequests.length, raceUrlCount + 1, "raced URL attempt must upgrade once");
  assert.equal(connectCount, raceControlCount, "raced URL attempt must not send a control");
  bridge.close(95);
  bridge.urlConnectCapabilities.clear();
  relayUrlConnectCapability = false;
  await deadline(() => pluginSockets.size === 0 && relaySockets.size === 0, "switch fixtures cleanup");

  // The negotiating plugin and the relay control must both proceed while health is pending.
  stallRelayHealth = true;
  const healthBeforeRace = relayHealthRequests;
  const abortsBeforeRace = relayHealthAborts;
  await verifyRaceOrder(112, "plugin");
  await deadline(() => relayHealthAborts === abortsBeforeRace + 1, "parallel health probe aborted");
  assert.equal(relayHealthRequests, healthBeforeRace + 1);
  stallRelayHealth = false;

  // An expired capability is replaced by a fresh observation, including omitted/non-boolean flags.
  relayUrlConnectCapability = true;
  for (const flag of [undefined, true, "false"]) {
    relayHealthRequiresToken = flag;
    bridge.urlConnectCapabilities.set(relayUrl, {enabled:true, expiresAt:Date.now() - 1});
    assert.equal(helper.urlConnect(relayUrl, validControl, false), null);
    await deadline(() => bridge.urlConnectCapabilities.get(relayUrl)?.expiresAt - Date.now() > 25000,
      "changed health response was cached");
    assert.equal(helper.urlConnect(relayUrl, validControl, false), null);
  }
  relayHealthRequiresToken = false;
  bridge.urlConnectCapabilities.set(relayUrl, {enabled:true, expiresAt:Date.now() + 60000});
  for (const unadvertised of [relayUrl.replace("/tunnel", "/another"), `${relayUrl}?tenant=other`]) {
    assert.equal(helper.urlConnect(unadvertised, validControl, false), null,
      "an origin-wide or variant endpoint capability leaked into another URL");
  }

  // A previously capable endpoint can change before the cached positive expires.
  // Exercise all failure signals: explicit refusal while the plugin negotiates,
  // upgrade close on the serial path, and an older receiver that ignores c altogether.
  for (const [id, reply, serial] of [[103, "error", false], [104, "close", true], [105, "ignore", true]]) {
    globalThis.location.search = serial ? "?relayRace=0&relayWarm=0" : "";
    globalThis.__gaiusDirectPlugin = !serial;
    pluginMode = "hang";
    urlConnectReply = reply;
    bridge.urlConnectCapabilities.set(relayUrl, {enabled:true, expiresAt:Date.now() + 60000});
    const before = {connectCount, urls:relayUrlConnectRequests.length, sockets:relayConnectionCount};
    const phases = await open(id, publicTarget, 25800 + id);
    assert.ok(phases.some(({phase}) => phase === "url-connect=true"));
    assert.ok(phases.some(({phase}) => phase === "url-connect=false"));
    assert.equal(connectCount, before.connectCount + 1);
    assert.equal(relayUrlConnectRequests.length, before.urls + 1);
    assert.equal(relayConnectionCount, before.sockets + 2);
    assert.equal(bridge.urlConnectCapabilities.get(relayUrl).enabled, false);
    if (!serial) assert.ok(phases.some(({phase, detail}) => phase === "direct-cancelled" && detail === "not-measured"));
    bridge.close(id);
    await deadline(() => pluginSockets.size === 0 && relaySockets.size === 0, "changed capability cleanup");
  }
  {
    const id = 124;
    const table = {formatVersion:1, sha256:"c".repeat(64)};
    clearRelayPreference();
    globalThis.location.search = "";
    globalThis.__gaiusDirectPlugin = true;
    globalThis.__gaiusRelayProtocolDeclaration = table;
    urlConnectReply = "accept";
    bridge.urlConnectCapabilities.set(relayUrl, {enabled:true, expiresAt:Date.now() + 60000});
    raceWitness = {events:[], acknowledge:new Map(), pluginNegotiating:true};
    const before = {connectCount, urls:relayUrlConnectRequests.length, sockets:relayConnectionCount,
      connected:stats.connected};
    try {
      bridge.open(id, publicTarget, 25907);
      const entry = bridge.channels.get(id);
      await deadline(() => raceWitness.acknowledge.size === 2 && entry.directNegotiating, "plugin and URL relay negotiating");
      assert.ok(entry.preconnectedRelay.urlConnect);
      const plugin = entry.ws;
      const rejectUrl = raceWitness.acknowledge.get("relay");
      rejectUrl({protocolTable:undefined});
      await deadline(() => connectCount === before.connectCount + 1, "control retry while plugin still negotiating");
      assert.equal(relayUrlConnectRequests.length, before.urls + 1);
      assert.equal(relayConnectionCount, before.sockets + 2);
      assert.deepEqual(relayConnectMessages.at(-1).protocolTable, table);
      assert.equal(entry.ws, plugin);
      assert.equal(plugin.readyState, WebSocket.OPEN);
      assert.equal(stats.connected, before.connected);
      assert.equal(bridge.urlConnectCapabilities.get(relayUrl).enabled, false);
      raceWitness.acknowledge.get("plugin")();
      await deadline(() => stats.connected === before.connected + 1, "plugin wins after incompatible URL ACK");
    } finally {
      raceWitness = null;
      delete globalThis.__gaiusRelayProtocolDeclaration;
      bridge.close(id);
    }
    await deadline(() => pluginSockets.size === 0 && relaySockets.size === 0, "incompatible URL ACK cleanup");
  }
  delete globalThis.__gaiusDirectPlugin;
  urlConnectReply = "accept";
  relayUrlConnectCapability = false;
  bridge.urlConnectCapabilities.clear();

  // A negative health response must stay on the control path for a subsequent cold join.
  assert.equal(helper.urlConnect(relayUrl, validControl, false), null);
  await deadline(() => {
    const negative = bridge.urlConnectCapabilities.get(relayUrl);
    return negative && !negative.enabled && negative.expiresAt - Date.now() > 25000;
  }, "negative health result was cached");
  globalThis.location.search = "?relayRace=0&relayWarm=0";
  pluginMode = "fail";
  const beforeNegative = {connectCount, urls:relayUrlConnectRequests.length, relayHealthRequests};
  const negativePhases = await open(96, publicTarget, 25696);
  assert.ok(negativePhases.some(({phase}) => phase === "url-connect=false"));
  assert.equal(connectCount, beforeNegative.connectCount + 1);
  assert.equal(relayUrlConnectRequests.length, beforeNegative.urls);
  assert.equal(relayHealthRequests, beforeNegative.relayHealthRequests);
  bridge.close(96);

  // c= is capability-gated on cold joins, while each excluded request sends one control.
  bridge.urlConnectCapabilities.set(relayUrl, {enabled:true, expiresAt:Date.now() + 60000});
  globalThis.__gaiusDirectPlugin = false;
  const originalRelayCandidates = globalThis.__gaiusBridgeUrls;
  const skin = {uuid:"0123456789abcdef0123456789abcdef", username:"Player", value:"fixture"};
  for (const [id, host, token, descriptor] of [
    [97, publicTarget, "fixture-token"],
    [98, localTunnel],
    [99, localTunnel, undefined, skin],
    [100, "x".repeat(800)],
    [106, "  Other.GaIuS-LoCaL...  "],
    [107, "other.gaius-local\u3002"],
  ]) {
    globalThis.__gaiusBridgeUrls = [{...originalRelayCandidates[0], token}];
    globalThis.__gaiusSkinDescriptor = descriptor;
    const before = {connectCount, urls:relayUrlConnectRequests.length};
    const phases = await open(id, host, 25600 + id);
    assert.ok(phases.some(({phase}) => phase === "url-connect=false"));
    assert.equal(connectCount, before.connectCount + 1);
    assert.equal(relayUrlConnectRequests.length, before.urls);
    const control = relayConnectMessages.at(-1);
    assert.equal(control.host, host);
    assert.equal(control.token, token);
    assert.deepEqual(control.skinDescriptor, descriptor && {...descriptor, signature:""});
    bridge.close(id);
  }
  delete globalThis.__gaiusSkinDescriptor;
  delete globalThis.__gaiusDirectPlugin;
  globalThis.__gaiusBridgeUrls = originalRelayCandidates;
  globalThis.location.search = "";
  bridge.urlConnectCapabilities.clear();
  await deadline(() => pluginSockets.size === 0 && relaySockets.size === 0, "URL fallback cleanup");
  // Loopback/private relay candidates cannot create targetless list warmups or timers.
  let excludedWarmTimeouts = 0;
  clearRelayPreference();
  globalThis.__gaiusBridgeUrl = loopbackRelayUrl;
  globalThis.__gaiusBridgeUrls = [{name:"local fixture", url:loopbackRelayUrl, priority:100}];
  globalThis.setTimeout = (...args) => { excludedWarmTimeouts++; return realSetTimeout(...args); };
  try { bridge.warmServerList(true); }
  finally {
    globalThis.setTimeout = realSetTimeout;
    globalThis.__gaiusBridgeUrl = primaryRelayUrl;
    globalThis.__gaiusBridgeUrls = originalRelayCandidates;
  }
  assert.equal(bridge.serverListWarmupEntry ?? null, null);
  assert.equal(excludedWarmTimeouts, 0, "excluded relay created a warmup expiry timer");

  pluginMode = "success"; relayMode = "success";
  await verifyRaceOrder(110, "plugin");
  globalThis.__gaiusRelayProtocolDeclaration = {formatVersion:1, sha256:"a".repeat(64)};
  await verifyRaceOrder(111, "relay");
  const rejectedRelayReplies = await verifyInvalidRelayReplies();

  pluginMode = "medium-success"; relayMode = "delay-connected";
  const savedFetchForFailure = globalThis.fetch;
  globalThis.fetch = () => Promise.reject(new Error("offline warmup fixture"));
  let beforeRelay = connectCount, beforeRelaySockets;
  const pluginFirstJoin = open(1, publicTarget, 25565);
  await deadline(() => relaySockets.size === 1, "relay loser reached OPEN before plugin success");
  let phases = await pluginFirstJoin;
  globalThis.fetch = savedFetchForFailure;
  await wait(50);
  assert.equal(connectCount, beforeRelay + 1, "open relay loser did not send exactly one connect control");
  await deadline(() => relaySockets.size === 0, "plugin winner closes open relay loser");
  assert.equal(relaySockets.size, 0, "plugin win retained a relay loser");
  assert.ok(phases.some((p) => p.phase === "direct-connected"));
  assert.ok(phases.some((p) => p.phase === "relay-race-start"));
  assert.ok(phases.some((p) => p.phase === "race-winner=plugin"));
  assertRelayBudget("pluginSuccessDirectConnected", measured(phases, "direct-connected"), 450);
  const pluginBytes = pluginBinaryWrites;
  const relayBytes = relayBinaryWrites;
  assert.equal(bridge.send(1, Uint8Array.from([1, 2, 3])), true);
  await deadline(() => pluginBinaryWrites === pluginBytes + 1, "plugin winner receives Minecraft bytes");
  assert.equal(relayBinaryWrites, relayBytes, "relay loser received Minecraft bytes after losing");
  bridge.close(1);

  pluginMode = "slow-success"; relayMode = "success";
  const slowPluginConnections = pluginConnectionCount;
  beforeRelay = connectCount;
  phases = await open(17, publicTarget, 25565);
  assert.ok(phases.some((phase) => phase.phase === "relay-connected"),
    "relay did not beat the 300 ms plugin response");
  assert.equal(connectCount, beforeRelay + 1, "racing relay sent connect control more than once");
  assert.ok(phases.some((p) => p.phase === "race-winner=relay"));
  assert.ok(measured(phases, "relay-race-start") < measured(phases, "relay-connected"));
  assert.deepEqual(phases.slice(0, 3).map(({phase}) => phase),
    ["open", "relay-race-start", "relay-websocket-start"], "relay socket was not launched at Join");
  assert.ok(!phases.some((p) => p.phase === "direct-failed"),
    "relay connect was deferred until plugin failure");
  await deadline(() => pluginSockets.size === 0, "slow plugin loser close");
  const relayBytesBeforeWinnerWrite = relayBinaryWrites;
  const pluginBytesBeforeWinnerWrite = pluginBinaryWrites;
  assert.equal(bridge.send(17, Uint8Array.from([4, 5, 6])), true);
  await deadline(() => relayBinaryWrites === relayBytesBeforeWinnerWrite + 1,
    "relay winner receives Minecraft bytes");
  assert.equal(pluginBinaryWrites, pluginBytesBeforeWinnerWrite,
    "closed plugin loser received Minecraft bytes");
  assert.ok(pluginConnectionCount > slowPluginConnections, "slow plugin fixture did not connect");
  assertRelayBudget("plugin300msRelayWon", measured(phases, "relay-connected"), 450);
  bridge.close(17);

  pluginMode = "fail"; relayMode = "success";
  globalThis.location.search = "?relay=1";
  relayConfirmCalls = 0;
  const externalRelayPrefix = `ws://relay.example:${relayPort}`;
  globalThis.__gaiusBridgeUrl = `${externalRelayPrefix}/tunnel`;
  globalThis.__gaiusBridgeUrls = [{name:"explicit external relay", url:globalThis.__gaiusBridgeUrl, priority:100}];
  globalThis.WebSocket = class RelayMappedWebSocket extends LoopbackWebSocket {
    constructor(url, protocols) {
      const mappedUrl = String(url).replace(externalRelayPrefix, `ws://127.0.0.1:${relayPort}`);
      super(mappedUrl, protocols);
    }
  };
  globalThis.fetch = (input, options) => loopbackFetch(
    String(input).replace(`http://relay.example:${relayPort}`, `http://127.0.0.1:${relayPort}`), options);
  beforeRelay = connectCount;
  beforeRelaySockets = relayConnectionCount;
  phases = await open(2, publicTarget, 25566);
  assert.equal(connectCount, beforeRelay + 1, "relay was redialed or omitted after plugin failure");
  assert.equal(relayConnectionCount, beforeRelaySockets + 1, "failed plugin caused a second relay socket dial");
  assert.ok(phases.some((p) => p.phase === "relay-connected"));
  assert.ok(phases.some((p) => p.phase === "url-connect=false"),
    "relay without url-connect capability did not use the control-message path");
  assert.equal(relayConfirmCalls, 1, "explicit relay URL was confirmed more than once");
  assertRelayBudget("pluginFailureRelayConnected", measured(phases, "relay-connected"), 450);
  relayConfirmCalls = 0;
  globalThis.__gaiusConfirmRelay = () => { relayConfirmCalls++; return false; };
  globalThis.location.hostname = "127.0.0.1";
  globalThis.location.origin = "http://127.0.0.1";
  globalThis.location.protocol = "http:";
  globalThis.__gaiusDirectPlugin = false;
  const deniedRelaySocketsBefore = relayConnectionCount;
  bridge.open(15, publicTarget, 25576);
  await deadline(() => relayConfirmCalls > 0,
    `explicit relay refused phases=${JSON.stringify(stats.connectPhases.filter((phase) => phase.id === 15))}`);
  assert.equal(relayConfirmCalls, 1, "refused explicit relay URL was confirmed repeatedly");
  assert.equal(relayConnectionCount, deniedRelaySocketsBefore,
    "refused explicit relay URL was dialed");
  bridge.close(15);
  globalThis.__gaiusDirectPlugin = true;
  globalThis.__gaiusConfirmRelay = () => { relayConfirmCalls++; return true; };
  globalThis.location.search = "";
  globalThis.__gaiusBridgeUrl = primaryRelayUrl;
  globalThis.__gaiusBridgeUrls = [{name:"primary fixture", url:primaryRelayUrl, priority:100}];
  globalThis.WebSocket = LoopbackWebSocket;
  globalThis.fetch = loopbackFetch;
  bridge.close(2); await wait(30);

  pluginMode = "hang";
  beforeRelay = connectCount;
  beforeRelaySockets = relayConnectionCount;
  const hangingStarted = Date.now();
  phases = await open(3, publicTarget, 25567);
  assert.equal(connectCount, beforeRelay + 1, "hung plugin did not promote prepared relay");
  assert.equal(relayConnectionCount, beforeRelaySockets + 1, "hung plugin caused a second relay socket dial");
  const hangingElapsed = Date.now() - hangingStarted;
  assert.ok(!phases.some((p) => p.phase === "direct-failed"),
    "relay winner waited for the hung plugin timeout");
  await deadline(() => pluginSockets.size === 0, "hung plugin loser close");
  timing.pluginHangWallMillis = hangingElapsed;
  assertRelayBudget("pluginHangRelayConnected", measured(phases, "relay-connected"), 450);
  bridge.close(3); await wait(30);

  // An actual direct timeout still records a miss. The serial path keeps the relay from winning first.
  globalThis.location.search = "?relayRace=0";
  phases = await open(116, publicTarget, 25901);
  assert.ok(phases.some(({phase, detail}) => phase === "direct-failed" && detail === "websocket-timeout"));
  assert.ok(bridge.directPluginMisses.has(`${publicTarget}:25901`));
  assert.ok(localStorage.getItem(`gaius.directPluginMiss:${publicTarget}:25901`));
  bridge.close(116);
  globalThis.location.search = "";

  pluginMode = "filtered";
  bridge.relaySocketOpenRtts.clear();
  phases = await open(20, publicTarget, 25580);
  assertRelayBudget("filteredColdRelayConnected", measured(phases, "relay-connected"), 450);
  assert.ok(measured(phases, "relay-predial-start") < 30,
    "cold predial waited for registry/preflight selection");
  await deadline(() => pluginSockets.size === 0, "filtered plugin loser close");
  bridge.close(20);
  for (const socket of filteredSockets) socket.destroy();
  await wait(30);

  pluginMode = "hang"; relayMode = "delay-open";
  bridge.relaySocketOpenRtts.clear();
  phases = await open(29, publicTarget, 25589);
  const relayOpenEvent = phases.find((phase) => phase.phase === "relay-predial-open");
  assert.ok(relayOpenEvent, "missing measured relay-open phase");
  const measuredOpen = Number(relayOpenEvent.detail);
  assert.ok(measuredOpen >= 145 && measuredOpen <= 220,
    `delayed relay open measurement was ${measuredOpen} ms`);
  const simulatedColdRelay = measured(phases, "relay-connected");
  assert.ok(simulatedColdRelay >= 180 && simulatedColdRelay <= 320,
    `163 ms relay RTT raced connect took ${simulatedColdRelay} ms (required 180-320 ms)`);
  timing.simulated163msRelayOpenColdRelayConnected = simulatedColdRelay;
  assert.ok(!phases.some((phase) => phase.phase === "direct-failed"),
    "relay winner waited for plugin timeout despite measured 163 ms relay open");
  bridge.close(29);
  relayMode = "success";
  await wait(30);

  // Keep a real successful relay preference across a simulated page refresh. A public
  // hostname is mapped to the local fixture so this remains an offline deterministic test.
  const persistedRelayUrl = `ws://relay.example:${relayPort}/tunnel`;
  const persistedRelayPrefix = `ws://relay.example:${relayPort}`;
  globalThis.WebSocket = class PersistedRelayWebSocket extends LoopbackWebSocket {
    constructor(url, protocols) {
      if (String(url) === persistedRelayUrl || String(url) === loopbackRelayUrl) {
        relayDialUrls.push(String(url));
      }
      super(String(url).replace(persistedRelayPrefix, `ws://127.0.0.1:${relayPort}`), protocols);
    }
  };
  globalThis.fetch = (input, options) => loopbackFetch(
    String(input).replace(`http://relay.example:${relayPort}`, `http://127.0.0.1:${relayPort}`), options);
  globalThis.location.hostname = "localhost";
  globalThis.__gaiusBridgeUrls = [{name:"persisted public", url:persistedRelayUrl, priority:200,
    use:"both", pinned:true, secret:"must-not-persist",
    auth:"must-not-persist", key:"must-not-persist"},
    {name:"loopback fallback", url:loopbackRelayUrl, priority:100}];
  pluginMode = "fail"; relayMode = "success";
  phases = await open(22, publicTarget, 25582);
  assert.equal(bridge.lastSuccessfulRelayUrl, persistedRelayUrl);
  const persistedRecord = JSON.parse(localStorage.getItem("gaius.lastSuccessfulRelay"));
  assert.equal(persistedRecord.candidate.name, "persisted public");
  assert.deepEqual(Object.keys(persistedRecord.candidate).sort(),
    ["name", "pinned", "priority", "url", "use"],
    "persistent relay candidate contains only the storage whitelist");
  assert.equal("token" in persistedRecord.candidate, false);
  assert.equal("secret" in persistedRecord.candidate, false);
  assert.equal("auth" in persistedRecord.candidate, false);
  assert.equal("key" in persistedRecord.candidate, false);
  localStorage.setItem("gaius.lastSuccessfulRelay", JSON.stringify({
    savedAt: persistedRecord.savedAt, url: persistedRecord.url,
    candidate: {...persistedRecord.candidate, token:"legacy-token", secret:"legacy-secret",
      auth:"legacy-auth", key:"legacy-key", credentials:"legacy-credentials",
      legacyMarker:"must-be-discarded"}
  }));
  bridge.close(22);
  bridge.lastSuccessfulRelayUrl = null;
  bridge.lastSuccessfulRelayCandidate = null;
  relayManifestDelay = 150;
  preserveRelayPreferenceForNextOpen = true;
  phases = await open(23, publicTarget, 25583);
  relayManifestDelay = 0;
  assert.equal(measured(phases, "relay-predial-start") < 30, true,
    "refreshed channel did not use persistent relay preference at t=0");
  assert.notEqual(bridge.lastSuccessfulRelayCandidate.token, "legacy-token");
  assert.notEqual(bridge.lastSuccessfulRelayCandidate.secret, "legacy-secret");
  assert.notEqual(bridge.lastSuccessfulRelayCandidate.auth, "legacy-auth");
  assert.notEqual(bridge.lastSuccessfulRelayCandidate.key, "legacy-key");
  assert.notEqual(bridge.lastSuccessfulRelayCandidate.credentials, "legacy-credentials");
  assert.equal("legacyMarker" in bridge.lastSuccessfulRelayCandidate, false);
  const refreshedRecord = JSON.parse(localStorage.getItem("gaius.lastSuccessfulRelay"));
  assert.deepEqual(Object.keys(refreshedRecord.candidate).sort(),
    ["name", "pinned", "priority", "url", "use"],
    "refreshed persistence contains only the storage whitelist");
  assert.ok(phases.some((p) => p.phase === "relay-predial-start" && p.detail === persistedRelayUrl),
    "refresh ignored persistent preferred relay candidate");
  assert.ok(measured(phases, "relay-websocket-start") < 30,
    "remembered relay WebSocket did not start at t=0");
  assert.ok(!phases.some((phase) => phase.phase === "relay-selection-ready"),
    "cached relay path waited for the delayed preflight selection gate");
  assertRelayBudget("refreshPersistentRelayConnected", measured(phases, "relay-connected"), 120);
  assert.ok(relayDialUrls.includes(persistedRelayUrl),
    "refresh did not open the persisted relay URL");
  bridge.close(23);

  const fallbackRelayUrl = `ws://127.0.0.1:${relayBPort}/tunnel`;
  localStorage.setItem("gaius.lastSuccessfulRelay", JSON.stringify({savedAt:Date.now(),
    url:persistedRelayUrl, candidate:{name:"failed remembered", url:persistedRelayUrl, priority:200}}));
  bridge.lastSuccessfulRelayUrl = null;
  bridge.lastSuccessfulRelayCandidate = null;
  globalThis.location.hostname = "play.example";
  globalThis.location.origin = "https://play.example";
  globalThis.__gaiusBridgeUrl = undefined;
  globalThis.__gaiusBridgeUrls = [
    {name:"remembered relay", url:persistedRelayUrl, priority:200},
    {name:"fallback relay", url:fallbackRelayUrl, priority:100},
  ];
  relayMode = "fail-preferred";
  preserveRelayPreferenceForNextOpen = true;
  phases = await open(32, publicTarget, 25592);
  const fallbackPhases = phases.filter((phase) => phase.phase === "relay-websocket-start");
  assert.ok(phases.some((phase) => phase.phase === "relay-predial-start" &&
    phase.detail === persistedRelayUrl), "failed cached relay was not attempted as a pre-dial");
  assert.ok(fallbackPhases.some((phase) => phase.detail === fallbackRelayUrl),
    `failed cached relay did not proceed to the fallback candidate: ${JSON.stringify({candidates:bridge.channels.get(32)?.candidates, phases})}`);
  assert.ok(fallbackPhases.every((phase) => phase.detail === persistedRelayUrl || phase.detail === fallbackRelayUrl),
    "cached relay fallback attempted an unrelated candidate");
  assert.ok(phases.some((phase) => phase.phase === "relay-connected" &&
    phase.detail === fallbackRelayUrl), "fallback candidate did not connect after cached relay failure");
  assertRelayBudget("failedCachedRelayFallbackConnected", measured(phases, "relay-connected"), 250);
  bridge.close(32);
  relayMode = "success";

  // A cold public page dials the built-in relay at t=0 without waiting for preflight.
  localStorage.removeItem("gaius.lastSuccessfulRelay");
  bridge.lastSuccessfulRelayUrl = null;
  bridge.lastSuccessfulRelayCandidate = null;
  globalThis.location.hostname = "play.example";
  globalThis.location.origin = "https://play.example";
  globalThis.__gaiusBridgeUrl = undefined;
  globalThis.__gaiusBridgeUrls = [{id:"gaius-hk-1", name:"Gaius HK Relay",
    url:persistedRelayUrl, priority:100}];
  pluginMode = "hang";
  bridge.relaySocketOpenRtts.clear();
  phases = await open(30, publicTarget, 25590);
  assert.ok(measured(phases, "relay-predial-start") < 30,
    `cold built-in relay predial was not immediate (${measured(phases, "relay-predial-start").toFixed(1)} ms)`);
  assertRelayBudget("coldBuiltInRelayConnected", measured(phases, "relay-connected"), 300);
  bridge.close(30);

  // A localhost test page with a public candidate must not speculate against 127.0.0.1.
  globalThis.location.hostname = "localhost";
  globalThis.__gaiusBridgeUrl = loopbackRelayUrl;
  globalThis.__gaiusBridgeUrls = [{name:"loopback fallback", url:loopbackRelayUrl, priority:200},
    {name:"public candidate", url:persistedRelayUrl, priority:100}];
  pluginMode = "success"; relayMode = "delay-connected";
  const beforeLocalhostChoice = relayDialUrls.length;
  phases = await open(24, publicTarget, 25584);
  assert.ok(phases.some((p) => p.phase === "relay-predial-start" && p.detail === persistedRelayUrl),
    "localhost page did not select the public relay candidate");
  assert.ok(relayDialUrls.slice(beforeLocalhostChoice).includes(persistedRelayUrl),
    "localhost page did not pre-dial the public relay URL");
  assert.ok(!relayDialUrls.slice(beforeLocalhostChoice).includes(loopbackRelayUrl),
    "localhost page pre-dialed its loopback candidate");
  assertRelayBudget("localhostPublicPredialDirectConnected", measured(phases, "direct-connected"), 250);
  bridge.close(24);
  relayMode = "success";

  for (const [id, host] of [[120, "127.0.0.1"], [121, "::ffff:127.0.0.1"],
    [125, "2002:7f00:1::1"], [126, "2002:c0a8:101::1"]]) {
    const before = relayConnectionCount;
    phases = await open(id, host, 25584);
    assert.ok(phases.some((p) => p.phase === "direct-connected"), host);
    assert.ok(!phases.some((p) => p.phase === "relay-predial-start"), host);
    assert.equal(relayConnectionCount, before, host);
    bridge.close(id);
  }
  globalThis.__gaiusBridgeUrl = loopbackRelayUrl;
  globalThis.__gaiusBridgeUrls = [{name:"loopback only", url:loopbackRelayUrl, priority:100}];
  pluginMode = "fail";
  phases = await open(122, publicTarget, 25584);
  assert.ok(phases.some((p) => p.phase === "relay-connected" && p.detail === loopbackRelayUrl));
  assert.ok(!phases.some((p) => p.phase === "relay-predial-start"), "loopback relay was pre-dialed");
  bridge.close(122);
  pluginMode = "success";

  globalThis.location.hostname = "play.example";
  globalThis.location.origin = "https://play.example";
  globalThis.__gaiusBridgeUrls = [
    {name:"private candidate", url:"ws://10.0.0.9/tunnel", priority:300},
    {name:"public candidate", url:persistedRelayUrl, priority:100},
  ];
  const beforePrivateChoice = relayDialUrls.length;
  phases = await open(31, publicTarget, 25591);
  assert.ok(relayDialUrls.slice(beforePrivateChoice).includes(persistedRelayUrl),
    "private relay candidate displaced the public built-in relay");
  assert.ok(!relayDialUrls.slice(beforePrivateChoice).includes("ws://10.0.0.9/tunnel"),
    "private relay candidate was pre-dialed");
  bridge.close(31);

  // A private/LAN page can connect directly, but does not wake a public relay in advance.
  globalThis.location.hostname = "192.168.1.20";
  globalThis.location.origin = "http://192.168.1.20";
  globalThis.__gaiusBridgeUrls = [{name:"public candidate", url:persistedRelayUrl, priority:100}];
  pluginMode = "success";
  const beforeLanPage = relayDialUrls.length;
  phases = await open(25, publicTarget, 25585);
  assert.equal(relayDialUrls.length, beforeLanPage,
    "LAN-only page pre-dialed a public relay");
  assert.ok(phases.some((p) => p.phase === "direct-connected"));
  assertRelayBudget("lanPageDirectConnected", measured(phases, "direct-connected"), 250);
  bridge.close(25);

  localStorage.removeItem("gaius.lastSuccessfulRelay");
  bridge.lastSuccessfulRelayUrl = null;
  bridge.lastSuccessfulRelayCandidate = null;
  globalThis.location.hostname = "play.example";
  globalThis.location.origin = "http://play.example";
  globalThis.__gaiusBridgeUrl = undefined;
  globalThis.__gaiusBridgeUrls = [{name:"LAN relay", url:"ws://192.168.1.30/tunnel",
    use:"lan", priority:100}];
  phases = await open(28, publicTarget, 25588);
  assert.ok(!phases.some((p) => p.phase === "relay-predial-start"),
    "LAN-only candidates triggered the implicit public relay predial");
  assert.ok(phases.some((p) => p.phase === "direct-connected"));
  assertRelayBudget("lanOnlyCandidateDirectConnected", measured(phases, "direct-connected"), 250);
  bridge.close(28);

  globalThis.location.hostname = "127.0.0.1";
  globalThis.location.origin = "http://127.0.0.1";
  globalThis.__gaiusBridgeUrl = primaryRelayUrl;
  globalThis.WebSocket = LoopbackWebSocket;
  globalThis.fetch = loopbackFetch;

  // Expired and malformed preference records are ignored and fall back to current candidates.
  localStorage.setItem("gaius.lastSuccessfulRelay", JSON.stringify({savedAt:Date.now()-86400001,
    url:persistedRelayUrl, candidate:{name:"expired", url:persistedRelayUrl, priority:999}}));
  bridge.lastSuccessfulRelayUrl = null;
  bridge.lastSuccessfulRelayCandidate = null;
  globalThis.__gaiusBridgeUrls = [{name:"primary fixture", url:primaryRelayUrl, priority:100}];
  const beforeExpiredPreference = relayConnectionCount;
  pluginMode = "fail";
  phases = await open(26, publicTarget, 25586);
  assert.ok(phases.some((p) => p.phase === "relay-predial-start" && p.detail === primaryRelayUrl),
    "expired relay preference did not fall back to current candidate");
  assert.equal(relayConnectionCount, beforeExpiredPreference + 1);
  assertRelayBudget("expiredRelayPreferenceConnected", measured(phases, "relay-connected"), 450);
  bridge.close(26);
  localStorage.setItem("gaius.lastSuccessfulRelay", "{broken json");
  bridge.lastSuccessfulRelayUrl = null;
  bridge.lastSuccessfulRelayCandidate = null;
  const beforeCorruptPreference = relayConnectionCount;
  phases = await open(27, publicTarget, 25587);
  assert.ok(phases.some((p) => p.phase === "relay-predial-start" && p.detail === primaryRelayUrl),
    "corrupt relay preference did not fall back to current candidate");
  assert.equal(relayConnectionCount, beforeCorruptPreference + 1);
  assertRelayBudget("corruptRelayPreferenceConnected", measured(phases, "relay-connected"), 450);
  bridge.close(27);
  await wait(30);

  pluginMode = "fail"; relayMode = "fail";
  const errorsBefore = stats.errors;
  bridge.open(4, publicTarget, 25568);
  await deadline(() => stats.errors > errorsBefore, "both candidates fail");
  const bothFailedPhases = stats.connectPhases.filter((phase) => phase.id === 4);
  assertRelayBudget("bothFailRelayFailed", measured(bothFailedPhases, "relay-failed"), 450);

  relayMode = "delay-briefly"; pluginMode = "fail";
  phases = await open(5, publicTarget, 25569); bridge.close(5);
  assert.ok(phases.some(({phase}) => phase === "direct-failed"), "first probe must observe a real plugin failure");
  relayMode = "success";
  assertRelayBudget("firstProbeRelayConnected", measured(phases, "relay-connected"), 450);
  await wait(30);
  const afterFirst = pluginConnectionCount;
  preserveRelayPreferenceForNextOpen = true;
  phases = await open(6, publicTarget, 25569); bridge.close(6);
  assertRelayBudget("cacheHitRelayConnected", measured(phases, "relay-connected"), 120);
  assert.ok(measured(phases, "relay-predial-start") < 30, "cached channel waited for relay selection before predial");
  assert.ok(measured(phases, "relay-websocket-start") < 30,
    "cached relay WebSocket waited for relay selection");
  await wait(30);
  assert.equal(pluginConnectionCount, afterFirst, "five-minute direct failure cache was not used");
  bridge.directPluginMisses.clear();
  preserveRelayPreferenceForNextOpen = true;
  phases = await open(10, publicTarget, 25569); bridge.close(10);
  assertRelayBudget("persistentCacheRelayConnected", measured(phases, "relay-connected"), 120);
  await wait(30);
  assert.equal(pluginConnectionCount, afterFirst,
    "persistent plugin failure cache was not used after in-memory cache was cleared");
  const cachedTargetKey = "race-target.example:25569";
  bridge.directPluginMisses.delete(cachedTargetKey);
  localStorage.removeItem(`gaius.directPluginMiss:${cachedTargetKey}`);
  globalThis.location.hostname = "127.0.0.1";
  globalThis.location.origin = "http://127.0.0.1";
  localStorage.setItem("gaius.lastSuccessfulRelay", JSON.stringify({savedAt:Date.now(),
    url:persistedRelayUrl, candidate:{name:"preferred relay", url:persistedRelayUrl, priority:100}}));
  bridge.lastSuccessfulRelayUrl = null; bridge.lastSuccessfulRelayCandidate = null;
  pluginMode = "medium-success"; relayMode = "success";
  preserveRelayPreferenceForNextOpen = true;
  const pluginDialsBeforeRace = pluginConnectionCount;
  phases = await open(70, publicTarget, 25569);
  assert.equal(pluginConnectionCount, pluginDialsBeforeRace + 1,
    "cached relay without persistent miss did not race the plugin");
  assert.ok(phases.some((phase) => phase.phase === "relay-predial-start"),
    "cached relay without persistent miss did not pre-dial relay in parallel");
  assert.ok(measured(phases, "relay-predial-start") < 30,
    "cached relay race did not start the relay alongside the plugin");
  bridge.close(70);

  bridge.directPluginMisses.delete(cachedTargetKey);
  localStorage.removeItem(`gaius.directPluginMiss:${cachedTargetKey}`);
  localStorage.setItem("gaius.lastSuccessfulRelay", JSON.stringify({savedAt:Date.now(),
    url:persistedRelayUrl, candidate:{name:"preferred relay", url:persistedRelayUrl, priority:100}}));
  bridge.lastSuccessfulRelayUrl = null; bridge.lastSuccessfulRelayCandidate = null;
  pluginMode = "success"; relayMode = "delay-open";
  preserveRelayPreferenceForNextOpen = true;
  phases = await open(71, publicTarget, 25569);
  assert.ok(phases.some((phase) => phase.phase === "direct-connected"),
    "successful plugin did not win and record an RTT sample");
  bridge.close(71);
  relayMode = "delay-open";
  localStorage.setItem("gaius.lastSuccessfulRelay", JSON.stringify({savedAt:Date.now(),
    url:persistedRelayUrl, candidate:{name:"preferred relay", url:persistedRelayUrl, priority:100}}));
  bridge.lastSuccessfulRelayUrl = null; bridge.lastSuccessfulRelayCandidate = null;
  preserveRelayPreferenceForNextOpen = true;
  phases = await open(72, publicTarget, 25569);
  assert.ok(phases.some((phase) => phase.phase === "direct-connected"),
    "the measured plugin did not win against a delayed relay");
  assert.deepEqual(phases.slice(0, 3).map(({phase}) => phase),
    ["open", "relay-race-start", "relay-websocket-start"], "a plugin RTT sample delayed the relay leg");
  bridge.close(72);
  relayMode = "success";
  pluginMode = "fail";
  bridge.directPluginMisses.set("race-target.example:25569", Date.now() - 300001);
  const beforeMemoryMissExpiry = pluginConnectionCount;
  phases = await open(11, publicTarget, 25569); bridge.close(11);
  assertRelayBudget("memoryCacheExpiryRelayConnected", measured(phases, "relay-connected"), 450);
  assert.equal(pluginConnectionCount, beforeMemoryMissExpiry + 1,
    "five-minute in-memory failure cache did not expire");

  const expiredTargetKey = "race-target.example:25569";
  bridge.directPluginMisses.delete(expiredTargetKey);
  localStorage.setItem(`gaius.directPluginMiss:${expiredTargetKey}`,
    JSON.stringify({recordedAt: Date.now() - 300001}));
  const beforePersistentExpiry = pluginConnectionCount;
  phases = await open(16, publicTarget, 25569); bridge.close(16);
  assert.equal(pluginConnectionCount, beforePersistentExpiry + 1,
    "expired five-minute persistent failure cache still skipped the plugin");
  assertRelayBudget("persistentCacheExpiryRelayConnected", measured(phases, "relay-connected"), 450);

  const storageMethods = {
    getItem: localStorage.getItem, setItem: localStorage.setItem, removeItem: localStorage.removeItem,
  };
  localStorage.getItem = localStorage.setItem = localStorage.removeItem = () => {
    throw new Error("storage disabled");
  };
  try {
    pluginMode = "fail"; relayMode = "delay-briefly";
    const beforeStorageFallback = pluginConnectionCount;
    phases = await open(12, publicTarget, 25575); bridge.close(12);
    assertRelayBudget("storageFallbackRelayConnected", measured(phases, "relay-connected"), 450);
    relayMode = "success";
    phases = await open(13, publicTarget, 25575); bridge.close(13);
    assertRelayBudget("storageFallbackCacheRelayConnected", measured(phases, "relay-connected"), 120);
    assert.equal(pluginConnectionCount, beforeStorageFallback + 1,
      "blocked localStorage did not fall back to the 30-second memory cache");
  } finally {
    Object.assign(localStorage, storageMethods);
  }

  pluginMode = "hang";
  bridge.open(7, publicTarget, 25570); bridge.close(7);
  await wait(100);
  assert.equal(relaySockets.size, 0, "cancel left an active relay socket");

  {
    const id = 123;
    const registry = bridge.relayRegistryPromise;
    let resolveRegistry;
    bridge.relayRegistryPromise = new Promise(resolve => { resolveRegistry = resolve; });
    const lateRelay = `ws://127.0.0.1:${relayBPort}/tunnel`;
    const fetchBefore = globalThis.fetch;
    let lateProbes = 0;
    globalThis.fetch = (url, options) => {
      const parsed = new URL(url);
      if (Number(parsed.port) === relayBPort && parsed.pathname === "/relay-node/v1") lateProbes++;
      return fetchBefore(url, options);
    };
    try {
      bridge.open(id, publicTarget, 25908);
      const entry = bridge.channels.get(id);
      assert.ok(entry.relayPreflightPromise, "Join did not start relay preparation");
      bridge.close(id);
      resolveRegistry([{name:"late registry relay", url:lateRelay, priority:100}]);
      await entry.relayPreflightPromise;
      assert.equal(lateProbes, 0, "registry response probed a target after Join cancellation");
      assert.ok(!entry.candidateUrls.has(lateRelay));
    } finally {
      bridge.close(id);
      globalThis.fetch = fetchBefore;
      bridge.relayRegistryPromise = registry;
    }
    await deadline(() => pluginSockets.size === 0 && relaySockets.size === 0, "late registry cleanup");
  }

  relayMode = "predial-fail";
  const failedPredialConnectionsBefore = relayConnectionCount;
  bridge.open(17, publicTarget, 25577);
  await deadline(() => relayConnectionCount > failedPredialConnectionsBefore &&
    relaySockets.size === 0 && bridge.channels.get(17)?.preconnectedRelay === null,
    "failed predial socket cleared before plugin completes");
  assert.equal(bridge.channels.get(17).currentCandidate.direct, true,
    "failed-predial assertion ran after promotion instead of during the plugin probe");
  assert.equal(bridge.channels.get(17).connected, false);
  bridge.close(17);
  await deadline(() => pluginSockets.size === 0, "failed-predial plugin cleanup");

  relayMode = "reorder";
  const relayBUrl = `ws://127.0.0.1:${relayBPort}/tunnel`;
  const reorderConnectionsBefore = relayConnectionCount;
  const reorderBConnectionsBefore = relayBConnectionCount;
  globalThis.__gaiusBridgeUrls = [
    {name:"relay A", url:primaryRelayUrl, priority:100},
    {name:"relay B", url:relayBUrl, priority:50},
  ];
  const reorderConnectedBefore = stats.connected;
  bridge.open(18, publicTarget, 25578);
  await deadline(() => releaseRelayBManifest && stats.connected > reorderConnectedBefore,
    "prepared relay A wins while relay B preflight is pending");
  assert.equal(bridge.channels.get(18).currentCandidate.url, primaryRelayUrl,
    "prepared relay A did not win after its connected response arrived first");
  const preparedRelayA = bridge.channels.get(18).ws;
  assert.equal(preparedRelayA.url, primaryRelayUrl,
    "prepared relay A socket was not reused for the winning session");
  assert.equal(bridge.channels.get(18).preconnectedRelay, null);
  releaseRelayBManifest();
  releaseRelayBManifest = null;
  await wait(30);
  assert.equal(relayConnectionCount, reorderConnectionsBefore + 1,
    "the winning relay should use only its prepared socket");
  assert.equal(relayBConnectionCount, reorderBConnectionsBefore,
    "late preflight opened a second relay after a winner connected");
  phases = stats.connectPhases.filter((phase) => phase.id === 18);
  assertRelayBudget("reorderedRelayConnected", measured(phases, "relay-connected"), 450);
  bridge.close(18);
  globalThis.__gaiusBridgeUrls = [{name:"primary fixture", url:primaryRelayUrl, priority:100}];
  relayMode = "success";
  await deadline(() => relaySockets.size === 0 && pluginSockets.size === 0,
    "re-ranked relay cleanup");

  pluginMode = "fail";
  // The preceding rerank selected B. Model a matching last-success/default
  // warmup here; the losing speculative socket is covered above.
  globalThis.location.hostname = "play.example";
  globalThis.location.origin = "https://play.example";
  globalThis.__gaiusBridgeUrl = undefined;
  globalThis.__gaiusBridgeUrls = [{name:"public warm fixture", url:persistedRelayUrl, priority:100}];
  globalThis.WebSocket = class WarmMappedWebSocket extends LoopbackWebSocket {
    constructor(url, protocols) { super(String(url).replace(persistedRelayPrefix,
      `ws://127.0.0.1:${relayPort}`), protocols); }
  };
  bridge.lastSuccessfulRelayUrl = persistedRelayUrl;
  bridge.lastSuccessfulRelayCandidate = {name:"public warm fixture", url:persistedRelayUrl, priority:100};
  // Origin warming belongs to the launcher test; keep list socket fixtures offline.
  globalThis.__gaiusWarmRelayOrigins = () => {};
  bridge.warmServerList(false);
  assert.equal(bridge.serverListWarmupEntry ?? null, null, "empty server list warmed a relay");
  globalThis.__gaiusMinecraftState = {screen:"net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen"};
  const connectControlsBeforeWarmup = connectCount;
  let warmTimeouts = 0, warmIntervals = 0;
  globalThis.setTimeout = (...args) => { warmTimeouts++; return realSetTimeout(...args); };
  globalThis.setInterval = (...args) => { warmIntervals++; return realSetInterval(...args); };
  try {
    bridge.warmServerList(true);
    assert.equal(warmTimeouts, 1, "CONNECTING list socket must have one application-layer dial timer");
  } finally { globalThis.setTimeout = realSetTimeout; globalThis.setInterval = realSetInterval; }
  assert.equal(warmIntervals, 0, "server-list warming installed a polling interval");
  await deadline(() => bridge.serverListWarmupEntry?.preconnectedRelay?.socket.readyState === WebSocket.OPEN,
    "saved-server relay warmup");
  assert.ok(bridge.serverListWarmupEntry.preconnectedRelay.timeout, "OPEN list socket has no expiry timer");
  await wait(20);
  assert.equal(connectCount, connectControlsBeforeWarmup,
    "targetless warm socket sent a connect control before a join existed");
  const warmedSocket = bridge.serverListWarmupEntry.preconnectedRelay.socket;
  const adoptingAttempt = 301;
  bridge.reserveServerListWarmup(publicTarget, 25581, adoptingAttempt);
  bridge.warmServerList(false); // native removed runs before asynchronous connect
  assert.equal(bridge.joinWarmupEntry.preconnectedRelay.socket, warmedSocket);
  // A different join to the same address must neither take nor release the reservation.
  bridge.cancelServerListWarmup(adoptingAttempt - 1);
  phases = await open(117, publicTarget, 25581, adoptingAttempt + 1);
  assert.notEqual(bridge.channels.get(117).ws, warmedSocket);
  bridge.close(117);
  assert.equal(bridge.joinWarmupEntry.preconnectedRelay.socket, warmedSocket);
  const connectControlsBeforeAdoption = connectCount;
  const urlRequestsBeforeAdoption = relayUrlConnectRequests.length;
  bridge.urlConnectCapabilities.set(persistedRelayUrl,
    {enabled:true, expiresAt:Date.now() + 60000});
  phases = await open(21, publicTarget, 25581, adoptingAttempt);
  assert.equal(bridge.channels.get(21).ws, warmedSocket, "channel did not adopt list warmup");
  assert.equal(connectCount, connectControlsBeforeAdoption + 1,
    "adopted warm socket did not send exactly one immediate connect control");
  assert.equal(relayUrlConnectRequests.length, urlRequestsBeforeAdoption,
    "warm socket adoption opened a second connection with c=");
  assert.ok(phases.some((p) => p.phase === "url-connect=false"));
  bridge.urlConnectCapabilities.clear();
  assert.equal(relayConnectMessages.at(-1).host, publicTarget);
  assert.equal(relayConnectMessages.at(-1).port, 25581);
  assertRelayBudget("serverListWarmRelayConnected", measured(phases, "relay-connected"), 120);
  // Removal after ownership transfer must leave the channel's socket alive.
  bridge.cancelServerListWarmup(adoptingAttempt);
  assert.equal(bridge.joinWarmupEntry, null);
  assert.equal(warmedSocket.readyState, WebSocket.OPEN);
  bridge.close(21);
  // Removal before transfer closes the socket and clears the transferred TTL.
  bridge.warmServerList(true);
  bridge.reserveServerListWarmup(publicTarget, 25581, 303);
  const cancelledWarmup = bridge.joinWarmupEntry.preconnectedRelay;
  bridge.warmServerList(false);
  bridge.cancelServerListWarmup(303);
  assert.equal(bridge.joinWarmupEntry, null);
  assert.equal(cancelledWarmup.timeout._destroyed, true);
  await deadline(() => cancelledWarmup.socket.readyState === WebSocket.CLOSED, "cancelled reservation closed");
  const deadPreparedSocket = {
    readyState: WebSocket.CLOSED,
    close() { this.closed = true; },
  };
  bridge.serverListWarmupEntry = {
    closed: false,
    connected: false,
    preconnectedRelay: {
      candidate: {name:"public warm fixture", url:persistedRelayUrl, priority:100},
      socket: deadPreparedSocket,
      failed: false,
    },
    expiresAt: Date.now() + 90000,
  };
  const relayDialsBeforeDeadWarmup = relayConnectionCount;
  bridge.warmServerList(true);
  await deadline(() => bridge.serverListWarmupEntry?.preconnectedRelay?.socket.readyState === WebSocket.OPEN,
    "dead warmup socket redial");
  const replacementWarmSocket = bridge.serverListWarmupEntry.preconnectedRelay.socket;
  assert.notEqual(replacementWarmSocket, deadPreparedSocket,
    "closed pre-dial socket was reused");
  assert.equal(deadPreparedSocket.closed, true,
    "closed pre-dial socket was not retired before redial");
  assert.ok(relayConnectionCount > relayDialsBeforeDeadWarmup,
    "closed pre-dial socket did not trigger an immediate relay dial");
  bridge.warmServerList(false);
  await deadline(() => replacementWarmSocket.readyState === WebSocket.CLOSED,
    "replacement warmup cleanup");
  // Leaving before the first screen snapshot must also close immediately.
  bridge.warmServerList(true);
  const earlyLeavingSocket = bridge.serverListWarmupEntry.preconnectedRelay.socket;
  bridge.warmServerList(false);
  await deadline(() => earlyLeavingSocket.readyState === WebSocket.CLOSED, "early native removal cleanup");
  bridge.warmServerList(true);
  await wait(120);
  const leavingSocket = bridge.serverListWarmupEntry.preconnectedRelay.socket;
  globalThis.__gaiusMinecraftState = {screen:"net.minecraft.client.gui.screens.TitleScreen"};
  bridge.warmServerList(false); // JoinMultiplayerScreen.removed()/onClose, no state polling.
  await deadline(() => bridge.serverListWarmupEntry === null, "leaving server-list cleanup");
  await deadline(() => leavingSocket.readyState === WebSocket.CLOSED, "list warmup socket close");
  assert.equal(bridge.serverListWarmupTimer, undefined, "list lifecycle must not install a polling timer");
  globalThis.__gaiusMinecraftState = {screen:"net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen"};
  bridge.warmServerList(true);
  await wait(120);
  const expiringSocket = bridge.serverListWarmupEntry.preconnectedRelay.socket;
  const realNow = Date.now;
  const expiresNow = realNow() + 91000;
  try {
    Date.now = () => expiresNow;
    bridge.serverListWarmupEntry.preconnectedRelay.timeout._onTimeout();
    await deadline(() => bridge.serverListWarmupEntry === null, "90-second warmup TTL");
  } finally { Date.now = realNow; }
  await deadline(() => expiringSocket.readyState === WebSocket.CLOSED, "expired warmup close");
  await wait(150);
  assert.equal(bridge.serverListWarmupEntry, null, "idle TTL automatically reopened warm socket");
  bridge.warmServerList(true);
  const reservedExpirySocket = bridge.serverListWarmupEntry.preconnectedRelay.socket;
  await deadline(() => reservedExpirySocket.readyState === WebSocket.OPEN, "open warmup before reservation expiry test");
  bridge.serverListWarmupEntry.expiresAt = Date.now() + 25;
  bridge.reserveServerListWarmup(publicTarget, 25581, 304);
  bridge.warmServerList(false);
  await deadline(() => bridge.joinWarmupEntry === null, "join reservation keeps original TTL");
  await deadline(() => reservedExpirySocket.readyState === WebSocket.CLOSED, "unadopted reservation closed");
  // Preserve only the persisted miss/relay preference as a refresh would. Run
  // five Joins against the same endpoint, with transport and preflight witnesses.
  const warmJoinTelemetry = [];
  const warmPort = 25581;
  const warmKey = `${publicTarget}:${warmPort}`;
  localStorage.setItem(`gaius.directPluginMiss:${warmKey}`, JSON.stringify({recordedAt: Date.now()}));
  const savedFetchForWarmJoins = globalThis.fetch;
  const warmJoinActions = [];
  globalThis.fetch = (...args) => {
    warmJoinActions.push("fetch");
    return savedFetchForWarmJoins(...args);
  };
  const WarmSocket = globalThis.WebSocket;
  globalThis.WebSocket = class extends WarmSocket {
    constructor(url, protocols) {
      super(url, protocols);
      warmJoinActions.push("websocket");
    }
  };
  try {
    for (const [index, scenario] of ["none", "open", "expired", "connecting", "open"].entries()) {
      const id = 150 + index;
      const attempt = 350 + index;
      const pluginDials = pluginConnectionCount;
      const pluginAttempts = stats.directAttempts;
      const controls = connectCount;
      let reservedSocket;
      if (scenario !== "none") {
        bridge.warmServerList(true);
        reservedSocket = bridge.serverListWarmupEntry.preconnectedRelay.socket;
        if (scenario !== "connecting") {
          await deadline(() => reservedSocket.readyState === WebSocket.OPEN, "warm fixture open");
        } else {
          assert.equal(reservedSocket.readyState, WebSocket.CONNECTING);
        }
        bridge.reserveServerListWarmup(publicTarget, warmPort, attempt);
        if (scenario === "expired") bridge.joinWarmupEntry.expiresAt = Date.now() - 1;
        bridge.warmServerList(false);
      }
      warmJoinActions.length = 0;
      bridge.directPluginMisses.clear();
      bridge.lastSuccessfulRelayUrl = null;
      bridge.lastSuccessfulRelayCandidate = null;
      preserveRelayPreferenceForNextOpen = true;
      const joinPhases = (await open(id, publicTarget, warmPort, attempt)).map(({phase}) => phase);
      const reused = scenario === "open" || scenario === "connecting";
      assert.deepEqual(joinPhases.slice(0, 3),
        ["open", "relay-race-start", reused ? "relay-predial-reused" : "relay-websocket-start"]);
      assert.equal(joinPhases.filter((phase) => phase === "relay-websocket-start").length, reused ? 0 : 1);
      assert.ok(joinPhases.includes("race-winner=relay") && joinPhases.includes("relay-connected"));
      assert.equal(stats.directAttempts, pluginAttempts);
      assert.equal(pluginConnectionCount, pluginDials);
      assert.equal(connectCount, controls + 1);
      assert.equal(bridge.channels.get(id).ws === reservedSocket, reused);
      assert.equal(warmJoinActions.filter((action) => action === "websocket").length, reused ? 0 : 1);
      if (!reused) assert.equal(warmJoinActions[0], "websocket", "preflight ran before WebSocket construction");
      if (scenario === "expired") {
        await deadline(() => reservedSocket.readyState === WebSocket.CLOSED, "expired predial retired");
      }
      warmJoinTelemetry.push({scenario, phases: joinPhases});
      bridge.close(id);
      await deadline(() => relaySockets.size === 0, "warm Join socket retired");
    }
  } finally {
    globalThis.fetch = savedFetchForWarmJoins;
    globalThis.WebSocket = WarmSocket;
    localStorage.removeItem(`gaius.directPluginMiss:${warmKey}`);
    bridge.directPluginMisses.delete(warmKey);
  }
  delete globalThis.__gaiusMinecraftState;
  globalThis.location.hostname = "127.0.0.1";
  globalThis.location.origin = "http://127.0.0.1";
  globalThis.__gaiusBridgeUrl = primaryRelayUrl;
  globalThis.__gaiusBridgeUrls = [{name:"primary fixture", url:primaryRelayUrl, priority:100}];
  globalThis.WebSocket = LoopbackWebSocket;

  pluginMode = "success";
  const concurrentBaseline = stats.connected;
  bridge.open(8, publicTarget, 25571); bridge.open(9, publicTarget, 25572);
  await deadline(() => stats.connected >= concurrentBaseline + 2, "concurrent channels");
  bridge.close(8); bridge.close(9); await wait(30);
  assert.equal(relaySockets.size, 0, "concurrent cleanup leaked relay sockets");
  assert.equal(pluginSockets.size, 0, "plugin sockets remained after cleanup");
  const warmupDeadlineCases = await verifyWarmupDeadlines();
  const targetPrewarmCases = await verifyTargetPrewarm();
  assert.equal(relaySockets.size, 0, "target prewarm regressions leaked relay sockets");
  assert.equal(pluginSockets.size, 0, "target prewarm regressions leaked plugin sockets");
  console.log(JSON.stringify({ok:true, startupRelayWork, raceEvidence, warmJoinTelemetry, warmupDeadlineCases, pluginBudgetChecksMillis:{unmeasured:pluginBudget(undefined, undefined), measured40:rearmedBudget}, timingsMillis:timing,
    cases:["bridge-init-zero-relay-fetch-websocket-timers-storage","relayWarm-off-no-prewarm","relayRace-off-plugin-first-serial-fallback","relayUrl-unknown-uses-control-without-waiting","relayUrl-stalled-health-aborted","relayUrl-stalled-health-during-plugin-race","relayUrl-expired-and-token-capabilities","relayUrl-stale-retries-control-on-error-close-timeout","relayUrl-cached-capability-uses-c-without-control","relayUrl-token-local-skin-oversize-hot-socket-fallback","relayUrl-preserves-oversized-session-table-in-control","relayUrl-off-control-fallback","relayUrl-default-race-sends-only-url","unmeasured-budget-350ms-measured-rearm-250ms","gated-plugin-winner-close-before-mc-write", "gated-relay-winner-close-before-mc-write-and-no-plugin-measurement","real-plugin-timeout-records-miss", "plugin-first-success-relay-loser-closed", "plugin-300ms-relay-wins","plugin-failure-relay-success","explicit-relay-refused-once",
      "plugin-hang-relay-reuse","filtered-cold-t0-predial","saved-server-warmup-adoption-and-leave-cleanup","cancel-reservation-before-and-after-transfer","same-target-different-attempt-cannot-adopt-or-cancel","dead-prewarm-redials-immediately","both-fail","cache-hit","persistent-cache-after-refresh",
      "24-hour-relay-preference-refresh-at-t0-ws-without-preflight-wait","failed-remembered-relay-normal-candidate-fallback",
      "cold-no-memory-built-in-predial-t0","prewarm-failure-isolated",
      "localhost-loopback-excluded-with-public-alternative","loopback-and-mapped-targets-no-predial",
      "loopback-relay-serial-fallback-only","private-relay-excluded-with-public-alternative","lan-page-does-not-predial-public-relay",
      "lan-only-candidates-no-default-public-predial","163ms-relay-rtt-raced-connected-180-320ms",
      "expired-and-corrupt-relay-preference-fallback","five-minute-memory-cache-expiry","90-second-warmup-expiry","five-minute-persistent-cache-expiry","storage-blocked-memory-fallback","cancel-no-leak",
      "failed-predial-reference-cleared","first-connected-prepared-relay-wins-before-late-preflight","concurrent",
      "cancel-ignores-late-registry-target-probe","relayUrl-incompatible-ack-retries-control-while-plugin-negotiates",
      "direct-miss-refresh-eager-relay-without-predial", "direct-miss-reuses-open-predial",
      "direct-miss-replaces-expired-open-predial", "direct-miss-reuses-connecting-predial", ...targetPrewarmCases],
    rejectedRelayReplies, relayConnectControls:connectCount, pluginSockets:pluginSockets.size, relaySockets:relaySockets.size}));
} finally {
  globalThis.fetch = originalFetch;
  for (const socket of filteredSockets) socket.destroy();
  if (bridge.serverListWarmupTimer) clearTimeout(bridge.serverListWarmupTimer);
  for (const socket of [...relaySockets, ...pluginSockets]) socket.terminate();
  await Promise.all([new Promise((r) => relayWss.close(r)), new Promise((r) => relayBWss.close(r)), new Promise((r) => pluginWss.close(r))]);
  await Promise.all([new Promise((r) => relayHttp.close(r)), new Promise((r) => relayBHttp.close(r)), new Promise((r) => pluginHttp.close(r))]);
}

// Keep clock control inside the fresh bridge, not ws, fetch, or the original smoke.
function warmupClock(watchIdle, allTimers = false) {
  let now = Date.now();
  const timers = new Map();
  const selective = new Set([120, 150, 4000, 15000]);
  function schedule(run, delay, interval) {
    const timer = {};
    timers.set(timer, {at:now + delay, run, interval});
    return timer;
  }
  return {
    Date: class extends Date { static now() { return now; } },
    setTimeout(callback, delay, ...args) {
      if (!allTimers && !selective.has(delay) && !(watchIdle && delay <= 15000)) {
        return realSetTimeout(callback, delay, ...args);
      }
      return schedule(() => callback(...args), delay);
    },
    setInterval(callback, delay, ...args) {
      assert.ok(allTimers || watchIdle, "unexpected interval in relay fixture");
      delay = Math.max(1, delay || 0);
      return schedule(() => callback(...args), delay, delay);
    },
    clearTimeout(timer) {
      if (!timers.delete(timer) && !allTimers) clearTimeout(timer);
    },
    hasTimer: (timer) => timers.has(timer),
    timerCount: () => timers.size,
    advance(ms, dispatch = true) {
      const end = now + ms;
      while (dispatch) {
        const next = [...timers].sort(([, a], [, b]) => a.at - b.at)[0];
        if (!next || next[1].at > end) break;
        const [timer, {at, run, interval}] = next;
        now = Math.max(now, at);
        if (interval) timers.get(timer).at += interval;
        else timers.delete(timer);
        run();
      }
      now = end;
    },
  };
}

// The two audit fixtures run the installed JSBody with controlled socket events
// and a clock that can advance independently of the browser's timer queue.
async function verifyWarmupDeadlines() {
  const relay = "wss://timer-relay.example/tunnel";
  const port = 25565;
  const attempt = 73;
  const id = 17;
  const cases = [];
  function createFixture() {
    const clock = warmupClock(false, true);
    const sockets = [];
    const context = {
      URL, URLSearchParams, AbortController, TextEncoder, TextDecoder, performance, queueMicrotask, console,
      Date:clock.Date, setTimeout:clock.setTimeout, clearTimeout:clock.clearTimeout,
      setInterval:clock.setInterval, clearInterval:clock.clearTimeout,
      location:new URL("https://timer-client.example/?relayUrl=0"),
      localStorage:{getItem:() => null, setItem() {}, removeItem() {}},
      fetch:async () => { throw new Error("no external networking"); },
      __gaiusBridgeUrl:relay, __gaiusBridgeUrls:[{url:relay, priority:100}],
      __gaiusDefaultRelayRegistries:false, __gaiusRelayRegistryUrls:[],
      __gaiusDirectPlugin:false,
      WebSocket:class {
        static CONNECTING = WebSocket.CONNECTING;
        static OPEN = WebSocket.OPEN;
        static CLOSING = WebSocket.CLOSING;
        static CLOSED = WebSocket.CLOSED;
        readyState = WebSocket.CONNECTING;
        sent = [];
        constructor(url) { this.url = url; sockets.push(this); }
        close() { this.readyState = WebSocket.CLOSED; }
        networkOpen() {
          assert.equal(this.readyState, WebSocket.CONNECTING);
          this.readyState = WebSocket.OPEN;
          this.onopen?.();
        }
        send(frame) {
          assert.equal(this.readyState, WebSocket.OPEN);
          this.sent.push(JSON.parse(frame).type);
        }
      },
    };
    context.window = context;
    for (const script of [bridgeScript, outboundScript, inboundScript]) {
      runInNewContext(`(function() {${script}\n})();`, context);
    }
    const state = context.__gaiusNettyBridge;
    state.warmServerList(true);
    assert.equal(sockets.length, 1);
    return {state, sockets, clock};
  }

  for (const [elapsed, dispatch] of [[90000, true], [91000, true], [90000, false]]) {
    const {state, sockets, clock} = createFixture();
    const [first] = sockets;
    clock.advance(89999);
    assert.equal(first.readyState, WebSocket.CONNECTING);
    clock.advance(elapsed - 89999, dispatch);
    if (!dispatch) first.networkOpen();
    assert.equal(state.serverListWarmupEntry, null);
    assert.equal(first.readyState, WebSocket.CLOSED);
    assert.deepEqual(first.sent, []);
    assert.equal(sockets.length, 1, "expiry must not automatically redial");
    state.warmServerList(true);
    assert.equal(sockets.length, 2, "expired CONNECTING socket was reused");
    state.warmServerList(false);
    assert.ok(sockets.every(({readyState}) => readyState === WebSocket.CLOSED));
    assert.equal(clock.timerCount(), 0);
    cases.push(`list-dial-${elapsed}ms-${dispatch ? "timer" : "late-open"}`);
  }

  for (const initiallyOpen of [true, false]) {
    for (const elapsed of [1000, 29999, 30000, 31000]) {
      const {state, sockets, clock} = createFixture();
      const [reserved] = sockets;
      if (initiallyOpen) reserved.networkOpen();
      state.reserveServerListWarmup(publicTarget, port, attempt);
      const timer = state.joinWarmupEntry.preconnectedRelay.timeout;
      state.warmServerList(false);
      clock.advance(elapsed, false);
      assert.ok(clock.hasTimer(timer), "expiry callback ran before the Join check");
      state.open(id, publicTarget, port, attempt);
      const entry = state.channels.get(id);
      const reused = entry.preconnectedRelay?.socket === reserved || entry.ws === reserved;
      assert.equal(reused, elapsed < 30000, `${initiallyOpen ? "OPEN" : "CONNECTING"} at ${elapsed}ms`);
      if (reused && !initiallyOpen) reserved.networkOpen();
      await Promise.resolve(); // Promotion of an already OPEN socket queues its open handler.
      if (!reused) {
        assert.equal(reserved.readyState, WebSocket.CLOSED);
        assert.equal(sockets.length, 2, "expired reservation did not start a fresh relay connection");
      }
      assert.deepEqual(reserved.sent, reused ? ["connect"] : []);
      assert.equal(state.joinWarmupEntry, null);
      assert.ok(!clock.hasTimer(timer), "handoff left the reservation timer armed");
      state.close(id);
      assert.ok(sockets.every(({readyState}) => readyState === WebSocket.CLOSED));
      cases.push(`reservation-${initiallyOpen ? "open" : "connecting"}-${elapsed}ms-delayed-timer`);
    }
  }

  const {state, sockets, clock} = createFixture();
  clock.advance(89000);
  state.reserveServerListWarmup(publicTarget, port, attempt);
  state.warmServerList(false);
  clock.advance(1000, false);
  state.open(id, publicTarget, port, attempt);
  assert.equal(sockets[0].readyState, WebSocket.CLOSED);
  assert.deepEqual(sockets[0].sent, []);
  assert.equal(sockets.length, 2, "reservation extended the original dial deadline");
  state.close(id);
  cases.push("reservation-keeps-earlier-dial-deadline");
  return cases;
}

async function withRsMock({capable = true, trace = true, stallHealth = false, stallUpgrade = false,
  direct = false, watchIdle = false} = {}, test) {
  const clock = warmupClock(watchIdle);
  const clients = [], servers = [], health = [], frames = [];
  const values = new Map();
  const protocolTable = {formatVersion:1, sha256:"a".repeat(64)};
  const heldUpgrades = new Set();
  const context = {
    AbortController, URL, URLSearchParams, performance, queueMicrotask, console,
    Date:clock.Date, setTimeout:clock.setTimeout, clearTimeout:clock.clearTimeout,
    setInterval:clock.setInterval, clearInterval:clock.clearTimeout,
    location:{...globalThis.location, search:`?relayUrl=0${trace ? "&rd32trace=1" : ""}`},
    localStorage:{getItem:(key) => values.get(key) ?? null,
      setItem:(key, value) => values.set(key, value), removeItem:(key) => values.delete(key)},
    fetch(url, options) {
      if (new URL(url).pathname !== "/health") return loopbackFetch(url, options);
      const request = {url:String(url), signal:options?.signal};
      health.push(request);
      return loopbackFetch(url, options).then((response) => ({ok:response.ok, async json() {
        return request.payload = await response.json();
      }}));
    },
    WebSocket:class extends LoopbackWebSocket {
      constructor(url, protocols) {
        super(url, protocols);
        const received = [];
        const client = {ws:this, received, sent:[]};
        clients.push(client);
        this.on("message", (data, binary) => { if (!binary) received.push(JSON.parse(String(data)).type); });
        this.on("close", (code) => { client.closeCode = code; });
        this.on("error", () => {});
      }
      send(data, ...args) {
        clients.find(({ws}) => ws === this).sent.push(data);
        super.send(data, ...args);
      }
    },
    __gaiusBridgeUrl:primaryRelayUrl,
    __gaiusBridgeUrls:[{url:primaryRelayUrl, name:"rs mock", priority:100}],
    __gaiusDirectPlugin:direct, __gaiusRelayProtocolDeclaration:protocolTable,
    __gaiusDefaultRelayRegistries:false, __gaiusRelayRegistryUrls:[],
  };
  context.window = context;
  rsMock = {capable, holdUpgrade(socket) {
    if (!stallUpgrade || !servers.length) return false;
    heldUpgrades.add(socket);
    return true;
  }, accept(ws, request) {
    servers.push({ws, url:request.url});
    ws.on("message", (data, binary) => {
      const message = binary ? {type:"binary"} : JSON.parse(String(data));
      frames.push({ws, message});
      if (message.type === "connect") {
        ws.send(JSON.stringify({type:"connecting", host:message.host, port:message.port,
          targetConnectTimeoutMs:1500}));
      }
    });
  }};
  stallRelayHealth = stallHealth;
  let state;
  try {
    const scripts = [bridgeScript, outboundScript, inboundScript];
    runInNewContext(scripts.map((script) => `(function() {${script}\n})();`).join("\n"), context);
    state = context.__gaiusNettyBridge;
    assert.equal(state.stats, context.window.__gaiusNetworkStats);
    assert.equal(state.stats.relaySegments !== undefined, trace, "Join traces must be opt-in");
    await test({state, clock, clients, servers, health, frames, protocolTable, heldUpgrades});
  } finally {
    state?.warmServerList(false);
    for (const id of state?.channels.keys() ?? []) state.close(id);
    for (const socket of heldUpgrades) socket.destroy();
    const sockets = [...clients, ...servers].map(({ws}) => ws);
    for (const ws of sockets) ws.terminate();
    stallRelayHealth = false;
    rsMock = null;
    await deadline(() => sockets.every((ws) => ws.readyState === WebSocket.CLOSED), "rs mock sockets retired");
  }
}

async function verifyTargetPrewarm() {
  const port = 25710;
  const attempt = 871;
  const id = 872;
  const cases = [];
  const run = async (name, options, test) => {
    try { await withRsMock(options, test); }
    catch (cause) { throw new Error(`Target prewarm: ${name}: ${cause.message}`, {cause}); }
    cases.push(name);
  };
  const select = ({state, clock}, selectedPort = port) => {
    state.serverListTarget(publicTarget, selectedPort);
    clock.advance(120);
  };
  const wireConnect = async (fixture, index, prewarm = true) => {
    await deadline(() => fixture.frames.length > index, `rs mock connect frame ${index}`);
    const frame = fixture.frames[index];
    const clientsWithAck = () => fixture.clients.filter(({received}) => received.includes("connecting"));
    await deadline(() => clientsWithAck().length > index, "relay connecting ACK delivered");
    const client = clientsWithAck()[index];
    assert.equal(fixture.servers.find(({ws}) => ws === frame.ws).url, "/tunnel");
    assert.equal(frame.message.type, "connect");
    assert.equal(frame.message.host, publicTarget);
    assert.equal(frame.message.port, port);
    assert.equal(frame.message.prewarm === true, prewarm);
    assert.deepEqual(frame.message.protocolTable, fixture.protocolTable);
    return {...frame, client};
  };
  const ready = async ({ws, message, client}, protocolTable) => {
    // RelayNode replies to text connect without echoing the offered protocol table.
    answerConnect("relay", ws, message)({protocolTable});
    await deadline(() => client.received.includes("connected"), "relay connected ACK delivered");
  };
  const reserve = (state) => {
    state.reserveServerListWarmup(publicTarget, port, attempt);
    state.warmServerList(false);
  };
  const awaitJoin = async ({state}) => {
    await deadline(() => state.stats.connected || state.channels.get(id)?.errors.length, "rs mock Join");
    assert.equal(state.pollError(id), null, "speculative connection failure escaped to Join");
    assert.equal(state.stats.connected, 1);
    return state.channels.get(id).ws;
  };
  function assertOrder(...timestamps) {
    assert.ok(timestamps.every(Number.isFinite), `missing trace timestamps: ${timestamps}`);
    for (let i = 1; i < timestamps.length; i++) {
      assert.ok(timestamps[i - 1] <= timestamps[i], `trace timestamps out of order: ${timestamps}`);
    }
  }
  function joinLegs(state, ...modes) {
    const legs = state.stats.relaySegments.filter((segment) => segment.id === id);
    assert.deepEqual(Array.from(legs, ({mode}) => mode), modes);
    for (const leg of legs) {
      assert.equal(leg.target, `${publicTarget}:${port}`);
      assert.equal(leg.relay, primaryRelayUrl);
      assert.equal(leg.joinSource, "server-list");
      assert.equal(leg.joinAt, legs[0].joinAt);
    }
    return legs;
  }

  await run("target-prewarm-selection-only-debounce-dedupe-cancel", {}, async (fixture) => {
    const {state, clock, clients, health, frames} = fixture;
    select(fixture); // Calls made before the list enters are ignored.
    assert.equal(clients.length, 0);
    assert.equal(health.length, 0);
    state.warmServerList(true);
    clock.advance(120);
    await deadline(() => clients[0]?.ws.readyState === WebSocket.OPEN, "targetless list predial");
    assert.equal(health.length, 0, "list enter alone ran a target capability probe");
    assert.equal(frames.length, 0, "list enter alone sent a connect");
    state.serverListTarget(publicTarget, port + 1);
    clock.advance(60);
    state.serverListTarget(publicTarget, port);
    clock.advance(119);
    await Promise.resolve();
    assert.equal(health.length, 0, "debounce ran before the latest target settled");
    state.serverListTarget(publicTarget, port);
    clock.advance(1);
    const warm = await wireConnect(fixture, 0);
    assert.equal(health.length, 1, "duplicate selection moved the debounce or repeated health");
    assert.equal(health[0].url, `http://primary-relay.example:${relayPort}/health`);
    await ready(warm);
    const warmDials = clients.length;
    select(fixture);
    assert.equal(clients.length, warmDials, "same selection dialed again");
    assert.equal(frames.length, 1);
    assert.equal(warm.client.sent.length, 1, "same selection sent another connect");
    assert.equal(state.stats.connected, 0, "speculative target was promoted without Join");
    assert.equal(state.stats.relaySegments.length, 0, "selection was traced as a Join");
    state.serverListTarget(null, 0);
    await deadline(() => warm.client.ws.readyState === WebSocket.CLOSED, "clear selection closed prewarm");
    state.serverListTarget(publicTarget, port + 2);
    state.warmServerList(false);
    clock.advance(120);
    assert.equal(clients.length, warmDials, "leaving did not cancel the pending selection");
    assert.equal(frames.length, 1, "prewarm emitted another frame without Join");
  });

  for (const leave of [false, true]) {
    await run(leave ? "target-prewarm-leave-aborts-health" : "target-prewarm-health-150ms-budget",
      {stallHealth:true}, async (fixture) => {
        const {state, clock, clients, health, frames} = fixture;
        const beforeRequest = relayHealthRequests, beforeAbort = relayHealthAborts;
        state.warmServerList(true);
        select(fixture);
        await deadline(() => relayHealthRequests > beforeRequest, "stalled target capability probe arrived");
        assert.equal(health.length, 1);
        clock.advance(149);
        assert.equal(health[0].signal.aborted, false);
        if (leave) state.warmServerList(false);
        else clock.advance(1);
        assert.equal(health[0].signal.aborted, true, "stalled target capability request not aborted");
        await deadline(() => relayHealthAborts > beforeAbort, "stalled /health closed server-side");
        assert.equal(clients.length, 1, "unconfirmed capability dialed a target socket");
        assert.equal(frames.length, 0);
      });
  }

  await run("old-relay-keeps-legacy-predial-and-ordinary-join", {capable:false, trace:false}, async (fixture) => {
    const {state, clients, frames, health} = fixture;
    state.warmServerList(true);
    select(fixture);
    await deadline(() => health[0]?.payload && clients[0]?.ws.readyState === WebSocket.OPEN,
      "old relay health result and targetless WebSocket");
    assert.equal(clients.length, 1, "old relay opened a target socket after health");
    assert.equal(frames.length, 0, "old capability allowed speculative connect");
    reserve(state);
    state.open(id, publicTarget, port, attempt);
    const ordinary = await wireConnect(fixture, 0, false);
    await ready(ordinary);
    assert.equal(await awaitJoin(fixture), clients[0].ws, "old relay lost its targetless predial");
    assert.equal(clients.length, 1);
    assert.equal(frames.length, 1);
    assert.equal(state.stats.relaySegments, undefined);
  });

  await run("target-prewarm-websocket-open-budget", {stallUpgrade:true}, async (fixture) => {
    const {state, clock, clients, frames, heldUpgrades} = fixture;
    state.warmServerList(true);
    await deadline(() => clients[0]?.ws.readyState === WebSocket.OPEN, "targetless predial before test");
    select(fixture);
    await deadline(() => heldUpgrades.size === 1, "target WebSocket upgrade held");
    const {ws} = clients.at(-1);
    clock.advance(3999);
    assert.equal(ws.readyState, WebSocket.CONNECTING, "target upgrade expired before its attempt budget");
    clock.advance(1);
    await deadline(() => ws.readyState === WebSocket.CLOSED, "target WebSocket attempt budget expired");
    assert.equal(frames.length, 0);
  });

  await run("target-prewarm-15s-from-send-without-keepalive", {watchIdle:true}, async (fixture) => {
    const {state, clock, clients, frames} = fixture;
    state.warmServerList(true);
    select(fixture);
    const warm = await wireConnect(fixture, 0);
    const warmDials = clients.length;
    clock.advance(1000); // Target readiness must not reset the lease.
    await ready(warm);
    clock.advance(13999);
    assert.equal(warm.client.ws.readyState, WebSocket.OPEN, "prewarm expired before 15s from connect");
    assert.equal(warm.client.sent.length, 1, "prewarm sent a keepalive/control before expiry");
    clock.advance(1);
    await deadline(() => warm.client.ws.readyState === WebSocket.CLOSED, "prewarm 15s expiry");
    select(fixture);
    assert.equal(clients.length, warmDials, "unchanged selection restarted an expired prewarm");
    assert.equal(frames.length, 1);
  });

  await run("target-prewarm-15s-reservation-rejected-with-delayed-timer", {}, async (fixture) => {
    const {state, clock, frames} = fixture;
    state.warmServerList(true);
    select(fixture);
    const warm = await wireConnect(fixture, 0);
    await ready(warm);
    reserve(state);
    const timer = state.joinWarmupEntry.preconnectedRelay.timeout;
    clock.advance(15000, false);
    assert.ok(clock.hasTimer(timer), "prewarm expiry callback ran before the Join check");
    state.open(id, publicTarget, port, attempt);
    assert.equal(state.joinWarmupEntry, null);
    assert.ok(!clock.hasTimer(timer));
    await deadline(() => warm.client.ws.readyState === WebSocket.CLOSED, "expired prewarm closed at Join");
    const fallback = await wireConnect(fixture, 1, false);
    await ready(fallback);
    assert.equal(await awaitJoin(fixture), fallback.client.ws);
    assert.equal(frames.length, 2);
    joinLegs(state, "control");
  });

  for (const [ack, name] of [
    ["before", "target-prewarm-join-reuses-exact-ws-with-one-connect-and-ordered-trace"],
    ["reserved", "target-prewarm-ack-between-reserve-and-open"],
    ["pending", "target-prewarm-join-waits-on-same-ws-without-resending"],
  ]) {
    await run(name, {direct:ack !== "pending"}, async (fixture) => {
      const {state, clients, frames} = fixture;
      state.warmServerList(true);
      select(fixture);
      const warm = await wireConnect(fixture, 0);
      if (ack === "before") await ready(warm);
      const dialsAtJoin = clients.length;
      reserve(state);
      if (ack === "reserved") await ready(warm);
      assert.equal(state.stats.connected, 0, "reserving a prewarm prematurely connected the channel");
      state.open(id, publicTarget, port, attempt);
      if (ack === "pending") {
        assert.equal(state.channels.get(id).ws, warm.client.ws);
        assert.equal(state.stats.connected, 0, "progress ACK made the channel usable");
        await ready(warm);
      }
      assert.equal(await awaitJoin(fixture), warm.client.ws);
      assert.equal(clients.length, dialsAtJoin, "Join unexpectedly started a second WS or direct plugin probe");
      assert.equal(frames.length, 1);
      assert.equal(warm.client.sent.length, 1, "Join sent a second connect or activation frame");
      const [leg] = joinLegs(state, "prewarm");
      for (const flag of ["predial", "predialReused", "targetPrewarmReused", "predialOpenBeforeJoin"]) {
        assert.equal(leg[flag], true, flag);
      }
      assertOrder(leg.wsOpenAt, leg.connectSentAt, leg.connectAckAt,
        ...(ack === "before" ? [leg.targetReadyAt, leg.joinAt] : [leg.joinAt, leg.targetReadyAt]),
        leg.targetUsableAt);
      assert.ok(leg.connectAckAt < leg.targetReadyAt, "connecting ACK was not timed separately from target ready");
    });
  }

  await run("target-prewarm-wrong-explicit-protocol-ack-falls-back", {}, async (fixture) => {
    const {state} = fixture;
    state.warmServerList(true);
    select(fixture);
    const warm = await wireConnect(fixture, 0);
    await ready(warm, {...fixture.protocolTable, sha256:"b".repeat(64)});
    await deadline(() => warm.client.ws.readyState === WebSocket.CLOSED, "invalid prewarm ACK rejected");
    reserve(state);
    state.open(id, publicTarget, port, attempt);
    const fallback = await wireConnect(fixture, 1, false);
    await ready(fallback);
    assert.equal(await awaitJoin(fixture), fallback.client.ws);
    joinLegs(state, "control");
  });

  for (const timing of ["before", "during"]) {
    for (const code of [1001, 1013]) {
      await run(`target-prewarm-close-${code}-${timing}-join-falls-back`, {}, async (fixture) => {
        const {state, frames} = fixture;
        state.warmServerList(true);
        select(fixture);
        const warm = await wireConnect(fixture, 0);
        if (timing === "before") {
          await ready(warm);
          warm.ws.close(code);
          await deadline(() => warm.client.closeCode === code, "prewarm closed before Join");
          reserve(state);
          state.open(id, publicTarget, port, attempt);
        } else {
          reserve(state);
          state.open(id, publicTarget, port, attempt);
          assert.equal(state.channels.get(id).ws, warm.client.ws, "Join must be on the pending prewarm before it fails");
          assert.equal(state.stats.connected, 0);
          warm.ws.close(code);
          await deadline(() => warm.client.closeCode === code, "prewarm closed during Join");
        }
        const fallback = await wireConnect(fixture, 1, false);
        assert.notEqual(fallback.ws, warm.ws, "ordinary fallback reused the failed WebSocket");
        await ready(fallback);
        assert.equal(await awaitJoin(fixture), fallback.client.ws);
        assert.equal(frames.length, 2, "fallback sent more than one ordinary connect");
        const legs = timing === "during" ? joinLegs(state, "prewarm", "control") : joinLegs(state, "control");
        const leg = legs.at(-1);
        assert.equal(leg.targetPrewarmReused, false);
        assertOrder(leg.joinAt, leg.connectSentAt, leg.connectAckAt, leg.targetReadyAt, leg.targetUsableAt);
        assertOrder(leg.wsOpenAt, leg.connectSentAt);
        if (timing === "during") {
          const [failed] = legs;
          assert.equal(failed.targetReadyAt, undefined, "failed prewarm reported a ready target");
          assert.equal(failed.targetUsableAt, undefined, "failed prewarm reported a usable target");
          assertOrder(failed.wsOpenAt, failed.connectSentAt, failed.connectAckAt, failed.joinAt, leg.wsOpenAt);
        }
      });
    }
  }
  return cases;
}
