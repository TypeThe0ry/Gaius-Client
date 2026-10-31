package dev.gaius.browser;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.WorldStem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.teavm.jso.JSBody;

/** Launches the official Minecraft server in a Web Worker for browser singleplayer. */
public final class BrowserSingleplayerClient {
    private static final int LOCAL_SERVER_PORT = 25565;
    private static final int READY_POLL_MILLIS = 25;
    /** Java-side safety bound; the JS watchdog normally fails earlier on inactivity. */
    private static final int READY_POLL_LIMIT = 7_200;
    /** Returned by the JS readiness probe when its poll belongs to an older launch. */
    private static final int STALE_WORKER_STATE = -2;
    private static String activeSessionId;

    private BrowserSingleplayerClient() {
    }

    /**
     * Starts the world in the integrated server Worker and always returns true: vanilla's
     * in-page integrated server cannot run here (its client connection is a netty LocalAddress,
     * which the browser channel does not carry), so a Worker that cannot start ends on an error
     * screen instead of falling back to it.
     */
    public static boolean open(
            Minecraft minecraft,
            LevelStorageSource.LevelStorageAccess storage,
            WorldStem worldStem,
            boolean newWorld) {
        return open(minecraft, storage, worldStem, Optional.empty(), newWorld);
    }

    /**
     * As {@link #open(Minecraft, LevelStorageSource.LevelStorageAccess, WorldStem, boolean)} for
     * the profiles whose {@code Minecraft.doWorldLoad} also receives the game rules chosen on the
     * Create World screen. The world data reaches the Worker through the saved level, but those
     * rules are a separate argument of vanilla's integrated server, so they travel in the start
     * message instead (see {@link BrowserGameRulesHandoff}).
     */
    public static boolean open(
            Minecraft minecraft,
            LevelStorageSource.LevelStorageAccess storage,
            WorldStem worldStem,
            Optional<GameRules> initialGameRules,
            boolean newWorld) {
        if (!isSupported()) {
            return failLaunch(minecraft, storage, worldStem,
                    "This browser cannot start the singleplayer server (it needs Web Workers and MessageChannel)");
        }
        if (!storageConfigurationValid()) {
            return failLaunch(minecraft, storage, worldStem,
                    BrowserI18n.string("singleplayer.storage_mismatch"));
        }

        String encodedGameRules;
        try {
            encodedGameRules = BrowserGameRulesHandoff.encode(initialGameRules);
        } catch (RuntimeException exception) {
            return failLaunch(minecraft, storage, worldStem,
                    BrowserI18n.string("singleplayer.rules_failed"));
        }

        String worldId = storage.getLevelId();
        long worldSeed = BrowserWorldLoaderCompat.seed(worldStem);
        worldStem.gaius$saveDataTag(storage);
        int renderDistance = minecraft.options.renderDistance().get();
        int simulationDistance = minecraft.options.simulationDistance().get();
        String sessionId = launchWorker(
                worldId,
                worldSeed,
                newWorld,
                renderDistance,
                simulationDistance,
                encodedGameRules);
        String launchGeneration = sessionId == null || sessionId.isEmpty()
                ? ""
                : localWorkerGeneration(sessionId);
        if (sessionId == null || sessionId.isEmpty() ||
                launchGeneration == null || launchGeneration.isEmpty()) {
            String detail = lastLaunchError();
            return failLaunch(minecraft, storage, worldStem, detail == null || detail.isEmpty()
                    ? BrowserI18n.string("singleplayer.worker_failed")
                    : detail);
        }
        activeSessionId = sessionId;

        worldStem.close();
        storage.safeClose();
        beginClientHandoff(sessionId, launchGeneration);
        try {
            minecraft.disconnectWithProgressScreen();
        } catch (RuntimeException | Error throwable) {
            cancelClientHandoff(sessionId, launchGeneration);
            requestWorkerStop(sessionId, launchGeneration);
            throw throwable;
        }

        connectWhenWorkerReady(minecraft, sessionId, launchGeneration, 0);
        return true;
    }

    private static boolean failLaunch(
            Minecraft minecraft,
            LevelStorageSource.LevelStorageAccess storage,
            WorldStem worldStem,
            String detail) {
        try {
            worldStem.close();
        } finally {
            storage.safeClose();
        }
        minecraft.gaius$setScreen(new DisconnectedScreen(
                new TitleScreen(),
                BrowserI18n.text("singleplayer.start_failed"),
                Component.literal(detail)));
        return true;
    }

    /** The detail of the last singleplayer:worker-launch-error event, or an empty string. */
    @JSBody(script = """
            const events = globalThis.__gaiusMinecraftEvents;
            if (!Array.isArray(events)) return '';
            for (let index = events.length - 1; index >= 0; index--) {
              const entry = events[index];
              if (entry && entry.event === 'singleplayer:worker-launch-error') {
                return String(entry.detail || '').split('\\n')[0].slice(0, 300);
              }
            }
            return '';
            """)
    private static native String lastLaunchError();

    private static void connectWhenWorkerReady(
            Minecraft minecraft,
            String sessionId,
            String launchGeneration,
            int pollCount) {
        int state = localWorkerState(sessionId, launchGeneration);
        if (state == STALE_WORKER_STATE) {
            // A delayed poll from a previous launch must not change the screen
            // or touch the replacement Worker that reused this session key.
            return;
        }
        if (state > 0) {
            String host = "client-" + sessionId + ".gaius-local";
            ServerData serverData = new ServerData(
                    "Singleplayer",
                    host + ":" + LOCAL_SERVER_PORT,
                    ServerData.Type.LAN);
            ConnectScreen.startConnecting(
                    new TitleScreen(),
                    minecraft,
                    new ServerAddress(host, LOCAL_SERVER_PORT),
                    serverData,
                    false,
                    null);
            return;
        }
        if (state < 0 || pollCount >= READY_POLL_LIMIT) {
            String detail = state < 0
                    ? BrowserI18n.string("singleplayer.stopped")
                    : BrowserI18n.string("singleplayer.timeout");
            String effectiveDetail = reportAttachFailure(sessionId, launchGeneration, detail);
            // This is an attach failure, not a normal disconnect.  Clear this
            // handoff and stop only the Worker that belongs to this poller;
            // the regular no-argument stop path deliberately protects a
            // pending handoff from a delayed disconnect.
            cancelClientHandoff(sessionId, launchGeneration);
            requestWorkerStop(sessionId, launchGeneration);
            minecraft.gaius$setScreen(new DisconnectedScreen(
                    new TitleScreen(),
                    BrowserI18n.text("singleplayer.start_failed"),
                    Component.literal(effectiveDetail == null || effectiveDetail.isEmpty()
                            ? detail
                            : effectiveDetail)));
            return;
        }
        HostLoop.schedule(
                () -> HostLoop.startThread(
                        () -> connectWhenWorkerReady(
                                minecraft, sessionId, launchGeneration, pollCount + 1)),
                READY_POLL_MILLIS);
    }

    public static void stop() {
        requestWorkerStop();
    }

    /** Starts the browser-level quit barrier used by the title screen's Quit Game action. */
    public static void quit() {
        requestBrowserQuit();
    }

    @JSBody(script = """
            const quit = globalThis.__gaiusRequestQuit;
            if (typeof quit === 'function') {
              try { quit(); } catch (ignored) {}
            }
            """)
    private static native void requestBrowserQuit();

    /** Treats an active Worker-hosted world like vanilla singleplayer in menu navigation. */
    public static boolean isLocalSession(Minecraft minecraft) {
        return minecraft != null && (minecraft.isLocalServer() || hasActiveWorker());
    }

    /** True only while the browser integrated-server Worker owns a live session. */
    public static boolean hasActiveWorkerSession() {
        return hasReadyWorker();
    }

    /** Stable session key used by the relay-backed Open to LAN broker. */
    public static String activeWorkerSessionId() {
        return activeSessionId;
    }

    /** Requests the live server Worker to open one independent LAN channel. */
    @JSBody(params = {"brokerSessionId", "workerSessionId"}, script = """
            const key = String(brokerSessionId || '');
            const workerKey = String(workerSessionId || '');
            if (!/^[a-f0-9]{32}$/.test(key)) return false;
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(workerKey)
              : null;
            if (!worker || worker.__gaiusTerminal ||
                !worker.__gaiusServerReady || !worker.__gaiusClientAttached ||
                typeof worker.postMessage !== 'function') return false;
            const descriptor = globalThis.__gaiusSkinDescriptor;
            worker.postMessage({
              type: 'lan-open',
              brokerSessionId: key,
              lanRelay: globalThis.__gaiusRelays &&
                typeof globalThis.__gaiusRelays.lanInviteRelay === 'function'
                ? String(globalThis.__gaiusRelays.lanInviteRelay() || '').trim()
                : '',
              skinDescriptor: descriptor && typeof descriptor === 'object'
                ? {
                    uuid: String(descriptor.uuid || ''),
                    username: String(descriptor.username || ''),
                    value: String(descriptor.value || ''),
                    signature: String(descriptor.signature || '')
                  }
                : null
            });
            return true;
            """)
    private static native boolean postLanServerConnectionRequest(
            String brokerSessionId, String workerSessionId);

    public static boolean requestLanServerConnection(String brokerSessionId) {
        return hasReadyWorker() && activeSessionId != null
                && postLanServerConnectionRequest(brokerSessionId, activeSessionId);
    }

    /** Applies changed video settings to an active Worker-hosted singleplayer server. */
    public static void syncDistances(Minecraft minecraft) {
        if (minecraft == null || minecraft.options == null) {
            return;
        }
        postWorkerDistances(
                minecraft.options.renderDistance().get(),
                minecraft.options.simulationDistance().get());
    }

    @JSBody(script = """
            return typeof Worker === 'function' && typeof MessageChannel === 'function';
            """)
    private static native boolean isSupported();

    // The storage allow-list rows of this script are generated from port/versions/*.json by
    // port/tools/profilegen (node port/tools/profilegen/profilegen.mjs); edit the profiles instead.
    @JSBody(script = """
            try {
              const profile=String(globalThis.__gaiusProfileId || '').trim();
              const world=Number(globalThis.__gaiusWorldVersion);
              const schema=Number(globalThis.__gaiusStorageSchema);
              const database=String(globalThis.__gaiusStorageDatabaseName || '').trim();
              const prefix=String(globalThis.__gaiusStoragePrefix || '');
              const opfs=String(globalThis.__gaiusStorageOpfsDirectory || '').trim();
              return (profile==='1.21.11' && world===4671 && schema===2
                && database==='gaius-fs-v2-1.21.11'
                && prefix==='gaius.fs.v2:1.21.11:'
                && opfs==='regions-v2-1.21.11') ||
                (profile==='26.2' && world===4903 && schema===2
                && database==='gaius-fs-v2-26.2'
                && prefix==='gaius.fs.v2:26.2:'
                && opfs==='regions-v2-26.2') ||
                (profile==='26.3' && world===5023 && schema===2
                && database==='gaius-fs-v2-26.3'
                && prefix==='gaius.fs.v2:26.3:'
                && opfs==='regions-v2-26.3');
            } catch (e) {
              return false;
            }
            """)
    private static native boolean storageConfigurationValid();

