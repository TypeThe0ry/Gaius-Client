# New Minecraft Version Bring-up Guide

## 1. Inputs and boundaries

`port/versions/<id>.json` is the entry point for version selection. Protocol, world version, Java release,
library coordinates, seam, patchSet, source sets, kernelSemantics and the shader harness
recipe are all determined by profile data; do not add Minecraft version branches in Java, Rust or JS.

Prepare inputs with the existing `fetch-version.sh` and `remap-client.sh` first, then start the fast checks.
Nothing is downloaded on this machine; missing inputs or offline dependencies for a REQUIRED stage are recorded as blocking and are not fetched on your behalf.
Missing baseline evidence (native entry, relay, lint, API, golden or corpus) that is used only for optional diff review
is recorded as review: keep the old table or disable the corresponding kernel, and state the next step.
A new version directory contains at least:

| Path | Purpose |
|---|---|
| `port/work/<id>/client-named.jar` | Mojang names, embedded `version.json`, class headers |
| `port/work/<id>/client-original.jar` | Official input, verified against the SHA-1 in the launcher metadata |
| `port/work/<id>/version.json` | Launcher metadata, releaseTime, coordinates and library SHA-1 values |
| `port/work/<id>/libraries/` | Offline jar/native libraries matching artifact.path in the metadata |
| `port/work/<previous>/` | Previous version jar and metadata, used for migration comparison |

`port/work` may be a shared junction: treat it as read-only. Do not traverse it recursively, modify it or clean it.
The offline Maven cache needs the TeaVM JSO/interop and existing ASM artifacts configured in this repository;
the offline Cargo cache needs the packages in `port/native/Cargo.lock`. Anything missing is blocking.
Set `GAIUS_JAVA_HOME` / `JAVA_HOME` to select a JDK that can handle the target class release;
set `GAIUS_MAVEN_REPOSITORY` to use a specific offline cache.

## 2. Fast path: no TeaVM compilation

```bash
node port/tools/bringup/gaius-bringup.mjs <id> --fast
node port/tools/bringup/gaius-bringup.mjs <id> --fast --from <previous> --out <output-directory>
```

`--from` pins the migration baseline; when omitted, the nearest existing profile is chosen by official releaseTime,
and only when no date is available by numeric-aware ID order. A snapshot ID need not be a plain numeric version.
A temporary draft is marked `bringup.provisional=true` and cannot serve as a previous version or a reviewed recipe;
`bringupAllowed` only permits a debug mode with a skip list. It is not a review status and must not be conflated with one.
`--root` / `--work` are for isolated copy directories; the output refuses to point at the shared work directory or its junction target.

By default it produces `port/mc/profiles/<id>/bringup-report.json` and `.md`, plus
`profile.draft.json`, a separate `bringup-evidence-*` directory and an offline Cargo target.
It does not install or overwrite `port/versions/<id>.json`, does not rewrite old locks, does not run profilegen
`--write`, and does not change the default JavaScript dist.

It runs the following in order:

1. JarProbe reads protocol, world/pack versions and the
   Java release from the jar's inner `version.json` and class major; extracts library coordinates and official SHA-1 values from the launcher metadata, and builds an isolated storage namespace.
   The single known seam generation is matched by the existence of all SPI classes in `seam.json`.
   The nearest reviewed recipe of the matched generation is inherited, with all kernels initially disabled.
2. Confirm the named Minecraft entry exists, verify the SHA-1 of the original jar and of every library,
   and run the draft profile schema/seam checks. Compare the named/original version metadata; for an official
   named distribution, equivalence is also verified by the SHA-256 of sorted class contents, allowing stripped jar signatures.
   This does not re-run the remapper; an obfuscated distribution does not claim a per-method mapping proof.
3. Compile a small seam adapter and run seamprobe; compare the aggregate signature/body with the
   16-hex detail fingerprints. Also list classes that vanished from the previous jar, unique simple-name relocation suggestions,
   and owners rewritten by the previous patch manifests that have vanished. Suggestions are not proof of an automatic rename.
