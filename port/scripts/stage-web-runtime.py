#!/usr/bin/env python3
"""Stage the browser runtime next to a profile's dist index.html.

    stage-web-runtime.py <dist> [--kernels DIR] [--wasm | --no-wasm] [--shaderxlat FILE] [--png FILE]

Copies, unhashed, what the launcher's gaius-boot.js tag loads in a plain dist directory (the
local static-host deployment input and the source of the portable page):

    gaius-boot.js                 port/web/boot
    gaius-sw.js                   port/web/sw (registered only by the hashed site layout)
    kernels/*.js                  port/web/kernels (runtime, policies, worker, job codecs)
    runtime/quality/*.js          port/web/runtime/quality (graphics quality layer)
    runtime/webgpu/gpu-glue.js,   the WebGPU backend glue (port/web/runtime/webgpu) and the shader
    runtime/webgpu/shaderxlat-job.js  translation job codec (port/native/golden/shaders), loaded
                                  for ?gpu=webgpu, and the quality layer's WGSL post chain
    runtime/webgpu/quality-post.js    (port/web/runtime/webgpu)
    runtime/webgpu/gaius-shaderxlat.wasm  the SPIR-V -> WGSL translator, from --shaderxlat FILE,
                                  else GAIUS_SHADERXLAT_WASM, else the default output of
                                  port/web/runtime/webgpu/build-translator.sh when it exists
    kernels/*.wasm, kernels.json  from --kernels (port/native/build-wasm-variants.sh output)
    gaius-wasm-boot.js,           port/web/runtime/wasmgc (the WebAssembly GC client loader, the
    gaius-jspi.js,                JSPI thread host and the singleplayer Worker's server host);
    gaius-wasm-worker.js,         with --wasm, or by default when the dist holds classes.wasm or
    png-kernel.js                singleplayer-server.wasm; the optional --png FILE is staged as
    gaius-png.wasm               gaius-png.wasm and only loads when the runtime switch is on
                                  (build-wasmgc.sh writes the patched TeaVM loader itself)

and records the kernel job codecs in kernels/kernels.json ("scripts") so the boot script loads
them. Missing optional inputs are skipped: the page then keeps its vanilla paths.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "port" / "web"
WASM_RUNTIME_SCRIPTS = ("gaius-wasm-boot.js", "gaius-jspi.js", "gaius-wasm-worker.js", "png-kernel.js")
WASM_MODULES = ("classes.wasm", "singleplayer-server.wasm")
# The WebGPU backend's page modules (gaius-boot.js WEBGPU_SCRIPTS), by their source.
WEBGPU_MODULES = {
    "runtime/webgpu/gpu-glue.js": WEB / "runtime" / "webgpu" / "gpu-glue.js",
    "runtime/webgpu/shaderxlat-job.js": ROOT / "port" / "native" / "golden" / "shaders" / "shaderxlat-job.js",
    "runtime/webgpu/quality-post.js": WEB / "runtime" / "webgpu" / "quality-post.js",
}
# gaius-boot.js WEBGPU_TRANSLATOR and its default build (build-translator.sh).
WEBGPU_TRANSLATOR = "runtime/webgpu/gaius-shaderxlat.wasm"
DEFAULT_TRANSLATOR = (ROOT / "port" / "native" / "target" / "shaderxlat-wasm" / "wasm32-unknown-unknown"
                      / "release-size" / "gaius_shaderxlat_wasm.wasm")


def copy(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


def stage_wasm(dist: Path) -> list[str]:
    """The WebAssembly GC loader scripts (make-wasm-page.py stages only these)."""
    dist.mkdir(parents=True, exist_ok=True)
    for name in WASM_RUNTIME_SCRIPTS:
        copy(WEB / "runtime" / "wasmgc" / name, dist / name)
    return list(WASM_RUNTIME_SCRIPTS)


def translator_module(explicit: Path | None) -> Path | None:
    """The gaius-shaderxlat module to stage: --shaderxlat, GAIUS_SHADERXLAT_WASM or the default build."""
    if explicit is not None:
        if not explicit.is_file():
            raise FileNotFoundError(f"--shaderxlat {explicit} is not a file")
        return explicit
    configured = os.environ.get("GAIUS_SHADERXLAT_WASM", "").strip()
    if configured:
        path = Path(configured)
        if not path.is_file():
            raise FileNotFoundError(f"GAIUS_SHADERXLAT_WASM={configured} is not a file")
        return path
    return DEFAULT_TRANSLATOR if DEFAULT_TRANSLATOR.is_file() else None


def stage_webgpu(dist: Path, translator: Path | None) -> list[str]:
    """The WebGPU glue, the job codec and, when one is built, the translator module."""
    staged: list[str] = []
    for name, source in WEBGPU_MODULES.items():
        if source.is_file():
            copy(source, dist / name)
            staged.append(name)
    module = translator_module(translator)
    if module is not None:
        copy(module, dist / WEBGPU_TRANSLATOR)
        staged.append(WEBGPU_TRANSLATOR)
    return staged


def stage(dist: Path, kernels: Path | None, wasm: bool | None = None,
          translator: Path | None = None, profile: str | None = None,
          png: Path | None = None) -> list[str]:
    dist.mkdir(parents=True, exist_ok=True)
    staged: list[str] = []
    if wasm is None:
        wasm = any((dist / name).is_file() for name in WASM_MODULES)
    if wasm:
        staged.extend(stage_wasm(dist))
        if png is not None:
            if not png.is_file():
                raise FileNotFoundError(f"--png {png} is not a file")
            copy(png, dist / "gaius-png.wasm")
            staged.append("gaius-png.wasm")
    elif png is not None:
        raise ValueError("--png requires a WebAssembly GC distribution")
    for source, name in ((WEB / "boot" / "gaius-boot.js", "gaius-boot.js"),
                         (WEB / "sw" / "gaius-sw.js", "gaius-sw.js")):
        copy(source, dist / name)
        staged.append(name)
    scripts: list[str] = []
    for source in sorted((WEB / "kernels").glob("*.js")):
        copy(source, dist / "kernels" / source.name)
        staged.append(f"kernels/{source.name}")
        if source.name.endswith("-job.js"):
            scripts.append(f"kernels/{source.name}")
    quality = WEB / "runtime" / "quality"
    if quality.is_dir():
        for source in sorted(quality.glob("*.js")):
            copy(source, dist / "runtime" / "quality" / source.name)
            staged.append(f"runtime/quality/{source.name}")
    staged.extend(stage_webgpu(dist, translator))
    profile = profile or os.environ.get("GAIUS_MINECRAFT_VERSION")
    if profile:
        table = json.loads(subprocess.check_output([
            "node", str(ROOT / "port/tools/kernelmirror/kernelmirror.mjs"), "table", "--profile", profile,
        ], text=True, encoding="utf-8"))
        table_text = json.dumps(table, indent=2) + "\n"
        (dist / "kernels/kernel-semantics.json").write_bytes(table_text.encode("utf-8"))
        source = (WEB / "kernels/kernel-mirror.js").read_text(encoding="utf-8")
        (dist / "kernels/kernel-mirror.js").write_bytes(
            ("globalThis.__gaiusKernelSemantics = " + table_text.rstrip() + ";\n" + source).encode("utf-8"))
        staged.append("kernels/kernel-semantics.json")
    if kernels is not None and (kernels / "kernels.json").is_file():
        for source in sorted(kernels.glob("*.wasm")):
            copy(source, dist / "kernels" / source.name)
            staged.append(f"kernels/{source.name}")
        copy(kernels / "kernels.json", dist / "kernels" / "kernels.json")
    manifest_path = dist / "kernels" / "kernels.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["scripts"] = scripts
        manifest_path.write_bytes((json.dumps(manifest, indent=2) + "\n").encode("utf-8"))
        staged.append("kernels/kernels.json")
    return staged


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dist", type=Path)
    parser.add_argument("--kernels", type=Path)
    parser.add_argument("--profile", help="profile ID from port/versions/*.json; fresh mirror gate, fail closed")
    wasm = parser.add_mutually_exclusive_group()
    wasm.add_argument("--wasm", dest="wasm", action="store_true", default=None,
                      help="stage the WebAssembly GC runtime scripts")
    wasm.add_argument("--no-wasm", dest="wasm", action="store_false")
    parser.add_argument("--shaderxlat", type=Path,
                        help="the gaius-shaderxlat module (runtime/webgpu/gaius-shaderxlat.wasm)")
    parser.add_argument("--png", type=Path,
                        help="optional PNG kernel for a WebAssembly GC distribution (default off at runtime)")
    args = parser.parse_args(argv)
    staged = stage(args.dist.resolve(), args.kernels.resolve() if args.kernels else None, args.wasm,
                   args.shaderxlat.resolve() if args.shaderxlat else None, args.profile,
                   png=args.png.resolve() if args.png else None)
    print(f"Staged {len(staged)} runtime files into {args.dist}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
