# Host ABI of the WebAssembly GC build (JSPI)

This file is the contract between Java code compiled for WebAssembly GC (TeaVM 0.15,
`gaius.teavm.jspi=true`) and the JavaScript host: the rules every JS -> Java
callback must follow, the imports and exports of the thread host (`gaiusJspi`,
`port/web/runtime/wasmgc/gaius-jspi.js`), and the audit of the callbacks that exist today.
The release client is WasmGC and uses this ABI for its browser host integration.

## How Java threads run

- Every Java thread, the main thread included, is one `WebAssembly.promising` activation of the
  export `gaius.jspi.run(handle)`. Blocking primitives (contended `synchronized`,
  `Object.wait`, `Thread.sleep`/`yield`/`join`, `LockSupport.park`, TeaVM `@Async` methods,
  `HostLoop.awaitHost`, `HostLoop.awaitAnimationFrame`) call a `WebAssembly.Suspending` import
  and suspend the whole wasm stack of that activation; no method is compiled as a coroutine
  (the build log says `jspi: async analysis skipped, 0 coroutine methods`).
- Green threads run one at a time on the agent. The run queue of `gaius-jspi.js` resumes
  them in FIFO order inside a MessageChannel macrotask for at most 4 ms, then yields to the
  browser. Its MessageChannel is the only one of the host: the frame resumes of `awaitFrame`
  and the render pump's slices (`HostLoop.yieldToEventLoop`) are turns of the same queue.
  A resumed frame ends its drain even if time remains in the slice; subsequent queued work
  waits for another macrotask instead of delaying that frame's commit.
- A suspending import only works when nothing but wasm frames lie between it and the
  activation entry. Java code reached from JavaScript in any other way, a **JS -> Java
  callback**, has no stack it may suspend.

## Hard rules for JS -> Java callbacks

1. **A callback never blocks; it only posts.** A `@JSFunctor` implementation, a JSO method or
   property that JavaScript calls, a JSBody that calls back into Java synchronously, a TeaVM
   `EventQueue` event (`HostLoop.schedule`, `HostLoop.postMacrotask`) and a `@JSExport`/`@Export`
   method all run outside any activation, or above a JS frame inside one. There they may only
   record state, complete an `AsyncCallback`, `LockSupport.unpark` a thread or start one
   (`HostLoop.startThread`, `Thread.start`). Long or blocking work belongs to a Java thread.
   - What happens otherwise: `Thread.sleep`, `Object.wait`, `LockSupport.park`,
     `HostLoop.awaitHost` and a **contended** monitor throw `IllegalStateException`
     ("... outside a Java thread activation (a JS callback into Java cannot suspend ...)" or
     "Can't enter monitor from another thread synchronously"). `Thread.yield`,
     `LockSupport.parkNanos(<=0)`, `HostLoop.yieldToEventLoop` and
     `HostLoop.awaitAnimationFrame` return at once. `HostLoop.canSuspend()` is false there.
   - The host counts the synchronous callbacks (`JspiHost.callbackDepth`, wrapped TeaVM JSO
     definitions), so this also holds for a callback that JavaScript makes while an activation
     runs; V8's `SuspendError` never reaches Java.
   - A class that another thread is initializing cannot be waited for in a callback: the
     callback reads it as it is and the case is counted (`clinitUnwaitable`, below).
2. **No new thread per frame.** Starting a thread allocates an activation stack, about 10 us
   under JSPI (M0 S5). A callback that fires per frame, per packet or per chunk hands work to a
   long-lived thread (a queue and `LockSupport.unpark`) instead of `new Thread(...).start()`.
3. **`Thread.yield()` gives way only to runnable threads.** It returns at once while the run
   queue is empty and the current 4 ms slice is not over; after the slice it suspends behind a
   queued resume, so the browser (timers, input, messages) gets a turn even for a spinning
   thread.
4. **Only numbers cross the hot ABI.** Per-frame imports and exports take numbers, `(ptr, len)`
   into TeaVM's linear memory and i32 handles; strings are for the control plane.
5. **The frame boundary is the head of the `Minecraft.run` loop.** The patched loop
   calls `HostLoop.awaitAnimationFrame()` (`MinecraftClientPatcher.patchMinecraftRunFrameBoundary`,
   WebAssembly GC only); a present (`SDL_GL_SwapWindow`, `glfwSwapBuffers`) does not yield and
   only records its swap interval (`HostLoop.framePresented`). `BrowserStartupScheduler` makes no
   artificial yields in this build.
6. **Class initialization follows the JVM.** A thread that requests a class while another thread
   runs (and may be suspended in) its initializer waits until the initializer ends; a recursive
   request from the initializing thread returns at once. The guards are generated around every
   dynamic class initializer (`GaiusTeaVMOptions.wasmWrapMethodBody`, build log line
   `jspi: class initializers wait for their initializing thread`). An initializer that throws
   ends the initialization as well (TeaVM keeps the class as the initializer left it; the JVM
   would throw `NoClassDefFoundError` on later use). The only initializer without a guard is
   that of `java.lang.JspiThreadSupport` itself, which runs before any thread exists.

