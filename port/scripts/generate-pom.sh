#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"

maven_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s\n' "$1"
  fi
}

config="$root/port/config.json"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
# The storage allow-lists in the sources (page, Worker and file system layers) are generated
# from port/versions/*.json by port/tools/profilegen; a profile added or changed without
# regenerating them would build a client that refuses its own storage.  The check's report
# goes to stderr: callers take this script's stdout as the path of the POM it wrote.
node "$root/port/tools/profilegen/profilegen.mjs" --check >&2 \
  || { echo "Run node port/tools/profilegen/profilegen.mjs --write and commit the result" >&2; exit 1; }
gaius_select_java_home
version="$GAIUS_MINECRAFT_VERSION"
teavm_version="$(jq -er '.teaVMVersion' "$config")"
work="$root/port/work/$version"
metadata="$work/version.json"
build_root="$(gaius_build_root "$root")"
overlay_directory="$(gaius_overlay_directory "$root")"
client="$overlay_directory/client-named-$version-gaius.jar"
output="$(gaius_resolve_path "$root" "${GAIUS_POM:-$build_root/generated-pom.xml}")"

# TeaVM role configuration.  A role is one compiled artifact: the browser
# client (classes.js) or the singleplayer server Worker
# (singleplayer-server.js).  GAIUS_TEAVM_ROLE selects it; without it the role
# follows GAIUS_MAIN_CLASS (the Worker main selects singleplayer-worker,
# anything else, including smoke mains, the client).  The role's compiler
# options are written below into the TeaVM plugin <properties>, where the
# patched TeaVM core (TeaVMCoreBrowserPatcher, GaiusTeaVMOptions) reads them,
# and where the release compiler profile attests them with the POM hash.  They
# are no longer JVM system properties in MAVEN_OPTS.
#
# Each option is overridable per role and for every role, in this order:
#   GAIUS_TEAVM_<CLIENT|WORKER>_<OPTION>, GAIUS_TEAVM_<OPTION>, role default.
# Options:
#   MAX_TOP_LEVEL_NAMES  TeaVM maxTopLevelNames.  Names past this budget become
#                        properties of one scope object ("A.x"), which V8
#                        keeps in dictionary mode, so every call of such a
#                        method or read of such a static field is a hash
#                        lookup (8.7 ns against 0.24 ns for a let binding in
#                        the v0.4.0 micro-benchmark).  Names are allocated by
#                        textual frequency, so the budget covers the most used
#                        names first.  80000 is TeaVM's own default; the
#                        client has about 290k names and the Worker about
#                        150k.  A larger budget means more top-level lets in
#                        the module function: V8 handled 300k in a synthetic
#                        test, JavaScriptCore and SpiderMonkey limits were not
#                        measured, which is why the default stays at TeaVM's
#                        value instead of covering every name.  The old 10000
#                        had no recorded reason.
#   SYNC_CLINITS         class-initialization edges do not make callers async.
#                        Cooperative yields are skipped inside a class
#                        initializer, so the client does not repaint while
#                        Blocks and Items initialize (startup progress events
#                        still record).  It can be turned off for one role
#                        without touching SYNC_MONITORS or ASYNC_BARRIER.
#   SYNC_MONITORS        java.lang.Object monitor primitives are compiled
#                        synchronously; a monitor held across a suspension
#                        that another green thread waits for fails fast.  A
#                        contended synchronized method compiled synchronously
#                        throws "Can't enter monitor from another thread
#                        synchronously" from monitorEnterSync instead.
#   ASYNC_BARRIER        comma-separated barrier groups (object, throwable,
#                        map, collection, iterator, stringbuilder) or none;
#                        see GaiusTeaVMOptions for the member methods.
#   PIN_RUNTIME_NAMES    TeaVM runtime functions ($rt_*, Long_*) keep
#                        top-level bindings past MAX_TOP_LEVEL_NAMES.
#   STRIP_TELEMETRY      true drops the gaius-telemetry platform tag, so
#                        @PlatformMarker("gaius-telemetry") checks compile to
#                        false (build-teavm-release.sh sets it).
# TeaVM backend (D2 of the v0.4.0 design): js (default, the existing build) or
# wasmgc (TeaVM WEBASSEMBLY_GC).  wasmgc stages port/src/target-wasmgc/java over
# the shared and profile sources, writes classes.wasm, needs overlays built for
# the same target (build-overlays.sh with GAIUS_TEAVM_TARGET=wasmgc) and defaults
# to stock TeaVM async analysis: the role options below cut async propagation,
# which is only guarded by the JavaScript runtime (thread.js), so a WebAssembly
# GC build turns them on only on explicit request.  A js POM is unchanged.
teavm_target="${GAIUS_TEAVM_TARGET:-js}"
case "$teavm_target" in
  js|wasmgc) ;;
  *)
    echo "Invalid GAIUS_TEAVM_TARGET: $teavm_target (expected js or wasmgc)" >&2
    exit 1
    ;;
