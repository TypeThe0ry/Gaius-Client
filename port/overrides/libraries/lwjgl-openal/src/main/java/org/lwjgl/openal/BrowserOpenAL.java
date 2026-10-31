package org.lwjgl.openal;

import dev.gaius.browser.JsArrays;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Set;
import java.util.function.IntFunction;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.FunctionProviderLocal;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

public final class BrowserOpenAL {
    static final long FUNCTION_ADDRESS = 1L;
    static final FunctionProviderLocal FUNCTION_PROVIDER = new FunctionProviderLocal() {
        @Override
        public long getFunctionAddress(ByteBuffer functionName) {
            return FUNCTION_ADDRESS;
        }

        @Override
        public long getFunctionAddress(CharSequence functionName) {
            return FUNCTION_ADDRESS;
        }

        @Override
        public long getFunctionAddress(long handle, ByteBuffer functionName) {
            return FUNCTION_ADDRESS;
        }

        @Override
        public long getFunctionAddress(long handle, CharSequence functionName) {
            return FUNCTION_ADDRESS;
        }
    };

    static final Set<String> ALC_EXTENSIONS = Set.of(
            "OpenALC10",
            "OpenALC11");

    static final Set<String> AL_EXTENSIONS = Set.of(
            "OpenAL10",
            "OpenAL11",
            "AL_EXT_source_distance_model",
            "AL_EXT_LINEAR_DISTANCE");

    static final IntFunction<PointerBuffer> POINTER_BUFFER_FACTORY =
            PointerBuffer::allocateDirect;

    private static final int AL_VENDOR = 0xB001;
    private static final int AL_VERSION = 0xB002;
    private static final int AL_RENDERER = 0xB003;
    private static final int AL_EXTENSIONS_PARAM = 0xB004;
    private static final int AL_GAIN = 0x100A;
    private static final int AL_PITCH = 0x1003;
    private static final int AL_POSITION = 0x1004;
    private static final int AL_DIRECTION = 0x1005;
    private static final int AL_VELOCITY = 0x1006;
    private static final int AL_LOOPING = 0x1007;
    private static final int AL_BUFFER = 0x1009;
    private static final int AL_SOURCE_RELATIVE = 0x0202;
    private static final int AL_MIN_GAIN = 0x100D;
    private static final int AL_MAX_GAIN = 0x100E;
    private static final int AL_ORIENTATION = 0x100F;
    private static final int AL_CONE_INNER_ANGLE = 0x1001;
    private static final int AL_CONE_OUTER_ANGLE = 0x1002;
    private static final int AL_CONE_OUTER_GAIN = 0x1022;
    private static final int AL_REFERENCE_DISTANCE = 0x1020;
    private static final int AL_ROLLOFF_FACTOR = 0x1021;
    private static final int AL_MAX_DISTANCE = 0x1023;
    private static final int AL_DISTANCE_MODEL = 0xD000;
    private static final int AL_NONE = 0x0000;
    private static final int AL_INVERSE_DISTANCE = 0xD001;
    private static final int AL_INVERSE_DISTANCE_CLAMPED = 0xD002;
    private static final int AL_LINEAR_DISTANCE = 0xD003;
    private static final int AL_LINEAR_DISTANCE_CLAMPED = 0xD004;
    private static final int AL_EXPONENT_DISTANCE = 0xD005;
    private static final int AL_EXPONENT_DISTANCE_CLAMPED = 0xD006;
    private static final int AL_SOURCE_STATE = 0x1010;
    private static final int AL_BUFFERS_QUEUED = 0x1015;
    private static final int AL_BUFFERS_PROCESSED = 0x1016;
    private static final int AL_FREQUENCY = 0x2001;
    private static final int AL_BITS = 0x2002;
    private static final int AL_CHANNELS = 0x2003;
    private static final int AL_SIZE = 0x2004;
    private static final int ALC_MAJOR_VERSION = 0x1000;
    private static final int ALC_MINOR_VERSION = 0x1001;
    private static final int ALC_ATTRIBUTES_SIZE = 0x1002;
    private static final int ALC_ALL_ATTRIBUTES = 0x1003;
    private static final int ALC_FREQUENCY = 0x1007;
    private static final int ALC_REFRESH = 0x1008;
    private static final int ALC_SYNC = 0x1009;
    private static final int ALC_MONO_SOURCES = 0x1010;
    private static final int ALC_STEREO_SOURCES = 0x1011;
    private static final int ALC_CONNECTED = 0x0313;
    private static final int ALC_NUM_HRTF_SPECIFIERS_SOFT = 0x1994;
    private static final int[] ALC_ATTRIBUTES = {
            ALC_FREQUENCY, 48_000,
            ALC_REFRESH, 60,
            ALC_SYNC, 0,
            ALC_MONO_SOURCES, 30,
            ALC_STEREO_SOURCES, 8,
            0
    };

    private BrowserOpenAL() {
    }

