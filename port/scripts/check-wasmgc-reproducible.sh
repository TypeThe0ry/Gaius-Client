#!/usr/bin/env bash
set -euo pipefail

# Reproducibility check of the WebAssembly GC release role (build-wasmgc.sh --release): two
# builds of one tree must give the same bytes after the binaryen step.
#
#   check-wasmgc-reproducible.sh build [--role client|singleplayer-worker] <profile> <work dir>
#   check-wasmgc-reproducible.sh compare [--role client|singleplayer-worker] [--json <report>]
#                                        <build root A> <dist A> <build root B> <dist B>
#
# build runs two release builds into <work dir>/a and <work dir>/b, each with its own overlay
# directory; the second one starts the JVM with -XX:hashCode=3, so identity hashes differ from
# the first (TeaVM's output must not depend on them). GAIUS_WASMGC_RESOURCE_ROOT (and _DIST)
# name the JavaScript build both read their resources from, as for build-wasmgc.sh; the rest of
# its settings (GAIUS_WASM_OPT, GAIUS_TEAVM_MAX_HEAP, ...) apply to both builds.
# compare checks, for the role's module: the shipped module, its symbol file and sha256 record,
# TeaVM's line tables (next to the symbols, or under pre-opt/ after wasm-opt), the wasm-opt
# report without its timing, and the build identity sidecar. Exit status 1 when any differs.

root="$(cd "$(dirname "$0")/../.." && pwd)"
usage() {
  echo "usage: $0 build [--role client|singleplayer-worker] <profile> <work dir>" >&2
  echo "       $0 compare [--role client|singleplayer-worker] [--json <report>] <build root A> <dist A> <build root B> <dist B>" >&2
  exit 2
}
[[ "$#" -ge 1 ]] || usage
command_name="$1"
shift
role=client
report=""
positional=()
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --role) [[ "$#" -ge 2 ]] || usage; role="$2"; shift 2 ;;
    --json) [[ "$#" -ge 2 ]] || usage; report="$2"; shift 2 ;;
    -*) usage ;;
    *) positional+=("$1"); shift ;;
  esac
done
case "$role" in
  client) module=classes.wasm; role_directory="" ;;
  singleplayer-worker) module=singleplayer-server.wasm; role_directory=server-worker ;;
  *) usage ;;
esac

sha() {
  if [[ -f "$1" ]]; then sha256sum "$1" | cut -d' ' -f1; else echo missing; fi
}

# The wasm-opt report without its timing (the only field that may differ).
report_sha() {
  if [[ -f "$1" ]]; then
    sed -E '/^  "seconds": /d; s/,$//' "$1" | tr -d '\r' | sha256sum | cut -d' ' -f1
  else
    echo missing
  fi
}

compare() {
  local build_a="$1" dist_a="$2" build_b="$3" dist_b="$4"
  local role_a="$build_a${role_directory:+/$role_directory}" role_b="$build_b${role_directory:+/$role_directory}"
  local items=(
    "module|$dist_a/$module|$dist_b/$module"
    "symbols|$role_a/symbols/$module.symbols.wasm|$role_b/symbols/$module.symbols.wasm"
    "module-sha256|$role_a/symbols/$module.sha256|$role_b/symbols/$module.sha256"
    "line-tables|$role_a/symbols/$module.teadbg|$role_b/symbols/$module.teadbg"
    "line-tables-pre-opt|$role_a/symbols/pre-opt/$module.teadbg|$role_b/symbols/pre-opt/$module.teadbg"
    "teavm-module-sha256|$role_a/symbols/pre-opt/$module.sha256|$role_b/symbols/pre-opt/$module.sha256"
    "build-identity|$dist_a/$module.build.json|$dist_b/$module.build.json"
  )
  local failed=0 json="" entry name path_a path_b sha_a sha_b verdict
  for entry in "${items[@]}" "wasm-opt-report|$role_a/wasm-opt.json|$role_b/wasm-opt.json"; do
    IFS='|' read -r name path_a path_b <<<"$entry"
    if [[ "$name" == wasm-opt-report ]]; then
      sha_a="$(report_sha "$path_a")"
      sha_b="$(report_sha "$path_b")"
    else
      sha_a="$(sha "$path_a")"
      sha_b="$(sha "$path_b")"
    fi
    if [[ "$sha_a" == missing && "$sha_b" == missing ]]; then
      verdict=absent
    elif [[ "$sha_a" == "$sha_b" ]]; then
      verdict=identical
    else
      verdict=DIFFERENT
      failed=1
    fi
    printf '%-20s %-9s %s %s\n' "$name" "$verdict" "${sha_a:0:16}" "${sha_b:0:16}"
    json+="${json:+,}\"$name\":{\"verdict\":\"$verdict\",\"a\":\"$sha_a\",\"b\":\"$sha_b\"}"
  done
  if [[ "$(sha "$dist_a/$module")" == missing ]]; then
    echo "no $module in $dist_a" >&2
    failed=1
  fi
  if [[ -n "$report" ]]; then
    mkdir -p "$(dirname "$report")"
    printf '{"role":"%s","module":"%s","reproducible":%s,"items":{%s}}\n' \
      "$role" "$module" "$([[ "$failed" == 0 ]] && echo true || echo false)" "$json" >"$report"
  fi
  if [[ "$failed" == 0 ]]; then
    echo "reproducible: $module of both builds is byte-identical"
  else
    echo "NOT reproducible: $module differs between the builds" >&2
  fi
  return "$failed"
}

case "$command_name" in
  compare)
    [[ "${#positional[@]}" -eq 4 ]] || usage
    compare "${positional[@]}"
    ;;
  build)
    [[ "${#positional[@]}" -eq 2 ]] || usage
    profile="${positional[0]}"
    work="${positional[1]}"
    mkdir -p "$work"
    work="$(cd "$work" && pwd)"
    role_arguments=()
    [[ "$role" == client ]] || role_arguments=(--role "$role")
    for build in a b; do
      export GAIUS_BUILD_ROOT="$work/$build/build"
      export GAIUS_OVERLAY_DIRECTORY="$work/$build/overlays"
      export GAIUS_DIST_DIRECTORY="$work/$build/dist"
      mkdir -p "$GAIUS_BUILD_ROOT" "$GAIUS_OVERLAY_DIRECTORY" "$GAIUS_DIST_DIRECTORY"
      if [[ "$build" == b ]]; then
        # The JVM settings of build a (build-wasmgc.sh's default, which appends
        # GAIUS_TEAVM_JVM_OPTIONS, or the caller's MAVEN_OPTS), plus other identity hashes;
        # -XX:hashCode is an experimental option on JDK 25.
        hash_options="-XX:+UnlockExperimentalVMOptions -XX:hashCode=3"
        if [[ -n "${MAVEN_OPTS:-}" ]]; then
          export MAVEN_OPTS="$MAVEN_OPTS $hash_options"
        else
          export GAIUS_TEAVM_JVM_OPTIONS="${GAIUS_TEAVM_JVM_OPTIONS:+$GAIUS_TEAVM_JVM_OPTIONS }$hash_options"
        fi
      fi
      echo "== build $build ($(date -Is))"
      bash "$root/port/scripts/build-wasmgc.sh" --release ${role_arguments[@]+"${role_arguments[@]}"} "$profile" \
        >"$work/$build.log" 2>&1 || { tail -n 40 "$work/$build.log" >&2; exit 1; }
    done
    compare "$work/a/build" "$work/a/dist" "$work/b/build" "$work/b/dist"
    ;;
  *)
    usage
    ;;
esac
