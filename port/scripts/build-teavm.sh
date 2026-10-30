#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
source "$root/port/scripts/teavm-publication-gate.sh"
gaius_require_js_target || exit 2
gaius_load_version_profile "$root"
gaius_select_java_home
version="$GAIUS_MINECRAFT_VERSION"
work="$root/port/work/$version"
build_root="$(gaius_build_root "$root")"
overlay_directory="$(gaius_overlay_directory "$root")"
maven_repository="$(gaius_maven_repository "$root")"
maven_repository_for_java="$(gaius_maven_repository_for_java "$root")"
if [[ -n "${GAIUS_DIST_DIRECTORY:-}" || -n "${GAIUS_BUILD_ROOT:-}" || -n "${GAIUS_VERSION_PROFILE_PATH:-}" ]]; then
  target_directory="$(gaius_dist_directory "$root")"
else
  target_directory="${GAIUS_TARGET_DIRECTORY:-$(gaius_dist_directory "$root")}"
fi
# The singleplayer launcher is only a redirect.  Build each profile from the
# tracked, version-neutral full shell instead of treating a generated shared
# dist as source input.
index_template="${GAIUS_INDEX_TEMPLATE:-$root/port/web/launcher/index.template.html}"
mkdir -p "$build_root"

# Client and integrated-server builds share the profile's generated POM/log,
# reports, resource table, dist directory, identities, and compression pass.
# Serialize those outputs per profile; a different GAIUS_BUILD_ROOT remains
# independent and can run in parallel.
output_lock="$build_root/.teavm-output.lock"
output_lock_owner=""
output_lock_owned_here=false
overlay_lock_owner=""
staging_root=""

