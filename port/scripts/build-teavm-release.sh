#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
source "$root/port/scripts/teavm-publication-gate.sh"
gaius_require_js_target || exit 2
gaius_load_version_profile "$root"
minecraft_version="$GAIUS_MINECRAFT_VERSION"
build_root="$(gaius_build_root "$root")"
if [[ -n "${GAIUS_DIST_DIRECTORY:-}" ]]; then
  dist="$(gaius_dist_directory "$root")"
elif [[ -n "${GAIUS_BUILD_ROOT:-}" || -n "${GAIUS_VERSION_PROFILE_PATH:-}" ]]; then
  # An isolated profile must never inherit a stale legacy TARGET_DIRECTORY.
  dist="$(gaius_dist_directory "$root")"
else
  dist="${GAIUS_TARGET_DIRECTORY:-$(gaius_dist_directory "$root")}"
fi
# Keep every child invocation on the same profile-scoped state/output roots.
# This also makes `build-teavm-release.sh` safe when callers pass only a
# profile and a build root rather than repeating all path variables.
export GAIUS_BUILD_ROOT="$build_root"
export GAIUS_DIST_DIRECTORY="$dist"
export GAIUS_TARGET_DIRECTORY="$dist"
export GAIUS_OVERLAY_DIRECTORY="$(gaius_overlay_directory "$root")"
# A release never uses bring-up mode or bring-up overlays (the BRINGUP marker
# of build-overlays.sh).  Overlays that will be reused as they are
# (GAIUS_SKIP_OVERLAY_BUILD, or a resumed client) are checked before any work;
# rebuilt overlays are checked again after the client build below.
gaius_refuse_bringup_release || exit 1
if [[ "${GAIUS_SKIP_OVERLAY_BUILD:-false}" == "true" \
      || "${GAIUS_SKIP_CLIENT_BUILD:-false}" == "true" ]]; then
  gaius_refuse_bringup_release "$GAIUS_OVERLAY_DIRECTORY" || exit 1
fi
asset_index_id="$(jq -er '.assetIndex.id // .assets' "$GAIUS_VERSION_METADATA" | tr -d '\r\n')"
identity_tool="$root/port/scripts/gaius_build_identity.py"
compiler_profile_tool="$root/port/scripts/teavm-compiler-profile.py"
client_pom="$build_root/generated-pom.xml"
client_resource_list="$build_root/generated-resources/dev/gaius/browser/minecraft-resources.txt"
client_embedded_resources="$build_root/generated-resources/dev/gaius/browser/minecraft-embedded-resources.txt"
asset_index="$root/port/work/$minecraft_version/assets/indexes/$asset_index_id.json"
generated_sounds="$build_root/generated-resources/assets/minecraft/sounds.json"
generated_unifont="$build_root/generated-resources/assets/minecraft/font/include/unifont.json"
generated_unifont_pua="$build_root/generated-resources/assets/minecraft/font/include/unifont_pua.json"
server_pom="$build_root/server-worker/generated-pom.xml"
server_resource_list="$build_root/server-worker/generated-resources/dev/gaius/browser/minecraft-resources.txt"

release_lock="$build_root/.release-build.lock"
mkdir -p "$build_root"
release_lock_owner=""
output_lock="$build_root/.teavm-output.lock"
output_lock_owner=""
output_lock_owned_here=false
release_staging_root=""
release_backup_root=""
release_backup_ready=false
release_completed=false
dist_existed_before_release=false