    @JSBody(params = {"sessionId"}, script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(String(sessionId))
              : null;
            const generation = worker && worker.__gaiusLaunchGeneration
              ? String(worker.__gaiusLaunchGeneration)
              : '';
            return /^[1-9][0-9]*$/.test(generation) ? generation : '';
            """)
    private static native String localWorkerGeneration(String sessionId);

    @JSBody(params = {"sessionId", "launchGeneration"}, script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const key = String(sessionId || '');
            const expectedGeneration = String(launchGeneration || '');
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(key)
              : null;
            if (!key || !/^[1-9][0-9]*$/.test(expectedGeneration) ||
                !worker || worker.__gaiusTerminal) return -1;
            if (String(worker.__gaiusLaunchGeneration || '') !== expectedGeneration) {
              return -2;
            }
            if (worker.__gaiusClientAttached) return 1;
            const ports = globalThis.__gaiusLocalServerPorts ||
              (globalThis.__gaiusLocalServerPorts = new Map());
            const ownedPort = worker.__gaiusClientPort || null;
            if (ownedPort && ports.get(key) !== ownedPort) {
              ports.set(key, ownedPort);
            }
            const mappedPort = ports.get(key);
            return worker.__gaiusServerReady && mappedPort &&
              String(mappedPort.__gaiusLaunchGeneration || '') === expectedGeneration
              ? 1
              : 0;
            """)
    private static native int localWorkerState(String sessionId, String launchGeneration);

    @JSBody(params = {"sessionId", "launchGeneration", "detail"}, script = """
            const key = String(sessionId || '');
            const expectedGeneration = String(launchGeneration || '');
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(key)
              : null;
            const failures = globalThis.__gaiusSingleplayerFailures;
            const failureKey = key + ':' + expectedGeneration;
            const remembered = failures && typeof failures.get === 'function'
              ? failures.get(failureKey)
              : null;
            if (!key || !expectedGeneration ||
                (worker && String(worker.__gaiusLaunchGeneration || '') !== expectedGeneration) ||
                (!worker && !remembered)) {
              return;
            }
            const events = globalThis.__gaiusMinecraftEvents ||
              (globalThis.__gaiusMinecraftEvents = []);
            events.push({
              event: 'singleplayer:client-attach-failed',
              detail: key + ': ' + String(
                remembered && remembered.detail ? remembered.detail : detail),
              at: Date.now()
            });
            if (events.length > 500) events.splice(0, events.length - 500);
            // DisconnectedScreen shows this text: keep the reason (an Error's first line), not
            // its stack.
            return String(remembered && remembered.detail ? remembered.detail : detail)
              .replace(/^[A-Za-z]*Error: /, '').split('\\n')[0].slice(0, 400);
            """)
    private static native String reportAttachFailure(
            String sessionId,
            String launchGeneration,
            String detail);

