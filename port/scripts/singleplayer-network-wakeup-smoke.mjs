#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const source = fs.readFileSync(path.join(root,
  "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java"), "utf8");
const region = (startMarker, endMarker) => {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0 && end > start, `missing source region: ${startMarker}`);
  return source.slice(start, end);
};
const signal = region("public static void signalIntegratedServerNetworkInput()",
  "private static boolean scheduleNetworkInputTask(");
const schedule = region("private static boolean scheduleNetworkInputTask(",
  "private static void runScheduledNetworkInput()");
const run = region("private static void runScheduledNetworkInput()",
  "private static void retryNetworkInputAfterTaskFailure()");
const strictDrain = region("private static boolean drainUrgentPackets()",
  "private static boolean drainScheduledNetworkInput()");
const scheduledDrain = region("private static boolean drainScheduledNetworkInput()",
  "public static boolean beginScheduledNetworkInputTask(");
const begin = region("public static boolean beginScheduledNetworkInputTask(",
  "public static void endScheduledNetworkInputTask(");
const end = region("public static void endScheduledNetworkInputTask(",
  "private static boolean drainUrgentPacketsFromServerLoop(");
const sharedDrain = region("private static boolean drainUrgentPacketsFromServerLoop(",
  "public static void pumpUrgentPacketsIfPending()");
const pendingPump = region("public static void pumpUrgentPacketsIfPending()",
  "private static boolean bindServerThreadFromServerLoop(");
const binding = region("private static boolean bindServerThreadFromServerLoop(",
  "public static void signalIntegratedServerNetworkInput()");
const retryFailure = region("private static void retryNetworkInputAfterTaskFailure()",
  "private static void deferNetworkInputRetry()");
const retry = region("private static void deferNetworkInputRetry()",
  "private static void finishNetworkInputBurst()");
const register = region("public static void registerServer(MinecraftServer minecraftServer)",
  "/** Applies local-only permissions");
const stopped = region("public static void markIntegratedServerStopped(MinecraftServer minecraftServer)",
  "public static void main(String[] args)");

assert.match(source, /MAX_NETWORK_INPUT_FOLLOWUPS = 4;/);
assert.match(source, /MAX_NETWORK_INPUT_DEFERRED_RETRIES = 4;/);
assert.match(source, /private static final AtomicBoolean NETWORK_INPUT_TASK_SCHEDULED = new AtomicBoolean\(\);/);
assert.match(source, /private static final Runnable NETWORK_INPUT_TASK\s*=\s*BrowserIntegratedServerMain::runScheduledNetworkInput;/);
assert.match(signal, /recordNetworkInputPending\(true\);/);
assert.match(signal, /scheduleNetworkInputTask\(false, true\);/);
assert.doesNotMatch(signal, /processQueuedPackets|drainUrgentPackets|new Thread/,
  "a browser signal must schedule work instead of executing packet handlers");
assert.match(schedule, /current == null \|\| currentServerThread == null \|\| serverThreadExited/);
assert.match(schedule, /!current\.isRunning\(\)/);
const claim = schedule.indexOf("NETWORK_INPUT_TASK_SCHEDULED.compareAndSet(false, true)");
const enqueue = schedule.indexOf("current.schedule(task)");
assert.ok(claim >= 0 && claim < enqueue);
assert.ok(claim < schedule.indexOf("networkInputFollowupsRemaining = MAX_NETWORK_INPUT_FOLLOWUPS"),
  "coalesced signals must not reset the active burst budget");
assert.match(schedule, /new TickTask\(Integer\.MIN_VALUE, NETWORK_INPUT_TASK\)/);
assert.match(schedule, /scheduledNetworkInputTask = task;/);
assert.ok(schedule.indexOf("LockSupport.unpark(currentServerThread)") < enqueue,
  "coalesced signals must refresh the wake permit");
assert.ok(schedule.indexOf("LockSupport.unpark(currentServerThread)", enqueue) > enqueue,
  "enqueue must precede the new task's explicit wake");