esac

teavm_role="${GAIUS_TEAVM_ROLE:-}"
if [[ -z "$teavm_role" ]]; then
  if [[ "${GAIUS_MAIN_CLASS:-}" == "dev.gaius.browser.BrowserIntegratedServerMain" ]]; then
    teavm_role="singleplayer-worker"
  else
    teavm_role="client"
  fi
fi
teavm_all_barrier_groups="object,throwable,map,collection,iterator,stringbuilder"
case "$teavm_role" in
  client)
    teavm_role_key="CLIENT"
    default_main_class="net.minecraft.client.main.Main"
    default_max_top_level_names=80000
    default_sync_clinits=true
    default_sync_monitors=true
    default_async_barrier="$teavm_all_barrier_groups"
    default_pin_runtime_names=true
    ;;
  singleplayer-worker)
    teavm_role_key="WORKER"
    default_main_class="dev.gaius.browser.BrowserIntegratedServerMain"
    default_max_top_level_names=80000
    default_sync_clinits=true
    default_sync_monitors=true
    default_async_barrier="object,throwable,map,collection,stringbuilder"
    default_pin_runtime_names=true
    ;;
  *)
    echo "Invalid GAIUS_TEAVM_ROLE: $teavm_role (expected client or singleplayer-worker)" >&2
    exit 1
    ;;
esac
if [[ "$teavm_target" == wasmgc ]]; then
  default_sync_clinits=false
  default_sync_monitors=false
  default_async_barrier=none
fi
main_class="${GAIUS_MAIN_CLASS:-$default_main_class}"
if [[ "$teavm_role" == "client" \
      && "$main_class" == "dev.gaius.browser.BrowserIntegratedServerMain" ]] \
    || [[ "$teavm_role" == "singleplayer-worker" \
      && "$main_class" != "dev.gaius.browser.BrowserIntegratedServerMain" ]]; then
  echo "GAIUS_TEAVM_ROLE=$teavm_role does not match main class $main_class" >&2
  exit 1
fi

# teavm_role_option NAME DEFAULT: prints the role override, the shared
# override or the default (see the table above).
source "$root/port/scripts/teavm-role-option.sh"
teavm_role_option() { gaius_teavm_role_option "$teavm_role_key" "$1" "$2"; }
max_top_level_names="$(teavm_role_option MAX_TOP_LEVEL_NAMES "$default_max_top_level_names")"
teavm_sync_clinits="$(teavm_role_option SYNC_CLINITS "$default_sync_clinits")"
teavm_sync_monitors="$(teavm_role_option SYNC_MONITORS "$default_sync_monitors")"
teavm_async_barrier="$(teavm_role_option ASYNC_BARRIER "$default_async_barrier")"
teavm_pin_runtime_names="$(teavm_role_option PIN_RUNTIME_NAMES "$default_pin_runtime_names")"
teavm_strip_telemetry="$(teavm_role_option STRIP_TELEMETRY false)"

