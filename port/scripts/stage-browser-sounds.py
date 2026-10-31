#!/usr/bin/env python3
"""Stage browser sound assets from the Minecraft content-addressed cache.

The production build invokes this helper once.  It keeps
manifest order and missing-file behavior while batching directory creation
and file copies in one Python process.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys


def safe_logical_path(value: str) -> str:
    raw = value.strip()
    # Reject Windows drive and UNC/device forms before slash normalization.
    # PurePosixPath alone treats `C:\\x` as a relative POSIX path.
    if re.match(r"^[A-Za-z]:", raw) or raw.startswith(("\\\\", "//")):
        raise ValueError(f"unsafe logical asset path: {value!r}")
    path = raw.replace("\\", "/")
    parsed = PurePosixPath(path)
    if "\x00" in raw or not path or parsed.is_absolute() or ".." in parsed.parts:
        raise ValueError(f"unsafe logical asset path: {value!r}")
    if path.startswith("./") or "//" in path:
        raise ValueError(f"non-canonical logical asset path: {value!r}")
    return path


def sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_digest(value: str, line_number: int) -> str:
    digest = value.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{40}", digest):
        raise ValueError(f"invalid SHA-1 digest on manifest line {line_number}: {value!r}")
    return digest


def stage(manifest: Path, objects: Path, output: Path, resource_list: Path,
          sound_names: Path | None = None, missing: str = "warn",
          verify_sha1: bool = False) -> dict[str, int]:
    entries: list[tuple[str, str]] = []
    with manifest.open("r", encoding="utf-8", newline="") as stream:
        for line_number, raw in enumerate(stream, 1):
            line = raw.rstrip("\r\n")
            if not line:
                continue
            fields = line.split("\t")
            if len(fields) != 2 or not fields[0] or not fields[1]:
                raise ValueError(f"invalid manifest line {line_number}: {line!r}")
            entries.append((safe_logical_path(fields[0]),
                            validate_digest(fields[1], line_number)))

    parents = {output / Path(logical).parent for logical, _ in entries}
    for parent in sorted(parents, key=lambda item: (len(item.parts), str(item))):
        parent.mkdir(parents=True, exist_ok=True)

    copied = missing_count = 0
    copied_sound_names: list[str] = []
    with resource_list.open("a", encoding="utf-8", newline="") as resources:
        for logical, digest in entries:
            source = objects / digest[:2] / digest
            target = output / logical
            if not source.is_file():
                missing_count += 1
                message = f"missing asset object for {logical} ({digest})"
                if missing == "error":
                    raise FileNotFoundError(message)
                print(f"WARNING: {message}", file=sys.stderr)
                continue
            if verify_sha1 and sha1(source) != digest:
                raise ValueError(f"SHA-1 mismatch for {logical} ({digest})")
            # Parent directories were batched above; this guard handles a
            # manifest containing a file at the output root without adding a
            # per-file mkdir subprocess.
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            resources.write(f"assets/{logical}\n")
            if logical.startswith("minecraft/sounds/") and logical.endswith(".ogg"):
                copied_sound_names.append(logical[len("minecraft/sounds/"):-4])
            copied += 1
    if sound_names is not None:
        sound_names.parent.mkdir(parents=True, exist_ok=True)
        sound_names.write_text(json.dumps(copied_sound_names, ensure_ascii=False,
                                          indent=2) + "\n", encoding="utf-8")
    return {"manifestEntries": len(entries), "copied": copied, "copiedSoundNames": len(copied_sound_names),
            "missing": missing_count, "parentDirectories": len(parents)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--objects", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resource-list", type=Path, required=True)
    parser.add_argument("--sound-names", type=Path)
    parser.add_argument("--missing", choices=("warn", "error"), default="warn")
    parser.add_argument("--verify-sha1", action="store_true")
    args = parser.parse_args()
    print(json.dumps(stage(args.manifest, args.objects, args.output, args.resource_list,
                           args.sound_names, args.missing, args.verify_sha1),
                     separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