4. Run extrefs, comparing section + coordinate + class/method/field usage; a profile ID,
   section count or text header is not mistaken for a usage change.
5. patchkit checks both the js and wasmgc targets on isolated copies using the draft and this run's usage lock,
   and diffs each against the previous reviewed manifest; it does not build the whole client.
6. kernelmirror verifies the profile SHA-256 of the previous lock, recomputes the mirrored methods of the previous/current seam recipes and
   compares the locks; a change in methods, semantics or dependencies automatically disables the corresponding kernel, which falls back to vanilla Java.
   A kernel already disabled in the previous profile is not re-enabled just because of a new lock; disabling propagates to downstream dependents.
7. Following the profile's `bringup.shader` recipe, dump the real shader corpus on the JVM, doing a
   frontend link when needed; run pass/fail with the native Rust translator. Cargo uses
   `--offline --locked -j 2`; the Java heap does not exceed `-Xmx3g`.
8. sourcesets resolves src/client × js/wasmgc and outputs a file/source/relocation inventory.
   Successful resolution does not mean Java compilation succeeds.

The following lightweight reviews are also part of `--fast`; no TeaVM game build or Chrome is needed:

| Stage | Offline evidence and tooling | Classification and degradation on failure or missing evidence |
|---|---|---|
| `native-entry` | Reuses the extrefs `usage.lock` to produce the entry diff for the native-library section and the seam `requiredEntries` check, written to `native-entry.json` | An optional change or missing evidence is review, keeping the existing adapter; only a missing declared REQUIRED entry is blocking; does not claim native/browser smoke passes |
| `relay-protocol` | `port/scripts/build-relay-protocol.mjs` validates the profile against `apps/bridge/protocols/<protocol>.json` and generates a standalone `relay-protocol.js` and `relay-protocol.json` | review; keep the previous reviewed table; the new profile does not enable multiplayer support on this basis |
| `version-lint` | `port/scripts/quickcheck/lint_version_literals.py --enforce`, covering verification scripts, written to `version-literals.json` | review; report the files actually hit, then fix by deriving defaults from the profile or adding a justified exact exemption |
| `apidiff` | Reuses the extrefs `apilock` to check the `api.lock` of library overrides, with section, coordinate and class/method/field usage diffs, producing `apidiff.json` | An API change or missing baseline/tool evidence is review; locate the adapter, patch or library recipe by owner |
| `kernel-golden` | Reuses `port/native/golden/run-golden.sh --verify <id>` and `port/native/fixtures/<id>/`, regenerates fixtures in a separate `GOLDEN_BUILD_ROOT` and compares hashes with the previous version file by file, writing `kernel-golden.diff.json` | A diff, verification failure or missing fixture is review/auto-disabled; if the impact cannot be located or fully verified, all kernels are conservatively disabled, staying on vanilla Java |
| `shader-corpus` | Extracts the previous version's corpus offline with the existing dump/link recipe and forms `shader-corpus-baseline.json` from the pipeline/module/layout summaries against this run's JVM corpus | A diff or missing baseline is review; keep the reviewed corpus and review again once the evidence is filled in |

Only REQUIRED failures block; an optional review failure does not become blocking through uniform exception handling.
In patchkit only `REQUIRED` failures can block; `PERF`, `FEATURE` and `TELEMETRY` failures are recorded as
review/disabled. A declined kernel is recorded as review/auto-disabled and continues on vanilla Java.
Every classification keeps its failure reason, and independent stages continue where possible.
Subprocesses are time-limited by the remaining 20-minute budget, and all logs and evidence stay in the output directory.
File reads, report writes and system process termination overhead are not guaranteed to fit a hard real-time 20 minutes.
`--draft-only` performs only the profile/remap bootstrap, and the report explicitly shows the remaining checks as pending.

## 3. automatic / review / blocking decisions

| Classification | Meaning and action |
|---|---|
| automatic | Data extraction, verification or checks completed with no migration differences; keep the evidence, do not publish on this basis |
| review | Library/usage/API/patch/corpus changes, body-only SPI changes, missing optional evidence, failed optional checks, automatic kernel disabling, same-jar aliases, etc.; review item by item per the files and actions in the report |
| blocking | A REQUIRED stage failed: unknown/ambiguous seam, SPI signature/generation change, a declared REQUIRED native entry missing, a REQUIRED patch failure, a required jar/cache missing, input verification failure or a required shader translation failure; fix and re-run |

