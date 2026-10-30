#!/usr/bin/env bash
# External API surface of vanilla Minecraft. Offline and
# without TeaVM: reads port/work/<profile> (client-named.jar, version.json, libraries/), the local
# Maven repository (ASM, TeaVM jars) and the running JDK's lib/ct.sym (the API of each Java release).
#
#   bash port/tools/extrefs/extrefs.sh usage <profile> [--write|--check|--print]
#       What vanilla references in the JDK and in each native library;
#       --write (re)writes port/mc/profiles/<profile>/usage.lock, --check compares with it
#       (exit 1 and a +/- list when vanilla changed).
#   bash port/tools/extrefs/extrefs.sh jdk <profile> [--target js|wasmgc] [--patched JAR] [--strict]
#           [--classlib ROOT]...
#       The JDK members and classes of the usage that TeaVM's class library, as Gaius extends it,
#       does not provide, each with the port/overrides/jdk/<release> it belongs in. Without
#       --classlib the class library is assembled from the sources (TeaVM's jar, the classlib
#       overlay, port/overrides/jdk/<release <= javaVersion>, the port/src substitutes and
#       TeaVMClasslibPatcher; about half a minute). --patched marks gaps whose call sites the
#       patched client jar (overlays: client-named-<profile>-gaius.jar) no longer has; known
#       gaps come from port/scripts/link-gap/known-<profile>.json. --strict exits 1 on any other gap.
#   bash port/tools/extrefs/extrefs.sh apilock <profile> [--check|--write [--overlays DIR]...]
#       The api.lock of every port/overrides/libraries/<library>/src/versions/<range> whose library
#       the profile ships: --check compares the profile's library jar with the lock (exit 1 when the
#       range selects that version and its API differs); --write regenerates the locks whose range
#       selects the profile's version from the compiled override classes of overlay directories
#       (default GAIUS_OVERLAY_DIRECTORY or port/work/overlays/<profile>; pass one per target).
#   bash port/tools/extrefs/extrefs.sh check [<profile>...]
#       usage --check and apilock --check for each profile (default: every profile whose
#       port/work/<profile>/client-named.jar exists).
#
# Exit status: 0 ok, 1 difference or gap, 2 usage error, 3 missing input.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
tool="$root/port/tools/extrefs/ExtRefs.java"

native_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s\n' "$1"
  fi
}

separator=":"
if command -v cygpath >/dev/null 2>&1; then
  separator=";"
fi

java_home="${JAVA_HOME:-${GAIUS_JAVA_HOME:-}}"
if [[ -n "$java_home" ]]; then
  java_home="$(native_path "$java_home")"
  java="$java_home/bin/java"
  javac="$java_home/bin/javac"
  jar_tool="$java_home/bin/jar"
else
  java=java
  javac=javac
  jar_tool=jar
fi
repository="${GAIUS_MAVEN_REPOSITORY:-$HOME/.m2/repository}"
teavm_version="$(jq -er '.teaVMVersion' "$root/port/config.json" | tr -d '\r')"
asm=""
for artifact in asm asm-tree; do
  jar="$repository/org/ow2/asm/$artifact/9.8/$artifact-9.8.jar"
  if [[ ! -f "$jar" ]]; then
    echo "Missing $jar (offline Maven repository)" >&2
    exit 3
  fi
  asm="$asm${asm:+$separator}$(native_path "$jar")"
done

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

extrefs() {
  "$java" -classpath "$asm" "$(native_path "$tool")" "$@"
}

# profile_init PROFILE: PROFILE_JSON JAVA_RELEASE WORK CLIENT LIBRARIES (TSV of coordinate and jar).
profile_init() {
  profile="$1"
  profile_json="$root/port/versions/$profile.json"
  if [[ ! -f "$profile_json" ]]; then
    echo "Unknown profile $profile: $profile_json is missing" >&2
    exit 2
  fi
  java_release="$(jq -er '.javaVersion' "$profile_json" | tr -d '\r')"
  work="$root/port/work/$profile"
  client="$work/client-named.jar"
  if [[ ! -f "$client" || ! -f "$work/version.json" ]]; then
    echo "Missing $client or $work/version.json: run fetch-version.sh and remap-client.sh for $profile" >&2
    exit 3
  fi
  libraries="$scratch/libraries-$profile.tsv"
  : >"$libraries"
  local coordinate path
  # Every library artifact of version.json except the natives-* classifier jars.
  while IFS=$'\t' read -r coordinate path; do
    [[ -z "$coordinate" ]] && continue
    if [[ -f "$work/libraries/$path" ]]; then
      printf '%s\t%s\n' "$coordinate" "$(native_path "$work/libraries/$path")" >>"$libraries"
    fi
  done < <(jq -r '.libraries[]
      | select(.downloads.artifact.path != null)
      | select((.name | split(":") | length) < 4 or ((.name | split(":"))[3] | startswith("natives") | not))
      | [.name, .downloads.artifact.path] | @tsv' "$work/version.json" | tr -d '\r' | sort -u)
}

usage_lock() {
  printf '%s\n' "$root/port/mc/profiles/$1/usage.lock"
}

command_usage() {
  profile_init "$1"
  shift
  local lock
  lock="$(usage_lock "$profile")"
  local arguments=(usage --profile "$profile" --release "$java_release" --client "$(native_path "$client")"
    --libraries "$(native_path "$libraries")")
  case "${1:-}" in
    --write) arguments+=(--write "$(native_path "$lock")") ;;
    --check) arguments+=(--check "$(native_path "$lock")") ;;
    --print) arguments+=(--print) ;;
    "") ;;
    *) echo "Unknown usage option $1" >&2; exit 2 ;;
  esac
  extrefs "${arguments[@]}"
}

