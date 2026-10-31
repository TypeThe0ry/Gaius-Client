# Exact section compiler golden fixtures

This headless harness invokes the selected named vanilla jar's actual
`SectionCompiler.compile`, block model renderer, fluid renderer, biome zoom and
`ClientLevel.calculateBlockTint`. It never launches a client, OpenGL, Chrome or
TeaVM. The adapters are selected by `kernelSemantics.code`, not version strings.
The JVM's `DefaultVertexFormat.BLOCK` stride must match the profile's
`kernelSemantics.meshVertexStride` before any fixture is accepted.

## Corpus and oracle

Every enabled mesher profile has 300 deterministic sections: 30 each of solid,
fluid, waterlogged, transparent/translucent, connected multipart, weighted
variants, biome tint, height edges, mixed and empty. Sections include dense
adjacent clusters, boundary blocks and a two-block neighbor margin. Inputs
exercise source/flowing/falling water, lava, waterlogged fences, glass, ice,
panes, connected fences, leaves, grass and slabs; AO/smooth lighting and cutout
leaves toggle independently. Mixed biome quart grids use vanilla biome zoom,
three tint palettes and blend radii zero through two. Height-edge sections
cover the bottom and top of the synthetic world's build range.

Baked geometry and sprite metadata are controlled synthetic inputs, not a
resource-pack loading acceptance test. The vanilla `SingleVariant`,
`WeightedVariants` (unequal weights) and `MultiPartModel` implementations perform
model selection; only the geometry provider is a fixture. Actual vanilla block
states supply occlusion/fluid/shape behavior. There is no kernel-derived vertex
oracle and no sorting or float normalization of the captured JVM vertex bytes.
Opaque layers retain their original emission order. Translucent index bytes
come from vanilla `MeshData.sortQuads` at the same fixed camera as the kernel;
the comparator checks the emitted vertex order and expands the kernel's quad
permutation into exact 16/32-bit JVM draw indices.

The snapshots deliberately do not cover all default resource-pack models,
block entities, custom tint callbacks or modded renderer extensions. Unmodeled
states remain unsupported. Passing this corpus does not prove full asset-pack
coverage or in-game rendering acceptance.

## Run offline

Use cached named client jars and their `classpath.txt` under `port/work`.
The producer stages modern sources with the canonical source-set resolver, including
profile token binding, type relocations and the selected MeshVersionOps implementation.
It records both original input hashes and compiled source hashes in the fixture manifest.
Java and javac are limited to a 2 GiB heap. The producer uses one Cargo job with locked
offline dependencies; the outer kernel build driver uses two jobs. All profiles run sequentially.

```powershell
$env:GAIUS_MESH_GOLDEN_OUT = '<out>/mesher'
bash port/native/check.sh --mesh-only --verify
```

By default this builds only the mesher crate's production ABI SIMD and baseline
wasm variants in the output directory, using the release build flags. These are
explicitly reported as `built-candidate`, **not** shipped-client evidence. To
check the actual shipped modules, set `GAIUS_MESH_DIST` to the existing release
boot distribution (or its `kernels` directory). Its `kernels.json` must bind
both modules' sizes and SHA-256 values. No full client build is started here.

```powershell
$env:GAIUS_MESH_DIST = '<existing-release-boot-dist>'
bash port/native/check.sh --mesh-only --verify
```

`--verify` independently recompiles/runs the JVM harness into `reproduce/` and
requires every fixture and source digest to reproduce. `--no-golden` with
`--mesh-only` reuses fixtures, but rejects changed profile, harness or jar
identities. Normal `check.sh` includes the mesher suite unless `--no-golden` is
selected; comparator and M2 regression tests still run without JVM fixtures.

For a single profile, run `port/scripts/mesh-jvm-wasm-golden.mjs` with `--profile`,
`--out`, `--target` and `--dist`. `--generate` writes new JVM data; `--native-only`
executes only native comparisons. Neither mode can satisfy M2's shipped-wasm
requirement by itself.

`check.mjs --profile <id>` (repeatable) selects profiles instead of every enabled one.
A selected profile whose mesher gate is off with a recorded
`kernelMirror.quarantined.mesher` reason runs with `--quarantined`: the kernel modules
are compared exactly as for an enabled profile, and `report.json` records
`gate: {enabled: false, quarantined: <reason>}`. This keeps parity evidence for a
quarantined profile without editing its version file; lifting the gate changes the
profile hash, so its fixtures are regenerated afterwards.

```powershell
node port/native/golden/mesh/check.mjs --out <dir> --dist <release-client-dist> --profile 26.2
```

## Artifacts and declines

Each profile output contains `fixtures/table.bin`, 300 `.job` snapshots and raw
per-layer `.vertices`/`.indices` files, plus `sections.json`, `declines.json` and
`fixtures.json`. The latter pins the vanilla jar, profile, harness sources and
every fixture file. `native/` retains each native result. `report.json` records
native/SIMD/baseline comparisons, counts, mismatch offsets, reasons and module
identities. `reports.json` aggregates profiles. These bulky binary artifacts
stay in the caller-selected output directory rather than the shared jar
junction or Git tree.

The ABI currently emits 28-byte BLOCK vertices. A profile requiring 32-byte
vertices with normals cannot be exact: every non-air section declines before
publishing geometry, preserving vanilla compilation. Empty sections still
compare exactly. `declines.json` registers the incompatible layout and each
encountered state with a reason. Such a correctness pass means safe fallback,
not accelerated meshing parity or performance. Unknown semantic layouts also
fail closed. Unsupported-state declines require an explicit registry reason,
an unsupported table flag, a matching interior state and a head-only result.

M2 requires all 300 cases in native and both wasm variants, coverage metadata,
exact bytes, native payload identity and registered declines. It rejects
candidate artifacts as shipping proof. It also rereads hashed source, profile,
fixture, native output and release module evidence, verifies the native vertex
comparison, and binds module paths to the selected release `build.dist`.