Exit codes: `0` all automatic, `1` has review and no blocking, `2` has blocking.
`noBlockingItems=true` differs from exit `0`: a new ID for the same jar gets an alias review and can still have zero blocking.
A passing shader native translation does not prove WebGPU rendering; identical locks do not prove golden parity;
the fast report always sets its claims for TeaVM, Chrome, parity and promotion to false.
`status=FAIL` by itself does not mean blocking; check both `required` and `classification`.
`auto-disabled` describes a degradation action; the report classification remains review and it is not counted in REQUIRED failures.

Fix flow: adapt the seam and source sets first, then handle REQUIRED patches; when FEATURE/PERF and similar
patches fail, disable them per the patchkit policy and never silently treat the failure as normal. When a mirrored method changes, keep the kernel
disabled, and after updating the implementation reach bit-exact parity with the target version's vanilla JVM golden fixtures.
Do not substitute tolerance-based approximation, old-version fixtures or a new lock for parity.

Only after review install the draft and review the kernelSemantics / source set recipes, and use each tool's explicit
refresh to check locks/manifests. Maintain version data and derived tables through the normal profilegen review flow;
fast tools never add an unreviewed version to the JS allowlist and never promote to a release on their own.

## 4. `--full`: post-review build and real acceptance

`gaius-bringup.mjs <id> --full --from <previous>` is the release-day entry point: it first runs all
fast steps, then runs the patchkit lock diff, SPI fingerprint, apidiff/extrefs, kernel
golden, shader corpus, version literal/reachability lint and relay table regeneration recheck needed for review. The lightweight summaries above are already part of
fast; the extra full-mode runs do not change fast's review and automatic degradation semantics for missing optional evidence. Every item in the report JSON/MD
carries `PASS`, `REVIEW`, `AUTO_DISABLED`, `FAIL` or `SKIPPED`, the actual reason and `required`; the exit code is `2` only when
a required item fails, and missing resources are recorded as SKIPPED against the declared optional gate, never disguised as PASS.
The patchkit diff classification keeps auto-carry, review target-changed and required-failed; a golden
diff automatically declines the corresponding kernel, which continues on vanilla Java. Only `--build` enables the WasmGC
client/server build (serialized through that slot.sh wrapper when `GAIUS_SLOT` is set) and the `WebAssembly.validate`
check; by default nothing is built and Chrome is not started.

Below is the command template for the full build; do not run it concurrently with other full builds.
`ID` comes from a reviewed profile; `OUT` is a separate full-build directory; `GAIUS_SLOT` is optional and points to a wrapper script on the build machine that serializes
builds/Chrome (interface `build|chrome <tag> -- <command>`, the same variable read by `gaius-bringup.mjs --build`);
when unset, the `slot` function below runs the command directly.
First confirm that Maven, Rust, Binaryen, fonts/resources and so on are cached; the existing build scripts may fetch missing
fonts or the Maven wrapper, so without download permission the caches must be filled in first, otherwise stop.

