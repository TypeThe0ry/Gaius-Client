#!/usr/bin/env bash

# Writer lock of one overlay directory (sourced by build-overlays.sh and the TeaVM build scripts;
# needs teavm-publication-gate.sh for gaius_teavm_lock_acquire/release).
#
# build-overlays.sh rewrites the overlay jars of one directory (GAIUS_OVERLAY_DIRECTORY or
# port/work/overlays/<profile>), and a TeaVM compile keeps them open until it ends.  The lock
# that keeps a second writer out of a directory, and keeps a compile from reading a half-built
# one, is therefore a property of that directory: two builds of different overlay directories
# run side by side, two builds of the same one take turns.  The lock is the directory
# ".<name>.build-overlays.lock" next to the overlay directory, named after its canonical path
# (symlinks and Windows junctions such as port/work resolved), so every spelling of one
# directory (/d/..., D:/..., a worktree's port/work junction) shares one lock.
#
# A caller that holds the lock of the directory (build-wasmgc.sh, build-teavm.sh,
# build-teavm-server-worker.sh, build-platform-smoke.sh) until its TeaVM compile has closed the
# jars runs build-overlays.sh with GAIUS_OVERLAY_LOCK_HELD=true, and that run keeps the lock
# (gaius_overlay_lock_join).

# gaius_overlay_lock_path DIR: creates the overlay directory DIR and prints its lock path.
gaius_overlay_lock_path() {
  local directory="$1"
  local canonical
  mkdir -p "$directory" || return 1
  canonical="$(cd "$directory" && pwd -P)" || return 1
  printf '%s/.%s.build-overlays.lock\n' "$(dirname "$canonical")" "$(basename "$canonical")"
}

# gaius_overlay_canonical_directory DIR: the path the lock is named after (DIR must exist).
gaius_overlay_canonical_directory() {
  (cd "$1" && pwd -P)
}

# gaius_overlay_lock_acquire ROOT DIR: takes the lock of overlay directory DIR (ROOT, the
# repository root, is not used) and exports
# GAIUS_OVERLAY_LOCK_PATH and GAIUS_OVERLAY_LOCK_OWNER_TOKEN (pass both to
# gaius_overlay_lock_release).
gaius_overlay_lock_acquire() {
  local root="$1"
  local directory="$2"
  local lock
  lock="$(gaius_overlay_lock_path "$directory")" || return 1
  gaius_teavm_lock_acquire "$lock" || return 1
  GAIUS_OVERLAY_LOCK_PATH="$lock"
  GAIUS_OVERLAY_LOCK_OWNER_TOKEN="$GAIUS_TEA_LOCK_OWNER_TOKEN"
  export GAIUS_OVERLAY_LOCK_PATH GAIUS_OVERLAY_LOCK_OWNER_TOKEN
}

# gaius_overlay_lock_join ROOT DIR CALLER_PID: for a build-overlays.sh run with
# GAIUS_OVERLAY_LOCK_HELD=true, whose caller (process CALLER_PID) must hold the lock of DIR. The
# run keeps it: GAIUS_OVERLAY_LOCK_PATH is exported and GAIUS_OVERLAY_LOCK_OWNER_TOKEN is empty
# (nothing to release). Fails when the caller does not hold it.
gaius_overlay_lock_join() {
  local directory="$2"
  local caller="$3"
  local lock
  lock="$(gaius_overlay_lock_path "$directory")" || return 1
  GAIUS_OVERLAY_LOCK_PATH="$lock"
  GAIUS_OVERLAY_LOCK_OWNER_TOKEN=""
  export GAIUS_OVERLAY_LOCK_PATH GAIUS_OVERLAY_LOCK_OWNER_TOKEN
  [[ -d "$lock" && "$(cat "$lock/pid" 2>/dev/null || true)" == "$caller" ]]
}

# gaius_overlay_lock_release LOCK TOKEN
gaius_overlay_lock_release() {
  gaius_teavm_lock_release "$1" "$2"
}
