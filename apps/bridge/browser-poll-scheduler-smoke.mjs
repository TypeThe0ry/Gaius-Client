/*
 * Fast, server-free regression smoke for browser-full-path's Node poll
 * scheduler.  It deliberately exercises only synthetic clients: no Java,
 * RelayNode, WebSocket, or generated artifacts are required.  The strict
 * multiplayer latency limits are checked as source invariants here and remain
 * unchanged; this smoke checks scheduler ordering/fairness and records host
 * timer behaviour without turning a noisy desktop into a false pass.
 */

import {CANONICAL_PROFILE, LEGACY_PROFILE, RENDERPEARL_PROFILE, escapeRegExp} from "../../port/scripts/verification-profiles.mjs";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { monitorEventLoopDelay } from "node:perf_hooks";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";

const schedulerPath = fileURLToPath(
    new URL("./browser-full-path-smoke.mjs", import.meta.url));
const source = await readFile(schedulerPath, "utf8");
const channelSource = await readFile(fileURLToPath(new URL(
    "../../port/overrides/libraries/netty-transport/src/main/java/" +
        "io/netty/channel/browser/BrowserWebSocketChannel.java",
    import.meta.url)), "utf8");
const schedulerStart = source.indexOf("function createFairClientPollScheduler");
const schedulerEnd = source.indexOf("\nfunction createClientIdentity", schedulerStart);
assert.ok(schedulerStart >= 0 && schedulerEnd > schedulerStart,
    "poll scheduler function boundaries changed; inspect before updating this smoke");
const schedulerSource = source.slice(schedulerStart, schedulerEnd);

// Static contract: bounded batch/fairness is mandatory, and poll scheduling
// must not regress to a recursive timer, setInterval fan-out, or nextTick loop.
assert.match(source, /const MAX_CLIENTS_PER_POLL_CALLBACK = 4/u);
assert.match(source, /const MAX_POLL_CALLBACK_WORK_MILLIS = 8/u);
assert.match(source, /const POLL_SCHEDULER_TIMER_YIELD_TURNS = 256/u);
assert.match(source, /const POLL_SCHEDULER_IDLE_BACKOFF_MILLIS = 1/u);
assert.match(source,
    /const POLL_SCHEDULER_IDLE_IMMEDIATE_WINDOW_MILLIS = 2/u,
    "near-due poll cursors must have a bounded immediate window");
assert.match(source,
    /const POLL_SCHEDULER_IDLE_IMMEDIATE_PROBE_BUDGET_MILLIS = 2/u,
    "near-due immediate probes must have an absolute time budget");
assert.match(source,
    /const POLL_SCHEDULER_IDLE_IMMEDIATE_SPIN_LIMIT = 16/u,
    "near-due immediate spins must retain a hard limit");
assert.match(source,
    /overdueWakePending && dispatched === 0/u,
    "overdue wake suppression must not discard a successful dispatch");
assert.match(source,
    /lastDueTicksBeforeIdle > 0 \|\| lastDueTicksAfterService > 0 \|\|\s*\n?\s*readyAfterDispatch/u,
    "ready poll candidates must not be parked on the idle timer");
assert.match(source,
    /probeElapsedMillis[\s\S]*POLL_SCHEDULER_IDLE_IMMEDIATE_PROBE_BUDGET_MILLIS/u,
    "near-due probe must enforce its absolute elapsed-time budget");
assert.match(source,
    /const MAX_PLAY_TICKS_PER_SCHEDULER_CALLBACK = MAX_CLIENTS_PER_POLL_CALLBACK/u);
assert.match(source,
    /const POLL_SCHEDULER_PLAY_TICK_TIMER_YIELD_GUARD_MILLIS = 16\.7/u,
    "near-due PLAY ticks must block periodic timer-yield quantization");
