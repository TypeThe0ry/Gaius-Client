#!/usr/bin/env node
// Packet parity gate (v0.4.0 M2): the server->client packet stream of one scripted singleplayer
// session of a Gaius build (its server Worker: TeaVM JavaScript or WebAssembly GC) against the
// stream a vanilla JVM server sends for the same session, compared packet by packet.
//
//   node port/scripts/packet-parity.mjs capture --dist <dist> --profile <id> --out <dir>
//        [--query runtime=wasm&serverRuntime=wasm] [--seed 26300]
//   node port/scripts/packet-parity.mjs jvm --profile <id> --capture <dir> --out <dir>
//   node port/scripts/packet-parity.mjs compare --profile <id> --capture <dir> --jvm <dir> --out <dir>
//   node port/scripts/packet-parity.mjs run --dist <dist> --profile <id> --out <dir> [--query ...]
//   node port/scripts/packet-parity.mjs inspect --capture <dir> --table <protocol json> [--dump <packet>]
//   node port/scripts/packet-parity.mjs --self-test
//
// capture: serves the dist with packet-parity/capture-probe.js injected into its pages and runs the
// singleplayer harness (minecraft-263-singleplayer-cdp.mjs: create a creative world with the
// seed, move, break and place a block, Save and Quit, re-enter); the probe records the raw bytes
// of every Netty browser bridge channel, the client's connection to the server Worker among them
// (<out>/capture.jsonl). The first channel that completes a login is the session.
// jvm: starts packet-parity/PacketParityServer.java (vanilla server classes of the profile's
// client jar, world of the same seed, creative, commands on, the browser server's spawn, view and
// simulation distances) and replays the browser client's packets of that session to it over TCP:
// handshake, login and configuration packets in order, each once the JVM has sent as many
// packets of that phase as the browser server had when the client sent it; play packets at their
// recorded times after the configuration ended. The replay answers keep_alive, ping, player
// teleports (accept_teleportation) and chunk batches (chunk_batch_received, at the client's
// recorded rate) itself instead of replaying the client's answers to the browser server. The
// received stream is <out>/jvm-stream.json.
// compare: decodes both server->client streams (frames, compression, the protocol id tables
// PacketParityServer dumps for the profile). Logical comparison retains every packet history,
// keys chunks by position and registries by identifier, and normalizes only typed map ordering,
// the profile-declared login session UUID, verified round trips and complete control framing.
// Time packets provide tick bounds, not exact per-packet ticks or entity identity evidence.
// Dynamic histories without that evidence remain unresolved. Writes packet-parity.json and a
// source/hash-bound packet-ledger.jsonl. The old count/first-packet/histogram comparison is only
// diagnostic; strict byteParity is retained as an exact fallback, never a semantic allowance.
// Exit status: 0 done (compare: proven logical or exact parity), 1 differences or failure, 2 usage.
import {spawn, spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {createReadStream, existsSync, mkdirSync, readFileSync, statSync, writeFileSync, appendFileSync,
  rmSync} from "node:fs";
import {createServer} from "node:http";
import {connect} from "node:net";
import {delimiter, dirname, extname, isAbsolute, join, normalize, relative, resolve} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {deflateSync, inflateSync} from "node:zlib";
import {compareLogical} from "./packet-parity/logical-compare.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const here = join(root, "port/scripts/packet-parity");

function usage(message) {
  if (message) console.error(`packet-parity: ${message}`);
  console.error("usage: packet-parity.mjs capture|jvm|compare|run [options] | --self-test (see the file header)");
  process.exit(2);
}

// ---------------------------------------------------------------------------------------------
// Wire format: frames of varint length; after set_compression, varint data length (0 = not
// compressed) and zlib data; a packet is varint id + payload.

export function readVarInt(bytes, offset) {
  let value = 0;
  let shift = 0;
  for (let index = offset; index < bytes.length && index < offset + 5; index++) {
    const byte = bytes[index];
    value |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return {value, size: index - offset + 1};
    shift += 7;
  }
  return null;
}

export function writeVarInt(value) {
  const out = [];
  let rest = value >>> 0;
  do {
    let byte = rest & 0x7f;
    rest >>>= 7;
    if (rest) byte |= 0x80;
    out.push(byte);
  } while (rest);
  return Buffer.from(out);
}

/** Incremental frame splitter of one direction of a connection. */
export class FrameReader {
  constructor() {
    this.buffer = Buffer.alloc(0);
    this.threshold = -1;
  }

  push(chunk) {
    this.buffer = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : Buffer.from(chunk);
    const frames = [];
    for (;;) {
      const length = readVarInt(this.buffer, 0);
      if (length && length.value <= 0) throw new Error("invalid packet frame length");
      if (!length || this.buffer.length < length.size + length.value) break;
      frames.push(this.buffer.subarray(length.size, length.size + length.value));
      this.buffer = this.buffer.subarray(length.size + length.value);
    }
    return frames;
  }

  /** The packet bytes (id + payload) of a frame under the current compression. */
  packet(frame) {
    if (this.threshold < 0) return Buffer.from(frame);
    const dataLength = readVarInt(frame, 0);
    if (!dataLength) throw new Error("truncated compressed frame");
    const body = frame.subarray(dataLength.size);
    if (dataLength.value < 0) throw new Error("invalid compressed packet length");
    if (dataLength.value === 0) return Buffer.from(body);
    const decoded = inflateSync(body);
    if (decoded.length !== dataLength.value) throw new Error("compressed packet length mismatch");
    return decoded;
  }
}

export function encodeFrame(packet, threshold) {
  let body = packet;
  if (threshold >= 0) {
    body = packet.length >= threshold
      ? Buffer.concat([writeVarInt(packet.length), deflateSync(packet)])
      : Buffer.concat([writeVarInt(0), packet]);
  }
  return Buffer.concat([writeVarInt(body.length), body]);
}

function readString(bytes, offset) {
  const length = readVarInt(bytes, offset);
  const start = offset + length.size;
  return {value: bytes.subarray(start, start + length.value).toString("utf8"), size: length.size + length.value};
}

// ---------------------------------------------------------------------------------------------
// Protocol tables (PacketParityServer --protocol-table) and the phase machine of a connection.

export class Protocol {
  constructor(table) {
    this.table = table;
    this.ids = {};
    for (const [key, packets] of Object.entries(table)) {
      if (!key.includes("/")) continue;
      this.ids[key] = new Map(Object.entries(packets).map(([id, name]) => [name.replace(/^minecraft:/, ""), Number(id)]));
    }
  }

  name(phase, flow, id) {
    const name = this.table[`${phase}/${flow}`]?.[String(id)];
    return name ? name.replace(/^minecraft:/, "") : `unknown-${id}`;
  }

  id(phase, flow, name) {
    const id = this.ids[`${phase}/${flow}`]?.get(name);
    if (id === undefined) throw new Error(`no ${phase}/${flow} packet ${name}`);
    return id;
  }
}

/**
 * Decodes both directions of one recorded connection into packets with phases: [{dir: "in"|"out",
 * t, phase, name, payload}] in time order. "in" is server->client.
 */
export function decodeConnection(records, protocol) {
  const readers = {in: new FrameReader(), out: new FrameReader()};
  const phase = {in: "handshake", out: "handshake"};
  const packets = [];
  let threshold = -1;
  for (const record of records) {
    if (record.d !== "in" && record.d !== "out") continue;
    const dir = record.d;
    const flow = dir === "in" ? "clientbound" : "serverbound";
    for (const frame of readers[dir].push(Buffer.from(record.b, "base64"))) {
      readers[dir].threshold = threshold;
      const bytes = readers[dir].packet(frame);
      const id = readVarInt(bytes, 0);
      const current = dir === "in" && phase.in === "handshake" ? "login" : phase[dir];
      const name = protocol.name(current, flow, id.value);
      const payload = bytes.subarray(id.size);
      packets.push({dir, t: record.t, phase: current, name, id: id.value, payload});
      // Phase changes, as Connection switches them.
      if (dir === "out" && current === "handshake" && name === "intention") {
        const host = readString(payload, readVarInt(payload, 0).size);
        const intent = readVarInt(payload, readVarInt(payload, 0).size + host.size + 2).value;
        phase.out = intent === 1 ? "status" : "login";
        phase.in = phase.out;
      } else if (dir === "in" && current === "login" && name === "login_compression") {
        threshold = readVarInt(payload, 0).value;
      } else if (dir === "in" && current === "login" && name === "login_finished") {
        phase.in = "configuration";
      } else if (dir === "out" && current === "login" && name === "login_acknowledged") {
        phase.out = "configuration";
      } else if (dir === "in" && current === "configuration" && name === "finish_configuration") {
        phase.in = "play";
      } else if (dir === "out" && current === "configuration" && name === "finish_configuration") {
        phase.out = "play";
      } else if (dir === "in" && current === "play" && name === "start_configuration") {
        phase.in = "configuration";
      } else if (dir === "out" && current === "play" && name === "configuration_acknowledged") {
        phase.out = "configuration";
      }
    }
  }
  if (readers.in.buffer.length || readers.out.buffer.length) throw new Error("incomplete packet capture frame");
  return {packets, threshold};
}

/** The recorded connections of a capture: Map id -> {open, records}. */
export function readCapture(file) {
  const connections = new Map();
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    if (!line.trim()) continue;
    for (const record of JSON.parse(line)) {
      if (!connections.has(record.id)) connections.set(record.id, {id: record.id, open: null, records: []});
      const connection = connections.get(record.id);
      if (record.d === "open") connection.open = record;
      connection.records.push(record);
    }
  }
  for (const connection of connections.values()) connection.records.sort((a, b) => a.t - b.t);
  return connections;
}

