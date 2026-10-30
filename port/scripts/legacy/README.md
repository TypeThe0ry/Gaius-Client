# Frozen verification scripts

These scripts assert the structure of Gaius's patches through source text, javap output and
version-specific class shapes. Per Minecraft version they grew into a large share of each
migration, so they are frozen here:

- `quick-check.py` and its 26.3 domain modules `quickcheck/profile_263_<domain>.py`;
- the 26.3 one-off smokes `*263*.mjs`.

They are not a shipped runtime artifact and not a separate product: they check the
patch and overlay layer that the internal JavaScript and WasmGC builds share. "Frozen" means that no
file or new structural check is added to them. They still run: `docs/releasing.md` calls
`quick-check.py` after each profile's release build, and the `version-profiles` job of
`.github/workflows/repository-guard.yml` runs the domain modules and the `*263*` smokes.

Rules:

- Keep them passing: when a patch changes on purpose, adjust the assertion it breaks.
- Add no file here and no new structural check to these files. A new check belongs to the patch
  that it verifies (a `REQUIRED` verification step registered through `PatchRegistry.run`, see
  `port/tools/patchkit/README.md`), or to a profile-parameterized script under `port/scripts/`
  that names no Minecraft version.
- `check-frozen.py` (CI `repository-guard`, job `wasmgc-gates`) fails on a file added here and on
  a new version-named script elsewhere in `port/scripts`.
- The P3 version-literal lint (`port/scripts/quickcheck/lint_version_literals.py`) skips this
  directory.

`minecraft-263-singleplayer-cdp.mjs` and `minecraft-263-multiplayer-cdp.mjs` stay in
`port/scripts/` for now: they are the active singleplayer and multiplayer acceptance harnesses
(the singleplayer one already takes `PROFILE`), which become `--profile` parameterized acceptance
scripts rather than frozen ones.
