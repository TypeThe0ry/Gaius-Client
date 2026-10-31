import {CANONICAL_PROFILE, LEGACY_PROFILE, RENDERPEARL_PROFILE} from "../../port/scripts/verification-profiles.mjs";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { fileURLToPath, pathToFileURL } from "node:url";
import {runInNewContext} from "node:vm";
import path from "node:path";

const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const fullPathScript = fileURLToPath(
    new URL("./browser-full-path-smoke.mjs", import.meta.url));
const acceptanceRunnerPath = fileURLToPath(
    new URL("./browser-full-path-acceptance.mjs", import.meta.url));
const stressRunnerPath = fileURLToPath(
    new URL("./browser-full-path-stress.mjs", import.meta.url));
const relayMainPath = fileURLToPath(new URL("./dist/main.js", import.meta.url));
const protocolPath = fileURLToPath(new URL("./dist/protocol.js", import.meta.url));
const multiplayerPath = fileURLToPath(new URL("./multiplayer-smoke.mjs", import.meta.url));
const verifyRuntimePath = fileURLToPath(
    new URL("./deploy/verify-runtime.sh", import.meta.url));
const packagePath = fileURLToPath(new URL("./package.json", import.meta.url));
const fullPathSourceText = await readFile(fullPathScript, "utf8");
const browserClientNetworkPath = path.resolve(
    bridgeDirectory,
    "../../port/src/main/java/dev/gaius/browser/BrowserClientNetwork.java",
);
const browserClientNetworkSource = await readFile(browserClientNetworkPath, "utf8");

// A late MessageChannel/watchdog callback from an inbound-pump scheduler that has
// since been replaced must be telemetry-only.  Keep this source contract here so
// reconnect/bridge replacement cannot regress into clearing the current bridge
// pending flag or re-entering Java on a retired scheduler.
assert.match(browserClientNetworkSource,
    /const ownerScheduler = scheduler[\s\S]*?const ownerGeneration = Number\(ownerScheduler\.generation\)/u,
    "inbound pump callbacks must capture scheduler identity and generation");
assert.match(browserClientNetworkSource,
    /if \(globalThis\.__gaiusNettyBridge !== bridge \|\|[\s\S]*?bridge\.inboundPumpScheduler !== ownerScheduler[\s\S]*?ownerScheduler\.__gaiusRetired === true[\s\S]*?ownerScheduler\.version !== 2[\s\S]*?ownerScheduler\.pending !== pending\)[\s\S]*?stats\.inboundPumpStaleCallbacks\+\+/u,
    "retired inbound pump callbacks must fail closed before mutating state");
assert.match(browserClientNetworkSource,
    /function schedulePump[\s\S]*?globalThis\.__gaiusNettyBridge !== bridge/u,
    "stale scheduler calls must reject a replaced bridge object");
assert.match(browserClientNetworkSource,
    /retireScheduler\s*=\s*function\(stale\)[\s\S]*?stale\.__gaiusRetired = true[\s\S]*?retiredPending\.watchdog/u,
    "scheduler replacement must retire and clean the old pending watchdog");
assert.match(browserClientNetworkSource,
    /schedulerIdentityDrift[\s\S]*?retireScheduler\(installedScheduler\)/u,
    "same-bridge scheduler replacement must retire the previously installed scheduler");
assert.match(browserClientNetworkSource,
    /scheduler\.version !== 2 \|\| scheduler\.__gaiusRetired === true/u,
    "retired scheduler objects must be replaced if reattached");
