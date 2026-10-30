import assert from "node:assert/strict";
import {test} from "node:test";
import {compareTickExact, validateTickCapture, validateTickScript} from "./tick-compare.mjs";

const allRules = ["entity-constructor-uuid", "living-entity-constructor-yaw", "recipe-property-set-order",
  "advancement-progress-order-and-grant-time", "heightmap-type-order", "attribute-snapshot-order", "chunk-broadcast-holder-order"];
const script = {schema: 1, profile: "test-profile", player: "Parity", seed: "42", ticks: 2,
  teleportAcknowledgement: "id-position", settle: "quiescent",
  normalization: {rules: allRules, advancementPositionBytes: 0},
  spawn: {x: 0, y: 80, z: 0}, viewDistance: 2, simulationDistance: 2, preloadRadius: 2,
  setupCommands: [], serverboundIds: {}, actions: [{tick: 1, connection: 0, wire: "0100"}]};
const settle = (tick, phase) => [{kind: "settle-start", tick, phase}, {kind: "settle-end", tick, phase, serverTasks: 0}];
const uuidA = "00000000-0000-0000-0000-00000000000a";
const uuidB = "00000000-0000-0000-0000-00000000000b";
const yawBits = "40490fdb";

// Wire builders for the decoded fields; the leading byte is the packet id.
const varint = value => {
  const bytes = [];
  do { let byte = value & 127; value >>>= 7; if (value) byte |= 128; bytes.push(byte); } while (value);
  return Buffer.from(bytes);
};
const text = value => Buffer.concat([varint(Buffer.byteLength(value)), Buffer.from(value)]);
const hex = (...parts) => Buffer.concat(parts.map(part => Buffer.isBuffer(part) ? part : Buffer.from(part, "hex"))).toString("hex");
const uuidBytes = uuid => Buffer.from(uuid.replaceAll("-", ""), "hex");
const addEntity = uuid => hex("01", varint(7), uuidBytes(uuid), "19", "00".repeat(30));
const position = bits => hex("41", varint(1), "00".repeat(48), bits, "00000000", "00000000");
const recipes = sets => hex("42", varint(sets.length), ...sets.flatMap(([key, items]) =>
  [text(key), varint(items.length), ...items.map(varint)]), "0000");
const advancements = progress => hex("43", "00", varint(1), text("minecraft:a"), "00", "00", varint(1), varint(1), text("c"), "00",
  varint(0), varint(progress.length), ...progress.flatMap(([key, instant]) =>
    [text(key), varint(1), text("unlock"), instant === null ? "00" : hex("01", instant)]), "01");
const chunk = order => hex("44", "00000000", "00000000", varint(order.length), ...order.flatMap(type =>
  [varint(type), varint(1), "000000000000000" + type]), "0100");
// Commands: [flags, children, name, parser?]; literal 1, argument 2, executable 4.
const commands = nodes => hex("46", varint(nodes.length), ...nodes.flatMap(([flags, children, name, parser]) =>
  [Buffer.from([flags]), varint(children.length), ...children.map(varint), ...(name === undefined ? [] : [text(name)]),
    ...(parser === undefined ? [] : [varint(parser), ...(parser === 1 ? ["03", "00000000", "00000005"] : [])])]), varint(0));
const attributes = order => hex("45", varint(9), varint(order.length), ...order.flatMap(([id, base]) =>
  [varint(id), Buffer.from(new Float64Array([base]).buffer).reverse(), varint(0)]));