    // The storage allow-list rows of this script are generated from port/versions/*.json by
    // port/tools/profilegen (node port/tools/profilegen/profilegen.mjs); edit the profiles instead.
    @JSBody(params = {"worldId", "worldSeed", "newWorld", "renderDistance", "simulationDistance", "initialGameRules"}, script = """
            let sessionId = '';
            let launchGeneration = '';
            let channel = null;
            let ports = null;
            let workers = null;
            let worker = null;
            let port2Transferred = false;
            const closePort = function(port) {
              if (!port) return;
              try { port.close(); } catch (ignored) {}
            };
            const bindPortGeneration = function(port) {
              if (!port || !/^[1-9][0-9]*$/.test(launchGeneration)) return false;
              try { port.__gaiusLaunchGeneration = launchGeneration; } catch (ignored) {}
              return String(port.__gaiusLaunchGeneration || '') === launchGeneration;
            };
            const handoffMatches = function(expectedSession, expectedGeneration) {
              const handoff = globalThis.__gaiusSingleplayerHandoff;
              if (handoff && typeof handoff === 'object') {
                return String(handoff.sessionId || '') === String(expectedSession || '') &&
                  String(handoff.generation || '') === String(expectedGeneration || '');
              }
              return String(handoff || '') === String(expectedSession || '') &&
                String(globalThis.__gaiusSingleplayerHandoffGeneration || '') ===
                  String(expectedGeneration || '');
            };
            const ownsLaunch = function() {
              return workers && sessionId && worker && workers.get(sessionId) === worker &&
                String(worker.__gaiusLaunchGeneration || '') === launchGeneration;
            };
            const clearHandoffLease = function() {
              if (!ownsLaunch() || !sessionId || !launchGeneration ||
                  !handoffMatches(sessionId, launchGeneration)) {
                return;
              }
              globalThis.__gaiusSingleplayerHandoff = '';
              globalThis.__gaiusSingleplayerHandoffGeneration = '';
            };
            const rollbackLaunch = function(detail) {
              clearHandoffLease();
              if (worker) {
                worker.__gaiusStorageClosed = false;
                worker.__gaiusTerminal = true;
                if (worker.__gaiusHandoffTimeout) {
                  clearTimeout(worker.__gaiusHandoffTimeout);
                  worker.__gaiusHandoffTimeout = 0;
                }
                if (worker.__gaiusStopTimeout) {
                  clearTimeout(worker.__gaiusStopTimeout);
                  worker.__gaiusStopTimeout = 0;
                }
                if (worker.__gaiusTelemetryTimer) {
                  clearInterval(worker.__gaiusTelemetryTimer);
                  worker.__gaiusTelemetryTimer = 0;
                }
                try { worker.terminate(); } catch (ignored) {}
              }
              if (ports && sessionId && channel && (!worker || ownsLaunch()) &&
                  ports.get(sessionId) === channel.port1) {
                ports.delete(sessionId);
              }
              if (workers && sessionId && worker && workers.get(sessionId) === worker &&
                  String(worker.__gaiusLaunchGeneration || '') === launchGeneration) {
                workers.delete(sessionId);
              }
              if (channel) {
                closePort(channel.port1);
                if (!port2Transferred) closePort(channel.port2);
              }
              const bridge = globalThis.__gaiusNettyBridge;
              if (workers && sessionId && worker && workers.get(sessionId) === worker &&
                  String(worker.__gaiusLaunchGeneration || '') === launchGeneration &&
                  bridge && typeof bridge.failLocalSession === 'function') {
                bridge.failLocalSession(sessionId, detail, launchGeneration);
              }
            };
            // The Worker script of a page opened from disk: it keeps every message until
            // {type: 'gaius-worker-bootstrap', source, files} arrives, imports the bootstrap
            // from a Blob of its own, publishes the files ({name: ArrayBuffer}) as
            // self.__gaiusWorkerFiles for the bootstrap, and then hands the kept messages over.
            const FILE_WORKER_LOADER = '(' + function() {
              var kept = [];
              self.onmessage = function(event) {
                var data = event.data;
                if (!data || data.type !== 'gaius-worker-bootstrap') {
                  kept.push(event);
                  return;
                }
                self.onmessage = null;
                self.__gaiusWorkerFiles = data.files || {};
                var url = URL.createObjectURL(new Blob([String(data.source || '')],
                  {type: 'text/javascript'}));
                try {
                  importScripts(url);
                } catch (error) {
                  self.postMessage({
                    type: 'bootstrap-crash',
                    detail: String(error && (error.stack || error.message) || error)
                  });
                  return;
                } finally {
                  URL.revokeObjectURL(url);
                }
                for (var i = 0; i < kept.length; i++) {
                  self.dispatchEvent(new MessageEvent('message', {
                    data: kept[i].data,
                    ports: kept[i].ports ? Array.prototype.slice.call(kept[i].ports) : []
                  }));
                }
                kept = null;
              };
            }.toString() + ')();';
            // The files the integrated server Worker reads, from the page's file payloads:
            // the bootstrap, the server of the selected runtime (gaius-boot.js serverRuntime)
            // with its runtime scripts, and the worldgen kernel scripts when the page has them.
            const loadFileWorkerFiles = function() {
              const boot = globalThis.__gaiusBoot;
              if (!boot || typeof boot.filePayload !== 'function' ||
                  typeof boot.hasFilePayload !== 'function') {
                return Promise.reject(new Error('this page has no file payloads'));
              }
              const runtimes = globalThis.__gaiusRuntimes;
              const wasmServer = boot.serverRuntime === 'wasm';
              const bootstrapName = 'singleplayer-server-worker.js';
              if (!wasmServer) {
                return Promise.reject(new Error('the WebAssembly GC integrated server is unavailable'));
              }
              const required = [bootstrapName];
              required.push(String(runtimes && runtimes.wasm && runtimes.wasm.server ||
                'singleplayer-server.wasm'), 'gaius-wasm-gc-runtime.js', 'gaius-jspi.js',
                'gaius-wasm-worker.js');
              const missing = required.filter(function(name) {
                return !boot.hasFilePayload(name);
              });
              if (missing.length) {
                return Promise.reject(new Error('no file payload for ' + missing.join(', ')));
              }
              const names = required.concat(['kernels/worldgen-job.js', 'kernels/worldgen-kernel.js']
                .filter(function(name) { return boot.hasFilePayload(name); }));
              return Promise.all(names.map(function(name) {
                return boot.filePayload(name);
              })).then(function(contents) {
                const bundle = {source: '', files: {}, transfer: []};
                for (let index = 0; index < names.length; index++) {
                  const bytes = contents[index];
                  if (names[index] === bootstrapName) {
                    bundle.source = new TextDecoder().decode(bytes);
                    continue;
                  }
                  // filePayload hands out bytes this page owns: they can move to the Worker.
                  const buffer = bytes.byteOffset === 0 &&
                    bytes.byteLength === bytes.buffer.byteLength
                    ? bytes.buffer
                    : bytes.slice().buffer;
                  bundle.files[names[index]] = buffer;
                  bundle.transfer.push(buffer);
                }
                return bundle;
              });
            };
            try {
              const profileId = String(globalThis.__gaiusProfileId || '').trim();
              const worldVersion = Number(globalThis.__gaiusWorldVersion);
              const storageSchema = Number(globalThis.__gaiusStorageSchema);
              const storageDatabaseName = String(
                globalThis.__gaiusStorageDatabaseName || ''
              ).trim();
              const storagePrefix = String(globalThis.__gaiusStoragePrefix || '');
              const storageOpfsDirectory = String(
                globalThis.__gaiusStorageOpfsDirectory || ''
              ).trim();
              const storageMatchesProfile =
                (profileId === '1.21.11' && worldVersion === 4671 &&
                  storageSchema === 2 &&
                  storageDatabaseName === 'gaius-fs-v2-1.21.11' &&
                  storagePrefix === 'gaius.fs.v2:1.21.11:' &&
                  storageOpfsDirectory === 'regions-v2-1.21.11') ||
                (profileId === '26.2' && worldVersion === 4903 &&
                  storageSchema === 2 &&
                  storageDatabaseName === 'gaius-fs-v2-26.2' &&
                  storagePrefix === 'gaius.fs.v2:26.2:' &&
                  storageOpfsDirectory === 'regions-v2-26.2') ||
                (profileId === '26.3' && worldVersion === 5023 &&
                  storageSchema === 2 &&
                  storageDatabaseName === 'gaius-fs-v2-26.3' &&
                  storagePrefix === 'gaius.fs.v2:26.3:' &&
                  storageOpfsDirectory === 'regions-v2-26.3');
              if (!storageMatchesProfile) {
                throw new Error('Singleplayer storage configuration does not match profile');
              }
              const bytes = new Uint8Array(16);
              crypto.getRandomValues(bytes);
              for (let i = 0; i < bytes.length; i++) {
                sessionId += bytes[i].toString(16).padStart(2, '0');
              }
              const previousGeneration = Number(
                globalThis.__gaiusSingleplayerLaunchGeneration
              );
              const nextGeneration = Number.isSafeInteger(previousGeneration) &&
                previousGeneration >= 0 && previousGeneration < Number.MAX_SAFE_INTEGER
                ? previousGeneration + 1
                : 1;
              launchGeneration = String(nextGeneration);
              globalThis.__gaiusSingleplayerLaunchGeneration = nextGeneration;
              if (!/^[1-9][0-9]*$/.test(launchGeneration)) {
                throw new Error('Singleplayer launch generation is invalid');
              }

              channel = new MessageChannel();
              if (!bindPortGeneration(channel.port1) || !bindPortGeneration(channel.port2)) {
                throw new Error('Singleplayer MessagePort generation binding failed');
              }
              ports = globalThis.__gaiusLocalServerPorts ||
                (globalThis.__gaiusLocalServerPorts = new Map());
              ports.set(sessionId, channel.port1);

              workers = globalThis.__gaiusSingleplayerWorkers ||
                (globalThis.__gaiusSingleplayerWorkers = new Map());
              const previousWorkers = Array.from(workers.entries());

              const buildToken = globalThis.__gaiusBootTimings &&
                globalThis.__gaiusBootTimings.buildToken
                ? String(globalThis.__gaiusBootTimings.buildToken)
                : 'dev';
              const resolvedWorkerUrl = new URL(
                'singleplayer-server-worker.js?v=' + encodeURIComponent(buildToken),
                location.href
              );
              let workerUrl = globalThis.__gaiusSingleplayerWorkerUrl ||
                resolvedWorkerUrl;
              try {
                const pageUrl = new URL(location.href);
                if (pageUrl.searchParams.get('gaiusMobAiTelemetry') === '1' ||
                    pageUrl.searchParams.get('gaiusServerTickTelemetry') === '1') {
                  if (!globalThis.__gaiusSingleplayerWorkerUrl) {
                    if (pageUrl.searchParams.get('gaiusMobAiTelemetry') === '1') {
                      resolvedWorkerUrl.searchParams.set('gaiusMobAiTelemetry', '1');
                    }
                    if (pageUrl.searchParams.get('gaiusServerTickTelemetry') === '1') {
                      resolvedWorkerUrl.searchParams.set('gaiusServerTickTelemetry', '1');
                    }
                    workerUrl = resolvedWorkerUrl;
                  } else {
                    const configuredWorkerUrl = new URL(
                      String(workerUrl),
                      location.href
                    );
                    // Blob URLs are opaque object handles; adding a query to
                    // one would create a different, invalid object URL.
                    if (configuredWorkerUrl.protocol !== 'blob:') {
                      if (pageUrl.searchParams.get('gaiusMobAiTelemetry') === '1') {
                        configuredWorkerUrl.searchParams.set('gaiusMobAiTelemetry', '1');
                      }
                      if (pageUrl.searchParams.get('gaiusServerTickTelemetry') === '1') {
                        configuredWorkerUrl.searchParams.set('gaiusServerTickTelemetry', '1');
                      }
                      workerUrl = configuredWorkerUrl;
                    }
                  }
                }
              } catch (ignored) {}
              // A page opened from disk cannot start a Worker from a sibling file, and that
              // Worker could not read its siblings either. It starts from a blob loader that
              // waits for the bootstrap and the files the server needs; the page decodes them
              // from its file payloads (gaius-boot.js filePayload) and posts them before start.
              let fileWorkerFiles = null;
              // The title screen creates one idle Worker so its bootstrap/runtime can settle
              // before the user clicks Create World.  Consume that exact instance here; all
              // launch state and MessagePort wiring below remains per-world.  __gaiusAdopted
              // hands ownership over: the idle branch in gaius-boot.js re-checks it after its
              // payload await and never terminates an adopted Worker.  A portable idle Worker
              // already receives its bootstrap from that branch, so the start path awaits that
              // delivery instead of decoding and transferring the payload a second time.
              const idleWorker = globalThis.__gaiusIdleSingleplayerWorker;
              let idleBootstrapDelivery = null;
              if (idleWorker && idleWorker.__gaiusIdle === true &&
                  !idleWorker.__gaiusTerminal && idleWorker.__gaiusAdopted !== true) {
                worker = idleWorker;
                globalThis.__gaiusIdleSingleplayerWorker = null;
                worker.__gaiusIdle = false;
                worker.__gaiusAdopted = true;
                if (worker.__gaiusIdleTimeout) {
                  clearTimeout(worker.__gaiusIdleTimeout);
                  worker.__gaiusIdleTimeout = 0;
                }
                idleBootstrapDelivery = worker.__gaiusBootstrapDelivery || null;
              } else {
                if (location.protocol === 'file:' && String(workerUrl).indexOf('blob:') !== 0) {
                  fileWorkerFiles = loadFileWorkerFiles();
                  fileWorkerFiles.catch(function() {});
                  workerUrl = URL.createObjectURL(new Blob([FILE_WORKER_LOADER],
                    {type: 'text/javascript'}));
                  const loaderUrl = workerUrl;
                  setTimeout(function() { URL.revokeObjectURL(loaderUrl); }, 60000);
                }
                worker = new Worker(workerUrl, {name: 'Gaius Integrated Server'});
              }
              try {
                const diagnosticUrl = new URL(location.href);
                if (diagnosticUrl.searchParams.get('diag') === '1' ||
                    diagnosticUrl.searchParams.get('gaiusDebug') === '1') {
                  worker.postMessage({type: 'diagnostic-config', gaiusDebug: true});
                }
                const structurePreloadIds = diagnosticUrl.searchParams
                  .getAll('gaiusStructurePreload').slice(0, 64);
                if (structurePreloadIds.length > 0) {
                  worker.postMessage({
                    type: 'diagnostic-config',
                    gaiusStructurePreloadIds: structurePreloadIds,
                    gaiusStructurePreloadBudgetMillis:
                      Number(diagnosticUrl.searchParams.get('gaiusStructurePreloadBudgetMillis')),
                  });
                }
                if (diagnosticUrl.searchParams.get('gaiusMobAiTelemetry') === '1' ||
                    diagnosticUrl.searchParams.get('gaiusServerTickTelemetry') === '1') {
                  if (diagnosticUrl.searchParams.get('gaiusMobAiTelemetry') === '1') {
                    worker.postMessage({
                      type: 'diagnostic-config',
                      gaiusMobAiTelemetry: true,
                      gaiusServerTickTelemetry:
                        diagnosticUrl.searchParams.get('gaiusServerTickTelemetry') === '1',
                    });
                  } else {
                    worker.postMessage({
                      type: 'diagnostic-config',
                      gaiusMobAiTelemetry: false,
                      gaiusServerTickTelemetry: true,
                    });
                  }
                }
              } catch (ignored) {}
              worker.__gaiusLaunchGeneration = launchGeneration;
              worker.__gaiusWorldId = String(worldId || '');
              worker.__gaiusHandoffPending = true;
              worker.__gaiusClientAttached = false;
              worker.__gaiusClientPort = channel.port1;
              worker.__gaiusRuntimeReady = false;
              worker.__gaiusServerReady = false;
              worker.__gaiusPrewarmReady = false;
              worker.__gaiusStopRequested = false;
              workers.set(sessionId, worker);
              const ownsSession = function() {
                return workers.get(sessionId) === worker &&
                  String(worker.__gaiusLaunchGeneration || '') === launchGeneration;
              };
              const ownsPendingPort = function() {
                return ownsSession() && ports.get(sessionId) === worker.__gaiusClientPort;
              };
              const copyScalarTelemetry = function(value) {
                const snapshot = {};
                if (!value || typeof value !== 'object') return snapshot;
                let copied = 0;
                const keys = Object.keys(value);
                for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                  if (copied >= 64) break;
                  const key = keys[keyIndex];
                  const current = value[key];
                  if (typeof current === 'number') {
                    if (Number.isFinite(current)) {
                      snapshot[key] = current;
                      copied++;
                    }
                  } else if (typeof current === 'boolean' ||
                      typeof current === 'string' || current === null) {
                    snapshot[key] = current;
                    copied++;
                  }
                }
                return snapshot;
              };
              // BrowserWebSocketChannel keeps the global pump aggregate in the
              // Worker heartbeat as a fixed side-band object. Do not route it
              // through the generic 64-key scalar copier: that cap is useful
              // for arbitrary stats but would silently drop pumpAll* fields.
              const globalPumpTelemetryKeys = [
                'pumpAllTurns',
                'pumpAllChannelsVisited',
                'pumpAllBudgetYields',
                'pumpAllMaxTurnMillis',
                'pumpAllMaxChannelsPerTurn',
                'pumpAllLastTurnMillis',
                'pumpAllLastChannelsVisited',
              ];
              const copyGlobalPumpTelemetry = function(value) {
                const snapshot = {};
                for (let keyIndex = 0; keyIndex < globalPumpTelemetryKeys.length; keyIndex++) {
                  const key = globalPumpTelemetryKeys[keyIndex];
                  const current = value && typeof value === 'object' ? value[key] : undefined;
                  snapshot[key] = typeof current === 'number' && Number.isFinite(current)
                    ? current
                    : null;
                }
                return snapshot;
              };
              // Keep the nested PlayerChunkSender state intact across the
              // Worker -> page heartbeat.  The generic scalar copier is
              // intentionally shallow and would otherwise drop `chunkSender`.
              const chunkSenderTelemetryKeys = [
                'telemetryVersion', 'pending', 'selected', 'unacknowledgedBatches',
                'playerChunkX', 'playerChunkZ', 'maxPending',
                'maxUnacknowledgedBatches', 'entryCount', 'selectedCount',
                'batchCount', 'readySelectedChunks',
              ];
              const copyChunkSenderTelemetry = function(value) {
                const snapshot = {};
                if (!value || typeof value !== 'object') return snapshot;
                for (let keyIndex = 0; keyIndex < chunkSenderTelemetryKeys.length; keyIndex++) {
                  const key = chunkSenderTelemetryKeys[keyIndex];
                  const current = value[key];
                  if (typeof current === 'number' && Number.isFinite(current)) {
                    snapshot[key] = current;
                  } else if (typeof current === 'boolean' ||
                      typeof current === 'string' || current === null) {
                    snapshot[key] = current;
                  }
                }
                if (value.last && typeof value.last === 'object') {
                  snapshot.last = copyChunkSenderTelemetry(value.last);
                }
                return snapshot;
              };
              const copyWorldgenTelemetry = function(value) {
                const snapshot = copyScalarTelemetry(value);
                if (value && typeof value === 'object' && value.chunkSender &&
                    typeof value.chunkSender === 'object') {
                  snapshot.chunkSender = copyChunkSenderTelemetry(value.chunkSender);
                }
                if (value && typeof value === 'object' && value.dispatcher &&
                    typeof value.dispatcher === 'object') {
                  snapshot.dispatcher = copyScalarTelemetry(value.dispatcher);
                }
                return snapshot;
              };
              worker.__gaiusTelemetryPending = new Map();
              worker.__gaiusTelemetrySequence = 0;
              worker.__gaiusTelemetrySent = 0;
              worker.__gaiusTelemetryReceived = 0;
              worker.__gaiusTelemetryMissed = 0;
              worker.__gaiusTelemetryErrors = 0;
              worker.__gaiusTelemetryTotalRtt = 0;
              worker.__gaiusTelemetryMaxRtt = 0;
              worker.__gaiusTelemetryLastRtt = 0;
              worker.__gaiusTelemetryMaxPageToWorker = 0;
              worker.__gaiusTelemetryMaxWorkerToPage = 0;
              worker.__gaiusTelemetryRttHistogram = new Uint32Array(512);
              worker.__gaiusTelemetryRttSamples = 0;
              worker.__gaiusTelemetrySchemaVersion = 2;
              worker.__gaiusTelemetryMeasurementId =
                typeof globalThis.__gaiusBenchmarkMeasurementId === 'string'
                  ? globalThis.__gaiusBenchmarkMeasurementId
                  : '';
              worker.__gaiusTelemetryResetAt = Date.now();
              worker.__gaiusTelemetryWorkerResetAt = 0;
              worker.__gaiusTelemetryMeasurementMismatches = 0;
              worker.__gaiusTelemetryLongestHeartbeatGap = 0;
              worker.__gaiusTelemetryLongestHeartbeatDelay = 0;
              worker.__gaiusTelemetryInterval = 1000;
              worker.__gaiusTelemetryLastPingAt = 0;
              worker.__gaiusTelemetryLastPongAt = 0;
              worker.__gaiusTelemetryChunkPriority = {};
              worker.__gaiusTelemetryNetwork = {};
              worker.__gaiusTelemetryGlobalPump = {};
              worker.__gaiusTelemetryWorldgen = {};
              worker.__gaiusTelemetryServerTick = {};
              worker.__gaiusTelemetryStorage = {};
              const telemetryPercentile = function(histogram, count, fraction) {
                if (!histogram || count <= 0) return 0;
                const target = Math.max(1, Math.ceil(count * fraction));
                let seen = 0;
                for (let index = 0; index < histogram.length; index++) {
                  seen += histogram[index];
                  if (seen >= target) return index + 1;
                }
                return histogram.length;
              };
              const publishWorkerTelemetry = function() {
                if (!ownsSession()) return;
                const state = globalThis.__gaiusWorkerMessageTelemetry ||
                  (globalThis.__gaiusWorkerMessageTelemetry = {});
                state.schemaVersion = worker.__gaiusTelemetrySchemaVersion;
                state.sessionId = sessionId;
                state.measurementId = worker.__gaiusTelemetryMeasurementId;
                state.resetAt = worker.__gaiusTelemetryResetAt;
                state.workerResetAt = worker.__gaiusTelemetryWorkerResetAt;
                state.measurementMismatches = worker.__gaiusTelemetryMeasurementMismatches;
                state.sent = worker.__gaiusTelemetrySent;
                state.received = worker.__gaiusTelemetryReceived;
                state.rttSampleCount = worker.__gaiusTelemetryRttSamples;
                state.missed = worker.__gaiusTelemetryMissed;
                state.errors = worker.__gaiusTelemetryErrors;
                state.pending = worker.__gaiusTelemetryPending.size;
                state.lastRttMillis = worker.__gaiusTelemetryLastRtt;
                state.averageRttMillis = worker.__gaiusTelemetryReceived > 0
                  ? worker.__gaiusTelemetryTotalRtt / worker.__gaiusTelemetryReceived
                  : 0;
                state.maxRttMillis = worker.__gaiusTelemetryMaxRtt;
                state.p95RttMillis = telemetryPercentile(
                  worker.__gaiusTelemetryRttHistogram,
                  worker.__gaiusTelemetryRttSamples,
                  0.95
                );
                state.p99RttMillis = telemetryPercentile(
                  worker.__gaiusTelemetryRttHistogram,
                  worker.__gaiusTelemetryRttSamples,
                  0.99
                );
                state.maxPageToWorkerMillis = worker.__gaiusTelemetryMaxPageToWorker;
                state.maxWorkerToPageMillis = worker.__gaiusTelemetryMaxWorkerToPage;
                state.configuredIntervalMillis = worker.__gaiusTelemetryInterval;
                state.longestHeartbeatGapMillis = Math.max(
                  worker.__gaiusTelemetryLongestHeartbeatGap,
                  worker.__gaiusTelemetryLastPongAt > 0
                    ? Date.now() - worker.__gaiusTelemetryLastPongAt
                    : 0
                );
                state.longestHeartbeatDelayMillis = Math.max(
                  worker.__gaiusTelemetryLongestHeartbeatDelay,
                  worker.__gaiusTelemetryLastPongAt > 0
                    ? Math.max(0, Date.now() - worker.__gaiusTelemetryLastPongAt
                      - worker.__gaiusTelemetryInterval)
                    : 0
                );
                state.lastPingAt = worker.__gaiusTelemetryLastPingAt;
                state.lastPongAt = worker.__gaiusTelemetryLastPongAt;
                state.chunkPriority = copyScalarTelemetry(
                  worker.__gaiusTelemetryChunkPriority
                );
                state.network = copyScalarTelemetry(worker.__gaiusTelemetryNetwork);
                state.globalPump = copyGlobalPumpTelemetry(
                  worker.__gaiusTelemetryGlobalPump
                );
                 // Compatibility contract: state.worldgen = copyScalarTelemetry (worldgen uses the bounded specialized copier).
                state.worldgen = copyWorldgenTelemetry(worker.__gaiusTelemetryWorldgen);
                state.serverTick = copyScalarTelemetry(worker.__gaiusTelemetryServerTick);
                state.serverDistance = copyScalarTelemetry(worker.__gaiusTelemetryServerDistance);
                state.storage = copyScalarTelemetry(worker.__gaiusTelemetryStorage);
                state.updatedAt = Date.now();
              };
              const STARTUP_INITIAL_DEADLINE_MS = 60000;
              const STARTUP_PROGRESS_GRACE_MS = 30000;
              const STARTUP_ABSOLUTE_DEADLINE_MS = 180000;
              worker.__gaiusStartupStartedAt = Date.now();
              worker.__gaiusStartupAbsoluteDeadlineAt =
                worker.__gaiusStartupStartedAt + STARTUP_ABSOLUTE_DEADLINE_MS;
              worker.__gaiusStartupInactivityDeadlineAt =
                worker.__gaiusStartupStartedAt + STARTUP_INITIAL_DEADLINE_MS;
              worker.__gaiusStartupLastProgressAt = worker.__gaiusStartupStartedAt;
              worker.__gaiusStartupLastProgressKind = 'launch';
              worker.__gaiusStartupLastProgressDetail = '';
              worker.__gaiusStartupProgressCounters = Object.create(null);
              worker.__gaiusStartupSeenPhases = Object.create(null);
              worker.__gaiusStartupSeenPhases.launch = true;
              const rememberStartupFailure = function(detail) {
                const failures = globalThis.__gaiusSingleplayerFailures ||
                  (globalThis.__gaiusSingleplayerFailures = new Map());
                const key = sessionId + ':' + launchGeneration;
                const telemetry = globalThis.__gaiusWorkerMessageTelemetry;
                failures.set(key, {
                  sessionId: sessionId,
                  launchGeneration: launchGeneration,
                  detail: String(detail),
                  at: Date.now(),
                  phase: worker.__gaiusStartupLastProgressKind || 'unknown',
                  lastProgressAt: worker.__gaiusStartupLastProgressAt || 0,
                  lastProgressDetail: worker.__gaiusStartupLastProgressDetail || '',
                  telemetry: telemetry && telemetry.sessionId === sessionId
                    ? telemetry
                    : null,
                });
                while (failures.size > 32) {
                  failures.delete(failures.keys().next().value);
                }
              };
              const recordStartupProgress = function(kind, detail, counters) {
                if (worker.__gaiusTerminal || worker.__gaiusClientAttached) return false;
                const progressKind = String(kind || 'unknown');
                const progressDetail = String(detail || '');
                const phaseFirstSeen = !worker.__gaiusStartupSeenPhases[progressKind];
                worker.__gaiusStartupSeenPhases[progressKind] = true;
                let advanced = false;
                const values = counters && typeof counters === 'object' ? counters : {};
                const names = Object.keys(values);
                for (let index = 0; index < names.length; index++) {
                  const name = names[index];
                  const value = Number(values[name]);
                  if (!Number.isFinite(value)) continue;
                  const previous = worker.__gaiusStartupProgressCounters[name];
                  if (previous === undefined) {
                    worker.__gaiusStartupProgressCounters[name] = value;
                    if (value > 0) advanced = true;
                  } else if (value > previous) {
                    worker.__gaiusStartupProgressCounters[name] = value;
                    advanced = true;
                  }
                }
                // A phase-only message counts once when first observed.  A
                // counter-bearing message advances only on a numeric increase;
                // alternating duplicate phases cannot keep the watchdog alive.
                if (names.length === 0 && phaseFirstSeen) advanced = true;
                if (!advanced) return false;
                const now = Date.now();
                worker.__gaiusStartupLastProgressAt = now;
                worker.__gaiusStartupLastProgressKind = progressKind;
                worker.__gaiusStartupLastProgressDetail = progressDetail;
                worker.__gaiusStartupInactivityDeadlineAt = Math.min(
                  Math.max(
                    worker.__gaiusStartupInactivityDeadlineAt,
                    now + STARTUP_PROGRESS_GRACE_MS,
                  ),
                  worker.__gaiusStartupAbsoluteDeadlineAt,
                );
                publishWorkerTelemetry();
                return true;
              };
              const startupNumericCounters = function(value, prefix, output) {
                if (!value || typeof value !== 'object') return;
                const keys = Object.keys(value);
                for (let index = 0; index < keys.length; index++) {
                  const key = keys[index];
                  const current = Number(value[key]);
                  if (!Number.isFinite(current) || !/(count|chunks?|slices?|progress|cached|registered|batches?|updates?|pulses?|entries|packets?|bytes|tasks?|runs?|signals?|schedules?)/i.test(key)) continue;
                  output[prefix + '.' + key] = current;
                }
              };
              const recordTelemetryProgress = function(message) {
                const counters = {};
                startupNumericCounters(message && message.chunkPriority, 'chunk', counters);
                startupNumericCounters(message && message.network, 'network', counters);
                startupNumericCounters(message && message.globalPump, 'pump', counters);
                startupNumericCounters(message && message.worldgen, 'worldgen', counters);
                startupNumericCounters(message && message.serverTick, 'tick', counters);
                startupNumericCounters(message && message.storage, 'storage', counters);
                if (Object.keys(counters).length === 0) return;
                recordStartupProgress('telemetry-counter', '', counters);
              };
              const armStartupWatchdog = function() {
                if (worker.__gaiusHandoffTimeout) clearTimeout(worker.__gaiusHandoffTimeout);
                const check = function() {
                  if (worker.__gaiusTerminal || worker.__gaiusClientAttached) return;
                  const now = Date.now();
                  if (now >= worker.__gaiusStartupAbsoluteDeadlineAt ||
                      now >= worker.__gaiusStartupInactivityDeadlineAt) {
                    terminateFailedWorker(
                      'Integrated server startup watchdog expired: ' +
                      (worker.__gaiusStartupLastProgressKind || 'no progress') +
                      (worker.__gaiusStartupLastProgressDetail
                        ? ' (' + worker.__gaiusStartupLastProgressDetail + ')'
                        : '')
                    );
                    return;
                  }
                  armStartupWatchdog();
                };
                worker.__gaiusHandoffTimeout = setTimeout(check, Math.max(
                  1,
                  Math.min(
                    worker.__gaiusStartupInactivityDeadlineAt,
                    worker.__gaiusStartupAbsoluteDeadlineAt,
                  ) - Date.now(),
                ));
              };
              const resetWorkerTelemetry = function(measurementId) {
                worker.__gaiusTelemetryPending.clear();
                worker.__gaiusTelemetrySent = 0;
                worker.__gaiusTelemetryReceived = 0;
                worker.__gaiusTelemetryMissed = 0;
                worker.__gaiusTelemetryErrors = 0;
                worker.__gaiusTelemetryTotalRtt = 0;
                worker.__gaiusTelemetryMaxRtt = 0;
                worker.__gaiusTelemetryLastRtt = 0;
                worker.__gaiusTelemetryMaxPageToWorker = 0;
                worker.__gaiusTelemetryMaxWorkerToPage = 0;
                worker.__gaiusTelemetryRttHistogram.fill(0);
                worker.__gaiusTelemetryRttSamples = 0;
                worker.__gaiusTelemetryMeasurementId = String(measurementId || '');
                worker.__gaiusTelemetryResetAt = Date.now();
                worker.__gaiusTelemetryWorkerResetAt = 0;
                worker.__gaiusTelemetryMeasurementMismatches = 0;
                worker.__gaiusTelemetryLongestHeartbeatGap = 0;
                worker.__gaiusTelemetryLongestHeartbeatDelay = 0;
                worker.__gaiusTelemetryLastPingAt = 0;
                worker.__gaiusTelemetryLastPongAt = Date.now();
                worker.__gaiusTelemetryChunkPriority = {};
                worker.__gaiusTelemetryNetwork = {};
                worker.__gaiusTelemetryGlobalPump = {};
                worker.__gaiusTelemetryWorldgen = {};
                worker.__gaiusTelemetryServerTick = {};
                worker.__gaiusTelemetryStorage = {};
                publishWorkerTelemetry();
              };
              const stopWorkerTelemetry = function() {
                if (worker.__gaiusTelemetryTimer) {
                  clearInterval(worker.__gaiusTelemetryTimer);
                  worker.__gaiusTelemetryTimer = 0;
                }
                worker.__gaiusTelemetryPending.clear();
                publishWorkerTelemetry();
              };
              const sendWorkerTelemetryPing = function() {
                if (!ownsSession() || worker.__gaiusTerminal) {
                  stopWorkerTelemetry();
                  return;
                }
                const now = Date.now();
                worker.__gaiusTelemetryPending.forEach(function(sentAt, sequence) {
                  if (now - sentAt >= 5000) {
                    worker.__gaiusTelemetryPending.delete(sequence);
                    worker.__gaiusTelemetryMissed++;
                  }
                });
                const sequence = ++worker.__gaiusTelemetrySequence;
                worker.__gaiusTelemetryPending.set(sequence, now);
                worker.__gaiusTelemetrySent++;
                worker.__gaiusTelemetryLastPingAt = now;
                try {
                  worker.postMessage({
                    type: 'telemetry-ping',
                    sessionId: sessionId,
                    sequence: sequence,
                    sentAtEpoch: now,
                    measurementId: worker.__gaiusTelemetryMeasurementId
                  });
                } catch (error) {
                  worker.__gaiusTelemetryPending.delete(sequence);
                  worker.__gaiusTelemetryErrors++;
                }
                publishWorkerTelemetry();
              };
              const startWorkerTelemetry = function() {
                if (worker.__gaiusTelemetryTimer || worker.__gaiusTerminal) return;
                sendWorkerTelemetryPing();
                worker.__gaiusTelemetryTimer = setInterval(
                  sendWorkerTelemetryPing,
                  worker.__gaiusTelemetryInterval
                );
              };
              const setWorkerTelemetryInterval = function(intervalMillis) {
                if (worker.__gaiusTerminal) return;
                const interval = Math.max(50, Math.min(5000, Number(intervalMillis) || 1000));
                if (worker.__gaiusTelemetryTimer) {
                  clearInterval(worker.__gaiusTelemetryTimer);
                }
                worker.__gaiusTelemetryInterval = interval;
                worker.__gaiusTelemetryLastPongAt = Date.now();
                sendWorkerTelemetryPing();
                worker.__gaiusTelemetryTimer = setInterval(sendWorkerTelemetryPing, interval);
              };
              const receiveWorkerTelemetryPong = function(message) {
                if (!ownsSession() || String(message.sessionId || '') !== sessionId) return;
                if (typeof message.measurementId !== 'string' ||
                    message.measurementId !== worker.__gaiusTelemetryMeasurementId) {
                  worker.__gaiusTelemetryMeasurementMismatches++;
                  publishWorkerTelemetry();
                  return;
                }
                const sequence = Number(message.sequence) || 0;
                const sentAt = worker.__gaiusTelemetryPending.get(sequence);
                if (!sentAt) return;
                worker.__gaiusTelemetryPending.delete(sequence);
                const now = Date.now();
                const rtt = Math.max(0, now - sentAt);
                const pageToWorker = Math.max(
                  0,
                  Number(message.receivedAtEpoch || sentAt) - sentAt
                );
                const workerToPage = Math.max(
                  0,
                  now - Number(message.sentAtEpoch || now)
                );
                worker.__gaiusTelemetryReceived++;
                worker.__gaiusTelemetryTotalRtt += rtt;
                worker.__gaiusTelemetryLastRtt = rtt;
                worker.__gaiusTelemetryMaxRtt = Math.max(worker.__gaiusTelemetryMaxRtt, rtt);
                const rttBucket = Math.min(
                  worker.__gaiusTelemetryRttHistogram.length - 1,
                  Math.floor(rtt)
                );
                if (worker.__gaiusTelemetryRttHistogram[rttBucket] < 0xffffffff) {
                  worker.__gaiusTelemetryRttHistogram[rttBucket]++;
                }
                worker.__gaiusTelemetryRttSamples++;
                worker.__gaiusTelemetryMaxPageToWorker = Math.max(
                  worker.__gaiusTelemetryMaxPageToWorker,
                  pageToWorker
                );
                worker.__gaiusTelemetryMaxWorkerToPage = Math.max(
                  worker.__gaiusTelemetryMaxWorkerToPage,
                  workerToPage
                );
                if (worker.__gaiusTelemetryLastPongAt > 0) {
                  const heartbeatGap = now - worker.__gaiusTelemetryLastPongAt;
                  worker.__gaiusTelemetryLongestHeartbeatGap = Math.max(
                    worker.__gaiusTelemetryLongestHeartbeatGap,
                    heartbeatGap
                  );
                  worker.__gaiusTelemetryLongestHeartbeatDelay = Math.max(
                    worker.__gaiusTelemetryLongestHeartbeatDelay,
                    Math.max(0, heartbeatGap - worker.__gaiusTelemetryInterval)
                  );
                }
                worker.__gaiusTelemetryLastPongAt = now;
                worker.__gaiusTelemetryWorkerResetAt = Number(message.workerResetAt) || 0;
                worker.__gaiusTelemetryChunkPriority = copyScalarTelemetry(
                  message.chunkPriority
                );
                worker.__gaiusTelemetryNetwork = copyScalarTelemetry(message.network);
                worker.__gaiusTelemetryGlobalPump = copyGlobalPumpTelemetry(
                  message.globalPump
                );
                 worker.__gaiusTelemetryWorldgen = copyWorldgenTelemetry(message.worldgen);
                worker.__gaiusTelemetryServerTick = copyScalarTelemetry(message.serverTick);
                worker.__gaiusTelemetryServerDistance = copyScalarTelemetry(message.serverDistance);
                worker.__gaiusTelemetryStorage = copyScalarTelemetry(message.storage);
                // A heartbeat proves transport only.  Startup grace is extended
                // only when a telemetry counter advances or a phase changes.
                recordTelemetryProgress(message);
                publishWorkerTelemetry();
              };
              worker.__gaiusStopTelemetry = stopWorkerTelemetry;
              worker.__gaiusResetTelemetry = resetWorkerTelemetry;
              worker.__gaiusSetTelemetryInterval = setWorkerTelemetryInterval;
              const failLocalSession = function(detail) {
                const bridge = globalThis.__gaiusNettyBridge;
                if (bridge && typeof bridge.failLocalSession === 'function') {
                  bridge.failLocalSession(sessionId, detail, launchGeneration);
                }
              };
              const clearWorkerStopTimeout = function() {
                if (!worker.__gaiusStopTimeout) return;
                clearTimeout(worker.__gaiusStopTimeout);
                worker.__gaiusStopTimeout = 0;
              };
              // The launcher keeps a 'waiting' status line visible until something replaces it.
              // Hand the screen back to Minecraft once the storage wait is over, whether the
              // world opened or the launch failed (its DisconnectedScreen carries the reason).
              const clearStorageWaitStatus = function() {
                if (!worker.__gaiusStorageWaitShown) return;
                worker.__gaiusStorageWaitShown = false;
                if (typeof globalThis.setStatus === 'function') {
                  globalThis.setStatus('running', 'Opening the world...');
                }
              };
              const terminateFailedWorker = function(detail) {
                if (worker.__gaiusTerminal) return;
                clearStorageWaitStatus();
                rememberStartupFailure(detail);
                clearHandoffLease();
                worker.__gaiusStorageClosed = false;
                worker.__gaiusTerminal = true;
                stopWorkerTelemetry();
                clearWorkerStopTimeout();
                if (worker.__gaiusHandoffTimeout) {
                  clearTimeout(worker.__gaiusHandoffTimeout);
                  worker.__gaiusHandoffTimeout = 0;
                }
                if (ownsSession()) failLocalSession(detail);
                if (ownsPendingPort()) {
                  try { worker.__gaiusClientPort.close(); } catch (ignored) {}
                  ports.delete(sessionId);
                } else {
                  try { if (worker.__gaiusClientPort) worker.__gaiusClientPort.close(); }
                  catch (ignored) {}
                }
                try { worker.terminate(); } catch (ignored) {}
                if (ownsSession()) workers.delete(sessionId);
              };
              const armWorkerStopTimeout = function(delayMillis, detail) {
                clearWorkerStopTimeout();
                worker.__gaiusStopTimeout = setTimeout(function() {
                  terminateFailedWorker(detail);
                }, Math.max(1000, Number(delayMillis) || 35000));
              };
              worker.__gaiusArmStopTimeout = armWorkerStopTimeout;
              worker.onmessage = function(event) {
                const message = event.data;
                if (message && message.type === 'telemetry-pong') {
                  receiveWorkerTelemetryPong(message);
                  return;
                }
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                events.push({event: 'singleplayer:worker', detail: message, at: Date.now()});
                if (events.length > 500) events.splice(0, events.length - 500);
                if (message && message.type === 'server-startup-progress') {
                  const counters = {};
                  const detailText = String(message.detail || '');
                  if (/^future-pump-waiting\\b/.test(detailText)) {
                    const taskMatch = /\\btasks=(\\d+)/.exec(detailText);
                    if (taskMatch) counters['startup.future-pump.tasks'] = Number(taskMatch[1]);
                  } else {
                    const pattern = /([A-Za-z][A-Za-z0-9_-]*)=(\\d+)/g;
                    let match;
                    while ((match = pattern.exec(detailText)) !== null) {
                      counters['startup.' + match[1]] = Number(match[2]);
                    }
                  }
                  const phaseMatch = /^([A-Za-z][A-Za-z0-9_-]*)/.exec(detailText);
                  const progressKind = phaseMatch
                    ? 'startup-detail:' + phaseMatch[1]
                    : 'server-startup-progress';
                  recordStartupProgress(progressKind, detailText, counters);
                } else if (message && message.type === 'runtime-ready') {
                  recordStartupProgress('runtime-ready', message.detail, {});
                } else if (message && message.type === 'server-listener-ready') {
                  recordStartupProgress('server-listener-ready', message.detail, {});
                } else if (message && message.type === 'server-prewarm-ready') {
                  worker.__gaiusPrewarmReady = true;
                  worker.__gaiusWorldLoaderPrewarmed = message.worldLoader === true;
                  worker.__gaiusRegistryHash = message.registryHash == null
                    ? '' : String(message.registryHash);
                  worker.__gaiusDatapackHash = message.datapackHash == null
                    ? '' : String(message.datapackHash);
                  recordStartupProgress('server-prewarm-ready', message.detail ||
                    ('bootstrap=' + !!message.bootstrap +
                      ';worldLoader=' + !!message.worldLoader), {});
                } else if (message && message.type === 'server-prewarm-failed') {
                  recordStartupProgress('server-prewarm-failed', message.detail, {});
                } else if (message && message.type === 'server-distances-ramping') {
                  recordStartupProgress('server-distances-ramping', message.detail, {});
                }
                if (message && message.type === 'runtime-ready') {
                  worker.__gaiusRuntimeReady = true;
                  startWorkerTelemetry();
                } else if (message && message.type === 'server-listener-ready') {
                  worker.__gaiusServerReady = true;
                } else if (message && message.type === 'storage-flushing') {
                  worker.__gaiusStorageFlushing = true;
                  if (worker.__gaiusStopRequested) {
                    armWorkerStopTimeout(
                      15000,
                      'Integrated server storage flush did not finish within 15 seconds'
                    );
                  }
                } else if (message && message.type === 'storage-opfs-waiting') {
                  // Only real contention for the world reports this; the line is cleared again
                  // as soon as storage opens or the launch ends (clearStorageWaitStatus).
                  worker.__gaiusStorageWaitShown = true;
                  if (typeof globalThis.setStatus === 'function') {
                    globalThis.setStatus('waiting',
                      (String(message.detail || '').split('\\n')[0] ||
                        'Waiting for the previous session to finish saving before opening this world') +
                        '...',
                      8, 'Waiting for world storage to become free...');
                  }
                } else if (message && message.type === 'storage-opfs-fallback') {
                  // OPFS is unavailable and the world opens on IndexedDB. A page opened from disk
                  // always lands here (Chromium refuses OPFS to file:// with a SecurityError), so
                  // this is not a wait for a previous session and must not say so.
                  events.push({
                    event: 'singleplayer:storage-opfs-fallback',
                    detail: String(message.detail || '').slice(0, 300),
                    at: Date.now()
                  });
                } else if (message && message.type === 'storage-ready') {
                  clearStorageWaitStatus();
                } else if (message && message.type === 'storage-closed') {
                  worker.__gaiusStorageClosed = true;
                } else if (message && message.type === 'stopped') {
                  clearStorageWaitStatus();
                  clearHandoffLease();
                  worker.__gaiusStopped = true;
                  worker.__gaiusTerminal = true;
                  stopWorkerTelemetry();
                  clearWorkerStopTimeout();
                  if (worker.__gaiusHandoffTimeout) {
                    clearTimeout(worker.__gaiusHandoffTimeout);
                    worker.__gaiusHandoffTimeout = 0;
                  }
                  if (ownsSession()) {
                    const storageRefresh = refreshPersistentFiles();
                    worker.__gaiusStorageRefresh = storageRefresh;
                    storageRefresh.catch(function(error) {
                      events.push({
                        event: 'singleplayer:storage-refresh-error',
                        detail: String(error && (error.stack || error.message) || error),
                        at: Date.now()
                      });
                    });
                  }
                  if (ownsPendingPort()) {
                    try { worker.__gaiusClientPort.close(); } catch (ignored) {}
                    ports.delete(sessionId);
                  } else {
                    try { if (worker.__gaiusClientPort) worker.__gaiusClientPort.close(); }
                    catch (ignored) {}
                  }
                  try { worker.terminate(); } catch (ignored) {}
                  if (worker.__gaiusStorageClosed === true) {
                    if (ownsSession()) workers.delete(sessionId);
                  } else if (typeof globalThis.setStatus === 'function') {
                    globalThis.setStatus('error',
                      'World storage close was not confirmed. Retry after reloading the page.',
                      0, 'Retry after reloading');
                  }
                } else if (message && message.type === 'controlled-stop') {
                  // A Wasm integrated-server pump failure has entered the Worker's final-save
                  // path. Keep it alive until stopped; terminating here races the save.
                  worker.__gaiusControlledStop = true;
                  worker.__gaiusStopRequested = true;
                  worker.__gaiusControlledStopReason = String(message.detail || message.reason || '');
                  armWorkerStopTimeout(
                    35000,
                    'Controlled integrated server stop did not finish within 35 seconds'
                  );
                } else if (message &&
                    (message.type === 'crash' || message.type === 'bootstrap-crash' ||
                      message.type === 'runtime-error')) {
                  // runtime-error: the WebAssembly GC server's main thread ended with an
                  // error, a trap included (server-worker-bootstrap.js startMain).
                  // A world that could not open (its storage held by another live tab, for
                  // example) is reported by Minecraft's own DisconnectedScreen with the Worker's
                  // reason; terminateFailedWorker hands the screen back to it.
                  const detail = String(message.detail || message.type);
                  if (!worker.__gaiusControlledStop) {
                    terminateFailedWorker(detail);
                  }
                }
              };
              armStartupWatchdog();
              worker.onerror = function(event) {
                const detail = event && event.message ? event.message : 'worker error';
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                events.push({event: 'singleplayer:worker-error', detail: detail, at: Date.now()});
                terminateFailedWorker(detail);
              };

              const lowMemory = !!(globalThis.__gaiusMemoryTier &&
                globalThis.__gaiusMemoryTier.lowMemory);
              const requestedView = Number(renderDistance) || 8;
              const requestedSimulation = Number(simulationDistance) || 6;
              const init = {
                type: 'start',
                sessionId: sessionId,
                launchGeneration: launchGeneration,
                worldId: String(worldId),
                worldSeed: String(worldSeed),
                newWorld: !!newWorld,
                initialGameRules: String(initialGameRules || ''),
                profileId: globalThis.__gaiusProfileId || null,
                skinDescriptor: globalThis.__gaiusSkinDescriptor || null,
                worldVersion: globalThis.__gaiusWorldVersion || null,
                storageSchema: globalThis.__gaiusStorageSchema || null,
                storageDatabaseName: globalThis.__gaiusStorageDatabaseName || null,
                storagePrefix: globalThis.__gaiusStoragePrefix || null,
                storageOpfsDirectory: globalThis.__gaiusStorageOpfsDirectory || null,
                bridgeUrl: globalThis.__gaiusBridgeUrl || null,
                bridgeToken: globalThis.__gaiusBridgeToken || null,
                lanRelay: globalThis.__gaiusLanRelay || null,
                renderDistance: Math.max(2, Math.min(32, lowMemory ? Math.min(requestedView, 6) : requestedView)),
                simulationDistance: Math.max(2, Math.min(32,
                  lowMemory ? Math.min(requestedSimulation, 4) : requestedSimulation)),
                worldgenSliceMillis: 8,
                distanceRampIntervalMillis: 250,
                port: channel.port2
              };
              worker.__gaiusDistances = init.renderDistance + ':' + init.simulationDistance;
              // Compile/import the server runtime while the start message is being queued. No
              // client port is transferred here; storage, Bootstrap, WorldLoader, datapacks and
              // PrepareSpawn remain owned by the start handshake (vanilla Main order).
              const prewarm = Object.assign({}, init, {type: 'prewarm'});
              delete prewarm.port;
              try {
                worker.postMessage(prewarm);
              } catch (ignored) {
                // The regular start below is the complete fallback.
              }
              const start = function() {
                if (worker.__gaiusStopRequested || !ownsSession()) {
                  try { channel.port2.close(); } catch (ignored) {}
                  terminateFailedWorker('Singleplayer launch was cancelled');
                  return;
                }
                if (!ownsPendingPort()) {
                  ports.set(sessionId, worker.__gaiusClientPort);
                }
                try {
                  worker.postMessage(init, [channel.port2]);
                  port2Transferred = true;
                } catch (error) {
                  closePort(channel.port2);
                  terminateFailedWorker(
                    String(error && (error.stack || error.message) || error)
                  );
                }
              };
              const stopPreviousWorker = function(entry) {
                const previousSessionId = entry[0];
                const previousWorker = entry[1];
                const previousGeneration = String(
                  previousWorker && previousWorker.__gaiusLaunchGeneration || ''
                );
                const ownsPreviousWorker = function() {
                  return workers.get(previousSessionId) === previousWorker &&
                    String(previousWorker && previousWorker.__gaiusLaunchGeneration || '') ===
                      previousGeneration;
                };
                if (!previousWorker || (previousWorker.__gaiusTerminal &&
                    previousWorker.__gaiusStorageClosed === true)) {
                  return Promise.resolve();
                }
                if (previousWorker.__gaiusTerminal) {
                  return Promise.reject(new Error(
                    'Previous integrated server ended before persistent storage closed'));
                }
                const waitForStorageRefresh = function() {
                  const refresh = previousWorker.__gaiusStorageRefresh;
                  return refresh && typeof refresh.then === 'function'
                    ? refresh.then(function() {}, function() {})
                    : Promise.resolve();
                };
                if (previousWorker.__gaiusStopped &&
                    previousWorker.__gaiusStorageClosed === true) {
                  return waitForStorageRefresh();
                }
                if (previousWorker.__gaiusStopped) {
                  return Promise.reject(new Error(
                    'Previous integrated server stopped before persistent storage closed'));
                }
                return new Promise(function(resolve, reject) {
                  let settled = false;
                  let timeout = 0;
                  const terminatePrevious = function(detail) {
                    previousWorker.__gaiusStorageClosed = false;
                    previousWorker.__gaiusTerminal = true;
                    if (previousWorker.__gaiusHandoffTimeout) {
                      clearTimeout(previousWorker.__gaiusHandoffTimeout);
                      previousWorker.__gaiusHandoffTimeout = 0;
                    }
                    if (previousWorker.__gaiusStopTimeout) {
                      clearTimeout(previousWorker.__gaiusStopTimeout);
                      previousWorker.__gaiusStopTimeout = 0;
                    }
                    if (typeof previousWorker.__gaiusStopTelemetry === 'function') {
                      previousWorker.__gaiusStopTelemetry();
                    }
                    const bridge = globalThis.__gaiusNettyBridge;
                  if (ownsPreviousWorker() &&
                      bridge && typeof bridge.failLocalSession === 'function') {
                      bridge.failLocalSession(previousSessionId, detail, previousGeneration);
                  }
                    const pendingPort = ports.get(previousSessionId);
                    if (ownsPreviousWorker() && pendingPort &&
                        pendingPort === previousWorker.__gaiusClientPort) {
                      try { pendingPort.close(); } catch (ignored) {}
                      ports.delete(previousSessionId);
                    } else {
                      closePort(previousWorker.__gaiusClientPort);
                    }
                    try { previousWorker.terminate(); } catch (ignored) {}
                    if (ownsPreviousWorker()) {
                      workers.delete(previousSessionId);
                    }
                  };
                  const armTimeout = function(delayMillis, detail) {
                    if (timeout) clearTimeout(timeout);
                    timeout = setTimeout(function() {
                      terminatePrevious(detail);
                      finish(new Error(detail));
                    }, delayMillis);
                  };
                  const finish = function(error) {
                    if (settled) return;
                    settled = true;
                    if (timeout) clearTimeout(timeout);
                    previousWorker.removeEventListener('message', onMessage);
                    const completion = waitForStorageRefresh();
                    completion.then(function() {
                      if (error) reject(error); else resolve();
                    }, function(refreshError) {
                      if (error) reject(error); else reject(refreshError);
                    });
                  };
                  const onMessage = function(event) {
                    if (event.data && event.data.type === 'storage-closed') {
                      previousWorker.__gaiusStorageClosed = true;
                    } else if (event.data && event.data.type === 'stopped') {
                      if (previousWorker.__gaiusStorageClosed === true) {
                        Promise.resolve().then(function() { finish(); });
                      } else {
                        finish(new Error('Previous integrated server stopped before storage close'));
                      }
                    } else if (event.data && event.data.type === 'storage-flushing') {
                      armTimeout(
                        15000,
                        'Previous integrated server storage flush timed out'
                      );
                    }
                  };
                  armTimeout(
                    35000,
                    'Previous integrated server did not stop within 35 seconds'
                  );
                  previousWorker.addEventListener('message', onMessage);
                  try {
                    previousWorker.__gaiusStopRequested = true;
                    previousWorker.postMessage({type: 'stop'});
                  } catch (error) {
                    terminatePrevious(String(error && (error.message || error) || error));
                    finish(error);
                  }
                });
              };
              const refreshPersistentFiles = function() {
                const refreshGeneration = Number(
                  globalThis.__gaiusPersistentFilesGeneration || 0
                );
                const storageDatabaseName = String(
                  globalThis.__gaiusStorageDatabaseName || ''
                ).trim();
                const storageSchema = Number(globalThis.__gaiusStorageSchema);
                const profileId = String(globalThis.__gaiusProfileId || '').trim();
                const worldVersion = Number(globalThis.__gaiusWorldVersion);
                const storagePrefix = String(globalThis.__gaiusStoragePrefix || '');
                const storageOpfsDirectory = String(
                  globalThis.__gaiusStorageOpfsDirectory || ''
                ).trim();
                const storageMatchesProfile =
                  (profileId === '1.21.11' && worldVersion === 4671 &&
                    storageSchema === 2 &&
                    storageDatabaseName === 'gaius-fs-v2-1.21.11' &&
                    storagePrefix === 'gaius.fs.v2:1.21.11:' &&
                    storageOpfsDirectory === 'regions-v2-1.21.11') ||
                  (profileId === '26.2' && worldVersion === 4903 &&
                    storageSchema === 2 &&
                    storageDatabaseName === 'gaius-fs-v2-26.2' &&
                    storagePrefix === 'gaius.fs.v2:26.2:' &&
                    storageOpfsDirectory === 'regions-v2-26.2') ||
                  (profileId === '26.3' && worldVersion === 5023 &&
                    storageSchema === 2 &&
                    storageDatabaseName === 'gaius-fs-v2-26.3' &&
                    storagePrefix === 'gaius.fs.v2:26.3:' &&
                    storageOpfsDirectory === 'regions-v2-26.3');
                if (!storageMatchesProfile) {
                  return Promise.reject(new Error(
                    'IndexedDB refresh storage configuration does not match profile'
                  ));
                }
                if (typeof indexedDB === 'undefined') return Promise.resolve();
                const worldPrefix = '/gaius/saves/';
                const isWorldMetadataPath = function(path) {
                  if (typeof path !== 'string' || !path.startsWith(worldPrefix)) {
                    return false;
                  }
                  const relative = path.substring(worldPrefix.length);
                  const separator = relative.indexOf('/');
                  if (separator <= 0) return false;
                  const file = relative.substring(separator + 1);
                  return file === 'level.dat' || file === 'level.dat_old' ||
                    file === 'icon.png' ||
                    file === 'data/minecraft/world_gen_settings.dat';
                };
                return new Promise(function(resolve, reject) {
                  const request = indexedDB.open(storageDatabaseName, storageSchema);
                  request.onsuccess = function() { resolve(request.result); };
                  request.onerror = function() {
                    reject(request.error || new Error('IndexedDB refresh open failed'));
                  };
                  request.onblocked = function() {
                    reject(new Error('IndexedDB metadata refresh was blocked'));
                  };
                }).then(function(database) {
                  const listMetadataPaths = new Promise(function(resolve, reject) {
                    const paths = [];
                    const transaction = database.transaction('files', 'readonly');
                    const store = transaction.objectStore('files');
                    const range = typeof IDBKeyRange !== 'undefined'
                      ? IDBKeyRange.bound(worldPrefix, worldPrefix + '\uffff')
                      : undefined;
                    const request = typeof store.openKeyCursor === 'function'
                      ? store.openKeyCursor(range)
                      : store.openCursor(range);
                    request.onsuccess = function() {
                      const cursor = request.result;
                      if (!cursor) {
                        resolve(paths);
                        return;
                      }
                      const path = cursor.key !== undefined
                        ? cursor.key
                        : cursor.value && cursor.value.path;
                      if (isWorldMetadataPath(path)) paths.push(path);
                      cursor.continue();
                    };
                    request.onerror = function() {
                      reject(request.error || new Error('IndexedDB metadata cursor failed'));
                    };
                  });
                  const readMetadata = function(paths) {
                    if (paths.length === 0) return Promise.resolve([]);
                    return new Promise(function(resolve, reject) {
                      const values = [];
                      const transaction = database.transaction('files', 'readonly');
                      const store = transaction.objectStore('files');
                      let failed = false;
                      for (let index = 0; index < paths.length; index++) {
                        const request = store.get(paths[index]);
                        request.onsuccess = function() {
                          if (request.result) values.push(request.result);
                        };
                        request.onerror = function() {
                          if (failed) return;
                          failed = true;
                          reject(request.error || new Error('IndexedDB metadata read failed'));
                        };
                      }
                      transaction.oncomplete = function() {
                        if (!failed) resolve(values);
                      };
                      transaction.onerror = function() {
                        if (!failed) reject(transaction.error ||
                          new Error('IndexedDB metadata transaction failed'));
                      };
                      transaction.onabort = transaction.onerror;
                    });
                  };
                  return listMetadataPaths.then(readMetadata).then(function(values) {
                    // Deletion advances the generation before awaiting storage cleanup. A
                    // refresh that started earlier must discard its stale snapshot.
                    if (Number(globalThis.__gaiusPersistentFilesGeneration || 0) !==
                        refreshGeneration) {
                      database.close();
                      return;
                    }
                    const files = globalThis.__gaiusPersistentFiles ||
                      (globalThis.__gaiusPersistentFiles = Object.create(null));
                    const paths = Object.keys(files);
                    for (let index = 0; index < paths.length; index++) {
                      if (isWorldMetadataPath(paths[index])) delete files[paths[index]];
                    }
                    for (let index = 0; index < values.length; index++) {
                      const entry = values[index];
                      if (entry && isWorldMetadataPath(entry.path)) {
                        files[entry.path] = entry.value;
                      }
                    }
                    database.close();
                  }, function(error) {
                    database.close();
                    throw error;
                  });
                });
              };
              // From disk: the loader Worker imports the bootstrap first; messages posted before
              // that (diagnostic-config) wait in the loader and reach the bootstrap in order.
              const deliverFileWorkerFiles = function() {
                if (idleBootstrapDelivery) {
                  return Promise.resolve(idleBootstrapDelivery).then(function(delivered) {
                    if (delivered === true) return true;
                    terminateFailedWorker(
                      'Singleplayer files could not be passed to the title-screen Worker');
                    return false;
                  });
                }
                if (!fileWorkerFiles) return true;
                return fileWorkerFiles.then(function(bundle) {
                  try {
                    worker.postMessage({
                      type: 'gaius-worker-bootstrap',
                      source: bundle.source,
                      files: bundle.files
                    }, bundle.transfer);
                  } catch (error) {
                    terminateFailedWorker('Singleplayer files could not be passed to the Worker: ' +
                      String(error && (error.message || error) || error));
                    return false;
                  }
                  return true;
                }, function(error) {
                  terminateFailedWorker('Singleplayer files could not be read from disk: ' +
                    String(error && (error.message || error) || error));
                  return false;
                });
              };
              Promise.allSettled(previousWorkers.map(stopPreviousWorker)).then(function(results) {
                const failed = results.find(function(result) {
                  return result && result.status === 'rejected';
                });
                if (failed) {
                  throw new Error(
                    'Previous integrated server could not stop cleanly; retryable: ' +
                    String(failed.reason && (failed.reason.message || failed.reason) || failed.reason)
                  );
                }
                const flush = typeof globalThis.__gaiusFsFlush === 'function'
                  ? globalThis.__gaiusFsFlush()
                  : null;
                return flush && typeof flush.then === 'function' ? flush : null;
              }).then(deliverFileWorkerFiles, function(error) {
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                const detail = String(error && (error.stack || error.message) || error);
                events.push({
                  event: 'singleplayer:worker-launch-error',
                  detail: detail,
                  at: Date.now()
                });
                if (typeof globalThis.setStatus === 'function') {
                  globalThis.setStatus('error',
                    'The previous session did not confirm storage close. Retry after reloading the page.',
                    0, 'Retry after reloading');
                }
                // The successor Worker was allocated before the previous-worker barrier could
                // reject. Tear it down immediately so a failed handoff cannot leave an unbooted
                // Worker alive until the generic readiness watchdog expires.
                rollbackLaunch(detail);
                return false;
              })
                .then(function(delivered) {
                  if (delivered) start();
                });
              return sessionId;
            } catch (error) {
              const detail = String(error && (error.stack || error.message) || error);
              rollbackLaunch(detail);
              const events = globalThis.__gaiusMinecraftEvents ||
                (globalThis.__gaiusMinecraftEvents = []);
              events.push({
                event: 'singleplayer:worker-launch-error',
                detail: detail,
                at: Date.now()
              });
              return null;
            }
            """)
    private static native String launchWorker(
            String worldId,
            long worldSeed,
            boolean newWorld,
            int renderDistance,
            int simulationDistance,
            String initialGameRules);

