#!/usr/bin/env bash

# macOS ships `shasum`, while GNU/Linux and Git for Windows normally ship the
# coreutils `sha1sum`/`sha256sum` pair.  Keep artifact verification portable
# instead of assuming the macOS command exists on every migration host.
gaius_hash_file() {
  local algorithm="$1"
  local file="$2"
  local command_name="${algorithm}sum"

  if command -v "$command_name" >/dev/null 2>&1; then
    "$command_name" "$file" | awk '{print $1}' | tr '[:upper:]' '[:lower:]' | tr -d '\r\n'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a "${algorithm#sha}" "$file" | awk '{print $1}' | tr '[:upper:]' '[:lower:]' | tr -d '\r\n'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst "-$algorithm" "$file" | awk '{print $NF}' | tr '[:upper:]' '[:lower:]' | tr -d '\r\n'
  else
    echo "No $algorithm checksum tool is available (tried $command_name, shasum, and openssl)" >&2
    return 1
  fi
}

gaius_sha1_file() {
  gaius_hash_file sha1 "$1"
}

gaius_sha256_file() {
  gaius_hash_file sha256 "$1"
}

gaius_load_version_profile() {
  local root="$1"
  local config="$root/port/config.json"
  local relative_profile

  relative_profile="${GAIUS_VERSION_PROFILE_PATH:-$(jq -er '.versionProfile' "$config")}"
  case "$relative_profile" in
    versions/*.json) ;;
    *)
      echo "port/config.json versionProfile must point inside port/versions" >&2
      return 1
      ;;
  esac

  GAIUS_VERSION_PROFILE="$root/port/$relative_profile"
  if [[ ! -f "$GAIUS_VERSION_PROFILE" ]]; then
    echo "Version profile is missing: $GAIUS_VERSION_PROFILE" >&2
    return 1
  fi

  GAIUS_MINECRAFT_VERSION="$(jq -er '.id' "$GAIUS_VERSION_PROFILE")"
  GAIUS_PROTOCOL_VERSION="$(jq -er '.protocolVersion' "$GAIUS_VERSION_PROFILE")"
  GAIUS_WORLD_VERSION="$(jq -er '.worldVersion' "$GAIUS_VERSION_PROFILE")"
  GAIUS_JAVA_VERSION="$(jq -er '.javaVersion' "$GAIUS_VERSION_PROFILE")"
  GAIUS_CLASS_FILE_VERSION="$(jq -er '.classFileVersion' "$GAIUS_VERSION_PROFILE")"
  GAIUS_CLIENT_DISTRIBUTION="$(jq -er '.clientDistribution' "$GAIUS_VERSION_PROFILE")"
  GAIUS_STORAGE_SCHEMA="$(jq -er '.storage.schema' "$GAIUS_VERSION_PROFILE")"
  GAIUS_STORAGE_DATABASE_NAME="$(jq -er '.storage.databaseName' "$GAIUS_VERSION_PROFILE")"
  GAIUS_STORAGE_PREFIX="$(jq -er '.storage.prefix' "$GAIUS_VERSION_PROFILE")"
  GAIUS_STORAGE_OPFS_DIRECTORY="$(jq -er '.storage.opfsDirectory' "$GAIUS_VERSION_PROFILE")"
  if [[ "$GAIUS_STORAGE_SCHEMA" != "2" ]]; then
    echo "Version profile storage.schema must be exactly 2: $GAIUS_VERSION_PROFILE (got $GAIUS_STORAGE_SCHEMA)" >&2
    return 1
  fi
  local expected_database_name="gaius-fs-v2-$GAIUS_MINECRAFT_VERSION"
  local expected_prefix="gaius.fs.v2:$GAIUS_MINECRAFT_VERSION:"
  local expected_opfs_directory="regions-v2-$GAIUS_MINECRAFT_VERSION"
  if [[ "$GAIUS_STORAGE_DATABASE_NAME" != "$expected_database_name" ]]; then
    echo "Version profile storage.databaseName must be $expected_database_name: $GAIUS_VERSION_PROFILE" >&2
    return 1
  fi
  if [[ "$GAIUS_STORAGE_PREFIX" != "$expected_prefix" ]]; then
    echo "Version profile storage.prefix must be $expected_prefix: $GAIUS_VERSION_PROFILE" >&2
    return 1
  fi
  if [[ "$GAIUS_STORAGE_OPFS_DIRECTORY" != "$expected_opfs_directory" ]]; then
    echo "Version profile storage.opfsDirectory must be $expected_opfs_directory: $GAIUS_VERSION_PROFILE" >&2
    return 1
  fi
  GAIUS_VERSION_METADATA="$root/port/work/$GAIUS_MINECRAFT_VERSION/version.json"
  # The patcher chain is selected by these two profile fields rather than by
  # comparing version strings.  They are loaded leniently here; build-overlays.sh
  # rejects a missing or unknown patch set, and check-version-profile.mjs
  # validates both fields.
  GAIUS_PATCH_SET="$(jq -r '.patchSet // empty' "$GAIUS_VERSION_PROFILE" | tr -d '\r')"
  GAIUS_EXTRA_PATCHERS="$(jq -r '(.extraPatchers // []) | join(" ")' "$GAIUS_VERSION_PROFILE" | tr -d '\r')"

  export GAIUS_VERSION_PROFILE GAIUS_MINECRAFT_VERSION GAIUS_PROTOCOL_VERSION
  export GAIUS_WORLD_VERSION GAIUS_JAVA_VERSION GAIUS_CLASS_FILE_VERSION
  export GAIUS_CLIENT_DISTRIBUTION GAIUS_VERSION_METADATA
  export GAIUS_STORAGE_SCHEMA GAIUS_STORAGE_DATABASE_NAME
  export GAIUS_STORAGE_PREFIX GAIUS_STORAGE_OPFS_DIRECTORY
  export GAIUS_PATCH_SET GAIUS_EXTRA_PATCHERS
}

# Bring-up mode lets an unfinished profile build its overlays while the
# patches listed in port/tools/bringup/<profile>.txt are skipped.  Each list
# line is "<patchId> | <owner package> | <reason>"; '#' starts a comment.
# The mode is active only when the caller exports GAIUS_BRINGUP=1 and the
# profile allows it ("bringupAllowed": true in port/versions/<id>.json; a new
# profile does, a profile whose patches are all finished does not).  Every
# skipped patch prints "BRINGUP_SKIP <patchId>" so check-build-log-skips.mjs can
# match the build log against the list.
gaius_bringup_list_path() {
  local root="$1"
  printf '%s\n' "$root/port/tools/bringup/$GAIUS_MINECRAFT_VERSION.txt"
}

gaius_bringup_profile_allowed() {
  jq -e '.bringupAllowed == true' "$GAIUS_VERSION_PROFILE" >/dev/null
}

gaius_bringup_active() {
  [[ "${GAIUS_BRINGUP:-}" == "1" ]] && gaius_bringup_profile_allowed
}

# Release builds never use bring-up mode.  Fails when GAIUS_BRINGUP is set to
# anything but 0, or when OVERLAY_DIRECTORY (optional) carries the BRINGUP
# marker that build-overlays.sh writes before a bring-up build and removes only
# after a complete build outside bring-up mode.
gaius_refuse_bringup_release() {
  local overlay_directory="${1:-}"
  if [[ -n "${GAIUS_BRINGUP:-}" && "${GAIUS_BRINGUP}" != "0" ]]; then
    echo "Refusing to build a release in bring-up mode (GAIUS_BRINGUP=${GAIUS_BRINGUP})" >&2
    echo "Unset GAIUS_BRINGUP; release profiles must build without skipped patches" >&2
    return 1
  fi
  if [[ -n "$overlay_directory" && -e "$overlay_directory/BRINGUP" ]]; then
    echo "Refusing to build a release from bring-up overlays: $overlay_directory/BRINGUP exists" >&2
    echo "Rebuild the overlays without GAIUS_BRINGUP=1 (port/scripts/build-overlays.sh)" >&2
    return 1
  fi
  return 0
}

# The JavaScript build entry points (build-teavm.sh, build-teavm-server-worker.sh,
# build-teavm-release.sh, build-version-release.sh) refuse an inherited
# GAIUS_TEAVM_TARGET other than js: build-overlays.sh, generate-pom.sh and the
# patchers read it, so it would build WebAssembly GC overlays and a
# WEBASSEMBLY_GC POM into the JavaScript output roots (build-wasmgc.sh is the
# wasmgc entry point).  Exports GAIUS_TEAVM_TARGET=js for the child scripts.
gaius_require_js_target() {
  local target="${GAIUS_TEAVM_TARGET:-js}"
  if [[ "$target" != js ]]; then
    echo "$(basename "$0") builds the JavaScript target, but GAIUS_TEAVM_TARGET=$target" >&2
    echo "Unset GAIUS_TEAVM_TARGET, or build the wasmgc target with port/scripts/build-wasmgc.sh" >&2
    return 1
  fi
  export GAIUS_TEAVM_TARGET=js
}

# Prints the patch ids of the profile's bring-up list, one per line.  Fails
# on malformed lines so a typo cannot silently widen or narrow the list.
# Whether a vanilla client jar uses the renderpearl render API (26.3+), whose
# OpenGL backend compiles shaders through shaderc/SPIRV-Cross.  unzip exits 11
# when no entry matches; any other failure is an error, never "no".
gaius_client_uses_renderpearl() {
  local client_jar="$1"
  local status=0
  if command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$client_jar" 'com/mojang/renderpearl/*' >/dev/null 2>&1 || status="$?"
    case "$status" in
      0) return 0 ;;
      11) return 1 ;;
      *)
        echo "Cannot list $client_jar (unzip exit $status)" >&2
        exit 1
        ;;
    esac
  fi
  local listing
  listing="$(jar --list --file "$client_jar")" || {
    echo "Cannot list $client_jar" >&2
    exit 1
  }
  grep -q '^com/mojang/renderpearl/' <<<"$listing"
}

# A patch id matches ^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$; PatchRegistry.java,
# check-version-profile.mjs and check-build-log-skips.mjs use the same rule.
gaius_bringup_ids() {
  local root="$1"
  local list
  list="$(gaius_bringup_list_path "$root")"
  if [[ ! -f "$list" ]]; then
    return 0
  fi
  tr -d '\r' <"$list" | awk -v list="$list" '
    {
      line = $0
      sub(/#.*/, "", line)
      if (line ~ /^[ \t]*$/) next
      fields = split(line, part, "|")
      for (i = 1; i <= fields; i++) gsub(/^[ \t]+|[ \t]+$/, "", part[i])
      if (fields != 3 || part[1] !~ /^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$/ \
          || part[2] !~ /^P[1-9][a-z]?$/ || part[3] == "") {
        printf "%s:%d: expected \"<patchId> | <owner P1..P9> | <reason>\"\n", list, NR > "/dev/stderr"
        bad = 1
        next
      }
      if (part[1] in seen) {
        printf "%s:%d: %s is listed twice\n", list, NR, part[1] > "/dev/stderr"
        bad = 1
        next
      }
      seen[part[1]] = 1
      print part[1]
    }
    END { exit bad }
  '
}

