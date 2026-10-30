#!/usr/bin/env python3
"""Build the browser's deterministic, externally loaded vanilla resource pack."""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import struct
import sys
import zipfile
from pathlib import Path


MAGIC = b"GAIUSVP1"
REQUIRED_RESOURCES = {
    "assets/minecraft/atlases/blocks.json",
    "assets/minecraft/atlases/gui.json",
    "assets/minecraft/atlases/items.json",
    "assets/minecraft/font/include/unifont.json",
    "assets/minecraft/font/unifont.zip",
    "assets/minecraft/lang/en_us.json",
    "assets/minecraft/lang/zh_cn.json",
    "assets/minecraft/lang/es_es.json",
    "assets/gaius/lang/en_us.json",
    "assets/gaius/lang/zh_cn.json",
    "assets/gaius/lang/es_es.json",
    "assets/minecraft/sounds.json",
    "assets/minecraft/sounds/random/eat1.ogg",
    "assets/minecraft/textures/block/stone.png",
    "assets/minecraft/textures/item/diamond.png",
    "data/minecraft/datapacks/minecart_improvements/pack.mcmeta",
    "pack.png",
}


def is_external_resource(name: str) -> bool:
    return name == "pack.png" or name.startswith(("assets/", "data/"))


def checked_resource_names(resource_list: Path) -> list[str]:
    names = sorted({line.strip() for line in resource_list.read_text(encoding="utf-8").splitlines()
                    if line.strip() and is_external_resource(line.strip())})
    if not names:
        raise RuntimeError("vanilla resource list did not contain external resources")
    for name in names:
        path = Path(name)
        if name.startswith("/") or path.is_absolute() or ".." in path.parts:
            raise RuntimeError(f"unsafe vanilla resource path: {name}")
    missing_required = sorted(REQUIRED_RESOURCES.difference(names))
    if missing_required:
        raise RuntimeError("vanilla resource list is missing required entries: "
                           + ", ".join(missing_required))
    return names


def build(resource_list: Path, client_jar: Path, generated_resources: Path, output: Path,
          asset_index: Path | None = None) -> None:
    names = checked_resource_names(resource_list)
    indexed_backgrounds = {}
    if asset_index is not None:
        objects = json.loads(asset_index.read_text(encoding="utf-8"))["objects"]
        indexed_backgrounds = {
            "assets/" + name: entry for name, entry in objects.items()
            if name.startswith("minecraft/textures/gui/title/background/")
        }
    payload = bytearray()
    index: dict[str, list[int]] = {}
    from_jar = 0
    from_generated = 0
    missing: list[str] = []

    with zipfile.ZipFile(client_jar) as client:
        jar_names = set(client.namelist())
        for name in names:
            if name in indexed_backgrounds:
                entry = indexed_backgrounds[name]
                digest = entry["hash"]
                source = asset_index.parent.parent / "objects" / digest[:2] / digest
                content = source.read_bytes()
                if len(content) != entry["size"] or hashlib.sha1(content).hexdigest() != digest:
                    raise RuntimeError(f"invalid indexed background asset: {name}")
                from_generated += 1
            elif name in jar_names:
                content = client.read(name)
                from_jar += 1
            else:
                generated = generated_resources / name
                if not generated.is_file():
                    missing.append(name)
                    continue
                content = generated.read_bytes()
                from_generated += 1
            index[name] = [len(payload), len(content)]
            payload.extend(content)

    if missing:
        preview = ", ".join(missing[:12])
        suffix = "" if len(missing) <= 12 else f" (+{len(missing) - 12} more)"
        raise RuntimeError(f"could not resolve vanilla resources: {preview}{suffix}")
    if set(index) != set(names):
        raise RuntimeError("vanilla pack index does not match the resource list")

    index_bytes = json.dumps(index, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    if len(index_bytes) > 0xFFFFFFFF:
        raise RuntimeError("vanilla pack index is too large")
    pack = MAGIC + struct.pack("<I", len(index_bytes)) + index_bytes + payload

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".part")
    try:
        with temporary.open("wb") as raw:
            with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as compressed:
                compressed.write(pack)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)

    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(
        "Vanilla asset pack: "
        f"{output} ({len(index)} resources, {len(payload)} payload bytes, "
        f"{output.stat().st_size} gzip bytes, jar={from_jar}, generated={from_generated}, "
        f"sha256={digest})"
    )


def main(argv: list[str]) -> int:
    if len(argv) not in (5, 6):
        print(
            "usage: build-vanilla-assets-pack.py "
            "<resource-list> <client-jar> <generated-resources> <output.pack.gz> [asset-index]",
            file=sys.stderr,
        )
        return 2
    build(*(Path(argument).resolve() for argument in argv[1:]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
