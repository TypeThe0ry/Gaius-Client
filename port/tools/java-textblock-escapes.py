#!/usr/bin/env python3
"""Reject Java text-block escapes that javac interprets before JavaScript sees them."""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VALID_ESCAPES = set("btnfrs'\"\\01234567xuUN\r\n")


def check_file(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8", errors="replace")
    errors: list[str] = []
    cursor = 0
    while True:
        start = source.find('"""', cursor)
        if start < 0:
            break
        end = source.find('"""', start + 3)
        if end < 0:
            errors.append(f"{path}:{source.count(chr(10), 0, start) + 1}: unterminated text block")
            break
        body = source[start + 3:end]
        offset = 0
        while offset < len(body) - 1:
            if body[offset] != "\\":
                offset += 1
                continue
            next_character = body[offset + 1]
            if next_character == "\\":
                offset += 2
                continue
            if next_character not in VALID_ESCAPES:
                line = source.count(chr(10), 0, start + 3 + offset) + 1
                errors.append(f"{path}:{line}: invalid Java text-block escape {body[offset:offset + 2]!r}")
            offset += 2
        cursor = end + 3
    return errors


def self_test() -> int:
    with tempfile.TemporaryDirectory(prefix="gaius-textblock-") as directory:
        root = Path(directory)
        (root / "Fixture.java").write_text(
            'class Fixture { String ok = """\\s"""; String bad = """\\d"""; }',
            encoding="utf-8")
        errors = check_file(root / "Fixture.java")
        if len(errors) != 1 or "\\d" not in errors[0]:
            print("java-textblock-escapes: self-test failed", file=sys.stderr)
            return 1
    print("java-textblock-escapes: self-test passed")
    return 0


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    roots = [Path(arg).resolve() for arg in sys.argv[1:]] or [ROOT]
    files = [path for root in roots for path in (root.rglob("*.java") if root.is_dir() else [root])]
    errors = [error for path in files for error in check_file(path)]
    for error in errors:
        print(error, file=sys.stderr)
    print(f"java-textblock-escapes: checked {len(files)} Java files, failed {len(errors)}")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
