#!/usr/bin/env python3
"""Build two TeaVM overlays offline from the locked classlib and Vineflower JARs."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from zipfile import ZipFile

RECIPES = Path(__file__).resolve().parent
sys.path.insert(0, str(RECIPES.parent / "tools"))
from make_source_patches import apply_script

PACKAGE = "org/teavm/classlib/java"
OVERLAYS = {
    f"{PACKAGE}/nio/TByteOrder": "teavm-byte-order.gpatch",
    f"{PACKAGE}/util/zip/TInflaterInputStream": "teavm-inflater-input-stream.gpatch",
}


def verify(path, sha256):
    with path.open("rb") as content:
        if hashlib.file_digest(content, "sha256").hexdigest() != sha256:
            raise ValueError(f"SHA-256 mismatch: {path}")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-repository", type=Path, required=True)
    parser.add_argument("--vineflower", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((RECIPES.parent / "toolchain.lock").read_text(encoding="utf-8"))
    tea = lock["teavm"]
    if args.version != tea["version"]:
        raise ValueError(f"TeaVM overlay recipe requires {tea['version']}, got {args.version}")
    relative = f"org/teavm/teavm-classlib/{args.version}/teavm-classlib-{args.version}.jar"
    classlib_pin = next(item for item in tea["artifacts"] if item["path"] == relative)
    source = verify(args.maven_repository / relative, classlib_pin["sha256"])
    vineflower = verify(args.vineflower, lock["vineflower"]["sha256"])

    with tempfile.TemporaryDirectory(prefix="teavm-classlib-recipe-") as work, ZipFile(source) as archive:
        work = Path(work)
        inputs = []
        for classname in OVERLAYS:
            entry = classname + ".class"
            local_class = work / Path(entry).name
            local_class.write_bytes(archive.read(entry))
            inputs.append(local_class)
        decompiled = work / "decompiled"
        decompiled.mkdir()
        subprocess.run(["java", "-Dfile.encoding=UTF-8", "-jar", vineflower,
                        "-ind=    ", "-log=WARN", *inputs, decompiled], check=True)
        results = {}
        for classname, patch in OVERLAYS.items():
            base = (decompiled / (Path(classname).name + ".java")).read_text(encoding="utf-8")
            script = (RECIPES / patch).read_text(encoding="utf-8")
            results[classname] = apply_script(script, base).encode("utf-8")
        for classname, data in results.items():
            target = args.out / (classname + ".java")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"TeaVM classlib recipe: {error}")
