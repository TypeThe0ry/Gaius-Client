# Region storage compatibility regression

## Root cause and fix boundary

The integrated WasmGC classlib fix is `TeaVMClasslibWasmGcPatcher.zeroAllocateDirect`:
after the `Heap.alloc` null check, it calls `Address.fillZero(address, capacity)`.
The unpatched WasmGC allocator returns reused bytes or allocator free-list metadata.
Vanilla `RegionFile` allocates an 8192-byte direct header and derives its location and
timestamp views from that buffer. An empty file does not initialize those bytes by
reading them. The first chunk write then persists the entire header, including garbage
locations for chunks that do not exist. Their advertised sectors produce truncation
errors on reopening, including in the frozen JS reader.

That fix is already present in the integrated source baseline. These tests verify it
without duplicating the patch or modifying the frozen JS build, storage namespaces,
region parsing, sector padding, or compression. Substituting only the old classlib JAR
into the current RegionFile harness is a failing negative control. The smaller classlib
repro exposes nonzero direct buffers and phantom location-table entries explicitly.
An old full-client Wasm artifact remains broken until rebuilt with the patched classlib.
Existing corrupt region headers are not repaired by zeroing future allocations.

## Coverage

`region-storage-parity.mjs` runs the actual vanilla `RegionFile` from each selected
profile's local named JAR. Each writer produces eight regions and 32 chunks containing
9000 deterministic payload bytes each. It checks all 8160 absent location and timestamp
entries, verifies sector bounds and padding, reopens every file, and checks
all payload bytes and EOF. It compiles small JS and WasmGC/JSPI probes with the selected
product overlays and compares six directed JVM/JS/Wasm reader pairs per profile.

The TeaVM probes replace only the unrelated `Util.getEpochMillis` bootstrap and
`ChunkPos` support class with narrow test implementations. `RegionFile`, compression,
FileChannel, ByteBuffer and BrowserFilePersistence remain product code. The independent
JVM writer/readers use the unpatched vanilla JAR and its real bootstrap, without these
support replacements. No Minecraft profile ID is embedded in the probes.

The Node bridge is a synchronous byte-store fixture. Its persisted region bytes must
equal the bytes reopened through the Java filesystem. It is not IndexedDB/OPFS evidence.

`region-storage-browser-gate.mjs` reuses those probe binaries in one real Chrome and
uses the current product Worker bootstrap for Wasm and the unmodified frozen Worker
bootstrap for JS. Both directions run against real OPFS sync handles and real IndexedDB.
For the IndexedDB cases only, the test Worker hides `navigator.storage.getDirectory` to
exercise the actual unavailable-OPFS fallback. Each reader gets a fresh Worker after
the writer's storage flush and clean close; no in-memory file map is transferred.
OPFS cases require patch writes, flushes and reconstruction of all regions. A silent
fallback does not count as OPFS acceptance. The gate exports the Java-visible region
bytes for an additional unpatched vanilla JVM read.

The test-only Worker supplies no-op integrated-server lifecycle hooks: there is no
game server, and the probe has already closed its RegionFiles. These tests do not cover
world generation, NBT semantics, client startup, game-server final save, or whole-world
Save & Quit. Reports deliberately keep `browserWorldGatePassed: false`; a component
PASS is not the M2 whole-world storage gate.

## Run offline

Use fresh output directories. All compiler dependencies must already exist in the local
Maven cache. Compiles are sequential and capped at `-Xmx3g`; no full client is compiled.
Set `GAIUS_JAVA_HOME` (or `JAVA_HOME`) to a JDK that satisfies each selected profile's
`javaVersion`, and `GAIUS_CHROME_BIN` (or `CHROME_BIN`) to the Chrome executable for the browser gate.
All artifact and profile paths below are inputs, not locations to modify.

`--wasm-overlays` and `--js-overlays` take comma-separated overlay roots (the
`build-overlays.sh` output of the WasmGC and of the JavaScript build, see
`GAIUS_OVERLAY_DIRECTORY` in `docs/releasing.md`). Each root is either the overlay
directory itself or a directory holding `wasm-<profile>` / `js-<profile>` subdirectories;
`--frozen-js-dist` takes the frozen JavaScript distributions the same way.

```powershell
$out = '<out>/storage'
$wasm = '<wasm-overlays>'
$js = '<js-overlays>'
$frozen = '<frozen-js-dist>'
node port/scripts/region-storage-parity.mjs --out "$out/parity" --wasm-overlays $wasm --js-overlays $js
node port/scripts/region-storage-browser-gate.mjs --out "$out/browser" --probes "$out/parity" --frozen-js-dist $frozen
node port/scripts/region-storage-parity.mjs --out "$out/verified" --wasm-overlays $wasm --js-overlays $js --browser-exports "$out/browser"
```

If your setup has a machine job runner that limits concurrent browser jobs, start the
browser gate through it.

The last invocation also independently reads every browser writer export (four per
profile: two storage backends, two writer runtimes) with the unpatched JVM. It recompiles the small probes, not the client. `--profile <id>` can
restrict the Node harness; the browser harness follows its input report's profiles.

The focused classlib regression needs only the cached TeaVM dependencies (this loop expects
`$wasm` to hold `wasm-<profile>` subdirectories):

```powershell
foreach ($file in Get-ChildItem -LiteralPath port/versions -Filter '*.json' -File) {
  $profile = (Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json).id
  node port/tests/classlib/runner/run.mjs --out "$out/classlib-$profile" --variants jvm,wasm --wasm-overlays "$wasm/wasm-$profile" --main dev.gaius.classlib.ReproMain --sections repro.directzero,repro.regionheader --jobs 1
}
```

Do not pass `--no-register` for a positive regression: every output must equal the JVM.
For a negative control, supply a known pre-fix overlay to that command, or use
`region-storage-parity.mjs --profile <id> --wasm-classlib <pre-fix classlib JAR>` with the
current remaining overlays. Both commands must fail; preserve their logs separately.

## Whole-world release gate

When the main release build has current client/server artifacts, reuse its Wasm dist and
run the existing gate for every profile in `port/versions/*.json`, with its matching frozen
JS dist. Never replace the frozen JS reader to make a gate pass.

```text
node port/scripts/storage-compat-gate.mjs --profile <id> --wasm <wasm dist> --js <matching frozen JS dist> --out <fresh storage output directory>
```

Full builds, if required, should go through the machine job runner when your setup has one.
Missing build artifacts or a failed memory preflight are an external acceptance blocker, not
grounds to claim a whole-world PASS.
