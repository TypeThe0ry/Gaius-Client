# Screenshot save regressions

Use an output directory outside `port/work`. The runners use only existing vanilla jars,
the local Maven cache and Java with a 3 GiB heap; they do not download dependencies or start Chrome.

First retain an independently patched copy of every JSON-selected profile and target:

```sh
TEMP=<output>/patchkit-tmp TMP=<output>/patchkit-tmp \
node port/tools/patchkit/patchkit.mjs check \
  --profile <profile-id> --target wasmgc --target js --out <output>/patchkit --keep --quiet
node port/tests/screenshot/run.mjs --out <output>/screenshot --patchkit-scratch <retained-scratch>
node port/tests/screenshot/run-browser.mjs --out <output>/browser-save \
  --patchkit-scratch <retained-scratch> --profile-file port/versions/<profile-id>.json
```

The `PATCHKIT_SCRATCH` line it prints names the retained scratch directory (without `--write`, the
checked-in patch manifests stay untouched). Repeat the patchkit profile option for each desired
profile. The JVM runner discovers all
profile JSON files and checks both targets. It loads the actual patched `NativeImage` from
the retained scratch copy, calls its public `writeToFile(Path)` and independently decodes PNG
output with ImageIO. It checks all four component layouts, CRCs, inflated pixel bytes, channel
ownership, short writes, invalid input and write failures. A 1000 by 790 RGBA image has
3,160,000 source bytes. Only the native allocation shim and Tracy fixture are test substitutes.
ASM checks keep the vanilla file/channel lifecycle and the entity constructor's vanilla random
draws intact while excluding the unsupported native PNG callback.

The small JS/WasmGC harness uses the actual patched TeaVM classlib, PNG encoder, file channel,
file persistence and WasmGC loader. It covers 2 by 1 and 1000 by 790 RGBA saves, close-triggered
persistence, startup restoration in a fresh process, transient-file exclusion and persistence
failure propagation. The Node host independently checks PNG dimensions and every inflated byte.
It supplies a deliberate in-memory persistence mirror, not IndexedDB or OPFS; the pointer shim
is not a test of the real WasmGC GPU allocation/readback path.

After compiling once, runtime profile storage settings can be checked without another compile:

```sh
node port/tests/screenshot/run-browser.cjs wasm <output>/browser-save/wasm \
  port/versions/<profile-id>.json large
node port/tests/screenshot/run-browser.cjs wasm <output>/browser-save/wasm \
  port/versions/<profile-id>.json restore-large
```

Full-game F2 on both graphics backends, real browser storage and restart restoration require
a fresh distribution and a real browser run. These regressions do not substitute for that gate.
