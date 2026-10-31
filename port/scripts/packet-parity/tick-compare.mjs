import {createHash} from "node:crypto";

const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === "object"
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const hash = value => createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex");
const kinds = new Set(["session", "ready", "player-spawn", "setup-command", "tick-start", "tick-end", "entity", "entity-identity",
  "inject", "input", "packet", "bundle-start", "bundle-end", "complete", "settle-start", "settle-end", "player-construct",
  "chunk-broadcast-start", "chunk-broadcast-end"]);

export function validateTickScript(script) {
  const integer = (value, minimum, maximum) => Number.isSafeInteger(value) && value >= minimum && value <= maximum;
  if (script?.schema !== 1 || typeof script.profile !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(script.profile)
      || typeof script.player !== "string" || !/^[A-Za-z0-9_]{1,16}$/.test(script.player)
      || !["id", "id-position"].includes(script.teleportAcknowledgement) || script.settle !== "quiescent"
      || typeof script.seed !== "string" || !/^-?\d+$/.test(script.seed)
      || BigInt(script.seed) < -(1n << 63n) || BigInt(script.seed) >= (1n << 63n) || BigInt(script.seed).toString() !== script.seed
      || !integer(script.ticks, 1, 10000) || !integer(script.viewDistance, 2, 8)
      || !integer(script.simulationDistance, 2, 8) || !integer(script.preloadRadius, script.viewDistance, 8)
      || !["x", "y", "z"].every(axis => integer(script.spawn?.[axis], -30000000, 30000000))
      || !Array.isArray(script.setupCommands) || script.setupCommands.some(command => typeof command !== "string")
      || !script.serverboundIds || Array.isArray(script.serverboundIds) || typeof script.serverboundIds !== "object"
      || Object.entries(script.serverboundIds).some(([name, id]) => !/^[a-z0-9_]+$/.test(name) || !integer(id, 0, 2147483647))
      || !Array.isArray(script.actions)) throw new Error("invalid tick script");
  if (script.normalization !== undefined) {
    const positionBytes = script.normalization?.advancementPositionBytes;
    if (positionBytes !== undefined && !integer(positionBytes, 0, 64)) throw new Error("invalid tick script");
    rulesOf(script);
  }
  let previous = 0;
  for (const action of script.actions) {
    if (!integer(action.tick, 1, script.ticks) || action.tick < previous || action.connection !== 0
        || typeof action.wire !== "string" || !/^(?:[0-9a-f]{2})+$/.test(action.wire)) {
      throw new Error("invalid/unordered tick action");
    }
    previous = action.tick;
  }
  return script;
}

