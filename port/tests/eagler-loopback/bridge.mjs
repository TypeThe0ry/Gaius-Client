import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {once} from "node:events";
import {readFile} from "node:fs/promises";
import {createServer as createHttpServer} from "node:http";
import {createRequire} from "node:module";
import {createConnection} from "node:net";
import {deflateSync, inflateSync} from "node:zlib";
import {RENDERPEARL_PROFILE} from "../../scripts/verification-profiles.mjs";

export const HOST = "127.0.0.1";
export const TMP = "/tmp/eglx";
const MAX_FRAME = (1 << 21) - 1; // Minecraft's TCP VarInt21 frame length
const MAX_BODY = 8 << 20;
const MAX_PENDING = 16 << 20;
export const table = JSON.parse(await readFile(new URL(
  `../../../apps/bridge/protocols/${RENDERPEARL_PROFILE.protocolVersion}.json`, import.meta.url), "utf8"));
assert.equal(table.name, RENDERPEARL_PROFILE.id, "RenderPearl profile and packet table disagree");
export const {protocolVersion: PROTOCOL, login, configuration, play} = table;
const require = createRequire(new URL("../../../apps/bridge/package.json", import.meta.url));
const octets = (...values) => Buffer.from(values);
const concat = (...parts) => Buffer.concat(parts);

function websocketServer() {
  try {
    return require(process.env.EAGLER_WS_MODULE || "ws").WebSocketServer;
  } catch (error) {
    throw new Error("Use the existing apps/bridge ws dependency or set EAGLER_WS_MODULE to an installed ws module", {cause: error});
  }
}

export function vint(value) {
  const bytes = [];
  for (let n = value >>> 0; ; n >>>= 7) {
    bytes.push((n & 127) | (n > 127 ? 128 : 0));
    if (n <= 127) return Buffer.from(bytes);
  }
}

function readVarInt(data, offset = 0, maxBytes = 5) {
  let value = 0;
  for (let i = 0; i < maxBytes; i++) {
    if (offset + i >= data.length) return null;
    const byte = data[offset + i];
    assert(i < 4 || byte <= 15, "VarInt overflow");
    value |= (byte & 127) << (i * 7);
    if (!(byte & 128)) return {value, size: i + 1};
  }
  throw new Error("oversized VarInt");
}

export class Cursor {
  constructor(bytes) { this.bytes = bytes; this.offset = 0; }
  take(size) {
    assert(size >= 0 && size <= this.bytes.length - this.offset, "truncated field");
    const start = this.offset;
    this.offset += size;
    return this.bytes.subarray(start, this.offset);
  }
  byte() { return this.take(1)[0]; }
  short() { return this.take(2).readUInt16BE(); }
  int() {
    const field = readVarInt(this.bytes, this.offset);
    assert(field, "truncated VarInt");
    this.offset += field.size;
    return field.value;
  }
  str8() { return this.take(this.byte()).toString("latin1"); }
  str() { return this.take(this.int()).toString("utf8"); }
  end() { assert.equal(this.offset, this.bytes.length, "trailing bytes"); }
}

export function u16(n) { const bytes = Buffer.alloc(2); bytes.writeUInt16BE(n); return bytes; }
function ascii8(value) { return concat(octets(value.length), Buffer.from(value, "ascii")); }
export function mcString(value) { const bytes = Buffer.from(value); return concat(vint(bytes.length), bytes); }
export function offlineUuid(name) {
  const uuid = createHash("md5").update(`OfflinePlayer:${name}`).digest();
  uuid[6] = (uuid[6] & 15) | 48;
  uuid[8] = (uuid[8] & 63) | 128;
  return uuid;
}

