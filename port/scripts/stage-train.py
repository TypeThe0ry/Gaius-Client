#!/usr/bin/env python3
"""Prepare WasmGC inputs and build the acceptance artifacts for a release train."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
READY_RE = re.compile(r"^(\S+) client \S*/classes\.wasm ")


def run(command: list[str], cwd: Path = ROOT) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def profiles_from_ready(train: Path) -> list[str]:
    ready = train / "READY.txt"
    if not ready.is_file():
        return []
    profiles = []
    for line in ready.read_text(encoding="utf-8").splitlines():
        match = READY_RE.match(line)
        if match and match.group(1) not in profiles:
            profiles.append(match.group(1))
    return profiles


def copy_tree(source: Path, target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for item in source.iterdir():
        destination = target / item.name
        if item.is_dir() and not item.is_symlink():
            shutil.copytree(item, destination, dirs_exist_ok=True)
        else:
            shutil.copy2(item, destination)


def preserve_existing(path: Path) -> None:
    if not path.exists():
        return
    index = 1
    while path.with_name(path.name + f".previous-{index}").exists():
        index += 1
    path.rename(path.with_name(path.name + f".previous-{index}"))


SHADER_TOOLCHAIN_PAYLOAD = (
    "gaius-shader-toolchain.js",
    "gaius-shader-toolchain.json",
    "gaius-shaderc.js",
    "gaius-shaderc.wasm",
    "gaius-spvc.js",
    "gaius-spvc.wasm",
)


def carry_shader_toolchain(client: Path, output: Path) -> None:
    """Carry the Wasm client's shader toolchain into the staging input."""
    if not (client / "gaius-shader-toolchain.js").is_file():
        return
    for name in SHADER_TOOLCHAIN_PAYLOAD:
        source = client / name
        if source.is_file() and not (output / name).exists():
            shutil.copy2(source, output / name)


def prepare_input(profile: str, fallback: Path, output: Path) -> Path:
    preserve_existing(output)
    copy_tree(fallback, output)
    shutil.copy2(ROOT / "port/web/launcher/index.template.html", output / "index.html")
    shutil.copy2(ROOT / "port/web/boot/gaius-boot.js", output / "gaius-boot.js")
    shutil.copy2(ROOT / "port/web/sw/gaius-sw.js", output / "gaius-sw.js")
    classes = output / "classes.wasm"
    if not classes.is_file():
        raise RuntimeError(f"{profile}: WasmGC input has no classes.wasm")
    run([sys.executable, str(ROOT / "port/scripts/postprocess-index-html.py"),
         str(output / "index.html"), str(classes), profile])
    return output


def wasm_paths(train: Path, profile: str) -> tuple[Path, Path]:
    client = train / "dist" / f"wasm-{profile}-client"
    server = train / "dist" / f"wasm-{profile}-server"
    if not (client / "classes.wasm").is_file():
        raise RuntimeError(f"{profile}: client artifact is not ready")
    ready_lines = (train / "READY.txt").read_text(encoding="utf-8").splitlines()
    if not any(line.startswith(profile + " server ") for line in ready_lines):
        raise RuntimeError(f"{profile}: server artifact is not ready (no READY.txt server gate)")
    if not (server / "singleplayer-server.wasm").is_file():
        raise RuntimeError(f"{profile}: server artifact is not ready")
    return client, server


def stage_profile(train: Path, profile: str, staged: Path,
                  scratch: Path, allow_mixed_provenance: bool = False) -> dict[str, object]:
    client, server = wasm_paths(train, profile)
    input_dir = prepare_input(profile, client, scratch / profile)
    site = staged / f"site-{profile}"
    acceptance = staged / f"acceptance-{profile}"
    preserve_existing(site)
    preserve_existing(acceptance)
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), str(input_dir), str(site),
         "--wasm-dist", str(client), "--wasm-server-dist", str(server), "--no-js-fallback"])
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), str(input_dir), str(acceptance),
         "--wasm-dist", str(client), "--wasm-server-dist", str(server), "--no-js-gzip",
         "--no-js-fallback"])
    portable = staged / f"portable-{profile}" / f"Gaius-{profile}.html"
    portable.parent.mkdir(parents=True, exist_ok=True)
    portable_flags = ["--wasm-dist", str(client), "--wasm-server-dist", str(server),
                      "--site-shell", str(acceptance)]
    portable_flags.append("--no-js-fallback")
    if allow_mixed_provenance:
        portable_flags.append("--allow-mixed-provenance")
    previous_profile = os.environ.get("GAIUS_VERSION_PROFILE_PATH")
    os.environ["GAIUS_VERSION_PROFILE_PATH"] = f"versions/{profile}.json"
    try:
        run([sys.executable, str(ROOT / "port/scripts/build-portable-html.py"), str(input_dir), str(portable),
             *portable_flags])
    finally:
        if previous_profile is None:
            os.environ.pop("GAIUS_VERSION_PROFILE_PATH", None)
        else:
            os.environ["GAIUS_VERSION_PROFILE_PATH"] = previous_profile
    return {"profile": profile, "site": str(site), "acceptance": str(acceptance), "portable": str(portable),
            "mixedProvenance": allow_mixed_provenance}


def check_total(staged: Path, profiles: list[str]) -> None:
    sites = [str(staged / f"site-{profile}") for profile in profiles]
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), "--check-total", *sites])


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("train", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--scratch", type=Path)
    parser.add_argument("--profile", action="append", default=[])
    parser.add_argument("--allow-mixed-provenance", action="store_true",
                        help="accept a client and server built from different commits (test trains only)")
    args = parser.parse_args(argv)
    train = args.train.resolve()
    staged = (args.output or train / "staged").resolve()
    staged.mkdir(parents=True, exist_ok=True)
    scratch = (args.scratch or staged / "inputs").resolve()
    results = []
    available = profiles_from_ready(train)
    requested = set(args.profile)
    pending = [f"{profile}: no client artifact listed in READY.txt"
               for profile in sorted(requested - set(available))]
    if not available and not requested:
        pending.append("no client artifacts listed in READY.txt")
    for profile in available:
        if args.profile and profile not in args.profile:
            continue
        if not (train / "dist" / f"wasm-{profile}-client").is_dir():
            pending.append(f"{profile}: Wasm client input is missing")
            continue
        try:
            results.append(stage_profile(train, profile, staged, scratch, args.allow_mixed_provenance))
        except RuntimeError as error:
            pending.append(str(error))
    receipt_path = staged / "STAGED.txt"
    previous = json.loads(receipt_path.read_text(encoding="utf-8")) if receipt_path.is_file() else {}
    updated = {str(item["profile"]): item for item in previous.get("profiles", [])}
    updated.update({str(item["profile"]): item for item in results})
    results = list(updated.values())
    if results:
        check_total(staged, list(updated))
    receipt = {"profiles": results, "pending": pending}
    (staged / "STAGED.txt").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))
    return 0 if not pending else 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
