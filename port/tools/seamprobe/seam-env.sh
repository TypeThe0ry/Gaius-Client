#!/usr/bin/env bash
# Shared setup for the render seam tools (seamprobe.sh, port/mc/seams/test-seam.sh); sourced.
# Offline: the vanilla jar and libraries come from port/work/<profile>, ASM and the TeaVM JSO jars
# from the local Maven repository (GAIUS_MAVEN_REPOSITORY, default ~/.m2/repository).
#
#   seam_env_init ROOT PROFILE        sets SEAM_PROFILE SEAM_GENERATION SEAM_DIR SEAM_JAR SEAM_JAVA
#                                     SEAM_JAVAC SEAM_SEP SEAM_LIBS SEAM_TEAVM SEAM_ASM
#   seam_compile OUT [EXTRA_ROOT...]  compiles the WebGPU core and the profile's seam (plus extra
#                                     source roots) into OUT/core and OUT/seam; sets SEAM_CLASSPATH
#   seam_path PATH                    PATH in the form the JDK on this host accepts

seam_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s\n' "$1"
  fi
}

seam_env_init() {
  local root="$1"
  SEAM_PROFILE="$2"
  local profile_json="$root/port/versions/$SEAM_PROFILE.json"
  if [[ ! -f "$profile_json" ]]; then
    echo "Unknown profile $SEAM_PROFILE: $profile_json is missing" >&2
    return 2
  fi
  SEAM_GENERATION="$(jq -r '.renderSeam // "none"' "$profile_json" | tr -d '\r')"
  SEAM_DIR="$root/port/mc/seams/$SEAM_GENERATION"
  SEAM_JAR="$root/port/work/$SEAM_PROFILE/client-named.jar"
  local java_home="${JAVA_HOME:-${GAIUS_JAVA_HOME:-}}"
  if [[ -n "$java_home" ]]; then
    java_home="$(seam_path "$java_home")"
    SEAM_JAVA="$java_home/bin/java"
    SEAM_JAVAC="$java_home/bin/javac"
  else
    SEAM_JAVA=java
    SEAM_JAVAC=javac
  fi
  SEAM_SEP=":"
  if command -v cygpath >/dev/null 2>&1; then
    SEAM_SEP=";"
  fi
  local repository="${GAIUS_MAVEN_REPOSITORY:-$HOME/.m2/repository}"
  local teavm_version
  teavm_version="$(jq -er '.teaVMVersion' "$root/port/config.json" | tr -d '\r')"
  SEAM_TEAVM=""
  local artifact
  for artifact in teavm-interop teavm-jso teavm-jso-apis; do
    local jar="$repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
    if [[ ! -f "$jar" ]]; then
      echo "Missing $jar (offline Maven repository)" >&2
      return 2
    fi
    SEAM_TEAVM="$SEAM_TEAVM${SEAM_TEAVM:+$SEAM_SEP}$(seam_path "$jar")"
  done
  SEAM_ASM=""
  for artifact in asm asm-tree; do
    local jar="$repository/org/ow2/asm/$artifact/9.8/$artifact-9.8.jar"
    if [[ ! -f "$jar" ]]; then
      echo "Missing $jar (offline Maven repository)" >&2
      return 2
    fi
    SEAM_ASM="$SEAM_ASM${SEAM_ASM:+$SEAM_SEP}$(seam_path "$jar")"
  done
  SEAM_LIBS=""
  local classpath="$root/port/work/$SEAM_PROFILE/classpath.txt"
  if [[ -f "$classpath" ]]; then
    # fetch-version.sh writes the host's class path: ';'-separated with drive letters, or
    # ':'-separated (POSIX paths, including Git Bash's /d/...).
    local separator=":"
    grep -q ';' "$classpath" && separator=";"
    local entry
    while IFS= read -r entry; do
      [[ -n "$entry" ]] && SEAM_LIBS="$SEAM_LIBS${SEAM_LIBS:+$SEAM_SEP}$(seam_path "$entry")"
    done < <(tr -d '\r\n' <"$classpath" | tr "$separator" '\n')
  fi
}

seam_compile() {
  local out="$1"
  shift
  local root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
  mkdir -p "$out/core" "$out/seam"
  local sources=()
  local source
  while IFS= read -r source; do
    sources+=("$source")
  done < <(find "$root/port/gpu/webgpu-core/src/main/java" -name '*.java' | sort)
  # The core compiles without the vanilla jar on its class path: no vanilla symbol can creep in.
  # (-nowarn: the TeaVM JSO annotations reference an IntelliJ annotation that is not on the path.)
  if ! "$SEAM_JAVAC" -J-Duser.language=en --release 21 -proc:none -nowarn -classpath "$SEAM_TEAVM" \
      -d "$(seam_path "$out/core")" "${sources[@]}" >"$out/javac-core.log" 2>&1; then
    cat "$out/javac-core.log" >&2
    return 1
  fi
  sources=()
  local seam_root
  for seam_root in "$SEAM_DIR/src/main/java" "$@"; do
    while IFS= read -r source; do
      sources+=("$source")
    done < <(find "$seam_root" -name '*.java' | sort)
  done
  local classpath
  classpath="$(seam_path "$out/core")$SEAM_SEP$(seam_path "$SEAM_JAR")$SEAM_SEP$SEAM_TEAVM"
  [[ -n "$SEAM_LIBS" ]] && classpath="$classpath$SEAM_SEP$SEAM_LIBS"
  if ! "$SEAM_JAVAC" -J-Duser.language=en --release 21 -proc:none -nowarn -classpath "$classpath" \
      -d "$(seam_path "$out/seam")" "${sources[@]}" >"$out/javac-seam.log" 2>&1; then
    cat "$out/javac-seam.log" >&2
    return 1
  fi
  SEAM_CLASSPATH="$classpath"
}