// A single reader survives TCP fragmentation/coalescing and an in-band change
// of compression mode. The callback may change the mode before the next frame.
export class MinecraftTcp {
  constructor(socket, onBody, onError) {
    this.socket = socket.setNoDelay(true);
    this.threshold = -1;
    let pending = Buffer.alloc(0);
    socket.on("data", chunk => {
      try {
        pending = pending.length ? concat(pending, chunk) : chunk;
        let offset = 0;
        while (offset < pending.length) {
          const length = readVarInt(pending, offset, 3);
          if (!length) break;
          assert(length.value > 0, "empty TCP frame");
          const start = offset + length.size;
          const end = start + length.value;
          if (end > pending.length) break;
          let body = pending.subarray(start, end);
          if (this.threshold >= 0) {
            const data = new Cursor(body);
            const expanded = data.int();
            const payload = body.subarray(data.offset);
            assert(expanded === 0 || (expanded >= this.threshold && expanded <= MAX_BODY), "invalid compressed size");
            body = expanded ? inflateSync(payload, {maxOutputLength: expanded}) : payload;
            if (expanded) assert.equal(body.length, expanded, "inflated size mismatch");
          }
          assert(body.length > 0, "empty Minecraft body");
          offset = end;
          onBody(body);
          if (socket.destroyed) return;
        }
        pending = pending.subarray(offset);
      } catch (error) { onError(error); }
    });
    socket.on("error", onError);
  }
  write(body) {
    assert(!this.socket.destroyed, "TCP backend closed");
    assert(body.length > 0 && body.length <= MAX_BODY, "invalid Minecraft body size");
    let framed = body;
    if (this.threshold >= 0) {
      framed = body.length >= this.threshold ? concat(vint(body.length), deflateSync(body)) : concat(octets(0), body);
    }
    assert(framed.length <= MAX_FRAME, "Minecraft TCP frame too large");
    assert(this.socket.writableLength + framed.length <= MAX_PENDING, "TCP output backlog exceeded");
    this.socket.write(concat(vint(framed.length), framed));
  }
}

async function listen(server) {
  server.listen(0, HOST);
  await once(server, "listening");
  return server.address().port;
}

function readHello(body) {
  assert.equal(body.byte(), 1, "expected Eagler hello (01)");
  assert.equal(body.byte(), 2, "expected multi-version Eagler hello");
  const candidates = () => {
    const count = body.short();
    assert(count >= 1 && count <= 16, "invalid Eagler candidate count");
    return Array.from({length: count}, () => body.short());
  };
  assert(candidates().includes(5), "fixture requires Eagler handshake v5");
  assert(candidates().includes(PROTOCOL), `fixture requires Minecraft protocol ${PROTOCOL}`);
  body.str8(); // brand
  body.str8(); // version
  assert.equal(body.byte(), 0, "fixture requires offline hello");
  const name = body.str8();
  assert(/^[A-Za-z0-9_]{3,16}$/.test(name), "invalid offline username");
  body.end();
  return name;
}

function readLogin(body, name) {
  assert.equal(body.byte(), 4, "expected Eagler login request (04)");
  assert.equal(body.str8(), name, "login name differs from Eagler hello");
  body.str8(); // requested server
  assert.equal(body.byte(), 0, "fixture requires no password");
  assert.equal(body.byte(), 0, "fixture requires cookies disabled");
  assert.equal(body.byte(), 0, "fixture requires empty cookie");
  const mask = body.int() >>> 0;
  for (let bits = mask; bits; bits >>>= 1) if (bits & 1) body.int();
  const extended = body.byte();
  assert(extended <= 32, "too many extended capabilities");
  for (let i = 0; i < extended; i++) { body.take(16); body.int(); }
  body.end();
}

