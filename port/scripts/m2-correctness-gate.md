# M2 correctness evidence

`m2-correctness-gate.mjs` reads existing local evidence. It never builds, opens a browser,
downloads files, or changes a world. Missing coverage is `BLOCKED`; a measured difference is
`FAIL`. Either exits with status 1. Only complete evidence for every JSON-selected profile
and every configured seed can return `PASS`.

```sh
node port/scripts/m2-correctness-gate.mjs --self-test
node port/scripts/m2-correctness-gate.mjs --config evidence.json --out results
```

The configuration uses schema 1, `sourceRevision` (the full source commit), a unique array of
decimal string `seeds`, and `profiles` keyed by IDs from `port/versions/*.json`. Each profile
can supply these paths, relative to the configuration or absolute:

| Key | Evidence |
| --- | --- |
| `seeds` | Array of `worldgen-seed-parity.mjs` summary JSON files |
| `packets` | `packet-parity.mjs` comparison JSON |
| `mesh` | Exact JVM SectionCompiler vertex-byte report; pixel A/B is diagnostic only |
| `light` | `light-jvm-wasm-golden.mjs` JSON |
| `storage` | Gate directory containing both direction reports and console logs |
| `inWorld` | Complete singleplayer harness `report.json` |
| `build` | Object with `dist` and full source `revision`; both client and server wasm required |

The seed gate requires nonzero isolated terrain and biome comparisons against the JVM for
both JavaScript and wasm, and a direct comparison of shared blocks, biomes and light. A
feature-order allowance does not waive direct byte/hash differences in this aggregate gate.
Packet reports retain the strict decoded-ID/payload/order comparison and a logical comparison.
The logical comparison accounts for every server-to-client packet exactly once in a JSONL
ledger. The aggregate verifies that ledger's SHA-256, coverage, counts and mutual matches, and
rechecks the hashes of both streams, capture metadata, the protocol table and profile JSON.
Missing or inconsistent evidence cannot pass. Ordered view/simulation distance histories must
also match; equal final distances are insufficient. An exact stream match remains an alternative.

Logical alignment keeps ordered histories, not the first or last packet of each kind. Only
independent registry identifiers and chunk positions may reorder. Typed tag/heightmap maps are
canonicalized without reordering their values. The profile's `packetSemantics` declares whether
login contains an ephemeral session UUID; player UUID, name and properties remain significant.
A heartbeat is explained only with its unique matching client response. Complete bundles and
chunk batches must pass their framing/count checks. A byte histogram is never identity proof.

Time updates reconstruct profile-selected clock states and bound the tick intervals of adjacent
packets. Identical clock-state transitions with different sampling cadence are explained, but
an interval is not an exact tick. Entity, movement, inventory and transient block histories
without exact tick and entity-binding evidence remain unresolved, even if their bytes match.
Unresolved histories are `BLOCKED`; measured content differences are `FAIL`. The legacy
count-only/first-packet diagnostics cannot make either result pass.

The existing replay uses captured wall-clock input timing and the final browser view/simulation
distances from startup. A browser capture with staged distance changes is therefore not a
controlled same-tick simulation of that JVM run. Fresh captures need matched distance histories
and exact tick/input/entity evidence before attributing chunk or entity differences to kernels.
The current comparator does not acquire that missing evidence or claim a deterministic replay.

For JVM light fixtures, compile `WorldgenSeedParity.java`, its selected `ParityCompat`,
`LightFixtureWriter.java`, and `BrowserLightStateTable.java` against the selected vanilla
jar. The latter requires the profile's supported light-state API; an unsupported API is a
blocker, not permission to substitute another profile's table. Run the harness with
`GAIUS_PARITY_LIGHT_FIXTURES` set to a fresh output directory and `--mode light-fixtures`,
or use `--mode saved --saved <copied-world>` for actual saved columns. Then run:

```sh
node port/scripts/light-jvm-wasm-golden.mjs --profile <profile-id> --dist <built-dist> \
  --fixtures <fixture-directory> --out <result-directory>
```

Both shipped SIMD and baseline modules are checked against vanilla JVM nibbles. The report
records source, fixture, codec and module hashes. Its settled-neighbour `PULL_RING` boundary
condition checks the kernel's fixed-boundary result, not production INITIAL-job scheduling
or the wasm Java fallback. Those still require a successful in-world/runtime capture.

If your setup has a machine job runner that limits concurrent browser or build jobs, run
browser capture/storage/singleplayer commands and full TeaVM builds through it. Use fresh
output directories, and give each run its own build, overlay and distribution roots
(`GAIUS_BUILD_ROOT`, `GAIUS_OVERLAY_DIRECTORY`, `GAIUS_DIST_DIRECTORY`; see
`docs/releasing.md`). Frozen JavaScript distributions are read-only inputs. The aggregate records SHA-256 identities of the evidence
it reads; its configuration is an explicit source binding, not an independent build attestation.
