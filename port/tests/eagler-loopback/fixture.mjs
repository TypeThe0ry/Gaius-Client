import assert from "node:assert/strict";
import {execFile} from "node:child_process";
import {once} from "node:events";
import {readdir} from "node:fs/promises";
import {createServer} from "node:net";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {promisify} from "node:util";
import {Cursor, MinecraftTcp, TMP, mcString, offlineUuid, startEaglerBridge, u16, vint,
  table as PROFILE, login, play, configuration, PROTOCOL as protocolVersion} from "./bridge.mjs";
// IDs in the full 26.3 GameProtocols table, including the interleaved common packets.
const CHAT = 9;
const MOVE = new Map([[30, 25], [31, 33]]);
const PLAYER_INPUT = 43;
const PLAYER_LOADED = 44;
const b = (...bytes) => Buffer.from(bytes);
const pack = (...parts) => Buffer.concat(parts);
const ascii8 = name => pack(b(name.length), Buffer.from(name));

export function trackPlay(expectedChat) {
  const evidence = {moves: 0, firstPosition: null, lastPosition: null, chatSent: false, keepAliveReplies: 0,
    playerInput: 0, playerLoaded: 0, packetCounts: {}, otherPackets: 0};
  return {evidence, observe(direction, phase, id, body) {
    if (direction !== "serverbound" || phase !== "play") return;
    if (id >= 0 && id < 64) evidence.packetCounts[id] = (evidence.packetCounts[id] ?? 0) + 1;
    else evidence.otherPackets++;
    if (id === PLAYER_INPUT) evidence.playerInput++;
    if (id === PLAYER_LOADED) evidence.playerLoaded++;
    if (id === CHAT) evidence.chatSent ||= body.str() === expectedChat;
    if (id === play.serverboundKeepAlive) evidence.keepAliveReplies++;
    if (MOVE.has(id)) {
      const bytes = body.take(MOVE.get(id));
      body.end();
      const position = [bytes.readDoubleBE(0), bytes.readDoubleBE(8), bytes.readDoubleBE(16)];
      assert(position.every(Number.isFinite), "non-finite movement");
      evidence.moves++;
      evidence.lastPosition = position;
      evidence.firstPosition ??= position;
    }
  }};
}