release_build_lock() {
  local status="$?"
  local failed_dist=""
  trap - EXIT
  if [[ "$release_completed" != true && "$release_backup_ready" == true ]]; then
    failed_dist="${dist}.release-failed-${release_lock_owner:-$$}"
    if [[ -e "$failed_dist" ]]; then
      echo "Refusing to overwrite existing failed release quarantine: $failed_dist" >&2
    elif [[ -d "$dist" ]] && mv "$dist" "$failed_dist"; then
      if [[ "$dist_existed_before_release" == true ]]; then
        if [[ -d "$release_backup_root" ]] && mv "$release_backup_root" "$dist"; then
          rm -rf -- "$failed_dist" || true
        else
          echo "Could not restore the previous release dist: $release_backup_root" >&2
          mv "$failed_dist" "$dist" 2>/dev/null || true
        fi
      else
        rm -rf -- "$failed_dist" || true
      fi
    elif [[ "$dist_existed_before_release" == true && -d "$release_backup_root" ]]; then
      mv "$release_backup_root" "$dist" 2>/dev/null \
        || echo "Could not restore missing release dist: $release_backup_root" >&2
    fi
  elif [[ "$release_completed" == true && -n "${release_backup_root:-}" ]]; then
    case "$release_backup_root" in
      "${dist}.release-backup-"*) rm -rf -- "$release_backup_root" || true ;;
      *) echo "Refusing to remove unsafe release backup: $release_backup_root" >&2 ;;
    esac
  fi
  if [[ -n "${release_staging_root:-}" ]]; then
    case "$release_staging_root" in
      "$build_root"/.teavm-staging/*)
        rm -rf -- "$release_staging_root" || true
        ;;
      *)
        echo "Refusing to remove unsafe TeaVM release staging path: $release_staging_root" >&2
        ;;
    esac
  fi
  if [[ "$output_lock_owned_here" == true && -n "${output_lock_owner:-}" ]]; then
    gaius_teavm_lock_release "$output_lock" "$output_lock_owner" || true
  fi
  if [[ -n "${release_lock_owner:-}" ]]; then
    gaius_teavm_lock_release "$release_lock" "$release_lock_owner" || true
  fi
  exit "$status"
}
trap release_build_lock EXIT

gaius_teavm_lock_acquire "$release_lock"
release_lock_owner="$GAIUS_TEA_LOCK_OWNER_TOKEN"

# The release wrapper also touches the final dist (HTML, relay registry,
# Wasm, compression). Hold the same profile output lock as direct client and
# Worker invocations, and let child scripts assert/reuse this ownership.
if [[ "${GAIUS_TEA_OUTPUT_LOCK_HELD:-false}" == "true" ]]; then
  output_lock_owner="${GAIUS_TEA_OUTPUT_LOCK_OWNER:-}"
  gaius_teavm_lock_assert_owner "$output_lock" "$output_lock_owner" \
    || { echo "GAIUS_TEA_OUTPUT_LOCK_HELD=true without the profile output lock" >&2; exit 1; }
else
  gaius_teavm_lock_acquire "$output_lock"
  output_lock_owner="$GAIUS_TEA_LOCK_OWNER_TOKEN"
  output_lock_owned_here=true
fi

release_staging_root="$build_root/.teavm-staging/release-$release_lock_owner"
mkdir -p "$release_staging_root"
release_backup_root="${dist}.release-backup-$release_lock_owner"
if [[ -e "$release_backup_root" ]]; then
  echo "Release backup path already exists: $release_backup_root" >&2
  exit 1
fi
if [[ -d "$dist" ]]; then
  dist_existed_before_release=true
  cp -a "$dist" "$release_backup_root"
  release_backup_ready=true
fi
mkdir -p "$dist"
release_backup_ready=true

export GAIUS_TEA_OUTPUT_LOCK_HELD=true
export GAIUS_TEA_OUTPUT_LOCK_OWNER="$output_lock_owner"

verify_identity() {
  local role="$1"
  local artifact="$2"
  "$root/port/scripts/run-python.sh" "$identity_tool" verify \
    --root "$root" \
    --role "$role" \
    --artifact "$artifact" >/dev/null
}

write_identity() {
  local role="$1"
  local artifact="$2"
  "$root/port/scripts/run-python.sh" "$identity_tool" write \
    --root "$root" \
    --role "$role" \
    --artifact "$artifact"
}

artifact_sha256() {
  local artifact="$1"
  gaius_sha256_file "$artifact"
}

write_client_release_profile() {
  local client_js="$1"
  local vanilla_asset_pack="$dist/vanilla-assets.pack.gz"
  "$root/port/scripts/run-python.sh" "$compiler_profile_tool" write \
    --root "$root" \
    --role client \
    --artifact "$client_js" \
    --pom "$client_pom" \
    --resource "$client_resource_list" \
    --resource "$client_embedded_resources" \
    --resource "$asset_index" \
    --resource "$generated_sounds" \
    --resource "$generated_unifont" \
    --resource "$generated_unifont_pua" \
    --resource "$vanilla_asset_pack" \
    --require-release
}

verify_client_release_profile() {
  local client_js="$1"
  local vanilla_asset_pack="$dist/vanilla-assets.pack.gz"
  "$root/port/scripts/run-python.sh" "$compiler_profile_tool" verify \
    --root "$root" \
    --role client \
    --artifact "$client_js" \
    --pom "$client_pom" \
    --resource "$client_resource_list" \
    --resource "$client_embedded_resources" \
    --resource "$asset_index" \
    --resource "$generated_sounds" \
    --resource "$generated_unifont" \
    --resource "$generated_unifont_pua" \
    --resource "$vanilla_asset_pack" \
    --require-release >/dev/null
}

verify_worker_release_profile() {
  local server_js="$1"
  "$root/port/scripts/run-python.sh" "$compiler_profile_tool" verify \
    --root "$root" \
    --role singleplayer-worker \
    --artifact "$server_js" \
    --pom "$server_pom" \
    --resource "$server_resource_list" \
    --require-release >/dev/null
}

generate_client_release_pom() {
  client_pom="$build_root/release-generated-pom.xml"
  GAIUS_TEAVM_ROLE=client \
    GAIUS_POM="$client_pom" \
    GAIUS_TARGET_DIRECTORY="$dist" \
    GAIUS_TARGET_FILE="${GAIUS_TARGET_FILE:-classes.js}" \
    GAIUS_RESOURCE_DIRECTORY="$build_root/generated-resources" \
    "$root/port/scripts/generate-pom.sh" >/dev/null
}

# A release of a renderpearl client (26.3+) must ship the WebAssembly shader
# toolchain: build-teavm.sh and postprocess-index-html.py fail without it.
export GAIUS_SHADER_TOOLCHAIN_STRICT=1
export GAIUS_TEA_OPTIMIZATION_LEVEL="${GAIUS_TEA_OPTIMIZATION_LEVEL:-ADVANCED}"
export GAIUS_SOURCE_MAPS="${GAIUS_SOURCE_MAPS:-false}"
export GAIUS_DEBUG_INFO="${GAIUS_DEBUG_INFO:-false}"
export GAIUS_MINIFYING="${GAIUS_MINIFYING:-true}"
export GAIUS_SHORT_FILE_NAMES="${GAIUS_SHORT_FILE_NAMES:-true}"
export GAIUS_ASSERTIONS_REMOVED="${GAIUS_ASSERTIONS_REMOVED:-true}"
# Releases drop the gaius-telemetry platform tag for both roles, so code
# guarded by @PlatformMarker("gaius-telemetry") compiles to constant false and
# its telemetry-only @JSBody calls leave the hot paths (generate-pom.sh role
# table).  GAIUS_TEAVM_STRIP_TELEMETRY=false keeps them for a profiling build.
export GAIUS_TEAVM_STRIP_TELEMETRY="${GAIUS_TEAVM_STRIP_TELEMETRY:-true}"
case "$GAIUS_TEAVM_STRIP_TELEMETRY" in
  true|false) ;;
  *)
    echo "Invalid GAIUS_TEAVM_STRIP_TELEMETRY: $GAIUS_TEAVM_STRIP_TELEMETRY (expected true or false)" >&2
    exit 1
    ;;
esac

if [[ "$GAIUS_TEA_OPTIMIZATION_LEVEL" != "ADVANCED" \
      && "$GAIUS_TEA_OPTIMIZATION_LEVEL" != "FULL" ]] \
    || [[ "$GAIUS_SOURCE_MAPS" != "false" \
      || "$GAIUS_DEBUG_INFO" != "false" \
      || "$GAIUS_MINIFYING" != "true" \
      || "$GAIUS_SHORT_FILE_NAMES" != "true" \
      || "$GAIUS_ASSERTIONS_REMOVED" != "true" ]]; then
  echo "Release builds require ADVANCED/FULL optimization, minification, short names, removed assertions, and no debug/source maps" >&2
  exit 1
fi

if [[ "${GAIUS_SKIP_CLIENT_BUILD:-false}" == "true" ]]; then
  # A previous staged build may have left generated-pom.xml pointing at its
  # private target directory. Resume verification must use a POM whose target
  # directory is the published dist, not that discarded staging path.
  generate_client_release_pom
  client_js="$dist/${GAIUS_TARGET_FILE:-classes.js}"
  vanilla_asset_pack="$dist/vanilla-assets.pack.gz"
  expected_client_sha256="${GAIUS_RESUME_CLIENT_SHA256:-}"
  if [[ ! -s "$client_js" || ! -s "$vanilla_asset_pack" ]]; then
    echo "Cannot resume release: client JavaScript or vanilla asset pack is missing" >&2
    echo "Client: $client_js" >&2
    echo "Assets: $vanilla_asset_pack" >&2
    exit 1
  fi
  if [[ -z "$expected_client_sha256" ]]; then
    echo "Cannot resume release: GAIUS_RESUME_CLIENT_SHA256 is required" >&2
    exit 1
  fi
  actual_client_sha256="$(artifact_sha256 "$client_js")"
  if [[ "$actual_client_sha256" != "$expected_client_sha256" ]]; then
    echo "Cannot resume release: client JavaScript SHA-256 does not match" >&2
    exit 1
  fi
  if grep -Fq 'Error in @JSBody' "$build_root/teavm-build.log"; then
    echo "Cannot resume release: TeaVM log contains invalid @JSBody JavaScript" >&2
    exit 1
  fi
  grep -aFq 'gaius-java-finite-long-cast' "$client_js" \
    || { echo "Cannot resume release: finite-long postprocess marker is missing" >&2; exit 1; }
  grep -aFq 'gaius-module-thread-slot' "$client_js" \
    || { echo "Cannot resume release: module-level thread slot postprocess marker is missing" >&2; exit 1; }
  grep -aFq 'target-attestation' "$client_js" \
    || { echo "Cannot resume release: Relay target-attestation guard is missing" >&2; exit 1; }
  node --check "$client_js"
  gzip -t "$vanilla_asset_pack"
  verify_identity client "$client_js" \
    || { echo "Cannot resume release: client build identity is stale or missing" >&2; exit 1; }
  verify_client_release_profile "$client_js" \
    || { echo "Cannot resume release: client compiler profile is stale, missing, or not release-grade" >&2; exit 1; }
  verify_identity vanilla-assets "$vanilla_asset_pack" \
    || { echo "Cannot resume release: vanilla asset build identity is stale or missing" >&2; exit 1; }
  set +e
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/analyze-teavm-log.py" \
    "$build_root/teavm-build.log" \
    "$build_root/teavm-gap.json" \
    "$build_root/teavm-gap.md"
  analysis_status="$?"
  set -e
  gaius_teavm_publish_allowed "$build_root/teavm-build.log" "$analysis_status" \
    || { echo "Cannot resume release: TeaVM publication gate rejected the client log" >&2; exit 1; }
  gaius_teavm_remove_stale_incomplete_reports \
    "$build_root/teavm-gap.json" "$build_root/teavm-gap.md"
  echo "Reusing successfully compiled client JavaScript: $client_js"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/postprocess-index-html.py" \
    "$dist/index.html" \
    "$client_js" \
    "$minecraft_version" \
    "$asset_index_id"
else
  GAIUS_SKIP_COMPRESSION=true "$root/port/scripts/build-teavm.sh"
  generate_client_release_pom
  write_client_release_profile "$dist/${GAIUS_TARGET_FILE:-classes.js}"
fi
# The client was compiled from these overlays, and the server Worker below
# reuses them.
gaius_refuse_bringup_release "$GAIUS_OVERLAY_DIRECTORY" || exit 1
if [[ "${GAIUS_SKIP_SERVER_WORKER:-false}" != "true" ]]; then
  GAIUS_SKIP_OVERLAY_BUILD=true GAIUS_SKIP_COMPRESSION=true \
    "$root/port/scripts/build-teavm-server-worker.sh"
  server_pom="$build_root/server-worker/release-generated-pom.xml"
else
  server_js="$dist/singleplayer-server.js"
  server_log="$build_root/server-worker/teavm-build.log"
  if [[ ! -f "$server_log" ]]; then
    echo "Cannot resume release: server Worker TeaVM log is missing at $server_log" >&2
    exit 1
  fi
  set +e
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/analyze-teavm-log.py" \
    "$server_log" \
    "$build_root/server-worker/teavm-gap.json" \
    "$build_root/server-worker/teavm-gap.md"
  server_analysis_status="$?"
  set -e
  gaius_teavm_publish_allowed "$server_log" "$server_analysis_status" \
    || { echo "Cannot resume release: TeaVM publication gate rejected the server log" >&2; exit 1; }
  gaius_teavm_remove_stale_incomplete_reports \
    "$build_root/server-worker/teavm-gap.json" \
    "$build_root/server-worker/teavm-gap.md"
  if grep -Fq 'Error in @JSBody' "$server_log"; then
    echo "Cannot resume release: server Worker TeaVM log contains invalid @JSBody JavaScript" >&2
    exit 1
  fi
  if [[ ! -s "$server_js" ]] || ! verify_identity singleplayer-worker "$server_js"; then
    echo "Cannot resume release: server Worker or matching build identity is missing at $server_js" >&2
    exit 1
  fi
  server_pom="$build_root/server-worker/release-generated-pom.xml"
  if [[ ! -f "$server_pom" ]]; then
    GAIUS_TEAVM_ROLE=singleplayer-worker \
      GAIUS_POM="$server_pom" \
      GAIUS_TARGET_DIRECTORY="$dist" \
      GAIUS_TARGET_FILE="singleplayer-server.js" \
      GAIUS_RESOURCE_DIRECTORY="$build_root/server-worker/generated-resources" \
      "$root/port/scripts/generate-pom.sh" >/dev/null
  fi
  verify_worker_release_profile "$server_js" \
    || { echo "Cannot resume release: server Worker compiler profile is stale, missing, or not release-grade" >&2; exit 1; }
  staged_worker_bootstrap="$release_staging_root/singleplayer-server-worker.js"
  final_worker_bootstrap="$dist/singleplayer-server-worker.js"
  cp "$root/port/web/singleplayer/server-worker-bootstrap.js" \
    "$staged_worker_bootstrap"
  write_identity worker-bootstrap "$staged_worker_bootstrap"
  gaius_teavm_publish_bundle \
    "$staged_worker_bootstrap" "$final_worker_bootstrap" \
    "${staged_worker_bootstrap}.build.json" \
      "${final_worker_bootstrap}.build.json"
  echo "Reusing successfully compiled server Worker JavaScript: $server_js"
fi
"$root/port/scripts/run-python.sh" \
  "$root/port/scripts/postprocess-index-html.py" \
  "$dist/index.html" \
  "$dist/${GAIUS_TARGET_FILE:-classes.js}" \
  "$minecraft_version" \
  "$asset_index_id"
# A renderpearl client (26.3+) ships the WebAssembly shader toolchain that
# build-teavm.sh published next to classes.js; postprocess-index-html.py has
# just tagged the page with its loader (STRICT: the files exist).  The
# toolchain manifest names every toolchain file with size and sha256, so one
# identity sidecar on it (role shader-toolchain) covers the loader and the four
# modules.  build-portable-html.py embeds the toolchain and verifies this
# sidecar.  26.2 has no loader tag and keeps exactly its six identity roles.
shader_toolchain_manifest="$dist/gaius-shader-toolchain.json"
if grep -Fq 'data-gaius-shader-toolchain' "$dist/index.html"; then
  if [[ ! -s "$shader_toolchain_manifest" ]]; then
    echo "The page loads the WebAssembly shader toolchain but $shader_toolchain_manifest is missing" >&2
    exit 1
  fi
  if [[ "${GAIUS_SKIP_CLIENT_BUILD:-false}" == "true" ]]; then
    verify_identity shader-toolchain "$shader_toolchain_manifest" \
      || { echo "Cannot resume release: shader toolchain build identity is stale or missing" >&2; exit 1; }
    echo "Reusing the identity-verified shader toolchain: $shader_toolchain_manifest"
  else
    write_identity shader-toolchain "$shader_toolchain_manifest"
  fi
elif [[ -e "$shader_toolchain_manifest" ]]; then
  echo "The page does not load the WebAssembly shader toolchain but $shader_toolchain_manifest exists" >&2
  exit 1
fi
wasm_hotpath="$dist/gaius-hotpath.wasm"
if [[ "${GAIUS_SKIP_WASM_HOTPATH:-false}" != "true" ]]; then
  if ! GAIUS_DIST_DIRECTORY="$dist" "$root/port/scripts/build-wasm-hotpath.sh"; then
    if [[ "${GAIUS_REQUIRE_WASM_HOTPATH:-false}" == "true" ]]; then
      exit 1
    fi
    if ! verify_identity wasm-hotpath "$wasm_hotpath"; then
      echo "Cannot resume release: Wasm build failed and no matching artifact identity exists" >&2
      exit 1
    fi
    echo "WARNING: Wasm build failed; reusing the identity-verified module." >&2
  else
    write_identity wasm-hotpath "$wasm_hotpath"
  fi
elif ! verify_identity wasm-hotpath "$wasm_hotpath"; then
  echo "Cannot resume release: skipped Wasm artifact has no matching build identity" >&2
  exit 1
fi
rm -f "$dist/${GAIUS_TARGET_FILE:-classes.js}.map" \
  "$dist/${GAIUS_TARGET_FILE:-classes.js}.teavmdbg"
cp "$root/relay-nodes.json" "$dist/relay-nodes.json"
write_identity relay-registry "$dist/relay-nodes.json"
GAIUS_DIST_DIRECTORY="$dist" GAIUS_COMPRESS_EXCLUDE=Gaius.html "$root/port/scripts/compress-dist.sh"
GAIUS_BUILD_ROOT="$build_root" GAIUS_DIST_DIRECTORY="$dist" \
  "$root/port/scripts/run-python.sh" \
  "$root/port/scripts/build-portable-html.py"
GAIUS_DIST_DIRECTORY="$dist" GAIUS_COMPRESS_FILES=Gaius.html:Gaius.manifest.json \
  "$root/port/scripts/compress-dist.sh"
# Every published profile must carry the final launcher security contract in its
# launcher shell. The portable page also inlines the runtime scripts, whose
# diagnostic switches read the query string, so the allow-list covers the shell only.
if ! "$root/port/scripts/run-python.sh" \
  "$root/port/scripts/check-release-launcher-security.py" "$dist/index.html"; then
  echo "Release launcher security contract failed" >&2
  exit 1
fi
# Do not publish a profile build whose portable artifact silently predates the
# multiplayer bounded-drain bridge.  This is a release/artifact identity gate,
# not a latency or stall threshold, and it runs only after the final HTML and
# compressed classes have been written.  Keep the JSON evidence in the
# profile-scoped build root so a failed release remains diagnosable.
artifact_contract="$root/apps/bridge/browser-full-path-artifact-contract-smoke.mjs"
if [[ ! -f "$artifact_contract" ]]; then
  echo "Multiplayer artifact contract is missing: $artifact_contract" >&2
  exit 1
fi
if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is required to verify the multiplayer portable artifact" >&2
  exit 1
fi
artifact_contract_report="$build_root/browser-full-path-artifact-contract.json"
if ! GAIUS_VERSION_PROFILE_PATH="${GAIUS_VERSION_PROFILE_PATH:-versions/$minecraft_version.json}" \
  GAIUS_DIST_DIRECTORY="$dist" \
  node "$artifact_contract" >"$artifact_contract_report" 2>&1; then
  echo "Multiplayer portable artifact contract failed; see $artifact_contract_report" >&2
  cat "$artifact_contract_report" >&2 || true
  exit 1
fi
release_completed=true