assert.match(browserClientNetworkSource,
    /const activeGenerationDrift = !!\(scheduler && scheduler\.pending[\s\S]*?activeGenerationDrift/u,
    "active pending generation drift must trigger scheduler replacement");
assert.match(browserClientNetworkSource,
    /const observedBridgeGenerationValid\s*=\s*[\s\S]*?Number\.isFinite\(observedBridgeGeneration\)[\s\S]*?observedBridgeGeneration > 0/u,
    "bridge generation validity must be established before normalizing a pending scheduler");
assert.match(browserClientNetworkSource,
    /const observedSchedulerGenerationValid\s*=\s*[\s\S]*?Number\.isFinite\(observedSchedulerGeneration\)[\s\S]*?observedSchedulerGeneration > 0/u,
    "scheduler generation validity must be established before normalizing a pending scheduler");
assert.match(browserClientNetworkSource,
    /const activeGenerationInvalid = !!\(scheduler && scheduler\.pending[\s\S]*?!observedBridgeGenerationValid[\s\S]*?!observedSchedulerGenerationValid/u,
    "a pending scheduler with a one-sided invalid generation must fail closed");
assert.match(browserClientNetworkSource,
    /activeGenerationInvalid \|\| activeGenerationDrift/u,
    "pending generation replacement must cover invalid and mismatched generations");
assert.match(browserClientNetworkSource,
    /generation:\s*nextGeneration,/u,
    "inbound pump scheduler must carry a bridge-scoped generation");
assert.match(browserClientNetworkSource,
    /const replacementBaseGeneration = Math\.max\([\s\S]*?retiredSchedulerGeneration[\s\S]*?let nextGeneration = \(replacementBaseGeneration \+ 1\) >>> 0/u,
    "replacement scheduler generation must advance beyond bridge and retired scheduler");
assert.match(browserClientNetworkSource,
    /const pending = \{token: token, finish: null, watchdog: 0\}/u,
    "pending pump record must retain its watchdog for retirement cleanup");
assert.match(browserClientNetworkSource,
    /callback\(\) may synchronously install[\s\S]*?ownerScheduler\.__gaiusRetired === true[\s\S]*?ownerScheduler\.version !== 2/u,
    "post-callback owner guard must reject retired or legacy scheduler state");
assert.match(browserClientNetworkSource,
    /const normalizedGeneration = Math\.max\([\s\S]*?bridge\.inboundPumpGeneration = normalizedGeneration/u,
    "legacy scheduler generation must normalize monotonically");
assert.equal(
    (browserClientNetworkSource.match(/const bridgeGeneration\s*=\s*Number\(/gu) ?? []).length,
    1,
    "inbound pump JSBody must avoid duplicate bridgeGeneration declarations");

// Server-free race model: replacing the scheduler while an old watchdog/message
// callback is queued must leave the replacement pending record untouched and must
// not re-enter the callback supplied to the retired generation.
let generationGuardEvidence = null;
{
    const oldScheduler = {
        generation: 1,
        __gaiusRetired: true,
        pending: { token: 7, watchdog: 42 },
    };
    const newScheduler = { generation: 1, pending: { token: 1 } };
    const bridge = { inboundPumpScheduler: newScheduler, inboundPumpPending: true };
    let staleCallbacks = 0;
    let javaCallbacks = 0;
    let reports = 0;
    let scheduled = 0;
    const oldPending = oldScheduler.pending;
    const clearTimer = () => { oldPending.watchdog = 0; };
    const finishRetired = () => {
        if (bridge.inboundPumpScheduler !== oldScheduler ||
            oldScheduler.__gaiusRetired === true ||
            oldScheduler.generation !== 1 || oldScheduler.pending !== oldPending) {
            if (oldPending.watchdog !== 0) clearTimer();
            if (staleCallbacks === 0) staleCallbacks++;
            return;
        }
        javaCallbacks++;
        bridge.inboundPumpPending = false;
        reports++;
        scheduled++;
    };
    finishRetired();
    finishRetired();
    assert.equal(staleCallbacks, 1, "retired callback must be counted once");
    assert.equal(javaCallbacks, 0, "retired callback must not re-enter Java");
    assert.equal(reports, 0, "retired callback must not publish a report");
    assert.equal(scheduled, 0, "retired callback must not schedule continuation");
    assert.equal(oldPending.watchdog, 0, "retired watchdog must be cleared");
    assert.equal(bridge.inboundPumpPending, true,
        "retired callback must not clear replacement pending state");
    assert.equal(newScheduler.pending.token, 1,
        "replacement pending record must remain intact");

    // Rebuilding the bridge object is a separate identity boundary from replacing
    // the scheduler field on the same object; a retired callback must fail closed
    // in both cases.
    const bridgeOwnerScheduler = { generation: 3, pending: { token: 9 } };
    const bridgeObject = {
        inboundPumpScheduler: bridgeOwnerScheduler,
        inboundPumpPending: true,
    };
    const replacementBridgeObject = {
        inboundPumpScheduler: newScheduler,
        inboundPumpPending: true,
    };
    let activeBridgeObject = bridgeObject;
    let bridgeObjectJavaCallbacks = 0;
    const finishAcrossBridgeReplacement = () => {
        if (activeBridgeObject !== bridgeObject ||
            bridgeObject.inboundPumpScheduler !== bridgeOwnerScheduler ||
            bridgeOwnerScheduler.__gaiusRetired === true) {
            return;
        }
        bridgeObjectJavaCallbacks++;
    };
    activeBridgeObject = replacementBridgeObject;
    finishAcrossBridgeReplacement();
    assert.equal(bridgeObjectJavaCallbacks, 0,
        "replaced bridge object must not re-enter Java from an old callback");

    const callbackScheduler = { generation: 2, pending: { token: 8 } };
    const callbackBridge = { inboundPumpScheduler: callbackScheduler };
    let callbackReports = 0;
    let callbackContinuations = 0;
    const ownerGeneration = callbackScheduler.generation;
    callbackScheduler.pending.finish = () => {
        callbackScheduler.pending = null;
        // Simulate callback-triggered bridge replacement (reconnect/install).
        callbackBridge.inboundPumpScheduler = { generation: ownerGeneration + 1, pending: null };
        if (callbackBridge.inboundPumpScheduler !== callbackScheduler ||
            callbackScheduler.generation !== ownerGeneration) return;
        callbackReports++;
        callbackContinuations++;
    };
    callbackScheduler.pending.finish();
    assert.equal(callbackReports, 0,
        "replacement during callback must suppress stale report");
    assert.equal(callbackContinuations, 0,
        "replacement during callback must suppress stale continuation");

    const legacyBridge = { inboundPumpGeneration: 9 };
    const legacyScheduler = { generation: 2 };
    const normalizedGeneration = Math.max(
        Number(legacyBridge.inboundPumpGeneration) || 0,
        Number(legacyScheduler.generation) || 0,
        1,
    );
    legacyScheduler.generation = normalizedGeneration;
    legacyBridge.inboundPumpGeneration = normalizedGeneration;
    assert.equal(legacyScheduler.generation, 9,
        "legacy scheduler must not lower bridge generation");
    assert.equal(legacyBridge.inboundPumpGeneration, 9,
        "bridge generation must remain monotonic");

    const retiredHighGeneration = 17;
    const replacementBaseGeneration = Math.max(9, retiredHighGeneration);
    let replacementGeneration = (replacementBaseGeneration + 1) >>> 0;
    if (replacementGeneration === 0) replacementGeneration = 1;
    assert.equal(replacementGeneration, 18,
        "replacement must advance beyond a higher retired scheduler generation");

    const activePendingScheduler = { version: 2, generation: 2, pending: { token: 4 } };
    const activePendingBridgeGeneration = 9;
    const activePendingDrift = !!(activePendingScheduler.pending &&
        Number.isFinite(activePendingBridgeGeneration) &&
        activePendingBridgeGeneration > 0 &&
        Number.isFinite(activePendingScheduler.generation) &&
        activePendingScheduler.generation > 0 &&
        activePendingBridgeGeneration !== activePendingScheduler.generation);
    assert.equal(activePendingDrift, true,
        "active pending generation bump must be detected before normalization");
    activePendingScheduler.__gaiusRetired = true;
    activePendingScheduler.pending.watchdog = 0;
    activePendingScheduler.pending = null;
    const activePendingBridgeState = { inboundPumpPending: false };
    assert.equal(activePendingScheduler.pending, null,
        "generation drift replacement must clear the retired pending record");
    assert.equal(activePendingBridgeState.inboundPumpPending, false,
        "generation drift replacement must clear bridge pending state");

    // A pending callback must never be repaired in place when either side of the
    // bridge generation is invalid.  In particular, normalizing scheduler NaN/0
    // to bridge=5 would invalidate the callback's captured ownerGeneration while
    // leaving pending=true forever.  The production path replaces the scheduler,
    // clears its watchdog and clears the bridge pending flag instead.
    const generationInvalidCases = [
        {label: "scheduler-zero", bridge: 5, scheduler: 0},
        {label: "scheduler-nan", bridge: 5, scheduler: Number.NaN},
        {label: "bridge-zero", bridge: 0, scheduler: 5},
        {label: "bridge-nan", bridge: Number.NaN, scheduler: 5},
    ];
    generationGuardEvidence = generationInvalidCases.map((entry) => {
        const pending = {token: 11, watchdog: 91};
        const scheduler = {
            version: 2,
            generation: entry.scheduler,
            pending,
            __gaiusRetired: false,
        };
        const bridge = {
            inboundPumpGeneration: entry.bridge,
            inboundPumpScheduler: scheduler,
            inboundPumpPending: true,
        };
        const bridgeGeneration = Number(bridge.inboundPumpGeneration);
        const schedulerGeneration = Number(scheduler.generation);
        const bridgeValid = Number.isFinite(bridgeGeneration) && bridgeGeneration > 0;
        const schedulerValid = Number.isFinite(schedulerGeneration) && schedulerGeneration > 0;
        const invalid = !!(scheduler && scheduler.pending &&
            (!bridgeValid || !schedulerValid));
        assert.equal(invalid, true,
            `${entry.label}: one-sided invalid generation was not detected`);
        const replacementBase = Math.max(
            bridgeValid ? bridgeGeneration : 0,
            schedulerValid ? schedulerGeneration : 0,
        );
        let replacementGeneration = (replacementBase + 1) >>> 0;
        if (replacementGeneration === 0) replacementGeneration = 1;
        if (invalid) {
            scheduler.__gaiusRetired = true;
            pending.watchdog = 0;
            scheduler.pending = null;
            bridge.inboundPumpPending = false;
            bridge.inboundPumpGeneration = replacementGeneration;
        }
        assert.equal(pending.watchdog, 0,
            `${entry.label}: retired watchdog was not cleared`);
        assert.equal(scheduler.pending, null,
            `${entry.label}: retired pending record was not cleared`);
        assert.equal(bridge.inboundPumpPending, false,
            `${entry.label}: bridge pending flag was not cleared`);
        assert.equal(bridge.inboundPumpGeneration, replacementGeneration,
            `${entry.label}: replacement generation was not advanced`);
        return {
            label: entry.label,
            bridgeGenerationValid: bridgeValid,
            schedulerGenerationValid: schedulerValid,
            replacementGeneration,
            pendingCleared: scheduler.pending === null,
            watchdogCleared: pending.watchdog === 0,
            bridgePendingCleared: bridge.inboundPumpPending === false,
        };
    });

    // With no pending callback, an old/legacy scheduler is still normalized in
    // place.  This preserves compatibility without reopening the stale-pending
    // wedge above.
    const legacyNoPendingBridge = {inboundPumpGeneration: 5};
    const legacyNoPendingScheduler = {generation: 0, pending: null};
    const legacyBridgeGeneration = Number(legacyNoPendingBridge.inboundPumpGeneration);
    const legacySchedulerGeneration = Number(legacyNoPendingScheduler.generation);
    const legacyBridgeValid = Number.isFinite(legacyBridgeGeneration) &&
        legacyBridgeGeneration > 0;
    const legacySchedulerValid = Number.isFinite(legacySchedulerGeneration) &&
        legacySchedulerGeneration > 0;
    const legacyActiveGenerationInvalid = !!(legacyNoPendingScheduler.pending &&
        (!legacyBridgeValid || !legacySchedulerValid));
    assert.equal(legacyActiveGenerationInvalid, false,
        "legacy no-pending scheduler must keep the normalization path");
    const legacyNormalizedGeneration = Math.max(
        legacyBridgeValid ? legacyBridgeGeneration : 0,
        legacySchedulerValid ? legacySchedulerGeneration : 0,
        1,
    );
    legacyNoPendingScheduler.generation = legacyNormalizedGeneration;
    assert.equal(legacyNoPendingScheduler.generation, 5,
        "legacy no-pending scheduler generation did not normalize");

    const versionMutatedScheduler = {
        version: 1,
        __gaiusRetired: false,
        generation: 4,
    };
    const versionMutatedBridge = { inboundPumpScheduler: versionMutatedScheduler };
    let versionMutatedReports = 0;
    if (versionMutatedBridge.inboundPumpScheduler === versionMutatedScheduler &&
        versionMutatedScheduler.__gaiusRetired !== true &&
        versionMutatedScheduler.version === 2) {
        versionMutatedReports++;
    }
    assert.equal(versionMutatedReports, 0,
        "version-mutated scheduler must not publish after callback");
}

// Arrival timeline is diagnostic evidence only.  Keep these source-level
// assertions here so a future scheduler refactor cannot silently turn the
// timeline into a new gate, synthesize a wire timestamp, or grow an unbounded
// retention structure.  The real runtime stress job remains the authority for
// observed values; this smoke only protects the contract shape.
assert.match(fullPathSourceText,
    /ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS\s*=\s*250/u,
    "arrival timeline diagnostic threshold drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_TIMELINE_SAMPLE_LIMIT\s*=\s*64/u,
    "arrival timeline per-client sample limit drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_TIMELINE_RECONNECT_PHASE_LIMIT\s*=\s*64/u,
    "arrival timeline reconnect phase limit drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_TIMELINE_FRAME_RING_LIMIT\s*=\s*64/u,
    "arrival timeline frame ring limit drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_PRODUCTION_TIMELINE_EVENT_LIMIT\s*=\s*256/u,
    "production bridge timeline limit drifted");
assert.match(fullPathSourceText,
    /globalThis\.__gaiusBrowserArrivalTimeline\s*=\s*ARRIVAL_TRACE_ENABLED;[\s\S]*?new Function\(init\)\(\);/u,
    "production bridge timeline switch must latch before bridge JSBody init");
assert.match(fullPathSourceText,
    /function productionBridgeArrivalTimelineEvidence\(stats\)\s*\{/u,
    "production bridge timeline evidence copier missing");
assert.match(fullPathSourceText,
    /arrivalTimelineProductionBridge\s*:/u,
    "full-path transport must export production bridge timeline separately");
assert.match(fullPathSourceText,
    /productionBridge\s*:\s*productionBridgeArrivalTimelineEvidence\(browserRuntime\.stats\)/u,
    "top-level arrival report must retain production bridge evidence");
assert.match(fullPathSourceText,
    /eventFields\s*=\s*\[[\s\S]*?bridgeEnqueueAt[\s\S]*?pipelineHandoffAt/u,
    "production bridge evidence must use a fixed scalar event allowlist");
assert.match(fullPathSourceText,
    /function productionBridgeArrivalTimelineEvidence\(stats\)[\s\S]*?executionContext:\s*["']node-jsbody-harness["']/u,
    "production bridge evidence must identify the Node JSBody harness context");
assert.match(fullPathSourceText,
    /function productionBridgeArrivalTimelineEvidence\(stats\)[\s\S]*?teaVmArtifactBound:\s*false/u,
    "Node JSBody bridge evidence must not claim TeaVM artifact binding");
assert.match(fullPathSourceText,
    /wireAtSource:\s*["']unavailable["']/u,
    "arrival timeline must fail closed when wire time is unavailable");
assert.match(fullPathSourceText,
    /isBinary\s*!==\s*false\s*&&/u,
    "arrival timeline must not count text Buffer frames as binary wire frames");
assert.match(fullPathSourceText,
    /return\s+["']unattributed-arrival-gap["']/u,
    "arrival timeline must not infer upstream silence from missing timestamps");
assert.match(fullPathSourceText,
    /missing-local-segments=>unattributed/u,
    "arrival timeline attribution policy drifted");
assert.match(fullPathSourceText,
    /strictGatesChanged:\s*false/u,
    "arrival timeline must remain diagnostic-only");
assert.match(fullPathSourceText,
    /independentExecution:\s*false/u,
    "live arrival timeline must not be labelled as an independent model");
assert.match(fullPathSourceText,
    /phaseRingScope:\s*["']all-lifecycle-phases["']/u,
    "arrival phase ring scope must be explicit");
assert.match(fullPathSourceText,
    /arrivalIntentionalDropPending\s*=\s*false/u,
    "arrival timeline intentional-drop state must reset for a fresh lifecycle");
assert.match(fullPathSourceText,
    /reconnectRecoveryAtDecode/u,
    "arrival timeline must mark reconnect-boundary decode samples explicitly");
assert.match(fullPathSourceText,
    /ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID\s*=\s*113/u,
    "periodic server-sync packet id contract drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS\s*=\s*1000/u,
    "periodic server-sync cadence contract drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS\s*=\s*125/u,
    "periodic server-sync cadence tolerance drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION\s*=\s*["']periodic-server-sync["']/u,
    "periodic server-sync classification label drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID\s*=\s*CANONICAL_PROFILE\.id/u,
    "periodic server-sync profile contract drifted");
assert.match(fullPathSourceText,
    /ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION\s*=\s*776/u,
    "periodic server-sync protocol contract drifted");
assert.match(fullPathSourceText,
    /excludedFromUserVisibleStall\s*:/u,
    "periodic server-sync exclusion marker missing");
assert.match(fullPathSourceText,
    /strictGateImpact\s*:\s*["']none["']/u,
    "periodic server-sync must remain outside strict gates");
assert.match(fullPathSourceText,
    /CALLBACK_TAIL_SLOW_THRESHOLD_MILLIS\s*=\s*16\.7/u,
    "strict callback tail gate drifted");
assert.match(fullPathSourceText,
    /const CALLBACK_FINALIZATION_TAIL_TELEMETRY_SCHEMA_VERSION\s*=\s*["']gaius\.browser-client-poll-callback-finalization-tail\.v1["']/u,
    "callback finalization-tail schema drifted");
assert.match(fullPathSourceText,
    /const CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS\s*=\s*16\.7/u,
    "callback finalization-tail threshold drifted");
assert.match(fullPathSourceText,
    /const CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT\s*=\s*64/u,
    "callback finalization-tail sample limit drifted");
// Per-poll phase evidence is bounded diagnostic context for explaining a
// callback tail.  It must not become a second acceptance gate, an independent
// execution claim, or an unbounded packet/payload trace.
assert.match(fullPathSourceText,
    /const POLL_PHASE_TELEMETRY_SCHEMA_VERSION\s*=\s*["']gaius\.browser-client-poll-phase\.v1["']/u,
    "poll phase telemetry schema drifted");
assert.match(fullPathSourceText,
    /const POLL_PHASE_SLOW_THRESHOLD_MILLIS\s*=\s*16\.7/u,
    "poll phase diagnostic threshold drifted");
assert.match(fullPathSourceText,
    /const POLL_PHASE_SAMPLE_LIMIT\s*=\s*64/u,
    "poll phase sample limit drifted");
assert.match(fullPathSourceText,
    /const POLL_PHASE_FRAME_SAMPLE_LIMIT\s*=\s*8/u,
    "poll phase frame cap drifted");
assert.match(fullPathSourceText,
    /const POLL_PHASE_PACKET_SAMPLE_LIMIT\s*=\s*64/u,
    "poll phase packet cap drifted");
assert.match(fullPathSourceText,
    /const POLL_PHASE_SEGMENT_ACCOUNTING\s*=\s*["']inclusive-overlapping["']/u,
    "poll phase segment accounting semantics drifted");
assert.match(fullPathSourceText,
    /candidate\.poll\(callbackSequenceNumber,\s*trigger\)/u,
    "scheduler must pass callback provenance into client poll");
assert.doesNotMatch(fullPathSourceText, /candidate\.poll\(\);/u,
    "scheduler retained a no-argument poll call");
const pollPhaseContractSourceIndex = fullPathSourceText.indexOf(
    "pollPhaseTelemetry");
assert.ok(pollPhaseContractSourceIndex >= 0,
    "poll phase telemetry was omitted from the performance contract");
const pollPhaseContractWindows = [
    ...fullPathSourceText.matchAll(/pollPhaseTelemetry/gu),
].map(({ index }) => fullPathSourceText.slice(index, index + 2500));
assert.ok(pollPhaseContractWindows.some((window) =>
    /strictGatesChanged\s*:\s*false/u.test(window) &&
    /independentExecution\s*:\s*false/u.test(window) &&
    /diagnosticOnly\s*:\s*true/u.test(window) &&
    /retention\s*:\s*["']longest-duration-desc-sequence-asc["']/u.test(window)),
"poll phase telemetry contract must remain bounded, diagnostic-only, and non-independent");
for (const marker of [
    "resetPollPhaseContext(",
    "addPollPhaseSegment(",
    "retainPollPhaseSample(",
    "pollPhaseTelemetryResult(",
    "this.bridge.pollInbound(",
    "Buffer.concat(",
    "inflateSync(",
]) {
    assert.ok(fullPathSourceText.includes(marker),
        `poll phase source omitted ${marker}`);
}

const COMPATIBILITY_ENVIRONMENT = Object.freeze({
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "0",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "2",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "1000",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "0",
});

function sanitizedEnvironment(profilePath, overrides = {}) {
    const environment = { ...process.env };
    for (const name of Object.keys(environment)) {
        if (name.toUpperCase().startsWith("GAIUS_BROWSER_FULL_PATH_")) {
            delete environment[name];
        }
    }
    return {
        ...environment,
        ...COMPATIBILITY_ENVIRONMENT,
        ...overrides,
        GAIUS_VERSION_PROFILE_PATH: profilePath,
    };
}

function configuration(profilePath, overrides = {}, argumentsList = ["--print-config"]) {
    return JSON.parse(execFileSync(process.execPath, [fullPathScript, ...argumentsList], {
        cwd: bridgeDirectory,
        env: sanitizedEnvironment(profilePath, overrides),
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
    }));
}

function stressConfiguration(profilePath, overrides = {},
    argumentsList = ["--print-config", "--tier=8"]) {
    return JSON.parse(execFileSync(process.execPath, [stressRunnerPath, ...argumentsList], {
        cwd: bridgeDirectory,
        env: sanitizedEnvironment(profilePath, overrides),
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
    }));
}

function assertPollPhaseTelemetryContract(config, label) {
    // Accept the historical nested location while requiring one canonical
    // object in the emitted performance contract.  This lets the scheduler
    // keep its callback-tail grouping without duplicating a potentially large
    // sample ring in the top-level result.
    const telemetry = config?.performanceContract?.pollPhaseTelemetry ??
        config?.performanceContract?.pollScheduler?.pollPhaseTelemetry;
    assert.ok(telemetry && typeof telemetry === "object",
        `${label}: pollPhaseTelemetry contract missing`);
    assert.equal(telemetry.schemaVersion,
        "gaius.browser-client-poll-phase.v1",
        `${label}: poll phase schema drifted`);
    assert.equal(telemetry.slowThresholdMillis, 16.7,
        `${label}: poll phase diagnostic threshold drifted`);
    assert.equal(telemetry.sampleLimit, 64,
        `${label}: poll phase sample limit drifted`);
    assert.equal(telemetry.frameSampleLimit, 8,
        `${label}: poll phase frame cap drifted`);
    assert.equal(telemetry.packetSampleLimit, 64,
        `${label}: poll phase packet cap drifted`);
    assert.equal(telemetry.segmentAccounting, "inclusive-overlapping",
        `${label}: poll phase segment accounting semantics drifted`);
    assert.equal(telemetry.diagnosticOnly, true,
        `${label}: poll phase telemetry is not diagnostic-only`);
    assert.equal(telemetry.strictGatesChanged, false,
        `${label}: poll phase telemetry changed strict gates`);
    assert.equal(telemetry.independentExecution, false,
        `${label}: poll phase telemetry claimed independent execution`);
    assert.equal(telemetry.retention,
        "longest-duration-desc-sequence-asc",
        `${label}: poll phase retention policy drifted`);
}

function assertPollSchedulerProbeContract(config, label) {
    const scheduler = config?.performanceContract?.pollScheduler;
    assert.ok(scheduler && typeof scheduler === "object",
        `${label}: poll scheduler contract missing`);
    assert.equal(scheduler.idleImmediateWindowMillis, 2,
        `${label}: idle immediate window drifted`);
    assert.equal(scheduler.idleImmediateProbeBudgetMillis, 2,
        `${label}: idle probe budget drifted`);
    assert.equal(scheduler.idleImmediateProbeSpinLimit, 16,
        `${label}: idle probe spin limit drifted`);
}

function assertCallbackFinalizationTailContract(config, label) {
    const telemetry = config?.performanceContract?.callbackFinalizationTail ??
        config?.performanceContract?.pollScheduler?.callbackFinalizationTail;
    assert.ok(telemetry && typeof telemetry === "object",
        `${label}: callbackFinalizationTail contract missing`);
    assert.equal(telemetry.schemaVersion,
        "gaius.browser-client-poll-callback-finalization-tail.v1",
        `${label}: callback finalization-tail schema drifted`);
    assert.equal(telemetry.slowThresholdMillis, 16.7,
        `${label}: callback finalization-tail threshold drifted`);
    assert.equal(telemetry.sampleLimit, 64,
        `${label}: callback finalization-tail sample limit drifted`);
    assert.equal(telemetry.retention, "longest-tail-desc-sequence-asc",
        `${label}: callback finalization-tail retention drifted`);
    assert.equal(telemetry.diagnosticOnly, true,
        `${label}: callback finalization-tail became a gate`);
    assert.equal(telemetry.strictGatesChanged, false,
        `${label}: callback finalization-tail changed strict gates`);
    assert.equal(telemetry.strictRawDurationGateMillis, 16.7,
        `${label}: callback finalization-tail strict gate drifted`);
    assert.equal(telemetry.measuredFrom,
        "callback-work-end-to-finalize-finish",
        `${label}: callback finalization-tail start semantics drifted`);
    assert.equal(telemetry.totalAfterFinalizeFrom,
        "callback-start-to-finalize-finish",
        `${label}: callback finalization-tail total semantics drifted`);
    assert.equal(telemetry.includesContinuationScheduling, true,
        `${label}: callback finalization-tail omitted scheduling endpoint`);
}

function assertPeriodicServerSyncContract(config, label) {
    const telemetry = config?.performanceContract?.arrivalTimeline
        ?.periodicServerSync;
    assert.ok(telemetry && typeof telemetry === "object",
        `${label}: periodic server-sync contract missing`);
    assert.equal(telemetry.schemaVersion,
        "gaius.browser-client-arrival-periodic-server-sync.v1",
        `${label}: periodic server-sync schema drifted`);
    assert.equal(telemetry.classification, "periodic-server-sync",
        `${label}: periodic server-sync classification drifted`);
    assert.equal(telemetry.profileId, CANONICAL_PROFILE.id,
        `${label}: periodic server-sync profile drifted`);
    assert.equal(telemetry.protocolVersion, 776,
        `${label}: periodic server-sync protocol drifted`);
    assert.equal(telemetry.packetId, 113,
        `${label}: periodic server-sync packet id drifted`);
    assert.equal(telemetry.nominalGapMillis, 1000,
        `${label}: periodic server-sync nominal cadence drifted`);
    assert.equal(telemetry.toleranceMillis, 125,
        `${label}: periodic server-sync tolerance drifted`);
    assert.equal(telemetry.excludedFromUserVisibleStall, true,
        `${label}: periodic server-sync exclusion marker drifted`);
    assert.equal(telemetry.strictGateImpact, "none",
        `${label}: periodic server-sync changed strict-gate accounting`);
    assert.equal(telemetry.diagnosticOnly, true,
        `${label}: periodic server-sync telemetry is not diagnostic-only`);
    assert.equal(telemetry.strictGatesChanged, false,
        `${label}: periodic server-sync changed strict gates`);
}

const expectedProfiles = [
    {
        path: `versions/${CANONICAL_PROFILE.id}.json`, id: CANONICAL_PROFILE.id, protocol: 776, world: 4903,
        java: 25, serverSha1: "823e2250d24b3ddac457a60c92a6a941943fcd6a",
    },
    {
        path: `versions/${LEGACY_PROFILE.id}.json`, id: LEGACY_PROFILE.id, protocol: 774, world: 4671,
        java: 21, serverSha1: "64bb6d763bed0a9f1d632ec347938594144943ed",
    },
];

for (const expected of expectedProfiles) {
    const config = configuration(expected.path);
    assert.equal(config.profile.id, expected.id);
    assert.equal(config.profile.protocolVersion, expected.protocol);
    assert.equal(config.profile.worldVersion, expected.world);
    assert.equal(config.profile.javaVersion, expected.java);
    assert.equal(config.profile.serverSha1, expected.serverSha1);
    assert.equal(config.profile.expectedServerJarSha1, expected.serverSha1);
    assert.equal(config.wireProfile.name, expected.id);
    assert.equal(config.wireProfile.protocolVersion, expected.protocol);
    assert.equal(config.acceptanceMode, false);
    assert.equal(config.clients, 2);
    assert.equal(config.performanceContract.mode, "compatible-smoke");
    assert.equal(config.performanceContract.arrivalTimeline?.wireAtSource,
        "unavailable",
        `${expected.id} arrival wire source contract drifted`);
    assertPollPhaseTelemetryContract(config, `${expected.id} compatible config`);
    assertPollSchedulerProbeContract(config,
        `${expected.id} compatible config`);
    assertCallbackFinalizationTailContract(config,
        `${expected.id} compatible config`);
    assertPeriodicServerSyncContract(config,
        `${expected.id} compatible config`);
    assert.equal(config.performanceContract.strictAcceptanceTarget, null);
    assert.equal(config.performanceContract.soakMillis, 1000);
    assert.equal(config.performanceContract.reconnectWaves, 0);
    assert.deepEqual(config.performanceContract.canonicalProfiles[expected.id], {
        protocolVersion: expected.protocol,
        worldVersion: expected.world,
        javaVersion: expected.java,
        serverSha1: expected.serverSha1,
    });
    assert.deepEqual(config.performanceContract.relayRuntimeGauges, [
        "activeLocalTunnelSessions",
        "pendingSyntheticPlayTicks",
        "activeServerFrameDrainHandles",
        "activeServerFrameDrainTimers",
        "activeClientStallTimers",
    ]);
    assert.equal(config.performanceContract.relayDrainMaxDurationMillis, 16.7);
    assert.equal(config.performanceContract.relayDrainSendErrors, 0);
    assert.equal(config.performanceContract.relayDrainCleanupRequired, true);
    assert.deepEqual(config.performanceContract.browserRuntimeCleanupGauges, [
        "activeHighWatermarks",
        "decodedSliceBacklog",
        "decoderCumulationBytes",
        "decodedPacketQueue",
    ]);
    assert.equal(config.performanceContract.syntheticMarkerLabel,
        "synthetic-inbound-marker");
    assert.equal(config.performanceContract.minimumChunkPackets, 9);
    assert.equal(config.performanceContract.reconnectWaves, 0);
    assert.equal(config.performanceContract.lifecycleCleanupRequired, true);
    assert.deepEqual(config.performanceContract.reconnect, {
        simultaneousDrop: true,
        abnormalTransportDrop: true,
        transportCloseErrorRetained: true,
        syntheticInboundMarkerRetained: true,
        javaFinalCloseAfterTransportDrop: true,
        freshChannelIds: true,
        sameAccountIdentity: true,
        freshProtocolBuffers: true,
        freshEncryptionState: true,
        requiredSessionChecksPerClientPerWave: {
            joins: 1,
            hasJoined: 1,
        },
        requiredMilestonesPerWave: [
            "abnormal-transport-drop",
            "close-error-retained",
            "synthetic-inbound-marker-retained",
            "java-final-close-all-zero",
            "relay-connected",
            "login-finished",
            "configuration-finished",
            "play-login",
            "first-chunk",
            "chunk-9",
        ],
    });
    assert.deepEqual(config.performanceContract.requiredMilestones, [
        "relay-connected",
        "login-finished",
        "configuration-finished",
        "play-login",
        "first-chunk",
        "chunk-9",
    ]);
}

for (const expected of expectedProfiles) {
    for (const tier of [8, 16]) {
        const desiredChunksPerTick = tier === 8 ? 32 : 64;
        const soakMillis = tier === 8 ? 60_000 : 120_000;
        const reconnectWaves = tier === 8 ? 2 : 4;
        const clientLifecycles = tier === 8 ? 24 : 80;
        const stress = configuration(expected.path, {
            GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "0",
            GAIUS_BROWSER_FULL_PATH_STRESS: "1",
            GAIUS_BROWSER_FULL_PATH_STRESS_TIER: String(tier),
            GAIUS_BROWSER_FULL_PATH_CLIENTS: String(tier),
            GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "257",
            GAIUS_BROWSER_FULL_PATH_SOAK_MS: String(soakMillis),
            GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: String(reconnectWaves),
            GAIUS_BROWSER_FULL_PATH_CLIENT_VIEW_DISTANCE: "8",
            GAIUS_BROWSER_FULL_PATH_SERVER_VIEW_DISTANCE: "8",
            GAIUS_BROWSER_FULL_PATH_DESIRED_CHUNKS_PER_TICK:
                String(desiredChunksPerTick),
        }, ["--print-config", "--stress"]);
        assert.equal(stress.acceptanceMode, false);
        assert.equal(stress.stressMode, true);
        assert.equal(stress.stressTier, tier);
        assert.equal(stress.clients, tier);
        assert.equal(stress.performanceContract.mode, `stress-tier-${tier}`);
        assertPollPhaseTelemetryContract(stress,
            `${expected.id} stress tier ${tier} config`);
        assertPollSchedulerProbeContract(stress,
            `${expected.id} stress tier ${tier} config`);
        assertCallbackFinalizationTailContract(stress,
            `${expected.id} stress tier ${tier} config`);
        assertPeriodicServerSyncContract(stress,
            `${expected.id} stress tier ${tier} config`);
        assert.equal(stress.performanceContract.minimumChunkPackets, 257);
        assert.equal(stress.performanceContract.soakMillis, soakMillis);
        assert.equal(stress.performanceContract.reconnectWaves, reconnectWaves);
        assert.equal(stress.stressTarget.clientLifecycles, clientLifecycles);
        assert.equal(stress.performanceContract.minimumChunkMetric,
            "unique-chunk-position");
        assert.deepEqual(stress.performanceContract.chunkWindow, {
            clientViewDistance: 8,
            serverViewDistance: 8,
            effectiveRadius: 8,
            maximumUniqueChunkCapacity: 257,
            initialDistanceContract: {
                source: "clientbound-login",
                packetId: expected.protocol === 776 ? 49 : 48,
                fields: ["chunkRadius", "simulationDistance"],
            },
            observedDistancePackets: {
                cacheCenter: expected.protocol === 776 ? 94 : 92,
                cacheRadius: expected.protocol === 776 ? 95 : 93,
                simulationDistance: expected.protocol === 776 ? 111 : 109,
            },
            observedDistanceContractRequiredBeforeCounting: true,
        });
        assert.deepEqual(stress.performanceContract.stressLatencyDistribution, {
            schemaVersion: "gaius.multiplayer-stress-latency-target.v1",
            histogramSchemaVersion: "gaius.latency-histogram.v1",
            bucketUpperBoundsMillis: [
                1, 2, 4, 8, 16.7, 25, 50, 60, 75, 100, 250, 500, 1000, null,
            ],
            pollGap: { p99Millis: 16.7, p999Millis: 50, maxMillis: 100 },
            playTickGap: { p99Millis: 60, p999Millis: 75, maxMillis: 100 },
            preMinimumChunkGap: { p99Millis: 100, maxMillis: 250 },
            storage: "fixed-bucket-counts-only",
            rawSamplesRetained: false,
        });
        assert.deepEqual(stress.performanceContract.chunkBatch, {
            clientboundFinishedPacketId: 11,
            clientboundStartPacketId: 12,
            serverboundAcknowledgementPacketId: expected.protocol === 776 ? 11 : 10,
            desiredChunksPerTick,
            acknowledgementEncoding: "float32-be",
        });
        assert.equal(stress.identityContract.explicitProfileUsernameMap, true);
        assert.equal(stress.identityContract.uniqueProfiles, tier);
        assert.equal(stress.identityContract.uniqueUsernames, tier);
        assert.equal(stress.identityContract.identities.length, tier);
        for (const identity of stress.identityContract.identities) {
            assert.match(identity.profileId, /^[0-9a-f]{32}$/u);
            assert.match(identity.username, /^[A-Za-z0-9_]{1,16}$/u);
        }
    }
}

// The dedicated runner is canonical-local by construction. Deliberately feed
// it a legacy profile plus an external target and shared-output roots; its
// print-only child must still resolve the immutable canonical extreme contract.
const canonicalStressRunner = JSON.parse(execFileSync(process.execPath, [
    stressRunnerPath, "--print-config", "--tier=16",
], {
    cwd: bridgeDirectory,
    env: sanitizedEnvironment("versions/1.21.11.json", {
        GAIUS_EXTERNAL_RELAY_URL: "wss://example.invalid/tunnel",
        GAIUS_EXTERNAL_TARGET: "example.invalid:25565",
        GAIUS_BUILD_ROOT: "poison-build-root",
        GAIUS_DIST_DIRECTORY: "poison-dist-root",
    }),
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
}));
assert.equal(canonicalStressRunner.ok, true);
assert.deepEqual(canonicalStressRunner.tiers, [16]);
assert.equal(canonicalStressRunner.configurations[0].profile.id, CANONICAL_PROFILE.id);
assert.equal(canonicalStressRunner.configurations[0].profile.protocolVersion, 776);
assert.equal(canonicalStressRunner.configurations[0].profile.worldVersion, 4903);
assert.equal(canonicalStressRunner.configurations[0].clients, 16);

// Print-only stress configuration must expose the trace mode without ever
// claiming a completed strict run.  Exercise both modes here so a future
// result-aggregation change cannot silently turn diagnostic evidence into a
// release pass (or make trace-off config unusable).
for (const [traceValue, expectedDiagnostics] of [
    ["0", {
        arrivalTraceEnabled: false,
        diagnosticOnly: false,
        strictEvidenceEligible: true,
    }],
    ["1", {
        arrivalTraceEnabled: true,
        diagnosticOnly: true,
        strictEvidenceEligible: false,
    }],
]) {
    const traceStressConfig = stressConfiguration("versions/" + CANONICAL_PROFILE.id + ".json", {
        GAIUS_BROWSER_FULL_PATH_TRACE: traceValue,
    });
    assert.equal(traceStressConfig.ok, true,
        `trace=${traceValue} print-config unexpectedly failed`);
    assert.equal(traceStressConfig.functionalOk, true,
        `trace=${traceValue} print-config functional marker drifted`);
    assert.equal(traceStressConfig.strictAcceptancePassed, null,
        `trace=${traceValue} print-config claimed a completed strict run`);
    assert.deepEqual(traceStressConfig.diagnostics, expectedDiagnostics,
        `trace=${traceValue} print-config diagnostics drifted`);
}

expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_STRESS: "1",
}, ["--print-config", "--acceptance", "--stress"]);
expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_STRESS: "1",
    GAIUS_BROWSER_FULL_PATH_STRESS_TIER: "32",
}, ["--print-config", "--stress"]);
expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_STRESS: "1",
    GAIUS_BROWSER_FULL_PATH_STRESS_TIER: "8",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "8",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "257",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "60000",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "2",
    GAIUS_BROWSER_FULL_PATH_CLIENT_VIEW_DISTANCE: "8",
    GAIUS_BROWSER_FULL_PATH_SERVER_VIEW_DISTANCE: "8",
    GAIUS_BROWSER_FULL_PATH_DESIRED_CHUNKS_PER_TICK: "32",
    GAIUS_EXTERNAL_RELAY_URL: "wss://example.invalid/tunnel",
}, ["--print-config", "--stress"]);

// The contract runner itself must not inherit a developer's strict gate.  This
// deliberately pollutes the parent environment with every browser gate knob;
// configuration() must clear all of them and inject the compatibility baseline.
const pollutedGateValues = {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "127",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
    GAIUS_BROWSER_FULL_PATH_SERVER_JAR: "poison.jar",
};
const savedPollutedGateValues = new Map(Object.keys(pollutedGateValues).map((name) => [
    name, process.env[name],
]));
try {
    Object.assign(process.env, pollutedGateValues);
    const sanitized = configuration("versions/" + CANONICAL_PROFILE.id + ".json");
    assert.equal(sanitized.acceptanceMode, false,
        "configuration inherited strict acceptance pollution");
    assert.equal(sanitized.clients, 2,
        "configuration inherited polluted client count");
    assert.equal(sanitized.performanceContract.minimumChunkPackets, 9,
        "configuration inherited polluted chunk target");
    assert.equal(sanitized.performanceContract.soakMillis, 1000,
        "configuration inherited polluted soak target");
    assert.equal(sanitized.performanceContract.reconnectWaves, 0,
        "configuration inherited polluted reconnect wave count");
}
finally {
    for (const [name, value] of savedPollutedGateValues) {
        if (value === undefined) delete process.env[name];
        else process.env[name] = value;
    }
}

for (const expected of expectedProfiles) {
    const strict = configuration(expected.path, {
        GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
        GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
        GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
        GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
        GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
    }, ["--print-config", "--acceptance"]);
    assert.equal(strict.acceptanceMode, true);
    assert.deepEqual(strict.strictAcceptanceTarget, {
        clients: 4,
        minimumChunkPackets: 9,
        soakMillis: 15000,
        reconnectWaves: 1,
    });
    assert.equal(strict.clients, 4);
    assert.deepEqual(strict.performanceContract.strictAcceptanceTarget,
        strict.strictAcceptanceTarget);
    assert.equal(strict.performanceContract.mode, "strict-acceptance");
    assertPollSchedulerProbeContract(strict,
        `${expected.id} strict config`);
    assertCallbackFinalizationTailContract(strict,
        `${expected.id} strict config`);
}

// 26.3 (protocol 777) is not a canonical strict-acceptance profile yet, but
// the compatible full-path client must drive it with its own jar-derived
// PLAY ids.  Before this check existed every non-1.21.11 profile silently
// borrowed the 26.2 ids (login 49, cache 94/95/111).
{
    const compatible263 = configuration("versions/" + RENDERPEARL_PROFILE.id + ".json");
    assert.equal(compatible263.profile.id, RENDERPEARL_PROFILE.id);
    assert.equal(compatible263.profile.protocolVersion, 777);
    assert.equal(compatible263.profile.worldVersion, 5023);
    assert.deepEqual(compatible263.wireProfile, { name: RENDERPEARL_PROFILE.id, protocolVersion: 777 });
    assert.equal(compatible263.acceptanceMode, false);
    assert.equal(compatible263.performanceContract.mode, "compatible-smoke");
    assert.equal(compatible263.performanceContract.canonicalProfiles[RENDERPEARL_PROFILE.id], undefined);
    assert.deepEqual(compatible263.performanceContract.chunkWindow.initialDistanceContract, {
        source: "clientbound-login",
        packetId: 50,
        fields: ["chunkRadius", "simulationDistance"],
    });
    assert.deepEqual(compatible263.performanceContract.chunkWindow.observedDistancePackets, {
        cacheCenter: 96,
        cacheRadius: 97,
        simulationDistance: 113,
    });
    assert.equal(compatible263.performanceContract.chunkBatch.clientboundFinishedPacketId, 11);
    assert.equal(compatible263.performanceContract.chunkBatch.clientboundStartPacketId, 12);
    assert.equal(
        compatible263.performanceContract.chunkBatch.serverboundAcknowledgementPacketId, 11);
    // Strict acceptance stays fail-closed for a profile without a canonical
    // release contract instead of evaluating it against 26.2 evidence.
    assert.throws(() => configuration("versions/" + RENDERPEARL_PROFILE.id + ".json", {
        GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
        GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
        GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
        GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
        GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
    }, ["--print-config", "--acceptance"]));
}

function expectConfigurationFailure(profilePath, overrides, argumentsList = ["--print-config"]) {
    assert.throws(() => configuration(profilePath, overrides, argumentsList));
}

const strictProfileOverrides = {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
};
const aliasProfileName = `.contract-profile-alias-${process.pid}-${Date.now()}.json`;
const resolverSource = fullPathSourceText.match(/^async function loadActiveVersionProfile\(\) \{[\s\S]*?^\}/mu)?.[0];
assert.ok(resolverSource, "active profile resolver source is required");
const repository = path.resolve(bridgeDirectory, "../..");
await assert.rejects(runInNewContext(`(${resolverSource})()`, {
    assert, path, repository,
    process: {env: {GAIUS_VERSION_PROFILE_PATH: `versions/${aliasProfileName}`}},
    nativePath: (value) => value,
    resolveRepositoryPath: (value) => path.resolve(repository, value),
    pathInside: (parent, child) => path.dirname(child) === parent,
    readFile: (selected, encoding) => readFile(selected.endsWith("config.json") ? selected
        : path.join(repository, "port", "versions", `${CANONICAL_PROFILE.id}.json`), encoding),
    releaseEvidenceMode: true,
}), /strict acceptance profile basename/u,
    "strict acceptance accepted a profile whose basename was not <id>.json");

expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4suffix",
});
expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "09",
});
expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000ms",
});
expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1.0",
});
expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "true",
});
expectConfigurationFailure("versions/" + CANONICAL_PROFILE.id + ".json", {}, ["--print-config", "--acceptance-suffix"]);