function capture(tickOne = []) {
  const preload = Array.from({length: 25}, () => settle(0, "preload")).flat();
  return [{kind: "session", tick: 0, script}, ...preload, {kind: "ready", tick: 0, seed: script.seed, spawn: script.spawn,
    viewDistance: script.viewDistance, simulationDistance: script.simulationDistance}, {kind: "tick-start", tick: 1, serverTick: 0},
    {kind: "entity", tick: 1, id: 3, uuid: uuidA, entityType: "player", dimension: "overworld"},
    {kind: "player-construct", tick: 1, id: 3, yRotBits: yawBits},
    {kind: "inject", tick: 1, connection: 0, phase: "play", name: "player_input", wire: "0100", source: "script", action: 0},
    {kind: "input", tick: 1, connection: 0, phase: "play", name: "player_input", wire: "0100", source: "replay", order: 0},
    {kind: "packet", tick: 1, connection: 0, phase: "play", name: "set_health", wire: "0211", order: 0},
    {kind: "player-spawn", tick: 1, connection: 0, x: 0.5, y: 80, z: 0.5}, ...settle(1, "tick-start"),
    ...tickOne.map(row => row.kind === "packet" ? {tick: 1, connection: 0, phase: "play", ...row} : {tick: 1, ...row}),
    ...settle(1, "tick-end"), {kind: "tick-end", tick: 1, serverTick: 1, viewDistance: 2, simulationDistance: 2},
    {kind: "tick-start", tick: 2, serverTick: 1}, ...settle(2, "tick-start"), ...settle(2, "tick-end"),
    {kind: "tick-end", tick: 2, serverTick: 2, viewDistance: 2, simulationDistance: 2}, {kind: "complete", tick: 2, actions: 1}]
    .map((event, sequence) => structuredClone({...event, sequence}));
}
const at = (events, kind, nth = 0) => events.filter(event => event.kind === kind)[nth];
const renumber = events => { events.forEach((event, sequence) => event.sequence = sequence); return events; };
const packet = (name, wire) => ({kind: "packet", name, wire});
const orders = rows => { let order = 0; return rows.map(row => row.kind === "packet" ? {...row, order: ++order} : row); };
const group = (chunk, ...packets) => [{kind: "chunk-broadcast-start", chunk}, ...packets, {kind: "chunk-broadcast-end", chunk}];
function compare(first, second) {
  return compareTickExact(capture(orders(first)), capture(orders(second)));
}

