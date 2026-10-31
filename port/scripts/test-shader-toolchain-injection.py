#!/usr/bin/env python3
"""Checks the shader toolchain loader injection of postprocess-index-html.py (PLAN D5, contract C7).

* A client whose classes.js never names window.__gaiusShaderToolchain (every
  26.2 build) gets exactly the page it got before: the output equals the output
  of the same postprocess with the injection function disabled.
* A client that calls the toolchain (26.3) gets one loader script in <head>
  and one await of window.__gaiusShaderToolchainReady before main(args); a
  second postprocess run changes nothing; the loader URL carries the content
  token of the toolchain files.
* Missing toolchain files are a warning, and an error with
  GAIUS_SHADER_TOOLCHAIN_STRICT=1.
* The generated boot script still passes a Node syntax check.
"""

from __future__ import annotations

import importlib.util
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from verification_profiles import CANONICAL_ID, RENDERPEARL_ID

ROOT = Path(__file__).resolve().parents[2]
POSTPROCESS = ROOT / "port/scripts/postprocess-index-html.py"
TEMPLATE = ROOT / "port/web/launcher/index.template.html"
FILES = ("gaius-shader-toolchain.js", "gaius-shaderc.js", "gaius-shaderc.wasm", "gaius-spvc.js", "gaius-spvc.wasm")


def load_module():
    spec = importlib.util.spec_from_file_location("postprocess_index_html", POSTPROCESS)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def postprocess(index: Path, classes: Path, version: str, asset_index: str, env: dict | None = None):
    return subprocess.run(
        [sys.executable, str(POSTPROCESS), str(index), str(classes), version, asset_index],
        text=True, capture_output=True, check=False, env={**os.environ, **(env or {})},
    )


def node_check(html: str, directory: Path) -> None:
    node = shutil.which("node")
    if not node:
        return
    for number, source in enumerate(re.findall(r"<script(?:\s[^>]*)?>(.*?)</script>", html, re.DOTALL | re.IGNORECASE)):
        script = directory / f"inline-{number}.js"
        script.write_text(source, encoding="utf-8")
        result = subprocess.run([node, "--check", str(script)], text=True, capture_output=True, check=False)
        if result.returncode != 0:
            raise AssertionError(f"inline script {number} failed the Node syntax check:\n{result.stderr}")


def main() -> int:
    module = load_module()
    with tempfile.TemporaryDirectory(prefix="gaius-shader-toolchain-injection-") as temporary:
        directory = Path(temporary)

        # --- a client without the toolchain (26.2): byte-identical to the page without the function
        plain_dir = directory / "plain"
        reference_dir = directory / "reference"
        for target in (plain_dir, reference_dir):
            target.mkdir()
            (target / "index.html").write_text(TEMPLATE.read_text(encoding="utf-8"), encoding="utf-8")
            (target / "classes.js").write_text("window.__gaiusSmokeClasses = true;\n", encoding="utf-8")
        result = postprocess(plain_dir / "index.html", plain_dir / "classes.js", CANONICAL_ID, "32")
        if result.returncode != 0:
            raise AssertionError(f"26.2 postprocess failed:\n{result.stderr}")
        plain = (plain_dir / "index.html").read_text(encoding="utf-8")
        original = module.patch_shader_toolchain_loader
        module.patch_shader_toolchain_loader = lambda text, classes_js, index, profile_id: text
        try:
            module.patch_index(reference_dir / "index.html", reference_dir / "classes.js", CANONICAL_ID, "32")
        finally:
            module.patch_shader_toolchain_loader = original
        if plain != (reference_dir / "index.html").read_text(encoding="utf-8"):
            raise AssertionError("a client without the shader toolchain got a different page")
        if "gaius-shader-toolchain" in plain or "__gaiusShaderToolchainReady" in plain:
            raise AssertionError("a client without the shader toolchain got the loader")

        # --- a client that calls the toolchain (26.3)
        needs_dir = directory / "needs"
        needs_dir.mkdir()
        index = needs_dir / "index.html"
        classes = needs_dir / "classes.js"
        index.write_text(TEMPLATE.read_text(encoding="utf-8"), encoding="utf-8")
        classes.write_text("function x(){return window.__gaiusShaderToolchain.shadercBegin();}\n", encoding="utf-8")
        strict = postprocess(index, classes, RENDERPEARL_ID, "34", {"GAIUS_SHADER_TOOLCHAIN_STRICT": "1"})
        if strict.returncode == 0 or "gaius-shaderc.wasm" not in strict.stderr:
            raise AssertionError(f"strict mode accepted missing toolchain files:\n{strict.stderr}")
        warned = postprocess(index, classes, RENDERPEARL_ID, "34")
        if warned.returncode != 0 or "warning:" not in warned.stderr:
            raise AssertionError(f"missing toolchain files were not a warning:\n{warned.stderr}")
        for name in FILES:
            (needs_dir / name).write_bytes(name.encode("ascii"))
        index.write_text(TEMPLATE.read_text(encoding="utf-8"), encoding="utf-8")
        first = postprocess(index, classes, RENDERPEARL_ID, "34", {"GAIUS_SHADER_TOOLCHAIN_STRICT": "1"})
        if first.returncode != 0:
            raise AssertionError(f"26.3 postprocess failed:\n{first.stderr}")
        page = index.read_text(encoding="utf-8")
        token = module.content_token(*(needs_dir / name for name in FILES))
        loader = (f'<script data-gaius-shader-toolchain data-profile="{RENDERPEARL_ID}" '
                  f'src="gaius-shader-toolchain.js?v={token}"></script>')
        head, body = page.split("</head>", 1)
        if head.count(loader) != 1 or page.count("gaius-shader-toolchain.js?v=") != 1:
            raise AssertionError("the loader script is not in <head> exactly once")
        if body.count("await window.__gaiusShaderToolchainReady;") != 1:
            raise AssertionError("the boot sequence does not await the toolchain exactly once")
        if body.index("await window.__gaiusShaderToolchainReady;") > body.index("main(window.__gaiusDefaultArgs);"):
            raise AssertionError("the toolchain is awaited after main(args)")
        # Fail closed: a page that needs the toolchain must not start main(args)
        # when the loader script itself did not load.
        gate = body.index("if (!window.__gaiusShaderToolchainReady) {")
        if not (gate < body.index("await window.__gaiusShaderToolchainReady;")
                and "throw new Error(" in body[gate:body.index("await window.__gaiusShaderToolchainReady;")]):
            raise AssertionError("a missing toolchain loader does not stop the boot before main(args)")
        second = postprocess(index, classes, RENDERPEARL_ID, "34", {"GAIUS_SHADER_TOOLCHAIN_STRICT": "1"})
        if second.returncode != 0 or index.read_text(encoding="utf-8") != page:
            raise AssertionError(("a second postprocess changed the " + RENDERPEARL_ID + " page"))
        (needs_dir / "gaius-spvc.wasm").write_bytes(b"rebuilt")
        third = postprocess(index, classes, RENDERPEARL_ID, "34")
        rebuilt = index.read_text(encoding="utf-8")
        if third.returncode != 0 or rebuilt.count("gaius-shader-toolchain.js?v=") != 1 or loader in rebuilt:
            raise AssertionError("a rebuilt toolchain did not replace the loader token exactly once")
        node_check(page, needs_dir)
    print("shader toolchain injection: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