// Compatibility mode keeps the historical parseInt/clamp behavior, including
// values with a suffix; only strict acceptance is fail-closed.
assert.equal(configuration("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4suffix",
}).clients, 4);

assert.equal(configuration("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "0",
}).performanceContract.minimumChunkPackets, 1);
assert.equal(configuration("versions/" + LEGACY_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "999",
}).performanceContract.minimumChunkPackets, 128);
assert.equal(configuration("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
}).performanceContract.reconnectWaves, 1);
assert.equal(configuration("versions/" + CANONICAL_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "-1",
}).performanceContract.reconnectWaves, 0);
assert.equal(configuration("versions/" + LEGACY_PROFILE.id + ".json", {
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "999",
}).performanceContract.reconnectWaves, 8);
for (const [radius, capacity] of [[2, 25], [4, 77], [6, 157], [8, 257]]) {
    const configured = configuration("versions/" + CANONICAL_PROFILE.id + ".json", {
        GAIUS_BROWSER_FULL_PATH_CLIENT_VIEW_DISTANCE: String(radius),
        GAIUS_BROWSER_FULL_PATH_SERVER_VIEW_DISTANCE: String(radius),
    });
    assert.equal(configured.performanceContract.chunkWindow.effectiveRadius, radius);
    assert.equal(configured.performanceContract.chunkWindow.maximumUniqueChunkCapacity, capacity);
}

