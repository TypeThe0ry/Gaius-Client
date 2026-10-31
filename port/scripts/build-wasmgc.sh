#!/usr/bin/env bash
set -euo pipefail

# WebAssembly GC build of one Minecraft profile (v0.4.0 design D1/D2, M0 spike S1).
#
#   build-wasmgc.sh [--role client|singleplayer-worker] [--release] [--skip-overlays] <profile>
#
# The same source tree as the JavaScript build, compiled with TeaVM 0.15
# WEBASSEMBLY_GC: overlays are built with GAIUS_TEAVM_TARGET=wasmgc (the
# target-wasmgc source sets and the wasmgc patch chain), the POM stages
# port/src/target-wasmgc/java and writes classes.wasm (client) or
# singleplayer-server.wasm (Worker).  The TeaVM WebAssembly GC runtime is then
# patched for Gaius (port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs) and the
# minimal launcher page is staged next to the module.
#
# Output roots are separate from the JavaScript build of the same profile:
#   GAIUS_BUILD_ROOT          default port/target/<profile>-wasmgc
#   GAIUS_OVERLAY_DIRECTORY   default port/work/overlays/<profile>-wasmgc
#   GAIUS_DIST_DIRECTORY      default port/web/dist/<profile>-wasmgc
# Browser resources (resource lists, staged sounds and fonts, the vanilla asset
# pack) do not depend on the backend; until build-teavm.sh shares its resource
# staging, they are taken from a JavaScript build of the same profile:
#   GAIUS_WASMGC_RESOURCE_ROOT  that build's GAIUS_BUILD_ROOT (required)
#   GAIUS_WASMGC_RESOURCE_DIST  that build's dist (vanilla-assets.pack.gz; optional)
# Compiler settings: GAIUS_TEA_OPTIMIZATION_LEVEL (default SIMPLE), MAVEN_OPTS
# (default -Xms2g -Xmx<GAIUS_TEAVM_MAX_HEAP>, G1, compact object headers when the JDK
# has them, and a GC log in the build root; GAIUS_TEAVM_MAX_HEAP defaults to 14g;
# GAIUS_TEAVM_JVM_OPTIONS is appended to the default, e.g. for a heap dump on
# OutOfMemoryError, and is ignored when MAVEN_OPTS is set).
# Compact object headers (-XX:+UseCompactObjectHeaders, JDK 25, JEP 519) take 4
# bytes off every Java object of the compiler JVM; TeaVM's program model and
# dependency graph are mostly small objects, so the live heap of a compile shrinks
# by about 9% (2026-10-09, an ADVANCED WebAssembly GC compile of 88k methods of the
# 26.3 client: 1518 MB -> 1387 MB at the dependency-analysis peak). The module and
# TeaVM's line tables are byte-identical with and without the flag (also with
# -XX:hashCode=3 and with ParallelGC): the WebAssembly GC output does not depend on
# identity hashes (TypeSet domain order, TeaVMCoreBrowserPatcher). The JavaScript
# build scripts do not set the flag: their stock TypeSet iterates in identity-hash
# order, and the flag changes the identity hashes and with them classes.js.
# The role options default to stock TeaVM async analysis for wasmgc
# (generate-pom.sh); GAIUS_TEAVM_ASYNC_DUMP writes the async census (S2).
#
# --release is the counterpart of build-teavm-release.sh (v0.4.0 WP8): ADVANCED
# optimization, removed assertions and the gaius-telemetry tag dropped, as in the
# JavaScript release, and an obfuscated module: TeaVM's WebAssembly GC
# obfuscation (GAIUS_MINIFYING=true) only leaves the name section out of an
# otherwise byte-identical module, so the release compiles with the section
# and port/tools/wasm/wasm-symbols.mjs moves it to
# <symbols>/<module>.symbols.wasm before anything else reads the module.
# GAIUS_DEBUG_INFO=true also keeps TeaVM's line tables (<module>.teadbg, moved
# to the same directory).  <symbols> is GAIUS_WASMGC_SYMBOL_DIRECTORY, default
# <role build root>/symbols; port/tools/wasm/wasm-symbolize.mjs turns stack
# traces of the shipped module back into Java frames with these files.
# Before the split, the release runs binaryen's wasm-opt on the named module
# (port/scripts/wasm-opt-release.sh: -Os -g, open world, the browser baseline's
# features; report in <role build root>/wasm-opt.json).  GAIUS_WASM_OPT names
# wasm-opt (binaryen 133; default: the one on PATH); without it the release
# warns and ships TeaVM's module, and GAIUS_WASM_OPT_STRICT=1 makes that an
# error.  TeaVM's line tables then describe the module before wasm-opt and stay
# in <symbols>/pre-opt/ with that module's sha256.
# Every build writes the build identity of its module, <module>.build.json
# (roles wasm-client and wasm-singleplayer-worker, gaius_build_identity.py).
#
# Gates (teavm-publication-gate.sh, port/scripts/quickcheck/wasmgc_rules.py):
# the static source rules run before anything is built, and the module must
# then compile in V8 with zero invalid functions and the log must report the
# JSPI mode ("jspi: async analysis skipped, 0 coroutine methods") and the
# JVM class initialization guard ("jspi: class initializers wait for their
# initializing thread (JVM semantics)"); a red rule fails the build.  The release also writes wasm-size.json (port/tools/wasm/
# wasm-size.mjs) next to the TeaVM log (GAIUS_WASMGC_SIZE_REPORT=true|false
# overrides; the brotli pass takes a few minutes).
#
# The script holds the writer lock of its overlay directory (port/scripts/overlay-lock.sh)
# from before the overlays are (re)built until the TeaVM compilation has closed the overlay
# jars, also with --skip-overlays, so no other build rewrites them meanwhile; builds of other
# overlay directories are not held up.  It refuses directories of a JavaScript build: an
# overlay directory that holds js overlays, and a build root or dist that is the JavaScript
# build it takes resources from.
root="$(cd "$(dirname "$0")/../.." && pwd)"
role=client
release=false
skip_overlays=false
profile_arg=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --role)
      role="$2"
      shift 2
      ;;
    --release)
      release=true
      shift
      ;;
    --skip-overlays)
      skip_overlays=true
      shift
      ;;
    *)
      profile_arg="$1"
      shift
      ;;
  esac
