#!/usr/bin/env python3
"""Atlas prebake: build the GAIUSAP1 payload from the vanilla classes, and its JVM gates.

    atlasprebake.py build --profile ID --pack vanilla-assets.pack.gz --out gaius-atlas-prebake.bin.gz
                          [--summary FILE.json] [--scratch DIR] [--max-mip N] [--mips 0,1,2,3,4]
                          [--anisotropy 0]
    atlasprebake.py gate  --profile ID --pack vanilla-assets.pack.gz --out DIR [--payload FILE]
                          [--scratch DIR]

build runs the profile's vanilla client jar (port/work/<profile>, JDK of the profile's javaVersion)
over the browser's vanilla asset pack: for every sprite of a mipmapped atlas its vanilla mip chain
(MipmapGenerator.generateMipLevels: level 0 after vanilla's in-place change, levels 1..max), for
every atlas and option set the vanilla Stitcher walk. BrowserAtlasPrebake (the browser class the
patches call) replays an entry only for exactly the inputs recorded with it.

gate applies the two port/tools patches (PatchStep, the code build-overlays.sh runs) to the
vanilla jar and loads every atlas through vanilla SpriteLoader.stitch three ways: vanilla classes,
patched classes with the payload, patched classes in verify mode. The dumps (atlas sizes, the
region map in iteration order, placements, UV bits, padding, SHA-256 of every mip level of every
sprite and of every composed atlas level) must be byte-identical, with every mip chain and layout
served by the payload; then the fallback cases (a changed pixel, a dropped sprite, anisotropic
padding, a corrupted payload, another profile, mode off, levels or layouts the payload lacks, a
maximum size that does not fit) must equal vanilla with the expected misses. The report is
<out>/gate.json; exit status 0 only when every check passes.

--pack may also be the profile's client-named.jar (its own assets), for a gate run of a profile
whose pack is not built. Version independent: the atlas list, sprite sources and option shapes
come from the jar itself; profile ids appear only as arguments.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PORT = HERE.parents[1]
ROOT = PORT.parent


def java_tool(name: str) -> str:
    for home in (os.environ.get("GAIUS_JAVA_HOME"), os.environ.get("JAVA_HOME")):
        if home and (Path(home) / "bin" / name).exists():
            return str(Path(home) / "bin" / name)
    return name


def maven_repository() -> Path:
    configured = os.environ.get("GAIUS_MAVEN_REPOSITORY")
    if configured:
        path = Path(configured)
        return path if path.is_absolute() else ROOT / path
    return Path.home() / ".m2" / "repository"


def profile_json(profile: str) -> dict:
    path = PORT / "versions" / f"{profile}.json"
    if not path.is_file():
        raise SystemExit(f"unknown profile {profile} (no {path})")
    return json.loads(path.read_text(encoding="utf-8"))


def client_classpath(profile: str) -> list[Path]:
    work = PORT / "work" / profile
    jar = work / "client-named.jar"
    listing = work / "classpath.txt"
    if not jar.is_file() or not listing.is_file():
        raise SystemExit(f"missing {jar} or {listing} (port/scripts/fetch-version.sh and remap-client.sh)")
    paths = [jar]
    seen = set()
    for entry in listing.read_text(encoding="utf-8").replace(";", ":").split(":"):
        entry = entry.strip()
        if "/libraries/" not in entry:
            continue
        relative = entry.split("/libraries/", 1)[1]
        library = work / "libraries" / relative
        if relative in seen or not library.is_file():
            continue
        seen.add(relative)
        paths.append(library)
    return paths


def teavm_jars() -> list[Path]:
    version = json.loads((PORT / "config.json").read_text(encoding="utf-8"))["teaVMVersion"]
    repository = maven_repository() / "org" / "teavm"
    jars = [repository / name / version / f"{name}-{version}.jar"
            for name in ("teavm-jso", "teavm-jso-apis", "teavm-interop", "teavm-core", "teavm-classlib")]
    missing = [str(jar) for jar in jars if not jar.is_file()]
    if missing:
        raise SystemExit("missing TeaVM jars (run a TeaVM build once): " + ", ".join(missing))
    return jars


def asm_jars() -> list[Path]:
    repository = maven_repository() / "org" / "ow2" / "asm"
    jars = [repository / "asm" / "9.8" / "asm-9.8.jar", repository / "asm-tree" / "9.8" / "asm-tree-9.8.jar"]
    missing = [str(jar) for jar in jars if not jar.is_file()]
    if missing:
        raise SystemExit("missing ASM jars (build-overlays.sh bootstraps them): " + ", ".join(missing))
    return jars


def join(paths: list[Path]) -> str:
    return os.pathsep.join(str(path) for path in paths)


def run(command: list[str], log: Path | None = None) -> subprocess.CompletedProcess:
    result = subprocess.run(command, text=True, capture_output=True)
    if log is not None:
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0:
        sys.stderr.write(result.stdout[-4000:] + result.stderr[-4000:])
        raise SystemExit(f"command failed ({result.returncode}): {' '.join(command[:4])} ...")
    return result


def compile_tool(profile: str, scratch: Path) -> Path:
    """The builder/dump classes with BrowserAtlasPrebake (the browser source, compiled for the JVM)."""
    classes = scratch / "classes"
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    release = str(profile_json(profile).get("javaVersion", 21))
    sources = sorted(str(path) for path in (HERE / "java").rglob("*.java"))
    sources.append(str(PORT / "src" / "target-wasmgc" / "java" / "dev" / "gaius" / "browser" / "BrowserAtlasPrebake.java"))
    sourcepath = join([PORT / "src" / "target-wasmgc" / "java",
                       PORT / "overrides" / "classlib" / "src" / "target-wasmgc" / "java"])
    run([java_tool("javac"), "-J-Duser.language=en", "--release", release, "-proc:none", "-nowarn",
         "-encoding", "UTF-8", "-implicit:class", "-classpath", join(client_classpath(profile) + teavm_jars()),
         "-sourcepath", sourcepath, "-d", str(classes), *sources], scratch / "logs" / "javac-tool.log")
    return classes


def patch_classes(profile: str, scratch: Path) -> Path:
    """MipmapGenerator, Stitcher and Stitcher$Region as the port/tools patches write them."""
    tools = scratch / "patch-tools"
    shutil.rmtree(tools, ignore_errors=True)
    tools.mkdir(parents=True)
    classpath = asm_jars() + [jar for jar in teavm_jars() if jar.name.startswith("teavm-core-")]
    run([java_tool("javac"), "-J-Duser.language=en", "--release", "21", "-proc:none", "-nowarn", "-encoding", "UTF-8",
         "-classpath", join(classpath), "-sourcepath", str(PORT / "tools" / "src" / "main" / "java"),
         "-d", str(tools), str(HERE / "patch" / "dev" / "gaius" / "tools" / "atlasprebake" / "PatchStep.java")],
        scratch / "logs" / "javac-patch.log")
    patched = scratch / "patched"
    shutil.rmtree(patched, ignore_errors=True)
    patched.mkdir(parents=True)
    run([java_tool("java"), "-cp", join([tools] + classpath), "dev.gaius.tools.atlasprebake.PatchStep",
         str(PORT / "work" / profile / "client-named.jar"), str(patched)], scratch / "logs" / "patch.log")
    for name in ("MipmapGenerator.class", "Stitcher.class", "Stitcher$Region.class"):
        if not (patched / "net" / "minecraft" / "client" / "renderer" / "texture" / name).is_file():
            raise SystemExit(f"PatchStep did not write {name}")
    return patched


def java_run(profile: str, scratch: Path, classpath: list[Path], arguments: list[str], log: Path) -> str:
    temporary = scratch / "tmp"
    temporary.mkdir(parents=True, exist_ok=True)
    command = [java_tool("java"), "-Xmx6g", "-Xverify:all", f"-Djava.io.tmpdir={temporary}",
               "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
               "-cp", join(classpath + client_classpath(profile)), "dev.gaius.tools.atlasprebake.AtlasPrebakeTool",
               *arguments]
    return run(command, log).stdout


def default_scratch(profile: str) -> Path:
    return PORT / "work" / "atlasprebake" / profile


def build(args: argparse.Namespace) -> dict:
    scratch = (args.scratch or default_scratch(args.profile)).resolve()
    classes = compile_tool(args.profile, scratch)
    command = ["build", "--pack", str(args.pack.resolve()), "--profile", args.profile, "--scratch", str(scratch),
               "--out", str(args.out.resolve()), "--max-mip", str(args.max_mip), "--mips", args.mips,
               "--anisotropy", args.anisotropy]
    summary = args.summary.resolve() if args.summary else scratch / "summary.json"
    command += ["--summary", str(summary)]
    java_run(args.profile, scratch, [classes], command, scratch / "logs" / "build.log")
    return json.loads(summary.read_text(encoding="utf-8"))


def gate(args: argparse.Namespace) -> int:
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    scratch = (args.scratch or default_scratch(args.profile)).resolve()
    pack = args.pack.resolve()
    classes = compile_tool(args.profile, scratch)
    patched = patch_classes(args.profile, scratch)
    payload = args.payload.resolve() if args.payload else out / "gaius-atlas-prebake.bin.gz"
    report: dict = {"profile": args.profile, "pack": str(pack), "payload": str(payload), "checks": []}
    if not args.payload:
        java_run(args.profile, scratch, [classes], ["build", "--pack", str(pack), "--profile", args.profile,
                 "--scratch", str(scratch), "--out", str(payload), "--summary", str(out / "summary.json")],
                 out / "logs" / "build.log")
    # A payload that only knows mip level 2 and no layout for level 4.
    narrow = out / "narrow.bin.gz"
    java_run(args.profile, scratch, [classes], ["build", "--pack", str(pack), "--profile", args.profile,
             "--scratch", str(scratch), "--out", str(narrow), "--max-mip", "2", "--mips", "2",
             "--summary", str(out / "narrow-summary.json")], out / "logs" / "build-narrow.log")
    tampered = {}
    for kind in ("mip", "layout"):
        tampered[kind] = out / f"tampered-{kind}.bin.gz"
        java_run(args.profile, scratch, [classes], ["build", "--pack", str(pack), "--profile", args.profile,
                 "--scratch", str(scratch), "--out", str(tampered[kind]), "--tamper", kind,
                 "--summary", str(out / f"tampered-{kind}-summary.json")], out / "logs" / f"build-tampered-{kind}.log")
    summary = json.loads((out / "summary.json").read_text(encoding="utf-8")) if (out / "summary.json").is_file() else {}
    report["summary"] = {key: summary.get(key) for key in ("payloadBytes", "gzipBytes", "mipEntries",
                                                         "stitchRecords", "mipLevelBytes", "level0Bytes")}
    atlases = summary.get("atlases", {})
    mipmapped_sprites = sum(entry.get("sprites", 0) for entry in atlases.values() if entry.get("mipmaps"))
    atlas_count = len(atlases)

    def dump(name: str, patched_classes: bool, extra: list[str], payload_file: Path | None = None,
             mode: str = "on") -> tuple[str, dict]:
        target = out / f"{name}.txt"
        counters = out / f"{name}.counters.json"
        arguments = ["dump", "--pack", str(pack), "--profile", args.profile, "--scratch", str(scratch),
                     "--out", str(target), "--counters", str(counters), *extra]
        if payload_file is not None:
            arguments += ["--payload", str(payload_file), "--mode", mode]
        classpath = ([patched] if patched_classes else []) + [classes]
        java_run(args.profile, scratch, classpath, arguments, out / "logs" / f"{name}.log")
        return target.read_text(encoding="utf-8"), json.loads(counters.read_text(encoding="utf-8"))

    failures = 0

    def check(name: str, ok: bool, detail: dict) -> None:
        nonlocal failures
        report["checks"].append({"name": name, "pass": bool(ok), **detail})
        print(("PASS " if ok else "FAIL ") + name + " " + json.dumps(detail, sort_keys=True))
        if not ok:
            failures += 1

    def compare(name: str, extra: list[str], payload_file: Path | None, mode: str, expect) -> None:
        vanilla_text, _ = dump(f"{name}-vanilla", False, extra)
        prebake_text, counters = dump(f"{name}-prebake", True, extra, payload_file, mode)
        equal = vanilla_text == prebake_text
        expectation = expect(counters)
        lines = vanilla_text.count("\n")
        check(name, equal and expectation, {"byteEqual": equal, "dumpLines": lines, "counters": counters})

    full_mips = lambda c: c.get("mipHits") == mipmapped_sprites and c.get("mipMissKey", 0) == 0 \
        and c.get("mipMissHash", 0) == 0 and c.get("mipMissLevel", 0) == 0
    full_stitch = lambda c: c.get("stitchHits") == atlas_count and c.get("stitchMisses", 0) == 0

    # Positive: the default browser option set and the other covered ones.
    compare("default-mip2-max8192", ["--mip", "2", "--max-size", "8192"], payload, "on",
            lambda c: full_mips(c) and full_stitch(c) and c.get("status") == "on")
    for mip in (0, 1, 3, 4):
        compare(f"mip{mip}", ["--mip", str(mip), "--max-size", "8192"], payload, "on",
                lambda c, mip=mip: full_stitch(c) and (c.get("mipHits") == (mipmapped_sprites if mip > 0 else 0)))
    compare("max16384", ["--mip", "2", "--max-size", "16384"], payload, "on",
            lambda c: full_mips(c) and full_stitch(c))
    compare("max10000-not-power-of-two", ["--mip", "2", "--max-size", "10000"], payload, "on",
            lambda c: full_mips(c) and full_stitch(c))
    # Verify mode: vanilla runs, every level and layout is compared, nothing differs.
    compare("verify", ["--mip", "4", "--max-size", "8192"], payload, "verify",
            lambda c: c.get("status") == "verify" and c.get("verifyChecks", 0) > 0
            and c.get("verifyMismatches") == 0 and full_mips(c) and full_stitch(c))
    # Fallbacks.
    compare("changed-pixel", ["--mip", "2", "--mutate-pixel", "minecraft:block/stone"], payload, "on",
            lambda c: c.get("mipMissHash") == 1 and c.get("mipHits") == mipmapped_sprites - 1 and full_stitch(c))
    compare("dropped-sprite", ["--mip", "2", "--drop-sprite", "minecraft:block/stone"], payload, "on",
            lambda c: c.get("stitchMisses") == 1 and c.get("stitchHits") == atlas_count - 1
            and c.get("mipHits") == mipmapped_sprites - 1)
    compare("anisotropic-padding", ["--mip", "2", "--anisotropy", "3"], payload, "on",
            lambda c: c.get("stitchHits") == 0 and c.get("stitchMisses") == atlas_count and full_mips(c))
    compare("corrupt-payload", ["--mip", "2", "--corrupt-payload"], payload, "on",
            lambda c: c.get("mipHits") == 0 and c.get("stitchHits") == 0 and "mismatch" in str(c.get("status")))
    vanilla_text, _ = dump("other-profile-vanilla", False, ["--mip", "2"])
    other_text, other = dump("other-profile-prebake", True, ["--mip", "2", "--profile", args.profile + "-other"],
                             payload, "on")
    check("other-profile", vanilla_text == other_text and other.get("mipHits") == 0 and other.get("stitchHits") == 0
          and other.get("status") == "profile-mismatch",
          {"byteEqual": vanilla_text == other_text, "counters": other})
    compare("mode-off", ["--mip", "2"], payload, "off",
            lambda c: c.get("status") == "off" and c.get("mipHits") == 0 and c.get("stitchHits") == 0)
    compare("narrow-payload-mip4", ["--mip", "4"], narrow, "on",
            lambda c: c.get("mipHits") == 0 and c.get("mipMissLevel") == mipmapped_sprites
            and c.get("stitchHits") == atlas_count - sum(1 for entry in atlases.values() if entry.get("mipmaps")))
    # A maximum size below the range a layout was recorded for: vanilla's StitcherException in both.
    vanilla_text, _ = dump("max512-too-small-vanilla", False, ["--mip", "2", "--max-size", "512"])
    small_text, small = dump("max512-too-small-prebake", True, ["--mip", "2", "--max-size", "512"], payload, "on")
    check("max512-too-small", vanilla_text == small_text and "stitch-failed" in small_text
          and small.get("stitchMisses", 0) >= 1, {"byteEqual": vanilla_text == small_text,
          "stitchFailedAtlases": small_text.count("stitch-failed"), "counters": small})
    # Controls: a payload with one wrong mip byte, or one sprite moved by a pixel, still matches
    # every key; the dumps must differ from vanilla and verify mode must count the mismatch.
    for kind in ("mip", "layout"):
        extra = ["--mip", "2"] if kind == "mip" else ["--mip", "0"]
        vanilla_text, _ = dump(f"tampered-{kind}-vanilla", False, extra)
        wrong_text, wrong = dump(f"tampered-{kind}-prebake", True, extra, tampered[kind], "on")
        verify_text, verified = dump(f"tampered-{kind}-verify", True, extra, tampered[kind], "verify")
        check(f"control-tampered-{kind}", wrong_text != vanilla_text and verify_text == vanilla_text
              and verified.get("verifyMismatches", 0) >= 1,
              {"prebakeDiffers": wrong_text != vanilla_text, "verifyEqualsVanilla": verify_text == vanilla_text,
               "verifyMismatches": verified.get("verifyMismatches")})

    report["pass"] = failures == 0
    report["failures"] = failures
    (out / "gate.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"ATLASPREBAKE_GATE {'PASS' if failures == 0 else 'FAIL'} checks={len(report['checks'])} failures={failures}")
    return 0 if failures == 0 else 1


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build")
    gate_parser = commands.add_parser("gate")
    for sub in (build_parser, gate_parser):
        sub.add_argument("--profile", required=True)
        sub.add_argument("--pack", type=Path, required=True)
        sub.add_argument("--out", type=Path, required=True)
        sub.add_argument("--scratch", type=Path)
    build_parser.add_argument("--summary", type=Path)
    build_parser.add_argument("--max-mip", type=int, default=4)
    build_parser.add_argument("--mips", default="0,1,2,3,4")
    build_parser.add_argument("--anisotropy", default="0")
    gate_parser.add_argument("--payload", type=Path)
    args = parser.parse_args(argv)
    if args.command == "build":
        summary = build(args)
        print(json.dumps({key: summary[key] for key in ("payloadBytes", "gzipBytes", "mipEntries", "stitchRecords")}))
        return 0
    return gate(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