if [[ ! "$max_top_level_names" =~ ^[1-9][0-9]*$ ]]; then
  echo "Invalid TeaVM MAX_TOP_LEVEL_NAMES for $teavm_role: $max_top_level_names" >&2
  exit 1
fi
for teavm_boolean in teavm_sync_clinits teavm_sync_monitors teavm_pin_runtime_names teavm_strip_telemetry; do
  case "${!teavm_boolean}" in
    true|false) ;;
    *)
      echo "Invalid boolean value for $teavm_boolean ($teavm_role): ${!teavm_boolean} (expected true or false)" >&2
      exit 1
      ;;
  esac
done
if [[ "$teavm_async_barrier" != "none" ]]; then
  IFS=',' read -r -a teavm_barrier_groups <<<"$teavm_async_barrier"
  for teavm_barrier_group in "${teavm_barrier_groups[@]}"; do
    case ",$teavm_all_barrier_groups," in
      *",$teavm_barrier_group,"*) ;;
      *)
        echo "Invalid TeaVM ASYNC_BARRIER group for $teavm_role: '$teavm_barrier_group' (expected none or a list of $teavm_all_barrier_groups)" >&2
        exit 1
        ;;
    esac
  done
fi
if [[ "$teavm_strip_telemetry" == "true" ]]; then
  teavm_telemetry=false
else
  teavm_telemetry=true
fi

if [[ -n "${GAIUS_TARGET_DIRECTORY:-}" ]]; then
  target_directory="$(gaius_resolve_path "$root" "$GAIUS_TARGET_DIRECTORY")"
else
  target_directory="$(gaius_dist_directory "$root")"
fi
if [[ "$teavm_target" == wasmgc ]]; then
  target_file="${GAIUS_TARGET_FILE:-classes.wasm}"
else
  target_file="${GAIUS_TARGET_FILE:-classes.js}"
fi
maven_directory="$(gaius_resolve_path "$root" "${GAIUS_MAVEN_DIRECTORY:-$build_root/maven}")"
resource_directory="$(gaius_resolve_path "$root" "${GAIUS_RESOURCE_DIRECTORY:-$build_root/generated-resources}")"
patched_classlib="$overlay_directory/teavm-classlib-$teavm_version-gaius.jar"
optimization_level="${GAIUS_TEA_OPTIMIZATION_LEVEL:-SIMPLE}"
source_maps_generated="${GAIUS_SOURCE_MAPS:-true}"
debug_information_generated="${GAIUS_DEBUG_INFO:-true}"
minifying="${GAIUS_MINIFYING:-false}"
short_file_names="${GAIUS_SHORT_FILE_NAMES:-false}"
assertions_removed="${GAIUS_ASSERTIONS_REMOVED:-false}"
excluded_library_prefixes="${GAIUS_EXCLUDED_LIBRARY_PREFIXES:-}"

# PlatformSmoke contains optional render-backend probes whose direct calls
# track the current named (26.2) API.  The 1.21.11 mapped profile does not
# need this smoke main class for the normal Minecraft client entrypoint, and
# compiling it would incorrectly make the legacy POM depend on 26.2-only
# method signatures. Keep the source available for the named profile while
# excluding it from the legacy client compilation set.
compiler_source_excludes=""
compile_excludes=$(node "$root/port/tools/sourcesets/sourcesets.mjs" compiler-excludes \
  --profile "$version" --target "$teavm_target" --root "$root")
if [[ -n "$compile_excludes" ]]; then
  compiler_source_excludes="            <excludes>"
  while IFS= read -r source; do
    compiler_source_excludes+=$'\n'"              <exclude>$source</exclude>"
  done <<<"$compile_excludes"
  compiler_source_excludes+=$'\n'"            </excludes>"
fi

