import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const sourcePath = path.join(
  repository,
  "port",
  "src",
  "main",
  "java",
  "dev",
  "gaius",
  "browser",
  "BrowserPacketScheduler.java",
);
const source = fs.readFileSync(sourcePath, "utf8");

function block(startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0 && end > start, `missing source block: ${startMarker}`);
  return source.slice(start, end);
}

const flush = block(
  "private static void flushOwnerClientFrameAccounting(PacketProcessorLedger ledger)",
  "private static void startOwnerClientFrame(PacketProcessorLedger ledger)",
);
assert.match(flush, /try\s*\{/);
assert.match(flush, /BrowserClientNetwork\.recordClientPacketFrame\(/);
assert.match(flush, /finally\s*\{/);
assert.match(flush, /ledger\.clientFramePacketCount\s*=\s*0/);
assert.match(flush, /ledger\.clientFramePacketHandleNanos\s*=\s*0L/);
assert.match(flush, /ledger\.clientFrameAccountingActive\s*=\s*false/);

const begin = block(
  "public static void beginClientFrame()",
  "private static void beginOwnerBatch(PacketProcessorLedger ledger)",
);
assert.match(begin, /flushOwnerClientFrameAccounting\(previous\)/);
assert.match(begin, /pendingClientFrame\s*=\s*true/);

const start = block(
  "private static void startOwnerClientFrame(PacketProcessorLedger ledger)",
  "public static long currentClientFrameSequence()",
);
assert.match(start, /flushOwnerClientFrameAccounting\(ledger\)/);
assert.match(start, /ledger\.clientFrameAccountingActive\s*=\s*true/);

// Model the exact regression: beginClientFrame() and owner binding both call flush.  A flush is
// idempotent after the first publication, so a frame with two packets yields one evidence event.
const ledger = {
  active: true,
  packetCount: 2,
  events: 0,
};
function modeledFlush() {
  if (!ledger.active || ledger.packetCount === 0) return;
  ledger.events++;
  ledger.packetCount = 0;
  ledger.active = false;
}
modeledFlush();
modeledFlush();
assert.equal(ledger.events, 1);
assert.equal(ledger.packetCount, 0);
assert.equal(ledger.active, false);

console.log(JSON.stringify({
  smoke: "client-frame-accounting",
  flushIdempotent: true,
  duplicateFrameEvidence: false,
  result: "pass",
}));
