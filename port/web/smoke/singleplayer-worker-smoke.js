"use strict";

const SERVER_PORT = 25565;
const PROFILE_ID = "00000000000040008000000000000002";
const SMOKE_TIMEOUT_MS = 240000;
const STOP_TIMEOUT_MS = 30000;
const STORE_NAME = "files";
const PLAY_PROTOCOLS = Object.freeze({
  774: Object.freeze({
    clientbound: Object.freeze({
      disconnect: 32,
      keepAlive: 43,
      levelChunkWithLight: 44,
      login: 48,
      ping: 59,
    }),
    serverbound: Object.freeze({
      chunkBatchReceived: 10,
      keepAlive: 27,
      playerLoaded: 43,
      pong: 44,
    }),
  }),
  776: Object.freeze({
    clientbound: Object.freeze({
      disconnect: 32,
      keepAlive: 44,
      levelChunkWithLight: 45,
      login: 49,
      ping: 61,
    }),
    serverbound: Object.freeze({
      chunkBatchReceived: 11,
      keepAlive: 28,
      playerLoaded: 44,
      pong: 45,
    }),
  }),
  777: Object.freeze({
    clientbound: Object.freeze({
      disconnect: 32,
      keepAlive: 45,
      levelChunkWithLight: 46,
      login: 50,
      ping: 62,
    }),
    serverbound: Object.freeze({
      chunkBatchReceived: 11,
      keepAlive: 28,
      playerLoaded: 44,
      pong: 45,
    }),
  }),
});
// CONFIGURATION ids per protocol. 26.3 (777) registers clientbound
// post_effects at id 10, so select_known_packs moves from 14 to 15; the other
// ids used here and every serverbound id are unchanged. All ids are derived
// from the profile's client jar, like apps/bridge/dist/protocol.js.
const CONFIGURATION_IDS_774_776 = Object.freeze({
  clientbound: Object.freeze({
    disconnect: 2,
    finish: 3,
    keepAlive: 4,
    ping: 5,
    selectKnownPacks: 14,
  }),
  serverbound: Object.freeze({
    clientInformation: 0,
    finish: 3,
    keepAlive: 4,
    pong: 5,
    selectKnownPacks: 7,
  }),
});
const CONFIGURATION_PROTOCOLS = Object.freeze({
  774: CONFIGURATION_IDS_774_776,
  776: CONFIGURATION_IDS_774_776,
  777: Object.freeze({
    clientbound: Object.freeze({
      disconnect: 2,
      finish: 3,
      keepAlive: 4,
      ping: 5,
      selectKnownPacks: 15,
    }),
    serverbound: Object.freeze({
      clientInformation: 0,
      finish: 3,
      keepAlive: 4,
      pong: 5,
      selectKnownPacks: 7,
    }),
  }),
});

const runButton = document.getElementById("run");
const statusNode = document.getElementById("status");
const logNode = document.getElementById("log");
const smokeState = globalThis.__gaiusSingleplayerWorkerSmoke = {
  state: "idle",
  versionProfile: null,
  protocolVersion: null,
  storage: null,
  events: [],
  compressionThreshold: null,
  loginFinished: false,
  configurationPackets: 0,
  playPackets: 0,
  playLoginPackets: 0,
  chunkPackets: 0,
  chunkBatchAckCount: 0,
  knownPackRequests: 0,
  loginProfileId: null,
  serverDistances: null,
  removedWorldFiles: 0,
  diagnosticSnapshots: [],
};

runButton.addEventListener("click", () => {
  void runSmoke();
});

if (new URLSearchParams(location.search).get("autorun") === "1") {
  setTimeout(() => void runSmoke(), 0);
}

