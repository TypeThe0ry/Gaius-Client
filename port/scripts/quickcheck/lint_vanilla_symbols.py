#!/usr/bin/env python3
"""P2 lint: the core layers reference no vanilla symbol (D14 P2, D16).

  lint_vanilla_symbols.py [--root-dir DIR]... [--classes DIR]... [--json F] [--enforce] [--self-test]

A vanilla symbol is a name in net.minecraft or com.mojang (the Minecraft client, blaze3d
and the Mojang libraries: authlib, brigadier, datafixers, serialization, logging).  The
core layers must work without them so that a new Minecraft version does not reach them.
The default roots are port/gpu and port/core; a root that does not exist yet is listed and
skipped.  Sources are scanned as text (comments excluded for Java, Rust, JS and WGSL); with
--classes the constant pools of compiled classes are scanned too (the D14 enforcement).
Zero findings are allowed.

Warn mode (M1): findings are printed (GitHub warning annotations on Actions) and the exit
status is 0.  --enforce exits 1 on any finding.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import struct
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javasrc  # noqa: E402

ROOT = javasrc.ROOT
DEFAULT_ROOTS = ("port/gpu", "port/core")
SYMBOL = re.compile(r"\b(?:net[./]minecraft|com[./]mojang)(?:[./][\w$]+)+")
C_LIKE = (".java", ".rs", ".js", ".mjs", ".ts", ".wgsl", ".kt", ".c", ".h", ".cpp")
TEXT = C_LIKE + (".py", ".sh", ".toml", ".txt", ".glsl", ".vsh", ".fsh", ".json", ".md", ".xml")
SKIP_DIRS = {"target", "node_modules", "build", "dist", ".git"}


def scan_sources(root_dir: Path, repo: Path) -> list[dict]:
    findings = []
    for dirpath, dirnames, filenames in os.walk(root_dir):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = Path(dirpath) / name
            if not name.endswith(TEXT):
                continue
            text = javasrc.read_text(path)
            if name.endswith(C_LIKE):
                text = javasrc.strip_comments(text)
            for match in SYMBOL.finditer(text):
                findings.append({"where": f"{javasrc.rel(path, repo)}:{javasrc.line_of(text, match.start())}",
                                 "symbol": match.group(0).replace("/", ".")})
    return findings


def class_constants(data: bytes) -> list[str]:
    """UTF-8 constants of a class file (class names, descriptors and strings)."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return []
    count = struct.unpack_from(">H", data, 8)[0]
    offset = 10
    index = 1
    values = []
    while index < count:
        tag = data[offset]
        if tag == 1:
            length = struct.unpack_from(">H", data, offset + 1)[0]
            values.append(data[offset + 3:offset + 3 + length].decode("utf-8", "replace"))
            offset += 3 + length
        elif tag in (3, 4):
            offset += 5
        elif tag in (5, 6):
            offset += 9
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            offset += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            offset += 5
        elif tag == 15:
            offset += 4
        else:
            raise ValueError(f"bad constant pool tag {tag}")
        index += 1
    return values


def scan_classes(classes_dir: Path, repo: Path) -> list[dict]:
    findings = []
    for path in sorted(classes_dir.rglob("*.class")):
        for value in class_constants(path.read_bytes()):
            for match in SYMBOL.finditer(value):
                findings.append({"where": javasrc.rel(path, repo), "symbol": match.group(0).replace("/", ".")})
    return findings


def run(repo: Path, roots: list[str], classes: list[str]) -> dict:
    result = {"roots": [], "findings": []}
    for root_name in roots:
        root_dir = (repo / root_name) if not Path(root_name).is_absolute() else Path(root_name)
        if not root_dir.is_dir():
            result["roots"].append({"root": root_name, "present": False})
            continue
        found = scan_sources(root_dir, repo)
        result["roots"].append({"root": root_name, "present": True, "findings": len(found)})
        result["findings"].extend(found)
    for classes_dir in classes:
        class_path = Path(classes_dir)
        present = class_path.is_dir() and any(class_path.rglob("*.class"))
        found = scan_classes(class_path, repo) if present else []
        result["roots"].append({"root": classes_dir, "present": present, "classes": True, "findings": len(found)})
        result["findings"].extend(found)
    return result


def report(result: dict, enforce: bool) -> None:
    mode = "enforce" if enforce else "warn"
    roots = ", ".join(f"{r['root']}" + ("" if r["present"] else " (not present)") for r in result["roots"])
    print(f"[lint P2 {mode}] vanilla symbols in core layers: {len(result['findings'])} (roots: {roots})")
    annotate = os.environ.get("GITHUB_ACTIONS") == "true"
    for index, finding in enumerate(result["findings"][:200]):
        print(f"  {finding['where']}: {finding['symbol']}")
        if annotate and index < 10 and ":" in finding["where"]:
            path, line = finding["where"].rsplit(":", 1)
            print(f"::{'error' if enforce else 'warning'} file={path},line={line}::P2 vanilla symbol "
                  f"{finding['symbol']} in a core layer")


def self_test() -> int:
    failures = []
    with tempfile.TemporaryDirectory(prefix="gaius-p2-") as scratch_name:
        repo = Path(scratch_name)
        gpu = repo / "port/gpu/src/dev/gaius/gpu"
        gpu.mkdir(parents=True)
        (gpu / "Clean.java").write_text("package dev.gaius.gpu;\n// net.minecraft.client in a comment\n"
                                        "final class Clean {}\n", encoding="utf-8")
        result = run(repo, ["port/gpu", "port/core"], [])
        if result["findings"] or result["roots"][1]["present"]:
            failures.append(f"clean tree: {result}")
        (gpu / "Bad.java").write_text("package dev.gaius.gpu;\nimport com.mojang.blaze3d.systems.GpuDevice;\n"
                                      "final class Bad { String s = \"net/minecraft/client/Minecraft\"; }\n",
                                      encoding="utf-8")
        result = run(repo, ["port/gpu"], [])
        symbols = sorted(f["symbol"] for f in result["findings"])
        if symbols != ["com.mojang.blaze3d.systems.GpuDevice", "net.minecraft.client.Minecraft"]:
            failures.append(f"injected symbols: {symbols}")
        # A class file with a vanilla owner in its constant pool.
        name = b"net/minecraft/client/Minecraft"
        pool = b"\x01" + struct.pack(">H", len(name)) + name
        classes = repo / "classes"
        classes.mkdir()
        (classes / "X.class").write_bytes(b"\xca\xfe\xba\xbe\x00\x00\x00\x41" + struct.pack(">H", 2) + pool)
        result = run(repo, [], [str(classes)])
        if [f["symbol"] for f in result["findings"]] != ["net.minecraft.client.Minecraft"]:
            failures.append(f"class constant pool: {result['findings']}")
    for failure in failures:
        print(f"self-test FAILED: {failure}", file=sys.stderr)
    if not failures:
        print("lint_vanilla_symbols self-test passed")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root-dir", action="append")
    parser.add_argument("--classes", action="append", default=[])
    parser.add_argument("--json")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--repo", default=str(ROOT))
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    result = run(Path(args.repo), args.root_dir or list(DEFAULT_ROOTS), args.classes)
    report(result, args.enforce)
    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    missing_classes = any(entry.get("classes") and not entry["present"] for entry in result["roots"])
    return 1 if args.enforce and (result["findings"] or missing_classes) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
