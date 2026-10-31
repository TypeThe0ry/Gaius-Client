#!/usr/bin/env python3
"""P9 cross-source-set token similarity gate; warning rollout is the default."""

from __future__ import annotations

import argparse
from collections import Counter
from difflib import SequenceMatcher
from itertools import combinations
import json
from pathlib import Path
import re

from javasrc import ROOT, strip_comments

TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|\d[\w.]*|\S')
THRESHOLD = 0.8


def tokens(text: str) -> tuple[str, ...]:
    return tuple(TOKEN.findall(strip_comments(text)))


def similarity(left: tuple[str, ...], right: tuple[str, ...]) -> float:
    return max(SequenceMatcher(None, left, right, autojunk=False).ratio(),
               SequenceMatcher(None, right, left, autojunk=False).ratio())


def collect(root: Path) -> list[dict]:
    shared = root / "port/mc/shared"
    manifest = json.loads((shared / "sourcesets.json").read_text(encoding="utf-8"))
    directories = [(entry["name"], shared / entry["name"] / kind / "java")
                   for entry in manifest["sets"] for kind in ("src", "client")]
    for kind, base in (("src", root / "port/src"), ("client", root / "port/overrides/client")):
        directories.append(("base", base / "main/java"))
        directories.extend((f"target-{target}", base / f"target-{target}/java")
                           for target in ("js", "wasmgc"))
    sources = []
    for source_set, directory in directories:
        if not directory.is_dir():
            continue
        for path in sorted(directory.rglob("*.java")):
            code = tokens(path.read_text(encoding="utf-8"))
            sources.append({"path": path.relative_to(root).as_posix(), "set": source_set,
                            "tokens": code, "counts": Counter(code)})
    return sources


def compare(sources: list[dict], allowed: dict) -> list[dict]:
    findings = []
    for left, right in combinations(sources, 2):
        if left["set"] == right["set"]:
            continue
        total = len(left["tokens"]) + len(right["tokens"])
        if total and 2 * sum((left["counts"] & right["counts"]).values()) / total < THRESHOLD:
            continue
        score = similarity(left["tokens"], right["tokens"])
        if score < THRESHOLD:
            continue
        pair = tuple(sorted((left["path"], right["path"])))
        findings.append({"paths": list(pair), "sets": [left["set"], right["set"]],
                         "similarity": score, "exception": allowed.get(pair)})
    return sorted(findings, key=lambda finding: (-finding["similarity"], finding["paths"]))


def exceptions(path: Path, sources: list[dict]) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    known = {source["path"]: source["set"] for source in sources}
    allowed = {}
    for entry in data["exceptions"]:
        pair = tuple(sorted(entry["paths"]))
        if (len(pair) != 2 or pair[0] == pair[1] or pair in allowed
                or any(name not in known for name in pair) or known[pair[0]] == known[pair[1]]
                or not isinstance(entry["reason"], str) or not entry["reason"].strip()):
            raise ValueError(f"invalid or stale P9 exception: {entry}")
        allowed[pair] = entry["reason"]
    return allowed


def exit_code(findings: list[dict], enforce: bool) -> int:
    return int(enforce and any(finding["exception"] is None for finding in findings))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--warn", action="store_true")
    mode.add_argument("--enforce", action="store_true")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    sources = collect(args.root.resolve())
    allowed = exceptions(args.root / "port/mc/shared/similarity-exceptions.json", sources)
    findings = compare(sources, allowed)
    unused = set(allowed) - {tuple(finding["paths"]) for finding in findings}
    if unused:
        raise ValueError(f"stale P9 exceptions below threshold: {sorted(unused)}")
    report = {"format": "gaius-p9-similarity/1", "threshold": THRESHOLD,
              "mode": "enforce" if args.enforce else "warn", "sources": len(sources),
              "allowedExceptions": len(allowed), "findings": findings}
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
    for finding in findings:
        state = "ALLOW" if finding["exception"] else "FAIL" if args.enforce else "WARN"
        print(f"P9 {state} {finding['similarity']:.1%} {' <> '.join(finding['paths'])}"
              + (f" ({finding['exception']})" if finding["exception"] else ""))
    violations = sum(finding["exception"] is None for finding in findings)
    print(f"P9 sources={len(sources)} pairs={len(findings)} exceptions={len(allowed)} "
          f"violations={violations} mode={report['mode']}")
    return exit_code(findings, args.enforce)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, KeyError, OSError) as error:
        print(f"P9 configuration error: {error}")
        raise SystemExit(2)