async function runSmoke() {
  if (smokeState.state === "running") {
    return;
  }
  resetState();
  setState("running", "Starting official server Worker");
  runButton.disabled = true;

  let worker;
  let clientPort;
  let stopped;
  let distancesActive;
  let worldId;
  let storage;
  try {
    requireBrowserFeature("Worker", globalThis.Worker);
    requireBrowserFeature("MessageChannel", globalThis.MessageChannel);
    requireBrowserFeature("IndexedDB", globalThis.indexedDB);
    requireBrowserFeature("DecompressionStream", globalThis.DecompressionStream);

    const sessionId = randomSessionId();
    worldId = "gaius-smoke-" + Date.now().toString(36);
    smokeState.sessionId = sessionId;
    smokeState.worldId = worldId;

    const activeVersionProfile = await loadActiveVersionProfile();
    const activeProtocolVersion = Number(activeVersionProfile.protocolVersion);
    const activePlayProtocol = PLAY_PROTOCOLS[activeProtocolVersion];
    if (!activePlayProtocol) {
      throw new Error(
        "No browser smoke PLAY packet table exists for protocol " + activeProtocolVersion
      );
    }
    const activeConfigurationProtocol = CONFIGURATION_PROTOCOLS[activeProtocolVersion];
    if (!activeConfigurationProtocol) {
      throw new Error(
        "No browser smoke CONFIGURATION packet table exists for protocol " +
          activeProtocolVersion
      );
    }
    smokeState.versionProfile = activeVersionProfile.id;
    smokeState.protocolVersion = activeProtocolVersion;
    storage = storageConfigForProfile(activeVersionProfile);
    smokeState.storage = storage;

    const version = new URLSearchParams(location.search).get("v") || "worker-smoke-v1";
    const workerUrl = new URL("../dist/singleplayer-server-worker.js", location.href);
    workerUrl.searchParams.set("v", version);
    const channel = new MessageChannel();
    clientPort = channel.port1;
    const protocol = createProtocolClient(
      clientPort,
      sessionId,
      activeProtocolVersion,
      activePlayProtocol,
      activeConfigurationProtocol
    );
    stopped = deferred();
    distancesActive = deferred();
    const failed = deferred();
    const diagnosticResponses = [];
    const diagnosticWaiters = [];
    worker = new Worker(workerUrl, {name: "Gaius singleplayer smoke server"});

    worker.onmessage = (event) => {
      const message = event.data || {};
      record("worker", message.type || "message", message.detail || "");
      if (message.type === "diagnostic-snapshot") {
        const waiter = diagnosticWaiters.shift();
        if (waiter) waiter.resolve(message);
        else diagnosticResponses.push(message);
      }
      if (message.type === "server-created") {
        setTimeout(() => {
          worker.postMessage({
            type: "distances",
            renderDistance: 7,
            simulationDistance: 3,
          });
        }, 1000);
      } else if (message.type === "server-distances" && message.detail === "7/3") {
        smokeState.serverDistances = message.detail;
        distancesActive.resolve();
        protocol.startLogin();
      } else if (message.type === "stopped") {
        stopped.resolve();
      } else if (message.type === "bootstrap-crash" || message.type === "crash" ||
          message.type === "storage-write-error") {
        failed.reject(new Error(message.type + ": " + String(message.detail || "unknown error")));
      }
    };
    worker.onerror = (event) => {
      failed.reject(new Error(event.message || "Singleplayer Worker failed"));
    };

    worker.postMessage({
      type: "start",
      sessionId,
      launchGeneration: "1",
      worldId,
      newWorld: true,
      profileId: storage.profileId,
      worldVersion: storage.worldVersion,
      storageSchema: storage.storageSchema,
      storageDatabaseName: storage.storageDatabaseName,
      storagePrefix: storage.storagePrefix,
      storageOpfsDirectory: storage.storageOpfsDirectory,
      renderDistance: 8,
      simulationDistance: 5,
      port: channel.port2,
    }, [channel.port2]);

    await withTimeout(
      Promise.race([
        Promise.all([protocol.playReady, distancesActive.promise]),
        failed.promise,
      ]),
      SMOKE_TIMEOUT_MS,
      "server PLAY login/chunk data"
    );
    smokeState.compressionThreshold = protocol.compressionThreshold;
    smokeState.loginFinished = protocol.loginFinished;
    smokeState.configurationPackets = protocol.configurationPackets;
    smokeState.playPackets = protocol.playPackets;
    smokeState.playLoginPackets = protocol.playLoginPackets;
    smokeState.chunkPackets = protocol.chunkPackets;
    smokeState.chunkBatchAckCount = protocol.chunkBatchAckCount;
    smokeState.knownPackRequests = protocol.knownPackRequests;
    smokeState.loginProfileId = protocol.loginProfileId;
    // Exercise the real Worker control channel without enabling continuous
    // probes. Disabled snapshots intentionally contain no telemetry details.
    worker.postMessage({type: "diagnostic-snapshot"});
    const disabledSnapshot = await withTimeout(
      nextDiagnosticSnapshot(diagnosticResponses, diagnosticWaiters),
      5000,
      "disabled diagnostic snapshot"
    );
    requireCondition(disabledSnapshot.enabled === false,
      "Diagnostic snapshot leaked while slow probe was disabled");
    worker.postMessage({type: "diagnostic-config", gaiusSlowProbeTelemetry: true});
    worker.postMessage({type: "diagnostic-snapshot"});
    const enabledSnapshot = await withTimeout(
      nextDiagnosticSnapshot(diagnosticResponses, diagnosticWaiters),
      5000,
      "enabled diagnostic snapshot"
    );
    requireCondition(enabledSnapshot.enabled === true,
      "Diagnostic snapshot did not enable");
    requireDiagnosticSnapshot(enabledSnapshot);
    worker.postMessage({type: "diagnostic-config", gaiusSlowProbeTelemetry: false});
    worker.postMessage({type: "diagnostic-snapshot"});
    const reDisabledSnapshot = await withTimeout(
      nextDiagnosticSnapshot(diagnosticResponses, diagnosticWaiters),
      5000,
      "re-disabled diagnostic snapshot"
    );
    requireCondition(reDisabledSnapshot.enabled === false,
      "Diagnostic snapshot did not disable");
    smokeState.diagnosticSnapshots = [disabledSnapshot, enabledSnapshot, reDisabledSnapshot];
    setState("running", "PLAY and chunk data passed; stopping server cleanly");
    protocol.closeTransport();
    worker.postMessage({type: "stop"});
    await withTimeout(
      Promise.race([stopped.promise, failed.promise]),
      STOP_TIMEOUT_MS,
      "server shutdown"
    );
    clientPort.close();
    worker.terminate();
    worker = undefined;
    clientPort = undefined;

    smokeState.removedWorldFiles = await removeSmokeWorld(
      worldId,
      storage.storageDatabaseName,
    );
    smokeState.finishedAt = Date.now();
    setState("passed", "Gaius singleplayer Worker smoke passed");
    record("result", "passed", JSON.stringify({
      protocolVersion: activeProtocolVersion,
      compressionThreshold: smokeState.compressionThreshold,
      configurationPackets: smokeState.configurationPackets,
      playPackets: smokeState.playPackets,
      playLoginPackets: smokeState.playLoginPackets,
      chunkPackets: smokeState.chunkPackets,
      chunkBatchAckCount: smokeState.chunkBatchAckCount,
      knownPackRequests: smokeState.knownPackRequests,
      loginProfileId: smokeState.loginProfileId,
      serverDistances: smokeState.serverDistances,
      removedWorldFiles: smokeState.removedWorldFiles,
      diagnosticSnapshots: smokeState.diagnosticSnapshots.map((snapshot) => ({
        enabled: snapshot.enabled,
        keys: Object.keys(snapshot),
        serializedLength: JSON.stringify(snapshot).length,
      })),
    }));
  } catch (error) {
    smokeState.error = String(error && (error.stack || error.message) || error);
    setState("failed", "Singleplayer Worker smoke failed: " + smokeState.error);
    record("result", "failed", smokeState.error);
    if (worker) {
      try {
        worker.postMessage({type: "stop"});
        if (stopped) {
          await Promise.race([stopped.promise, delay(3000)]);
        }
      } catch (ignored) {
        // The worker may already have terminated after reporting a crash.
      }
      worker.terminate();
    }
    if (clientPort) {
      clientPort.close();
    }
    if (worldId && storage) {
      try {
        smokeState.removedWorldFiles = await removeSmokeWorld(
          worldId,
          storage.storageDatabaseName,
        );
      } catch (cleanupError) {
        record("cleanup", "failed", String(cleanupError));
      }
    }
  } finally {
    runButton.disabled = false;
  }
}