```bash
export GAIUS_VERSION_PROFILE_PATH="versions/$ID.json"
export CARGO_NET_OFFLINE=true CARGO_BUILD_JOBS=2 MAVEN_ARGS=--offline
export GAIUS_TEAVM_MAX_HEAP=<heap>   # choose by build machine memory; default 14g. Setting MAVEN_OPTS overrides the compiler JVM arguments
slot() { local kind="$1" tag="$2"; shift 3; if [ -n "${GAIUS_SLOT:-}" ]; then bash "$GAIUS_SLOT" "$kind" "$tag" -- "$@"; else "$@"; fi; }
export GAIUS_BUILD_ROOT="$OUT/js-build"
export GAIUS_DIST_DIRECTORY="$OUT/js-dist"
export GAIUS_OVERLAY_DIRECTORY="$OUT/js-overlays"
slot build "bringup-$ID-js" -- bash port/scripts/build-version-release.sh "$ID"

export GAIUS_WASMGC_RESOURCE_ROOT="$OUT/js-build"
export GAIUS_WASMGC_RESOURCE_DIST="$OUT/js-dist"
export GAIUS_BUILD_ROOT="$OUT/wasmgc-build"
export GAIUS_DIST_DIRECTORY="$OUT/wasmgc-dist"
export GAIUS_OVERLAY_DIRECTORY="$OUT/wasmgc-overlays"
slot build "bringup-$ID-wasm-client" -- bash port/scripts/build-wasmgc.sh --role client "$ID"
slot build "bringup-$ID-wasm-worker" -- bash port/scripts/build-wasmgc.sh --role singleplayer-worker "$ID"
bash port/scripts/run-python.sh port/scripts/build-portable-html.py \
  "$OUT/js-dist" "$OUT/wasmgc-dist/Gaius-wasm.html" --wasm-dist "$OUT/wasmgc-dist"

GAIUS_FILE_DIST="$OUT/js-dist" GAIUS_FILE_MODE=single GAIUS_FILE_OUTPUT="$OUT/acceptance-js.json" \
  slot chrome "bringup-$ID-js" -- node port/scripts/browser-file-entry-acceptance.mjs --runtime js
GAIUS_FILE_DIST="$OUT/wasmgc-dist" GAIUS_FILE_MODE=single GAIUS_FILE_OUTPUT="$OUT/acceptance-wasm.json" \
  slot chrome "bringup-$ID-wasm" -- node port/scripts/browser-file-entry-acceptance.mjs --runtime wasm
```

`MAVEN_ARGS=--offline` requires the existing Maven to support that environment variable; with `GAIUS_SLOT`, build and Chrome
are each serialized. The full client may OOM with a smaller heap: report the failure and adjust
`GAIUS_TEAVM_MAX_HEAP` to the build machine's capacity; do not raise it on your own on a constrained shared machine. The log BUILD_EXIT, wasm sidecar/hash, actual runtime,
world entry/terrain draw, worker tick, and singleplayer and multiplayer/skin/LAN
product acceptance each need independent checking; a title screen alone or an HTTP 200 is not enough for acceptance. The singleplayer
acceptance in the template above is not the full set of release gates; add the multiplayer and protocol/world compatibility checks from the existing release handbook.

## 5. Six-hour snapshot CI

`.github/workflows/snapshot-bringup.yml` triggers every six hours at minute 17 UTC and can also be triggered manually.
It is off by default: it runs only when the repository variable `GAIUS_SNAPSHOT_BRINGUP_ENABLED=true` is set.
Scheduling is not guaranteed to be punctual; CI does not commit, push or publish.

The CI-only script reads `latest.snapshot` from the Mojang manifest, verifies the SHA-1 of the selected metadata,
fetches the corresponding client and verifies its SHA-1; it builds a provisional profile from the jar, calls the existing
`fetch-version.sh` / `remap-client.sh` to prepare that version, the previous version and the shader recipe dependencies,
and then runs the same offline `--fast`. An already reviewed ID does not overwrite its profile and is instead rechecked against its own reviewed baseline.
`latest.snapshot` sometimes points to an already reviewed release; do not misreport it as a new profile.

The workflow restores the offline Maven/Cargo caches, with the key determined by Cargo.lock/port config; a warm cache is needed before the first
enablement, and a cold or purged cache is blocking and must not be used to claim snapshot compatibility.
You can first set `GAIUS_SNAPSHOT_BRINGUP_RUNNER` to the label of a Linux runner that already has warm caches;
the first successful run saves the same Linux cache key, after which the default `ubuntu-latest` is restored.
When the variable is unset, an Ubuntu runner is still used, and no machine identity is guessed or written.
A new JDK/class format that is uncached or unsupported likewise requires a manual update of the runtime environment.
Only CI acquisition may use the network; `--fast` has no download action. A review exit is converted to CI success with the report
kept, while blocking stays non-zero; neither promotes a profile. The initialization-failure report ensures that an early acquisition
or bootstrap failure still produces an artifact; the actual fast result replaces that placeholder report.