test("identical tick histories pass without dynamic-packet waivers", () => {
  const result = compareTickExact(capture(), capture());
  assert.equal(result.passed, true);
  assert.equal(result.captures[0].actions, 1);
  assert.equal(result.explained, 0);
});
test("off-thread entity observations remain ineligible for an exact tick", () => {
  const rows = capture();
  Object.assign(at(rows, "entity"), {tick: -1, observedTick: 1, thread: "Worker-Main-1"});
  assert.throws(() => validateTickCapture(rows), /invalid event sequence/);
  assert.throws(() => compareTickExact(rows, rows), /invalid event sequence/);
});
test("worker allocations are accepted only inside a settle", () => {
  const rows = capture();
  at(rows, "entity").worker = true;
  assert.throws(() => validateTickCapture(rows), /invalid entity allocation/);
  const settled = capture();
  const end = settled.findIndex(event => event.kind === "settle-end" && event.tick === 1);
  const [allocation] = settled.splice(settled.indexOf(at(settled, "entity")), 1);
  settled.splice(end - 1, 0, {...allocation, worker: true});
  assert.equal(validateTickCapture(renumber(settled)).entities, 1);
});
test("teleport acknowledgement layouts and the settle strategy must be explicitly supported", () => {
  for (const layout of [undefined, "unknown"]) {
    assert.throws(() => validateTickScript({...script, teleportAcknowledgement: layout}), /invalid tick script/);
  }
  assert.throws(() => validateTickScript({...script, settle: undefined}), /invalid tick script/);
  assert.equal(validateTickScript({...script, teleportAcknowledgement: "id"}).teleportAcknowledgement, "id");
});
for (const [name, mutate] of [
  ["distance ramp", events => at(events, "tick-end").simulationDistance++],
  ["shifted applied tick", events => at(events, "input").tick++],
  ["packet order gap", events => at(events, "packet").order++],
  ["live input", events => at(events, "input").source = "live"],
  ["unapplied injection", events => events.splice(events.indexOf(at(events, "input")), 1)],
  ["missing footer", events => events.pop()],
  ["allocation alias", events => events.splice(events.indexOf(at(events, "entity")), 0, structuredClone(at(events, "entity")))],
  ["partial bundle", events => events.splice(events.indexOf(at(events, "tick-end")), 0, {kind: "bundle-start", tick: 1, connection: 0})],
  ["invented vanilla counter", events => at(events, "tick-start").serverTick++],
  ["late entity", events => events.splice(events.indexOf(at(events, "tick-end")) + 1, 0,
    {kind: "entity", tick: 1, id: 4, uuid: "late", entityType: "player", dimension: "overworld"})],
  ["wrong player spawn", events => at(events, "player-spawn").y++],
  ["missing player spawn", events => events.splice(events.indexOf(at(events, "player-spawn")), 1)],
  ["repeated player spawn", events => events.splice(events.indexOf(at(events, "player-spawn")), 0, structuredClone(at(events, "player-spawn")))],
  ["missing player construction", events => events.splice(events.indexOf(at(events, "player-construct")), 1)],
  ["missing preload settle", events => events.splice(1, 2)],
  ["missing closing settle", events => events.splice(events.indexOf(at(events, "tick-end")) - 2, 2)],
  ["work after the closing settle", events => events.splice(events.indexOf(at(events, "tick-end")), 0,
    {kind: "packet", tick: 1, connection: 0, phase: "play", name: "set_health", wire: "0211", order: 1})],
  ["unterminated chunk broadcast", events => events.splice(events.indexOf(at(events, "tick-end")) - 2, 0,
    {kind: "chunk-broadcast-start", tick: 1, chunk: "[0, 0]"})],
  ["vanilla disconnect", events => Object.assign(at(events, "packet"), {name: "disconnect"})],
]) test(`rejects ${name}`, () => {
  const events = capture();
  mutate(events);
  assert.throws(() => validateTickCapture(renumber(events)));
});
test("payload, allocation identity and order differences remain unexplained", () => {
  for (const mutate of [events => at(events, "packet").wire = "0212", events => at(events, "entity").id++,
    events => at(events, "packet").name = "set_experience"]) {
    const second = capture();
    mutate(second);
    let result;
    try { result = compareTickExact(capture(), second); } catch { continue; }
    assert.equal(result.passed, false);
    assert.equal(result.explained, 0);
  }
});

test("different scenario cannot be certified as equivalent", () => {
  const second = capture();
  second[0].script.seed = "43";
  assert.throws(() => compareTickExact(capture(), second), /non-equivalent/);
});
test("invalid ticks and unordered scripts fail closed", () => {
  assert.throws(() => validateTickScript({...script, ticks: 0}));
  assert.throws(() => validateTickScript({...script, actions: [{tick: 2, connection: 0, wire: "01"}, ...script.actions]}));
});
test("JSON property ordering is not a semantic packet difference", () => {
  const second = capture().map(event => Object.fromEntries(Object.entries(event).reverse()));
  assert.equal(compareTickExact(capture(), second).passed, true);
});
test("setup commands and initial state need successful observed receipts", () => {
  for (const mutate of [events => at(events, "ready").seed = "43", events => at(events, "ready").spawn.y++,
    events => events[0].script.setupCommands.push("gamerule respawn_radius 0")]) {
    const events = capture();
    mutate(events);
    assert.throws(() => validateTickCapture(events), /non-equivalent/);
  }
  const events = capture();
  events[0].script.setupCommands = ["gamerule respawn_radius 0"];
  events.splice(1, 0, {kind: "setup-command", tick: 0, command: "gamerule respawn_radius 0", success: true, result: 0});
  assert.equal(validateTickCapture(renumber(events)).ticks, 2);
  events[1].success = false;
  assert.throws(() => validateTickCapture(events), /unverified/);
});