## `gaiusJspi` imports (`dev.gaius.browser.JspiHost`)

| Import | Kind | Signature | Meaning |
|---|---|---|---|
| `park` | Suspending | `(handle i32, timeoutMillis f64) -> i32` | Suspend until `unpark` or the timeout (<= 0: none); 1 unparked or a pending permit, 0 timeout. Spurious returns allowed. |
| `yield` | Suspending | `(handle i32) -> i32` | Rule 3; 1 when it suspended. |
| `awaitFrame` | Suspending | `(handle i32, interval i32) -> i32` | Frame boundary (rule 5): hidden page 50 ms; `interval != 0` next animation frame then one task (100 ms watchdog); uncapped: one task in a world, the next animation frame in menus. The task is a turn of the run queue that never shares the macrotask of the previous frame, so the browser gets its turn (and commits the frame) between two frames. A wait paced by the animation frame posts one task instead when an animation frame already ran since the thread last resumed (vanilla's frame limiter slept past it; `rafAlreadyRanCount`). Per frame at most one `requestAnimationFrame` (none for an uncapped world frame) and no timer: one watchdog timer serves all waits. Updates `window.__gaiusFrameTelemetry` like the JavaScript build's per-present yield (a run-queue turn counts as `messageChannelYieldCount`). |
| `unpark` | plain | `(handle i32) -> ()` | Resume a parked activation (from a host callback: right away; inside the run queue: queued), or leave a permit. |
| `start` | plain | `(handle i32) -> ()` | Queue a new activation for a thread slot. |
| `release` | plain | `(handle i32) -> ()` | The thread of the slot finished. |
| `callbackDepth` | plain | `() -> i32` | Synchronous JS -> Java calls on the stack (rule 1). |
| `clinitEvent` | plain | `(kind i32) -> ()` | Telemetry: 0 suspension with a class initializer on the stack, 1 wait for another thread's initializer, 2 a callback that could not wait. |

Exports: `gaius.jspi.run(handle)` (activation entry, wrapped in `WebAssembly.promising`),
`gaius.jspi.abandon(handle)` (ends a thread whose activation trapped), and
`gaius.jspi.clinitEnter(id) -> i32` / `gaius.jspi.clinitExit(id)` (exported only to keep them
for the generated initialization guards; JavaScript does not call them).

Telemetry: `window.__gaiusJspiScheduler.stats` holds `posts, slices, maxQueue, turns, hops,
parks, frames, clinitSuspend, clinitWait, clinitUnwaitable`; `clinitSuspend` also counts into
`globalThis.__gaiusClinitSuspensions`, the counter the JavaScript build's thread.js keeps and
`browser-file-entry-acceptance.mjs` reports (first stack in `__gaiusFirstClinitSuspension`).

## Linear-memory data transfer (`dev.gaius.browser.JsArrays`, M1 WP4)

Per-frame GL and AL data (uniforms, `clearBufferfv`, `drawBuffers`, the terrain multi-draw and
AL `bufferData`) crosses into JavaScript as a range of the linear memory instead of a typed
array copied from a Java array.

- **Views**: `globalThis.__gaiusLinear = {memory, buffer, u8, i8, i16, u16, i32, u32, f32, f64,
  dv, refreshes, refresh()}` covers the whole `WebAssembly.Memory`
  (`globalThis.__gaiusWasmMemory`). `JsArrays.installLinearViews()` (wasmgc implementation)
  installs it; `BrowserOpenGL.initialize()` and the first AL `bufferData` call it.
- **Refresh**: `memory.grow` detaches the old buffer and every view over it reads length 0. Each
  linear JSBody takes its view as `L.f32.length ? L.f32 : L.refresh().f32`, so it is correct
  without a hook; `gaius-wasm-boot.js` also passes `memory.onResize` to `TeaVM.wasmGC.load`,
  which refreshes the views as soon as TeaVM's `Heap` grows (`notifyHeapResized`).
- **Address and length**: a linear JSBody takes an `int` byte address (8-byte aligned; element
  index `address >>> 2` or `>>> 3`) and an element count (a byte count for AL). A direct
  little-endian buffer passes `MemoryUtil.memAddress(buffer)` at its position; a Java array or
  any other buffer is staged with `JsArrays.stageInt8/16/32/stageFloat32` in one reused scratch
  area (TeaVM `Heap`, doubled on demand), whose address is valid until the next
  `scratch`/`stage` call. Nothing suspends between staging and the JS call, so JSPI threads do
  not interleave there.
- **`JsArrays.float64`** stages the doubles in that area too and returns a `Float64Array` view
  cached per length (the packed telemetry publishers of WP3): no typed array per call.
- **JavaScript build**: `JsArrays.LINEAR_MEMORY` is a compile-time constant, `false` there and
  `true` in the wasmgc build. javac drops the other branch, so the JavaScript bytecode of these
  entry points is unchanged and keeps the zero-copy `fromJavaArray` views; the wasm bytecode
  has only the linear branch. Rule e of `port/scripts/quickcheck/wasmgc_rules.py` checks the
  same split on the sources.

## Audit of the JS -> Java callbacks (M1 WP2, 2026-10-04, base d8179050)

"Posts" means the callback only records state, completes a callback, unparks or starts a
thread. "Runs work" means it runs Java work that does not block in practice but would throw
under contention (rule 1). Entries marked **action** need a change by the owner of the file.
The audit is a record of 2026-10-04; the WebAssembly GC server Worker has shipped since, and the
**action** entries are still open in the code at the time of writing (a new thread per result in
`BrowserLightKernel` and `BrowserWorldgenKernel`, `TScheduledThreadPoolExecutor.runScheduled` and
`openLanServerConnection` run in the callback; Open to LAN is a known limitation of the release,
`docs/release-v0.4.0.md`).

| Entry point | Called from | Java work | Verdict |
|---|---|---|---|
| `TJspiThreadSupport.AsyncResult` (`HostLoop.awaitHost` resume, TeaVM `@Async` callbacks such as `TXHRURLConnection.performRequest`) | any host callback | completes, `JspiHost.unpark` | posts |
| `BrowserGlfw/BrowserSdlDom.FrameYieldCallback` | rAF / MessageChannel / timer | completes the JavaScript build's per-present yield; unused on WebAssembly GC (rule 5) | posts |
| `HostLoop.MacrotaskCallback` (`src/main` HostLoop) | MessageChannel | JavaScript build only; the WebAssembly GC HostLoop posts through TeaVM's EventQueue | n/a |
| `MeshKernelBridge.Wake` (`onWake`) | mesh kernel worker message | `HostLoop.startThread(deliverResults)` | posts; one host task per wake: keep wakes coalesced (rule 2) |
| `LightKernelHost.ResultCallback/FailureCallback` (`BrowserLightKernel`) | light kernel worker message | `runOnJavaThread`: a new daemon `Thread` per result | posts; **action** (rule 2): a long-lived consumer thread instead of a thread per chunk result |
| `BrowserWorldgenKernel.ChunkCallback/IdsCallback/ErrorCallback` (`WorldgenKernel262/263`) | worldgen kernel worker message | `runOnJavaThread`: a new daemon `Thread` per result | posts; **action** (rule 2), as above (server Worker, M2) |
| `BrowserOpenGL.MappedBufferReleaseCallback` (`releaseAllMappedBuffers`) | `webglcontextlost/restored` events | clears the mapped-buffer table, frees storage | runs work (no monitor, no wait) |
| `BrowserClientNetwork.BrowserPumpCallback` (`pumpInbound`) | MessageChannel / timer of the inbound pump | posts one bounded Netty transport decode turn (`BrowserWebSocketChannel.pumpAllAndReportProgress`) with `HostLoop.startThread` | posts; the decode turn and any continuation run in a JSPI activation, so contended Netty/vanilla locks may park safely |
| TeaVM EventQueue events: `HostLoop.schedule` / `postMacrotask` tasks | timer / run queue | `BrowserCompressionDecoder` pump (Netty decode), `BrowserRenderScheduler` frame watchdog (state, unpark), `BrowserResourceReloadScheduler.runAfterYield` (submits reload batches), `BrowserWebSocketChannel` outbound retry (`flush`), `BrowserSingleplayerClient` ready poll (starts a thread), `TModernRuntimeSupport` resumes (complete) | the watchdog, the ready poll and the resumes post; the decoder pump and the outbound flush run work as `pumpInbound` does |
| `TScheduledThreadPoolExecutor` (`HostLoop.schedule` -> `runScheduled`) | timer | runs the vanilla scheduled `Callable` in the timer callback | **action**: a vanilla task may block (contended monitor, `join`), so the callable belongs on a Java thread (`HostLoop.startThread`); the JavaScript build has the same limit ("non-threading context") |
| `BrowserIntegratedServerMain` `@JSExport` methods | server Worker messages | `setIntegratedServerDistances`, `signalIntegratedServerNetworkInput`/`pumpIntegratedServerNetworkInput` (queue a server task, unpark), `stopIntegratedServer` (`halt(false)`), `isIntegratedServerStopped`, `completeIndexedDbFallbackHydration` post or read state; `openLanServerConnection` runs `ServerConnectionListener.openAdditionalBrowserConnection` in the callback | **action** for `openLanServerConnection` (WP10, M2): run the Netty bootstrap on the server thread (queue a server task) |
| DOM input listeners (`BrowserGlfw`, `BrowserSdlDom`) | DOM events | none: JSBody listeners queue events in JavaScript, Java polls them | n/a |
| `gaius.jspi.run`, `gaius.jspi.abandon` | `gaius-jspi.js` | activation entry; slot release after a trap (outside any activation, posts only) | by design |

Covered by `port/tests/jspi` (semantic suite): blocking in a `@JSFunctor` callback and in a
synchronous JSBody callback (IllegalStateException, the thread keeps working), a contended
monitor in a callback, a callback that reads a class under initialization, and the frame
boundary from a callback (returns at once).