The artifact stores JSON/MD, drafts, diffs, locks, logs and translator results, and does not upload jars,
native binaries or the full Cargo target; it is retained for 14 days. An unknown shader ABI/SPI is recorded as blocking;
generations are not guessed, REQUIRED failures are not swallowed, and new kernel semantics are not copied over.

## 6. Reproducible verification and historical migration scope

Tests:

```bash
node --test port/tools/bringup/bringup.test.mjs port/tools/bringup/native-api.test.mjs
node port/tools/patchkit/patchkit.mjs --self-test
node port/tools/patchkit/patchkit.mjs lint
node port/tools/profilegen/profilegen.mjs --check
```

The actual proofs from 2026-10-08 are kept in `<out>/bringup`:

- `scratch` is an isolated copy of the sources, not another git worktree; `26.4` is a separate jar
  copy of `26.3` with a read-only library junction, not the real official `26.4`. The command is
  `node <output>/scratch/port/tools/bringup/gaius-bringup.mjs 26.4 --fast --from 26.3`.
  The default report path is `port/mc/profiles/26.4` inside scratch; the expected result is zero blocking and an alias review.
- The real migration command is `node port/tools/bringup/gaius-bringup.mjs 26.3 --fast --from 26.2
  --out <output>/migration`. It compares the previous lock/manifests and the previous vanilla jar, and does not
  hide the historical SPI, class migration, dependency and kernel changes just because the current checkout has already been fixed.
- The patchers in the current checkout have already completed that migration, so this command has zero REQUIRED failures
  and must not be claimed to reproduce the original six patch failures. The previous manifest's owner inventory covers only
  the owners actually rewritten; the broader precheck census recorded by the architecture migration (60 owners, 32 suggestions)
  uses a different counting basis.
- Stable migration data from the actual run: 549 old classes vanished, 194 unique-name relocation suggestions,
  44 owners vanished/15 suggestions in the previous manifest; usage added 1537/vanished 1365;
  kernel mirror methods UNCHANGED 3498, BODY 127, SIGNATURE 1895, GONE 1010,
  all five kernels disabled; native shader 216/216 pipelines and 175/175 modules passed.

The last alias run took 68.792 seconds: automatic 10 / review 1 / blocking 0, exit 1;
the real migration took 64.205 seconds: automatic 4 / review 6 / blocking 2, exit 2.
The two blocking items are the previous SPI generation migration and an SPI signature change respectively, not tool execution failures.
`proof-summary.{json,md}` summarizes the commands, counts, input/report SHAs and verification logs; the full diffs and
per-command exits are as given in the corresponding latest `bringup-report.json`.
Zero blocking in scratch is not proof of support for a new official jar or for browser execution.

For the existing `check-version-profile.mjs --all`, the local metadata asset-index
SHA-1 of the legacy profile differs from the reviewed profile; see the output's `profile-check.log`. This task did not refresh it,
it is not a pass item of fast mode, and it does not block the two isolated proofs above.

### Offline inputs and manual work

`--fast` builds a reviewable migration draft, not release proof. fast covers seamprobe, usage/extrefs,
patchkit, kernel mirror, native shader translation, sourcesets, plus the native entry,
relay protocol, verification lint, apidiff, kernel golden and shader corpus baseline diffs.
When native entry has only a summary, it cannot claim native smoke passes; corpus hash/diff, native translation
and JVM fixture verification cannot substitute for kernel bit-exact parity and browser acceptance.
`--full` runs the complete review gates; only an explicit `--build` compiles WasmGC. All inputs must come from the existing
offline caches; when a REQUIRED stage lacks a jar, LWJGL native, Maven artifact, Cargo crate or shader
tool, record blocking; when an optional review lacks evidence, record review; do not go online to fill the gap.

