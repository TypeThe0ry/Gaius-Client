# Offline version bring-up

```bash
node port/tools/bringup/gaius-bringup.mjs <id> --fast
node port/tools/bringup/gaius-bringup.mjs <id> --fast --from <previous-id> --out <output-directory>
node --test port/tools/bringup/bringup.test.mjs port/tools/bringup/native-api.test.mjs
```

The tests need no jar (set `GAIUS_BRINGUP_TEST_OUT` to keep their scratch output; by default a private temporary directory is used and removed). CI runs them in the `bringup-toolkit` job of `repository-guard.yml`.

Inputs must already exist. Fast mode never fetches, installs a profile, refreshes
reviewed locks, runs TeaVM or starts Chrome. Reports and all scratch evidence go
under `--out`, defaulting to `port/mc/profiles/<id>`. Exit codes are 0 (automatic),
1 (review, no blockers), and 2 (blocking). The overall child-process budget is
20 minutes; the report records elapsed time, command logs and artifact identities.

Fast mode also checks native entry descriptors, relay tables, verification
version lint, override API locks, JVM goldens and predecessor shader corpora.
Optional patch failures stay review; declined or unverified kernels remain
disabled with vanilla Java active. Every report item names the human file action.

`--draft-only` is an offline bootstrap aid, not a completed fast check. `--full`
adds audit gates without TeaVM or Chrome by default; only `--build` requests game
builds. Set `GAIUS_SLOT` to the path of a build-slot wrapper script to run those builds
inside an isolated build slot; without it they run directly in the checkout. Follow
`docs/new-version.md` for coordinated builds and acceptance.
A draft and a fast pass do not authorize promotion on a shared machine.

`snapshot-bringup.sh` and `snapshot-profile.mjs` are CI-only. The scheduled
workflow is disabled unless `GAIUS_SNAPSHOT_BRINGUP_ENABLED` is exactly `true`.
See `docs/new-version.md` for offline cache prerequisites, decision rules and proof runs.
