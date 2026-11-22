import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {setTimeout as delay} from "node:timers/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
), "utf8");
const helperSource = await readFile(new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserRelayRaceHelpers.java",
  import.meta.url,
), "utf8");
const routeSource = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/transport/ConnectionTransports.java",
  import.meta.url,
), "utf8");
const networkPatchSource = await readFile(new URL(
  "../tools/src/main/java/dev/gaius/tools/patches/network/NetworkPatches.java",
  import.meta.url,
), "utf8");
const handshakeSource = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/transport/EaglerHandshakeHandler.java",
  import.meta.url,
), "utf8");
function functionSource(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `${name} is present`);
  let depth = 0;
  let opened = false;
  for (let index = source.indexOf("{", start); index < source.length; index++) {
    if (source[index] === "{") { depth++; opened = true; }
    if (source[index] === "}") {
      depth--;
      if (depth === 0 && opened) return source.slice(start, index + 1);
    }
  }
  assert.fail(`unterminated ${name}`);
}

const context = vm.createContext({URL});
const modeFunction = functionSource("rawTransportMode").replace("function rawTransportMode", "function");
const urlFunction = functionSource("rawTransportUrl").replace("function rawTransportUrl", "function");
const helpers = vm.runInContext(`({rawTransportMode: ${modeFunction},` +
  `rawTransportUrl: ${urlFunction}})`, context);

test("raw mode is explicit, host-scoped, and off by default", () => {
  context.__gaiusWsTransportModes = {"play.example": "raw"};
  assert.equal(helpers.rawTransportMode("PLAY.EXAMPLE"), true);
  assert.equal(helpers.rawTransportMode("other.example"), false);
  delete context.__gaiusWsTransportModes;
  assert.equal(helpers.rawTransportMode("play.example"), false);
  context.__gaiusWsTransportModes = {"play.example": "relay"};
  assert.equal(helpers.rawTransportMode("play.example"), false);
  context.__gaiusWsProxyModes = {"play.example": true};
  assert.equal(helpers.rawTransportMode("PLAY.EXAMPLE"), true);
  context.__gaiusWsProxyModes["play.example"] = false;
  assert.equal(helpers.rawTransportMode("play.example"), false);
});

test("an Eagler server entry sets the URL on its own channel", () => {
  assert.match(routeSource, /channel\.attr\(EAGLER_URL\)\.set/);
  assert.match(routeSource, /case EAGLER -> EAGLER/);
  assert.match(routeSource, /case RELAY, PLUGIN, WSPROXY -> TUNNEL/);
  assert.match(networkPatchSource, /"dev\/gaius\/browser\/transport\/ConnectionTransports", "install"/);
  assert.match(networkPatchSource, /"bindGaiusTransport", "\(Ljava\/lang\/String;Ljava\/lang\/String;\)V"/);
});

test("Eagler login enters Java configuration after leaving TCP framing behind", () => {
  assert.match(handshakeSource, /pipeline\.remove\("splitter"\)/);
  assert.match(handshakeSource, /pipeline\.remove\("prepender"\)/);
  assert.match(handshakeSource, /LoginProtocols\.CLIENTBOUND\.codec\(\)\.encode/);
  assert.match(source, /entry\.eagler \? remaining : Math\.min\(maximumInboundSliceBytes, remaining\)/);
  assert.match(source, /eagler \? readableBytes : Math\.min/);
});

test("raw targets retain ws path/query while rejecting unsafe or non-WebSocket URLs", () => {
  context.__gaiusWsTargets = {
    "play.example": "wss://proxy.example/eagler?token=route#ignored",
    "bad.example": "https://proxy.example/tunnel",
    "creds.example": "ws://user:pass@proxy.example/tunnel",
  };
  assert.equal(helpers.rawTransportUrl("PLAY.EXAMPLE"),
    "wss://proxy.example/eagler?token=route");
  assert.equal(helpers.rawTransportUrl("bad.example"), null);
  assert.equal(helpers.rawTransportUrl("creds.example"), null);
  assert.equal(helpers.rawTransportUrl("missing.example"), null);
  assert.equal(helpers.rawTransportUrl("PLAY.EXAMPLE", "ws://127.0.0.1:19000/other?route=a#ignored"),
    "ws://127.0.0.1:19000/other?route=a");
  assert.equal(helpers.rawTransportUrl("PLAY.EXAMPLE", ""), null);
});

