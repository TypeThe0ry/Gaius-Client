# Kernel mirror guard

The mirror manifest names exact vanilla owner/name/JVM-descriptor identities and their
applicable profiles. It deliberately watches complete mirrored classes, including private,
synthetic, constructor and static-initializer methods. This is conservative: an unrelated
method change in a watched class can require review. No class or method wildcard is used
when hashing or deciding whether to run a kernel.

`shaderxlat` is an explicit non-Java mirror: its contract is the shader corpus rather than
a vanilla Java algorithm. It still requires a profile approval and honours the runtime gate.
Noise is a dependency of mesher and worldgen, so a noise refusal disables both dependants.

## Offline commands

Use the locally remapped `port/work/<profile>/client-named.jar`, Node and a JDK that can
read that profile's class-file version. The tool invokes Java with `-Xmx3g` by default and needs no
Maven, npm or third-party dependencies. `GAIUS_JAVA_HOME`/`JAVA_HOME`, `GAIUS_WORK`,
`--java-home`, `--work` and `--jar` can select existing local inputs.
`--java-heap 2g` or `GAIUS_KERNELMIRROR_JAVA_HEAP=2g` bounds the hasher on
memory-constrained hosts without changing the hashes or approval contract.

```sh
node port/tools/kernelmirror/kernelmirror.mjs generate --check
node port/tools/kernelmirror/kernelmirror.mjs gate --check
node port/tools/profilegen/profilegen.mjs --check
node port/tools/kernelmirror/kernelmirror.mjs generate --profile "$MC" --out-dir "$REPORT"
node port/tools/kernelmirror/kernelmirror.mjs diff "$PREVIOUS_LOCK" "$REPORT/$MC/kernel-mirror.lock"
node port/tools/kernelmirror/test-kernelmirror.mjs
node port/scripts/kernel-mirror-smoke.mjs
```

`generate` writes method locks; it never updates approvals. `gate` writes the per-profile
`kernel-semantics.json`. `table --profile <id>` emits the freshly evaluated gate on stdout.
`--check` writes nothing and fails on stale outputs; `--warn` reports differences or missing
inputs without failing CI. `diff` accepts two exact lock paths or profile IDs and emits
every method's `UNCHANGED`, `BODY`, `SIGNATURE` or `GONE` status and each kernel's decision.
Added methods and alternate descriptors are `SIGNATURE`; a missing method without an
alternate is `GONE`. Declaration, semantics and dependency changes also disable kernels.

## Hash and approval contract

`targetHash` is SHA-256 of normalized class-file bytecode, not of `javap` text. Constants
are resolved to symbolic values, owners, names and descriptors, including bootstrap method
handles/arguments. Branch, switch and exception-table targets use instruction ordinals;
`ldc_w`, wide branch encodings and wide local encodings lose index/offset noise. Access
flags, opcodes, operands, numeric bit patterns and exception handlers remain significant.
Debug tables, stack maps, class-file version and compiler resource bounds are ignored.
The normalization version is part of the lock and the hash input.

`port/versions/*.json` holds the separately reviewed `kernelMirror.lockSha256` and default
`enabled` booleans. The digest binds the exact UTF-8/LF lock bytes to that profile. A new
profile, stale approval, missing jar, missing method, changed body/signature, expanded mirror
set or malformed lock fails closed. Regenerating a changed lock cannot approve it by itself.
Profilegen derives checked-in tables without requiring jars; build staging additionally
re-inspects the selected jar and embeds the fresh decision in `kernels/kernel-mirror.js`
next to `kernels/kernel-semantics.json`. Portable HTML and hashed Pages assets preserve it.
Server Workers receive that same table and validate their profile identity.

Java hosts and JS runtimes/facades check the gate before submission. A disabled family
returns `needs-vanilla` / `NEEDS_VANILLA`, false availability, or the existing transient
callback, without creating workers or dispatching jobs. Query switches cannot override it.

## Bringing up a profile after a diff

1. Keep the family disabled. Save a candidate lock outside the reviewed profile directory,
   then compare it to the prior lock. Never copy the old approval into a new profile.
2. Inspect actual named jars with `javap -J-Xmx3g -p -s -classpath <jar> <owner>`.
   Update explicit owner/name/descriptor/profile entries for moved or changed methods and
   include their mirrored helpers, static tables and synthetic bodies. Do not infer a
   descriptor from a source comment or a simple method name.
3. Review the Rust port and profile `kernelSemantics` against the changed vanilla methods.
   Re-run the existing JVM golden harness and native parity fixtures using local caches,
   `cargo --offline --locked -j 4` and Java `-Xmx3g`. Any unsupported semantics must continue
   to decline. A matching method hash is not evidence of kernel/vanilla numeric parity.
4. Only after review, install the candidate lock and explicitly update that profile's
   `kernelMirror.lockSha256` and approved defaults. Preserve intentional off defaults.
   Run profilegen `--write`, then all three `--check` commands and the two smoke tests.
5. Stage the selected profile through the normal build scripts, inspect the embedded table
   and verify fallback. Commit the manifest, lock, approval, table and parity evidence
   coherently. Warn-mode CI is diagnostic; the staging/runtime guard is the fail-closed gate.

The guard is limited to the explicit watched method set, not a transitive proof of all
external classes, hierarchy metadata, data packs, registries, resources or JVM libraries.
Extend the manifest when porting new vanilla helpers. Complete-class coverage reduces
omissions but can yield false positives; it does not replace human review and golden parity.

The schema-only `meshVertexStride` review is recorded in
`port/native/reviews/mesh-vertex-stride.json`. It binds the prior approved and new lock
digests, unchanged method counts, named jar hashes, live JVM vertex-format evidence and
preserved defaults. It does not approve any later method or semantics change.

The additive ordinary-ore family (`ore`, a dependant of `worldgen`) is recorded in
`port/native/reviews/ore-lock-entries.json`. It binds each profile's previous and new lock
digests, shows that every pre-existing method hash is unchanged and only ore methods were added,
pins the ore method set, the named jar hashes and the independent ASM oracle fixtures, and
documents that `ore` stays disabled by default (`PROFILE_DEFAULT_OFF`) in every profile. It does
not approve enabling the family.
