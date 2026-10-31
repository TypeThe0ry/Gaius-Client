package dev.gaius.browser;

import io.netty.channel.browser.BrowserWebSocketChannel;
import net.minecraft.network.PacketProcessor;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/** Keeps browser transport decoding live without running PLAY handlers on a WebSocket callback. */
public final class BrowserClientNetwork {
    private static final BrowserPumpCallback PUMP_CALLBACK = BrowserClientNetwork::pumpInbound;
    private static boolean installed;
    private static boolean pumping;

    private BrowserClientNetwork() {
    }

    public static void install() {
        // Minecraft.runTick calls this on every client frame.  Keep the Java entry cheap and
        // let the JS bridge decide whether the current bridge object is already installed.  A
        // boolean-only guard permanently binds the callback to the first bridge: if a reconnect
        // or worker handoff replaces __gaiusNettyBridge, the new bridge would never receive an
        // inbound pump.  installInboundPump() is idempotent for the same bridge and retires a
        // stale scheduler when the bridge identity changes.
        configureClientPacketDrain();
        // Singleplayer uses the same page-side PacketProcessor as multiplayer: the integrated
        // server feeds chunk packets through the local browser bridge, so leaving the pressure
        // drain opt-in only for remote sessions lets decoded chunk packets accumulate behind the
        // vanilla sixteen-packet frame budget. Promote the bounded drain for every unset client
        // bridge; an explicit URL/embedder boolean still remains authoritative.
        enableClientPacketDrainIfUnset();
        // Cache the JSFunctor so the per-frame retry path allocates no callback wrapper.  The
        // bridge-side identity guard decides whether this cached callback is actually rebound.
        installed = installInboundPump(PUMP_CALLBACK);
    }

    /**
     * Enables the bounded pressure drain when a valid remote multiplayer session starts.
     *
     * <p>The URL/global opt-out is resolved first so a page can explicitly keep the vanilla
     * packet budget. A pre-existing boolean supplied by an embedding page remains authoritative;
     * only an unset value is promoted to the bounded remote-session path.</p>
     */
    public static void enableClientPacketDrainForRemoteSession() {
        configureClientPacketDrain();
        enableClientPacketDrainIfUnset();
    }

    /**
     * Starts a generation-scoped remote drain session after the recovery layer has recorded the
     * active connection attempt.  The session token is diagnostic/lifecycle state only: it does
     * not change the existing URL/embedder opt-in precedence or any packet budget.
     */
    @JSBody(params = "address", script = """
            const state = globalThis.__gaiusMultiplayerRecovery ||
              (globalThis.__gaiusMultiplayerRecovery = {});
            const normalizedAddress = String(address || state.activeAddress || '')
              .trim().toLowerCase();
            const attempt = Math.max(0, Number(state.activeAttempt) || 0);
            const start = function(value, requestedAttempt) {
              const bridge = globalThis.__gaiusNettyBridge;
              if (!bridge) return false;
              const stats = bridge.stats || globalThis.__gaiusNetworkStats || {};
              const activeState = globalThis.__gaiusMultiplayerRecovery || state;
              const normalized = String(value || activeState.activeAddress || '')
                .trim().toLowerCase();
              const activeAttempt = Math.max(
                0, Number(requestedAttempt || activeState.activeAttempt) || 0);
              const previous = bridge.clientPacketDrainSession;
              if (previous && previous.active === true &&
                  String(previous.address || '') === normalized &&
                  Math.max(0, Number(previous.attempt) || 0) === activeAttempt) {
                delete activeState.pendingClientPacketDrainSession;
                return true;
              }
              let sequence = Number(bridge.clientPacketDrainSessionSequence) || 0;
              sequence = sequence >= 2147483647 ? 1 : sequence + 1;
              bridge.clientPacketDrainSessionSequence = sequence;
              const token = normalized + '#' + String(activeAttempt) + '#' + String(sequence);
              if (previous && previous.active) {
                previous.active = false;
                previous.endedAt = Date.now();
                previous.endReason = 'superseded';
              }
              const session = {
                token: token,
                address: normalized,
                attempt: activeAttempt,
                pumpGeneration: Math.max(0, Number(bridge.inboundPumpGeneration) || 0),
                source: 'remote-session',
                active: true,
                startedAt: Date.now(),
                endedAt: 0,
                endReason: ''
              };
              bridge.clientPacketDrainSession = session;
              bridge.clientPacketDrainSessionToken = token;
              bridge.clientPacketDrainDemand = false;
              bridge.clientPacketDrainDemandToken = '';
              bridge.clientPacketDrainPending = false;
              stats.clientPacketDrainLastSkipReason = 'none';
              stats.clientPacketDrainSessionBegins =
                (Number(stats.clientPacketDrainSessionBegins) || 0) + 1;
              stats.clientPacketDrainSessionSequence = sequence;
              stats.clientPacketDrainSessionLastAttempt = activeAttempt;
              stats.clientPacketDrainSessionLastAddress = normalized;
              delete activeState.pendingClientPacketDrainSession;
              return true;
            };
            state.beginClientPacketDrainRemoteSession = start;
            if (!start(normalizedAddress, attempt)) {
              state.pendingClientPacketDrainSession = {
                address: normalizedAddress,
                attempt: attempt,
                active: true,
                startedAt: Date.now()
              };
            }
            """)
    public static native void beginClientPacketDrainRemoteSession(String address);

    /**
     * Ends only the currently active token.  A late callback from an older connection is a
     * no-op, so it cannot clear demand/pending state belonging to a newer reconnect attempt.
     * An automatically promoted flag is retired with the session; an explicit URL/embedder flag
     * remains authoritative and is left untouched.
     */
    @JSBody(params = {"token", "reason"}, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (!bridge) return false;
            const stats = bridge.stats || globalThis.__gaiusNetworkStats || {};
            const session = bridge.clientPacketDrainSession;
            const requested = String(token || '');
            if (!session || !session.active || !requested || session.token !== requested) {
              stats.clientPacketDrainSessionStaleEnds =
                (Number(stats.clientPacketDrainSessionStaleEnds) || 0) + 1;
              return false;
            }
            session.active = false;
            session.endedAt = Date.now();
            session.endReason = String(reason || 'ended');
            bridge.clientPacketDrainSessionToken = '';
            bridge.clientPacketDrainDemand = false;
            bridge.clientPacketDrainDemandToken = '';
            bridge.clientPacketDrainPending = false;
            stats.clientPacketDrainSessionEnds =
              (Number(stats.clientPacketDrainSessionEnds) || 0) + 1;
            stats.clientPacketDrainSessionLastEndReason = session.endReason;
            if (globalThis.__gaiusClientPacketDrainAutoEnabled === true &&
                globalThis.__gaiusClientPacketDrainEnabled === true) {
              delete globalThis.__gaiusClientPacketDrainEnabled;
              delete globalThis.__gaiusClientPacketDrainAutoEnabled;
            }
            return true;
            """)
    public static native boolean endClientPacketDrainRemoteSession(String token, String reason);

    /**
     * Ends the active session for a matching address, or any active remote session when the
     * address is empty (for a local-server/menu transition).  The address check keeps a late
     * disconnect callback from retiring a newer connection that already owns the bridge.
     */
    @JSBody(params = {"address", "reason"}, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (!bridge) return false;
            const stats = bridge.stats || globalThis.__gaiusNetworkStats || {};
            const session = bridge.clientPacketDrainSession;
            const expected = String(address || '').trim().toLowerCase();
            const actual = session ? String(session.address || '').trim().toLowerCase() : '';
            if (!session || session.active !== true || (expected && actual !== expected)) {
              stats.clientPacketDrainSessionStaleEnds =
                (Number(stats.clientPacketDrainSessionStaleEnds) || 0) + 1;
              return false;
            }
            session.active = false;
            session.endedAt = Date.now();
            session.endReason = String(reason || 'ended');
            bridge.clientPacketDrainSessionToken = '';
            bridge.clientPacketDrainDemand = false;
            bridge.clientPacketDrainDemandToken = '';
            bridge.clientPacketDrainPending = false;
            stats.clientPacketDrainSessionEnds =
              (Number(stats.clientPacketDrainSessionEnds) || 0) + 1;
            stats.clientPacketDrainSessionLastEndReason = session.endReason;
            if (globalThis.__gaiusClientPacketDrainAutoEnabled === true &&
                globalThis.__gaiusClientPacketDrainEnabled === true) {
              delete globalThis.__gaiusClientPacketDrainEnabled;
              delete globalThis.__gaiusClientPacketDrainAutoEnabled;
            }
            const state = globalThis.__gaiusMultiplayerRecovery;
            const pending = state && state.pendingClientPacketDrainSession;
            const pendingAddress = pending
              ? String(pending.address || '').trim().toLowerCase() : '';
            if (pending && (!expected || pendingAddress === expected)) {
              pending.active = false;
              pending.endedAt = Date.now();
              pending.endReason = session.endReason;
              delete state.pendingClientPacketDrainSession;
            }
            return true;
            """)
    public static native boolean endCurrentClientPacketDrainRemoteSession(
            String address, String reason);

