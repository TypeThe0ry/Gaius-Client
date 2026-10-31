#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
config="$root/port/config.json"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
gaius_select_java_home
version="$GAIUS_MINECRAFT_VERSION"
art_version="$(jq -er '.autoRenamingToolVersion' "$config")"
work="$root/port/work/$version"
tools="$root/port/work/tools"
art="$tools/AutoRenamingTool-$art_version-all.jar"
input="$work/client-original.jar"
mappings="$work/client-mappings.txt"
output="$work/client-named.jar"

strip_jar_signatures() {
  local archive="$1"
  python3 - "$archive" <<'PY'
import os
import re
import sys
import tempfile
import zipfile

archive = sys.argv[1]
signature_suffixes = (".SF", ".RSA", ".DSA", ".EC")
directory = os.path.dirname(os.path.abspath(archive))
fd, temporary = tempfile.mkstemp(prefix=".unsigned-", suffix=".jar", dir=directory)
os.close(fd)
try:
    with zipfile.ZipFile(archive, "r") as source, zipfile.ZipFile(temporary, "w") as target:
        for info in source.infolist():
            upper = info.filename.upper()
            if upper.startswith("META-INF/") and upper.rsplit("/", 1)[-1].endswith(signature_suffixes):
                continue
            content = source.read(info.filename)
            if upper == "META-INF/MANIFEST.MF":
                lines = content.decode("utf-8").splitlines(keepends=True)
                cleaned = []
                dropping = False
                for line in lines:
                    if line.startswith((" ", "\t")):
                        if not dropping:
                            cleaned.append(line)
                        continue
                    dropping = bool(re.match(r"^[^:]*Digest[^:]*:", line, re.I))
                    if not dropping:
                        cleaned.append(line)
                content = "".join(cleaned).encode("utf-8")
            target.writestr(info, content)
    os.replace(temporary, archive)
except BaseException:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
    raise

with zipfile.ZipFile(archive, "r") as bundle:
    remaining = [
        name for name in bundle.namelist()
        if name.upper().startswith("META-INF/")
        and name.upper().rsplit("/", 1)[-1].endswith(signature_suffixes)
    ]
if remaining:
    raise SystemExit("signed entries remain after rewrite: " + ", ".join(remaining))
PY
}

if [[ ! -f "$input" ]]; then
  echo "Run ./port/scripts/fetch-version.sh first" >&2
  exit 1
fi

if [[ "$GAIUS_CLIENT_DISTRIBUTION" == "named" ]]; then
  cp "$input" "$output"
  echo "Using the official named Minecraft $version client without remapping"
else
  if [[ ! -f "$mappings" ]]; then
    echo "Official mappings are missing; run ./port/scripts/fetch-version.sh first" >&2
    exit 1
  fi

  mkdir -p "$tools"
  if [[ ! -f "$art" ]]; then
    curl -fL --retry 3 -o "$art.part" \
      "https://maven.neoforged.net/releases/net/neoforged/AutoRenamingTool/$art_version/AutoRenamingTool-$art_version-all.jar"
    mv "$art.part" "$art"
  fi

  library_arguments=()
  while IFS= read -r library; do
    library_arguments+=(--lib "$library")
  done < <(find "$work/libraries" -type f -name '*.jar' -print | sort)

  rm -f "$output"
  echo "Remapping official Minecraft $version client to Mojang names"
  java -Xmx3g -jar "$art" \
    --input "$input" \
    --output "$output" \
    --map "$mappings" \
    --reverse \
    --strip-sigs \
    --ann-fix \
    --record-fix \
    --src-fix JAVA \
    "${library_arguments[@]}"
fi

strip_jar_signatures "$output"

main_class="net/minecraft/client/main/Main.class"
if command -v unzip >/dev/null 2>&1; then
  # BusyBox unzip does not implement every Info-ZIP listing mode. Match the
  # final member column in the portable long-listing format.
  if ! unzip -l "$output" 2>/dev/null |
    awk -v expected="$main_class" '$NF == expected { found = 1 } END { exit(found ? 0 : 1) }'; then
    echo "Remapped client does not contain $main_class" >&2
    exit 1
  fi
  class_count="$(unzip -l "$output" | awk '$4 ~ /\.class$/ { count++ } END { print count + 0 }')"
else
  read -r main_present class_count < <(python3 - "$output" "$main_class" <<'PY'
import sys
import zipfile

archive, expected = sys.argv[1:]
with zipfile.ZipFile(archive) as bundle:
    names = bundle.namelist()
print(1 if expected in names else 0, sum(name.endswith('.class') for name in names))
PY
  )
  if [[ "$main_present" != "1" ]]; then
    echo "Remapped client does not contain $main_class" >&2
    exit 1
  fi
fi
echo "Named client ready:"
echo "  output:  $output"
echo "  classes: $class_count"
echo "  main:    $main_class"
