import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import test from "node:test";
import {compareLogical} from "./logical-compare.mjs";
import {writeVarInt, FrameReader, encodeFrame, decodeConnection, Protocol,
  compareSimulationConditions} from "../packet-parity.mjs";
import {evaluatePackets, verifyPacketLedger} from "../m2-correctness-gate.mjs";

const modern = {loginSessionUuid: true, timeUpdate: "world-clocks"};
const legacy = {loginSessionUuid: false, timeUpdate: "day-time"};
const text = value => Buffer.concat([writeVarInt(Buffer.byteLength(value)), Buffer.from(value)]);
const packet = (name, payload = [], phase = "play", dir = "in") =>
  ({name, payload: Buffer.from(payload), phase, dir, id: 1, t: 1});
const tag = (name, ids) => Buffer.concat([text(name), writeVarInt(ids.length), ...ids.map(writeVarInt)]);
const tags = entries => Buffer.concat([writeVarInt(1), text("registry"), writeVarInt(entries.length), ...entries]);
const time = (tick, day = 6000, running = false) => {
  const bytes = Buffer.alloc(17);
  bytes.writeBigInt64BE(BigInt(tick));
  bytes.writeBigInt64BE(BigInt(day), 8);
  bytes[16] = Number(running);
  return packet("set_time", bytes);
};