export async function startEaglerBridge(backendPort, report, observe = () => {}) {
  assert(Number.isInteger(backendPort) && backendPort > 0 && backendPort <= 65535, "invalid backend port");
  const server = createHttpServer((req, res) => res.writeHead(404).end());
  const WebSocketServer = websocketServer();
  const wss = new WebSocketServer({server, maxPayload: MAX_BODY, perMessageDeflate: false});
  wss.on("connection", ws => {
    const evidence = {handshake: [], handshakeCapture: [], backendLogin: [], fromTcp: 0, toTcp: 0, phase: "hello"};
    report.push(evidence);
    let name, socket, tcp, finishConfigurationSent = false;
    const profileKeys = new Set();
    const timer = setTimeout(() => fail(new Error("login timeout")), 30000).unref();
    const fail = error => {
      evidence.error ??= `${evidence.phase}: ${error.message}`;
      socket?.destroy();
      if (ws.readyState === 1) ws.close(1002, "loopback protocol error");
    };
    const send = body => {
      assert(ws.readyState === 1 && ws.bufferedAmount + body.length <= MAX_PENDING, "WebSocket output unavailable");
      ws.send(body, {binary: true}, error => { if (error) fail(error); });
    };
    // Capture only validated login/control messages; never log profile blobs or game traffic.
    const capture = (direction, bytes) => evidence.handshakeCapture.push([direction, bytes.toString("hex")]);
    const handshake = body => {
      send(body);
      evidence.handshake.push(body[0]);
      capture("S", body);
    };
    const fromBackend = bytes => {
      const body = new Cursor(bytes);
      const id = body.int();
      if (evidence.phase === "backend-login") {
        if (id === login.clientboundCompression) {
          assert.equal(tcp.threshold, -1, "duplicate Set Compression");
          tcp.threshold = body.int();
          assert(tcp.threshold >= 0 && tcp.threshold <= MAX_BODY, "invalid compression threshold");
          body.end();
          evidence.backendLogin.push("compression");
          evidence.compressionThreshold = tcp.threshold;
          return;
        }
        assert.equal(id, login.clientboundLoginFinished, `expected offline backend Login Success, received packet ${id}`);
        const uuid = body.take(16);
        const acceptedName = body.str();
        assert.equal(acceptedName, name, "backend changed offline username");
        const properties = body.int();
        assert(properties >= 0 && properties <= 1024, "invalid profile property count");
        for (let i = 0; i < properties; i++) { body.str(); body.str(); if (body.byte()) body.str(); }
        evidence.backendSession = body.take(16).toString("hex");
        body.end();
        evidence.backendLogin.push("success");
        evidence.username = acceptedName;
        evidence.uuid = uuid.toString("hex");
        handshake(concat(octets(5), ascii8(acceptedName), uuid, octets(0, 0)));
        evidence.phase = "finish";
        return;
      }
      assert(evidence.phase === "configuration" || evidence.phase === "play", "backend data before Eagler finish");
      if (evidence.phase === "configuration" && id === configuration.clientboundFinish) finishConfigurationSent = true;
      if (evidence.phase === "play" && id === play.clientboundLogin) evidence.playLogin = true;
      evidence.fromTcp++;
      observe("clientbound", evidence.phase, id, body);
      send(bytes);
    };
    ws.on("message", (bytes, binary) => {
      try {
        assert(binary, "expected binary WebSocket message");
        const body = new Cursor(bytes);
        switch (evidence.phase) {
          case "hello":
            name = readHello(body);
            capture("C", bytes);
            handshake(concat(octets(2), u16(5), u16(PROTOCOL), ascii8("GaiusLoopback"), ascii8("1"), octets(0, 0, 0, 0)));
            evidence.phase = "login";
            break;
          case "login":
            readLogin(body, name);
            capture("C", bytes);
            evidence.phase = "backend-login";
            socket = createConnection({host: HOST, port: backendPort});
            tcp = new MinecraftTcp(socket, fromBackend, fail);
            socket.once("connect", () => {
              try {
                tcp.write(concat(octets(0), vint(PROTOCOL), mcString(HOST), u16(backendPort), octets(2)));
                tcp.write(concat(vint(login.serverboundHello), mcString(name), offlineUuid(name)));
                evidence.backendLogin.push("handshake", "hello");
              } catch (error) { fail(error); }
            });
            socket.once("end", () => {
              evidence.backendEnded = true;
              if (ws.readyState === 1) ws.close(1000, "backend closed");
            });
            break;
          case "finish": {
            const id = body.byte();
            if (id === 7) {
              const count = body.byte();
              assert(profileKeys.size + count <= 8, "too many profile items");
              for (let i = 0; i < count; i++) {
                const key = body.str8();
                assert(!profileKeys.has(key), "duplicate profile item");
                profileKeys.add(key);
                body.take(body.short());
              }
              body.end();
              break;
            }
            assert.equal(id, 8, "expected Eagler finish request (08)");
            body.end();
            capture("C", bytes);
            handshake(octets(9));
            evidence.phase = "configuration";
            tcp.write(vint(login.serverboundLoginAcknowledged));
            evidence.backendLogin.push("acknowledged");
            clearTimeout(timer);
            break;
          }
          case "configuration":
          case "play": {
            const id = body.int();
            assert(id >= 0, "invalid packet ID");
            // The login ack and configuration-finished both use packet id 03.
            if (evidence.phase === "configuration" && id === configuration.serverboundFinish) {
              body.end();
              assert(finishConfigurationSent, "early finish configuration or leaked Minecraft login ack");
              evidence.phase = "play";
            }
            evidence.toTcp++;
            observe("serverbound", evidence.phase, id, body);
            tcp.write(bytes);
            break;
          }
          default: throw new Error("Eagler packet while waiting for backend Login Success");
        }
      } catch (error) { fail(error); }
    });
    ws.on("error", fail);
    ws.on("close", (code, reason) => {
      clearTimeout(timer);
      evidence.close = {code, reason: reason.toString()};
      socket?.destroy();
    });
  });
  const port = await listen(server);
  return {port, url: `ws://${HOST}:${port}/eagler`, async close() {
    for (const client of wss.clients) client.terminate();
    wss.close();
    await new Promise(done => server.close(done));
  }};
}