function nextDiagnosticSnapshot(responses, waiters) {
  if (responses.length > 0) return Promise.resolve(responses.shift());
  const pending = deferred();
  waiters.push(pending);
  return pending.promise;
}

function requireDiagnosticSnapshot(snapshot) {
  requireCondition(JSON.stringify(snapshot).length <= 4 * 4096 + 256,
    "Diagnostic snapshot exceeded bounded response length");
  for (const key of [
    "worldgenSchedulerMarker",
    "futurePumpTelemetry",
    "worldgenStats",
    "serverTickTelemetry",
  ]) {
    const value = snapshot[key];
    requireCondition(value && typeof value === "object" && !Array.isArray(value),
      "Diagnostic snapshot field is not a shallow object: " + key);
    for (const [field, nested] of Object.entries(value)) {
      requireCondition(nested === null || typeof nested !== "object",
        "Diagnostic snapshot included nested telemetry: " + key);
      if (typeof nested === "number") {
        requireCondition(Number.isFinite(nested),
          "Diagnostic snapshot included a non-finite value: " + key);
      }
      if (typeof nested === "string") {
        const maxLength = ["maxSliceContext", "maxTaskContext", "chunkHolderProbeMaxContext"].includes(field)
          ? 1024
          : 256;
        requireCondition(nested.length <= maxLength,
          "Diagnostic snapshot string exceeded bound: " + key);
      }
    }
  }
}