# assemble_classlib TARGET OUT_JAR: TeaVM's class library jar plus the Gaius substitutes, the way
# build-overlays.sh builds it (main source roots only; target roots need the JSPI-patched classes).
assemble_classlib() {
  local target="$1" out="$2"
  local upstream="$repository/org/teavm/teavm-classlib/$teavm_version/teavm-classlib-$teavm_version.jar"
  local classpath
  classpath="$(native_path "$upstream")"
  local artifact
  for artifact in teavm-interop teavm-jso teavm-jso-apis teavm-core teavm-platform; do
    classpath="$classpath$separator$(native_path "$repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar")"
  done
  classpath="$classpath$separator$(native_path "$repository/com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar")"
  local entry
  while IFS=$'\t' read -r _ entry; do
    classpath="$classpath$separator$entry"
  done <"$libraries"
  local roots=("$root/port/overrides/classlib/src/main/java")
  local directory release
  for directory in "$root"/port/overrides/jdk/*/; do
    release="$(basename "$directory")"
    if [[ "$release" =~ ^[0-9]+$ && "$release" -le "$java_release" && -d "$directory/src/main/java" ]]; then
      roots+=("$directory/src/main/java")
    fi
  done
  roots+=("$root/port/src/main/java/org/teavm/classlib")
  : >"$scratch/classlib.args"
  local source
  for directory in "${roots[@]}"; do
    while IFS= read -r source; do
      printf '"%s"\n' "$(native_path "$source")" >>"$scratch/classlib.args"
    done < <(find "$directory" -type f -name '*.java' | sort)
  done
  mkdir -p "$scratch/classlib-classes" "$scratch/classlib-tool" "$scratch/classlib-patches"
  "$javac" -J-Duser.language=en --release 21 -proc:none -nowarn -encoding UTF-8 \
    -classpath "$classpath" -d "$(native_path "$scratch/classlib-classes")" \
    "@$(native_path "$scratch/classlib.args")" >"$scratch/classlib-javac.log" 2>&1 \
    || { cat "$scratch/classlib-javac.log" >&2; echo "The class library sources do not compile" >&2; exit 3; }
  cp "$upstream" "$out"
  "$jar_tool" --update --file "$(native_path "$out")" -C "$(native_path "$scratch/classlib-classes")" .
  "$javac" -J-Duser.language=en --release 21 -proc:none -nowarn -classpath "$asm" \
    -d "$(native_path "$scratch/classlib-tool")" \
    "$(native_path "$root/port/tools/src/main/java/dev/gaius/tools/TeaVMClasslibPatcher.java")" \
    "$(native_path "$root/port/tools/src/main/java/dev/gaius/tools/BuildTarget.java")"
  GAIUS_TEAVM_TARGET="$target" "$java" -classpath "$(native_path "$scratch/classlib-tool")$separator$asm" \
    dev.gaius.tools.TeaVMClasslibPatcher "$(native_path "$out")" \
    "$(native_path "$scratch/classlib-patches")" >"$scratch/classlib-patcher.log" 2>&1 \
    || { cat "$scratch/classlib-patcher.log" >&2; exit 3; }
  "$jar_tool" --update --file "$(native_path "$out")" -C "$(native_path "$scratch/classlib-patches")" .
}

command_jdk() {
  profile_init "$1"
  shift
  local target=js strict="" patched="" classlib=()
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --target) target="$2"; shift 2 ;;
      --patched) patched="$2"; shift 2 ;;
      --classlib) classlib+=("$2"); shift 2 ;;
      --strict) strict=--strict; shift ;;
      *) echo "Unknown jdk option $1" >&2; exit 2 ;;
    esac
  done
  if [[ "${#classlib[@]}" -eq 0 ]]; then
    assemble_classlib "$target" "$scratch/classlib.jar"
    classlib=("$scratch/classlib.jar")
  fi
  # The releases that have a port/overrides/jdk directory or are a profile's javaVersion.
  local releases=() file release
  for file in "$root"/port/versions/*.json; do
    releases+=("$(jq -er '.javaVersion' "$file" | tr -d '\r')")
  done
  for file in "$root"/port/overrides/jdk/*/; do
    release="$(basename "$file")"
    [[ "$release" =~ ^[0-9]+$ ]] && releases+=("$release")
  done
  local release_list
  release_list="$(printf '%s\n' "${releases[@]}" | sort -n -u | awk -v max="$java_release" '$1 <= max' | paste -sd, -)"
  local arguments=(jdk --profile "$profile" --release "$java_release" --releases "$release_list"
    --target "$target" --client "$(native_path "$client")" --libraries "$(native_path "$libraries")"
    --known "$(native_path "$root/port/scripts/link-gap/known-$profile.json")")
  for file in "${classlib[@]}"; do
    arguments+=(--classlib "$(native_path "$file")")
  done
  [[ -n "$patched" ]] && arguments+=(--patched "$(native_path "$patched")")
  [[ -n "$strict" ]] && arguments+=("$strict")
  extrefs "${arguments[@]}"
}