done
case "$role" in
  client|singleplayer-worker) ;;
  *)
    echo "Unknown role '$role' (expected client or singleplayer-worker)" >&2
    exit 2
    ;;
esac
if [[ -z "$profile_arg" ]]; then
  echo "usage: $0 [--role client|singleplayer-worker] [--release] [--skip-overlays] <profile>" >&2
  exit 2
fi
if [[ "$release" == true ]]; then
  export GAIUS_TEA_OPTIMIZATION_LEVEL="${GAIUS_TEA_OPTIMIZATION_LEVEL:-ADVANCED}"
  export GAIUS_MINIFYING="${GAIUS_MINIFYING:-false}"
  export GAIUS_ASSERTIONS_REMOVED="${GAIUS_ASSERTIONS_REMOVED:-true}"
  export GAIUS_SOURCE_MAPS="${GAIUS_SOURCE_MAPS:-false}"
  export GAIUS_DEBUG_INFO="${GAIUS_DEBUG_INFO:-true}"
  export GAIUS_WASM_DEBUG_INFO_LOCATION="${GAIUS_WASM_DEBUG_INFO_LOCATION:-EXTERNAL}"
  export GAIUS_TEAVM_STRIP_TELEMETRY="${GAIUS_TEAVM_STRIP_TELEMETRY:-true}"
  # GAIUS_MINIFYING stays false: the name section is split off after the build
  # (the same bytes TeaVM's obfuscation would write, plus a symbol file).  FULL is
  # refused: TeaVM 0.15 stops the 26.3 Worker with a NullPointerException in
  # WasmGCMethodGenerator.generateRegularMethodBody after 15 minutes (WP8).
  if [[ "$GAIUS_TEA_OPTIMIZATION_LEVEL" != ADVANCED ]] \
      || [[ "$GAIUS_MINIFYING" != false || "$GAIUS_ASSERTIONS_REMOVED" != true \
        || "$GAIUS_SOURCE_MAPS" != false || "$GAIUS_DEBUG_INFO" != true \
        || "$GAIUS_WASM_DEBUG_INFO_LOCATION" != EXTERNAL ]]; then
    echo "A wasmgc release needs ADVANCED optimization, removed assertions, no source maps," >&2
    echo "the name section (GAIUS_MINIFYING=false; it is split into the symbol file) and" >&2
    echo "TeaVM's line tables in an external file (GAIUS_DEBUG_INFO=true, EXTERNAL)" >&2
    exit 1
  fi
  case "$GAIUS_TEAVM_STRIP_TELEMETRY" in
    true|false) ;;
    *)
      echo "Invalid GAIUS_TEAVM_STRIP_TELEMETRY: $GAIUS_TEAVM_STRIP_TELEMETRY (expected true or false)" >&2
      exit 1
      ;;
  esac
fi
# 14g also for --release (WP8): the ADVANCED 26.3 client compiled in 1391 s at
# -Xmx14g (24 s of GC pauses, one full GC) and in 3518 s at -Xmx16g (80 s), both
# runs starting with 15 GB of free memory; both heaps fill up, and on a 32 GB
# machine that also runs VMware and WSL the larger heap only pages more.
teavm_max_heap="${GAIUS_TEAVM_MAX_HEAP:-14g}"
if [[ ! "$teavm_max_heap" =~ ^[1-9][0-9]*[gm]$ ]]; then
  echo "Invalid GAIUS_TEAVM_MAX_HEAP: $teavm_max_heap (expected e.g. 14g)" >&2
  exit 2