/** The first connection that completed a login (the singleplayer session). */
export function sessionConnection(connections, protocol) {
  for (const connection of [...connections.values()].sort((a, b) => (a.records[0]?.t ?? 0) - (b.records[0]?.t ?? 0))) {
    try {
      const decoded = decodeConnection(connection.records, protocol);
      if (decoded.packets.some((packet) => packet.dir === "in" && packet.name === "login_finished")) {
        return {connection, ...decoded};
      }
    } catch {}
  }
  return null;
}

// Field readers of the few packets the replay and the comparison look into.
const view = (payload) => new DataView(payload.buffer, payload.byteOffset, payload.byteLength);

export function playLoginDistances(payload) {
  let offset = 4 + 1;
  const count = readVarInt(payload, offset);
  offset += count.size;
  for (let index = 0; index < count.value; index++) offset += readString(payload, offset).size;
  const maxPlayers = readVarInt(payload, offset);
  offset += maxPlayers.size;
  const view = readVarInt(payload, offset);
  offset += view.size;
  const simulation = readVarInt(payload, offset);
  return {viewDistance: view.value, simulationDistance: simulation.value};
}

export function compareSimulationConditions(browser, jvm) {
  const history = packets => {
    let state = null;
    const states = [];
    for (const packet of packets) {
      if (packet.phase !== "play" || packet.dir !== "in") continue;
      if (packet.name === "login") {
        if (state) throw new Error("multiple play logins in one comparison session");
        state = playLoginDistances(packet.payload);
      } else if (packet.name === "set_chunk_cache_radius" || packet.name === "set_simulation_distance") {
        const value = readVarInt(packet.payload, 0);
        if (!state || !value || value.size !== packet.payload.length || value.value <= 0) {
          throw new Error("invalid distance transition");
        }
        state = {...state, [packet.name === "set_chunk_cache_radius" ? "viewDistance" : "simulationDistance"]:
          value.value};
      } else continue;
      if (!Number.isSafeInteger(state.viewDistance) || state.viewDistance <= 0
          || !Number.isSafeInteger(state.simulationDistance) || state.simulationDistance <= 0) {
        throw new Error("invalid play login distances");
      }
      if (!states.length || JSON.stringify(states.at(-1)) !== JSON.stringify(state)) states.push({...state});
    }
    if (!states.length) throw new Error("play login distance evidence missing");
    return states;
  };
  try {
    const left = history(browser);
    const right = history(jvm);
    return {pass: JSON.stringify(left) === JSON.stringify(right), browser: left, jvm: right,
      tickBinding: "distance transitions are ordered but lack exact tick evidence"};
  } catch (error) { return {pass: false, problem: error.message}; }
}

export function spawnPosition(payload) {
  const dimension = readString(payload, 0);
  const packed = view(payload).getBigInt64(dimension.size);
  const x = Number(BigInt.asIntN(26, packed >> 38n));
  const y = Number(BigInt.asIntN(12, packed));
  const z = Number(BigInt.asIntN(26, (packed >> 12n)));
  return {dimension: dimension.value, x, y, z};
}

function chunkKey(name, payload) {
  if (name === "level_chunk_with_light") return `${view(payload).getInt32(0)},${view(payload).getInt32(4)}`;
  if (name === "light_update") {
    const x = readVarInt(payload, 0);
    const z = readVarInt(payload, x.size);
    return `${x.value | 0},${z.value | 0}`;
  }
  if (name === "forget_level_chunk") {
    const packed = view(payload).getBigInt64(0);
    return `${Number(BigInt.asIntN(32, packed))},${Number(BigInt.asIntN(32, packed >> 32n))}`;
  }
  return null;
}

// ---------------------------------------------------------------------------------------------
// capture

function argMap(args) {
  const map = {};
  for (let index = 0; index < args.length; index++) {
    if (!args[index].startsWith("--")) usage(`unexpected ${args[index]}`);
    map[args[index].slice(2)] = args[index + 1] !== undefined && !args[index + 1].startsWith("--") ? args[++index] : true;
  }
  return map;
}

