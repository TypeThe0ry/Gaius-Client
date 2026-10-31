#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
source "$root/port/scripts/teavm-publication-gate.sh"
gaius_require_js_target || exit 2
gaius_load_version_profile "$root"
gaius_select_java_home
build_root="$(gaius_build_root "$root")"
overlay_directory="$(gaius_overlay_directory "$root")"
maven_repository="$(gaius_maven_repository "$root")"
maven_repository_for_java="$(gaius_maven_repository_for_java "$root")"
if [[ -n "${GAIUS_DIST_DIRECTORY:-}" || -n "${GAIUS_BUILD_ROOT:-}" || -n "${GAIUS_VERSION_PROFILE_PATH:-}" ]]; then
  dist="$(gaius_dist_directory "$root")"
else
  dist="${GAIUS_TARGET_DIRECTORY:-$(gaius_dist_directory "$root")}"
fi
server_target="$build_root/server-worker"
resource_list="$build_root/generated-resources/dev/gaius/browser/minecraft-resources.txt"
server_resources="$server_target/generated-resources"
mkdir -p "$build_root"

# VanillaPackResources.lowerBound() relies on Java String.compareTo order.  A
# locale-aware or partially generated list can make the binary-search prefix
# range stop early and silently omit datapack resources, so fail closed before
# the Worker compiler consumes it.
assert_java_sorted_resource_list() {
  local path="$1"
  if [[ ! -s "$path" ]]; then
    echo "Browser resource list is missing or empty: $path" >&2
    exit 1
  fi
  if LC_ALL=C grep -nFx '' "$path" >/dev/null 2>&1; then
    echo "Browser resource list contains a blank entry: $path" >&2
    exit 1
  fi
  if ! LC_ALL=C sort -c "$path" >/dev/null 2>&1; then
    echo "Browser resource list is not sorted in Java String.compareTo order: $path" >&2
    exit 1
  fi
  if [[ -n "$(LC_ALL=C uniq -d "$path")" ]]; then
    echo "Browser resource list contains duplicate entries: $path" >&2
    exit 1
  fi
}

# Client and Worker builds for one profile share the generated resources,
# release dist, identities, and compression pass.  Serialize that output
# surface while keeping distinct profile roots independently runnable.
output_lock="$build_root/.teavm-output.lock"
output_lock_owner=""
output_lock_owned_here=false
overlay_lock_owner=""
staging_root=""

