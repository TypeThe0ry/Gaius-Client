#!/usr/bin/env python3
"""Verify the launcher's original icon assets and their recorded provenance offline."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
PNG_SIZES = {
    "favicon-16.png": 16, "favicon-32.png": 32, "apple-touch-icon.png": 180,
    "icon-192.png": 192, "icon-512.png": 512, "icon-maskable-512.png": 512,
    "home-1024.png": 1024,
}
REQUIRED = (*PNG_SIZES, "favicon.ico", "icon.svg")


def check_assets(root: Path = ROOT) -> int:
    directory = root / "port/web/launcher/icons"
    sources = (directory / "SOURCES.md").read_text(encoding="utf-8")
    if "original artwork by the project owner" not in sources:
        raise ValueError("icon provenance must credit the project owner")
    records = dict(re.findall(
        r"\|\s*`([^`]+)`\s*\|\s*https://gaius\.fun/[^\s|]+\s*\|\s*`([0-9a-f]{64})`\s*\|",
        sources))
    total = 0
    for name in REQUIRED:
        data = (directory / name).read_bytes()
        total += len(data)
        if records.get(name) != hashlib.sha256(data).hexdigest():
            raise ValueError(f"missing or mismatched source SHA-256: {name}")
        if name in PNG_SIZES:
            if len(data) < 33 or data[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR":
                raise ValueError(f"invalid PNG header: {name}")
            size = PNG_SIZES[name]
            if struct.unpack(">II", data[16:24]) != (size, size):
                raise ValueError(f"incorrect PNG dimensions: {name}")
        elif name.endswith(".ico"):
            if len(data) < 6 or data[:4] != b"\x00\x00\x01\x00":
                raise ValueError("invalid ICO header")
            count = struct.unpack_from("<H", data, 4)[0]
            if len(data) < 6 + count * 16:
                raise ValueError("truncated ICO directory")
            sizes = set()
            for offset in range(6, 6 + count * 16, 16):
                width, height = data[offset:offset + 2]
                length, start = struct.unpack_from("<II", data, offset + 8)
                if not length or start < 6 + count * 16 or start + length > len(data):
                    raise ValueError("truncated ICO image")
                sizes.add((width or 256, height or 256))
            if sizes != {(16, 16), (32, 32), (48, 48)}:
                raise ValueError("ICO must contain the 16/32/48 images")
        elif ET.fromstring(data).tag != "{http://www.w3.org/2000/svg}svg":
            raise ValueError("invalid SVG root")
    if total >= 1_000_000:
        raise ValueError(f"icon assets exceed the repository budget: {total} bytes")
    return total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        total = check_assets(args.root)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"launcher assets: FAIL: {error}", file=sys.stderr)
        return 1
    print(f"launcher assets: PASS ({len(REQUIRED)} verified icons, {total} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
