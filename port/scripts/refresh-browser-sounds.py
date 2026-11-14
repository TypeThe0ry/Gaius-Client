#!/usr/bin/env python3
"""Refresh a copied WasmGC resource tree with the current browser audio selection."""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import subprocess

SCRIPTS = Path(__file__).resolve().parent


def refresh(asset_index: Path, generated: Path, build: Path) -> dict[str, int]:
    objects = asset_index.parent.parent / "objects"
    metadata = json.loads(asset_index.read_text(encoding="utf-8"))["objects"]
    sound_hash = metadata["minecraft/sounds.json"]["hash"]
    sound_source = objects / sound_hash[:2] / sound_hash
    spec = importlib.util.spec_from_file_location("browser_sounds", SCRIPTS / "stage-browser-sounds.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if module.sha1(sound_source) != sound_hash:
        raise ValueError("sounds.json does not match the asset index")

    build.mkdir(parents=True, exist_ok=True)
    manifest = build / "browser-sound-assets.tsv"
    selection = subprocess.run([
        "jq", "-L", str(SCRIPTS), "-r",
        'include "browser-sound-path"; .objects | to_entries[] '
        '| select(.key | browser_sound_path) | [.key,.value.hash] | @tsv',
        str(asset_index)], check=True, capture_output=True, text=True).stdout
    manifest.write_text(selection, encoding="utf-8")
    resources = generated / "dev/gaius/browser/minecraft-resources.txt"
    original = resources.read_text(encoding="utf-8").splitlines()
    pending = resources.with_suffix(".audio-part")
    pending.write_text("".join(line + "\n" for line in original
                               if not line.startswith("assets/minecraft/sounds/" )
                               and line != "assets/minecraft/sounds.json"), encoding="utf-8")
    names = build / "browser-sound-names.json"
    result = module.stage(manifest, objects, generated / "assets", pending,
                          names, missing="error", verify_sha1=True)
    filtered = subprocess.run([
        "jq", "--slurpfile", "allowed", str(names),
        "-f", str(SCRIPTS / "browser-sounds-metadata.jq"), str(sound_source)],
        check=True, capture_output=True, text=True).stdout
    target = generated / "assets/minecraft/sounds.json"
    target.write_text(filtered, encoding="utf-8")
    lines = set(pending.read_text(encoding="utf-8").splitlines())
    lines.add("assets/minecraft/sounds.json")
    pending.write_text("".join(line + "\n" for line in sorted(lines)), encoding="utf-8")
    pending.replace(resources)
    result["events"] = len(json.loads(filtered))
    (build / "browser-sound-stage.json").write_text(json.dumps(result) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("asset_index", type=Path)
    parser.add_argument("generated_resources", type=Path)
    parser.add_argument("build", type=Path)
    args = parser.parse_args()
    print(json.dumps(refresh(args.asset_index, args.generated_resources, args.build)))