    @JSBody(script = """
            if (window.__gaiusOpenAL) {
              return;
            }
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            const DEFAULT_FORWARD = [0, 0, -1];
            const DEFAULT_UP = [0, 1, 0];
            // Vanilla creates an AL source for every sound it plays and deletes it when the sound
            // ends; the gain/panner pair of a deleted source waits here for the next source
            // instead of becoming garbage (still connected to the master gain, with no input).
            const MAX_POOLED_GRAPHS = 32;
            const state = {
              next: 1,
              context: null,
              buffers: new Map(),
              sources: new Map(),
              resumeHooked: false,
              masterGainNode: null,
              graphPool: [],
              directionalAudio: false,
              listener: {
                gain: 1,
                position: [0, 0, 0],
                velocity: [0, 0, 0],
                forward: [0, 0, -1],
                up: [0, 1, 0],
                normalizedForward: [0, 0, -1],
                normalizedUp: [0, 1, 0]
              },
              distanceModel: 0xD002,
              stats: {
                initialized: true,
                backend: 'WebAudio',
                buffers: 0,
                sources: 0,
                bufferUploads: 0,
                sourcePlays: 0,
                sourceStops: 0,
                queuedBuffers: 0,
                unqueuedBuffers: 0,
                activeSources: 0,
                webAudioNodesCreated: 0,
                webAudioNodesDisposed: 0,
                webAudioConnections: 0,
                webAudioDisconnects: 0,
                webAudioNaturalEnds: 0,
                webAudioGraphsPooled: 0,
                webAudioGraphReuses: 0,
                audioParamWrites: 0,
                audioParamWritesSkipped: 0,
                directionalAudio: false,
                panningModel: 'equalpower',
                hrtfFallbacks: 0,
                lastFormat: 0,
                lastFrequency: 0,
                lastUploadBytes: 0,
                contextState: AudioContextClass ? 'created-lazily' : 'unavailable'
              }
            };
            // Only this file writes these AudioParams, so the last value it wrote is the value the
            // param holds: an unchanged value (the listener transform every frame, a source that
            // did not move) schedules no automation event.
            function setParam(param, value) {
              if (!param) return;
              const number = Number(value) || 0;
              if (param.__gaiusValue === number) {
                state.stats.audioParamWritesSkipped++;
                return;
              }
              try {
                if (state.context && typeof param.setValueAtTime === 'function') {
                  param.setValueAtTime(number, state.context.currentTime);
                } else {
                  param.value = number;
                }
                param.__gaiusValue = number;
                state.stats.audioParamWrites++;
              } catch (ignored) {
                param.__gaiusValue = undefined;
                try { param.value = number; } catch (ignoredAgain) {}
              }
            }
            // The same for the plain attributes of a PannerNode (model names, distances, cones).
            function setAttribute(node, name, value) {
              const cache = node.__gaiusAttributes || (node.__gaiusAttributes = {});
              if (cache[name] === value) return;
              cache[name] = undefined;
              node[name] = value;
              cache[name] = value;
            }
            function finiteNumber(value, fallback) {
              const number = Number(value);
              return Number.isFinite(number) ? number : fallback;
            }
            function setVector(target, x, y, z, fallbackX, fallbackY, fallbackZ) {
              target[0] = finiteNumber(x, fallbackX === undefined ? 0 : fallbackX);
              target[1] = finiteNumber(y, fallbackY === undefined ? 0 : fallbackY);
              target[2] = finiteNumber(z, fallbackZ === undefined ? 0 : fallbackZ);
              return target;
            }
            function normalizedVector(value, fallback, target) {
              const x = finiteNumber(value && value[0], fallback[0]);
              const y = finiteNumber(value && value[1], fallback[1]);
              const z = finiteNumber(value && value[2], fallback[2]);
              const length = Math.hypot(x, y, z);
              const output = target || [0, 0, 0];
              if (!(length > 0.000001)) {
                output[0] = fallback[0];
                output[1] = fallback[1];
                output[2] = fallback[2];
                return output;
              }
              output[0] = x / length;
              output[1] = y / length;
              output[2] = z / length;
              return output;
            }
            function validDistanceModel(model) {
              model = model|0;
              if (model === 0x0000
                  || model === 0xD001
                  || model === 0xD002
                  || model === 0xD003
                  || model === 0xD004
                  || model === 0xD005
                  || model === 0xD006) {
                return model;
              }
              return 0xD002;
            }
            function effectiveDistanceModel(source) {
              return validDistanceModel(source && source.distanceModel != null
                ? source.distanceModel : state.distanceModel);
            }
            function distanceModelName(model) {
              model = validDistanceModel(model);
              if (model === 0xD003 || model === 0xD004) return 'linear';
              if (model === 0xD005 || model === 0xD006) return 'exponential';
              return 'inverse';
            }
            function applyPanningModel(panner) {
              if (!panner) return;
              const requested = state.directionalAudio ? 'HRTF' : 'equalpower';
              try {
                panner.panningModel = requested;
                const applied = String(panner.panningModel || requested);
                state.stats.panningModel = applied;
                if (state.directionalAudio && applied.toUpperCase() !== 'HRTF') {
                  state.stats.hrtfFallbacks++;
                }
              } catch (error) {
                state.stats.lastError = String(error && (error.message || error));
                if (requested !== 'equalpower') {
                  try { panner.panningModel = 'equalpower'; } catch (ignored) {}
                  state.stats.panningModel = 'equalpower';
                  state.stats.hrtfFallbacks++;
                }
              }
            }
            function applyListener() {
              const ctx = state.context;
              if (!ctx || !ctx.listener) return;
              const listener = ctx.listener;
              const p = state.listener.position;
              const v = state.listener.velocity;
              const f = normalizedVector(
                state.listener.forward, DEFAULT_FORWARD, state.listener.normalizedForward);
              const u = normalizedVector(
                state.listener.up, DEFAULT_UP, state.listener.normalizedUp);
              try {
                if (listener.positionX) {
                  setParam(listener.positionX, p[0]);
                  setParam(listener.positionY, p[1]);
                  setParam(listener.positionZ, p[2]);
                } else if (listener.setPosition) {
                  listener.setPosition(p[0], p[1], p[2]);
                }
                if (listener.forwardX) {
                  setParam(listener.forwardX, f[0]);
                  setParam(listener.forwardY, f[1]);
                  setParam(listener.forwardZ, f[2]);
                  setParam(listener.upX, u[0]);
                  setParam(listener.upY, u[1]);
                  setParam(listener.upZ, u[2]);
                } else if (listener.setOrientation) {
                  listener.setOrientation(f[0], f[1], f[2], u[0], u[1], u[2]);
                }
                if (listener.velocityX) {
                  setParam(listener.velocityX, v[0]);
                  setParam(listener.velocityY, v[1]);
                  setParam(listener.velocityZ, v[2]);
                } else if (listener.setVelocity) {
                  listener.setVelocity(v[0], v[1], v[2]);
                }
              } catch (error) {
                state.stats.lastError = String(error && (error.message || error));
              }
              if (state.masterGainNode) setParam(state.masterGainNode.gain, state.listener.gain);
            }
            function ensureContext() {
              if (!AudioContextClass) return null;
              if (!state.context) {
                try {
                  state.context = new AudioContextClass();
                  state.stats.contextState = state.context.state || 'unknown';
                  applyListener();
                } catch (error) {
                  state.stats.contextState = 'failed';
                  state.stats.lastError = String(error && (error.message || error));
                  return null;
                }
              }
              if (!state.resumeHooked) {
                state.resumeHooked = true;
                                const resume = function() {
                  if (state.context && state.context.state === 'suspended') {
                                        state.context.resume().then(function() {
                      state.stats.contextState = state.context.state || 'running';
                                        }, function(ignored) {});
                  }
                };
                window.addEventListener('pointerdown', resume, {passive:true});
                window.addEventListener('keydown', resume, {passive:true});
                window.addEventListener('touchstart', resume, {passive:true});
              }
              return state.context;
            }
            function freshSource() {
              return {
                gain: 1,
                pitch: 1,
                looping: false,
                relative: false,
                position: [0, 0, 0],
                direction: [0, 0, -1],
                normalizedDirection: [0, 0, -1],
                velocity: [0, 0, 0],
                referenceDistance: 1,
                rolloffFactor: 1,
                maxDistance: 10000,
                coneInnerAngle: 360,
                coneOuterAngle: 360,
                coneOuterGain: 0,
                minGain: 0,
                maxGain: 1,
                distanceModel: null,
                buffer: 0,
                queue: [],
                scheduled: [],
                resumeOffset: 0,
                resumeQueueIndex: 0,
                state: 0x1011,
                active: false,
                gainNode: null,
                panner: null
              };
            }
            function applyPanner(source) {
              const panner = source && source.panner;
              if (!panner) return;
              try {
                const model = effectiveDistanceModel(source);
                const referenceDistance = Math.max(0.0001,
                  finiteNumber(source.referenceDistance, 1));
                const maxDistance = Math.max(referenceDistance,
                  finiteNumber(source.maxDistance, 10000));
                setAttribute(panner, 'distanceModel', distanceModelName(model));
                setAttribute(panner, 'refDistance', referenceDistance);
                setAttribute(panner, 'rolloffFactor', model === 0x0000 ? 0
                  : Math.max(0, finiteNumber(source.rolloffFactor, 1)));
                setAttribute(panner, 'maxDistance', maxDistance);
                setAttribute(panner, 'coneInnerAngle', Math.max(0, Math.min(360,
                  finiteNumber(source.coneInnerAngle, 360))));
                setAttribute(panner, 'coneOuterAngle', Math.max(0, Math.min(360,
                  finiteNumber(source.coneOuterAngle, 360))));
                setAttribute(panner, 'coneOuterGain', Math.max(0, Math.min(1,
                  finiteNumber(source.coneOuterGain, 0))));
                const p = source.position || [0, 0, 0];
                const d = normalizedVector(
                  source.direction, DEFAULT_FORWARD, source.normalizedDirection);
                const v = source.velocity || [0, 0, 0];
                if (panner.positionX) {
                  setParam(panner.positionX, finiteNumber(p[0], 0));
                  setParam(panner.positionY, finiteNumber(p[1], 0));
                  setParam(panner.positionZ, finiteNumber(p[2], 0));
                } else if (panner.setPosition) {
                  panner.setPosition(finiteNumber(p[0], 0), finiteNumber(p[1], 0),
                    finiteNumber(p[2], 0));
                }
                if (panner.orientationX) {
                  setParam(panner.orientationX, d[0]);
                  setParam(panner.orientationY, d[1]);
                  setParam(panner.orientationZ, d[2]);
                } else if (panner.setOrientation) {
                  panner.setOrientation(d[0], d[1], d[2]);
                }
                if (panner.velocityX) {
                  setParam(panner.velocityX, finiteNumber(v[0], 0));
                  setParam(panner.velocityY, finiteNumber(v[1], 0));
                  setParam(panner.velocityZ, finiteNumber(v[2], 0));
                }
              } catch (error) {
                state.stats.lastError = String(error && (error.message || error));
              }
            }
            function ensureSourceGraph(source) {
              const ctx = ensureContext();
              if (!ctx || !source) return false;
              if (!state.masterGainNode) {
                state.masterGainNode = ctx.createGain();
                trackNodeCreated(state.masterGainNode, 'master-gain');
                connectTrackedNode(state.masterGainNode, ctx.destination);
                applyListener();
              }
              if (!source.gainNode && state.graphPool.length > 0) {
                // A pooled pair is still connected (panner -> gain -> master) and has no input;
                // every parameter of the new source is applied below.
                const pooled = state.graphPool.pop();
                state.stats.webAudioGraphsPooled = state.graphPool.length;
                state.stats.webAudioGraphReuses++;
                source.gainNode = pooled.gainNode;
                source.panner = pooled.panner;
                if (source.panner) applyPanningModel(source.panner);
              }
              if (!source.gainNode) {
                source.gainNode = ctx.createGain();
                trackNodeCreated(source.gainNode, 'source-gain');
                connectTrackedNode(source.gainNode, state.masterGainNode);
              }
              setParam(source.gainNode.gain,
                Math.max(Number(source.minGain) || 0,
                  Math.min(Number(source.maxGain) || 1, Math.max(0, Number(source.gain) || 0))));
              if (!source.relative && !source.panner) {
                source.panner = ctx.createPanner();
                trackNodeCreated(source.panner, 'source-panner');
                applyPanningModel(source.panner);
                connectTrackedNode(source.panner, source.gainNode);
              }
              if (source.panner) applyPanner(source);
              return true;
            }
            function trackNodeCreated(node, kind) {
              if (!node || node.__gaiusTrackedNode) return node;
              node.__gaiusTrackedNode = true;
              node.__gaiusNodeKind = String(kind || 'audio-node');
              node.__gaiusDisposed = false;
              node.__gaiusConnected = false;
              state.stats.webAudioNodesCreated++;
              return node;
            }
            function disconnectTrackedNode(node) {
              if (!node || !node.__gaiusConnected) return;
              try { node.disconnect(); } catch (ignored) {}
              node.__gaiusConnected = false;
              state.stats.webAudioDisconnects++;
            }
            function connectTrackedNode(node, destination) {
              if (!node || !destination) return false;
              disconnectTrackedNode(node);
              node.connect(destination);
              node.__gaiusConnected = true;
              state.stats.webAudioConnections++;
              return true;
            }
            function disposeTrackedNode(node, stopFirst) {
              if (!node || node.__gaiusDisposed) return;
              if (stopFirst) {
                try { node.stop(); } catch (ignored) {}
              }
              disconnectTrackedNode(node);
              node.__gaiusDisposed = true;
              state.stats.webAudioNodesDisposed++;
            }
            function disposeScheduledEntry(entry) {
              if (!entry) return;
              const node = entry.node;
              entry.node = null;
              entry.ended = true;
              if (node) node.onended = null;
              disposeTrackedNode(node, true);
            }
            function setSourceActive(source, active) {
              if (!source || !!source.active === !!active) return;
              source.active = !!active;
              state.stats.activeSources = Math.max(
                0, state.stats.activeSources + (source.active ? 1 : -1));
            }
            function retireScheduledEntry(source, entry) {
              if (!source || !entry || entry.ended) return;
              const node = entry.node;
              entry.node = null;
              entry.ended = true;
              if (node) node.onended = null;
              disposeTrackedNode(node, false);
              state.stats.webAudioNaturalEnds++;
              if (entry.queued) {
                const allEnded = !source.scheduled || source.scheduled.every(function(candidate) {
                  return !candidate || candidate.ended;
                });
                if (allEnded && source.state === 0x1012) {
                  source.state = 0x1014;
                  setSourceActive(source, false);
                }
                return;
              }
              const index = source.scheduled ? source.scheduled.indexOf(entry) : -1;
              if (index >= 0) source.scheduled.splice(index, 1);
              if (source.state === 0x1012 && (!source.scheduled || source.scheduled.length === 0)) {
                source.state = 0x1014;
                setSourceActive(source, false);
              }
            }
            function connectNode(source, node) {
              if (!ensureSourceGraph(source)) return false;
              const destination = source.relative ? source.gainNode : source.panner;
              if (!destination) return false;
              return connectTrackedNode(node, destination);
            }
            function reconnectSource(source) {
              if (!source) return;
              ensureSourceGraph(source);
              if (!source.relative && source.panner) applyPanner(source);
              if (!source.scheduled) return;
              for (var reconnectIndex = 0; reconnectIndex < source.scheduled.length; reconnectIndex++) {
                const entry = source.scheduled[reconnectIndex];
                if (entry && entry.node) connectNode(source, entry.node);
              }
            }
            function stopNodes(source) {
              if (!source || !source.scheduled) return;
              for (var stopIndex = 0; stopIndex < source.scheduled.length; stopIndex++) {
                disposeScheduledEntry(source.scheduled[stopIndex]);
              }
              source.scheduled = [];
              setSourceActive(source, false);
            }
            function capturePausePosition(source) {
              source.resumeOffset = 0;
              source.resumeQueueIndex = 0;
              const ctx = state.context;
              if (!ctx || !source.scheduled || source.scheduled.length === 0) return;
              const now = Number(ctx.currentTime) || 0;
              let selectedIndex = -1;
              for (let index = 0; index < source.scheduled.length; index++) {
                const candidate = source.scheduled[index];
                if (candidate && !candidate.ended && candidate.endTime > now + 0.0005) {
                  selectedIndex = index;
                  break;
                }
              }
              if (selectedIndex < 0) return;
              const entry = source.scheduled[selectedIndex];
              const duration = Math.max(0.001, Number(entry.mediaDuration) || 0.001);
              const pitch = Math.max(0.01, Number(entry.pitch) || Number(source.pitch) || 1);
              const elapsed = Math.max(0, now - (Number(entry.startTime) || now)) * pitch;
              let offset = Math.max(0, (Number(entry.startOffset) || 0) + elapsed);
              if (entry.loop) {
                offset %= duration;
              } else {
                offset = Math.min(offset, Math.max(0, duration - 0.0005));
              }
              source.resumeOffset = offset;
              source.resumeQueueIndex = source.buffer ? 0 : selectedIndex;
            }
            function disposeSourceGraph(source) {
              if (!source) return;
              stopNodes(source);
              const gainNode = source.gainNode;
              const panner = source.panner;
              source.panner = null;
              source.gainNode = null;
              // stopNodes disconnected every input, so the pair is silent. Keep it for the next
              // source while it is intact: gain -> master, and the panner (if any) -> gain.
              if (gainNode && !gainNode.__gaiusDisposed && gainNode.__gaiusConnected
                  && state.masterGainNode && !state.masterGainNode.__gaiusDisposed
                  && (!panner || (!panner.__gaiusDisposed && panner.__gaiusConnected))
                  && state.graphPool.length < MAX_POOLED_GRAPHS) {
                state.graphPool.push({gainNode: gainNode, panner: panner});
                state.stats.webAudioGraphsPooled = state.graphPool.length;
                return;
              }
              disposeTrackedNode(panner, false);
              disposeTrackedNode(gainNode, false);
            }
            function disposeGraphPool() {
              const pool = state.graphPool;
              state.graphPool = [];
              state.stats.webAudioGraphsPooled = 0;
              for (var poolIndex = 0; poolIndex < pool.length; poolIndex++) {
                disposeTrackedNode(pool[poolIndex].panner, false);
                disposeTrackedNode(pool[poolIndex].gainNode, false);
              }
            }
            function processedCount(source) {
              if (!source || !source.scheduled || source.scheduled.length === 0) return 0;
              const ctx = state.context;
              if (!ctx) return 0;
              const now = ctx.currentTime || 0;
                            var count = 0;
                            for (var processedIndex = 0; processedIndex < source.scheduled.length; processedIndex++) {
                                var entry = source.scheduled[processedIndex];
                if (entry && (entry.ended || entry.endTime <= now + 0.005)) count++;
              }
              return Math.min(count, source.queue ? source.queue.length : count);
            }
            function refreshState(source) {
              if (!source) return 0x1014;
              if (source.state !== 0x1012) return source.state || 0x1011;
              if (source.looping) return 0x1012;
              const ctx = state.context;
              if (!ctx || !source.scheduled || source.scheduled.length === 0) {
                source.state = 0x1014;
                setSourceActive(source, false);
                return source.state;
              }
              const now = ctx.currentTime || 0;
                            for (var refreshIndex = 0; refreshIndex < source.scheduled.length; refreshIndex++) {
                                var entry = source.scheduled[refreshIndex];
                if (entry && entry.endTime > now + 0.005) return 0x1012;
              }
              source.state = 0x1014;
              setSourceActive(source, false);
              return source.state;
            }
            function connectSourceNode(source, node) {
              if (!connectNode(source, node)) return false;
              setParam(node.playbackRate, Math.max(0.01, Number(source.pitch) || 1));
              return true;
            }
            function scheduleBuffer(source, bufferId, startAt, loop, offsetSeconds) {
              const ctx = ensureContext();
              const buffer = state.buffers.get(bufferId|0);
              if (!ctx || !buffer || !buffer.audio) return startAt;
              const node = ctx.createBufferSource();
              trackNodeCreated(node, 'buffer-source');
              node.buffer = buffer.audio;
              node.loop = !!loop;
              if (!connectSourceNode(source, node)) {
                disposeTrackedNode(node, true);
                return startAt;
              }
              const when = Math.max(ctx.currentTime, Number(startAt) || ctx.currentTime);
              const mediaDuration = Math.max(0.001, Number(buffer.audio.duration) || 0.001);
              const pitch = Math.max(0.01, Number(source.pitch) || 1);
              let offset = Math.max(0, Number(offsetSeconds) || 0);
              if (loop) {
                offset %= mediaDuration;
              } else {
                offset = Math.min(offset, Math.max(0, mediaDuration - 0.0005));
              }
              const duration = Math.max(0.001, (mediaDuration - offset) / pitch);
              const queued = !loop && !source.buffer && !!(source.queue && source.queue.length);
              const entry = {bufferId: bufferId|0, node: node,
                startTime: when, startOffset: offset, mediaDuration: mediaDuration,
                pitch: pitch, loop: !!loop,
                endTime: loop ? Number.MAX_VALUE : when + duration,
                queued: queued, ended: false};
              source.scheduled.push(entry);
              node.onended = function() { retireScheduledEntry(source, entry); };
              try {
                node.start(when, offset);
              } catch (error) {
                state.stats.lastError = String(error && (error.message || error));
                const index = source.scheduled.indexOf(entry);
                if (index >= 0) source.scheduled.splice(index, 1);
                disposeScheduledEntry(entry);
                return startAt;
              }
              return when + duration;
            }
            state.ensureContext = ensureContext;
            state.setParam = setParam;
            state.finiteNumber = finiteNumber;
            state.setVector = setVector;
            state.validDistanceModel = validDistanceModel;
            state.effectiveDistanceModel = effectiveDistanceModel;
            state.freshSource = freshSource;
            state.stopNodes = stopNodes;
            state.capturePausePosition = capturePausePosition;
            state.disposeScheduledEntry = disposeScheduledEntry;
            state.setSourceActive = setSourceActive;
            state.retireScheduledEntry = retireScheduledEntry;
            state.disposeSourceGraph = disposeSourceGraph;
            state.disposeGraphPool = disposeGraphPool;
            state.processedCount = processedCount;
            state.refreshState = refreshState;
            state.scheduleBuffer = scheduleBuffer;
            state.applyListener = applyListener;
            state.applyPanningModel = applyPanningModel;
            state.applyPanner = applyPanner;
            state.reconnectSource = reconnectSource;
            window.__gaiusOpenAL = state;
            window.__gaiusAudioStats = state.stats;
            """)
    public static native void init();