export function validateTickCapture(events) {
  if (!Array.isArray(events) || events.length < 4 || events[0]?.kind !== "session") throw new Error("missing session");
  const script = validateTickScript(events[0].script);
  const orders = new Map();
  const entities = new Set();
  const bundles = new Set();
  let active = null;
  let tick = 0;
  let ready = false;
  let playerSpawn = false;
  let complete = false;
  let action = 0;
  let pending = null;
  let setupCommand = 0;
  let settle = null;
  let settles = [];
  let preloads = 0;
  let constructed = false;
  let broadcast = null;
  for (let index = 0; index < events.length; index++) {
    const event = events[index];
    if (!kinds.has(event.kind) || event.sequence !== index || event.tick !== tick && event.kind !== "tick-start"
        || complete || index && event.kind === "session") throw new Error(`invalid event sequence at ${index}`);
    if (event.kind === "ready") {
      if (tick !== 0 || ready || settle || preloads !== (2 * script.preloadRadius + 1) ** 2
          || setupCommand !== script.setupCommands.length || event.seed !== script.seed
          || event.viewDistance !== script.viewDistance || event.simulationDistance !== script.simulationDistance
          || ["x", "y", "z"].some(axis => event.spawn?.[axis] !== script.spawn[axis])) throw new Error("non-equivalent tick-zero setup");
      ready = true;
    } else if (event.kind === "settle-start") {
      const phase = tick === 0 && active === null && !ready ? "preload" : active !== null ? ["tick-start", "tick-end"][settles.length] : null;
      if (settle || !phase || event.phase !== phase) throw new Error("unbound settle");
      settle = event.phase;
    } else if (event.kind === "settle-end") {
      if (!settle || event.phase !== settle || !Number.isSafeInteger(event.serverTasks)) throw new Error("unbound settle");
      if (settle === "preload") preloads++;
      else settles.push(settle);
      if (settle === "tick-end" && events[index + 1]?.kind !== "tick-end") throw new Error("work after the closing settle");
      settle = null;
    } else if (event.kind === "chunk-broadcast-start") {
      if (active === null || broadcast !== null || typeof event.chunk !== "string") throw new Error("unbound chunk broadcast");
      broadcast = event.chunk;
    } else if (event.kind === "chunk-broadcast-end") {
      if (event.chunk !== broadcast) throw new Error("unbound chunk broadcast");
      broadcast = null;
    } else if (event.kind === "player-construct") {
      if (active !== 1 || constructed || playerSpawn || !Number.isSafeInteger(event.id)
          || typeof event.yRotBits !== "string" || !/^[0-9a-f]{8}$/.test(event.yRotBits)) throw new Error("invalid player construction");
      constructed = true;
    } else if (event.kind === "player-spawn") {
      if (active !== 1 || !constructed || playerSpawn || event.connection !== 0 || event.x !== script.spawn.x + 0.5
          || event.y !== script.spawn.y || event.z !== script.spawn.z + 0.5) throw new Error("non-equivalent player spawn");
      playerSpawn = true;
    } else if (event.kind === "setup-command") {
      if (ready || tick !== 0 || event.command !== script.setupCommands[setupCommand++] || event.success !== true
          || !Number.isSafeInteger(event.result)) throw new Error("unverified vanilla setup command");
    } else if (event.kind === "tick-start") {
      if (!ready || active !== null || event.tick !== tick + 1 || event.serverTick !== tick
          || tick >= script.ticks) throw new Error("missing/repeated tick");
      active = ++tick;
      settles = [];
    } else if (event.kind === "tick-end") {
      if (active !== tick || !playerSpawn || event.serverTick !== tick || pending || bundles.size || settle || broadcast !== null
          || settles.join() !== "tick-start,tick-end" || event.viewDistance !== script.viewDistance
          || event.simulationDistance !== script.simulationDistance) throw new Error("incomplete tick or distance transition");
      active = null;
    } else if (event.kind === "complete") {
      if (active !== null || tick !== script.ticks || action !== script.actions.length || pending
          || event.actions !== action || index !== events.length - 1) throw new Error("incomplete capture");
      complete = true;
    } else if (event.kind === "entity") {
      if (tick > 0 && active === null || !Number.isSafeInteger(event.id) || entities.has(event.id) || typeof event.uuid !== "string"
          || !event.entityType || !event.dimension || "worker" in event && (event.worker !== true || !settle)) {
        throw new Error("invalid entity allocation");
      }
      entities.add(event.id);
    } else if (event.kind === "entity-identity") {
      if (tick > 0 && active === null || !entities.has(event.id) || typeof event.uuid !== "string"
          || "worker" in event && (event.worker !== true || !settle)) throw new Error("unbound entity identity");
    } else if (event.kind.startsWith("bundle-")) {
      if (active === null || !Number.isSafeInteger(event.connection)) throw new Error("unbound bundle");
      if (event.kind === "bundle-start") {
        if (bundles.has(event.connection)) throw new Error("nested bundle");
        bundles.add(event.connection);
      } else if (!bundles.delete(event.connection)) throw new Error("unmatched bundle");
    } else if (["packet", "input", "inject"].includes(event.kind)) {
      if (active === null || event.connection !== 0 || event.phase !== "play" || !event.name
          || !/^(?:[0-9a-f]{2})+$/.test(event.wire)) throw new Error("packet outside exact PLAY tick");
      // The virtual peer ignores send-completion callbacks, so a vanilla disconnect would leave it attached.
      if (event.kind === "packet" && event.name === "disconnect") throw new Error("vanilla disconnected the virtual peer");
      if (event.kind === "inject") {
        if (pending) throw new Error("input did not reach application barrier");
        if (event.source === "script") {
          const expected = script.actions[action];
          if (!expected || event.action !== action || event.tick !== expected.tick
              || event.connection !== expected.connection || event.wire !== expected.wire) throw new Error("script injection mismatch");
          action++;
        } else if (event.source !== "response" || event.action !== -1
            || !["accept_teleportation", "chunk_batch_received", "keep_alive", "pong"].includes(event.name)) {
          throw new Error("unscripted injection");
        }
        pending = event;
      } else {
        const key = `${event.kind}/${event.connection}`;
        const order = orders.get(key) ?? 0;
        if (event.order !== order) throw new Error("missing/repeated connection order");
        orders.set(key, order + 1);
        if (event.kind === "input") {
          if (!pending || event.source !== "replay" || event.wire !== pending.wire
              || event.tick !== pending.tick || event.name !== pending.name || event.connection !== pending.connection) {
            throw new Error("applied input not bound to injection");
          }
          pending = null;
        }
      }
    }
  }
  if (!complete) throw new Error("missing completion footer");
  return {script, scriptHash: hash(script), ticks: tick, actions: action, entities: entities.size,
    packets: events.filter(event => event.kind === "packet").length,
    inputs: events.filter(event => event.kind === "input").length};
}

