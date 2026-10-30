#!/usr/bin/env python3
"""Audit Anvil region files without decoding NBT.

Reports header locations, sector bounds, chunk payload lengths, compression ids,
overlaps, and file-size/sector alignment.  Exit status is non-zero if any
structural anomaly is found.  The input may be one .mca file, a region
directory, or a directory tree containing .mca files.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import struct
import sys
from collections import defaultdict

SECTOR = 4096
HEADER = 2 * SECTOR


def files_for(path: pathlib.Path) -> list[pathlib.Path]:
    if path.is_file():
        return [path] if path.suffix == ".mca" else []
    if path.is_dir():
        return sorted(path.rglob("*.mca"))
    raise FileNotFoundError(path)


def region_coords(path: pathlib.Path) -> tuple[int | None, int | None]:
    try:
        _, rx, rz, ext = path.name.split(".")
        if ext != "mca":
            raise ValueError
        return int(rx), int(rz)
    except ValueError:
        return None, None


def audit(path: pathlib.Path) -> dict:
    raw = path.read_bytes()
    size = len(raw)
    rx, rz = region_coords(path)
    anomalies: list[dict] = []
    entries: list[dict] = []
    occupied: dict[int, list[int]] = defaultdict(list)
    if size < HEADER:
        anomalies.append({"kind": "truncated_header", "bytes": size})
        return {"file": str(path), "bytes": size,
                "sectorRemainder": size % SECTOR, "sha256": hashlib.sha256(raw).hexdigest(),
                "region": {"x": rx, "z": rz}, "entries": entries,
                "anomalies": anomalies}

    if size % SECTOR:
        anomalies.append({"kind": "file_not_sector_aligned", "remainder": size % SECTOR})

    for index in range(1024):
        packed = struct.unpack_from(">I", raw, index * 4)[0]
        offset, sectors = packed >> 8, packed & 0xFF
        if not packed:
            continue
        local_x, local_z = index % 32, index // 32
        entry = {"index": index, "local": {"x": local_x, "z": local_z},
                 "chunk": ({"x": rx * 32 + local_x, "z": rz * 32 + local_z}
                            if rx is not None and rz is not None else None),
                 "offset": offset, "sectors": sectors,
                 "start": offset * SECTOR, "end": (offset + sectors) * SECTOR}
        entries.append(entry)
        if offset < 2:
            anomalies.append({"kind": "location_in_header", "index": index,
                              "offset": offset, "sectors": sectors})
        if sectors == 0:
            anomalies.append({"kind": "zero_sector_count", "index": index, "offset": offset})
        for sector in range(offset, offset + sectors):
            occupied[sector].append(index)
        end = (offset + sectors) * SECTOR
        if end > size:
            anomalies.append({"kind": "location_out_of_range", "index": index,
                              "offset": offset, "sectors": sectors,
                              "requiredEnd": end, "fileBytes": size,
                              "missingBytes": end - size})
        start = offset * SECTOR
        if start + 5 > size:
            anomalies.append({"kind": "chunk_header_truncated", "index": index,
                              "offset": offset, "sectors": sectors})
            continue
        length = struct.unpack_from(">I", raw, start)[0]
        compression = raw[start + 4]
        entry.update({"length": length, "compression": compression,
                      "allocatedBytes": sectors * SECTOR,
                      "payloadBytesAvailable": max(0, min(size, end) - (start + 5))})
        if length < 1:
            anomalies.append({"kind": "payload_length_too_small", "index": index,
                              "length": length})
        if length + 4 > sectors * SECTOR:
            anomalies.append({"kind": "payload_length_exceeds_allocation", "index": index,
                              "length": length, "sectors": sectors,
                              "allocatedBytes": sectors * SECTOR})
        # A valid chunk record has 4-byte length + 1-byte compression + payload.
        if start + 4 + length > size:
            anomalies.append({"kind": "payload_out_of_file", "index": index,
                              "length": length, "requiredEnd": start + 4 + length,
                              "fileBytes": size})
        elif start + 4 + length > end:
            anomalies.append({"kind": "payload_out_of_sector_allocation", "index": index,
                              "length": length, "requiredEnd": start + 4 + length,
                              "allocationEnd": end})
        if compression not in (1, 2, 3):
            anomalies.append({"kind": "unknown_compression", "index": index,
                              "compression": compression})
        if end > size and start + 4 + length <= size and start + 4 + length <= end:
            # RegionFile writes the record first and may defer zero-padding the
            # final sector until close().  This is distinct from a short record.
            anomalies.append({"kind": "record_complete_but_unpadded", "index": index,
                              "length": length, "compression": compression,
                              "recordEnd": start + 4 + length, "fileBytes": size,
                              "missingBytes": end - size})

    for sector, indexes in sorted(occupied.items()):
        if len(indexes) > 1:
            anomalies.append({"kind": "overlapping_entries", "sector": sector,
                              "indexes": indexes})
    return {"file": str(path), "bytes": size, "sectorRemainder": size % SECTOR,
            "sha256": hashlib.sha256(raw).hexdigest(), "region": {"x": rx, "z": rz},
            "entries": entries, "anomalies": anomalies}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=pathlib.Path)
    parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args()
    paths = files_for(args.path)
    if not paths:
        parser.error("no .mca files found")
    reports = [audit(p) for p in paths]
    result = {"schema": 1, "files": reports,
              "summary": {"files": len(reports),
                          "anomalousFiles": sum(bool(r["anomalies"]) for r in reports),
                          "anomalies": sum(len(r["anomalies"]) for r in reports),
                          "entries": sum(len(r["entries"]) for r in reports)}}
    print(json.dumps(result, indent=2 if args.pretty else None, ensure_ascii=False))
    return 1 if result["summary"]["anomalies"] else 0


if __name__ == "__main__":
    sys.exit(main())
