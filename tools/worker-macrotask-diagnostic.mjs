// Diagnostic injection only. Callback order, arguments and return values are preserved.
export function installMacrotaskDiagnostic() {
  const trace = globalThis.__gaiusMacrotaskDiagnostic = {
    diagnosticOnly: true, posted: 0, resumed: 0, pendingHighWater: 0,
    totalQueueMillis: 0, totalCallbackMillis: 0, maxQueueMillis: 0,
    maxCallbackMillis: 0, samples: [], droppedSamples: 0,
  };
  let scheduler;
  Object.defineProperty(globalThis, '__gaiusMacrotaskScheduler', {
    configurable: true,
    get() { return scheduler; },
    set(value) {
      scheduler = value;
      if (!value?.pending || value.pending.__gaiusTraced) return;
      const pending = value.pending;
      const originalSet = pending.set;
      pending.__gaiusTraced = true;
      pending.set = function(id, callback) {
        const postedAt = performance.now();
        trace.posted++;
        trace.pendingHighWater = Math.max(trace.pendingHighWater, this.size + 1);
        return originalSet.call(this, id, function(...args) {
          const resumedAt = performance.now();
          const queueMillis = resumedAt - postedAt;
          trace.resumed++;
          trace.totalQueueMillis += queueMillis;
          trace.maxQueueMillis = Math.max(trace.maxQueueMillis, queueMillis);
          try { return callback.apply(this, args); }
          finally {
            const callbackMillis = performance.now() - resumedAt;
            trace.totalCallbackMillis += callbackMillis;
            trace.maxCallbackMillis = Math.max(trace.maxCallbackMillis, callbackMillis);
            if (queueMillis >= 20 || callbackMillis >= 20 || trace.resumed <= 16) {
              if (trace.samples.length < 512) trace.samples.push({
                id, postedAt, resumedAt, queueMillis, callbackMillis,
                pending: pending.size, slices: globalThis.__gaiusWorldgenStats?.slices ?? null,
              });
              else trace.droppedSamples++;
            }
          }
        });
      };
    },
  });
}