assert.match(schedule, /catch \(RuntimeException \| Error exception\) \{\s*NETWORK_INPUT_TASK_SCHEDULED\.set\(false\);\s*scheduledNetworkInputTask = null;\s*activeNetworkInputTask = null;/s);

assert.match(strictDrain, /Thread\.currentThread\(\) != serverThread/);
for (const method of [scheduledDrain, begin]) {
  assert.match(method, /!isWorkerRuntime\(\) \|\| current == null \|\| serverThreadExited/);
  assert.doesNotMatch(method, /Thread\.currentThread\(\)|instanceof TickTask|tickTask\.getTick\(/,
    "resumed scheduled work must not require a stable TeaVM wrapper or TickTask type");
}
assert.match(begin, /!NETWORK_INPUT_TASK_SCHEDULED\.get\(\)/);
assert.match(scheduledDrain, /urgentPacketPumpActive/);
assert.match(scheduledDrain, /networkInputReschedulePending = true/);
assert.match(scheduledDrain, /network-pump-deferred/);
assert.match(scheduledDrain, /return drainUrgentPacketsFromServerLoop\(current\);/);
assert.match(begin, /activeNetworkInputTask != null/);
assert.match(begin, /activeNetworkInputTask = task;\s*return true;/);
assert.match(end, /if \(entered && activeNetworkInputTask == task\) \{\s*activeNetworkInputTask = null;\s*if \(scheduledNetworkInputTask == task\) \{\s*scheduledNetworkInputTask = null;/s,
  "only the entered dispatch may release its matching lease");
assert.match(sharedDrain, /urgentPacketPumpActive = true;\s*try \{/);
assert.match(sharedDrain, /finally \{\s*urgentPacketPumpActive = false;/);
assert.ok(sharedDrain.indexOf("pumpBrowserChannelsAtFrameBoundary()") <
  sharedDrain.indexOf("processQueuedPackets()"));
assert.ok(pendingPump.indexOf("bindServerThreadFromServerLoop") <
  pendingPump.indexOf("BrowserWebSocketChannel.hasPendingInput"));
assert.match(binding, /current != server \|\| serverThreadExited \|\| !current\.isRunning\(\)/);
assert.match(binding, /serverThread = actualServerThread;/);
assert.match(run, /pumped = drainScheduledNetworkInput\(\);/);
assert.match(run, /finally \{\s*NETWORK_INPUT_TASK_SCHEDULED\.set\(false\);/);
assert.match(run, /if \(!pumped\) \{\s*retryNetworkInputAfterTaskFailure\(\);\s*return;/);
assert.match(run, /if \(!inputPending\) \{\s*finishNetworkInputBurst\(\);\s*return;/);
assert.match(run, /networkInputFollowupsRemaining <= 0/);
assert.match(run, /networkInputFollowupsRemaining--;\s*scheduleNetworkInputTask\(true, false\);/);
assert.match(retryFailure, /serverThreadExited\s*\|\| !current\.isRunning\(\)/);
assert.match(retryFailure, /deferNetworkInputRetry\(\);/);
assert.match(retry, /networkInputDeferredRetriesRemaining--;/);
assert.match(retry, /int delayMillis = 1 << Math\.min\(3, retry\);/);
assert.match(retry, /TModernRuntimeSupport\.yieldToEventLoop\(delayMillis\);/);
// Current implementation restarts its retry ladder after an 8 ms yield. It does not
// promise a total retry bound or an owner-generation guard across suspension.
assert.match(retry, /networkInputDeferredRetriesRemaining <= 0/);
assert.match(retry, /networkInputDeferredRetriesRemaining = MAX_NETWORK_INPUT_DEFERRED_RETRIES;\s*TModernRuntimeSupport\.yieldToEventLoop\(8\);/);
assert.match(retry, /scheduleNetworkInputTask\(false, false\);/);
assert.doesNotMatch(run + retry + retryFailure, /new Thread|setTimeout\(|setInterval\(/);
for (const lifecycle of [register, stopped]) {
  assert.match(lifecycle, /NETWORK_INPUT_TASK_SCHEDULED\.set\(false\);/);
  assert.match(lifecycle, /scheduledNetworkInputTask = null;/);
  assert.match(lifecycle, /activeNetworkInputTask = null;/);
}
assert.match(register, /serverThreadExited = false;/);
assert.match(stopped, /if \(server == minecraftServer\) \{/);
assert.match(stopped, /serverThreadExited = true;\s*serverThread = null;/);

// Execute the actual telemetry JSBody rather than a separate invented Java scheduler
// model. These checks prove emitted counter semantics, not JVM/TeaVM task execution.
const telemetryMarker = '@JSBody(params = {"event", "pending"}, script = """';
const networkMethodAt = source.indexOf("private static native void recordNetworkPumpState(");
const telemetryStart = source.lastIndexOf(telemetryMarker, networkMethodAt);
const telemetryEnd = networkMethodAt;
assert.ok(telemetryStart >= 0 && telemetryEnd > telemetryStart, "missing network telemetry source region");
const telemetry = source.slice(telemetryStart, telemetryEnd);
const script = telemetry.slice(telemetry.indexOf('"""') + 3, telemetry.lastIndexOf('""")'));
assert.match(script, /var field = '';/);
assert.doesNotMatch(script, /let field = '';/);
const context = vm.createContext({});
const record = vm.runInContext(`(function(event, pending) {${script}\n})`, context);
assert.doesNotThrow(() => record(0, true), "missing telemetry must be harmless");
const stats = {};
context.__gaiusNetworkStats = stats;
record(-1, false);
assert.equal(stats.integratedServerTaskTelemetryVersion, 1);
const counters = ["Signals", "Unparks", "Coalesced", "Schedules", "ScheduleFailures",
  "Runs", "Followups", "LifecycleDrops", "WrongThread", "BudgetExhaustions",
  "DeferredRetries", "RetryExhaustions"];
for (const [event, suffix] of counters.entries()) {
  const field = `integratedServerTask${suffix}`;
  assert.equal(stats[field], 0, `${field} must initialize to zero`);
  record(event, true);
  assert.equal(stats[field], 1, `${field} must count its event`);
  assert.equal(stats.integratedServerTaskPending, 1);
}
record(5, false);
assert.equal(stats.integratedServerTaskRuns, 2);
assert.equal(stats.integratedServerTaskSignals, 1, "later events must preserve counters");
assert.equal(stats.integratedServerTaskPending, 0);
console.log(JSON.stringify({ok: true, staticPermitLifecycleChecks: true,
  telemetryJsBodyExecuted: true, telemetryEventsChecked: counters.length,
  runtimeSchedulingVerified: false, retryLadderRestarts: true}));