    /** Returns the active remote-session token, or an empty string when no session is active. */
    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const session = bridge && bridge.clientPacketDrainSession;
            return session && session.active ? String(session.token || '') : '';
            """)
    public static native String currentClientPacketDrainRemoteSessionToken();

    /** Drops a bridge-missing pending descriptor when the active attempt changes to local. */
    @JSBody(params = "reason", script = """
            const state = globalThis.__gaiusMultiplayerRecovery;
            const pending = state && state.pendingClientPacketDrainSession;
            if (!pending) return false;
            pending.active = false;
            pending.endedAt = Date.now();
            pending.endReason = String(reason || 'cleared');
            delete state.pendingClientPacketDrainSession;
            // A remote attempt can be recorded before BrowserWebSocketChannel has installed its
            // bridge.  If that attempt is then switched back to a local/menu path, there is no
            // bridge for endCurrentClientPacketDrainRemoteSession() to retire.  Remove only the
            // automatic promotion made by enableClientPacketDrainIfUnset(); an explicit
            // URL/embedder flag (autoEnabled=false) remains authoritative.
            const bridge = globalThis.__gaiusNettyBridge;
            const session = bridge && bridge.clientPacketDrainSession;
            if (globalThis.__gaiusClientPacketDrainAutoEnabled === true &&
                globalThis.__gaiusClientPacketDrainEnabled === true &&
                (!session || session.active !== true)) {
              delete globalThis.__gaiusClientPacketDrainEnabled;
              delete globalThis.__gaiusClientPacketDrainAutoEnabled;
            }
            return true;
            """)
    public static native boolean clearPendingClientPacketDrainRemoteSession(String reason);

    /**
     * Runs one existing bounded Netty transport turn from an independent browser macrotask.
     *
     * <p>The callback deliberately stops at raw transport decode. Ordinary PLAY packets still
     * enter PacketProcessor's FIFO and are handled only at the vanilla client tick's scheduled
     * packet-processing point; this path never invokes those handlers. The channel pump's boolean
     * is a continuation hint (slice progress or an aggregate-budget yield), so pairing it with
     * {@code hasPumpableInput()} retries a ready channel that fell beyond the round-robin cursor
     * without spinning while all channels are idle or exact-queue paused.</p>
     */
    private static boolean pumpInbound() {
        // MessageChannel/timer callbacks are synchronous JS -> Java calls on WasmGC.  The
        // Netty decode path can contend on vanilla connection/packet locks, so it must run in a
        // real JSPI activation rather than on the callback stack.
        markInboundPumpActivationStarted();
        HostLoop.startThread(() -> {
            if (pumping) {
                recordJavaPumpSkipped();
                markInboundPumpActivationFinished();
                return;
            }
            pumping = true;
            try {
                while (true) {
                    boolean continuationHint = BrowserWebSocketChannel.pumpAllAndReportProgress();
                    if (!continuationHint || !BrowserWebSocketChannel.hasPumpableInput()) {
                        break;
                    }
                    // Keep one JSPI activation for the drain, but give browser MessagePort,
                    // worker, and rendering callbacks a turn between bounded transport slices.
                    if (!HostLoop.isWasmGc()) {
                        requestInboundPumpContinuation();
                        break;
                    }
                    HostLoop.yieldToEventLoop(0);
                }
            } finally {
                pumping = false;
                markInboundPumpActivationFinished();
            }
        });
        return false;
    }

    /**
     * Runs the client/server frame-boundary transport pump through the same re-entry guard as
     * the asynchronous browser wakeup.  TeaVM continuations can resume a Java tick while a
     * MessageChannel callback is still unwinding; calling {@code BrowserWebSocketChannel.pumpAll}
     * directly from that second entry would otherwise make two callers share the global channel
     * cursor and decoder telemetry.  A skipped nested call is intentionally fail-closed: the
     * already-running pump owns the channels, and its continuation will schedule the next turn.
     */
    public static void pumpBrowserChannelsAtFrameBoundary() {
        if (pumping) {
            recordJavaPumpSkipped();
            return;
        }
        pumping = true;
        try {
            boolean continuationHint = BrowserWebSocketChannel.pumpAllAndReportProgress();
            if (continuationHint && BrowserWebSocketChannel.hasPumpableInput()) {
                requestInboundPumpContinuation();
            }
        } finally {
            pumping = false;
        }
    }

    /**
     * Requests the existing browser wakeup scheduler without entering Java recursively.  A frame
     * pump may stop at the aggregate transport budget while a later channel still has input; the
     * MessageChannel/timer scheduler is the bounded continuation point for that remainder.
     */
    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (!bridge || typeof bridge.inboundPump !== 'function') return;
            bridge.inboundPump('frame-continuation');
            """)
    private static native void requestInboundPumpContinuation();

    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const scheduler = bridge && bridge.inboundPumpScheduler;
            if (!scheduler || scheduler.__gaiusRetired === true) return;
            scheduler.activationPending = true;
            scheduler.running = true;
            """)
    private static native void markInboundPumpActivationStarted();

    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const scheduler = bridge && bridge.inboundPumpScheduler;
            if (!scheduler || scheduler.__gaiusRetired === true) return;
            scheduler.activationPending = false;
            scheduler.running = false;
            """)
    private static native void markInboundPumpActivationFinished();

    /**
     * Starts the authoritative packet-accounting epoch before raw transport work for this tick.
     */
    public static void beginClientPacketFrame() {
        BrowserPacketScheduler.beginClientFrame();
    }

    /**
     * Replaces the one vanilla {@link PacketProcessor#processQueuedPackets()} call in
     * Minecraft.runTick without moving its profiler/order boundary or adding a second batch.
     *
     * <p>The old experiment entered Java from a self-reposting JavaScript MessageChannel while
     * Minecraft's TeaVM continuation could already be suspended. That path could process packets
     * but starved the next real game present. This safe point stays on the main continuation and
     * preserves the patched {@code Queue.poll} FIFO. Below 64 packets (or when disabled) the
     * original sixteen-packet/two-millisecond batch remains unchanged. Pressure and exact-pause
     * recovery both target queue depth 63, with the same two-millisecond deadline and a
     * 256-packet clock-failure ceiling. Exactly one PacketProcessor call occurs per scheduled
     * runTick boundary. If a second PacketProcessor claims the static accounting epoch, the
     * scheduler fails closed and the patched processor dispatches its retained vanilla method.</p>
     */
    public static void processClientPacketsAtScheduledFrameBoundary(
            PacketProcessor packetProcessor) {
        boolean accountingValid = BrowserPacketScheduler.bindPacketProcessor(packetProcessor);
        int queueBefore = BrowserPacketScheduler.queuedPacketCount();
        boolean drainEnabled = isClientPacketFrameBoundaryDrainEnabled();
        if (!accountingValid || queueBefore < 64 || !drainEnabled) {
            if (!accountingValid && queueBefore >= 64 && drainEnabled) {
                recordClientPacketDrainJavaSkipped(
                        BrowserPacketScheduler.clientPacketDrainClaimSkipReason(packetProcessor));
            }
            packetProcessor.processQueuedPackets();
            return;
        }
        long runTickSequence = BrowserPacketScheduler.currentClientFrameSequence();
        int handleDepthBefore = BrowserPacketScheduler.queuedPacketHandleDepth();
        boolean pausedBefore = BrowserPacketScheduler.isPacketQueuePaused();
        String mode = pausedBefore ? "critical" : "pressure";
        int targetQueue = BrowserPacketScheduler.clientPacketDrainTargetQueue();
        int requestedPackets = Math.max(1, queueBefore - targetQueue);
        int batchTargetPackets = Math.min(
                BrowserPacketScheduler.clientPacketDrainHardMaxPackets(),
                requestedPackets);
        long startedNanos = System.nanoTime();
        String outcome = "claim-skipped";
        String failureType = null;
        if (!BrowserPacketScheduler.tryBeginClientPacketDrain(packetProcessor, pausedBefore)) {
            // Keep re-entrant handler/active-drain paths non-recursive, but do not silently drop
            // a frame when the owner claim loses a transient race.  The existing patched
            // PacketProcessor method has a bounded ordinary FIFO path when no adaptive claim is
            // active, so threshold/claim races can make one vanilla-compatible pass now without
            // clobbering an outer adaptive drain.
            String claimSkipReason =
                    BrowserPacketScheduler.clientPacketDrainClaimSkipReason(packetProcessor);
            boolean vanillaFallback =
                    "threshold-race".equals(claimSkipReason)
                            || "claim-race".equals(claimSkipReason);
            recordClientPacketDrainJavaSkipped(claimSkipReason);
            if (vanillaFallback) {
                packetProcessor.processQueuedPackets();
            }
            int queueAfter = BrowserPacketScheduler.queuedPacketCount();
            int handleDepthAfter = BrowserPacketScheduler.queuedPacketHandleDepth();
            boolean pausedAfter = BrowserPacketScheduler.isPacketQueuePaused();
            double elapsedMillis = Math.max(0L, System.nanoTime() - startedNanos) / 1_000_000.0;
            recordClientPacketFrameBoundaryDrain(
                    runTickSequence,
                    0L,
                    queueBefore,
                    queueAfter,
                    0,
                    handleDepthBefore,
                    handleDepthAfter,
                    pausedBefore,
                    pausedAfter,
                    elapsedMillis,
                    vanillaFallback ? "vanilla-fallback" : "claim-skipped",
                    targetQueue,
                    requestedPackets,
                    batchTargetPackets,
                    Math.max(0, queueAfter - targetQueue),
                    mode,
                    "claim-skipped",
                    null);
            return;
        }
        try {
            packetProcessor.processQueuedPackets();
            outcome = "completed";
        } catch (RuntimeException | Error failure) {
            outcome = "failure";
            failureType = failure.getClass().getName();
            BrowserPacketScheduler.interruptClientPacketDrain(packetProcessor);
            throw failure;
        } finally {
            // Capture the exact scheduler-owned batch identity/count before releasing the claim.
            // A PacketProcessor.close/reset may have cleared its queue during a handler, so queue
            // depth delta is evidence of a clear, not evidence that every removed item ran.
            long drainEpoch = BrowserPacketScheduler.clientPacketDrainEpoch();
            int handlerCompletions =
                    BrowserPacketScheduler.clientPacketDrainHandlerCompletions();
            BrowserPacketScheduler.finishClientPacketDrain(packetProcessor);
            recordClientPacketFrameBoundaryDrain(
                    runTickSequence,
                    drainEpoch,
                    queueBefore,
                    BrowserPacketScheduler.queuedPacketCount(),
                    handlerCompletions,
                    handleDepthBefore,
                    BrowserPacketScheduler.queuedPacketHandleDepth(),
                    pausedBefore,
                    BrowserPacketScheduler.isPacketQueuePaused(),
                    Math.max(0L, System.nanoTime() - startedNanos) / 1_000_000.0,
                    BrowserPacketScheduler.clientPacketDrainStopReason(),
                    BrowserPacketScheduler.clientPacketDrainTargetQueue(),
                    BrowserPacketScheduler.clientPacketDrainRequestedPackets(),
                    BrowserPacketScheduler.clientPacketDrainBatchTargetPackets(),
                    BrowserPacketScheduler.clientPacketDrainRemainingDebt(),
                    mode,
                    outcome,
                    failureType);
        }
    }

    @JSBody(params = "callback", script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (!bridge) return false;
            const stats = bridge.stats || globalThis.__gaiusNetworkStats || {};
            // runTick invokes BrowserClientNetwork.install() every frame.  Do not tear down a
            // healthy scheduler (or cancel its pending MessageChannel callback) on every frame;
            // only rebuild when the bridge object or one of its required hooks has changed.
            // Binding the marker back to the object identity prevents a copied marker on a
            // replacement bridge from authorizing closures that still capture the old bridge.
            // Keep the scheduler identity in the marker as well: an embedding page can replace
            // the scheduler object on the same bridge while retaining the bridge marker.  In
            // that case the old closures must be retired and rebuilt instead of being accepted by
            // the cheap same-bridge fast path.
            const installedForThisBridge = bridge.__gaiusInboundPumpInstalledBy === bridge &&
              bridge.__gaiusInboundPumpInstalledScheduler === bridge.inboundPumpScheduler &&
              typeof bridge.inboundPump === 'function' &&
              typeof bridge.clientPacketDrain === 'function' &&
              typeof bridge.invalidateClientPacketDrain === 'function' &&
              bridge.inboundPumpScheduler &&
              bridge.inboundPumpScheduler.version === 2 &&
              bridge.inboundPumpScheduler.__gaiusRetired !== true;
            if (installedForThisBridge) return true;
            const clock = function() {
              return typeof performance !== 'undefined' && performance.now
                ? performance.now()
                : Date.now();
            };
            let scheduler = bridge.inboundPumpScheduler;
            const installedScheduler = bridge.__gaiusInboundPumpInstalledScheduler;
            const schedulerIdentityDrift = !!(installedScheduler &&
              installedScheduler !== scheduler);
            const observedBridgeGeneration = Number(bridge.inboundPumpGeneration);
            const observedSchedulerGeneration = Number(scheduler && scheduler.generation);
            const observedBridgeGenerationValid =
              Number.isFinite(observedBridgeGeneration) && observedBridgeGeneration > 0;
            const observedSchedulerGenerationValid =
              Number.isFinite(observedSchedulerGeneration) && observedSchedulerGeneration > 0;
            // A pending callback has captured the scheduler generation.  If either side of
            // the bridge identity is invalid, normalizing it in place can make that callback
            // permanently stale while leaving pending=true.  Replace the whole scheduler
            // instead; legacy no-pending objects still take the normalization path below.
            const activeGenerationInvalid = !!(scheduler && scheduler.pending &&
              (!observedBridgeGenerationValid || !observedSchedulerGenerationValid));
            const activeGenerationDrift = !!(scheduler && scheduler.pending &&
              observedBridgeGenerationValid && observedSchedulerGenerationValid &&
              observedBridgeGeneration !== observedSchedulerGeneration);
            const retireScheduler = function(stale) {
              if (!stale || stale.__gaiusRetired === true) return;
              stale.__gaiusRetired = true;
              const retiredPending = stale.pending;
              if (retiredPending && retiredPending.watchdog) {
                try { clearTimeout(retiredPending.watchdog); } catch (ignored) {}
                retiredPending.watchdog = 0;
              }
              stale.pending = null;
              // Invalidate a queued diagnostics microtask from a retired scheduler before
              // replacing it; transport callbacks use a separate token/queue and are untouched.
              let retiredGeneration =
                ((Number(stale.reportGeneration) || 0) + 1) >>> 0;
              if (retiredGeneration === 0) retiredGeneration = 1;
              stale.reportGeneration = retiredGeneration;
              stale.reportPending = false;
              stale.reportDirty = false;
              if (stale.channel) {
                try { stale.channel.port1.onmessage = null; } catch (ignored) {}
                try { if (stale.channel.port1.close) stale.channel.port1.close(); }
                catch (ignored) {}
                try { if (stale.channel.port2.close) stale.channel.port2.close(); }
                catch (ignored) {}
              }
            };
            if (!scheduler || scheduler.version !== 2 || scheduler.__gaiusRetired === true ||
                schedulerIdentityDrift || activeGenerationInvalid || activeGenerationDrift) {
              const replacementBridgeGeneration = Number(bridge.inboundPumpGeneration);
              const retiredSchedulerGeneration = Number(scheduler && scheduler.generation);
              const replacementBaseGeneration = Math.max(
                Number.isFinite(replacementBridgeGeneration) && replacementBridgeGeneration > 0
                  ? replacementBridgeGeneration : 0,
                Number.isFinite(retiredSchedulerGeneration) && retiredSchedulerGeneration > 0
                  ? retiredSchedulerGeneration : 0,
                Number.isFinite(Number(installedScheduler && installedScheduler.generation)) &&
                    Number(installedScheduler.generation) > 0
                  ? Number(installedScheduler.generation) : 0
              );
              let nextGeneration = (replacementBaseGeneration + 1) >>> 0;
              if (nextGeneration === 0) nextGeneration = 1;
              retireScheduler(scheduler);
              if (installedScheduler !== scheduler) retireScheduler(installedScheduler);
              scheduler = {
                version: 2,
                // Pump generation changes whenever this scheduler is replaced.  A
                // pending callback captures both the object identity and generation so
                // a late MessageChannel/watchdog callback from a retired scheduler can
                // never clear state or enter Java on the current bridge.
                generation: nextGeneration,
                channel: null,
                pending: null,
                running: false,
                activationPending: false,
                nextToken: 1,
                reportPending: false,
                reportDirty: false,
                reportStage: '',
                reportGeneration: 0,
                reportHandle: 0
              };
              bridge.inboundPumpScheduler = scheduler;
              bridge.inboundPumpGeneration = nextGeneration;
              bridge.inboundPumpPending = false;
            }
            const bridgeGeneration = Number(bridge.inboundPumpGeneration);
            const schedulerGeneration = Number(scheduler.generation);
            const normalizedGeneration = Math.max(
              Number.isFinite(bridgeGeneration) && bridgeGeneration > 0
                ? bridgeGeneration : 0,
              Number.isFinite(schedulerGeneration) && schedulerGeneration > 0
                ? schedulerGeneration : 0,
              1
            );
            scheduler.generation = normalizedGeneration;
            bridge.inboundPumpGeneration = normalizedGeneration;
            // Keep the scheduler object compatible with older generated clients while adding
            // a bounded latest-only diagnostics lane. These fields never gate transport work.
            if (typeof scheduler.reportPending !== 'boolean') scheduler.reportPending = false;
            if (typeof scheduler.activationPending !== 'boolean') scheduler.activationPending = false;
            if (typeof scheduler.reportDirty !== 'boolean') scheduler.reportDirty = false;
            if (typeof scheduler.reportStage !== 'string') scheduler.reportStage = '';
            scheduler.reportGeneration = (Number(scheduler.reportGeneration) || 0) >>> 0;
            if (!Number.isFinite(Number(scheduler.reportHandle))) scheduler.reportHandle = 0;
            const retiredClientScheduler = bridge.clientPacketDrainScheduler;
            if (retiredClientScheduler && retiredClientScheduler.channel) {
              try { retiredClientScheduler.channel.port1.onmessage = null; } catch (ignored) {}
              try {
                if (retiredClientScheduler.channel.port1.close) {
                  retiredClientScheduler.channel.port1.close();
                }
              } catch (ignored) {}
              try {
                if (retiredClientScheduler.channel.port2.close) {
                  retiredClientScheduler.channel.port2.close();
                }
              } catch (ignored) {}
            }
            bridge.clientPacketDrainScheduler = null;
            bridge.clientPacketDrainPending = false;
            bridge.clientPacketDrainDemand = false;
            bridge.clientPacketDrainDemandToken =
              typeof bridge.clientPacketDrainDemandToken === 'string'
                ? bridge.clientPacketDrainDemandToken : '';
            bridge.clientPacketDrainSessionSequence = Math.max(
              0, Number(bridge.clientPacketDrainSessionSequence) || 0);
            stats.inboundPumpInstalled = (stats.inboundPumpInstalled|0) + 1;
            stats.inboundPumpRequested = stats.inboundPumpRequested|0;
            stats.inboundPumpScheduled = stats.inboundPumpScheduled|0;
            stats.inboundPumpCoalesced = stats.inboundPumpCoalesced|0;
            stats.inboundPumpCallbacks = stats.inboundPumpCallbacks|0;
            stats.inboundPumpMessageCallbacks = stats.inboundPumpMessageCallbacks|0;
            stats.inboundPumpTimerCallbacks = stats.inboundPumpTimerCallbacks|0;
            stats.inboundPumpWatchdogCallbacks = stats.inboundPumpWatchdogCallbacks|0;
            stats.inboundPumpStaleCallbacks = stats.inboundPumpStaleCallbacks|0;
            stats.inboundPumpJavaStarted = stats.inboundPumpJavaStarted|0;
            stats.inboundPumpJavaCompleted = stats.inboundPumpJavaCompleted|0;
            stats.inboundPumpJavaSkipped = stats.inboundPumpJavaSkipped|0;
            stats.inboundPumpJavaFailures = stats.inboundPumpJavaFailures|0;
            stats.inboundPumpRescheduled = stats.inboundPumpRescheduled|0;
            stats.inboundPumpBlockedByExactQueue = stats.inboundPumpBlockedByExactQueue|0;
            stats.inboundPumpMessageChannelFailures =
              stats.inboundPumpMessageChannelFailures|0;
            stats.maxInboundPumpScheduleWaitMillis =
              Math.max(0, Number(stats.maxInboundPumpScheduleWaitMillis) || 0);
            stats.inboundPumpDomReports = stats.inboundPumpDomReports|0;
            stats.inboundPumpDomReportCoalesced = stats.inboundPumpDomReportCoalesced|0;
            stats.inboundPumpDomReportFlushes = stats.inboundPumpDomReportFlushes|0;
            stats.inboundPumpDomReportBytes =
              Math.max(0, Number(stats.inboundPumpDomReportBytes) || 0);
            stats.clientPacketDrainInstalled = (stats.clientPacketDrainInstalled|0) + 1;
            stats.clientPacketDrainRequested = stats.clientPacketDrainRequested|0;
            stats.clientPacketDrainScheduled = stats.clientPacketDrainScheduled|0;
            stats.clientPacketDrainCoalesced = stats.clientPacketDrainCoalesced|0;
            stats.clientPacketDrainCallbacks = stats.clientPacketDrainCallbacks|0;
            stats.clientPacketDrainMessageCallbacks =
              stats.clientPacketDrainMessageCallbacks|0;
            stats.clientPacketDrainTimerCallbacks = stats.clientPacketDrainTimerCallbacks|0;
            stats.clientPacketDrainWatchdogCallbacks =
              stats.clientPacketDrainWatchdogCallbacks|0;
            stats.clientPacketDrainStaleCallbacks = stats.clientPacketDrainStaleCallbacks|0;
            stats.clientPacketDrainJavaStarted = stats.clientPacketDrainJavaStarted|0;
            stats.clientPacketDrainJavaCompleted = stats.clientPacketDrainJavaCompleted|0;
            stats.clientPacketDrainJavaSkipped = stats.clientPacketDrainJavaSkipped|0;
            stats.clientPacketDrainLastSkipReason =
              typeof stats.clientPacketDrainLastSkipReason === 'string'
                ? stats.clientPacketDrainLastSkipReason : 'none';
            stats.clientPacketDrainClaimSkippedActiveDrain =
              stats.clientPacketDrainClaimSkippedActiveDrain|0;
            stats.clientPacketDrainClaimSkippedHandlerDepth =
              stats.clientPacketDrainClaimSkippedHandlerDepth|0;
            stats.clientPacketDrainClaimSkippedOwnerConflict =
              stats.clientPacketDrainClaimSkippedOwnerConflict|0;
            stats.clientPacketDrainClaimSkippedThresholdRace =
              stats.clientPacketDrainClaimSkippedThresholdRace|0;
            stats.clientPacketDrainClaimSkippedRetiredOwner =
              stats.clientPacketDrainClaimSkippedRetiredOwner|0;
            stats.clientPacketDrainClaimSkippedWorkerServer =
              stats.clientPacketDrainClaimSkippedWorkerServer|0;
            stats.clientPacketDrainClaimSkippedNullOwner =
              stats.clientPacketDrainClaimSkippedNullOwner|0;
            stats.clientPacketDrainClaimSkippedClaimRace =
              stats.clientPacketDrainClaimSkippedClaimRace|0;
            stats.clientPacketDrainClaimSkippedUnknown =
              stats.clientPacketDrainClaimSkippedUnknown|0;
            stats.clientPacketDrainJavaFailures = stats.clientPacketDrainJavaFailures|0;
            stats.clientPacketDrainRescheduled = stats.clientPacketDrainRescheduled|0;
            stats.clientPacketDrainBelowThreshold =
              stats.clientPacketDrainBelowThreshold|0;
            stats.clientPacketDrainDisabledRequests =
              stats.clientPacketDrainDisabledRequests|0;
            stats.clientPacketDrainInvalidations = stats.clientPacketDrainInvalidations|0;
            stats.clientPacketDrainCancelled = stats.clientPacketDrainCancelled|0;
            stats.clientPacketDrainCancelledBeforeJava =
              stats.clientPacketDrainCancelledBeforeJava|0;
            stats.clientPacketDrainInvalidatedWhileRunning =
              stats.clientPacketDrainInvalidatedWhileRunning|0;
            stats.clientPacketDrainMessageChannelFailures =
              stats.clientPacketDrainMessageChannelFailures|0;
            stats.clientPacketDrainPacketsProcessed =
              Math.max(0, Number(stats.clientPacketDrainPacketsProcessed) || 0);
            stats.maxClientPacketDrainPacketsPerTurn =
              Math.max(0, Number(stats.maxClientPacketDrainPacketsPerTurn) || 0);
            stats.maxClientPacketDrainScheduleWaitMillis =
              Math.max(0, Number(stats.maxClientPacketDrainScheduleWaitMillis) || 0);
            stats.maxClientPacketDrainCallbackMillis =
              Math.max(0, Number(stats.maxClientPacketDrainCallbackMillis) || 0);
            stats.clientPacketDrainEventSequence =
              Math.max(0, Number(stats.clientPacketDrainEventSequence) || 0);
            stats.clientPacketDrainEventsDropped =
              Math.max(0, Number(stats.clientPacketDrainEventsDropped) || 0);
            if (!Array.isArray(stats.clientPacketDrainEvents)) {
              stats.clientPacketDrainEvents = [];
            } else if (stats.clientPacketDrainEvents.length > 64) {
              const retiredDrainEventsDropped = stats.clientPacketDrainEvents.length - 64;
              stats.clientPacketDrainEvents.splice(0, retiredDrainEventsDropped);
              stats.clientPacketDrainEventsDropped += retiredDrainEventsDropped;
            }
            stats.clientPacketDrainSessionBegins =
              Math.max(0, Number(stats.clientPacketDrainSessionBegins) || 0);
            stats.clientPacketDrainSessionEnds =
              Math.max(0, Number(stats.clientPacketDrainSessionEnds) || 0);
            stats.clientPacketDrainSessionStaleEnds =
              Math.max(0, Number(stats.clientPacketDrainSessionStaleEnds) || 0);
            stats.clientPacketDrainSessionSequence = Math.max(
              0, Number(stats.clientPacketDrainSessionSequence) || 0);
            stats.clientPacketDrainSessionLastAttempt = Math.max(
              0, Number(stats.clientPacketDrainSessionLastAttempt) || 0);
            if (typeof stats.clientPacketDrainSessionLastAddress !== 'string') {
              stats.clientPacketDrainSessionLastAddress = '';
            }
            if (typeof stats.clientPacketDrainSessionLastEndReason !== 'string') {
              stats.clientPacketDrainSessionLastEndReason = '';
            }
            stats.clientPacketDrainDemandSignals =
              Math.max(0, Number(stats.clientPacketDrainDemandSignals) || 0);
            stats.frameBoundaryDrainAttempts =
              Math.max(0, Number(stats.frameBoundaryDrainAttempts) || 0);
            stats.frameBoundaryDrainClaims =
              Math.max(0, Number(stats.frameBoundaryDrainClaims) || 0);
            stats.frameBoundaryDrainCompleted =
              Math.max(0, Number(stats.frameBoundaryDrainCompleted) || 0);
            stats.frameBoundaryDrainFailures =
              Math.max(0, Number(stats.frameBoundaryDrainFailures) || 0);
            stats.frameBoundaryDrainVanillaFallback =
              Math.max(0, Number(stats.frameBoundaryDrainVanillaFallback) || 0);
            stats.frameBoundaryDrainSkippedNested =
              Math.max(0, Number(stats.frameBoundaryDrainSkippedNested) || 0);
            stats.frameBoundaryDrainSkippedClaim =
              Math.max(0, Number(stats.frameBoundaryDrainSkippedClaim) || 0);
            stats.frameBoundaryDrainSkippedMinecraft =
              Math.max(0, Number(stats.frameBoundaryDrainSkippedMinecraft) || 0);
            stats.frameBoundaryDrainSkippedProcessor =
              Math.max(0, Number(stats.frameBoundaryDrainSkippedProcessor) || 0);
            stats.frameBoundaryDrainPacketsProcessed =
              Math.max(0, Number(stats.frameBoundaryDrainPacketsProcessed) || 0);
            stats.frameBoundaryDrainQueueDepthReduction =
              Math.max(0, Number(stats.frameBoundaryDrainQueueDepthReduction) || 0);
            stats.frameBoundaryDrainUnattributedQueueReduction =
              Math.max(0, Number(stats.frameBoundaryDrainUnattributedQueueReduction) || 0);
            stats.frameBoundaryDrainTotalMillis =
              Math.max(0, Number(stats.frameBoundaryDrainTotalMillis) || 0);
            stats.frameBoundaryDrainMaxMillis =
              Math.max(0, Number(stats.frameBoundaryDrainMaxMillis) || 0);
            stats.frameBoundaryDrainMaxPacketsPerTurn =
              Math.max(0, Number(stats.frameBoundaryDrainMaxPacketsPerTurn) || 0);
            stats.frameBoundaryDrainTargetStops =
              Math.max(0, Number(stats.frameBoundaryDrainTargetStops) || 0);
            stats.frameBoundaryDrainDeadlineStops =
              Math.max(0, Number(stats.frameBoundaryDrainDeadlineStops) || 0);
            stats.frameBoundaryDrainEmptyStops =
              Math.max(0, Number(stats.frameBoundaryDrainEmptyStops) || 0);
            stats.frameBoundaryDrainInterruptedStops =
              Math.max(0, Number(stats.frameBoundaryDrainInterruptedStops) || 0);
            stats.frameBoundaryDrainHardCapStops =
              Math.max(0, Number(stats.frameBoundaryDrainHardCapStops) || 0);
            stats.frameBoundaryDrainMaxRequestedPackets =
              Math.max(0, Number(stats.frameBoundaryDrainMaxRequestedPackets) || 0);
            stats.frameBoundaryDrainMaxBatchTargetPackets =
              Math.max(0, Number(stats.frameBoundaryDrainMaxBatchTargetPackets) || 0);
            stats.frameBoundaryDrainMaxRemainingDebt =
              Math.max(0, Number(stats.frameBoundaryDrainMaxRemainingDebt) || 0);
            stats.frameBoundaryDrainEventSequence =
              Math.max(0, Number(stats.frameBoundaryDrainEventSequence) || 0);
            stats.frameBoundaryDrainEventsDropped =
              Math.max(0, Number(stats.frameBoundaryDrainEventsDropped) || 0);
            if (!Array.isArray(stats.frameBoundaryDrainEvents)) {
              stats.frameBoundaryDrainEvents = [];
            } else if (stats.frameBoundaryDrainEvents.length > 64) {
              const boundaryDrainEventsDropped = stats.frameBoundaryDrainEvents.length - 64;
              stats.frameBoundaryDrainEvents.splice(0, boundaryDrainEventsDropped);
              stats.frameBoundaryDrainEventsDropped += boundaryDrainEventsDropped;
            }
            stats.clientPacketFrameSequence =
              Math.max(0, Number(stats.clientPacketFrameSequence) || 0);
            stats.clientPacketFramesWithWork =
              Math.max(0, Number(stats.clientPacketFramesWithWork) || 0);
            stats.clientPacketFramePacketsProcessed =
              Math.max(0, Number(stats.clientPacketFramePacketsProcessed) || 0);
            stats.clientPacketFrameHandlerMillis =
              Math.max(0, Number(stats.clientPacketFrameHandlerMillis) || 0);
            stats.clientPacketFrameSafeDrainTurns =
              Math.max(0, Number(stats.clientPacketFrameSafeDrainTurns) || 0);
            stats.clientPacketFrameVanillaDrainTurns =
              Math.max(0, Number(stats.clientPacketFrameVanillaDrainTurns) || 0);
            stats.maxClientPacketFramePackets =
              Math.max(0, Number(stats.maxClientPacketFramePackets) || 0);
            stats.maxClientPacketFrameHandlerMillis =
              Math.max(0, Number(stats.maxClientPacketFrameHandlerMillis) || 0);
            stats.maxClientPacketFrameSafeDrainTurns =
              Math.max(0, Number(stats.maxClientPacketFrameSafeDrainTurns) || 0);
            stats.maxClientPacketFrameVanillaDrainTurns =
              Math.max(0, Number(stats.maxClientPacketFrameVanillaDrainTurns) || 0);
            stats.clientPacketFrameEventSequence =
              Math.max(0, Number(stats.clientPacketFrameEventSequence) || 0);
            stats.clientPacketFrameEventsDropped =
              Math.max(0, Number(stats.clientPacketFrameEventsDropped) || 0);
            if (!Array.isArray(stats.clientPacketFrameEvents)) {
              stats.clientPacketFrameEvents = [];
            } else if (stats.clientPacketFrameEvents.length > 64) {
              const packetFrameEventsDropped = stats.clientPacketFrameEvents.length - 64;
              stats.clientPacketFrameEvents.splice(0, packetFrameEventsDropped);
              stats.clientPacketFrameEventsDropped += packetFrameEventsDropped;
            }
            function writeReport(stage) {
              const root = typeof document !== 'undefined' ? document.documentElement : null;
              if (!root) return false;
              const payload = JSON.stringify({
                stage: stage,
                requested: stats.inboundPumpRequested|0,
                scheduled: stats.inboundPumpScheduled|0,
                coalesced: stats.inboundPumpCoalesced|0,
                callback: stats.inboundPumpCallbacks|0,
                started: stats.inboundPumpJavaStarted|0,
                completed: stats.inboundPumpJavaCompleted|0,
                skipped: stats.inboundPumpJavaSkipped|0,
                rescheduled: stats.inboundPumpRescheduled|0,
                watchdog: stats.inboundPumpWatchdogCallbacks|0,
                pending: !!scheduler.pending,
                running: !!scheduler.running,
                received: stats.receivedFrames|0,
                pumped: stats.pumpCalls|0
              });
              root.setAttribute('data-gaius-network-pump', payload);
              stats.inboundPumpDomReports = (stats.inboundPumpDomReports|0) + 1;
              stats.inboundPumpDomReportBytes =
                Math.max(0, Number(stats.inboundPumpDomReportBytes) || 0) + payload.length;
              stats.inboundPumpDomReportLastStage = String(stage || 'unknown');
              return true;
            }
            function flushReport() {
              if (!scheduler.reportPending) return false;
              scheduler.reportPending = false;
              scheduler.reportHandle = 0;
              if (!scheduler.reportDirty) return false;
              const stage = scheduler.reportStage || 'unknown';
              scheduler.reportStage = '';
              scheduler.reportDirty = false;
              stats.inboundPumpDomReportFlushes =
                (stats.inboundPumpDomReportFlushes|0) + 1;
              return writeReport(stage);
            }
            function scheduleReportFlush() {
              const generation = scheduler.reportGeneration;
              const run = function() {
                if (generation !== scheduler.reportGeneration) return;
                scheduler.reportHandle = 0;
                flushReport();
              };
              if (typeof queueMicrotask === 'function') {
                try {
                  queueMicrotask(run);
                  return;
                } catch (error) {}
              }
              scheduler.reportHandle = setTimeout(run, 0);
            }
            function report(stage) {
              const root = typeof document !== 'undefined' ? document.documentElement : null;
              if (!root) return;
              const normalizedStage = String(stage || 'unknown');
              scheduler.reportStage = normalizedStage;
              scheduler.reportDirty = true;
              if (scheduler.reportPending) {
                stats.inboundPumpDomReportCoalesced =
                  (stats.inboundPumpDomReportCoalesced|0) + 1;
                return;
              }
              scheduler.reportPending = true;
              // Preserve the old synchronous observer contract for the first report in a turn;
              // only later reports in that same turn are latest-only coalesced.
              if (writeReport(normalizedStage)) scheduler.reportDirty = false;
              let generation = ((Number(scheduler.reportGeneration) || 0) + 1) >>> 0;
              if (generation === 0) generation = 1;
              scheduler.reportGeneration = generation;
              scheduleReportFlush();
            }
            bridge.flushInboundPumpReport = function() { return flushReport(); };
            function ensureMessageChannel() {
              if (scheduler.channel || typeof MessageChannel !== 'function') return;
              try {
                const channel = new MessageChannel();
                const channelScheduler = scheduler;
                channel.port1.onmessage = function(event) {
                  const token = Number(event && event.data) || 0;
                  const pending = channelScheduler.pending;
                  if (!pending || pending.token !== token) {
                    stats.inboundPumpStaleCallbacks++;
                    return;
                  }
                  pending.finish('message');
                };
                scheduler.channel = channel;
              } catch (error) {
                stats.inboundPumpMessageChannelFailures++;
              }
            }
            function schedulePump(reason) {
              if (globalThis.__gaiusNettyBridge !== bridge ||
                  scheduler.__gaiusRetired === true ||
                  scheduler.version !== 2 ||
                  bridge.inboundPumpScheduler !== scheduler) {
                stats.inboundPumpStaleCallbacks++;
                return false;
              }
              const closeWake = String(reason || '') === 'remote-close-retire';
              if (bridge.exactPacketQueuePaused && !closeWake) {
                stats.inboundPumpBlockedByExactQueue++;
                report('exact-paused');
                return false;
              }
              if (scheduler.pending || scheduler.running) {
                stats.inboundPumpCoalesced++;
                report('coalesced');
                return false;
              }
              let token = (Number(scheduler.nextToken) || 1) >>> 0;
              if (token === 0) token = 1;
              scheduler.nextToken = (token + 1) >>> 0;
              const scheduledAt = clock();
              const ownerScheduler = scheduler;
              const ownerGeneration = Number(ownerScheduler.generation) || 1;
              let watchdog = 0;
              let finished = false;
              let staleReported = false;
              const pending = {token: token, finish: null, watchdog: 0};
              const finish = function(source) {
                // The bridge may have installed a replacement scheduler while this
                // callback was queued.  Fail closed: only the exact pending record
                // on the owning generation may mutate bridge/scheduler state or call
                // the Java pump callback.  Retired callbacks are telemetry-only.
                if (globalThis.__gaiusNettyBridge !== bridge ||
                    bridge.inboundPumpScheduler !== ownerScheduler ||
                    ownerScheduler.__gaiusRetired === true ||
                    ownerScheduler.version !== 2 ||
                    (Number(ownerScheduler.generation) || 0) !== ownerGeneration ||
                    ownerScheduler.pending !== pending) {
                  if (!staleReported) {
                    staleReported = true;
                    if (pending.watchdog) {
                      try { clearTimeout(pending.watchdog); } catch (ignored) {}
                      pending.watchdog = 0;
                    }
                    stats.inboundPumpStaleCallbacks++;
                  }
                  return;
                }
                if (finished) {
                  if (!staleReported) {
                    staleReported = true;
                    stats.inboundPumpStaleCallbacks++;
                  }
                  return;
                }
                finished = true;
                if (watchdog) clearTimeout(watchdog);
                pending.watchdog = 0;
                if (scheduler.pending === pending) scheduler.pending = null;
                bridge.inboundPumpPending = false;
                const waitMillis = Math.max(0, clock() - scheduledAt);
                stats.maxInboundPumpScheduleWaitMillis = Math.max(
                  stats.maxInboundPumpScheduleWaitMillis,
                  waitMillis
                );
                stats.inboundPumpCallbacks++;
                if (source === 'message') stats.inboundPumpMessageCallbacks++;
                else if (source === 'watchdog') stats.inboundPumpWatchdogCallbacks++;
                else stats.inboundPumpTimerCallbacks++;
                scheduler.running = true;
                stats.inboundPumpJavaStarted++;
                let continuePumping = false;
                try {
                  continuePumping = !!callback();
                  stats.inboundPumpLastCallbackAtMillis = clock();
                } catch (error) {
                  stats.inboundPumpJavaFailures++;
                  globalThis.__gaiusLastNetworkPumpError = String(
                    error && (error.stack || error.message) || error
                  );
                } finally {
                  stats.inboundPumpJavaCompleted++;
                  // HostLoop.startThread returns before the JSPI task runs. Keep the scheduler
                  // owned by that activation until Java explicitly releases it, so MessagePort
                  // callbacks coalesce instead of starting competing activations.
                  scheduler.running = scheduler.activationPending === true;
                }
                // callback() may synchronously install a replacement bridge scheduler;
                // do not publish reports or schedule work against retired state.
                if (globalThis.__gaiusNettyBridge !== bridge ||
                    bridge.inboundPumpScheduler !== ownerScheduler ||
                    ownerScheduler.__gaiusRetired === true ||
                    ownerScheduler.version !== 2 ||
                    (Number(ownerScheduler.generation) || 0) !== ownerGeneration) {
                  return;
                }
                report(source);
                if (continuePumping && !bridge.exactPacketQueuePaused) {
                  stats.inboundPumpRescheduled++;
                  schedulePump('continuation');
                }
              };
              pending.finish = finish;
              scheduler.pending = pending;
              bridge.inboundPumpPending = true;
              stats.inboundPumpScheduled++;
              ensureMessageChannel();
              watchdog = setTimeout(function() { finish('watchdog'); }, 100);
              pending.watchdog = watchdog;
              if (scheduler.channel) {
                try {
                  scheduler.channel.port2.postMessage(token);
                } catch (error) {
                  stats.inboundPumpMessageChannelFailures++;
                  try {
                    scheduler.channel.port1.onmessage = null;
                    if (scheduler.channel.port1.close) scheduler.channel.port1.close();
                    if (scheduler.channel.port2.close) scheduler.channel.port2.close();
                  } catch (ignored) {}
                  scheduler.channel = null;
                  setTimeout(function() { finish('timer'); }, 0);
                }
              } else {
                setTimeout(function() { finish('timer'); }, 0);
              }
              report(reason || 'scheduled');
              return true;
            }
            function clientPacketQueueDepth() {
              return Math.max(0, Number(stats.decodedPacketQueue) || 0);
            }
            function reportClientPacketDrain(stage) {
              const root = typeof document !== 'undefined' ? document.documentElement : null;
              if (!root) return;
              root.setAttribute('data-gaius-client-packet-drain', JSON.stringify({
                stage: stage,
                requested: stats.clientPacketDrainRequested|0,
                scheduled: stats.clientPacketDrainScheduled|0,
                coalesced: stats.clientPacketDrainCoalesced|0,
                callbacks: stats.clientPacketDrainCallbacks|0,
                completed: stats.clientPacketDrainJavaCompleted|0,
                skipped: stats.clientPacketDrainJavaSkipped|0,
                lastSkipReason: String(stats.clientPacketDrainLastSkipReason || 'none'),
                skipReasons: {
                  activeDrain: stats.clientPacketDrainClaimSkippedActiveDrain|0,
                  handlerDepth: stats.clientPacketDrainClaimSkippedHandlerDepth|0,
                  ownerConflict: stats.clientPacketDrainClaimSkippedOwnerConflict|0,
                  thresholdRace: stats.clientPacketDrainClaimSkippedThresholdRace|0,
                  retiredOwner: stats.clientPacketDrainClaimSkippedRetiredOwner|0,
                  workerServer: stats.clientPacketDrainClaimSkippedWorkerServer|0,
                  nullOwner: stats.clientPacketDrainClaimSkippedNullOwner|0,
                  claimRace: stats.clientPacketDrainClaimSkippedClaimRace|0,
                  unknown: stats.clientPacketDrainClaimSkippedUnknown|0
                },
                failures: stats.clientPacketDrainJavaFailures|0,
                rescheduled: stats.clientPacketDrainRescheduled|0,
                enabled: globalThis.__gaiusClientPacketDrainEnabled === true,
                disabled: stats.clientPacketDrainDisabledRequests|0,
                invalidations: stats.clientPacketDrainInvalidations|0,
                cancelled: stats.clientPacketDrainCancelled|0,
                watchdog: stats.clientPacketDrainWatchdogCallbacks|0,
                queueDepth: clientPacketQueueDepth(),
                 generation: Math.max(0, Number(scheduler && scheduler.generation) || 0),
                 pending: !!(scheduler && scheduler.pending),
                 running: !!(scheduler && scheduler.running),
                 demand: !!bridge.clientPacketDrainDemand,
                 sessionActive: !!(bridge.clientPacketDrainSession &&
                   bridge.clientPacketDrainSession.active),
                 sessionAttempt: Math.max(0, Number(
                   bridge.clientPacketDrainSession &&
                   bridge.clientPacketDrainSession.attempt) || 0),
                 sessionSequence: Math.max(0, Number(
                   bridge.clientPacketDrainSessionSequence) || 0),
                 mode: 'single-call-runTick-boundary'
              }));
            }
            bridge.inboundPump = function(reason) {
              stats.inboundPumpRequested++;
              schedulePump(String(reason || 'requested'));
            };
             bridge.clientPacketDrain = function() {
               stats.clientPacketDrainRequested++;
               if (globalThis.__gaiusClientPacketDrainEnabled !== true) {
                 stats.clientPacketDrainDisabledRequests++;
                 if (stats.clientPacketDrainDisabledRequests === 1) {
                   reportClientPacketDrain('disabled');
                 }
                 return false;
               }
               const demand = clientPacketQueueDepth() >= 64;
               if (!demand) {
                 bridge.clientPacketDrainDemand = false;
                 bridge.clientPacketDrainDemandToken = '';
                 stats.clientPacketDrainBelowThreshold++;
                 reportClientPacketDrain('below-threshold');
                 return false;
               }
               if (bridge.clientPacketDrainDemand) return false;
               bridge.clientPacketDrainDemand = true;
               const session = bridge.clientPacketDrainSession;
               bridge.clientPacketDrainDemandToken =
                 session && session.active ? String(session.token || '') : '';
               stats.clientPacketDrainDemandSignals++;
               reportClientPacketDrain('frame-boundary-demand');
               return false;
             };
             bridge.invalidateClientPacketDrain = function(reason) {
               stats.clientPacketDrainInvalidations++;
               bridge.clientPacketDrainDemand = false;
               bridge.clientPacketDrainDemandToken = '';
               bridge.clientPacketDrainPending = false;
               stats.clientPacketDrainLastInvalidationReason = String(
                 reason || 'invalidated'
               );
               reportClientPacketDrain('invalidated');
               return false;
             };
            // ConnectScreen records the attempt before Netty constructs its first channel.  If
            // that early call could not see a bridge, hand the pending token to the first pump
            // installation, but only when address and attempt are still the active generation.
            const recovery = globalThis.__gaiusMultiplayerRecovery;
            const pendingSession = recovery && recovery.pendingClientPacketDrainSession;
            const startRemoteSession = recovery &&
              recovery.beginClientPacketDrainRemoteSession;
            if (pendingSession && typeof startRemoteSession === 'function') {
              const activeAddress = String(recovery.activeAddress || '').trim().toLowerCase();
              const activeAttempt = Math.max(0, Number(recovery.activeAttempt) || 0);
              const pendingAddress = String(pendingSession.address || '').trim().toLowerCase();
              const pendingAttempt = Math.max(0, Number(pendingSession.attempt) || 0);
              if (activeAddress === pendingAddress && activeAttempt === pendingAttempt) {
                if (startRemoteSession(pendingAddress, pendingAttempt)) {
                  delete recovery.pendingClientPacketDrainSession;
                }
              } else {
                delete recovery.pendingClientPacketDrainSession;
              }
            }
            bridge.__gaiusInboundPumpInstalledBy = bridge;
            bridge.__gaiusInboundPumpInstalledScheduler = scheduler;
            return true;
            """)
    private static native boolean installInboundPump(BrowserPumpCallback callback);

    /**
     * Enables the bounded client-tick packet drain only through an explicit page opt-in.
     *
     * <p>Keeping the default unset preserves the vanilla sixteen-packet path for existing pages,
     * while a release URL can opt into the pressure drain without injecting test-only globals.
     * A harness or embedding page that already supplied a boolean value always wins.</p>
     */
    @JSBody(script = """
            if (typeof globalThis.__gaiusClientPacketDrainEnabled === 'boolean') {
              if (typeof globalThis.__gaiusClientPacketDrainAutoEnabled !== 'boolean') {
                globalThis.__gaiusClientPacketDrainAutoEnabled = false;
              }
              return;
            }
            if (typeof URLSearchParams !== 'function' ||
                typeof location === 'undefined') return;
            // The URL is normally immutable for the lifetime of a page.  Remember the exact
            // search string after one parse so runTick's per-frame install retry does not allocate
            // a new URLSearchParams object on every frame.  If an embedder changes the query via
            // history/navigation, a different string deliberately causes one fresh parse.
            const search = String(location.search || '');
            if (globalThis.__gaiusClientPacketDrainConfiguredSearch === search) return;
            let value = null;
            try {
              const params = new URLSearchParams(search);
              value = params.get('gaiusClientPacketDrain');
              if (value === null) value = params.get('clientPacketDrain');
            } catch (ignored) {}
            globalThis.__gaiusClientPacketDrainConfiguredSearch = search;
            if (value === null) return;
            const normalized = String(value).trim().toLowerCase();
            if (normalized === '1' || normalized === 'true' || normalized === 'on') {
              globalThis.__gaiusClientPacketDrainEnabled = true;
              globalThis.__gaiusClientPacketDrainAutoEnabled = false;
            } else if (normalized === '0' || normalized === 'false' || normalized === 'off') {
              globalThis.__gaiusClientPacketDrainEnabled = false;
              globalThis.__gaiusClientPacketDrainAutoEnabled = false;
            }
            """)
    private static native void configureClientPacketDrain();

    @JSBody(script = """
            if (typeof globalThis.__gaiusClientPacketDrainEnabled === 'boolean') {
              if (typeof globalThis.__gaiusClientPacketDrainAutoEnabled !== 'boolean') {
                globalThis.__gaiusClientPacketDrainAutoEnabled = false;
              }
              return;
            }
            globalThis.__gaiusClientPacketDrainEnabled = true;
            globalThis.__gaiusClientPacketDrainAutoEnabled = true;
            """)
    private static native void enableClientPacketDrainIfUnset();

    @JSBody(script = """
            return globalThis.__gaiusClientPacketDrainEnabled === true;
            """)
    private static native boolean isClientPacketFrameBoundaryDrainEnabled();

    /** Records one pressure-triggered frame-boundary drain or a bounded vanilla fallback. */
    @JSBody(params = {
            "runTickSequence", "drainEpoch", "queueBefore", "queueAfter",
            "handlerCompletions", "handleDepthBefore", "handleDepthAfter",
            "pausedBefore", "pausedAfter", "elapsedMillis",
            "stopReason", "targetQueue", "requestedPackets", "batchTargetPackets",
            "remainingDebt", "mode", "outcome", "failureType"
    }, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats);
            if (!stats) return;
            const bounded = function(value) {
              const number = Math.max(0, Number(value) || 0);
              return Number.isFinite(number) ? number : 0;
            };
            const result = String(outcome || 'unknown');
            const drainMode = String(mode || 'pressure');
            const rawStopReason = String(stopReason || 'unknown');
            const drainStopReason = rawStopReason === 'target' ||
              rawStopReason === 'deadline' || rawStopReason === 'empty' ||
              rawStopReason === 'interrupted' ||
              rawStopReason === 'hard-cap' || rawStopReason === 'claim-skipped'
                ? rawStopReason : 'unknown';
            const before = bounded(queueBefore);
            const after = bounded(queueAfter);
            const epoch = bounded(drainEpoch);
            const packets = bounded(handlerCompletions);
            const queueDepthReduction = Math.max(0, before - after);
            const unattributedQueueReduction = Math.max(
              0, queueDepthReduction - packets);
            const millis = bounded(elapsedMillis);
            const target = bounded(targetQueue);
            const requested = bounded(requestedPackets);
            const batchTarget = bounded(batchTargetPackets);
            const debt = bounded(remainingDebt);
            stats.frameBoundaryDrainAttempts = bounded(stats.frameBoundaryDrainAttempts) + 1;
            if (result !== 'nested-skipped' && result !== 'claim-skipped' &&
                result !== 'vanilla-fallback') {
              stats.frameBoundaryDrainClaims = bounded(stats.frameBoundaryDrainClaims) + 1;
            }
            if (result === 'completed') {
              stats.frameBoundaryDrainCompleted = bounded(stats.frameBoundaryDrainCompleted) + 1;
            } else if (result === 'failure') {
              stats.frameBoundaryDrainFailures = bounded(stats.frameBoundaryDrainFailures) + 1;
            } else if (result === 'nested-skipped') {
              stats.frameBoundaryDrainSkippedNested =
                bounded(stats.frameBoundaryDrainSkippedNested) + 1;
            } else if (result === 'claim-skipped') {
              stats.frameBoundaryDrainSkippedClaim =
                bounded(stats.frameBoundaryDrainSkippedClaim) + 1;
            } else if (result === 'vanilla-fallback') {
              stats.frameBoundaryDrainSkippedClaim =
                bounded(stats.frameBoundaryDrainSkippedClaim) + 1;
              stats.frameBoundaryDrainVanillaFallback =
                bounded(stats.frameBoundaryDrainVanillaFallback) + 1;
            } else if (result === 'minecraft-skipped') {
              stats.frameBoundaryDrainSkippedMinecraft =
                bounded(stats.frameBoundaryDrainSkippedMinecraft) + 1;
            } else if (result === 'processor-skipped') {
              stats.frameBoundaryDrainSkippedProcessor =
                bounded(stats.frameBoundaryDrainSkippedProcessor) + 1;
            }
            stats.frameBoundaryDrainPacketsProcessed =
              bounded(stats.frameBoundaryDrainPacketsProcessed) + packets;
            stats.frameBoundaryDrainQueueDepthReduction =
              bounded(stats.frameBoundaryDrainQueueDepthReduction) + queueDepthReduction;
            stats.frameBoundaryDrainUnattributedQueueReduction =
              bounded(stats.frameBoundaryDrainUnattributedQueueReduction) +
                unattributedQueueReduction;
            stats.frameBoundaryDrainTotalMillis =
              bounded(stats.frameBoundaryDrainTotalMillis) + millis;
            stats.frameBoundaryDrainMaxMillis = Math.max(
              bounded(stats.frameBoundaryDrainMaxMillis), millis);
            stats.frameBoundaryDrainMaxPacketsPerTurn = Math.max(
              bounded(stats.frameBoundaryDrainMaxPacketsPerTurn), packets);
            if (drainStopReason === 'target') {
              stats.frameBoundaryDrainTargetStops =
                bounded(stats.frameBoundaryDrainTargetStops) + 1;
            } else if (drainStopReason === 'deadline') {
              stats.frameBoundaryDrainDeadlineStops =
                bounded(stats.frameBoundaryDrainDeadlineStops) + 1;
            } else if (drainStopReason === 'empty') {
              stats.frameBoundaryDrainEmptyStops =
                bounded(stats.frameBoundaryDrainEmptyStops) + 1;
            } else if (drainStopReason === 'interrupted') {
              stats.frameBoundaryDrainInterruptedStops =
                bounded(stats.frameBoundaryDrainInterruptedStops) + 1;
            } else if (drainStopReason === 'hard-cap') {
              stats.frameBoundaryDrainHardCapStops =
                bounded(stats.frameBoundaryDrainHardCapStops) + 1;
            }
            stats.frameBoundaryDrainMaxRequestedPackets = Math.max(
              bounded(stats.frameBoundaryDrainMaxRequestedPackets), requested);
            stats.frameBoundaryDrainMaxBatchTargetPackets = Math.max(
              bounded(stats.frameBoundaryDrainMaxBatchTargetPackets), batchTarget);
            stats.frameBoundaryDrainMaxRemainingDebt = Math.max(
              bounded(stats.frameBoundaryDrainMaxRemainingDebt), debt);
            stats.frameBoundaryDrainLastRunTickSequence = bounded(runTickSequence);
            stats.frameBoundaryDrainLastEpoch = epoch;
            stats.frameBoundaryDrainLastHandlerCompletions = packets;
            stats.frameBoundaryDrainLastQueueDepthReduction = queueDepthReduction;
            stats.frameBoundaryDrainLastUnattributedQueueReduction =
              unattributedQueueReduction;
            stats.frameBoundaryDrainLastOutcome = result;
            stats.frameBoundaryDrainLastMode = drainMode;
            stats.frameBoundaryDrainLastStopReason = drainStopReason;
            stats.frameBoundaryDrainLastTargetQueue = target;
            stats.frameBoundaryDrainLastRequestedPackets = requested;
            stats.frameBoundaryDrainLastBatchTargetPackets = batchTarget;
            stats.frameBoundaryDrainLastRemainingDebt = debt;
            stats.frameBoundaryDrainLastFailureType = failureType == null
              ? '' : String(failureType);
            const sequence = bounded(stats.frameBoundaryDrainEventSequence) + 1;
            stats.frameBoundaryDrainEventSequence = sequence;
            const events = Array.isArray(stats.frameBoundaryDrainEvents)
              ? stats.frameBoundaryDrainEvents
              : (stats.frameBoundaryDrainEvents = []);
            events.push({
              sequence: sequence,
              atMillis: typeof performance !== 'undefined' && performance.now
                ? performance.now() : Date.now(),
              runTickSequence: bounded(runTickSequence),
              drainEpoch: epoch,
              queueBefore: before,
              queueAfter: after,
              packetsProcessed: packets,
              handlerCompletions: packets,
              queueDepthReduction: queueDepthReduction,
              unattributedQueueReduction: unattributedQueueReduction,
              elapsedMillis: millis,
              stopReason: drainStopReason,
              targetQueue: target,
              requestedPackets: requested,
              batchTargetPackets: batchTarget,
              remainingDebt: debt,
              handleDepthBefore: bounded(handleDepthBefore),
              handleDepthAfter: bounded(handleDepthAfter),
              flowPausedBefore: !!pausedBefore,
              flowPausedAfter: !!pausedAfter,
              mode: drainMode,
              outcome: result,
              vanillaFallback: result === 'vanilla-fallback',
              failureType: failureType == null ? '' : String(failureType)
            });
            if (events.length > 64) {
              const dropped = events.length - 64;
              events.splice(0, dropped);
              stats.frameBoundaryDrainEventsDropped =
                bounded(stats.frameBoundaryDrainEventsDropped) + dropped;
            }
            const session = bridge.clientPacketDrainSession;
            const sessionActive = !session || session.active === true;
            bridge.clientPacketDrainDemand = after >= 64 && sessionActive;
            bridge.clientPacketDrainDemandToken = sessionActive && session
              ? String(session.token || '') : '';
            const root = typeof document !== 'undefined' ? document.documentElement : null;
            if (root) root.setAttribute('data-gaius-client-packet-frame-boundary', JSON.stringify({
              mode: 'single-call-runTick-boundary',
              lastMode: drainMode,
              attempts: bounded(stats.frameBoundaryDrainAttempts),
              claims: bounded(stats.frameBoundaryDrainClaims),
              completed: bounded(stats.frameBoundaryDrainCompleted),
              failures: bounded(stats.frameBoundaryDrainFailures),
              vanillaFallbacks: bounded(stats.frameBoundaryDrainVanillaFallback),
              packets: bounded(stats.frameBoundaryDrainPacketsProcessed),
              maxPacketsPerTurn: bounded(stats.frameBoundaryDrainMaxPacketsPerTurn),
              maxMillis: bounded(stats.frameBoundaryDrainMaxMillis),
              stopReason: drainStopReason,
              targetQueue: target,
              requestedPackets: requested,
              batchTargetPackets: batchTarget,
              remainingDebt: debt,
              runTickSequence: bounded(stats.frameBoundaryDrainLastRunTickSequence),
              drainEpoch: epoch,
              handlerCompletions: packets,
              queueDepthReduction: queueDepthReduction,
              unattributedQueueReduction: unattributedQueueReduction,
              queueAfter: after,
              demand: !!bridge.clientPacketDrainDemand,
              sessionActive: !!(session && session.active),
              sessionAttempt: Math.max(0, Number(session && session.attempt) || 0),
              sessionSequence: Math.max(0, Number(
                bridge.clientPacketDrainSessionSequence) || 0)
            }));
            """)
    private static native void recordClientPacketFrameBoundaryDrain(
            long runTickSequence,
            long drainEpoch,
            int queueBefore,
            int queueAfter,
            int handlerCompletions,
            int handleDepthBefore,
            int handleDepthAfter,
            boolean pausedBefore,
            boolean pausedAfter,
            double elapsedMillis,
            String stopReason,
            int targetQueue,
            int requestedPackets,
            int batchTargetPackets,
            int remainingDebt,
            String mode,
            String outcome,
            String failureType);

    /** Emits the preceding runTick's combined safe and vanilla PLAY handler work. */
    @JSBody(params = {
            "runTickSequence", "safeDrainTurns", "vanillaDrainTurns", "packetsProcessed",
            "handlerMillis"
    }, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats);
            if (!stats) return;
            const bounded = function(value) {
              const number = Math.max(0, Number(value) || 0);
              return Number.isFinite(number) ? number : 0;
            };
            const sequence = bounded(stats.clientPacketFrameEventSequence) + 1;
            const frameSequence = bounded(runTickSequence);
            const safeTurns = bounded(safeDrainTurns);
            const vanillaTurns = bounded(vanillaDrainTurns);
            const packets = bounded(packetsProcessed);
            const millis = bounded(handlerMillis);
            stats.clientPacketFrameEventSequence = sequence;
            stats.clientPacketFrameSequence = frameSequence;
            stats.clientPacketFramesWithWork = bounded(stats.clientPacketFramesWithWork) + 1;
            stats.clientPacketFramePacketsProcessed =
              bounded(stats.clientPacketFramePacketsProcessed) + packets;
            stats.clientPacketFrameHandlerMillis =
              bounded(stats.clientPacketFrameHandlerMillis) + millis;
            stats.clientPacketFrameSafeDrainTurns =
              bounded(stats.clientPacketFrameSafeDrainTurns) + safeTurns;
            stats.clientPacketFrameVanillaDrainTurns =
              bounded(stats.clientPacketFrameVanillaDrainTurns) + vanillaTurns;
            stats.maxClientPacketFramePackets = Math.max(
              bounded(stats.maxClientPacketFramePackets), packets);
            stats.maxClientPacketFrameHandlerMillis = Math.max(
              bounded(stats.maxClientPacketFrameHandlerMillis), millis);
            stats.maxClientPacketFrameSafeDrainTurns = Math.max(
              bounded(stats.maxClientPacketFrameSafeDrainTurns), safeTurns);
            stats.maxClientPacketFrameVanillaDrainTurns = Math.max(
              bounded(stats.maxClientPacketFrameVanillaDrainTurns), vanillaTurns);
            const events = Array.isArray(stats.clientPacketFrameEvents)
              ? stats.clientPacketFrameEvents
              : (stats.clientPacketFrameEvents = []);
            events.push({
              sequence: sequence,
              runTickSequence: frameSequence,
              safeDrainTurns: safeTurns,
              vanillaDrainTurns: vanillaTurns,
              packetsProcessed: packets,
              handlerMillis: millis
            });
            if (events.length > 64) {
              const dropped = events.length - 64;
              events.splice(0, dropped);
              stats.clientPacketFrameEventsDropped =
                bounded(stats.clientPacketFrameEventsDropped) + dropped;
            }
            """)
    public static native void recordClientPacketFrame(
            long runTickSequence,
            int safeDrainTurns,
            int vanillaDrainTurns,
            int packetsProcessed,
            double handlerMillis);

    /** Clears pressure demand when PacketProcessor.close/reset retires its FIFO. */
    @JSBody(params = "reason", script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (bridge && typeof bridge.invalidateClientPacketDrain === 'function') {
              bridge.invalidateClientPacketDrain(reason);
            }
            """)
    public static native void invalidateClientPacketDrain(String reason);

    @JSBody(script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (stats) stats.inboundPumpJavaSkipped =
              (stats.inboundPumpJavaSkipped|0) + 1;
            """)
    private static native void recordJavaPumpSkipped();

    @JSBody(params = "reason", script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (stats) {
              const normalized = String(reason || 'unknown');
              stats.clientPacketDrainJavaSkipped =
                (stats.clientPacketDrainJavaSkipped|0) + 1;
              stats.clientPacketDrainLastSkipReason = normalized;
              if (normalized === 'active-drain') {
                stats.clientPacketDrainClaimSkippedActiveDrain =
                  (stats.clientPacketDrainClaimSkippedActiveDrain|0) + 1;
              } else if (normalized === 'handler-depth') {
                stats.clientPacketDrainClaimSkippedHandlerDepth =
                  (stats.clientPacketDrainClaimSkippedHandlerDepth|0) + 1;
              } else if (normalized === 'owner-conflict') {
                stats.clientPacketDrainClaimSkippedOwnerConflict =
                  (stats.clientPacketDrainClaimSkippedOwnerConflict|0) + 1;
              } else if (normalized === 'threshold-race') {
                stats.clientPacketDrainClaimSkippedThresholdRace =
                  (stats.clientPacketDrainClaimSkippedThresholdRace|0) + 1;
              } else if (normalized === 'retired-owner') {
                stats.clientPacketDrainClaimSkippedRetiredOwner =
                  (stats.clientPacketDrainClaimSkippedRetiredOwner|0) + 1;
              } else if (normalized === 'worker-server') {
                stats.clientPacketDrainClaimSkippedWorkerServer =
                  (stats.clientPacketDrainClaimSkippedWorkerServer|0) + 1;
              } else if (normalized === 'null-owner') {
                stats.clientPacketDrainClaimSkippedNullOwner =
                  (stats.clientPacketDrainClaimSkippedNullOwner|0) + 1;
              } else if (normalized === 'claim-race') {
                stats.clientPacketDrainClaimSkippedClaimRace =
                  (stats.clientPacketDrainClaimSkippedClaimRace|0) + 1;
              } else {
                stats.clientPacketDrainClaimSkippedUnknown =
                  (stats.clientPacketDrainClaimSkippedUnknown|0) + 1;
              }
            }
            """)
    private static native void recordClientPacketDrainJavaSkipped(String reason);

    @JSFunctor
    private interface BrowserPumpCallback extends JSObject {
        boolean run();
    }
}