# Profile source sets: port/src/main/java is shared by every profile, and the
# source sets of port/mc/shared that the profile's fields select (render seam
# generation, patch set; port/mc/shared/sourcesets.json) are laid over it: each
# set's src/excludes.txt drops shared relative paths, its src/java files replace
# the files with the same relative path (and may add new ones), and its
# relocations.tsv moves vanilla packages in the code of the files it did not
# provide; port/src/target-<target>/java comes last.  No profile id selects a
# source directory, so a new profile with known fields needs no source change.
# port/tools/sourcesets/sourcesets.mjs stages the merged tree under
# $build_root/sources, which becomes the only Maven source directory.
#
# The staged tree is replaced only when its content changes, and a replaced
# tree is copied without preserving modification times.  A staged path can
# switch between a version file and the shared file (for example when a
# version override is deleted), and the file it switches to is usually older
# than the classes compiled from the previous tree; with its original time
# Maven's stale-source check would keep those classes.  Every file of a
# replaced tree is newer than any class compiled before, so Maven recompiles
# (it rebuilds the whole module on any change anyway), while an unchanged tree
# keeps its times and an unchanged rebuild stays up to date.
staged_source_directory="$build_root/sources"

stage_profile_sources() {
  local staging="$staged_source_directory.staging.$$"
  rm -rf "$staging"
  node "$root/port/tools/sourcesets/sourcesets.mjs" stage --kind src \
    --profile "$(maven_path "$GAIUS_VERSION_PROFILE")" --target "$teavm_target" \
    --out "$(maven_path "$staging")" >&2 \
    || exit 1
  if [[ -d "$staged_source_directory" ]] \
      && diff -rq "$staged_source_directory" "$staging" >/dev/null 2>&1; then
    rm -rf "$staging"
  else
    rm -rf "$staged_source_directory"
    mv "$staging" "$staged_source_directory"
  fi
}

maven_patched_classlib="$(maven_path "$patched_classlib")"
maven_client="$(maven_path "$client")"
maven_target_directory="$(maven_path "$target_directory")"
maven_maven_directory="$(maven_path "$maven_directory")"
maven_resource_directory="$(maven_path "$resource_directory")"
maven_source_directory="$(maven_path "$staged_source_directory")"
maven_source_resources="$(maven_path "$root/port/src/main/resources")"
maven_teavm_core_patch="$(maven_path "$overlay_directory/teavm-core-$teavm_version-gaius.jar")"
if [[ -n "$excluded_library_prefixes" ]]; then
  IFS=',' read -r -a excluded_library_prefix_list <<<"$excluded_library_prefixes"
else
  # macOS Bash 3 treats expansion of an empty array as an unbound variable under set -u.
  excluded_library_prefix_list=("")
fi

case "$optimization_level" in
  SIMPLE|ADVANCED|FULL) ;;
  *)
    echo "Invalid GAIUS_TEA_OPTIMIZATION_LEVEL: $optimization_level (expected SIMPLE, ADVANCED, or FULL)" >&2
    exit 1
    ;;
esac

for boolean_name in source_maps_generated debug_information_generated minifying short_file_names assertions_removed; do
  boolean_value="${!boolean_name}"
  case "$boolean_value" in
    true|false) ;;
    *)
      echo "Invalid boolean value for $boolean_name: $boolean_value (expected true or false)" >&2
      exit 1
      ;;
  esac
done

