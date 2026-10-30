#!/usr/bin/env python3
"""Stages a WasmGC-only dist next to classes.wasm.

usage: make-wasm-page.py <javascript-dist> <wasm-dist> [--file-payloads | --gzip-payload]

build-wasmgc.sh runs this after it wrote classes.wasm (and singleplayer-server.wasm) into the
wasm dist. The pieces live with the scripts that own each distribution form; this is the
command that puts them together for a wasm dist:

  1. Every file of the internal JavaScript dist is copied except its pages (index.html and the
     portable Gaius.html with Gaius.manifest.json). Client and server JavaScript bundles are not
     release inputs; existing support files are only replaced by newer ones.
  2. The page runtime always comes from this tree, not from the JavaScript dist, which may have
     been built from another tree (an older one has no WasmGC runtime configuration):
     stage-web-runtime.py stages gaius-boot.js, gaius-sw.js,
     the kernel and quality modules and the WebAssembly GC loader scripts (gaius-wasm-boot.js,
     gaius-jspi.js, gaius-wasm-worker.js), and the Worker bootstrap the wasm server shares
     (singleplayer-server-worker.js, from port/web/singleplayer/server-worker-bootstrap.js) is
     replaced too, with a new identity sidecar. Precompressed copies (.gz, .br) of a replaced
     file are written again, so a host that serves them never hands out the old bytes.
     build-wasmgc.sh wrote the patched TeaVM loader gaius-wasm-gc-runtime.js.
  3. --file-payloads (alias --gzip-payload) writes <file>.payload.js for classes.wasm, the
     vanilla pack, the shader toolchain modules and the hotpath (the files and the page index of
     build-portable-html.py write_file_payloads), so the dist also starts when it is opened from
     disk. The base64 text of a payload is the body comment of a function, and the payload
     script defines globalThis.__gaiusFilePayloadData[<file>] as a getter that returns the text
     through Function.prototype.toString (build-portable-html.py file_payload_script).
  4. index.html and index-wasm.html get the WasmGC configuration before the boot script
     (postprocess-index-html.py runtime_config_script): window.__gaiusRuntimes names the WasmGC
     builds and window.__gaiusFilePayloads names the payload scripts. When build-wasmgc.sh staged
     the shader toolchain for a WebGPU front-end that compiles GLSL in the page (26.2 and 1.21.11:
     gaius-shader-toolchain.js next to index.html) and the page does not load it itself,
     window.__gaiusWebGpuShaderToolchain names its loader; gaius-boot.js loads it for
     ?gpu=webgpu only.

The portable single HTML of a wasm dist is build-portable-html.py --wasm-dist <wasm-dist>.
"""
from __future__ import annotations

import gzip
import importlib.util
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SCRIPTS = ROOT / "port" / "scripts"
WORKER_BOOTSTRAP = ROOT / "port" / "web" / "singleplayer" / "server-worker-bootstrap.js"
# The JavaScript dist's pages: rebuilt here (index) or not valid for this dist (portable).
SKIP_PREFIXES = ("Gaius.html", "Gaius.manifest.json", "index.html", "index-wasm")
# Outputs of the M0 version of this script that the payload scripts replace.
STALE_OUTPUTS = ("classes.wasm.gz.js", "index-wasm-file.html")
PAGES = ("index.html", "index-wasm.html")


def load(name: str, file_name: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / file_name)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {file_name}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def refresh_precompressed(path: Path) -> None:
    """Writes the .gz and .br copies of `path` again where the dist has them."""
    data = path.read_bytes()
    gz = path.with_name(path.name + ".gz")
    if gz.is_file():
        gz.write_bytes(gzip.compress(data, compresslevel=9, mtime=0))
    br = path.with_name(path.name + ".br")
    if not br.is_file():
        return
    brotli = shutil.which("brotli")
    if brotli:
        command = [brotli, "-f", "-q", "11", str(path)]
    elif shutil.which("node"):
        command = ["node", str(SCRIPTS / "compress-brotli.mjs"), str(path)]
    else:
        # Without a compressor the stale copy goes: a host then serves the file itself.
        br.unlink()
        return
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL)