/*
 * Reviewed normalization rules. A profile lists the rules that apply to its vanilla codecs in
 * tickParity.normalization.rules; nothing else is normalized. Each rule names the vanilla source of
 * the difference, decodes only the field it covers with the profile's exact layout, and leaves the
 * row strict when that decode fails. Both original row hashes stay in the ledger.
 */
export const NORMALIZATION_RULES = {
  "entity-constructor-uuid": "Entity.<init> draws its UUID from an unseeded RandomSource (RandomSupport.generateUniqueSeed: "
    + "seed uniquifier ^ System.nanoTime). Allocation-row UUIDs, and identity rows and add_entity UUIDs that carry a UUID "
    + "first drawn by a recorded allocation of the same run, compare as that allocation's entity id.",
  "living-entity-constructor-yaw": "LivingEntity.<init> sets yRot from the same unseeded random. The recorded yaw of the "
    + "scenario player, and player_position / accept_teleportation yaw fields with exactly those bits, compare as a token.",
  "recipe-property-set-order": "RecipeManager builds property sets with Set.copyOf over identity-hashed items and an "
    + "immutable map keyed by identity-hashed keys. update_recipes property sets compare as a key-sorted map of item-id sets; "
    + "all following bytes stay exact.",
  "advancement-progress-order-and-grant-time": "PlayerAdvancements.flushDirty fills a HashMap from a set of records with "
    + "identity-hashed members, and CriterionProgress.grant stores Instant.now(). update_advancements progress entries "
    + "compare sorted by advancement id with each obtained instant reduced to its presence; additions, removals, criterion "
    + "order and flags stay exact.",
  "heightmap-type-order": "ClientboundLevelChunkPacketData collects heightmaps with Collectors.toMap (a HashMap keyed by "
    + "Heightmap.Types, whose hash is the identity hash). The heightmap entries compare sorted by type id; all other chunk "
    + "bytes stay exact.",
  "attribute-snapshot-order": "AttributeMap.attributesToSync is an ObjectOpenHashSet of AttributeInstance (identity hash). "
    + "update_attributes snapshots compare sorted by attribute id; each snapshot's bytes stay exact.",
  "chunk-broadcast-holder-order": "ServerChunkCache.chunkHoldersToBroadcast is a ReferenceOpenHashSet of ChunkHolder "
    + "(identity hash). Adjacent recorded ChunkHolder.broadcastChanges groups compare sorted by chunk; rows inside a group "
    + "keep their order and stay exact.",
  "game-rule-command-order": "GameRuleCommand.register adds one child per rule in GameRules.visitGameRuleTypes order, which "
    + "iterates a Reference2ObjectOpenHashMap keyed by GameRule (identity hash); the commands packet numbers nodes breadth "
    + "first from that order. The command graph compares by node path, with child order ignored only below the paths "
    + "the profile lists; every flag, name, parser property, suggestion, redirect and all other child orders stay exact.",
};

