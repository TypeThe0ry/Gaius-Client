import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(
  new URL("../src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java", import.meta.url),
  "utf8",
);

function extract(parameter, method) {
  const methodMarker = `private static native void ${method}`;
  const methodPosition = source.indexOf(methodMarker);
  assert.ok(methodPosition >= 0, `${method} declaration was not found`);
  const annotationPosition = source.lastIndexOf("@JSBody(", methodPosition);
  const declaration = source.slice(annotationPosition, methodPosition);
  const pattern = new RegExp(
    `@JSBody\\(params = "${parameter}", script = """([\\s\\S]*?)"""\\)\\s*$`,
  );
  const match = declaration.match(pattern);
  assert.ok(match, `${method} JSBody was not found`);
  return vm.runInContext(`(function(${parameter}) {${match[1]}\n})`, context);
}

const context = {globalThis: {}, Number, Math};
vm.createContext(context);
const begin = extract("startedAt", "recordServerTickBegin");
const end = extract("endedAt", "recordServerTickEnd");
const waitBegin = extract("startedAt", "recordServerWaitBegin");
const waitEnd = extract("endedAt", "recordServerWaitEnd");

// Disabled is the release default and must not allocate or mutate telemetry.
begin.call(context, 10);
end.call(context, 15);
waitBegin.call(context, 20);
waitEnd.call(context, 25);
assert.deepEqual(context.globalThis, {});

context.globalThis.__gaiusServerTickTelemetryEnabled = true;
begin.call(context, 100);
end.call(context, 117.5);
begin.call(context, 120);
end.call(context, 125);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.schemaVersion, 1);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.tickCount, 2);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.lastTickIntervalMillis, 20);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.maxTickIntervalMillis, 20);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver100MillisCount, 0);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver500MillisCount, 0);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.lastTickDurationMillis, 5);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.maxTickDurationMillis, 17.5);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.tickWorkOver500MillisCount, 0);

// Thresholds are strict: exact 100/500 ms samples do not count, while 501 ms does.
begin.call(context, 220);
end.call(context, 720);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver100MillisCount, 0);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver500MillisCount, 0);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.tickWorkOver500MillisCount, 0);
begin.call(context, 720);
end.call(context, 1220);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver100MillisCount, 1);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver500MillisCount, 0);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.tickWorkOver500MillisCount, 0);
begin.call(context, 1221);
end.call(context, 1722);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver100MillisCount, 2);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver500MillisCount, 1);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.tickWorkOver500MillisCount, 1);

waitBegin.call(context, 0);
waitEnd.call(context, 2);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.waitPhaseCount, 1);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.completedWaitPhaseCount, 1);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.waitPhaseOver500MillisCount, 0);
waitBegin.call(context, 300);
context.globalThis.__gaiusServerTickTelemetry = {};
waitEnd.call(context, 9);
assert.deepEqual(context.globalThis.__gaiusServerTickTelemetry, {});
waitBegin.call(context, 200);
waitEnd.call(context, 212.5);
waitEnd.call(context, 999);
waitBegin.call(context, 220);
waitEnd.call(context, 225);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.waitPhaseCount, 2);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.completedWaitPhaseCount, 2);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.lastWaitPhaseDurationMillis, 5);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.maxWaitPhaseDurationMillis, 12.5);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.totalWaitPhaseDurationMillis, 17.5);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.waitPhaseOpen, false);

waitBegin.call(context, 300);
waitEnd.call(context, 800);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.waitPhaseOver500MillisCount, 0);
waitBegin.call(context, 900);
waitEnd.call(context, 1401);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.waitPhaseOver500MillisCount, 1);
waitEnd.call(context, 9999);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.waitPhaseOver500MillisCount, 1);

// A replaced telemetry object starts fresh counters on its first valid sample.
context.globalThis.__gaiusServerTickTelemetry = {};
begin.call(context, 2000);
begin.call(context, 2601);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver100MillisCount, 1);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.intervalOver500MillisCount, 1);
end.call(context, 3102);
assert.equal(context.globalThis.__gaiusServerTickTelemetry.tickWorkOver500MillisCount, 1);

// Malformed diagnostic state is fail-open and must not escape into server code.
context.globalThis.__gaiusServerTickTelemetry = null;
assert.doesNotThrow(() => begin("not-a-number"));
assert.doesNotThrow(() => end("not-a-number"));
context.globalThis.__gaiusServerTickTelemetry = undefined;
assert.doesNotThrow(() => waitBegin("not-a-number"));
assert.doesNotThrow(() => waitEnd("not-a-number"));
assert.equal(context.globalThis.__gaiusServerTickTelemetry, undefined);

console.log("SERVER_TICK_TELEMETRY_OK", JSON.stringify(context.globalThis.__gaiusServerTickTelemetry));
