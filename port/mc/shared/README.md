# Vanilla-facing source sets

The Gaius sources that differ between Minecraft profiles live here, keyed by what changes (the
render seam generation, the patch set), never by a version id. A new profile JSON whose fields are already known compiles without a
source change.

`sourcesets.json` lists the sets and the profile fields that select each one:

| Set | Selected by | Holds |
|---|---|---|
| `canonical` | every profile | sources written against the canonical render seam generation (`canonicalRenderSeam`, the oldest non-LTS generation of the support window); profile fields are bound for every profile and types are relocated for newer generations |
| `renderpearl-1` | `renderSeam` `renderpearl-1` | small semantic adapters, remaining generation-specific sources, `excludes.txt`, and `relocations.tsv` |
| `legacy-12111` | `patchSet` `legacy-12111` | compatibility sources and excludes; intentional duplicates have explicit P9 exceptions |

Each set has up to two kinds of sources, laid over a shared root:

| Kind | Directory | Shared root | Consumer |
|---|---|---|---|
| `src` | `<set>/src/{java,excludes.txt}` | `port/src/main/java` | `generate-pom.sh` stages the TeaVM source tree |
| `client` | `<set>/client/{java,excludes.txt}` | `port/overrides/client/src/main/java` | `build-overlays.sh` compiles the client overrides |

## Resolution

For a profile, kind and TeaVM target: the shared root; then every selected set in manifest order,
first dropping the relative paths its `excludes.txt` lists (each must name a file resolved so far,
so a stale entry fails), then adding its `java/` files (a file replaces the one with the same
relative path); then the target root (`src/target-<target>/java` next to the shared root). Last,
the `relocations.tsv` of every selected set is applied to the code of each file that set did not
provide: `<canonical type>\t<new type>\t<reason>` moves or renames a type in imports and qualified
names, never in comments, strings, chars or text blocks. An explicit import also renames its
simple type references (including nested-type references). Wildcard imports do not authorize
simple-name rewrites: use an explicit import or a qualified type. Instance member contract
changes belong in an ops adapter, not a global textual member rename.

`profileFields` binds one code token in one source path to an integer profile field. The token
must occur exactly once in code; missing fields and stale tokens fail. `MeshProfile.PROFILE`
comes from `kernelSemantics.code`, not a version-named constant. `compileExcludes` selects
compiler-only exclusions by profile fields and target; both `generate-pom.sh` and the canonical
link check consume this list. Legacy identity and platform-smoke exclusions are unchanged.

`port/tools/sourcesets/sourcesets.mjs` implements this (`list`, `stage`, `path`, `check`,
`self-test`, `compiler-excludes`); `port/scripts/quickcheck/javasrc.py` mirrors file selection for the lints.
Smokes read a profile's copy of a file with `sourceFile(profile, kind, relativePath)` from that
module instead of naming a directory.

```
node port/tools/sourcesets/sourcesets.mjs check                       # every profile resolves
node port/tools/sourcesets/sourcesets.mjs list --profile <id> --kind src --target wasmgc
```

## A new Minecraft version

A profile whose `renderSeam` and `patchSet` are known selects its sets with no edit here. When it
does not compile:

- a vanilla type moved or changed its name: add a line to the generation's `relocations.tsv`;
- a member or semantic contract changed: add a small generation ops implementation;
- a genuinely different source contract: add the file (or an `excludes.txt` line) to the generation set, or start a new set
  with its own selector when the profile opens a new render seam generation.

When the support window slides, the canonical set moves forward one generation: apply that
generation's relocations to the canonical sources once (mechanical rename), move its remaining
files over, and point `canonicalRenderSeam` at it, so the relocation chain stays at most one long.

## Single-source ops

`BrowserProfile`, `MeshKernelHooks`, `BrowserTerrainBatchGlue`, `BrowserUnihexLoader`,
`MeshProfile` and the light kernel bridge (`BrowserLightKernel`, `BrowserLightStateTable`) each
have one canonical source. The three ops interfaces have statically selected instances: the
canonical factory type is relocated to the generation implementation, while the source-set
exclude removes the unused implementation. No runtime version test is involved.

