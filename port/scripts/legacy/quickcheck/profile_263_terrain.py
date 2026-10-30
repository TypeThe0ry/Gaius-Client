#!/usr/bin/env python3
"""Minecraft 26.3 terrain pipeline and frame-loop quick-check module (work package P5).

Interface for the quick-check profile dispatch (P9):

    checks(root: Path, overlay_jar: Path | None = None) -> list[tuple[str, bool]]

Each tuple is ``(description, passed)``, the shape of quick-check.py's check tables.  Without
``overlay_jar`` only source contracts are read (no build needed).  With it, the patched 26.3
client jar is also asserted through ``minecraft-263-terrain-patcher-smoke.mjs --client-jar``
(javap assertions plus the ASM BasicVerifier over every terrain class).

Standalone: ``python port/scripts/legacy/quickcheck/profile_263_terrain.py [--overlay-jar JAR]``
prints one PASS/FAIL line per check and exits 1 on any failure.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

TOOLS = Path("port/tools/src/main/java/dev/gaius/tools")


def _read(root: Path, relative: str | Path) -> str:
    try:
        return (root / relative).read_text(encoding="utf-8")
    except OSError:
        return ""


def _step(root: Path, patcher: str) -> str:
    """A patcher's source followed by its domain classes (port/mc/patches/<domain>):
    the classes under tools/patches/ whose class comment names the step, "The <domain> domain of
    the <Patcher> step" (port/tools/patchkit/README.md, "Domains")."""
    texts = [_read(root, TOOLS / f"{patcher}.java")]
    marker = re.compile(r"domain of the " + re.escape(patcher) + r" step\b")
    for path in sorted((root / TOOLS / "patches").glob("*/*.java")):
        text = _read(root, path.relative_to(root))
        if marker.search(re.sub(r"\s+\*\s+", " ", text)):
            texts.append(text)
    return "\n".join(texts)


def _bringup_owners(text: str) -> list[str]:
    owners = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if line.count("|") == 2:
            owners.append(line.split("|")[1].strip())
    return owners


