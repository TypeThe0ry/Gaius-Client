#!/usr/bin/env bash
set -euo pipefail

# Build a RelayNode deployment bundle from the committed files of a git ref.
#
#   deploy/make-bundle.sh <git-ref> <output-dir>
#
# The bundle root is the former apps/bridge directory and contains exactly the
# files the runtime needs (dist/, protocols/ when the ref has them, package.json,
# package-lock.json, Dockerfile) plus SHA256SUMS and BUNDLE-INFO. dist/ and
# protocols/ are one unit: never mix them across releases. Nothing is read from
# the working tree, so local edits or node_modules cannot leak into the bundle.
# The archive is reproducible for a given commit.

ref=${1:-}
out=${2:-}
[[ -n "$ref" && -n "$out" ]] || { echo "usage: make-bundle.sh <git-ref> <output-dir>" >&2; exit 2; }

repo=$(git rev-parse --show-toplevel)
commit=$(git -C "$repo" rev-parse --verify "${ref}^{commit}")
short=${commit:0:12}

paths=()
for candidate in apps/bridge/dist apps/bridge/protocols apps/bridge/package.json \
    apps/bridge/package-lock.json apps/bridge/Dockerfile; do
    if git -C "$repo" cat-file -e "${commit}:${candidate}" 2>/dev/null; then
        paths+=("$candidate")
    fi
done
for required in apps/bridge/dist apps/bridge/package.json apps/bridge/package-lock.json apps/bridge/Dockerfile; do
    printf '%s\n' "${paths[@]}" | grep -Fxq "$required" \
        || { echo "make-bundle: $required is missing at $short" >&2; exit 1; }
done

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
git -C "$repo" archive --format=tar "$commit" -- "${paths[@]}" | tar -x -C "$stage" --strip-components=2

version=$(node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")).version)' "$stage/package.json")
printf 'commit=%s\npackageVersion=%s\n' "$commit" "$version" > "$stage/BUNDLE-INFO"
(cd "$stage" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS)

mkdir -p "$out"
archive="$(cd "$out" && pwd)/gaius-relay-${version}-${short}.tar.gz"
(cd "$stage" && tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf - .) | gzip -n > "$archive"
(cd "$(dirname "$archive")" && sha256sum "$(basename "$archive")" > "$(basename "$archive").sha256")
printf 'make-bundle: %s (%s files)\n' "$archive" "$(wc -l < "$stage/SHA256SUMS")"
cat "${archive}.sha256"