class Reader {
  constructor(hex) { this.bytes = Buffer.from(hex, "hex"); this.offset = 0; }
  take(size) {
    if (!Number.isSafeInteger(size) || size < 0 || this.offset + size > this.bytes.length) throw new Error("truncated field");
    const value = this.bytes.subarray(this.offset, this.offset + size);
    this.offset += size;
    return value;
  }
  varint() {
    let value = 0;
    for (let shift = 0; shift < 35; shift += 7) {
      const byte = this.take(1)[0];
      value += (byte & 127) * 2 ** shift;
      if (!(byte & 128)) return value;
    }
    throw new Error("invalid VarInt");
  }
  count() {
    const value = this.varint();
    if (value > 1 << 20) throw new Error("unbounded collection");
    return value;
  }
  string() { return this.take(this.count()).toString("utf8"); }
  boolean() {
    const value = this.take(1)[0];
    if (value > 1) throw new Error("invalid boolean");
    return value === 1;
  }
  hexFrom(start) { return this.bytes.subarray(start, this.offset).toString("hex"); }
  rest() { return this.take(this.bytes.length - this.offset).toString("hex"); }
  end() { if (this.offset !== this.bytes.length) throw new Error("unconsumed bytes"); }
}

const uuidText = bytes => {
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};
const unique = (values, what) => {
  if (new Set(values).size !== values.length) throw new Error(`duplicate ${what}`);
  return values;
};
const byKey = (left, right) => left.key < right.key ? -1 : left.key > right.key ? 1 : 0;

/** Each decoder returns a canonical replacement for the row's wire, or throws and leaves the row strict. */
const decoders = {
  "recipe-property-set-order": (wire) => {
    const reader = new Reader(wire);
    const packetId = reader.varint();
    const sets = [];
    for (let count = reader.count(); count > 0; count--) {
      const key = reader.string();
      const items = [];
      for (let size = reader.count(); size > 0; size--) items.push(reader.varint());
      sets.push({key, items: unique(items, "recipe property item").sort((left, right) => left - right)});
    }
    unique(sets.map(set => set.key), "recipe property key");
    return {packetId, propertySets: sets.sort(byKey), following: reader.rest()};
  },
  "advancement-progress-order-and-grant-time": (wire, script) => {
    const positionBytes = script.normalization.advancementPositionBytes;
    if (!Number.isSafeInteger(positionBytes) || positionBytes < 0) throw new Error("advancement layout undeclared");
    const reader = new Reader(wire);
    const packetId = reader.varint();
    const reset = reader.boolean();
    const additionsStart = reader.offset;
    for (let count = reader.count(); count > 0; count--) {
      reader.string();
      if (reader.boolean()) reader.string();
      if (reader.boolean()) throw new Error("advancement display is outside the reviewed layout");
      for (let groups = reader.count(); groups > 0; groups--) for (let entries = reader.count(); entries > 0; entries--) reader.string();
      reader.boolean();
      reader.take(positionBytes);
    }
    const additions = reader.hexFrom(additionsStart);
    const removalsStart = reader.offset;
    for (let count = reader.count(); count > 0; count--) reader.string();
    const removals = reader.hexFrom(removalsStart);
    const progress = [];
    for (let count = reader.count(); count > 0; count--) {
      const key = reader.string();
      const criteria = [];
      for (let size = reader.count(); size > 0; size--) {
        const name = reader.string();
        const obtained = reader.boolean();
        if (obtained) reader.take(8);
        criteria.push([name, obtained]);
      }
      progress.push({key, criteria});
    }
    unique(progress.map(entry => entry.key), "advancement progress id");
    const show = reader.boolean();
    reader.end();
    return {packetId, reset, additions, removals, progress: progress.sort(byKey), show};
  },
  "heightmap-type-order": (wire) => {
    const reader = new Reader(wire);
    reader.varint();
    reader.take(8);
    const prefix = reader.hexFrom(0);
    const heightmaps = [];
    for (let count = reader.count(); count > 0; count--) {
      const start = reader.offset;
      const type = reader.varint();
      reader.take(8 * reader.count());
      heightmaps.push({key: type, wire: reader.hexFrom(start)});
    }
    unique(heightmaps.map(entry => entry.key), "heightmap type");
    return {prefix, heightmaps: heightmaps.sort((left, right) => left.key - right.key), following: reader.rest()};
  },
  "game-rule-command-order": (wire, script) => commandGraph(wire, script),
  "attribute-snapshot-order": (wire) => {
    const reader = new Reader(wire);
    reader.varint();
    reader.varint();
    const prefix = reader.hexFrom(0);
    const snapshots = [];
    for (let count = reader.count(); count > 0; count--) {
      const start = reader.offset;
      const attribute = reader.varint();
      reader.take(8);
      for (let modifiers = reader.count(); modifiers > 0; modifiers--) {
        reader.string();
        reader.take(8);
        reader.varint();
      }
      snapshots.push({key: attribute, wire: reader.hexFrom(start)});
    }
    reader.end();
    unique(snapshots.map(entry => entry.key), "attribute");
    return {prefix, snapshots: snapshots.sort((left, right) => left.key - right.key)};
  },
};
const packetRules = {update_recipes: "recipe-property-set-order", update_advancements: "advancement-progress-order-and-grant-time",
  level_chunk_with_light: "heightmap-type-order", update_attributes: "attribute-snapshot-order", commands: "game-rule-command-order"};

