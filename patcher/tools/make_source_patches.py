#!/usr/bin/env python3
"""Regenerate patcher/patches/<version>/<id>.gpatch from the Gaius sources.

Maintainer tool. Run it on a machine that has the official game files:

  java patcher/GaiusPatcher.java fetch <version>
  (for 1.21.11 also port/scripts/remap-client.sh)
  python3 patcher/tools/make_source_patches.py 26.2 26.3 1.21.11 [--decompiler <jar>]

For each version it asks the patcher to decompile the classes listed in
patcher/source-patches.json (the code path `prepare` uses), compares each
decompiled class with the Gaius source in this tree, and writes an edit
script that holds only the lines Gaius wrote. It refuses a script that would
publish official code: an inserted line that also appears in the decompiled
class of any version it processed. A line reviewed as not official code (a
declaration Gaius has to repeat, say) is accepted by adding the SHA-256 of
its stripped text to the entry's "reviewedLines".

  --pin-decompiler <jar>  writes the jar's SHA-256 into source-patches.json
  --check                 writes nothing; fails when a script is missing or stale
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAGIC = "gaius-source-patch 1"
# Lines this short, and import or package lines, carry no expression of their own.
TRIVIAL_LENGTH = 24


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def make_script(base: str, result: str) -> tuple[str, list[tuple[int, str]]]:
    """The edit script from base to result, and the inserted lines with their line numbers in result."""
    a = base.split("\n")
    b = result.split("\n")
    lines = [MAGIC, "base " + sha256(base), "result " + sha256(result)]
    inserted: list[tuple[int, str]] = []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        lines.append(f"@@ {i1} {i2 - i1}")
        for j in range(j1, j2):
            lines.append("+" + b[j])
            inserted.append((j + 1, b[j]))
    return "\n".join(lines) + "\n", inserted


def apply_script(script: str, base: str) -> str:
    """Python twin of EditScript.apply in GaiusPatcher.java."""
    lines = script.split("\n")
    if lines[0] != MAGIC or lines[-1] != "":
        raise ValueError("not a gaius source patch")
    if sha256(base) != lines[1].removeprefix("base "):
        raise ValueError("base does not match")
    a = base.split("\n")
    out: list[str] = []
    nxt = 0
    for line in lines[3:-1]:
        if line.startswith("@@ "):
            start, count = (int(part) for part in line[3:].split())
            out.extend(a[nxt:start])
            nxt = start + count
        elif line.startswith("+"):
            out.append(line[1:])
        else:
            raise ValueError("unexpected line " + line)
    out.extend(a[nxt:])
    result = "\n".join(out)
    if sha256(result) != lines[2].removeprefix("result "):
        raise ValueError("result does not match")
    return result


def is_trivial(stripped: str) -> bool:
    return len(stripped) < TRIVIAL_LENGTH or stripped.startswith(("import ", "package "))


def official_lines(bases: list[str]) -> set[str]:
    lines = set()
    for base in bases:
        for line in base.split("\n"):
            stripped = line.strip()
            if not is_trivial(stripped):
                lines.add(stripped)
    return lines


def leaked_lines(inserted: list[tuple[int, str]], official: set[str], reviewed: set[str]) -> list[tuple[int, str]]:
    """Inserted lines that are official code: present in a decompiled class and not reviewed."""
    leaks = []
    for number, line in inserted:
        stripped = line.strip()
        if stripped in official and not is_trivial(stripped) and sha256(stripped) not in reviewed:
            leaks.append((number, sha256(stripped)))
    return leaks


def decompile(java: str, version: str, out: Path, decompiler: Path | None, extra: list[str]) -> None:
    command = [java, str(ROOT / "patcher" / "GaiusPatcher.java"), "decompile", version, "--out", str(out)]
    if decompiler is not None:
        command += ["--decompiler", str(decompiler)]
    subprocess.run(command + extra, check=True)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("versions", nargs="*")
    parser.add_argument("--config", type=Path, default=ROOT / "patcher" / "source-patches.json")
    parser.add_argument("--root", type=Path, default=ROOT, help="tree that holds the Gaius sources")
    parser.add_argument("--decompiler", type=Path)
    parser.add_argument("--pin-decompiler", type=Path, metavar="JAR")
    parser.add_argument("--bases", type=Path, help="use decompiled bases already in <dir>/<version>/<id>.java")
    parser.add_argument("--java", default="java")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--patcher-arg", action="append", default=[], help="passed through to the patcher")
    args = parser.parse_args(argv)

    config = json.loads(args.config.read_text(encoding="utf-8"))
    if args.pin_decompiler:
        digest = hashlib.sha256(args.pin_decompiler.read_bytes()).hexdigest()
        config["decompiler"]["sha256"] = digest
        args.config.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"pinned the decompiler: {digest}")
        if not args.versions:
            return 0
    if not args.versions:
        parser.error("name at least one version")

    entries = {entry["id"]: entry for entry in config["patches"]}
    bases: dict[tuple[str, str], str] = {}
    with tempfile.TemporaryDirectory() as scratch:
        for version in args.versions:
            if args.bases:
                directory = args.bases / version
            else:
                directory = Path(scratch) / version
                decompile(args.java, version, directory, args.decompiler, args.patcher_arg)
            for entry in entries.values():
                if version in entry["versions"]:
                    bases[(version, entry["id"])] = (directory / f"{entry['id']}.java").read_bytes().decode("utf-8")

    patches_dir = args.config.parent / "patches"
    failures = 0
    for (version, entry_id), base in sorted(bases.items()):
        entry = entries[entry_id]
        target = args.root / entry["target"]
        result = target.read_bytes().decode("utf-8")
        script, inserted = make_script(base, result)
        assert apply_script(script, base) == result
        official = official_lines([text for (_, other), text in bases.items() if other == entry_id])
        leaks = leaked_lines(inserted, official, set(entry.get("reviewedLines", [])))
        kept = len(result.split("\n")) - len(inserted)
        label = f"{entry_id} for {version}"
        print(f"{label}: {len(inserted)} lines from Gaius, {kept} kept from the decompiled class")
        if leaks:
            failures += 1
            for number, digest in leaks:
                print(f"  REFUSED line {number} of {entry['target']} also appears in the decompiled class"
                      f" (stripped SHA-256 {digest}); keep it in place or review it into reviewedLines")
            continue
        path = patches_dir / version / f"{entry_id}.gpatch"
        if args.check:
            if not path.is_file() or path.read_bytes().decode("utf-8") != script:
                failures += 1
                print(f"  STALE {path.relative_to(args.config.parent.parent)}")
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(script.encode("utf-8"))
    for entry in entries.values():
        missing = [v for v in entry["versions"] if v not in args.versions]
        if missing:
            print(f"note: {entry['id']} was not checked against {', '.join(missing)}")
    if failures:
        print(f"{failures} source patch(es) need attention")
        return 1
    print("source patches are up to date" if args.check else "source patches written")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