# WebAssembly GC symbols (v0.4.0 WP8).  For the wasmgc target GAIUS_MINIFYING
# is TeaVM's obfuscation switch, which only leaves out the wasm name section
# (77.6 MB of the 154 MB SIMPLE client); build-wasmgc.sh --release keeps it and
# splits it into a symbol file instead.  GAIUS_DEBUG_INFO=true writes TeaVM's
# line and method tables, the input of its WebAssembly GC stack deobfuscator,
# either into a separate <target file>.teadbg next to the module (EXTERNAL, the
# default) or into custom sections of the module (EMBEDDED); TeaVM 0.15 fills
# them for SIMPLE builds and for very few functions of an ADVANCED one.  An
# obfuscated module without debug information could not be symbolized at all,
# so that combination is refused unless GAIUS_WASM_ALLOW_NO_SYMBOLS=true.  None
# of this applies to, or changes, a js POM.
wasm_debug_info_element=""
if [[ "$teavm_target" == wasmgc ]]; then
  wasm_debug_info_location="${GAIUS_WASM_DEBUG_INFO_LOCATION:-EXTERNAL}"
  case "$wasm_debug_info_location" in
    EXTERNAL|EMBEDDED) ;;
    *)
      echo "Invalid GAIUS_WASM_DEBUG_INFO_LOCATION: $wasm_debug_info_location (expected EXTERNAL or EMBEDDED)" >&2
      exit 1
      ;;
  esac
  if [[ "$minifying" == true && "$debug_information_generated" != true \
      && "${GAIUS_WASM_ALLOW_NO_SYMBOLS:-false}" != true ]]; then
    echo "An obfuscated wasmgc build (GAIUS_MINIFYING=true) needs GAIUS_DEBUG_INFO=true for its deobfuscation file" >&2
    echo "(set GAIUS_WASM_ALLOW_NO_SYMBOLS=true to build a module that cannot be symbolized)" >&2
    exit 1
  fi
  wasm_debug_info_element="              <wasmDebugInfoLocation>$wasm_debug_info_location</wasmDebugInfoLocation>
"
fi

if [[ ! -f "$metadata" || ! -f "$client" || ! -f "$patched_classlib" ]]; then
  echo "Run fetch-version.sh and remap-client.sh first" >&2
  echo "Then run build-overlays.sh" >&2
  exit 1
fi
# Overlays without a TEAVM_TARGET marker predate the marker and are js overlays.
overlay_target="js"
if [[ -f "$overlay_directory/TEAVM_TARGET" ]]; then
  overlay_target="$(tr -d '\r\n' <"$overlay_directory/TEAVM_TARGET")"
fi
if [[ "$overlay_target" != "$teavm_target" ]]; then
  echo "Overlays in $overlay_directory were built for TeaVM target $overlay_target, not $teavm_target" >&2
  echo "Rebuild them with GAIUS_TEAVM_TARGET=$teavm_target build-overlays.sh" >&2
  exit 1
fi

# Optional async census (TeaVMCoreBrowserPatcher, M0 spike S2): GAIUS_TEAVM_ASYNC_DUMP
# names the census file; GAIUS_TEAVM_ASYNC_DUMP_STOP=true ends the compilation after it.
async_dump_properties=""
if [[ -n "${GAIUS_TEAVM_ASYNC_DUMP:-}" ]]; then
  async_dump_properties="                <property>
                  <name>gaius.teavm.asyncDump</name>
                  <value>$(maven_path "$GAIUS_TEAVM_ASYNC_DUMP")</value>
                </property>
"
  if [[ "${GAIUS_TEAVM_ASYNC_DUMP_STOP:-false}" == "true" ]]; then
    async_dump_properties="$async_dump_properties                <property>
                  <name>gaius.teavm.asyncDumpStop</name>
                  <value>true</value>
                </property>
"
  fi
fi
# JSPI mode (v0.4.0 design D3): the wasmgc roles compile no method as a coroutine and
# suspend through WebAssembly.Suspending imports instead (GaiusTeaVMOptions.JSPI).
# The coroutine mode was removed: GaiusTeaVMOptions.platformTags fails a wasmgc role without it.
jspi_properties=""
if [[ "$teavm_target" == wasmgc ]]; then
  jspi_properties="                <property>
                  <name>gaius.teavm.jspi</name>
                  <value>true</value>
                </property>
"
fi
target_type_element=""
if [[ "$teavm_target" == wasmgc ]]; then
  target_type_element="              <targetType>WEBASSEMBLY_GC</targetType>