function createProtocolClient(
  port,
  sessionId,
  protocolVersion,
  playProtocol,
  configurationProtocol
) {
  const ready = deferred();
  const host = "client-" + sessionId + ".gaius-local";
  const {clientbound: clientboundPlay, serverbound: serverboundPlay} = playProtocol;
  const {
    clientbound: clientboundConfiguration,
    serverbound: serverboundConfiguration,
  } = configurationProtocol;
  let buffered = new Uint8Array(0);
  let packetWork = Promise.resolve();
  let remotePaused = false;
  let loginStarted = false;
  const pendingSends = [];
  const state = {
    compressionThreshold: undefined,
    phase: "login",
    loginFinished: false,
    configurationPackets: 0,
    configurationFinished: false,
    playPackets: 0,
    playLoginPackets: 0,
    chunkPackets: 0,
    knownPackRequests: 0,
    loginProfileId: undefined,
    playerLoadedSent: false,
    chunkBatchAckSent: false,
    chunkBatchAckCount: 0,
    chunkBatchAckTimer: undefined,
    lastAckedChunkPackets: 0,
    playReady: ready.promise,
    startLogin,
    closeTransport,
  };

  port.onmessage = (event) => {
    const message = event.data;
    if (isControlMessage(message)) {
      if (message.type === "flow") {
        remotePaused = Boolean(message.paused);
        if (!remotePaused) {
          flushSends();
        }
      } else if (message.type === "close" && state.chunkPackets === 0) {
        ready.reject(new Error("Local server transport closed before PLAY chunk data arrived"));
      }
      return;
    }
    if (!(message instanceof ArrayBuffer) && !ArrayBuffer.isView(message)) {
      ready.reject(new Error("Local server transport returned an unsupported message"));
      return;
    }
    const bytes = message instanceof ArrayBuffer
      ? new Uint8Array(message)
      : new Uint8Array(message.buffer, message.byteOffset, message.byteLength);
    buffered = concatenate(buffered, bytes);
    drainFrames();
  };
  port.onmessageerror = () => {
    ready.reject(new Error("Local server MessagePort could not decode a message"));
  };
  port.start();

  function startLogin() {
    if (loginStarted) {
      return;
    }
    loginStarted = true;
    record("protocol", "handshake", host + ":" + SERVER_PORT);
    const handshake = concatenateMany([
      encodeVarInt(protocolVersion),
      encodeString(host),
      new Uint8Array([(SERVER_PORT >>> 8) & 0xff, SERVER_PORT & 0xff]),
      encodeVarInt(2),
    ]);
    const uuid = hexBytes(PROFILE_ID);
    const hello = concatenate(encodeString("GaiusSmoke"), uuid);
    send(encodePacket(0, handshake));
    send(encodePacket(0, hello));
  }

  function closeTransport() {
    try {
      port.postMessage({type: "close"});
    } finally {
      port.close();
    }
  }

  function drainFrames() {
    try {
      while (true) {
        const outerLength = decodeVarInt(buffered, 0);
        if (outerLength === undefined) {
          return;
        }
        const frameStart = outerLength.bytesRead;
        const frameEnd = frameStart + outerLength.value;
        if (frameEnd > buffered.byteLength) {
          return;
        }
        const frame = buffered.slice(frameStart, frameEnd);
        buffered = buffered.slice(frameEnd);
        packetWork = packetWork.then(() => processFrame(frame));
        packetWork.catch((error) => ready.reject(error));
      }
    } catch (error) {
      ready.reject(error);
    }
  }

  async function processFrame(frame) {
    let packet = frame;
    if (state.compressionThreshold !== undefined) {
      const dataLength = decodeVarInt(frame, 0);
      if (dataLength === undefined) {
        throw new Error("Compressed Minecraft frame omitted its data length");
      }
      const payload = frame.subarray(dataLength.bytesRead);
      packet = dataLength.value === 0 ? payload : await inflateZlib(payload);
      if (dataLength.value !== 0 && packet.byteLength !== dataLength.value) {
        throw new Error("Minecraft compressed frame length did not match");
      }
    }
    const packetId = decodeVarInt(packet, 0);
    if (packetId === undefined) {
      throw new Error("Minecraft frame omitted its packet id");
    }
    const payload = packet.subarray(packetId.bytesRead);
    if (state.phase === "login" && packetId.value === 0) {
      throw new Error("Official server rejected the smoke login");
    }
    if (state.phase === "login" && packetId.value === 3) {
      const threshold = decodeVarInt(packet, packetId.bytesRead);
      if (threshold === undefined || threshold.value < 0) {
        throw new Error("Official server sent an invalid compression threshold");
      }
      state.compressionThreshold = threshold.value;
      record("protocol", "compression", String(threshold.value));
    } else if (state.phase === "login" && packetId.value === 2) {
      if (payload.byteLength < 16) {
        throw new Error("Official server login profile omitted its UUID");
      }
      state.loginProfileId = bytesToHex(payload.subarray(0, 16));
      if (state.loginProfileId !== PROFILE_ID) {
        throw new Error(
          "Integrated server changed the client profile UUID: " + state.loginProfileId
        );
      }
      state.loginFinished = true;
      state.phase = "configuration";
      record("protocol", "login-finished", "true");
      send(encodePacket(3, new Uint8Array(0), state.compressionThreshold));
      send(encodePacket(
        serverboundConfiguration.clientInformation,
        encodeClientInformation(),
        state.compressionThreshold
      ));
    } else if (state.phase === "configuration") {
      state.configurationPackets++;
      if (packetId.value === clientboundConfiguration.disconnect) {
        throw new Error("Official server disconnected during configuration");
      }
      if (packetId.value === clientboundConfiguration.selectKnownPacks) {
        state.knownPackRequests++;
        record("protocol", "known-packs", String(state.knownPackRequests));
        send(encodePacket(
          serverboundConfiguration.selectKnownPacks,
          encodeVarInt(0),
          state.compressionThreshold
        ));
      } else if (packetId.value === clientboundConfiguration.finish) {
        state.configurationFinished = true;
        state.phase = "play";
        record("protocol", "configuration-finished", String(state.configurationPackets));
        send(encodePacket(
          serverboundConfiguration.finish,
          new Uint8Array(0),
          state.compressionThreshold
        ));
      } else if (packetId.value === clientboundConfiguration.keepAlive) {
        send(encodePacket(
          serverboundConfiguration.keepAlive,
          payload,
          state.compressionThreshold
        ));
      } else if (packetId.value === clientboundConfiguration.ping) {
        send(encodePacket(serverboundConfiguration.pong, payload, state.compressionThreshold));
      }
    } else if (state.phase === "play") {
      state.playPackets++;
      if (packetId.value === clientboundPlay.disconnect) {
        throw new Error("Official server disconnected after entering PLAY");
      }
      if (packetId.value === clientboundPlay.keepAlive) {
        send(encodePacket(serverboundPlay.keepAlive, payload, state.compressionThreshold));
      } else if (packetId.value === clientboundPlay.ping) {
        send(encodePacket(serverboundPlay.pong, payload, state.compressionThreshold));
      } else if (packetId.value === clientboundPlay.login) {
        state.playLoginPackets++;
        record("protocol", "play-login", String(state.playLoginPackets));
        if (!state.playerLoadedSent) {
          state.playerLoadedSent = true;
          send(encodePacket(
            serverboundPlay.playerLoaded,
            new Uint8Array(0),
            state.compressionThreshold
          ));
        }
      } else if (packetId.value === clientboundPlay.levelChunkWithLight) {
        state.chunkPackets++;
        record("protocol", "chunk", String(state.chunkPackets));
        maybeScheduleChunkBatchAck();
      }
      if (state.playLoginPackets > 0 && state.chunkPackets > 0) {
        ready.resolve();
      }
    }
  }

  function maybeScheduleChunkBatchAck() {
    if (state.chunkBatchAckTimer !== undefined ||
        state.chunkPackets <= state.lastAckedChunkPackets) {
      return;
    }
    state.chunkBatchAckTimer = setTimeout(() => {
      state.chunkBatchAckTimer = undefined;
      if (state.chunkPackets <= state.lastAckedChunkPackets) {
        return;
      }
      state.lastAckedChunkPackets = state.chunkPackets;
      state.chunkBatchAckSent = true;
      state.chunkBatchAckCount++;
      record(
        "protocol",
        "chunk-batch-ack",
        state.chunkBatchAckCount + ":" + state.lastAckedChunkPackets
      );
      send(encodePacket(
        serverboundPlay.chunkBatchReceived,
        encodeFloat(10),
        state.compressionThreshold
      ));
    }, 200);
  }

  function send(bytes) {
    pendingSends.push(bytes);
    flushSends();
  }

  function flushSends() {
    while (!remotePaused && pendingSends.length > 0) {
      const bytes = pendingSends.shift();
      const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      port.postMessage(buffer, [buffer]);
    }
  }

  return state;
}

