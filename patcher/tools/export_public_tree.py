#!/usr/bin/env python3
"""Copy the files listed in patcher/public-allowlist.txt into a fresh directory.

The public repository never shares history with this private one: each
export is a plain copy of allowlisted, tracked files, checked by
public_tree_check.py before anything else happens. The script only writes to
--out; it does not commit, push or contact any remote.

Files under patcher/public-root/ are exported to the root of the public tree
(README, disclaimer, issue rules and templates); every other file keeps its
path.

Allowlist syntax, one pattern per line, relative to the repository root:
  patcher/**              every tracked file under patcher/
  port/versions/*.json    '*' stays inside one directory, '**' crosses them
  !patcher/tests/big/**   a later '!' line removes matches again
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import public_tree_check  # noqa: E402


def pattern_regex(pattern: str) -> re.Pattern[str]:
    out = []
    i = 0
    while i < len(pattern):
        if pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        elif pattern[i] == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.compile("^" + "".join(out) + "$")


PUBLIC_ROOT = "patcher/public-root/"


def export_path(path: str) -> str:
    return path[len(PUBLIC_ROOT):] if path.startswith(PUBLIC_ROOT) else path


def selected_files(repo: Path, allowlist: Path) -> list[str]:
    rules = []
    for line in public_tree_check.read_list(allowlist):
        negate = line.startswith("!")
        rules.append((negate, pattern_regex(line[1:] if negate else line)))
    tracked = subprocess.run(
        ["git", "ls-files", "-z"], cwd=repo, check=True, capture_output=True
    ).stdout.decode("utf-8").split("\0")
    chosen = []
    for path in tracked:
        if not path:
            continue
        keep = False
        for negate, regex in rules:
            if regex.match(path):
                keep = not negate
        if keep:
            chosen.append(path)
    return sorted(chosen)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True, help="empty or missing directory to export into")
    parser.add_argument("--repo", type=Path, default=HERE.parents[1])
    parser.add_argument("--allowlist", type=Path)
    parser.add_argument("--list", action="store_true", help="print the selected files and stop")
    known, rest = parser.parse_known_args(argv)

    repo = known.repo.resolve()
    allowlist = known.allowlist or repo / "patcher" / "public-allowlist.txt"
    files = selected_files(repo, allowlist)
    targets: dict[str, str] = {}
    for path in files:
        target = export_path(path)
        if target in targets:
            print(f"{path} and {targets[target]} would both be exported as {target}", file=sys.stderr)
            return 2
        targets[target] = path
    if known.list:
        print("\n".join(f"{path} -> {target}" if target != path else path for target, path in targets.items()))
        return 0

    out = known.out.resolve()
    if out.exists() and any(out.iterdir()):
        print(f"refusing to export into a non-empty directory: {out}", file=sys.stderr)
        return 2
    if out == repo or repo in out.parents:
        print("refusing to export inside the private repository", file=sys.stderr)
        return 2
    for target_path, path in targets.items():
        target = out / target_path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(repo / path, target)
    print(f"exported {len(files)} files to {out}")
    return public_tree_check.main([str(out), *rest])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