async function capture(options) {
  const dist = resolve(options.dist || usage("--dist is required"));
  const out = resolve(options.out || usage("--out is required"));
  const profile = options.profile || usage("--profile is required");
  mkdirSync(out, {recursive: true});
  const captureFile = join(out, "capture.jsonl");
  rmSync(captureFile, {force: true});
  const probe = `<script>${readFileSync(join(here, "capture-probe.js"), "utf8")}</script>`;
  const MIME = {".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json",
    ".gz": "application/gzip", ".png": "image/png", ".br": "application/octet-stream"};
  const server = createServer((request, response) => {
    const url = new URL(request.url, "http://127.0.0.1");
    if (request.method === "POST" && url.pathname === "/__packet-capture") {
      const chunks = [];
      request.on("data", (chunk) => chunks.push(chunk));
      request.on("end", () => {
        appendFileSync(captureFile, Buffer.concat(chunks).toString("utf8").replace(/\n/g, "") + "\n");
        response.writeHead(204);
        response.end();
      });
      return;
    }
    let path = normalize(join(dist, decodeURIComponent(url.pathname)));
    const rel = relative(dist, path);
    if (rel.startsWith("..") || isAbsolute(rel)) { response.writeHead(403); return response.end(); }
    if (url.pathname === "/") path = join(dist, "index.html");
    if (!existsSync(path) || statSync(path).isDirectory()) { response.writeHead(404); return response.end(); }
    if (extname(path) === ".html") {
      const page = readFileSync(path, "utf8").replace("<head>", `<head>${probe}`);
      response.writeHead(200, {"Content-Type": "text/html", "Cache-Control": "no-store"});
      return response.end(page);
    }
    response.writeHead(200, {"Content-Type": MIME[extname(path)] || "application/octet-stream",
      "Content-Length": statSync(path).size, "Cache-Control": "no-store"});
    createReadStream(path).pipe(response);
  });
  await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
  const query = options.query && options.query !== true ? `?${options.query}` : "";
  const pageUrl = `http://127.0.0.1:${server.address().port}/index.html${query}`;
  console.log(`packet-parity: capture ${profile} ${pageUrl}`);
  const harness = join(root, "port/scripts/minecraft-263-singleplayer-cdp.mjs");
  const code = await new Promise((done) => {
    const child = spawn(process.execPath, [harness], {stdio: ["ignore", "pipe", "pipe"], env: {...process.env,
      PAGE_URL: pageUrl, OUT: join(out, "harness"), PROFILE: profile, SEED: String(options.seed || "26300"),
      PLAYER_NAME: options.player || "PacketCheck"}});
    let tail = "";
    child.stdout.on("data", (chunk) => { tail = (tail + chunk).slice(-2000); });
    child.stderr.on("data", (chunk) => { tail = (tail + chunk).slice(-2000); });
    child.on("exit", (exitCode) => { console.log(tail.trim().split("\n").slice(-1)[0] || ""); done(exitCode ?? 1); });
  });
  await new Promise((done) => setTimeout(done, 1500));
  server.close();
  writeFileSync(join(out, "capture.json"), JSON.stringify({profile, dist, pageUrl, seed: String(options.seed || "26300"),
    harnessExit: code, player: options.player || "PacketCheck"}, null, 2) + "\n");
  if (!existsSync(captureFile)) throw new Error("the probe posted no capture");
  console.log(`PACKET_PARITY_CAPTURE ${profile} harnessExit=${code} ${captureFile}`);
  return code;
}

// ---------------------------------------------------------------------------------------------
// jvm: PacketParityServer plus the replay of the browser client's packets.

