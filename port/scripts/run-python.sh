#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${PYTHON:-}" ]]; then
  exec "$PYTHON" "$@"
fi

# Windows may expose a non-functional python3 App Execution Alias. Prefer the
# real `python` launcher when it can actually start, then fall back to python3.
if command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
  exec python "$@"
fi
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
  exec python3 "$@"
fi

echo "A working Python 3 interpreter was not found" >&2
exit 127