cleanup_teavm_server_worker() {
  local status="$?"
  trap - EXIT
  if [[ -n "${staging_root:-}" ]]; then
    case "$staging_root" in
      "$build_root"/.teavm-staging/*)
        rm -rf -- "$staging_root" || true
        ;;
      *)
        echo "Refusing to remove unsafe TeaVM staging path: $staging_root" >&2
        ;;
    esac
  fi
  if declare -F release_overlay_lock >/dev/null 2>&1; then
    release_overlay_lock || true
  fi
  if [[ "$output_lock_owned_here" == true && -n "${output_lock_owner:-}" ]]; then
    gaius_teavm_lock_release "$output_lock" "$output_lock_owner" || true
  fi
  exit "$status"
}
trap cleanup_teavm_server_worker EXIT

if [[ "${GAIUS_TEA_OUTPUT_LOCK_HELD:-false}" == "true" ]]; then
  output_lock_owner="${GAIUS_TEA_OUTPUT_LOCK_OWNER:-}"
  gaius_teavm_lock_assert_owner "$output_lock" "$output_lock_owner" \
    || { echo "GAIUS_TEA_OUTPUT_LOCK_HELD=true without the profile output lock" >&2; exit 1; }
else
  gaius_teavm_lock_acquire "$output_lock"
  output_lock_owner="$GAIUS_TEA_LOCK_OWNER_TOKEN"
  output_lock_owned_here=true
fi

if [[ "${GAIUS_PREPARE_ONLY:-false}" == "true" ]]; then
  staged_dist="$dist"
else
  staging_root="$build_root/.teavm-staging/server-worker-$output_lock_owner"
  staged_dist="$staging_root/dist"
fi
mkdir -p "$staged_dist"

# TeaVM keeps dependency JARs open throughout whole-program analysis. Prevent
# another build from truncating and replacing an overlay while it is being read
# (the writer lock of the overlay directory, overlay-lock.sh).
source "$root/port/scripts/overlay-lock.sh"
overlay_lock=""
release_overlay_lock() {
  if [[ -n "${overlay_lock_owner:-}" ]]; then
    gaius_overlay_lock_release "$overlay_lock" "$overlay_lock_owner"
  fi
}
gaius_overlay_lock_acquire "$root" "$overlay_directory"
overlay_lock="$GAIUS_OVERLAY_LOCK_PATH"
overlay_lock_owner="$GAIUS_OVERLAY_LOCK_OWNER_TOKEN"

if [[ "${GAIUS_SKIP_OVERLAY_BUILD:-false}" != "true" ]]; then
  GAIUS_OVERLAY_DIRECTORY="$overlay_directory" GAIUS_OVERLAY_LOCK_HELD=true "$root/port/scripts/build-overlays.sh" >/dev/null
fi

assert_java_sorted_resource_list "$resource_list"

rm -rf "$server_resources" "$server_target/maven"
mkdir -p "$staged_dist" "$server_target" \
  "$server_resources/dev/gaius/browser"
server_resource_list="$server_resources/dev/gaius/browser/minecraft-resources.txt"
server_resource_list_tmp="$server_resource_list.tmp"
awk 'index($0, "data/") == 1 \
  || $0 == "assets/.mcassetsroot" \
  || $0 == "assets/minecraft/lang/deprecated.json" \
  || $0 == "assets/minecraft/lang/en_us.json" \
  || $0 == "pack.png" { print }' "$resource_list" \
  >"$server_resource_list_tmp"
LC_ALL=C sort -u -o "$server_resource_list_tmp" "$server_resource_list_tmp"
mv "$server_resource_list_tmp" "$server_resource_list"
assert_java_sorted_resource_list "$server_resource_list"
# MinecraftResourceSupplier prefers the embedded list when it exists.  That list is
# generated for the client and intentionally excludes most data resources; leaving a
# stale copy in the server resource directory makes the server silently lose tags and
# other world data.  The server must use its complete filtered resource list above.
rm -f "$server_resources/dev/gaius/browser/minecraft-embedded-resources.txt"
export GAIUS_POM="$server_target/generated-pom.xml"
# The Worker role's TeaVM compiler options come from the role table in
# generate-pom.sh (POM <properties>); GAIUS_TEAVM_WORKER_<OPTION> overrides one
# of them for the Worker only.
export GAIUS_TEAVM_ROLE="singleplayer-worker"
export GAIUS_MAIN_CLASS="dev.gaius.browser.BrowserIntegratedServerMain"
export GAIUS_TARGET_DIRECTORY="$staged_dist"
export GAIUS_TARGET_FILE="singleplayer-server.js"
export GAIUS_MAVEN_DIRECTORY="$server_target/maven"
export GAIUS_RESOURCE_DIRECTORY="$server_resources"
export GAIUS_BUILD_ROOT="$build_root"
export GAIUS_OVERLAY_DIRECTORY="$overlay_directory"
export GAIUS_MAVEN_REPOSITORY="$maven_repository"
export GAIUS_EXCLUDED_LIBRARY_PREFIXES="${GAIUS_SERVER_EXCLUDED_LIBRARY_PREFIXES:-com/microsoft/azure/msal4j/,com/azure/azure-json/}"
export GAIUS_TEA_OPTIMIZATION_LEVEL="${GAIUS_SERVER_TEA_OPTIMIZATION_LEVEL:-ADVANCED}"
export GAIUS_SOURCE_MAPS="${GAIUS_SERVER_SOURCE_MAPS:-false}"
export GAIUS_DEBUG_INFO="${GAIUS_SERVER_DEBUG_INFO:-false}"
export GAIUS_MINIFYING="${GAIUS_SERVER_MINIFYING:-true}"
export GAIUS_SHORT_FILE_NAMES="${GAIUS_SERVER_SHORT_FILE_NAMES:-true}"
export GAIUS_ASSERTIONS_REMOVED="${GAIUS_SERVER_ASSERTIONS_REMOVED:-true}"

pom="$("$root/port/scripts/generate-pom.sh")"
log="$server_target/teavm-build.log"

if [[ "${GAIUS_PREPARE_ONLY:-false}" == "true" ]]; then
  echo "Prepared Minecraft $GAIUS_MINECRAFT_VERSION server Worker resources and POM: $pom"
  exit 0
fi

echo "Compiling the official Minecraft server Worker with TeaVM"
echo "POM: $pom"
echo "Log: $log"

set +e
# MAVEN_OPTS only sizes the compiler JVM.  The Worker's TeaVM options
# (syncClinits and the rest of the role table) are POM <properties>; a
# leftover gaius.teavm JVM system property fails the build.
maven_offline_args=()
if [[ "${GAIUS_MAVEN_OFFLINE:-false}" == "true" ]]; then
  maven_offline_args=(--offline)
fi
MAVEN_OPTS="${MAVEN_OPTS:--Xms2g -Xmx14g -XX:+UseG1GC -XX:MaxGCPauseMillis=500}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  "${maven_offline_args[@]}" \
  "-Dmaven.repo.local=$maven_repository_for_java" \
  --file "$pom" \
  package >"$log" 2>&1
build_status="$?"
set -e

tail -n 160 "$log" || true

set +e
"$root/port/scripts/run-python.sh" \
  "$root/port/scripts/analyze-teavm-log.py" \
  "$log" \
  "$server_target/teavm-gap.json" \
  "$server_target/teavm-gap.md"
analysis_status="$?"
set -e
if [[ "$analysis_status" -ne 0 ]]; then
  echo "TeaVM server analysis did not complete; incomplete gap report was preserved" >&2
fi

if grep -Fq "Error in @JSBody" "$log"; then
  echo "TeaVM emitted invalid @JSBody JavaScript; refusing to publish the server Worker" >&2
  if [[ "$build_status" -eq 0 ]]; then
    build_status=1
  fi
fi

teavm_publish_allowed=false
if gaius_teavm_publish_allowed "$log" "$analysis_status"; then
  teavm_publish_allowed=true
elif [[ "$build_status" -eq 0 ]]; then
  # Preserve a real Maven failure status.  Only a Maven-successful build that
  # fails this post-build gate is converted to the generic publication error.
  build_status=1
fi

if [[ "$build_status" -eq 0 && "$teavm_publish_allowed" == true ]]; then
  staged_server_js="$staged_dist/singleplayer-server.js"
  final_server_js="$dist/singleplayer-server.js"
  staged_worker_bootstrap="$staged_dist/singleplayer-server-worker.js"
  final_worker_bootstrap="$dist/singleplayer-server-worker.js"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/postprocess-teavm-js.py" "$staged_server_js"
  cp "$root/port/web/singleplayer/server-worker-bootstrap.js" \
    "$staged_worker_bootstrap"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/gaius_build_identity.py" write \
    --root "$root" \
    --role singleplayer-worker \
    --artifact "$staged_server_js"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/gaius_build_identity.py" write \
    --root "$root" \
    --role worker-bootstrap \
    --artifact "$staged_worker_bootstrap"

  # The Maven POM points at the private staging directory. Generate a second
  # POM for the logical published path, then hash the staged bytes into a
  # release profile that can be committed with the whole Worker artifact set.
  server_release_pom="$server_target/release-generated-pom.xml"
  GAIUS_POM="$server_release_pom" \
    GAIUS_TARGET_DIRECTORY="$dist" \
    GAIUS_TARGET_FILE="singleplayer-server.js" \
    GAIUS_RESOURCE_DIRECTORY="$server_resources" \
    "$root/port/scripts/generate-pom.sh" >/dev/null
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/teavm-compiler-profile.py" write \
    --root "$root" \
    --role singleplayer-worker \
    --artifact "$final_server_js" \
    --artifact-input "$staged_server_js" \
    --output "${staged_server_js}.release.json" \
    --pom "$server_release_pom" \
    --resource "$server_resources/dev/gaius/browser/minecraft-resources.txt" \
    --require-release

  gaius_teavm_publish_bundle \
    "$staged_server_js" "$final_server_js" \
    "${staged_server_js}.build.json" "${final_server_js}.build.json" \
    "${staged_server_js}.release.json" "${final_server_js}.release.json" \
    "$staged_worker_bootstrap" "$final_worker_bootstrap" \
    "${staged_worker_bootstrap}.build.json" \
      "${final_worker_bootstrap}.build.json"
  gaius_teavm_remove_stale_incomplete_reports \
    "$server_target/teavm-gap.json" "$server_target/teavm-gap.md"
  if [[ "${GAIUS_SKIP_COMPRESSION:-false}" != "true" ]]; then
    GAIUS_DIST_DIRECTORY="$dist" GAIUS_COMPRESS_FILES="singleplayer-server.js:singleplayer-server-worker.js" \
      "$root/port/scripts/compress-dist.sh" >/dev/null
  fi
fi

exit "$build_status"