def stage_tree_runtime(wasm_dist: Path, stage_web_runtime) -> list[str]:
    """The page runtime and the Worker bootstrap of this tree, over the copied ones (step 2)."""
    staged = stage_web_runtime.stage(wasm_dist, None, True)
    worker = wasm_dist / "singleplayer-server-worker.js"
    if worker.is_file() or (wasm_dist / "singleplayer-server.wasm").is_file():
        shutil.copyfile(WORKER_BOOTSTRAP, worker)
        staged.append(worker.name)
        sidecar = worker.with_name(worker.name + ".build.json")
        try:
            identity = load("gaius_build_identity", "gaius_build_identity.py")
            identity.write_sidecar(ROOT, "worker-bootstrap", worker, sidecar)
            staged.append(sidecar.name)
        except Exception as error:  # noqa: BLE001 - any failure leaves the dist without it
            # The copied sidecar describes the replaced bytes: leave no stale identity behind.
            for stale in (sidecar, *(sidecar.with_name(sidecar.name + ext) for ext in (".gz", ".br"))):
                stale.unlink(missing_ok=True)
            print(f"warning: no identity sidecar for {worker.name}: {error}", file=sys.stderr)
    for name in staged:
        refresh_precompressed(wasm_dist / name)
    return staged


def copy_javascript_dist(js_dist: Path, wasm_dist: Path) -> None:
    for entry in js_dist.iterdir():
        if entry.name.startswith(SKIP_PREFIXES):
            continue
        target = wasm_dist / entry.name
        if entry.is_dir():
            shutil.copytree(entry, target, dirs_exist_ok=True)
        elif not target.exists() or target.stat().st_mtime < entry.stat().st_mtime:
            shutil.copy2(entry, target)


def main(argv: list[str]) -> int:
    args = [arg for arg in argv if not arg.startswith("--")]
    options = [arg for arg in argv if arg.startswith("--")]
    unknown = set(options) - {"--file-payloads", "--gzip-payload"}
    if len(args) != 2 or unknown:
        print(__doc__, file=sys.stderr)
        return 2
    js_dist, wasm_dist = Path(args[0]).resolve(), Path(args[1]).resolve()
    if not (wasm_dist / "classes.wasm").is_file():
        print(f"no classes.wasm in {wasm_dist}", file=sys.stderr)
        return 1
    stage_web_runtime = load("gaius_stage_web_runtime", "stage-web-runtime.py")
    postprocess = load("gaius_postprocess_index_html", "postprocess-index-html.py")

    copy_javascript_dist(js_dist, wasm_dist)
    staged = stage_tree_runtime(wasm_dist, stage_web_runtime)
    for name in STALE_OUTPUTS:
        stale = wasm_dist / name
        if stale.is_file():
            stale.unlink()

    payloads = None
    if "--file-payloads" in options or "--gzip-payload" in options:
        portable = load("gaius_build_portable_html", "build-portable-html.py")
        payloads = portable.write_file_payloads(wasm_dist)

    runtimes: dict[str, dict[str, str]] = {"wasm": {"client": "classes.wasm"}}
    if (wasm_dist / "singleplayer-server.wasm").is_file():
        runtimes["wasm"]["server"] = "singleplayer-server.wasm"
    js_page = (js_dist / "index.html").read_text(encoding="utf-8").replace("\r\n", "\n")
    profile_id = postprocess.profile_from_index(js_page)
    profile = postprocess.load_profile_for_version(profile_id)
    js_page = postprocess.patch_early_profile_id(js_page, profile)
    webgpu_toolchain = None
    if (wasm_dist / "gaius-shader-toolchain.js").is_file():
        token = postprocess.content_token(
            *(wasm_dist / name for name in postprocess.SHADER_TOOLCHAIN_FILES))
        webgpu_toolchain = {"loader": f"gaius-shader-toolchain.js?v={token}", "version": token}
    js_page = re.sub(
        rf'  <script {re.escape(postprocess.SHADER_TOOLCHAIN_MARKER)}[^>]*></script>\n',
        "",
        js_page,
    )
    script = postprocess.runtime_config_script(runtimes, payloads, webgpu_toolchain)
    relay_protocol = wasm_dist / "relay-protocol.js"
    if relay_protocol.is_file():
        declaration = relay_protocol.read_text(encoding="utf-8").replace("</", "<\\/")
        script += "  <script data-gaius-relay-protocol>\n" + declaration + "  </script>\n"
    page = postprocess.apply_runtime_config(js_page, script)
    for name in PAGES:
        (wasm_dist / name).write_text(page, encoding="utf-8", newline="\n")
    print(f"staged the WebAssembly GC dist in {wasm_dist} (WasmGC-only, "
          f"{len(staged)} page runtime files from this tree, {len(payloads or {})} file payloads)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
