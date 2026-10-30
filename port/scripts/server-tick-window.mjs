function hasTickCounters(tick) {
  return tick && ["intervalCount", "totalTickIntervalMillis", "completedTickCount",
    "totalTickDurationMillis", "completedWaitPhaseCount", "totalWaitPhaseDurationMillis"]
    .every((field) => Number.isFinite(tick[field]));
}

export function serverTickWindow(start, end) {
  const fields = [
    "intervalCount",
    "totalTickIntervalMillis",
    "completedTickCount",
    "totalTickDurationMillis",
    "completedWaitPhaseCount",
    "totalWaitPhaseDurationMillis",
  ];
  if (start === null || end === null || start === undefined || end === undefined) {
    return {available: false, reason: "missing"};
  }
  const delta = {};
  for (const field of fields) {
    const finish = end[field];
    const begin = start[field];
    if (typeof finish !== "number" || typeof begin !== "number" ||
        !Number.isFinite(finish) || !Number.isFinite(begin)) {
      return {available: false, reason: "missing-counter", field};
    }
    delta[field] = finish - begin;
    if (delta[field] < 0) {
      return {available: false, reason: "counter-reset", field};
    }
  }
  const elapsedMillis = end.sampledAtMillis - start.sampledAtMillis;
  if (!Number.isFinite(start.sampledAtMillis) || !Number.isFinite(end.sampledAtMillis) ||
      elapsedMillis <= 0) {
    return {available: false, reason: "invalid-sample-window"};
  }
  // A catch-up burst can average 20 TPS while entities still freeze for seconds.
  // Preserve interval/work/wait hitches for this exact window, not cumulative maxima.
  const hitchCounts = {};
  for (const field of ["intervalOver100MillisCount", "intervalOver500MillisCount",
    "tickWorkOver500MillisCount", "waitPhaseOver500MillisCount"]) {
    const begin = start[field];
    const finish = end[field];
    hitchCounts[field] = Number.isInteger(begin) && Number.isInteger(finish) &&
      begin >= 0 && finish >= begin ? finish - begin : null;
  }
  return {
    available: true,
    schemaVersion: Number(end.schemaVersion) || null,
    intervalCount: delta.intervalCount,
    completedTickCount: delta.completedTickCount,
    elapsedMillis,
    totalTickIntervalMillis: delta.totalTickIntervalMillis,
    totalTickDurationMillis: delta.totalTickDurationMillis,
    completedWaitPhaseCount: delta.completedWaitPhaseCount,
    totalWaitPhaseDurationMillis: delta.totalWaitPhaseDurationMillis,
    observedTps: delta.intervalCount * 1000 / elapsedMillis,
    hitchCounts,
  };
}

// Collect the first Worker tick snapshot without moving the measurement
// window's browser start.  The caller supplies the real Worker read and clock
// so this helper remains testable and cannot invent a timestamp or epoch.
export async function collectInitialServerTickSamples(initialSamples, {
  read,
  sleep,
  now = () => Date.now(),
  expectedSessionId,
  expectedMeasurementId,
  intervalMillis = 50,
  maxWaitMillis = 500,
} = {}) {
  if (typeof read !== "function" || typeof sleep !== "function") {
    throw new TypeError("read and sleep callbacks are required");
  }
  if (!Number.isFinite(intervalMillis) || intervalMillis <= 0 ||
      !Number.isFinite(maxWaitMillis) || maxWaitMillis < 0) {
    throw new RangeError("intervalMillis/maxWaitMillis must be finite and nonnegative");
  }
  const samples = Array.isArray(initialSamples) ? initialSamples.slice() : [];
  const startedAt = now();
  let attempts = 0;
  while (true) {
    const sample = await read();
    attempts++;
    if (sample !== undefined && sample !== null) samples.push(sample);
    const tick = sample?.worker?.serverTick;
    if (hasTickCounters(tick) && Number.isFinite(tick.sampledAtMillis)) {
      const worker = sample.worker;
      if (worker.sessionId !== expectedSessionId ||
          worker.measurementId !== expectedMeasurementId) {
        return {available: false, reason: "worker-window-changed", samples, attempts,
          waitedMillis: Math.max(0, now() - startedAt)};
      }
      return {available: true, reason: "initial-tick-observed", samples, attempts,
        waitedMillis: Math.max(0, now() - startedAt)};
    }
    const elapsed = Math.max(0, now() - startedAt);
    if (elapsed >= maxWaitMillis) {
      return {available: false, reason: "initial-tick-timeout", samples, attempts,
        waitedMillis: elapsed};
    }
    await sleep(Math.min(intervalMillis, maxWaitMillis - elapsed));
  }
}

export function browserServerTickWindow(samples, maximumSampleAgeMillis = 250) {
  if (!Array.isArray(samples) || samples.length < 2) {
    return {available: false, reason: "insufficient-browser-samples"};
  }
  const first = samples[0], last = samples.at(-1);
  const browserElapsedMillis = last.at - first.at;
  if (!Number.isFinite(browserElapsedMillis) || browserElapsedMillis <= 0) {
    return {available: false, reason: "invalid-browser-window"};
  }
  let start = null, previous = null, sessionId = null, measurementId = null;
  for (const sample of samples) {
    const worker = sample.worker;
    const tick = worker?.serverTick;
    if (!tick || !Number.isFinite(tick.sampledAtMillis)) {
      if (start) return {available: false, reason: "missing-tick-snapshot-inside-window"};
      continue;
    }
    // A reset heartbeat may arrive before the first instrumented tick. It has
    // a timestamp but no counters; preserve its browser time, not fake zeros.
    if (!start && !hasTickCounters(tick)) continue;
    if (!worker.sessionId || !worker.measurementId) {
      return {available: false, reason: "missing-worker-identity"};
    }
    if (start && (worker.sessionId !== sessionId || worker.measurementId !== measurementId)) {
      return {available: false, reason: "worker-window-changed"};
    }
    const ageMillis = sample.at - worker.updatedAt;
    if (!Number.isFinite(ageMillis) || ageMillis < 0 || ageMillis > maximumSampleAgeMillis) {
      return {available: false, reason: "stale-worker-snapshot", ageMillis};
    }
    if (previous && tick.sampledAtMillis < previous.sampledAtMillis) {
      return {available: false, reason: "worker-clock-reset"};
    }
    if (previous && tick.sampledAtMillis > previous.sampledAtMillis) {
      const segment = serverTickWindow(previous, tick);
      if (!segment.available) return segment;
    }
    start ??= tick;
    sessionId = worker.sessionId;
    measurementId = worker.measurementId;
    previous = tick;
  }
  const result = serverTickWindow(start, previous);
  if (!result.available) return result;
  const coverageRatio = result.elapsedMillis / browserElapsedMillis;
  if (Math.abs(result.elapsedMillis - browserElapsedMillis) > maximumSampleAgeMillis * 2) {
    return {available: false, reason: "incomplete-browser-window", browserElapsedMillis,
      observedElapsedMillis: result.elapsedMillis, coverageRatio};
  }
  return {...result, browserElapsedMillis, coverageRatio, sessionId, measurementId};
}