    @JSBody(params = "enabled", script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            state.directionalAudio = !!enabled;
            state.stats.directionalAudio = state.directionalAudio;
            state.sources.forEach(function(source) {
              if (source && source.panner) state.applyPanningModel(source.panner);
            });
            """)
    public static native void setDirectionalAudio(boolean enabled);

    @JSBody(script = """
            if (!window.__gaiusOpenAL) return;
            const state = window.__gaiusOpenAL;
            state.sources.forEach(function(source) { state.disposeSourceGraph(source); });
            state.sources.clear();
            state.buffers.clear();
            state.disposeGraphPool();
            if (state.masterGainNode) {
              const master = state.masterGainNode;
              state.masterGainNode = null;
              if (!master.__gaiusDisposed) {
                if (master.__gaiusConnected) {
                  try { master.disconnect(); } catch (ignored) {}
                  master.__gaiusConnected = false;
                  state.stats.webAudioDisconnects++;
                }
                master.__gaiusDisposed = true;
                state.stats.webAudioNodesDisposed++;
              }
            }
            state.stats.sources = 0;
            state.stats.buffers = 0;
            state.stats.activeSources = 0;
            """)
    public static native void cleanup();

    public static int getError() {
        return 0;
    }

    public static boolean alcIsExtensionPresent(long device, CharSequence extension) {
        return extension != null && ALC_EXTENSIONS.contains(extension.toString());
    }

    public static boolean alcIsExtensionPresent(long device, ByteBuffer extension) {
        if (extension == null) {
            return false;
        }
        ByteBuffer copy = extension.duplicate();
        StringBuilder name = new StringBuilder(copy.remaining());
        while (copy.hasRemaining()) {
            int value = copy.get() & 0xFF;
            if (value == 0) {
                break;
            }
            name.append((char) value);
        }
        return alcIsExtensionPresent(device, name);
    }

    public static int alcGetInteger(long device, int parameter) {
        return switch (parameter) {
            case ALC_MAJOR_VERSION, ALC_MINOR_VERSION, ALC_CONNECTED -> 1;
            case ALC_ATTRIBUTES_SIZE -> ALC_ATTRIBUTES.length;
            case ALC_FREQUENCY -> 48_000;
            case ALC_REFRESH -> 60;
            case ALC_MONO_SOURCES -> 30;
            case ALC_STEREO_SOURCES -> 8;
            case ALC_SYNC, ALC_NUM_HRTF_SPECIFIERS_SOFT -> 0;
            default -> 0;
        };
    }

    public static void alcGetIntegerv(long device, int parameter, IntBuffer values) {
        if (values == null) {
            return;
        }
        IntBuffer copy = values.duplicate();
        if (parameter == ALC_ALL_ATTRIBUTES) {
            for (int value : ALC_ATTRIBUTES) {
                if (!copy.hasRemaining()) {
                    break;
                }
                copy.put(value);
            }
        } else if (copy.hasRemaining()) {
            copy.put(alcGetInteger(device, parameter));
        }
    }

    public static void alcGetIntegerv(long device, int parameter, int[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        if (parameter == ALC_ALL_ATTRIBUTES) {
            System.arraycopy(ALC_ATTRIBUTES, 0, values, 0, Math.min(values.length, ALC_ATTRIBUTES.length));
        } else {
            values[0] = alcGetInteger(device, parameter);
        }
    }

    public static int getInteger(int parameter) {
        return switch (parameter) {
            case AL_GAIN, AL_PITCH -> 1;
            case AL_DISTANCE_MODEL -> getDistanceModelJs();
            default -> 0;
        };
    }

    @JSBody(script = """
            const state = window.__gaiusOpenAL;
            return state ? state.distanceModel|0 : 0xD002;
            """)
    private static native int getDistanceModelJs();

    public static float getFloat(int parameter) {
        return getInteger(parameter);
    }

    public static double getDouble(int parameter) {
        return getInteger(parameter);
    }

    public static String getString(int parameter) {
        return switch (parameter) {
            case AL_VENDOR -> "Gaius";
            case AL_VERSION -> "OpenAL 1.1 Web Audio";
            case AL_RENDERER -> "Gaius Browser OpenAL";
            case AL_EXTENSIONS_PARAM -> String.join(" ", AL_EXTENSIONS);
            default -> null;
        };
    }

    public static int genSource() {
        init();
        return genSourceJs();
    }

    public static void genSources(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int index = 0; index < sources.length; index++) {
            sources[index] = genSource();
        }
    }

    public static void genSources(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            copy.put(genSource());
        }
    }

    @JSBody(script = """
            const state = window.__gaiusOpenAL;
            const id = state.next++;
            state.sources.set(id, state.freshSource());
            state.stats.sources = state.sources.size;
            return id|0;
            """)
    private static native int genSourceJs();

    public static void deleteSource(int source) {
        deleteSourceJs(source);
    }

    public static void deleteSources(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            deleteSource(source);
        }
    }

    public static void deleteSources(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            deleteSource(copy.get());
        }
    }

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            source = source|0;
            const src = state.sources.get(source);
            if (src) state.disposeSourceGraph(src);
            state.sources.delete(source);
            state.stats.sources = state.sources.size;
            """)
    private static native void deleteSourceJs(int source);

