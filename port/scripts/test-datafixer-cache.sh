#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
libraries="${1:?usage: test-datafixer-cache.sh <version-libraries> <output-directory> [client-named.jar]}"
output="${2:?output directory is required}"
libraries="$(cd "$libraries" && pwd)"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
repository="${GAIUS_MAVEN_REPOSITORY:-$HOME/.m2/repository}"
asm="$repository/org/ow2/asm/asm/9.8/asm-9.8.jar:$repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar"
mapfile -t jars < <(rg --files "$libraries" | sed -n '/\.jar$/p' | sort)
mapfile -t datafixers < <(printf '%s\n' "${jars[@]}" | sed -n '/\/datafixerupper\//p')
[[ "${#datafixers[@]}" == 1 ]] || { echo 'Expected exactly one DataFixerUpper JAR' >&2; exit 1; }
classpath="$(IFS=:; echo "${jars[*]}")"
mkdir -p "$output/classes" "$output/patched"
javac -J-Xmx768m --release 21 -proc:none -classpath "$asm:$classpath" -d "$output/classes" \
  "$root/port/src/main/java/dev/gaius/browser/BrowserDataFixerCaches.java" \
  "$root/port/tools/src/main/java/dev/gaius/tools/DataFixerCacheBrowserPatcher.java" \
  "$root/port/tools/src/test/java/dev/gaius/tools/BrowserDataFixerCachesTest.java"
java -Xmx768m -classpath "$output/classes:$asm" dev.gaius.tools.DataFixerCacheBrowserPatcher \
  "${datafixers[0]}" "$output/patched"
java -Xmx2g -classpath "$output/classes:$classpath" dev.gaius.tools.BrowserDataFixerCachesTest original \
  | tee "$output/original.log"
java -Xmx2g -classpath "$output/patched:$output/classes:$classpath" dev.gaius.tools.BrowserDataFixerCachesTest bounded \
  | tee "$output/bounded.log"
sed -n '/^MIGRATIONS /p' "$output/original.log" > "$output/original-migrations.txt"
sed -n '/^MIGRATIONS /p' "$output/bounded.log" > "$output/bounded-migrations.txt"
cmp "$output/original-migrations.txt" "$output/bounded-migrations.txt"
echo 'PASS original and bounded migration bytes match'
if [[ -n "${3:-}" ]]; then
  client="$(cd "$(dirname "$3")" && pwd)/$(basename "$3")"
  javac -J-Xmx768m --release 21 -proc:none -classpath "$client:$classpath" -d "$output/classes" \
    "$root/port/tools/src/test/java/dev/gaius/tools/BrowserDataFixerMigrationTest.java"
  for mode in original bounded; do
    mkdir -p "$output/migrations-$mode"
    runtime="$output/classes:$client:$classpath"
    [[ "$mode" != bounded ]] || runtime="$output/patched:$runtime"
    (cd "$output/migrations-$mode"; uptime; java -Xmx2g -Djava.io.tmpdir="${TMPDIR:?TMPDIR must be explicit}" \
      -classpath "$runtime" dev.gaius.tools.BrowserDataFixerMigrationTest "$output/migrations-$mode"; uptime) \
      | tee "$output/migrations-$mode.log"
  done
  for name in options chunk player; do
    cmp "$output/migrations-original/$name.nbt" "$output/migrations-bounded/$name.nbt"
  done
  echo 'PASS historical options, chunk and player NBT bytes match'
fi