assert.match(source, /const CALLBACK_TAIL_SLOW_THRESHOLD_MILLIS = 16\.7/u);
assert.match(source, /const CALLBACK_TAIL_SAMPLE_LIMIT = 64/u);
assert.match(source,
    /const CALLBACK_FINALIZATION_TAIL_TELEMETRY_SCHEMA_VERSION\s*=\s*["']gaius\.browser-client-poll-callback-finalization-tail\.v1["']/u,
    "callback finalization-tail schema drifted");
assert.match(source,
    /const CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS = 16\.7/u,
    "callback finalization-tail threshold drifted");
assert.match(source,
    /const CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT = 64/u,
    "callback finalization-tail sample limit drifted");
assert.match(source,
    /const PLAY_TICK_TIMING_TELEMETRY_SCHEMA_VERSION\s*=\s*["']gaius\.browser-client-play-tick-timing\.v1["']/u,
    "PLAY tick timing schema drifted");
assert.match(source, /const PLAY_TICK_TIMING_SAMPLE_LIMIT = 64/u,
    "PLAY tick timing ring limit drifted");
assert.match(source,
    /const PLAY_TICK_TIMING_RETENTION = ["']last-64-chronological["']/u,
    "PLAY tick timing retention policy drifted");
assert.match(source,
    /const PLAY_TICK_SLOW_SAMPLE_THRESHOLD_MILLIS = 60/u,
    "slow PLAY tick diagnostic threshold drifted");
assert.match(source,
    /const PLAY_TICK_SLOW_SAMPLE_LIMIT = 64/u,
    "slow PLAY tick diagnostic ring limit drifted");
assert.match(source,
    /const PLAY_TICK_SLOW_SAMPLE_RETENTION = ["']largest-gap-desc-sequence-asc["']/u,
    "slow PLAY tick retention policy drifted");
// Per-client poll phase evidence is deliberately a diagnostic ring.  Keep the
// schema/caps visible at source level so a scheduler refactor cannot silently
// turn it into an unbounded trace or alter the strict frame gate.
assert.match(source,
    /const POLL_PHASE_TELEMETRY_SCHEMA_VERSION\s*=\s*["']gaius\.browser-client-poll-phase\.v1["']/u,
    "poll phase telemetry schema drifted");
assert.match(source,
    /const POLL_PHASE_SLOW_THRESHOLD_MILLIS\s*=\s*16\.7/u,
    "poll phase diagnostic threshold drifted");
assert.match(source,
    /const POLL_PHASE_SAMPLE_LIMIT\s*=\s*64/u,
    "poll phase sample limit drifted");
assert.match(source,
    /const POLL_PHASE_FRAME_SAMPLE_LIMIT\s*=\s*8/u,
    "poll phase frame cap drifted");
assert.match(source,
    /const POLL_PHASE_PACKET_SAMPLE_LIMIT\s*=\s*64/u,
    "poll phase packet cap drifted");
assert.match(source,
    /const POLL_PHASE_SEGMENT_ACCOUNTING\s*=\s*["']inclusive-overlapping["']/u,
    "poll phase segment accounting semantics drifted");
// A 26.2 ClientboundSetTime packet (id 113) normally arrives on the vanilla
// twenty-tick cadence.  This is a narrow diagnostic hint only; it must never
// relax or replace the strict packet/tick/callback gates.
assert.match(source,
    /ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID\s*=\s*CANONICAL_PROFILE\.id/u,
    "periodic server-sync profile contract drifted");
assert.match(source,
    /ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION\s*=\s*776/u,
    "periodic server-sync protocol contract drifted");
assert.match(source,
    /ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID\s*=\s*113/u,
    "periodic server-sync packet id contract drifted");
assert.match(source,
    /ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS\s*=\s*1000/u,
    "periodic server-sync cadence contract drifted");
assert.match(source,
    /ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS\s*=\s*125/u,
    "periodic server-sync cadence tolerance drifted");
assert.match(source,
    /phaseAtDecode\s*===\s*["']play["'][\s\S]*?!localSlowSegment/u,
    "periodic server-sync hint must be restricted to PLAY without a slow local segment");
assert.match(source,
    /excludedFromUserVisibleStall\s*:/u,
    "periodic server-sync exclusion marker missing");
assert.match(source,
    /strictGateImpact\s*:\s*["']none["']/u,
    "periodic server-sync must remain outside strict gates");
assert.match(source, /pollPhaseSamplesTotal/u,
    "client poll phase total counter is missing");
assert.match(source, /pollPhaseSamplesDropped/u,
    "client poll phase dropped counter is missing");
assert.match(source, /pollPhaseSamples/u,
    "client poll phase bounded ring is missing");
assert.match(source, /resetPollPhaseContext\(/u,
    "poll phase context is not reset per poll");
assert.match(source, /addPollPhaseSegment\(/u,
    "poll phase segment timing helper is missing");
assert.match(source, /retainPollPhaseSample\(/u,
    "poll phase bounded retention helper is missing");
assert.match(source, /pollPhaseTelemetryResult\(/u,
    "poll phase result serializer is missing");
for (const field of [
    "playTickTimingRing",
    "playTickTimingSequence",
    "recordPlayTickTiming(",
    "playTickTimingResult(",
    "playTickTimingSamplesTotal",
    "playTickTimingSamplesDropped",
    "PLAY_TICK_SLOW_SAMPLE_THRESHOLD_MILLIS",
    "PLAY_TICK_SLOW_SAMPLE_LIMIT",
    "PLAY_TICK_SLOW_SAMPLE_RETENTION",
    "slowSamplesTotal",
    "slowSamplesDropped",
    "slowSamples",
]) {
    assert.match(source, new RegExp(escapeRegExp(field), "u"),
        `PLAY tick timing telemetry omitted ${field}`);
}
const playTickTimingRecordStart = source.indexOf("    recordPlayTickTiming({");
const playTickTimingRecordEnd = source.indexOf("\n    playTickTimingResult()", playTickTimingRecordStart);
assert.ok(playTickTimingRecordStart >= 0 &&
    playTickTimingRecordEnd > playTickTimingRecordStart,
    "PLAY tick timing record boundaries changed; inspect bounded telemetry");
const playTickTimingRecordSource = source.slice(
    playTickTimingRecordStart, playTickTimingRecordEnd);
assert.match(playTickTimingRecordSource, /phase:\s*typeof this\.phase/u,
    "PLAY tick timing sample omitted client phase");
const playTickTimingStart = playTickTimingRecordEnd;
const playTickTimingEnd = source.indexOf("\n    servicePlayTick(", playTickTimingStart);
assert.ok(playTickTimingEnd > playTickTimingStart,
    "PLAY tick timing result boundaries changed; inspect bounded telemetry");
const playTickTimingSource = source.slice(playTickTimingStart, playTickTimingEnd);
assert.match(playTickTimingSource,
    /strictGatesChanged:\s*false[\s\S]*?diagnosticOnly:\s*true[\s\S]*?independentExecution:\s*false/u,
    "PLAY tick timing evidence must remain diagnostic-only");
const clientPollStart = source.indexOf("    poll(schedulerCallbackSequence");
const clientPollEnd = source.indexOf("\n    parsePackets(", clientPollStart);
assert.ok(clientPollStart >= 0 && clientPollEnd > clientPollStart,
    "BrowserMinecraftClient.poll boundaries changed; inspect telemetry hooks");
const clientPollSource = source.slice(clientPollStart, clientPollEnd);
const phaseScanStart = source.indexOf("    recordPhases() {");
const phaseScanEnd = source.indexOf("\n    close(", phaseScanStart);
assert.ok(phaseScanStart >= 0 && phaseScanEnd > phaseScanStart,
    "recordPhases boundaries changed; inspect incremental phase scan hooks");
const phaseScanSource = source.slice(phaseScanStart, phaseScanEnd);
assert.match(phaseScanSource, /phaseScanSource/u,
    "recordPhases must retain source-array identity for incremental scanning");
assert.match(phaseScanSource, /phaseScanIndex/u,
    "recordPhases must retain an append cursor for incremental scanning");
assert.match(phaseScanSource, /phaseScanCursorEvent/u,
    "recordPhases must retain an event-identity cursor for ring rollover");
assert.match(phaseScanSource, /source\.indexOf\(this\.phaseScanCursorEvent\)/u,
    "recordPhases must detect in-place ring rollover by event identity");
assert.match(phaseScanSource,
    /sourceRolled[\s\S]*?scanStart[\s\S]*?sourceRolled\s*\?\s*0/u,
    "ring rollover must rescan the bounded ring without resetting the compatibility history");
assert.match(phaseScanSource,
    /if\s*\(sourceReplaced\s*\|\|\s*sourceTruncated\s*\|\|\s*!eventSeen\)\s*\n?\s*this\.connectPhases\.push\(event\)/u,
    "ring rollover must not duplicate previously published phase events");
assert.doesNotMatch(phaseScanSource,
    /this\.stats\.connectPhases\.filter\(\(event\) => event\.id === this\.id\)/u,
    "recordPhases reverted to an O(history) filter on every poll");
for (const marker of [
    "this.checkError()",
    "this.recordPhases()",
    "this.parsePackets(",
    "this.bridge.pollInbound(",
    "Buffer.concat(",
    "this.decipher.update(",
    "retainPollPhaseSample(",
]) {
    assert.match(clientPollSource, new RegExp(escapeRegExp(marker), "u"),
        `poll phase source omitted ${marker}`);
}
assert.match(clientPollSource, /finally\s*\{[\s\S]*?pollContext\.durationRawMillis/u,
    "poll phase duration must be finalized on success and error");
const pollPhaseContractIndex = source.indexOf("pollPhaseTelemetry");
assert.ok(pollPhaseContractIndex >= 0,
    "performance contract omitted pollPhaseTelemetry");
const pollPhaseContractWindows = [...source.matchAll(/pollPhaseTelemetry/gu)]
    .map(({ index }) => source.slice(index, index + 2500));
assert.ok(pollPhaseContractWindows.some((window) =>
    /strictGatesChanged\s*:\s*false/u.test(window) &&
    /independentExecution\s*:\s*false/u.test(window) &&
    /diagnosticOnly\s*:\s*true/u.test(window)),
"poll phase evidence must remain diagnostic-only and non-independent");
assert.match(schedulerSource, /setImmediate/u);
assert.match(schedulerSource,
    /candidate\.poll\(callbackSequenceNumber,\s*trigger\)/u,
    "scheduler did not pass callback provenance into client poll");
assert.doesNotMatch(schedulerSource, /candidate\.poll\(\);/u,
    "scheduler retained a no-argument poll call");
assert.match(schedulerSource, /setTimeout\(\(\) => \{[\s\S]*?\}, 0\)/u);
assert.match(schedulerSource, /POLL_SCHEDULER_TIMER_YIELD_TURNS/u);
assert.match(schedulerSource, /POLL_SCHEDULER_TIMER_YIELD_WORK_MILLIS/u);
assert.match(schedulerSource, /POLL_SCHEDULER_WATCHDOG_MILLIS/u);
assert.match(schedulerSource, /maxClientPollDurationMillis/u);
assert.match(schedulerSource, /eventLoopDelayMonitor/u);
assert.match(schedulerSource, /callbackRunning/u);
assert.match(schedulerSource, /nextPollDueAtByClient/u);
assert.match(schedulerSource, /readNextPollDueAt/u);
assert.match(schedulerSource, /writeNextPollDueAt/u);
assert.match(schedulerSource, /hasPendingInbound/u);
assert.match(schedulerSource, /hasImmediateInbound/u);
assert.match(schedulerSource, /isPollReady/u);
assert.match(schedulerSource, /readPollFairnessFloor/u);
assert.match(schedulerSource, /isPollFairlyEligible/u);
assert.match(schedulerSource, /hasFairPollReady/u);
assert.match(schedulerSource, /earliestPollDueAt/u,
    "idle admission must inspect the earliest scheduler-owned due cursor");
assert.match(schedulerSource, /dueInImmediateWindow/u,
    "near-due poll cursors must bypass timer quantization with bounded immediates");
assert.match(schedulerSource, /idleImmediateSpins/u,
    "near-due immediate spin budget is missing");
assert.match(schedulerSource, /visibleClientIdCollator/u,
    "client-id collator must be pre-warmed outside the callback hot path");
assert.match(schedulerSource, /compareVisibleClientIds/u,
    "visible client evidence must use the bounded id comparator");
assert.doesNotMatch(schedulerSource,
    /String\(left\.id\)\.localeCompare\(/u,
    "callback hot path must not lazily initialize numeric locale collation");
assert.match(schedulerSource, /fairnessSkips/u);
assert.match(schedulerSource, /callbackSequence/u);
assert.match(schedulerSource, /callbackTail/u);
assert.match(schedulerSource, /slowCallbackSamplesTotal/u);
assert.match(schedulerSource, /slowCallbackSamplesDropped/u);
assert.match(schedulerSource, /slowCallbackSamples/u);
assert.match(schedulerSource, /phaseTimingsMillis/u);
assert.match(schedulerSource, /pollCandidatesInspected/u);
assert.match(schedulerSource, /tickCandidatesInspected/u);
assert.match(schedulerSource, /maxPerTickDurationRawMillis/u);
assert.match(schedulerSource, /maxPerPollDurationRawMillis/u);
assert.match(schedulerSource, /budgetReachedPhase/u);
assert.match(schedulerSource, /terminalPhase/u);
assert.match(schedulerSource, /strictFrameBudgetExcessMillis/u);
assert.match(schedulerSource, /nextContinuation/u);
// `readyAfterDispatch` crosses the poll-loop/finalizer boundary.  Keep one
// callback-scoped declaration before the client block; a block-scoped
// declaration here either throws in the finalizer or lets a ready zero-
// dispatch turn fall through to the idle timer.
const readyAfterDispatchDeclaration = schedulerSource.indexOf(
    "let readyAfterDispatch = false;");
const firstClientBlock = schedulerSource.indexOf(
    "if (clients.length > 0) {", readyAfterDispatchDeclaration);
assert.ok(readyAfterDispatchDeclaration >= 0 && firstClientBlock >
    readyAfterDispatchDeclaration,
"readyAfterDispatch must be declared before the client poll block");
assert.equal((schedulerSource.match(/let readyAfterDispatch = false;/gu) ?? []).length,
    1,
"readyAfterDispatch must have exactly one callback-scoped declaration");
assert.match(schedulerSource,
    /if\s*\(dispatched === 0\)[\s\S]*?lastDueTicksAfterService > 0[\s\S]*?readyAfterDispatch[\s\S]*?nextContinuation = "immediate"/u,
"zero-dispatch finalization must preserve readiness as an immediate continuation");
assert.match(source,
    /const SCHEDULER_GAP_TELEMETRY_SCHEMA_VERSION\s*=\s*["']gaius\.browser-client-scheduler-gap\.v1["']/u,
    "scheduler gap telemetry schema drifted");
assert.match(source, /const SCHEDULER_GAP_SLOW_THRESHOLD_MILLIS\s*=\s*250/u,
    "scheduler gap diagnostic threshold drifted");
assert.match(source,
    /const SCHEDULER_GAP_STRICT_CALLBACK_THRESHOLD_MILLIS\s*=\s*16\.7/u,
    "scheduler gap strict callback threshold drifted");
assert.match(source, /const SCHEDULER_GAP_SAMPLE_LIMIT\s*=\s*64/u,
    "scheduler gap sample limit drifted");
assert.match(schedulerSource, /lastCallbackFinishedAt/u,
    "scheduler gap evidence lost callback-finish boundary");
assert.match(schedulerSource, /schedulerGapSamplesTotal/u,
    "scheduler gap total counter is missing");
assert.match(schedulerSource, /schedulerGapSamplesDropped/u,
    "scheduler gap dropped counter is missing");
assert.match(schedulerSource, /retainSchedulerGapSample\(/u,
    "scheduler gap bounded retention helper is missing");
assert.match(schedulerSource, /interCallbackIdleGapRawMillis/u,
    "scheduler gap sample lost idle-gap split");
assert.match(schedulerSource, /schedulerGap:\s*\{/u,
    "scheduler gap evidence is not serialized");
assert.match(schedulerSource,
    /schedulerGap:[\s\S]*?diagnosticOnly:\s*true[\s\S]*?strictGatesChanged:\s*false/u,
    "scheduler gap telemetry must remain diagnostic-only");
assert.match(source,
    /const SCHEDULER_SEGMENT_TELEMETRY_SCHEMA_VERSION\s*=\s*["']gaius\.browser-client-poll-scheduler-segments\.v1["']/u,
    "scheduler segment telemetry schema drifted");
assert.match(source,
    /const SCHEDULER_SEGMENT_SLOW_THRESHOLD_MILLIS\s*=\s*16\.7/u,
    "scheduler segment diagnostic threshold drifted");
assert.match(source,
    /const SCHEDULER_SEGMENT_SAMPLE_LIMIT\s*=\s*64/u,
    "scheduler segment sample limit drifted");
assert.match(source,
    /const SCHEDULER_SEGMENT_TELEMETRY_ENABLED\s*=\s*ARRIVAL_TRACE_ENABLED/u,
    "scheduler segment telemetry must share the opt-in diagnostic switch");
assert.match(schedulerSource, /retainSchedulerSegmentSample\(/u,
    "scheduler segment bounded retention helper is missing");
assert.match(schedulerSource, /schedulerSegmentSamplesTotal/u,
    "scheduler segment total counter is missing");
assert.match(schedulerSource, /schedulerSegmentSamplesDropped/u,
    "scheduler segment dropped counter is missing");
assert.match(schedulerSource, /schedulerSegments:\s*\{/u,
    "scheduler segment evidence is not serialized");
for (const field of [
    "schedulerAdmissionRawMillis",
    "schedulerReadinessRawMillis",
    "schedulerFairnessRawMillis",
    "schedulerPostDispatchReadinessRawMillis",
    "schedulerPostDispatchRawMillis",
    "schedulerEvidenceRawMillis",
    "schedulerFinalizationRawMillis",
    "slowestSegmentRawMillis",
]) {
    assert.match(schedulerSource, new RegExp(field, "u"),
        `scheduler segment evidence omitted ${field}`);
}
assert.match(schedulerSource,
    /strictEvidenceEligible:\s*SCHEDULER_SEGMENT_STRICT_EVIDENCE_ELIGIBLE/u,
    "scheduler segment trace must remain ineligible for strict evidence");
assert.match(source,
    /schedulerSegments:\s*schedulerSegmentTelemetryContract\(\)/u,
    "performance contract omitted scheduler segment telemetry");
assert.match(schedulerSource, /scheduled-delay/u,
    "scheduler gap evidence lost scheduled-delay trigger");
assert.match(schedulerSource, /previousCallbackSequence/u,
    "scheduler gap evidence lost previous callback sequence");
assert.match(schedulerSource, /previousCallbackStartedAtMillis/u,
    "scheduler gap evidence lost previous callback start timestamp");
assert.match(schedulerSource, /previousCallbackFinishedAtMillis/u,
    "scheduler gap evidence lost previous callback finish timestamp");
assert.match(schedulerSource, /interCallbackGapRawMillis/u,
    "scheduler gap evidence lost start-to-start gap");
assert.match(schedulerSource, /clockAnomaly/u,
    "scheduler gap evidence lost clock anomaly marker");
// The gap sample is finalized from the sibling `finally` block.  Keep its
// delay values in `run()` scope so a future edit cannot hide a block-scoped
// declaration inside `try` and trigger a runtime ReferenceError on callback.
const schedulerDelayScopeOffset = schedulerSource.indexOf(
    "let scheduleDelayRawMillis = 0;");
const schedulerRunTryOffset = schedulerSource.indexOf(
    "\n        try {", schedulerDelayScopeOffset);
assert.ok(schedulerDelayScopeOffset >= 0 && schedulerRunTryOffset >
    schedulerDelayScopeOffset,
"scheduler delay evidence must be declared before the callback try block");
assert.equal(schedulerSource.indexOf("const scheduleDelayMillis"), -1,
    "scheduler delay must not be block-scoped inside try");
assert.match(source, /ARRIVAL_TRACE_FORCED_EVENT_LIMIT\s*=\s*64/u,
    "arrival forced trace ring limit drifted");
assert.match(source, /["']arrival-gap-boundary["']/u,
    "arrival gap boundary event kind is missing");
assert.match(source, /forcedBoundaryDropped/u,
    "arrival forced boundary overflow accounting is missing");
assert.match(source, /forcedBoundaryCoverage/u,
    "arrival forced boundary coverage is missing");
assert.match(source, /force:\s*true/u,
    "arrival forced boundary marker is not force-retained");
assert.match(schedulerSource, /finalizeStartAt/u,
    "callback finalization-tail start timestamp is missing");
assert.match(schedulerSource, /finalizeFinishAt/u,
    "callback finalization-tail finish timestamp is missing");
assert.match(schedulerSource, /tailRawMillis/u,
    "callback finalization-tail raw duration is missing");
assert.match(schedulerSource, /totalAfterFinalizeRawMillis/u,
    "callback finalization-tail total endpoint is missing");
assert.match(schedulerSource, /slowFinalizationTailSamplesDropped/u,
    "callback finalization-tail drop counter is missing");
assert.match(schedulerSource, /callbackFinalizationTail/u,
    "callback finalization-tail evidence is missing");
assert.match(schedulerSource, /servicePlayTick/u);
assert.match(schedulerSource, /MAX_PLAY_TICKS_PER_SCHEDULER_CALLBACK/u);
assert.match(schedulerSource,
    /const tickNow = performance\.now\(\);[\s\S]*?if\s*\(!isPlayTickDue\(candidate,\s*tickNow\)\)\s*continue;[\s\S]*?candidate\.servicePlayTick\(\s*tickNow,\s*callbackSequenceNumber,\s*trigger,\s*callbackPhase(?:,\s*tickSchedulerContext)?\)/u,
    "scheduler did not pass PLAY tick callback provenance");
assert.match(schedulerSource, /tickSchedulerContext/u,
    "scheduler did not expose bounded tick attribution context");
assert.match(schedulerSource, /budgetRemainingMillis/u,
    "tick attribution context lost callback budget remaining");
assert.match(schedulerSource, /callbackFinishedAtMillis/u,
    "tick attribution context lost callback finish timestamp");
assert.match(schedulerSource, /schedulerNextContinuation/u,
    "tick attribution context lost selected continuation");
assert.match(schedulerSource, /isPlayTickDue/u,
    "scheduler must inspect unserviced PLAY tick deadlines before idling");
assert.match(schedulerSource,
    /if\s*\(client\.playTickSuspended\s*===\s*true\)\s*return\s*true/u,
    "scheduler due admission must wake a resumed suspended PLAY client");
assert.match(schedulerSource, /countDuePlayTicks/u,
    "scheduler must count due PLAY ticks after the bounded tick batch");
assert.match(schedulerSource, /hasPlayTickDueWithin/u,
    "scheduler must inspect near-due PLAY tick deadlines before timer-yield");
assert.match(schedulerSource, /lastDueTicksAfterService/u);
assert.match(schedulerSource, /lastDueTicksBeforeIdle/u);
assert.match(schedulerSource,
    /lastDueTicksBeforeIdle\s*=\s*countDuePlayTicks\(clients,\s*readinessAt\)/u);
assert.match(schedulerSource,
    /if\s*\(lastDueTicksBeforeIdle\s*>\s*0\)\s*\{[\s\S]*?nextContinuation\s*=\s*"immediate"/u,
    "due PLAY ticks must force an immediate continuation instead of idle timer");
assert.match(schedulerSource, /dueTickImmediateContinuations/u);
assert.match(schedulerSource,
    /const dueTicksPending\s*=\s*lastDueTicksAfterService\s*>\s*0\s*\|\|\s*\n\s*lastDueTicksBeforeIdle\s*>\s*0/u,
    "due PLAY tick state must be carried through the final continuation choice");
assert.match(schedulerSource,
    /const readyPollPending\s*=\s*readyAfterDispatch[\s\S]*?const continuation\s*=\s*dueTicksPending\s*\|\|\s*readyPollPending\s*\|\|\s*\n?\s*timerYieldSuppressedByPlayTick\s*\n?\s*\?\s*"immediate"\s*\n\s*:\s*needsTimerYield\s*\?/u,
    "due PLAY ticks and ready polls must take precedence over the periodic timer yield");
assert.match(schedulerSource,
    /const playTickDueSoon\s*=\s*needsTimerYield\s*&&\s*[\s\S]*?hasPlayTickDueWithin/u,
    "near-due PLAY tick scan must stay off the normal immediate hot path");
assert.match(schedulerSource,
    /const timerYieldSuppressedByPlayTick\s*=\s*playTickDueSoon/u,
    "near-due PLAY ticks must suppress the periodic timer yield");
assert.match(schedulerSource, /idleCallbacks/u);
assert.match(schedulerSource, /POLL_SCHEDULER_IDLE_BACKOFF_MILLIS/u);
assert.match(source, /this\.nextPollDueAt = performance\.now\(\)/u);
assert.match(source, /hasPendingInbound\(\)\s*\{[\s\S]*?this\.buffer\.byteLength/u);
assert.match(source, /playTickActive/u);
assert.match(source, /nextPlayTickDueAt/u);
assert.match(source, /playTickSkippedPeriods/u);
const tickStart = source.indexOf("    servicePlayTick(now = performance.now(),");
const tickEnd = source.indexOf("\n    checkError()", tickStart);
assert.ok(tickStart >= 0 && tickEnd > tickStart,
    "shared play-tick service boundaries changed; inspect before updating this smoke");
const tickSource = source.slice(tickStart, tickEnd);
for (const field of [
    "dueAtMillis: dueAt",
    "tickAtMillis: tickAt",
    "previousGapMillis",
    "schedulerCallbackSequence",
    "trigger: schedulerTrigger",
    "skipPeriods",
]) {
    assert.match(tickSource,
        new RegExp(escapeRegExp(field), "u"),
        `PLAY tick timing sample omitted ${field}`);
}
assert.match(source, /phase:\s*typeof this\.phase/u,
    "PLAY tick timing sample omitted client phase");
assert.match(tickSource,
    /this\.recordPlayTickTiming\(\{[\s\S]*?skippedPeriodsTotal:\s*this\.playTickSkippedPeriods/u,
    "PLAY tick timing sample lost cumulative skip-period context");
assert.doesNotMatch(tickSource, /setInterval\s*\(/u,
    "play ticks must use the shared due-driven scheduler, not per-client intervals");
assert.doesNotMatch(tickSource, /setTimeout\s*\(/u,
    "play-tick service must not create a hidden per-client timer");
assert.match(source, /this\.nextPlayTickDueAt = performance\.now\(\)/u);
assert.match(source, /periodMillis = 50/u);
assert.match(source,
    /Math\.ceil\(\(tickAt - nextDueAt\) \/ periodMillis\)/u,
    "production PLAY tick skip accounting must use ceil from the next due time");
assert.match(source, /hasImmediateInbound\(\)\s*\{[\s\S]*?decodeVarInt\(this\.buffer, 0\)/u,
    "client immediate-readiness must inspect a complete outer frame before waking");
assert.match(source, /frameEnd <= this\.buffer\.byteLength/u,
    "client immediate-readiness must not spin on split/partial frames");
assert.doesNotMatch(schedulerSource, /setInterval\s*\(/u,
    "poll scheduler must not use setInterval");
assert.doesNotMatch(schedulerSource, /process\.nextTick\s*\(/u,
    "poll scheduler must not recurse through nextTick");
// Keep the release thresholds immutable.  A scheduler optimization is not a
// license to loosen the acceptance gate.
assert.match(source,
    /pollGap: Object\.freeze\(\{ p99Millis: 16\.7, p999Millis: 50, maxMillis: 100 \}\)/u);
assert.match(source, /BROWSER_GLOBAL_PUMP_TELEMETRY_FIELDS/u);
for (const field of [
    "pumpAllTurns",
    "pumpAllChannelsVisited",
    "pumpAllBudgetYields",
    "pumpAllMaxTurnMillis",
    "pumpAllMaxChannelsPerTurn",
    "pumpAllLastTurnMillis",
    "pumpAllLastChannelsVisited",
]) {
    assert.match(source, new RegExp(`${field}: Object\\.prototype\\.hasOwnProperty`, "u"),
        `browserRuntimeSnapshot omitted ${field}`);
}
assert.match(source, /browserGlobalPumpTelemetryEvidence/u);
assert.match(source, /BROWSER_GLOBAL_PUMP_MAX_TOTAL_MILLIS = 4/u);
assert.match(channelSource, /MAX_TOTAL_MILLIS_PER_PUMP\s*=\s*4\.0/u,
    "Java global pump aggregate budget marker changed");
// Arrival timing is a bounded, diagnostic-only bridge trace.  Keep these
// source contracts here because this smoke never instantiates TeaVM/Netty:
// a green scheduler model must not silently lose the production handoff
// markers or turn the diagnostic ring into an unbounded payload trace.
assert.match(channelSource,
    /arrivalTimelineEnabled:\s*globalThis\.__gaiusBrowserArrivalTimeline === true/u,
    "arrival timeline must be opt-in at bridge creation");
assert.match(channelSource,
    /private boolean arrivalTimelineTracing;/u,
    "arrival timeline switch must be cached on the Java side");
assert.match(channelSource,
    /if\s*\(arrivalTimelineTracing\)\s*\{[\s\S]*?recordArrivalPumpBoundary/u,
    "default-off pump path must avoid diagnostic bridge calls");
assert.match(channelSource,
    /private static native boolean readArrivalTimelineEnabled\(\)/u,
    "arrival timeline switch read marker is missing");
assert.match(channelSource,
    /arrivalTimeline:\s*\{[\s\S]*?schemaVersion:\s*['"]gaius\.browser-client-arrival-timeline\.v1['"]/u,
    "arrival timeline schema marker is missing");
assert.match(channelSource, /perChannelLimit:\s*64/u,
    "arrival timeline per-channel cap drifted");
assert.match(channelSource, /globalLimit:\s*256/u,
    "arrival timeline global cap drifted");
assert.match(channelSource,
    /entry\.arrivalTimelineEvents\.length\s*>=\s*arrivalTimelinePerChannelLimit/u,
    "arrival timeline per-channel ring is not bounded at write");
assert.match(channelSource,
    /timeline\.events\.length\s*>=\s*arrivalTimelineGlobalLimit/u,
    "arrival timeline global ring is not bounded at write");
assert.match(channelSource, /perChannelDropped:\s*0/u,
    "arrival timeline per-channel overflow counter is missing");
assert.match(channelSource, /dropped:\s*0/u,
    "arrival timeline global overflow counter is missing");
assert.match(channelSource, /strictGatesChanged:\s*false/u,
    "arrival timeline must not change strict gates");
assert.match(channelSource, /wireAtSource:\s*['"]unavailable['"]/u,
    "arrival timeline must declare wireAt unavailable");
assert.match(channelSource, /wireAt:\s*null/u,
    "arrival timeline must not fabricate a server wire timestamp");
assert.match(channelSource,
    /if\s*\(event\.data instanceof ArrayBuffer\)\s*\{[\s\S]*?recordArrivalMessage/u,
    "binary WebSocket onmessage path lost its arrival marker");
assert.match(channelSource,
    /if\s*\(message instanceof ArrayBuffer \|\| ArrayBuffer\.isView\(message\)\)[\s\S]*?recordArrivalMessage/u,
    "binary local-port path lost its arrival marker");
assert.match(channelSource,
    /state\.deliverInbound = function\(entry, buffer\)/u,
    "arrival bridge enqueue marker is missing");
assert.match(channelSource,
    /const arrivalToken = arguments\.length > 2 \? arguments\[2\] : null;/u,
    "arrival token was dropped before bridge enqueue");
assert.match(channelSource, /entry\.arrivalInboundMeta\.push\(/u,
    "arrival metadata was not carried through slice admission");
assert.match(channelSource,
    /entry\.pendingInbound\.push\(frame\)[\s\S]*?arrivalTimelineRecord\(entry,\s*['"]bridge-enqueue['"]/u,
    "bridge enqueue marker is not after pending-frame admission");
assert.match(channelSource,
    /state\.pollInboundScheduled = function\(id, requestFlush\)[\s\S]*?arrivalTimelineRecord\(entry,\s*['"]bridge-dequeue['"]/u,
    "bridge dequeue marker is not tied to poll dequeue");
assert.match(channelSource,
    /recordArrivalPumpBoundary\(socketId,\s*['"]pump-begin['"]/u,
    "Java pump-begin handoff marker is missing");
assert.match(channelSource,
    /recordArrivalPumpBoundary\([\s\S]*?socketId,\s*['"]pipeline-handoff['"]/u,
    "Java pipeline handoff marker is missing");
assert.match(channelSource,
    /recordArrivalPumpBoundary\([\s\S]*?socketId,\s*['"]pipeline-complete['"]/u,
    "Java pipeline-complete marker is missing");
assert.match(channelSource,
    /recordArrivalPumpBoundary\([\s\S]*?socketId,\s*['"]pump-end['"]/u,
    "Java pump-end handoff marker is missing");
assert.match(channelSource,
    /private static native void recordArrivalPumpBoundary\(/u,
    "arrival pump bridge method is missing");
assert.match(channelSource, /function arrivalTimelineDelta\(/u,
    "arrival timeline clock-delta helper is missing");
assert.match(channelSource, /clockAnomaly:/u,
    "arrival timeline clock anomaly field is missing");

const CLIENT_COUNT = 8;
const BATCH_LIMIT = 4;
const CALLBACK_WORK_BUDGET_MILLIS = 8;
const RUN_MILLIS = 180;
const HARD_STOP_MILLIS = RUN_MILLIS + 500;

function round(value) {
    return Number(Number(value).toFixed(3));
}

function eventLoopEvidence(histogram) {
    const toMillis = (value) => {
        const number = Number(value);
        return Number.isFinite(number) && number > 0 ? round(number / 1e6) : 0;
    };
    return {
        samples: Number(histogram.count) || 0,
        p99Millis: toMillis(histogram.percentile(99)),
        maxMillis: toMillis(histogram.max),
    };
}

function busyWait(millis) {
    const duration = Math.max(0, Number(millis) || 0);
    if (duration === 0) return;
    const deadline = performance.now() + duration;
    while (performance.now() < deadline) {
        // Deliberately synchronous: model inflate/parse work inside one
        // client.poll() without involving a server or socket.
    }
}

/**
 * Run a tiny synthetic eight-client poll loop.  `hybrid` mirrors the
 * production harness policy: setImmediate is the normal continuation and a
 * zero-delay timer is inserted every 256 turns.  Each callback also observes
 * the production 8 ms aggregate work budget before admitting another client.
 * The other candidates are
 * diagnostic baselines only; their numbers are never used to relax a gate.
 */
async function runCandidate(kind, workMillis = 0) {
    const counts = Array(CLIENT_COUNT).fill(0);
    const maximumGaps = Array(CLIENT_COUNT).fill(0);
    let cursor = 0;
    let callbacks = 0;
    let immediateCallbacks = 0;
    let timerCallbacks = 0;
    let timerYields = 0;
    let overlaps = 0;
    let running = false;
    let scheduledHandle;
    let scheduledKind;
    let turnsSinceTimer = 0;
    let lastCallbackAt;
    const lastPollAt = Array(CLIENT_COUNT).fill(undefined);
    const startedAt = performance.now();
    const histogram = monitorEventLoopDelay({ resolution: 10 });
    histogram.enable();
    let heartbeatSamples = 0;
    let heartbeatMaxGap = 0;
    let lastHeartbeatAt;
    const heartbeat = setInterval(() => {
        const now = performance.now();
        if (lastHeartbeatAt !== undefined) {
            heartbeatMaxGap = Math.max(heartbeatMaxGap, now - lastHeartbeatAt);
        }
        lastHeartbeatAt = now;
        heartbeatSamples++;
    }, 10);
    heartbeat.unref?.();

    return await new Promise((resolve) => {
        let stopped = false;
        const clearScheduled = () => {
            if (scheduledHandle === undefined) return;
            if (scheduledKind === "immediate") clearImmediate(scheduledHandle);
            else clearTimeout(scheduledHandle);
            scheduledHandle = undefined;
            scheduledKind = undefined;
        };
        const finish = () => {
            if (stopped) return;
            stopped = true;
            clearScheduled();
            clearInterval(heartbeat);
            histogram.disable();
            resolve({
                kind,
                workMillis,
                elapsedMillis: round(performance.now() - startedAt),
                callbacks,
                immediateCallbacks,
                timerCallbacks,
                timerYields,
                overlaps,
                counts,
                dispatchSkew: Math.max(...counts) - Math.min(...counts),
                maxPollGapsMillis: maximumGaps.map(round),
                maxPollGapMillis: round(Math.max(...maximumGaps)),
                heartbeatSamples,
                heartbeatMaxGapMillis: round(heartbeatMaxGap),
                eventLoopDelay: eventLoopEvidence(histogram),
            });
        };
        const schedule = (mode) => {
            if (stopped || scheduledHandle !== undefined) return;
            if (mode === "immediate") {
                scheduledKind = "immediate";
                scheduledHandle = setImmediate(() => {
                    scheduledHandle = undefined;
                    scheduledKind = undefined;
                    immediateCallbacks++;
                    run("immediate");
                });
            }
            else {
                scheduledKind = "timer";
                scheduledHandle = setTimeout(() => {
                    scheduledHandle = undefined;
                    scheduledKind = undefined;
                    timerCallbacks++;
                    if (mode === "timer-yield") timerYields++;
                    run(mode);
                }, mode === "timer-1" ? 1 : 0);
            }
        };
        const run = (trigger) => {
            if (stopped) return;
            if (running) {
                overlaps++;
                return;
            }
            running = true;
            const callbackStartedAt = performance.now();
            if (lastCallbackAt !== undefined) {
                // Keep the variable live for parity with the production
                // scheduler's inter-callback evidence.
                void (callbackStartedAt - lastCallbackAt);
            }
            lastCallbackAt = callbackStartedAt;
            callbacks++;
            const dispatchLimit = Math.min(BATCH_LIMIT, CLIENT_COUNT);
            for (let index = 0; index < dispatchLimit; index++) {
                if (index > 0 && performance.now() - callbackStartedAt >=
                    CALLBACK_WORK_BUDGET_MILLIS) break;
                const client = cursor;
                cursor = (cursor + 1) % CLIENT_COUNT;
                counts[client]++;
                busyWait(workMillis);
                const pollAt = performance.now();
                if (lastPollAt[client] !== undefined) {
                    maximumGaps[client] = Math.max(
                        maximumGaps[client], pollAt - lastPollAt[client]);
                }
                lastPollAt[client] = pollAt;
                if (performance.now() - callbackStartedAt >= CALLBACK_WORK_BUDGET_MILLIS) break;
            }
            running = false;
            if (performance.now() - startedAt >= RUN_MILLIS || callbacks >= 100_000) {
                finish();
                return;
            }
            if (trigger === "timer" || trigger === "timer-yield" || trigger === "timer-1") {
                turnsSinceTimer = 0;
            }
            else {
                turnsSinceTimer++;
            }
            if (kind === "hybrid") {
                const timerYield = turnsSinceTimer >= 256;
                if (timerYield) turnsSinceTimer = 0;
                schedule(timerYield ? "timer-yield" : "immediate");
            }
            else {
                schedule(kind === "immediate" ? "immediate" : kind);
            }
        };
        // Give every candidate a real asynchronous first turn.  This also
        // exercises the Windows zero-delay timer path in the hybrid policy.
        schedule(kind === "hybrid" ? "timer-yield" : kind);
        setTimeout(finish, HARD_STOP_MILLIS).unref?.();
    });
}

// Deterministic model of the production due/readiness filter.  It deliberately
// keeps the same four-client cap and cursor advancement as the real scheduler,
// but uses plain objects so the regression is server-free and repeatable.
function modelDispatch(clients, cursor, now, batchLimit = BATCH_LIMIT) {
    if (clients.length === 0) return {
        ids: [], cursor: 0, idle: true, inspected: 0, fairnessSkips: 0,
    };
    let nextCursor = cursor >= clients.length ? 0 : cursor;
    const visited = new Set();
    const ids = [];
    let inspected = 0;
    let fairnessSkips = 0;
    const live = clients.filter((client) => client !== undefined &&
        !client.closed && !client.pollingPaused && !client.failure);
    const fairnessFloor = live.length === 0 ? 0 : Math.min(...live.map((client) =>
        client.dispatchCount ?? 0));
    while (inspected < clients.length && ids.length < batchLimit) {
        const client = clients[nextCursor];
        nextCursor = (nextCursor + 1) % clients.length;
        inspected++;
        if (client === undefined || visited.has(client.id)) continue;
        visited.add(client.id);
        if (client.closed || client.pollingPaused || client.failure) continue;
        const ready = now >= client.nextPollDueAt;
        if (!ready) continue;
        if ((client.dispatchCount ?? 0) > fairnessFloor) {
            fairnessSkips++;
            continue;
        }
        ids.push(client.id);
        // Model a successful poll. Queued input is observed at the next
        // scheduler-owned due turn rather than bypassing peer fairness.
        client.nextPollDueAt = now + 1;
        client.pendingInbound = false;
        client.dispatchCount = (client.dispatchCount ?? 0) + 1;
    }
    const currentLive = clients.filter((client) => client !== undefined &&
        !client.closed && !client.pollingPaused && !client.failure);
    const currentFloor = currentLive.length === 0 ? 0 : Math.min(...currentLive.map((client) =>
        client.dispatchCount ?? 0));
    const readyAfter = clients.some((client) =>
        client !== undefined && !client.closed && !client.pollingPaused &&
        !client.failure &&
        (client.dispatchCount ?? 0) <= currentFloor &&
        now >= client.nextPollDueAt);
    return {
        ids,
        cursor: nextCursor,
        idle: ids.length === 0 || !readyAfter,
        inspected,
        fairnessSkips,
    };
}

function makeModelClients({ dueAt = 100, pending = [] } = {}) {
    const pendingIds = new Set(pending);
    return Array.from({ length: CLIENT_COUNT }, (_, id) => ({
        id,
        nextPollDueAt: dueAt,
        pendingInbound: pendingIds.has(id),
        closed: false,
        pollingPaused: false,
        failure: undefined,
        dispatchCount: 0,
    }));
}

// Model the bounded BrowserWebSocketChannel phase ring.  The production
// source mutates one array in place (push + splice) once it reaches 256
// entries, so a length-only cursor can permanently skip a newly appended
// relay-connected event.  This model keeps the event-identity cursor and
// publishes each object at most once while still rebuilding on replacement.
const PHASE_RING_MODEL_LIMIT = 256;
function modelRecordPhases(model) {
    const source = Array.isArray(model.stats.connectPhases)
        ? model.stats.connectPhases : [];
    const sourceReplaced = model.phaseScanSource !== source;
    const sourceTruncated = source.length < model.phaseScanIndex;
    const cursorIndex = !sourceReplaced && model.phaseScanCursorEvent !== null
        ? source.indexOf(model.phaseScanCursorEvent) : -1;
    const sourceRolled = !sourceReplaced && !sourceTruncated &&
        model.phaseScanCursorEvent !== null && cursorIndex < 0;
    if (sourceReplaced || sourceTruncated) {
        model.phaseScanSource = source;
        model.phaseScanIndex = 0;
        model.phaseScanCursorEvent = null;
        model.connectPhases = [];
    }
    const scanStart = sourceReplaced || sourceTruncated ? 0
        : sourceRolled ? 0
        : model.phaseScanCursorEvent === null ? model.phaseScanIndex
        : cursorIndex + 1;
    for (let index = scanStart; index < source.length; index++) {
        const event = source[index];
        if (event === null || typeof event !== "object" || event.id !== model.id)
            continue;
        const eventSeen = model.seen.has(event);
        if (!eventSeen) model.seen.add(event);
        if (sourceReplaced || sourceTruncated || !eventSeen)
            model.connectPhases.push(event);
    }
    model.phaseScanSource = source;
    model.phaseScanIndex = source.length;
    model.phaseScanCursorEvent = source.length > 0
        ? source[source.length - 1] : null;
}

// Deterministic model for the near-due idle decision.  A one-millisecond due
// cursor must use a bounded immediate spin so a Windows timer quantum cannot
// manufacture a 15--25 ms poll gap; a far-future cursor still parks on the
// normal idle timer backoff.
function modelIdleContinuation({
    now = 0,
    earliestPollDueAt = Infinity,
    readyAfterDispatch = false,
    dueTicks = 0,
    idleImmediateSpins = 0,
} = {}) {
    if (dueTicks > 0 || readyAfterDispatch) {
        return { continuation: "immediate", idleImmediateSpins: 0 };
    }
    const dueInImmediateWindow = Number.isFinite(earliestPollDueAt) &&
        earliestPollDueAt - now <= 2;
    if (dueInImmediateWindow && idleImmediateSpins < 16) {
        return {
            continuation: "immediate",
            idleImmediateSpins: idleImmediateSpins + 1,
        };
    }
    return { continuation: "idle", idleImmediateSpins: 0 };
}

// Deadline-aware model of the production near-due probe.  The 16-turn limit
// is only a per-burst CPU guard; the 2 ms absolute budget is the real bound.
// Once a burst rolls over, the model must remain on the immediate path while
// the deadline probe is still inside its budget.  If the deadline is already
// overdue when the budget expires, one immediate wake is retained so the due
// client can dispatch instead of falling onto a quantized timer.
function modelDeadlineProbeContinuation({
    now = 0,
    earliestPollDueAt = Infinity,
    idleImmediateSpins = 0,
    probeStartedAt,
    overdueWakePending = false,
    dispatched = 0,
    dueTicks = 0,
    readyAfterDispatch = false,
} = {}) {
    let nextSpins = idleImmediateSpins;
    let nextProbeStartedAt = probeStartedAt;
    let nextOverdueWakePending = overdueWakePending;
    if (dueTicks > 0 || readyAfterDispatch) {
        return {
            continuation: "immediate",
            idleImmediateSpins: 0,
            probeStartedAt: undefined,
            overdueWakePending: false,
        };
    }
    if (overdueWakePending && dispatched === 0) {
        return {
            continuation: "idle",
            idleImmediateSpins: 0,
            probeStartedAt: undefined,
            overdueWakePending: false,
        };
    }
    const dueInWindow = Number.isFinite(earliestPollDueAt) &&
        earliestPollDueAt - now <= 2;
    if (!dueInWindow) {
        return {
            continuation: "idle",
            idleImmediateSpins: 0,
            probeStartedAt: undefined,
            overdueWakePending: false,
        };
    }
    if (!Number.isFinite(nextProbeStartedAt)) {
        nextProbeStartedAt = now;
        nextSpins = 0;
    }
    const elapsed = Math.max(0, now - nextProbeStartedAt);
    if (elapsed < 2) {
        if (nextSpins >= 16) nextSpins = 0;
        return {
            continuation: "immediate",
            idleImmediateSpins: nextSpins + 1,
            probeStartedAt: nextProbeStartedAt,
            overdueWakePending: false,
        };
    }
    if (earliestPollDueAt <= now) {
        nextSpins = 0;
        nextProbeStartedAt = undefined;
        nextOverdueWakePending = true;
        return {
            continuation: "immediate",
            idleImmediateSpins: nextSpins,
            probeStartedAt: nextProbeStartedAt,
            overdueWakePending: nextOverdueWakePending,
        };
    }
    return {
        continuation: "idle",
        idleImmediateSpins: 0,
        probeStartedAt: undefined,
        overdueWakePending: false,
    };
}

const PLAY_TICK_TIMER_YIELD_GUARD_MODEL_MILLIS = 16.7;
function modelHasPlayTickDueWithin(clients, now = 0,
    guardMillis = PLAY_TICK_TIMER_YIELD_GUARD_MODEL_MILLIS) {
    const current = Number(now);
    const guard = Number(guardMillis);
    if (!Number.isFinite(current) || !Number.isFinite(guard) || guard < 0) {
        return false;
    }
    return clients.some((client) => {
        if (client === null || client === undefined || client.closed ||
            client.pollingPaused || client.failure !== undefined ||
            client.phase !== "play" || client.playTickActive === false ||
            client.servicePlayTick !== true) {
            return false;
        }
        if (client.playTickSuspended === true) return true;
        const dueAt = Number(client.nextPlayTickDueAt);
        if (!Number.isFinite(dueAt)) return false;
        const epsilon = Number.EPSILON * Math.max(
            1, Math.abs(current), Math.abs(dueAt), Math.abs(guard)) * 4;
        return dueAt - current <= guard + epsilon;
    });
}

// Model the complete continuation choice made after a scheduler callback has
// unwound.  In particular, readiness is measured in the poll loop but is
// consumed by the outer zero-dispatch finalizer; this model keeps that
// cross-block state explicit instead of only testing the inner deadline
// probe.  The final timer-yield rule remains lower priority than due ticks,
// and this model never changes a strict acceptance gate.
function modelFinalizerContinuation({
    dispatched = 0,
    nextContinuation = "immediate",
    readyAfterDispatch = false,
    dueTicksBeforeIdle = 0,
    dueTicksAfterService = 0,
    idleImmediateSpins = 0,
    idleImmediateProbeStartedAt,
    overdueWakePending = false,
    needsTimerYield = false,
    playTickDueSoon = false,
    playTickClients,
    now = 0,
} = {}) {
    let selectedContinuation = nextContinuation;
    const dueTicksPending = dueTicksAfterService > 0 ||
        dueTicksBeforeIdle > 0;
    const readyPollPending = readyAfterDispatch;
    if (dispatched === 0) {
        if (dueTicksBeforeIdle > 0 || dueTicksAfterService > 0 ||
            readyAfterDispatch) {
            selectedContinuation = "immediate";
        }
        else if (!(selectedContinuation === "immediate" &&
            idleImmediateSpins > 0 &&
            Number.isFinite(idleImmediateProbeStartedAt)) &&
            !overdueWakePending) {
            selectedContinuation = "idle";
        }
    }
    const computedPlayTickDueSoon = Array.isArray(playTickClients)
        ? modelHasPlayTickDueWithin(playTickClients, now)
        : playTickDueSoon;
    const timerYieldSuppressedByPlayTick = needsTimerYield &&
        computedPlayTickDueSoon;
    const continuation = dueTicksPending || readyPollPending ||
        timerYieldSuppressedByPlayTick
        ? "immediate"
        : needsTimerYield ? "timer-yield" : selectedContinuation;
    return {
        continuation,
        selectedContinuation,
        dueTicksPending,
        readyPollPending,
        playTickDueSoon: computedPlayTickDueSoon,
        timerYieldSuppressedByPlayTick,
        strictGatesChanged: false,
    };
}

// Deterministic model for the shared 20 Hz PLAY tick service. It permits one
// send per scheduler turn, preserves the 50 ms cadence, and re-anchors after a
// delayed turn instead of emitting a catch-up burst that would contend with
// inbound parsing.
function modelPlayTick(state, now) {
    if (!state.active || state.closed || state.phase !== "play" ||
        state.paused || state.pollingPaused) return false;
    if (state.playTickSuspended === true) {
        state.playTickSuspended = false;
        state.nextDueAt = now;
    }
    if (!Number.isFinite(Number(state.nextDueAt)) || now < state.nextDueAt) return false;
    const dueAt = state.nextDueAt;
    state.ticks++;
    state.lastTickAt = now;
    const nextDueAt = dueAt + 50;
    if (nextDueAt < now) {
        state.skippedPeriods += Math.max(1, Math.ceil((now - nextDueAt) / 50));
        state.nextDueAt = now + 50;
    }
    else {
        state.nextDueAt = nextDueAt;
    }
    return true;
}

function modelPlayTickBatch(states, cursor, now, limit = BATCH_LIMIT) {
    let nextCursor = states.length === 0 ? 0 : cursor % states.length;
    let serviced = 0;
    let sent = 0;
    for (let attempts = 0;
        attempts < states.length && serviced < Math.min(limit, states.length);
        attempts++) {
        const state = states[nextCursor];
        nextCursor = (nextCursor + 1) % states.length;
        if (state === undefined || state.closed || state.paused ||
            state.pollingPaused || !state.active) continue;
        if (!modelIsPlayTickDue(state, now)) continue;
        serviced++;
        if (modelPlayTick(state, now)) sent++;
    }
    return { cursor: nextCursor, serviced, sent };
}

// The production client keeps the last 64 successful PLAY tick timing records
// per client.  This model checks the bounded chronological retention contract;
// it deliberately has no effect on the cadence or any strict gate.
const PLAY_TICK_TIMING_MODEL_SCHEMA_VERSION =
    "gaius.browser-client-play-tick-timing.v1";
const PLAY_TICK_TIMING_MODEL_LIMIT = 64;
function retainPlayTickTimingSample(state, candidate) {
    const finiteMillis = (value, allowNull = false) => {
        if (allowNull && (value === null || value === undefined)) return null;
        const number = Number(value);
        return Number.isFinite(number) && number >= 0 ? number : null;
    };
    const nonNegativeInteger = (value) => Number.isSafeInteger(value) && value >= 0
        ? value : 0;
    const sample = {
        schemaVersion: PLAY_TICK_TIMING_MODEL_SCHEMA_VERSION,
        sequence: Number.isSafeInteger(candidate?.sequence) ? candidate.sequence : state.total + 1,
        dueAtMillis: finiteMillis(candidate?.dueAtMillis),
        tickAtMillis: finiteMillis(candidate?.tickAtMillis),
        previousGapMillis: finiteMillis(candidate?.previousGapMillis, true),
        schedulerCallbackSequence: Number.isSafeInteger(
            candidate?.schedulerCallbackSequence) ? candidate.schedulerCallbackSequence : null,
        trigger: typeof candidate?.trigger === "string" ? candidate.trigger : "direct",
        skipPeriods: nonNegativeInteger(candidate?.skipPeriods),
        phase: typeof candidate?.phase === "string" ? candidate.phase : null,
    };
    state.total++;
    if (state.samples[state.nextIndex] !== undefined) state.dropped++;
    state.samples[state.nextIndex] = sample;
    state.nextIndex = (state.nextIndex + 1) % PLAY_TICK_TIMING_MODEL_LIMIT;
    state.count = Math.min(PLAY_TICK_TIMING_MODEL_LIMIT, state.count + 1);
}

function orderedPlayTickTimingSamples(state) {
    const firstIndex = state.count < PLAY_TICK_TIMING_MODEL_LIMIT
        ? 0 : state.nextIndex;
    return Array.from({ length: state.count }, (_, offset) =>
        state.samples[(firstIndex + offset) % PLAY_TICK_TIMING_MODEL_LIMIT]);
}

// Model the production largest-gap ring used to retain callback provenance for
// strict-threshold tick overruns.  It is deliberately separate from the
// chronological ring and never participates in continuation or gate decisions.
function retainPlayTickSlowSampleModel(state, candidate) {
    const gap = Number(candidate?.previousGapMillis);
    if (!Number.isFinite(gap) || gap < 60) return false;
    state.total++;
    state.samples.push({
        ...candidate,
        slowThresholdMillis: 60,
        diagnosticOnly: true,
        strictGatesChanged: false,
        independentExecution: false,
    });
    state.samples.sort((left, right) =>
        Number(right.previousGapMillis) - Number(left.previousGapMillis) ||
        left.sequence - right.sequence);
    if (state.samples.length > 64) {
        state.samples.length = 64;
        state.dropped++;
    }
    return true;
}

function modelIsPlayTickDue(state, now) {
    if (state === undefined || state === null || state.closed || state.paused ||
        state.pollingPaused ||
        state.failure !== undefined || state.phase !== "play" ||
        state.active !== true) return false;
    if (state.playTickSuspended === true) return true;
    const dueAt = Number(state.nextDueAt);
    const current = Number(now);
    return Number.isFinite(dueAt) && Number.isFinite(current) && current >= dueAt;
}

function modelCountDuePlayTicks(states, now) {
    return states.reduce((count, state) =>
        count + (modelIsPlayTickDue(state, now) ? 1 : 0), 0);
}

// Bounded callback-tail retention model.  Only callbacks at/above the strict
// 16.7 ms frame threshold enter the diagnostic ring; the strict gate itself
// remains a separate assertion in browser-full-path-stress.mjs.
function retainCallbackTailSample(state, sample) {
    if (sample.durationRawMillis < 16.7) return false;
    state.total++;
    state.samples.push(sample);
    state.samples.sort((left, right) =>
        right.durationRawMillis - left.durationRawMillis ||
        left.callbackSequence - right.callbackSequence);
    if (state.samples.length > 64) {
        state.samples.length = 64;
        state.dropped++;
    }
    return true;
}

// Finalization-tail model mirrors the production diagnostic ring.  It is
// intentionally separate from callbackDurationRawMillis: a slow finalizer is
// evidence for attribution, never a replacement for the strict callback gate.
function retainCallbackFinalizationTailSample(state, sample) {
    const tail = Number(sample?.tailRawMillis);
    if (!Number.isFinite(tail) || tail < 16.7) return false;
    state.total++;
    state.samples.push({
        ...sample,
        tailRawMillis: tail,
        totalAfterFinalizeRawMillis: Number(
            sample?.totalAfterFinalizeRawMillis) >= 0
            ? Number(sample.totalAfterFinalizeRawMillis) : 0,
    });
    state.samples.sort((left, right) =>
        right.tailRawMillis - left.tailRawMillis ||
        left.callbackSequence - right.callbackSequence);
    if (state.samples.length > 64) {
        state.samples.length = 64;
        state.dropped++;
    }
    return true;
}

{
    const tail = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainCallbackTailSample(tail, {
        callbackSequence: 1, durationRawMillis: 16.699,
    }), false, "fast callback entered the slow-tail ring");
    assert.deepEqual(tail, { total: 0, dropped: 0, samples: [] },
        "fast callback changed slow-tail counters");
    for (let sequence = 1; sequence <= 65; sequence++) {
        retainCallbackTailSample(tail, {
            callbackSequence: sequence,
            durationRawMillis: 16.7 + sequence / 1000,
        });
    }
    assert.equal(tail.total, 65, "slow-tail total did not count every candidate");
    assert.equal(tail.samples.length, 64, "slow-tail ring exceeded its hard limit");
    assert.equal(tail.dropped, 1, "slow-tail dropped count did not track overflow");
    assert.equal(tail.total, tail.samples.length + tail.dropped,
        "slow-tail total/retained/dropped accounting diverged");
    assert.ok(tail.samples.every((sample) => sample.durationRawMillis >= 16.7),
        "slow-tail ring retained a sub-threshold sample");
    for (let index = 1; index < tail.samples.length; index++) {
        const previous = tail.samples[index - 1];
        const current = tail.samples[index];
        assert.ok(previous.durationRawMillis > current.durationRawMillis ||
            previous.durationRawMillis === current.durationRawMillis &&
                previous.callbackSequence <= current.callbackSequence,
        "slow-tail ring ordering was not duration-desc/sequence-asc");
    }
    const phaseTimings = {
        beforeTickLoop: 0.3,
        afterTickLoop: 2.1,
        beforePollLoop: 2.1,
        afterPollLoop: 8.4,
        beforeFinalEvidence: 9.0,
        afterFinalEvidence: 16.8,
    };
    const phaseValues = Object.values(phaseTimings);
    assert.ok(phaseValues.every((value, index) => index === 0 ||
        value >= phaseValues[index - 1]),
    "callback-tail phase timings were not monotonic");
}

{
    const tail = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainCallbackFinalizationTailSample(tail, {
        callbackSequence: 1, tailRawMillis: 16.699,
    }), false, "fast finalization tail entered the slow-tail ring");
    assert.equal(retainCallbackFinalizationTailSample(tail, {
        callbackSequence: 2, tailRawMillis: Number.NaN,
    }), false, "non-finite finalization tail entered the slow-tail ring");
    assert.deepEqual(tail, { total: 0, dropped: 0, samples: [] },
        "fast finalization tail changed diagnostic counters");
    for (let sequence = 1; sequence <= 65; sequence++) {
        retainCallbackFinalizationTailSample(tail, {
            schemaVersion:
                "gaius.browser-client-poll-callback-finalization-tail.v1",
            callbackSequence: sequence,
            finalizeStartAtMillis: sequence,
            finalizeFinishAtMillis: sequence + 17,
            tailRawMillis: 16.7 + sequence / 1000,
            totalAfterFinalizeRawMillis: 20 + sequence / 1000,
            callbackDurationRawMillis: 12,
        });
    }
    assert.equal(tail.total, 65,
        "finalization-tail total did not count every candidate");
    assert.equal(tail.samples.length, 64,
        "finalization-tail ring exceeded its hard limit");
    assert.equal(tail.dropped, 1,
        "finalization-tail dropped count did not track overflow");
    assert.equal(tail.total, tail.samples.length + tail.dropped,
        "finalization-tail total/retained/dropped accounting diverged");
    for (let index = 0; index < tail.samples.length; index++) {
        const sample = tail.samples[index];
        assert.ok(Number.isFinite(sample.tailRawMillis) &&
            sample.tailRawMillis >= 16.7,
        "finalization-tail ring retained an invalid sample");
        assert.ok(Number.isFinite(sample.totalAfterFinalizeRawMillis) &&
            sample.totalAfterFinalizeRawMillis >= 0,
        "finalization-tail total endpoint was not finite");
        if (index > 0) {
            const previous = tail.samples[index - 1];
            assert.ok(previous.tailRawMillis > sample.tailRawMillis ||
                previous.tailRawMillis === sample.tailRawMillis &&
                    previous.callbackSequence <= sample.callbackSequence,
            "finalization-tail ring ordering drifted");
        }
    }
}

// Scheduler-gap model: retain split callback-boundary evidence without
// changing the strict callback gate.  The model mirrors the production
// largest-gap/duration ordering and its bounded 64-entry diagnostic ring.
const SCHEDULER_GAP_MODEL_LIMIT = 64;
function clampSchedulerGapModel(value) {
    const number = Number(value);
    return {
        millis: Number.isFinite(number) ? Math.max(0, number) : 0,
        clockAnomaly: !Number.isFinite(number) || number < 0,
    };
}

function retainSchedulerGapModel(state, candidate) {
    const idle = clampSchedulerGapModel(candidate?.interCallbackIdleGapRawMillis);
    const startToStart = clampSchedulerGapModel(candidate?.interCallbackGapRawMillis);
    const scheduled = clampSchedulerGapModel(candidate?.scheduledDelayRawMillis);
    const duration = clampSchedulerGapModel(candidate?.callbackDurationRawMillis);
    const triggerKinds = [];
    if (idle.millis >= 250 || startToStart.millis >= 250) {
        triggerKinds.push("inter-callback-gap");
    }
    if (scheduled.millis >= 250) triggerKinds.push("scheduled-delay");
    if (duration.millis >= 16.7) triggerKinds.push("callback-duration");
    if (candidate?.budgetReached === true) triggerKinds.push("budget-reached");
    if (triggerKinds.length === 0) return false;
    state.total++;
    state.samples.push({
        callbackSequence: Number.isSafeInteger(candidate?.callbackSequence)
            ? candidate.callbackSequence : state.total,
        interCallbackIdleGapRawMillis: idle.millis,
        interCallbackGapRawMillis: startToStart.millis,
        scheduledDelayRawMillis: scheduled.millis,
        callbackDurationRawMillis: duration.millis,
        triggerKinds,
        clockAnomaly: idle.clockAnomaly || startToStart.clockAnomaly ||
            scheduled.clockAnomaly || duration.clockAnomaly,
    });
    state.samples.sort((left, right) =>
        right.interCallbackIdleGapRawMillis - left.interCallbackIdleGapRawMillis ||
        right.callbackDurationRawMillis - left.callbackDurationRawMillis ||
        left.callbackSequence - right.callbackSequence);
    if (state.samples.length > SCHEDULER_GAP_MODEL_LIMIT) {
        state.samples.length = SCHEDULER_GAP_MODEL_LIMIT;
        state.dropped++;
    }
    return true;
}

// Scheduler segment model: diagnostic-only attribution for time spent around
// candidate admission/readiness/fairness, post-dispatch work, evidence, and
// finalization. It intentionally keeps the largest segment samples rather
// than making any sample eligible to relax the strict callback/tick gates.
const SCHEDULER_SEGMENT_MODEL_LIMIT = 64;
const SCHEDULER_SEGMENT_MODEL_THRESHOLD_MILLIS = 16.7;
function retainSchedulerSegmentModel(state, candidate) {
    const segmentFields = [
        "admissionRawMillis",
        "readinessRawMillis",
        "fairnessRawMillis",
        "postDispatchReadinessRawMillis",
        "postDispatchRawMillis",
        "evidenceRawMillis",
        "finalizationRawMillis",
        "callbackDurationRawMillis",
    ];
    const values = segmentFields.map((field) => {
        const number = Number(candidate?.[field]);
        return Number.isFinite(number) && number >= 0 ? number : 0;
    });
    const slowestSegmentRawMillis = Math.max(...values);
    if (slowestSegmentRawMillis < SCHEDULER_SEGMENT_MODEL_THRESHOLD_MILLIS) {
        return false;
    }
    state.total++;
    state.samples.push({
        callbackSequence: Number.isSafeInteger(candidate?.callbackSequence)
            ? candidate.callbackSequence : state.total,
        slowestSegmentRawMillis,
    });
    state.samples.sort((left, right) =>
        right.slowestSegmentRawMillis - left.slowestSegmentRawMillis ||
        left.callbackSequence - right.callbackSequence);
    if (state.samples.length > SCHEDULER_SEGMENT_MODEL_LIMIT) {
        state.samples.length = SCHEDULER_SEGMENT_MODEL_LIMIT;
        state.dropped++;
    }
    return true;
}

function createArrivalTraceModel() {
    return {
        normal: new Array(64),
        forced: new Array(64),
        normalNext: 0,
        forcedNext: 0,
        normalDropped: 0,
        forcedDropped: 0,
    };
}

function recordArrivalTraceModel(state, event, force = false) {
    const record = { ...event, forceRetained: force };
    const normalIndex = state.normalNext;
    if (state.normal[normalIndex] !== undefined) state.normalDropped++;
    state.normal[normalIndex] = record;
    state.normalNext = (normalIndex + 1) % 64;
    if (force) {
        const forcedIndex = state.forcedNext;
        if (state.forced[forcedIndex] !== undefined) state.forcedDropped++;
        state.forced[forcedIndex] = record;
        state.forcedNext = (forcedIndex + 1) % 64;
    }
}

function prioritizedArrivalTraceModel(state) {
    const unique = [];
    const seen = new Set();
    for (const event of [...state.normal, ...state.forced]) {
        if (event === undefined || seen.has(event)) continue;
        seen.add(event);
        unique.push(event);
    }
    const forcedBoundary = unique.filter((event) =>
        event.kind === "arrival-gap-boundary" && event.forceRetained === true);
    const forced = unique.filter((event) =>
        event.forceRetained === true && event.kind !== "arrival-gap-boundary");
    const ordinary = unique.filter((event) => event.forceRetained !== true);
    return [...forcedBoundary, ...forced, ...ordinary].slice(0, 64).sort((left, right) =>
        left.sequence - right.sequence);
}

{
    const gap = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainSchedulerGapModel(gap, {
        callbackSequence: 1,
        interCallbackIdleGapRawMillis: 0,
        interCallbackGapRawMillis: 0,
        scheduledDelayRawMillis: 300,
        callbackDurationRawMillis: 1,
    }), true, "scheduled callback delay did not enter gap evidence");
    assert.deepEqual(gap.samples[0].triggerKinds, ["scheduled-delay"],
        "scheduled-delay trigger was not retained");
    for (let sequence = 2; sequence <= 65; sequence++) {
        retainSchedulerGapModel(gap, {
            callbackSequence: sequence,
            interCallbackIdleGapRawMillis: 0,
            interCallbackGapRawMillis: 0,
            scheduledDelayRawMillis: 300,
            callbackDurationRawMillis: 1,
        });
    }
    assert.equal(gap.total, 65,
        "scheduler gap total did not count every slow candidate");
    assert.equal(gap.samples.length, SCHEDULER_GAP_MODEL_LIMIT,
        "scheduler gap ring exceeded its hard limit");
    assert.equal(gap.dropped, 1,
        "scheduler gap dropped count did not track overflow");
    const anomaly = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainSchedulerGapModel(anomaly, {
        callbackSequence: 1,
        interCallbackIdleGapRawMillis: -4,
        interCallbackGapRawMillis: -3,
        scheduledDelayRawMillis: 0,
        callbackDurationRawMillis: 20,
    }), true, "clock-anomaly callback was not retained");
    assert.equal(anomaly.samples[0].interCallbackIdleGapRawMillis, 0,
        "negative idle gap was not clamped");
    assert.equal(anomaly.samples[0].interCallbackGapRawMillis, 0,
        "negative start-to-start gap was not clamped");
    assert.equal(anomaly.samples[0].clockAnomaly, true,
        "negative callback clock was not marked anomalous");

    const trace = createArrivalTraceModel();
    recordArrivalTraceModel(trace, {
        sequence: 1, kind: "arrival-gap-boundary",
    }, true);
    for (let sequence = 2; sequence <= 66; sequence++) {
        recordArrivalTraceModel(trace, { sequence, kind: "decode-end" });
    }
    const prioritized = prioritizedArrivalTraceModel(trace);
    assert.ok(prioritized.some((event) =>
        event.kind === "arrival-gap-boundary" && event.forceRetained === true),
    "forced arrival boundary was overwritten by ordinary trace events");
    assert.equal(trace.forcedDropped, 0,
        "forced boundary ring unexpectedly overflowed");
}

{
    const segments = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainSchedulerSegmentModel(segments, {
        callbackSequence: 1,
        callbackDurationRawMillis: 16.699,
    }), false, "fast scheduler segment entered the slow ring");
    assert.deepEqual(segments, { total: 0, dropped: 0, samples: [] },
        "fast scheduler segment changed diagnostic counters");
    for (let sequence = 1; sequence <= 65; sequence++) {
        retainSchedulerSegmentModel(segments, {
            callbackSequence: sequence,
            admissionRawMillis: sequence / 1000,
            readinessRawMillis: 0,
            fairnessRawMillis: 0,
            postDispatchReadinessRawMillis: 0,
            postDispatchRawMillis: 0,
            evidenceRawMillis: 0,
            finalizationRawMillis: 0,
            callbackDurationRawMillis: 16.7 + sequence / 1000,
        });
    }
    assert.equal(segments.total, 65,
        "scheduler segment total did not count every slow candidate");
    assert.equal(segments.samples.length, SCHEDULER_SEGMENT_MODEL_LIMIT,
        "scheduler segment ring exceeded its hard limit");
    assert.equal(segments.dropped, 1,
        "scheduler segment dropped count did not track overflow");
    assert.equal(segments.total,
        segments.samples.length + segments.dropped,
        "scheduler segment total/retained/dropped accounting diverged");
    for (let index = 1; index < segments.samples.length; index++) {
        const previous = segments.samples[index - 1];
        const current = segments.samples[index];
        assert.ok(previous.slowestSegmentRawMillis >
            current.slowestSegmentRawMillis ||
            previous.slowestSegmentRawMillis === current.slowestSegmentRawMillis &&
                previous.callbackSequence <= current.callbackSequence,
        "scheduler segment ring ordering drifted");
    }
}

// Poll-phase diagnostic model.  The production client may parse/inflate a
// bounded burst of frames synchronously, so callback-tail evidence must retain
// one bounded sample per slow poll rather than infer phases from packet-arrival
// gaps.  This model is intentionally independent of the strict gate: it only
// checks shape, clamping, and deterministic retention policy.
const POLL_PHASE_SCHEMA_VERSION = "gaius.browser-client-poll-phase.v1";
const POLL_PHASE_THRESHOLD_MILLIS = 16.7;
const POLL_PHASE_LIMIT = 64;
const POLL_PHASE_FRAME_LIMIT = 8;
const POLL_PHASE_PACKET_LIMIT = 64;
const POLL_PHASE_PACKET_ID_LIMIT = 8;
const POLL_PHASE_SEGMENT_ACCOUNTING = "inclusive-overlapping";
const POLL_PHASE_SEGMENTS = Object.freeze([
    "preludeMillis",
    "checkErrorMillis",
    "recordPhasesMillis",
    "prefixParseMillis",
    "bridgeDrainMillis",
    "bridgePollMillis",
    "decryptMillis",
    "concatMillis",
    "parseMillis",
    "inflateMillis",
    "handlerMillis",
    "finalizeMillis",
]);

const ARRIVAL_PERIODIC_SERVER_SYNC_SCHEMA_VERSION =
    "gaius.browser-client-arrival-periodic-server-sync.v1";
const ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION = "periodic-server-sync";
const ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID = CANONICAL_PROFILE.id;
const ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION = 776;
const ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID = 113;
const ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS = 1000;
const ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS = 125;

function classifyPeriodicServerSyncArrival({
    profileId,
    protocolVersion,
    packetId,
    decodedGapMillis,
    phaseAtDecode = "play",
    localSegments = [],
}) {
    const localSlowSegment = localSegments.some((value) =>
        Number.isFinite(value) && value >= 250);
    const periodic = profileId === ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID &&
        protocolVersion === ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION &&
        packetId === ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID &&
        phaseAtDecode === "play" &&
        !localSlowSegment &&
        Number.isFinite(decodedGapMillis) &&
        Math.abs(decodedGapMillis - ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS) <=
            ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS;
    return periodic
        ? ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION
        : "unknown-arrival-gap";
}

function clampPollSegment(startAt, endAt) {
    const start = Number(startAt);
    const end = Number(endAt);
    if (!Number.isFinite(start) || !Number.isFinite(end)) {
        return { millis: 0, clockAnomaly: true };
    }
    const millis = end - start;
    if (!Number.isFinite(millis) || millis < 0) {
        return { millis: 0, clockAnomaly: true };
    }
    return { millis, clockAnomaly: false };
}

function boundedInteger(value, maximum) {
    const number = Number(value);
    if (!Number.isFinite(number)) return 0;
    return Math.min(maximum, Math.max(0, Math.trunc(number)));
}

function normalizePollPhaseSample(candidate) {
    const durationRawMillis = Number(candidate?.durationRawMillis);
    const duration = Number.isFinite(durationRawMillis) && durationRawMillis >= 0
        ? durationRawMillis : 0;
    const rawSegments = candidate?.segments ?? {};
    const segments = Object.fromEntries(POLL_PHASE_SEGMENTS.map((name) => {
        const value = Number(rawSegments[name]);
        return [name, Number.isFinite(value) && value >= 0
            ? Math.min(duration, value) : 0];
    }));
    const rawWork = candidate?.work ?? {};
    const rawPacketIds = Array.isArray(candidate?.slowPacketIds)
        ? candidate.slowPacketIds : [];
    const clockAnomaly = candidate?.clockAnomaly === true ||
        POLL_PHASE_SEGMENTS.some((name) => {
            const value = Number(rawSegments[name]);
            return Number.isFinite(value) && value < 0;
        });
    return {
        schemaVersion: POLL_PHASE_SCHEMA_VERSION,
        segmentAccounting: POLL_PHASE_SEGMENT_ACCOUNTING,
        pollSequence: Number.isSafeInteger(candidate?.pollSequence)
            ? candidate.pollSequence : 0,
        schedulerCallbackSequence: Number.isSafeInteger(
            candidate?.schedulerCallbackSequence)
            ? candidate.schedulerCallbackSequence : null,
        schedulerTrigger: typeof candidate?.schedulerTrigger === "string"
            ? candidate.schedulerTrigger : null,
        durationRawMillis: duration,
        durationMillis: Number(duration.toFixed(3)),
        strictThresholdMillis: POLL_PHASE_THRESHOLD_MILLIS,
        strictGatesChanged: false,
        independentExecution: false,
        diagnosticOnly: true,
        clockAnomaly,
        outcome: candidate?.outcome === "error" ? "error" : "ok",
        trigger: candidate?.trigger ?? "poll-duration",
        segments,
        work: {
            frames: boundedInteger(rawWork.frames, POLL_PHASE_FRAME_LIMIT),
            packetsParsed: boundedInteger(rawWork.packetsParsed,
                POLL_PHASE_PACKET_LIMIT),
            bridgePollCalls: boundedInteger(rawWork.bridgePollCalls,
                POLL_PHASE_FRAME_LIMIT),
            frameBytes: boundedInteger(rawWork.frameBytes, Number.MAX_SAFE_INTEGER),
            compressedPackets: boundedInteger(rawWork.compressedPackets,
                POLL_PHASE_PACKET_LIMIT),
        },
        slowPacketIds: rawPacketIds.slice(0, POLL_PHASE_PACKET_ID_LIMIT),
    };
}

function classifyPollPhase(sample) {
    const candidates = [
        ["bridge-drain", sample?.segments?.bridgeDrainMillis],
        ["decrypt-transform", sample?.segments?.decryptMillis],
        ["buffer-concat", sample?.segments?.concatMillis],
        ["parse-inflate", Math.max(
            Number(sample?.segments?.parseMillis) || 0,
            Number(sample?.segments?.inflateMillis) || 0)],
        ["packet-dispatch", sample?.segments?.handlerMillis],
        ["prelude", Math.max(
            Number(sample?.segments?.preludeMillis) || 0,
            Number(sample?.segments?.checkErrorMillis) || 0,
            Number(sample?.segments?.recordPhasesMillis) || 0)],
        ["finalize", sample?.segments?.finalizeMillis],
    ];
    let winner = "poll-duration";
    let maximum = 0;
    for (const [name, value] of candidates) {
        const duration = Number(value);
        if (Number.isFinite(duration) && duration > maximum) {
            winner = name;
            maximum = duration;
        }
    }
    return maximum >= POLL_PHASE_THRESHOLD_MILLIS ? winner : "poll-duration";
}

function retainPollPhaseSample(state, candidate) {
    const duration = Number(candidate?.durationRawMillis);
    if (!Number.isFinite(duration) || duration < POLL_PHASE_THRESHOLD_MILLIS) {
        return false;
    }
    state.total++;
    state.samples.push(normalizePollPhaseSample(candidate));
    state.samples.sort((left, right) =>
        right.durationRawMillis - left.durationRawMillis ||
        left.pollSequence - right.pollSequence);
    if (state.samples.length > POLL_PHASE_LIMIT) {
        state.samples.length = POLL_PHASE_LIMIT;
        state.dropped++;
    }
    return true;
}

{
    const fast = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainPollPhaseSample(fast, {
        pollSequence: 1, durationRawMillis: 16.699,
    }), false, "sub-threshold poll entered diagnostic ring");
    assert.deepEqual(fast, { total: 0, dropped: 0, samples: [] },
        "sub-threshold poll changed diagnostic counters");

    const forward = clampPollSegment(10, 12.5);
    assert.equal(forward.clockAnomaly, false,
        "normal poll segment was marked as a clock anomaly");
    assert.equal(forward.millis, 2.5,
        "normal poll segment duration was not preserved");
    const reversed = clampPollSegment(12.5, 10);
    assert.equal(reversed.millis, 0,
        "negative poll segment was not clamped to zero");
    assert.equal(reversed.clockAnomaly, true,
        "negative poll segment did not expose a clock anomaly");
    const invalid = clampPollSegment("not-a-time", 10);
    assert.equal(invalid.clockAnomaly, true,
        "non-finite poll segment did not expose a clock anomaly");

    const ring = { total: 0, dropped: 0, samples: [] };
    for (let sequence = 1; sequence <= 65; sequence++) {
        retainPollPhaseSample(ring, {
            pollSequence: sequence,
            durationRawMillis: 16.7 + sequence / 1000,
            segments: { parseMillis: sequence / 1000 },
            work: { frames: 8, packetsParsed: 64, bridgePollCalls: 8 },
        });
    }
    assert.equal(ring.total, 65,
        "poll phase total did not count every slow candidate");
    assert.equal(ring.samples.length, POLL_PHASE_LIMIT,
        "poll phase ring exceeded its hard limit");
    assert.equal(ring.dropped, 1,
        "poll phase dropped count did not track overflow");
    assert.equal(ring.total, ring.samples.length + ring.dropped,
        "poll phase total/retained/dropped accounting diverged");
    for (let index = 1; index < ring.samples.length; index++) {
        const previous = ring.samples[index - 1];
        const current = ring.samples[index];
        assert.ok(previous.durationRawMillis > current.durationRawMillis ||
            previous.durationRawMillis === current.durationRawMillis &&
                previous.pollSequence <= current.pollSequence,
        "poll phase ring ordering was not duration-desc/sequence-asc");
    }
    assert.ok(ring.samples.every((sample) =>
        sample.work.frames <= POLL_PHASE_FRAME_LIMIT &&
        sample.work.packetsParsed <= POLL_PHASE_PACKET_LIMIT &&
        sample.work.bridgePollCalls <= POLL_PHASE_FRAME_LIMIT),
    "poll phase frame/packet work exceeded bounded caps");

    const tie = { total: 0, dropped: 0, samples: [] };
    retainPollPhaseSample(tie, { pollSequence: 9, durationRawMillis: 20 });
    retainPollPhaseSample(tie, { pollSequence: 3, durationRawMillis: 20 });
    assert.deepEqual(tie.samples.map((sample) => sample.pollSequence), [3, 9],
        "equal-duration poll phases were not sequence ordered");

    const anomaly = normalizePollPhaseSample({
        pollSequence: 7,
        durationRawMillis: 24,
        clockAnomaly: true,
        outcome: "error",
        segments: { parseMillis: -4, handlerMillis: 12, finalizeMillis: 40 },
        work: { frames: 99, packetsParsed: 99, bridgePollCalls: 99 },
        slowPacketIds: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    });
    assert.equal(anomaly.clockAnomaly, true,
        "poll phase clock anomaly marker was lost during normalization");
    assert.equal(anomaly.segmentAccounting, POLL_PHASE_SEGMENT_ACCOUNTING,
        "poll phase segment accounting semantics were lost during normalization");
    assert.equal(anomaly.outcome, "error",
        "poll phase error outcome was lost during normalization");
    assert.equal(anomaly.segments.parseMillis, 0,
        "negative poll phase segment was not clamped");
    assert.equal(anomaly.segments.finalizeMillis, 24,
        "poll phase segment was not bounded by poll duration");
    assert.equal(anomaly.work.frames, POLL_PHASE_FRAME_LIMIT,
        "poll phase frame count escaped its cap");
    assert.equal(anomaly.work.packetsParsed, POLL_PHASE_PACKET_LIMIT,
        "poll phase packet count escaped its cap");
    assert.equal(anomaly.slowPacketIds.length, POLL_PHASE_PACKET_ID_LIMIT,
        "poll phase packet-id detail escaped its cap");
    assert.equal(classifyPollPhase({
        segments: { bridgeDrainMillis: 18, parseMillis: 17 },
    }), "bridge-drain", "bridge phase trigger classification drifted");
    assert.equal(classifyPollPhase({
        segments: { parseMillis: 20, inflateMillis: 19 },
    }), "parse-inflate", "parse phase trigger classification drifted");
    assert.equal(classifyPollPhase({
        segments: { handlerMillis: 19 },
    }), "packet-dispatch", "handler phase trigger classification drifted");
    assert.equal(classifyPollPhase({
        segments: { parseMillis: 2 },
    }), "poll-duration", "fast phase incorrectly changed trigger");

    const correlated = normalizePollPhaseSample({
        pollSequence: 11,
        schedulerCallbackSequence: 7,
        schedulerTrigger: "immediate",
        durationRawMillis: 18,
    });
    assert.equal(correlated.schedulerCallbackSequence, 7,
        "scheduler callback provenance was not retained by the model");
    assert.equal(correlated.schedulerTrigger, "immediate",
        "scheduler trigger provenance was not retained by the model");

    const empty = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainPollPhaseSample(empty, {
        pollSequence: 1, durationRawMillis: 0,
        work: { frames: 0, packetsParsed: 0 },
    }), false, "empty poll produced a slow diagnostic sample");
    const failed = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainPollPhaseSample(failed, {
        pollSequence: 2, durationRawMillis: 18, outcome: "error",
    }), true, "poll error did not retain a slow diagnostic sample");
    assert.equal(failed.samples[0].outcome, "error",
        "retained poll error lost its outcome");
    for (const sample of ring.samples) {
        assert.ok(sample.durationRawMillis >= POLL_PHASE_THRESHOLD_MILLIS,
            "poll phase ring retained a sub-threshold sample");
        for (const value of Object.values(sample.segments)) {
            assert.ok(Number.isFinite(value) && value >= 0 &&
                value <= sample.durationRawMillis,
            "poll phase segment was negative/non-finite/outside duration");
        }
    }

    // Cadence-hint model: only the canonical 26.2/protocol-776 packet 113
    // with a gap in the narrow one-second window receives the periodic-server
    // sync label.  Every nearby/non-canonical case stays unknown; the hint is
    // diagnostic and carries no strict-pass semantics.
    assert.equal(classifyPeriodicServerSyncArrival({
        profileId: CANONICAL_PROFILE.id,
        protocolVersion: 776,
        packetId: 113,
        decodedGapMillis: 1000,
    }), ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION,
    "canonical " + CANONICAL_PROFILE.id + " packet 113 cadence was not classified");
    assert.equal(classifyPeriodicServerSyncArrival({
        profileId: CANONICAL_PROFILE.id,
        protocolVersion: 776,
        packetId: 113,
        decodedGapMillis: 1004.5,
    }), ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION,
    "near-one-second " + CANONICAL_PROFILE.id + " packet 113 cadence was not classified");
    assert.equal(classifyPeriodicServerSyncArrival({
        profileId: CANONICAL_PROFILE.id,
        protocolVersion: 776,
        packetId: 113,
        decodedGapMillis: 1000,
        phaseAtDecode: "configuration",
    }), "unknown-arrival-gap",
    "packet 113 outside PLAY must not receive a cadence hint");
    assert.equal(classifyPeriodicServerSyncArrival({
        profileId: CANONICAL_PROFILE.id,
        protocolVersion: 776,
        packetId: 113,
        decodedGapMillis: 1000,
        phaseAtDecode: "play",
        localSegments: [300],
    }), "unknown-arrival-gap",
    "a slow local segment must not be hidden by a cadence hint");
    for (const candidate of [
        { profileId: LEGACY_PROFILE.id, protocolVersion: 774, packetId: 113,
            decodedGapMillis: 1000 },
        { profileId: CANONICAL_PROFILE.id, protocolVersion: 776, packetId: 112,
            decodedGapMillis: 1000 },
        { profileId: CANONICAL_PROFILE.id, protocolVersion: 776, packetId: 113,
            decodedGapMillis: 800 },
        { profileId: CANONICAL_PROFILE.id, protocolVersion: 776, packetId: 113,
            decodedGapMillis: 1201 },
        { profileId: CANONICAL_PROFILE.id, protocolVersion: 775, packetId: 113,
            decodedGapMillis: 1000 },
        // 26.3/777 has no canonical cadence contract.  Its PLAY id 113 is
        // set_simulation_distance and set_time moved to 115, so neither may
        // inherit the 26.2 hint.
        { profileId: RENDERPEARL_PROFILE.id, protocolVersion: 777, packetId: 113,
            decodedGapMillis: 1000 },
        { profileId: RENDERPEARL_PROFILE.id, protocolVersion: 777, packetId: 115,
            decodedGapMillis: 1000 },
    ]) {
        assert.equal(classifyPeriodicServerSyncArrival(candidate),
            "unknown-arrival-gap",
        `non-canonical cadence candidate was misclassified: ${JSON.stringify(candidate)}`);
    }
}

{
    const ticks = {
        active: true,
        closed: false,
        phase: "play",
        paused: false,
        nextDueAt: 0,
        lastTickAt: undefined,
        ticks: 0,
        skippedPeriods: 0,
    };
    assert.equal(modelPlayTick(ticks, 0), true,
        "shared tick service did not preserve the immediate PLAY tick");
    assert.equal(modelPlayTick(ticks, 49), false,
        "shared tick service sent a tick before the 50 ms due time");
    assert.equal(modelPlayTick(ticks, 50), true,
        "shared tick service missed the second 50 ms tick");
    assert.equal(ticks.nextDueAt, 100,
        "shared tick service drifted from the nominal cadence");
    assert.equal(modelPlayTick(ticks, 260), true,
        "shared tick service did not recover after a delayed scheduler turn");
    assert.equal(ticks.nextDueAt, 310,
        "delayed tick did not re-anchor its next due time");
    assert.equal(ticks.skippedPeriods, 3,
        "delayed tick did not account for skipped periods from the next due time");
    const boundaryTicks = {
        active: true,
        closed: false,
        phase: "play",
        paused: false,
        nextDueAt: 100,
        ticks: 0,
        skippedPeriods: 0,
    };
    assert.equal(modelPlayTick(boundaryTicks, 250), true,
        "boundary delayed tick was not serviced");
    assert.equal(boundaryTicks.skippedPeriods, 2,
        "exact-period delay must count from nextDueAt with ceil semantics");
    const notDue = {
        active: true,
        closed: false,
        phase: "play",
        paused: false,
        pollingPaused: false,
        playTickSuspended: false,
        nextDueAt: 100,
        ticks: 0,
        skippedPeriods: 0,
    };
    const notDueBatch = modelPlayTickBatch([notDue], 0, 0, BATCH_LIMIT);
    assert.equal(notDueBatch.serviced, 0,
        "not-due PLAY client was admitted to the service batch");
    assert.equal(notDueBatch.sent, 0,
        "not-due PLAY client emitted a tick");
    const resumed = {
        active: true,
        closed: false,
        phase: "play",
        paused: false,
        pollingPaused: false,
        playTickSuspended: true,
        nextDueAt: undefined,
        ticks: 0,
        skippedPeriods: 0,
    };
    assert.equal(modelIsPlayTickDue(resumed, 0), true,
        "resumed suspended PLAY client was not admitted");
    const resumedBatch = modelPlayTickBatch([resumed], 0, 0, BATCH_LIMIT);
    assert.equal(resumedBatch.serviced, 1,
        "resumed suspended PLAY client did not receive its recovery service");
    assert.equal(resumedBatch.sent, 1,
        "resumed suspended PLAY client did not emit its recovery tick");
    assert.equal(resumed.playTickSuspended, false,
        "recovery service did not clear PLAY suspension");
    const pausedSuspended = {
        active: true,
        closed: false,
        phase: "play",
        paused: false,
        pollingPaused: true,
        playTickSuspended: true,
        nextDueAt: undefined,
        ticks: 0,
        skippedPeriods: 0,
    };
    assert.equal(modelIsPlayTickDue(pausedSuspended, 0), false,
        "transport-paused suspended PLAY client was admitted");
    const pausedSuspendedBatch = modelPlayTickBatch(
        [pausedSuspended], 0, 0, BATCH_LIMIT);
    assert.equal(pausedSuspendedBatch.serviced, 0,
        "transport-paused suspended PLAY client was serviced");
    assert.equal(pausedSuspendedBatch.sent, 0,
        "transport-paused suspended PLAY client emitted a tick");
    ticks.paused = true;
    assert.equal(modelPlayTick(ticks, 400), false,
        "paused client emitted a shared tick");

    const tickClients = Array.from({ length: 16 }, () => ({
        active: true,
        closed: false,
        phase: "play",
        paused: false,
        nextDueAt: 0,
        ticks: 0,
        skippedPeriods: 0,
    }));
    const firstBatch = modelPlayTickBatch(tickClients, 0, 0);
    const secondBatch = modelPlayTickBatch(tickClients, firstBatch.cursor, 0);
    assert.equal(firstBatch.serviced, BATCH_LIMIT,
        "shared tick batch exceeded/under-ran its bounded first turn");
    assert.equal(secondBatch.serviced, BATCH_LIMIT,
        "shared tick batch did not retain its independent fair cursor");
    assert.ok(firstBatch.sent + secondBatch.sent <= BATCH_LIMIT * 2,
        "shared tick batch emitted more than its per-turn cap");
}

{
    const ring = {
        nextIndex: 0,
        count: 0,
        total: 0,
        dropped: 0,
        samples: new Array(PLAY_TICK_TIMING_MODEL_LIMIT),
    };
    retainPlayTickTimingSample(ring, {
        sequence: 1,
        dueAtMillis: 100,
        tickAtMillis: 100.2,
        previousGapMillis: null,
        schedulerCallbackSequence: null,
        trigger: "play-start",
        skipPeriods: 0,
        phase: "play",
    });
    for (let sequence = 2; sequence <= 65; sequence++) {
        retainPlayTickTimingSample(ring, {
            sequence,
            dueAtMillis: sequence * 50,
            tickAtMillis: sequence * 50 + 0.2,
            previousGapMillis: 50,
            schedulerCallbackSequence: sequence + 100,
            trigger: "immediate",
            skipPeriods: sequence === 65 ? 2 : 0,
            phase: "play",
        });
    }
    const ordered = orderedPlayTickTimingSamples(ring);
    assert.equal(ring.total, 65,
        "PLAY tick timing model did not count every successful tick");
    assert.equal(ring.count, PLAY_TICK_TIMING_MODEL_LIMIT,
        "PLAY tick timing model exceeded its hard per-client limit");
    assert.equal(ordered.length, PLAY_TICK_TIMING_MODEL_LIMIT,
        "PLAY tick timing model serializer escaped its hard limit");
    assert.equal(ring.dropped, 1,
        "PLAY tick timing model dropped-count did not track overwrite");
    assert.deepEqual([ordered[0].sequence, ordered.at(-1).sequence], [2, 65],
        "PLAY tick timing ring was not chronological after wraparound");
    for (const sample of ordered) {
        assert.equal(sample.schemaVersion, PLAY_TICK_TIMING_MODEL_SCHEMA_VERSION);
        assert.ok(Number.isFinite(sample.dueAtMillis) &&
            Number.isFinite(sample.tickAtMillis),
        "PLAY tick timing sample lost due/tick timestamps");
        assert.ok(sample.previousGapMillis === null ||
            Number.isFinite(sample.previousGapMillis),
        "PLAY tick timing sample lost the previous gap");
        assert.ok(sample.schedulerCallbackSequence === null ||
            Number.isSafeInteger(sample.schedulerCallbackSequence),
        "PLAY tick timing sample lost scheduler callback provenance");
        assert.equal(typeof sample.trigger, "string");
        assert.ok(Number.isSafeInteger(sample.skipPeriods) && sample.skipPeriods >= 0);
        assert.equal(sample.phase, "play");
    }
    assert.equal(ordered.at(-1).skipPeriods, 2,
        "PLAY tick timing model lost skip-period evidence");
}

// The slow-gap ring must retain the largest strict-threshold candidates even
// after the chronological ring has wrapped.  Fast samples stay out of the
// diagnostic ring, and overflow is explicit rather than silently overwriting
// the evidence needed to correlate a callback sequence.
{
    const slow = { total: 0, dropped: 0, samples: [] };
    assert.equal(retainPlayTickSlowSampleModel(slow, {
        sequence: 1, previousGapMillis: 59.999,
        schedulerCallbackSequence: 10, trigger: "immediate",
    }), false, "sub-threshold tick gap entered the slow ring");
    for (let sequence = 2; sequence <= 66; sequence++) {
        retainPlayTickSlowSampleModel(slow, {
            sequence,
            previousGapMillis: 60 + sequence / 1000,
            schedulerCallbackSequence: sequence + 100,
            trigger: sequence % 2 === 0 ? "timer-yield" : "immediate",
        });
    }
    assert.equal(slow.total, 65,
        "slow tick ring did not count every threshold candidate");
    assert.equal(slow.samples.length, 64,
        "slow tick ring exceeded its hard limit");
    assert.equal(slow.dropped, 1,
        "slow tick ring dropped-count did not track overflow");
    assert.equal(slow.samples[0].sequence, 66,
        "slow tick ring did not retain the largest gap first");
    assert.equal(slow.samples.at(-1).sequence, 3,
        "slow tick ring did not evict the smallest retained gap");
    assert.ok(slow.samples.every((sample) =>
        sample.previousGapMillis >= 60 && sample.diagnosticOnly === true &&
        sample.strictGatesChanged === false),
    "slow tick ring retained invalid or gate-affecting evidence");
    for (let index = 1; index < slow.samples.length; index++) {
        const previous = slow.samples[index - 1];
        const current = slow.samples[index];
        assert.ok(previous.previousGapMillis > current.previousGapMillis ||
            previous.previousGapMillis === current.previousGapMillis &&
                previous.sequence <= current.sequence,
        "slow tick ring ordering drifted from gap-desc/sequence-asc");
    }
}

// A bounded tick batch must not park the continuation while peers still have
// due PLAY ticks. With eight due clients and a four-client cap, the first turn
// services four and the remaining four force an immediate next turn rather
// than the one-millisecond idle timer (whose host quantization inflated the
// previous tier-8 tick p99).
{
    const dueClients = Array.from({ length: CLIENT_COUNT }, () => ({
        active: true,
        closed: false,
        phase: "play",
        paused: false,
        failure: undefined,
        nextDueAt: 0,
        ticks: 0,
        skippedPeriods: 0,
    }));
    const first = modelPlayTickBatch(dueClients, 0, 0, BATCH_LIMIT);
    assert.equal(first.serviced, BATCH_LIMIT,
        "due PLAY model exceeded its per-turn service cap");
    assert.equal(first.sent, BATCH_LIMIT,
        "due PLAY model did not service the first bounded batch");
    const dueAfterFirst = modelCountDuePlayTicks(dueClients, 0);
    assert.equal(dueAfterFirst, CLIENT_COUNT - BATCH_LIMIT,
        "unserviced due PLAY clients were not retained for the next turn");
    const continuation = dueAfterFirst > 0 ? "immediate" : "idle";
    assert.equal(continuation, "immediate",
        "remaining due PLAY ticks incorrectly selected idle backoff");
    const second = modelPlayTickBatch(dueClients, first.cursor, 0, BATCH_LIMIT);
    assert.equal(second.serviced, BATCH_LIMIT,
        "second due PLAY batch did not preserve the bounded cap");
    assert.equal(modelCountDuePlayTicks(dueClients, 0), 0,
        "all due PLAY clients were not drained across immediate turns");
    assert.equal(
        modelCountDuePlayTicks(dueClients.map((state) => ({ ...state,
            paused: true,
            nextDueAt: 0,
        })), 0),
        0,
        "paused PLAY clients incorrectly forced an immediate continuation");
}

// Immediate-readiness model: a split outer VarInt/packet must wait for the
// bridge or the normal due cadence, while a complete frame wakes immediately.
function modelHasImmediateInbound(buffer, bridgePending = false) {
    const bytes = Buffer.from(buffer ?? []);
    if (bytes.byteLength > 0) {
        let value = 0;
        for (let index = 0; index < 5; index++) {
            if (index >= bytes.byteLength) break;
            const next = bytes[index];
            value |= (next & 0x7f) << (index * 7);
            if ((next & 0x80) === 0) {
                const frameEnd = index + 1 + value;
                if (value < 0 || !Number.isSafeInteger(frameEnd)) return true;
                if (frameEnd <= bytes.byteLength) return true;
                break;
            }
        }
    }
    return bridgePending;
}

assert.equal(modelHasImmediateInbound(Buffer.from([0x80])), false,
    "split outer VarInt must not manufacture an immediate wake-up");
assert.equal(modelHasImmediateInbound(Buffer.from([0x80]), true), true,
    "split outer VarInt must wake when the bridge has another chunk");
assert.equal(modelHasImmediateInbound(Buffer.from([0x01, 0xaa])), true,
    "complete outer frame must wake immediately");
assert.equal(modelHasImmediateInbound(Buffer.from([0x03, 0xaa])), false,
    "incomplete outer payload must remain on due cadence");

// Idle filtering: a fully quiescent client set must not receive a fallback
// no-op poll, and the scheduler must select its idle timer continuation.
{
    const idleClients = makeModelClients({ dueAt: 100 });
    const idle = modelDispatch(idleClients, 0, 0);
    assert.deepEqual(idle.ids, [], "all-not-due clients were spuriously polled");
    assert.equal(idle.idle, true, "all-not-due clients did not select idle backoff");
    assert.equal(idle.inspected, CLIENT_COUNT,
        "idle filter stopped before inspecting the visible client set");
}

assert.deepEqual(modelIdleContinuation({
    now: 100,
    earliestPollDueAt: 101,
    idleImmediateSpins: 0,
}), { continuation: "immediate", idleImmediateSpins: 1 },
"near-due poll cursor was incorrectly parked on the quantized timer");
assert.deepEqual(modelIdleContinuation({
    now: 100,
    earliestPollDueAt: 100,
    idleImmediateSpins: 15,
}), { continuation: "immediate", idleImmediateSpins: 16 },
"the final bounded near-due immediate spin was not admitted");
assert.deepEqual(modelIdleContinuation({
    now: 100,
    earliestPollDueAt: 101,
    idleImmediateSpins: 16,
}), { continuation: "idle", idleImmediateSpins: 0 },
"near-due immediate spin budget exceeded its hard limit");
assert.deepEqual(modelIdleContinuation({
    now: 100,
    earliestPollDueAt: 99,
}), { continuation: "immediate", idleImmediateSpins: 1 },
"an overdue poll cursor was incorrectly parked on the quantized timer");
assert.deepEqual(modelIdleContinuation({
    now: 100,
    earliestPollDueAt: 125,
}), { continuation: "idle", idleImmediateSpins: 0 },
"far-future poll cursor did not use idle backoff");

// The deadline probe must not turn its per-burst guard into an early timer
// fallback.  Sixteen very cheap immediate turns can finish well before the
// due boundary; rolling the burst counter keeps the same absolute probe alive.
{
    const first = modelDeadlineProbeContinuation({
        now: 100,
        earliestPollDueAt: 101,
    });
    assert.deepEqual(first, {
        continuation: "immediate",
        idleImmediateSpins: 1,
        probeStartedAt: 100,
        overdueWakePending: false,
    }, "near-due deadline probe did not start on immediate path");
    const rollover = modelDeadlineProbeContinuation({
        now: 100.4,
        earliestPollDueAt: 101,
        idleImmediateSpins: 16,
        probeStartedAt: 100,
    });
    assert.equal(rollover.continuation, "immediate",
        "per-burst spin guard incorrectly parked a still-live probe");
    assert.equal(rollover.idleImmediateSpins, 1,
        "per-burst spin counter did not roll over at its hard limit");
    assert.equal(rollover.probeStartedAt, 100,
        "burst rollover reset the absolute probe deadline");
    const futureBudgetExpiry = modelDeadlineProbeContinuation({
        now: 102.1,
        earliestPollDueAt: 103,
        idleImmediateSpins: 4,
        probeStartedAt: 100,
    });
    assert.deepEqual(futureBudgetExpiry, {
        continuation: "idle",
        idleImmediateSpins: 0,
        probeStartedAt: undefined,
        overdueWakePending: false,
    }, "far-side budget expiry did not return to idle timer");
    const overdueWake = modelDeadlineProbeContinuation({
        now: 102.1,
        earliestPollDueAt: 102,
        idleImmediateSpins: 4,
        probeStartedAt: 100,
    });
    assert.deepEqual(overdueWake, {
        continuation: "immediate",
        idleImmediateSpins: 0,
        probeStartedAt: undefined,
        overdueWakePending: true,
    }, "overdue deadline did not retain its final immediate wake");
    const dispatchedAfterWake = modelDeadlineProbeContinuation({
        now: 102.2,
        earliestPollDueAt: 103.2,
        overdueWakePending: true,
        dispatched: 1,
    });
    assert.equal(dispatchedAfterWake.continuation, "immediate",
        "successful overdue wake incorrectly forced the peer batch to idle");
    const readyWithoutDispatch = modelDeadlineProbeContinuation({
        now: 250,
        earliestPollDueAt: Infinity,
        readyAfterDispatch: true,
        dispatched: 0,
        dueTicks: 0,
    });
    assert.deepEqual(readyWithoutDispatch, {
        continuation: "immediate",
        idleImmediateSpins: 0,
        probeStartedAt: undefined,
        overdueWakePending: false,
    }, "fair-ready candidate was parked after a budgeted zero-dispatch turn");
}

// The near-due guard must mirror the production candidate filters and use an
// inclusive boundary: a tick exactly 16.7 ms away is still protected, while a
// tick just beyond the guard remains eligible for the fairness timer-yield.
{
    const playTickClient = (overrides = {}) => ({
        phase: "play",
        playTickActive: true,
        playTickSuspended: false,
        servicePlayTick: true,
        closed: false,
        pollingPaused: false,
        failure: undefined,
        nextPlayTickDueAt: 116.7,
        ...overrides,
    });
    assert.equal(modelHasPlayTickDueWithin(
        [playTickClient()], 100), true,
        "16.7 ms PLAY-tick boundary did not suppress timer-yield");
    assert.equal(modelHasPlayTickDueWithin(
        [playTickClient({ nextPlayTickDueAt: 116.701 })], 100), false,
        "tick just beyond the guard incorrectly suppressed timer-yield");
    assert.equal(modelHasPlayTickDueWithin(
        [playTickClient({ nextPlayTickDueAt: 200 })], 100), false,
        "far-future PLAY tick changed timer-yield fairness");
    assert.equal(modelHasPlayTickDueWithin(
        [playTickClient({ playTickSuspended: true, nextPlayTickDueAt: 200 })], 100), true,
        "suspended PLAY tick was not admitted immediately");
    assert.equal(modelHasPlayTickDueWithin([
        playTickClient({ closed: true }),
        playTickClient({ pollingPaused: true }),
        playTickClient({ failure: new Error("failed") }),
        playTickClient({ phase: "configuration" }),
        playTickClient({ playTickActive: false }),
        playTickClient({ servicePlayTick: false }),
    ], 100), false,
    "retired/non-PLAY clients incorrectly triggered the near-due guard");
    const computed = modelFinalizerContinuation({
        dispatched: 1,
        nextContinuation: "immediate",
        needsTimerYield: true,
        playTickClients: [playTickClient()],
        now: 100,
    });
    assert.equal(computed.continuation, "immediate",
        "finalizer did not use the filtered near-due PLAY-tick model");
    assert.equal(computed.timerYieldSuppressedByPlayTick, true,
        "finalizer did not record near-due timer-yield suppression");
}

// Full finalizer continuation regression.  A callback can dispatch no poll
// while the post-dispatch readiness probe still sees a fair candidate.  That
// state must win over an earlier/idle continuation and must survive the
// finalizer without changing any strict gate.
{
    const fairReady = modelFinalizerContinuation({
        dispatched: 0,
        nextContinuation: "idle",
        readyAfterDispatch: true,
        dueTicksBeforeIdle: 0,
        dueTicksAfterService: 0,
        needsTimerYield: true,
    });
    assert.deepEqual(fairReady, {
        continuation: "immediate",
        selectedContinuation: "immediate",
        dueTicksPending: false,
        readyPollPending: true,
        playTickDueSoon: false,
        timerYieldSuppressedByPlayTick: false,
        strictGatesChanged: false,
    }, "ready zero-dispatch turn was overwritten by timer-yield finalization");

    const idle = modelFinalizerContinuation({
        dispatched: 0,
        nextContinuation: "immediate",
        readyAfterDispatch: false,
        dueTicksBeforeIdle: 0,
        dueTicksAfterService: 0,
        idleImmediateSpins: 0,
    });
    assert.equal(idle.continuation, "idle",
        "quiescent zero-dispatch turn did not use idle backoff");

    const activeProbe = modelFinalizerContinuation({
        dispatched: 0,
        nextContinuation: "immediate",
        readyAfterDispatch: false,
        idleImmediateSpins: 1,
        idleImmediateProbeStartedAt: 100,
    });
    assert.equal(activeProbe.continuation, "immediate",
        "active near-due probe was cleared by zero-dispatch finalization");

    const dueTickBeatsYield = modelFinalizerContinuation({
        dispatched: 0,
        nextContinuation: "idle",
        readyAfterDispatch: false,
        dueTicksAfterService: 1,
        needsTimerYield: true,
    });
    assert.deepEqual(dueTickBeatsYield, {
        continuation: "immediate",
        selectedContinuation: "immediate",
        dueTicksPending: true,
        readyPollPending: false,
        playTickDueSoon: false,
        timerYieldSuppressedByPlayTick: false,
        strictGatesChanged: false,
    }, "due PLAY tick was not retained on the immediate continuation");

    const periodicYield = modelFinalizerContinuation({
        dispatched: 1,
        nextContinuation: "immediate",
        readyAfterDispatch: false,
        needsTimerYield: true,
    });
    assert.equal(periodicYield.continuation, "timer-yield",
        "timer-yield cadence changed for a dispatched callback");

    const nearDuePlayTick = modelFinalizerContinuation({
        dispatched: 1,
        nextContinuation: "immediate",
        readyAfterDispatch: false,
        needsTimerYield: true,
        playTickDueSoon: true,
    });
    assert.deepEqual(nearDuePlayTick, {
        continuation: "immediate",
        selectedContinuation: "immediate",
        dueTicksPending: false,
        readyPollPending: false,
        playTickDueSoon: true,
        timerYieldSuppressedByPlayTick: true,
        strictGatesChanged: false,
    }, "near-due PLAY tick was parked on periodic timer-yield");
}

// A queued inbound frame does not bypass nextPollDueAt.  This is the
// starvation regression: a continuously busy client must wait for its fair
// due turn instead of monopolising setImmediate while peers are parked.
{
    const pendingClients = makeModelClients({ dueAt: 100, pending: [6] });
    const pending = modelDispatch(pendingClients, 0, 0);
    assert.deepEqual(pending.ids, [],
        "pending inbound client bypassed the fairness due boundary");
    assert.equal(pending.idle, true,
        "not-due pending client did not select idle backoff");
}

// Due clients retain strict round-robin ordering across bounded callbacks.
{
    const dueClients = makeModelClients({ dueAt: 0 });
    const first = modelDispatch(dueClients, 0, 0);
    const second = modelDispatch(dueClients, first.cursor, 0);
    assert.deepEqual(first.ids, [0, 1, 2, 3], "first due batch lost round-robin order");
    assert.deepEqual(second.ids, [4, 5, 6, 7], "second due batch lost round-robin order");
    assert.ok(first.ids.length <= BATCH_LIMIT && second.ids.length <= BATCH_LIMIT,
        "due model exceeded MAX_CLIENTS_PER_POLL_CALLBACK");
}

// Fairness floor regression: a continuously-ready client may not take a
// second turn until every visible peer has received its first turn.  Repeating
// the model at due times must keep the count spread at one or less, including
// when the shared callback is capped at four clients.
{
    const busyClients = makeModelClients({ dueAt: 0, pending: [0] });
    let busyCursor = 0;
    for (let now = 0; now <= 8; now++) {
        const result = modelDispatch(busyClients, busyCursor, now);
        busyCursor = result.cursor;
    }
    const counts = busyClients.map((client) => client.dispatchCount ?? 0);
    assert.ok(Math.max(...counts) - Math.min(...counts) <= 1,
        "fairness floor allowed a busy client to run more than one turn ahead");
    assert.ok(busyClients[0].dispatchCount <= busyClients[7].dispatchCount + 1,
        "busy inbound client bypassed the visible-client fairness floor");
}

// Closed/paused/failed clients are never revived by an unconditional fallback.
{
    const retired = makeModelClients({ dueAt: 0 });
    for (const client of retired) client.pollingPaused = true;
    const result = modelDispatch(retired, 0, 0);
    assert.deepEqual(result.ids, [], "paused clients were dispatched by fallback");
    assert.equal(result.idle, true, "retired client set did not choose idle backoff");
}

// In-place phase-ring rollover regression.  The array identity and length are
// deliberately unchanged after push+splice; the event-identity cursor must
// still discover the new relay-connected event exactly once without replaying
// the previous ring into the compatibility history.
{
    const phaseModel = {
        id: "client-0",
        stats: { connectPhases: [] },
        connectPhases: [],
        seen: new WeakSet(),
        phaseScanSource: null,
        phaseScanIndex: 0,
        phaseScanCursorEvent: null,
    };
    const phaseSource = phaseModel.stats.connectPhases;
    for (let index = 0; index < PHASE_RING_MODEL_LIMIT; index++) {
        phaseSource.push({
            id: phaseModel.id,
            phase: index === 127 ? "relay-connected" : `phase-${index}`,
        });
    }
    modelRecordPhases(phaseModel);
    assert.equal(phaseModel.connectPhases.length, PHASE_RING_MODEL_LIMIT,
        "initial phase ring was not published in full");
    assert.equal(phaseModel.connectPhases.filter((event) =>
        event.phase === "relay-connected").length, 1,
    "initial relay-connected phase count is wrong");
    const phaseSourceIdentity = phaseSource;
    const rolloverEvent = { id: phaseModel.id, phase: "relay-connected" };
    phaseSource.push(rolloverEvent);
    phaseSource.splice(0, 1);
    assert.equal(phaseSource.length, PHASE_RING_MODEL_LIMIT,
        "phase-ring model did not preserve its hard limit");
    modelRecordPhases(phaseModel);
    assert.strictEqual(phaseModel.phaseScanSource, phaseSourceIdentity,
        "phase-ring rollover replaced the source array");
    assert.equal(phaseModel.connectPhases.length, PHASE_RING_MODEL_LIMIT + 1,
        "phase-ring rollover lost or duplicated a phase event");
    assert.equal(phaseModel.connectPhases.filter((event) =>
        event.phase === "relay-connected").length, 2,
    "new relay-connected phase was not captured exactly once");
    assert.equal(phaseModel.connectPhases.filter((event) =>
        event === rolloverEvent).length, 1,
    "rollover event was published more than once");
    const publishedAfterRollover = phaseModel.connectPhases.length;
    modelRecordPhases(phaseModel);
    assert.equal(phaseModel.connectPhases.length, publishedAfterRollover,
        "phase-ring poll duplicated events without a source mutation");
    assert.strictEqual(phaseModel.phaseScanCursorEvent, rolloverEvent,
        "phase-ring cursor did not advance to the newest event");
}

const results = [];
for (const kind of ["hybrid", "immediate", "timer-0", "timer-1"]) {
    results.push(await runCandidate(kind));
}
// Regression guard for the failure mode that motivated this smoke: if every
// 2--3 ms parser turn forces a zero-delay timer, Windows timer quantization can
// turn an otherwise healthy eight-client loop into ~32 ms gaps.  The hybrid
// policy must keep the immediate path dominant and yield on the bounded
// 256-turn cadence instead of timer-yielding after each heavy callback.
const heavyHybrid = await runCandidate("hybrid", 2.5);
const heavyTimerBaseline = await runCandidate("timer-0", 2.5);
results.push(heavyHybrid, heavyTimerBaseline);

for (const result of results) {
    assert.equal(result.overlaps, 0, `${result.kind}: overlapping callbacks`);
    assert.ok(result.callbacks > 0, `${result.kind}: no callbacks ran`);
    assert.ok(result.counts.every((count) => count > 0),
        `${result.kind}: a synthetic client was starved`);
    assert.ok(result.dispatchSkew <= 1,
        `${result.kind}: round-robin skew exceeded one dispatch`);
}
const hybrid = results[0];
assert.ok(hybrid.immediateCallbacks > 0,
    "hybrid scheduler never exercised setImmediate");
assert.ok(hybrid.timerYields > 0,
    "hybrid scheduler never exercised the bounded timer yield");
assert.ok(heavyHybrid.immediateCallbacks > heavyHybrid.timerYields * 2,
    "heavy hybrid loop regressed to a timer after every parser turn");
assert.ok(heavyHybrid.maxPollGapMillis <= 100,
    `heavy hybrid poll gap exceeded strict max: ${heavyHybrid.maxPollGapMillis}ms`);
assert.ok(heavyHybrid.maxPollGapMillis <=
    heavyTimerBaseline.maxPollGapMillis + 5,
    `heavy hybrid gap ${heavyHybrid.maxPollGapMillis}ms exceeded timer baseline ` +
        `${heavyTimerBaseline.maxPollGapMillis}ms by more than scheduler jitter`);

console.log(JSON.stringify({
    ok: true,
    schemaVersion: "gaius.browser-client-poll-scheduler-smoke.v1",
    clients: CLIENT_COUNT,
    batchLimit: BATCH_LIMIT,
    callbackWorkBudgetMillis: CALLBACK_WORK_BUDGET_MILLIS,
    playTickTimerYieldGuardMillis:
        PLAY_TICK_TIMER_YIELD_GUARD_MODEL_MILLIS,
    timerYieldGuardPolicy:
        "near-due-inclusive-boundary; strict-gates-unchanged",
    runMillis: RUN_MILLIS,
    strictPollGapTarget: { p99Millis: 16.7, p999Millis: 50, maxMillis: 100 },
    playTickTiming: {
        schemaVersion: PLAY_TICK_TIMING_MODEL_SCHEMA_VERSION,
        sampleLimit: PLAY_TICK_TIMING_MODEL_LIMIT,
        retention: "last-64-chronological",
        diagnosticOnly: true,
        strictGatesChanged: false,
        modelSamplesTotal: 65,
        modelRetainedSampleCount: PLAY_TICK_TIMING_MODEL_LIMIT,
        modelDroppedSampleCount: 1,
    },
    pollPhaseTelemetry: {
        schemaVersion: POLL_PHASE_SCHEMA_VERSION,
        segmentAccounting: POLL_PHASE_SEGMENT_ACCOUNTING,
        slowThresholdMillis: POLL_PHASE_THRESHOLD_MILLIS,
        sampleLimit: POLL_PHASE_LIMIT,
        frameSampleLimit: POLL_PHASE_FRAME_LIMIT,
        packetSampleLimit: POLL_PHASE_PACKET_LIMIT,
        diagnosticOnly: true,
        strictGatesChanged: false,
        independentExecution: false,
        retention: "longest-duration-desc-sequence-asc",
    },
    callbackFinalizationTail: {
        schemaVersion:
            "gaius.browser-client-poll-callback-finalization-tail.v1",
        slowThresholdMillis: 16.7,
        sampleLimit: 64,
        retention: "longest-tail-desc-sequence-asc",
        diagnosticOnly: true,
        strictGatesChanged: false,
        strictRawDurationGateMillis: 16.7,
        measuredFrom: "callback-work-end-to-finalize-finish",
        totalAfterFinalizeFrom: "callback-start-to-finalize-finish",
        includesContinuationScheduling: true,
    },
    schedulerSegments: {
        schemaVersion: "gaius.browser-client-poll-scheduler-segments.v1",
        enabled: false,
        slowThresholdMillis: 16.7,
        sampleLimit: 64,
        retention: "largest-segment-desc-sequence-asc",
        measuredSegments: [
            "admission",
            "readiness",
            "fairness",
            "postDispatchReadiness",
            "postDispatch",
            "evidence",
            "finalization",
        ],
        diagnosticOnly: true,
        strictGatesChanged: false,
        independentExecution: false,
        strictEvidenceEligible: true,
        modelSamplesTotal: 65,
        modelRetainedSampleCount: SCHEDULER_SEGMENT_MODEL_LIMIT,
        modelDroppedSampleCount: 1,
    },
    schedulerGap: {
        schemaVersion: "gaius.browser-client-scheduler-gap.v1",
        slowGapThresholdMillis: 250,
        strictCallbackThresholdMillis: 16.7,
        sampleLimit: 64,
        retention: "largest-gap-or-callback-desc-sequence-asc",
        diagnosticOnly: true,
        strictGatesChanged: false,
        strictRawDurationGateMillis: 16.7,
        triggerKinds: [
            "inter-callback-gap",
            "scheduled-delay",
            "callback-duration",
            "budget-reached",
        ],
        clockAnomalyField: "clockAnomaly",
        correlationFields: [
            "previousCallbackSequence",
            "previousCallbackStartedAtMillis",
            "previousCallbackFinishedAtMillis",
            "interCallbackGapRawMillis",
        ],
        modelSamplesTotal: 65,
        modelRetainedSampleCount: 64,
        modelDroppedSampleCount: 1,
    },
    arrivalTraceBoundary: {
        boundaryEventKind: "arrival-gap-boundary",
        forcedEventLimit: 64,
        forcedBoundaryPriority: true,
        modelBoundaryRetainedAfterOrdinaryOverflow: true,
        diagnosticOnly: true,
        strictGatesChanged: false,
    },
    arrivalPeriodicServerSync: {
        schemaVersion: ARRIVAL_PERIODIC_SERVER_SYNC_SCHEMA_VERSION,
        classification: ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION,
        profileId: ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID,
        protocolVersion: ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION,
        packetId: ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID,
        nominalGapMillis: ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS,
        toleranceMillis: ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS,
        excludedFromUserVisibleStall: true,
        strictGateImpact: "none",
        diagnosticOnly: true,
        strictGatesChanged: false,
        model: {
            canonical: "periodic-server-sync",
            nonCanonical: "unknown-arrival-gap",
        },
    },
    productionArrivalTimeline: {
        schemaVersion: "gaius.browser-client-arrival-timeline.v1",
        enabled: false,
        diagnosticOnly: true,
        strictEvidenceEligible: false,
        strictGatesChanged: false,
        wireAtSource: "unavailable",
        limits: { perChannelEvents: 64, globalEvents: 256 },
        verification: "source-static-only; no Java/TeaVM runtime",
    },
    phaseRingRollover: {
        sourceIdentityPreserved: true,
        limit: PHASE_RING_MODEL_LIMIT,
        newRelayConnectedCapturedExactlyOnce: true,
        priorEventsNotDuplicated: true,
        diagnosticOnly: true,
        strictGatesChanged: false,
    },
    results,
}));
