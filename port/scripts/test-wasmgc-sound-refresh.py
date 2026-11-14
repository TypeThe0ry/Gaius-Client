#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

SCRIPTS = Path(__file__).resolve().parent


with tempfile.TemporaryDirectory(prefix="wasmgc-audio-") as temporary:
    root = Path(temporary)
    assets = root / "assets"
    index = assets / "indexes/fixture.json"
    index.parent.mkdir(parents=True)
    objects = {}

    def add(path, body):
        digest = hashlib.sha1(body).hexdigest()
        target = assets / "objects" / digest[:2] / digest
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
        objects[path] = {"hash": digest, "size": len(body)}
        return target

    sample = b"fixture audio bytes"
    audio = add("minecraft/sounds/ambient/cave/cave1.ogg", sample)
    add("minecraft/sounds/music/menu.ogg", b"excluded")
    definitions = {"ambient.cave": {"sounds": ["ambient/cave/cave1"]},
                   "entity.cod.ambient": {"sounds": []},
                   "alias": {"sounds": [{"name": "ambient.cave", "type": "event"}]},
                   "music.menu": {"sounds": ["music/menu"]}}
    add("minecraft/sounds.json", json.dumps(definitions).encode())
    index.write_text(json.dumps({"objects": objects}))
    generated = root / "generated"
    resources = generated / "dev/gaius/browser/minecraft-resources.txt"
    resources.parent.mkdir(parents=True)
    resources.write_text("assets/minecraft/textures/stone.png\nassets/minecraft/sounds/stale.ogg\n")
    command = [sys.executable, str(SCRIPTS / "refresh-browser-sounds.py"),
               str(index), str(generated), str(root / "build")]
    subprocess.run(command, check=True, capture_output=True, text=True)
    actual = json.loads((generated / "assets/minecraft/sounds.json").read_text())
    assert actual == {k: v for k, v in definitions.items() if k != "music.menu"}, actual
    assert (generated / "assets/minecraft/sounds/ambient/cave/cave1.ogg").read_bytes() == sample
    assert resources.read_text().splitlines() == sorted([
        "assets/minecraft/textures/stone.png", "assets/minecraft/sounds.json",
        "assets/minecraft/sounds/ambient/cave/cave1.ogg"])
    before = resources.read_bytes()
    audio.unlink()
    failed = subprocess.run(command, capture_output=True, text=True)
    assert failed.returncode != 0
    assert resources.read_bytes() == before, "missing assets published a partial resource list"

wasmgc = (SCRIPTS / "build-wasmgc.sh").read_text()
assert wasmgc.index('"$root/port/scripts/refresh-browser-sounds.py"') < wasmgc.index('pack_args=(')
print("WasmGC sound refresh fixture passed: samples, aliases, silent events, missing-object refusal")
