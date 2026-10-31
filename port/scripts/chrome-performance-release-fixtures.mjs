import {CANONICAL_PROFILE, LEGACY_PROFILE} from "./verification-profiles.mjs";
import {
  evaluateUncappedFramePacing,
  mergeFramePacingTelemetrySources,
  normalizeFramePacingEvidenceSnapshot,
  normalizeFramePacingSettlementEvidence,
  REQUIRED_UNCAPPED_FRAME_PACING_FIELDS,
} from "./performance-metrics.mjs";

const fixtureHash = (character) => character.repeat(64);
export const acceptanceMeasurementEpochId = "fixture-measurement-epoch";

const acceptanceUncappedRequirements = {
  requiredSwapInterval: 0,
  minimumSamples: 2,
  minimumUncappedYieldCount: 1,
  minimumMessageChannelYieldCount: 1,
  maximumFairYieldCount: 0,
  maximumSchedulerYieldCount: 0,
  maximumTimerYieldCount: 0,
  maximumVsyncYieldCount: 0,
  maximumPresentToRafCount: 0,
  maximumMessageChannelCreateFailureCount: 0,
  maximumMessageChannelPostFailureCount: 0,
  maximumMessageChannelRebuildCount: 0,
  maximumCancelledMessageTaskCount: 0,
  maximumWatchdogYieldCount: 0,
  requireFramePacingSettlement: true,
  settlementSchemaVersion: 1,
  settlementPollIntervalMillis: 8,
  settlementPollIntervalMillisMin: 5,
  settlementPollIntervalMillisMax: 10,
  settlementTimeoutMillis: 200,
  settlementTimeoutMillisMin: 150,
  settlementTimeoutMillisMax: 250,
  requiredFields: [...REQUIRED_UNCAPPED_FRAME_PACING_FIELDS],
};

function acceptanceFramePacingSnapshot(count) {
  return {
    measurementId: acceptanceMeasurementEpochId,
    measurementEpochId: acceptanceMeasurementEpochId,
    swapInterval: 0,
    uncappedYieldCount: count,
    vsyncYieldCount: 0,
    visibleYieldCount: count,
    hiddenYieldCount: 0,
    presentToRafCount: 0,
    messageChannelYieldCount: count,
    fairYieldCount: 0,
    schedulerYieldCount: 0,
    timerYieldCount: 0,
    yieldRequestCount: count,
    yieldCompletionCount: count,
    pendingYieldCount: 0,
    maxPendingYieldCount: count > 0 ? 1 : 0,
    duplicateYieldCallbackCount: 0,
    messageChannelCreateFailureCount: 0,
    messageChannelPostFailureCount: 0,
    messageChannelRebuildCount: 0,
    cancelledMessageTaskCount: 0,
    watchdogYieldCount: 0,
  };
}

function withDualFramePacingSources(snapshot, {
  capturedAt,
  controllerRequestedAt = capturedAt - 1,
  controllerReceivedAt = capturedAt + 1,
  cutoffYieldRequestCount,
} = {}) {
  const pacing = {...snapshot};
  const result = {
    ...pacing,
    capturedAt,
    controllerRequestedAt,
    controllerReceivedAt,
    evaluationLatencyMillis: controllerReceivedAt - controllerRequestedAt,
    deadlineExceeded: false,
    frame: {...pacing},
    runtimeInvariants: {framePacing: {...pacing}},
  };
  if (cutoffYieldRequestCount != null) {
    result.cutoffYieldRequestCount = cutoffYieldRequestCount;
  }
  return result;
}

export function makeAcceptanceFramePacingSettlement(initial, {
  initialCapturedAt = 3,
  capturedAt = 4,
  final = initial,
} = {}) {
  const requiredMessageChannelCompletionDelta = initial.pendingYieldCount === 1 ? 1 : 0;
  const messageChannelCompletionDelta = final.messageChannelYieldCount
    - initial.messageChannelYieldCount;
  const yieldCompletionDelta = final.yieldCompletionCount - initial.yieldCompletionCount;
  const settled = requiredMessageChannelCompletionDelta === 0
    || (messageChannelCompletionDelta >= 1 && yieldCompletionDelta >= 1);
  const finalScalar = withDualFramePacingSources(final, {capturedAt});
  const controllerStartedAt = finalScalar.controllerRequestedAt;
  const controllerCompletedAt = finalScalar.controllerReceivedAt;
  return {
    schemaVersion: 1,
    measurementId: acceptanceMeasurementEpochId,
    measurementEpochId: acceptanceMeasurementEpochId,
    initialCapturedAt,
    capturedAt,
    controllerStartedAt,
    controllerCompletedAt,
    controllerElapsedMillis: controllerCompletedAt - controllerStartedAt,
    pollIntervalMillis: 8,
    timeoutMillis: 200,
    initialPendingYieldCount: initial.pendingYieldCount,
    requiredMessageChannelCompletionDelta,
    messageChannelCompletionDelta,
    yieldCompletionDelta,
    settled,
    timedOut: !settled,
    deadlineExceeded: false,
    samples: [finalScalar],
    final: structuredClone(finalScalar),
  };
}