"
fi
# Initial off-heap memory of the WebAssembly GC client (MiB, teavm-maven-plugin
# minDirectBuffersSize; TeaVM's default is 2). LWJGL's BrowserMemory, the native
# images and the direct buffers live there, and TeaVM's Heap grows the memory by
# exactly the missing pages, so the 26.3 resource reload grew it once per image
# that did not fit (Heap.tryExtend, about 140 ms sampled). The client's linear
# memory settles at about 127 MiB (WebGPU) to 152 MiB (WebGL2) in the soak runs
# and never shrinks, so starting at 128 MiB costs no memory the session would not
# take anyway. GAIUS_WASM_MIN_DIRECT_BUFFERS_MB overrides it; the server Worker
# POM keeps TeaVM's default (no element) unless the variable is set.
wasm_min_direct_buffers_element=""
wasm_min_direct_buffers_mb="${GAIUS_WASM_MIN_DIRECT_BUFFERS_MB:-}"
if [[ "$teavm_target" == wasmgc && -z "$wasm_min_direct_buffers_mb" && "$teavm_role" == client ]]; then
  wasm_min_direct_buffers_mb=128
fi
if [[ "$teavm_target" == wasmgc && -n "$wasm_min_direct_buffers_mb" ]]; then
  if [[ ! "$wasm_min_direct_buffers_mb" =~ ^[1-9][0-9]*$ ]] || (( wasm_min_direct_buffers_mb > 1024 )); then
    echo "Invalid GAIUS_WASM_MIN_DIRECT_BUFFERS_MB: $wasm_min_direct_buffers_mb (expected 1..1024)" >&2
    exit 1
  fi
  wasm_min_direct_buffers_element="              <minDirectBuffersSize>$wasm_min_direct_buffers_mb</minDirectBuffersSize>
"
fi

mkdir -p "$(dirname "$output")" "$root/port/src/main/java" \
  "$target_directory" "$maven_directory" "$build_root"
stage_profile_sources