- `VersionOps`: glyph upload overload, optional linear-memory vertex copy (`false` keeps the
  existing Java-array path, and a short native copy still fails closed), the server's structure
  template manager accessor, the PNG decode call and the pause menu column span of the Open to
  LAN button. `BrowserStructurePreloader` and `BrowserFontBitmapCache` are single sources that go
  through the first two, `BrowserLanSession` through the last (its invite falls back to the
  running client's own version id). `BrowserGpuBufferPoolCache` is a single source whose
  `GpuBuffer` import follows the generation's relocation.
- `MeshVersionOps`: AO permeability, fluid-flow blocker, shaded face and uniform upload method.
- `LightVersionOps`: the light dampening accessor of a block state and the `ChunkPos`
  coordinates and packed key; `legacy-12111/relocations.tsv` selects `LightBlockLightOps`
  (`getLightBlock`, `ChunkPos.x`/`z`/`asLong`) instead of the canonical `DampeningLightOps`.
- Package/type-only differences stay in `relocations.tsv`, including profile-service types and
  the dynamic GPU data type. The legacy mesh exclusions remain; its glyph adapter is canonical.

## Compile and link gate

After building overlays, run the gate for each target. `--overlay-root` expects directories named
`<profile-id>-<target>`; alternatively use `--profile <id> --overlay-dir <directory>`.

```
node port/tools/sourcesets/canonical-link.mjs --target js --overlay-root <dir> --out <report-dir>
node port/tools/sourcesets/canonical-link.mjs --target wasmgc --overlay-root <dir> --out <report-dir>
node port/tools/sourcesets/test-canonical-link.mjs --out <test-dir>
node port/tools/sourcesets/test-version-ops.mjs --out <test-dir>
```

The gate stages relocated/profile-bound sources, compiles the selected runtime with javac, and
scans canonical runtime/client classes plus the selected ops with `LinkGapScanner`. Linkage is
checked against the matching patched vanilla overlay: Gaius-added vanilla members require it.
The raw vanilla jar hash is recorded separately; this is not a claim that Gaius's added ABI links
against an unpatched vanilla jar. Client override classes must come from that overlay build.
Only local Maven artifacts are read; missing inputs stop the gate without downloading. Each
attempt keeps compiler/link logs, unresolved-symbol TSV, suggestions TSV and a JSON report.
Any compiler error or unresolved symbol fails. Suggestions are candidates to inspect, never
automatic proof of semantic equivalence. This verifies JVM compile/link compatibility, not a
full TeaVM build, browser rendering or kernel golden parity.

The injected-gap test checks missing classes, methods and fields and verifies repaired linkage.
The ops JVM fixture tests both targets for every profile, covering upload argument identity,
all Boolean mesh predicate combinations, every face/override/shade combination, profile codes,
uniform callbacks and short-copy rejection. Fixture results are distinct from vanilla execution.

## P9 similarity gate

```
python3 port/scripts/quickcheck/lint_source_similarity.py --warn --out <report.json>
python3 port/scripts/quickcheck/lint_source_similarity.py --enforce
python3 port/scripts/quickcheck/test_lint_source_similarity.py
```

The gate compares Java files across manifest source sets, shared roots and target roots. It
ignores comments/whitespace but retains literal values and identifiers. Similarity is the larger
of the two directed token SequenceMatcher ratios, with autojunk disabled; files need not have
matching names. A score >= 80% fails in enforce mode unless the exact pair has a reason in
`similarity-exceptions.json`. Same-set pairs are not P9 violations. Invalid, duplicate and stale
exceptions fail even in warning mode. CI runs `--enforce`; no unresolved generation
clone remains, and the exceptions are individual reasons, not blanket exemptions. The twelve
allowed pairs are the tiny mesh and light ops pairs, six legacy-compatibility pairs and four
generation pairs that are not copies (the worldgen hook interfaces and the world seed and biome
zoom accessors that patchers add by name, the two worldgen kernel bridges, and the pack resource
overrides of two differently named vanilla classes), listed with their individual reasons in the
file.

JS overlay regression uses `overlay-golden-diff.py diff <base> <new> --products --strict-added`:
every decompressed archive entry must be byte-identical, including line-number tables. ZIP
timestamps/compression/order are packaging metadata and are excluded by this existing gate.
Moving TeaVM runtime sources does not itself preserve the final compiled JavaScript client;
that separate artifact is not rebuilt by the overlay checks.
