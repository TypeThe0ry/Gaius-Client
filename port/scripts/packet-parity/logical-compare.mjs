import {createHash} from "node:crypto";
export {compareTickExact, validateTickCapture, validateTickScript} from "./tick-compare.mjs";

const digest = bytes => createHash("sha256").update(bytes).digest("hex");
const encode = value => Buffer.from(JSON.stringify(value));
const dynamicNames = new Set(["add_entity", "remove_entities", "entity_event", "set_entity_data",
  "update_attributes", "set_equipment", "set_entity_motion", "rotate_head", "entity_position_sync",
  "move_entity_pos", "move_entity_pos_rot", "move_entity_rot", "damage_event", "block_update",
  "section_blocks_update", "block_event", "level_event", "player_position", "set_health",
  "set_experience", "set_slot", "container_set_slot", "container_set_content", "system_chat",
  "command_suggestions", "block_changed_ack", "forget_level_chunk", "update_advancements"]);

class Cursor {
  constructor(bytes) { this.bytes = bytes; this.offset = 0; }
  take(length) {
    if (!Number.isSafeInteger(length) || length < 0 || this.offset + length > this.bytes.length) {
      throw new Error("truncated or oversized packet field");
    }
    const result = this.bytes.subarray(this.offset, this.offset + length);
    this.offset += length;
    return result;
  }
  integer() {
    let value = 0;
    for (let index = 0; index < 5; index++) {
      const byte = this.take(1)[0];
      if (index === 4 && (byte & 0xf0)) throw new Error("invalid VarInt");
      value |= (byte & 0x7f) << (7 * index);
      if (!(byte & 0x80)) return value;
    }
    throw new Error("invalid VarInt");
  }
  count() {
    const count = this.integer();
    if (count < 0 || count > this.bytes.length) throw new Error("invalid collection length");
    return count;
  }
  long() { return this.take(8).readBigInt64BE(); }
  variableLong() {
    let value = 0n;
    for (let index = 0; index < 10; index++) {
      const byte = this.take(1)[0];
      if (index === 9 && (byte & 0xfe)) throw new Error("invalid VarLong");
      value |= BigInt(byte & 0x7f) << BigInt(index * 7);
      if (!(byte & 0x80)) return BigInt.asIntN(64, value);
    }
    throw new Error("invalid VarLong");
  }
  string() { return new TextDecoder("utf-8", {fatal: true}).decode(this.take(this.count())); }
  bool() {
    const value = this.take(1)[0];
    if (value > 1) throw new Error("invalid boolean");
    return value === 1;
  }
  end() { if (this.offset !== this.bytes.length) throw new Error("unparsed packet suffix"); }
}

function sortedMap(entries) {
  if (new Set(entries.map(([key]) => key)).size !== entries.length) throw new Error("duplicate map key");
  return entries.sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
}

function tags(bytes) {
  const cursor = new Cursor(bytes);
  const registries = [];
  for (let remaining = cursor.count(); remaining > 0; remaining--) {
    const registry = cursor.string();
    const entries = [];
    for (let tagCount = cursor.count(); tagCount > 0; tagCount--) {
      const tag = cursor.string();
      const ids = [];
      for (let idCount = cursor.count(); idCount > 0; idCount--) ids.push(cursor.integer());
      entries.push([tag, ids]);
    }
    registries.push([registry, sortedMap(entries)]);
  }
  cursor.end();
  return encode(sortedMap(registries));
}

function loginFinished(bytes, semantics) {
  const cursor = new Cursor(bytes);
  const player = cursor.take(16).toString("hex");
  const name = cursor.string();
  const properties = [];
  for (let remaining = cursor.count(); remaining > 0; remaining--) {
    properties.push([cursor.string(), cursor.string(), cursor.bool() ? cursor.string() : null]);
  }
  if (semantics.loginSessionUuid) cursor.take(16);
  cursor.end();
  return encode({player, name, properties});
}