function javaHome() {
  const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
  return home ? home.replace(/^\/([a-zA-Z])\//, "$1:/") : null;
}

function javaTool(name) {
  const home = javaHome();
  return home ? join(home, "bin", name) : name;
}

function vanillaClasspath(profile) {
  const work = join(root, "port/work", profile);
  const profileJson = JSON.parse(readFileSync(join(root, "port/versions", `${profile}.json`), "utf8"));
  const jar = profileJson.clientDistribution === "obfuscated-with-mappings"
    ? join(work, "client-named.jar") : join(work, "client-original.jar");
  const text = readFileSync(join(work, "classpath.txt"), "utf8").trim();
  const entries = text.startsWith("/") ? text.split(":") : text.split(delimiter);
  const libraries = entries.filter(Boolean).map((entry) => entry.replace(/^\/([a-zA-Z])\//, "$1:/"))
    .filter((entry) => !/client-(named|original)\.jar$/.test(entry));
  return [jar, ...libraries];
}

export function protocolTable(profile, out) {
  const classes = join(out, "classes");
  mkdirSync(classes, {recursive: true});
  const classpath = vanillaClasspath(profile);
  const compile = spawnSync(javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "--release", "25", "-proc:none",
    "-cp", classpath.join(delimiter), "-d", classes, join(here, "PacketParityServer.java"),
    join(root, "port/scripts/worldgen-seed-parity/compat", profile, "ParityCompat.java")], {encoding: "utf8"});
  if (compile.status !== 0) throw new Error(`javac PacketParityServer: ${compile.stderr || compile.stdout}`);
  const tableFile = join(out, `protocol-${profile}.json`);
  const dump = spawnSync(javaTool("java"), ["-Xmx1g", "-cp", [classes, ...classpath].join(delimiter),
    "dev.gaius.parity.PacketParityServer", "--protocol-table", tableFile], {encoding: "utf8"});
  if (dump.status !== 0) throw new Error(`protocol table: ${dump.stderr || dump.stdout}`);
  return {classes, classpath, table: JSON.parse(readFileSync(tableFile, "utf8"))};
}

function freePort() {
  return new Promise((done) => {
    const server = createServer();
    server.listen(0, "127.0.0.1", () => { const port = server.address().port; server.close(() => done(port)); });
  });
}

/**
 * Replays the client side of the recorded session against a server on host:port and records what
 * it sends back. Returns {records} in the capture format (id 1, "in"/"out", t, base64).
 */
export async function replay(session, protocol, port, {settleMs = 5000} = {}) {
  const sent = session.packets.filter((packet) => packet.dir === "out");
  const received = session.packets.filter((packet) => packet.dir === "in");
  // Before the play phase, the client answers the server: each client packet waits for the server
  // packet the browser client had received last before sending it (by name and occurrence within
  // the phase), e.g. login_acknowledged for login_finished, the client's select_known_packs for the
  // server's, finish_configuration for the server's.
  const gate = new Map();
  {
    let next = 0;
    const occurrences = new Map();
    let last = null;
    for (const packet of sent) {
      if (packet.phase === "play") break;
      while (next < received.length && received[next].t <= packet.t) {
        const other = received[next++];
        const key = `${other.phase}/${other.name}`;
        occurrences.set(key, (occurrences.get(key) ?? 0) + 1);
        last = {phase: other.phase, name: other.name, count: occurrences.get(key)};
      }
      if (last && last.phase === packet.phase) gate.set(packet, last);
    }
  }
  const answered = new Set(["keep_alive", "pong", "accept_teleportation", "chunk_batch_received",
    "configuration_acknowledged"]);
  const playStart = sent.find((packet) => packet.phase === "configuration" && packet.name === "finish_configuration")?.t;
  const lastTime = session.packets[session.packets.length - 1]?.t ?? 0;
  // accept_teleportation carries more than the id in newer versions (26.3: the position and
  // rotation the client ends up at): the answer to the JVM's n-th teleport is the client's n-th
  // recorded answer with the JVM's teleport id in front.
  const teleportAnswers = sent.filter((packet) => packet.name === "accept_teleportation").map((packet) => packet.payload);
  let teleports = 0;
  const batchRate = (() => {
    const packet = sent.find((candidate) => candidate.name === "chunk_batch_received");
    return packet ? view(packet.payload).getFloat32(0) : 9;
  })();
  const records = [];
  const started = performance.now();
  const now = () => Math.round((performance.now() - started) * 10) / 10;
  const socket = connect(port, "127.0.0.1");
  await new Promise((ready, fail) => { socket.once("connect", ready); socket.once("error", fail); });
  const reader = new FrameReader();
  let threshold = -1;
  let phaseIn = "login";
  let phaseOut = "handshake";
  const seenNames = new Map();
  let playStartedAt = null;
  let lastSendAt = performance.now();
  const queue = sent.slice();
  const write = (packetBytes) => {
    const frame = encodeFrame(packetBytes, threshold);
    records.push({id: 1, d: "out", t: now(), b: frame.toString("base64")});
    socket.write(frame);
  };
  const packetOf = (phase, name, payload) => Buffer.concat([writeVarInt(protocol.id(phase, "serverbound", name)), payload]);
  const pump = () => {
    while (queue.length) {
      const packet = queue[0];
      if (answered.has(packet.name) && packet.phase !== "handshake" && packet.phase !== "login") { queue.shift(); continue; }
      if (packet.phase !== phaseOut) {
        // The client's phase moves on only through its own packets, which come in order.
        return;
      }
      if (packet.phase === "play") {
        if (playStartedAt === null) return;
        if (performance.now() - playStartedAt < packet.t - playStart) return;
      } else if (gate.has(packet)) {
        const wanted = gate.get(packet);
        // A server packet the JVM never sends (a difference compare reports) holds the client
        // for 3 s at most; the phase changes of the client always wait for the server's.
        const changesPhase = packet.name === "login_acknowledged" || packet.name === "finish_configuration";
        if ((seenNames.get(`${wanted.phase}/${wanted.name}`) ?? 0) < wanted.count
            && (changesPhase || performance.now() - lastSendAt < 3000)) {
          if (!changesPhase || !(packet.name === "login_acknowledged" ? phaseIn !== "login" : phaseIn === "play")) return;
        }
      }
      queue.shift();
      lastSendAt = performance.now();
      write(Buffer.concat([writeVarInt(protocol.id(packet.phase, "serverbound", packet.name)), packet.payload]));
      if (packet.phase === "handshake" && packet.name === "intention") phaseOut = "login";
      else if (packet.phase === "login" && packet.name === "login_acknowledged") phaseOut = "configuration";
      else if (packet.phase === "configuration" && packet.name === "finish_configuration") {
        phaseOut = "play";
        playStartedAt = performance.now();
      }
    }
  };
  socket.on("data", (chunk) => {
    records.push({id: 1, d: "in", t: now(), b: Buffer.from(chunk).toString("base64")});
    for (const frame of reader.push(chunk)) {
      reader.threshold = threshold;
      const bytes = reader.packet(frame);
      const id = readVarInt(bytes, 0);
      const name = protocol.name(phaseIn, "clientbound", id.value);
      const payload = bytes.subarray(id.size);
      seenNames.set(`${phaseIn}/${name}`, (seenNames.get(`${phaseIn}/${name}`) ?? 0) + 1);
      if (phaseIn === "login" && name === "login_compression") threshold = readVarInt(payload, 0).value;
      else if (phaseIn === "login" && name === "login_finished") phaseIn = "configuration";
      else if (phaseIn === "configuration" && name === "finish_configuration") phaseIn = "play";
      // The answers the client gives itself.
      if (name === "keep_alive") write(packetOf(phaseOut, "keep_alive", payload));
      else if (name === "ping") write(packetOf(phaseOut, "pong", payload));
      else if (phaseIn === "play" && name === "player_position") {
        const template = teleportAnswers[Math.min(teleports++, teleportAnswers.length - 1)];
        const rest = template ? template.subarray(readVarInt(template, 0).size) : Buffer.alloc(0);
        write(packetOf("play", "accept_teleportation", Buffer.concat([writeVarInt(readVarInt(payload, 0).value), rest])));
      } else if (phaseIn === "play" && name === "chunk_batch_finished") {
        const rate = Buffer.alloc(4);
        rate.writeFloatBE(batchRate);
        write(packetOf("play", "chunk_batch_received", rate));
      }
    }
    pump();
  });
  const timer = setInterval(pump, 5);
  pump();
  const end = (playStart !== undefined ? lastTime - playStart : 0) + settleMs;
  await new Promise((done) => {
    const check = setInterval(() => {
      if (socket.destroyed || (playStartedAt !== null && performance.now() - playStartedAt > end)) {
        clearInterval(check);
        done();
      }
    }, 100);
  });
  clearInterval(timer);
  socket.destroy();
  return {records, unsent: queue.length, threshold};
}

async function jvm(options) {
  const profile = options.profile || usage("--profile is required");
  const captureDir = resolve(options.capture || usage("--capture is required"));
  const out = resolve(options.out || usage("--out is required"));
  mkdirSync(out, {recursive: true});
  const {classes, classpath, table} = protocolTable(profile, out);
  const protocol = new Protocol(table);
  const meta = JSON.parse(readFileSync(join(captureDir, "capture.json"), "utf8"));
  const session = sessionConnection(readCapture(join(captureDir, "capture.jsonl")), protocol);
  if (!session) throw new Error("the capture has no connection that completed a login");
  // The session ends with the first Save and Quit: the client's last packet of the connection.
  const login = session.packets.find((packet) => packet.dir === "in" && packet.phase === "play" && packet.name === "login");
  const spawnPacket = session.packets.find((packet) => packet.dir === "in" && packet.name === "set_default_spawn_position");
  if (!login || !spawnPacket) throw new Error("the session has no play login or default spawn position");
  // The browser server starts with distance 1 and raises it in steps (the server-distances-ramping
  // of the singleplayer Worker): its final view and simulation distances are the last
  // set_chunk_cache_radius and set_simulation_distance it sent, which the JVM server uses from the start.
  const distances = playLoginDistances(login.payload);
  const lastOf = (name) => session.packets.filter((packet) => packet.dir === "in" && packet.name === name).pop();
  const radius = lastOf("set_chunk_cache_radius");
  const simulation = lastOf("set_simulation_distance");
  if (radius) distances.viewDistance = readVarInt(radius.payload, 0).value;
  if (simulation) distances.simulationDistance = readVarInt(simulation.payload, 0).value;
  const difficultyPacket = session.packets.find((packet) => packet.dir === "in" && packet.name === "change_difficulty");
  const difficulty = difficultyPacket ? difficultyPacket.payload[0] : 2;
  const spawnAt = spawnPosition(spawnPacket.payload);
  const port = await freePort();
  const universe = join(out, "universe");
  rmSync(universe, {recursive: true, force: true});
  const ready = join(out, "ready");
  const stop = join(out, "stop");
  rmSync(ready, {force: true});
  rmSync(stop, {force: true});
  const serverLog = join(out, "server.log");
  const server = spawn(javaTool("java"), ["-Xmx3g", "-cp", [classes, ...classpath].join(delimiter),
    "dev.gaius.parity.PacketParityServer", "--seed", meta.seed, "--universe", universe, "--port", String(port),
    "--player", meta.player, "--spawn", `${spawnAt.x},${spawnAt.y},${spawnAt.z}`,
    "--view-distance", String(distances.viewDistance), "--simulation-distance", String(distances.simulationDistance),
    "--difficulty", String(difficulty),
    "--duration-s", "900", "--ready", ready, "--stop", stop], {stdio: ["ignore", "pipe", "pipe"]});
  server.stdout.on("data", (chunk) => appendFileSync(serverLog, chunk));
  server.stderr.on("data", (chunk) => appendFileSync(serverLog, chunk));
  const exited = new Promise((done) => server.on("exit", (code) => done(code)));
  const deadline = Date.now() + 300000;
  while (!existsSync(ready)) {
    if (Date.now() > deadline || server.exitCode !== null) throw new Error(`the JVM server did not start (${serverLog})`);
    await new Promise((done) => setTimeout(done, 250));
  }
  console.log(`packet-parity: JVM server ${profile} on ${port}, spawn ${spawnAt.x},${spawnAt.y},${spawnAt.z}, `
    + `view ${distances.viewDistance}, simulation ${distances.simulationDistance}`);
  const result = await replay(session, protocol, port);
  writeFileSync(stop, "stop\n");
  const code = await exited;
  writeFileSync(join(out, "jvm-stream.json"), JSON.stringify({profile, seed: meta.seed, port, spawn: spawnAt, distances,
    unsent: result.unsent, serverExit: code, records: result.records}) + "\n");
  console.log(`PACKET_PARITY_JVM ${profile} records=${result.records.length} unsent=${result.unsent} serverExit=${code}`);
  return code === 0 ? 0 : 1;
}

// ---------------------------------------------------------------------------------------------
// compare

// Packets whose payload is a value of the moment (time, ids of a round trip, tick rate) or
// depends on the client's movement: compared by count only.
const VOLATILE = new Set(["keep_alive", "ping", "set_time", "set_entity_motion", "move_entity_pos",
  "move_entity_pos_rot", "move_entity_rot", "rotate_head", "entity_position_sync", "set_entity_data",
  "teleport_entity", "player_position", "bundle_delimiter", "add_entity", "remove_entities",
  "update_attributes", "set_equipment", "chunk_batch_start", "chunk_batch_finished", "set_chunk_cache_center",
  "ticking_step", "ticking_state", "sound", "sound_entity", "level_event", "level_particles", "animate",
  "block_update", "section_blocks_update", "block_changed_ack", "take_item_entity", "entity_event",
  "set_health", "set_experience", "player_info_update", "player_info_remove", "system_chat",
  "disguised_chat", "player_chat", "container_set_content", "container_set_slot", "set_held_slot",
  "set_cursor_item", "set_player_inventory", "game_event", "damage_event", "hurt_animation", "block_event",
  "block_destruction", "update_mob_effect", "remove_mob_effect", "set_passengers", "pong_response",
  "debug_sample", "custom_payload", "forget_level_chunk", "set_simulation_distance"]);
const KEYED = new Set(["level_chunk_with_light", "light_update"]);
/**
 * The parts of a packet that are values of the session, removed before the byte comparison: the
 * play login's entity id (how many entities the server created first) and its view and simulation
 * distances (the browser server starts at 1 and raises them, server-distances-ramping of the
 * singleplayer Worker; the JVM server starts at the final distances), the login_finished session id
 * (a random UUID at its end), the times update_advancements reports criteria as obtained.
 */
export function sessionValuesRemoved(name, payload) {
  if (name === "login") {
    let offset = 4 + 1;
    const count = readVarInt(payload, offset);
    offset += count.size;
    for (let index = 0; index < count.value; index++) offset += readString(payload, offset).size;
    offset += readVarInt(payload, offset).size;
    const distancesStart = offset;
    offset += readVarInt(payload, offset).size;
    offset += readVarInt(payload, offset).size;
    return Buffer.concat([payload.subarray(4, distancesStart), payload.subarray(offset)]);
  }
  if (name === "login_finished") return payload.subarray(0, Math.max(0, payload.length - 16));
  if (name === "update_advancements") {
    // CriterionProgress: a present Optional (01) and the time the criterion was obtained, epoch
    // milliseconds of the session.
    const copy = Buffer.from(payload);
    for (let index = 0; index + 9 <= copy.length; index++) {
      if (copy[index] !== 1) continue;
      const millis = copy.readBigUInt64BE(index + 1);
      if (millis > 1500000000000n && millis < 4100000000000n) copy.fill(0, index + 1, index + 9);
    }
    return copy;
  }
  return payload;
}

/**
 * Whether two payloads hold the same bytes in another order: vanilla writes several maps from
 * HashMaps whose order follows identity hashes (update_tags by registry, update_recipes by
 * property set, update_advancements' progress), so even two JVM runs order them differently.
 */
export function sameBytesAnyOrder(a, b) {
  if (a.length !== b.length) return false;
  const counts = new Int32Array(256);
  for (const byte of a) counts[byte]++;
  for (const byte of b) counts[byte]--;
  return counts.every((count) => count === 0);
}

/**
 * A chunk packet with its heightmaps in type order: vanilla collects them with Collectors.toMap into
 * a HashMap keyed by the Heightmap.Types enum, whose order follows the enum's identity hash, so
 * two JVM runs already write them in different orders. x, z, then varint count and per heightmap
 * varint type id, varint long count and the longs; the sections and the rest stay as they are.
 */
export function canonicalChunk(payload) {
  try {
    return canonicalChunkOrThrow(payload);
  } catch {
    return payload;
  }
}

function canonicalChunkOrThrow(payload) {
  let offset = 8;
  const count = readVarInt(payload, offset);
  offset += count.size;
  const entries = [];
  for (let index = 0; index < count.value; index++) {
    const start = offset;
    const type = readVarInt(payload, offset);
    if (!type) throw new Error("truncated heightmap");
    offset += type.size;
    const longs = readVarInt(payload, offset);
    if (!longs) throw new Error("truncated heightmap");
    offset += longs.size + longs.value * 8;
    if (offset > payload.length) throw new Error("truncated heightmap");
    entries.push({type: type.value, bytes: payload.subarray(start, offset)});
  }
  entries.sort((a, b) => a.type - b.type);
  return Buffer.concat([payload.subarray(0, 8 + count.size), ...entries.map((entry) => entry.bytes),
    payload.subarray(offset)]);
}

/**
 * The block state and biome ids of a level_chunk_with_light payload: x, z, heightmaps, then a varint
 * length and the sections (per section: one short of non-empty blocks, before 26.x, or two with the
 * fluid count since then; the block and the biome paletted container, each a bits byte, the
 * palette (bits 0: one varint id; up to 8 bits for blocks or 3 for biomes: a varint list of ids;
 * more: none, the ids are global) and the fixed-size long array of the bit storage).
 */
export function chunkSections(payload, {sectionShorts, blockGlobalBits, biomeGlobalBits}) {
  let offset = 8;
  const count = readVarInt(payload, offset);
  offset += count.size;
  for (let index = 0; index < count.value; index++) {
    const type = readVarInt(payload, offset);
    offset += type.size;
    const longs = readVarInt(payload, offset);
    offset += longs.size + longs.value * 8;
  }
  const length = readVarInt(payload, offset);
  offset += length.size;
  const end = offset + length.value;
  const blocks = [];
  const biomes = [];
  const container = (size, maxIndirectBits, globalBits, out) => {
    const bits = payload[offset++];
    let palette = null;
    if (bits === 0) {
      const id = readVarInt(payload, offset);
      offset += id.size;
      palette = [id.value];
    } else if (bits <= maxIndirectBits) {
      const entries = readVarInt(payload, offset);
      offset += entries.size;
      palette = [];
      for (let index = 0; index < entries.value; index++) {
        const id = readVarInt(payload, offset);
        offset += id.size;
        palette.push(id.value);
      }
    } else if (bits !== globalBits) {
      throw new Error(`unexpected ${bits}-bit container (global ${globalBits})`);
    }
    if (bits === 0) {
      for (let index = 0; index < size; index++) out.push(palette[0]);
      return;
    }
    const perLong = Math.floor(64 / bits);
    const longCount = Math.ceil(size / perLong);
    const mask = (1n << BigInt(bits)) - 1n;
    for (let index = 0; index < size; index++) {
      const word = payload.readBigUInt64BE(offset + Math.floor(index / perLong) * 8);
      const value = Number((word >> BigInt((index % perLong) * bits)) & mask);
      out.push(palette ? palette[value] : value);
    }
    offset += longCount * 8;
  };
  while (offset < end) {
    offset += 2 * sectionShorts;
    container(4096, 8, blockGlobalBits, blocks);
    container(64, 3, biomeGlobalBits, biomes);
  }
  if (offset !== end) throw new Error(`sections end at ${offset}, not ${end}`);
  return {blocks, biomes};
}

/** Registry sizes from the configuration phase (registry_data: registry id, varint entry count). */
export function registrySizes(packets) {
  const sizes = {};
  for (const packet of packets) {
    if (packet.phase !== "configuration" || packet.name !== "registry_data") continue;
    const id = readString(packet.payload, 0);
    sizes[id.value] = readVarInt(packet.payload, id.size).value;
  }
  return sizes;
}

const bitsFor = (count) => Math.max(1, Math.ceil(Math.log2(count)));

function firstDifference(a, b) {
  const length = Math.min(a.length, b.length);
  for (let index = 0; index < length; index++) if (a[index] !== b[index]) return index;
  return a.length === b.length ? -1 : length;
}

/**
 * Compares two decoded server->client streams. Returns {phases, play, problems, notes}. With
 * {sections} (chunkSections options) the chunks of a position both servers sent are also
 * compared block by block and biome by biome.
 */
export function compareStreams(browser, jvmStream, {sections = null} = {}) {
  const problems = [];
  const notes = [];
  const result = {phases: {}, play: {keyed: {}, oneOff: {}, counted: {}}};
  for (const phase of ["login", "configuration"]) {
    const a = browser.filter((packet) => packet.phase === phase);
    const b = jvmStream.filter((packet) => packet.phase === phase);
    // login_compression is transport framing: the browser host connection is uncompressed (vanilla
    // singleplayer's memory connection), a TCP server compresses.
    const names = (list) => list.map((packet) => packet.name)
      .filter((name) => name !== "keep_alive" && name !== "ping" && name !== "login_compression");
    const sequenceEqual = names(a).join() === names(b).join();
    const differing = [];
    if (sequenceEqual) {
      const pa = a.filter((packet) => names([packet]).length);
      const pb = b.filter((packet) => names([packet]).length);
      for (let index = 0; index < pa.length; index++) {
        const a = sessionValuesRemoved(pa[index].name, pa[index].payload);
        const b = sessionValuesRemoved(pb[index].name, pb[index].payload);
        const at = firstDifference(a, b);
        if (at < 0) continue;
        if (sameBytesAnyOrder(a, b)) {
          notes.push(`${phase}: ${pa[index].name} #${index} holds the same bytes in another order (map order)`);
          continue;
        }
        differing.push({index, name: pa[index].name, browserBytes: pa[index].payload.length,
          jvmBytes: pb[index].payload.length, firstDifference: at});
      }
    } else {
      problems.push(`${phase}: packet sequences differ: browser ${names(a).join(" ")} | jvm ${names(b).join(" ")}`);
    }
    result.phases[phase] = {browser: a.length, jvm: b.length, sequenceEqual, differing};
    for (const difference of differing) {
      problems.push(`${phase}: ${difference.name} #${difference.index} differs at byte ${difference.firstDifference} `
        + `(browser ${difference.browserBytes} bytes, jvm ${difference.jvmBytes})`);
    }
  }
  const playA = browser.filter((packet) => packet.phase === "play");
  const playB = jvmStream.filter((packet) => packet.phase === "play");
  const byName = (list) => {
    const map = new Map();
    for (const packet of list) {
      if (!map.has(packet.name)) map.set(packet.name, []);
      map.get(packet.name).push(packet);
    }
    return map;
  };
  const a = byName(playA);
  const b = byName(playB);
  for (const name of [...new Set([...a.keys(), ...b.keys()])].sort()) {
    const listA = a.get(name) || [];
    const listB = b.get(name) || [];
    if (KEYED.has(name)) {
      const canonical = name === "level_chunk_with_light" ? canonicalChunk : (payload) => payload;
      const lastA = new Map(listA.map((packet) => [chunkKey(name, packet.payload), canonical(packet.payload)]));
      const firstB = new Map();
      for (const packet of listB) {
        const key = chunkKey(name, packet.payload);
        if (!firstB.has(key)) firstB.set(key, []);
        firstB.get(key).push(canonical(packet.payload));
      }
      let compared = 0;
      let equal = 0;
      const differing = [];
      for (const [key, payload] of lastA) {
        const candidates = firstB.get(key);
        if (!candidates) continue;
        compared++;
        if (candidates.some((candidate) => firstDifference(candidate, payload) < 0)) equal++;
        else differing.push(key);
      }
      result.play.keyed[name] = {browser: listA.length, jvm: listB.length, keysBrowser: lastA.size, keysJvm: firstB.size,
        compared, equal, differing: differing.slice(0, 50), differingCount: differing.length};
      if (name === "level_chunk_with_light" && sections) {
        // Block by block: the decorated blocks follow the decoration order (worldgen-seed-parity),
        // the biomes do not.
        const totals = {chunks: 0, blocks: 0, blocksDiffering: 0, biomes: 0, biomesDiffering: 0, undecoded: 0,
          biomeChunks: []};
        const jvmLast = new Map(listB.map((packet) => [chunkKey(name, packet.payload), packet.payload]));
        for (const packet of listA) {
          const key = chunkKey(name, packet.payload);
          const other = jvmLast.get(key);
          if (!other) continue;
          let one;
          let two;
          try {
            one = chunkSections(packet.payload, sections);
            two = chunkSections(other, sections);
          } catch {
            totals.undecoded++;
            continue;
          }
          totals.chunks++;
          let biomeDifferences = 0;
          for (let index = 0; index < one.blocks.length; index++) if (one.blocks[index] !== two.blocks[index]) totals.blocksDiffering++;
          for (let index = 0; index < one.biomes.length; index++) if (one.biomes[index] !== two.biomes[index]) biomeDifferences++;
          totals.blocks += one.blocks.length;
          totals.biomes += one.biomes.length;
          totals.biomesDiffering += biomeDifferences;
          if (biomeDifferences) totals.biomeChunks.push(key);
        }
        result.play.chunkBlocks = totals;
        notes.push(`play chunks: ${totals.chunks} positions decoded, ${totals.blocksDiffering} of ${totals.blocks} blocks `
          + `differ (${totals.blocks ? (100 * totals.blocksDiffering / totals.blocks).toFixed(3) : "0"} %), `
          + `${totals.biomesDiffering} of ${totals.biomes} biome cells differ`);
        if (totals.biomesDiffering) {
          problems.push(`play chunks: biomes differ in ${totals.biomeChunks.length} chunk(s): ${totals.biomeChunks.slice(0, 12).join(" ")}`);
        }
        if (totals.undecoded) problems.push(`play chunks: ${totals.undecoded} chunk packet(s) could not be decoded`);
      }
      if (differing.length) {
        notes.push(`play ${name}: ${equal}/${compared} positions byte-equal; differing ${differing.slice(0, 12).join(" ")}`
          + `${differing.length > 12 ? " ..." : ""} (chunk contents follow decoration order, see seed parity)`);
      }
    } else if (VOLATILE.has(name)) {
      result.play.counted[name] = {browser: listA.length, jvm: listB.length};
    } else {
      const pa = listA[0];
      const pb = listB[0];
      const a = pa ? sessionValuesRemoved(name, pa.payload) : null;
      const b = pb ? sessionValuesRemoved(name, pb.payload) : null;
      const at = a && b ? firstDifference(a, b) : null;
      const reordered = at !== null && at >= 0 && sameBytesAnyOrder(a, b);
      result.play.oneOff[name] = {browser: listA.length, jvm: listB.length, firstEqual: at === -1 || reordered,
        reordered, firstDifference: at, browserBytes: pa?.payload.length ?? null, jvmBytes: pb?.payload.length ?? null};
      if (!pa || !pb) {
        notes.push(`play ${name}: only the ${pa ? "browser" : "JVM"} server sent it (${pa ? listA.length : listB.length}x)`);
      } else if (reordered) {
        notes.push(`play ${name}: the same bytes in another order (map order)`);
      } else if (at !== -1) {
        problems.push(`play ${name}: first packet differs at byte ${at} (browser ${pa.payload.length} bytes, `
          + `jvm ${pb.payload.length})`);
      }
    }
  }
  return {...result, problems, notes};
}

export function comparePacketBytes(browser, jvmStream) {
  const rows = (packets) => packets.map((packet) => ({phase: packet.phase, name: packet.name, id: packet.id,
    bytes: packet.payload.length, sha256: createHash("sha256").update(writeVarInt(packet.id))
      .update(packet.payload).digest("hex")}));
  const left = rows(browser);
  const right = rows(jvmStream);
  let equal = 0;
  const samples = [];
  const compared = Math.min(left.length, right.length);
  for (let index = 0; index < Math.max(left.length, right.length); index++) {
    const one = left[index];
    const two = right[index];
    if (one && two && one.phase === two.phase && one.name === two.name && one.sha256 === two.sha256) {
      equal++;
    } else if (samples.length < 32) {
      samples.push({index, browser: one ?? null, jvm: two ?? null});
    }
  }
  return {pass: compared > 0 && equal === left.length && equal === right.length, compared, equal,
    unequal: compared - equal, browserOnly: Math.max(0, left.length - right.length),
    jvmOnly: Math.max(0, right.length - left.length), samples, browser: left, jvm: right};
}

async function compare(options) {
  const profile = options.profile || usage("--profile is required");
  const captureDir = resolve(options.capture || usage("--capture is required"));
  const jvmDir = resolve(options.jvm || usage("--jvm is required"));
  const out = resolve(options.out || jvmDir);
  const table = JSON.parse(readFileSync(join(jvmDir, `protocol-${profile}.json`), "utf8"));
  const protocol = new Protocol(table);
  const session = sessionConnection(readCapture(join(captureDir, "capture.jsonl")), protocol);
  if (!session) throw new Error("the capture has no connection that completed a login");
  const captureMeta = JSON.parse(readFileSync(join(captureDir, "capture.json"), "utf8"));
  const jvmMeta = JSON.parse(readFileSync(join(jvmDir, "jvm-stream.json"), "utf8"));
  const jvmDecoded = decodeConnection(jvmMeta.records, protocol);
  const browserIn = session.packets.filter((packet) => packet.dir === "in");
  const jvmIn = jvmDecoded.packets.filter((packet) => packet.dir === "in");
  // The browser session ends at the first Save and Quit; compare the JVM stream up to the same
  // time after the play phase began.
  const sizes = registrySizes(browserIn);
  const sections = table.blockStates && sizes["minecraft:worldgen/biome"] ? {
    sectionShorts: table.sectionShorts,
    blockGlobalBits: bitsFor(table.blockStates),
    biomeGlobalBits: bitsFor(sizes["minecraft:worldgen/biome"]),
  } : null;
  if (sections && ![1, 2].includes(sections.sectionShorts)) {
    throw new Error("protocol table lacks sectionShorts; refresh it with the JVM table command");
  }
  const result = compareStreams(browserIn, jvmIn, {sections});
  const byteParity = comparePacketBytes(browserIn, jvmIn);
  const profileFile = join(root, "port/versions", `${profile}.json`);
  const semantics = JSON.parse(readFileSync(profileFile, "utf8")).packetSemantics;
  const logical = compareLogical(session.packets, jvmDecoded.packets, semantics);
  const ledgerBytes = Buffer.from(logical.ledger.map(row => JSON.stringify(row)).join("\n") + "\n");
  mkdirSync(out, {recursive: true});
  writeFileSync(join(out, "packet-ledger.jsonl"), ledgerBytes);
  const {ledger, ...logicalParity} = logical;
  logicalParity.ledger = {file: "packet-ledger.jsonl", sha256: createHash("sha256").update(ledgerBytes).digest("hex")};
  const sourceProblems = [];
  if (captureMeta.harnessExit !== 0 || jvmMeta.serverExit !== 0 || jvmMeta.unsent !== 0) {
    sourceProblems.push("the browser or JVM scripted session did not complete successfully");
  }
  if (captureMeta.profile !== profile || jvmMeta.profile !== profile || captureMeta.seed !== jvmMeta.seed) {
    sourceProblems.push("capture and JVM profile/seed identities differ");
  }
  const conditions = compareSimulationConditions(browserIn, jvmIn);
  if (!conditions.pass) sourceProblems.push(conditions.problem ?? "view/simulation distance histories differ");
  const sourceChecks = {pass: sourceProblems.length === 0, problems: sourceProblems,
    conditions,
    sha256: Object.fromEntries(Object.entries({capture: join(captureDir, "capture.jsonl"),
      captureMetadata: join(captureDir, "capture.json"),
      jvm: join(jvmDir, "jvm-stream.json"), table: join(jvmDir, `protocol-${profile}.json`), profile: profileFile})
      .map(([name, file]) => [name, createHash("sha256").update(readFileSync(file)).digest("hex")]))};
  const summary = {profile, capture: captureDir, jvm: jvmDir, compression: {browser: session.threshold,
    jvm: jvmDecoded.threshold}, packets: {browser: browserIn.length, jvm: jvmIn.length}, byteParity,
    diagnostics: result, logicalParity, sourceChecks,
    verdict: sourceChecks.pass && (logicalParity.pass || byteParity.pass) ? "PARITY" : "DIFFERENCES"};
  writeFileSync(join(out, "packet-parity.json"), JSON.stringify(summary, null, 2) + "\n");
  for (const problem of sourceProblems) console.log(`PACKET_PARITY_DIFFERENCE ${profile}: ${problem}`);
  console.log(`PACKET_PARITY ${profile} ${summary.verdict} accounted=${logicalParity.accounted} `
    + `equal=${logicalParity.equal} explained=${logicalParity.explained} differences=${logicalParity.difference} `
    + `unresolved=${logicalParity.unresolved}; ${join(out, "packet-parity.json")}`);
  return summary.verdict === "PARITY" ? 0 : 1;
}

// ---------------------------------------------------------------------------------------------

function selfTest() {
  const failures = [];
  const expect = (label, condition) => { if (!condition) failures.push(label); };
  for (const value of [0, 1, 127, 128, 300, 2097151, 0x7fffffff]) {
    const encoded = writeVarInt(value);
    expect(`varint ${value}`, readVarInt(encoded, 0).value === value && readVarInt(encoded, 0).size === encoded.length);
  }
  const reader = new FrameReader();
  const packet = Buffer.from([5, 1, 2, 3]);
  const frames = reader.push(Buffer.concat([encodeFrame(packet, -1), encodeFrame(packet, -1).subarray(0, 2)]));
  expect("one whole frame and a partial one", frames.length === 1 && reader.buffer.length === 2);
  reader.threshold = 2;
  const big = Buffer.alloc(300, 7);
  const compressed = new FrameReader();
  compressed.threshold = 256;
  const [frame] = compressed.push(encodeFrame(big, 256));
  expect("compressed frame round trip", compressed.packet(frame).equals(big));
  const [small] = compressed.push(encodeFrame(packet, 256));
  expect("small packet below the threshold", compressed.packet(small).equals(packet));
  const table = {"handshake/serverbound": {"0": "minecraft:intention"},
    "login/clientbound": {"2": "minecraft:login_finished", "3": "minecraft:login_compression"},
    "login/serverbound": {"0": "minecraft:hello", "3": "minecraft:login_acknowledged"},
    "configuration/clientbound": {"3": "minecraft:finish_configuration", "7": "minecraft:registry_data"},
    "configuration/serverbound": {"3": "minecraft:finish_configuration"},
    "play/clientbound": {"0": "minecraft:level_chunk_with_light", "1": "minecraft:commands"},
    "play/serverbound": {"0": "minecraft:chat_command"}};
  const protocol = new Protocol(table);
  const intent = Buffer.concat([writeVarInt(0), writeVarInt(777), writeVarInt(1), Buffer.from("x"), Buffer.from([0, 1]),
    writeVarInt(2)]);
  const records = [
    {d: "out", t: 1, b: encodeFrame(intent, -1).toString("base64")},
    {d: "out", t: 2, b: encodeFrame(Buffer.concat([writeVarInt(0), Buffer.from([1, 65])]), -1).toString("base64")},
    {d: "in", t: 3, b: encodeFrame(Buffer.concat([writeVarInt(3), writeVarInt(256)]), -1).toString("base64")},
    {d: "in", t: 4, b: encodeFrame(Buffer.from([2, 9]), 256).toString("base64")},
    {d: "out", t: 5, b: encodeFrame(Buffer.from([3]), 256).toString("base64")},
    {d: "in", t: 6, b: encodeFrame(Buffer.concat([writeVarInt(7), Buffer.alloc(400, 1)]), 256).toString("base64")},
    {d: "in", t: 7, b: encodeFrame(Buffer.from([3]), 256).toString("base64")},
    {d: "out", t: 8, b: encodeFrame(Buffer.from([3]), 256).toString("base64")},
    {d: "in", t: 9, b: encodeFrame(Buffer.concat([writeVarInt(0), Buffer.from([0, 0, 0, 1, 0, 0, 0, 2, 9])]), 256).toString("base64")},
  ];
  const decoded = decodeConnection(records, protocol);
  const sequence = decoded.packets.map((p) => `${p.dir}:${p.phase}:${p.name}`).join();
  expect(`phases follow the connection (${sequence})`, sequence ===
    "out:handshake:intention,out:login:hello,in:login:login_compression,in:login:login_finished,"
    + "out:login:login_acknowledged,in:configuration:registry_data,in:configuration:finish_configuration,"
    + "out:configuration:finish_configuration,in:play:level_chunk_with_light");
  expect("compression threshold read", decoded.threshold === 256);
  const browser = decoded.packets.filter((p) => p.dir === "in");
  expect("exact bytes of a stream equal itself", comparePacketBytes(browser, browser).pass);
  expect("empty streams are not evidence", !comparePacketBytes([], []).pass);
  let result = compareStreams(browser, browser);
  expect("a stream equals itself", result.problems.length === 0 && result.play.keyed.level_chunk_with_light.equal === 1);
  const other = browser.map((p) => (p.name === "registry_data" ? {...p, payload: Buffer.alloc(400, 2)} : p));
  result = compareStreams(browser, other);
  expect("a configuration difference is a problem", result.problems.some((p) => /registry_data #0 differs at byte 0/.test(p)));
  const chunkOther = browser.map((p) => (p.name === "level_chunk_with_light"
    ? {...p, payload: Buffer.from([0, 0, 0, 1, 0, 0, 0, 2, 8])} : p));
  result = compareStreams(browser, chunkOther);
  expect("a chunk byte difference fails strict parity", !comparePacketBytes(browser, chunkOther).pass);
  expect("missing packets fail strict parity", !comparePacketBytes(browser, browser.slice(1)).pass);
  const ordered = browser.map((packet) => packet.name === "registry_data"
    ? {...packet, payload: Buffer.from([1, 2])} : packet);
  const permuted = ordered.map((packet) => packet.name === "registry_data"
    ? {...packet, payload: Buffer.from([2, 1])} : packet);
  expect("map byte order is not waived", !comparePacketBytes(ordered, permuted).pass);
  expect("a chunk difference is a note with its position", result.problems.length === 0
    && result.notes.some((n) => /1,2/.test(n)));
  const heightmap = (type, value) => Buffer.concat([writeVarInt(type), writeVarInt(1), Buffer.alloc(8, value)]);
  const chunkPayload = (order) => Buffer.concat([Buffer.from([0, 0, 0, 1, 0, 0, 0, 2]), writeVarInt(2),
    ...order.map((type) => heightmap(type, type)), Buffer.from([5, 6, 7])]);
  expect("heightmap order does not matter", canonicalChunk(chunkPayload([1, 4])).equals(canonicalChunk(chunkPayload([4, 1]))));
  expect("heightmap contents do", !canonicalChunk(chunkPayload([1, 4])).equals(canonicalChunk(Buffer.concat([
    Buffer.from([0, 0, 0, 1, 0, 0, 0, 2]), writeVarInt(2), heightmap(1, 1), heightmap(4, 9), Buffer.from([5, 6, 7])]))));
  const spawnPayload = Buffer.concat([writeVarInt(19), Buffer.from("minecraft:overworld"), Buffer.alloc(16)]);
  const packed = (BigInt(-24 & 0x3ffffff) << 38n) | (BigInt(104 & 0x3ffffff) << 12n) | BigInt(319 & 0xfff);
  spawnPayload.writeBigInt64BE(BigInt.asIntN(64, packed), 20);
  const at = spawnPosition(spawnPayload);
  expect("spawn position unpacked", at.x === -24 && at.z === 104 && at.y === 319 && at.dimension === "minecraft:overworld");
  for (const failure of failures) console.log(`PACKET_PARITY_SELF_TEST_FAIL ${failure}`);
  console.log(`PACKET_PARITY_SELF_TEST ${failures.length ? "FAIL" : "PASS"} (${failures.length} failure(s))`);
  return failures.length === 0;
}

// Run as a script only (the functions above are also imported by other tools).
const main = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
const [command, ...rest] = main ? process.argv.slice(2) : ["--imported"];
if (command === "--self-test") process.exit(selfTest() ? 0 : 1);
const options = main ? argMap(rest) : {};
if (main) try {
  if (command === "capture") process.exitCode = await capture(options) === 0 ? 0 : 1;
  else if (command === "jvm") process.exitCode = await jvm(options);
  else if (command === "compare") process.exitCode = await compare(options);
  else if (command === "table") protocolTable(options.profile || usage("--profile is required"),
    resolve(options.out || usage("--out is required")));
  else if (command === "inspect") {
    const protocol = new Protocol(JSON.parse(readFileSync(resolve(options.table || usage("--table is required")), "utf8")));
    const connections = readCapture(join(resolve(options.capture || usage("--capture is required")), "capture.jsonl"));
    for (const connection of connections.values()) {
      console.log(`connection ${connection.id} open ${JSON.stringify(connection.open)} records ${connection.records.length}`);
    }
    const session = sessionConnection(connections, protocol);
    if (!session) throw new Error("no connection completed a login");
    const counts = {};
    for (const packet of session.packets) {
      const key = `${packet.dir} ${packet.phase} ${packet.name}`;
      counts[key] = (counts[key] ?? 0) + 1;
    }
    console.log(JSON.stringify({session: session.connection.id, threshold: session.threshold, packets: session.packets.length,
      first: session.packets.slice(0, 14).map((packet) => `${packet.dir}:${packet.phase}:${packet.name}:${packet.payload.length}`),
      counts}, null, 1));
    if (options.dump) {
      const packet = session.packets.find((candidate) => candidate.name === options.dump);
      console.log(`${options.dump}: ${packet ? packet.payload.subarray(0, 160).toString("hex") : "none"}`);
    }
  }
  else if (command === "run") {
    const out = resolve(options.out || usage("--out is required"));
    const captureDir = join(out, "browser");
    const jvmDir = join(out, "jvm");
    if (await capture({...options, out: captureDir}) !== 0) throw new Error("browser scripted session failed");
    if (await jvm({...options, capture: captureDir, out: jvmDir}) !== 0) throw new Error("JVM scripted session failed");
    process.exitCode = await compare({...options, capture: captureDir, jvm: jvmDir, out});
  } else usage(command ? `unknown command ${command}` : "");
} catch (error) {
  console.error(`packet-parity: ${error.stack || error}`);
  process.exitCode = 1;
}