test("constructor UUIDs compare by allocation, including add_entity UUIDs drawn by an allocation", () => {
  const second = capture(orders([packet("add_entity", addEntity(uuidB))]));
  at(second, "entity").uuid = uuidB;
  const result = compareTickExact(capture(orders([packet("add_entity", addEntity(uuidA))])), second);
  assert.equal(result.passed, true);
  assert.deepEqual(result.explainedByRule, {"entity-constructor-uuid": 2});
});
test("UUIDs that no recorded allocation drew stay strict", () => {
  const unrelated = "00000000-0000-0000-0000-0000000000ff";
  assert.equal(compare([packet("add_entity", addEntity(uuidA))], [packet("add_entity", addEntity(unrelated))]).passed, false);
  const first = capture();
  const second = capture();
  for (const [events, uuid] of [[first, uuidA], [second, uuidB]]) {
    events.splice(events.indexOf(at(events, "player-construct")), 0, {kind: "entity-identity", tick: 1, id: 3, uuid: "profile-" + uuid});
    renumber(events);
  }
  assert.equal(compareTickExact(first, second).passed, false);
  const twice = capture();
  twice.splice(twice.indexOf(at(twice, "player-construct")), 0, {...at(twice, "entity"), id: 4});
  assert.throws(() => compareTickExact(renumber(twice), renumber(structuredClone(twice))), /drawn twice/);
});
test("constructor yaw compares only where the recorded bits appear", () => {
  const second = capture(orders([packet("player_position", position("3f800000"))]));
  at(second, "player-construct").yRotBits = "3f800000";
  const result = compareTickExact(capture(orders([packet("player_position", position(yawBits))])), second);
  assert.equal(result.passed, true);
  assert.equal(result.explainedByRule["living-entity-constructor-yaw"], 2);
  const changed = capture(orders([packet("player_position", position("3f800001"))]));
  at(changed, "player-construct").yRotBits = "3f800000";
  assert.equal(compareTickExact(capture(orders([packet("player_position", position(yawBits))])), changed).passed, false);
});
test("recipe property sets compare as sets; contents and following bytes stay exact", () => {
  const sets = [["minecraft:a", [1, 2, 3]], ["minecraft:b", [4]]];
  const shuffled = [["minecraft:b", [4]], ["minecraft:a", [3, 1, 2]]];
  assert.equal(compare([packet("update_recipes", recipes(sets))], [packet("update_recipes", recipes(shuffled))]).passed, true);
  assert.equal(compare([packet("update_recipes", recipes(sets))], [packet("update_recipes", recipes([["minecraft:b", [4]], ["minecraft:a", [3, 1, 5]]]))]).passed, false);
  const duplicated = [["minecraft:a", [1, 1]]];
  assert.equal(compare([packet("update_recipes", recipes(duplicated))], [packet("update_recipes", recipes([["minecraft:a", [1, 1, 1]]]))]).passed, false);
});
test("advancement progress order and grant instants compare; presence and other fields stay exact", () => {
  const first = [["minecraft:x", "0000019000000001"], ["minecraft:y", null]];
  const second = [["minecraft:y", null], ["minecraft:x", "0000019000000999"]];
  assert.equal(compare([packet("update_advancements", advancements(first))], [packet("update_advancements", advancements(second))]).passed, true);
  const ungranted = [["minecraft:y", null], ["minecraft:x", null]];
  assert.equal(compare([packet("update_advancements", advancements(first))], [packet("update_advancements", advancements(ungranted))]).passed, false);
});
test("heightmap entries compare by type; heights stay exact", () => {
  assert.equal(compare([packet("level_chunk_with_light", chunk([1, 4, 5]))], [packet("level_chunk_with_light", chunk([4, 1, 5]))]).passed, true);
  const other = chunk([4, 1, 5]).replace("0000000000000001", "0000000000000002");
  assert.equal(compare([packet("level_chunk_with_light", chunk([1, 4, 5]))], [packet("level_chunk_with_light", other)]).passed, false);
});
test("attribute snapshots compare by attribute; values stay exact", () => {
  assert.equal(compare([packet("update_attributes", attributes([[6, 1], [10, 2]]))],
    [packet("update_attributes", attributes([[10, 2], [6, 1]]))]).passed, true);
  assert.equal(compare([packet("update_attributes", attributes([[6, 1], [10, 2]]))],
    [packet("update_attributes", attributes([[10, 3], [6, 1]]))]).passed, false);
});
test("adjacent chunk broadcast groups compare by chunk; rows outside or inside a group keep their order", () => {
  const a = group("[0, 0]", packet("block_update", "0b01"), packet("light_update", "0c01"));
  const b = group("[1, 0]", packet("block_update", "0b02"));
  const result = compare([...a, ...b], [...b, ...a]);
  assert.equal(result.passed, true);
  assert.equal(result.explainedByRule["chunk-broadcast-holder-order"] > 0, true);
  // A group that keeps its place among the groups still shifts when an earlier group's size differs.
  const c = group("[2, 0]", packet("block_update", "0b03"));
  const shifted = compare([...c, ...b, ...a], [...a, ...b, ...c]);
  assert.equal(shifted.passed, true);
  const inner = group("[0, 0]", packet("light_update", "0c01"), packet("block_update", "0b01"));
  assert.equal(compare([...a, ...b], [...b, ...inner]).passed, false);
  assert.equal(compare([packet("block_update", "0b01"), packet("block_update", "0b02")],
    [packet("block_update", "0b02"), packet("block_update", "0b01")]).passed, false);
  const separated = [...a, packet("set_health", "0211"), ...b];
  assert.equal(compare(separated, [...b, packet("set_health", "0211"), ...a]).passed, false);
});
test("only the listed command paths compare without child order", () => {
  const declared = {...script, normalization: {...script.normalization, rules: [...allRules, "game-rule-command-order"],
    commandArgumentTypes: [{name: "brigadier:bool", singleton: true}, {name: "brigadier:integer", singleton: false}],
    unorderedCommandChildren: [["gamerule"]]}};
  const run = nodes => {
    const events = capture(orders([packet("commands", commands(nodes))]));
    events[0].script = declared;
    return events;
  };
  // "a" takes a bool and "b" an integer value; vanilla visits the rules in either order.
  const visited = [[0, [1, 2]], [1, [3, 4], "gamerule"], [5, [], "say"], [5, [5], "a"], [5, [6], "b"],
    [6, [], "value", 0], [6, [], "value", 1]];
  const reversed = [[0, [1, 2]], [1, [3, 4], "gamerule"], [5, [], "say"], [5, [5], "b"], [5, [6], "a"],
    [6, [], "value", 1], [6, [], "value", 0]];
  const result = compareTickExact(run(visited), run(reversed));
  assert.equal(result.passed, true);
  assert.equal(result.explainedByRule["game-rule-command-order"], 1);
  const otherParser = [[0, [1, 2]], [1, [3, 4], "gamerule"], [5, [], "say"], [5, [5], "b"], [5, [6], "a"],
    [6, [], "value", 0], [6, [], "value", 1]];
  assert.equal(compareTickExact(run(visited), run(otherParser)).passed, false);
  const rootOrder = [[0, [2, 1]], [1, [3, 4], "gamerule"], [5, [], "say"], [5, [5], "a"], [5, [6], "b"],
    [6, [], "value", 0], [6, [], "value", 1]];
  assert.equal(compareTickExact(run(visited), run(rootOrder)).passed, false);
});
test("rules a profile does not list are not applied", () => {
  const strict = {...script, normalization: {rules: [], advancementPositionBytes: 0}};
  const [first, second] = [capture(), capture()];
  first[0].script = strict;
  second[0].script = strict;
  at(second, "entity").uuid = uuidB;
  assert.equal(compareTickExact(first, second).passed, false);
  second[0].script = {...strict, normalization: {rules: ["unreviewed-waiver"]}};
  first[0].script = second[0].script;
  assert.throws(() => compareTickExact(first, second), /unknown normalization rule/);
});
