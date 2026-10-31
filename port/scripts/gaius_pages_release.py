from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import tarfile
from pathlib import Path


LIMIT_BYTES = 900_000_000
MATRIX_ASSET = "Gaius-pages-matrix.json"


def verified_asset(directory: Path, name: str) -> Path:
    records = (directory / "SHA256SUMS").read_bytes().decode("ascii").split("\n")
    matches = [record for record in records if re.fullmatch(r"[0-9a-f]{64}  " + re.escape(name), record)]
    if len(matches) != 1:
        raise RuntimeError(f"SHA256SUMS must contain exactly one text-mode LF record for {name}")
    path = directory / name
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != matches[0][:64]:
        raise RuntimeError(f"release checksum mismatch: {name}")
    return path


def checked_matrix(matrix: dict[str, object], expected_profiles: list[str] | None = None) -> dict[str, object]:
    if matrix.get("schema") != 1 or matrix.get("limitBytes") != LIMIT_BYTES or matrix.get("retention") != "previous-hashed":
        raise RuntimeError("unsupported Pages matrix")
    profiles = matrix.get("profiles")
    if not isinstance(profiles, list) or not profiles:
        raise RuntimeError("empty Pages matrix")
    names = set()
    for profile in profiles:
        name = profile["id"]
        if not isinstance(name, str) or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name) is None or name in names:
            raise RuntimeError("invalid or duplicate Pages profile")
        names.add(name)
        if profile["path"] != name + "/" or profile["siteArchive"] != f"Gaius-site-{name}.tar.gz" \
                or profile["portableAsset"] != f"Gaius-{name}.html" or profile["runtimes"] != ["wasm"]:
            raise RuntimeError(f"invalid Pages matrix paths or runtimes: {name}")
    if expected_profiles is not None and [profile["id"] for profile in profiles] != expected_profiles:
        raise RuntimeError("Pages matrix profiles do not match the enabled version profiles")
    return matrix


def archive_entries(path: Path) -> dict[str, tarfile.TarInfo]:
    entries = {}
    total = 0
    with tarfile.open(path, "r:gz") as archive:
        for entry in archive:
            if not entry.isfile() or entry.name in entries or re.fullmatch(
                    r"[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*", entry.name) is None:
                raise RuntimeError(f"unsafe or duplicate archive entry: {path.name}: {entry.name}")
            total += entry.size
            if total > LIMIT_BYTES:
                raise RuntimeError(f"archive exceeds the Pages size gate: {path.name}")
            entries[entry.name] = entry
    for required in ("index.html", "gaius-sw.js", "gaius-site.json"):
        if required not in entries or entries[required].size == 0:
            raise RuntimeError(f"missing site entry: {path.name}: {required}")
    return entries


def check_site(path: Path, entries: dict[str, tarfile.TarInfo], profile: str) -> None:
    with tarfile.open(path, "r:gz") as archive:
        descriptor = json.load(archive.extractfile(entries["gaius-site.json"]))
    if descriptor.get("profile") != profile:
        raise RuntimeError(f"site profile mismatch: {path.name}")
    assets = descriptor.get("assets", {})
    if any(url not in entries for url in assets.values()):
        raise RuntimeError(f"missing mapped site asset: {path.name}")
    if any(name in assets for name in ("classes.js", "singleplayer-server.js",
                                       "classes.js.gz", "singleplayer-server.js.gz")):
        raise RuntimeError(f"JavaScript client/server assets are not part of the release: {path.name}")
    if descriptor.get("runtimePolicy") != "wasm-only":
        raise RuntimeError(f"site is not WasmGC-only: {path.name}")
    for name in ("classes.wasm.gz", "singleplayer-server.wasm.gz",
                 "gaius-wasm-gc-runtime.js", "gaius-wasm-boot.js", "gaius-jspi.js", "gaius-wasm-worker.js",
                 "singleplayer-server-worker.js"):
        if name not in assets or entries[assets[name]].size == 0:
            raise RuntimeError(f"incomplete release runtime: {path.name}: {name}")
    for name in ("classes.wasm", "singleplayer-server.wasm"):
        virtual = descriptor.get("inflate", {}).get(name)
        compressed = assets[name + ".gz"]
        match = re.fullmatch(r"(.*)\.wasm\.([0-9a-f]{16})\.gz", compressed)
        if not match or virtual != f"{match.group(1)}.{match.group(2)}.wasm" or virtual in entries or name in assets:
            raise RuntimeError(f"release wasm must ship gzip-only with a virtual compile URL: {path.name}")
    if any(re.fullmatch(r"(?:classes|singleplayer-server)(?:\.[0-9a-f]+)?\.wasm", name) for name in entries):
        raise RuntimeError(f"raw release wasm in site archive: {path.name}")
    if any(re.fullmatch(r"(?:classes|singleplayer-server)(?:\.[0-9a-f]+)?\.js(?:\.gz)?", name)
           for name in entries):
        raise RuntimeError(f"JavaScript client/server file in release archive: {path.name}")


def redirect(target: str, profile: str) -> bytes:
    return (f'<!doctype html>\n<meta charset="utf-8">\n<title>Gaius {profile}</title>\n'
            f'<script>location.replace("{target}" + location.search + location.hash);</script>\n'
            f'<meta http-equiv="refresh" content="0; url={target}">\n').encode("utf-8")


def publication_hint(tag: str, layout: str, profiles: list[str]) -> bytes:
    return (json.dumps({"schema": 1, "tag": tag, "layout": layout, "profiles": profiles},
                       sort_keys=True) + "\n").encode("utf-8")