/** Argument-type properties of ClientboundCommandsPacket; singletons carry none (ArgumentTypeInfos). */
const commandProperties = {
  "brigadier:double": reader => numberBounds(reader, 8), "brigadier:float": reader => numberBounds(reader, 4),
  "brigadier:integer": reader => numberBounds(reader, 4), "brigadier:long": reader => numberBounds(reader, 8),
  "brigadier:string": reader => reader.varint(), "minecraft:entity": reader => reader.take(1),
  "minecraft:score_holder": reader => reader.take(1), "minecraft:time": reader => reader.take(4),
  "minecraft:resource_or_tag": reader => reader.string(), "minecraft:resource_or_tag_key": reader => reader.string(),
  "minecraft:resource": reader => reader.string(), "minecraft:resource_key": reader => reader.string(),
  "minecraft:resource_selector": reader => reader.string(),
};
function numberBounds(reader, size) {
  const flags = reader.take(1)[0];
  if (flags & ~3) throw new Error("unknown number bound flags");
  if (flags & 1) reader.take(size);
  if (flags & 2) reader.take(size);
}

/** The command graph keyed by node path; children are ordered except below the listed paths. */
function commandGraph(wire, script) {
  const {commandArgumentTypes: types, unorderedCommandChildren: unordered} = script.normalization;
  if (!Array.isArray(types) || !Array.isArray(unordered)) throw new Error("command layout undeclared");
  const reader = new Reader(wire);
  const packetId = reader.varint();
  const nodes = [];
  for (let count = reader.count(); count > 0; count--) {
    const start = reader.offset;
    const flags = reader.take(1)[0];
    if (flags & ~0x3f || (flags & 3) === 3) throw new Error("unknown command node flags");
    const children = [];
    for (let size = reader.count(); size > 0; size--) children.push(reader.varint());
    const redirect = flags & 8 ? reader.varint() : null;
    const detail = reader.offset;
    let name = null;
    if ((flags & 3) !== 0) name = reader.string();
    if ((flags & 3) === 2) {
      const type = types[reader.varint()];
      if (!type) throw new Error("unknown argument type");
      if (!type.singleton) {
        if (!commandProperties[type.name]) throw new Error("argument properties outside the reviewed layout");
        commandProperties[type.name](reader);
      }
    }
    if (flags & 0x10) reader.string();
    // The node's own bytes, without its child and redirect indices.
    nodes.push({flags, children, redirect, name, own: reader.bytes.subarray(start, start + 1).toString("hex")
      + reader.bytes.subarray(detail, reader.offset).toString("hex")});
  }
  const rootIndex = reader.varint();
  reader.end();
  const paths = new Map([[rootIndex, []]]);
  const queue = [rootIndex];
  while (queue.length) {
    const index = queue.shift();
    const node = nodes[index];
    if (!node) throw new Error("command child out of range");
    unique(node.children.map(child => nodes[child]?.name), "command child name");
    for (const child of node.children) {
      if (paths.has(child) || nodes[child]?.name === null) throw new Error("command graph is not a tree");
      paths.set(child, [...paths.get(index), nodes[child].name]);
      queue.push(child);
    }
  }
  if (paths.size !== nodes.length) throw new Error("unreachable command node");
  const key = path => JSON.stringify(path);
  const unorderedKeys = new Set(unordered.map(key));
  const graph = [...paths].map(([index, path]) => {
    const node = nodes[index];
    const children = node.children.map(child => nodes[child].name);
    if (unorderedKeys.has(key(path))) children.sort();
    if (node.redirect !== null && !paths.has(node.redirect)) throw new Error("command redirect out of range");
    return {key: key(path), own: node.own, children, redirect: node.redirect === null ? null : paths.get(node.redirect)};
  });
  return {packetId, graph: graph.sort(byKey)};
}

