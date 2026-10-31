# Offline version-profile cache checks

`node port/scripts/check-version-profile.mjs --all` is read-only and offline unless `--remote`
is explicitly supplied. A cached asset index, when present, must hash to the SHA-1 named by its
cached `version.json`; the metadata must then match the profile's pinned revision. Matching an
index id is insufficient. Cache corruption and a coherent but differently pinned revision are
reported separately, with the metadata, profile and index paths and the actual hashes.

## Cache inspected on 2026-10-08

The `1.21.11` profile records Mojang asset index `29` with SHA-1
`4043c34fdd5d06b2ac1f6f46c5b91a4ac16e3452`. That pin entered repository history in `6d6c53d2`
on 2026-09-29. `docs/feasibility.md` records 4,590 objects and 446,134,512 total asset bytes for
the earlier official metadata snapshot.

The local `port/work/1.21.11/version.json` instead names
`ccece3dfc4c1331584c5cb2655edc51192341efd` in both `assetIndex.sha1` and its content-addressed
Mojang package URL. The cached `assets/indexes/29.json` is 529,966 bytes and its actual SHA-1 is
exactly `ccece3dfc4c1331584c5cb2655edc51192341efd`. It contains 4,591 objects; the metadata
declares 451,545,103 total asset bytes. Client, server and mappings hashes, Java version and
release timestamp agree with the profile.

This is a self-consistent **different cache revision**, not evidence of a damaged index. The
extra object and different total size are consistent with a republished index, but neither a
matching cached digest nor a cached publisher URL authenticates that hypothesis. The old index
bytes and an independently verified official metadata snapshot naming the replacement were
not available in this task. No network access was used. Keep the original repository pin rather
than promote an unauthenticated local cache to the reference merely to make the check green.

## Recovery

1. Keep a copy of the conflicting metadata and index outside shared `port/work`.
2. If keeping the frozen revision, restore a trusted metadata/index pair that names and hashes
   to the profile pin through the repository's cache/build workflow. Refetching current metadata
   is not guaranteed to restore a historical asset-index revision.
3. If adopting a republished revision, first verify publisher metadata identifying that exact
   digest and the index bytes, then change only the profile's `official.assetIndexSha1` and
   record the evidence. Do not weaken the SHA-1 check or select a revision using the index id.
4. Rerun `node port/scripts/check-version-profile.mjs --all --require-local`.

On the inspected cache the all-profile check intentionally fails with an actionable diagnostic;
the checker does not rewrite shared caches, download data or silently relax the frozen pin.

## 26.3 asset index republish, reviewed on 2026-10-15

The official manifest entry for `26.3` (version JSON `11f1aefe473eefba0af62e3286c33b692807b2eb`,
manifest `time` 2026-10-13, `releaseTime` unchanged) now names asset index `34` with SHA-1
`e787e8afd149a4cefa89d341b91f5aed8f5d93a0`, 597,035 bytes, `totalSize` 484,829,952. The
repository had pinned `abfaa525f923f807df8b4e4d29c1b5e3a104adbe` (same id, same index file size,
`totalSize` 483,782,790). Both index bytes were fetched from their content-addressed
`piston-meta.mojang.com/v1/packages/<sha1>/34.json` URLs and hash to their names. Client, server,
Java version and release time in the version JSON are unchanged.

Both indexes hold 5,147 objects with the same keys. Exactly 142 objects changed (+1,047,162 bytes in total) and none were added
or removed; they are all of the index's `minecraft/lang/<locale>.json` entries (every locale except `en_us`, which
comes from the client jar), each larger. Gaius fetches and packs only `sounds.json`, the
unifont files, title backgrounds and the listed `.ogg` sounds from the index
(`port/scripts/fetch-version.sh`), plus `en_us`/`deprecated` language files from the client jar, so
no shipped content or `vanilla-assets.pack` byte changed. Only `official.assetIndexSha1` in
`port/versions/26.3.json` was updated. Local `port/work/26.3` caches still holding the old index must
be refetched with `fetch-version.sh`.

## 26.2 asset index republish, reviewed on 2026-10-15

Same republish as 26.3 (manifest `time` 2026-10-13, `releaseTime` unchanged; version JSON
`91ef51b7d36ca092c5c02aba42f0f40c7cc1db84`, sha1 matches the manifest). Asset index `32` moved from
`9a622c23d8ff76c4c44787c2e171c56feed1f786` to `edb1c85bb6a1227c35194c715f1687d6e52d125d`; both are
586,366 bytes, still downloadable by content address and hash to their names. Client, server and
mappings sha1, Java version and release time match the profile. Both indexes hold 5,057 objects with
the same keys; the only changes are the 142 `minecraft/lang/<locale>.json` entries (+1,047,162 bytes,
`totalSize` 480,579,086 -> 481,626,248). Nothing Gaius fetches or packs changes (see the 26.3 note);
only `official.assetIndexSha1` was updated.

## 1.21.11 asset index republish, reviewed on 2026-10-15

Same republish as 26.3 (manifest `time` 2026-10-13, `releaseTime` unchanged; version JSON
`718cd84b4912691fe5de829aab6876a4b7f6c5c4`, sha1 matches the manifest). Asset index `29` moved from
`4043c34fdd5d06b2ac1f6f46c5b91a4ac16e3452` to `e81d9e2536723a11eb68e11c5642708926b8be5f`; both are
529,966 bytes, still downloadable by content address and hash to their names. Client, server and
mappings sha1, Java version and release time match the profile. Both indexes hold 4,591 objects with
the same keys; the only changes are the 142 `minecraft/lang/<locale>.json` entries (+1,047,162 bytes,
`totalSize` 451,631,470 -> 452,678,632). Nothing Gaius fetches or packs changes; only
`official.assetIndexSha1` was updated.

This also explains the "different cache revision" described above: `ccece3df...` (451,545,103 total
bytes) is another republish of the same 4,591 keys that differs from the old pin only in those 142
language entries (-86,367 bytes), not an extra object. It is superseded by `e81d9e25...`; the earlier
"4,590 objects" in `docs/feasibility.md` predates the pin. Refetch `port/work/1.21.11` with
`fetch-version.sh` to replace the `ccece3df...` cache.

## Asset index republish of all three profiles, reviewed on 2026-10-29

The official manifest (entries `time` 2026-10-27, `releaseTime` unchanged) names new asset
index revisions for every profile: `1.21.11` index `29` `0e43ee533675d60aa9b7e5959742ac9c9cd2eccb`,
`26.2` index `32` `a35ecceffb8b3443b5d2fb88ca6f93f9b1fbcbf9` and `26.3` index `34`
`917ff6d74b9f635c46bac0d28857206bbcc1ffe4`. Each was fetched from its content-addressed
`piston-meta.mojang.com/v1/packages/<sha1>/<id>.json` URL and hashes to its name, with the same
byte size as the current pin (529,966, 586,366 and 597,035 bytes). Client sha1 and Java version in
the version JSONs match the profiles. Against the pins each index has the same keys (4,591, 5,057
and 5,147 objects), none added or removed; exactly 143 objects changed in each: the 142
`minecraft/lang/<locale>.json` entries and the root `pack.mcmeta` (+209,839 bytes in total).
`fetch-version.sh` takes neither from the index, so nothing Gaius fetches or packs changes. The
new digests were added to `official.assetIndexSha1Alternates`; the pins stay. With them,
`check-version-profile.mjs --all --require-local --remote` passes on caches holding the new indexes.
