// Gaius WebAssembly GC thread host for TeaVM's JSPI mode (gaius.teavm.jspi): the run queue,
// the gaiusJspi imports of dev.gaius.browser.JspiHost and the routing of TeaVM EventQueue
// events into the same queue. Exposes globalThis.GaiusJspi; the boot passes
// installJspi/routeEventQueue to TeaVM.wasmGC.load through options.installImports and starts
// the program with startMain(args) instead of TeaVM's main export.
//
// Run queue: ready work (resumed threads, started threads, zero-delay TeaVM events, the frame
// resumes of the frame pacer) runs in one macrotask posted through the queue's MessageChannel,
// the only one of the host, for at most SLICE_MS, then yields to the event loop. Nested
// setTimeout(0) would be clamped to 4 ms; a MessageChannel message is not.
//
// Frame boundary (design D4): the patched Minecraft.run waits in the awaitFrame import at the
// head of its loop (HostLoop.awaitAnimationFrame) with the pacing of the JavaScript build's
// per-present yield; see createFramePacer. The rules for JS -> Java callbacks are in
// port/web/runtime/host-abi.md.
(function () {
  "use strict";
  var SLICE_MS = 4;
  // setTimeout takes a signed 32-bit delay; a longer one fires at once (TimeoutOverflowWarning).
  // A thread that waits longer re-parks for the time left when the timer fires.
  var MAX_TIMER_MS = 2147483647;
  // JspiHost.clinitEvent kinds.
  var CLINIT_SUSPEND = 0;
  var CLINIT_WAIT = 1;

  // Long-running activations are routine in normal play (a chunk-generation layer, a big
  // resume chain), so they are counted always (scheduler stats and, when frame telemetry is on,
  // __gaiusFrameTelemetry.jspiSlowActivationCount) but logged only once per session, or every
  // time when a debug switch is on: __gaiusFrameTelemetry.enabled, globalThis.__gaiusDebug,
  // or the gaiusDebug / diag query parameter.
  var slowActivationLogged = false;

  function debugSwitchOn() {
    var root = globalThis;
    var telemetry = root.__gaiusFrameTelemetry;
    if (telemetry && telemetry.enabled) {
      return true;
    }
    if (root.__gaiusDebug === true) {
      return true;
    }
    try {
      if (root.location && root.location.search && typeof URLSearchParams === "function") {
        var params = new URLSearchParams(root.location.search);
        return params.has("gaiusDebug") || params.has("diag");
      }
    } catch (ignored) { /* no location */ }
    return false;
  }

  function reportSlowActivation(stats, pending) {
    stats.slowActivations = (stats.slowActivations || 0) + 1;
    var telemetry = globalThis.__gaiusFrameTelemetry;
    if (telemetry && telemetry.enabled) {
      telemetry.jspiSlowActivationCount = stats.slowActivations;
    }
    if (slowActivationLogged && !debugSwitchOn()) {
      return;
    }
    slowActivationLogged = true;
    console.warn("JSPI activation is taking unusually long to suspend", {
      hops: stats.hops, pending: pending, turns: stats.turns, occurrences: stats.slowActivations
    });
  }

  function createScheduler() {
    var queue = [];
    var head = 0;
    var scheduled = false;
    var retryTimer = null;
    var channel = new MessageChannel();
    var closed = false;
    // clinitSuspend: suspensions of a thread with a class initializer on its stack; clinitWait:
    // waits of a thread for a class that another thread initializes (JVM semantics, design
    // D3); clinitUnwaitable: a JS callback reached such a class and went on without waiting.
    var stats = { posts: 0, slices: 0, maxQueue: 0, turns: 0, hops: 0, parks: 0, frames: 0,
      activationFailures: 0, scheduleFailures: 0, clinitSuspend: 0, clinitWait: 0, clinitUnwaitable: 0 };
    // True from the moment the drain loop resumes (or starts) an activation until that
    // activation suspends again (a suspending import returned a promise) or finishes.
    var turn = { running: false };
    // draining: drain() runs (between its first and last microtask). sliceEnd: when the Java
    // code that runs now has had its slice of the current macrotask (see the yield import).
    var slice = { draining: false, end: 0 };
    // Work posted with postTurn while a drain runs: it goes into the queue when that drain ends,
    // so it runs in a later macrotask and the browser gets its turn in between.
    var later = [];

    function drainFailure(error) {
      console.error(error);
    }

    function scheduleDrain() {
      if (closed || scheduled || retryTimer !== null) {
        return;
      }
      scheduled = true;
      try {
        channel.port2.postMessage(0);
      } catch (error) {
        // A closed/transient MessagePort must not strand the ready queue with scheduled=true.
        // Keep the work queued and retry through a timer macrotask, which also gives the host
        // a chance to replace a failed channel before the next turn.
        scheduled = false;
        stats.scheduleFailures++;
        retryTimer = setTimeout(function () {
          retryTimer = null;
          if (closed || scheduled || head >= queue.length) {
            return;
          }
          scheduled = true;
          drain().catch(drainFailure);
        }, 0);
      }
    }

    function post(fn) {
      if (closed) return false;
      queue.push(fn);
      stats.posts++;
      if (queue.length - head > stats.maxQueue) {
        stats.maxQueue = queue.length - head;
      }
      scheduleDrain();
    }

    // A frame resume: never in the macrotask that ran the previous frame (that would draw two
    // frames without a commit in between), so it waits for the next drain when one runs now.
    // Its resumed activation ends the drain before subsequent queued work delays the commit.
    function postTurn(fn) {
      if (closed) return false;
      fn.endsDrain = true;
      if (slice.draining) {
        later.push(fn);
        stats.posts++;
        return;
      }
      post(fn);
    }

    // Each item either runs synchronously (TeaVM Fiber events) or resumes a JSPI activation
    // (returns true). JSPI resumes the wasm stack from a promise reaction, so the loop waits
    // microtask by microtask until that activation suspends again or finishes; the next item
    // then still runs in this macrotask, in FIFO order.
    async function drain() {
      if (closed) return;
      stats.slices++;
      var end = performance.now() + SLICE_MS;
      slice.end = end;
      slice.draining = true;
      try {
        while (!closed && head < queue.length) {
          var fn = queue[head];
          queue[head] = undefined;
          head++;
          turn.running = true;
          var resumed = false;
          var endsDrain = fn.endsDrain === true;
          try {
            resumed = fn() === true;
          } catch (e) {
            // A failing event must not stall the queue.
            console.error(e);
          }
          if (resumed) {
            stats.turns++;
            var warned = false;
            for (;;) {
              if (!turn.running) break;
              stats.hops++;
              if (!warned && stats.hops % 1024 === 0) {
                warned = true;
                reportSlowActivation(stats, queue.length - head);
              }
              await null;
            }
          } else {
            // Synchronous events have no activation callback to clear the state.
            turn.running = false;
          }
          if ((resumed && endsDrain) || performance.now() >= end) {
            break;
          }
        }
      } finally {
        slice.draining = false;
      }
      if (closed) return;
      if (head === queue.length) {
        queue.length = 0;
        head = 0;
      } else if (head > 1024) {
        queue = queue.slice(head);
        head = 0;
      }
      if (later.length > 0) {
        for (var index = 0; index < later.length; index++) {
          queue.push(later[index]);
        }
        later.length = 0;
        if (queue.length - head > stats.maxQueue) {
          stats.maxQueue = queue.length - head;
        }
      }
      scheduled = false;
      if (head < queue.length) {
        scheduleDrain();
      }
    }

    channel.port1.onmessage = function () {
      drain().catch(function (e) {
        console.error(e);
      });
    };

    return {
      post: post,
      postTurn: postTurn,
      turn: turn,
      pending: function () {
        return queue.length - head;
      },
      draining: function () {
        return slice.draining;
      },
      // A host callback (a timer, an unpark from an event handler, the start of main) is a
      // macrotask of its own: the Java code it resumes gets a fresh slice.
      beginSlice: function () {
        if (!slice.draining) {
          slice.end = performance.now() + SLICE_MS;
        }
      },
      // A frame starts: it gets a slice of its own also when the drain resumed it.
      freshSlice: function () {
        slice.end = performance.now() + SLICE_MS;
      },
      sliceExpired: function () {
        return performance.now() >= slice.end;
      },
      close: function () {
        if (closed) return false;
        closed = true;
        if (retryTimer !== null) {
          clearTimeout(retryTimer);
          retryTimer = null;
        }
        for (var queuedIndex = head; queuedIndex < queue.length; queuedIndex++) {
          var queued = queue[queuedIndex];
          try { if (queued && typeof queued.cancel === "function") queued.cancel(); } catch (_) {}
        }
        for (var laterIndex = 0; laterIndex < later.length; laterIndex++) {
          try { if (later[laterIndex] && typeof later[laterIndex].cancel === "function") later[laterIndex].cancel(); } catch (_) {}
        }
        queue.length = 0;
        later.length = 0;
        head = 0;
        scheduled = false;
        turn.running = false;
        try { channel.port1.onmessage = null; } catch (_) {}
        try { channel.port1.close(); } catch (_) {}
        try { channel.port2.close(); } catch (_) {}
        return true;
      },
      closed: function () {
        return closed;
      },
      stats: stats
    };
  }

  // The wait of HostLoop.awaitAnimationFrame (the awaitFrame import), with the pacing and the
  // window.__gaiusFrameTelemetry counters of the JavaScript build's per-present yield
  // (BrowserGlfw/BrowserSdlDom scheduleFrameYield; performance-metrics.mjs reads the counters
  // and checks their sums), so both builds report frames the same way:
  // - hidden page: a 50 ms timer;
  // - present synchronized to the display (swap interval != 0): the next animation frame,
  //   then one task (a watchdog of 100 ms covers a suspended rAF);
  // - uncapped present: in a world one task; in menus (no level in __gaiusMinecraftState) the
  //   next animation frame first, because a menu frame takes about 1.5 ms and back-to-back
  //   frames starved the compositor.
  // The task is a turn of the run queue (createScheduler.postTurn, the host's one MessageChannel;
  // counted as messageChannelYieldCount), never the macrotask that ran the previous frame, so the
  // browser always gets its turn (input, compositing, the commit of the frame) between two
  // frames. Without a run queue (the unit test) the pacer posts to a MessageChannel of its own.
  // resume runs from that task or timer; the thread then resumes in its microtask checkpoint with
  // a fresh run-queue slice.
  //
  // The wait starts at the head of the Minecraft.run loop, after the frame's own work, which
  // ends with vanilla's frame limiter (FramerateLimiter.limitDisplayFPS sleeps until the 60 fps
  // menu deadline). When that sleep ends just after an animation frame, waiting for the next
  // one would cost a whole refresh. So the waits that are paced by the animation frame do not
  // wait when an animation frame already ran since the thread last resumed (a resume of such a
  // wait watches the next animation frame to find out): still at most one frame per display
  // refresh, as with the JavaScript build's wait right after the present, but a late frame is
  // not delayed further. A world frame with an uncapped present needs no watch.
  //
  // Per frame the pacer makes at most one requestAnimationFrame (shared by the watch and the
  // paced waits) and no timer: one watchdog timer serves every wait and is only re-armed while a
  // wait is pending.
  function createFramePacer(scheduler) {
    var root = globalThis;
    var WATCHDOG_MS = 100;
    var HIDDEN_MS = 50;
    var hasRaf = typeof root.requestAnimationFrame === "function";
    // Visible waits that have not resumed yet, oldest first (the watchdog's list).
    var pending = [];
    // Waits that post their task after the next animation frame.
    var rafWaiters = [];
    var rafRequested = false;
    // An animation frame ran since the last resume (see above); watchArmed: the next animation
    // frame sets it.
    var rafSinceResume = false;
    var watchArmed = false;
    var watchdogTimer = null;
    var closed = false;
    var rafId = null;
    var allWaits = [];
    // The pacer's own MessageChannel (only without a run queue) and its waits in post order.
    var channel = null;
    var channelWaits = [];

    function clock() {
      return typeof performance !== "undefined" && performance.now ? performance.now() : Date.now();
    }

    function telemetryOf() {
      var telemetry = root.__gaiusFrameTelemetry;
      return telemetry && telemetry.enabled ? telemetry : null;
    }

    function requestFrame() {
      if (!closed && !rafRequested) {
        rafRequested = true;
        rafId = root.requestAnimationFrame(function (at) {
          rafId = null;
          onAnimationFrame(at);
        });
      }
    }

    function onAnimationFrame() {
      rafRequested = false;
      if (closed) return;
      if (watchArmed) {
        watchArmed = false;
        rafSinceResume = true;
      }
      if (rafWaiters.length === 0) {
        return;
      }
      var waiters = rafWaiters;
      rafWaiters = [];
      for (var index = 0; index < waiters.length; index++) {
        var w = waiters[index];
        if (w.done) {
          continue;
        }
        var telemetry = w.telemetry;
        if (telemetry && w.presentToRaf) {
          var delay = Math.max(0, clock() - w.requestedAt);
          telemetry.presentToRafCount = (telemetry.presentToRafCount || 0) + 1;
          telemetry.lastPresentToRafMillis = delay;
          telemetry.totalPresentToRafMillis = (telemetry.totalPresentToRafMillis || 0) + delay;
          telemetry.longestPresentToRafMillis = Math.max(telemetry.longestPresentToRafMillis || 0, delay);
        }
        postTask(w);
      }
    }

    function armWatchdog() {
      if (closed || watchdogTimer !== null || pending.length === 0) {
        return;
      }
      watchdogTimer = setTimeout(onWatchdog, Math.max(0, pending[0].since + WATCHDOG_MS - clock()));
    }

    function onWatchdog() {
      if (closed) return;
      watchdogTimer = null;
      var now = clock();
      while (pending.length > 0 && now - pending[0].since >= WATCHDOG_MS - 0.001) {
        if (pending[0].gpuBackpressure) {
          // A GPU completion can legitimately take longer than the generic JSPI watchdog. Keep
          // this frame pending until the gate settles; device loss/terminate resolves that gate.
          pending[0].since = now;
          break;
        }
        finish(pending[0], "watchdog");
      }
      armWatchdog();
    }

    function retireChannel(telemetry) {
      if (channel === null) {
        return;
      }
      try { channel.port1.onmessage = null; } catch (ignored) { /* closed */ }
      try { channel.port1.close(); } catch (ignored) { /* closed */ }
      try { channel.port2.close(); } catch (ignored) { /* closed */ }
      channel = null;
      // Their messages are lost with the channel; the watchdog resumes them if still pending.
      channelWaits.length = 0;
      if (telemetry) {
        telemetry.messageChannelRebuildCount = (Number(telemetry.messageChannelRebuildCount) || 0) + 1;
      }
    }

    function openChannel(telemetry) {
      if (channel !== null || typeof MessageChannel !== "function") {
        return channel;
      }
      try {
        channel = new MessageChannel();
        channel.port1.onmessage = function () {
          var w = channelWaits.shift();
          if (w) {
            finish(w, "message");
          }
        };
      } catch (ignored) {
        channel = null;
        if (telemetry) {
          telemetry.messageChannelCreateFailureCount =
            (Number(telemetry.messageChannelCreateFailureCount) || 0) + 1;
        }
      }
      return channel;
    }

    // Ends the wait once; a later timer, message or animation frame of the same wait finds it
    // done and does nothing. Returns true when it resumed the thread.
    function finish(w, source) {
      if (w.done || closed) {
        return false;
      }
      w.done = true;
      var allIndex = allWaits.indexOf(w);
      if (allIndex >= 0) allWaits.splice(allIndex, 1);
      var index = pending.indexOf(w);
      if (index >= 0) {
        pending.splice(index, 1);
      }
      if (source === "watchdog" && w.channelTask) {
        // The pacer's own channel lost the message.
        retireChannel(w.telemetry);
      }
      var telemetry = w.telemetry;
      if (telemetry) {
        if (source !== "message" && w.taskPosted) {
          telemetry.cancelledMessageTaskCount = (Number(telemetry.cancelledMessageTaskCount) || 0) + 1;
        }
        telemetry.pendingYieldCount = Math.max(0, (Number(telemetry.pendingYieldCount) || 0) - 1);
        var delay = Math.max(0, clock() - w.requestedAt);
        telemetry.yieldCompletionCount = (telemetry.yieldCompletionCount || 0) + 1;
        telemetry.lastYieldResumeDelayMillis = delay;
        telemetry.totalYieldResumeDelayMillis = (telemetry.totalYieldResumeDelayMillis || 0) + delay;
        telemetry.longestYieldResumeDelayMillis = Math.max(telemetry.longestYieldResumeDelayMillis || 0, delay);
        if (source === "message") {
          telemetry.messageChannelYieldCount = (telemetry.messageChannelYieldCount || 0) + 1;
        } else {
          telemetry.timerYieldCount = (telemetry.timerYieldCount || 0) + 1;
          if (source === "watchdog") {
            telemetry.watchdogYieldCount = (telemetry.watchdogYieldCount || 0) + 1;
          }
        }
      }
      rafSinceResume = false;
      if (w.watch && hasRaf) {
        watchArmed = true;
        requestFrame();
      } else {
        watchArmed = false;
      }
      if (typeof w.resume === "function") w.resume();
      return true;
    }

    function postTask(w) {
      if (closed || w.done) return;
      w.taskPosted = true;
      if (scheduler) {
        scheduler.postTurn(function () {
          return finish(w, "message");
        });
        return;
      }
      if (openChannel(w.telemetry) !== null) {
        channelWaits.push(w);
        try {
          channel.port2.postMessage(0);
          w.channelTask = true;
          return;
        } catch (ignored) {
          channelWaits.pop();
          retireChannel(w.telemetry);
          if (w.telemetry) {
            w.telemetry.messageChannelPostFailureCount =
              (Number(w.telemetry.messageChannelPostFailureCount) || 0) + 1;
          }
        }
      }
      w.taskPosted = false;
      w.timer = setTimeout(function () {
        w.timer = null;
        finish(w, "timer");
      }, 0);
    }

    // WebGPU's canvas FIFO can accept far more submissions than the compositor has displayed.
    // In an uncapped world frame, wait for the glue's bounded surface gate before posting the next
    // task. The gate is deliberately outside the synchronous submit import: this keeps command
    // ordering intact while preventing input from following an ever-growing queue of old frames.
    function postAfterSurfaceBackpressure(w) {
      if (closed || w.done) return;
      var gpu = root.GaiusWebGpu;
      var gate = null;
      if (gpu && typeof gpu.waitForSurfaceBackpressure === "function") {
        try { gate = gpu.waitForSurfaceBackpressure(); } catch (_) { gate = null; }
      }
      if (!gate) {
        postTask(w);
        return;
      }
      w.gpuBackpressure = true;
      if (w.telemetry) {
        w.telemetry.surfaceBackpressureWaitCount =
          (Number(w.telemetry.surfaceBackpressureWaitCount) || 0) + 1;
      }
      Promise.resolve(gate).then(function () {
        w.gpuBackpressure = false;
        postTask(w);
      }, function () {
        // Device loss and teardown resolve the gate; a rejected gate must still let JSPI resume so
        // the normal device-unavailable path can report the failure instead of hanging.
        w.gpuBackpressure = false;
        postTask(w);
      });
    }

    // A wait paced by the animation frame whose frame already ran: one task, no rAF wait.
    function frameAlreadyRan(w) {
      if (!rafSinceResume) {
        return false;
      }
      if (w.telemetry) {
        w.telemetry.rafAlreadyRanCount = (Number(w.telemetry.rafAlreadyRanCount) || 0) + 1;
      }
      postTask(w);
      return true;
    }

    function wait(interval, resume, cancel) {
      var telemetry = telemetryOf();
      var hidden = typeof document !== "undefined" && document.visibilityState !== "visible";
      var synchronizedToDisplay = Number(interval) !== 0;
      var w = { resume: resume, cancel: cancel, telemetry: telemetry, requestedAt: telemetry ? clock() : 0, since: 0,
        done: false, timer: null, watch: true, presentToRaf: false, taskPosted: false, channelTask: false };
      allWaits.push(w);
      if (telemetry) {
        telemetry.yieldRequestCount = (telemetry.yieldRequestCount || 0) + 1;
        telemetry.pendingYieldCount = Math.max(0, Number(telemetry.pendingYieldCount) || 0) + 1;
        telemetry.maxPendingYieldCount = Math.max(Number(telemetry.maxPendingYieldCount) || 0,
          telemetry.pendingYieldCount);
        telemetry.duplicateYieldCallbackCount = Number(telemetry.duplicateYieldCallbackCount) || 0;
        telemetry.swapInterval = Number(interval) || 0;
        if (synchronizedToDisplay) {
          telemetry.vsyncYieldCount = (telemetry.vsyncYieldCount || 0) + 1;
        } else {
          telemetry.uncappedYieldCount = (telemetry.uncappedYieldCount || 0) + 1;
        }
        if (hidden) {
          telemetry.hiddenYieldCount = (telemetry.hiddenYieldCount || 0) + 1;
        } else {
          telemetry.visibleYieldCount = (telemetry.visibleYieldCount || 0) + 1;
        }
      }

      if (hidden) {
        w.timer = setTimeout(function () {
          w.timer = null;
          finish(w, "timer");
        }, HIDDEN_MS);
        return;
      }
      w.since = clock();
      pending.push(w);
      armWatchdog();
      if (synchronizedToDisplay && hasRaf) {
        if (frameAlreadyRan(w)) {
          return;
        }
        w.presentToRaf = true;
        rafWaiters.push(w);
        requestFrame();
        return;
      }
      var state = root.__gaiusMinecraftState;
      if ((state && state.level) || !hasRaf) {
        // An uncapped world frame is not paced by the animation frame: nothing to watch.
        w.watch = false;
        var perf = root.__gaiusGL && root.__gaiusGL.perfGl2;
        if (perf && typeof perf.deferPresent === "function" && perf.deferPresent(function () {
          postAfterSurfaceBackpressure(w);
        })) {
          return;
        }
        postAfterSurfaceBackpressure(w);
        return;
      }
      if (frameAlreadyRan(w)) {
        return;
      }
      if (telemetry) {
        telemetry.uncappedRafWaitCount = (Number(telemetry.uncappedRafWaitCount) || 0) + 1;
      }
      rafWaiters.push(w);
      requestFrame();
    }

    function close() {
      if (closed) return false;
      closed = true;
      if (watchdogTimer !== null) {
        clearTimeout(watchdogTimer);
        watchdogTimer = null;
      }
      if (rafId !== null && typeof root.cancelAnimationFrame === "function") {
        try { root.cancelAnimationFrame(rafId); } catch (_) {}
      }
      rafId = null;
      rafRequested = false;
      for (var i = 0; i < pending.length; i++) {
        var w = pending[i];
        w.done = true;
        if (w.timer !== null) clearTimeout(w.timer);
        w.timer = null;
        if (typeof w.cancel === "function") {
          try { w.cancel(); } catch (_) {}
        }
        w.cancel = null;
        w.resume = null;
      }
      for (var j = 0; j < allWaits.length; j++) {
        var hiddenWait = allWaits[j];
        hiddenWait.done = true;
        if (hiddenWait.timer !== null) clearTimeout(hiddenWait.timer);
        hiddenWait.timer = null;
        if (typeof hiddenWait.cancel === "function") {
          try { hiddenWait.cancel(); } catch (_) {}
        }
        hiddenWait.cancel = null;
        hiddenWait.resume = null;
      }
      allWaits.length = 0;
      pending.length = 0;
      rafWaiters.length = 0;
      retireChannel(null);
      channelWaits.length = 0;
      return true;
    }

    return { wait: wait, close: close, closed: function () { return closed; } };
  }

  // gaiusJspi imports for TeaVM's JSPI mode (dev.gaius.browser.JspiHost). Handles are the
  // Java thread slots; 0 is the main thread.
  function installJspi(importObj, scheduler, getExports, onError) {
    if (typeof WebAssembly.Suspending !== "function" || typeof WebAssembly.promising !== "function") {
      throw new Error("JSPI (WebAssembly.Suspending/promising) is not available");
    }
    var states = [];
    var generations = [];
    // A released slot is invalid until the next park/start creates its new generation. Host
    // callbacks can arrive after release; dropping them prevents a stale permit leaking into a
    // thread that later reuses the numeric handle.
    var released = [];
    var runActivation = null;
    var frames = createFramePacer(scheduler);
    // Synchronous JS -> Java calls (JSO methods and @JSFunctor callbacks, exported Java
    // classes, property accessors) run Java on top of a JS frame. A suspending import cannot
    // suspend there: V8 throws SuspendError, a JS exception that skips the Java finally blocks
    // on its way. JspiHost.callbackDepth() lets the class library refuse to block in such a
    // callback (IllegalStateException), as outside any activation.
    var callbackDepth = 0;
    var callbackName = '';
    countJavaCallbacks(importObj.teavmJso);

    function countJavaCallbacks(jso) {
      if (!jso) {
        return;
      }
      // Each TeaVM JSO definition import receives the wasm function that a JS caller ends up
      // calling; the argument indexes of those functions.
      var definitions = {
        defineMethod: [2],
        defineStaticMethod: [2],
        defineFunction: [0],
        defineProperty: [2, 3],
        defineStaticProperty: [2, 3],
        createClass: [2]
      };
      Object.keys(definitions).forEach(function (name) {
        var define = jso[name];
        if (typeof define !== "function") {
          return;
        }
        var indexes = definitions[name];
        jso[name] = function () {
          var args = Array.prototype.slice.call(arguments);
          for (var i = 0; i < indexes.length; i++) {
            args[indexes[i]] = counted(args[indexes[i]]);
          }
          return define.apply(this, args);
        };
      });
    }

    function counted(fn) {
      if (typeof fn !== "function") {
        return fn;
      }
      var wrapper = function () {
        callbackDepth++;
        var previousName = callbackName;
        callbackName = fn.name || 'anonymous';
        try {
          return fn.apply(this, arguments);
        } finally {
          callbackName = previousName;
          callbackDepth--;
        }
      };
      // The JSO glue derives the parameter list of the JS-visible function from fn.length.
      Object.defineProperty(wrapper, "length", { value: fn.length });
      return wrapper;
    }

    function state(handle) {
      var s = states[handle];
      if (s === undefined) {
        var generation = (generations[handle] || 0) + 1;
        generations[handle] = generation;
        s = { resolve: null, reject: null, permit: false, timer: 0, generation: generation, deadline: 0 };
        states[handle] = s;
      }
      released[handle] = false;
      return s;
    }

    function wake(handle, generation, expected, value) {
      var s = states[handle];
      if (s === undefined || s !== expected || s.generation !== generation || released[handle]) {
        return false;
      }
      if (s.resolve === null) {
        s.permit = true;
        return false;
      }
      var resolve = s.resolve;
      s.resolve = null;
      s.reject = null;
      if (s.timer !== 0) {
        clearTimeout(s.timer);
        s.timer = 0;
      }
      s.deadline = 0;
      resolve(value);
      return true;
    }

    function armParkTimer(s, resolve) {
      var remaining = s.deadline - Date.now();
      s.timer = setTimeout(function () {
        s.timer = 0;
        if (s.resolve !== resolve) {
          return;
        }
        if (s.deadline - Date.now() > 0) {
          armParkTimer(s, resolve);
          return;
        }
        s.resolve = null;
        s.deadline = 0;
        scheduler.beginSlice();
        resolve(0);
      }, Math.min(remaining, MAX_TIMER_MS));
    }

    function suspended() {
      scheduler.turn.running = false;
    }

    function run() {
      if (runActivation === null) {
        runActivation = WebAssembly.promising(getExports()["gaius.jspi.run"]);
      }
      return runActivation;
    }

    // An activation that ends with a wasm trap (a null dereference or an array bound in a build
    // without strict checks, a stack overflow, ...) or another JS exception skips the Java
    // finally blocks of TJspiThreadSupport.run: gaius.jspi.abandon releases its thread slot
    // and ends the thread instead (TJspiThreadSupport.abandon).
    function abandon(handle) {
      // getExports returns the instance's exports object, as in run().
      var exports = getExports ? getExports() : null;
      var abandonExport = exports ? exports["gaius.jspi.abandon"] : undefined;
      if (typeof abandonExport === "function") {
        try {
          abandonExport(handle);
        } catch (e) {
          console.error(e);
        }
      }
      states[handle] = undefined;
    }

    function activation(handle) {
      run()(handle).then(suspended, function (e) {
        suspended();
        scheduler.stats.activationFailures++;
        // Keep the rejection observable after the promise reaction has been consumed. The
        // normal thread error callback remains the integration hook for the page/Worker.
        globalThis.__gaiusJspiActivationError = e;
        abandon(handle);
        try {
          onError(e);
        } catch (callbackError) {
          console.error("Gaius: JSPI activation error callback failed", callbackError);
        }
      });
      return true;
    }

    importObj.gaiusJspi = {
      park: new WebAssembly.Suspending(function (handle, timeoutMillis) {
        var s = state(handle);
        scheduler.stats.parks++;
        if (s.permit) {
          s.permit = false;
          return 1;
        }
        suspended();
        return new Promise(function (resolve, reject) {
          s.resolve = resolve;
          s.reject = reject;
          if (timeoutMillis > 0) {
            s.deadline = Date.now() + timeoutMillis;
            armParkTimer(s, resolve);
          }
        });
      }),
      unpark: function (handle, expectedGeneration) {
        if (released[handle]) {
          return;
        }
        var s = states[handle];
        if (s === undefined) {
          if (expectedGeneration !== undefined) {
            return;
          }
          s = state(handle);
        }
        if (expectedGeneration !== undefined && s.generation !== expectedGeneration) {
          return;
        }
        var generation = s.generation;
        if (s.resolve === null) {
          s.permit = true;
          return;
        }
        if (!scheduler.draining()) {
          // A host callback (a frame callback, an I/O completion): resume the thread from this
          // callback's microtask checkpoint instead of one MessageChannel hop later. Inside the
          // drain the queue keeps FIFO order and the slice budget.
          scheduler.beginSlice();
          wake(handle, generation, s, 1);
          return;
        }
        scheduler.post(function () {
          return wake(handle, generation, s, 1);
        });
      },
      yield: new WebAssembly.Suspending(function (handle) {
        // Nothing else is runnable and the slice is not over: keep running. Once the thread has
        // had its slice it suspends behind a queued resume even with an empty queue, so the
        // drain ends the slice and the browser (timers, input, messages) gets a turn: a thread
        // that spins on Thread.yield() waiting for one of them still sees it happen.
        if (scheduler.pending() === 0 && !scheduler.sliceExpired()) {
          return 0;
        }
        suspended();
        return new Promise(function (resolve, reject) {
          var resume = function () {
            resolve(1);
            return true;
          };
          resume.cancel = function () {
            reject(new Error("Gaius JSPI scheduler closed"));
          };
          scheduler.post(resume);
        });
      }),
      // HostLoop.awaitAnimationFrame: interval is the swap interval of the last present.
      awaitFrame: new WebAssembly.Suspending(function (handle, interval) {
        scheduler.stats.frames++;
        suspended();
        return new Promise(function (resolve, reject) {
          frames.wait(interval, function () {
            scheduler.freshSlice();
            resolve(1);
          }, function () {
            reject(new Error("Gaius JSPI frame pacer closed"));
          });
        });
      }),
      clinitEvent: function (kind) {
        var stats = scheduler.stats;
        if (kind === CLINIT_SUSPEND) {
          stats.clinitSuspend++;
          globalThis.__gaiusClinitSuspensions = (globalThis.__gaiusClinitSuspensions | 0) + 1;
          if (!globalThis.__gaiusFirstClinitSuspension) {
            globalThis.__gaiusFirstClinitSuspension = String(new Error("class initializer suspended").stack);
          }
        } else if (kind === CLINIT_WAIT) {
          stats.clinitWait++;
        } else {
          stats.clinitUnwaitable++;
        }
      },
      start: function (handle) {
        state(handle);
        scheduler.post(function () {
          return activation(handle);
        });
      },
      release: function (handle) {
        var s = states[handle];
        if (s && s.timer !== 0) {
          clearTimeout(s.timer);
          s.timer = 0;
        }
        if (s) {
          s.deadline = 0;
        }
        states[handle] = undefined;
        released[handle] = true;
      },
      callbackDepth: function () {
        return callbackDepth;
      },
      callbackName: function () {
        return callbackName;
      }
    };

    return {
      // Runs the program's main method as the activation of the main thread (handle 0).
      // TeaVM's own "main" export is a JS wrapper and cannot be wrapped in promising.
      // The returned promise settles when the main method returns or throws.
      // When main returns, its turn ends like a thread activation's (activation): if its last
      // resume came through the run queue (a Thread.yield or park of main, for instance the
      // integrated server waiting for its IndexedDB fallback storage), the drain still waits for
      // that turn, microtask by microtask. Left running, the drain never yields to the event loop
      // again and nothing queued behind it, the threads main started included, ever runs.
      startMain: function (args) {
        globalThis.__gaiusJspiMainArgs = args || [];
        state(0);
        scheduler.beginSlice();
        return run()(0).then(function (value) {
          suspended();
          return value;
        }, function (e) {
          suspended();
          abandon(0);
          throw e;
        });
      },
      close: function () {
        if (frames && typeof frames.close === "function") frames.close();
        if (scheduler && typeof scheduler.close === "function") scheduler.close();
        for (var index = 0; index < states.length; index++) {
          var s = states[index];
          if (!s) continue;
          if (s.timer !== 0) clearTimeout(s.timer);
          s.timer = 0;
          s.deadline = 0;
          var reject = s.reject;
          s.resolve = null;
          s.reject = null;
          if (typeof reject === "function") {
            try { reject(new Error("Gaius JSPI host closed")); } catch (_) {}
          }
          states[index] = undefined;
          released[index] = true;
        }
        runActivation = null;
      },
      states: states
    };
  }

  // TeaVM EventQueue events (teavmAsync.offer, for example HostLoop.schedule): zero-delay
  // events go through the run queue, in FIFO order with thread resumptions, instead of a
  // clamped setTimeout; delayed ones keep TeaVM's setTimeout. Queued ids are negative.
  // kill (HostLoop.killSchedule) cancels both kinds.
  function routeEventQueue(importObj, scheduler) {
    var nextId = 0;
    var queued = new Set();
    var stock = importObj.teavmAsync;
    var routed = {
      offer: function (instance, fn, time) {
        if (time - Date.now() > 0) {
          return stock.offer(instance, fn, time);
        }
        var id = -(++nextId);
        queued.add(id);
        scheduler.post(function () {
          if (queued.delete(id)) {
            fn(instance);
          }
          return false;
        });
        return id;
      },
      kill: function (id) {
        if (id < 0) {
          queued.delete(id);
        } else {
          stock.kill(id);
        }
        return 0;
      },
      close: function () {
        queued.clear();
        if (stock && typeof stock.close === "function") return stock.close();
        return false;
      }
    };
    importObj.teavmAsync = routed;
    globalThis.__gaiusWasmEventQueue = routed;
  }

  // The scheduler of this host (the one createScheduler returned last), so that host code outside the
  // run queue can ask for its backlog: the tick parity settle probe must not call a Worker idle while
  // started or resumed activations (for example the continuation of a bounded chunk-generation layer)
  // are still queued behind the timer that woke it.
  var current = null;

  globalThis.GaiusJspi = {
    createScheduler: function () {
      current = createScheduler();
      return current;
    },
    // Items queued and not yet run: resumed or started activations and zero-delay events. The
    // activation that runs now has already left the queue.
    pendingRuns: function () {
      return current === null ? 0 : current.pending();
    },
    installJspi: installJspi,
    routeEventQueue: routeEventQueue,
    // The frame wait of the awaitFrame import, for port/tests/jspi/frame-pacer-test.mjs
    // (createFramePacer(scheduler); without a scheduler it posts to a MessageChannel of its own).
    createFramePacer: createFramePacer
  };
})();