async function loadActiveVersionProfile() {
  const response = await fetch("../dist/classes.js.build.json", {cache: "no-store"});
  if (!response.ok) {
    throw new Error("Could not load the active client build identity: " + response.status);
  }
  const buildIdentity = await response.json();
  if (!buildIdentity || buildIdentity.kind !== "gaius-build-identity" ||
      buildIdentity.role !== "client") {
    throw new Error("The active client build identity is invalid");
  }
  const activeVersionProfile = buildIdentity.profile;
  if (!activeVersionProfile || typeof activeVersionProfile.id !== "string" ||
      !Number.isSafeInteger(Number(activeVersionProfile.protocolVersion))) {
    throw new Error("The active client version profile is invalid");
  }
  return activeVersionProfile;
}

function storageConfigForProfile(profile) {
  const profileId = String(profile?.id || "");
  const worldVersion = Number(profile?.worldVersion);
  const storage = profile?.storage || {};
  const result = {
    profileId,
    worldVersion,
    storageSchema: Number(storage.schema),
    storageDatabaseName: String(storage.databaseName || ""),
    storagePrefix: String(storage.prefix || ""),
    storageOpfsDirectory: String(storage.opfsDirectory || ""),
  };
  if (!result.profileId || !Number.isSafeInteger(result.worldVersion) ||
      result.worldVersion <= 0 || result.storageSchema !== 2 ||
      result.storageDatabaseName !== `gaius-fs-v2-${result.profileId}` ||
      result.storagePrefix !== `gaius.fs.v2:${result.profileId}:` ||
      result.storageOpfsDirectory !== `regions-v2-${result.profileId}`) {
    throw new Error(
      "The active client version profile has an invalid schema-2 storage namespace",
    );
  }
  return Object.freeze(result);
}