The bring-up JVM seamprobe selects `natives-windows`, `natives-windows-arm64`, `natives-macos`, `natives-macos-arm64` or `natives-linux` by platform. The corresponding classifier jar must already be in the libraries of the offline work root; the tool extracts it into the evidence directory via `-Dorg.lwjgl.system.SharedLibraryExtractPath`. If the native jar is missing, fix the input first and do not mark the shader or JVM stage as passed.

### 26.4 and later versions: manual actions layer by layer

Start with `node port/tools/bringup/gaius-bringup.mjs 26.4 --fast --from 26.3 --out <out>`;
the real official inputs must already be cached, and an alias dry run that copies an old jar cannot prove support for the new version.
Each item's `action` in the report corresponds to the table below: read its `evidence` first, then perform the action file by file.
`<evidence>` means this run's `<out>/bringup-evidence-*`; `<id>` and `<previous>` are fixed by the command.
A layer with no differences only needs its evidence kept; do not add version branches or refresh reviewed locks because of it.

| Layer / report stage | Files to check manually | Action and completion condition |
|---|---|---|
| profile | `<out>/profile.draft.json`, `port/versions/<id>.json` | Verify `official`, Java/protocol/world/pack versions, libraries, storage, `renderSeam`, `inputSeam`, `patchSet`, `extraPatchers`, `kernelSemantics`, `shaderFront`, `bringup` and `pages`; install the draft only after review |
| remap/inputs | `port/work/<id>/client-original.jar`, `client-named.jar`, `version.json`, `libraries/` | Fill in or fix offline inputs against the SHAs and missing paths in the report; the work root may be shared, so use it read-only and let the input maintainer fix it; continue once the re-run of remap/verification passes |
| seamprobe | `<evidence>/classes.diff.json`, `spi.diff.json`, `patch-owners.diff.json`, `port/mc/seams/<generation>/seam.json` and `src/main/java/` | Adapt the affected adapters based on the class relocations and SPI details; for an unknown generation, declare the seam recipe first; fix signature/generation REQUIRED diffs and re-run, with no automatic renaming |
| extrefs | `<evidence>/usage.diff.json`, `usage.lock`, `port/mc/profiles/<id>/usage.lock`, `port/versions/<id>.json` | Review added/vanished usage by library coordinate and owner; fix the library recipe or the corresponding shim; after review, explicitly refresh the usage lock through the tool |
| native entry | `<evidence>/native-entry.json`, `usage.lock`, `requiredEntries` in `port/mc/seams/<generation>/seam.json`, `port/overrides/libraries/<library>/src/`, `port/tools/src/main/java/dev/gaius/tools/Lwjgl*Patcher.java` | Locate the library override and entry patcher by usage owner; fill in the REQUIRED entry signatures/redirects and re-run; keep optional changes as review, the old adapter or a FEATURE stub; after building the overlay, run `port/scripts/lwjgl-browser-compat-smoke.sh` for an actual smoke test |
| patchkit | `<evidence>/patch.js.diff.json`, `patch.wasmgc.diff.json`, `port/tools/src/main/java/dev/gaius/tools/patches/`, `port/tools/patchkit/classification.tsv`, `acknowledgements.json` | Fix the corresponding domain patch/registration for a REQUIRED failure and re-run; review target changes one by one for ack; PERF/FEATURE/TELEMETRY failures stay disabled with the reason recorded and are not promoted to blocking; refresh `port/mc/profiles/<id>/patch-manifest.*.json` only after review |
| kernel mirror | `<evidence>/kernel-mirror.diff.json`, `kernel-mirror.lock`, `port/native/kernel-mirror.toml`, `kernelMirror` in `port/versions/<id>.json` | Review method/dependency changes and the reasons for transitive disabling; a declined kernel stays disabled and on vanilla Java; never enable it merely because the new lock was generated successfully |
| kernel golden | `<evidence>/kernel-golden.diff.json`, `port/native/fixtures/<id>/`, `port/native/golden/src/`, `port/native/crates/`, `port/mc/profiles/<id>/kernel-semantics.json` | Regenerate fixtures with the target version's vanilla JVM and run `bash port/native/golden/run-golden.sh --verify <id>`; fix the affected kernel and obtain bit-exact parity before it can be reviewed for enabling; a missing fixture or mismatch stays review/auto-disabled |
| shader translation | `<evidence>/shader-translation/results.json` and shader logs, `bringup.shader` in `port/versions/<id>.json`, `port/native/golden/shaders/java/dev/gaius/shaderxlat/`, `port/native/crates/gaius-shaderxlat/` | Fix the profile-owned dump/link recipe or the translator, and fill in the existing offline natives; the REQUIRED blocker is lifted only after all required translations pass; do separate browser acceptance |
| shader corpus baseline | `<evidence>/shader-corpus-baseline.json`, `shader-corpus/corpus.json`, `previous-shader-corpus/corpus.json`, `bringup.shader` in `port/versions/<previous>.json` | Review pipeline/module/layout changes at the baseline paths given in the report; fix the previous version's offline inputs or the dump/link recipe, and use `port/native/golden/shaders/dump-corpus.sh` and `run-corpus.sh` to fill in the corpus; a missing baseline stays review with the reviewed corpus kept, and must not be taken as browser parity |
| sourcesets | `<evidence>/sourcesets.json`, `renderSeam`/`patchSet` in `port/versions/<id>.json`, sets/relocations in `port/mc/shared/sourcesets.json` and the corresponding source set under `port/mc/shared/` | Fix missing sources/relocations or the profile recipe; once resolution passes, hand off to the subsequent Java/TeaVM build verification, and do not treat resolution as a successful compile |
| relay protocol | `<evidence>/relay-protocol/relay-protocol.js`, `apps/bridge/protocols/<protocol>.json`, `relayProtocol`/`protocolVersion`/`official` in `port/versions/<id>.json` | Generate and review the table from the offline report with the existing `tools/gen-protocol-ids.mjs`, then run `node port/scripts/build-relay-protocol.mjs --profile <id> --out <evidence>/relay-protocol`; when protocol or provenance is unverified, keep the previous table and review, and do not enable multiplayer for the new profile |
| verification lint | `<evidence>/version-literals.log`, the hit files under `port/scripts/` or `apps/bridge/`, `port/scripts/verification-profiles.mjs`, `port/scripts/quickcheck/version-literals-exempt.txt` | Change hard-coded default profiles/matrices to be derived from profile data; when a data file truly needs an exemption, write the exact path and reason; re-run `python port/scripts/quickcheck/lint_version_literals.py --enforce` |
| API diff | `<evidence>/apidiff.json`, `apidiff-*.log`, `usage.lock`, the corresponding `port/overrides/libraries/<library>/src/versions/<range>/api.lock` and adjacent sources, `port/tools/src/main/java/dev/gaius/tools/patches/platform/` or seam files | Fix the library shim, JDK patch or seam by API owner; after reviewing the overlay, refresh the relevant locks with `bash port/tools/extrefs/extrefs.sh apilock <id> --write`; review changed libraries that have no API baseline one by one, and never declare compatibility from an empty summary |
| profile-derived output | The files listed by `port/tools/profilegen/profilegen.mjs --regions`, `port/versions/<id>.json` | After the draft is reviewed and installed, run `node port/tools/profilegen/profilegen.mjs --write`, then `--check`; verify the derived table diffs, and never hand-edit generated regions or let fast add to the allowlist automatically |

If the new version has no matching seam generation, or the SPI, resource format or shader harness changes,
the manual changes concentrate on the corresponding seam, recipes and REQUIRED patches. A kernel whose bit-exact parity cannot be proven
must stay disabled; missing optional evidence must keep an actionable follow-up and must not be marked as passed.

### Honest effort estimate

When an existing seam generation matches and the offline inputs are complete, `--fast`, report review and profile metadata typically take 30–90 minutes; a separate `--full` review typically takes 1–3 hours. A new renderpearl generation involving SPI or patch changes typically takes 1–3 engineering days for seam/patch adaptation and review, plus build and browser acceptance windows. Missing required inputs, REQUIRED patch failures, shader ABI changes or TeaVM memory failures extend the time until the blocking items are resolved. A kernel with a golden mismatch stays disabled, and the time needed to gather the review evidence is counted separately.
