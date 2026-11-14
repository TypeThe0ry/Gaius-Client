#!/usr/bin/env python3
"""Build the compact browser boot snapshot from a validated vanilla asset pack."""

from __future__ import annotations

import gzip
import hashlib
import json
import struct
import sys
from pathlib import Path


MAGIC = b"GAIUSIS1"
VERSION = 1
HEADER = "<HHII"
RECORD = "<HII32s"


def unpack_pack_bytes(compressed: bytes) -> tuple[dict[str, list[int]], bytes]:
    raw = gzip.decompress(compressed)
    if raw[:8] != b"GAIUSVP1" or len(raw) < 12:
        raise ValueError("vanilla asset pack has an invalid header")
    index_length = struct.unpack_from("<I", raw, 8)[0]
    data_offset = 12 + index_length
    if data_offset > len(raw):
        raise ValueError("vanilla asset pack index is truncated")
    index = json.loads(raw[12:data_offset].decode("utf-8"))
    if not isinstance(index, dict):
        raise ValueError("vanilla asset pack index is not an object")
    return index, raw[data_offset:]


def build_compressed(compressed: bytes, profile: str) -> tuple[bytes, dict[str, int | str]]:
    index, payload = unpack_pack_bytes(compressed)
    names = sorted(name for name in index if name.startswith("data/"))
    profile_bytes = profile.encode("utf-8")
    if len(profile_bytes) > 0xFFFF:
        raise ValueError("profile name is too long")
    records = bytearray()
    for name in names:
        range_value = index[name]
        if (not isinstance(range_value, list) or len(range_value) != 2
                or not all(isinstance(value, int) and value >= 0 for value in range_value)):
            raise ValueError(f"invalid vanilla asset range: {name}")
        offset, length = range_value
        if offset + length > len(payload):
            raise ValueError(f"vanilla asset range is outside payload: {name}")
        name_bytes = name.encode("utf-8")
        if len(name_bytes) > 0xFFFF:
            raise ValueError(f"resource name is too long: {name}")
        content = payload[offset:offset + length]
        records.extend(struct.pack(RECORD, len(name_bytes), offset, length,
                                   hashlib.sha256(content).digest()))
        records.extend(name_bytes)
    # The vanilla pack already owns the bytes. Keep this envelope metadata-only so the browser
    # can validate the pack without carrying a second copy of every datapack JSON document.
    header = MAGIC + struct.pack(HEADER, VERSION, len(profile_bytes), len(names), 0)
    data = header + profile_bytes + records
    return data, {"profile": profile, "entries": len(names), "payloadBytes": 0,
                  "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def build(pack: Path, profile: str, output: Path) -> dict[str, int | str]:
    data, result = build_compressed(pack.read_bytes(), profile)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    return result


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print("usage: build-boot-snapshot.py <vanilla-assets.pack.gz> <profile> <output.bin>", file=sys.stderr)
        return 2
    try:
        print(json.dumps(build(Path(argv[1]), argv[2], Path(argv[3])), sort_keys=True))
    except (OSError, ValueError, gzip.BadGzipFile, json.JSONDecodeError) as error:
        print(f"boot snapshot: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
