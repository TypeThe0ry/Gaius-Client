#!/usr/bin/env python3
"""Normalize ZIP metadata for disposable TeaVM overlay JARs."""

import os
import stat
import sys
import tempfile
import zipfile
from pathlib import Path


FIXED_TIME = (1980, 1, 1, 0, 0, 0)
CHUNK_SIZE = 1024 * 1024


def normalize_jar(path: Path) -> None:
    source_stat = path.stat()
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    os.close(fd)
    temporary = Path(temporary_name)
    try:
        with zipfile.ZipFile(path, "r") as source, zipfile.ZipFile(
            temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
        ) as target:
            target.comment = source.comment
            for original in source.infolist():
                entry = zipfile.ZipInfo(original.filename, FIXED_TIME)
                entry.compress_type = original.compress_type
                entry.create_system = original.create_system
                entry.external_attr = original.external_attr
                entry.internal_attr = original.internal_attr
                entry.comment = original.comment
                entry.extra = b""
                with source.open(original, "r") as input_stream, target.open(
                    entry, "w", force_zip64=True
                ) as output_stream:
                    while chunk := input_stream.read(CHUNK_SIZE):
                        output_stream.write(chunk)
        os.chmod(temporary, stat.S_IMODE(source_stat.st_mode))
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def normalize_tree(root: Path) -> int:
    jars = sorted(path for path in root.rglob("*.jar") if path.is_file())
    for jar in jars:
        normalize_jar(jar)
    return len(jars)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: normalize-overlay-jars.py <overlay-directory>", file=sys.stderr)
        return 2
    root = Path(argv[1])
    if not root.is_dir():
        print(f"overlay directory not found: {root}", file=sys.stderr)
        return 2
    count = normalize_tree(root)
    print(f"Normalized ZIP timestamps in {count} overlay JARs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
