#!/usr/bin/env python3
"""Measure handwritten per-version code against tracked Gaius source."""
from __future__ import annotations
import argparse
import subprocess
import sys
from pathlib import Path

EXTENSIONS = {".java", ".rs", ".js", ".mjs", ".ts", ".c", ".h", ".cpp", ".cc"}
# Generated or vendored output that is committed (TeaVM smoke bundle, built relay) is not handwritten.
EXCLUDED = ("port/web/dist/", "port/work/", "node_modules/", "target/", "port/web/smoke/platform-smoke",
            "apps/bridge/dist/", "port/scripts/legacy/")
# Per-generation seams and shared sources, their harnesses, and the per-version patcher classes.
VERSION_MARKERS = ("port/mc/seams/", "port/mc/shared/renderpearl-1/", "port/mc/shared/legacy-12111/",
                   "port/tools/seam-harness/", "/dev/gaius/tools/Minecraft12111BrowserPatcher.java",
                   "/dev/gaius/tools/Minecraft262BrowserPatcher.java", "/dev/gaius/tools/Minecraft263BrowserPatcher.java")

def tracked(root: Path) -> list[str]:
    result = subprocess.run(["git", "ls-files"], cwd=root, check=True, text=True,
                            stdout=subprocess.PIPE).stdout.splitlines()
    return [path.replace("\\", "/") for path in result
            if Path(path).suffix in EXTENSIONS and not any(path.startswith(prefix) for prefix in EXCLUDED)
            and "/test/" not in f"/{path}" and not path.endswith("Test.java")
            and not path.endswith(("-smoke.mjs", "-smoke.js", ".test.mjs"))]

def lines(root: Path, paths: list[str]) -> int:
    return sum(1 for path in paths for line in (root / path).read_text(encoding="utf-8", errors="replace").splitlines()
               if line.strip() and not line.lstrip().startswith(("//", "/*", "*", "#")))

def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--max-percent", type=float, default=5.0)
    parser.add_argument("--enforce", action="store_true")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    paths = tracked(root)
    specific = [path for path in paths if any(marker in path for marker in VERSION_MARKERS)]
    total = lines(root, paths)
    version = lines(root, specific)
    ratio = 100.0 * version / total if total else 0.0
    print(f"adapter-ratio files={len(paths)} version_files={len(specific)} lines={total} "
          f"version_lines={version} ratio={ratio:.2f}% limit={args.max_percent:.2f}%")
    return 1 if args.enforce and ratio > args.max_percent else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
