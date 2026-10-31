#!/usr/bin/env python3
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent
SCRIPT = ROOT / "stage-browser-sounds.py"


def object_for(objects: Path, payload: bytes) -> str:
    digest = hashlib.sha1(payload).hexdigest()
    target = objects / digest[:2] / digest
    target.parent.mkdir(parents=True)
    target.write_bytes(payload)
    return digest


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="browser-sound-stage-fixture-") as temp:
        root = Path(temp)
        objects = root / "objects"
        output = root / "generated" / "assets"
        manifest = root / "sounds.tsv"
        resource_list = root / "resources.txt"
        sound_names = root / "browser-sound-names.json"
        first = object_for(objects, b"first")
        second = object_for(objects, b"second")
        manifest.write_text(
            f"minecraft/sounds/a/first.ogg\t{first}\n"
            f"minecraft/sounds/a/b/second.ogg\t{second}\n"
            f"minecraft/sounds/missing.ogg\t{'0' * 40}\n",
            encoding="utf-8",
        )
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--manifest", str(manifest),
             "--objects", str(objects), "--output", str(output),
             "--resource-list", str(resource_list), "--sound-names", str(sound_names),
             "--verify-sha1"],
            check=True, capture_output=True, text=True,
        )
        assert '"copied":2' in result.stdout, result.stdout
        assert "WARNING: missing asset object for minecraft/sounds/missing.ogg" in result.stderr
        assert (output / "minecraft/sounds/a/first.ogg").read_bytes() == b"first"
        assert (output / "minecraft/sounds/a/b/second.ogg").read_bytes() == b"second"
        assert resource_list.read_text(encoding="utf-8").splitlines() == [
            "assets/minecraft/sounds/a/first.ogg",
            "assets/minecraft/sounds/a/b/second.ogg",
        ]
        assert sound_names.read_text(encoding="utf-8").splitlines() == [
            "[",
            '  "a/first",',
            '  "a/b/second"',
            "]",
        ]
        bad = root / "bad.tsv"
        bad.write_text(f"../escape.ogg\t{first}\n", encoding="utf-8")
        failed = subprocess.run(
            [sys.executable, str(SCRIPT), "--manifest", str(bad),
             "--objects", str(objects), "--output", str(output),
             "--resource-list", str(resource_list)],
            capture_output=True, text=True,
        )
        assert failed.returncode != 0
        for bad_path in ("C:\\escape.ogg", "C:relative.ogg", "\\\\server\\share\\x.ogg"):
            bad.write_text(f"{bad_path}\t{first}\n", encoding="utf-8")
            failed = subprocess.run(
                [sys.executable, str(SCRIPT), "--manifest", str(bad),
                 "--objects", str(objects), "--output", str(output),
                 "--resource-list", str(resource_list)],
                capture_output=True, text=True,
            )
            assert failed.returncode != 0, bad_path
        bad.write_text(f"minecraft/sounds/bad.ogg\t{'0' * 39}x\n", encoding="utf-8")
        failed = subprocess.run(
            [sys.executable, str(SCRIPT), "--manifest", str(bad),
             "--objects", str(objects), "--output", str(output),
             "--resource-list", str(resource_list)],
            capture_output=True, text=True,
        )
        assert failed.returncode != 0
        print("browser sound staging fixture passed")


if __name__ == "__main__":
    main()