function encodePacket(id, payload, compressionThreshold) {
  const packet = concatenate(encodeVarInt(id), payload);
  let body = packet;
  if (compressionThreshold !== undefined) {
    if (packet.byteLength >= compressionThreshold) {
      throw new Error("Smoke client only sends small uncompressed post-login packets");
    }
    body = concatenate(encodeVarInt(0), packet);
  }
  return concatenate(encodeVarInt(body.byteLength), body);
}

function encodeString(value) {
  const bytes = new TextEncoder().encode(value);
  return concatenate(encodeVarInt(bytes.byteLength), bytes);
}

function encodeClientInformation() {
  return concatenateMany([
    encodeString("en_us"),
    new Uint8Array([6]),
    encodeVarInt(0),
    new Uint8Array([1, 0x7f]),
    encodeVarInt(1),
    new Uint8Array([0, 1]),
    encodeVarInt(0),
  ]);
}

function encodeFloat(value) {
  const result = new Uint8Array(4);
  new DataView(result.buffer).setFloat32(0, value, false);
  return result;
}

function encodeVarInt(value) {
  const bytes = [];
  let remaining = value >>> 0;
  do {
    let next = remaining & 0x7f;
    remaining >>>= 7;
    if (remaining !== 0) {
      next |= 0x80;
    }
    bytes.push(next);
  } while (remaining !== 0);
  return new Uint8Array(bytes);
}