export const acceptanceFixtureIdentity = {
  manifestSha256: fixtureHash("a"),
  compatibilitySha256: fixtureHash("b"),
  coherent: true,
  artifactCompatibilities: [fixtureHash("b"), fixtureHash("b"), fixtureHash("b"), fixtureHash("b")],
};

export const releaseManifestFixtureIdentity = {
  profileId: CANONICAL_PROFILE.id,
  profilePath: "versions/" + CANONICAL_PROFILE.id + ".json",
  profileSha256: fixtureHash("c"),
  clientDistribution: "named",
  protocolVersion: 776,
  worldVersion: 4903,
  worldgenTelemetryMode: "task-pulsed",
  storage: {
    schema: 2,
    databaseName: "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
    prefix: "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
    opfsDirectory: "regions-v2-" + CANONICAL_PROFILE.id + "",
  },
};

export const legacyReleaseManifestFixtureIdentity = {
  profileId: LEGACY_PROFILE.id,
  profilePath: "versions/" + LEGACY_PROFILE.id + ".json",
  profileSha256: fixtureHash("d"),
  clientDistribution: "obfuscated-with-mappings",
  protocolVersion: 774,
  worldVersion: 4671,
  worldgenTelemetryMode: "checkpoint-only",
  storage: {
    schema: 2,
    databaseName: "gaius-fs-v2-" + LEGACY_PROFILE.id + "",
    prefix: "gaius.fs.v2:" + LEGACY_PROFILE.id + ":",
    opfsDirectory: "regions-v2-" + LEGACY_PROFILE.id + "",
  },
};

export function makeManifestIdentityFixture(identity = releaseManifestFixtureIdentity) {
  const storage = {...identity.storage};
  const nestedProfile = {
    id: identity.profileId,
    path: identity.profilePath,
    sha256: identity.profileSha256,
    clientDistribution: identity.clientDistribution,
    protocolVersion: identity.protocolVersion,
    worldVersion: identity.worldVersion,
    worldgenTelemetryMode: identity.worldgenTelemetryMode,
    storage: {...storage},
  };
  const compatibilitySha256 = fixtureHash("b");
  return {
    artifact: "Gaius.html",
    profile: identity.profileId,
    profilePath: identity.profilePath,
    profileSha256: identity.profileSha256,
    worldVersion: identity.worldVersion,
    worldgenTelemetryMode: identity.worldgenTelemetryMode,
    storage,
    buildIdentity: {
      schemaVersion: 2,
      compatibilitySha256,
      profile: nestedProfile,
    },
    classesJs: {build: {compatibilitySha256}},
    singleplayerServerJs: {build: {compatibilitySha256}},
    singleplayerWorkerBootstrap: {build: {compatibilitySha256}},
    wasmHotpath: {build: {compatibilitySha256}},
  };
}

export const acceptanceFixtureProfile = {
  evidenceRole: "release-hard-target",
  releaseEvidence: true,
  scenario: "steady",
  route: "singleplayer",
  renderDistance: 8,
  simulationDistance: 6,
  gates: {
    averageFpsMin: 120,
    onePercentLowFpsMin: 60,
    coverageRatioMin: 0.98,
    longestFrameMsMax: 50,
    stallCountAtLeast2sMax: 0,
    freezeCountMax: 0,
    memory: false,
  },
};

