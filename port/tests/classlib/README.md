# Class library conformance suite

`dev.gaius.classlib.Main` prints one `key = value` line per case. `runner/run.mjs` compiles it for
the JVM (the reference), TeaVM's JavaScript backend and TeaVM's WebAssembly GC backend, runs every
section on each runtime, and compares each runtime's lines with the JVM's. A difference passes only
when `differences.txt` registers it with a reason; a register entry that matches nothing fails the
run too, so the register stays exact.

```sh
# overlay directories of build-overlays.sh for the js and the wasmgc target (TeaVM as the product
# compiles it: the patched teavm-core and teavm-classlib jars)
node port/tests/classlib/runner/run.mjs --out <dir> \
  --js-overlays <js-overlays> --wasm-overlays <wasmgc-overlays> [--variants jvm,js,wasm,wasm-strict]
```

Every TeaVM compile runs with `-Xmx3g`, one at a time, from the local Maven repository (nothing is
downloaded); a full run takes about a minute. Node 24 runs the JavaScript output (`runner/run-js.cjs`)
and the WebAssembly GC output with JSPI (`runner/run-wasm.cjs`, `--experimental-wasm-jspi`), with
the product's runtime files: `postprocess-teavm-js.py` for the JavaScript output,
`patch-wasm-gc-runtime.mjs` and `gaius-jspi.js` for the WebAssembly GC output. The TeaVM options are
the product's client role (`generate-pom.sh`): ADVANCED and minified for JavaScript, SIMPLE with
`gaius.teavm.jspi=true` for WebAssembly GC. `wasm-strict` is the WebAssembly GC build with TeaVM's
strict mode, for the decision below.

Output in `<dir>`: `report.json` (every difference with both values, crash details, timings),
`differences.txt` (readable), `values-<variant>.txt`.

## Sections

| section | what |
|---|---|
| `hashmap`, `hashcode` | HashMap/HashSet/ConcurrentHashMap iteration order for value-hash keys (ints, longs, strings, doubles, records, lists, UUIDs, BlockPos-like keys) after put, remove, copy, putAll, collectors; the hash codes themselves |
| `format` | `String.format` as vanilla uses it, rounding, flags, errors |
| `floatstr` | `Float/Double.toString`, concatenation, parsing, round trips |
| `math`, `strictmath` | `Math`/`StrictMath` bit for bit; the `BrowserStrictMath` helpers on the cases of `port/native/golden/strict/StrictMathCheck.java` |
| `trunc` | narrowing conversions: NaN, infinities, out-of-range values (WebAssembly `trunc_sat`) |
| `ints`, `random`, `sort`, `character`, `strings`, `date`, `misc` | Integer/Long, `java.util.Random`, sort stability and PriorityQueue ties, Character over all code points, case mapping and charsets, `Date.toString` (HotSpot format), UUIDs, Base64, CRC32, big numbers |
| `streams` | stream results whose size the class library must know in advance: `sorted()` over sources whose estimate counts the remaining elements (the `filter().sorted().toList()` of `LevelStorageSource.loadLevelSummaries`), spliterators that are not SIZED or estimate more than `Integer.MAX_VALUE`, `distinct()` across the separate pulls of `iterator()`, `skip()` and `concat()`; the end of a stream's `spliterator()` (`spliteratorEnd`: `tryAdvance()` must return false once nothing is left, and `Spliterators.iterator` over it must not repeat the last element, the Guava `mapWithIndex` path of vanilla's NBT list streams that loaded the last entity of every chunk twice); the overlay's `java.util.stream` classes fix all of them (TeaVM 0.15 trapped on WebAssembly GC and returned wrong results in both builds) |
| `views` | read-only queries of `Collections.unmodifiableMap/Set/Collection` and of the `Collectors.toUnmodifiableMap/Set` results (hits, misses, null keys and values, colliding hashes, live views, mutators that throw); `TeaVMClasslibPatcher.patchUnmodifiableViewLookups` forwards them to the wrapped collection as the JDK does instead of TeaVM's linear `AbstractMap`/`AbstractCollection` scans (`bench` times the model-discovery shaped `unmodifiableLookup`) |
| `trap.<case>` | implicit checks: null dereference, array bounds, casts, array stores, integer division, stack overflow; one process per case, so a trap ends only that case |
| `repro.*` | the TeaVM 0.15 bug repros of M0 S1 (`WasmBugs`: two code generation bugs; `ResTest`: resource read offset; `BufTest`, `BufTest2`: three direct ByteBuffer bugs; `CatchRepro`, `SyncRepro`: S5 shapes; `WeakRefNull`: the runtime's `WeakRef(null)`), of T0 (`BufCompare`, `ResCompare`, `SumProbe`) and of M2 (`DirectZero`: `allocateDirect` memory is zeroed after earlier direct buffers were freed, the WebAssembly GC branch handed out stale heap blocks; `FilesDirs`: `Files.createDirectories` of an existing one-name directory returns quietly and of a file throws `FileAlreadyExistsException`) |
| `bench` | timing of array, field, cast and HashMap kernels (informative lines only) |

Inputs come from `Rand`, the specified `java.util.Random` algorithm written out, so every runtime
draws the same inputs even though TeaVM's own `Random` differs. Every NaN prints as `NaN` (Java
does not specify NaN payloads). `ReproMain` holds the repros alone: `--main
dev.gaius.classlib.ReproMain --wasm-overlays stock --no-register` compiles them with the unpatched
TeaVM jars, where the WebAssembly module fails validation (`WasmBugs::report3`, invalid
`br_on_cast`), which the overlay patches fix.