assert.throws(() => execFileSync(process.execPath, [fullPathScript], {
    cwd: bridgeDirectory,
    env: sanitizedEnvironment("versions/" + CANONICAL_PROFILE.id + ".json", {
        GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "128",
        GAIUS_BROWSER_FULL_PATH_CLIENT_VIEW_DISTANCE: "6",
        GAIUS_BROWSER_FULL_PATH_SERVER_VIEW_DISTANCE: "2",
    }),
    stdio: ["ignore", "pipe", "pipe"],
}), /Command failed/u,
"execution accepted a chunk target above the effective radius capacity");

const [relayMain, fullPathSource, multiplayerSource, verifyRuntimeSource,
    protocolSource] = await Promise.all([
    readFile(relayMainPath, "utf8"),
    readFile(fullPathScript, "utf8"),
    readFile(multiplayerPath, "utf8"),
    readFile(verifyRuntimePath, "utf8"),
    readFile(protocolPath, "utf8"),
]);
const [acceptanceRunner, stressRunner, packageSource] = await Promise.all([
    readFile(acceptanceRunnerPath, "utf8"),
    readFile(stressRunnerPath, "utf8"),
    readFile(packagePath, "utf8"),
]);
assert.match(fullPathSource,
    /for \(let wave = 1; wave <= reconnectWaves; wave\+\+\)/);
assert.match(fullPathSource,
    /forceAbnormalTransportDrop\(\s*browserRuntime, previousClients, wave\)/);
assert.match(fullPathSource,
    /probe\.entry\.ws\.terminate\(\)/);