export function makeAcceptanceFixtureReport({
  profileName = "hard-a",
  profile = acceptanceFixtureProfile,
  contractSchemaVersion = 14,
  buildIdentity = acceptanceFixtureIdentity,
  uncappedEvidence = acceptanceUncappedRequirements,
  analysisOverrides = {},
} = {}) {
  const releaseEvidence = profile.releaseEvidence === true;
  const mode = releaseEvidence ? "release-gating" : "diagnostic-stress";
  const verdict = "pass";
  const expectedDistance = `${Number(profile.renderDistance)}:${Number(profile.simulationDistance)}`;
  const naturalWorkerEvidence = {
    mode: "natural-observation",
    complete: true,
    truncatedCount: 0,
    messages: [{
      type: "start",
      launchGeneration: "1",
      renderDistance: Number(profile.renderDistance),
      simulationDistance: Number(profile.simulationDistance),
      profileId: CANONICAL_PROFILE.id,
      worldVersion: 4903,
      storageSchema: 2,
      storageDatabaseName: "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
      storagePrefix: "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
      storageOpfsDirectory: "regions-v2-" + CANONICAL_PROFILE.id + "",
    }],
  };
  const harnessOverrideEvidence = {
    enabled: false,
    mode: "disabled",
    messages: [],
    truncatedCount: 0,
    releaseEligible: false,
  };
  const samples = [4, 8].map((count, index) => {
    const framePacing = acceptanceFramePacingSnapshot(count);
    return {
    at: index + 1,
    evaluationLatencyMillis: 1,
    measurementId: acceptanceMeasurementEpochId,
    measurementEpochId: acceptanceMeasurementEpochId,
    frame: {...framePacing},
    runtimeInvariants: {framePacing: {...framePacing}},
    };
  });
  const finalFramePacing = acceptanceFramePacingSnapshot(8);
  const settlement = makeAcceptanceFramePacingSettlement(finalFramePacing);
  const telemetry = {
    capturedAt: 3,
    measurementId: acceptanceMeasurementEpochId,
    measurementEpochId: acceptanceMeasurementEpochId,
    controllerRequestedAt: 2,
    controllerReceivedAt: 3,
    evaluationLatencyMillis: 1,
    deadlineExceeded: false,
    frame: {...finalFramePacing},
    runtimeInvariants: {framePacing: {...finalFramePacing}},
    framePacingSettlement: settlement,
  };
  const cleanupFramePacing = withDualFramePacingSources(settlement.final, {
    capturedAt: 5,
    controllerRequestedAt: 5,
    controllerReceivedAt: 6,
    cutoffYieldRequestCount: settlement.final.yieldRequestCount,
  });
  telemetry.cleanupFramePacing = structuredClone(cleanupFramePacing);
  const cleanupTelemetry = structuredClone(cleanupFramePacing);
  cleanupTelemetry.framePacingClosure = structuredClone(cleanupFramePacing);
  const sampleSources = samples.map((sample, index) =>
    normalizeFramePacingEvidenceSnapshot(sample, {
      requiredFields: uncappedEvidence.requiredFields,
      label: `sample[${index}]`,
      requireDualSources: true,
    }));
  const finalSources = normalizeFramePacingEvidenceSnapshot(telemetry, {
    requiredFields: uncappedEvidence.requiredFields,
    label: "final",
    requireDualSources: true,
  });
  const settlementSources = normalizeFramePacingSettlementEvidence(settlement, {
    requiredFields: uncappedEvidence.requiredFields,
    label: "settlement",
    requireDualSources: true,
  });
  const cleanupSources = normalizeFramePacingEvidenceSnapshot(cleanupTelemetry, {
    requiredFields: uncappedEvidence.requiredFields,
    label: "cleanup",
    requireDualSources: true,
  });
  const framePacingEvidence = evaluateUncappedFramePacing({
    samples: sampleSources.map(({merged}) => merged),
    final: finalSources.merged,
    settlement: settlementSources.settlement,
    cleanup: cleanupSources.merged,
    measurementEpochId: acceptanceMeasurementEpochId,
    requireEpochClosure: true,
    sourceOverlapMismatches: [
      ...sampleSources.flatMap(({overlapMismatches}) => overlapMismatches),
      ...sampleSources.flatMap(({sourceCompletenessFailures}) => sourceCompletenessFailures),
      ...finalSources.overlapMismatches,
      ...finalSources.sourceCompletenessFailures,
      ...settlementSources.overlapMismatches,
      ...settlementSources.sourceCompletenessFailures,
      ...cleanupSources.overlapMismatches,
      ...cleanupSources.sourceCompletenessFailures,
    ],
    timing: {
      lastSampleAt: 2,
      finalCapturedAt: 3,
      settlementCapturedAt: 4,
      cleanupCapturedAt: 5,
    },
    requirements: uncappedEvidence,
  });
  return {
    schemaVersion: contractSchemaVersion,
    passed: true,
    verdict,
    profileName,
    buildIdentity,
    telemetry,
    cleanupTelemetry,
    samples,
    configuration: {
      profileName,
      contractSchemaVersion,
      profile,
      worldgenTelemetryMode: "task-pulsed",
      gating: releaseEvidence,
      releaseEvidence,
      strictChecks: true,
      measurementId: acceptanceMeasurementEpochId,
      measurementEpochId: acceptanceMeasurementEpochId,
      buildIdentity,
      workerDistanceContract: {
        mode: "natural-observation",
        releaseEligible: true,
        optionsPreference: expectedDistance,
        expectedWorkerServerDistance: expectedDistance,
        matchingWorkerStartMessages: 1,
        expectedStorage: {
          profileId: CANONICAL_PROFILE.id,
          worldVersion: 4903,
          storageSchema: 2,
          storageDatabaseName: "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
          storagePrefix: "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
          storageOpfsDirectory: "regions-v2-" + CANONICAL_PROFILE.id + "",
        },
        naturalWorkerEvidence,
        harnessOverrideEvidence,
      },
    },
    analysis: {
      verdict,
      passed: true,
      mode,
      gating: releaseEvidence,
      releaseEvidence,
      evidenceRole: profile.evidenceRole || null,
      worldgenTelemetryMode: "task-pulsed",
      environment: {
        profileName,
        frameLimiter: {uncapped: true},
        distanceContract: {
          mode: "natural-observation",
          releaseEligible: true,
          optionsPreference: expectedDistance,
          expectedWorkerServerDistance: expectedDistance,
          matchingWorkerStartMessages: 1,
          expectedStorage: {
            profileId: CANONICAL_PROFILE.id,
            worldVersion: 4903,
            storageSchema: 2,
            storageDatabaseName: "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
            storagePrefix: "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
            storageOpfsDirectory: "regions-v2-" + CANONICAL_PROFILE.id + "",
          },
          naturalWorkerEvidence,
          harnessOverrideEvidence,
        },
      },
      naturalWorkerEvidence,
      harnessOverrideEvidence,
      frame: {
        sampleCount: 36_000,
        rawFrameCount: 36_000,
        rawSampleCount: 360,
        averageFpsRaw: 144,
        onePercentLowFpsRaw: 72,
        coverageRatioRaw: 1,
        longestFrameMsRaw: 18,
        longFrames: {
          atLeast2000Ms: 0,
        },
      },
      freezes: {
        total: 0,
      },
      stability: {
        verdict: "pass",
        fatalEvents: [],
        contextLosses: [],
        maximumStateStallMillis: 0,
      },
      queues: {
        verdict: "pass",
        queues: {
          "chunk.pendingTasks": {
            available: true,
            maximum: 2,
            finalValue: 0,
            longestHighWaterMs: 20,
            failed: false,
          },
          "chunk.compileBacklog": {
            available: true,
            maximum: 1,
            finalValue: 0,
            longestHighWaterMs: 20,
            failed: false,
          },
          "chunk.uploadBacklog": {
            available: true,
            maximum: 1,
            finalValue: 0,
            longestHighWaterMs: 20,
            failed: false,
          },
        },
      },
      workerMessage: {
        rttSampleCount: 360,
        p99RttMillis: 12,
        maxRttMillis: 24,
        pending: 0,
      },
      memory: {
        verdict: "pass",
        v8Heap: {
          verdict: "pass",
          regularSampleCount: 8,
          postGcSampleCount: 4,
          leakSignal: false,
          finalThreeWindowsPositive: false,
          postGcSlopeMiBPerMinute: 0.1,
          retainedGrowthPercent: 1,
        },
        browserMemory: {
          verdict: "pass",
          regularSampleCount: 8,
          postGcSampleCount: 4,
          metrics: {
            liveBytes: {finalThreeWindowsPositive: false},
          },
        },
        processRss: {
          verdict: "pass",
          availability: {availableSampleCount: 8},
          failedMetrics: [],
          total: {failed: false},
          byType: {},
        },
      },
      performanceEvidence: {
        framePacing: framePacingEvidence,
      },
      ...analysisOverrides,
    },
  };
}