    @JSBody(params = {"source"}, script = """
            return !!(window.__gaiusOpenAL && window.__gaiusOpenAL.sources.has(source|0));
            """)
    public static native boolean isSource(int source);

    public static void sourcef(int source, int parameter, float value) {
        init();
        sourcefJs(source, parameter, value);
    }

    public static void source3f(int source, int parameter, float x, float y, float z) {
        init();
        source3fJs(source, parameter, x, y, z);
    }

    public static void sourcefv(int source, int parameter, FloatBuffer values) {
        if (values == null || values.remaining() < 3) {
            return;
        }
        FloatBuffer copy = values.duplicate();
        source3f(source, parameter, copy.get(), copy.get(), copy.get());
    }

    public static void sourcefv(int source, int parameter, float[] values) {
        if (values == null || values.length < 3) {
            return;
        }
        source3f(source, parameter, values[0], values[1], values[2]);
    }

    @JSBody(params = {"source", "parameter", "value"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            parameter = parameter|0;
            value = Number(value);
            if (!Number.isFinite(value)) value = 0;
            if (parameter === 0x100A) {
              src.gain = Math.max(0, value);
              if (src.gainNode) state.setParam(src.gainNode.gain,
                Math.max(src.minGain, Math.min(src.maxGain, src.gain)));
            } else if (parameter === 0x1003) {
              src.pitch = Math.max(0.01, value);
              if (src.scheduled) {
                for (var pitchIndex = 0; pitchIndex < src.scheduled.length; pitchIndex++) {
                  var pitchEntry = src.scheduled[pitchIndex];
                  if (pitchEntry && pitchEntry.node) state.setParam(pitchEntry.node.playbackRate, src.pitch);
                }
              }
            } else if (parameter === 0x1001) {
              src.coneInnerAngle = value;
              state.applyPanner(src);
            } else if (parameter === 0x1002) {
              src.coneOuterAngle = value;
              state.applyPanner(src);
            } else if (parameter === 0x1022) {
              src.coneOuterGain = value;
              state.applyPanner(src);
            } else if (parameter === 0x100D) {
              src.minGain = Math.max(0, Math.min(1, value));
              if (src.gainNode) state.setParam(src.gainNode.gain,
                Math.max(src.minGain, Math.min(src.maxGain, src.gain)));
            } else if (parameter === 0x100E) {
              src.maxGain = Math.max(src.minGain, Math.min(1, value));
              if (src.gainNode) state.setParam(src.gainNode.gain,
                Math.max(src.minGain, Math.min(src.maxGain, src.gain)));
            } else if (parameter === 0x1020) {
              src.referenceDistance = Math.max(0.0001, value);
              state.applyPanner(src);
            } else if (parameter === 0x1021) {
              src.rolloffFactor = Math.max(0, value);
              state.applyPanner(src);
            } else if (parameter === 0x1023) {
              src.maxDistance = Math.max(0.0001, value);
              state.applyPanner(src);
            } else {
              src[parameter] = value;
            }
            """)
    private static native void sourcefJs(int source, int parameter, float value);

    @JSBody(params = {"source", "parameter", "x", "y", "z"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            if ((parameter|0) === 0x1004) {
              state.setVector(src.position, x, y, z);
              state.applyPanner(src);
            } else if ((parameter|0) === 0x1005) {
              state.setVector(src.direction, x, y, z);
              state.applyPanner(src);
            } else if ((parameter|0) === 0x1006) {
              state.setVector(src.velocity, x, y, z);
              state.applyPanner(src);
            } else {
              const value = src[parameter|0] || [0, 0, 0];
              src[parameter|0] = state.setVector(value, x, y, z);
            }
            """)
    private static native void source3fJs(int source, int parameter, float x, float y, float z);

    public static void sourcei(int source, int parameter, int value) {
        init();
        sourceiJs(source, parameter, value);
    }

    public static void source3i(int source, int parameter, int x, int y, int z) {
        source3f(source, parameter, x, y, z);
    }

    public static void sourceiv(int source, int parameter, IntBuffer values) {
        if (values == null || values.remaining() == 0) {
            return;
        }
        IntBuffer copy = values.duplicate();
        if (copy.remaining() >= 3) {
            source3i(source, parameter, copy.get(), copy.get(), copy.get());
        } else {
            sourcei(source, parameter, copy.get());
        }
    }

    public static void sourceiv(int source, int parameter, int[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        if (values.length >= 3) {
            source3i(source, parameter, values[0], values[1], values[2]);
        } else {
            sourcei(source, parameter, values[0]);
        }
    }

    @JSBody(params = {"source", "parameter", "value"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            parameter = parameter|0;
            value = value|0;
            if (parameter === 0x1007) {
              src.looping = value !== 0;
              if (src.scheduled) {
                for (var loopIndex = 0; loopIndex < src.scheduled.length; loopIndex++) {
                  var loopEntry = src.scheduled[loopIndex];
                  if (loopEntry && loopEntry.node) loopEntry.node.loop = src.looping;
                }
              }
            } else if (parameter === 0x1009) {
              src.buffer = value;
              src.resumeOffset = 0;
              src.resumeQueueIndex = 0;
            } else if (parameter === 0x0202) {
              src.relative = value !== 0;
              state.reconnectSource(src);
            } else if (parameter === 0xD000) {
              src.distanceModel = state.validDistanceModel(value);
              state.applyPanner(src);
            } else {
              src[parameter] = value;
            }
            """)
    private static native void sourceiJs(int source, int parameter, int value);

    public static int getSourcei(int source, int parameter) {
        return getSourceiJs(source, parameter);
    }

    public static void getSourcei(int source, int parameter, IntBuffer values) {
        if (values != null && values.hasRemaining()) {
            values.duplicate().put(getSourcei(source, parameter));
        }
    }

    public static void getSourcei(int source, int parameter, int[] values) {
        if (values != null && values.length > 0) {
            values[0] = getSourcei(source, parameter);
        }
    }

    public static void getSourceiv(int source, int parameter, IntBuffer values) {
        getSourcei(source, parameter, values);
    }

    public static void getSourceiv(int source, int parameter, int[] values) {
        getSourcei(source, parameter, values);
    }

    @JSBody(params = {"source", "parameter"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return 0;
            const src = state.sources.get(source|0);
            if (!src) return 0;
            parameter = parameter|0;
            if (parameter === 0x1010) return state.refreshState(src)|0;
            if (parameter === 0x1015) return (src.queue ? src.queue.length : 0)|0;
            if (parameter === 0x1016) return state.processedCount(src)|0;
            if (parameter === 0x1009) return (src.buffer || 0)|0;
            if (parameter === 0x0202) return src.relative ? 1 : 0;
            if (parameter === 0x1007) return src.looping ? 1 : 0;
            if (parameter === 0xD000) return state.effectiveDistanceModel(src)|0;
            return (src[parameter] || 0)|0;
            """)
    private static native int getSourceiJs(int source, int parameter);

    public static void sourceQueueBuffers(int source, int buffer) {
        sourceQueueBufferJs(source, buffer);
    }

    public static void sourceQueueBuffers(int source, int[] buffers) {
        if (buffers == null) {
            return;
        }
        for (int buffer : buffers) {
            sourceQueueBuffers(source, buffer);
        }
    }

    public static void sourceQueueBuffers(int source, IntBuffer buffers) {
        if (buffers == null) {
            return;
        }
        IntBuffer copy = buffers.duplicate();
        while (copy.hasRemaining()) {
            sourceQueueBuffers(source, copy.get());
        }
    }

    @JSBody(params = {"source", "buffer"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            src.queue.push(buffer|0);
            if (src.state === 0x1012) {
              let at = state.context ? state.context.currentTime : 0;
              if (src.scheduled && src.scheduled.length) {
                const tail = src.scheduled[src.scheduled.length - 1];
                if (tail && Number.isFinite(tail.endTime)) at = Math.max(at, tail.endTime);
              }
              state.scheduleBuffer(src, buffer|0, at, false);
            }
            state.stats.queuedBuffers++;
            """)
    private static native void sourceQueueBufferJs(int source, int buffer);

    public static int sourceUnqueueBuffer(int source) {
        return sourceUnqueueBufferJs(source);
    }

    public static void sourceUnqueueBuffers(int source, int[] buffers) {
        if (buffers == null) {
            return;
        }
        for (int index = 0; index < buffers.length; index++) {
            buffers[index] = sourceUnqueueBuffer(source);
        }
    }

    public static void sourceUnqueueBuffers(int source, IntBuffer buffers) {
        if (buffers == null) {
            return;
        }
        IntBuffer copy = buffers.duplicate();
        while (copy.hasRemaining()) {
            copy.put(sourceUnqueueBuffer(source));
        }
    }

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return 0;
            const src = state.sources.get(source|0);
            if (!src || !src.queue || src.queue.length === 0) return 0;
            const processed = state.processedCount(src);
            if (processed <= 0 && state.refreshState(src) === 0x1012) return 0;
            const id = src.queue.shift() || 0;
            if (src.scheduled && src.scheduled.length > 0) {
              state.disposeScheduledEntry(src.scheduled.shift());
            }
            if (src.resumeQueueIndex > 0) {
              src.resumeQueueIndex--;
            } else {
              src.resumeOffset = 0;
            }
            state.stats.unqueuedBuffers++;
            return id|0;
            """)
    private static native int sourceUnqueueBufferJs(int source);

    public static void sourcePlay(int source) {
        init();
        sourcePlayJs(source);
    }

    public static void sourcePause(int source) {
        init();
        sourcePauseJs(source);
    }

    public static void sourceStop(int source) {
        init();
        sourceStopJs(source);
    }

    public static void sourceRewind(int source) {
        sourceStop(source);
    }

    public static void sourcePlayv(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            sourcePlay(source);
        }
    }

    public static void sourcePlayv(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            sourcePlay(copy.get());
        }
    }

    public static void sourcePausev(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            sourcePause(source);
        }
    }

    public static void sourcePausev(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            sourcePause(copy.get());
        }
    }