function rulesOf(script) {
  const rules = script.normalization?.rules ?? [];
  if (!Array.isArray(rules) || rules.some(rule => typeof rule !== "string" || !Object.hasOwn(NORMALIZATION_RULES, rule))
      || new Set(rules).size !== rules.length) throw new Error("unknown normalization rule");
  return new Set(rules);
}

/** Replaces the 16 UUID bytes after the packet and entity ids of add_entity when they are a recorded constructor UUID. */
function addEntityUuid(wire, origins) {
  const reader = new Reader(wire);
  reader.varint();
  reader.varint();
  const start = reader.offset;
  const origin = origins.get(uuidText(reader.take(16)));
  if (origin === undefined) return null;
  return `${reader.hexFrom(0).slice(0, start * 2)}<constructor-uuid:${origin}>${reader.rest()}`;
}

/** Replaces the yaw float after the given fixed-size prefix when it carries the recorded constructor yaw bits. */
function yawField(wire, bytesBefore, yaw) {
  const reader = new Reader(wire);
  reader.varint();
  reader.varint();
  reader.take(bytesBefore);
  const start = reader.offset;
  if (reader.take(4).toString("hex") !== yaw.bits) return null;
  return `${reader.hexFrom(0).slice(0, start * 2)}<constructor-yaw:${yaw.id}>${reader.rest()}`;
}

/** Normalized rows of one validated capture: {raw, value, rules, moved} in compared order. */
export function normalizeTickCapture(events) {
  const script = events[0].script;
  const rules = rulesOf(script);
  const origins = new Map();
  let yaw = null;
  if (rules.has("entity-constructor-uuid")) {
    for (const event of events) if (event.kind === "entity") {
      if (origins.has(event.uuid)) throw new Error("constructor UUID drawn twice");
      origins.set(event.uuid, event.id);
    }
  }
  if (rules.has("living-entity-constructor-yaw")) {
    const constructed = events.filter(event => event.kind === "player-construct");
    if (constructed.length === 1) yaw = {id: constructed[0].id, bits: constructed[0].yRotBits};
  }
  const rows = events.map(raw => {
    const {sequence, ...value} = raw;
    const applied = new Set();
    const set = (field, replacement, rule) => {
      if (replacement === null || replacement === undefined) return;
      value[field] = replacement;
      applied.add(rule);
    };
    if (rules.has("entity-constructor-uuid")) {
      if (raw.kind === "entity") set("uuid", `<constructor-uuid:${raw.id}>`, "entity-constructor-uuid");
      if (raw.kind === "entity-identity" && origins.has(raw.uuid)) set("uuid", `<constructor-uuid:${origins.get(raw.uuid)}>`, "entity-constructor-uuid");
      if (raw.kind === "packet" && raw.name === "add_entity") {
        try { set("wire", addEntityUuid(raw.wire, origins), "entity-constructor-uuid"); } catch { /* strict */ }
      }
    }
    if (yaw) {
      if (raw.kind === "player-construct") set("yRotBits", `<constructor-yaw:${raw.id}>`, "living-entity-constructor-yaw");
      try {
        if (raw.kind === "packet" && raw.name === "player_position") set("wire", yawField(raw.wire, 48, yaw), "living-entity-constructor-yaw");
        if (["inject", "input"].includes(raw.kind) && raw.name === "accept_teleportation" && script.teleportAcknowledgement === "id-position") {
          set("wire", yawField(raw.wire, 24, yaw), "living-entity-constructor-yaw");
        }
      } catch { /* strict */ }
    }
    const rule = raw.kind === "packet" ? packetRules[raw.name] : undefined;
    if (rule && rules.has(rule)) {
      try { set("wire", decoders[rule](raw.wire, script), rule); } catch { /* strict */ }
    }
    return {raw, value, rules: applied, moved: false};
  });
  return rules.has("chunk-broadcast-holder-order") ? orderBroadcastGroups(rows) : rows;
}

