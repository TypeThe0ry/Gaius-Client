#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent


def main() -> None:
    fetch = (ROOT / "fetch-version.sh").read_text(encoding="utf-8")
    build = (ROOT / "build-teavm.sh").read_text(encoding="utf-8")
    assert 'include "browser-sound-path"' in fetch
    assert 'include "browser-sound-path"' in build

    entries = [
        "minecraft/sounds/ambient/cave/cave1.ogg",
        "minecraft/sounds/ambient/basalt_deltas/additions.ogg",
        "minecraft/sounds/weather/rain1.ogg",
        "minecraft/sounds/weather/rain2.ogg",
        "minecraft/sounds/block/stone/break1.ogg",
        "minecraft/sounds/music/game/creative1.ogg",
        "minecraft/sounds/ambientish/cave1.ogg",
        "minecraft/sounds/weather/rain.txt",
    ]
    index = {"objects": {path: {"hash": "0" * 40} for path in entries}}
    selected = subprocess.run(
        ["jq", "-L", str(ROOT), "-r",
         'include "browser-sound-path"; .objects | keys[] | select(browser_sound_path)'],
        input=json.dumps(index), check=True, capture_output=True, text=True,
    ).stdout.splitlines()
    assert selected == sorted(entries[:5] + [entries[6]]), selected

    with tempfile.TemporaryDirectory(prefix="browser-sound-metadata-") as temp:
        root = Path(temp)
        source = root / "sounds.json"
        allowed = root / "allowed.json"
        source.write_text(json.dumps({
            "ambient/cave": {"sounds": [
                {"name": "ambient/cave/cave1", "type": "file"},
                {"name": "missing", "type": "file"},
            ]},
            "alias": {"sounds": [{"name": "ambient/cave", "type": "event"}]},
            "empty": {"sounds": [{"name": "missing", "type": "file"}]},
            "already-empty": {"sounds": [], "subtitle": "subtitles.block.generic.hit"},
            "alias-to-empty": {"sounds": [{"name": "already-empty", "type": "event"}]},
        }), encoding="utf-8")
        allowed.write_text(json.dumps(["ambient/cave/cave1"]), encoding="utf-8")
        metadata = json.loads(subprocess.run(
            ["jq", "--slurpfile", "allowed", str(allowed),
             "-f", str(ROOT / "browser-sounds-metadata.jq"), str(source)],
            check=True, capture_output=True, text=True,
        ).stdout)
        assert set(metadata) == {"ambient/cave", "alias", "already-empty", "alias-to-empty"}, metadata
        assert len(metadata["ambient/cave"]["sounds"]) == 1
        assert metadata["alias"]["sounds"][0]["type"] == "event"
        assert metadata["already-empty"] == {
            "sounds": [], "subtitle": "subtitles.block.generic.hit"}, metadata
        assert metadata["alias-to-empty"]["sounds"] == [
            {"name": "already-empty", "type": "event"}], metadata
    print("browser sound selection test passed (ambient, weather, gameplay; excludes music and non-audio paths)")


if __name__ == "__main__":
    main()