# The api.lock files of the library override version directories.
api_locks() {
  find "$root/port/overrides/libraries" -path '*/src/versions/*/api.lock' -type f 2>/dev/null | sort
}

# library_jar GROUP:ARTIFACT: the profile's jar of the library (the plain artifact, else the first
# classifier jar such as lwjgl's "unsafe" one), as "coordinate<TAB>path", or nothing.
library_jar() {
  awk -F'\t' -v prefix="$1:" '
    index($1, prefix) == 1 {
      if (split($1, parts, ":") == 3) { print $1 "\t" $2; found = 1; exit }
      if (other == "") other = $1 "\t" $2
    }
    END { if (!found && other != "") print other }' "$libraries"
}

command_apilock() {
  profile_init "$1"
  shift
  local mode="--check" overlays=()
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --check|--write) mode="$1"; shift ;;
      --overlays) overlays+=("$2"); shift 2 ;;
      *) echo "Unknown apilock option $1" >&2; exit 2 ;;
    esac
  done
  if [[ "${#overlays[@]}" -eq 0 ]]; then
    overlays=("${GAIUS_OVERLAY_DIRECTORY:-$root/port/work/overlays/$profile}")
  fi
  local status=0 lock library match coordinate jar version range override name
  while IFS= read -r lock; do
    library="$(awk '$1 == "library" { print $2; exit }' "$lock" | tr -d '\r')"
    range="$(basename "$(dirname "$lock")")"
    match="$(library_jar "$library")"
    if [[ -z "$match" ]]; then
      echo "API_LOCK_SKIP ${lock#"$root/"}: $profile has no $library"
      continue
    fi
    coordinate="${match%%$'\t'*}"
    jar="${match#*$'\t'}"
    version="$(cut -d: -f3 <<<"$coordinate")"
    if [[ "$mode" == --write ]]; then
      if ! extrefs apilock --matches "$range" --version "$version" >/dev/null; then
        echo "API_LOCK_SKIP ${lock#"$root/"}: range $range does not select $coordinate"
        continue
      fi
      override="$(dirname "$(dirname "$(dirname "$(dirname "$lock")")")")"
      name="$(basename "$override")"
      local classes=() overlay
      for overlay in "${overlays[@]}"; do
        if [[ -d "$overlay/library-classes/$name" ]]; then
          classes+=(--classes "$(native_path "$overlay/library-classes/$name")")
        fi
      done
      if [[ "${#classes[@]}" -eq 0 ]]; then
        echo "No compiled $name override classes in ${overlays[*]} (library-classes/$name):" \
          "build the $profile overlays first (one --overlays directory per target)" >&2
        exit 3
      fi
      extrefs apilock --jar "$jar" --write "$(native_path "$lock")" --coordinate "$library" \
        --range "$range" --verified "$coordinate" --origin "${override#"$root/"}/src/versions/$range" \
        "${classes[@]}"
    else
      extrefs apilock --jar "$jar" --check "$(native_path "$lock")" --version "$version" || status=1
    fi
  done < <(api_locks)
  return "$status"
}

command_check() {
  local profiles=("$@") status=0 file
  if [[ "${#profiles[@]}" -eq 0 ]]; then
    for file in "$root"/port/versions/*.json; do
      local id
      id="$(basename "$file" .json)"
      [[ -f "$root/port/work/$id/client-named.jar" ]] && profiles+=("$id")
    done
  fi
  for id in "${profiles[@]}"; do
    command_usage "$id" --check || status=1
    command_apilock "$id" --check || status=1
  done
  return "$status"
}

case "${1:-}" in
  usage) shift; [[ "$#" -ge 1 ]] || { echo "usage: extrefs.sh usage <profile> [--write|--check|--print]" >&2; exit 2; }
    command_usage "$@" ;;
  jdk) shift; [[ "$#" -ge 1 ]] || { echo "usage: extrefs.sh jdk <profile> [options]" >&2; exit 2; }
    command_jdk "$@" ;;
  apilock) shift; [[ "$#" -ge 1 ]] || { echo "usage: extrefs.sh apilock <profile> [--check|--write]" >&2; exit 2; }
    command_apilock "$@" ;;
  check) shift; command_check "$@" ;;
  *)
    sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