APIs that TeaVM 0.15's class library lacks are build errors, not runtime differences, and are not
in the suite: `String.lines()`, `Base64.getMimeEncoder()`, `Math.multiplyHigh`,
`Math.unsignedMultiplyHigh`, `Character.getDirectionality/isMirrored/isIdeographic`. The product
redirects such vanilla calls in `JdkCompatPatches263` (`BrowserJdkCompat`).

## Follow-ups (entries marked FIX in differences.txt)

`repro.filesdirs#001` and `#005` are class-library bugs, not test differences: the JVM returns an
existing directory and throws `FileAlreadyExistsException` for a file. The shared
`TeaVMClasslibPatcher` now applies the former wasm-only prologue to both targets. This intentionally
changes the internal JavaScript overlay semantics: vanilla's second chunk IO error report creates
the existing `debug` directory and must not fail. Rebuild only the small suite with fresh overlays
to verify it; do not register these two failures as acceptable.

Behaviour that the product should change, most important first. All are in the TeaVM class library
or code generation that both builds share, unless the line says otherwise.

1. WebAssembly GC integer division and remainder: `i32/i64.div_s` and `rem_s` without checks, also
   in strict mode. Division by zero traps instead of throwing `ArithmeticException`, and
   `MIN_VALUE / -1` (also inside `Math.floorDiv`) traps instead of returning `MIN_VALUE`.
2. WebAssembly GC `UUID.nameUUIDFromBytes` (every offline-player UUID): TeaVM's MD5 computes its
   constants with a saturating `(int)` cast that only the JavaScript output wraps. Fix: `(int) (long)`.
3. WebAssembly GC `double %` and `float %`: computed as `a - b * trunc(a / b)`. Use an exact fmod
   (the JavaScript `%` operator is exact).
4. `HashMap`/`HashSet` iteration order (both builds): the overlay `THashMap` and `THashSet` now
   follow the JDK's spreading, tail insertion, growth and tree-bin order
   (`port/scripts/classlib-hashmap-order.test.mjs` compares them with the JVM); `LinkedHashMap`
   keeps TeaVM's behaviour. Record hash codes as HotSpot computes them are still to do; the
   `hashmap.*` and `hashcode.*` entries of `differences.txt` were registered before this change.
5. `java.util.Random` bounded draws and `nextBytes` (both builds): the JDK algorithms
   (`Collections.shuffle(list, random)` follows).
6. `Math.ulp`, `Math.round`/`rint` near 0.5, `Double.toString` shortest output, `%.Nf` HALF_UP
   rounding of the decimal digits, `%d` of `Long.MIN_VALUE`, `Date.toString` in HotSpot format,
   UTF-8 malformed input, `String.split("")`, `StringBuilder.setLength`, case mapping without
   special casing on WebAssembly GC.

## TeaVM strict mode for the WebAssembly GC builds (recommendation)