def checks(root: Path, overlay_jar: Path | None = None) -> list[tuple[str, bool]]:
    client = _step(root, "MinecraftClientPatcher")
    browser262 = _step(root, "Minecraft262BrowserPatcher")
    telemetry = _read(root, TOOLS / "MinecraftChunkDrawTelemetryPatcher.java")
    terrain263 = _step(root, "Minecraft263BrowserPatcher")
    # One source for every profile; the renderpearl generation moves GpuBuffer by relocation.
    helper = _read(root, "port/src/main/java/dev/gaius/browser/BrowserGpuBufferPoolCache.java")
    relocations = _read(root, "port/mc/shared/renderpearl-1/relocations.tsv")
    bringup = _read(root, "port/tools/bringup/26.3.txt")
    queue_smoke = _read(root, "port/scripts/section-task-queue-smoke.mjs")
    scripts = root / "port/scripts/legacy"

    results: list[tuple[str, bool]] = [
        (
            "26.3 bring-up list has no P5 terrain entries",
            bool(bringup) and "P5" not in _bringup_owners(bringup),
        ),
        (
            "DynamicGpuData transforms and chunk-section UBOs start at the browser slab",
            "patchDynamicGpuDataBrowserInitialCapacity" in client
            and '"Dynamic Transforms UBO", 2, "Terrain UBO"' in client
            and '"Chunk Sections UBO", 1, null' in client
            and 'output.resolveSibling("DynamicGpuData.class")' in client,
        ),
        (
            "MappableRingBuffer telemetry accepts the 26.3 NO_TIMEOUT wait",
            "Long.valueOf(-1L) : Long.valueOf(Long.MAX_VALUE)" in client
            and 'symbols.renderType("com/mojang/blaze3d/buffers/GpuFence")' in client,
        ),
        (
            "GPU pool cache advances its frame before the 26.3 endFrame recycle sweep",
            "recycleAtEndFrame ? afterAvailableClear : endFrameReturn" in client
            and "requireStagedPoolRecycleSite(acquire, owner, !recycleAtEndFrame)" in client
            and "isStagedPoolEndFrameTail" in client
            and "import com.mojang.blaze3d.buffers.GpuBuffer;" in helper
            and "com.mojang.blaze3d.buffers.GpuBuffer\tcom.mojang.renderpearl.api.buffers.GpuBuffer\t" in relocations
            and not (root / "port/mc/shared/renderpearl-1/src/java/dev/gaius/browser/BrowserGpuBufferPoolCache.java").exists(),
        ),
        (
            "GameRenderer frame loop uses render()V/renderLevel()V and the local-1 profiler",
            "gameRendererDeltaFreeFrame(node, jar)" in client
            and "int profilerLocal = deltaFreeFrame ? 1 : 3;" in client
            and 'call.desc.equals("(Lnet/minecraft/client/DeltaTracker;F)V")' in client,
        ),
        (
            "LevelRenderer 26.3 prepare, early occlusion update and refresh are fail-closed",
            "patchLevelRendererBrowserExtractSectionDrawGroups" in client
            and "patchCurrentLevelRendererPrepareAfterOcclusionUpdate" in client
            and "browserVisibleSectionRefreshInstructions(current ? 3 : 4)" in client
            and "LEVEL_RENDERER_RENDER_263" in client
            and '(current ? "IZ)V" : "I)V")' in client,
        ),
        (
            "LevelExtractor requeue is only written next to the compileSections consumption hook",
            "requireLevelRendererUpdateConsumption(output)" in client
            and "LevelExtractor.extractBlockDestroyAnimation telemetry target was not found" in client
            and "captured-frustum receiver is no longer the camera local 2" in client,
        ),
        (
            "EntityRenderDispatcher, section budgets and UberGpuBuffer take the 26.3 shapes",
            '"DDDF)Z" : "DDD)Z"' in client
            and "sectionHeapFactoryVertexFormat(heapFactory)" in client
            and "uberGpuBufferUploadDescriptor(node)" in client,
        ),
        (
            "Live frame targeting is atomic and recomputes the 26.3 camera partial tick",
            "patchCurrentLiveFrameTargetingRefresh" in browser262
            and '"getCameraEntityPartialTicks"' in browser262
            and "is written only after that half has been validated" in browser262,
        ),
        (
            "UberGpuBuffer node cleanup closes renderpearl buffers through the interface",
            "symbols.invokeOpcode(gpuBufferKey)" in browser262
            and "symbols.isInterface(gpuBufferKey)" in browser262,
        ),
        (
            "Chunk-draw telemetry has a 26.3 ProfileShape on the renderpearl frontend",
            'profile.modernPatchSet() && profile.renderpearl()' in telemetry
            and '"extractSectionDrawGroups"' in telemetry
            and '"drawMultipleIndexed"' in telemetry
            and 'RENDERPEARL + "backend/api/RenderPassBackend"' in telemetry,
        ),
        (
            "TerrainPatches263 pins multi-draw-indirect off and verifies the paired hooks",
            "forceSeparateTerrainDraws" in terrain263 and "verifyTerrainChain" in terrain263,
        ),
        (
            "26.3 terrain smokes exist and the section queue smoke accepts 26.3",
            all((scripts / name).is_file() for name in (
                "minecraft-263-terrain-patcher-smoke.mjs",
                "minecraft-263-gpu-buffer-pool-smoke.mjs",
                "minecraft-263-mappable-ring-telemetry-smoke.mjs",
            ))
            and 'version !== CANONICAL_PROFILE.id && version !== RENDERPEARL_PROFILE.id' in queue_smoke,
        ),
    ]
    if overlay_jar is not None:
        completed = subprocess.run(
            ["node", str(scripts / "minecraft-263-terrain-patcher-smoke.mjs"),
             "--client-jar", str(overlay_jar)],
            cwd=root, capture_output=True, text=True, check=False,
        )
        passed = (completed.returncode == 0
                  and "Minecraft 26.3 terrain patcher smoke passed" in completed.stdout)
        description = f"26.3 overlay terrain bytecode ({overlay_jar.name})"
        if not passed:
            # Keep the smoke's own diagnosis in the quick-check output; CI shows nothing else.
            output = (completed.stdout + "\n" + completed.stderr).strip().splitlines()[-12:]
            description += f" [exit {completed.returncode}]\n      " + "\n      ".join(output)
        results.append((description, passed))
    return results


def main(argv: list[str]) -> int:
    root = Path(__file__).resolve().parents[4]
    overlay = None
    if "--overlay-jar" in argv:
        overlay = Path(argv[argv.index("--overlay-jar") + 1]).resolve()
    failed = 0
    for description, passed in checks(root, overlay):
        print(("PASS " if passed else "FAIL ") + description)
        failed += 0 if passed else 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
