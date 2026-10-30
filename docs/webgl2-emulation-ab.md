# WebGL2 emulation A/B switches

These experiments are opt-in and apply to the WebGL2 emulation layer
(`BrowserOpenGL`) of the WasmGC client, which renders when WebGPU is not
selected or unavailable (force it with `?gpu=webgl2`). The WebGPU backend does
not go through this layer. Select profiles through `port/versions/*.json`, not
implementation constants.

| URL switch | Experiment |
| --- | --- |
| `gaiusGlLargeBuffers=1` | GPU generations preserve pending large buffers before partial writes. |
| `gaiusGlGlue=1` | Wasm numeric binding and per-program uniform caches; redundant bind filtering. |
| `gaiusGlLazyFbo=1` | Defer framebuffer restores; cache quality FBOs by complete attachment set. |
| `gaiusGlVaoCache=1` | Cache VAO attributes, enables and divisors; avoid unmap upload bind round trips. |
| `gaiusGlGpuPace=1` | Experimental GPU queue backpressure; throughput can regress. Not a promotion gate. |
| `gaiusGlFrameBudget=1` | Smaller wasm compile/upload/install budgets; needs newly compiled wasm. |
| `gaiusGlAB=1` | Install the adapter with experiments off for runtime A/B. |
| `gaiusGlVerify=1` | Expensive GPU-byte and committed draw-state checks, separate from measurements. |

Switches also accept `true` and `on`. Runtime control is
`window.__gaiusGL.perfGl2.setFlags({buffers, glue, fbo, attrib, pace})`.
Telemetry is `window.__gaiusGLStats.perfGl2`.
`BrowserOpenGL.publishPerfCounters()` publishes wasm aggregates; upload
accounting also publishes periodically.

## Correctness boundaries

The large-buffer threshold applies to every target. Draws, copies, pixel-pack
reads and pixel-unpack uploads mark pending allocations. Rotation copies the
complete GPU layout and repairs generic/indexed bindings, inactive transform
feedback objects and VAO references. Recycling requires a signaled zero-timeout
fence and compatible size and element classification. Retired references have
bounded count and byte accounting; deletion is native deferred GL deletion.
Logical handles are never lent to another buffer by the recycling pool.
The older uniform adapter keeps ownership of its allocations. Active transform
feedback declines rotation and preserves native behavior.

Wasm holds numeric identities, uniform handles including missing locations,
and aggregate counters. JS retains WebGL objects, physical generations and
external quality-pass shadows. Only external binds invalidate wasm binding
caches; matching Java-origin binds do not call back into wasm. Linking,
deletion, context loss and A/B transitions invalidate affected state.

FBO consumers include draw, clear, blit, read, attachment mutation,
invalidation and status checks. Queries report logical bindings. Attachment
keys include texture identity, point, target, level and layer. Incomplete sets
are rejected, not cached. Texture storage must remain valid for the set; this
is not a general renderbuffer cache.

GPU pacing flushes nonblockingly, polls without waiting, and releases held
callbacks on disable or bounded timeout. It does not drop frames. The separate
frame-budget experiment preserves first-task progress, so an indivisible task
may still exceed its budget.

## Validation gates

```text
node port/scripts/browser-opengl-perfgl2-smoke.mjs
node port/scripts/browser-opengl-perfgl2-java-smoke.mjs <output-directory>
node port/scripts/quality-gl-shadow-smoke.mjs
node port/tests/jspi/frame-pacer-test.mjs
```

The JVM harness uses cached jars, `javac -J-Xmx3g` and `java -Xmx3g`, writing
only below its output argument. Model/JVM checks are not full-client acceptance.
Native GL bytes, in-world state, screenshots and compiled-client checks remain
distinct gates. Source injection cannot exercise new Java caches or budgets.

Keep measurement reports and traces outside the source tree. Pixel comparisons
must first pass off/off stability. Different simulation/animation times are
not an equivalence oracle. Trace command-buffer waits as well as explicit GL
queries, GC and install/pump bursts.
