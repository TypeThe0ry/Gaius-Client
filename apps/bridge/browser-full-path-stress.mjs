/*
 * Explicit high-load multiplayer runner.
 *
 * The ordinary smoke and --acceptance contracts stay bounded at 2/4 clients.
 * This entry point is the only supported way to opt into the fixed 8/16 client,
 * radius-8 / 257-unique-chunk extreme tiers.
 */

import {CANONICAL_PROFILE} from "../../port/scripts/verification-profiles.mjs";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const fullPathScript = fileURLToPath(
    new URL("./browser-full-path-smoke.mjs", import.meta.url));
const channelSourcePath = fileURLToPath(new URL(
    "../../port/overrides/libraries/netty-transport/src/main/java/" +
        "io/netty/channel/browser/BrowserWebSocketChannel.java",
    import.meta.url,
));
const BROWSER_CHANNEL_SOURCE_EVIDENCE_SCHEMA_VERSION =
    "gaius.browser-websocket-channel-source.v1";
const BROWSER_CHANNEL_SOURCE_MARKERS = Object.freeze([
    ["pumpAllAndReportProgress", "public static boolean pumpAllAndReportProgress()", false],
    ["aggregatePumpBudget", "private static final double MAX_TOTAL_MILLIS_PER_PUMP = 4.0;", false],
    ["initBridge", "private static native void initBridge();", true],
    ["initBridgeTail", "private static native void initBridgeTail();", true],
    ["initOutboundScheduler", "private static native void initOutboundScheduler();", true],
    ["initInboundScheduler", "private static native void initInboundScheduler();", true],
    ["relayNodeRecordResolverRead", "const resolver = state.relayNodeRecordResolver;", false],
    ["relayNodeRecordResolverPublish", "state.relayNodeRecordResolver = relayNodeRecord;", false],
].map(([name, marker, jsBody]) => Object.freeze({name, marker, jsBody})));
const canonicalProfilePath = fileURLToPath(
    new URL("../../port/versions/" + CANONICAL_PROFILE.id + ".json", import.meta.url));
const canonicalEvidenceRoot = fileURLToPath(
    new URL("../../port/target/" + CANONICAL_PROFILE.id + "/browser-relay-full-path-evidence/", import.meta.url));
const HARD_DEADLINES_MILLIS = Object.freeze({
    8: 20 * 60_000,
    16: 40 * 60_000,
});
const INBOUND_FLOW_EVIDENCE_FIELDS = Object.freeze([
    "flowPausedChannels",
    "decodeFlowPausedChannels",
    "activeHighWatermarks",
    "decodedSliceBacklog",
    "decoderCumulationBytes",
    "decodedPacketQueue",
    "flowPauses",
    "flowResumes",
    "decodedSliceBacklogPauses",
    "decodedSliceBacklogResumes",
    "inlineDecodedPackets",
    "maxDecoderCumulationBytes",
    "maxDecodedPacketQueue",
    "highWatermarkDurationMillis",
    "longestHighWatermarkMillis",
    "activeHighWatermarkLongestMillis",
]);
const INBOUND_FLOW_EVENT_FIELDS = Object.freeze([
    "sequence",
    "channelId",
    "reason",
    "startedAtMillis",
    "endedAtMillis",
    "durationMillis",
    "startDepth",
    "endDepth",
    "startQueuedBytes",
    "endQueuedBytes",
]);
const INBOUND_FLOW_DURATION_FIELDS = new Set([
    "highWatermarkDurationMillis",
    "longestHighWatermarkMillis",
    "activeHighWatermarkLongestMillis",
]);
const MAX_STDOUT_BYTES = 64 * 1024 * 1024;
const MAX_DIAGNOSTIC_TAIL_BYTES = 64 * 1024;
const POLL_PHASE_TELEMETRY_SCHEMA_VERSION =
    "gaius.browser-client-poll-phase.v1";
const POLL_PHASE_SLOW_THRESHOLD_MILLIS = 16.7;
const POLL_PHASE_SAMPLE_LIMIT = 64;
const POLL_PHASE_FRAME_SAMPLE_LIMIT = 8;
const POLL_PHASE_PACKET_SAMPLE_LIMIT = 64;
const POLL_PHASE_PACKET_ID_SAMPLE_LIMIT = 8;
const POLL_PHASE_SEGMENT_ACCOUNTING = "inclusive-overlapping";
const CALLBACK_FINALIZATION_TAIL_TELEMETRY_SCHEMA_VERSION =
    "gaius.browser-client-poll-callback-finalization-tail.v1";
const CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS = 16.7;
const CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT = 64;
const CALLBACK_FINALIZATION_TAIL_RETENTION = "longest-tail-desc-sequence-asc";
const ARRIVAL_WIRE_AT_SOURCE = "unavailable";
const ARRIVAL_TRACE_SCHEMA_VERSION =
    "gaius.browser-client-arrival-trace.v1";
const ARRIVAL_TRACE_EVENT_LIMIT = 64;
const ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE =
    process.env.GAIUS_BROWSER_FULL_PATH_TRACE !== "1";
const ARRIVAL_PERIODIC_SERVER_SYNC_SCHEMA_VERSION =
    "gaius.browser-client-arrival-periodic-server-sync.v1";
const ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION = "periodic-server-sync";
const ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID = CANONICAL_PROFILE.id;
const ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION = 776;
const ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID = 113;
const ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS = 1000;
const ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS = 125;
const POLL_PHASE_SEGMENT_FIELDS = Object.freeze([
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
const POLL_PHASE_TRIGGERS = new Set([
    "poll-duration",
    "bridge-drain",
    "decrypt-transform",
    "buffer-concat",
    "parse-inflate",
    "packet-dispatch",
    "prelude",
    "finalize",
]);
const argumentsList = process.argv.slice(2);
const printConfigOnly = removeFlag("--print-config");
const runAll = removeFlag("--all");
const selectedTier = readTierArgument();
if (argumentsList.length !== 0) {
    throw new Error(`Unsupported stress runner arguments: ${argumentsList.join(" ")}`);
}
if (runAll && selectedTier !== undefined) {
    throw new Error("--all and --tier are mutually exclusive");
}
const tiers = runAll ? [8, 16] : [selectedTier ?? 8];
const browserChannelSourceEvidence = await inspectBrowserChannelSource();

const configurations = [];
const runs = [];
const results = [];
for (const tier of tiers) {
    const environment = stressEnvironment(tier);
    const configurationRun = await runChild(
        ["--print-config", "--stress"], environment, 30_000, false);
    const configuration = requireSuccessfulResult(
        configurationRun, `stress tier ${tier} configuration`);
    assert.equal(configuration.stressMode, true);
    assert.equal(configuration.acceptanceMode, false);
    assert.equal(configuration.stressTier, tier);
    assert.equal(configuration.profile.id, CANONICAL_PROFILE.id);
    assert.equal(configuration.profile.protocolVersion, 776);
    assert.equal(configuration.profile.worldVersion, 4903);
    assert.equal(configuration.clients, tier);
    assertSourceProvenance(
        configuration.browserChannelSourceEvidence,
        browserChannelSourceEvidence,
        `stress tier ${tier} configuration`,
    );
    assertSourceProvenance(
        configuration.performanceContract.browserChannelSourceEvidence,
        browserChannelSourceEvidence,
        `stress tier ${tier} performance contract`,
    );
    assert.equal(configuration.performanceContract.mode, `stress-tier-${tier}`);
    assert.equal(configuration.performanceContract.arrivalTimeline?.wireAtSource,
        ARRIVAL_WIRE_AT_SOURCE,
        `stress tier ${tier} arrival wire source contract drifted`);
    assertPeriodicServerSyncContract(
        configuration.performanceContract.arrivalTimeline?.periodicServerSync,
        `stress tier ${tier} configuration`,
    );
    assert.equal(configuration.performanceContract.minimumChunkMetric,
        "unique-chunk-position");
    assert.equal(configuration.performanceContract.chunkWindow.maximumUniqueChunkCapacity, 257);
    assert.equal(
        configuration.performanceContract.chunkWindow
            .observedDistanceContractRequiredBeforeCounting,
        true,
    );
    assert.deepEqual(configuration.performanceContract.chunkWindow.initialDistanceContract, {
        source: "clientbound-login",
        packetId: 49,
        fields: ["chunkRadius", "simulationDistance"],
    });
    assert.deepEqual(configuration.performanceContract.chunkWindow.observedDistancePackets, {
        cacheCenter: 94,
        cacheRadius: 95,
        simulationDistance: 111,
    });
    assert.equal(configuration.performanceContract.chunkBatch.acknowledgementEncoding,
        "float32-be");
    assert.deepEqual(configuration.performanceContract.stressLatencyDistribution.pollGap, {
        p99Millis: 16.7,
        p999Millis: 50,
        maxMillis: 100,
    });
    assert.deepEqual(configuration.performanceContract.stressLatencyDistribution.playTickGap, {
        p99Millis: 60,
        p999Millis: 75,
        maxMillis: 100,
    });
    assert.deepEqual(
        configuration.performanceContract.stressLatencyDistribution.preMinimumChunkGap,
        { p99Millis: 100, maxMillis: 250 },
    );
    const schedulerContract = configuration.performanceContract.pollScheduler;
    assert.ok(schedulerContract && typeof schedulerContract === "object",
        `stress tier ${tier} omitted poll scheduler contract`);
    assert.equal(schedulerContract.schemaVersion,
        "gaius.browser-client-poll-scheduler.v1");
    assert.equal(schedulerContract.mode,
        "round-robin-bounded-batch-per-macrotask");
    assert.equal(schedulerContract.maxBatchClients, 4);
    assert.equal(schedulerContract.callbackWorkBudgetMillis, 8);
    assert.equal(schedulerContract.callbackBudgetCoversPlayTicks, true);
    assert.equal(schedulerContract.maxPlayTicksPerSchedulerCallback, 4);
    assert.equal(schedulerContract.maxVisibleDispatchSkew, 1);
    assert.equal(schedulerContract.strictEventLoopMaxMillis, 100);
    assert.equal(schedulerContract.rawLatencyEvidence, true);
    assert.equal(schedulerContract.dueState,
        "side-map-authoritative-client-property-mirror-only");
    assert.equal(schedulerContract.immediateInboundPriority,
        "client-method-buffer-then-bridge");
    assert.equal(schedulerContract.idleImmediateWindowMillis, 2);
    assert.equal(schedulerContract.idleImmediateProbeBudgetMillis, 2);
    assert.equal(schedulerContract.idleImmediateProbeSpinLimit, 16);
    assert.deepEqual(schedulerContract.callbackTail, {
        schemaVersion: "gaius.browser-client-poll-callback-tail.v1",
        slowThresholdMillis: 16.7,
        sampleLimit: 64,
        retention: "longest-duration-desc-sequence-asc",
        strictRawDurationGateMillis: 16.7,
    });
    assert.deepEqual(schedulerContract.callbackFinalizationTail, {
        schemaVersion: CALLBACK_FINALIZATION_TAIL_TELEMETRY_SCHEMA_VERSION,
        slowThresholdMillis: CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS,
        sampleLimit: CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT,
        retention: CALLBACK_FINALIZATION_TAIL_RETENTION,
        diagnosticOnly: true,
        strictGatesChanged: false,
        strictRawDurationGateMillis: 16.7,
        measuredFrom: "callback-work-end-to-finalize-finish",
        totalAfterFinalizeFrom: "callback-start-to-finalize-finish",
        includesContinuationScheduling: true,
    });
    const pollPhaseContract = configuration.performanceContract.pollPhaseTelemetry;
    assert.ok(pollPhaseContract && typeof pollPhaseContract === "object",
        `stress tier ${tier} omitted poll phase telemetry contract`);
    assert.equal(pollPhaseContract.schemaVersion,
        POLL_PHASE_TELEMETRY_SCHEMA_VERSION);
    assert.equal(pollPhaseContract.slowThresholdMillis,
        POLL_PHASE_SLOW_THRESHOLD_MILLIS);
    assert.equal(pollPhaseContract.sampleLimit, POLL_PHASE_SAMPLE_LIMIT);
    assert.equal(pollPhaseContract.frameSampleLimit,
        POLL_PHASE_FRAME_SAMPLE_LIMIT);
    assert.equal(pollPhaseContract.packetSampleLimit,
        POLL_PHASE_PACKET_SAMPLE_LIMIT);
    assert.equal(pollPhaseContract.segmentAccounting,
        POLL_PHASE_SEGMENT_ACCOUNTING,
        `stress tier ${tier} poll phase segment accounting semantics drifted`);
    assert.equal(pollPhaseContract.diagnosticOnly, true);
    assert.equal(pollPhaseContract.strictGatesChanged, false);
    assert.equal(pollPhaseContract.independentExecution, false);
    assert.equal(pollPhaseContract.retention,
        "longest-duration-desc-sequence-asc");
    configurations.push(configuration);
    if (!printConfigOnly) {
        const run = await runChild(
            ["--stress"], environment, HARD_DEADLINES_MILLIS[tier], true);
        let resultValidationError = null;
        if (run.result !== null) {
            try {
                validateStressResult(run.result, tier, configuration);
            }
            catch (error) {
                resultValidationError = String(error?.stack || error);
            }
        }
        const { result, ...processEvidence } = run;
        runs.push({
            tier,
            evidenceRoot: canonicalEvidenceRoot,
            ...processEvidence,
            resultOk: result?.ok ?? null,
            strictEvidenceEligible:
                result?.acceptance?.diagnostics?.strictEvidenceEligible ?? null,
            resultValidationError,
        });
        if (run.result !== null) results.push(run.result);
    }
}

// `ok` is the release-facing result for an executed stress run.  A trace-enabled
// run is still useful diagnostic evidence, but it carries allocation/sorting
// overhead in the browser hot path and therefore cannot satisfy clean strict
// performance evidence. Keep the functional result separately so diagnostic
// output remains useful while the process exits fail-closed for strict release.
const functionalOk = printConfigOnly || runs.every((run) =>
    run.exitCode === 0 && !run.timedOut && run.parseError === null &&
    run.resultOk === true && run.resultValidationError === null);
const strictAcceptancePassed = printConfigOnly
    ? null
    : functionalOk && ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE &&
        runs.every((run) =>
            run.strictEvidenceEligible === true);
const ok = printConfigOnly || strictAcceptancePassed === true;
console.log(JSON.stringify({
    schemaVersion: "browser-full-path-stress-result-v1",
    ok,
    functionalOk,
    strictAcceptancePassed,
    mode: printConfigOnly ? "print-config" : "stress",
    tiers,
    hardDeadlinesMillis: Object.fromEntries(tiers.map((tier) => [
        tier, HARD_DEADLINES_MILLIS[tier],
    ])),
    browserChannelSourceEvidence,
    diagnostics: {
        arrivalTraceEnabled: !ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        diagnosticOnly: !ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        strictEvidenceEligible: ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
    },
    evidenceRoot: canonicalEvidenceRoot,
    configurations,
    runs,
    results,
}));
if (!ok) process.exitCode = 1;

function validateStressResult(result, tier, configuration) {
    assert.equal(result?.ok, true, `stress tier ${tier} child result was not ok`);
    assert.equal(result?.profile?.id, CANONICAL_PROFILE.id);
    assert.equal(result?.profile?.protocolVersion, 776);
    assertSourceProvenance(
        result?.transport?.browserChannelSourceEvidence,
        browserChannelSourceEvidence,
        `stress tier ${tier} child transport`,
    );
    assert.equal(result?.transport?.browserChannelSourceSha256,
        result?.transport?.browserChannelSourceEvidence?.sourceSha256,
        `stress tier ${tier} child source SHA-256 alias drifted`);
    assert.equal(result?.transport?.browserChannelSourceNormalizedSha256,
        result?.transport?.browserChannelSourceEvidence?.normalizedSourceSha256,
        `stress tier ${tier} child normalized source SHA-256 alias drifted`);
    assert.deepEqual(result?.transport?.browserJsBodyMarkers,
        result?.transport?.browserChannelSourceEvidence?.jsBodyMarkers,
        `stress tier ${tier} child JSBody marker alias drifted`);
    assert.equal(result?.transport?.stressMode, true,
        `stress tier ${tier} child did not report stress mode`);
    assert.equal(result?.transport?.stressTier, tier,
        `stress tier ${tier} child reported the wrong tier`);
    assert.equal(result?.transport?.clients, configuration.stressTarget.clients);
    assert.equal(result?.transport?.reconnectWaves,
        configuration.stressTarget.reconnectWaves);
    assert.equal(result?.transport?.expectedConnections,
        configuration.stressTarget.clientLifecycles);
    assert.equal(result?.acceptance?.mode, `stress-tier-${tier}`);
    assert.equal(result?.acceptance?.diagnostics?.arrivalTraceEnabled,
        !ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        `stress tier ${tier} acceptance trace-enabled marker drifted`);
    assert.equal(result?.acceptance?.diagnostics?.diagnosticOnly,
        !ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        `stress tier ${tier} acceptance diagnostic marker drifted`);
    assert.equal(result?.acceptance?.diagnostics?.strictEvidenceEligible,
        ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        `stress tier ${tier} acceptance strict-evidence eligibility drifted`);
    assertStressSchedulerEvidence(result, tier, configuration);
    assertArrivalTimelineEvidence(result, tier);
    const observed = result?.acceptance?.observed;
    assert.equal(observed?.clients, configuration.stressTarget.clients);
    assert.equal(observed?.minimumChunkPackets,
        configuration.stressTarget.minimumChunkPackets);
    assert.equal(observed?.uniqueChunkTarget, true);
    assert.equal(observed?.soakMillis, configuration.stressTarget.soakMillis);
    assert.ok(observed?.actualSoakMillis >= configuration.stressTarget.soakMillis,
        `stress tier ${tier} observed only ${observed?.actualSoakMillis}ms soak`);
    assert.equal(observed?.reconnectWaveCount,
        configuration.stressTarget.reconnectWaves);
    assert.equal(observed?.expectedConnections,
        configuration.stressTarget.clientLifecycles);
    assert.ok(Array.isArray(result?.clients));
    assert.equal(result.clients.length, configuration.stressTarget.clientLifecycles,
        `stress tier ${tier} omitted a client lifecycle`);
    for (const [index, client] of result.clients.entries()) {
        assert.ok(client?.chunkWindow?.uniqueChunkPositionsTowardTarget >=
            configuration.stressTarget.minimumChunkPackets,
        `stress tier ${tier} client lifecycle ${index + 1} observed only ` +
            `${client?.chunkWindow?.uniqueChunkPositionsTowardTarget} target chunks`);
        assert.equal(client?.chunkWindow?.observedChunkCacheRadius,
            configuration.stressTarget.serverViewDistance);
        assert.equal(client?.chunkWindow?.observedSimulationDistance,
            configuration.stressTarget.simulationDistance);
    }
    assert.ok(Array.isArray(result?.reconnectWaves));
    assert.equal(result.reconnectWaves.length, configuration.stressTarget.reconnectWaves,
        `stress tier ${tier} root reconnect evidence count drifted`);
    const inboundFlow = result?.inboundFlow ??
        result?.acceptance?.observed?.inboundFlow;
    assert.ok(inboundFlow && typeof inboundFlow === "object",
        `stress tier ${tier} omitted inbound-flow evidence`);
    assert.deepEqual(result?.acceptance?.observed?.inboundFlow, inboundFlow,
        `stress tier ${tier} observed inbound-flow evidence diverged`);
    assert.deepEqual(result?.acceptance?.actual?.inboundFlow, inboundFlow,
        `stress tier ${tier} actual inbound-flow evidence diverged`);
    const pauseLimitMillis =
        configuration.performanceContract.multiplayerPerformance.maxSoakPhaseStallMillis;
    const orderedStages = [];
    assertInboundFlowStage(inboundFlow.initial,
        inboundFlow.initial?.label ?? "initial connect through minimum chunks",
        pauseLimitMillis, {
            diagnosticLabel: `stress tier ${tier} initial`,
        });
    orderedStages.push(inboundFlow.initial);
    assert.ok(Array.isArray(inboundFlow.reconnectWaves),
        `stress tier ${tier} reconnect inbound-flow evidence is not an array`);
    assert.equal(inboundFlow.reconnectWaves.length,
        configuration.stressTarget.reconnectWaves,
        `stress tier ${tier} omitted a reconnect inbound-flow stage`);
    for (let wave = 1; wave <= configuration.stressTarget.reconnectWaves; wave++) {
        const evidence = inboundFlow.reconnectWaves[wave - 1];
        assert.equal(evidence?.wave, wave,
            `stress tier ${tier} inbound-flow wave order drifted`);
        assertInboundFlowStage(evidence?.preDrop,
            evidence?.preDrop?.label ??
                `reconnect wave ${wave} pre-drop browser inbound flow resume`,
            pauseLimitMillis, {
                diagnosticLabel: `stress tier ${tier} reconnect wave ${wave} pre-drop`,
            });
        orderedStages.push(evidence.preDrop);
        assertInboundFlowStage(evidence,
            evidence?.label ?? `reconnect wave ${wave} browser inbound flow resume`,
            pauseLimitMillis, {
                diagnosticLabel: `stress tier ${tier} reconnect wave ${wave}`,
            });
        assert.deepEqual(result.reconnectWaves[wave - 1]?.inboundFlow, evidence,
            `stress tier ${tier} reconnect wave ${wave} root evidence diverged`);
        orderedStages.push(evidence);
    }
    assertInboundFlowStage(inboundFlow.postSoak,
        inboundFlow.postSoak?.label ?? "post-soak browser inbound flow resume",
        pauseLimitMillis, {
            diagnosticLabel: `stress tier ${tier} post-soak`,
        });
    orderedStages.push(inboundFlow.postSoak);
    assertInboundFlowStage(inboundFlow.finalCleanup,
        inboundFlow.finalCleanup?.label ?? "final browser transport cleanup",
        pauseLimitMillis, {
            requireCleanup: true,
            diagnosticLabel: `stress tier ${tier} final cleanup`,
        });
    orderedStages.push(inboundFlow.finalCleanup);
    const stageTimes = orderedStages.map((stage) => stage.capturedAtElapsedMillis);
    for (let index = 1; index < stageTimes.length; index++) {
        assert.ok(stageTimes[index] >= stageTimes[index - 1],
            `stress tier ${tier} inbound-flow timestamps were not monotonic`);
    }
    const soak = result?.acceptance?.observed?.soakPerformance;
    assert.ok(soak && Number.isFinite(soak.maxContinuousFlowPauseMillis),
        `stress tier ${tier} omitted continuous inbound-pause evidence`);
    assert.equal(soak?.ok, true, `stress tier ${tier} soak was not healthy`);
    assert.deepEqual(soak?.violations, [],
        `stress tier ${tier} soak retained liveness violations`);
    assert.ok(soak.maxContinuousFlowPauseMillis <=
        configuration.performanceContract.multiplayerPerformance.maxSoakPhaseStallMillis,
    `stress tier ${tier} inbound flow paused for ` +
        `${soak.maxContinuousFlowPauseMillis}ms`);
    const rawEventLoopMax = Number.isFinite(soak.rawMaxBrowserEventLoopGapMillis)
        ? soak.rawMaxBrowserEventLoopGapMillis
        : NaN;
    assert.ok(Number.isFinite(rawEventLoopMax) &&
        rawEventLoopMax <= configuration.performanceContract.pollScheduler
            .strictEventLoopMaxMillis,
    `stress tier ${tier} raw browser event-loop max reached ${rawEventLoopMax}ms`);
    assert.equal(soak.limits.maxBrowserEventLoopGapMillis,
        configuration.performanceContract.pollScheduler.strictEventLoopMaxMillis,
        `stress tier ${tier} event-loop limit drifted`);
    assertStressClientRawLatency(result.clients, tier, configuration);
}

function assertStressSchedulerEvidence(result, tier, configuration) {
    const scheduler = result?.pollScheduler;
    assert.ok(scheduler && typeof scheduler === "object",
        `stress tier ${tier} omitted poll scheduler evidence`);
    assert.equal(scheduler.schemaVersion,
        "gaius.browser-client-poll-scheduler.v1");
    assert.equal(scheduler.mode,
        "round-robin-bounded-batch-per-macrotask");
    assert.equal(scheduler.maxBatchClients,
        configuration.performanceContract.pollScheduler.maxBatchClients);
    assert.equal(scheduler.callbackWorkBudgetMillis,
        configuration.performanceContract.pollScheduler.callbackWorkBudgetMillis);
    assert.equal(scheduler.callbackBudgetCoversPlayTicks, true,
        `stress tier ${tier} callback budget did not cover PLAY ticks`);
    // Runtime evidence names this observed quantity after the service path
    // (`maxPlayTickServicesPerCallback`); keep the contract's descriptive
    // `maxPlayTicksPerSchedulerCallback` name separate from the measured field.
    // The configured value is a hard upper bound, not a requirement to batch
    // exactly four ticks: timer phase skew can legitimately leave only one
    // client due in a callback.  The strict callback/poll duration gates below
    // still reject an over-budget turn, while this assertion rejects only a
    // real cap breach.
    assert.ok(scheduler.maxPlayTickServicesPerCallback <=
        configuration.performanceContract.pollScheduler.maxPlayTicksPerSchedulerCallback,
        `stress tier ${tier} observed PLAY tick batch exceeded configured cap`);
    assert.equal(scheduler.maxPlayTickServicesPerCallbackLimit,
        configuration.performanceContract.pollScheduler.maxPlayTicksPerSchedulerCallback);
    assert.equal(scheduler.stopped, true,
        `stress tier ${tier} poll scheduler was not stopped`);
    assert.equal(scheduler.overlappingCallbacks, 0,
        `stress tier ${tier} poll scheduler callbacks overlapped`);
    assert.equal(scheduler.playTickServiceErrors, 0,
        `stress tier ${tier} PLAY tick service reported an error`);
    for (const [name, value] of [
        ["callbacks", scheduler.callbacks],
        ["dispatchedPolls", scheduler.dispatchedPolls],
        ["maxClientsPerCallback", scheduler.maxClientsPerCallback],
        ["maxPlayTickServicesPerCallback", scheduler.maxPlayTickServicesPerCallback],
        ["callbackBudgetExhaustions", scheduler.callbackBudgetExhaustions],
        ["callbackBudgetOverruns", scheduler.callbackBudgetOverruns],
        ["callbackBudgetTickSkips", scheduler.callbackBudgetTickSkips],
        ["callbackBudgetPollSkips", scheduler.callbackBudgetPollSkips],
        ["dueMapEntries", scheduler.dueMapEntries],
    ]) {
        assert.ok(Number.isSafeInteger(value) && value >= 0,
            `stress tier ${tier} scheduler ${name} was invalid: ${value}`);
    }
    assert.ok(scheduler.maxClientsPerCallback <= scheduler.maxBatchClients,
        `stress tier ${tier} exceeded poll callback client cap`);
    assert.ok(scheduler.maxPlayTickServicesPerCallback <=
        scheduler.maxPlayTickServicesPerCallbackLimit,
    `stress tier ${tier} exceeded PLAY tick callback cap`);
    assert.equal(scheduler.dueMapEntries, 0,
        `stress tier ${tier} scheduler retained due-map entries after stop`);
    const callbackTail = scheduler.callbackTail;
    assert.ok(callbackTail && typeof callbackTail === "object",
        `stress tier ${tier} omitted callback-tail telemetry`);
    assert.equal(callbackTail.schemaVersion,
        configuration.performanceContract.pollScheduler.callbackTail.schemaVersion,
        `stress tier ${tier} callback-tail schema drifted`);
    assert.equal(callbackTail.slowThresholdMillis, 16.7,
        `stress tier ${tier} callback-tail threshold drifted`);
    assert.equal(callbackTail.sampleLimit, 64,
        `stress tier ${tier} callback-tail sample limit drifted`);
    for (const [name, value] of [
        ["slowCallbackSamplesTotal", callbackTail.slowCallbackSamplesTotal],
        ["slowCallbackSamplesDropped", callbackTail.slowCallbackSamplesDropped],
        ["retainedSampleCount", callbackTail.retainedSampleCount],
    ]) {
        assert.ok(Number.isSafeInteger(value) && value >= 0,
            `stress tier ${tier} callback-tail ${name} was invalid: ${value}`);
    }
    assert.ok(Array.isArray(callbackTail.samples),
        `stress tier ${tier} callback-tail samples were not an array`);
    assert.ok(callbackTail.samples.length <= callbackTail.sampleLimit,
        `stress tier ${tier} callback-tail ring exceeded its hard limit`);
    assert.equal(callbackTail.retainedSampleCount, callbackTail.samples.length,
        `stress tier ${tier} callback-tail retained count diverged`);
    assert.equal(callbackTail.slowCallbackSamplesTotal,
        callbackTail.retainedSampleCount + callbackTail.slowCallbackSamplesDropped,
        `stress tier ${tier} callback-tail total/drop accounting diverged`);
    for (let index = 0; index < callbackTail.samples.length; index++) {
        const sample = callbackTail.samples[index];
        assert.ok(sample && typeof sample === "object",
            `stress tier ${tier} callback-tail sample ${index} was invalid`);
        assert.ok(Number.isFinite(sample.durationRawMillis) &&
            sample.durationRawMillis >= callbackTail.slowThresholdMillis,
        `stress tier ${tier} callback-tail sample ${index} was below threshold`);
        if (index > 0) {
            const previous = callbackTail.samples[index - 1];
            assert.ok(previous.durationRawMillis > sample.durationRawMillis ||
                previous.durationRawMillis === sample.durationRawMillis &&
                    previous.callbackSequence <= sample.callbackSequence,
            `stress tier ${tier} callback-tail samples were not stably ordered`);
        }
    }
    if (scheduler.maxCallbackDurationRawMillis >= 16.7) {
        assert.ok(callbackTail.samples.length > 0,
            `stress tier ${tier} strict callback overrun had no retained sample`);
        assert.equal(callbackTail.samples[0].durationRawMillis,
            Math.max(...callbackTail.samples.map((sample) => sample.durationRawMillis)),
            `stress tier ${tier} callback-tail max sample was not retained first`);
    }
    assertCallbackFinalizationTailEvidence(scheduler, tier, configuration);
    for (const [name, value] of [
        ["maxCallbackDurationRawMillis", scheduler.maxCallbackDurationRawMillis],
        ["maxClientPollDurationRawMillis", scheduler.maxClientPollDurationRawMillis],
        ["maxInterCallbackGapRawMillis", scheduler.maxInterCallbackGapRawMillis],
        ["maxScheduleDelayRawMillis", scheduler.maxScheduleDelayRawMillis],
    ]) {
        assert.ok(Number.isFinite(value) && value >= 0,
            `stress tier ${tier} scheduler ${name} was not raw finite latency: ${value}`);
    }
    assert.ok(scheduler.maxCallbackDurationRawMillis <= 16.7,
        `stress tier ${tier} scheduler callback reached ` +
        `${scheduler.maxCallbackDurationRawMillis}ms (render budget 16.7ms)`);
    assert.ok(scheduler.maxClientPollDurationRawMillis <= 16.7,
        `stress tier ${tier} client poll reached ` +
        `${scheduler.maxClientPollDurationRawMillis}ms (render budget 16.7ms)`);
    assert.ok(Number.isFinite(scheduler.maxVisibleDispatchSkew) &&
        scheduler.maxVisibleDispatchSkew <=
            configuration.performanceContract.pollScheduler.maxVisibleDispatchSkew,
    `stress tier ${tier} visible dispatch skew reached ${scheduler.maxVisibleDispatchSkew}`);
    assert.equal(scheduler.maxVisibleDispatchSkewLimit,
        configuration.performanceContract.pollScheduler.maxVisibleDispatchSkew);
    assert.ok(Array.isArray(scheduler.visibleDispatchCounts),
        `stress tier ${tier} omitted visible dispatch counts`);
    assert.equal(scheduler.visibleClientCount, scheduler.visibleDispatchCounts.length);
    const visibleIds = new Set();
    const visibleCounts = [];
    for (const entry of scheduler.visibleDispatchCounts) {
        assert.ok(entry && (typeof entry.id === "number" || typeof entry.id === "string"),
            `stress tier ${tier} visible dispatch entry omitted id`);
        assert.ok(!visibleIds.has(entry.id),
            `stress tier ${tier} visible dispatch ids were duplicated`);
        visibleIds.add(entry.id);
        assert.ok(Number.isSafeInteger(entry.count) && entry.count >= 0,
            `stress tier ${tier} visible dispatch count was invalid`);
        visibleCounts.push(entry.count);
    }
    if (visibleCounts.length > 0) {
        assert.ok(Math.max(...visibleCounts) - Math.min(...visibleCounts) <=
            scheduler.maxVisibleDispatchSkewLimit,
        `stress tier ${tier} visible dispatch count snapshot was unfair`);
    }
    const eventLoop = scheduler.eventLoopDelay;
    assert.ok(eventLoop && eventLoop.available === true,
        `stress tier ${tier} scheduler event-loop telemetry unavailable`);
    assert.ok(Number.isFinite(eventLoop.rawMaxMillis) && eventLoop.rawMaxMillis >= 0,
        `stress tier ${tier} scheduler raw event-loop max missing`);
    assert.ok(eventLoop.rawMaxMillis <=
        configuration.performanceContract.pollScheduler.strictEventLoopMaxMillis,
    `stress tier ${tier} scheduler event-loop max reached ${eventLoop.rawMaxMillis}ms`);
    assert.deepEqual(result?.acceptance?.observed?.pollScheduler, scheduler,
        `stress tier ${tier} observed scheduler evidence diverged`);
    assert.deepEqual(result?.acceptance?.actual?.pollScheduler, scheduler,
        `stress tier ${tier} actual scheduler evidence diverged`);
}

function assertCallbackFinalizationTailEvidence(scheduler, tier, configuration) {
    const contract = configuration.performanceContract.pollScheduler
        .callbackFinalizationTail;
    const tail = scheduler.callbackFinalizationTail;
    assert.ok(tail && typeof tail === "object",
        `stress tier ${tier} omitted callback-finalization-tail telemetry`);
    assert.equal(tail.schemaVersion, contract.schemaVersion,
        `stress tier ${tier} callback-finalization-tail schema drifted`);
    assert.equal(tail.slowThresholdMillis,
        CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS,
        `stress tier ${tier} callback-finalization-tail threshold drifted`);
    assert.equal(tail.sampleLimit, CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT,
        `stress tier ${tier} callback-finalization-tail sample limit drifted`);
    assert.equal(tail.retention, CALLBACK_FINALIZATION_TAIL_RETENTION,
        `stress tier ${tier} callback-finalization-tail retention drifted`);
    assert.equal(tail.diagnosticOnly, true,
        `stress tier ${tier} callback-finalization-tail became a gate`);
    assert.equal(tail.strictGatesChanged, false,
        `stress tier ${tier} callback-finalization-tail changed strict gates`);
    assert.equal(tail.strictRawDurationGateMillis, 16.7,
        `stress tier ${tier} callback-finalization-tail strict gate drifted`);
    assert.equal(tail.includesContinuationScheduling, true,
        `stress tier ${tier} callback-finalization-tail omitted schedule endpoint`);
    for (const [name, value] of [
        ["slowTailSamplesTotal", tail.slowTailSamplesTotal],
        ["slowTailSamplesDropped", tail.slowTailSamplesDropped],
        ["retainedSampleCount", tail.retainedSampleCount],
        ["droppedSampleCount", tail.droppedSampleCount],
    ]) {
        assert.ok(Number.isSafeInteger(value) && value >= 0,
            `stress tier ${tier} callback-finalization-tail ${name} invalid: ${value}`);
    }
    assert.equal(tail.droppedSampleCount, tail.slowTailSamplesDropped,
        `stress tier ${tier} callback-finalization-tail dropped aliases diverged`);
    assert.ok(Array.isArray(tail.samples),
        `stress tier ${tier} callback-finalization-tail samples were not an array`);
    assert.ok(tail.samples.length <= tail.sampleLimit,
        `stress tier ${tier} callback-finalization-tail ring exceeded its hard limit`);
    assert.equal(tail.retainedSampleCount, tail.samples.length,
        `stress tier ${tier} callback-finalization-tail retained count diverged`);
    assert.equal(tail.slowTailSamplesTotal,
        tail.retainedSampleCount + tail.slowTailSamplesDropped,
        `stress tier ${tier} callback-finalization-tail total/drop accounting diverged`);
    for (let index = 0; index < tail.samples.length; index++) {
        const sample = tail.samples[index];
        assert.ok(sample && typeof sample === "object",
            `stress tier ${tier} callback-finalization-tail sample ${index} invalid`);
        assert.equal(sample.schemaVersion, tail.schemaVersion,
            `stress tier ${tier} callback-finalization-tail sample ${index} schema drifted`);
        assert.ok(Number.isSafeInteger(sample.callbackSequence) &&
            sample.callbackSequence > 0,
        `stress tier ${tier} callback-finalization-tail sample ${index} sequence invalid`);
        for (const [name, value] of [
            ["finalizeStartAtMillis", sample.finalizeStartAtMillis],
            ["finalizeFinishAtMillis", sample.finalizeFinishAtMillis],
            ["tailRawMillis", sample.tailRawMillis],
            ["tailMillis", sample.tailMillis],
            ["totalAfterFinalizeRawMillis", sample.totalAfterFinalizeRawMillis],
            ["totalAfterFinalizeMillis", sample.totalAfterFinalizeMillis],
            ["callbackDurationRawMillis", sample.callbackDurationRawMillis],
            ["callbackDurationMillis", sample.callbackDurationMillis],
        ]) {
            assert.ok(Number.isFinite(value) && value >= 0,
                `stress tier ${tier} callback-finalization-tail sample ${index} ` +
                `${name} was not finite/non-negative: ${value}`);
        }
        assert.ok(sample.finalizeFinishAtMillis >= sample.finalizeStartAtMillis,
            `stress tier ${tier} callback-finalization-tail sample ${index} ` +
            "finish preceded start");
        assert.ok(sample.tailRawMillis >= tail.slowThresholdMillis,
            `stress tier ${tier} callback-finalization-tail sample ${index} ` +
            "was below threshold");
        assert.ok(sample.totalAfterFinalizeRawMillis >=
            sample.callbackDurationRawMillis,
        `stress tier ${tier} callback-finalization-tail sample ${index} ` +
            "total endpoint preceded callback work endpoint");
        assert.equal(sample.tailMillis,
            Number(sample.tailRawMillis.toFixed(3)),
            `stress tier ${tier} callback-finalization-tail sample ${index} ` +
            "rounded tail diverged");
        if (index > 0) {
            const previous = tail.samples[index - 1];
            assert.ok(previous.tailRawMillis > sample.tailRawMillis ||
                previous.tailRawMillis === sample.tailRawMillis &&
                    previous.callbackSequence <= sample.callbackSequence,
            `stress tier ${tier} callback-finalization-tail samples were not ` +
            "stably ordered");
        }
    }
    for (const [name, value] of [
        ["maxTailRawMillis", tail.maxTailRawMillis],
        ["maxTailMillis", tail.maxTailMillis],
        ["maxTotalAfterFinalizeRawMillis", tail.maxTotalAfterFinalizeRawMillis],
        ["maxTotalAfterFinalizeMillis", tail.maxTotalAfterFinalizeMillis],
    ]) {
        assert.ok(Number.isFinite(value) && value >= 0,
            `stress tier ${tier} callback-finalization-tail ${name} invalid: ${value}`);
    }
    const maxSampleTail = tail.samples.length === 0 ? 0 :
        Math.max(...tail.samples.map((sample) => sample.tailRawMillis));
    assert.ok(tail.maxTailRawMillis >= maxSampleTail,
        `stress tier ${tier} callback-finalization-tail max omitted a retained sample`);
    assert.equal(tail.maxTailMillis,
        Number(tail.maxTailRawMillis.toFixed(3)),
        `stress tier ${tier} callback-finalization-tail rounded max diverged`);
    const last = tail.lastFinalization;
    if (scheduler.callbacks > 0) {
        assert.ok(last && typeof last === "object",
            `stress tier ${tier} callback-finalization-tail omitted last sample`);
    }
    if (last !== null && last !== undefined) {
        assert.equal(last.schemaVersion, tail.schemaVersion,
            `stress tier ${tier} callback-finalization-tail last schema drifted`);
        assert.ok(Number.isSafeInteger(last.callbackSequence) &&
            last.callbackSequence > 0,
        `stress tier ${tier} callback-finalization-tail last sequence invalid`);
        for (const [name, value] of [
            ["finalizeStartAtMillis", last.finalizeStartAtMillis],
            ["finalizeFinishAtMillis", last.finalizeFinishAtMillis],
            ["tailRawMillis", last.tailRawMillis],
            ["totalAfterFinalizeRawMillis", last.totalAfterFinalizeRawMillis],
            ["callbackDurationRawMillis", last.callbackDurationRawMillis],
        ]) {
            assert.ok(Number.isFinite(value) && value >= 0,
                `stress tier ${tier} callback-finalization-tail last ${name} invalid`);
        }
        assert.ok(last.finalizeFinishAtMillis >= last.finalizeStartAtMillis,
            `stress tier ${tier} callback-finalization-tail last finish preceded start`);
        assert.ok(last.totalAfterFinalizeRawMillis >=
            last.callbackDurationRawMillis,
        `stress tier ${tier} callback-finalization-tail last endpoint regressed`);
    }
}

function assertOptionalPollPhaseTelemetry(
    performanceEvidence,
    tier,
    lifecycleLabel,
    contract,
) {
    const telemetry = performanceEvidence?.pollPhaseTelemetry;
    // The field is additive diagnostic evidence.  Older result artifacts may
    // legitimately omit it; when present, malformed data fails closed.  No
    // assertion below requires a retained sample, so an entirely fast run is
    // still a valid strict run.
    if (telemetry === undefined || telemetry === null) return false;
    const label = `stress tier ${tier} ${lifecycleLabel} poll phase`;
    assert.ok(telemetry && typeof telemetry === "object",
        `${label} telemetry was not an object`);
    const expected = contract ?? {
        schemaVersion: POLL_PHASE_TELEMETRY_SCHEMA_VERSION,
        slowThresholdMillis: POLL_PHASE_SLOW_THRESHOLD_MILLIS,
        sampleLimit: POLL_PHASE_SAMPLE_LIMIT,
        frameSampleLimit: POLL_PHASE_FRAME_SAMPLE_LIMIT,
        packetSampleLimit: POLL_PHASE_PACKET_SAMPLE_LIMIT,
        segmentAccounting: POLL_PHASE_SEGMENT_ACCOUNTING,
    };
    assert.equal(telemetry.schemaVersion, expected.schemaVersion,
        `${label} schema drifted`);
    assert.equal(telemetry.slowThresholdMillis,
        expected.slowThresholdMillis, `${label} threshold drifted`);
    assert.equal(telemetry.strictThresholdMillis, 16.7,
        `${label} strict threshold drifted`);
    assert.equal(telemetry.sampleLimit, expected.sampleLimit,
        `${label} sample limit drifted`);
    assert.equal(telemetry.frameSampleLimit, expected.frameSampleLimit,
        `${label} frame cap drifted`);
    assert.equal(telemetry.packetSampleLimit, expected.packetSampleLimit,
        `${label} packet cap drifted`);
    assert.equal(telemetry.segmentAccounting, expected.segmentAccounting,
        `${label} segment accounting semantics drifted`);
    assert.equal(telemetry.diagnosticOnly, true,
        `${label} was not marked diagnostic-only`);
    assert.equal(telemetry.strictGatesChanged, false,
        `${label} changed strict gates`);
    assert.equal(telemetry.independentExecution, false,
        `${label} claimed independent execution`);
    assert.equal(telemetry.retention,
        "longest-duration-desc-sequence-asc",
        `${label} retention policy drifted`);
    const integerFields = [
        "pollsTotal",
        "slowSamplesTotal",
        "pollPhaseSamplesTotal",
        "retainedSampleCount",
        "slowSamplesDropped",
        "pollPhaseSamplesDropped",
        "droppedSampleCount",
    ];
    for (const name of integerFields) {
        assert.ok(Number.isSafeInteger(telemetry[name]) && telemetry[name] >= 0,
            `${label} ${name} was not a non-negative safe integer`);
    }
    assert.equal(telemetry.slowSamplesTotal, telemetry.pollPhaseSamplesTotal,
        `${label} slow/phase total aliases diverged`);
    assert.equal(telemetry.slowSamplesDropped,
        telemetry.pollPhaseSamplesDropped,
        `${label} slow/phase dropped aliases diverged`);
    assert.equal(telemetry.slowSamplesDropped, telemetry.droppedSampleCount,
        `${label} dropped aliases diverged`);
    const samples = telemetry.samples;
    assert.ok(Array.isArray(samples), `${label} samples were not an array`);
    assert.ok(samples.length <= telemetry.sampleLimit,
        `${label} sample ring exceeded its hard limit`);
    assert.equal(telemetry.retainedSampleCount, samples.length,
        `${label} retained sample count diverged`);
    assert.equal(telemetry.slowSamplesTotal,
        telemetry.retainedSampleCount + telemetry.slowSamplesDropped,
        `${label} total/retained/dropped accounting diverged`);
    assert.ok(telemetry.pollsTotal >= telemetry.slowSamplesTotal,
        `${label} slow sample count exceeded poll count`);

    const assertFiniteNonNegative = (value, name, maximum = undefined) => {
        assert.ok(Number.isFinite(value) && value >= 0,
            `${label} ${name} was not finite/non-negative`);
        if (maximum !== undefined) assert.ok(value <= maximum,
            `${label} ${name} exceeded ${maximum}`);
    };
    for (let index = 0; index < samples.length; index++) {
        const sample = samples[index];
        assert.ok(sample && typeof sample === "object",
            `${label} sample ${index} was not an object`);
        assert.equal(sample.schemaVersion, telemetry.schemaVersion,
            `${label} sample ${index} schema drifted`);
        assert.equal(sample.segmentAccounting, telemetry.segmentAccounting,
            `${label} sample ${index} segment accounting semantics drifted`);
        assert.ok(Number.isSafeInteger(sample.pollSequence) &&
            sample.pollSequence >= 0,
        `${label} sample ${index} poll sequence was invalid`);
        assertFiniteNonNegative(sample.startedAtMillis,
            `sample ${index} startedAtMillis`);
        assertFiniteNonNegative(sample.durationRawMillis,
            `sample ${index} durationRawMillis`);
        assertFiniteNonNegative(sample.durationMillis,
            `sample ${index} durationMillis`);
        assert.ok(sample.durationRawMillis >= telemetry.slowThresholdMillis,
            `${label} sample ${index} was below the slow threshold`);
        assert.equal(sample.slowThresholdMillis,
            telemetry.slowThresholdMillis,
            `${label} sample ${index} diagnostic threshold drifted`);
        assert.equal(sample.strictThresholdMillis, 16.7,
            `${label} sample ${index} strict threshold drifted`);
        assert.equal(sample.strictGatesChanged, false,
            `${label} sample ${index} changed strict gates`);
        assert.equal(sample.independentExecution, false,
            `${label} sample ${index} claimed independent execution`);
        assert.equal(sample.diagnosticOnly, true,
            `${label} sample ${index} was not diagnostic-only`);
        assert.ok(POLL_PHASE_TRIGGERS.has(sample.trigger),
            `${label} sample ${index} trigger was invalid: ${sample.trigger}`);
        assertFiniteNonNegative(sample.triggerMillis,
            `sample ${index} triggerMillis`, sample.durationRawMillis);
        assert.ok(sample.outcome === "ok" || sample.outcome === "error",
            `${label} sample ${index} outcome was invalid`);
        assert.equal(typeof sample.clockAnomaly, "boolean",
            `${label} sample ${index} clockAnomaly was not boolean`);
        if (sample.schedulerCallbackSequence !== null) {
            assert.ok(Number.isSafeInteger(sample.schedulerCallbackSequence) &&
                sample.schedulerCallbackSequence >= 0,
            `${label} sample ${index} scheduler callback sequence was invalid`);
        }
        assert.ok(sample.clientId !== undefined && sample.clientId !== null,
            `${label} sample ${index} omitted client id`);
        assert.ok(Number.isSafeInteger(sample.wave) && sample.wave >= 0,
            `${label} sample ${index} wave was invalid`);
        assert.ok(sample.segments && typeof sample.segments === "object",
            `${label} sample ${index} segments were omitted`);
        for (const name of POLL_PHASE_SEGMENT_FIELDS) {
            assert.ok(Object.prototype.hasOwnProperty.call(sample.segments, name),
                `${label} sample ${index} omitted segment ${name}`);
            assertFiniteNonNegative(sample.segments[name],
                `sample ${index} segment ${name}`, sample.durationRawMillis);
        }
        const work = sample.work;
        assert.ok(work && typeof work === "object",
            `${label} sample ${index} work was omitted`);
        for (const [name, maximum] of [
            ["frames", expected.frameSampleLimit],
            ["bridgePollCalls", expected.frameSampleLimit],
            ["packetsParsed", expected.packetSampleLimit],
            ["compressedPackets", expected.packetSampleLimit],
        ]) {
            assert.ok(Number.isSafeInteger(work[name]) && work[name] >= 0 &&
                work[name] <= maximum,
            `${label} sample ${index} work.${name} exceeded cap ${maximum}`);
        }
        assert.ok(Number.isSafeInteger(work.frameBytes) && work.frameBytes >= 0,
            `${label} sample ${index} frameBytes was invalid`);
        for (const name of [
            "maxBridgePollMillis",
            "maxInflateMillis",
            "maxHandlerMillis",
            "maxPacketMillis",
        ]) {
            assertFiniteNonNegative(work[name],
                `sample ${index} work.${name}`, sample.durationRawMillis);
        }
        const buffer = sample.buffer;
        assert.ok(buffer && typeof buffer === "object",
            `${label} sample ${index} buffer was omitted`);
        for (const name of ["beforeBytes", "afterBytes", "maximumBytes"]) {
            assert.ok(Number.isSafeInteger(buffer[name]) && buffer[name] >= 0,
                `${label} sample ${index} buffer.${name} was invalid`);
        }
        assert.ok(sample.budget && typeof sample.budget === "object",
            `${label} sample ${index} budget was omitted`);
        assert.equal(typeof sample.budget.frameYield, "boolean",
            `${label} sample ${index} frame-yield flag was invalid`);
        assert.equal(typeof sample.budget.packetYield, "boolean",
            `${label} sample ${index} packet-yield flag was invalid`);
        assert.ok(Array.isArray(sample.slowPacketIds) &&
            sample.slowPacketIds.length <= POLL_PHASE_PACKET_ID_SAMPLE_LIMIT,
        `${label} sample ${index} slow packet-id detail exceeded cap`);
        for (const packetId of sample.slowPacketIds) {
            assert.ok(Number.isSafeInteger(packetId) && packetId >= 0,
                `${label} sample ${index} packet id was invalid`);
        }
        assert.equal(Object.prototype.hasOwnProperty.call(sample, "payload"), false,
            `${label} sample ${index} retained raw payload bytes`);
        assert.equal(Object.prototype.hasOwnProperty.call(sample, "stack"), false,
            `${label} sample ${index} retained a stack trace`);
        if (index > 0) {
            const previous = samples[index - 1];
            assert.ok(previous.durationRawMillis > sample.durationRawMillis ||
                previous.durationRawMillis === sample.durationRawMillis &&
                    previous.pollSequence <= sample.pollSequence,
            `${label} samples were not ordered duration-desc/sequence-asc`);
        }
    }
    return true;
}

function assertStressClientRawLatency(clients, tier, configuration) {
    for (const [index, client] of clients.entries()) {
        const performanceEvidence = client?.performance;
        assert.ok(performanceEvidence && typeof performanceEvidence === "object",
            `stress tier ${tier} client lifecycle ${index + 1} omitted performance evidence`);
        const checks = [
            ["pollGapHistogram", 100],
            ["playTickGapHistogram", 100],
            ["preMinimumChunkGapHistogram", 250],
        ];
        for (const [name, limit] of checks) {
            const histogram = performanceEvidence[name];
            assert.ok(histogram && Number.isSafeInteger(histogram.count) &&
                histogram.count > 0,
            `stress tier ${tier} client lifecycle ${index + 1} ${name} had no samples`);
            assert.ok(Number.isFinite(histogram.rawMaxMillis) &&
                histogram.rawMaxMillis >= 0 && histogram.rawMaxMillis <= limit,
            `stress tier ${tier} client lifecycle ${index + 1} ${name} raw max ` +
                `${histogram.rawMaxMillis}ms exceeded ${limit}ms`);
            const bounds = histogram.rawQuantileUpperBoundsMillis;
            assert.ok(bounds && Number.isFinite(bounds.p99Millis),
                `stress tier ${tier} client lifecycle ${index + 1} ${name} omitted raw quantile bounds`);
        }
        assertOptionalPollPhaseTelemetry(
            performanceEvidence,
            tier,
            `client lifecycle ${index + 1}`,
            configuration?.performanceContract?.pollPhaseTelemetry,
        );
    }
}

function assertInboundFlowStage(
    evidence,
    expectedLabel,
    configurationPauseLimitMillis,
    options = {},
) {
    const label = options.diagnosticLabel ?? expectedLabel;
    const requireCleanup = options.requireCleanup === true;
    const isWindowEvidence = evidence?.schemaVersion ===
        "gaius.browser-inbound-flow-window-evidence.v2";
    assert.ok(isWindowEvidence || evidence?.schemaVersion ===
        "gaius.browser-inbound-flow-evidence.v1", `${label} schema drifted`);
    const expectedSource = isWindowEvidence
        ? "BrowserWebSocketChannel.__gaiusNettyBridge.stats.highWatermarkEvents"
        : "BrowserWebSocketChannel.__gaiusNettyBridge.stats";
    assert.equal(evidence?.source, expectedSource,
        `${label} source drifted`);
    assert.equal(evidence?.label, expectedLabel, `${label} stage label drifted`);
    assert.equal(evidence?.available, true, `${label} telemetry was unavailable`);
    assert.equal(evidence?.ready, true, `${label} transport remained paused`);
    const withinPauseLimit = isWindowEvidence
        ? evidence?.windowWithinPauseLimit
        : evidence?.withinPauseLimit;
    const maximumPauseMillis = isWindowEvidence
        ? evidence?.windowMaximumPauseMillis
        : evidence?.maximumPauseMillis;
    const missing = isWindowEvidence
        ? evidence?.telemetryMissing
        : evidence?.missing;
    const fields = isWindowEvidence
        ? evidence?.eventFields
        : evidence?.fields;
    assert.equal(withinPauseLimit, true,
        `${label} exceeded the per-channel pause limit`);
    assert.ok(Number.isFinite(maximumPauseMillis) && maximumPauseMillis >= 0,
    `${label} maximum per-channel pause was invalid`);
    assert.deepEqual(missing, [], `${label} telemetry fields were missing`);
    assert.deepEqual(fields, [
        ...(isWindowEvidence ? INBOUND_FLOW_EVENT_FIELDS : INBOUND_FLOW_EVIDENCE_FIELDS),
    ],
        `${label} evidence field contract drifted`);
    assert.ok(Number.isFinite(Date.parse(evidence?.capturedAt)),
        `${label} capture timestamp was invalid`);
    assert.ok(Number.isFinite(evidence?.capturedAtElapsedMillis) &&
        evidence.capturedAtElapsedMillis >= 0,
    `${label} monotonic capture timestamp was invalid`);
    for (const name of INBOUND_FLOW_EVIDENCE_FIELDS) {
        const value = evidence?.observed?.[name];
        assert.ok(INBOUND_FLOW_DURATION_FIELDS.has(name)
            ? Number.isFinite(value) && value >= 0
            : Number.isSafeInteger(value) && value >= 0,
        `${label} field ${name} is not a valid non-negative metric`);
    }
    const derivedMaximumPauseMillis = Math.max(
        evidence.observed.longestHighWatermarkMillis,
        evidence.observed.activeHighWatermarkLongestMillis,
    );
    assert.equal(maximumPauseMillis, derivedMaximumPauseMillis,
        `${label} maximum pause derivation drifted`);
    assert.equal(evidence.pauseLimitMillis,
        configurationPauseLimitMillis,
        `${label} pause limit drifted`);
    assert.equal(withinPauseLimit,
        derivedMaximumPauseMillis <= configurationPauseLimitMillis,
        `${label} pause-limit verdict drifted`);
    assert.equal(evidence.observed.flowPausedChannels, 0, `${label} flow pause remained`);
    assert.equal(evidence.observed.decodeFlowPausedChannels, 0,
        `${label} decoder pause remained`);
    assert.equal(evidence.observed.activeHighWatermarks, 0,
        `${label} high watermark remained`);
    if (requireCleanup) {
        for (const name of [
            "decodedSliceBacklog",
            "decoderCumulationBytes",
            "decodedPacketQueue",
        ]) {
            assert.equal(evidence.observed[name], 0,
                `${label} retained ${name}`);
        }
    }
}

function removeFlag(flag) {
    const index = argumentsList.indexOf(flag);
    if (index === -1) return false;
    argumentsList.splice(index, 1);
    if (argumentsList.includes(flag)) {
        throw new Error(`${flag} may be supplied only once`);
    }
    return true;
}

function readTierArgument() {
    const equalsArguments = argumentsList.filter((value) => value.startsWith("--tier="));
    if (equalsArguments.length > 1) throw new Error("--tier may be supplied only once");
    let raw;
    if (equalsArguments.length === 1) {
        raw = equalsArguments[0].slice("--tier=".length);
        argumentsList.splice(argumentsList.indexOf(equalsArguments[0]), 1);
    }
    const index = argumentsList.indexOf("--tier");
    if (index !== -1) {
        if (raw !== undefined || index + 1 >= argumentsList.length) {
            throw new Error("Use exactly one --tier=8, --tier=16, --tier 8, or --tier 16");
        }
        raw = argumentsList[index + 1];
        argumentsList.splice(index, 2);
    }
    if (raw === undefined) return undefined;
    if (raw !== "8" && raw !== "16") {
        throw new Error("Stress tier must be exactly 8 or 16");
    }
    return Number(raw);
}

function stressEnvironment(tier) {
    // Keep the child deterministic: the stress runner must not inherit a
    // stale relay/public-target/profile override from the parent shell.  The
    // Java/Node launch plumbing is still preserved explicitly, as is the
    // platform baseline required by child_process and the vanilla server.
    const preservedGaiusNames = new Set([
        "GAIUS_JAVA",
        "GAIUS_JAVA_HOME",
        "GAIUS_JAVA_21",
        "GAIUS_JAVA_25",
    ]);
    const environment = Object.fromEntries(Object.entries(process.env)
        .filter(([name]) => {
            const normalized = name.toUpperCase();
            return !normalized.startsWith("GAIUS_") ||
                preservedGaiusNames.has(normalized);
        }));
    environment.GAIUS_VERSION_PROFILE_PATH = canonicalProfilePath;
    environment.GAIUS_BROWSER_FULL_PATH_STRESS = "1";
    environment.GAIUS_BROWSER_FULL_PATH_STRESS_TIER = String(tier);
    if (process.env.GAIUS_BROWSER_FULL_PATH_TRACE === "1") {
        environment.GAIUS_BROWSER_FULL_PATH_TRACE = "1";
    }
    return environment;
}

function requireSuccessfulResult(run, label) {
    assert.equal(run.timedOut, false, `${label} exceeded ${run.deadlineMillis}ms`);
    assert.equal(run.exitCode, 0,
        `${label} exited ${run.exitCode ?? run.signal}: ${run.stderrTail}`);
    assert.equal(run.parseError, null, `${label} returned invalid JSON: ${run.parseError}`);
    assert.notEqual(run.result, null, `${label} did not return a JSON result`);
    return run.result;
}

async function runChild(childArguments, environment, deadlineMillis, relayDiagnostics) {
    return await new Promise((resolve) => {
        const startedAt = new Date();
        const startedMono = performance.now();
        const child = spawn(process.execPath, [fullPathScript, ...childArguments], {
            env: environment,
            stdio: ["ignore", "pipe", "pipe"],
            detached: process.platform !== "win32",
        });
        let stdout = Buffer.alloc(0);
        let stdoutTail = Buffer.alloc(0);
        let stdoutBytes = 0;
        let stderrBytes = 0;
        let stderrTail = Buffer.alloc(0);
        let timedOut = false;
        let spawnError = null;
        let cleanupAttempted = false;
        let cleanupMethod = null;
        let cleanupError = null;
        let cleanupExitCode = null;
        let cleanupSignal = null;
        let cleanupCompletedAt = null;
        let cleanupCompletion = Promise.resolve();
        let killTimer;
        const deadlineTimer = setTimeout(() => {
            timedOut = true;
            cleanupAttempted = true;
            if (process.platform === "win32") {
                cleanupMethod = "taskkill-tree";
                const cleanup = spawn("taskkill.exe", [
                    "/PID", String(child.pid), "/T", "/F",
                ], { stdio: "ignore", windowsHide: true });
                cleanupCompletion = new Promise((cleanupResolved) => {
                    cleanup.once("error", (error) => {
                        cleanupError = String(error);
                        cleanupCompletedAt = new Date().toISOString();
                        try { child.kill("SIGKILL"); }
                        catch (fallbackError) {
                            cleanupError += `; parent fallback: ${String(fallbackError)}`;
                        }
                        cleanupResolved();
                    });
                    cleanup.once("close", (exitCode, signal) => {
                        cleanupExitCode = exitCode;
                        cleanupSignal = signal;
                        cleanupCompletedAt = new Date().toISOString();
                        if (exitCode !== 0) {
                            cleanupError ??= `taskkill exited ${exitCode ?? signal}`;
                            try { child.kill("SIGKILL"); }
                            catch (fallbackError) {
                                cleanupError += `; parent fallback: ${String(fallbackError)}`;
                            }
                        }
                        cleanupResolved();
                    });
                });
            }
            else {
                cleanupMethod = "posix-process-group";
                try { process.kill(-child.pid, "SIGTERM"); }
                catch (error) { cleanupError = String(error); }
            }
            killTimer = setTimeout(() => {
                if (process.platform === "win32") return;
                try { process.kill(-child.pid, "SIGKILL"); }
                catch (error) { cleanupError ??= String(error); }
            }, 5_000);
            killTimer.unref?.();
        }, deadlineMillis);
        deadlineTimer.unref?.();
        child.stdout.on("data", (chunk) => {
            const bytes = Buffer.from(chunk);
            stdoutBytes += bytes.byteLength;
            stdoutTail = boundedTail(stdoutTail, bytes, MAX_DIAGNOSTIC_TAIL_BYTES);
            if (stdout.byteLength < MAX_STDOUT_BYTES) {
                stdout = Buffer.concat([
                    stdout,
                    bytes.subarray(0, MAX_STDOUT_BYTES - stdout.byteLength),
                ]);
            }
        });
        child.stderr.on("data", (chunk) => {
            const bytes = Buffer.from(chunk);
            stderrBytes += bytes.byteLength;
            stderrTail = boundedTail(stderrTail, bytes, MAX_DIAGNOSTIC_TAIL_BYTES);
            if (relayDiagnostics) process.stderr.write(bytes);
        });
        child.once("error", (error) => { spawnError = String(error); });
        child.once("close", async (exitCode, signal) => {
            clearTimeout(deadlineTimer);
            clearTimeout(killTimer);
            if (cleanupAttempted) {
                await Promise.race([
                    cleanupCompletion,
                    new Promise((cleanupWaitResolved) =>
                        setTimeout(cleanupWaitResolved, 2_000)),
                ]);
            }
            const completedAt = new Date();
            const text = stdout.toString("utf8");
            const lines = text.trim().split(/\r?\n/u).filter(Boolean);
            let result = null;
            let parseError = null;
            if (stdoutBytes > MAX_STDOUT_BYTES) {
                parseError = `stdout exceeded ${MAX_STDOUT_BYTES} bytes`;
            }
            else if (lines.length !== 1) {
                parseError = `expected one JSON line, received ${lines.length}`;
            }
            else {
                try { result = JSON.parse(lines[0]); }
                catch (error) { parseError = String(error); }
            }
            resolve({
                command: [process.execPath, fullPathScript, ...childArguments],
                startedAt: startedAt.toISOString(),
                completedAt: completedAt.toISOString(),
                elapsedMillis: Number((performance.now() - startedMono).toFixed(1)),
                deadlineMillis,
                exitCode,
                signal,
                timedOut,
                spawnError,
                cleanupAttempted,
                cleanupMethod,
                cleanupError,
                cleanupExitCode,
                cleanupSignal,
                cleanupCompletedAt,
                stdoutBytes,
                stderrBytes,
                stdoutTail: stdoutTail.toString("utf8"),
                stderrTail: stderrTail.toString("utf8"),
                parseError,
                result,
            });
        });
    });
}

function boundedTail(previous, next, maximumBytes) {
    const combined = Buffer.concat([previous, next]);
    return combined.byteLength <= maximumBytes
        ? combined
        : combined.subarray(combined.byteLength - maximumBytes);
}

function assertPeriodicServerSyncContract(telemetry, label) {
    assert.ok(telemetry && typeof telemetry === "object",
        `${label}: periodic server-sync contract missing`);
    assert.equal(telemetry.schemaVersion,
        ARRIVAL_PERIODIC_SERVER_SYNC_SCHEMA_VERSION,
        `${label}: periodic server-sync schema drifted`);
    assert.equal(telemetry.classification,
        ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION,
        `${label}: periodic server-sync classification drifted`);
    assert.equal(telemetry.profileId,
        ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID,
        `${label}: periodic server-sync profile drifted`);
    assert.equal(telemetry.protocolVersion,
        ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION,
        `${label}: periodic server-sync protocol drifted`);
    assert.equal(telemetry.packetId,
        ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID,
        `${label}: periodic server-sync packet id drifted`);
    assert.equal(telemetry.nominalGapMillis,
        ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS,
        `${label}: periodic server-sync nominal cadence drifted`);
    assert.equal(telemetry.toleranceMillis,
        ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS,
        `${label}: periodic server-sync tolerance drifted`);
    assert.equal(telemetry.excludedFromUserVisibleStall, true,
        `${label}: periodic server-sync exclusion marker drifted`);
    assert.equal(telemetry.strictGateImpact, "none",
        `${label}: periodic server-sync changed strict-gate accounting`);
    assert.equal(telemetry.diagnosticOnly, true,
        `${label}: periodic server-sync telemetry is not diagnostic-only`);
    assert.equal(telemetry.strictGatesChanged, false,
        `${label}: periodic server-sync changed strict gates`);
    if (Object.prototype.hasOwnProperty.call(telemetry, "gapCountTotal")) {
        assert.ok(Number.isSafeInteger(telemetry.gapCountTotal) &&
            telemetry.gapCountTotal >= 0,
        `${label}: periodic server-sync gap count is invalid`);
        assert.equal(telemetry.gapsExcludedFromUserVisibleStall,
            telemetry.gapCountTotal,
            `${label}: periodic server-sync exclusion count diverged`);
    }
}

function assertArrivalTimelineEvidence(result, tier) {
    const topLevel = result?.arrivalTimeline;
    assert.equal(topLevel?.schemaVersion,
        "gaius.browser-client-arrival-timeline.v1",
        `stress tier ${tier} omitted arrival timeline schema`);
    assert.equal(topLevel?.independentExecution, false,
        `stress tier ${tier} mislabelled live arrival evidence as independent`);
    assert.equal(topLevel?.strictGatesChanged, false,
        `stress tier ${tier} arrival timeline changed strict gates`);
    assert.equal(topLevel?.diagnosticOnly, true,
        `stress tier ${tier} arrival timeline lost diagnostic-only marker`);
    assert.equal(topLevel?.strictEvidenceEligible,
        ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        `stress tier ${tier} arrival strict-evidence eligibility drifted`);
    assertArrivalTraceContract(topLevel?.trace,
        `stress tier ${tier} top-level arrival timeline trace`);
    assertPeriodicServerSyncContract(topLevel?.periodicServerSync,
        `stress tier ${tier} top-level arrival timeline`);
    assert.equal(topLevel?.limits?.perClientSlowSamples, 64);
    assert.equal(topLevel?.limits?.perClientReconnectPhases, 64);
    assert.equal(topLevel?.limits?.frameMetadataRing, 64);
    assert.ok(Array.isArray(topLevel?.clients),
        `stress tier ${tier} omitted per-client arrival evidence`);
    assert.ok(Array.isArray(result?.clients),
        `stress tier ${tier} omitted lifecycle client evidence`);
    assert.equal(topLevel.clients.length, result.clients.length,
        `stress tier ${tier} arrival/client lifecycle count diverged`);
    assert.equal(topLevel.transport?.strictGatesChanged, false,
        `stress tier ${tier} transport arrival telemetry changed strict gates`);
    assert.equal(topLevel.transport?.source?.wireAtSource, "unavailable",
        `stress tier ${tier} transport arrival telemetry synthesized wire time`);
    assert.equal(topLevel.transport?.source?.attributionPolicy,
        "trusted-wire-required-for-upstream; missing-local-segments=>unattributed",
        `stress tier ${tier} transport arrival attribution policy drifted`);
    if (topLevel.transport?.trace !== undefined) {
        assertArrivalTraceContract(topLevel.transport.trace,
            `stress tier ${tier} transport arrival trace`);
    }
    assertPeriodicServerSyncContract(topLevel.transport?.periodicServerSync,
        `stress tier ${tier} transport arrival timeline`);
    for (const [index, client] of result.clients.entries()) {
        const topLevelClient = topLevel.clients[index];
        assert.equal(topLevelClient?.id, client?.id,
            `stress tier ${tier} lifecycle ${index + 1} arrival client id diverged`);
        const timeline = client?.arrivalTimeline;
        assert.equal(timeline?.schemaVersion,
            "gaius.browser-client-arrival-timeline.v1",
            `stress tier ${tier} client lifecycle ${index + 1} omitted arrival schema`);
        assert.equal(timeline?.strictGatesChanged, false,
            `stress tier ${tier} client lifecycle ${index + 1} changed strict gates`);
        assertArrivalTraceContract(timeline?.trace,
            `stress tier ${tier} client lifecycle ${index + 1} trace`);
        assertPeriodicServerSyncContract(timeline?.periodicServerSync,
            `stress tier ${tier} client lifecycle ${index + 1}`);
        assert.equal(timeline?.source?.wireAtSource, "unavailable",
            `stress tier ${tier} client lifecycle ${index + 1} synthesized wire time`);
        assert.equal(timeline?.source?.attributionPolicy,
            "trusted-wire-required-for-upstream; missing-local-segments=>unattributed",
            `stress tier ${tier} lifecycle ${index + 1} attribution policy drifted`);
        assert.ok(Array.isArray(timeline?.slowSamples));
        assert.ok(timeline.slowSamples.length <= 64,
            `stress tier ${tier} client lifecycle ${index + 1} exceeded slow sample cap`);
        for (const [sampleIndex, sample] of timeline.slowSamples.entries()) {
            if (sample.traceWindow !== undefined && sample.traceWindow !== null) {
                assertArrivalTraceWindow(sample.traceWindow,
                    `stress tier ${tier} lifecycle ${index + 1} arrival sample ${sampleIndex}`);
            }
            if (Object.prototype.hasOwnProperty.call(sample ?? {}, "wireAtSource")) {
                assert.equal(sample.wireAtSource, ARRIVAL_WIRE_AT_SOURCE,
                    `stress tier ${tier} lifecycle ${index + 1} arrival sample ${sampleIndex} ` +
                    "synthesized wire timestamp source");
            }
            if (sample.classification === ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION) {
                assert.equal(sample.profileId, ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID,
                    `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                    `${sampleIndex} profile id drifted`);
                assert.equal(sample.protocolVersion,
                    ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION,
                    `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                    `${sampleIndex} protocol drifted`);
                assert.equal(sample.phaseAtDecode, "play",
                    `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                    `${sampleIndex} was outside PLAY`);
                assert.equal(sample.packetId, ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID,
                    `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                    `${sampleIndex} packet id drifted`);
                assert.equal(sample.excludedFromUserVisibleStall, true,
                    `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                    `${sampleIndex} was not excluded from visible-stall reporting`);
                assert.equal(sample.strictGateImpact, "none",
                    `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                    `${sampleIndex} changed strict-gate accounting`);
                assert.equal(sample.cadenceHint,
                    ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION,
                    `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                    `${sampleIndex} cadence hint drifted`);
                assert.ok(Math.abs(sample.decodedGapMillis -
                    ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS) <=
                    ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS,
                `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                    `${sampleIndex} gap escaped cadence tolerance`);
                for (const [segmentName, segmentValue] of Object.entries(
                    sample.segments ?? {})) {
                    if (segmentName === "wireToOnmessageMillis" ||
                        segmentValue === null || segmentValue === undefined) {
                        continue;
                    }
                    assert.ok(!(Number.isFinite(segmentValue) && segmentValue >= 250),
                        `stress tier ${tier} lifecycle ${index + 1} periodic sample ` +
                        `${sampleIndex} hid slow local segment ${segmentName}`);
                }
            }
        }
        assert.ok(Array.isArray(timeline?.reconnectPhases));
        assert.ok(timeline.reconnectPhases.length <= 64,
            `stress tier ${tier} client lifecycle ${index + 1} exceeded phase cap`);
        assert.equal(timeline?.phaseRingScope, "all-lifecycle-phases",
            `stress tier ${tier} lifecycle ${index + 1} mislabelled phase ring scope`);
        if (client.wave > 0) {
            const boundary = timeline.reconnectBoundary;
            assert.equal(timeline.reconnectBoundary?.attempted, true,
                `stress tier ${tier} reconnect lifecycle ${index + 1} omitted boundary`);
            assert.equal(boundary?.seededFromPreviousLifecycle, true,
                `stress tier ${tier} reconnect lifecycle ${index + 1} omitted prior decode seed`);
            assert.equal(boundary?.firstDecodedAfterReconnect, true,
                `stress tier ${tier} reconnect lifecycle ${index + 1} omitted first decode boundary`);
            for (const field of ["priorDecodeEndAt", "disconnectAt",
                "reconnectScheduledAt", "firstDecodedAt", "reconnectGapMillis"]) {
                assert.ok(Number.isFinite(boundary?.[field]) && boundary[field] >= 0,
                    `stress tier ${tier} reconnect lifecycle ${index + 1} missing finite ${field}`);
            }
            assert.ok(boundary.reconnectScheduledAt >= boundary.disconnectAt,
                `stress tier ${tier} reconnect lifecycle ${index + 1} phase order disconnect/schedule`);
            assert.ok(boundary.firstDecodedAt >= boundary.reconnectScheduledAt,
                `stress tier ${tier} reconnect lifecycle ${index + 1} phase order schedule/decode`);
            assert.ok(boundary.reconnectGapMillis >= 0,
                `stress tier ${tier} reconnect lifecycle ${index + 1} negative reconnect gap`);
        }
    }
}

function assertArrivalTraceContract(trace, label) {
    assert.ok(trace && typeof trace === "object", `${label} omitted`);
    assert.equal(trace.schemaVersion, ARRIVAL_TRACE_SCHEMA_VERSION,
        `${label} schema drifted`);
    assert.equal(trace.eventLimit, ARRIVAL_TRACE_EVENT_LIMIT,
        `${label} event limit drifted`);
    assert.equal(trace.enabled, !ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        `${label} enabled marker drifted from requested diagnostic mode`);
    assert.equal(trace.diagnosticOnly, true,
        `${label} was not diagnostic-only`);
    assert.equal(trace.strictEvidenceEligible,
        ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        `${label} strict-evidence eligibility drifted`);
    assert.equal(trace.strictGatesChanged, false,
        `${label} changed strict gates`);
    assert.equal(trace.wireAtSource, ARRIVAL_WIRE_AT_SOURCE,
        `${label} synthesized wire time`);
    assert.equal(trace.bridgeEnqueueTimestampAvailable, false,
        `${label} claimed bridge enqueue timestamps`);
    assert.equal(typeof trace.enabled, "boolean",
        `${label} enabled flag missing`);
}

function assertArrivalTraceWindow(window, label) {
    assert.ok(window && typeof window === "object", `${label} omitted`);
    assert.equal(window.schemaVersion, ARRIVAL_TRACE_SCHEMA_VERSION,
        `${label} schema drifted`);
    assert.equal(window.enabled, !ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        `${label} enabled marker drifted from requested diagnostic mode`);
    assert.equal(window.diagnosticOnly, true,
        `${label} was not diagnostic-only`);
    assert.equal(window.strictEvidenceEligible,
        ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        `${label} strict-evidence eligibility drifted`);
    assert.equal(window.strictGatesChanged, false,
        `${label} changed strict gates`);
    assert.equal(window.wireAtSource, ARRIVAL_WIRE_AT_SOURCE,
        `${label} synthesized wire time`);
    assert.equal(window.bridgeEnqueueTimestampAvailable, false,
        `${label} claimed bridge enqueue timestamps`);
    assert.ok(Number.isSafeInteger(window.limit) &&
        window.limit === ARRIVAL_TRACE_EVENT_LIMIT,
    `${label} window limit drifted`);
    assert.ok(Array.isArray(window.events) &&
        window.events.length <= ARRIVAL_TRACE_EVENT_LIMIT,
    `${label} exceeded bounded event limit`);
    assert.ok(Number.isSafeInteger(window.dropped) && window.dropped >= 0,
        `${label} dropped count invalid`);
    assert.ok(Number.isSafeInteger(window.ringDropped) && window.ringDropped >= 0,
        `${label} ring dropped count invalid`);
    assert.ok(typeof window.coverage === "string",
        `${label} coverage missing`);
    for (const event of window.events) {
        assert.equal(event.schemaVersion, ARRIVAL_TRACE_SCHEMA_VERSION,
            `${label} event schema drifted`);
        assert.ok(Number.isSafeInteger(event.sequence) && event.sequence > 0,
            `${label} event sequence invalid`);
    }
}

async function inspectBrowserChannelSource() {
    const source = await readFile(channelSourcePath, "utf8");
    const normalizedSource = source.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
    const markers = BROWSER_CHANNEL_SOURCE_MARKERS.map(({name, marker, jsBody}) => {
        const offset = source.indexOf(marker);
        assert.ok(offset >= 0,
            `BrowserWebSocketChannel source marker missing: ${name}`);
        const entry = { name, marker, present: true };
        if (jsBody) {
            const script = extractJsBody(source, marker);
            entry.jsBodyBytes = Buffer.byteLength(script, "utf8");
            entry.jsBodySha256 = createHash("sha256").update(script).digest("hex");
            entry.jsBodyNormalizedSha256 = createHash("sha256")
                .update(script.replaceAll("\r\n", "\n").replaceAll("\r", "\n"))
                .digest("hex");
        }
        return entry;
    });
    const jsBodyMarkers = markers
        .filter((marker) => marker.jsBodySha256 !== undefined)
        .map((marker) => ({
            name: marker.name,
            marker: marker.marker,
            present: marker.present,
            jsBodyBytes: marker.jsBodyBytes,
            jsBodySha256: marker.jsBodySha256,
            jsBodyNormalizedSha256: marker.jsBodyNormalizedSha256,
        }));
    return {
        schemaVersion: BROWSER_CHANNEL_SOURCE_EVIDENCE_SCHEMA_VERSION,
        independentExecution: true,
        path: channelSourcePath,
        relativePath: "port/overrides/libraries/netty-transport/src/main/java/" +
            "io/netty/channel/browser/BrowserWebSocketChannel.java",
        hashAlgorithm: "sha256",
        sourceBytes: Buffer.byteLength(source, "utf8"),
        sourceSha256: createHash("sha256").update(source).digest("hex"),
        normalizedSourceBytes: Buffer.byteLength(normalizedSource, "utf8"),
        normalizedSourceSha256: createHash("sha256")
            .update(normalizedSource)
            .digest("hex"),
        requiredMarkerCount: markers.length,
        sourceMarkers: markers.map((marker) => ({
            name: marker.name,
            marker: marker.marker,
            present: marker.present,
            ...(marker.jsBodySha256 === undefined ? {} : {
                jsBodySha256: marker.jsBodySha256,
                jsBodyNormalizedSha256: marker.jsBodyNormalizedSha256,
            }),
        })),
        jsBodyMarkers,
    };
}

function extractJsBody(source, marker) {
    const markerOffset = source.indexOf(marker);
    const annotationOffset = source.lastIndexOf("@JSBody(script = \"\"\"", markerOffset);
    const scriptOffset = source.indexOf("\"\"\"", annotationOffset) + 3;
    const firstScriptEnd = source.indexOf("\"\"\")", scriptOffset);
    // Method signatures occur after their text block, while resolver markers
    // live inside the JSBody text itself. Select the enclosing terminator in
    // both cases without changing the extracted script bytes.
    const scriptEnd = markerOffset > scriptOffset && markerOffset < firstScriptEnd
        ? firstScriptEnd
        : source.lastIndexOf("\"\"\")", markerOffset);
    assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
        `could not extract BrowserWebSocketChannel JSBody for ${marker}`);
    return source.slice(scriptOffset, scriptEnd);
}

function assertSourceProvenance(actual, expected, label) {
    assert.ok(actual && typeof actual === "object",
        `${label} omitted BrowserWebSocketChannel source evidence`);
    assert.equal(actual.schemaVersion, expected.schemaVersion,
        `${label} source evidence schema drifted`);
    assert.equal(actual.independentExecution, true,
        `${label} source evidence was not independently collected`);
    assert.equal(actual.relativePath, expected.relativePath,
        `${label} BrowserWebSocketChannel source path drifted`);
    assert.equal(actual.hashAlgorithm, "sha256",
        `${label} source hash algorithm drifted`);
    for (const [name, value] of [
        ["sourceBytes", actual.sourceBytes],
        ["normalizedSourceBytes", actual.normalizedSourceBytes],
        ["requiredMarkerCount", actual.requiredMarkerCount],
    ]) {
        assert.ok(Number.isSafeInteger(value) && value > 0,
            `${label} source evidence ${name} was invalid: ${value}`);
    }
    for (const name of ["sourceSha256", "normalizedSourceSha256"]) {
        assert.match(actual[name], /^[0-9a-f]{64}$/u,
            `${label} source evidence ${name} was invalid`);
    }
    assert.equal(actual.normalizedSourceSha256, expected.normalizedSourceSha256,
        `${label} source normalized SHA-256 drifted`);
    assert.equal(actual.sourceSha256, expected.sourceSha256,
        `${label} source SHA-256 drifted`);
    assert.equal(actual.requiredMarkerCount, expected.requiredMarkerCount,
        `${label} source marker count drifted`);
    assert.ok(Array.isArray(actual.sourceMarkers),
        `${label} source markers were not an array`);
    assert.deepEqual(actual.sourceMarkers, expected.sourceMarkers,
        `${label} source marker evidence drifted`);
    assert.deepEqual(actual.jsBodyMarkers, expected.jsBodyMarkers,
        `${label} JSBody marker evidence drifted`);
}
