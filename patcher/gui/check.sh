#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${GAIUS_BUILDER_OUT_DIR:-${SCRIPT_DIR}/target/check}"
TMP_DIR="${GAIUS_BUILDER_TMP_DIR:-${TMPDIR:-/tmp}}"
mkdir -p "${OUT_DIR}/shots" "${TMP_DIR}"
OUT_DIR="$(cd "${OUT_DIR}" && pwd)"
TMP_DIR="$(cd "${TMP_DIR}" && pwd)"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-${SCRIPT_DIR}/target}"
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