    public static void sourceStopv(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            sourceStop(source);
        }
    }

    public static void sourceStopv(IntBuffer sources) {
        if (sources == null) {
            return;
        }
        IntBuffer copy = sources.duplicate();
        while (copy.hasRemaining()) {
            sourceStop(copy.get());
        }
    }

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            state.ensureContext();
            const wasPaused = src.state === 0x1013;
            const resumeOffset = wasPaused ? Math.max(0, Number(src.resumeOffset) || 0) : 0;
            const resumeQueueIndex = wasPaused
              ? Math.max(0, Math.min(src.queue ? src.queue.length : 0,
                  Number(src.resumeQueueIndex) || 0)) : 0;
            state.stopNodes(src);
            src.scheduled = [];
            if (src.buffer) {
              state.scheduleBuffer(src, src.buffer|0, 0, src.looping, resumeOffset);
            } else if (src.queue && src.queue.length) {
                            var at = state.context ? state.context.currentTime : 0;
                            for (var processedIndex = 0; processedIndex < resumeQueueIndex; processedIndex++) {
                              src.scheduled.push({bufferId: src.queue[processedIndex]|0, node: null,
                                startTime: at, startOffset: 0, mediaDuration: 0,
                                pitch: Math.max(0.01, Number(src.pitch) || 1), loop: false,
                                endTime: at, queued: true, ended: true});
                            }
                            for (var queueIndex = resumeQueueIndex; queueIndex < src.queue.length; queueIndex++) {
                                at = state.scheduleBuffer(src, src.queue[queueIndex]|0, at, false,
                                  queueIndex === resumeQueueIndex ? resumeOffset : 0);
              }
            }
            src.resumeOffset = 0;
            src.resumeQueueIndex = 0;
            src.state = src.scheduled && src.scheduled.length ? 0x1012 : 0x1014;
            state.stats.sourcePlays++;
            state.setSourceActive(src, src.state === 0x1012);
            if (state.context) state.stats.contextState = state.context.state || 'unknown';
            """)
    private static native void sourcePlayJs(int source);

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            if (state.refreshState(src) !== 0x1012) return;
            state.capturePausePosition(src);
            state.stopNodes(src);
            src.state = 0x1013;
            """)
    private static native void sourcePauseJs(int source);

    @JSBody(params = {"source"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const src = state.sources.get(source|0);
            if (!src) return;
            state.stopNodes(src);
            src.resumeOffset = 0;
            src.resumeQueueIndex = 0;
            src.state = 0x1014;
            state.stats.sourceStops++;
            """)
    private static native void sourceStopJs(int source);

    public static int genBuffer() {
        init();
        return genBufferJs();
    }

    public static void genBuffers(int[] buffers) {
        if (buffers == null) {
            return;
        }
        for (int index = 0; index < buffers.length; index++) {
            buffers[index] = genBuffer();
        }
    }

    public static void genBuffers(IntBuffer buffers) {
        if (buffers == null) {
            return;
        }
        IntBuffer copy = buffers.duplicate();
        while (copy.hasRemaining()) {
            copy.put(genBuffer());
        }
    }

    @JSBody(script = """
            const state = window.__gaiusOpenAL;
            const id = state.next++;
            state.buffers.set(id, {format:0, frequency:0, size:0, audio:null});
            state.stats.buffers = state.buffers.size;
            return id|0;
            """)
    private static native int genBufferJs();

    public static void deleteBuffer(int buffer) {
        deleteBufferJs(buffer);
    }

    public static void deleteBuffers(int[] buffers) {
        if (buffers == null) {
            return;
        }
        for (int buffer : buffers) {
            deleteBuffer(buffer);
        }
    }

    public static void deleteBuffers(IntBuffer buffers) {
        if (buffers == null) {
            return;
        }
        IntBuffer copy = buffers.duplicate();
        while (copy.hasRemaining()) {
            deleteBuffer(copy.get());
        }
    }

    @JSBody(params = {"buffer"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            state.buffers.delete(buffer|0);
            state.stats.buffers = state.buffers.size;
            """)
    private static native void deleteBufferJs(int buffer);

    @JSBody(params = {"buffer"}, script = """
            return !!(window.__gaiusOpenAL && window.__gaiusOpenAL.buffers.has(buffer|0));
            """)
    public static native boolean isBuffer(int buffer);

    public static void bufferData(int buffer, int format, ByteBuffer data, int frequency) {
        if (JsArrays.LINEAR_MEMORY) {
            int length = data == null ? 0 : data.remaining();
            bufferDataLinearJs(buffer, format, linearAddress(data, length), length, frequency);
            return;
        }
        bufferDataJs(buffer, format, bytes(data), frequency);
    }

    public static void bufferData(int buffer, int format, ShortBuffer data, int frequency) {
        if (JsArrays.LINEAR_MEMORY) {
            int length = data == null ? 0 : data.remaining() * 2;
            bufferDataLinearJs(buffer, format, linearAddress(data, length), length, frequency);
            return;
        }
        bufferDataJs(buffer, format, shorts(data), frequency);
    }

    public static void bufferData(int buffer, int format, IntBuffer data, int frequency) {
        if (JsArrays.LINEAR_MEMORY) {
            int length = data == null ? 0 : data.remaining() * 4;
            bufferDataLinearJs(buffer, format, linearAddress(data, length), length, frequency);
            return;
        }
        bufferDataJs(buffer, format, ints(data), frequency);
    }

    public static void bufferData(int buffer, int format, FloatBuffer data, int frequency) {
        if (JsArrays.LINEAR_MEMORY) {
            int length = data == null ? 0 : data.remaining() * 4;
            bufferDataLinearJs(buffer, format, linearAddress(data, length), length, frequency);
            return;
        }
        bufferDataJs(buffer, format, floats(data), frequency);
    }

    public static void bufferData(int buffer, int format, short[] data, int frequency) {
        if (JsArrays.LINEAR_MEMORY) {
            int count = data == null ? 0 : data.length;
            int address = count == 0 ? 0 : JsArrays.stageInt16(data, 0, count);
            bufferDataLinearJs(buffer, format, address, count * 2, frequency);
            return;
        }
        bufferDataJs(buffer, format, shorts(data), frequency);
    }

    public static void bufferData(int buffer, int format, int[] data, int frequency) {
        if (JsArrays.LINEAR_MEMORY) {
            int count = data == null ? 0 : data.length;
            int address = count == 0 ? 0 : JsArrays.stageInt32(data, 0, count);
            bufferDataLinearJs(buffer, format, address, count * 4, frequency);
            return;
        }
        bufferDataJs(buffer, format, ints(data), frequency);
    }

    public static void bufferData(int buffer, int format, float[] data, int frequency) {
        if (JsArrays.LINEAR_MEMORY) {
            int count = data == null ? 0 : data.length;
            int address = count == 0 ? 0 : JsArrays.stageFloat32(data, 0, count);
            bufferDataLinearJs(buffer, format, address, count * 4, frequency);
            return;
        }
        bufferDataJs(buffer, format, floats(data), frequency);
    }

    /**
     * WebAssembly GC: linear-memory address of the {@code byteLength} remaining bytes of a
     * buffer, in place when it is direct (linear memory is little-endian, the byte order the
     * JavaScript build writes) and staged otherwise; 0 for no data.
     */
    private static int linearAddress(Buffer data, int byteLength) {
        JsArrays.installLinearViews();
        if (data == null || byteLength <= 0) {
            return 0;
        }
        if (data.isDirect() && littleEndian(data)) {
            return (int) MemoryUtil.memAddress(data);
        }
        int address = JsArrays.scratch(byteLength);
        long target = address;
        int position = data.position();
        int count = data.remaining();
        if (data instanceof ByteBuffer bytes) {
            for (int i = 0; i < count; i++) {
                MemoryUtil.memPutByte(target + i, bytes.get(position + i));
            }
        } else if (data instanceof ShortBuffer shorts) {
            for (int i = 0; i < count; i++) {
                MemoryUtil.memPutShort(target + ((long) i << 1), shorts.get(position + i));
            }
        } else if (data instanceof IntBuffer ints) {
            for (int i = 0; i < count; i++) {
                MemoryUtil.memPutInt(target + ((long) i << 2), ints.get(position + i));
            }
        } else if (data instanceof FloatBuffer floats) {
            for (int i = 0; i < count; i++) {
                MemoryUtil.memPutFloat(target + ((long) i << 2), floats.get(position + i));
            }
        } else {
            throw new IllegalArgumentException("Unsupported OpenAL buffer data: " + data);
        }
        return address;
    }

    /** True when a buffer's elements are stored little-endian (bytes always are). */
    private static boolean littleEndian(Buffer data) {
        ByteOrder order;
        if (data instanceof ByteBuffer) {
            return true;
        } else if (data instanceof ShortBuffer shorts) {
            order = shorts.order();
        } else if (data instanceof IntBuffer ints) {
            order = ints.order();
        } else if (data instanceof FloatBuffer floats) {
            order = floats.order();
        } else {
            return false;
        }
        return order == ByteOrder.LITTLE_ENDIAN;
    }

    @JSBody(params = {"buffer", "format", "data", "frequency"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const ctx = state.ensureContext();
            format = format|0;
            frequency = Math.max(1, frequency|0);
            const bytes = data ? new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength || 0) : new Uint8Array(0);
            const channels = (format === 0x1102 || format === 0x1103) ? 2 : 1;
            const bits = (format === 0x1101 || format === 0x1103) ? 16 : 8;
            const bytesPerSample = bits >> 3;
            const frameCount = Math.max(0, Math.floor(bytes.length / Math.max(1, channels * bytesPerSample)));
            var audio = null;
            if (ctx && frameCount > 0) {
              audio = ctx.createBuffer(channels, frameCount, frequency);
              if (bits === 16) {
                                const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
                                for (var channel = 0; channel < channels; channel++) {
                                    var out = audio.getChannelData(channel);
                                    for (var frame = 0; frame < frameCount; frame++) {
                                        var offset = (frame * channels + channel) * 2;
                    out[frame] = Math.max(-1, Math.min(1, view.getInt16(offset, true) / 32768));
                  }
                }
              } else {
                                for (var channel = 0; channel < channels; channel++) {
                                    var out = audio.getChannelData(channel);
                                    for (var frame = 0; frame < frameCount; frame++) {
                    out[frame] = ((bytes[frame * channels + channel] || 0) - 128) / 128;
                  }
                }
              }
            }
                        state.buffers.set(buffer|0, {
                            format: format,
                            frequency: frequency,
                            size: bytes.length,
                            channels: channels,
                            bits: bits,
                            audio: audio
                        });
            state.stats.buffers = state.buffers.size;
            state.stats.bufferUploads++;
            state.stats.lastFormat = format;
            state.stats.lastFrequency = frequency;
            state.stats.lastUploadBytes = bytes.length;
            if (state.context) state.stats.contextState = state.context.state || 'unknown';
            """)
    private static native void bufferDataJs(int buffer, int format, Int8Array data, int frequency);

    /**
     * {@link #bufferDataJs} reading {@code length} bytes at a linear-memory address (WebAssembly
     * GC); the decoding is the same.
     */
    @JSBody(params = {"buffer", "format", "address", "length", "frequency"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            const ctx = state.ensureContext();
            format = format|0;
            frequency = Math.max(1, frequency|0);
            const byteLength = Math.max(0, length | 0);
            var bytes = new Uint8Array(0);
            if ((address | 0) !== 0 && byteLength > 0) {
              const linear = globalThis.__gaiusLinear;
              const memory = linear.u8.length ? linear.u8 : linear.refresh().u8;
              bytes = memory.subarray(address >>> 0, (address >>> 0) + byteLength);
            }
            const channels = (format === 0x1102 || format === 0x1103) ? 2 : 1;
            const bits = (format === 0x1101 || format === 0x1103) ? 16 : 8;
            const bytesPerSample = bits >> 3;
            const frameCount = Math.max(0, Math.floor(bytes.length / Math.max(1, channels * bytesPerSample)));
            var audio = null;
            if (ctx && frameCount > 0) {
              audio = ctx.createBuffer(channels, frameCount, frequency);
              if (bits === 16) {
                                const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
                                for (var channel = 0; channel < channels; channel++) {
                                    var out = audio.getChannelData(channel);
                                    for (var frame = 0; frame < frameCount; frame++) {
                                        var offset = (frame * channels + channel) * 2;
                    out[frame] = Math.max(-1, Math.min(1, view.getInt16(offset, true) / 32768));
                  }
                }
              } else {
                                for (var channel = 0; channel < channels; channel++) {
                                    var out = audio.getChannelData(channel);
                                    for (var frame = 0; frame < frameCount; frame++) {
                    out[frame] = ((bytes[frame * channels + channel] || 0) - 128) / 128;
                  }
                }
              }
            }
                        state.buffers.set(buffer|0, {
                            format: format,
                            frequency: frequency,
                            size: bytes.length,
                            channels: channels,
                            bits: bits,
                            audio: audio
                        });
            state.stats.buffers = state.buffers.size;
            state.stats.bufferUploads++;
            state.stats.lastFormat = format;
            state.stats.lastFrequency = frequency;
            state.stats.lastUploadBytes = bytes.length;
            if (state.context) state.stats.contextState = state.context.state || 'unknown';
            """)
    private static native void bufferDataLinearJs(
            int buffer, int format, int address, int length, int frequency);

    public static int getBufferi(int buffer, int parameter) {
        return getBufferiJs(buffer, parameter);
    }

    public static void getBufferi(int buffer, int parameter, IntBuffer values) {
        if (values != null && values.hasRemaining()) {
            values.duplicate().put(getBufferi(buffer, parameter));
        }
    }

    public static void getBufferi(int buffer, int parameter, int[] values) {
        if (values != null && values.length > 0) {
            values[0] = getBufferi(buffer, parameter);
        }
    }

    @JSBody(params = {"buffer", "parameter"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return 0;
            const entry = state.buffers.get(buffer|0);
            if (!entry) return 0;
            parameter = parameter|0;
            if (parameter === 0x2001) return entry.frequency|0;
            if (parameter === 0x2002) return entry.bits|0;
            if (parameter === 0x2003) return entry.channels|0;
            if (parameter === 0x2004) return entry.size|0;
            return 0;
            """)
    private static native int getBufferiJs(int buffer, int parameter);

    public static void listenerf(int parameter, float value) {
        init();
        listenerfJs(parameter, value);
    }

    @JSBody(params = {"parameter", "value"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            if ((parameter|0) === 0x100A) {
              state.listener.gain = Math.max(0, Number(value) || 0);
              state.applyListener();
            }
            """)
    private static native void listenerfJs(int parameter, float value);

    public static void listeneri(int parameter, int value) {
        listenerf(parameter, value);
    }

    public static void listener3f(int parameter, float x, float y, float z) {
        init();
        listener3fJs(parameter, x, y, z);
    }

    @JSBody(params = {"parameter", "x", "y", "z"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            if ((parameter|0) === 0x1004) {
              state.setVector(state.listener.position, x, y, z);
            } else if ((parameter|0) === 0x1006) {
              state.setVector(state.listener.velocity, x, y, z);
            }
            state.applyListener();
            """)
    private static native void listener3fJs(int parameter, float x, float y, float z);

    public static void listenerfv(int parameter, FloatBuffer values) {
        if (values == null) return;
        FloatBuffer copy = values.duplicate();
        if ((parameter == AL_ORIENTATION && copy.remaining() >= 6)
                || (parameter != AL_ORIENTATION && copy.remaining() >= 3)) {
            if (parameter == AL_ORIENTATION) {
                listenerOrientation(copy.get(), copy.get(), copy.get(), copy.get(), copy.get(), copy.get());
            } else {
                listener3f(parameter, copy.get(), copy.get(), copy.get());
            }
        }
    }

    public static void listenerfv(int parameter, float[] values) {
        if (values == null) return;
        if (parameter == AL_ORIENTATION && values.length >= 6) {
            listenerOrientation(values[0], values[1], values[2], values[3], values[4], values[5]);
        } else if (values.length >= 3) {
            listener3f(parameter, values[0], values[1], values[2]);
        }
    }

    public static void listenerOrientation(
            float forwardX, float forwardY, float forwardZ,
            float upX, float upY, float upZ) {
        init();
        listenerOrientationJs(forwardX, forwardY, forwardZ, upX, upY, upZ);
    }

    @JSBody(params = {"forwardX", "forwardY", "forwardZ", "upX", "upY", "upZ"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            state.setVector(
              state.listener.forward, forwardX, forwardY, forwardZ, 0, 0, -1);
            state.setVector(state.listener.up, upX, upY, upZ, 0, 1, 0);
            state.applyListener();
            """)
    private static native void listenerOrientationJs(
            float forwardX, float forwardY, float forwardZ,
            float upX, float upY, float upZ);

    /**
     * The listener transform of one Minecraft Listener.setTransform: the same vectors and Web
     * Audio parameters as {@code listener3f(AL_POSITION, ...)} followed by
     * {@code listenerOrientation(...)}, with one JavaScript call and one applyListener. The
     * orientation an intermediate applyListener would write is the one already applied, which
     * setParam skips, so the AudioParam writes are the same.
     */
    public static void listenerTransform(
            float positionX, float positionY, float positionZ,
            float forwardX, float forwardY, float forwardZ,
            float upX, float upY, float upZ) {
        init();
        listenerTransformJs(positionX, positionY, positionZ,
                forwardX, forwardY, forwardZ, upX, upY, upZ);
    }

    @JSBody(params = {"positionX", "positionY", "positionZ",
            "forwardX", "forwardY", "forwardZ", "upX", "upY", "upZ"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            state.setVector(state.listener.position, positionX, positionY, positionZ);
            state.setVector(
              state.listener.forward, forwardX, forwardY, forwardZ, 0, 0, -1);
            state.setVector(state.listener.up, upX, upY, upZ, 0, 1, 0);
            state.applyListener();
            """)
    private static native void listenerTransformJs(
            float positionX, float positionY, float positionZ,
            float forwardX, float forwardY, float forwardZ,
            float upX, float upY, float upZ);

    public static void distanceModel(int model) {
        init();
        distanceModelJs(model);
    }

    @JSBody(params = {"model"}, script = """
            const state = window.__gaiusOpenAL;
            if (!state) return;
            state.distanceModel = state.validDistanceModel(model);
            state.sources.forEach(function(source) { state.applyPanner(source); });
            """)
    private static native void distanceModelJs(int model);

    public static void dopplerFactor(float value) {
    }

    public static void dopplerVelocity(float value) {
    }

    public static void speedOfSound(float value) {
    }

    public static boolean isExtensionPresent(CharSequence extension) {
        return extension != null && (AL_EXTENSIONS.contains(extension.toString())
                || ALC_EXTENSIONS.contains(extension.toString()));
    }

    public static int getEnumValue(CharSequence name) {
        return 0;
    }

    public static long getProcAddress(CharSequence name) {
        return FUNCTION_ADDRESS;
    }

    public static void bufferf(int buffer, int parameter, float value) {
    }

    public static void buffer3f(int buffer, int parameter, float x, float y, float z) {
    }

    public static void bufferi(int buffer, int parameter, int value) {
    }

    public static void buffer3i(int buffer, int parameter, int x, int y, int z) {
    }

    public static void bufferfv(int buffer, int parameter, FloatBuffer values) {
    }

    public static void bufferfv(int buffer, int parameter, float[] values) {
    }

    public static void bufferiv(int buffer, int parameter, IntBuffer values) {
    }

    public static void bufferiv(int buffer, int parameter, int[] values) {
    }

    private static Int8Array bytes(ByteBuffer buffer) {
        if (buffer == null) {
            return JsArrays.int8(new byte[0]);
        }
        ByteBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return JsArrays.int8(data);
    }

    private static Int8Array shorts(ShortBuffer buffer) {
        if (buffer == null) {
            return JsArrays.int8(new byte[0]);
        }
        ShortBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining() * 2];
        int offset = 0;
        while (copy.hasRemaining()) {
            short value = copy.get();
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
        }
        return JsArrays.int8(data);
    }

    private static Int8Array shorts(short[] values) {
        if (values == null) {
            return JsArrays.int8(new byte[0]);
        }
        byte[] data = new byte[values.length * 2];
        int offset = 0;
        for (short value : values) {
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
        }
        return JsArrays.int8(data);
    }

    private static Int8Array ints(IntBuffer buffer) {
        if (buffer == null) {
            return JsArrays.int8(new byte[0]);
        }
        IntBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining() * 4];
        int offset = 0;
        while (copy.hasRemaining()) {
            int value = copy.get();
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
            data[offset++] = (byte) (value >>> 16);
            data[offset++] = (byte) (value >>> 24);
        }
        return JsArrays.int8(data);
    }

    private static Int8Array ints(int[] values) {
        if (values == null) {
            return JsArrays.int8(new byte[0]);
        }
        byte[] data = new byte[values.length * 4];
        int offset = 0;
        for (int value : values) {
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
            data[offset++] = (byte) (value >>> 16);
            data[offset++] = (byte) (value >>> 24);
        }
        return JsArrays.int8(data);
    }

    private static Int8Array floats(FloatBuffer buffer) {
        if (buffer == null) {
            return JsArrays.int8(new byte[0]);
        }
        FloatBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining() * 4];
        int offset = 0;
        while (copy.hasRemaining()) {
            int value = Float.floatToRawIntBits(copy.get());
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
            data[offset++] = (byte) (value >>> 16);
            data[offset++] = (byte) (value >>> 24);
        }
        return JsArrays.int8(data);
    }

    private static Int8Array floats(float[] values) {
        if (values == null) {
            return JsArrays.int8(new byte[0]);
        }
        byte[] data = new byte[values.length * 4];
        int offset = 0;
        for (float number : values) {
            int value = Float.floatToRawIntBits(number);
            data[offset++] = (byte) value;
            data[offset++] = (byte) (value >>> 8);
            data[offset++] = (byte) (value >>> 16);
            data[offset++] = (byte) (value >>> 24);
        }
        return JsArrays.int8(data);
    }
}