test("empty evidence cannot pass", () => assert.equal(compareLogical([], [], modern).pass, false));
test("independent registry keys align without ordinal cascades", () => {
  const first = packet("registry_data", text("first"), "configuration");
  const second = packet("registry_data", text("second"), "configuration");
  assert.equal(compareLogical([first, second], [second, first], modern).pass, true);
});
test("login compression is transport framing, valid only as a threshold", () => {
  const login = (name, payload) => packet(name, payload, "login");
  const finished = login("opaque", [1]);
  const tcp = [login("login_compression", writeVarInt(256)), finished];
  const memory = [finished];
  const result = compareLogical(memory, tcp, legacy);
  assert.equal(result.pass, true, JSON.stringify(result.classifications));
  assert.equal(result.classifications["explained/login/login_compression/compression-framing"], 1);
  assert.equal(compareLogical(tcp, tcp, legacy).pass, true);
  assert.equal(compareLogical([login("login_compression", [0x80])], tcp, legacy).difference >= 1, true);
  assert.equal(compareLogical([login("login_compression", [0x01, 0x01])], tcp, legacy).difference >= 1, true);
});
test("typed tag maps allow key order but preserve ID order and values", () => {
  const first = packet("update_tags", tags([tag("first", [1, 2]), tag("second", [3])]), "configuration");
  const second = packet("update_tags", tags([tag("second", [3]), tag("first", [1, 2])]), "configuration");
  const result = compareLogical([first], [second], modern);
  assert.equal(result.pass, true);
  assert.equal(result.explained, 2);
  const mutated = packet("update_tags", tags([tag("first", [2, 1]), tag("second", [3])]), "configuration");
  assert.equal(compareLogical([first], [mutated], modern).pass, false);
});
test("duplicate map keys and truncated fields are not normalized", () => {
  const duplicate = packet("update_tags", tags([tag("same", [1]), tag("same", [2])]), "configuration");
  assert.equal(compareLogical([duplicate], [duplicate], modern).unresolved, 2);
  const truncated = packet("update_tags", [1, 10], "configuration");
  assert.equal(compareLogical([truncated], [truncated], modern).pass, false);
});
test("a byte histogram never establishes content equality", () => {
  assert.equal(compareLogical([packet("opaque", [1, 2, 3])], [packet("opaque", [3, 2, 1])], modern).difference, 2);
});
test("one insertion does not contaminate the remaining stable history", () => {
  const rows = [packet("opaque", [1]), packet("opaque", [2]), packet("opaque", [3])];
  const result = compareLogical(rows, [rows[0], packet("opaque", [9]), ...rows.slice(1)], modern);
  assert.equal(result.equal, 6);
  assert.equal(result.difference, 1);
  assert.equal(result.accounted, 7);
});
test("same-key history reversal, duplicates and missing packets still fail", () => {
  const rows = [packet("opaque", [1]), packet("opaque", [2])];
  for (const other of [[rows[1], rows[0]], [...rows, rows[1]], rows.slice(1)]) {
    assert.equal(compareLogical(rows, other, modern).pass, false);
  }
});
test("unproven cross-kind reordering and identical bytes with different packet types fail", () => {
  const first = packet("first", [1]);
  const second = packet("second", [1]);
  assert.equal(compareLogical([first, second], [second, first], modern).pass, false);
  assert.equal(compareLogical([first], [second], modern).pass, false);
});
test("session UUID normalization is profile-selected and never strips player identity", () => {
  const player = Buffer.alloc(16, 7);
  const profile = Buffer.concat([player, text("Player"), writeVarInt(0)]);
  const first = packet("login_finished", Buffer.concat([profile, Buffer.alloc(16, 1)]), "login");
  const second = packet("login_finished", Buffer.concat([profile, Buffer.alloc(16, 2)]), "login");
  assert.equal(compareLogical([first], [second], modern).pass, true);
  assert.equal(compareLogical([first], [second], legacy).pass, false);
  const old = packet("login_finished", profile, "login");
  assert.equal(compareLogical([old], [old], legacy).pass, true);
  const wrong = Buffer.from(profile);
  wrong[15]++;
  assert.equal(compareLogical([old], [packet("login_finished", wrong, "login")], legacy).pass, false);
});
test("only monotonically sampled identical clock-state transitions are cadence differences", () => {
  assert.equal(compareLogical([time(10), time(30)], [time(20)], legacy).pass, true);
  assert.equal(compareLogical([time(30), time(10)], [time(20)], legacy).pass, false);
  assert.equal(compareLogical([time(10)], [time(20, 7000)], legacy).pass, false);
});
test("heartbeat tokens require one matching outbound response", () => {
  const ping = packet("ping", [0, 0, 0, 1]);
  const pong = packet("pong", [0, 0, 0, 1], "play", "out");
  assert.equal(compareLogical([ping, pong], [ping, pong], modern).pass, true);
  assert.equal(compareLogical([ping], [ping], modern).unresolved, 2);
});
test("batch and bundle validation cannot hide missing content or incomplete framing", () => {
  const start = packet("chunk_batch_start");
  const end = packet("chunk_batch_finished", writeVarInt(0));
  assert.equal(compareLogical([start, end], [start, end], modern).pass, true);
  assert.equal(compareLogical([start], [start], modern).pass, false);
  assert.equal(compareLogical([packet("bundle_delimiter")], [packet("bundle_delimiter")], modern).pass, false);
  assert.equal(compareLogical([start, packet("chunk_batch_finished", writeVarInt(1))], [start, end], modern).pass, false);
});
test("tick bounds are not exact tick or entity-binding evidence", () => {
  const rows = [time(10), packet("set_entity_motion", [1, 0, 0]), time(30)];
  const result = compareLogical(rows, rows, legacy);
  assert.equal(result.pass, false);
  assert.equal(result.unresolved, 2);
  assert.deepEqual(result.ledger.find(row => row.name === "set_entity_motion").tick,
    {lower: 10, upper: 30, exact: false});
});
test("all input packets have exactly one ledger row", () => {
  const rows = [packet("opaque", [1]), packet("opaque", [2]), time(10)];
  const result = compareLogical(rows, rows.slice(1), legacy);
  assert.equal(result.accounted, 5);
  assert.equal(new Set(result.ledger.map(row => `${row.side}/${row.index}`)).size, 5);
});
test("aggregate rejects incomplete coverage, unbound evidence and unresolved histories", () => {
  const result = compareLogical([packet("opaque", [1])], [packet("opaque", [1])], modern);
  const ledgerBytes = Buffer.from(result.ledger.map(row => JSON.stringify(row)).join("\n") + "\n");
  const report = {verdict: "PARITY", logicalParity: {...result, ledger: {
    sha256: createHash("sha256").update(ledgerBytes).digest("hex")}},
    sourceChecks: {pass: true, sha256: Object.fromEntries(["capture", "captureMetadata", "jvm", "table", "profile"]
      .map(name => [name, "b".repeat(64)]))}};
  const verification = {ledger: verifyPacketLedger(report, ledgerBytes), sources: true};
  assert.equal(evaluatePackets(report, verification).status, "PASS");
  assert.equal(evaluatePackets(report).status, "BLOCKED");
  assert.equal(verifyPacketLedger(report, Buffer.from("tampered\n")).ok, false);
  const duplicate = Buffer.from(JSON.stringify(result.ledger[0]) + "\n" + JSON.stringify(result.ledger[0]) + "\n");
  const duplicateReport = {...report, logicalParity: {...report.logicalParity,
    ledger: {sha256: createHash("sha256").update(duplicate).digest("hex")}}};
  assert.equal(verifyPacketLedger(duplicateReport, duplicate).ok, false);
  report.logicalParity.accounted--;
  assert.equal(evaluatePackets(report, verification).status, "BLOCKED");
  report.logicalParity.accounted++;
  report.logicalParity.pass = false;
  assert.equal(evaluatePackets(report, verification).status, "FAIL");
  report.logicalParity.pass = true;
  report.sourceChecks.pass = false;
  assert.equal(evaluatePackets(report, verification).status, "FAIL");
});
test("frame lengths, compression lengths and truncated captures fail closed", () => {
  assert.throws(() => new FrameReader().push(Buffer.from([0])), /frame length/);
  const reader = new FrameReader();
  reader.threshold = 0;
  const frame = encodeFrame(Buffer.from([1, 2, 3]), 0);
  const frames = reader.push(frame);
  frames[0][0]++;
  assert.throws(() => reader.packet(frames[0]), /length mismatch/);
  assert.throws(() => decodeConnection([{d: "in", t: 1, b: Buffer.from([4, 1]).toString("base64")}],
    new Protocol({})), /incomplete/);
});
test("cross-kind mismatches retain valid mutual ledger bindings and fail the gate", () => {
  const result = compareLogical([packet("opaque", [1])], [packet("different", [1])], modern);
  const bytes = Buffer.from(result.ledger.map(row => JSON.stringify(row)).join("\n") + "\n");
  const report = {verdict: "DIFFERENCES", logicalParity: {...result, ledger: {
    sha256: createHash("sha256").update(bytes).digest("hex")}},
    sourceChecks: {pass: true, sha256: Object.fromEntries(["capture", "captureMetadata", "jvm", "table", "profile"]
      .map(name => [name, "b".repeat(64)]))}};
  const verification = {ledger: verifyPacketLedger(report, bytes), sources: true};
  assert.equal(verification.ledger.ok, true);
  assert.equal(evaluatePackets(report, verification).status, "FAIL");
});
test("equal final distances do not imply comparable simulation conditions", () => {
  const login = (viewDistance, simulationDistance) => packet("login", Buffer.concat([
    Buffer.alloc(5), writeVarInt(0), writeVarInt(8), writeVarInt(viewDistance), writeVarInt(simulationDistance)]));
  const staged = [login(1, 1), packet("set_chunk_cache_radius", writeVarInt(12)),
    packet("set_simulation_distance", writeVarInt(6))];
  const fixed = [login(12, 6)];
  assert.equal(compareSimulationConditions(staged, fixed).pass, false);
  assert.equal(compareSimulationConditions(staged, staged).pass, true);
  assert.equal(compareSimulationConditions(fixed, fixed).pass, true);
  assert.equal(compareSimulationConditions([], fixed).pass, false);
  assert.equal(compareSimulationConditions([login(1, 1), packet("set_chunk_cache_radius", [0])], fixed).pass, false);
});
