# Off-thread structure lookup regression

Seed `-7327290349473744307`, with a player near block `30000,30000`, reaches a snowy
village in chunk `1876,1886`. Entity variant selection calls `StructureCheck.test`,
which queries `ServerChunkCache.getChunk` from the world-generation thread through
the structure stream. Vanilla hands the lookup to the server thread and waits.

The browser must suspend that calling thread until the lookup completes. Replacing
the off-thread `CompletableFuture.join` is not sufficient if the JavaScript Worker
compiler still forces `Iterator.hasNext` and `Iterator.next` to be synchronous.
The Worker excludes only the iterator async barrier; client defaults stay unchanged.
WebAssembly GC already uses no async barriers and needs the cooperative lookup patch.

`offthread-structure-check.positions` covers the failing chunk and its four adjacent
chunks. Run it with a profile supported by the full-mode JVM harness, using the
cached vanilla jars. No downloads are needed:

```sh
node port/scripts/worldgen-seed-parity.mjs --profile "$profile" \
  --seeds "-7327290349473744307" --modes full \
  --positions port/scripts/worldgen-seed-parity/cases/offthread-structure-check.positions \
  --out-dir "$out/jvm-$profile"
```

To compare a freshly patched JVM overlay, add `--patched "$overlay"`.

Each catalog profile requires an independent check. The full-mode JVM harness uses
the current worldgen/server APIs; older profiles can need a compatible harness and
the remapped vanilla input for an obfuscated client distribution. A harness compile
failure is not a seed-parity mismatch or proof that the browser fix works.

The bounded TeaVM regression uses the production Worker options, class library and
future pump. It first reproduces the old iterator-barrier failure, then checks the
stream lookup, single execution on the server thread, completed and failed futures,
and a host callback that cannot suspend:

```sh
node port/scripts/server-offthread-wait-teavm-smoke.mjs
```

For actual browser Workers, rebuild only the server payload into an isolated output
directory. Keep the client and existing release output unchanged. Run the capture
with each internal server build (`js` is the JavaScript build used for parity tooling, `wasm` the WasmGC
server), through your machine job runner if your setup has one:

```sh
node port/scripts/worldgen-seed-parity/browser-world.mjs \
  --dist "$dist" --seed "-7327290349473744307" --out "$out/browser-$runtime" \
  --runtime "$runtime" --server-runtime "$runtime" --far 30000,30000 \
  --settle-s 60 --unload-s 90 --timeout-s 900
```

A passing small fixture or bytecode check is not evidence that the full Worker ran.
Inspect the capture's `farClient`, Worker errors and saved regions, then compare the
saved terrain with vanilla using `worldgen-seed-parity.mjs --saved`. Full-chunk block
hashes can differ after ticking; the saved-terrain comparison excludes those changes.
