#!/usr/bin/env python3
"""Smoke-test stage-train JS role argument validation without building artifacts."""
import subprocess
import sys

script = sys.argv[1]
cases = (
    ([script, "unused", "--js-role", "frozen"], "requires --frozen-js-source"),
    ([script, "unused", "--js-role", "missing", "--frozen-js-source", "somewhere"],
     "only valid with --js-role=frozen"),
)
for args, expected in cases:
    result = subprocess.run([sys.executable, *args], text=True, capture_output=True)
    if result.returncode == 0 or expected not in result.stderr:
        raise SystemExit(f"expected argument rejection containing {expected!r}; got {result.returncode}: {result.stderr}")
print("stage-train-js-role-smoke: PASS")