async function startTcp(onConnection) {
  const connections = new Set();
  const errors = [];
  const server = createServer(socket => {
    connections.add(socket);
    socket.once("close", () => connections.delete(socket));
    const fail = error => { errors.push(error.message); socket.destroy(); };
    onConnection(socket, fail);
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return {port: server.address().port, errors, async close() {
    for (const socket of connections) socket.destroy();
    await new Promise(done => server.close(done));
  }};
}

function readHandshake(body) {
  assert.equal(body.int(), 0);
  assert.equal(body.int(), protocolVersion);
  assert.equal(body.str(), "127.0.0.1");
  assert(body.short() > 0);
  assert.equal(body.int(), 2, "backend should receive log-in intention");
  body.end();
}

function readLogin(body) {
  assert.equal(body.int(), login.serverboundHello);
  const name = body.str();
  const uuid = body.take(16);
  assert.deepEqual(uuid, offlineUuid(name));
  body.end();
  return {name, uuid};
}

const SESSION = Buffer.from("692cb69994604ca8b255c5d1456f4125", "hex");
const loginSuccess = ({name, uuid}) => pack(vint(login.clientboundLoginFinished), uuid, mcString(name), b(0), SESSION);

async function makeWorldPackets(chatText) {
  assert(process.env.MC_SERVER_DIR, `set MC_SERVER_DIR to a local ${PROFILE.name} server directory with cached versions/ and libraries/, or use BACKEND_PORT`);
  const dir = resolve(process.env.MC_SERVER_DIR);
  const libraryDir = join(dir, "libraries");
  const libraries = (await readdir(libraryDir, {recursive: true})).filter(path => path.endsWith(".jar")).sort();
  const classpath = [join(dir, "versions", PROFILE.name, `server-${PROFILE.name}.jar`),
    ...libraries.map(file => join(libraryDir, file))].join(":");
  const source = fileURLToPath(new URL("EncodeWorld.java", import.meta.url));
  const {stdout} = await promisify(execFile)(process.env.JAVA || "java", ["-Xmx1G", `-Djava.io.tmpdir=${TMP}`,
    "-cp", classpath, source, String(play.clientboundChunk), chatText], {cwd: TMP, timeout: 60000, maxBuffer: 8 << 20})
    .catch(error => { throw new Error(`World packet encoder failed (${error.code ?? error.signal}): ${[error.stderr, error.stdout?.split(/\r?\n/).filter(line => !line.includes("EAGLER_FIXTURE")).slice(-20).join("\n")].filter(Boolean).join("\n") || error.message}`); });
  const packets = {config: [], play: [], chat: [], move: []};
  for (const [, phase, body] of stdout.matchAll(/^EAGLER_FIXTURE (config|play|chat|move) ([A-Za-z0-9+/=]+)$/gm)) {
    packets[phase].push(Buffer.from(body, "base64"));
  }
  assert(packets.config.length > 1 && packets.play.length > 1, "missing generated world packets");
  assert.equal(packets.chat.length, 1, "missing generated chat packet");
  assert.equal(packets.move.length, 2, "missing generated position packets");
  return packets;
}

export async function startSyntheticBackend(chatText, packets) {
  packets ??= await makeWorldPackets(chatText);
  const received = [];
  const metrics = trackPlay(chatText);
  const backend = await startTcp((socket, fail) => {
    let phase = "intention";
    let keepAlive;
    let keepAliveId = 0n;
    const tcp = new MinecraftTcp(socket, bytes => {
      const body = new Cursor(bytes);
      switch (phase) {
        case "intention":
          readHandshake(body);
          phase = "hello";
          break;
        case "hello": {
          const accepted = readLogin(body);
          tcp.write(pack(vint(login.clientboundCompression), vint(256)));
          tcp.threshold = 256;
          tcp.write(loginSuccess(accepted));
          phase = "ack";
          break;
        }
        case "ack":
          assert.equal(body.int(), login.serverboundLoginAcknowledged);
          body.end();
          phase = "config";
          for (const packet of packets.config) tcp.write(packet);
          break;
        case "config":
          if (body.int() === configuration.serverboundFinish) {
            body.end();
            phase = "play";
            for (const packet of packets.play) tcp.write(packet);
            keepAlive = setInterval(() => {
              try {
                const id = Buffer.alloc(8);
                id.writeBigInt64BE(++keepAliveId);
                tcp.write(pack(vint(play.clientboundKeepAlive), id));
              } catch (error) { fail(error); }
            }, 5000);
          }
          break;
        case "play": {
          const id = body.int();
          metrics.observe("serverbound", phase, id, new Cursor(bytes.subarray(body.offset)));
          // Retain just the packet kinds needed to verify the fixture itself.
          if (received.length < 64 && (id === CHAT || id === play.serverboundKeepAlive || MOVE.has(id))) received.push(id);
          if (id === CHAT && body.str() === chatText) tcp.write(packets.chat[0]);
          break;
        }
      }
    }, fail);
    socket.once("close", () => clearInterval(keepAlive));
  });
  return {...backend, received, playEvidence: metrics.evidence};
}

const hello = name => pack(b(1, 2), u16(1), u16(5), u16(1), u16(protocolVersion), ascii8("Test"), ascii8("1"), b(0), ascii8(name));
const eaglerLogin = name => pack(b(4), ascii8(name), b(0, 0, 0, 0, 0, 0));
const wire = body => pack(vint(body.length), body);

async function connect(url) {
  const ws = new WebSocket(url);
  ws.binaryType = "arraybuffer";
  const events = [];
  ws.addEventListener("message", e => events.push(Buffer.from(e.data)));
  try {
    await once(ws, "open", {signal: AbortSignal.timeout(5000)});
    return {ws, events, async next() {
      if (!events.length) await once(ws, "message", {signal: AbortSignal.timeout(8000)});
      return events.shift();
    }};
  } catch (error) { ws.close(); throw error; }
}

async function runWireCase({compression, rejectLoginAck = false}) {
  const evidence = [];
  const accepted = {name: "TestGuest", uuid: offlineUuid("TestGuest")};
  const configPayload = pack(vint(configuration.clientboundKnownPacks), Buffer.alloc(240, 0x42));
  const serverPayload = pack(vint(play.clientboundChunk), Buffer.alloc(300, 0x91));
  const movePacket = pack(vint(31), Buffer.alloc(33, 0x12));
  const stage = [];
  const backend = await startTcp((socket, fail) => {
    const tcp = new MinecraftTcp(socket, bytes => {
      stage.push(bytes);
      switch (stage.length) {
        case 1: readHandshake(new Cursor(bytes)); break;
        case 2: {
          assert.deepEqual(readLogin(new Cursor(bytes)), accepted);
          const success = loginSuccess(accepted);
          if (compression >= 0) {
            // Coalesce the end of the Set Compression frame and Login Success
            // so the bridge must switch its reader mode within a single read.
            const compressionFrame = wire(pack(vint(login.clientboundCompression), vint(compression)));
            socket.write(compressionFrame.subarray(0, 1));
            socket.cork();
            socket.write(compressionFrame.subarray(1));
            tcp.threshold = compression;
            tcp.write(success);
            socket.uncork();
          } else tcp.write(success);
          break;
        }
        case 3:
          assert.deepEqual(bytes, vint(login.serverboundLoginAcknowledged));
          if (!rejectLoginAck) {
            socket.cork();
            tcp.write(configPayload);
            tcp.write(vint(configuration.clientboundFinish));
            socket.uncork();
          }
          break;
        case 4:
          assert(!rejectLoginAck, "duplicate login ack reached backend");
          assert.deepEqual(bytes, vint(configuration.serverboundFinish));
          tcp.write(serverPayload);
          break;
        case 5:
          assert.deepEqual(bytes, movePacket);
          tcp.write(movePacket);
          break;
        default: assert.fail("unexpected packet reached backend");
      }
    }, fail);
  });
  let ws, bridge;
  try {
    bridge = await startEaglerBridge(backend.port, evidence);
    const client = await connect(bridge.url);
    ws = client.ws;
    const {events, next} = client;
    ws.send(hello(accepted.name));
    assert.deepEqual(await next(), pack(b(2), u16(5), u16(protocolVersion), ascii8("GaiusLoopback"), ascii8("1"), b(0, 0, 0, 0)));
    ws.send(eaglerLogin(accepted.name));
    assert.deepEqual(await next(), pack(b(5), ascii8(accepted.name), accepted.uuid, b(0, 0)));
    assert.equal(stage.length, 2, "backend ack preceded client finish");
    ws.send(pack(b(7, 1), ascii8("example"), u16(2), b(0xff, 8)));
    ws.send(b(8));
    assert.deepEqual(await next(), b(9));
    if (rejectLoginAck) {
      ws.send(vint(login.serverboundLoginAcknowledged));
      const [close] = await once(ws, "close", {signal: AbortSignal.timeout(5000)});
      assert.equal(close.code, 1002);
      assert.match(evidence[0].error, /leaked Minecraft login ack/);
      assert.equal(stage.length, 3);
    } else {
      assert.deepEqual(await next(), configPayload);
      assert.deepEqual(await next(), vint(configuration.clientboundFinish));
      ws.send(vint(configuration.serverboundFinish));
      assert.deepEqual(await next(), serverPayload);
      ws.send(movePacket);
      assert.deepEqual(await next(), movePacket);
      assert.deepEqual(events, []);
      assert.equal(stage.length, 5);
      assert.equal(evidence[0].phase, "play");
      assert.equal(evidence[0].error, undefined);
    }
    assert.deepEqual(evidence[0].handshake, [2, 5, 9]);
    assert.equal(evidence[0].backendSession, SESSION.toString("hex"));
    assert.deepEqual(evidence[0].backendLogin, ["handshake", "hello", ...(compression >= 0 ? ["compression"] : []), "success", "acknowledged"]);
    assert.deepEqual(backend.errors, []);
  } finally {
    ws?.close();
    await bridge?.close();
    await backend.close();
  }
}

async function testSyntheticWorld(token, world) {
  const metrics = trackPlay(token);
  const backend = await startSyntheticBackend(token, world);
  const evidence = [];
  let bridge, ws;
  try {
    bridge = await startEaglerBridge(backend.port, evidence, metrics.observe);
    const client = await connect(bridge.url);
    ws = client.ws;
    const {next} = client;
    ws.send(hello("TestGuest"));
    assert.equal((await next())[0], 2);
    ws.send(eaglerLogin("TestGuest"));
    assert.equal((await next())[0], 5);
    ws.send(b(8));
    assert.deepEqual(await next(), b(9));
    for (const packet of world.config) assert.deepEqual(await next(), packet);
    ws.send(vint(configuration.serverboundFinish));
    for (const packet of world.play) assert.deepEqual(await next(), packet);

    for (const packet of world.move) ws.send(packet);
    ws.send(pack(vint(CHAT), mcString(token), Buffer.alloc(21))); // unsigned chat fixture, only text is read
    assert.deepEqual(await next(), world.chat[0]);
    assert.deepEqual(backend.received.slice(0, 3), [30, 31, CHAT]);
    assert.deepEqual(backend.playEvidence.firstPosition, [9.5, 66, 8.5]);
    assert.deepEqual(backend.playEvidence.lastPosition, [10.5, 66, 8.5]);
    assert.equal(backend.playEvidence.moves, 2);
    assert(metrics.evidence.chatSent);
    const keepAlive = new Cursor(await next());
    assert.equal(keepAlive.int(), play.clientboundKeepAlive);
    ws.send(pack(vint(play.serverboundKeepAlive), keepAlive.take(8)));
    keepAlive.end();
    // Another request/response provides an ordering barrier for the TCP reply.
    ws.send(pack(vint(CHAT), mcString(token), Buffer.alloc(21)));
    assert.deepEqual(await next(), world.chat[0]);
    assert.deepEqual(backend.received, [30, 31, CHAT, play.serverboundKeepAlive, CHAT]);
    assert.equal(metrics.evidence.keepAliveReplies, 1);
    assert.deepEqual(backend.errors, []);
    assert.equal(evidence[0].error, undefined);
  } finally {
    ws?.close();
    await bridge?.close();
    await backend.close();
  }
}

export async function selfTest() {
  await runWireCase({compression: -1});
  await runWireCase({compression: 256});
  await runWireCase({compression: 0});
  await runWireCase({compression: -1, rejectLoginAck: true});
  console.log("PASS Eagler loopback: offline login, Set Compression off/256/0, TCP framing both ways, duplicate login ack rejected");
  if (process.env.MC_SERVER_DIR) {
    const token = "loopback test";
    const world = await makeWorldPackets(token);
    assert.equal(new Cursor(world.play[0]).int(), play.clientboundLogin);
    assert.equal(new Cursor(world.config.at(-1)).int(), configuration.clientboundFinish);
    assert.equal(world.play.filter(body => new Cursor(body).int() === play.clientboundChunk).length, 81);
    await testSyntheticWorld(token, world);
    console.log("PASS synthetic world loopback: configuration, Login Play, 81 chunks, movement, chat echo, keepalive reply");
  }
}