assert.match(fullPathSource,
    /entry\.ws\.onmessage\(\{\s*data: tail\.buffer\.slice/);
assert.match(fullPathSource,
    /client\.close\("java-final-close"\)/);
assert.match(fullPathSource,
    /abnormal close discarded channel \$\{probe\.id\} synthetic inbound marker/);
assert.match(fullPathSource,
    /tailOffset \+ probe\.tail\.byteLength, drained\.byteLength/);
assert.match(fullPathSource,
    /syntheticInboundMarker: \{[\s\S]*?networkFrame: false/);
assert.match(fullPathSource,
    /\^WebSocket transport closed: \(\?!1000\\b\)\\d\+/);
assert.match(fullPathSource,
    /id: 700 \+ wave \* 100 \+ index/);
assert.match(fullPathSource,
    /"reconnect reused an AES shared secret"/);
assert.match(fullPathSource,
    /sessionAtChunks\.joins - sessionBeforeDrop\.joins/);
assert.match(fullPathSource,
    /snapshot\.target\.activeConnections === 0/);
assert.match(fullPathSource,
    /strict acceptance mode|strict acceptance/iu);
assert.match(stressRunner,
    /const functionalOk = printConfigOnly \|\| runs\.every\(\(run\) =>/u,
    "stress runner must retain a separate functional result");
assert.match(stressRunner,
    /const strictAcceptancePassed = printConfigOnly\s*\? null\s*:\s*functionalOk\s*&&\s*ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE\s*&&\s*runs\.every\(\(run\) =>[\s\S]*?run\.strictEvidenceEligible === true/u,
    "stress runner must require every executed run to carry strict evidence eligibility");
assert.match(stressRunner,
    /const ok = printConfigOnly \|\| strictAcceptancePassed === true/u,
    "stress runner ok result must fail closed when strict evidence is ineligible");
assert.match(stressRunner,
    /strictAcceptancePassed,/u,
    "stress runner must expose the strict acceptance result");
assert.match(fullPathSource,
    /GAIUS_BROWSER_FULL_PATH_CLIENTS/);
assert.match(fullPathSource,
    /GAIUS_BROWSER_FULL_PATH_SOAK_MS/);
assert.match(fullPathSource,
    /GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES/);
assert.match(fullPathSource,
    /partialEvidence:\s*\{[\s\S]*?clients: allClients\.map\(\(client\) =>\s*clientLivenessEvidence\(client\)\)/,
"failure evidence does not retain every client latency histogram");
assert.match(fullPathSource,
    /browserRuntime: partialBrowserRuntime/,
"failure evidence does not retain the browser transport snapshot");
assert.match(fullPathSource,
    /relayRuntime: partialRelayRuntime/,
"failure evidence does not retain the RelayNode runtime snapshot");
assert.match(fullPathSource,
    /performanceContract: browserFullPathPerformanceContract\(\)/,
"failure evidence does not bind diagnostics to the fixed performance contract");
assert.match(fullPathSource,
    /assertSoakLiveness\(/);
assert.match(fullPathSource,
    /onlineEncryptionResult\(\)/);
assert.match(fullPathSource,
    /activeLocalTunnelSessions/);
assert.match(fullPathSource,
    /pendingSyntheticPlayTicks/);
assert.match(fullPathSource,
    /activeServerFrameDrainHandles/);
assert.match(fullPathSource,
    /activeServerFrameDrainTimers/);
assert.match(fullPathSource,
    /activeClientStallTimers/);
assert.match(fullPathSource,
    /BROWSER_RUNTIME_CLEANUP_GAUGES/);
assert.match(fullPathSource,
    /activeHighWatermarks/);
assert.match(fullPathSource,
    /decodedSliceBacklog/);
assert.match(fullPathSource,
    /decoderCumulationBytes/);
assert.match(fullPathSource,
    /decodedPacketQueue/);
assert.match(fullPathSource,
    /const BROWSER_QUEUED_PACKET_SLOW_EVENT_LIMIT = 64/);
assert.match(fullPathSource,
    /queuedPacketHandleSamples: runtime\.stats\.queuedPacketHandleSamples \?\? null/);
assert.match(fullPathSource,
    /maxQueuedPacketHandleMillis: runtime\.stats\.maxQueuedPacketHandleMillis \?\? null/);
assert.match(fullPathSource,
    /maxQueuedPacketHandleType:[\s\S]*?typeof runtime\.stats\.maxQueuedPacketHandleType === "string"[\s\S]*?: null/);
assert.match(fullPathSource,
    /slowQueuedPacketEventSequence:[\s\S]*?runtime\.stats\.slowQueuedPacketEventSequence \?\? null/);
assert.match(fullPathSource,
    /slowQueuedPacketEventsDropped:[\s\S]*?runtime\.stats\.slowQueuedPacketEventsDropped \?\? null/);
assert.match(fullPathSource,
    /slowQueuedPacketEvents: Array\.isArray\(runtime\.stats\.slowQueuedPacketEvents\)[\s\S]*?\.slice\(-BROWSER_QUEUED_PACKET_SLOW_EVENT_LIMIT\)[\s\S]*?\.map\(\(event\) => \(\{ \.\.\.event \}\)\)/,
    "full-path evidence must defensively copy at most 64 queued-handler slow events");
assert.match(fullPathSource,
    /browserRuntimeCleanupGaugeEvidence/);
assert.match(fullPathSource,
    /const profileJavaVariable = `GAIUS_JAVA_\$\{profile\.javaVersion\}`/);
assert.match(fullPathSource,
    /runtimeJavaMajorMeetsPolicy\(profile, result\.major\)/);
assert.match(fullPathSource,
    /executable: result\.executable/);
assert.match(fullPathSource,
    /runtimeJavaExecutable/);
assert.match(fullPathSource,
    /runtimeJavaMajor/);
assert.match(fullPathSource,
    /--print-java-resolution/);
assert.match(fullPathSource,
    /major-exactly-21/);
assert.match(fullPathSource,
    /major-at-least-25/);
assert.match(fullPathSource,
    /path\.basename\(profilePath\) !== `\$\{profile\.id\}\.json`/);
assert.match(fullPathSource,
    /canonicalProfilePath: repositoryRelativePath\(activeProfile\.path\)/);
assert.match(fullPathSource,
    /syntheticMarkerLabel: "synthetic-inbound-marker"/);
assert.match(fullPathSource,
    /schemaVersion: "browser-full-path-result-v2"/);
assert.match(fullPathSource,
    /if \(soakMs > 0\) await delayAtLeast\(soakMs\)/);
assert.match(fullPathSource,
    /async function delayAtLeast\(durationMillis\)[\s\S]*?performance\.now\(\)[\s\S]*?Math\.ceil\(remaining\)/);
assert.doesNotMatch(fullPathSource,
    /if \(soakMs > 0\) await delay\(soakMs\)/,
    "strict soak must re-check its monotonic deadline after an early timer wakeup");
assert.match(fullPathSource,
    /authorization: `Bearer \$\{relayToken\}`/);
assert.match(fullPathSource,
    /client\.dropTimingResult\(dropAt\)/);
assert.match(fullPathSource,
    /const MULTIPLAYER_PERFORMANCE_TARGET = Object\.freeze\(/);
assert.match(fullPathSource,
    /maxConnectToMinimumChunksMillis: 15_000/);
assert.match(fullPathSource,
    /maxPreMinimumChunkPacketGapMillis: 500/);
assert.match(fullPathSource,
    /function assertClientPerformance\(client, label, options = \{\}\)/);
assert.match(fullPathSource,
    /function startSoakPerformanceObservation\(clients, browserRuntime\)/);
assert.match(fullPathSource,
    /maxBrowserEventLoopGapMillis/);
assert.match(fullPathSource,
    /assertSoakPerformance\(soakPerformance, "post-soak multiplayer performance"\)/);
assert.match(fullPathSource,
    /async function waitForBrowserInboundFlowReady\(runtime, label\)/);
assert.match(fullPathSource,
    /const evidence = browserInboundFlowEvidence\(snapshot, label\);[\s\S]*?return evidence\.ready/);
assert.match(fullPathSource,
    /assert\.ok\(evidence\.withinPauseLimit !== false/);
assert.ok(
    (fullPathSource.match(/await finishBrowserInboundFlowWindow\(/g) ?? []).length >= 4,
    "inbound-flow window evidence must gate initial, pre-drop, reconnect, and post-soak paths",
);
assert.match(fullPathSource,
    /inboundFlowEvidence\.initialConnectThroughMinimumChunks\s*=\s*\n?\s*await finishBrowserInboundFlowWindow/);
assert.match(fullPathSource,
    /inboundFlowEvidence\.reconnectWaves\.push\([\s\S]*?reconnectInboundFlow/);
assert.match(fullPathSource,
    /preDrop: preDropInboundFlow/);
assert.match(fullPathSource,
    /inboundFlowEvidence\.steadySoak = await finishBrowserInboundFlowWindow/);
assert.match(fullPathSource,
    /inboundFlowEvidence\.finalCleanup = assertBrowserInboundFlowWindow\(/);
assert.match(fullPathSource,
    /schemaVersion: BROWSER_INBOUND_FLOW_WINDOW_SCHEMA/);
assert.match(fullPathSource,
    /schemaVersion: "gaius\.browser-inbound-flow-evidence\.v1"/);
assert.match(fullPathSource,
    /maxContinuousFlowPauseMillis/);
assert.match(fullPathSource,
    /activeHighWatermarkLongestMillis/);
assert.match(fullPathSource,
    /source: "per-channel-high-watermark"/);
assert.match(fullPathSource,
    /const key = "browser-inbound-flow-stall";[\s\S]*?type: key/);
assert.match(fullPathSource,
    /MULTIPLAYER_PERFORMANCE_TARGET\.maxSoakPhaseStallMillis/);
assert.match(fullPathSource,
    /inboundFlow: inboundFlowEvidence/);
assert.match(fullPathSource,
    /00000000000040008000.*toString\(16\)\.padStart\(12, "0"\)/);
assert.match(fullPathSource,
    /profileUsernames: new Map/);
assert.match(fullPathSource,
    /state\.profileUsernames\.get\(String\(id\)\)/);
assert.match(fullPathSource,
    /clientboundChunkBatchStart/);
assert.match(fullPathSource,
    /clientboundChunkBatchFinished/);
assert.match(fullPathSource,
    /serverboundChunkBatchReceived/);
assert.match(fullPathSource,
    /stress tiers require the canonical local RelayNode and vanilla server/);
assert.match(fullPathSource,
    /function assertBrowserOutboundContinuationScheduler\(snapshot, label\)/);
assert.match(fullPathSource,
    /multiplayer budget continuation regressed to a clamped timer/);
assert.match(fullPathSource,
    /MessageChannel-one-callback-per-task/);
assert.match(fullPathSource,
    /client\.chunkBatchStarts, client\.chunkBatchFinished/);
assert.match(fullPathSource,
    /client\.chunkBatchFinished, client\.chunkBatchAcknowledgements/);
assert.match(fullPathSource,
    /client\.chunkBatchOpen, false/);
assert.match(fullPathSource,
    /client\.chunkBatchCountMismatches, 0/);
assert.match(fullPathSource,
    /chunkBatchInterruptedAtTransportDrop/);
assert.match(fullPathSource,
    /chunkBatchOpenAtTransportDrop/);
assert.match(fullPathSource,
    /chunkBatchPacketsAtTransportDrop/);
// Deterministic reconnect-boundary model: an intentionally dropped lifecycle
// may retain exactly one in-flight batch, but only when the boundary was
// recorded and every completed batch was ACKed.  An unmarked natural tail, a
// missing ACK, or more than one outstanding batch remains a hard failure.
const chunkBatchLifecycleAllowed = ({
    starts,
    finished,
    acknowledgements,
    open,
    interrupted,
    openAtDrop,
    closeReason,
}) => {
    if (interrupted) {
        return openAtDrop === true && closeReason === "java-final-close" &&
            starts >= finished && starts - finished === 1 && open === true &&
            finished === acknowledgements;
    }
    return starts === finished && finished === acknowledgements && open === false;
};
assert.equal(chunkBatchLifecycleAllowed({
    starts: 6, finished: 5, acknowledgements: 5, open: true,
    interrupted: true, openAtDrop: true, closeReason: "java-final-close",
}), true, "explicit transport-drop batch tail should be admissible");
assert.equal(chunkBatchLifecycleAllowed({
    starts: 6, finished: 6, acknowledgements: 6, open: false,
    interrupted: true, openAtDrop: true, closeReason: "java-final-close",
}), false, "an interrupted marker without an outstanding batch must fail closed");
assert.equal(chunkBatchLifecycleAllowed({
    starts: 6, finished: 5, acknowledgements: 5, open: true,
    interrupted: false, openAtDrop: false, closeReason: "java-final-close",
}), false, "unmarked natural batch tail must remain a hard failure");
assert.equal(chunkBatchLifecycleAllowed({
    starts: 7, finished: 5, acknowledgements: 5, open: true,
    interrupted: true, openAtDrop: true, closeReason: "java-final-close",
}), false, "more than one interrupted batch must remain a hard failure");
assert.equal(chunkBatchLifecycleAllowed({
    starts: 6, finished: 5, acknowledgements: 4, open: true,
    interrupted: true, openAtDrop: true, closeReason: "java-final-close",
}), false, "completed batches without ACK must remain a hard failure");
assert.equal(chunkBatchLifecycleAllowed({
    starts: 6, finished: 5, acknowledgements: 5, open: true,
    interrupted: true, openAtDrop: false, closeReason: "java-final-close",
}), false, "interrupted marker without an immutable open-at-drop snapshot must fail closed");
assert.equal(chunkBatchLifecycleAllowed({
    starts: 6, finished: 5, acknowledgements: 5, open: true,
    interrupted: true, openAtDrop: true, closeReason: "transport-drop",
}), false, "interrupted marker without Java final close must fail closed");
assert.match(fullPathSource,
    /acknowledgement\.writeFloatBE\(desiredChunksPerTick, 0\)/);
assert.match(fullPathSource,
    /function chunkTrackingCapacity\(viewDistance\)/);
assert.match(fullPathSource,
    /normalizedX \* normalizedX \+ normalizedZ \* normalizedZ/);
assert.match(fullPathSource,
    /minimumChunkMetric: stressMode \? "unique-chunk-position" : "chunk-packet"/);
assert.match(fullPathSource,
    /stressQualifiedChunkPositions\.size >= minimumChunkPackets/);
assert.match(fullPathSource,
    /observedDistanceContractRequiredBeforeCounting: stressMode/);
assert.match(fullPathSource,
    /client\.observedChunkCacheCenter\?\.x/);
assert.match(fullPathSource,
    /chunkTrackingCapacity\(client\.observedChunkCacheRadius\)/);
assert.match(fullPathSource,
    /const LATENCY_HISTOGRAM_BUCKETS_MILLIS = Object\.freeze\(\[[\s\S]*?16\.7[\s\S]*?Infinity/);
assert.match(fullPathSource,
    /schemaVersion: "gaius\.latency-histogram\.v1"/);
assert.match(fullPathSource,
    /pollGapHistogram: latencyHistogramResult\(this\.pollGapHistogram\)/);
assert.match(fullPathSource,
    /playTickGapHistogram: latencyHistogramResult\(this\.playTickGapHistogram\)/);
assert.match(fullPathSource,
    /preMinimumChunkGapHistogram:[\s\S]*?latencyHistogramResult\(this\.preMinimumChunkGapHistogram\)/);
assert.match(fullPathSource,
    /const MAX_INBOUND_FRAMES_PER_POLL = 8/);
assert.match(fullPathSource,
    /const MAX_PACKETS_PER_POLL = 64/);
assert.match(fullPathSource,
    /const CLIENT_POLL_INTERVAL_MILLIS = 1/);
assert.match(fullPathSource,
    /const MAX_CLIENTS_PER_POLL_CALLBACK = 4/);
assert.match(fullPathSource,
    /function createFairClientPollScheduler\(getClients\)/);
assert.match(fullPathSource,
    /mode: "round-robin-bounded-batch-per-macrotask"/);
assert.match(fullPathSource,
    /maxBatchClients: MAX_CLIENTS_PER_POLL_CALLBACK/);
assert.match(fullPathSource,
    /const CALLBACK_TAIL_SLOW_THRESHOLD_MILLIS = 16\.7/);
assert.match(fullPathSource,
    /const CALLBACK_TAIL_SAMPLE_LIMIT = 64/);
assert.match(fullPathSource,
    /callbackTail: \{/);
assert.match(fullPathSource,
    /CALLBACK_TAIL_TELEMETRY_SCHEMA_VERSION/);
assert.match(fullPathSource,
    /slowCallbackSamplesTotal/);
assert.match(fullPathSource,
    /slowCallbackSamplesDropped/);
assert.match(fullPathSource,
    /retainSlowCallbackSample/);
assert.match(fullPathSource,
    /phaseTimingsMillis/);
assert.match(fullPathSource,
    /pollCandidatesInspected/);
assert.match(fullPathSource,
    /tickCandidatesInspected/);
assert.match(fullPathSource,
    /maxPerTickDurationRawMillis/);
assert.match(fullPathSource,
    /maxPerPollDurationRawMillis/);
assert.match(fullPathSource,
    /budgetReachedPhase/);
assert.match(fullPathSource,
    /strictFrameBudgetExcessMillis/);
assert.match(fullPathSource,
    /slowCallbackSamples\.length > CALLBACK_TAIL_SAMPLE_LIMIT/);
assert.match(fullPathSource,
    /callbackFinalizationTail:\s*\{/u,
    "callback finalization-tail evidence object is missing");
for (const marker of [
    "finalizeStartAtMillis",
    "finalizeFinishAtMillis",
    "tailRawMillis",
    "totalAfterFinalizeRawMillis",
    "slowFinalizationTailSamplesTotal",
    "slowFinalizationTailSamplesDropped",
    "CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT",
]) {
    assert.ok(fullPathSource.includes(marker),
        `callback finalization-tail source omitted ${marker}`);
}
assert.match(fullPathSource,
    /finalizationTailRawMillis\s*=\s*finiteNonNegativeMillis/u,
    "callback finalization-tail must clamp non-finite raw duration");
assert.match(fullPathSource,
    /totalAfterFinalizeRawMillis\s*=\s*finiteNonNegativeMillis/u,
    "callback finalization-tail total endpoint is missing");
assert.match(fullPathSource,
    /candidate\.poll\(callbackSequenceNumber,\s*trigger\)/,
    "scheduler poll must carry callback provenance");
assert.doesNotMatch(fullPathSource,
    /setInterval\(\(\) => \{\s*for \(const client of currentClients\) client\.poll\(\)/,
    "full-path poll scheduler must not fan out every client in one timer callback");
assert.match(fullPathSource,
    /const pollSchedulerEvidence = pollScheduler\?\.evidence\(\) \?\? null/,
    "full-path result must capture bounded poll-scheduler evidence");
assert.match(fullPathSource,
    /while \(framesPolled < MAX_INBOUND_FRAMES_PER_POLL[\s\S]*?packetsRemaining > 0/);
assert.match(fullPathSource,
    /parsePackets\(maximumPackets = Number\.POSITIVE_INFINITY\)/);
assert.match(fullPathSource,
    /return parsedPackets;/);
assert.match(fullPathSource,
    /inboundDrainBudget: \{[\s\S]*?frameBudgetYields:[\s\S]*?packetBudgetYields:/);
assert.match(fullPathSource,
    /p99_9Millis: p999Millis/);
assert.match(fullPathSource,
    /latencyTarget\.pollGap\.p99Millis/);
assert.match(fullPathSource,
    /latencyTarget\.pollGap\.p999Millis/);
assert.match(fullPathSource,
    /latencyTarget\.playTickGap\.p99Millis/);
assert.match(fullPathSource,
    /latencyTarget\.playTickGap\.p999Millis/);
assert.match(fullPathSource,
    /latencyTarget\.preMinimumChunkGap\.p99Millis/);
assert.match(fullPathSource,
    /requireLatencyDistributions: false/);
assert.match(fullPathSource,
    /deferredUntilSteadySoak: !requireLatencyDistributions/);
assert.match(multiplayerSource,
    /acknowledgement\.writeFloatBE\(minecraftDesiredChunksPerTick, 0\)/);
assert.match(multiplayerSource,
    /clientboundSetChunkCacheCenter/);
assert.match(multiplayerSource,
    /clientboundSetChunkCacheRadius/);
assert.match(multiplayerSource,
    /clientboundSetSimulationDistance/);
{
    const relayProtocol = await import(pathToFileURL(protocolPath).href);
    const baseline = JSON.parse(await readFile(
        new URL("./test-fixtures/protocol-tables.json", import.meta.url), "utf8"));
    assert.deepEqual(baseline.map(({protocolVersion}) => {
        const profile = relayProtocol.resolveMinecraftProfile(protocolVersion);
        assert.ok(profile);
        return [
            profile.name,
            profile.protocolVersion,
            profile.play.clientboundLogin,
            profile.play.clientboundSetChunkCacheCenter,
            profile.play.clientboundSetChunkCacheRadius,
            profile.play.clientboundSetSimulationDistance,
            profile.play.clientboundChunkBatchFinished,
            profile.play.clientboundChunkBatchStart,
            profile.play.serverboundChunkBatchReceived,
        ];
    }), baseline.map(profile => [
        profile.name, profile.protocolVersion, profile.play.clientboundLogin,
        profile.play.clientboundSetChunkCacheCenter, profile.play.clientboundSetChunkCacheRadius,
        profile.play.clientboundSetSimulationDistance, profile.play.clientboundChunkBatchFinished,
        profile.play.clientboundChunkBatchStart, profile.play.serverboundChunkBatchReceived,
    ]));
}
assert.match(protocolSource,
    /export function decodeClientboundLoginDistances\(payload\)/);
assert.match(fullPathSource,
    /const initialDistances = decodeClientboundLoginDistances\(payload\)/);
assert.match(fullPathSource,
    /initialDistanceContract:[\s\S]*?source: "clientbound-login"[\s\S]*?fields: \["chunkRadius", "simulationDistance"\]/);

// Guard the PLAY control-flow ordering, not just field presence. Distance
// contract packets must be decoded before batch/chunk handling, and a batch
// must open, finish, ACK, and close through one ordered branch chain.
const playBranchOrder = [
    "clientboundSetChunkCacheCenter",
    "clientboundSetChunkCacheRadius",
    "clientboundSetSimulationDistance",
    "clientboundChunkBatchStart",
    "clientboundChunkBatchFinished",
    "clientboundChunk) {",
].map((marker) => fullPathSource.indexOf(marker));
assert.ok(playBranchOrder.every((index) => index >= 0),
    "full-path PLAY distance/batch/chunk branch missing");
for (let index = 1; index < playBranchOrder.length; index++) {
    assert.ok(playBranchOrder[index - 1] < playBranchOrder[index],
        "full-path PLAY distance/batch/chunk control flow changed order");
}
const batchStartBranch = fullPathSource.indexOf(
    "else if (packetId === this.profile.play.clientboundChunkBatchStart)");
const batchFinishedBranch = fullPathSource.indexOf(
    "else if (packetId === this.profile.play.clientboundChunkBatchFinished)");
const batchBranchSource = fullPathSource.slice(batchStartBranch, playBranchOrder.at(-1));
for (const marker of [
    "this.chunkBatchOpen = true",
    "if (!this.chunkBatchOpen)",
    "this.chunkBatchOpen = false",
    "acknowledgement.writeFloatBE(desiredChunksPerTick, 0)",
    "this.chunkBatchAcknowledgements++",
]) {
    assert.ok(batchBranchSource.includes(marker), `chunk-batch CFG omitted ${marker}`);
}
assert.ok(batchStartBranch < batchFinishedBranch,
    "chunk-batch finished branch moved before start branch");
assert.match(stressRunner,
    /const tiers = runAll \? \[8, 16\]/);
assert.match(stressRunner,
    /maximumUniqueChunkCapacity, 257/);
assert.match(stressRunner,
    /const HARD_DEADLINES_MILLIS = Object\.freeze/);
assert.match(stressRunner,
    /timedOut[\s\S]*?stderrTail[\s\S]*?parseError/);
assert.match(stressRunner,
    /if \(!ok\) process\.exitCode = 1/);
assert.match(stressRunner,
    /function validateStressResult\(result, tier, configuration\)/);
assert.match(stressRunner,
    /assertInboundFlowStage\(inboundFlow\.initial/);
assert.match(stressRunner,
    /inboundFlow\.reconnectWaves\.length/);
assert.match(stressRunner,
    /assertInboundFlowStage\(inboundFlow\.postSoak/);
assert.match(stressRunner,
    /assertInboundFlowStage\(inboundFlow\.finalCleanup/);
assert.match(stressRunner,
    /const INBOUND_FLOW_EVENT_FIELDS = Object\.freeze\(\[/);
assert.match(stressRunner,
    /isWindowEvidence \? INBOUND_FLOW_EVENT_FIELDS : INBOUND_FLOW_EVIDENCE_FIELDS/);
assert.match(stressRunner,
    /uniqueChunkPositionsTowardTarget/);
assert.match(stressRunner,
    /capturedAtElapsedMillis/);
assert.match(stressRunner,
    /derivedMaximumPauseMillis/);
assert.match(stressRunner,
    /pauseLimitMillis/);
assert.match(stressRunner,
    /resultValidationError === null/);
assert.match(stressRunner,
    /environment\.GAIUS_VERSION_PROFILE_PATH = canonicalProfilePath/);
assert.match(stressRunner,
    /const preservedGaiusNames = new Set\(\[/,
    "stress runner must define a fixed GAIUS_* allowlist");
assert.match(stressRunner,
    /!normalized\.startsWith\("GAIUS_"\)/,
    "stress runner must remove inherited non-allowlisted GAIUS_* variables");
assert.match(stressRunner,
    /process\.kill\(-child\.pid, "SIGTERM"\)/);
assert.match(stressRunner,
    /"taskkill\.exe"/);
assert.match(stressRunner,
    /"\/PID", String\(child\.pid\), "\/T", "\/F"/);
assert.match(stressRunner,
    /cleanupAttempted[\s\S]*?cleanupMethod[\s\S]*?cleanupError[\s\S]*?cleanupExitCode/);
assert.match(packageSource,
    /"stress:browser-full-path": "node browser-full-path-stress\.mjs"/);
assert.match(relayMain,
    /if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+`server data [\s\S]*?toString\("hex"\)/);
assert.match(relayMain,
    /if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+`client data [\s\S]*?toString\("hex"\)/);
assert.match(relayMain,
    /if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+`proxied \$\{keepAlivePhase\} keepalive profile=\$\{profile\.protocolVersion\}`/);
assert.doesNotMatch(relayMain,
    /proxied [\s\S]*?response\.toString\("hex"\)/,
    "KeepAlive trace must retain only fixed profile/phase scalars, not packet bytes");
assert.match(relayMain,
    /recordProxiedKeepAlive\(profile, keepAlivePhase\);/);
assert.equal(
    (relayMain.match(/if \(traceTunnel && protocolPhase === "play"\)/g) ?? []).length,
    2,
    "RelayNode must not decode PLAY packet ids solely for disabled tracing",
);
assert.match(relayMain, /const armClientStallTimer = \(\) => \{/);
assert.match(relayMain, /const clearClientStallTimer = \(\) => \{/);
assert.match(relayMain, /armClientStallTimer\(\);/);
assert.match(relayMain, /clearClientStallTimer\(\);/);
assert.match(relayMain, /"runtime-telemetry"/);
assert.match(relayMain, /runtime: relayRuntimeSnapshot\(\)/);
assert.match(relayMain, /const activeTunnelLeases = new Set\(\)/);
assert.match(relayMain, /activeTunnelLeases\.size/);
assert.match(relayMain, /activeTransportWebSockets: webSocketServer\.clients\.size/);
assert.match(verifyRuntimeSource, /"activeTunnelLeases"/);
assert.match(verifyRuntimeSource, /"activeTransportWebSockets"/);
assert.match(multiplayerSource, /activeTunnelLeasesAfterClose/);
assert.match(multiplayerSource, /logical and physical tunnel cleanup/);
assert.match(relayMain, /const maximumServerFrameDrainFrames = 32/);
assert.match(relayMain, /const maximumServerFrameDrainBytes = 512 \* 1024/);
assert.match(relayMain, /const maximumServerFrameDrainMillis = 2/);
assert.match(relayMain, /serverFrameTelemetry\.drainBudgetYields\+\+/);
assert.match(relayMain, /serverFrameTelemetry\.maxDrainDurationMillis/);
assert.match(relayMain, /if \(drainBudgetYielded\) \{\s+serverFrameDrainRescheduleRequested = true;/);
// A ws send callback can arrive after the tunnel has started teardown.  Keep
// that evidence in a bounded scalar without weakening the strict active/open
// send-error gate.
assert.match(relayMain, /sendErrorsAfterClose:\s*0/u,
    "RelayNode must initialize the after-close send-error scalar");
assert.match(relayMain,
    /const incrementServerFrameSendErrorsAfterClose = \(\) => \{[\s\S]*?Math\.min\([\s\S]*?Number\.MAX_SAFE_INTEGER[\s\S]*?sendErrorsAfterClose \+ 1/u,
    "RelayNode after-close send-error accounting must be bounded");
assert.match(relayMain,
    /serverFrameSendErrorsAfterClose:\s*serverFrameTelemetry\.sendErrorsAfterClose/u,
    "RelayNode must expose after-close send-error evidence");
assert.match(relayMain,
    /const sendErrorAfterClose = tunnelCancelled \|\|\s+webSocket\.readyState !== WebSocket\.OPEN/u,
    "RelayNode must classify send callbacks using teardown state");
assert.match(relayMain,
    /if \(sendErrorAfterClose\) \{\s+incrementServerFrameSendErrorsAfterClose\(\);/u,
    "closed callback errors must be separated from strict send errors");
assert.match(relayMain,
    /else \{\s+serverFrameTelemetry\.sendErrors\+\+;/u,
    "active/open callback errors must remain strict failures");
assert.match(relayMain,
    /if \(error && !tunnelCancelled &&\s+webSocket\.readyState === WebSocket\.OPEN\) \{\s+closeBoth/u,
    "active/open callback errors must still close the tunnel");

function classifyServerFrameSendError({readyState, tunnelCancelled}) {
    return tunnelCancelled || readyState !== "OPEN";
}

const sendErrorClassificationCases = [
    {readyState: "OPEN", tunnelCancelled: false, afterClose: false},
    {readyState: "CLOSING", tunnelCancelled: false, afterClose: true},
    {readyState: "CLOSED", tunnelCancelled: false, afterClose: true},
    {readyState: "OPEN", tunnelCancelled: true, afterClose: true},
];
const classifiedSendErrorCases = sendErrorClassificationCases.map((entry) => ({
    ...entry,
    actualAfterClose: classifyServerFrameSendError(entry),
}));
assert.deepEqual(
    classifiedSendErrorCases.map((entry) => entry.actualAfterClose),
    classifiedSendErrorCases.map((entry) => entry.afterClose),
    "send-error teardown classification model drifted",
);
assert.equal(
    classifiedSendErrorCases.filter((entry) => !entry.actualAfterClose).length,
    1,
    "open send error must remain in strict accounting",
);
assert.equal(
    classifiedSendErrorCases.filter((entry) => entry.actualAfterClose).length,
    3,
    "closed/cancelled send errors must be retained separately",
);
assert.equal(
    classifiedSendErrorCases.length,
    4,
    "send-error evidence model dropped a teardown case",
);
assert.match(relayMain, /const publicDnsCache = new Map\(\)/);
assert.match(relayMain, /const publicDnsCacheTtlMs = 5_000/);
assert.match(relayMain, /httpServer\.on\("upgrade", \(request, socket, head\) => \{\s+\/\/ Minecraft status[\s\S]*?socket\.setNoDelay\(true\);\s+socket\.setKeepAlive\(true, 30_000\);/);
assert.match(relayMain, /const maximumPublicDnsCacheEntries = 1024/);
assert.match(relayMain, /let publicDnsCacheHits = 0/);
assert.match(relayMain, /let publicDnsCacheMisses = 0/);
assert.match(relayMain, /let publicDnsCacheInflightJoins = 0/);
assert.match(relayMain, /function resolvePublicAddresses\(host, lookupOptions\)/);
assert.match(relayMain, /if \(cached\?\.promise !== undefined\) \{/);
assert.match(relayMain, /publicDnsCacheInflightJoins\+\+/);
assert.match(relayMain, /publicDnsCache\.set\(key, \{\s+addresses: publicAddresses,/);
assert.match(relayMain, /if \(current\?\.promise === promise\) \{\s+publicDnsCache\.delete\(key\)/);
assert.match(relayMain, /while \(publicDnsCache\.size > maximumPublicDnsCacheEntries\)/);
const externalMultiplayerSource = await readFile(
    new URL("../../port/scripts/browser-relay-external-multiplayer-smoke.mjs", import.meta.url),
    "utf8",
);
assert.match(externalMultiplayerSource, /function dnsCacheRuntime\(manifest\)/);
assert.match(externalMultiplayerSource, /Capture target activity before issuing that probe/);
assert.match(externalMultiplayerSource, /if \(!bridge\.channels\.has\(client\.id\)\)/);
assert.match(externalMultiplayerSource, /client\.closed = true;\s+client\.failure = String\(error\?\.stack \|\| error\);/);
assert.doesNotMatch(externalMultiplayerSource, /client\.pingSent === 0\) \{/,
    "external multiplayer smoke must not be limited to one ping per client");
assert.match(externalMultiplayerSource, /pingResponseGaps/);
assert.match(externalMultiplayerSource, /pingResponseGapLimitMillis/);
assert.match(externalMultiplayerSource, /external status ping drain/);
assert.match(externalMultiplayerSource, /dnsCacheTelemetry/);
assert.match(externalMultiplayerSource, /function relayRuntimeSnapshot\(manifest\)/);
assert.match(externalMultiplayerSource, /runtimeTelemetry/);
assert.match(externalMultiplayerSource, /dnsLookupsShared/);
assert.match(externalMultiplayerSource, /minimumSharedDnsLookups/);
assert.match(externalMultiplayerSource, /Hold the STATUS request until every relay-connected edge is up/);
assert.match(externalMultiplayerSource,
    /await Promise\.all\(targetManifestSamples\);\s+for \(const client of clients\) client\.sendInitial\(\);/,
    "external multiplayer smoke must establish target overlap before terminal STATUS requests",
);
assert.match(externalMultiplayerSource, /targetConcurrencyEvidence/);
assert.match(externalMultiplayerSource, /edgeObservations/);
assert.match(fullPathSource, /const relayRuntimeTelemetryRequired = !externalMode \|\| acceptanceMode/);
assert.match(fullPathSource, /strict acceptance requires every external RelayNode runtime gauge/);
assert.match(fullPathSource, /runtimeTelemetryRequired: relayRuntimeTelemetryRequired/);
assert.match(fullPathSource, /RELAY_RUNTIME_CONNECTION_GAUGES/);
assert.match(fullPathSource, /activeTunnelLeases/);
assert.match(fullPathSource, /activeTransportWebSockets/);
assert.doesNotMatch(
    relayMain,
    /updateTcpReadState = \(\) => \{[\s\S]*?\};\s+clientStallTimer = setInterval/,
    "RelayNode must not arm an idle stall timer before a connection reaches PLAY",
);
assert.match(acceptanceRunner,
    /for \(const profile of profiles\)/);
assert.match(acceptanceRunner,
    /await runProfile\(profile\)/);
assert.match(acceptanceRunner,
    /GAIUS_BROWSER_FULL_PATH_CLIENTS: "4"/);
assert.match(acceptanceRunner,
    /GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9"/);
assert.match(acceptanceRunner,
    /GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000"/);
assert.match(acceptanceRunner,
    /GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1"/);
assert.match(acceptanceRunner,
    /validateChildResult\(profile, report/);
assert.match(acceptanceRunner,
    /performanceContract\?\.canonicalProfiles\?\.\[profile\]/);
assert.match(acceptanceRunner,
    /const actual = results\.map\(\(entry\) => entry\.actual/);
assert.match(acceptanceRunner,
    /const observed = results\.map\(\(entry\) => entry\.observed/);
assert.match(acceptanceRunner,
    /schemaVersion: "browser-full-path-acceptance-v3"/);
assert.match(acceptanceRunner,
    /acceptance\.required exact schema/);
assert.match(acceptanceRunner,
    /runtimeJavaPolicy/);
assert.match(acceptanceRunner,
    /checkRuntimeJava\(/);
assert.match(acceptanceRunner,
    /requiredChildAcceptance/);
assert.match(acceptanceRunner,
    /relayDrainMaxDurationMillis: 16\.7/);
assert.match(acceptanceRunner,
    /relayDrainSendErrors: 0/);
assert.match(acceptanceRunner,
    /relayDrainCleanupRequired: true/);
assert.match(acceptanceRunner,
    /multiplayerPerformance: \{ \.\.\.multiplayerPerformanceTarget \}/);
assert.match(acceptanceRunner,
    /stableSerialize\(actual\) === stableSerialize\(wanted\)/);
assert.match(acceptanceRunner,
    /queuedBytesAfterClose/);
assert.match(acceptanceRunner,
    /queuedFramesAfterClose/);
assert.match(acceptanceRunner,
    /inboundQueuedBytesAfterClose/);
assert.match(acceptanceRunner,
    /activeRelayTargetLeasesAfterClose/);
assert.match(acceptanceRunner,
    /runs: results\.map/);
assert.match(acceptanceRunner,
    /PROFILE_TIMEOUT_MS = 600000/);
assert.match(acceptanceRunner,
    /detached: process\.platform !== "win32"/);
assert.match(acceptanceRunner,
    /process\.kill\(-pid, signal\)/);
assert.match(acceptanceRunner,
    /process\.kill\(-pid, 0\)/);
assert.match(acceptanceRunner,
    /groupProbe/);
assert.match(acceptanceRunner,
    /taskkill\.exe/);
assert.match(acceptanceRunner,
    /"\/T", "\/F"/);
assert.match(acceptanceRunner,
    /shell: false/);
assert.match(acceptanceRunner,
    /cleanupConfirmed: false/);
assert.match(acceptanceRunner,
    /PRESERVED_GAIUS_ENV_NAMES/);
assert.match(acceptanceRunner,
    /upperName\.startsWith\("GAIUS_"\)/);
assert.match(acceptanceRunner,
    /GAIUS_MAXIMUM_CONNECTIONS/);
assert.match(acceptanceRunner,
    /GAIUS_FRAME_MAX_BYTES/);
assert.match(acceptanceRunner,
    /GAIUS_REGISTRY_URL/);
assert.match(acceptanceRunner,
    /GAIUS_DNS_RETRY_LIMIT/);
assert.match(acceptanceRunner,
    /--contract-env/);
assert.match(acceptanceRunner,
    /process\.exitCode = 1/);
assert.throws(() => execFileSync(process.execPath, [acceptanceRunnerPath], {
    cwd: bridgeDirectory,
    env: {
        ...process.env,
        GAIUS_BROWSER_FULL_PATH_CLIENTS: "4suffix",
    },
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
}), "acceptance runner must reject inherited suffixed gate parameters");
const packageJson = JSON.parse(packageSource);
assert.equal(packageJson.scripts["smoke:browser-full-path-acceptance"],
    "node browser-full-path-acceptance.mjs");
const environmentProbe = JSON.parse(execFileSync(process.execPath, [
    acceptanceRunnerPath, "--contract-env",
], {
    cwd: bridgeDirectory,
    env: {
        ...process.env,
        GAIUS_MAXIMUM_CONNECTIONS: "poison-connections",
        GAIUS_MAXIMUM_FRAME_BYTES: "poison-frame",
        GAIUS_FRAME_MAX_BYTES: "poison-frame-alias",
        GAIUS_RELAY_URL: "ws://poison.invalid/relay",
        GAIUS_RELAY_REGISTRY: "https://poison.invalid/registry",
        GAIUS_REGISTRY_URL: "https://poison.invalid/registry",
        GAIUS_REGISTRY_TOKEN: "poison-token",
        GAIUS_DNS_TEST_MODE: "poison-dns-test",
        GAIUS_DNS_RETRY_LIMIT: "poison-dns",
        GAIUS_DNS_RETRY_BACKOFF_MS: "poison-backoff",
        GAIUS_TRACE_TUNNEL: "poison-trace",
        GAIUS_ALLOW_PRIVATE_TARGETS: "1",
        GAIUS_TARGET_AFFINITY: "poison-target",
        GAIUS_BROWSER_FULL_PATH_SERVER_JAR: "poison-server.jar",
        GAIUS_SMOKE_SERVER_JAR: "poison-smoke-server.jar",
        GAIUS_JAVA: "contract-java",
        GAIUS_JAVA_HOME: "contract-java-home",
        GAIUS_JAVA_21: "contract-java-21",
        GAIUS_JAVA_25: "contract-java-25",
    },
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
}));
assert.deepEqual(environmentProbe.remainingPollution, [],
    "runner leaked inherited GAIUS_* pollution into the child environment");
assert.deepEqual(environmentProbe.removedPollution, [
    "GAIUS_MAXIMUM_CONNECTIONS",
    "GAIUS_MAXIMUM_FRAME_BYTES",
    "GAIUS_FRAME_MAX_BYTES",
    "GAIUS_RELAY_URL",
    "GAIUS_RELAY_REGISTRY",
    "GAIUS_REGISTRY_URL",
    "GAIUS_REGISTRY_TOKEN",
    "GAIUS_DNS_TEST_MODE",
    "GAIUS_DNS_RETRY_LIMIT",
    "GAIUS_DNS_RETRY_BACKOFF_MS",
    "GAIUS_TRACE_TUNNEL",
    "GAIUS_ALLOW_PRIVATE_TARGETS",
    "GAIUS_TARGET_AFFINITY",
    "GAIUS_BROWSER_FULL_PATH_SERVER_JAR",
    "GAIUS_SMOKE_SERVER_JAR",
]);
assert.deepEqual(environmentProbe.preservedJava, [
    "GAIUS_JAVA",
    "GAIUS_JAVA_HOME",
    "GAIUS_JAVA_21",
    "GAIUS_JAVA_25",
]);
assert.deepEqual(environmentProbe.fixed, {
    acceptance: "1",
    clients: "4",
    minChunks: "9",
    soakMillis: "15000",
    reconnectWaves: "1",
    origin: "http://127.0.0.1:8781",
    allowedHosts: "127.0.0.1",
});

// Stress runs must use the same GAIUS_* isolation rule as strict acceptance.
// This is a print-config-only probe: it injects relay/DNS/capacity pollution
// and proves the child still stays on the canonical local 26.2 tier-8 target.
assert.match(stressRunner,
    /preservedGaiusNames/,
    "stress runner must declare its GAIUS allowlist");
assert.match(stressRunner,
    /!normalized\.startsWith\("GAIUS_"\)/,
    "stress runner must remove inherited non-allowlisted GAIUS_* variables");
const stressEnvironmentProbe = JSON.parse(execFileSync(process.execPath, [
    stressRunnerPath, "--tier=8", "--print-config",
], {
    cwd: bridgeDirectory,
    env: {
        ...process.env,
        GAIUS_RELAY_REGISTRY_URL: "https://poison.invalid/registry",
        GAIUS_RELAY_PUBLIC_URL: "https://poison.invalid/relay",
        GAIUS_RELAY_REGISTRY_TOKEN: "poison-token",
        GAIUS_RELAY_NODE_ID: "poison-node",
        GAIUS_TRACE_TUNNEL: "1",
        GAIUS_RELAY_FRAME_TIMELINE: "1",
        GAIUS_DNS_TEST_MODE: "poison-dns",
        GAIUS_ALLOW_PRIVATE_TARGETS: "1",
        GAIUS_MAXIMUM_CONNECTIONS: "poison-connections",
        GAIUS_TARGET_AFFINITY_MS: "poison-affinity",
        GAIUS_SMOKE_SERVER_JAR: "poison-server.jar",
        GAIUS_BUILD_ROOT: "poison-build-root",
    },
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
}));
assert.equal(stressEnvironmentProbe.ok, true,
    "stress runner pollution probe did not remain print-config green");
assert.deepEqual(stressEnvironmentProbe.tiers, [8],
    "stress runner pollution probe changed the requested tier");
assert.equal(stressEnvironmentProbe.configurations.length, 1,
    "stress runner pollution probe omitted its configuration");
const stressProbeConfiguration = stressEnvironmentProbe.configurations[0];
assert.equal(stressProbeConfiguration.profile.id, CANONICAL_PROFILE.id,
    "stress runner pollution changed the profile id");
assert.equal(stressProbeConfiguration.profile.protocolVersion, 776,
    "stress runner pollution changed the protocol");
assert.equal(stressProbeConfiguration.profile.worldVersion, 4903,
    "stress runner pollution changed the world version");
assert.equal(stressProbeConfiguration.profile.javaVersion, 25,
    "stress runner pollution changed the Java policy");
assert.equal(stressProbeConfiguration.profile.serverSha1,
    "823e2250d24b3ddac457a60c92a6a941943fcd6a",
    "stress runner pollution changed the server identity");
assert.equal(stressProbeConfiguration.profile.expectedServerJarSha1,
    "823e2250d24b3ddac457a60c92a6a941943fcd6a",
    "stress runner pollution changed the expected server identity");
assert.equal(stressProbeConfiguration.profile.canonicalProfilePath,
    "port/versions/" + CANONICAL_PROFILE.id + ".json",
    "stress runner pollution changed the profile path");
assert.equal(stressProbeConfiguration.performanceContract.externalRelay, null,
    "stress runner inherited an external RelayNode target");
assert.equal(stressProbeConfiguration.performanceContract.stressTarget.clients, 8,
    "stress runner inherited a client-count override");
assert.equal(stressProbeConfiguration.performanceContract.stressTarget.minimumChunkPackets,
    257,
    "stress runner inherited a chunk-count override");
assert.equal(stressProbeConfiguration.performanceContract.stressTarget.soakMillis,
    60000,
    "stress runner inherited a soak override");
assert.equal(stressProbeConfiguration.performanceContract.pollScheduler.callbackFinalizationTail
    .strictGatesChanged, false,
    "stress runner pollution changed strict callback gates");

// Dynamic resolver contract: profile-specific candidates must win over a
// generic candidate, and the returned major/source must satisfy each strict
// profile policy. This launches only tiny local fake-java fixtures, never a
// server or RelayNode.
const javaFixtureDirectory = await mkdtemp(path.join(tmpdir(),
    "gaius-browser-java-contract-"));
const javaFixtureSuffix = process.platform === "win32" ? ".cmd" : ".sh";
const javaFixture = async (name, major) => {
    const fixturePath = path.join(javaFixtureDirectory, `${name}${javaFixtureSuffix}`);
    const contents = process.platform === "win32"
        ? `@echo off\r\necho openjdk version "${major}.0.0" 1>&2\r\nexit /b 0\r\n`
        : `#!/bin/sh\nprintf 'openjdk version "${major}.0.0"\\n' 1>&2\n`;
    await writeFile(fixturePath, contents, "utf8");
    if (process.platform !== "win32") await chmod(fixturePath, 0o755);
    return fixturePath;
};
try {
    const genericJava = await javaFixture("generic", 26);
    const java21 = await javaFixture("profile-21", 21);
    const java25 = await javaFixture("profile-25", 25);
    const resolutionEnvironment = (profilePath, profileVariable) => ({
        ...sanitizedEnvironment(profilePath, {
            GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
            GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
            GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
            GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
            GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
            GAIUS_JAVA: genericJava,
            GAIUS_JAVA_21: java21,
            GAIUS_JAVA_25: java25,
        }),
        [profileVariable]: profileVariable === "GAIUS_JAVA_21" ? java21 : java25,
    });
    const resolveProfile = (profilePath, profileVariable) => JSON.parse(
        execFileSync(process.execPath, [fullPathScript, "--print-java-resolution"], {
            cwd: bridgeDirectory,
            env: resolutionEnvironment(profilePath, profileVariable),
            encoding: "utf8",
            stdio: ["ignore", "pipe", "pipe"],
        }));
    const resolved21 = resolveProfile("versions/" + LEGACY_PROFILE.id + ".json", "GAIUS_JAVA_21");
    assert.equal(resolved21.profile.id, LEGACY_PROFILE.id);
    assert.equal(resolved21.runtimeJavaMajor, 21);
    assert.equal(resolved21.runtimeJavaSource, "GAIUS_JAVA_21");
    assert.match(resolved21.runtimeJavaExecutable,
        /profile-21\.(?:cmd|sh)$/u);
    assert.equal(resolved21.runtimeJavaPolicy, "major-exactly-21");
    const resolved25 = resolveProfile("versions/" + CANONICAL_PROFILE.id + ".json", "GAIUS_JAVA_25");
    assert.equal(resolved25.profile.id, CANONICAL_PROFILE.id);
    assert.equal(resolved25.runtimeJavaMajor, 25);
    assert.equal(resolved25.runtimeJavaSource, "GAIUS_JAVA_25");
    assert.match(resolved25.runtimeJavaExecutable,
        /profile-25\.(?:cmd|sh)$/u);
    assert.equal(resolved25.runtimeJavaPolicy, "major-at-least-25");
}
finally {
    await rm(javaFixtureDirectory, { recursive: true, force: true });
}

console.log(JSON.stringify({
    ok: true,
    profiles: expectedProfiles.map(({ id, protocol, world }) => ({ id, protocol, world })),
    minimumChunkPackets: { default: 9, minimum: 1, maximum: 128 },
    reconnectWaves: { default: 0, minimum: 0, maximum: 8 },
    strictAcceptance: {
        clients: 4,
        minimumChunkPackets: 9,
        soakMillis: 15000,
        reconnectWaves: 1,
        profileOrder: [CANONICAL_PROFILE.id, LEGACY_PROFILE.id],
        runtimeJavaPolicy: {
            [LEGACY_PROFILE.id]: "major-exactly-21",
            [CANONICAL_PROFILE.id]: "major-at-least-25",
        },
    },
    stressTiers: {
        optInOnly: true,
        clients: [8, 16],
        uniqueChunksPerClient: 257,
        clientViewDistance: 8,
        serverViewDistance: 8,
        simulationDistance: 4,
        soakMillis: { 8: 60000, 16: 120000 },
        reconnectWaves: { 8: 2, 16: 4 },
        clientLifecycles: { 8: 24, 16: 80 },
        desiredChunksPerTick: { 8: 32, 16: 64 },
    },
    pollPhaseTelemetry: {
        schemaVersion: "gaius.browser-client-poll-phase.v1",
        segmentAccounting: "inclusive-overlapping",
        slowThresholdMillis: 16.7,
        sampleLimit: 64,
        frameSampleLimit: 8,
        packetSampleLimit: 64,
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
    arrivalTimeline: {
        schemaVersion: "gaius.browser-client-arrival-timeline.v1",
        wireAtSource: "unavailable",
        attributionPolicy:
            "trusted-wire-required-for-upstream; missing-local-segments=>unattributed",
    },
    chunkBatchProtocol: {
        clientboundFinished: 11,
        clientboundStart: 12,
        serverboundAcknowledgement: { [LEGACY_PROFILE.id]: 10, [CANONICAL_PROFILE.id]: 11 },
        acknowledgementEncoding: "float32-be",
    },
    observedDistancePackets: {
        initialDistanceContract: {
            source: "clientbound-login",
            packetIds: { [LEGACY_PROFILE.id]: 48, [CANONICAL_PROFILE.id]: 49 },
            fields: ["chunkRadius", "simulationDistance"],
        },
        [LEGACY_PROFILE.id]: { cacheCenter: 92, cacheRadius: 93, simulationDistance: 109 },
        [CANONICAL_PROFILE.id]: { cacheCenter: 94, cacheRadius: 95, simulationDistance: 111 },
        requiredBeforeStressChunkCounting: true,
    },
    stressLatencyHistograms: {
        bucketUpperBoundsMillis: [
            1, 2, 4, 8, 16.7, 25, 50, 60, 75, 100, 250, 500, 1000, null,
        ],
        output: ["count", "p95Millis", "p99Millis", "p999Millis", "maxMillis"],
        pollGap: { p99Millis: 16.7, p999Millis: 50, maxMillis: 100 },
        playTickGap: { p99Millis: 60, p999Millis: 75, maxMillis: 100 },
        preMinimumChunkGap: { p99Millis: 100, maxMillis: 250 },
        rawSamplesRetained: false,
    },
    reconnectContract: {
        simultaneousDrop: true,
        abnormalTransportDrop: true,
        closeErrorAndSyntheticMarkerRetained: true,
        javaFinalCloseAfterTransportDrop: true,
        freshChannelIds: true,
        sameAccountIdentity: true,
        freshProtocolBuffers: true,
        freshEncryptionState: true,
        sessionJoinPerClientPerWave: true,
        relayAndBrowserCleanupEvidence: true,
    },
    lifecycleCleanupRequired: true,
    traceFormattingGuarded: true,
    stallTimerArmedOnlyInPlay: true,
    generationGuard: {
        schemaVersion: "gaius.browser-inbound-pump-generation-guard.v1",
        invalidPendingCases: generationGuardEvidence,
        legacyNoPendingNormalization: true,
    },
}));