Without strict mode the WebAssembly GC output has no null, bounds or cast checks: a null
dereference, an out-of-bounds index or a failing cast traps (`RuntimeError`). A trap cannot be
caught in WebAssembly, so the Java thread ends instead of throwing `NullPointerException`,
`ArrayIndexOutOfBoundsException` or `ClassCastException`; under JSPI the monitors that thread holds
are never released. The JavaScript build behaves differently again: a null dereference becomes a
`RuntimeException` that `catch (Exception)` blocks see, and out-of-bounds reads give 0. With strict
mode (`wasm-strict`) all three exceptions are thrown as on the JVM (`trap.npe-*`, `trap.aioobe-*`,
`trap.cce` equal the JVM). Strict mode does not cover integer division (follow-up 1), stack
overflow, negative array sizes, array stores or casts to array types.

Measured on this suite (node 24, `run.mjs --sections bench --jobs 1`, six alternating runs, best
of each): module size +6.2 % (1.72 MB to 1.83 MB); a bounds-check-bound 3x3 blur over an int array
+55 to 60 %; checked casts and interface calls about +20 %; linked-list field walks and HashMap
merges unchanged.

Recommendation: build the WebAssembly GC client and server with strict mode on. Vanilla relies on
catching these exceptions where input is not trusted (packet and NBT decoding, resource and data
pack loading, command parsing, `catch (Exception)` around listeners): there a non-strict build
kills a thread, and possibly the session, where the JVM and the JavaScript build recover. Confirm
the cost on the real client in one build train before switching (size against the WP8 limit, title
and singleplayer frame times, worldgen chunks per second; the array kernel above is the worst case
of tight loops). If the cost is too high, the fallback is to keep strict mode off and give the
untrusted-input paths (packet, NBT and resource decoding) explicit checks, rather than leaving
them without any.

Status: the product build scripts (`generate-pom.sh`, `build-wasmgc.sh`) do not set `teavm.strict`,
so the WebAssembly GC client and server are built without strict mode.

### Measured on the 26.3 client (M1 wave 2)

One 26.3 client release (`build-wasmgc.sh --release 26.3` at 7c000825, ADVANCED) with strict mode,
set as the Maven user property `teavm.strict` (`MAVEN_ARGS="--offline -Dteavm.strict=true"`, the
`${teavm.strict}` parameter of teavm-maven-plugin 0.15), against the same release without it (the
T1 train build). The A/B pages differ only in `classes.wasm`; Chrome 154, hardware GPU, one Chrome at
a time with no TeaVM build running:

| | non-strict | strict | change |
|---|---|---|---|
| module, raw (no name section) | 61.78 MB | 65.45 MB | +5.9 % |
| code section | 21.24 MB | 24.77 MB | +16.6 % |
| gzip-9 / brotli-11 | 22.57 / 15.50 MB | 23.42 / 15.96 MB | +3.8 % / +2.9 % (still below `classes.js.br`, 17.15 MB) |
| first screen, cold, 5 runs (median) | 8.81 s | 10.00 s | +1.19 s; `compileStreaming` 0.58 → 0.79 s, `main` returns 2.23 → 2.57 s |
| title screen at 60 Hz, main-thread task time | 81.8 ms/s | 84.7 ms/s | +3.5 % |
| title screen uncapped | 737.5 fps | 730 fps | -1 % |
| in world (seed 20261004), uncapped, 3 runs (median; JS server Worker) | 120.1 fps, p95 17.7 ms | 116.7 fps, p95 17.8 ms | -2.8 % fps; frames without a tick 6.64 → 6.89 ms |
| warm start to the title screen | 10.10 s | 10.39 s | +0.3 s |

Strict mode costs about 3 % of in-world frame rate and of the compressed download, and about 1.2 s
of a cold start: 0.34 s until `main` returns (0.21 s of it compiling) and 0.86 s more in the
game's own startup code up to the first screen. A warm start (same profile, cached files) loses
0.3 s. That is small against what it fixes, so the recommendation stands: build the WebAssembly GC
client and the singleplayer server Worker with strict mode on. The server matters most (packets from LAN guests, NBT from saves, commands:
a trap there ends the world's thread). The server Worker and worldgen throughput under strict mode
are not measured yet (no strict server build in this round). Strict mode still leaves integer
division (`/` and `%` by zero, `MIN_VALUE / -1`) as traps, which needs the code generation fix of
follow-up 1 either way.
