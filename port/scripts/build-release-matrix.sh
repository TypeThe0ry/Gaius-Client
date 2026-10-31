#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point; v0.4 release policy lives in release-train.sh.
root="$(cd "$(dirname "$0")/../.." && pwd)"
exec bash "$root/port/scripts/release-train.sh" "$@"
