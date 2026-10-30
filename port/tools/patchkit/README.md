# patchkit

The patch kit, including the method targets (P6) and static initializers (P7) below: every ASM patch carries a kind,
build targets and profiles; a patch that is not REQUIRED is disabled instead of failing the
build; a check mode lists every outcome without a build; the per-profile, per-target patch
manifests are generated from the PatchRegistry run log and compared with a lock diff.

## Registration

```java
PatchRegistry.configureProfile(minecraftVersion);
PatchRegistry.configureOutput(root);
PatchRegistry.run("MinecraftClientPatcher.patchGlx", "REQUIRED js wasmgc",
        () -> RenderPatches.patchGlx(...));
PatchRegistry.run("MinecraftClientPatcher.patchAquiferBrowserNearestCenters", "PERF js",
        () -> WorldgenPatches.patchAquiferBrowserNearestCenters(...));
PatchRegistry.run("Minecraft262BrowserPatcher.patchVulkanBackend", "REQUIRED js wasmgc >=26.2",
        () -> ModernRenderPatches.patchVulkanBackend(...));
```

The spec is `<KIND> [js|wasmgc]... [<profiles>]...`:

| Kind | Meaning | A failing body |
|---|---|---|
| `REQUIRED` | the browser client is broken without the patch | fails the build (`PATCH_FAILED`) |
| `FEATURE` | a browser feature or default; vanilla stays correct | is disabled (`PATCH_DISABLED`) |
| `PERF` | speed or memory only | is disabled |
| `TELEMETRY` | diagnostics only | is disabled |

PatchRegistry treats a spec without targets as both, but `lint` requires every registration to
name them (see "Domains"); a patch of one target is reported as `PATCH_TARGET_SKIPPED` by the
other (`port/tools/target-skips/<target>.txt` lists those ids for the G4 gate). Profiles default
to `*`; terms are a profile id, `>=<id>`, `<=<id>` or a comma-separated union, compared on the
leading dotted numbers (`>=26.3` covers `26.4-snapshot-2`). A profile outside the spec reports
`PATCH_NOT_APPLICABLE <id> profile=<p>` without running the body. The current specs declare the
profiles each patch reaches today, so no current build reports it.

A disabled patch is restored: PatchRegistry tracks the files of the output root that the body
created, rewrote or deleted, and puts them back. Files written elsewhere are not tracked, and a
patcher that does not call `configureOutput` cannot disable anything (the failure then fails the
build). Disabling only removes the patch's own output: a later patch that depends on it fails in
its turn (and is disabled or fails by its own kind), and the 26.3 chain checks its paired terrain
hooks in `TerrainPatches263.verifyTerrainChain` (REQUIRED).

`port/tools/patchkit/classification.tsv` records the reviewed kind of every id with its reason,
the census category the review started from and where the reason holds (`same`, `js-only` for
patches that only the JavaScript build needs, `wasm-only`). The kind in a registration must equal
the kind there (`patchkit.mjs lint`).

PatchRegistry switches (system property / environment variable):
`gaius.patch.check` / `GAIUS_PATCH_CHECK` (a failing REQUIRED patch is restored and recorded),
`gaius.patch.strict` / `GAIUS_PATCH_STRICT` (nothing is disabled),
`gaius.patch.runlog` / `GAIUS_PATCH_RUN_LOG` (JSON lines, one per outcome),
`gaius.patch.fail` / `GAIUS_PATCH_FAIL` (comma-separated ids whose body fails; tests only).

