"use strict";
globalThis.runStorageProbe = configuration => new Promise((resolve, reject) => {
  const {profile, runtime, mode, world, backend} = configuration;
  const query = new URLSearchParams({profile: profile.id, runtime, mode, world, backend});
  const worker = new Worker(`/worker.js?${query}`);
  const channel = new MessageChannel();
  let result;
  let settled = false;
  const events = [];
  globalThis.storageProbeEvents = events;
  const timeout = setTimeout(() => finish(new Error(`storage probe timed out: ${JSON.stringify(events)}`)), 120000);
  function finish(error) {
    if (settled) return;
    settled = true;
    clearTimeout(timeout);
    channel.port1.close();
    worker.terminate();
    if (error) reject(error);
    else resolve({...result, cleanStop: true});
  }
  worker.onerror = event => finish(new Error(event.message));
  worker.onmessage = event => {
    const message = event.data;
    events.push({type: message.type, detail: String(message.detail || "").slice(0, 300)});
    if (events.length > 12) events.shift();
    if (["bootstrap-crash", "runtime-crash", "storage-flush-failed", "runtime-stop-failed", "probe-error"].includes(message.type)) {
      finish(new Error(`${message.type}: ${message.detail}`));
    } else if (message.type === "probe-complete") {
      result = message;
      worker.postMessage({type: "stop"});
    } else if (message.type === "stopped") {
      if (!result) finish(new Error("worker stopped without completing probe"));
      else finish();
    }
  };
  worker.postMessage({type: "start", sessionId: `storage-${runtime}-${mode}`, launchGeneration: "1",
    port: channel.port2, worldId: world, profileId: profile.id, worldVersion: profile.worldVersion,
    storageSchema: profile.storage.schema, storageDatabaseName: profile.storage.databaseName,
    storagePrefix: profile.storage.prefix, storageOpfsDirectory: profile.storage.opfsDirectory,
    serverScriptUrl: "/loader.js"}, [channel.port2]);
});