function chunk(bytes) {
  const cursor = new Cursor(bytes);
  const position = cursor.take(8);
  const maps = [];
  for (let remaining = cursor.count(); remaining > 0; remaining--) {
    const kind = cursor.integer();
    maps.push([kind, cursor.take(cursor.count() * 8).toString("hex")]);
  }
  return Buffer.concat([position, encode(sortedMap(maps)), bytes.subarray(cursor.offset)]);
}

function timeState(bytes, semantics, clocks) {
  const cursor = new Cursor(bytes);
  const tick = cursor.long();
  if (tick < 0n || tick > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error("unsupported game tick");
  let state;
  if (semantics.timeUpdate === "world-clocks") {
    const entries = [];
    for (let remaining = cursor.count(); remaining > 0; remaining--) {
      const clock = cursor.integer();
      const ticks = cursor.variableLong();
      const partial = cursor.take(4).readFloatBE();
      const rate = cursor.take(4).readFloatBE();
      if (partial !== 0 || (rate !== 0 && rate !== 1)) throw new Error("unsupported clock interpolation/rate");
      entries.push([clock, {time: String(rate === 1 ? ticks - tick : ticks), rate}]);
    }
    for (const [clock, value] of sortedMap(entries)) clocks.set(clock, value);
    state = sortedMap([...clocks]);
    if (!state.length) throw new Error("clock delta without an initial snapshot");
  } else if (semantics.timeUpdate === "day-time") {
    const day = cursor.long();
    const running = cursor.bool();
    state = {time: String(running ? day - tick : day), running};
  } else throw new Error("profile lacks a supported time-update codec");
  cursor.end();
  return {tick: Number(tick), state: digest(encode(state))};
}

function logicalKey(packet) {
  const prefix = `${packet.phase}/${packet.name}`;
  const cursor = new Cursor(packet.payload);
  if (packet.name === "registry_data") return `${prefix}/${cursor.string()}`;
  if (packet.name === "level_chunk_with_light") {
    const position = cursor.take(8);
    return `${prefix}/${position.readInt32BE()},${position.readInt32BE(4)}`;
  }
  if (packet.name === "forget_level_chunk") return `${prefix}/${cursor.take(8).toString("hex")}`;
  if (packet.name === "light_update") {
    return `${prefix}/${cursor.integer()},${cursor.integer()}`;
  }
  if (packet.name === "section_blocks_update" || packet.name === "block_update") {
    return `${prefix}/${cursor.take(8).toString("hex")}`;
  }
  return dynamicNames.has(packet.name) ? prefix : `${packet.phase}/ordered`;
}

function annotate(packets, semantics, side) {
  const clocks = new Map();
  const rows = packets.map((packet, index) => ({packet, index, side, phase: packet.phase,
    name: packet.name, bytes: packet.payload.length, wireHash: digest(packet.payload), tick: null}));
  let previousTick = null;
  for (const row of rows) {
    try {
      row.key = logicalKey(row.packet);
      const {name, payload} = row.packet;
      let canonical = payload;
      if (name === "update_tags") canonical = tags(payload);
      else if (name === "login_finished") canonical = loginFinished(payload, semantics);
      else if (name === "level_chunk_with_light") canonical = chunk(payload);
      else if (name === "set_time") {
        const value = timeState(payload, semantics, clocks);
        if (previousTick !== null && value.tick < previousTick) throw new Error("game tick went backwards");
        row.anchor = value.tick;
        row.timeState = value.state;
        previousTick = value.tick;
      }
      row.contentHash = digest(canonical);
    } catch (error) { row.error = error.message; }
  }
  let lower = null;
  for (const row of rows) {
    if (row.anchor !== undefined) lower = row.anchor;
    if (row.phase === "play") row.tick = {lower, upper: null, exact: row.anchor !== undefined};
  }
  let upper = null;
  for (let index = rows.length - 1; index >= 0; index--) {
    const row = rows[index];
    if (row.anchor !== undefined) upper = row.anchor;
    if (row.tick) row.tick.upper = row.anchor ?? upper;
  }
  return rows;
}

function overlap(left, right) {
  if (!left || !right || left.lower === null || left.upper === null
      || right.lower === null || right.upper === null) return false;
  return left.lower <= right.upper && right.lower <= left.upper;
}

function controls(rows, allPackets, finish) {
  let bundle = false;
  let batch = null;
  const controlRows = [];
  for (const row of rows) {
    const {name, payload} = row.packet;
    if (row.error) continue;
    if (name === "bundle_delimiter") {
      if (payload.length) { finish(row, "difference", "invalid-bundle-delimiter"); continue; }
      bundle = !bundle;
      controlRows.push([row, "bundle-framing"]);
    } else if (name === "chunk_batch_start") {
      if (batch !== null || payload.length) { finish(row, "difference", "invalid-chunk-batch"); continue; }
      batch = 0;
      controlRows.push([row, "chunk-batch-framing"]);
    } else if (name === "level_chunk_with_light" && batch !== null) batch++;
    else if (name === "chunk_batch_finished") {
      try {
        const cursor = new Cursor(payload);
        const count = cursor.integer();
        cursor.end();
        if (batch === null || count !== batch) throw new Error("chunk batch size mismatch");
        batch = null;
        controlRows.push([row, "chunk-batch-framing"]);
      } catch (error) { finish(row, "difference", error.message); }
    } else if (name === "login_compression" && row.phase === "login") {
      // Transport framing, not game state: the frame decoder consumed the threshold. The browser
      // host's connection is uncompressed like vanilla singleplayer's memory connection and sends
      // none, a TCP server (the JVM replay of an older capture, a LAN guest) sends one.
      try {
        const cursor = new Cursor(payload);
        if (cursor.integer() < 0) throw new Error("negative compression threshold");
        cursor.end();
        controlRows.push([row, "compression-framing"]);
      } catch (error) { finish(row, "difference", error.message); }
    } else if (name === "keep_alive" || name === "ping") {
      const replyName = name === "ping" ? "pong" : "keep_alive";
      const bytes = name === "ping" ? 4 : 8;
      const replies = allPackets.filter(packet => packet.dir === "out" && packet.phase === row.phase
        && packet.name === replyName && packet.payload.equals(payload) && packet.t >= row.packet.t);
      const requests = rows.filter(request => request.phase === row.phase && request.name === name
        && request.packet.payload.equals(payload));
      if (payload.length !== bytes || replies.length !== 1 || requests.length !== 1) {
        finish(row, "unresolved", "heartbeat-response-not-proven");
      } else finish(row, "explained", "acknowledged-heartbeat-token");
    }
  }
  for (const [row, reason] of controlRows) {
    finish(row, bundle || batch !== null ? "unresolved" : "explained",
      bundle || batch !== null ? "incomplete-control-framing" : reason);
  }
}

export function compareLogical(browserPackets, jvmPackets, semantics) {
  if (typeof semantics?.loginSessionUuid !== "boolean") throw new Error("profile lacks loginSessionUuid semantics");
  const browser = annotate(browserPackets.filter(packet => packet.dir !== "out"), semantics, "browser");
  const jvm = annotate(jvmPackets.filter(packet => packet.dir !== "out"), semantics, "jvm");
  const ledger = [];
  const finish = (row, status, reason, counterpart = null) => {
    if (row.status) throw new Error("packet classified twice");
    row.status = status;
    ledger.push({side: row.side, index: row.index, phase: row.phase, name: row.name, key: row.key ?? null,
      bytes: row.bytes, wireHash: row.wireHash, contentHash: row.contentHash ?? null, tick: row.tick,
      status, reason, counterpart});
  };
  for (const rows of [browser, jvm]) {
    for (const row of rows) if (row.error) finish(row, "unresolved", `decode: ${row.error}`);
  }
  controls(browser, browserPackets, finish);
  controls(jvm, jvmPackets, finish);
  const timeRows = [browser, jvm].map(rows => rows.filter(row => row.name === "set_time" && !row.status));
  const transitions = rows => rows.filter((row, index) => !index || row.timeState !== rows[index - 1].timeState)
    .map(row => row.timeState);
  if (timeRows[0].length && timeRows[1].length
      && JSON.stringify(transitions(timeRows[0])) === JSON.stringify(transitions(timeRows[1]))) {
    for (const rows of timeRows) for (const row of rows) finish(row, "explained", "time-sampling-same-clock-transitions");
  }
  const groups = new Map();
  for (const row of [...browser, ...jvm]) {
    if (row.status) continue;
    if (!groups.has(row.key)) groups.set(row.key, {browser: [], jvm: []});
    groups.get(row.key)[row.side].push(row);
  }
  const classifyMissing = row => finish(row, dynamicNames.has(row.name) ? "unresolved" : "difference",
    dynamicNames.has(row.name) ? "tick-or-entity-history-not-proven" : "missing-logical-packet");
  const compatible = (left, right) => left.name === right.name && left.phase === right.phase
    && left.contentHash === right.contentHash
    && (!dynamicNames.has(left.name) || overlap(left.tick, right.tick));
  for (const group of groups.values()) {
    let browserIndex = 0;
    let jvmIndex = 0;
    while (browserIndex < group.browser.length && jvmIndex < group.jvm.length) {
      const left = group.browser[browserIndex];
      const right = group.jvm[jvmIndex];
      if (compatible(left, right)) {
        const equal = left.wireHash === right.wireHash;
        const dynamic = dynamicNames.has(left.name);
        const status = dynamic ? "unresolved" : equal ? "equal" : "explained";
        const reason = dynamic ? "matching-content-needs-exact-tick-and-entity-binding"
          : equal ? "logical-content" : "typed-map-or-session-uuid";
        finish(left, status, reason, right.index);
        finish(right, status, reason, left.index);
        browserIndex++;
        jvmIndex++;
        continue;
      }
      let leftSkip = -1;
      let rightSkip = -1;
      for (let distance = 1; distance <= 64; distance++) {
        if (browserIndex + distance < group.browser.length && compatible(group.browser[browserIndex + distance], right)) {
          leftSkip = distance;
          break;
        }
        if (jvmIndex + distance < group.jvm.length && compatible(left, group.jvm[jvmIndex + distance])) {
          rightSkip = distance;
          break;
        }
      }
      if (leftSkip >= 0) {
        while (leftSkip-- > 0) classifyMissing(group.browser[browserIndex++]);
      } else if (rightSkip >= 0) {
        while (rightSkip-- > 0) classifyMissing(group.jvm[jvmIndex++]);
      } else {
        const dynamic = dynamicNames.has(left.name);
        const reason = dynamic ? "tick-or-entity-history-not-proven" : "logical-content-mismatch";
        finish(left, dynamic ? "unresolved" : "difference", reason, right.index);
        finish(right, dynamic ? "unresolved" : "difference", reason, left.index);
        browserIndex++;
        jvmIndex++;
      }
    }
    while (browserIndex < group.browser.length) classifyMissing(group.browser[browserIndex++]);
    while (jvmIndex < group.jvm.length) classifyMissing(group.jvm[jvmIndex++]);
  }
  ledger.sort((left, right) => left.side.localeCompare(right.side) || left.index - right.index);
  const counts = {equal: 0, explained: 0, difference: 0, unresolved: 0};
  const classifications = {};
  for (const row of ledger) {
    counts[row.status]++;
    const key = `${row.status}/${row.phase}/${row.name}/${row.reason}`;
    classifications[key] = (classifications[key] ?? 0) + 1;
  }
  const accounted = ledger.length;
  const total = browser.length + jvm.length;
  const pass = browser.length > 0 && jvm.length > 0 && accounted === total
    && counts.difference === 0 && counts.unresolved === 0;
  const anchors = rows => rows.filter(row => row.anchor !== undefined).map(row => row.anchor);
  return {schema: 1, pass, browser: browser.length, jvm: jvm.length, accounted, ...counts, classifications,
    tickEvidence: {kind: "set-time-bounds-not-exact-ticks", browser: anchors(browser), jvm: anchors(jvm)}, ledger};
}
