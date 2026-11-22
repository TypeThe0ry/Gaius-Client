package io.netty.channel.browser;

import org.teavm.jso.JSBody;

final class BrowserRelayRaceHelpers {
    private BrowserRelayRaceHelpers() {}

    @JSBody(script = """
            if (!globalThis.__gaiusRelayRaceHelpers) {
              globalThis.__gaiusRelayRaceHelpers = {
                sendConnect: function(pending, relay, bridgeToken, tunnelRole, skinDescriptor) {
                  const owner = pending.owner;
                  if (owner.id === undefined || owner.closed || owner.connected || owner.preconnectedRelay !== pending || pending.urlConnect || pending.controlSent || pending.socket.__gaiusRelayConnectSent) return;
                  if (!tunnelRole && owner.relayProtocolDeclaration === undefined) owner.relayProtocolDeclaration = globalThis.__gaiusRelayProtocolDeclaration || null;
                  const control = {type:'connect', host:owner.host, port:owner.port};
                  if (!tunnelRole && owner.relayProtocolDeclaration) control.protocolTable = owner.relayProtocolDeclaration;
                  if (tunnelRole && skinDescriptor) control.skinDescriptor = skinDescriptor;
                  const token = bridgeToken(relay);
                  if (token !== undefined) control.token = token;
                  try {
                    pending.socket.send(JSON.stringify(control));
                    if (pending.trace) pending.trace.connectSentAt = this.now();
                    pending.protocolTable = control.protocolTable;
                    pending.controlSent = true;
                    pending.socket.__gaiusRelayConnectSent = true;
                  } catch (ignored) {}
                },
                // Keep relay accounting in this JSBody to stay below javac's UTF-8 constant limit.
                relayNodeStats: function(state, acquireTargetRelayLease) {
                  function relayNodeRecord(candidate) {
                    if (!candidate || candidate.direct) return null;
                    const nodes = state.stats.relayNodes;
                    let record = nodes[candidate.url];
                    if (!record) {
                      record = {
                        name: candidate.name,
                        priority: candidate.priority,
                        attempts: 0,
                        successes: 0,
                        failures: 0,
                        lastAttemptAt: 0,
                        lastSuccessAt: 0,
                        lastFailureAt: 0,
                        lastError: null,
                        lastPreflightAt: 0,
                        lastPreflightError: null,
                        availableConnections: null,
                        targetConnectTimeoutMs: null,
                        targetAffinityMs: null,
                        targetActiveConnections: 0,
                        targetRecentlyReachable: false,
                        targetAttestation: false
                      };
                      nodes[candidate.url] = record;
                    }
                    return record;
                  }
                  function recordRelayNodeAttempt(candidate) {
                    const record = relayNodeRecord(candidate);
                    if (!record) return;
                    record.attempts++;
                    record.lastAttemptAt = Date.now();
                  }
                  function recordRelayNodeSuccess(entry, candidate) {
                    const record = relayNodeRecord(candidate);
                    if (!record) return;
                    record.successes++;
                    record.lastSuccessAt = Date.now();
                    record.lastError = null;
                    state.stats.relayNodeSuccesses++;
                    acquireTargetRelayLease(entry, candidate);
                  }
                  function recordRelayNodeFailure(candidate, message) {
                    const record = relayNodeRecord(candidate);
                    if (!record) return;
                    record.failures++;
                    record.lastFailureAt = Date.now();
                    record.lastError = String(message || 'RelayNode connection failed').slice(0, 240);
                    state.stats.relayNodeFailures++;
                  }
                  return {
                    relayNodeRecord: relayNodeRecord,
                    recordRelayNodeAttempt: recordRelayNodeAttempt,
                    recordRelayNodeSuccess: recordRelayNodeSuccess,
                    recordRelayNodeFailure: recordRelayNodeFailure
                  };
                },
                markAcceptedLinkRelayFailed: function(candidate) {
                  const raw = String(globalThis.__gaiusLinkBridgeUrl || '').trim();
                  if (!raw || !candidate) return;
                  try {
                    const accepted = new URL(raw, location.href);
                    if (accepted.protocol === 'http:') accepted.protocol = 'ws:';
                    if (accepted.protocol === 'https:') accepted.protocol = 'wss:';
                    if (accepted.pathname === '' || accepted.pathname === '/' ||
                        accepted.pathname === '/health' || accepted.pathname === '/relay-node/v1') {
                      accepted.pathname = '/tunnel';
                    }
                    accepted.hash = '';
                    accepted.search = '';
                    if (candidate.url === accepted.href) globalThis.__gaiusLinkBridgeFailed = true;
                  } catch (ignored) {}
                },
                externalExplicitRelayUrl: function(url) {
                  const params = new URLSearchParams(location.search || '');
                  const explicitRelayLink = params.getAll('bridge').concat(
                    params.getAll('relay'), params.getAll('relayRegistry')
                  ).some(function(value) { return String(value || '').trim() && value !== '0'; });
                  if (!explicitRelayLink) return false;
                  try {
                    const parsed = new URL(url, location.href);
                    const host = String(parsed.hostname || '').toLowerCase();
                    return parsed.origin !== location.origin && host !== 'localhost' &&
                      !host.endsWith('.localhost') &&
                      !/^127[.][0-9]+[.][0-9]+[.][0-9]+$/.test(host) && host !== '::1';
                  } catch (ignored) {
                    return true;
                  }
                },
                receive: function(pending, event, openRemoteCandidate, validateRelay, rejectPredial, extendUrlDeadline) {
                  const owner = pending.owner;
                  if (owner.id === undefined || owner.closed || owner.connected || pending.failed ||
                      owner.preconnectedRelay !== pending || event.target !== pending.socket ||
                      pending.socket.readyState !== WebSocket.OPEN || (!pending.controlSent && !pending.urlConnect)) return;
                  if (typeof event.data !== 'string') {
                    if (pending.targetWarm) rejectPredial(pending, true);
                    return;
                  }
                  try {
                    const message = JSON.parse(event.data);
                    if (message && message.type === 'error' && (pending.urlConnect || pending.targetWarm)) {
                      rejectPredial(pending, true);
                      return;
                    }
                    if (message && message.type === 'connecting') {
                      if (pending.urlConnect || pending.targetWarm || pending.trace) {
                        const matches = extendUrlDeadline(message);
                        if (!matches && pending.targetWarm) rejectPredial(pending, true);
                        if (matches && pending.trace && pending.trace.connectAckAt === undefined) {
                          pending.trace.connectAckAt = this.now();
                        }
                      }
                      return;
                    }
                    if (!message || message.type !== 'connected') {
                      if (pending.targetWarm) rejectPredial(pending, true);
                      return;
                    }
                    if (!validateRelay(owner, message, pending.protocolTable, pending.targetWarm)) {
                      // Compatibility fallback stays on the relay leg. A bad ACK
                      // never elects a winner or changes the plugin measurement.
                      rejectPredial(pending, pending.urlConnect || pending.targetWarm);
                      return;
                    }
                    if (!pending.earlyMessage && pending.trace) {
                      pending.trace.targetReadyAt = this.now();
                      if (pending.trace.connectAckAt === undefined) pending.trace.connectAckAt = pending.trace.targetReadyAt;
                    }
                    pending.earlyMessage = event;
                    const index = owner.candidates.findIndex(function(candidate) { return !candidate.direct && candidate.url === pending.candidate.url; });
                    if (index < 0 || !owner.currentCandidate || !owner.currentCandidate.direct || !owner.ws ||
                        owner.ws === pending.socket) return;
                    const loser = owner.ws;
                    this.recordConnectPhase(owner, 'direct-cancelled', 'not-measured');
                    owner.webSocketGeneration++;
                    owner.candidateIndex = index;
                    owner.relaySelectionReady = true;
                    owner.ws = null;
                    if (loser && loser !== pending.socket) { try { loser.close(1000, 'Relay won connection race'); } catch (ignored) {} }
                    openRemoteCandidate(owner);
                  } catch (ignored) { if (pending.targetWarm) rejectPredial(pending, true); }
                },
                savedRelay: function(state, normalizeRelayUrl, persistentRelayCandidate) {
                  const key = 'gaius.lastSuccessfulRelay';
                  const now = Date.now();
                  const ttl = 24 * 60 * 60 * 1000;
                  const age = now - state.lastSuccessfulRelayAt;
                  if (state.lastSuccessfulRelayCandidate && age >= 0 && age < ttl) {
                    return state.lastSuccessfulRelayCandidate;
                  }
                  state.lastSuccessfulRelayUrl = null;
                  state.lastSuccessfulRelayCandidate = null;
                  try {
                    const raw = localStorage.getItem(key);
                    if (!raw) return null;
                    const value = JSON.parse(raw);
                    const savedAge = value && Number.isFinite(value.savedAt) ? now - value.savedAt : -1;
                    const url = savedAge >= 0 && savedAge < ttl ? normalizeRelayUrl(value.url) : null;
                    const saved = url && persistentRelayCandidate(value.candidate, url);
                    if (saved) {
                      state.lastSuccessfulRelayUrl = url;
                      state.lastSuccessfulRelayCandidate = saved;
                      state.lastSuccessfulRelayAt = value.savedAt;
                      return saved;
                    }
                    localStorage.removeItem(key);
                  } catch (ignored) {}
                  return null;
                },
                onlyLanAndImplicitRelay: function(candidates, privateRelayUrl, defaultUrl) {
                  return candidates.some(function(candidate) {
                    return candidate.use === 'lan';
                  }) && !candidates.some(function(candidate) {
                    return candidate.use !== 'lan' && !privateRelayUrl(candidate.url) &&
                      candidate.url !== defaultUrl;
                  });
                },
                preferredRelay: function(entry, state, normalizeRelayUrl, persistentRelayCandidate,
                    bridgeUrls, privateRelayUrl, defaultUrl) {
                  const saved = this.savedRelay(
                    state, normalizeRelayUrl, persistentRelayCandidate);
                  const candidates = bridgeUrls(saved ? [saved] : [], entry);
                  const defaultCandidate = normalizeRelayUrl(defaultUrl);
                  if (this.onlyLanAndImplicitRelay(candidates, privateRelayUrl, defaultCandidate)) return null;
                  const publicCandidates = candidates.filter(function(candidate) {
                    return candidate.use !== 'lan' && !privateRelayUrl(candidate.url);
                  });
                  const preferred = state.lastSuccessfulRelayUrl || defaultCandidate;
                  const params = new URLSearchParams(location.search || '');
                  const namedExplicit = params.getAll('bridge').concat(params.getAll('relay'))
                    .some(function(value) { return String(value || '').trim() && value !== '0'; });
                  const builtIn = publicCandidates.find(function(candidate) {
                    return String(candidate.id || '').startsWith('gaius-');
                  });
                  const hasMemoryPreference = !!(saved || state.lastSuccessfulRelayUrl);
                  return ((hasMemoryPreference || namedExplicit) &&
                    publicCandidates.find(function(candidate) { return candidate.url === preferred; })) ||
                    (!hasMemoryPreference && !namedExplicit && builtIn) ||
                    publicCandidates[0] || null;
                },
                publicIpv4: function(a, b, c) {
                  return a !== 0 && a !== 10 && a !== 127 && a < 224 &&
                    !(a === 100 && b >= 64 && b <= 127) && !(a === 169 && b === 254) &&
                    !(a === 172 && b >= 16 && b <= 31) && !(a === 192 && b === 168) &&
                    !(a === 192 && b === 0 && c === 0) && !(a === 198 && (b === 18 || b === 19)) &&
                    !(a === 198 && b === 51 && c === 100) && !(a === 203 && b === 0 && c === 113);
                },
                publicHost: function(host) {
                  const value = String(host || '').trim().toLowerCase().replace(/^\\[|\\]$/g, '').replace(/[.]+$/, '');
                  if (!value || /[\\s/@?#%\\\\]/.test(value)) return false;
                  try {
                    const name = new URL('http://' + (value.includes(':') ? '[' + value + ']' : value)).hostname;
                    // Only the global IPv6 unicast range is eligible. This also excludes mapped IPv4.
                    if (name.startsWith('[') && (name.indexOf(':') !== 5 ||
                        (name.charAt(1) !== '2' && name.charAt(1) !== '3'))) return false;
                    const policy = globalThis.__gaiusRelayAddressPolicy;
                    if (policy && typeof policy.isPublicRelayHost === 'function') return policy.isPublicRelayHost(name);
                    if (name.startsWith('[')) {
                      if (name.startsWith('[2002:')) {
                        const halves = name.slice(1, -1).split('::');
                        const words = halves[0].split(':');
                        const high = parseInt(words[1] || '0', 16);
                        const low = parseInt(words[2] || '0', 16);
                        return this.publicIpv4(high >>> 8, high & 255, low >>> 8);
                      }
                      return !name.startsWith('[2001:db8:') && !name.startsWith('[2001:0:') && !name.startsWith('[2001::');
                    }
                    if (/^[0-9.]+$/.test(name)) {
                      const [a, b, c] = name.split('.').map(Number);
                      return this.publicIpv4(a, b, c);
                    }
                    return name.includes('.') && !/\\.(?:localhost|local|lan|internal|gaius-local|home\\.arpa)$/.test(name);
                  } catch (ignored) { return false; }
                },
                publicAddress: function(address) {
                  const value = String(address || '').trim();
                  if (!value) return false;
                  if (!value.startsWith('[') && value.split(':').length > 2) return this.publicHost(value);
                  if (/[\\s/@?#%\\\\]/.test(value)) return false;
                  try { return this.publicHost(new URL('http://' + value).hostname); }
                  catch (ignored) { return false; }
                },
                prioritizePredial: function(entry) {
                  const pending = entry.preconnectedRelay;
                  if (!pending || pending.failed || pending.socket.readyState > WebSocket.OPEN) return false;
                  const start = entry.candidateIndex;
                  const index = entry.candidates.findIndex(function(candidate, index) {
                    return index >= start && !candidate.direct && candidate.url === pending.candidate.url;
                  });
                  if (index < 0) return false;
                  if (index !== start) entry.candidates.splice(start, 0, entry.candidates.splice(index, 1)[0]);
                  return true;
                },
                warmupUsable: function(entry) {
                  const pending = entry && entry.preconnectedRelay;
                  return !!(pending && !pending.failed && pending.socket.readyState <= WebSocket.OPEN &&
                    Date.now() < entry.expiresAt);
                },
                installBridgeDefaults: function(state) {
                  // Harness defaults; TeaVM installs these.
                  state.scheduleEventLoopGapProbe = function() {};
                  state.stopEventLoopGapProbeIfIdle = function() {};
                  state.startHighWatermark = function() {};
                  state.finishHighWatermark = function() {};
                  state.discardInbound = function(entry) {
                  if (!entry) return;
                  state.finishHighWatermark(entry);
                  state.stats.inboundQueuedBytes = Math.max(
                    0,
                    state.stats.inboundQueuedBytes - entry.inboundBytes - entry.pendingInboundBytes
                  );
                  entry.inbound = [];
                  entry.inboundHead = 0;
                  entry.inboundBytes = 0;
                  entry.pendingInbound = [];
                  entry.pendingInboundHead = 0;
                  entry.pendingInboundBytes = 0;
                  entry.flowPaused = false;
                  };
                  const fallbackInboundQueueFrames = 4096;
                  function fallbackInboundFrameCount(entry) {
                  return Math.max(0, entry.inbound.length - entry.inboundHead);
                  }
                  state.deliverInbound = function(entry, buffer) {
                  if (!entry || entry.closed) return;
                  const bytes = buffer instanceof ArrayBuffer
                    ? new Uint8Array(buffer)
                    : new Uint8Array(buffer.buffer, buffer.byteOffset || 0, buffer.byteLength || 0);
                  if (fallbackInboundFrameCount(entry) >= fallbackInboundQueueFrames) {
                    state.failInbound(entry, 'Browser transport inbound queue exceeded frame limit');
                    return;
                  }
                  entry.inbound.push(bytes);
                  entry.inboundBytes += bytes.byteLength;
                  state.stats.inboundQueuedBytes += bytes.byteLength;
                  state.stats.receivedFrames++;
                  state.stats.receivedBytes += bytes.byteLength;
                  };
                  state.pollInboundScheduled = function(id, requestFlush) {
                  const entry = state.channels.get(id|0);
                  if (!entry || entry.inboundHead >= entry.inbound.length) return null;
                  requestFlush(entry);
                  const chunk = entry.inbound[entry.inboundHead++];
                  entry.inboundBytes = Math.max(0, entry.inboundBytes - chunk.byteLength);
                  state.stats.inboundQueuedBytes = Math.max(
                    0,
                    state.stats.inboundQueuedBytes - chunk.byteLength
                  );
                  if (entry.inboundHead >= entry.inbound.length) {
                    entry.inbound = [];
                    entry.inboundHead = 0;
                  }
                  return new Int8Array(chunk.buffer, chunk.byteOffset || 0, chunk.byteLength);
                  };
                  state.recordPumpTelemetry = function() {};
                  state.recordDecodedSliceScheduled = function() {};
                  state.finishDecodedSliceScheduled = function() {};
                  state.recordDecodedPacketQueueScheduled = function() {};
                  state.recordInlineDecodedPacketScheduled = function() {};
                  state.hasPendingInboundScheduled = function(id) {
                  const entry = state.channels.get(id|0);
                  return !!entry && entry.inboundHead < entry.inbound.length;
                  };
                  state.hasPumpableInboundScheduled = function(id) {
                  const entry = state.channels.get(id|0);
                  return !!entry && !entry.disposed && !state.exactPacketQueuePaused &&
                    entry.inboundHead < entry.inbound.length;
                  };
                },
                now: function() {
                  return typeof performance === 'undefined' ? Date.now() : performance.now();
                },
                configureTargetWarmup: function(state, api) {
                  const race = this;
                  const capability = 'target-prewarm';
                  const ttl = 15000;
                  let visible = false;
                  let selected = null;
                  let active = null;
                  let cache = null;
                  const tracing = new URLSearchParams(location.search || '').get('rd32trace') === '1';
                  state.relaySegmentTracing = tracing;
                  if (tracing) state.stats.relaySegments = [];

                  function release(slot) {
                    if (active !== slot) {
                      if (state.joinWarmupEntry === slot.entry) race.expireWarmup(slot.entry);
                      return;
                    }
                    active = null;
                    clearTimeout(slot.timer);
                    if (slot.controller) slot.controller.abort();
                    if (slot.pending) {
                      try { slot.pending.socket.close(); } catch (ignored) {}
                    }
                  }
                  function dial(slot, relay) {
                    if (active !== slot) return;
                    clearTimeout(slot.timer);
                    if (state.serverListWarmupEntry) race.expireWarmup(state.serverListWarmupEntry);
                    let socket;
                    try { socket = new WebSocket(relay.url); }
                    catch (ignored) { release(slot); return; }
                    const entry = slot.entry;
                    const started = Date.now();
                    const pending = {owner:entry, candidate:relay, socket:socket, targetWarm:true,
                      urlConnect:false, controlSent:false, startedAt:started, openMillis:0};
                    slot.pending = entry.preconnectedRelay = pending;
                    const dialTimeout = 4000;
                    entry.expiresAt = started + dialTimeout;
                    slot.timer = setTimeout(function() { release(slot); }, dialTimeout);
                    socket.onclose = socket.onerror = function() { release(slot); };
                    socket.onopen = function() {
                      if (active !== slot) return;
                      if (!race.warmupUsable(entry)) { release(slot); return; }
                      pending.openMillis = Date.now() - started;
                      if (tracing) pending.wsOpenAt = race.now();
                      const control = {type:'connect', host:entry.host, port:entry.port, prewarm:true};
                      const table = globalThis.__gaiusRelayProtocolDeclaration;
                      if (table) control.protocolTable = table;
                      pending.protocolTable = control.protocolTable;
                      try {
                        entry.expiresAt = Date.now() + ttl;
                        socket.send(JSON.stringify(control));
                        pending.controlSent = true;
                        socket.__gaiusRelayConnectSent = true;
                        if (tracing) pending.connectSentAt = race.now();
                        clearTimeout(slot.timer);
                        slot.timer = setTimeout(function() { release(slot); }, ttl);
                      } catch (ignored) { release(slot); }
                    };
                    socket.onmessage = function(event) {
                      if (active !== slot && state.joinWarmupEntry !== entry) return;
                      try {
                        const msg = typeof event.data === 'string' ? JSON.parse(event.data) : null;
                        if (!msg || pending.earlyMessage || !pending.controlSent || Date.now() >= entry.expiresAt) {
                          release(slot);
                          return;
                        }
                        if (msg.type === 'connecting' && api.relayTargetMatches(entry, msg)) {
                          if (tracing && pending.connectAckAt === undefined) pending.connectAckAt = race.now();
                          return;
                        }
                        if (!api.validateRelay(entry, msg, pending.protocolTable, true)) {
                          release(slot);
                          return;
                        }
                        pending.earlyMessage = event;
                        if (tracing) {
                          pending.targetReadyAt = race.now();
                          if (pending.connectAckAt === undefined) pending.connectAckAt = pending.targetReadyAt;
                        }
                      } catch (ignored) { release(slot); }
                    };
                  }
                  function prepare(slot) {
                    const entry = slot.entry;
                    const relay = api.preferredRelay(entry);
                    if (!api.canPredialRelay(entry, relay) || api.bridgeToken(relay) !== undefined) {
                      release(slot);
                      return;
                    }
                    const endpoint = new URL(relay.url, location.href);
                    if (endpoint.pathname !== '/tunnel' || endpoint.search || endpoint.hash ||
                        endpoint.username || endpoint.password) { release(slot); return; }
                    const key = endpoint.href;
                    const known = cache && cache.get(key);
                    if (known && Date.now() < known.until) {
                      if (known.supported) dial(slot, relay);
                      else release(slot);
                      return;
                    }
                    endpoint.protocol = endpoint.protocol === 'wss:' ? 'https:' : 'http:';
                    if (typeof fetch !== 'function' || typeof AbortController !== 'function') { release(slot); return; }
                    const controller = new AbortController();
                    slot.controller = controller;
                    const deadline = Date.now() + 150;
                    function result(supported) {
                      if (active !== slot || slot.controller !== controller) return;
                      const expired = Date.now() >= deadline;
                      slot.controller = null;
                      clearTimeout(slot.timer);
                      const usable = !expired && supported;
                      if (expired) controller.abort();
                      if (!cache) cache = new Map();
                      if (cache.size >= 16 && !cache.has(key)) cache.delete(cache.keys().next().value);
                      cache.set(key, {supported:usable, until:Date.now() + (usable ? 300000 : 30000)});
                      if (usable) dial(slot, relay);
                      else release(slot);
                    }
                    slot.timer = setTimeout(function() { controller.abort(); result(false); }, 150);
                    Promise.resolve().then(function() {
                      if (active !== slot || Date.now() >= deadline) return null;
                      return fetch(endpoint.origin + '/health', {cache:'no-store', credentials:'omit',
                        redirect:'error', signal:controller.signal});
                    }).then(function(response) {
                      if (!response || !response.ok || Date.now() >= deadline) return null;
                      return response.json();
                    }).then(function(value) {
                      result(!!(value && Date.now() < deadline && value.requiresToken !== true &&
                        Array.isArray(value.capabilities) && value.capabilities.includes(capability)));
                    }).catch(function() { result(false); });
                  }

                  state.serverListTarget = function(host, port) {
                    if (!visible) return;
                    const targetKey = host ? api.normalizedTargetKey(host, port) : null;
                    if (selected === targetKey) return;
                    selected = targetKey;
                    if (active) release(active);
                    if (!host || !Number.isInteger(port) || port < 1 || port > 65535 ||
                        !api.canRaceRelay({host:host})) return;
                    const slot = {entry:{host:host, port:port, targetKey:targetKey, closed:false, connected:false}};
                    active = slot;
                    slot.timer = setTimeout(function() {
                      try { prepare(slot); } catch (ignored) { release(slot); }
                    }, 120);
                  };
                  this.setTargetWarmupVisible = function(value) {
                    if (!value) state.serverListTarget(null, 0);
                    visible = value;
                  };
                  this.takeTargetWarmup = function(key) {
                    const slot = active;
                    if (!slot) return null;
                    if (slot.entry.targetKey !== key || !race.warmupUsable(slot.entry) || !slot.pending.controlSent) {
                      release(slot);
                      return null;
                    }
                    active = null;
                    clearTimeout(slot.timer);
                    return slot.entry;
                  };
                },
                reservedTargetPredial: function(state, entry) {
                  const reserved = state.joinWarmupEntry;
                  const pending = reserved && reserved.preconnectedRelay;
                  return entry.warmupAttempt && pending && pending.targetWarm &&
                    reserved.warmupAttempt === entry.warmupAttempt && reserved.targetKey === entry.targetKey &&
                    this.warmupUsable(reserved) ? pending : null;
                },
                recordRelayOpenIntent: function(state, entry) {
                  entry.relayOpenAt = this.now();
                  const intent = state.relayJoinIntent;
                  if (intent && intent.id === entry.warmupAttempt) {
                    entry.relayJoinIntent = intent;
                    state.relayJoinIntent = null;
                  }
                },
                segment: function(state, entry, candidate, mode, pending) {
                  if (!state.relaySegmentTracing) return null;
                  const intent = entry.relayJoinIntent;
                  const trace = {id:entry.id, target:entry.targetKey, relay:candidate.url, mode:mode,
                    joinAt:intent ? intent.at : entry.relayOpenAt, joinSource:intent ? 'server-list' : 'netty-open',
                    predialOpenBeforeJoin:!!(intent && intent.open), predialReused:!!(pending && pending.listReused),
                    targetPrewarmReused:mode === 'prewarm', predial:!!pending};
                  if (pending) {
                    trace.wsOpenAt = pending.wsOpenAt;
                    trace.connectSentAt = pending.connectSentAt;
                    trace.connectAckAt = pending.connectAckAt;
                    trace.targetReadyAt = pending.targetReadyAt;
                  }
                  const records = state.stats.relaySegments;
                  records.push(trace);
                  if (records.length > 128) records.shift();
                  return trace;
                },
                configureWarmup: function(state, api) {
                  const race = this;
                  const listWarmupMs = 90000;
                  const reservationMs = 30000;
                  function enabled(name) {
                    return new URLSearchParams(location.search || '').get(name) !== '0';
                  }
                  function expire(entry) {
                    if (state.serverListWarmupEntry === entry) state.serverListWarmupEntry = null;
                    if (state.joinWarmupEntry === entry) state.joinWarmupEntry = null;
                    api.clearRelayPreparation(entry);
                  }
                  this.expireWarmup = expire;
                  function armExpiry(entry, deadline) {
                    const pending = entry.preconnectedRelay;
                    if (pending.timeout) clearTimeout(pending.timeout);
                    entry.expiresAt = deadline;
                    pending.timeout = setTimeout(function() { expire(entry); }, Math.max(0, deadline - Date.now()));
                  }
                  this.armListWarmupExpiry = function(entry) {
                    if (state.serverListWarmupEntry === entry) armExpiry(entry, Date.now() + listWarmupMs);
                  };
                  this.recordConnectPhase = api.recordConnectPhase;
                  state.cancelServerListWarmup = function(attempt) {
                    const reserved = state.joinWarmupEntry;
                    if (attempt && reserved && reserved.warmupAttempt === attempt) expire(reserved);
                    if (attempt && state.relayJoinIntent && state.relayJoinIntent.id === attempt) {
                      state.relayJoinIntent = null;
                    }
                  };
                  state.reserveServerListWarmup = function(host, port, attempt) {
                    if (!attempt) return;
                    const key = api.normalizedTargetKey(host, port);
                    const eligible = api.canRaceRelay({host:host});
                    const target = eligible ? race.takeTargetWarmup(key) : null;
                    const entry = target || state.serverListWarmupEntry;
                    if (state.relaySegmentTracing) state.relayJoinIntent = {id:attempt, at:race.now(),
                      open:!!(race.warmupUsable(entry) && entry.preconnectedRelay.socket.readyState === WebSocket.OPEN)};
                    if (!eligible) return;
                    if (!race.warmupUsable(entry)) {
                      if (entry) expire(entry);
                      return;
                    }
                    if (state.joinWarmupEntry) expire(state.joinWarmupEntry);
                    entry.targetKey = key;
                    entry.warmupAttempt = attempt;
                    state.joinWarmupEntry = entry;
                    if (!target) state.serverListWarmupEntry = null;
                    // Reservation can shorten the dial deadline or OPEN freshness, never extend it.
                    armExpiry(entry, Math.min(entry.expiresAt, Date.now() + reservationMs));
                  };
                  state.warmServerList = function(visible) {
                    race.setTargetWarmupVisible(visible);
                    if (!visible && typeof globalThis.__gaiusCancelRelayOrigins === 'function') {
                      globalThis.__gaiusCancelRelayOrigins();
                    }
                    const existing = state.serverListWarmupEntry;
                    if (visible && enabled('relayWarm') && race.warmupUsable(existing)) return;
                    if (existing) expire(existing);
                    if (!visible || !enabled('relayWarm')) return;
                    const relay = api.preferredRelay(null);
                    if (!relay || api.privateRelayUrl(relay.url) || relay.use === 'lan' ||
                        api.isLanPage() || api.externalExplicitRelayUrl(relay.url)) return;
                    const entry = {closed:false, connected:false, preconnectedRelay:null};
                    state.serverListWarmupEntry = entry;
                    api.startRelayPredial(entry, relay);
                    if (!entry.preconnectedRelay) { state.serverListWarmupEntry = null; return; }
                    if (enabled('relayBoot') && typeof globalThis.__gaiusWarmRelayOrigins === 'function') {
                      globalThis.__gaiusWarmRelayOrigins(function() {
                        return state.serverListWarmupEntry === entry;
                      });
                    }
                  };
                },
                enabled: function(name) {
                  return new URLSearchParams(location.search || '').get(name) !== '0';
                },
                coldRelayUrl: function(entry, candidate, token, tunnelRole) {
                  let url = null;
                  if (!tunnelRole) {
                    if (entry.relayProtocolDeclaration === undefined) {
                      entry.relayProtocolDeclaration = globalThis.__gaiusRelayProtocolDeclaration || null;
                    }
                    const control = {type:'connect', host:entry.host, port:entry.port, token:token};
                    if (entry.relayProtocolDeclaration) control.protocolTable = entry.relayProtocolDeclaration;
                    // Keep the same connect envelope as the control frame. A full build table
                    // may take the request over the URL limit, in which case keep the frame.
                    url = this.urlConnect(candidate.url, control, false);
                  }
                  this.recordConnectPhase(entry, 'url-connect=' + (url ? 'true' : 'false'), candidate.url);
                  return url;
                },
                urlConnect: function(url, control, hotSocket) {
                  if (!this.enabled('relayUrl') || hotSocket || control.token !== undefined ||
                      control.skinDescriptor !== undefined || this.localConnectHost(control.host)) {
                    return null;
                  }
                  const encodedUrl = this.encodeConnect(url, control);
                  if (!encodedUrl) return null;
                  const state = globalThis.__gaiusNettyBridge;
                  const cache = state.urlConnectCapabilities || (state.urlConnectCapabilities = new Map());
                  const endpoint = new URL(url, location.href).href;
                  const parsed = new URL(endpoint);
                  parsed.protocol = parsed.protocol === 'wss:' ? 'https:' : 'http:';
                  const origin = parsed.origin;
                  const cached = cache.get(endpoint);
                  if (cached && cached.expiresAt > Date.now()) return cached.enabled ? encodedUrl : null;
                  if (typeof fetch !== 'function') return null;
                  if (cache.size >= 128 && !cache.has(endpoint)) cache.delete(cache.keys().next().value);
                  const pending = {enabled:false, expiresAt:Date.now() + 5000};
                  cache.set(endpoint, pending);
                  const controller = typeof AbortController === 'function' ? new AbortController() : null;
                  const options = {cache:'no-store', credentials:'omit', redirect:'error'};
                  if (controller) options.signal = controller.signal;
                  let timeout;
                  // Unknown capability never delays this connection or changes its wire mode.
                  Promise.race([
                    Promise.resolve().then(function() { return fetch(origin + '/health', options); }).then(function(response) {
                      if (!response.ok) return false;
                      return response.json().then(function(value) {
                        return value && value.requiresToken === false && Array.isArray(value.capabilities) &&
                          value.capabilities.includes('url-connect');
                      });
                    }),
                    new Promise(function(resolve) {
                      timeout = setTimeout(function() {
                        if (controller) controller.abort();
                        resolve(false);
                      }, 150);
                    })
                  ]).catch(function() { return false; }).then(function(supported) {
                    clearTimeout(timeout);
                    if (cache.get(endpoint) === pending) {
                      cache.set(endpoint, {enabled:!!supported, expiresAt:Date.now() + (supported ? 300000 : 30000)});
                    }
                  });
                  return null;
                },
                rejectUrlConnect: function(url) {
                  const endpoint = new URL(url, location.href).href;
                  const cache = globalThis.__gaiusNettyBridge.urlConnectCapabilities;
                  if (!cache) return;
                  if (cache.size >= 128 && !cache.has(endpoint)) cache.delete(cache.keys().next().value);
                  cache.set(endpoint, {enabled:false, expiresAt:Date.now() + 30000});
                },
                localConnectHost: function(host) {
                  let name = String(host || '').trim();
                  try { name = new URL('http://' + name).hostname; } catch (ignored) {}
                  return /(?:^|\\.)gaius-local[.]*$/i.test(name);
                },
                encodeConnect: function(url, control) {
                  try {
                    if (control.token !== undefined || control.skinDescriptor !== undefined) return null;
                    const request = {type:'connect', host:control.host, port:control.port};
                    if (control.protocolTable !== undefined) request.protocolTable = control.protocolTable;
                    const json = JSON.stringify(request);
                    const bytes = new TextEncoder().encode(json);
                    if (bytes.length > 1024) return null;
                    let binary = '';
                    bytes.forEach(function(value) { binary += String.fromCharCode(value); });
                    const encoded = btoa(binary).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/g, '');
                    if (encoded.length > 1024) return null;
                    const parsed = new URL(url, location.href);
                    if ((parsed.protocol !== 'wss:' && parsed.protocol !== 'ws:') ||
                        parsed.username || parsed.password || parsed.pathname !== '/tunnel' ||
                        parsed.search || parsed.hash) return null;
                    parsed.searchParams.set('c', encoded);
                    return parsed.href;
                  } catch (ignored) { return null; }
                }
              };
            }
            """)
    static native void init();

    @JSBody(params = "address", script = "return globalThis.__gaiusRelayRaceHelpers.publicAddress(address);")
    static native boolean isPublicAddress(String address);
}
