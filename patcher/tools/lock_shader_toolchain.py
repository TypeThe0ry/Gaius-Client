#!/usr/bin/env python3
"""Refresh the shader pins in toolchain.lock from a verified builder output."""

import argparse
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "port/wasm/shader-toolchain"
LOCK = ROOT / "patcher/toolchain.lock"
MANIFEST = "gaius-shader-toolchain.json"
LOADER = "gaius-shader-toolchain.js"
FILES = (LOADER, "gaius-shaderc.js", "gaius-shaderc.wasm", "gaius-spvc.js", "gaius-spvc.wasm")
SOURCES = ("shaderc", "glslang", "spirv-tools", "spirv-headers", "spirv-cross")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def shader_pins(directory: Path) -> dict:
    pins_data = (SOURCE / "pins.env").read_bytes()
    pins = dict(re.findall(r"^(GAIUS_ST_\w+)=([^\s]+)$", pins_data.decode("utf-8"), re.M))
    version = pins["GAIUS_ST_EMSCRIPTEN_VERSION"]
    commit = pins["GAIUS_ST_EMSCRIPTEN_COMMIT"]
    manifest_data = (directory / MANIFEST).read_bytes()
    manifest = json.loads(manifest_data)
    sources = {name: pins[f"GAIUS_ST_{name.upper().replace('-', '_')}_COMMIT"] for name in SOURCES}
    if manifest.get("schema") != 1 or manifest.get("sources") != sources:
        raise ValueError("manifest schema or source pins do not match pins.env")
    if manifest.get("emscripten") != f"{version} ({commit})":
        raise ValueError("manifest Emscripten pin does not match pins.env")
    if set(manifest["files"]) != set(FILES):
        raise ValueError("manifest does not list exactly the five shader toolchain files")
    hashes = {}
    for name in FILES:
        data = (directory / name).read_bytes()
        digest = sha256(data)
        if manifest["files"][name] != {"bytes": len(data), "sha256": digest}:
            raise ValueError(f"{name} does not match its manifest size and SHA-256")
        if name == LOADER and data != (SOURCE / "loader" / LOADER).read_bytes():
            raise ValueError(f"{name} does not match the loader in this checkout")
        hashes[name] = digest
    return {"emscripten": version, "emscriptenCommit": commit, "pinsSha256": sha256(pins_data),
            "manifest": MANIFEST, "manifestSha256": sha256(manifest_data), "files": hashes}


def pack_archive(directory: Path, shader: dict) -> bytes:
    buffer = io.BytesIO()
    hashes = {MANIFEST: shader["manifestSha256"], **shader["files"]}
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, expected in hashes.items():
            data = (directory / name).read_bytes()
            if sha256(data) != expected:
                raise ValueError(f"{name} changed after manifest verification")
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    return buffer.getvalue()


def refresh_lock(text: str, shader: dict, archive: dict | None = None) -> str:
    lock = json.loads(text)
    if lock.get("schema") != 1:
        raise ValueError("unsupported toolchain.lock schema")
    previous = lock["wasm"]["shader"]
    if "prebuiltArchive" in previous and archive is None:
        raise ValueError("--archive is required to refresh the pinned shader ZIP")
    if archive is not None:
        shader = {**shader, "prebuiltArchive": archive}
    distribution = previous.get("distribution")
    if distribution is not None and distribution != {
        "status": "planned", "manifestUrl": None, "manifestSha256": None,
    }:
        raise ValueError("cannot refresh a lock with active distribution metadata")
    if "sdk" in previous:
        for key in ("emscripten", "emscriptenCommit"):
            if previous["sdk"][key] != shader[key]:
                raise ValueError("SDK metadata must be refreshed for the new Emscripten pin")
    shader = {**previous, **shader}
    # Replace only the parsed shader object, keeping other lock sections byte-for-byte.
    entries = list(re.finditer(r'^    "shader": ', text, re.M))
    if len(entries) != 1:
        raise ValueError("toolchain.lock must contain one shader block")
    start = entries[0].end()
    _, end = json.JSONDecoder().raw_decode(text, start)
    encoded = json.dumps(shader, indent=2).replace("\n", "\n    ")
    updated = text[:start] + encoded + text[end:]
    lock["wasm"]["shader"] = shader
    if json.loads(updated) != lock:
        raise ValueError("shader block does not match wasm.shader in toolchain.lock")
    return updated


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="output of build-wasm-shader-toolchain.sh")
    parser.add_argument("--archive", type=Path, help="write the deterministic ZIP to this path")
    args = parser.parse_args()
    try:
        text = LOCK.read_bytes().decode("utf-8")
        shader = shader_pins(args.directory)
        data = None
        pin = None
        if args.archive is not None:
            if args.archive.suffix != ".zip":
                raise ValueError("archive output must have a .zip extension")
            data = pack_archive(args.directory, shader)
            pin = {"file": args.archive.name, "bytes": len(data), "sha256": sha256(data)}
        updated = refresh_lock(text, shader, pin)
        if data is not None:
            args.archive.write_bytes(data)
        if updated != text:
            LOCK.write_bytes(updated.encode("utf-8"))
    except (OSError, KeyError, TypeError, ValueError) as error:
        parser.exit(1, f"{parser.prog}: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
