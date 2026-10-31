#!/usr/bin/env python3
"""Regenerate patcher/patches/<version>/<id>.gpatch from the Gaius sources.

Maintainer tool. Run it on a machine that has the official game files:

  java patcher/GaiusPatcher.java fetch <version>
  (for 1.21.11 also port/scripts/remap-client.sh)
  python3 patcher/tools/make_source_patches.py 26.2 26.3 1.21.11 [--decompiler <jar>]

For each version it asks the patcher to decompile the classes listed in
patcher/source-patches.json (the code path `prepare` uses), compares each
decompiled class with the Gaius source in this tree, and writes an edit
script that holds only the lines Gaius wrote. A line of the Gaius source that
the user's own decompiled class already has, moved or re-indented, becomes a
copy by line number, never text. The tool refuses a script that would still
publish official code: a line it has to carry as text although it appears in
the decompiled class of a version it processed (usually a line only another
version has, which means this version cannot produce the file: drop it from
the entry's "versions"). A line reviewed as not official code (a declaration
Gaius has to repeat, say) is accepted by adding the SHA-256 of its stripped
text to the entry's "reviewedLines".

  --pin-decompiler <jar>  writes the jar's SHA-256 into source-patches.json
  --check                 writes nothing; fails when a script is missing or stale
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAGIC = "gaius-source-patch 2"
READABLE = {"gaius-source-patch 1", MAGIC}
# Lines this short, and import or package lines, carry no expression of their own.
TRIVIAL_LENGTH = 24
INDENT = re.compile(r"^(?: *|\t*)$")


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def body(line: str) -> str:
    return line.lstrip(" \t")


def indent_spec(indent: str) -> str | None:
    """'8' for eight spaces, 't2' for two tabs; None for a mixed indent."""
    if not INDENT.match(indent):
        return None
    return f"t{len(indent)}" if indent.startswith("\t") else str(len(indent))


class Ops:
    """Turns result lines into patch lines: a copy of a base line where one fits, else the literal text."""

    def __init__(self, base_lines: list[str]):
        self.exact: dict[str, int] = {}
        self.by_body: dict[str, int] = {}
        for index, line in enumerate(base_lines):
            self.exact.setdefault(line, index)
            self.by_body.setdefault(body(line), index)

    def line(self, text: str) -> tuple[str, bool]:
        """(patch line, whether it carries the text itself)."""
        if text in self.exact:
            return f"={self.exact[text]} 1", False
        source = self.by_body.get(body(text))
        if source is not None and body(text):
            spec = indent_spec(text[: len(text) - len(body(text))])
            if spec is not None:
                return f"~{source} {spec}", False
        return "+" + text, True


def merge_copies(lines: list[str]) -> list[str]:
    """Joins =i 1, =i+1 1, ... into one =i n."""
    merged: list[str] = []
    for line in lines:
        if line.startswith("=") and merged and merged[-1].startswith("="):
            start, count = (int(part) for part in merged[-1][1:].split())
            index, more = (int(part) for part in line[1:].split())
            if index == start + count:
                merged[-1] = f"={start} {count + more}"
                continue
        merged.append(line)
    return merged


def make_script(base: str, result: str) -> tuple[str, list[tuple[int, str]]]:
    """The edit script from base to result, and the lines it carries as text, with their line numbers in result.

    Lines are aligned ignoring their indent, so a line that only moved or was
    re-indented becomes a copy of the user's own decompiled line instead of
    text in the patch.
    """
    a = base.split("\n")
    b = result.split("\n")
    ops = Ops(a)
    hunks: list[list] = []  # [start, count, patch lines]
    inserted: list[tuple[int, str]] = []

    def emit(start: int, count: int, j_lines: range) -> None:
        patch_lines = []
        for j in j_lines:
            text, literal = ops.line(b[j])
            patch_lines.append(text)
            if literal:
                inserted.append((j + 1, b[j]))
        if hunks and hunks[-1][0] + hunks[-1][1] == start:
            hunks[-1][1] += count
            hunks[-1][2].extend(patch_lines)
        else:
            hunks.append([start, count, patch_lines])

    matcher = difflib.SequenceMatcher(None, [body(x) for x in a], [body(x) for x in b], autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag != "equal":
            emit(i1, i2 - i1, range(j1, j2))
            continue
        for offset in range(i2 - i1):
            if a[i1 + offset] != b[j1 + offset]:
                emit(i1 + offset, 1, range(j1 + offset, j1 + offset + 1))

    lines = [MAGIC, "base " + sha256(base), "result " + sha256(result)]
    for start, count, patch_lines in hunks:
        lines.append(f"@@ {start} {count}")
        lines.extend(merge_copies(patch_lines))
    return "\n".join(lines) + "\n", inserted


def apply_script(script: str, base: str) -> str:
    """Python twin of EditScript.apply in GaiusPatcher.java."""
    lines = script.split("\n")
    if lines[0] not in READABLE or lines[-1] != "":
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
        elif line.startswith("="):
            start, count = (int(part) for part in line[1:].split())
            if start + count > len(a):
                raise ValueError("copy past the end of the base")
            out.extend(a[start:start + count])
        elif line.startswith("~"):
            index, spec = line[1:].split()
            indent = "\t" * int(spec[1:]) if spec.startswith("t") else " " * int(spec)
            out.append(indent + body(a[int(index)]))
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
        # The Gaius sources are checked in with LF (eol=lf); a Windows checkout made before that
        # attribute may still hold CRLF, which is not the text prepare has to rebuild.
        result = target.read_bytes().decode("utf-8").replace("\r\n", "\n")
        script, inserted = make_script(base, result)
        assert apply_script(script, base) == result
        official = official_lines([text for (_, other), text in bases.items() if other == entry_id])
        leaks = leaked_lines(inserted, official, set(entry.get("reviewedLines", [])))
        kept = len(result.split("\n")) - len(inserted)
        label = f"{entry_id} for {version}"
        print(f"{label}: {len(inserted)} lines of Gaius text, {kept} lines taken from the decompiled class")
        if leaks:
            failures += 1
            for number, digest in leaks:
                print(f"  REFUSED line {number} of {entry['target']} is official code that this version's"
                      f" decompiled class cannot supply (stripped SHA-256 {digest}). If only another version has"
                      f" it, drop this version from the entry's versions; if it is not official code, review it"
                      f" into reviewedLines")
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