    @JSBody(params = {"renderDistance", "simulationDistance"}, script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.values !== 'function') return;
            const view = Math.max(2, Math.min(32, Number(renderDistance) || 8));
            const simulation = Math.max(2, Math.min(32, Number(simulationDistance) || 6));
            const key = view + ':' + simulation;
            workers.forEach(function(worker) {
              if (!worker || worker.__gaiusTerminal || worker.__gaiusDistances === key) return;
              worker.__gaiusDistances = key;
              try {
                worker.postMessage({
                  type: 'distances',
                  renderDistance: view,
                  simulationDistance: simulation
                });
              } catch (ignored) {}
            });
            """)
    private static native void postWorkerDistances(int renderDistance, int simulationDistance);

    @JSBody(params = {"sessionId", "launchGeneration"}, script = """
            const key = String(sessionId || '');
            const generation = String(launchGeneration || '');
            if (!key || !/^[1-9][0-9]*$/.test(generation)) return;
            globalThis.__gaiusSingleplayerHandoff = key;
            globalThis.__gaiusSingleplayerHandoffGeneration = generation;
            """)
    private static native void beginClientHandoff(String sessionId, String launchGeneration);

    @JSBody(params = {"sessionId", "launchGeneration"}, script = """
            const key = String(sessionId || '');
            const generation = String(launchGeneration || '');
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(key)
              : null;
            if (!key || !generation || !worker || worker.__gaiusTerminal ||
                String(worker.__gaiusLaunchGeneration || '') !== generation) {
              return;
            }
            const handoff = globalThis.__gaiusSingleplayerHandoff;
            const matches = handoff && typeof handoff === 'object'
              ? String(handoff.sessionId || '') === key &&
                String(handoff.generation || '') === generation
              : String(handoff || '') === key &&
                String(globalThis.__gaiusSingleplayerHandoffGeneration || '') === generation;
            if (matches) {
              globalThis.__gaiusSingleplayerHandoff = '';
              globalThis.__gaiusSingleplayerHandoffGeneration = '';
            }
            """)
    private static native void cancelClientHandoff(String sessionId, String launchGeneration);

    @JSBody(script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.values !== 'function') return false;
            let active = false;
            workers.forEach(function(worker) {
              if (worker && !worker.__gaiusTerminal) active = true;
            });
            return active;
            """)
    private static native boolean hasActiveWorker();

    /**
     * Open to LAN is only offered after both ends of the local Worker handoff
     * are ready. A live Worker by itself is not sufficient: during startup the
     * injected ServerConnectionListener may not have registered its browser
     * listener yet, and an early LAN request would otherwise be silently lost.
     */
    @JSBody(script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.values !== 'function') return false;
            let ready = false;
            workers.forEach(function(worker) {
              if (worker && !worker.__gaiusTerminal &&
                  worker.__gaiusServerReady && worker.__gaiusClientAttached) {
                ready = true;
              }
            });
            return ready;
            """)
    private static native boolean hasReadyWorker();

    @JSBody(script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.values !== 'function') return;
            const handoff = globalThis.__gaiusSingleplayerHandoff;
            const handoffSession = handoff && typeof handoff === 'object'
              ? String(handoff.sessionId || '')
              : String(handoff || '');
            const handoffGeneration = handoff && typeof handoff === 'object'
              ? String(handoff.generation || '')
              : String(globalThis.__gaiusSingleplayerHandoffGeneration || '');
            workers.forEach(function(worker, sessionId) {
              const workerGeneration = String(worker && worker.__gaiusLaunchGeneration || '');
              const ownsWorker = function() {
                return workers.get(sessionId) === worker &&
                  String(worker && worker.__gaiusLaunchGeneration || '') === workerGeneration;
              };
              if (handoffSession === String(sessionId) &&
                  handoffGeneration && handoffGeneration === workerGeneration &&
                  ownsWorker() &&
                  worker.__gaiusHandoffPending && !worker.__gaiusClientAttached) {
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                events.push({
                  event: 'singleplayer:handoff-disconnect-ignored',
                  detail: String(sessionId),
                  at: Date.now()
                });
                if (events.length > 500) events.splice(0, events.length - 500);
                return;
              }
              if (!ownsWorker()) return;
              worker.__gaiusStopRequested = true;
              try {
                worker.postMessage({type: 'stop'});
              } catch (error) {
                const bridge = globalThis.__gaiusNettyBridge;
                if (ownsWorker() && bridge &&
                    typeof bridge.failLocalSession === 'function') {
                  bridge.failLocalSession(sessionId, String(error &&
                    (error.message || error) || error), workerGeneration);
                }
              }
              if (typeof worker.__gaiusArmStopTimeout === 'function') {
                worker.__gaiusArmStopTimeout(
                  35000,
                  'Integrated server did not stop within 35 seconds'
                );
                return;
              }
              if (worker.__gaiusStopTimeout) clearTimeout(worker.__gaiusStopTimeout);
              worker.__gaiusStopTimeout = setTimeout(function() {
                if (worker.__gaiusTerminal) return;
                worker.__gaiusStorageClosed = false;
                worker.__gaiusTerminal = true;
                if (typeof worker.__gaiusStopTelemetry === 'function') {
                  worker.__gaiusStopTelemetry();
                }
                const bridge = globalThis.__gaiusNettyBridge;
                if (ownsWorker() &&
                    bridge && typeof bridge.failLocalSession === 'function') {
                  bridge.failLocalSession(
                    sessionId,
                    'Integrated server did not stop within 35 seconds',
                    workerGeneration
                  );
                }
                const ports = globalThis.__gaiusLocalServerPorts;
                const pendingPort = ports && typeof ports.get === 'function'
                  ? ports.get(sessionId)
                  : null;
                if (ownsWorker() && pendingPort &&
                    pendingPort === worker.__gaiusClientPort) {
                  try { pendingPort.close(); } catch (ignored) {}
                  ports.delete(sessionId);
                } else {
                  try { if (worker.__gaiusClientPort) worker.__gaiusClientPort.close(); }
                  catch (ignored) {}
                }
                try { worker.terminate(); } catch (ignored) {}
                if (ownsWorker()) workers.delete(sessionId);
              }, 35000);
            });
            """)
    private static native void requestWorkerStop();

    @JSBody(params = {"sessionId", "launchGeneration"}, script = """
            const key = String(sessionId || '');
            const expectedGeneration = String(launchGeneration || '');
            if (!key || !expectedGeneration) return;
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.get !== 'function') return;
            const worker = workers.get(key);
            if (!worker || worker.__gaiusTerminal ||
                String(worker.__gaiusLaunchGeneration || '') !== expectedGeneration) return;
            const ownsWorker = function() {
              return workers.get(key) === worker &&
                String(worker.__gaiusLaunchGeneration || '') === expectedGeneration;
            };
            const handoff = globalThis.__gaiusSingleplayerHandoff;
            const handoffMatches = handoff && typeof handoff === 'object'
              ? String(handoff.sessionId || '') === key &&
                String(handoff.generation || '') === expectedGeneration
              : String(handoff || '') === key &&
                String(globalThis.__gaiusSingleplayerHandoffGeneration || '') ===
                  expectedGeneration;
            if (handoffMatches && ownsWorker()) {
              globalThis.__gaiusSingleplayerHandoff = '';
              globalThis.__gaiusSingleplayerHandoffGeneration = '';
            }
            worker.__gaiusHandoffPending = false;
            worker.__gaiusStopRequested = true;
            try {
              worker.postMessage({type: 'stop'});
            } catch (error) {
              const bridge = globalThis.__gaiusNettyBridge;
              if (ownsWorker() && bridge &&
                  typeof bridge.failLocalSession === 'function') {
                bridge.failLocalSession(key, String(error &&
                  (error.message || error) || error), expectedGeneration);
              }
            }
            if (typeof worker.__gaiusArmStopTimeout === 'function') {
              worker.__gaiusArmStopTimeout(
                35000,
                'Integrated server did not stop within 35 seconds'
              );
              return;
            }
            if (worker.__gaiusStopTimeout) clearTimeout(worker.__gaiusStopTimeout);
            worker.__gaiusStopTimeout = setTimeout(function() {
              if (worker.__gaiusTerminal) return;
              worker.__gaiusStorageClosed = false;
              worker.__gaiusTerminal = true;
              if (typeof worker.__gaiusStopTelemetry === 'function') {
                worker.__gaiusStopTelemetry();
              }
              const bridge = globalThis.__gaiusNettyBridge;
              if (ownsWorker() && bridge &&
                  typeof bridge.failLocalSession === 'function') {
                bridge.failLocalSession(
                  key,
                  'Integrated server did not stop within 35 seconds',
                  expectedGeneration
                );
              }
              const ports = globalThis.__gaiusLocalServerPorts;
              const pendingPort = ports && typeof ports.get === 'function'
                ? ports.get(key)
                : null;
              if (ownsWorker() && pendingPort && pendingPort === worker.__gaiusClientPort) {
                try { pendingPort.close(); } catch (ignored) {}
                ports.delete(key);
              } else {
                try { if (worker.__gaiusClientPort) worker.__gaiusClientPort.close(); }
                catch (ignored) {}
              }
              try { worker.terminate(); } catch (ignored) {}
              if (ownsWorker()) workers.delete(key);
            }, 35000);
            """)
    private static native void requestWorkerStop(String sessionId, String launchGeneration);
}
