#!/usr/bin/env python3
"""Run ASM BasicVerifier over classes emitted by a Gaius patch chain.

Usage:
  python port/tools/verify-patched-bytecode.py --changed-root DIR
  python port/tools/verify-patched-bytecode.py --jar PATCHED.jar --changed-root DIR

The changed-root form verifies every .class below the patch output directory.
The jar form accepts --class for one explicit class and is useful for a copied
patched client jar. The Java helper is compiled into the caller's scratch dir.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import json
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "port" / "tools" / "PatchedBytecodeVerifier.java"


def find_jar(name: str) -> pathlib.Path:
    if name == "asm-analysis" and os.environ.get("ASM_ANALYSIS_JAR"):
        candidate = pathlib.Path(os.environ["ASM_ANALYSIS_JAR"])
        if candidate.is_file():
            return candidate
    candidates = []
    m2 = os.environ.get("M2_REPO")
    if m2:
        candidates.append(pathlib.Path(m2) / "org" / "ow2" / "asm" / name / "9.8" / f"{name}-9.8.jar")
    candidates.append(
        ROOT / "port" / "work" / "tools" / "apache-maven-3.9.11" / "lib" / f"{name}-9.8.jar"
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise SystemExit(f"ASM {name} 9.8 jar not found; set M2_REPO")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--changed-root", type=pathlib.Path)
    parser.add_argument("--jar", type=pathlib.Path)
    parser.add_argument("--manifest", type=pathlib.Path)
    parser.add_argument("--class", dest="class_name")
    parser.add_argument("--scratch", type=pathlib.Path)
    args = parser.parse_args()
    if (args.changed_root is None) == (args.jar is None):
        parser.error("exactly one of --changed-root or --jar is required")
    scratch = args.scratch or ROOT / "scratch" / "tmp" / "trainfix5-bytecode"
    scratch.mkdir(parents=True, exist_ok=True)
    classes = scratch / "classes"
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir()
    cp = os.pathsep.join(str(find_jar(name)) for name in ("asm", "asm-tree", "asm-analysis"))
    subprocess.run(["javac", "--release", "21", "-cp", cp, "-d", str(classes), str(SOURCE)], check=True)
    command = ["java", "-cp", os.pathsep.join((str(classes), cp)), "dev.gaius.tools.PatchedBytecodeVerifier"]
    if args.changed_root is not None:
        command += ["--changed-root", str(args.changed_root)]
    else:
        command += ["--jar", str(args.jar)]
        if args.manifest:
            manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
            rewritten = sorted({name for patch in manifest.get("patches", [])
                                for name in patch.get("rewrites", []) if name and not name.startswith("<")})
            rewritten = [name[:-6] if name.endswith(".class") else name for name in rewritten]
            classes_file = scratch / "changed-classes.txt"
            classes_file.write_text("\n".join(rewritten) + "\n", encoding="utf-8")
            command += ["--classes-file", str(classes_file)]
        if args.class_name:
            command += ["--class", args.class_name]
    result = subprocess.run(command)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
