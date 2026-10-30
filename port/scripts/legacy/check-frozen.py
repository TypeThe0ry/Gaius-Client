#!/usr/bin/env python3
"""Guard of the frozen verification scripts (port/scripts/legacy/README.md).

Fails when a file is added under port/scripts/legacy, or when a new script (.mjs, .js, .py, .sh)
named after a Minecraft version (``*263*``, ``*_264_*``, ...) appears elsewhere under port/scripts.
The version-named scripts that predate the freeze outside legacy are listed in ``EXISTING``.
Removing a frozen or listed file is fine.

    python3 port/scripts/legacy/check-frozen.py [--self-test]
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
LEGACY = "port/scripts/legacy/"

FROZEN = {
    "README.md",
    "check-frozen.py",
    "quick-check.py",
    "quickcheck/profile_263_input.py",
    "quickcheck/profile_263_render.py",
    "quickcheck/profile_263_server.py",
    "quickcheck/profile_263_terrain.py",
    "quickcheck/profile_263_ui.py",
    "quickcheck/profile_263_worldgen.py",
    "bitmap-font-sharing-263-smoke.mjs",
    "minecraft-263-gpu-buffer-pool-smoke.mjs",
    "minecraft-263-mappable-ring-telemetry-smoke.mjs",
    "minecraft-263-terrain-patcher-smoke.mjs",
    "vanilla-pack-layers-263-smoke.mjs",
}

# Version-named scripts outside legacy that predate the freeze: the singleplayer and multiplayer
# acceptance harnesses (to take a --profile) and the smokes of the older profiles. The two-client
# driver is the third multiplayer acceptance harness (port/scripts/README-multiplayer-acceptance.md).
EXISTING = {
    "port/scripts/minecraft-263-multiplayer-cdp.mjs",
    "port/scripts/minecraft-263-two-client-cdp.mjs",
    "port/scripts/minecraft-263-singleplayer-cdp.mjs",
    "port/scripts/minecraft-12111-patcher-smoke.mjs",
    "port/scripts/minecraft-262-gpu-buffer-pool-smoke.mjs",
    "port/scripts/minecraft-262-link-gap-smoke.mjs",
    "port/scripts/minecraft-262-mappable-ring-telemetry-smoke.mjs",
    "port/scripts/minecraft-262-p1-patcher-smoke.mjs",
}
SCRIPT_SUFFIXES = (".mjs", ".js", ".py", ".sh")

# A Minecraft version in a file name: 1211x/12111, 262, 263, 26_3, 26.4 ... (not inside longer numbers).
VERSION_NAME = re.compile(r"(?<![0-9])(?:1\.?21\.?1\d|2[6-9][._-]?[0-9])(?![0-9])")


def problems(files: list[str]) -> list[str]:
    found = []
    for path in files:
        if path.startswith(LEGACY):
            if path[len(LEGACY):] not in FROZEN:
                found.append(f"new file in the frozen directory: {path}")
        elif path.startswith("port/scripts/") and path not in EXISTING and path.endswith(SCRIPT_SUFFIXES):
            name = path.rsplit("/", 1)[-1]
            if VERSION_NAME.search(name):
                found.append(f"version-named script outside {LEGACY}: {path}")
    return found


def self_test() -> int:
    clean = [LEGACY + "quick-check.py", "port/scripts/build-overlays.sh",
             "port/scripts/minecraft-263-singleplayer-cdp.mjs", "port/scripts/quickcheck/wasmgc_rules.py",
             "port/scripts/bench/harness-common.mjs", "port/scripts/link-gap/known-26.3.json",
             "port/scripts/fixtures/screen-1024x768.mjs"]
    assert problems(clean) == [], problems(clean)
    assert problems([LEGACY + "new-check.py"]), "a new legacy file passed"
    assert problems([LEGACY + "quickcheck/profile_264_input.py"]), "a new legacy module passed"
    for name in ("minecraft-264-smoke.mjs", "profile_26_4_render.py", "quick-check-12111.py", "x-26.4.mjs"):
        assert problems(["port/scripts/" + name]), f"{name} passed"
    print("check-frozen self-test passed")
    return 0


def main(argv: list[str]) -> int:
    if argv[1:] == ["--self-test"]:
        return self_test()
    listed = subprocess.run(["git", "ls-files", "port/scripts"], cwd=ROOT, check=True,
                            capture_output=True, text=True).stdout.split("\n")
    found = problems([path for path in listed if path])
    for problem in found:
        print("FROZEN " + problem)
    print(f"check-frozen: {len(found)} problem(s); {len(FROZEN)} frozen files in {LEGACY}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
