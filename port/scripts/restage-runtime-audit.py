#!/usr/bin/env python3
"""Verify actual portable bytes and compare runtime-only restaging arms.

Usage: restage-runtime-audit.py OUTPUT_ROOT ORIGINAL_TRAIN [--report PATH]
Checks embedded payload lengths/hashes, includes inline runtime modules in the
payload table, and requires A/B2 to differ in exactly the two p1gate modules.
"""
from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
from pathlib import Path
import re


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def portable(path: Path) -> dict:
    html = path.read_text(encoding="utf-8")
    marker = "window.__gaiusFilePayloads="
    index, _ = json.JSONDecoder().raw_decode(html[html.index(marker) + len(marker):])
    parts: dict[str, list[str]] = {}
    for name, number, body in re.findall(
        r'<script type="text/x-gaius-b64" data-gaius-asset="([^"]+)" data-part="(\d+)">([^<]*)</script>', html
    ):
        values = parts.setdefault(name, [])
        if int(number) != len(values):
            raise ValueError(f"{path}: unordered payload parts: {name}")
        values.append(body)
    if set(parts) != set(index):
        raise ValueError(f"{path}: index and actual payload names differ")
    rows = {}
    for name, entry in index.items():
        data = base64.b64decode("".join(parts[name]), validate=True)
        if len(data) != entry["bytes"] or sha(data) != entry["sha256"]:
            raise ValueError(f"{path}: payload integrity mismatch: {name}")
        inflated = gzip.decompress(data) if data.startswith(b"\x1f\x8b") else data
        rows[name] = {"kind": "file", "bytes": len(data), "sha256": sha(data),
                      "inflatedBytes": len(inflated), "inflatedSha256": sha(inflated)}
    for name, body in re.findall(r'<script data-gaius-boot-module="([^"]+)">\n(.*?)</script>', html, re.S):
        data = body.encode("utf-8")
        key = "inline:" + name
        if key in rows:
            raise ValueError(f"{path}: duplicate inline module: {name}")
        rows[key] = {"kind": "inline", "bytes": len(data), "sha256": sha(data),
                     "inflatedBytes": len(data), "inflatedSha256": sha(data)}
    for name, body in re.findall(r'<script[^>]*data-gaius-wasm-script="([^"]+)"[^>]*>(.*?)</script>', html, re.S):
        data = body.encode("utf-8")
        rows["wasm-script:" + name] = {"kind": "wasm-script", "bytes": len(data), "sha256": sha(data),
                                     "inflatedBytes": len(data), "inflatedSha256": sha(data)}
    for number, body in enumerate(re.findall(r'<script data-gaius-boot="v1">(.*?)</script>', html, re.S)):
        data = body.encode("utf-8")
        rows[f"boot:{number}"] = {"kind": "boot", "bytes": len(data), "sha256": sha(data),
                                   "inflatedBytes": len(data), "inflatedSha256": sha(data)}
    return {"file": str(path), "bytes": path.stat().st_size, "sha256": sha(path.read_bytes()), "payloads": rows}


def differences(left: dict, right: dict) -> list[str]:
    return sorted(k for k in left.keys() | right.keys()
                  if left.get(k, {}).get("sha256") != right.get(k, {}).get("sha256"))


def site(path: Path) -> dict:
    manifest = json.loads((path / "gaius-site.json").read_text())
    return {name: {"sha256": sha((path / physical).read_bytes()),
                   "bytes": (path / physical).stat().st_size}
            for name, physical in manifest["assets"].items()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("original_train", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    labels = ["A", "B2", "V0", "V1", "V2", "V3", "V4"]
    result = {"artifacts": {}, "comparisons": {}, "sites": {}}
    original = args.original_train / "staged/portable-26.3/Gaius-26.3.html"
    result["artifacts"]["original"] = portable(original)
    for label in labels:
        path = args.output / f"Gaius-26.3-{label}.html"
        if path.is_file():
            result["artifacts"][label] = portable(path)
    artifacts = result["artifacts"]
    for left, right in [("original", "V0"), ("A", "B2"), ("V0", "V1"),
                        ("V1", "V2"), ("V2", "V3"), ("V3", "V4"), ("A", "V4")]:
        if left in artifacts and right in artifacts:
            result["comparisons"][f"{left}..{right}"] = differences(
                artifacts[left]["payloads"], artifacts[right]["payloads"])
    original_site = args.original_train / "staged/site-26.3"
    if original_site.is_dir():
        baseline = site(original_site)
        for label in labels:
            candidates = [args.output / f"staged-{label}/site-26.3",
                          args.output / label / "staged/site-26.3"]
            candidate = next((p for p in candidates if p.is_dir()), None)
            if candidate:
                table = site(candidate)
                result["sites"][label] = {"assets": table, "diffFromOriginal": differences(baseline, table)}
        for left, right in [("A", "B2"), ("V0", "V1"), ("V1", "V2"),
                            ("V2", "V3"), ("V3", "V4")]:
            if left in result["sites"] and right in result["sites"]:
                result["comparisons"][f"site:{left}..{right}"] = differences(
                    result["sites"][left]["assets"], result["sites"][right]["assets"])
    required = ["gaius-jspi.js", "inline:runtime/webgpu/gpu-glue.js", "wasm-script:gaius-jspi.js"]
    ab = result["comparisons"].get("A..B2")
    result["abExpectedDifferences"] = required
    site_ab = result["comparisons"].get("site:A..B2")
    result["abPass"] = ab == required and (site_ab is None or site_ab ==
        ["gaius-jspi.js", "runtime/webgpu/gpu-glue.js"])
    report = args.report or args.output / "PAYLOAD-AUDIT.json"
    report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"report": str(report), "comparisons": result["comparisons"],
                      "abPass": result["abPass"]}, indent=2))
    if ab is not None and not result["abPass"]:
        raise SystemExit("A/B2 differ outside (or omit) the two required p1gate modules")


if __name__ == "__main__":
    main()