def assemble(directory: Path, output: Path, fallback_matrix: dict[str, object], kept, classify,
             tag: str, previous: Path | None = None) -> dict[str, object]:
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", tag) is None:
        raise RuntimeError("invalid release tag")
    has_release_matrix = (directory / MATRIX_ASSET).is_file()
    expected_profiles = [profile["id"] for profile in fallback_matrix["profiles"]]
    matrix = checked_matrix(json.loads(verified_asset(directory, MATRIX_ASSET).read_text(encoding="utf-8")),
                            expected_profiles) if has_release_matrix else checked_matrix(fallback_matrix, expected_profiles)
    profiles = matrix["profiles"]
    site_count = sum((directory / profile["siteArchive"]).is_file() for profile in profiles)
    if site_count not in (0, len(profiles)) or has_release_matrix and site_count != len(profiles):
        raise RuntimeError("partial Pages release matrix; refusing single-file fallback")
    layout = "site" if site_count else "single-file"
    files = {}
    extras = {}
    runtime_bytes = {}
    retained_bytes = {}
    checksums = {}
    warnings = []
    if has_release_matrix:
        checksums[MATRIX_ASSET] = hashlib.sha256((directory / MATRIX_ASSET).read_bytes()).hexdigest()
    for profile in profiles:
        name = profile["id"]
        runtime_bytes[name] = {"wasm": 0, "shared": 0}
        asset = profile["siteArchive"] if site_count else profile["portableAsset"]
        source = verified_asset(directory, asset)
        with source.open("rb") as stream:
            checksums[asset] = hashlib.file_digest(stream, "sha256").hexdigest()
        if site_count:
            entries = archive_entries(source)
            check_site(source, entries, name)
            with tarfile.open(source, "r:gz") as archive:
                descriptor = json.load(archive.extractfile(entries["gaius-site.json"]))
            logical_names = {url: logical for logical, url in descriptor["assets"].items()}
            for entry in entries.values():
                files[f"{name}/{entry.name}"] = (source, entry.name, entry.size)
                runtime_bytes[name][classify(logical_names.get(entry.name, entry.name))] += entry.size
            extras[profile["portableAsset"]] = redirect(profile["path"], name)
        else:
            files[profile["portableAsset"]] = (source, None, source.stat().st_size)
            runtime_bytes[name]["wasm"] = source.stat().st_size
            extras[name + "/index.html"] = redirect("../" + profile["portableAsset"], name)
            extras[name + "/gaius-sw.js"] = (
                'self.addEventListener("install", () => self.skipWaiting());\n'
                'self.addEventListener("activate", event => event.waitUntil(self.registration.unregister()));\n'
            ).encode("utf-8")
    if site_count and previous is not None:
        additions = {}
        try:
            archives = list(previous.glob("Gaius-site-*.tar.gz"))
            if not archives:
                raise RuntimeError("no previous site archives")
            for source in archives:
                name = source.name[len("Gaius-site-"):-len(".tar.gz")]
                if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name) is None:
                    raise RuntimeError(f"invalid retained profile: {name}")
                source = verified_asset(previous, source.name)
                entries = archive_entries(source)
                check_site(source, entries, name)
                for entry in entries.values():
                    target = f"{name}/{entry.name}"
                    if kept(entry.name) and target not in files:
                        additions[target] = (source, entry.name, entry.size)
        except RuntimeError as error:
            if str(error).startswith("invalid retained profile:"):
                raise
            warnings.append(f"previous generation not verifiable: {error}")
        except (OSError, ValueError, KeyError, tarfile.TarError) as error:
            warnings.append(f"previous generation not verifiable: {error}")
        else:
            files.update(additions)
            for target, (_, _, size) in additions.items():
                name = target.split("/", 1)[0]
                retained_bytes[name] = retained_bytes.get(name, 0) + size
    extras["gaius-pages.json"] = publication_hint(tag, layout, [profile["id"] for profile in profiles])
    if has_release_matrix:
        extras[MATRIX_ASSET] = (directory / MATRIX_ASSET).read_bytes()
    total = sum(size for _, _, size in files.values()) + sum(len(data) for data in extras.values())
    report = {"layout": layout, "profiles": runtime_bytes, "retainedBytes": retained_bytes,
              "totalBytes": total, "limitBytes": LIMIT_BYTES, "ok": total <= LIMIT_BYTES,
              "checksums": checksums, "warnings": warnings}
    if not report["ok"]:
        raise RuntimeError(f"Pages total {total} exceeds hard limit {LIMIT_BYTES} bytes (including retained files)")
    if output.exists() and (output.is_symlink() or output.is_junction() or any(output.iterdir())):
        raise RuntimeError(f"refusing to overwrite nonempty Pages output: {output}")
    output.mkdir(parents=True, exist_ok=True)
    grouped = {}
    for target, (source, entry, _) in files.items():
        grouped.setdefault(source, {})[entry] = target
    for source, targets in grouped.items():
        if None in targets:
            shutil.copyfile(source, output / targets[None])
            continue
        with tarfile.open(source, "r:gz") as archive:
            for entry in archive:
                if entry.name not in targets:
                    continue
                target = output / targets[entry.name]
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(entry) as body, target.open("wb") as destination:
                    shutil.copyfileobj(body, destination)
    for name, data in extras.items():
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    actual = sum(path.stat().st_size for path in output.rglob("*") if path.is_file())
    if actual != total:
        raise RuntimeError(f"Pages size changed during assembly: planned {total}, actual {actual}")
    return report


def main(argv: list[str], fallback_matrix: dict[str, object], kept, classify) -> int:
    parser = argparse.ArgumentParser(description="Assemble checksum-verified Pages release assets without deploying")
    parser.add_argument("assets", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--previous-release", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args(argv)
    report = assemble(args.assets, args.output, fallback_matrix, kept, classify, args.tag, args.previous_release)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0