cleanup_teavm_client() {
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
trap cleanup_teavm_client EXIT

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
  staged_target_directory="$target_directory"
else
  staging_root="$build_root/.teavm-staging/client-$output_lock_owner"
  staged_target_directory="$staging_root/dist"
fi
mkdir -p "$staged_target_directory"

export GAIUS_BUILD_ROOT="$build_root"
export GAIUS_OVERLAY_DIRECTORY="$overlay_directory"
export GAIUS_DIST_DIRECTORY="$target_directory"
export GAIUS_MAVEN_REPOSITORY="$maven_repository"

# The browser client logs through the Gaius slf4j overlay; log4j-core and the
# log4j slf4j binding pull desktop SSL/script/OSGi/disruptor paths into the
# TeaVM reachable graph, so they are excluded from the client classpath.
# log4j-api stays available for any direct API references.
client_log4j_exclusions="org/apache/logging/log4j/log4j-core/,org/apache/logging/log4j/log4j-slf4j2-impl/"
export GAIUS_EXCLUDED_LIBRARY_PREFIXES="${GAIUS_EXCLUDED_LIBRARY_PREFIXES:+$GAIUS_EXCLUDED_LIBRARY_PREFIXES,}$client_log4j_exclusions"

# TeaVM keeps dependency JARs open throughout whole-program analysis. Hold the
# writer lock of the overlay directory (overlay-lock.sh) from before regeneration
# until every consumer has closed.
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
    GAIUS_OVERLAY_DIRECTORY="$overlay_directory" \
      GAIUS_OVERLAY_LOCK_HELD=true "$root/port/scripts/build-overlays.sh" \
      >"$build_root/overlays.log" 2>&1 \
      || { tail -n 60 "$build_root/overlays.log" >&2; exit 1; }
    # WP1 rule f: the js patch set must match the checked-in baseline.  A bring-up
    # build skips listed patches on purpose and is not compared.
    if ! gaius_bringup_active; then
      gaius_js_patch_summary_gate "$root" "$version" "$build_root/overlays.log"
    fi
else
  echo "Skipping overlay rebuild because GAIUS_SKIP_OVERLAY_BUILD=true"
  # A resumed build may reuse an overlay JAR that an earlier pass already
  # postprocessed.  Refresh the patch tools from the current source even when
  # the expensive overlay rebuild is skipped; otherwise a stale non-idempotent
  # patcher class can reject that valid, already-rewritten JAR.
  tool_classes="$overlay_directory/tool-classes"
  asm_version="9.8"
  teavm_version="$(jq -er '.teaVMVersion' "$root/port/config.json")"
  tool_compile_classpath="$maven_repository/org/ow2/asm/asm/$asm_version/asm-$asm_version.jar:$maven_repository/org/ow2/asm/asm-tree/$asm_version/asm-tree-$asm_version.jar"
  if command -v cygpath >/dev/null 2>&1; then
    tool_compile_classpath="$maven_repository_for_java/org/ow2/asm/asm/$asm_version/asm-$asm_version.jar;$maven_repository_for_java/org/ow2/asm/asm-tree/$asm_version/asm-tree-$asm_version.jar;$maven_repository_for_java/org/teavm/teavm-core/$teavm_version/teavm-core-$teavm_version.jar"
  else
    tool_compile_classpath="$tool_compile_classpath:$maven_repository/org/teavm/teavm-core/$teavm_version/teavm-core-$teavm_version.jar"
  fi
  mkdir -p "$tool_classes"
  find "$tool_classes" -type f -delete
  javac --release 21 -proc:none \
    -classpath "$tool_compile_classpath" \
    -sourcepath "$root/port/tools/src/main/java" \
    -d "$tool_classes" \
    "$root/port/tools/src/main/java/dev/gaius/tools/"*.java
fi
# Gson/Guava TypeToken portability patches are client-overlay postprocessing,
# not part of regenerating the base overlays.  Always refresh and apply them
# while the overlay writer lock is held so resume/release builds with
# GAIUS_SKIP_OVERLAY_BUILD=true cannot consume a stale unpatched client JAR.
gson_type_token_patches="$build_root/gson-type-token-client-patches"
mkdir -p "$gson_type_token_patches"
find "$gson_type_token_patches" -type f -delete
gson_patcher_classpath="$overlay_directory/tool-classes:$maven_repository/org/ow2/asm/asm/9.8/asm-9.8.jar:$maven_repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar"
# Windows java.exe needs `;` and native paths; Git Bash does not rewrite this list.
if command -v cygpath >/dev/null 2>&1; then
  gson_patcher_classpath="$(cygpath -mp "$gson_patcher_classpath")"
fi
java -classpath \
  "$gson_patcher_classpath" \
  dev.gaius.tools.GsonTypeTokenClientPatcher \
  "$overlay_directory/client-named-$version-gaius.jar" \
  "$gson_type_token_patches" \
  "$work/libraries"
jar --update \
  --file "$overlay_directory/client-named-$version-gaius.jar" \
  -C "$gson_type_token_patches" .
node "$root/port/scripts/gson-type-token-smoke.mjs" \
  --profile "$GAIUS_VERSION_PROFILE" \
  --overlay "$overlay_directory"
icu_path="$(gaius_library_path "com.ibm.icu:icu4j")"
resource_list_dir="$build_root/generated-resources/dev/gaius/browser"
resource_list="$resource_list_dir/minecraft-resources.txt"
embedded_resource_list="$resource_list_dir/minecraft-embedded-resources.txt"
generated_resources="$build_root/generated-resources"
generated_assets="$build_root/generated-resources/assets"
vanilla_asset_pack="$staged_target_directory/vanilla-assets.pack.gz"
mkdir -p "$resource_list_dir"
jar tf "$overlay_directory/client-named-$version-gaius.jar" |
  awk '(index($0, "assets/") == 1 || index($0, "data/") == 1 || $0 == "pack.png") && substr($0, length($0), 1) != "/" { print }' >"$resource_list"
jar tf "$overlay_directory/libraries/$icu_path" |
  awk 'index($0, "com/ibm/icu/impl/data/icudata/") == 1 && substr($0, length($0), 1) != "/" { print }' >>"$resource_list"
rm -rf "$generated_assets/minecraft/sounds" "$generated_assets/minecraft/sounds.json" \
  "$generated_assets/minecraft/font"
asset_index_id="$(jq -er '.assetIndex.id // .assets' "$work/version.json" 2>/dev/null | tr -d '\r\n' || true)"
GAIUS_ASSET_INDEX_ID="$asset_index_id"
export GAIUS_ASSET_INDEX_ID
asset_index="$work/assets/indexes/$asset_index_id.json"
copied_sound_assets=0
copied_font_assets=0
if [[ -n "$asset_index_id" && -f "$asset_index" ]]; then
  browser_sound_manifest="$build_root/browser-sound-assets.tsv"
  jq -r '
    .objects
    | to_entries[]
    | select(
        .key as $path
        | ($path == "minecraft/sounds/ui/toast/in.ogg")
          or ($path == "minecraft/sounds/ui/toast/out.ogg")
          or ($path == "minecraft/sounds/ui/toast/challenge_complete.ogg")
          or ($path == "minecraft/sounds/ui/stonecutter/cut1.ogg")
          or ($path == "minecraft/sounds/ui/stonecutter/cut2.ogg")
          or (
            ($path | endswith(".ogg"))
            and (
              ($path | startswith("minecraft/sounds/block/"))
              or ($path | startswith("minecraft/sounds/random/"))
              or ($path | startswith("minecraft/sounds/dig/"))
              or ($path | startswith("minecraft/sounds/step/"))
              or ($path | startswith("minecraft/sounds/mob/"))
              or ($path | startswith("minecraft/sounds/entity/"))
              or ($path | startswith("minecraft/sounds/item/"))
              or ($path | startswith("minecraft/sounds/damage/"))
              or ($path | startswith("minecraft/sounds/liquid/"))
              or ($path | startswith("minecraft/sounds/fire/"))
              or ($path | startswith("minecraft/sounds/portal/"))
              or ($path | startswith("minecraft/sounds/minecart/"))
              or ($path | startswith("minecraft/sounds/enchant/"))
              or ($path | startswith("minecraft/sounds/fireworks/"))
              or ($path | startswith("minecraft/sounds/event/"))
              or ($path | startswith("minecraft/sounds/note/"))
              or ($path | startswith("minecraft/sounds/tile/"))
            )
          )
      )
    | [.key, .value.hash]
    | @tsv
  ' "$asset_index" | tr -d '\r' >"$browser_sound_manifest"
  # Stage the content-addressed sound objects in one Python process.  The
  # helper preserves manifest/resource/name order and keeps the old warning
  # and skip behavior for an absent object.
  browser_sound_names="$build_root/browser-sound-names.json"
  sound_missing_policy=warn
  sound_verify_args=()
  if [[ "${GAIUS_ASSETS_OFFLINE:-false}" == "true" ]]; then
    sound_missing_policy=error
    sound_verify_args=(--verify-sha1)
  fi
  sound_stage_result="$("$root/port/scripts/run-python.sh" \
    "$root/port/scripts/stage-browser-sounds.py" \
    --manifest "$browser_sound_manifest" \
    --objects "$work/assets/objects" \
    --output "$generated_assets" \
    --resource-list "$resource_list" \
    --sound-names "$browser_sound_names" \
    --missing "$sound_missing_policy" "${sound_verify_args[@]}")"
  printf '%s\n' "$sound_stage_result" >"$build_root/browser-sound-stage.json"
  copied_sound_assets="$(jq -er '.copied' "$build_root/browser-sound-stage.json")"
  echo "Staged browser sounds: $copied_sound_assets copied"

  # Mojang ships the Unicode fallback as indexed assets rather than client-jar
  # entries. Embed it so browser resource packs can safely override default.json.
  browser_font_manifest="$build_root/browser-font-assets.tsv"
  jq -r '
    .objects
    | to_entries[]
    | select(
        .key == "minecraft/font/include/unifont.json"
        or .key == "minecraft/font/include/unifont_pua.json"
        or .key == "minecraft/font/unifont.zip"
        or .key == "minecraft/font/unifont_jp.zip"
        or .key == "minecraft/font/unifont_pua.zip"
      )
    | [.key, .value.hash]
    | @tsv
  ' "$asset_index" | tr -d '\r' >"$browser_font_manifest"
  while IFS=$'\t' read -r logical_path hash; do
    if [[ -z "$logical_path" || -z "$hash" ]]; then
      continue
    fi
    source="$work/assets/objects/${hash:0:2}/$hash"
    if [[ ! -f "$source" ]] || [[ "$(gaius_sha1_file "$source")" != "$hash" ]]; then
      if [[ "${GAIUS_ASSETS_OFFLINE:-false}" == "true" ]]; then
        echo "Missing or invalid offline browser Unicode font asset: $logical_path ($hash)" >&2
        exit 1
      fi
      mkdir -p "$(dirname "$source")"
      temporary="$source.part"
      curl -fsSL --http1.1 --retry 5 --retry-delay 1 \
        -o "$temporary" "https://resources.download.minecraft.net/${hash:0:2}/$hash"
      if [[ "$(gaius_sha1_file "$temporary")" != "$hash" ]]; then
        rm -f "$temporary"
        echo "SHA-1 mismatch for browser Unicode font asset $logical_path" >&2
        exit 1
      fi
      mv "$temporary" "$source"
    fi
    target="$generated_assets/$logical_path"
    mkdir -p "$(dirname "$target")"
    cp "$source" "$target"
    printf 'assets/%s\n' "$logical_path" >>"$resource_list"
    copied_font_assets=$((copied_font_assets + 1))
  done <"$browser_font_manifest"

  # TeaVM resolves duplicate resources from the client overlay JAR before the
  # generated-resource directory. Replace the empty JAR stub so the embedded
  # runtime definition actually reaches the browser instead of merely appearing
  # in minecraft-resources.txt.
  client_overlay="$overlay_directory/client-named-$version-gaius.jar"
  for font_definition in \
    assets/minecraft/font/include/unifont.json \
    assets/minecraft/font/include/unifont_pua.json; do
    zip -q -d "$client_overlay" "$font_definition" >/dev/null 2>&1 || true
    (cd "$(dirname "$generated_assets")" && jar uf "$client_overlay" "$font_definition")
  done

  sounds_json_hash="$(jq -r '.objects["minecraft/sounds.json"].hash // ""' "$asset_index" | tr -d '\r\n')"
  if [[ -n "$sounds_json_hash" ]]; then
    sounds_json_source="$work/assets/objects/${sounds_json_hash:0:2}/$sounds_json_hash"
    if [[ -f "$sounds_json_source" &&
          ( "${GAIUS_ASSETS_OFFLINE:-false}" != "true" ||
            "$(gaius_sha1_file "$sounds_json_source")" == "$sounds_json_hash" ) ]]; then
      sounds_json_target="$generated_assets/minecraft/sounds.json"
      allowed_sounds_json="$build_root/browser-sound-names.json"
      mkdir -p "$(dirname "$sounds_json_target")"
      jq --slurpfile allowed "$allowed_sounds_json" '
        def sound_name:
          (if type == "string" then . else (.name // "") end)
          | sub("^minecraft:"; "")
          | sub("^sounds/"; "")
          | sub("\\.ogg$"; "");
        def playable_file:
          . as $entry
          | ((if ($entry | type) == "object" then ($entry.type // "file") else "file" end) == "file")
          and (($allowed[0] | index($entry | sound_name)) != null);
        with_entries(
          .value |= (
            if type == "object" and (.sounds | type) == "array" then
              .sounds = (.sounds | map(select(playable_file)))
            else
              .
            end
          )
          | select((.value.sounds | type) == "array" and (.value.sounds | length) > 0)
        )
      ' "$sounds_json_source" >"$sounds_json_target"
      printf 'assets/minecraft/sounds.json\n' >>"$resource_list"
      echo "Filtered browser sounds.json: $(jq 'length' "$sounds_json_target") events"
    else
      echo "WARNING: missing or invalid browser sounds.json object ($sounds_json_hash)" >&2
      if [[ "${GAIUS_ASSETS_OFFLINE:-false}" == "true" ]]; then
        exit 1
      fi
    fi
  else
    echo "WARNING: browser sounds.json not listed in asset index" >&2
    if [[ "${GAIUS_ASSETS_OFFLINE:-false}" == "true" ]]; then
      exit 1
    fi
  fi
else
  echo "WARNING: asset index not found at $asset_index; run port/scripts/fetch-version.sh to enable browser game sounds" >&2
  if [[ "${GAIUS_ASSETS_OFFLINE:-false}" == "true" ]]; then
    exit 1
  fi
fi
# Language files are content-addressed assets in the official index, while the Gaius overlay
# language files live in the source resource tree. Stage both explicitly so every profile's
# externally loaded pack and TeaVM resource supplier see the same locale set.
if [[ -n "$asset_index_id" && -f "$asset_index" ]]; then
  for locale in zh_cn es_es; do
    language_hash="$(jq -r --arg path "minecraft/lang/$locale.json" '.objects[$path].hash // ""' "$asset_index" | tr -d '\r\n')"
    language_source="$work/assets/objects/${language_hash:0:2}/$language_hash"
    if [[ -z "$language_hash" || ! -f "$language_source" ]] || [[ "$(gaius_sha1_file "$language_source")" != "$language_hash" ]]; then
      if [[ "${GAIUS_ASSETS_OFFLINE:-false}" == "true" ]]; then
        echo "Missing or invalid offline browser language asset: minecraft/lang/$locale.json" >&2
        exit 1
      fi
      mkdir -p "$(dirname "$language_source")"
      temporary="$language_source.part"
      curl -fsSL --http1.1 --retry 5 --retry-delay 1 -o "$temporary" \
        "https://resources.download.minecraft.net/${language_hash:0:2}/$language_hash"
      if [[ "$(gaius_sha1_file "$temporary")" != "$language_hash" ]]; then
        rm -f "$temporary"
        echo "SHA-1 mismatch for browser language asset minecraft/lang/$locale.json" >&2
        exit 1
      fi
      mv "$temporary" "$language_source"
    fi
    language_target="$generated_assets/minecraft/lang/$locale.json"
    mkdir -p "$(dirname "$language_target")"
    cp "$language_source" "$language_target"
    printf 'assets/minecraft/lang/%s.json\n' "$locale" >>"$resource_list"
  done
else
  echo "Missing asset index; cannot stage zh_cn/es_es language files" >&2
  exit 1
fi
for locale in en_us zh_cn es_es; do
  gaius_language="$root/port/src/main/resources/assets/gaius/lang/$locale.json"
  if [[ ! -f "$gaius_language" ]]; then
    echo "Missing Gaius language resource: $gaius_language" >&2
    exit 1
  fi
  target="$generated_assets/gaius/lang/$locale.json"
  mkdir -p "$(dirname "$target")"
  cp "$gaius_language" "$target"
  printf 'assets/gaius/lang/%s.json\n' "$locale" >>"$resource_list"
done
# VanillaPackResources.lowerBound() uses Java String.compareTo ordering.  For
# UTF-8 resource paths, the C/POSIX byte order is the same code-point order,
# unlike locale-aware sort order (which can make a prefix range incomplete).
LC_ALL=C sort -u -o "$resource_list" "$resource_list"
awk '
  index($0, "assets/") != 1 && index($0, "data/") != 1 && $0 != "pack.png" { print; next }
  $0 == "assets/minecraft/lang/deprecated.json" { print; next }
  $0 == "assets/minecraft/lang/en_us.json" { print; next }
  $0 == "assets/minecraft/lang/zh_cn.json" { print; next }
  $0 == "assets/minecraft/lang/es_es.json" { print; next }
  $0 == "assets/gaius/lang/en_us.json" { print; next }
  $0 == "assets/gaius/lang/zh_cn.json" { print; next }
  $0 == "assets/gaius/lang/es_es.json" { print; next }
  $0 == "assets/minecraft/font/include/unifont.json" { print; next }
  $0 == "assets/minecraft/font/unifont.zip" { print; next }
  $0 == "assets/minecraft/sounds/random/eat1.ogg" { print; next }
' "$resource_list" | LC_ALL=C sort -u >"$embedded_resource_list"
"$root/port/scripts/run-python.sh" \
  "$root/port/scripts/build-vanilla-assets-pack.py" \
  "$resource_list" \
  "$overlay_directory/client-named-$version-gaius.jar" \
  "$generated_resources" \
  "$vanilla_asset_pack" \
  "$asset_index"
echo "Generated browser resource list: $(wc -l <"$resource_list" | tr -d ' ') entries"
echo "Embedded TeaVM resource subset: $(wc -l <"$embedded_resource_list" | tr -d ' ') entries"
echo "Mapped browser sound assets: $copied_sound_assets"
echo "Mapped browser Unicode font assets: $copied_font_assets"
# WP1 rule g: the js POMs that generate-pom.sh writes for these overlays (the
# canonical client and Worker option sets, development and release) must match
# the checked-in baselines.
if ! gaius_bringup_active; then
  gaius_js_pom_gate "$root" "$version" "$overlay_directory"
fi
# The client role's TeaVM compiler options (maxTopLevelNames, syncClinits,
# syncMonitors, async barrier, runtime name pinning, telemetry tag) come from
# the role table in generate-pom.sh and end up in the POM <properties>.
pom="$(GAIUS_TEAVM_ROLE=client GAIUS_BUILD_ROOT="$build_root" GAIUS_OVERLAY_DIRECTORY="$overlay_directory" GAIUS_TARGET_DIRECTORY="$staged_target_directory" GAIUS_RESOURCE_DIRECTORY="$generated_resources" "$root/port/scripts/generate-pom.sh")"
log="$build_root/teavm-build.log"

if [[ "${GAIUS_PREPARE_ONLY:-false}" == "true" ]]; then
  echo "Prepared Minecraft $version client overlays, resources, asset pack, and POM: $pom"
  exit 0
fi

echo "Compiling the official Minecraft $version client with TeaVM"
echo "POM: $pom"
echo "Log: $log"

set +e
# MAVEN_OPTS only sizes the compiler JVM; TeaVM options live in the POM.
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
  "$build_root/teavm-gap.json" \
  "$build_root/teavm-gap.md"
analysis_status="$?"
set -e

if [[ "$analysis_status" -ne 0 ]]; then
  echo "TeaVM analysis did not complete; incomplete gap report was preserved" >&2
fi

if grep -Fq "Error in @JSBody" "$log"; then
  echo "TeaVM emitted invalid @JSBody JavaScript; refusing to publish the client output" >&2
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
  staged_target_js="$staged_target_directory/${GAIUS_TARGET_FILE:-classes.js}"
  final_target_js="$target_directory/${GAIUS_TARGET_FILE:-classes.js}"
  # All postprocessing and identity generation happens in the job directory.
  # The previous release is not touched until every validation step succeeds.
  # Start from the tracked version-neutral launcher, then postprocess it with
  # this profile's version and asset index.
  if [[ ! -f "$index_template" ]]; then
    echo "Missing index template: $index_template" >&2
    exit 1
  fi
  if head -n 1 "$index_template" | grep -Fqx \
      'version https://git-lfs.github.com/spec/v1'; then
    echo "Index template is an unresolved Git LFS pointer: $index_template" >&2
    exit 1
  fi
  cp "$index_template" "$staged_target_directory/index.html"
  # Keep index metadata stable for artifacts not produced by this job.  The
  # release wrapper reruns index postprocess after the Worker build, while a
  # standalone client build must retain the previous Worker tokens.
  for preserved_asset in \
    singleplayer-server.js \
    singleplayer-server-worker.js; do
    if [[ -f "$target_directory/$preserved_asset" ]]; then
      cp "$target_directory/$preserved_asset" "$staged_target_directory/$preserved_asset"
    fi
  done
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/postprocess-teavm-js.py" "$staged_target_js"
  # A client on the renderpearl render API (26.3+) compiles its shaders with
  # the WebAssembly shader toolchain (PLAN D5, contract C7).  Stage its runtime
  # files next to index.html, where postprocess-index-html.py injects the
  # loader, and publish them with the client.  The modules come from emsdk
  # (GAIUS_EMSDK) or a verified prebuilt directory
  # (GAIUS_SHADER_TOOLCHAIN_PREBUILT).  A release
  # (GAIUS_SHADER_TOOLCHAIN_STRICT=1) fails without them; a development build
  # warns, and its page refuses to start instead of running without shaders.
  # 26.2 has no renderpearl classes, so its page and dist stay unchanged.
  shader_toolchain_publish=()
  if gaius_client_uses_renderpearl "$work/client-named.jar"; then
    if bash "$root/port/scripts/build-wasm-shader-toolchain.sh" \
        --work "$build_root/shader-toolchain" \
        --dist "$staged_target_directory"; then
      for shader_toolchain_file in \
        gaius-shader-toolchain.js gaius-shader-toolchain.json \
        gaius-shaderc.js gaius-shaderc.wasm gaius-spvc.js gaius-spvc.wasm; do
        shader_toolchain_publish+=(
          "$staged_target_directory/$shader_toolchain_file"
          "$target_directory/$shader_toolchain_file")
      done
    elif [[ "${GAIUS_SHADER_TOOLCHAIN_STRICT:-}" == "1" ]]; then
      echo "The WebAssembly shader toolchain could not be built for Minecraft $version" >&2
      exit 1
    else
      echo "WARNING: no WebAssembly shader toolchain for Minecraft $version" \
        "(set GAIUS_EMSDK or GAIUS_SHADER_TOOLCHAIN_PREBUILT); its page will not start" >&2
    fi
  fi
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/postprocess-index-html.py" \
    "$staged_target_directory/index.html" \
    "$staged_target_js" \
    "$version" \
    "$asset_index_id"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/gaius_build_identity.py" write \
    --root "$root" \
    --role client \
    --artifact "$staged_target_js"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/gaius_build_identity.py" write \
    --root "$root" \
    --role vanilla-assets \
    --artifact "$vanilla_asset_pack"

  # Copy into destination-local temporary files and replace only after all
  # staged artifacts, postprocess output, and identity records exist.  This
  # is the publication commit; Maven never writes the final dist file.
  gaius_teavm_publish_bundle \
    "$staged_target_js" "$final_target_js" \
    "${staged_target_js}.build.json" "${final_target_js}.build.json" \
    "$vanilla_asset_pack" "$target_directory/vanilla-assets.pack.gz" \
    "${vanilla_asset_pack}.build.json" \
      "$target_directory/vanilla-assets.pack.gz.build.json" \
    "$staged_target_directory/index.html" "$target_directory/index.html" \
    "${shader_toolchain_publish[@]}"
  # v0.4 page runtime next to index.html: gaius-boot.js (the launcher's one boot tag), the
  # Service Worker, the kernel runtime and job codecs, the quality layer and, when built, the
  # wasm kernels. build-version-release.sh prepares the kernels for a release
  # (GAIUS_KERNELS_PREPARED=1); a standalone client build makes them here (cargo rebuilds only
  # what changed) unless GAIUS_SKIP_KERNELS=1. Without cargo or the wasm32 target the page
  # keeps every vanilla path.
  kernel_directory="$build_root/kernels"
  if [[ "${GAIUS_KERNELS_PREPARED:-}" != "1" && "${GAIUS_SKIP_KERNELS:-}" != "1" ]]; then
    if ! bash "$root/port/native/build-wasm-variants.sh" --out "$kernel_directory"; then
      if [[ "${GAIUS_KERNELS_STRICT:-}" == "1" ]]; then
        echo "The wasm kernels could not be built for Minecraft $version" >&2
        exit 1
      fi
      echo "WARNING: no wasm kernels for Minecraft $version; the page keeps the vanilla paths" >&2
      rm -rf "$kernel_directory"
    fi
  fi
  if [[ "${GAIUS_KERNELS_PREPARED:-}" != "1" ]]; then
    # Never leave a previous build's modules behind a kernels.json that no longer lists them.
    rm -rf "$target_directory/kernels"
  fi
  if [[ -z "${GAIUS_SHADERXLAT_WASM:-}" && "${GAIUS_SKIP_KERNELS:-}" != "1" ]]; then
    if translator_module="$(bash "$root/port/web/runtime/webgpu/build-translator.sh" | tail -n 1)" \
        && [[ -s "$translator_module" ]]; then
      export GAIUS_SHADERXLAT_WASM="$translator_module"
    elif [[ "${GAIUS_KERNELS_STRICT:-}" == "1" ]]; then
      echo "The WebGPU shader translator could not be built" >&2
      exit 1
    else
      echo "WARNING: no WebGPU shader translator; ?gpu=webgpu falls back to WebGL 2" >&2
    fi
  fi
  stage_arguments=("$target_directory" --profile "$GAIUS_MINECRAFT_VERSION")
  if [[ "${GAIUS_SKIP_KERNELS:-}" != "1" && -f "$kernel_directory/kernels.json" ]]; then
    stage_arguments+=(--kernels "$kernel_directory")
  fi
  "$root/port/scripts/run-python.sh" "$root/port/scripts/stage-web-runtime.py" "${stage_arguments[@]}"
  gaius_teavm_remove_stale_incomplete_reports \
    "$build_root/teavm-gap.json" "$build_root/teavm-gap.md"
  if [[ "${GAIUS_SKIP_COMPRESSION:-false}" != "true" ]]; then
    GAIUS_DIST_DIRECTORY="$target_directory" \
      "$root/port/scripts/compress-dist.sh" >/dev/null
  fi
fi

exit "$build_status"