/** Sorts each run of adjacent ChunkHolder.broadcastChanges groups by chunk; a run with a repeated chunk stays as recorded. */
function orderBroadcastGroups(rows) {
  const result = [];
  for (let index = 0; index < rows.length;) {
    if (rows[index].raw.kind !== "chunk-broadcast-start") {
      result.push(rows[index++]);
      continue;
    }
    const groups = [];
    while (index < rows.length && rows[index].raw.kind === "chunk-broadcast-start") {
      const end = rows.findIndex((row, position) => position > index && row.raw.kind === "chunk-broadcast-end");
      groups.push({key: rows[index].raw.chunk, rows: rows.slice(index, end + 1)});
      index = end + 1;
    }
    const keys = groups.map(group => group.key);
    const sorted = new Set(keys).size === keys.length ? [...groups].sort(byKey) : groups;
    // The connection output order numbers of the run are reassigned in the compared order.
    const outputOrders = groups.flatMap(group => group.rows).filter(row => row.raw.kind === "packet").map(row => row.raw.order);
    // Reordering a run moves every row of it to a new sequence, including groups that keep their
    // place among the groups, so the rule covers the whole run.
    const reordered = sorted.some((group, position) => group !== groups[position]);
    for (const group of sorted) {
      for (const row of group.rows) {
        row.moved = reordered;
        if (row.raw.kind === "packet") row.value.order = outputOrders.shift();
      }
      result.push(...group.rows);
    }
  }
  return result;
}

export function compareTickExact(left, right) {
  const captures = [left, right].map(validateTickCapture);
  if (captures[0].scriptHash !== captures[1].scriptHash) throw new Error("non-equivalent scripts");
  const rows = [left, right].map(normalizeTickCapture);
  const ledger = [];
  const aligned = new Set();
  const contentHash = row => row ? hash(row.value) : null;
  for (let index = 0; index < Math.max(rows[0].length, rows[1].length); index++) {
    // Vanilla's worker completion order is not stable. Align only an identical normalized
    // event in the same tick/kind within a bounded local window; never align different content.
    if (rows[0][index] && rows[1][index] && contentHash(rows[0][index]) !== contentHash(rows[1][index])) {
      const target = rows[0][index];
      const match = rows[1].findIndex((candidate, position) => position > index && position <= index + 64
        && candidate.raw.tick === target.raw.tick && candidate.raw.kind === target.raw.kind
        && contentHash(candidate) === contentHash(target));
      if (match >= 0) {
        const [candidate] = rows[1].splice(match, 1);
        rows[1].splice(index, 0, candidate);
        aligned.add(index);
      }
    }
    const [first, second] = [rows[0][index], rows[1][index]];
    const [firstRaw, secondRaw] = [first?.raw ?? null, second?.raw ?? null];
    const leftHash = hash(firstRaw);
    const rightHash = hash(secondRaw);
    const exact = leftHash === rightHash;
    const reasons = new Set([...(first?.rules ?? []), ...(second?.rules ?? [])]);
    if (first?.moved || second?.moved) reasons.add("chunk-broadcast-holder-order");
    // A row is explained only by a rule that touched it, never by its global sequence alone.
    const normalized = first && second && reasons.size > 0 && hash(first.value) === hash(second.value);
    ledger.push({index, leftSequence: firstRaw?.sequence, rightSequence: secondRaw?.sequence,
      tick: firstRaw?.tick ?? secondRaw?.tick, kind: firstRaw?.kind ?? secondRaw?.kind,
      status: exact ? "equal" : normalized ? "explained" : aligned.has(index) ? "explained" : "difference",
      reason: exact ? "tick-connection-entity-history-exact" : normalized ? [...reasons].sort().join("+")
        : aligned.has(index) ? "timing-only-content-aligned"
        : "unexplained-exact-event-difference", leftHash, rightHash});
  }
  const differences = ledger.filter(row => row.status === "difference").length;
  const explained = ledger.filter(row => row.status === "explained").length;
  const byRule = {};
  for (const row of ledger) if (row.status === "explained") for (const rule of row.reason.split("+")) byRule[rule] = (byRule[rule] ?? 0) + 1;
  return {method: "tick-exact-v2", normalizationRules: [...rulesOf(left[0].script)].sort(), explainedByRule: byRule,
    captures, events: ledger.length, explained, differences, unexplained: differences, passed: differences === 0, ledger};
}
