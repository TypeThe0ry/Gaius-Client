#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
source "$root/port/scripts/teavm-publication-gate.sh"
gaius_load_version_profile "$root"
gaius_select_java_home
build_root="$(gaius_build_root "$root")"
overlay_directory="$(gaius_overlay_directory "$root")"
maven_repository="$(gaius_maven_repository "$root")"
maven_repository_for_java="$(gaius_maven_repository_for_java "$root")"
export GAIUS_MAVEN_REPOSITORY="$maven_repository"
smoke_directory="${GAIUS_PLATFORM_SMOKE_DIRECTORY:-$root/port/web/smoke}"
if [[ -n "${GAIUS_BUILD_ROOT:-}" || -n "${GAIUS_VERSION_PROFILE_PATH:-}" ]] && [[ -z "${GAIUS_PLATFORM_SMOKE_DIRECTORY:-}" ]]; then
  smoke_directory="$root/port/web/smoke/$GAIUS_MINECRAFT_VERSION"
fi
# The smoke compiler reads the same mutable overlay JARs as release builds.
# Keep the writer lock of the overlay directory (overlay-lock.sh) until TeaVM
# exits so a concurrent rebuild cannot corrupt an already-open ZipFile.
source "$root/port/scripts/overlay-lock.sh"
overlay_lock=""
overlay_lock_owner=""
release_overlay_lock() {
  local status="$?"
  trap - EXIT
  if [[ -n "${overlay_lock_owner:-}" ]]; then
    gaius_overlay_lock_release "$overlay_lock" "$overlay_lock_owner" || true
  fi
  exit "$status"
}
trap release_overlay_lock EXIT
gaius_overlay_lock_acquire "$root" "$overlay_directory"
overlay_lock="$GAIUS_OVERLAY_LOCK_PATH"
overlay_lock_owner="$GAIUS_OVERLAY_LOCK_OWNER_TOKEN"

if [[ "${GAIUS_SKIP_OVERLAY_BUILD:-false}" != "true" ]]; then
  GAIUS_OVERLAY_DIRECTORY="$overlay_directory" GAIUS_OVERLAY_LOCK_HELD=true "$root/port/scripts/build-overlays.sh" >/dev/null
else
  echo "Skipping overlay rebuild because GAIUS_SKIP_OVERLAY_BUILD=true"
fi

pom="$(
  GAIUS_MAIN_CLASS=dev.gaius.browser.PlatformSmoke \
  GAIUS_TARGET_DIRECTORY="$smoke_directory" \
  GAIUS_TARGET_FILE=platform-smoke-v4.js \
  GAIUS_BUILD_ROOT="$build_root" \
  GAIUS_OVERLAY_DIRECTORY="$overlay_directory" \
  GAIUS_POM="$build_root/platform-smoke-pom.xml" \
  "$root/port/scripts/generate-pom.sh"
)"

log="$build_root/platform-smoke-teavm.log"
MAVEN_OPTS="${MAVEN_OPTS:--Xms512m -Xmx4g -XX:+UseG1GC}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  "-Dmaven.repo.local=$maven_repository_for_java" \
  --file "$pom" \
  package 2>&1 | tee "$log"

if grep -Fq 'Error in @JSBody' "$log"; then
  echo "Platform smoke rejected TeaVM @JSBody errors" >&2
  exit 1
fi
