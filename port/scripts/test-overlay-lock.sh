#!/usr/bin/env bash
# Regression test of the overlay directory writer lock (port/scripts/overlay-lock.sh): one
# directory reached through two spellings shares one lock, two directories do not, and a run of
# build-overlays.sh joins the lock only when its caller holds it.  Synthetic directories only:
# no overlay build, Maven or TeaVM.
#
#   port/scripts/test-overlay-lock.sh
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root_dir/port/scripts/teavm-publication-gate.sh"
source "$root_dir/port/scripts/overlay-lock.sh"
export GAIUS_TEA_LOCK_POLL_SECONDS=0.05

scratch="$(mktemp -d)"
cleanup() {
  rm -rf "$scratch"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# acquire_within SECONDS ROOT DIR: takes and releases the lock of DIR in another bash process
# (the lock records the pid of the process, $$, which a subshell would share with this one);
# succeeds when that finished within SECONDS.
acquire_within() {
  local limit="$1" fake_root="$2" directory="$3"
  bash -c 'set -eu
    source "$1/port/scripts/teavm-publication-gate.sh"
    source "$1/port/scripts/overlay-lock.sh"
    gaius_overlay_lock_acquire "$2" "$3"
    gaius_overlay_lock_release "$GAIUS_OVERLAY_LOCK_PATH" "$GAIUS_OVERLAY_LOCK_OWNER_TOKEN"' \
    acquire "$root_dir" "$fake_root" "$directory" 2>/dev/null &
  local waiter=$!
  local waited=0
  while kill -0 "$waiter" 2>/dev/null; do
    if (( waited >= limit * 20 )); then
      kill "$waiter" 2>/dev/null || true
      wait "$waiter" 2>/dev/null || true
      return 1
    fi
    sleep 0.05
    waited=$((waited + 1))
  done
  wait "$waiter"
}

fake_root="$scratch/root"
mkdir -p "$fake_root/port/work" "$scratch/a" "$scratch/b"
ln -s "$scratch/a" "$scratch/a-link" 2>/dev/null || true

# 1. Two spellings of one directory share the lock path.
first="$(gaius_overlay_lock_path "$scratch/a")"
second="$(gaius_overlay_lock_path "$scratch/./b/../a")"
[[ "$first" == "$second" ]] || fail "lock paths differ: $first vs $second"
if [[ -L "$scratch/a-link" ]]; then
  [[ "$(gaius_overlay_lock_path "$scratch/a-link")" == "$first" ]] \
    || fail "a symlink to the directory gets another lock"
fi
[[ "$(gaius_overlay_lock_path "$scratch/b")" != "$first" ]] || fail "two directories share a lock"

# 2. A held lock keeps writers of the same directory waiting, not those of another one.
gaius_overlay_lock_acquire "$fake_root" "$scratch/a"
held_path="$GAIUS_OVERLAY_LOCK_PATH"
held_token="$GAIUS_OVERLAY_LOCK_OWNER_TOKEN"
[[ "$(cat "$held_path/pid")" == "$$" ]] || fail "the lock does not record its owner"
acquire_within 5 "$fake_root" "$scratch/b" || fail "another directory waited for the lock"
if acquire_within 1 "$fake_root" "$scratch/./a"; then
  fail "the same directory was locked twice"
fi
gaius_overlay_lock_release "$held_path" "$held_token"
[[ ! -d "$held_path" ]] || fail "release left the lock directory"
acquire_within 5 "$fake_root" "$scratch/a" || fail "a released lock still blocks"

# 3. gaius_overlay_lock_join (build-overlays.sh with GAIUS_OVERLAY_LOCK_HELD=true): a caller
# process that holds the lock of the directory, of another directory or none, and a run whose
# parent is that caller.
# join_as_child ROOT DIR HOLD: the caller takes HOLD (dir: the lock of DIR, other: the lock of
# OTHER_DIR, none), then a child bash joins DIR and prints "joined <token>" or "refused"; the
# caller releases afterwards.
join_as_child() {
  bash -c 'set -eu
    source "$1/port/scripts/teavm-publication-gate.sh"
    source "$1/port/scripts/overlay-lock.sh"
    case "$4" in
      dir) gaius_overlay_lock_acquire "$2" "$3" ;;
      other) gaius_overlay_lock_acquire "$2" "$5" ;;
    esac
    bash -c '\''set -eu
      source "$1/port/scripts/teavm-publication-gate.sh"
      source "$1/port/scripts/overlay-lock.sh"
      if gaius_overlay_lock_join "$2" "$3" "$PPID"; then
        echo "joined ${GAIUS_OVERLAY_LOCK_OWNER_TOKEN:-none}"
      else
        echo refused
      fi'\'' child "$1" "$2" "$3"
    case "$4" in
      dir|other) gaius_overlay_lock_release "$GAIUS_OVERLAY_LOCK_PATH" "$GAIUS_OVERLAY_LOCK_OWNER_TOKEN" ;;
    esac' caller "$root_dir" "$1" "$2" "$3" "$OTHER_DIR"
}
OTHER_DIR="$scratch/a"
joined="$(join_as_child "$fake_root" "$scratch/b" dir)"
[[ "$joined" == "joined none" ]] || fail "a caller holding the directory lock: $joined"
joined="$(join_as_child "$fake_root" "$scratch/b" other)"
[[ "$joined" == "refused" ]] || fail "a caller holding another directory's lock: $joined"
joined="$(join_as_child "$fake_root" "$scratch/b" none)"
[[ "$joined" == "refused" ]] || fail "a caller holding no lock: $joined"
[[ ! -d "$(gaius_overlay_lock_path "$scratch/b")" && ! -d "$first" ]] || fail "join left a lock behind"

echo "overlay lock: ok"