An expected profile exclusion uses a `NOT_APPLICABLE` spec flag and a reason keyed by the
patch id in `port/versions/<profile>.json` `patchkitNotApplicable`. A profile whose generation
applies the patch on one TeaVM target only declares `{"targets": ["js"], "reason": "..."}`
instead of a bare reason: the patch is NOT_APPLICABLE (with the reason) on the named targets and
runs on the others, so the per-generation targets live in the profile, not in a version literal
of the spec (lint: the targets must be among the spec's; G4: a declared exclusion must cover the
log's target). The registry decides before calling the body. An absent shape on a declared-applicable profile is a failure, not a printed
skip. `runPart(id, spec, node, body)` records an in-memory sub-patch before the parent writes its
class; `runPart(id, spec, body)` covers a separate file-producing step. Parts do not increment
the parent's summary counters. The existing legacy mesh registration retains its historical
summary slot (`summary=applied`), while its manifest outcome is correctly NOT_APPLICABLE.
Other excluded parts log `summary=part`. G4 validates these annotations against the spec and
profile declarations rather than ignoring arbitrary skip messages. PATCH_SUMMARY is a legacy
compatibility counter, not the manifest outcome census.

Use the `OVERRIDE` spec flag for a whole-class replacement. It fingerprints all vanilla method
bodies, including otherwise unchanged methods, rather than just the methods the output diff
detects. Formerly unregistered spliterator and server-worker steps are now registered parts.

## Domains

The patch code is organised by domain, not by the
Minecraft version or the patcher that first needed it. A domain is the Java package
`dev.gaius.tools.patches.<domain>` under `port/tools/src/main/java`: every port/tools compile
(`build-overlays.sh`, `build-teavm.sh`, `patchkit.mjs`) already passes that root as `-sourcepath`,
so a domain class is compiled as soon as a patcher calls it and no build script changes.
The domains are `render`, `mesh`, `light`, `worldgen`, `serverworker`, `network`, `storage`,
`resources`, `ui`, `input`, `audio`, `telemetry` and `platform` (JVM/OS services and threading).

The patcher steps of `build-overlays.sh` stay the chain: each `main` registers its patches in
the historical order, `PatchRegistry.run("<id>", "<spec>", () -> <DomainClass>.patchX(...))`,
and the body lives in one domain class per patcher step: `<Domain>Patches` for
MinecraftClientPatcher (every profile), `Modern<Domain>Patches` for Minecraft262BrowserPatcher
(the `modern` patch set arm), `Legacy<Domain>Patches` for Minecraft12111BrowserPatcher (the
`legacy-12111` arm), `Renderpearl<Domain>Patches` for Minecraft263BrowserPatcher (the
`extraPatchers` step of a profile with a renderpearl render seam) and
`serverworker/WorkerOverridePatches` for MinecraftServerWorkerPatcher. Code that is a unit of its
own keeps a name for what it does: `render/QualityTierPatches` (graphics quality tiers),
`render/RenderpearlTerrainPatches` (the multi-draw-indirect pin and the terrain chain check),
`worldgen/WorldgenStrictMath` (Java float semantics of the worldgen classes) and
`platform/JdkCompatPatches` (the JDK APIs TeaVM's class library lacks) for
Minecraft263BrowserPatcher, `ui/GraphicsPresetStartupPatcher` for the graphics preset replay of
both patch set arms. No class name carries a Minecraft version: the profiles a patch reaches are
its spec (`>=26.3`), and a profile selects the renderpearl step through `extraPatchers`.
Minecraft263BrowserPatcher registers its patches in the groups of contract C2 (render, input,
terrain, worldgen, server, ui, then the worldgen float rewrite, the JDK API redirects and the
non-suspending worldgen guard), one public method per group.
A domain class names its step in its class comment ("The <domain> domain of the <Patcher>
step"); `port/scripts/patcher-sources.mjs` reads a step with its domain classes for the smokes
that check patch code by its text. The kernel hooks are `light/LightKernelPatches`,
`mesh/MeshKernelPatches` and `worldgen/WorldgenKernelPatches`, the terrain batch patches
`render/TerrainBatchPatches`. The ASM helpers that several domains of one step share live in
the package `dev.gaius.tools.patches.support`, one class per step: `ClientPatchSupport`
(MinecraftClientPatcher), `ModernPatchSupport` (Minecraft262BrowserPatcher), `LegacyPatchSupport`
(Minecraft12111BrowserPatcher) and `RenderpearlPatchSupport` (Minecraft263BrowserPatcher), plus
`QualityClasses` for the graphics quality patches. The domain classes import them statically,
so the moved code calls exactly the helpers it called before, and the patcher classes keep only
their registrations. The support package is not a domain (patchkit does not count it); a
support class names its step in its class comment ("... more than one domain of the <Patcher>
step uses"), so `patcher-sources.mjs` reads it with the step. Patch ids keep their historical
`<Patcher>.<method>` and `<Group>Patches263.<method>` names: they key the manifests,
classification.tsv, the target-skip lists, bring-up lists and acknowledgements.

AbstractSpliteratorBrowserPatcher stays with the library patchers in `dev.gaius.tools`: its
transform (JDK `Spliterators$AbstractSpliterator` subclasses become direct `Spliterator`s, plus
Guava's `ChecksumMethodHandles`) is library level, names no Minecraft class, and
`build-overlays.sh` runs it on the Guava overlay as well as first step of the client chain. Its
`AbstractSpliteratorBrowserPatcher.classOverrides` is therefore the one registered id outside the
domain packages. MinecraftChunkDrawTelemetryPatcher registers no patch.

`lint` requires every registration to name its targets in its spec (`REQUIRED js wasmgc`, not
`REQUIRED`): which generation leaves a target out is then a `patchkitNotApplicable` table entry
of `port/versions/<profile>.json`, not a spec default or a code edit. It prints
`inDomains=<ids with domain code> targetsUndeclared=<ids on the default targets>` (262 of 263
and 0 at the time of writing: the library-level spliterator patch is the one id outside the domains).

## Commands

```bash
node port/tools/patchkit/patchkit.mjs check                     # 3 profiles x 2 targets, ~4 min
node port/tools/patchkit/patchkit.mjs check --profile 26.3 --target wasmgc --out <out>
node port/tools/patchkit/patchkit.mjs check --write             # refresh port/mc/profiles/*/patch-manifest.*
node port/tools/patchkit/patchkit.mjs check --compare [--warn]  # lock diff against the checked-in manifests
node port/tools/patchkit/patchkit.mjs diff old.json new.json    # e.g. 26.3 against a 26.4 bring-up
node port/tools/patchkit/patchkit.mjs lint
node port/tools/patchkit/patchkit.mjs --self-test
node --test port/tools/patchkit/target-lock.test.mjs
node port/tools/patchkit/patchkit.mjs --bytecode-test
```

For an unregistered version, `check --profile-file /absolute/profile.draft.json
--work /absolute/input-root --scratch /absolute/output-root --usage-lock
/absolute/usage.lock --out /absolute/manifests` checks both targets without
installing the draft or refreshing checked-in manifests. The draft must select
one profile; `--write` and `--compare` are rejected in this mode. `--scratch`
keeps compilation, copied jar and profile/seam metadata in the caller's output
directory; `--usage-lock` binds the patchers to that run's external-reference
evidence. The default registered-profile commands are unchanged.

`check` compiles `port/tools`, copies `port/work/<profile>/client-named.jar` to a scratch
directory, adds the client source class used by the class-initialization patch, and runs the chain
of `build-overlays.sh` on it (AbstractSpliteratorBrowserPatcher, MinecraftClientPatcher, the
patchSet arm, the profile's extraPatchers) in check mode. The other client overrides are not
compiled in; on the current profiles the PATCH_* lines equal those of the real overlay builds. It
prints `PATCHKIT <profile> <target> <OUTCOME> <KIND> <id> [reason]` per id.

## Manifests and the lock diff

`port/mc/profiles/<profile>/patch-manifest.<target>.json` lists every registered id (and the
sub-ids of dropped parts) with kind, targets, profiles, outcome, reason, the vanilla classes it
rewrote (`rewrites`), the files it added (`adds`) and the targets a drop proved absent
(`absent`), plus the PATCH_SUMMARY lines of each patcher step.

The lock diff classifies every id: `unchanged`; `renamed` (a gone id and a new one with the same
patcher, kind and rewritten classes, or the same method name); `target-changed` (other rewritten
classes, absent targets, outcome, kind, targets or profiles); `failed` (now FAILED or DISABLED);
`new`; `gone`. A REQUIRED id that failed is `blocking`; REQUIRED ids that changed, were renamed
or are gone need `review`. Repeated and newly introduced failures are still `failed`, never
`unchanged` or silently hidden inside `new` / `renamed`.

## Method targets (P6)

Schema 2 adds `targetHashAlgorithm: sha256-asm-symbolic-v1` and `methodTargets` to every patch.
Each row names the internal class, method, JVM descriptor and operation, and carries a SHA-256
`targetHash` of the **original vanilla** method before any patcher runs. `check` supplies the
immutable vanilla jar and the mutable input jar separately to PatchRegistry. The latter is
only used to determine what the current patch changed, never as the hash source. Fingerprinting
reads ASM trees without debug information or stack-map frames. It encodes symbolic owners,
descriptors, typed constants (raw floating-point bits), bootstrap handles/arguments, executable
instruction offsets for branch/switch labels, and exception handlers. It excludes line tables,
constant-pool indices, incidental label allocation, and computed max-stack/max-locals values.

Injections without a vanilla method use a deterministic absence hash and `vanillaPresent:false`;
this is not a hash of the patch's new body. Structural changes carry an additional `<class>`
shape target. Whole-class overrides carry a normalized full-class hash. Patches may have no
rows when they do not apply, fail with restored outputs, or only produce non-class resources.
Written classes without any fingerprint are rejected.

The diff detects method-body changes even when class names and all other patch metadata stay
unchanged. Rewrites, overrides, removed vanilla methods and `<clinit>` target drift are blocking
until an exact entry in `port/tools/patchkit/acknowledgements.json` is reviewed by a human.
An injection that reintroduces an existing vanilla method also retains this gate:

```json
{
  "profile": "<profile id>",
  "target": "js",
  "id": "<patch id>",
  "previousTargetDigest": "<64 lowercase hex characters from diff>",
  "currentTargetDigest": "<64 lowercase hex characters from diff>",
  "reviewer": "<human reviewer identity>",
  "reason": "<why this exact vanilla target change remains correct>"
}
```

The enclosing document has `schema:1` and an `acknowledgements` array. Digests are SHA-256 over
the sorted target tuples, not the raw jar or the manifest bytes. Entries cannot use wildcards,
apply to another profile/target, or accept a later different body. The agent never supplies a
human acknowledgement automatically. A matching entry clears the body-change block; unrelated
metadata changes still require a lock refresh. Rename rows retain their own body-change gates.

`check` refuses unacknowledged vanilla body changes even without `--compare`, except in explicit
CI `--compare --warn` mode. `check --write` refuses failed runs, malformed targets and unacknowledged
body changes even with `--warn`. All selected runs must pass before any lock is written. Schema-1 locks have no
historical method hashes: their one-time schema-2 initialization establishes the baseline,
without pretending to verify historical body parity. Subsequent schema-2 refreshes are gated.

## Static initializers (P7)

Each targeted `<clinit>` records sorted, deduplicated `vanillaStaticWrites` and `staticWrites`
sets of symbolic `PUTSTATIC` owner/name/descriptor identities. Both the vanilla and patched
footprints are pinned by the schema-2 lock. The gate compares each to its previous locked set,
not patched to vanilla: existing backend stubs and type remaps intentionally have different
sets, and this step must not retroactively change their JavaScript bytes. Injected initializers
have no vanilla body but still have a pinned patched footprint. Adding, removing or changing a pinned initializer
or either footprint is a hard gate, including when the patch id disappears. Review acknowledgements
do not waive this gate. FAILED and DISABLED outcomes retain it. New schema-2 patch ids that
target an initializer are also rejected, even if their recorded footprint matches an existing
writer: refreshing the lock cannot approve a new writer or erase a pinned initializer.
This is a direct bytecode write-set check, not an execution-based proof
of transitive helper effects or initializer ordering.

`repository-guard.yml` runs the JS diff fixtures and offline JVM bytecode fixtures, then both
targets for the cataloged profiles. `--compare --warn` warns about target/body/metadata drift
but fails FAILED REQUIRED, malformed evidence, crashes and P7 write-set changes. CI warn mode
does not grant permission to refresh a lock. No TeaVM compilation, browser, network resolution
or changes to JavaScript-target class output are needed for these checks.
