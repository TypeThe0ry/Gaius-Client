# Reviewed JS overlay baseline

G1 compares the contents of every consumed archive entry, not whole-JAR packaging
hashes. `port/reviews/g1-js-overlays.json` records the explicitly accepted changes
from the integration baseline. Each profile has exact keys, before/after SHA256
values and a review reason. A null before hash means an addition, not a wildcard.

Run the strict check for the selected profile. `$BASE` and `$CANDIDATE` are snapshot files
written by `overlay-golden-diff.py snapshot <overlayDir> <out.json>` from the baseline's and the
candidate's `port/work/overlays/<profile>` (the repository gate does this in
`.github/workflows/repository-guard.yml`):

```sh
python3 port/scripts/overlay-golden-diff.py diff "$BASE" "$CANDIDATE" \
  --products --strict-added --profile "$MC" --accept port/reviews/g1-js-overlays.json
```

The accept file cannot be combined with `--ignore` or `--allow-added`. It validates
every reviewed candidate hash even when the base has already advanced to that
hash. Unknown changes, removals, additions, altered reviewed entries and unrelated
base hashes fail. The repository gate uses this check rather than a PR-label bypass
or broad class-path exemptions.

To review another intended change, rebuild the selected JS overlays with the same
cached JDK and profile, snapshot them, inspect every delta and update only the exact
affected records with a reason. Keep the original before hash when the base still
uses that baseline; use the previously reviewed after hash as before when advancing
an already promoted baseline. Never replace a failure with a wildcard or approval
of an entire failing run. Keep snapshot hashes and build receipts in the review.

The PNG JavaScript stub has a compile-time false `BrowserPng.AVAILABLE`. Javac
eliminates the accelerated branch from `STBImage`, including for an explicit query
opt-in. Rust decoding is WasmGC-only, where the availability check precedes all
input copying and falls back unless the decoder is explicitly enabled and ready.
`png-target-gate-smoke.mjs` checks the actual JS overlay bytecode, while the PNG
runtime fixture checks the disabled Wasm boot and decoder paths without Chrome.

```sh
python3 -m unittest discover -s port/scripts -p test_overlay_golden_diff.py
node port/scripts/png-target-gate-smoke.mjs --snapshots "$SNAPSHOTS" --out "$OUT"
```