fi
case "$profile_arg" in
  versions/*.json) profile="$profile_arg" ;;
  *.json) profile="versions/$profile_arg" ;;
  *) profile="versions/$profile_arg.json" ;;
esac
export GAIUS_VERSION_PROFILE_PATH="$profile"
source "$root/port/scripts/version-profile.sh"
source "$root/port/scripts/teavm-publication-gate.sh"
source "$root/port/scripts/overlay-lock.sh"
gaius_load_version_profile "$root"
gaius_select_java_home
if command -v cygpath >/dev/null 2>&1; then
  PATH="$(cygpath -u "$GAIUS_JAVA_HOME")/bin:$PATH"
  export PATH
fi
version="$GAIUS_MINECRAFT_VERSION"
work="$root/port/work/$version"

export GAIUS_TEAVM_TARGET=wasmgc
export GAIUS_BUILD_ROOT="${GAIUS_BUILD_ROOT:-$root/port/target/$version-wasmgc}"
export GAIUS_OVERLAY_DIRECTORY="${GAIUS_OVERLAY_DIRECTORY:-$root/port/work/overlays/$version-wasmgc}"
export GAIUS_DIST_DIRECTORY="${GAIUS_DIST_DIRECTORY:-$root/port/web/dist/$version-wasmgc}"
build_root="$(gaius_build_root "$root")"
overlay_directory="$(gaius_overlay_directory "$root")"
dist="$(gaius_dist_directory "$root")"
maven_repository="$(gaius_maven_repository "$root")"
maven_repository_for_java="$(gaius_maven_repository_for_java "$root")"
resource_root="${GAIUS_WASMGC_RESOURCE_ROOT:-}"
if [[ -z "$resource_root" || ! -d "$resource_root/generated-resources" ]]; then
  echo "GAIUS_WASMGC_RESOURCE_ROOT must name the build root of a JavaScript build of $version" >&2
  echo "(it provides generated-resources and server-worker/generated-resources)" >&2
  exit 2
fi
same_directory() {
  [[ -n "$1" && -n "$2" && -d "$1" && -d "$2" ]] \
    && [[ "$(cd "$1" && pwd -P)" == "$(cd "$2" && pwd -P)" ]]
}
if same_directory "$build_root" "$resource_root"; then
  echo "GAIUS_BUILD_ROOT is the JavaScript build root GAIUS_WASMGC_RESOURCE_ROOT ($resource_root)" >&2
  exit 2
fi
if same_directory "$dist" "${GAIUS_WASMGC_RESOURCE_DIST:-}"; then
  echo "GAIUS_DIST_DIRECTORY is the JavaScript dist GAIUS_WASMGC_RESOURCE_DIST ($dist);" >&2
  echo "the wasm launcher page would replace its index.html" >&2
  exit 2
fi
# An overlay directory without a TEAVM_TARGET marker predates the marker and
# holds js overlays (generate-pom.sh reads it the same way); build-overlays.sh
# writes "<target>-incomplete" while it rebuilds one.
overlay_marker="$(tr -d '\r\n' 2>/dev/null <"$overlay_directory/TEAVM_TARGET" || true)"
if [[ "$overlay_marker" == js || "$overlay_marker" == js-incomplete \
    || ( -z "$overlay_marker" && -f "$overlay_directory/client-named-$version-gaius.jar" ) ]]; then
  echo "$overlay_directory holds JavaScript overlays; set GAIUS_OVERLAY_DIRECTORY to a wasmgc one" >&2
  exit 2
fi
mkdir -p "$build_root" "$dist"
timings="$build_root/wasmgc-timings.txt"
: >"$timings"
# Gates (c), (d) and (e) need only the sources: fail before overlays and TeaVM.
gaius_wasmgc_source_gate "$root" "$build_root/wasmgc-rules-source.json"
phase_started=0
phase_begin() {
  phase_started="$(date +%s)"
  echo "== $1 ($(date -Is))"
}
phase_end() {
  printf '%s %ss\n' "$1" "$(( $(date +%s) - phase_started ))" | tee -a "$timings"
}

overlay_lock=""
overlay_lock_owner=""
release_overlay_lock() {
  if [[ -n "${overlay_lock_owner:-}" ]]; then
    gaius_overlay_lock_release "$overlay_lock" "$overlay_lock_owner" || true
    overlay_lock_owner=""
  fi
}
trap release_overlay_lock EXIT
# TeaVM keeps the overlay jars open for the whole compilation: hold the writer lock of the
# overlay directory from here until the script exits (build-overlays.sh, run below with
# GAIUS_OVERLAY_LOCK_HELD=true, checks that this process holds it).
gaius_overlay_lock_acquire "$root" "$overlay_directory"
overlay_lock="$GAIUS_OVERLAY_LOCK_PATH"
overlay_lock_owner="$GAIUS_OVERLAY_LOCK_OWNER_TOKEN"

if [[ "$skip_overlays" != true ]]; then
  phase_begin overlays
  GAIUS_OVERLAY_LOCK_HELD=true "$root/port/scripts/build-overlays.sh" \
    >"$build_root/overlays.log" 2>&1 \
    || { tail -n 60 "$build_root/overlays.log" >&2; exit 1; }
  phase_end overlays
fi
if [[ "$(tr -d '\r\n' <"$overlay_directory/TEAVM_TARGET" 2>/dev/null)" != wasmgc ]]; then
  echo "$overlay_directory holds no wasmgc overlays" >&2
  exit 1
fi

asm_classpath="$maven_repository/org/ow2/asm/asm/9.8/asm-9.8.jar:$maven_repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar"
tool_classpath="$overlay_directory/tool-classes:$asm_classpath"
if command -v cygpath >/dev/null 2>&1; then
  tool_classpath="$(cygpath -mp "$tool_classpath")"
fi
client_overlay="$overlay_directory/client-named-$version-gaius.jar"

# The wasm reachability lint (warn mode in M1, never fails the build): with this
# build's overlay log and patched client jar it sees which patches the wasmgc
# chain dropped and what the jar really references.
native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}
reach_arguments=(--profile "$version" --client-jar "$version=$(native_path "$client_overlay")"
  --json "$(native_path "$build_root/wasm-reach.json")")
if [[ "$skip_overlays" != true ]]; then
  reach_arguments+=(--overlay-log "$version=$(native_path "$build_root/overlays.log")")
fi
"$root/port/scripts/run-python.sh" "$root/port/scripts/quickcheck/lint_wasm_reach.py" "${reach_arguments[@]}" \
  | tee "$build_root/wasm-reach.txt" \
  || echo "The wasm reachability lint did not run (warn mode, ignored)" >&2

if [[ "$role" == client ]]; then
  phase_begin resources
  # The same client-jar steps as build-teavm.sh: Gson TypeToken capture fixes and
  # the generated Unicode font definitions.
  gson_patches="$build_root/gson-type-token-client-patches"
  rm -rf "$gson_patches"
  mkdir -p "$gson_patches"
  java -classpath "$tool_classpath" dev.gaius.tools.GsonTypeTokenClientPatcher \
    "$client_overlay" "$gson_patches" "$work/libraries"
  # Both updates rewrite the overlay jar in place on every build (--skip-overlays too) and stamp
  # the entries with a fixed date, so the jar, and the overlay identity of the build's sidecars,
  # are the same after the second build as after the first.
  jar_date=1980-01-01T00:00:02Z
  jar --update --date="$jar_date" --file "$client_overlay" -C "$gson_patches" .
  rm -rf "$build_root/generated-resources"
  cp -R "$resource_root/generated-resources" "$build_root/generated-resources"
  for font_definition in \
    assets/minecraft/font/include/unifont.json \
    assets/minecraft/font/include/unifont_pua.json; do
    if [[ -f "$build_root/generated-resources/$font_definition" ]]; then
      zip -q -d "$client_overlay" "$font_definition" >/dev/null 2>&1 || true
      (cd "$build_root/generated-resources" \
        && jar --update --date="$jar_date" --file "$client_overlay" "$font_definition")
    fi
  done
  # Build the archive from this profile's generated resource list and client jar.  Copying the
  # JavaScript build's pack is unsafe: a shared resource root can contain a different profile's
  # pack (the archive index is profile-specific even when the resource root is reused).
  asset_index_id="$(jq -er '.assetIndex.id // .assets' "$work/version.json" 2>/dev/null | tr -d '\r\n' || true)"
  asset_index="${work}/assets/indexes/${asset_index_id}.json"
  pack_args=("$build_root/generated-resources/dev/gaius/browser/minecraft-resources.txt"
    "$client_overlay" "$build_root/generated-resources" "$dist/vanilla-assets.pack.gz")
  if [[ -n "$asset_index_id" && -f "$asset_index" ]]; then
    pack_args+=("$asset_index")
  fi
  "$root/port/scripts/run-python.sh" "$root/port/scripts/build-vanilla-assets-pack.py" "${pack_args[@]}"
  phase_end resources
  role_root="$build_root"
  target_file="classes.wasm"
  resource_directory="$build_root/generated-resources"
  export GAIUS_EXCLUDED_LIBRARY_PREFIXES="org/apache/logging/log4j/log4j-core/,org/apache/logging/log4j/log4j-slf4j2-impl/"
else
  role_root="$build_root/server-worker"
  rm -rf "$role_root/generated-resources"
  mkdir -p "$role_root"
  cp -R "$resource_root/server-worker/generated-resources" "$role_root/generated-resources"
  # MinecraftResourceSupplier embeds the bundled language files in both roles; the server
  # Worker resource root only carries its own list, so take them from the client root.
  for language_root in assets/minecraft/lang assets/gaius/lang; do
    if [[ -d "$resource_root/generated-resources/$language_root" ]]; then
      mkdir -p "$role_root/generated-resources/$language_root"
      cp -n "$resource_root/generated-resources/$language_root/"*.json         "$role_root/generated-resources/$language_root/" 2>/dev/null || true
    fi
  done
  target_file="singleplayer-server.wasm"
  resource_directory="$role_root/generated-resources"
  export GAIUS_MAIN_CLASS="dev.gaius.browser.BrowserIntegratedServerMain"
  export GAIUS_EXCLUDED_LIBRARY_PREFIXES="${GAIUS_SERVER_EXCLUDED_LIBRARY_PREFIXES:-com/microsoft/azure/msal4j/,com/azure/azure-json/}"
fi

phase_begin pom
mkdir -p "$role_root"
export GAIUS_TEAVM_ROLE="$role"
export GAIUS_POM="$role_root/generated-pom.xml"
export GAIUS_TARGET_DIRECTORY="$dist"
export GAIUS_TARGET_FILE="$target_file"
export GAIUS_MAVEN_DIRECTORY="$role_root/maven"
export GAIUS_RESOURCE_DIRECTORY="$resource_directory"
export GAIUS_MAVEN_REPOSITORY="$maven_repository"
export GAIUS_SOURCE_MAPS="${GAIUS_SOURCE_MAPS:-false}"
export GAIUS_DEBUG_INFO="${GAIUS_DEBUG_INFO:-false}"
pom="$("$root/port/scripts/generate-pom.sh")"
phase_end pom

phase_begin teavm
log="$role_root/teavm-build.log"
gc_log="$role_root/teavm-gc.log"
gc_log_option="-Xlog:gc:file=$gc_log:time,uptime"
if command -v cygpath >/dev/null 2>&1; then
  gc_log_option="-Xlog:gc:file=$(cygpath -m "$gc_log"):time,uptime"
fi
echo "POM: $pom"
echo "Log: $log"
compact_headers=""
if java -XX:+UseCompactObjectHeaders -version >/dev/null 2>&1; then
  compact_headers="-XX:+UseCompactObjectHeaders"
fi
teavm_jvm_options="-Xms2g -Xmx$teavm_max_heap -XX:+UseG1GC $compact_headers -XX:MaxGCPauseMillis=500 $gc_log_option${GAIUS_TEAVM_JVM_OPTIONS:+ $GAIUS_TEAVM_JVM_OPTIONS}"
set +e
if [[ -n "${MAVEN_OPTS:-}" ]]; then
  echo "TeaVM heap: -Xmx$teavm_max_heap (MAVEN_OPTS set: $MAVEN_OPTS)" | tee -a "$timings"
else
  echo "TeaVM heap: -Xmx$teavm_max_heap (JVM: $teavm_jvm_options)" | tee -a "$timings"
fi
MAVEN_OPTS="${MAVEN_OPTS:-$teavm_jvm_options}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  "-Dmaven.repo.local=$maven_repository_for_java" \
  --file "$pom" \
  package >"$log" 2>&1
build_status="$?"
set -e
phase_end teavm
grep -E '^\[ERROR\] [A-Z]' "$log" | sed -E 's/^\[ERROR\] //' | sort | uniq -c | sort -rn \
  >"$role_root/teavm-errors.txt" || true
echo "TeaVM exit status $build_status; $(grep -c '^\[ERROR\]' "$log" || true) error lines" | tee -a "$timings"
if [[ "$build_status" -ne 0 || ! -f "$dist/$target_file" ]]; then
  head -n 40 "$role_root/teavm-errors.txt" >&2 || true
  exit 1
fi

# TeaVM 0.15 writes data segment offsets (i32.const) as unsigned LEB128; offsets in
# [2^20, 2^21) then read as negative and V8 rejects the module at instantiation.
# Re-encode them before anything hashes or optimizes the module.
node "$root/port/tools/wasm/wasm-data-fix.mjs" "$dist/$target_file" | tee -a "$timings"
node "$root/port/tools/wasm/wasm-data-check.mjs" "$dist/$target_file"

# The binaryen step of the release (wasm-opt-release.sh): wasm-opt -Os in the open world with
# the browser baseline's features, run on the module as TeaVM wrote it, name section included
# (-g), so the symbol file split off below names the functions of the optimized module.
# GAIUS_WASM_OPT names binaryen's wasm-opt; without it (or with another binaryen release) the
# step is skipped with a warning, and GAIUS_WASM_OPT_STRICT=1 makes that an error.
wasm_opt_report="$role_root/wasm-opt.json"
rm -f "$wasm_opt_report"
wasm_opt_applied=false
teavm_module_sha256=""
if [[ "$release" == true ]]; then
  phase_begin wasm-opt
  teavm_module_sha256="$(sha256sum "$dist/$target_file" | cut -d' ' -f1)"
  optimized="$role_root/wasm-opt/$target_file"
  rm -f "$optimized"
  bash "$root/port/scripts/wasm-opt-release.sh" "$dist/$target_file" --out "$optimized" \
    --report "$wasm_opt_report" | tee -a "$timings"
  if [[ "$(jq -r '.applied' "$wasm_opt_report")" == true ]]; then
    mv -f "$optimized" "$dist/$target_file"
    wasm_opt_applied=true
  fi
  rm -rf "$role_root/wasm-opt"
  phase_end wasm-opt
fi

# TeaVM's one-time initialisers (the start function, initReflection, WasmGCResources.<clinit>)
# are huge straight-line functions that V8 tiers up to TurboFan after their only call: 2.7 GB
# of compiler memory for 18 s when the server Worker starts. wasm-outline-init.mjs moves their
# straight-line runs into small functions that stay in Liftoff (see the tool's header), on the
# final module so the symbol file below describes what ships. GAIUS_WASM_OUTLINE=off skips it.
# Like wasm-opt, it moves code: TeaVM's line tables then describe the module before it.
outline_moved_code=false
if [[ "${GAIUS_WASM_OUTLINE:-on}" != off ]]; then
  outline_input_sha256="$(sha256sum "$dist/$target_file" | cut -d' ' -f1)"
  node "$root/port/tools/wasm/wasm-outline-init.mjs" "$dist/$target_file" | tee -a "$timings"
  if [[ "$(sha256sum "$dist/$target_file" | cut -d' ' -f1)" != "$outline_input_sha256" ]]; then
    outline_moved_code=true
    [[ "$wasm_opt_applied" == true ]] || teavm_module_sha256="$outline_input_sha256"
  fi
fi

# Symbols never ship: the name section (release) and TeaVM's line tables go to
# the symbol directory; the symbol file and <module>.sha256 record the shipped
# module's sha256 (wasm-symbolize.mjs --module checks it).  TeaVM's line tables
# address the module TeaVM wrote: after wasm-opt they no longer describe the
# shipped module and are kept under pre-opt/ with the sha256 of the module they
# do describe (a GAIUS_WASM_OPT=off release build writes that module again).
symbol_directory="${GAIUS_WASMGC_SYMBOL_DIRECTORY:-$role_root/symbols}"
rm -f "$symbol_directory/$target_file.teadbg" "$symbol_directory/$target_file.sha256" \
  "$symbol_directory/pre-opt/$target_file.teadbg" "$symbol_directory/pre-opt/$target_file.sha256"
if [[ "$release" == true ]]; then
  mkdir -p "$symbol_directory"
  node "$root/port/tools/wasm/wasm-symbols.mjs" split "$dist/$target_file" \
    --out "$symbol_directory/$target_file.symbols.wasm" | tee -a "$timings"
fi
debug_file="$dist/$target_file.teadbg"
if [[ -f "$debug_file" ]]; then
  mkdir -p "$symbol_directory"
  if [[ "$wasm_opt_applied" == true || "$outline_moved_code" == true ]]; then
    mkdir -p "$symbol_directory/pre-opt"
    mv -f "$debug_file" "$symbol_directory/pre-opt/$target_file.teadbg"
    printf '%s\n' "$teavm_module_sha256" >"$symbol_directory/pre-opt/$target_file.sha256"
    echo "$target_file.teadbg -> $symbol_directory/pre-opt (TeaVM module $teavm_module_sha256)" \
      | tee -a "$timings"
  else
    mv -f "$debug_file" "$symbol_directory/$target_file.teadbg"
    echo "$target_file.teadbg -> $symbol_directory ($(wc -c <"$symbol_directory/$target_file.teadbg" | tr -d ' ') bytes)" \
      | tee -a "$timings"
  fi
  sha256sum "$dist/$target_file" | cut -d' ' -f1 >"$symbol_directory/$target_file.sha256"
elif [[ "$release" == true ]]; then
  echo "TeaVM wrote no $target_file.teadbg although GAIUS_DEBUG_INFO=true" >&2
  exit 1
fi

# Gates (a) and (b): zero invalid functions in V8, and the JSPI mode in the log.
phase_begin gates
gaius_wasmgc_artifact_gate "$root" "$dist/$target_file" "$log" "$role_root/wasmgc-rules-artifact.json"
phase_end gates

phase_begin runtime
# TeaVM ships the WebAssembly GC loader as a resource of teavm-core; it is not
# written by the compiler.  Gaius patches it once per build (D5, D13).
teavm_version="$(jq -er '.teaVMVersion' "$root/port/config.json")"
runtime_source="$role_root/wasm-gc-runtime.js"
unzip -p "$overlay_directory/teavm-core-$teavm_version-gaius.jar" \
  org/teavm/backend/wasm/wasm-gc-runtime.js >"$runtime_source"
node "$root/port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs" \
  "$runtime_source" "$dist/gaius-wasm-gc-runtime.js"
# The SPIR-V -> WGSL translator of ?gpu=webgpu (runtime/webgpu/gaius-shaderxlat.wasm, which
# stage-web-runtime.py stages from GAIUS_SHADERXLAT_WASM): built here unless one is given. Without
# cargo and the wasm32 target the dist has none and ?gpu=webgpu falls back to WebGL 2
# ("translator unavailable"); GAIUS_KERNELS_STRICT=1 makes that an error.
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
# A WebGPU render seam with the blaze3d GLSL front-end (renderSeam set, shaderFront blaze3d*: 26.2
# and 1.21.11) compiles every pipeline with shaderc in the page. The browser shader toolchain goes
# next to index.html, where make-wasm-page.py names it for gaius-boot.js, which loads it for
# ?gpu=webgpu only (the WebGL 2 path never needs it). Without it ?gpu=webgpu falls back to WebGL 2;
# GAIUS_SHADER_TOOLCHAIN_STRICT=1 makes that an error.
render_seam="$(jq -r '.renderSeam // "none"' "$GAIUS_VERSION_PROFILE" | tr -d '\r')"
shader_front="$(jq -r '.shaderFront // ""' "$GAIUS_VERSION_PROFILE" | tr -d '\r')"
if [[ "$role" == client && "$render_seam" != none && "$shader_front" == blaze3d* ]]; then
  if bash "$root/port/scripts/build-wasm-shader-toolchain.sh" --work "$role_root/shader-toolchain"       --dist "$dist" >"$role_root/shader-toolchain.log" 2>&1; then
    echo "shader toolchain for the WebGPU front-end ($shader_front) staged in $dist" | tee -a "$timings"
  elif [[ "${GAIUS_SHADER_TOOLCHAIN_STRICT:-}" == "1" ]]; then
    tail -n 20 "$role_root/shader-toolchain.log" >&2
    echo "The WebAssembly shader toolchain could not be built for Minecraft $version" >&2
    exit 1
  else
    tail -n 5 "$role_root/shader-toolchain.log" >&2
    echo "WARNING: no WebAssembly shader toolchain for Minecraft $version"       "(set GAIUS_EMSDK or GAIUS_SHADER_TOOLCHAIN_PREBUILT); ?gpu=webgpu falls back to WebGL 2" >&2
  fi
fi
# The page runtime (gaius-boot.js, kernel and quality modules) and the WebAssembly GC loader
# scripts of both roles always come from this tree, also when the dist was staged before from
# an older one.
# The wasm kernels (worldgen, noise, light, mesh) and kernels.json go next to the client the
# same way build-teavm.sh stages them for the JavaScript build: the site builder lists them in
# the page manifest from the client dist, and without kernels.json every page keeps the vanilla
# paths. GAIUS_KERNELS_PREPARED=1 takes prepared kernels from GAIUS_KERNEL_DIRECTORY,
# GAIUS_SKIP_KERNELS=1 builds none, GAIUS_KERNELS_STRICT=1 makes a failed kernel build an error.
stage_arguments=("$dist" --wasm --profile "$version")
if [[ "$role" == client && "${GAIUS_SKIP_KERNELS:-}" != "1" ]]; then
  kernel_directory="${GAIUS_KERNEL_DIRECTORY:-$role_root/kernels}"
  if [[ "${GAIUS_KERNELS_PREPARED:-}" != "1" ]]; then
    if ! bash "$root/port/native/build-wasm-variants.sh" --out "$kernel_directory"; then
      if [[ "${GAIUS_KERNELS_STRICT:-}" == "1" ]]; then
        echo "The wasm kernels could not be built for Minecraft $version" >&2
        exit 1
      fi
      echo "WARNING: no wasm kernels for Minecraft $version; the page keeps the vanilla paths" >&2
      rm -rf "$kernel_directory"
    fi
    rm -rf "$dist/kernels"
  fi
  if [[ -f "$kernel_directory/kernels.json" ]]; then
    stage_arguments+=(--kernels "$kernel_directory")
  elif [[ "${GAIUS_KERNELS_STRICT:-}" == "1" ]]; then
    echo "No kernels.json in $kernel_directory for Minecraft $version" >&2
    exit 1
  fi
  # The PNG decoder (crates/gaius-png-wasm) that STBImage hands PNGs to; it declines what it does
  # not decode like the Java decoder (port/native/golden/png/check.py), which then decodes it. The
  # site builder switches it on when the dist has gaius-png.wasm; without it the Java decoder runs.
  png_target="${CARGO_TARGET_DIR:-$root/port/native/target}/wasm-png"
  if CARGO_TARGET_DIR="$png_target" cargo build --manifest-path "$root/port/native/Cargo.toml" \
      --release --offline --locked --target wasm32-unknown-unknown -p gaius-png-wasm --lib \
      && [[ -s "$png_target/wasm32-unknown-unknown/release/gaius_png_wasm.wasm" ]]; then
    stage_arguments+=(--png "$png_target/wasm32-unknown-unknown/release/gaius_png_wasm.wasm")
  elif [[ "${GAIUS_KERNELS_STRICT:-}" == "1" ]]; then
    echo "The PNG decoder module could not be built for Minecraft $version" >&2
    exit 1
  else
    echo "WARNING: no PNG decoder module for Minecraft $version; STBImage keeps the Java decoder" >&2
    rm -f "$dist/gaius-png.wasm"
  fi
fi
"$root/port/scripts/run-python.sh" "$root/port/scripts/stage-web-runtime.py" "${stage_arguments[@]}"
node "$root/port/scripts/build-relay-protocol.mjs" --profile "$version" --out "$dist"
# The page names the wasm builds present in the dist (window.__gaiusRuntimes), so it is
# staged again after the singleplayer-worker role, which writes singleplayer-server.wasm
# next to the client's classes.wasm (make-wasm-page.py is idempotent).  It copies the
# JavaScript dist the resources come from and then stages this tree's page runtime and Worker
# bootstrap over the copied ones.
if [[ -f "$dist/classes.wasm" && -n "${GAIUS_WASMGC_RESOURCE_DIST:-}" \
    && -f "$GAIUS_WASMGC_RESOURCE_DIST/index.html" ]]; then
  "$root/port/scripts/run-python.sh" "$root/port/web/runtime/wasmgc/make-wasm-page.py" \
    "$GAIUS_WASMGC_RESOURCE_DIST" "$dist" --file-payloads
fi
phase_end runtime

raw_bytes="$(wc -c <"$dist/$target_file" | tr -d ' ')"
gzip_bytes="$(gzip -9 -c "$dist/$target_file" | wc -c | tr -d ' ')"
echo "$target_file raw=$raw_bytes gzip=$gzip_bytes" | tee -a "$timings"
if [[ "${GAIUS_WASMGC_SIZE_REPORT:-$release}" == true ]]; then
  phase_begin size
  # Compared with the brotli file of the JavaScript build the resources come from
  # (classes.js.br, singleplayer-server.js.br), the WP8 size target.
  size_compare=()
  js_reference="${GAIUS_WASMGC_RESOURCE_DIST:-}/${target_file%.wasm}.js.br"
  if [[ -n "${GAIUS_WASMGC_RESOURCE_DIST:-}" && -f "$js_reference" ]]; then
    size_compare=(--compare "$js_reference")
  fi
  node "$root/port/tools/wasm/wasm-size.mjs" --json "$role_root/wasm-size.json" \
    ${size_compare[@]+"${size_compare[@]}"} "$dist/$target_file" | tee -a "$timings"
  phase_end size
fi

# Build identity: <module>.build.json next to the module (role wasm-client or
# wasm-singleplayer-worker) carries the source, protocol and overlay identity of the
# JavaScript sidecars plus the WebAssembly GC build: TeaVM settings, the binaryen step and
# the symbol file, and the inputs only this build reads (gaius_build_identity.py).
case "$role" in
  client) identity_role=wasm-client ;;
  *) identity_role=wasm-singleplayer-worker ;;
esac
wasm_build="$role_root/wasm-build.json"
symbols_file="$symbol_directory/$target_file.symbols.wasm"
symbols_json=null
if [[ "$release" == true && -s "$symbols_file" ]]; then
  symbols_json="$(jq -cn --arg name "$target_file.symbols.wasm" \
    --arg sha256 "$(sha256sum "$symbols_file" | cut -d' ' -f1)" \
    --argjson bytes "$(wc -c <"$symbols_file" | tr -d ' ')" '{name: $name, sha256: $sha256, bytes: $bytes}')"
fi
wasm_opt_json=null
[[ -s "$wasm_opt_report" ]] && wasm_opt_json="$(jq -c 'del(.seconds)' "$wasm_opt_report")"
source "$root/port/scripts/teavm-role-option.sh"
if [[ "$role" == client ]]; then telemetry_role=CLIENT; else telemetry_role=WORKER; fi
telemetry_stripped="$(gaius_teavm_role_option "$telemetry_role" STRIP_TELEMETRY false)"
jq -n \
  --arg role "$role" \
  --argjson release "$release" \
  --arg optimization "${GAIUS_TEA_OPTIMIZATION_LEVEL:-SIMPLE}" \
  --arg assertionsRemoved "${GAIUS_ASSERTIONS_REMOVED:-false}" \
  --arg telemetryStripped "$telemetry_stripped" \
  --argjson wasmOpt "$wasm_opt_json" \
  --argjson symbols "$symbols_json" \
  '{target: "wasmgc", role: $role, release: $release, optimization: $optimization,
    assertionsRemoved: ($assertionsRemoved == "true"), telemetryStripped: ($telemetryStripped == "true"),
    wasmOpt: $wasmOpt, symbols: $symbols}' >"$wasm_build"
"$root/port/scripts/run-python.sh" "$root/port/scripts/gaius_build_identity.py" write \
  --root "$root" \
  --role "$identity_role" \
  --artifact "$dist/$target_file" \
  --wasm-build "$wasm_build"
# The client role builds its own profile-specific vanilla pack above; record its identity next to
# it like the JavaScript build does, so staging can verify the pack it publishes (a release
# train hands this build a resource dist without index.html, so make-wasm-page.py does not run).
if [[ "$role" == client && -f "$dist/vanilla-assets.pack.gz" ]]; then
  "$root/port/scripts/run-python.sh" "$root/port/scripts/gaius_build_identity.py" write \
    --root "$root" \
    --role vanilla-assets \
    --artifact "$dist/vanilla-assets.pack.gz"
fi
echo "BUILD_EXIT=0"
