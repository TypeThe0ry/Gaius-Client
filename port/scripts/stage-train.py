#!/usr/bin/env python3
"""Prepare WasmGC inputs and build the acceptance artifacts for a release train."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from relay_artifact_gate import assert_artifact

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


def prepare_input(profile: str, fallback: Path, output: Path, frozen: Path | None = None) -> Path:
    preserve_existing(output)
    copy_tree(frozen if frozen is not None else fallback, output)
    if frozen is not None:
        carry_shader_toolchain(fallback, output)
        # Current Wasm resources and its page runtime remain authoritative.
        for name in ("vanilla-assets.pack.gz", "vanilla-assets.pack.gz.build.json", "relay-protocol.js"):
            source = fallback / name
            if source.is_file():
                shutil.copy2(source, output / name)
    shutil.copy2(ROOT / "port/web/launcher/index.template.html", output / "index.html")
    shutil.copy2(ROOT / "port/web/boot/gaius-boot.js", output / "gaius-boot.js")
    shutil.copy2(ROOT / "port/web/sw/gaius-sw.js", output / "gaius-sw.js")
    classes = fallback / "classes.wasm"
    if frozen is None:
        classes = output / "classes.wasm"
    if not classes.is_file():
        raise RuntimeError(f"{profile}: WasmGC input has no classes.wasm")
    # The input shell also serves the frozen JavaScript client. Its shader
    # dependency must be installed before the portable builder validates it.
    page_client = output / "classes.js" if frozen is not None else classes
    if not page_client.is_file():
        raise RuntimeError(f"{profile}: page client input is missing: {page_client}")
    run([sys.executable, str(ROOT / "port/scripts/postprocess-index-html.py"),
         str(output / "index.html"), str(page_client), profile])
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


FROZEN_COMPONENTS = ("classes.js", "singleplayer-server.js", "singleplayer-server-worker.js",
                     "gaius-hotpath.wasm", "vanilla-assets.pack.gz", "relay-nodes.json")


def frozen_receipt(directory: Path, wasm_client: Path | None = None) -> dict[str, object]:
    artifacts = []
    if wasm_client is not None and not (wasm_client / "classes.wasm.build.json").is_file():
        raise RuntimeError(f"Wasm client identity is missing: {wasm_client}")
    expected = json.loads((wasm_client / "classes.wasm.build.json").read_text()) if wasm_client is not None else None
    for name in FROZEN_COMPONENTS:
        artifact = directory / name
        sidecar = directory / (name + ".build.json")
        if not artifact.is_file() or not sidecar.is_file():
            raise RuntimeError(f"frozen JavaScript input is incomplete: {artifact}")
        with artifact.open("rb") as stream:
            sha = hashlib.file_digest(stream, "sha256").hexdigest()
        identity = json.loads(sidecar.read_text(encoding="utf-8"))
        roles = {"classes.js": "client", "singleplayer-server.js": "singleplayer-worker",
                 "singleplayer-server-worker.js": "worker-bootstrap", "gaius-hotpath.wasm": "wasm-hotpath",
                 "vanilla-assets.pack.gz": "vanilla-assets", "relay-nodes.json": "relay-registry"}
        if identity.get("kind") != "gaius-build-identity" or identity.get("schemaVersion") != 2 or identity.get("role") != roles[name]:
            raise RuntimeError(f"frozen JavaScript identity contract is invalid: {sidecar}")
        if not isinstance(identity.get("source", {}).get("sha256"), str):
            raise RuntimeError(f"frozen JavaScript source identity is missing: {sidecar}")
        unsigned = dict(identity)
        recorded_identity = unsigned.pop("identitySha256", None)
        encoded = json.dumps(unsigned, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("ascii")
        if recorded_identity != hashlib.sha256(encoded).hexdigest():
            raise RuntimeError(f"frozen JavaScript sidecar identity is invalid: {sidecar}")
        if identity.get("artifact") != {"name": name, "bytes": artifact.stat().st_size, "sha256": sha}:
            raise RuntimeError(f"frozen JavaScript identity differs from payload: {artifact}")
        if expected is not None:
            for key in ("protocol", "storage", "worldVersion"):
                if identity.get(key) != expected.get(key):
                    raise RuntimeError(f"frozen JavaScript {key} is incompatible with Wasm: {artifact}")
            if identity.get("profile", {}).get("id") != expected.get("profile", {}).get("id"):
                raise RuntimeError(f"frozen JavaScript profile mismatch: {artifact}")
        artifacts.append({"name": name, "sha256": sha, "identitySha256": identity.get("identitySha256"),
                          "sourceSha256": identity.get("source", {}).get("sha256")})
    if len({item["sourceSha256"] for item in artifacts}) != 1:
        raise RuntimeError("frozen JavaScript client/server sources differ")
    return {"directory": str(directory.resolve()), "artifacts": artifacts}


def stage_profile(train: Path, profile: str, staged: Path,
                  scratch: Path, allow_mixed_provenance: bool = False,
                  js_role: str = "built", frozen_js_source: str | None = None,
                  frozen: Path | None = None) -> dict[str, object]:
    client, server = wasm_paths(train, profile)
    input_dir = prepare_input(profile, client, scratch / profile, frozen)
    fallback_flags = ["--no-js-fallback"] if frozen is None else ["--frozen-js"]
    site = staged / f"site-{profile}"
    acceptance = staged / f"acceptance-{profile}"
    preserve_existing(site)
    preserve_existing(acceptance)
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), str(input_dir), str(site),
         "--wasm-dist", str(client), "--wasm-server-dist", str(server), *fallback_flags])
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), str(input_dir), str(acceptance),
         "--wasm-dist", str(client), "--wasm-server-dist", str(server), "--no-js-gzip",
         *fallback_flags])
    portable = staged / f"portable-{profile}" / f"Gaius-{profile}.html"
    portable.parent.mkdir(parents=True, exist_ok=True)
    portable_flags = ["--wasm-dist", str(client), "--wasm-server-dist", str(server),
                      "--site-shell", str(acceptance)]
    portable_flags.append("--frozen-js" if frozen is not None else "--no-js-fallback")
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
            "mixedProvenance": allow_mixed_provenance,
            "jsRole": js_role,
            "frozenJsSource": frozen_js_source,
            "jsFallbackIncluded": frozen is not None,
            "frozenJsArtifacts": frozen_receipt(frozen, client) if frozen is not None else None}


def check_total(staged: Path, profiles: list[str]) -> None:
    sites = [str(staged / f"site-{profile}") for profile in profiles]
    run([sys.executable, str(ROOT / "port/scripts/build-pages-site.py"), "--check-total", *sites])


def check_relay_artifacts(staged: Path, profiles: list[str]) -> None:
    for profile in profiles:
        assert_artifact(staged / f"site-{profile}" / "index.html", ROOT)
        assert_artifact(staged / f"portable-{profile}" / f"Gaius-{profile}.html", ROOT)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("train", type=Path)
    parser.add_argument("--frozen-root", type=Path,
                        help="immutable JavaScript dist root with one directory per profile")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--scratch", type=Path)
    parser.add_argument("--profile", action="append", default=[])
    parser.add_argument("--allow-mixed-provenance", action="store_true",
                        help="accept a client and server built from different commits (test trains only)")
    parser.add_argument("--js-role", choices=("built", "frozen", "missing"),
                        help="record whether this train built JS, stages a frozen JS role, or omits JS")
    parser.add_argument("--frozen-js-source",
                        help="explicit source directory recorded when --js-role=frozen")
    args = parser.parse_args(argv)
    train = args.train.resolve()
    marker = train / "JS_ROLE.txt"
    marker_data: dict[str, str] = {}
    if marker.is_file():
        for line in marker.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                marker_data[key] = value
    if args.js_role is None:
        args.js_role = marker_data.get("role", "built")
    if args.frozen_js_source is None:
        args.frozen_js_source = marker_data.get("source")
    if args.js_role == "frozen" and not args.frozen_js_source:
        parser.error("--js-role=frozen requires --frozen-js-source")
    if args.js_role != "frozen" and args.frozen_js_source:
        parser.error("--frozen-js-source is only valid with --js-role=frozen")
    staged = (args.output or train / "staged").resolve()
    staged.mkdir(parents=True, exist_ok=True)
    scratch = (args.scratch or staged / "inputs").resolve()
    results = []
    frozen_root = args.frozen_root or (train / "js-internal/dist")
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
            frozen = None if args.js_role == "missing" else frozen_root / profile
            if frozen is not None:
                binding = frozen_receipt(frozen, train / "dist" / f"wasm-{profile}-client")
                freeze_path = train / "FROZEN-JS.json"
                frozen_set = json.loads(freeze_path.read_text()) if freeze_path.is_file() else {}
                if profile in frozen_set and frozen_set[profile] != binding:
                    raise RuntimeError(f"frozen JavaScript inputs changed after selection: {profile}")
                frozen_set[profile] = binding
                freeze_path.write_text(json.dumps(frozen_set, indent=2) + "\n", encoding="utf-8")
            results.append(stage_profile(train, profile, staged, scratch, args.allow_mixed_provenance,
                                         "frozen" if frozen is not None else "missing",
                                         str(frozen.resolve()) if frozen is not None else None, frozen))
        except RuntimeError as error:
            pending.append(str(error))
    receipt_path = staged / "STAGED.txt"
    previous = json.loads(receipt_path.read_text(encoding="utf-8")) if receipt_path.is_file() else {}
    updated = {str(item["profile"]): item for item in previous.get("profiles", [])}
    updated.update({str(item["profile"]): item for item in results})
    results = list(updated.values())
    if results:
        check_relay_artifacts(staged, list(updated))
        check_total(staged, list(updated))
    receipt = {"profiles": results, "pending": pending}
    (staged / "STAGED.txt").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))
    return 0 if not pending else 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