{
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.gaius</groupId>
  <artifactId>minecraft-$version-browser</artifactId>
  <version>0.1.0-SNAPSHOT</version>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <teavm.version>$teavm_version</teavm.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-interop</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-jso</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-jso-apis</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.gaius.teavm</groupId>
      <artifactId>teavm-classlib-overlay</artifactId>
      <version>\${teavm.version}</version>
      <scope>system</scope>
      <systemPath>$maven_patched_classlib</systemPath>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-core</artifactId>
      <version>\${teavm.version}</version>
      <scope>system</scope>
      <systemPath>$maven_teavm_core_patch</systemPath>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-platform</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-jso-impl</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-metaprogramming-impl</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>com.jcraft</groupId>
      <artifactId>jzlib</artifactId>
      <version>1.1.3</version>
    </dependency>
    <dependency>
      <groupId>joda-time</groupId>
      <artifactId>joda-time</artifactId>
      <version>2.12.2</version>
    </dependency>
    <dependency>
      <groupId>dev.gaius.minecraft</groupId>
      <artifactId>client-named</artifactId>
      <version>$version</version>
      <scope>system</scope>
      <systemPath>$maven_client</systemPath>
    </dependency>
EOF

  index=0
  while IFS= read -r library; do
    printf '    <dependency>\n'
    printf '      <groupId>dev.gaius.minecraft.library</groupId>\n'
    printf '      <artifactId>library-%03d</artifactId>\n' "$index"
    printf '      <version>1</version>\n'
    printf '      <scope>system</scope>\n'
    printf '      <systemPath>%s</systemPath>\n' "$library"
    printf '    </dependency>\n'
    index=$((index + 1))
  done < <(
      jq -r '
        .libraries[]
        | (.name | split(":")) as $coordinates
        | select(
            ($coordinates | length) == 3
            or (($coordinates | length) == 4
                and $coordinates[0] == "org.lwjgl"
                and $coordinates[1] == "lwjgl"
                and $coordinates[3] == "unsafe"))
        | .downloads.artifact.path
      ' "$metadata" |
      tr -d '\r' |
      while IFS= read -r path; do
        for excluded_prefix in "${excluded_library_prefix_list[@]}"; do
          if [[ -n "$excluded_prefix" && "$path" == "$excluded_prefix"* ]]; then
            continue 2
          fi
        done
        if [[ "$path" == ca/weblite/java-objc-bridge/* ||
              "$path" == net/java/dev/jna/jna/* ||
              "$path" == net/java/dev/jna/jna-platform/* ]]; then
          continue
        fi
        patched="$overlay_directory/libraries/$path"
        if [[ -f "$patched" ]]; then
        printf '%s\n' "$(maven_path "$patched")"
      else
          printf '%s\n' "$(maven_path "$work/libraries/$path")"
        fi
      done
  )

  cat <<EOF
  </dependencies>

  <build>
    <sourceDirectory>$maven_source_directory</sourceDirectory>
    <resources>
      <resource>
        <directory>$maven_source_resources</directory>
      </resource>
      <resource>
        <directory>$maven_resource_directory</directory>
      </resource>
    </resources>
    <directory>$maven_maven_directory</directory>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.1</version>
        <configuration>
          <release>21</release>
          <proc>none</proc>
$compiler_source_excludes
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.teavm</groupId>
        <artifactId>teavm-maven-plugin</artifactId>
        <version>\${teavm.version}</version>
        <dependencies>
          <dependency>
            <groupId>dev.gaius.teavm</groupId>
            <artifactId>teavm-core-browser-patch</artifactId>
            <version>0.1.0</version>
            <scope>system</scope>
            <systemPath>$maven_teavm_core_patch</systemPath>
          </dependency>
        </dependencies>
        <executions>
          <execution>
            <id>compile-minecraft-client</id>
            <phase>package</phase>
            <goals>
              <goal>compile</goal>
            </goals>
            <configuration>
              <mainClass>$main_class</mainClass>
              <targetDirectory>$maven_target_directory</targetDirectory>
              <targetFileName>$target_file</targetFileName>
              <optimizationLevel>$optimization_level</optimizationLevel>
              <sourceMapsGenerated>$source_maps_generated</sourceMapsGenerated>
              <debugInformationGenerated>$debug_information_generated</debugInformationGenerated>
              <minifying>$minifying</minifying>
              <shortFileNames>$short_file_names</shortFileNames>
              <assertionsRemoved>$assertions_removed</assertionsRemoved>
              <stopOnErrors>true</stopOnErrors>
              <maxTopLevelNames>$max_top_level_names</maxTopLevelNames>
${target_type_element}${wasm_min_direct_buffers_element}${wasm_debug_info_element}              <properties>
                <property>
                  <name>gaius.teavm.role</name>
                  <value>$teavm_role</value>
                </property>
                <property>
                  <name>gaius.teavm.syncClinits</name>
                  <value>$teavm_sync_clinits</value>
                </property>
                <property>
                  <name>gaius.teavm.syncMonitors</name>
                  <value>$teavm_sync_monitors</value>
                </property>
                <property>
                  <name>gaius.teavm.asyncBarrier</name>
                  <value>$teavm_async_barrier</value>
                </property>
                <property>
                  <name>gaius.teavm.pinRuntimeNames</name>
                  <value>$teavm_pin_runtime_names</value>
                </property>
                <property>
                  <name>gaius.teavm.telemetry</name>
                  <value>$teavm_telemetry</value>
                </property>
                <property>
                  <name>gaius.teavm.cutReport</name>
                  <value>$maven_maven_directory/gaius-teavm-sync-methods.txt</value>
                </property>
${async_dump_properties}${jspi_properties}              </properties>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
EOF
} >"$output"

# Callers capture stdout as the POM path; the role summary goes to stderr.
echo "TeaVM target $teavm_target role $teavm_role: maxTopLevelNames=$max_top_level_names syncClinits=$teavm_sync_clinits syncMonitors=$teavm_sync_monitors asyncBarrier=$teavm_async_barrier pinRuntimeNames=$teavm_pin_runtime_names telemetry=$teavm_telemetry" >&2
echo "$output"
