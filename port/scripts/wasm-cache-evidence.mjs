// cachedModule marks serialization, not durable disk completion. Associate it with
// the preceding compiledModule on the same thread; shader cache events do not count.
export function clientCacheWrites(events) {
  const compiling = new Map();
  const writes = [];
  const serialization = events.filter(event => event.name === "v8.wasm.compiledModule"
    || event.name === "v8.wasm.cachedModule").sort((a, b) => a.ts - b.ts);
  for (const event of serialization) {
    const key = `${event.pid}:${event.tid}`;
    if (event.name === "v8.wasm.compiledModule") compiling.set(key, event.args?.url || "");
    if (event.name !== "v8.wasm.cachedModule") continue;
    const url = compiling.get(key);
    compiling.delete(key);
    if (!url || !/\/classes\.[0-9a-f]{16,64}\.wasm(?:\?|$)/.test(url)) continue;
    writes.push({url, ts: event.ts, producedCacheSize: event.args?.producedCacheSize || 0});
  }
  return writes;
}

export function cacheObservation(events, clockUs, settleMs) {
  const writes = clientCacheWrites(events);
  const last = writes.at(-1);
  const quietMs = last ? Math.max(0, (clockUs - last.ts) / 1000) : null;
  return {writes, quietMs, ready: !!last && quietMs >= settleMs};
}