function decodeVarInt(bytes, offset) {
  let value = 0;
  for (let index = 0; index < 5; index++) {
    const position = offset + index;
    if (position >= bytes.byteLength) {
      return undefined;
    }
    const next = bytes[position];
    value |= (next & 0x7f) << (index * 7);
    if ((next & 0x80) === 0) {
      return {value, bytesRead: index + 1};
    }
  }
  throw new Error("Minecraft VarInt exceeded five bytes");
}

async function inflateZlib(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

function concatenate(first, second) {
  const result = new Uint8Array(first.byteLength + second.byteLength);
  result.set(first, 0);
  result.set(second, first.byteLength);
  return result;
}

function concatenateMany(parts) {
  let result = new Uint8Array(0);
  for (const part of parts) {
    result = concatenate(result, part);
  }
  return result;
}

function hexBytes(value) {
  const result = new Uint8Array(value.length / 2);
  for (let index = 0; index < result.length; index++) {
    result[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
  }
  return result;
}

function bytesToHex(bytes) {
  let result = "";
  for (let index = 0; index < bytes.byteLength; index++) {
    result += bytes[index].toString(16).padStart(2, "0");
  }
  return result;
}

function randomSessionId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let value = "";
  for (const byte of bytes) {
    value += byte.toString(16).padStart(2, "0");
  }
  return value;
}

async function removeSmokeWorld(worldId, storageDatabaseName) {
  const database = await openDatabase(storageDatabaseName);
  const prefix = "/gaius/saves/" + worldId;
  let removed = 0;
  await new Promise((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, "readwrite");
    const request = transaction.objectStore(STORE_NAME).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        return;
      }
      const path = cursor.value && cursor.value.path;
      if (typeof path === "string" && (path === prefix || path.startsWith(prefix + "/"))) {
        cursor.delete();
        removed++;
      }
      cursor.continue();
    };
    request.onerror = () => reject(request.error || new Error("IndexedDB cleanup cursor failed"));
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error || new Error("IndexedDB cleanup failed"));
    transaction.onabort = () => reject(transaction.error || new Error("IndexedDB cleanup aborted"));
  });
  database.close();
  return removed;
}

function openDatabase(storageDatabaseName) {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(storageDatabaseName, 2);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(STORE_NAME)) {
        database.createObjectStore(STORE_NAME, {keyPath: "path"});
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("IndexedDB open failed"));
    request.onblocked = () => reject(new Error("IndexedDB open blocked"));
  });
}

function isControlMessage(message) {
  return message && typeof message === "object" &&
    !(message instanceof ArrayBuffer) && !ArrayBuffer.isView(message);
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return {promise, resolve, reject};
}

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function withTimeout(promise, timeoutMs, label) {
  return Promise.race([
    promise,
    delay(timeoutMs).then(() => {
      throw new Error("Timed out waiting for " + label + " after " + timeoutMs + " ms");
    }),
  ]);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function requireBrowserFeature(name, value) {
  if (value === undefined || value === null) {
    throw new Error(name + " is unavailable in this browser");
  }
}

function resetState() {
  smokeState.state = "idle";
  smokeState.startedAt = Date.now();
  smokeState.finishedAt = undefined;
  smokeState.error = undefined;
  smokeState.events.length = 0;
  smokeState.versionProfile = null;
  smokeState.protocolVersion = null;
  smokeState.storage = null;
  smokeState.compressionThreshold = null;
  smokeState.loginFinished = false;
  smokeState.configurationPackets = 0;
  smokeState.playPackets = 0;
  smokeState.playLoginPackets = 0;
  smokeState.chunkPackets = 0;
  smokeState.chunkBatchAckCount = 0;
  smokeState.knownPackRequests = 0;
  smokeState.loginProfileId = null;
  smokeState.serverDistances = null;
  smokeState.removedWorldFiles = 0;
  logNode.textContent = "";
}

function setState(state, message) {
  smokeState.state = state;
  document.body.dataset.state = state;
  statusNode.textContent = message;
}

function record(source, type, detail) {
  const entry = {source, type, detail: String(detail || ""), at: Date.now()};
  smokeState.events.push(entry);
  if (smokeState.events.length > 500) {
    smokeState.events.splice(0, smokeState.events.length - 500);
  }
  const elapsed = ((entry.at - smokeState.startedAt) / 1000).toFixed(1);
  logNode.textContent += "[" + elapsed + "s] " + source + ":" + type +
    (entry.detail ? " " + entry.detail : "") + "\n";
  logNode.scrollTop = logNode.scrollHeight;
}
