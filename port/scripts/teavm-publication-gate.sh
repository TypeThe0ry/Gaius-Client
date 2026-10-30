#!/usr/bin/env bash

# Shared publication gate for the client and integrated-server TeaVM builds.
# A Maven process can return success after TeaVM has only started analysis, so
# its exit status alone is not evidence that the generated JavaScript is ready.

# The build scripts are invoked from Git Bash on both Unix and Windows.  Do
# not use a PID file as the lock itself: mkdir is the atomic operation, while
# owner/pid are metadata written immediately afterwards.  In particular, a
# contender that observes the directory between those writes must wait rather
# than deleting a live lock (the old implementation had exactly that TOCTOU).

gaius_teavm_lock_token() {
  # Keep the token path-safe and parse-free.  The pid remains in its dedicated
  # file for the compatibility check in build-overlays.sh.
  printf '%s-%s-%s\n' "$$" "$(date +%s 2>/dev/null || printf '0')" "${RANDOM:-0}"
}

gaius_teavm_lock_mtime() {
  local lock_dir="$1"
  local value
  value="$(stat -c '%Y' "$lock_dir" 2>/dev/null || true)"
  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    value="$(stat -f '%m' "$lock_dir" 2>/dev/null || true)"
  fi
  [[ "$value" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$value"
}

gaius_teavm_lock_old_enough() {
  local lock_dir="$1"
  local mtime now stale_seconds
  stale_seconds="${GAIUS_TEA_LOCK_STALE_SECONDS:-30}"
  [[ "$stale_seconds" =~ ^[0-9]+$ ]] || stale_seconds=30
  mtime="$(gaius_teavm_lock_mtime "$lock_dir" || true)"
  [[ "$mtime" =~ ^[0-9]+$ ]] || return 1
  now="$(date +%s 2>/dev/null || printf '0')"
  [[ "$now" =~ ^[0-9]+$ ]] || return 1
  (( now >= mtime + stale_seconds ))
}

gaius_teavm_reclaim_stale_lock() {
  local lock_dir="$1"
  local owner_file="$lock_dir/owner"
  local pid_file="$lock_dir/pid"
  local lock_pid
  local stale_path

  # Missing metadata is a normal transient state immediately after mkdir.
  # Wait for the owner to finish its metadata transaction; only reclaim an
  # abandoned directory after the conservative stale timeout.
  if [[ ! -f "$owner_file" && -f "$pid_file" ]]; then
    # Compatibility with a lock left by the pre-owner-token implementation:
    # a dead, valid PID is enough to reclaim it; a live PID still gets the
    # normal wait path below.
    lock_pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [[ "$lock_pid" =~ ^[0-9]+$ ]] && kill -0 "$lock_pid" 2>/dev/null; then
      return 1
    fi
  elif [[ ! -f "$owner_file" || ! -f "$pid_file" ]]; then
    if ! gaius_teavm_lock_old_enough "$lock_dir"; then
      return 1
    fi
  else
    lock_pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [[ ! "$lock_pid" =~ ^[0-9]+$ ]]; then
      if ! gaius_teavm_lock_old_enough "$lock_dir"; then
        return 1
      fi
    elif kill -0 "$lock_pid" 2>/dev/null; then
      return 1
    fi
  fi

  # Rename first, then remove the quarantine directory.  This closes the
  # check/remove race: a new owner can acquire lock_dir after this rename, but
  # can never be removed by this cleanup operation.
  stale_path="${lock_dir}.stale.$$-${RANDOM:-0}"
  if mv "$lock_dir" "$stale_path" 2>/dev/null; then
    rm -rf "$stale_path"
    return 0
  fi
  return 1
}

gaius_teavm_lock_acquire() {
  local lock_dir="$1"
  local owner_token="${2:-$(gaius_teavm_lock_token)}"
  local parent_dir="$(dirname "$lock_dir")"
  local lock_pid
  local owner_file
  local pid_file
  local metadata_tmp
  local poll_seconds="${GAIUS_TEA_LOCK_POLL_SECONDS:-0.2}"
  if [[ ! "$poll_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    poll_seconds=0.2
  fi

  mkdir -p "$parent_dir"
  while true; do
    if mkdir "$lock_dir" 2>/dev/null; then
      owner_file="$lock_dir/owner"
      pid_file="$lock_dir/pid"
      # The temporary owner file prevents readers from seeing a partial token.
      metadata_tmp="$lock_dir/.owner.$$-${RANDOM:-0}.tmp"
      printf '%s\n' "$owner_token" >"$metadata_tmp"
      mv -f "$metadata_tmp" "$owner_file"
      printf '%s\n' "$$" >"$pid_file"
      GAIUS_TEA_LOCK_OWNER_TOKEN="$owner_token"
      export GAIUS_TEA_LOCK_OWNER_TOKEN
      return 0
    fi

    owner_file="$lock_dir/owner"
    pid_file="$lock_dir/pid"
    # A lock directory without a pid is in the owner's mkdir/metadata window;
    # never treat an empty read as a dead owner.
    lock_pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [[ -z "$lock_pid" ]]; then
      sleep "$poll_seconds"
      gaius_teavm_reclaim_stale_lock "$lock_dir" || true
      continue
    fi
    if [[ "$lock_pid" =~ ^[0-9]+$ ]] && kill -0 "$lock_pid" 2>/dev/null; then
      sleep "$poll_seconds"
      continue
    fi
    gaius_teavm_reclaim_stale_lock "$lock_dir" || sleep "$poll_seconds"
  done
}

gaius_teavm_lock_assert_owner() {
  local lock_dir="$1"
  local expected_token="$2"
  local actual_token
  [[ -n "$expected_token" && -f "$lock_dir/owner" ]] || return 1
  actual_token="$(cat "$lock_dir/owner" 2>/dev/null || true)"
  [[ "$actual_token" == "$expected_token" ]]
}

gaius_teavm_lock_release() {
  local lock_dir="$1"
  local expected_token="${2:-}"
  local actual_token
  local release_path

  [[ -d "$lock_dir" ]] || return 0
  if ! gaius_teavm_lock_assert_owner "$lock_dir" "$expected_token"; then
    echo "TeaVM lock owner mismatch; leaving lock in place: $lock_dir" >&2
    return 1
  fi
  actual_token="$(cat "$lock_dir/owner" 2>/dev/null || true)"
  [[ "$actual_token" == "$expected_token" ]] || return 1
  release_path="${lock_dir}.release.$$-${RANDOM:-0}"
  if mv "$lock_dir" "$release_path" 2>/dev/null; then
    rm -rf -- "$release_path" || true
    return 0
  fi
  return 1
}

# On Windows a just-written file is often held for a moment by an on-access
# scanner, so replacing it can fail with "Device or resource busy" even though
# nothing in the build still uses it.  Retry briefly before giving up; the last
# attempt reports the real error.
gaius_teavm_replace_file() {
  local source_path="$1"
  local target_path="$2"
  local attempt
  for (( attempt=1; attempt<20; attempt++ )); do
    mv -f "$source_path" "$target_path" 2>/dev/null && return 0
    sleep 0.5
  done
  mv -f "$source_path" "$target_path"
}

# Publish through a temporary file in the destination directory.  A copy
# failure therefore leaves the previous release untouched, and the final mv
# is atomic on the filesystems used by POSIX and Git Bash on Windows.
gaius_teavm_publish_file() {
  local source_path="$1"
  local target_path="$2"
  local temporary_path
  [[ -f "$source_path" ]] || {
    echo "TeaVM staged artifact is missing: $source_path" >&2
    return 1
  }
  mkdir -p "$(dirname "$target_path")"
  temporary_path="$target_path.publish.$$-${RANDOM:-0}.tmp"
  rm -f "$temporary_path"
  if ! cp "$source_path" "$temporary_path"; then
    rm -f "$temporary_path"
    return 1
  fi
  if ! gaius_teavm_replace_file "$temporary_path" "$target_path"; then
    rm -f "$temporary_path"
    return 1
  fi
}

gaius_teavm_publish_sidecar_if_present() {
  local staged_artifact="$1"
  local final_artifact="$2"
  local staged_sidecar="${staged_artifact}.build.json"
  local final_sidecar="${final_artifact}.build.json"
  if [[ -f "$staged_sidecar" ]]; then
    gaius_teavm_publish_file "$staged_sidecar" "$final_sidecar"
  fi
}

# Preflight every source and destination-local copy before replacing any
# target. If one commit fails, restore every target already replaced. This is
# a file-set transaction: readers may still observe the short commit window,
# but a failed build cannot leave a permanently mixed old/new artifact set.
gaius_teavm_publish_bundle() {
  if (( $# == 0 || $# % 2 != 0 )); then
    echo "TeaVM publish bundle requires source/target pairs" >&2
    return 2
  fi

  local transaction_token="$$-${RANDOM:-0}"
  local fail_after="${GAIUS_TEA_PUBLISH_FAIL_AFTER:-}"
  local source_path target_path temporary_path backup_path
  local index committed=0 rollback_failed=false
  local -a sources=() targets=() temporaries=() backups=() existed=()
  [[ -z "$fail_after" || "$fail_after" =~ ^[0-9]+$ ]] || fail_after=""

  while (( $# > 0 )); do
    source_path="$1"
    target_path="$2"
    shift 2
    [[ -f "$source_path" ]] || {
      echo "TeaVM staged artifact is missing: $source_path" >&2
      return 1
    }
    if [[ -e "$target_path" && ! -f "$target_path" ]]; then
      echo "TeaVM publish target is not a regular file: $target_path" >&2
      return 1
    fi
    mkdir -p "$(dirname "$target_path")"
    temporary_path="${target_path}.publish.${transaction_token}.${#targets[@]}.tmp"
    backup_path="${target_path}.publish.${transaction_token}.${#targets[@]}.bak"
    rm -f -- "$temporary_path" "$backup_path"
    if ! cp "$source_path" "$temporary_path"; then
      rm -f -- "$temporary_path" "$backup_path" || true
      for temporary_path in "${temporaries[@]}"; do rm -f -- "$temporary_path" || true; done
      for backup_path in "${backups[@]}"; do rm -f -- "$backup_path" || true; done
      return 1
    fi
    if [[ -f "$target_path" ]]; then
      if ! cp "$target_path" "$backup_path"; then
        rm -f -- "$temporary_path" "$backup_path" || true
        for temporary_path in "${temporaries[@]}"; do rm -f -- "$temporary_path" || true; done
        for backup_path in "${backups[@]}"; do rm -f -- "$backup_path" || true; done
        return 1
      fi
      existed+=(true)
    else
      existed+=(false)
    fi
    sources+=("$source_path")
    targets+=("$target_path")
    temporaries+=("$temporary_path")
    backups+=("$backup_path")
  done

  for (( index=0; index<${#targets[@]}; index++ )); do
    if ! gaius_teavm_replace_file "${temporaries[$index]}" "${targets[$index]}"; then
      break
    fi
    committed=$((committed + 1))
    if [[ -n "$fail_after" && "$committed" -ge "$fail_after" ]]; then
      echo "Injected TeaVM bundle publication failure after $committed files" >&2
      break
    fi
  done

  if (( committed != ${#targets[@]} )); then
    for (( index=committed-1; index>=0; index-- )); do
      if [[ "${existed[$index]}" == true ]]; then
        gaius_teavm_replace_file "${backups[$index]}" "${targets[$index]}" || rollback_failed=true
      else
        rm -f -- "${targets[$index]}" || rollback_failed=true
      fi
    done
    for temporary_path in "${temporaries[@]}"; do rm -f -- "$temporary_path" || true; done
    for backup_path in "${backups[@]}"; do rm -f -- "$backup_path" || true; done
    if [[ "$rollback_failed" == true ]]; then
      echo "TeaVM bundle rollback was incomplete" >&2
    fi
    return 1
  fi

  for backup_path in "${backups[@]}"; do rm -f -- "$backup_path" || true; done
  return 0
}

gaius_teavm_completion_sentinel_present() {
  local log_path="$1"
  [[ -f "$log_path" ]] || return 1
  grep -Fq "Output file built with errors" "$log_path" \
    || grep -Fq "[INFO] BUILD SUCCESS" "$log_path"
}

gaius_teavm_publish_allowed() {
  local log_path="$1"
  local analysis_status="$2"

  if [[ "$analysis_status" -ne 0 ]]; then
    echo "TeaVM analyzer did not complete (exit $analysis_status); refusing to publish" >&2
    return 1
  fi
  if ! gaius_teavm_completion_sentinel_present "$log_path"; then
    echo "TeaVM log has no completed-analysis sentinel; refusing to publish" >&2
    return 1
  fi
  return 0
}

gaius_teavm_remove_stale_incomplete_reports() {
  local json_path="$1"
  local markdown_path="$2"
  rm -f "${json_path%.json}.incomplete.json" \
    "${markdown_path%.md}.incomplete.md"
}

# WebAssembly GC and JavaScript-target CI gates (v0.4.0 WP1).  The rules live in
# port/scripts/quickcheck/wasmgc_rules.py (letters a-g as in its docstring); these
# wrappers run them from the build scripts and fail the calling build on a red rule.
# Sourcing this file defines the functions only.
gaius_quickcheck_rules() {
  local root="$1"
  shift
  "$root/port/scripts/run-python.sh" "$root/port/scripts/quickcheck/wasmgc_rules.py" "$@"
}

# Rules c, d and e on the source tree, before overlays or TeaVM run.
gaius_wasmgc_source_gate() {
  local root="$1"
  local json_path="${2:-}"
  if ! gaius_quickcheck_rules "$root" source ${json_path:+--json "$json_path"}; then
    echo "WebAssembly GC source rules failed (port/scripts/quickcheck/wasmgc_rules.py source)" >&2
    return 1
  fi
}

# Rules a and b on a compiled module and its TeaVM log (every wasmgc role compiles in JSPI
# mode; GaiusTeaVMOptions.platformTags refuses a wasmgc role without it).
gaius_wasmgc_artifact_gate() {
  local root="$1"
  local wasm_path="$2"
  local log_path="$3"
  local json_path="${4:-}"
  if ! gaius_quickcheck_rules "$root" artifact --wasm "$wasm_path" --log "$log_path" \
      ${json_path:+--json "$json_path"}; then
    echo "WebAssembly GC artifact rules failed for $wasm_path" >&2
    return 1
  fi
}

# Rule f: the PATCH_* lines of a js-target build-overlays.sh log against the baseline.
gaius_js_patch_summary_gate() {
  local root="$1"
  local profile="$2"
  local overlay_log="$3"
  if ! gaius_quickcheck_rules "$root" patch-summary --profile "$profile" --log "$overlay_log"; then
    echo "The $profile JavaScript patch set differs from port/scripts/quickcheck/baselines/js-patch-summary.json" >&2
    return 1
  fi
}

# Rule g: the js POMs generate-pom.sh writes for these overlays against the baselines.
gaius_js_pom_gate() {
  local root="$1"
  local profile="$2"
  local overlay_directory="$3"
  if ! gaius_quickcheck_rules "$root" pom --profile "$profile" --overlay-dir "$overlay_directory"; then
    echo "The generated $profile JavaScript POMs differ from port/scripts/quickcheck/baselines/js-pom" >&2
    return 1
  fi
}