test("raw WebSocket path bypasses relay control and preserves only binary payload semantics", () => {
  assert.match(source, /entry\.rawTransport = entry\.eagler \|\| state\.rawTransportMode\(normalizedHost\)/);
  assert.match(source, /entry\.candidates\.push\(\{url: rawUrl, direct: true, raw: true\}\)/);
  assert.match(source, /if \(candidate\.raw\) \{\s*clearCandidateTimeout\(entry\);\s*entry\.connected = true;/);
  assert.match(source, /if \(candidate\.raw\) \{\s*fail\(entry, 'Raw WebSocket transport received a text frame'\)/);
  assert.match(source, /if \(candidate\.raw\) \{\s*try \{ ws\.close\(\); \} catch \(ignored\) \{\}\s*fail\(entry, 'Raw WebSocket handshake timed out'\)/);
  assert.match(source, /if \(candidate\.raw\) \{\s*fail\(entry, 'Raw WebSocket closed before open:/);
  assert.match(source, /deliverInbound\(entry, event\.data, binaryArrivalToken\)/);
  assert.match(source, /if \(entry\.rawTransport\) \{[\s\S]*?openRemoteCandidate\(entry\);\s*return;\s*\}/);
});

function bridgeScript(marker) {
  const end = source.indexOf(`private static native void ${marker}();`);
  const start = source.lastIndexOf('@JSBody(script = """', end) + '@JSBody(script = """'.length;
  assert.ok(start > 20 && end > start);
  return source.slice(start, source.lastIndexOf('""")', end)).replaceAll("\\\\", "\\");
}

function setupBridge() {
  const sockets = [];
  const fetches = [];
  class Socket {
    static OPEN = 1;
    readyState = Socket.OPEN;
    bufferedAmount = 0;
    sent = [];
    constructor(url) { this.url = url; sockets.push(this); }
    send(data) { this.sent.push(data); }
    close() { this.readyState = 3; }
    receiveClose(code, reason = "") {
      this.close();
      this.onclose({code, reason});
    }
  }
  const browser = vm.createContext({URL, URLSearchParams, ArrayBuffer, Uint8Array,
    performance, setTimeout, clearTimeout, queueMicrotask, WebSocket: Socket,
    localStorage: {getItem: () => null}, location: new URL("file:///Gaius.html"),
    fetch: async value => { fetches.push(value); throw Error("Unexpected relay fetch"); }});
  browser.window = browser;
  const helperEnd = helperSource.indexOf("static native void init();");
  const helperStart = helperSource.lastIndexOf('@JSBody(script = """', helperEnd) + '@JSBody(script = """'.length;
  const helperBody = helperSource.slice(helperStart, helperSource.lastIndexOf('""")', helperEnd)).replaceAll("\\\\", "\\");
  // Mirrors BrowserWebSocketChannel's constructor, including the real inbound scheduler.
  vm.runInContext([
    bridgeScript("initBridge"),
    helperBody,
    bridgeScript("initBridgeTail"),
    bridgeScript("initOutboundScheduler"),
    bridgeScript("initInboundScheduler"),
  ].map(body => `(function () {${body}})();`).join("\n"), browser);
  return {bridge: browser.__gaiusNettyBridge, sockets, fetches};
}

test("Eagler preserves large messages and scopes URLs to the channel", async () => {
  const {bridge, sockets, fetches} = setupBridge();
  const first = "ws://127.0.0.1:10000/a?route=one";
  const second = "ws://127.0.0.1:10001/b?route=two";
  const large = new Uint8Array(384 * 1024).map((_, index) => index % 251);
  try {
    bridge.open(501, "127.0.0.1", 10000, first);
    bridge.open(502, "127.0.0.1", 10001, second);
    assert.deepEqual(sockets.map(socket => socket.url), [first, second]);
    for (const socket of sockets) socket.onopen();
    sockets[0].onmessage({data: large.buffer});
    sockets[0].onmessage({data: Uint8Array.of(9).buffer});
    sockets[1].onmessage({data: Uint8Array.of(5).buffer});
    assert.equal(bridge.send(501, large), true);
    await delay(30);
    assert.deepEqual(new Uint8Array(bridge.pollInbound(501)), large);
    assert.deepEqual(new Uint8Array(bridge.pollInbound(501)), Uint8Array.of(9));
    assert.deepEqual(new Uint8Array(bridge.pollInbound(502)), Uint8Array.of(5));
    assert.equal(bridge.pollInbound(501), null);
    assert.equal(bridge.pollInbound(502), null);
    assert.equal(sockets[0].sent.length, 1);
    assert.deepEqual(new Uint8Array(sockets[0].sent[0]), large);
    assert.equal(sockets[1].sent.length, 0);
    assert.deepEqual(fetches, []);
  } finally {
    bridge.close(501);
    bridge.close(502);
  }
});


test("Eagler close events distinguish normal closure and preserve protocol errors", () => {
  const {bridge, sockets, fetches} = setupBridge();
  const url = "ws://127.0.0.1:10000/eagler";
  const cases = [
    {id: 501, opened: true, code: 1000, reason: "normal", error: null},
    {id: 502, opened: true, code: 1002, reason: "protocol error",
      error: "WebSocket transport closed: 1002 protocol error"},
    {id: 503, opened: true, code: 1006, reason: "",
      error: "WebSocket transport closed: 1006 "},
    {id: 504, opened: false, code: 1002, reason: "rejected",
      error: "Raw WebSocket closed before open: 1002 rejected"},
  ];
  try {
    for (const {id, opened, code, reason, error} of cases) {
      bridge.open(id, "127.0.0.1", 10000, url);
      const socket = sockets.at(-1);
      if (opened) socket.onopen();
      socket.receiveClose(code, reason);
      assert.equal(bridge.pollError(id), error);
      assert.equal(bridge.pollError(id), null, "close error is delivered once");
      assert.equal(bridge.send(id, Uint8Array.of(8)), false);
    }
    assert.equal(sockets.length, cases.length, "Eagler failures must not try other routes");
    assert.deepEqual(fetches, []);
  } finally {
    for (const {id} of cases) bridge.close(id);
  }
});
