#!/usr/bin/env python3
"""Quick-check module: Minecraft 26.3 worldgen patches (work package P6, migration plan D6).

Bytecode assertions on the patched 26.3 client jar for the worldgen and server-chunk
cooperation patches, plus the non-suspending worldgen guard of WorldgenPatches263. quick-check.py
(owned by P9) can import ``run_checks`` and fold the results into its own report; the module
also runs on its own:

    python port/scripts/legacy/quickcheck/profile_263_worldgen.py \\
        [--jar port/work/overlays/26.3/client-named-26.3-gaius.jar] \\
        [--tool-classes port/work/overlays/26.3/tool-classes] [--javap <javap>]

Exit status: 0 all checks pass, 1 a check failed, 2 missing prerequisites.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[4]
PROFILE = "26.3"
PULSE = "dev/gaius/browser/BrowserWorldgenDeepCheckpoint.pulse:()V"

# Helpers of the 26.2 density-runtime patches that 26.3 registers as dropped; the 26.3 jar must
# not reference them any more.
DROPPED_HELPERS = (
    "dev/gaius/browser/BrowserImprovedNoise",
    "dev/gaius/browser/BrowserPerlinNoise",
    "dev/gaius/browser/BrowserNoiseInterpolator",
    "dev/gaius/browser/BrowserDensityFunctions",
    "dev/gaius/browser/BrowserBeardifier",
    "dev/gaius/browser/BrowserSurfaceBiomeSupplier",
    "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper",
)

# 26.2 targets of the dropped patches; their absence is what PatchRegistry.dropped asserted.
ABSENT_26_2_TARGETS = (
    "net/minecraft/world/level/levelgen/synth/ImprovedNoise.class",
    "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator.class",
    "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce.class",
    "net/minecraft/world/level/levelgen/DensityFunctions$Clamp.class",
    "net/minecraft/world/level/levelgen/SurfaceRules$Context.class",
    "net/minecraft/world/level/levelgen/SurfaceRules$LazyCondition.class",
    "net/minecraft/world/level/levelgen/SurfaceRules$SequenceRule.class",
    "net/minecraft/world/level/levelgen/SurfaceSystem.class",
    "net/minecraft/world/level/levelgen/DensityFunction$Visitor.class",
    "net/minecraft/world/level/biome/BiomeManager$NoiseBiomeSource.class",
)


def find_javap(explicit: str | None) -> str:
    if explicit:
        return explicit
    for variable in ("GAIUS_JAVA_HOME", "JAVA_HOME"):
        home = os.environ.get(variable)
        if home:
            candidate = Path(home.replace("\\", "/")) / "bin" / ("javap.exe" if os.name == "nt" else "javap")
            if candidate.exists():
                return str(candidate)
    found = shutil.which("javap")
    if not found:
        raise FileNotFoundError("javap not found (set GAIUS_JAVA_HOME or JAVA_HOME)")
    return found


class Disassembly:
    """``javap -c -p`` of one class, split into methods keyed by their javap header line."""

    def __init__(self, text: str):
        self.text = text
        self.methods: dict[str, str] = {}
        current = None
        lines: list[str] = []
        for line in text.splitlines():
            if line.startswith("  ") and not line.startswith("    ") and line.rstrip().endswith(";"):
                if current is not None:
                    self.methods[current] = "\n".join(lines)
                current = line.strip()
                lines = []
            elif current is not None:
                lines.append(line)
        if current is not None:
            self.methods[current] = "\n".join(lines)

    def method(self, pattern: str) -> str:
        matches = [body for header, body in self.methods.items() if re.search(pattern, header)]
        if len(matches) != 1:
            raise AssertionError(f"expected one method matching {pattern!r}, found {len(matches)}")
        return matches[0]


def disassemble(javap: str, jar: Path, class_name: str) -> Disassembly:
    result = subprocess.run([javap, "-c", "-p", "-constants", "-classpath", str(jar), class_name],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise AssertionError(f"javap {class_name} failed: {result.stderr.strip()[:400]}")
    return Disassembly(result.stdout)


def count(text: str, needle: str) -> int:
    return text.count(needle)


def run_checks(jar: Path, javap: str, tool_classes: Path | None = None,
               asm_classpath: list[Path] | None = None) -> list[tuple[str, bool, str]]:
    """Returns (label, passed, detail) for every 26.3 worldgen assertion."""
    results: list[tuple[str, bool, str]] = []
    cache: dict[str, Disassembly] = {}

    def cls(name: str) -> Disassembly:
        if name not in cache:
            cache[name] = disassemble(javap, jar, name)
        return cache[name]

    def check(label: str, predicate: Callable[[], bool | tuple[bool, str]]) -> None:
        try:
            outcome = predicate()
            passed, detail = outcome if isinstance(outcome, tuple) else (bool(outcome), "")
        except Exception as error:  # an assertion helper failed: report, keep going
            passed, detail = False, str(error)
        results.append((label, passed, detail))

    biome = "net.minecraft.world.level.biome.BiomeManager"
    check("BiomeManager.getBiome(III) uses the nearest-corner helper and BiomeResolver", lambda: (
        count(cls(biome).method(r"getBiome\(int, int, int\)"),
              "dev/gaius/browser/BrowserBiomeManager.nearestCorner:(JIII)I") == 1
        and count(cls(biome).method(r"getBiome\(int, int, int\)"),
                  "InterfaceMethod net/minecraft/world/level/biome/BiomeResolver.getNoiseBiome:(III)") == 1))
    check("BiomeManager no longer references BiomeManager$NoiseBiomeSource",
          lambda: "BiomeManager$NoiseBiomeSource" not in cls(biome).text)

    server = "net.minecraft.server.MinecraftServer"
    check("setInitialSpawn takes the spawn chunk from ChunkGenerator.getOrigin", lambda: (
        lambda body: "ChunkGenerator.getOrigin:(Lnet/minecraft/world/level/levelgen/RandomState;)" in body
        and "server.browserFastInitialSpawn" in body
        and "RandomState.sampler" not in body and "findSpawnPosition" not in body)(
            cls(server).method(r"setInitialSpawn\(")))

    generator = "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator"
    check("NoiseBasedChunkGenerator.doFill: 3 deep pulses, default block hoisted once", lambda: (
        lambda body: (count(body, PULSE) == 3 and count(body, "NoiseGeneratorSettings.defaultBlock") == 1,
                      f"pulses={count(body, PULSE)} defaultBlock={count(body, 'NoiseGeneratorSettings.defaultBlock')}"))(
            cls(generator).method(r"void doFill\(net\.minecraft\.world\.level\.levelgen\.NoiseChunk, ")))
    check("NoiseBasedChunkGenerator.generateCarvers: 3 deep pulses",
          lambda: count(cls(generator).method(r"void generateCarvers\("), PULSE) == 3)
    check("MaterialSystem.buildSurface: 4 deep pulses", lambda: count(
        cls("net.minecraft.world.level.levelgen.material.MaterialSystem").method(r"void buildSurface\("),
        PULSE) == 4)
    mask = "net.minecraft.world.level.chunk.CarvingMask"
    check("CarvingMask.visit and visitSegment: 1 deep pulse each", lambda: (
        count(cls(mask).method(r"void visit\("), PULSE) == 1
        and count(cls(mask).method(r"void visitSegment\("), PULSE) == 1))
    chunk_generator = "net.minecraft.world.level.chunk.ChunkGenerator"
    check("ChunkGenerator createStructures lambda: pulses before both structure attempts and on 4 back-edges",
          lambda: count(cls(chunk_generator).method(r"lambda\$createStructures\$"), PULSE) == 6)
    check("ChunkGenerator.applyBiomeDecoration: 5 deep pulses",
          lambda: count(cls(chunk_generator).method(r"void applyBiomeDecoration\("), PULSE) == 5)
    check("WorldCarver.carveEllipsoid (static interface method): 3 deep pulses", lambda: count(
        cls("net.minecraft.world.level.levelgen.carver.WorldCarver").method(r"static void carveEllipsoid\("),
        PULSE) == 3)
    check("LevelChunkSection.fillBiomesFromNoise(BiomeResolver,III): 3 deep pulses", lambda: count(
        cls("net.minecraft.world.level.chunk.LevelChunkSection").method(
            r"fillBiomesFromNoise\(net\.minecraft\.world\.level\.biome\.BiomeResolver, int, int, int\)"),
        PULSE) == 3)
    check("NaturalSpawner generation-spawn telemetry (BlockPos descriptor)", lambda: (
        lambda body: all(f"BrowserGenerationSpawnTelemetry.{name}" in body
                         for name in ("begin", "entityAdded", "complete", "failed")))(
            cls("net.minecraft.world.level.NaturalSpawner").method(
                r"spawnMobsForChunkGeneration\(net\.minecraft\.world\.level\.ServerLevelAccessor, "
                r"net\.minecraft\.core\.BlockPos, ")))
    aquifer = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer"

    def aquifer_fast_path() -> tuple[bool, str]:
        body = cls(aquifer).method(r"computeSubstance\(int, int, int, double\)")
        call = body.find("BrowserAquifer.selectNearestCached")
        before = body[:call].splitlines()[-6:]
        after = []
        for line in body[call:].splitlines()[1:]:
            if re.search(r"\bgoto\b", line):
                break  # the fast path ends with a jump back into the vanilla code
            after.append(line)
        loads = [line.split(":", 1)[1].split()[:2] for line in before if "iload" in line]
        stores = sorted({int(match) for line in after for match in re.findall(r"istore\s+(\d+)", line)})
        ok = (call >= 0 and [" ".join(load) for load in loads[-3:]] == ["iload_1", "iload_2", "iload_3"]
              and stores == list(range(10, 18)))
        return ok, f"loads={loads[-3:]} stores={stores}"
    check("Aquifer.computeSubstance(IIID): nearest-center fast path on locals 1..3 -> 10..17",
          aquifer_fast_path)
    context = "net.minecraft.world.level.levelgen.material.MaterialRuleContext"
    check("MaterialRuleContext int shadow counters", lambda: (
        "int browserUpdateXZ;" in cls(context).text and "int browserUpdateY;" in cls(context).text
        and count(cls(context).method(r"void updateXZ\("), "browserUpdateXZ") == 2
        and count(cls(context).method(r"void updateY\("), "browserUpdateY") == 2))
    for suffix, counter in (("$LazyXZCondition", "browserUpdateXZ"), ("$LazyYCondition", "browserUpdateY")):
        check(f"MaterialRuleContext{suffix}.test() compares the int counter", lambda suffix=suffix, counter=counter: (
            lambda body: counter in body and "lcmp" not in body and "compute:()Z" in body)(
                cls(context + suffix).method(r"boolean test\(\)")))
    region = "net.minecraft.world.level.chunk.storage.RegionFileStorage"
    check("RegionFileStorage.cache(long, Optional) bounded to 16 regions", lambda: (
        lambda body: "bipush        16" in body and "sipush        256" not in body)(
            cls(region).method(r"void cache\(long, ")))
    check("Worldgen hash caches on the CubicSpline records only", lambda: all(
        "browserHashCodeComputed" in cls(name).text
        for name in ("net.minecraft.util.CubicSpline$Constant", "net.minecraft.util.CubicSpline$Multipoint")))

    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        present = [target for target in ABSENT_26_2_TARGETS if target in names]
        results.append(("26.2 targets of dropped worldgen patches are absent", not present,
                        ", ".join(present)))
        needles = [helper.encode() for helper in DROPPED_HELPERS]
        referencing = sorted({f"{name} -> {needle.decode()}" for name in names if name.endswith(".class")
                              for needle in needles if needle in archive.read(name)})
        results.append(("No class references a dropped 26.2 worldgen helper", not referencing,
                        "; ".join(referencing[:5])))

    if tool_classes is None or not asm_classpath:
        # Fail closed: without the tool classes and ASM the non-suspending guard (R8) and its
        # self-test cannot run, and a silent omission would read as a pass.
        missing = "tool classes" if tool_classes is None else "ASM 9.8 (asm, asm-tree) in M2_REPO or ~/.m2"
        for mode in ("--verify-non-suspending", "--self-test"):
            results.append((f"WorldgenPatches263 {mode}", False, f"not run: {missing} missing"))
    else:
        java = str(Path(javap).with_name(Path(javap).name.replace("javap", "java")))
        classpath = os.pathsep.join([str(tool_classes)] + [str(path) for path in asm_classpath])
        for mode in ("--verify-non-suspending", "--self-test"):
            result = subprocess.run([java, "-cp", classpath, "dev.gaius.tools.patches.worldgen.RenderpearlWorldgenPatches",
                                     mode, str(jar)], capture_output=True, text=True)
            summary = [line for line in result.stdout.splitlines()
                       if line.startswith(("WORLDGEN_NON_SUSPENDING", "WORLDGEN_GUARD_SELF_TEST"))]
            results.append((f"WorldgenPatches263 {mode}", result.returncode == 0,
                            " | ".join(summary)[-600:] or result.stderr.strip()[-400:]))
    return results


def default_asm_classpath() -> list[Path]:
    repository = Path(os.environ.get("M2_REPO", Path.home() / ".m2" / "repository"))
    jars = [repository / "org/ow2/asm/asm/9.8/asm-9.8.jar",
            repository / "org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar"]
    return jars if all(jar.exists() for jar in jars) else []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    overlay = ROOT / "port" / "work" / "overlays" / PROFILE
    parser.add_argument("--jar", type=Path, default=overlay / f"client-named-{PROFILE}-gaius.jar")
    parser.add_argument("--tool-classes", type=Path, default=overlay / "tool-classes")
    parser.add_argument("--javap")
    arguments = parser.parse_args()
    if not arguments.jar.exists():
        print(f"missing patched client jar {arguments.jar} (run build-overlays.sh for {PROFILE})")
        return 2
    try:
        javap = find_javap(arguments.javap)
    except FileNotFoundError as error:
        print(error)
        return 2
    tool_classes = arguments.tool_classes if arguments.tool_classes.exists() else None
    results = run_checks(arguments.jar, javap, tool_classes, default_asm_classpath())
    failures = 0
    for label, passed, detail in results:
        print(f"{'PASS' if passed else 'FAIL'} {label}" + (f" ({detail})" if detail and not passed else ""))
        failures += 0 if passed else 1
    print(f"PROFILE_263_WORLDGEN checks={len(results)} failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
