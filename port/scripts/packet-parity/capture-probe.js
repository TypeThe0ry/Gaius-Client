// Packet capture probe of the packet parity gate (port/scripts/packet-parity.mjs capture): injected
// into the test page before any other script. It records the raw byte stream of every channel of
// the Netty browser bridge (globalThis.__gaiusNettyBridge of BrowserWebSocketChannel; the client's
// connection to the singleplayer server Worker is one of them): what Java hands to send(id, data)
// and what pollInbound(id) hands back, with performance.now() times, plus open/close calls. Nothing
// is changed on the way: the probe wraps the bridge object in a Proxy whose open, send, pollInbound
// and close call the originals and then copy the bytes. Every second the new records are POSTed to
// /__packet-capture of the serving page (one JSON array per request).
(function () {
  "use strict";
  if (globalThis.__gaiusPacketCapture) return;
  var endpoint = "/__packet-capture";
  var pending = [];
  var capture = {records: 0, bytes: 0, posted: 0, postErrors: 0};
  globalThis.__gaiusPacketCapture = capture;
  function base64(bytes) {
    var text = "";
    for (var offset = 0; offset < bytes.length; offset += 0x8000) {
      text += String.fromCharCode.apply(null, bytes.subarray(offset, offset + 0x8000));
    }
    return btoa(text);
  }
  function bytesOf(data) {
    if (!data) return null;
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    return null;
  }
  function record(id, direction, data) {
    var bytes = bytesOf(data);
    if (!bytes || bytes.length === 0) return;
    pending.push({id: id | 0, d: direction, t: Math.round(performance.now() * 10) / 10, b: base64(bytes)});
    capture.records++;
    capture.bytes += bytes.length;
  }
  function wrap(bridge) {
    return new Proxy(bridge, {
      get: function (target, key) {
        var value = target[key];
        if (typeof value !== "function") return value;
        if (key === "open") {
          return function (id, host, port) {
            pending.push({id: id | 0, d: "open", t: Math.round(performance.now() * 10) / 10,
              host: String(host), port: port | 0});
            return value.apply(target, arguments);
          };
        }
        if (key === "send") {
          return function (id, data) {
            var copy = bytesOf(data);
            copy = copy ? copy.slice() : null;
            var accepted = value.apply(target, arguments);
            if (accepted && copy) record(id, "out", copy);
            return accepted;
          };
        }
        if (key === "pollInbound") {
          return function (id) {
            var result = value.apply(target, arguments);
            if (result) record(id, "in", result);
            return result;
          };
        }
        if (key === "close") {
          return function (id) {
            pending.push({id: id | 0, d: "close", t: Math.round(performance.now() * 10) / 10});
            return value.apply(target, arguments);
          };
        }
        return value;
      }
    });
  }
  var raw;
  var proxied;
  Object.defineProperty(globalThis, "__gaiusNettyBridge", {
    configurable: true,
    enumerable: true,
    get: function () { return proxied; },
    set: function (value) { raw = value; proxied = value ? wrap(value) : value; }
  });
  function flush() {
    if (!pending.length) return;
    var batch = pending.splice(0, pending.length);
    try {
      fetch(endpoint, {method: "POST", body: JSON.stringify(batch), keepalive: batch.length < 64})
        .then(function () { capture.posted += batch.length; })
        .catch(function () { capture.postErrors++; });
    } catch (error) {
      capture.postErrors++;
    }
  }
  setInterval(flush, 1000);
  addEventListener("pagehide", flush);
  capture.flush = flush;
})();
