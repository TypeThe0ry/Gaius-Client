#!/usr/bin/env python3
"""Local receipts and fail-closed gates for release-train.sh (no downloads)."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import re
from datetime import datetime, timezone

import gaius_build_identity as identity

ROOT = Path(__file__).resolve().parents[2]
MIN_FREE = 10 * 1024**3


def normalized_release_version() -> str:
    version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    return re.sub(r"-pre\.(\d+)(?=$|[+])", r"-pre\1", version)


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def profiles() -> list[str]:
    entries = [json.loads(p.read_text(encoding="utf-8"))
               for p in (ROOT / "port/versions").glob("*.json")]
    return [p["id"] for p in sorted(entries, key=lambda p: p.get("official", {}).get("releaseTime", ""), reverse=True)
            if p.get("pages", {}).get("enabled") and p["pages"].get("runtimes") == ["wasm"]]


def write(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def receipt(path: Path) -> dict:
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha(path)}


def disk(path: Path) -> None:
    while not path.exists():
        path = path.parent
    free = shutil.disk_usage(path).free
    print(f"Disk gate: {free} bytes free; required {MIN_FREE}", flush=True)
    if free < MIN_FREE:
        raise RuntimeError("At least 10 GiB free is required")


def clean(expected: str | None = None) -> str:
    commit = git("rev-parse", "HEAD")
    if git("status", "--porcelain", "--untracked-files=all"):
        raise RuntimeError("Source checkout must be clean, including untracked files")
    if expected is not None and commit != expected:
        raise RuntimeError("Source commit changed during release train")
    return commit


def resource_map(path: Path) -> dict:
    result = json.loads(path.read_text(encoding="utf-8"))
    for profile in profiles():
        for key in ("build", "dist"):
            directory = Path(result[profile][key])
            if not directory.is_absolute() or not directory.is_dir():
                raise RuntimeError(f"Missing absolute resource directory: {profile}/{key}: {directory}")
            result[profile][key] = str(directory.resolve())
        # compile_train copies this pack into every Wasm role; fail before hours of compiling.
        pack = Path(result[profile]["dist"]) / "vanilla-assets.pack.gz"
        if not pack.is_file() or not Path(f"{pack}.build.json").is_file():
            raise RuntimeError(f"Missing cached vanilla resource pack or its identity: {pack}")
    return result


def preflight(out: Path, resources: Path, mode: str) -> None:
    if out == ROOT or ROOT in out.parents:
        raise RuntimeError("Train output must be outside the source checkout")
    if out.exists() and any(out.iterdir()):
        raise RuntimeError("Train output must be new or empty; never mix trains")
    commit = clean()
    disk(out)
    resources_data = resource_map(resources)
    config = json.loads((ROOT / "port/config.json").read_text())
    maven = ROOT / "port/work/tools" / f"apache-maven-{config['mavenVersion']}/bin/mvn"
    if not maven.is_file():
        raise RuntimeError(f"Offline Maven launcher missing (downloads prohibited): {maven}")
    shader = os.environ.get("GAIUS_SHADER_TOOLCHAIN_PREBUILT", "")
    if not shader or not Path(shader).is_dir():
        raise RuntimeError("Set GAIUS_SHADER_TOOLCHAIN_PREBUILT to a cached toolchain; source fetching is prohibited")
    optimizer = os.environ.get("GAIUS_WASM_OPT", "wasm-opt")
    if not shutil.which(optimizer):
        raise RuntimeError("Cached wasm-opt is missing; set GAIUS_WASM_OPT")
    out.mkdir(parents=True, exist_ok=True)
    write(out / "SOURCE.json", {"commit": commit, "internalJsMode": mode,
                               "resources": resources_data})


def run(command: list[str], env: dict | None = None, log: Path | None = None) -> None:
    if command and command[0] == "bash":
        # On Windows CreateProcess finds System32\bash.exe (WSL) before PATH, so resolve the
        # bash of the calling environment (Git Bash) through PATH explicitly.
        command = [shutil.which("bash", path=(env or os.environ).get("PATH")) or "bash", *command[1:]]
    print("+ " + " ".join(command), flush=True)
    if log:
        log.parent.mkdir(parents=True, exist_ok=True)
        print(f"  output: {log}", flush=True)
        with log.open("w", encoding="utf-8") as stream:
            status = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT).returncode
        if status:
            # The train log otherwise only names the failed command; show why it failed.
            tail = log.read_text(encoding="utf-8", errors="replace").splitlines()[-40:]
            print(f"--- last {len(tail)} lines of {log} ---", *tail, "---", sep="\n", file=sys.stderr, flush=True)
            raise subprocess.CalledProcessError(status, command)
    else:
        subprocess.run(command, cwd=ROOT, env=env, check=True)


def build_env(out: Path, profile: str, role: str, target: str) -> dict:
    env = dict(os.environ)
    # Prevent inherited diagnostic/build roots from changing the release configuration.
    for key in ("MAVEN_OPTS", "JDK_JAVA_OPTIONS", "GAIUS_BRINGUP", "GAIUS_EXTRA_PATCHERS",
                "GAIUS_PREPARE_ONLY", "GAIUS_SKIP_CLIENT_BUILD", "GAIUS_SKIP_OVERLAY_BUILD",
                "GAIUS_DEBUG_PACKET_PARITY", "GAIUS_DEBUG_CHUNK_GENERATION_ORDER",
                "GAIUS_TEAVM_ASYNC_DUMP", "GAIUS_TEAVM_JSPI", "GAIUS_SKIP_KERNELS",
                "GAIUS_KERNELS_PREPARED", "GAIUS_OVERLAY_LOCK_HELD", "GAIUS_MAIN_CLASS",
                "GAIUS_TARGET_FILE", "GAIUS_POM", "GAIUS_TEAVM_ROLE", "GAIUS_RESOURCE_DIRECTORY",
                "GAIUS_MAVEN_DIRECTORY", "GAIUS_EXCLUDED_LIBRARY_PREFIXES", "GAIUS_KERNEL_DIRECTORY",
                "GAIUS_WASMGC_SYMBOL_DIRECTORY", "GAIUS_WASMGC_RESOURCE_ROOT", "GAIUS_WASMGC_RESOURCE_DIST"):
        env.pop(key, None)
    suffix = f"wasm-{profile}-{role}" if target == "wasmgc" else profile
    # T17 may raise the memory budget for the GC client on a quiet build
    # machine. Keep the worker's larger default independent of that override.
    if role == "client" and target == "wasmgc":
        teavm_heap = env.get("GAIUS_TEAVM_MAX_HEAP", "8g")
    else:
        teavm_heap = "14g"
    env.update(GAIUS_VERSION_PROFILE_PATH=f"versions/{profile}.json", GAIUS_TEAVM_TARGET=target,
               GAIUS_BUILD_ROOT=str(out / "build" / suffix),
               GAIUS_OVERLAY_DIRECTORY=str(out / "overlays" / suffix),
               GAIUS_DIST_DIRECTORY=str(out / "dist" / suffix),
               GAIUS_TARGET_DIRECTORY=str(out / "dist" / suffix),
               CARGO_TARGET_DIR=str(out / "cargo-target"),
               GAIUS_TEAVM_MAX_HEAP=teavm_heap,
               GAIUS_TEA_OPTIMIZATION_LEVEL="ADVANCED", GAIUS_ASSERTIONS_REMOVED="true",
               GAIUS_SOURCE_MAPS="false", GAIUS_DEBUG_INFO="true" if target == "wasmgc" else "false",
               GAIUS_MINIFYING="false" if target == "wasmgc" else "true",
               GAIUS_WASM_DEBUG_INFO_LOCATION="EXTERNAL", GAIUS_SHORT_FILE_NAMES="true",
               GAIUS_TEAVM_STRIP_TELEMETRY="true", GAIUS_TEAVM_CLIENT_STRIP_TELEMETRY="true",
               GAIUS_TEAVM_WORKER_STRIP_TELEMETRY="true")
    return env


def compile_train(out: Path, resources: Path, mode: str) -> None:
    clean(json.loads((out / "SOURCE.json").read_text())["commit"])
    inputs = resource_map(resources)
    for profile in profiles():
        if mode == "rebuild":
            js_out = out / "js-internal"
            env = build_env(js_out, profile, "client", "js")
            # Do not call fetch-version.sh or remap-client.sh: inputs must be cached.
            run(["bash", "port/scripts/build-version-release.sh", profile], env,
                out / "logs" / f"{profile}-js.log")
        for role in ("client", "server"):
            env = build_env(out, profile, role, "wasmgc")
            # This phase only compiles roles. Staging uses the current launcher later;
            # old cached index pages must not enter make-wasm-page.py here.
            if mode == "rebuild":
                # The internal resource build carries the current page runtime, shader toolchain
                # and Worker bootstrap; make-wasm-page.py copies them next to the Wasm client.
                resource_dist = out / "js-internal" / "dist" / profile
                # Its build root also carries the current generated resource list (language
                # files added since the cached resource build); the cached root can be stale.
                resource_build = out / "js-internal" / "build" / profile
            else:
                resource_build = Path(inputs[profile]["build"])
                resource_dist = out / "resource-inputs" / profile
                resource_dist.mkdir(parents=True, exist_ok=True)
                pack = Path(inputs[profile]["dist"]) / "vanilla-assets.pack.gz"
                if not (resource_dist / pack.name).exists():
                    shutil.copy2(pack, resource_dist / pack.name)
            env.update(GAIUS_WASMGC_RESOURCE_ROOT=str(resource_build),
                       GAIUS_WASMGC_RESOURCE_DIST=str(resource_dist))
            command = ["bash", "port/scripts/build-wasmgc.sh", "--release"]
            if role == "server":
                command += ["--role", "singleplayer-worker"]
            run([*command, profile], env, out / "logs" / f"{profile}-{role}.log")


def verify(out: Path) -> None:
    source = json.loads((out / "SOURCE.json").read_text())
    clean(source["commit"])
    artifacts = []
    ready = []
    for profile in profiles():
        for role, name, identity_role in (("client", "classes.wasm", "wasm-client"),
                                          ("server", "singleplayer-server.wasm", "wasm-singleplayer-worker")):
            env = build_env(out, profile, role, "wasmgc")
            path = Path(env["GAIUS_DIST_DIRECTORY"]) / name
            run(["node", "-e", "if(!WebAssembly.validate(require('fs').readFileSync(process.argv[1])))process.exit(1)", str(path)])
            run(["node", "port/tools/wasm/wasm-data-check.mjs", str(path)])
            run([sys.executable, "port/scripts/gaius_build_identity.py", "verify", "--root", str(ROOT),
                 "--role", identity_role, "--artifact", str(path)], env)
            run(["node", "port/tools/wasm/wasm-size.mjs", "--no-compress", "--json",
                 str(out / "logs" / f"{profile}-{role}-size.json"), str(path)])
            artifacts.extend([receipt(path), receipt(Path(str(path) + ".build.json"))])
            ready.append(f"{profile} {role} {path.as_posix()} {sha(path)}")
    write(out / "artifacts.json", artifacts)
    (out / "READY.txt").write_text("\n".join(ready) + "\n", encoding="utf-8")


def manifest(out: Path) -> None:
    source = json.loads((out / "SOURCE.json").read_text())
    clean(source["commit"])
    matrix = json.loads((out / "accept/matrix.json").read_text())
    cases = {"site-wasm-auto", "site-wasm-webgl2", "site-wasm-webgpu",
             "acceptance-default", "reload-soak", "portable-file", "quit-game-site",
             "quit-game-portable", "fallback-webgpu-unavailable", "unsupported-browser"}
    expected = {(p, c) for p in profiles() for c in cases}
    rows = matrix["rows"]
    if (matrix["verdict"] != "PASS" or len(rows) != len(expected) or matrix.get("pending")
            or {(r["profile"], r["id"]) for r in rows} != expected
            or any(r["verdict"] != "PASS" for r in rows)):
        raise RuntimeError(f"Release acceptance must pass all {len(expected)} distinct cases")
    # Recheck recorded artifacts after acceptance, then hash the complete staged payload.
    artifacts = json.loads((out / "artifacts.json").read_text())
    for item in artifacts:
        if receipt(Path(item["path"])) != item:
            raise RuntimeError(f"Artifact changed during acceptance: {item['path']}")
    staged = [receipt(p) for p in sorted((out / "staged").rglob("*")) if p.is_file()]
    write(out / "release-manifest.json", {"schema": 1, "version": normalized_release_version(),
          "finishedAt": datetime.now(timezone.utc).isoformat(), "source": source,
          "profiles": profiles(),
          "artifacts": artifacts, "staged": staged,
          "acceptance": {"verdict": "PASS", "passed": len(rows), "matrix": receipt(out / "accept/matrix.json")}})
    print("Release train PASS: release-manifest.json", flush=True)


def main() -> None:
    action, *args = sys.argv[1:]
    if action == "profiles":
        print("\n".join(profiles()))
    elif action == "disk":
        disk(Path(args[0]))
    elif action == "preflight":
        preflight(Path(args[0]).resolve(), Path(args[1]), args[2])
    elif action == "compile":
        compile_train(Path(args[0]), Path(args[1]), args[2])
    elif action == "plan":
        out, mode = Path(args[0]), args[1]
        for profile in profiles():
            if mode == "rebuild":
                print(f"  Internal JS resources: bash port/scripts/build-version-release.sh {profile} -> {out / 'js-internal/dist' / profile}")
            for role in ("client", "server"):
                option = "" if role == "client" else " --role singleplayer-worker"
                print(f"  WasmGC: bash port/scripts/build-wasmgc.sh --release{option} {profile} -> {out / 'dist' / f'wasm-{profile}-{role}'}")
    elif action == "verify":
        verify(Path(args[0]))
    elif action == "manifest":
        manifest(Path(args[0]))
    else:
        raise RuntimeError(f"Unknown action: {action}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Release train gate failed: {error}", file=sys.stderr)
        sys.exit(1)
