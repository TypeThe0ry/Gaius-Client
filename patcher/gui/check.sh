#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_OUT="${SCRIPT_DIR}/target/check"
OUT_DIR="${GAIUS_BUILDER_OUT_DIR:-${DEFAULT_OUT}}"
TMP_DIR="${GAIUS_BUILDER_TMP_DIR:-${TMPDIR:-/tmp}/gaius-builder-check}"
mkdir -p "${OUT_DIR}/shots" "${TMP_DIR}"
export CARGO_TARGET_DIR="${TMP_DIR}/target"
export TMP="${TMP_DIR}"
export TEMP="${TMP_DIR}"
export TMPDIR="${TMP_DIR}"
cd "${SCRIPT_DIR}"

cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo run --release -- doctor
cargo run --release -- --screenshot "${OUT_DIR}/shots/check.png" \
  --page 2 --theme dark --lang zh --size 960x620