# Prints the entries of a library version excludes.txt, one relative path per line:
# port/overrides/libraries/<name>/src/versions/<artifact-version>/excludes.txt
# (the source sets of port/mc/shared have their own, read by port/tools/sourcesets).
# Each entry is a path relative to SHARED_ROOT (the matching src/main/java).
# '#' starts a comment, CR line ends and surrounding whitespace are ignored,
# and an entry that does not name an existing file under SHARED_ROOT fails, so
# a typo or a stale entry cannot be ignored silently.  Prints nothing when
# FILE does not exist.
gaius_version_excludes() {
  local file="$1"
  local shared_root="$2"
  local entry
  local line=0
  if [[ ! -f "$file" ]]; then
    return 0
  fi
  while IFS= read -r entry || [[ -n "$entry" ]]; do
    line=$((line + 1))
    entry="${entry%$'\r'}"
    entry="${entry%%#*}"
    entry="${entry#"${entry%%[![:space:]]*}"}"
    entry="${entry%"${entry##*[![:space:]]}"}"
    if [[ -z "$entry" ]]; then
      continue
    fi
    case "$entry" in
      /*|..|../*|*/..|*/../*)
        echo "Invalid entry in $file:$line: $entry (expected a path relative to $shared_root)" >&2
        return 1
        ;;
    esac
    if [[ ! -f "$shared_root/$entry" ]]; then
      echo "Stale entry in $file:$line: $shared_root/$entry does not exist" >&2
      return 1
    fi
    printf '%s\n' "$entry"
  done <"$file"
}

# Like gaius_library_path, but prints nothing and succeeds when the profile's
# version.json does not contain the library (for example lwjgl-glfw in 26.3
# or lwjgl-sdl in 26.2).  Missing metadata is still an error.
gaius_library_path_optional() {
  local coordinate="$1"
  local fallback_classifier="${2:-}"
  if [[ ! -f "$GAIUS_VERSION_METADATA" ]]; then
    echo "Version metadata is missing: $GAIUS_VERSION_METADATA" >&2
    return 1
  fi

  jq -r --arg coordinate "$coordinate" --arg fallback "$fallback_classifier" '
    [
      .libraries[]
      | select(.name | startswith($coordinate + ":"))
      | select(.downloads.artifact.path != null)
      | {parts: (.name | split(":")), path: .downloads.artifact.path}
    ] as $matches
    | (
        first($matches[] | select(.parts | length == 3))
        // first($matches[] | select($fallback != "" and .parts[3] == $fallback))
        // {path: ""}
      ).path
  ' "$GAIUS_VERSION_METADATA" | tr -d '\r'
}

# Build-state paths are profile-scoped by default.  A caller may still provide
# explicit roots for a disposable build, but a normal invocation must not
# recreate the old shared port/target, port/work/overlays, or port/web/dist
# outputs.  Release automation can therefore build both profiles in separate
# invocations without changing port/config.json or clobbering another
# profile's generated files:
#
#   GAIUS_VERSION_PROFILE_PATH=versions/1.21.11.json \
#   GAIUS_BUILD_ROOT=port/target/1.21.11 \
#   GAIUS_OVERLAY_DIRECTORY=port/work/overlays/1.21.11 \
#   GAIUS_DIST_DIRECTORY=port/web/dist/1.21.11 \
#   port/scripts/build-teavm-release.sh
#
# Keep these as functions instead of exporting default values so child scripts
# can resolve the same profile-scoped roots.
gaius_resolve_path() {
  local root="$1"
  local value="$2"
  case "$value" in
    [A-Za-z]:/*|[A-Za-z]:\\*)
      # release-train.py passes native Windows paths. Bash joins paths into colon class
      # paths that `cygpath -mp` splits again, so keep them in POSIX form.
      if command -v cygpath >/dev/null 2>&1; then
        cygpath -u "$value"
      else
        printf '%s\n' "$value"
      fi
      ;;
    /*)
      printf '%s\n' "$value"
      ;;
    *)
      printf '%s/%s\n' "$root" "$value"
      ;;
  esac
}

# Keep the shell-visible Maven repository and Maven's Java-visible repository
# on the same path.  Java derives user.home from the OS account rather than the
# shell HOME variable, so isolated CI/cluster jobs must pass the repository
# explicitly instead of silently falling back to a shared ~/.m2 cache.
gaius_maven_repository() {
  local root="$1"
  if [[ -n "${GAIUS_MAVEN_REPOSITORY:-}" ]]; then
    gaius_resolve_path "$root" "$GAIUS_MAVEN_REPOSITORY"
  else
    printf '%s\n' "$HOME/.m2/repository"
  fi
}

gaius_maven_repository_for_java() {
  local repository
  repository="$(gaius_maven_repository "$1")"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$repository"
  else
    printf '%s\n' "$repository"
  fi
}

gaius_build_root() {
  local root="$1"
  if [[ -n "${GAIUS_BUILD_ROOT:-}" ]]; then
    gaius_resolve_path "$root" "$GAIUS_BUILD_ROOT"
  else
    printf '%s\n' "$root/port/target/$GAIUS_MINECRAFT_VERSION"
  fi
}

gaius_dist_directory() {
  local root="$1"
  if [[ -n "${GAIUS_DIST_DIRECTORY:-}" ]]; then
    gaius_resolve_path "$root" "$GAIUS_DIST_DIRECTORY"
  else
    printf '%s\n' "$root/port/web/dist/$GAIUS_MINECRAFT_VERSION"
  fi
}

gaius_overlay_directory() {
  local root="$1"
  if [[ -n "${GAIUS_OVERLAY_DIRECTORY:-}" ]]; then
    gaius_resolve_path "$root" "$GAIUS_OVERLAY_DIRECTORY"
  else
    printf '%s\n' "$root/port/work/overlays/$GAIUS_MINECRAFT_VERSION"
  fi
}

gaius_library_path() {
  local coordinate="$1"
  local fallback_classifier="${2:-}"
  if [[ ! -f "$GAIUS_VERSION_METADATA" ]]; then
    echo "Version metadata is missing: $GAIUS_VERSION_METADATA" >&2
    return 1
  fi

  jq -er --arg coordinate "$coordinate" --arg fallback "$fallback_classifier" '
    [
      .libraries[]
      | select(.name | startswith($coordinate + ":"))
      | select(.downloads.artifact.path != null)
      | {parts: (.name | split(":")), path: .downloads.artifact.path}
    ] as $matches
    | (
        first($matches[] | select(.parts | length == 3))
        // first($matches[] | select($fallback != "" and .parts[3] == $fallback))
      ).path
  ' "$GAIUS_VERSION_METADATA"
}

gaius_select_java_home() {
  local requested_version="$GAIUS_JAVA_VERSION"
  local candidates=()
  local candidate
  local detected_version

  if [[ -n "${GAIUS_JAVA_HOME:-}" ]]; then
    candidates+=("$GAIUS_JAVA_HOME")
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    candidates+=("$JAVA_HOME")
  fi
  candidates+=(
    "/opt/homebrew/opt/openjdk@$requested_version/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@$requested_version/libexec/openjdk.jdk/Contents/Home"
  )
  if [[ "$(uname -s)" == "Darwin" ]]; then
    candidate="$(/usr/libexec/java_home -v "$requested_version" 2>/dev/null || true)"
    if [[ -n "$candidate" ]]; then
      candidates+=("$candidate")
    fi
  fi
  if command -v javac >/dev/null 2>&1; then
    candidate="$(cd "$(dirname "$(command -v javac)")/.." 2>/dev/null && pwd || true)"
    if [[ -n "$candidate" ]]; then
      candidates+=("$candidate")
    fi
  fi

  for candidate in "${candidates[@]}"; do
    if [[ ! -x "$candidate/bin/javac" || ! -x "$candidate/bin/java" ]]; then
      continue
    fi
    detected_version="$("$candidate/bin/javac" -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
    if [[ "$detected_version" =~ ^[0-9]+$ ]] &&
        [[ "$detected_version" -ge "$requested_version" ]]; then
      GAIUS_JAVA_HOME="$candidate"
      JAVA_HOME="$candidate"
      PATH="$candidate/bin:$PATH"
      export GAIUS_JAVA_HOME JAVA_HOME PATH
      return 0
    fi
  done

  echo "Minecraft $GAIUS_MINECRAFT_VERSION requires JDK $requested_version or newer" >&2
  echo "Set GAIUS_JAVA_HOME to a compatible JDK installation" >&2
  return 1
}
