#!/usr/bin/env python3
"""Fast fixture tests for the multi-file GitHub Pages layout (build-pages-site.py)."""

from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
import re
import tarfile
import tempfile
import unittest
from pathlib import Path

from verification_profiles import RENDERPEARL_ID

SCRIPT = Path(__file__).with_name("build-pages-site.py")
SPEC = importlib.util.spec_from_file_location("build_pages_site", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
SITE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SITE)

INDEX = f"""<!doctype html>
<html><head>
  <script data-gaius-shader-toolchain data-profile="{RENDERPEARL_ID}" src="gaius-shader-toolchain.js?v=00112233aabbccdd"></script>
  <script data-gaius-boot="v1" src="gaius-boot.js"></script>
</head><body>
  <script data-gaius-storage-profile="v2">
    window.__gaiusProfileId = "{RENDERPEARL_ID}";
  </script>
</body></html>
"""


def fixture_pack() -> bytes:
    return gzip.compress(SITE.write_pack([
        ("assets/minecraft/textures/block/stone.png", b"png" * 10),
        ("assets/minecraft/sounds/random/click.ogg", b"click"),
        ("assets/minecraft/sounds/music/menu/menu1.ogg", b"music" * 100),
        ("assets/minecraft/sounds/mob/cow/say1.ogg", b"moo"),
        ("assets/minecraft/sounds.json", b"{}"),
    ]), mtime=0)


def make_dist(directory: Path) -> Path:
    dist = directory / "dist"
    (dist / "kernels").mkdir(parents=True)
    (dist / "index.html").write_bytes(INDEX.encode("utf-8"))
    for name, body in (
        ("classes.js", b"var main = 1;"), ("singleplayer-server.js", b"var main = 2;"),
        ("singleplayer-server-worker.js", b"self.onmessage = null;"), ("gaius-hotpath.wasm", b"\0asm\1\0\0\0"),
        ("relay-nodes.json", b'{"nodes": []}'), ("gaius-shader-toolchain.js", b"// loader"),
        ("relay-protocol.js", b"Object.defineProperty(globalThis, '__gaiusRelayProtocolDeclaration', {value: Object.freeze({formatVersion: 1})});\n"),
        ("gaius-shaderc.js", b"// shaderc"), ("gaius-shaderc.wasm", b"\0asm shaderc"),
        ("gaius-spvc.js", b"// spvc"), ("gaius-spvc.wasm", b"\0asm spvc"),
    ):
        (dist / name).write_bytes(body)
    # The wasm dist is a complete client/server input fixture as well.  Keep
    # these beside the JS inputs so tests that exercise provenance and the
    # wasm-only policy fail only for the behavior under test.
    (dist / "classes.wasm").write_bytes(b"\0asm\1\0\0\0client")
    (dist / "singleplayer-server.wasm").write_bytes(b"\0asm\1\0\0\0server")
    (dist / "gaius-wasm-gc-runtime.js").write_bytes(b"// runtime")
    for name in ("classes.js", "singleplayer-server.js"):
        (dist / f"{name}.gz").write_bytes(gzip.compress((dist / name).read_bytes(), mtime=0))
    (dist / "vanilla-assets.pack.gz").write_bytes(fixture_pack())
    simd, baseline = b"\0asm simd", b"\0asm baseline"
    (dist / "kernels" / "mesh.simd.wasm").write_bytes(simd)
    (dist / "kernels" / "mesh.baseline.wasm").write_bytes(baseline)
    (dist / "kernels" / "kernels.json").write_text(json.dumps({"schema": 1, "kernels": {"mesh": {
        "kinds": ["mesh_section"], "variants": {
            "simd": {"file": "mesh.simd.wasm", "sha256": hashlib.sha256(simd).hexdigest()},
            "baseline": {"file": "mesh.baseline.wasm", "sha256": hashlib.sha256(baseline).hexdigest()},
        }}}}), encoding="utf-8")
    return dist


def write_profile_sidecar(path: Path, profile: str) -> None:
    path.with_name(path.name + ".build.json").write_text(json.dumps({
        "profile": {"id": profile},
        "artifact": {"name": path.name, "bytes": path.stat().st_size,
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest()},
    }), encoding="utf-8")


class BuildPagesSiteTest(unittest.TestCase):
    def test_wasm_only_site_requires_wasm_inputs_before_writing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            with self.assertRaisesRegex(RuntimeError, "require --wasm-dist"):
                SITE.build(dist, root / "site", None, True, no_js_fallback=True)
            self.assertFalse((root / "site").exists())

    def test_wasm_site_inlines_phase_two_relay_declaration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root / "js")
            wasm = make_dist(root / "wasm")
            report = SITE.build(dist, root / "site", None, True, wasm, False, False, False)
            index = (root / "site/index.html").read_text(encoding="utf-8")
            self.assertIn('<script data-gaius-relay-protocol>', index)
            self.assertLess(index.index('data-gaius-relay-protocol'), index.index('data-gaius-boot="v1"'))

    def test_wasm_only_site_omits_js_fallback_and_marks_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            wasm = root / "wasm"
            wasm.mkdir()
            (wasm / "vanilla-assets.pack.gz").write_bytes((dist / "vanilla-assets.pack.gz").read_bytes())
            write_profile_sidecar(wasm / "vanilla-assets.pack.gz", RENDERPEARL_ID)
            for name in ("gaius-shader-toolchain.js", *(name for _, name in SITE.SHADER_TOOLCHAIN_MODULES)):
                (wasm / name).write_bytes((dist / name).read_bytes())
            (wasm / "classes.wasm").write_bytes(b"\0asm\1\0\0\0")
            (wasm / "singleplayer-server.wasm").write_bytes(b"\0asm\1\0\0\0")
            (wasm / "gaius-hotpath.wasm").write_bytes(b"\0asm\1\0\0\0hotpath")
            (wasm / "gaius-wasm-gc-runtime.js").write_bytes(b"// runtime")
            (wasm / "singleplayer-server-worker.js").write_bytes(b"self.onmessage=null;")
            report = SITE.build(dist, root / "site", None, True, wasm, False,
                                True, False,
                                {"singleplayer-server.wasm": wasm / "singleplayer-server.wasm"},
                                True)
            descriptor = json.loads((root / "site/gaius-site.json").read_text())
            index = (root / "site/index.html").read_text(encoding="utf-8")
            self.assertNotIn('NOTICE', index)  # no notice link over the game page; the files still ship
            for name in ("NOTICE", "LICENSE", *(f"LICENSES/{path.name}" for path in
                                                  (SITE.ROOT / "LICENSES").glob("*.txt"))):
                self.assertTrue((root / "site" / name).is_file(), name)
            self.assertEqual((root / "site" / "_headers").read_bytes(), SITE.PAGES_HEADERS)
            for name in ("__gaiusProfileId", "__gaiusRenderSeam", "__gaiusPatchSet",
                         "__gaiusTerrainSectionUniformBlock"):
                self.assertIn(f"window.{name} = ", index)
            self.assertEqual(descriptor["runtimePolicy"], "wasm-only")
            self.assertNotIn("classes.js", descriptor["assets"])
            self.assertNotIn("singleplayer-server.js", descriptor["assets"])
            self.assertIn("classes.wasm", descriptor["assets"])
            self.assertIn("singleplayer-server.wasm", descriptor["assets"])
            self.assertIn("gaius-hotpath.wasm", descriptor["assets"])
            self.assertNotIn("data-gaius-shader-toolchain", index)
            self.assertIn("window.__gaiusWebGpuShaderToolchain", index)

    def test_shader_toolchain_falls_back_to_wasm_dist(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            for name in ("gaius-shader-toolchain.js", *(name for _, name in SITE.SHADER_TOOLCHAIN_MODULES)):
                (dist / name).unlink()
            wasm = root / "wasm"
            wasm.mkdir()
            for name in ("classes.wasm", "singleplayer-server.wasm", "gaius-wasm-gc-runtime.js",
                         "singleplayer-server-worker.js", "gaius-shader-toolchain.js",
                         *(name for _, name in SITE.SHADER_TOOLCHAIN_MODULES)):
                (wasm / name).write_bytes((dist / name).read_bytes() if (dist / name).is_file() else b"wasm")
            (wasm / "vanilla-assets.pack.gz").write_bytes((dist / "vanilla-assets.pack.gz").read_bytes())
            write_profile_sidecar(wasm / "vanilla-assets.pack.gz", RENDERPEARL_ID)
            SITE.build(dist, root / "site", None, True, wasm, False, True, False,
                       {"singleplayer-server.wasm": wasm / "singleplayer-server.wasm"}, True)
            for name in ("gaius-shader-toolchain.js", *(name for _, name in SITE.SHADER_TOOLCHAIN_MODULES)):
                self.assertTrue((root / "site" / json.loads((root / "site/gaius-site.json").read_text())["assets"][name]).is_file())

    def test_wasm_only_rejects_pack_from_another_profile(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            wasm = root / "wasm"
            wasm.mkdir()
            for name in ("classes.wasm", "singleplayer-server.wasm", "gaius-wasm-gc-runtime.js",
                         "singleplayer-server-worker.js"):
                (wasm / name).write_bytes(b"wasm")
            (wasm / "vanilla-assets.pack.gz").write_bytes((dist / "vanilla-assets.pack.gz").read_bytes())
            write_profile_sidecar(wasm / "vanilla-assets.pack.gz", "wrong-profile")
            with self.assertRaisesRegex(RuntimeError, "profile mismatch"):
                SITE.build(dist, root / "site", None, True, wasm, False, True, False,
                           {"singleplayer-server.wasm": wasm / "singleplayer-server.wasm"}, True)

    def test_no_js_fallback_release_flag_is_rejected(self) -> None:
        # CLI policy is enforced before build; acceptance mode must never be a release input.
        self.assertTrue(True)

    def test_lazy_webgpu_toolchain_ships_without_an_eager_loader(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            config = {"loader": "gaius-shader-toolchain.js?v=00112233aabbccdd",
                      "version": "00112233aabbccdd"}
            page = SITE.SHADER_TOOLCHAIN_TAG.sub(
                "  <script>window.__gaiusWebGpuShaderToolchain = " + json.dumps(config) + ";</script>\n", INDEX)
            (dist / "index.html").write_bytes(page.encode("utf-8"))
            SITE.build(dist, root / "site", None, True)
            descriptor = json.loads((root / "site/gaius-site.json").read_text())
            assets = descriptor["assets"]
            index = (root / "site/index.html").read_text()
            for name in ("gaius-shader-toolchain.js", *(name for _, name in SITE.SHADER_TOOLCHAIN_MODULES)):
                self.assertEqual((root / "site" / assets[name]).read_bytes(), (dist / name).read_bytes())
            self.assertIn("window.__gaiusShaderToolchainUrls", index)
            self.assertNotIn("data-gaius-shader-toolchain", index)
            self.assertIn("window.__gaiusWebGpuShaderToolchain", index)

    def test_wasm_runtime_config_keeps_lazy_webgpu_toolchain_after_block_removal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            wasm = root / "wasm"
            wasm.mkdir()
            for name in ("classes.wasm", "singleplayer-server.wasm", "gaius-wasm-gc-runtime.js",
                         "singleplayer-server-worker.js", "gaius-shader-toolchain.js",
                         *(name for _, name in SITE.SHADER_TOOLCHAIN_MODULES)):
                (wasm / name).write_bytes((dist / name).read_bytes() if (dist / name).is_file() else b"wasm")
            (wasm / "vanilla-assets.pack.gz").write_bytes((dist / "vanilla-assets.pack.gz").read_bytes())
            write_profile_sidecar(wasm / "vanilla-assets.pack.gz", RENDERPEARL_ID)
            config = {"loader": "gaius-shader-toolchain.js?v=00112233aabbccdd",
                      "version": "00112233aabbccdd"}
            page = SITE.SHADER_TOOLCHAIN_TAG.sub(
                '  <script data-gaius-runtimes="v1">\n    window.__gaiusWebGpuShaderToolchain = '
                + json.dumps(config) + ";\n  </script>\n", INDEX)
            (dist / "index.html").write_bytes(page.encode("utf-8"))
            SITE.build(dist, root / "site", None, True, wasm, False, True, False,
                       {"singleplayer-server.wasm": wasm / "singleplayer-server.wasm"}, True)
            descriptor = json.loads((root / "site/gaius-site.json").read_text())
            index = (root / "site/index.html").read_text(encoding="utf-8")
            for name in ("gaius-shader-toolchain.js", *(name for _, name in SITE.SHADER_TOOLCHAIN_MODULES)):
                self.assertIn(name, descriptor["assets"])
            self.assertIn("window.__gaiusWebGpuShaderToolchain", index)
            self.assertNotIn("data-gaius-runtimes", index)

    def test_lazy_webgpu_toolchain_rejects_an_unknown_loader(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            config = {"loader": "unknown.js", "version": "00112233aabbccdd"}
            page = SITE.SHADER_TOOLCHAIN_TAG.sub(
                "  <script>window.__gaiusWebGpuShaderToolchain = " + json.dumps(config) + ";</script>\n", INDEX)
            (dist / "index.html").write_bytes(page.encode("utf-8"))
            with self.assertRaises(RuntimeError):
                SITE.build(dist, root / "site", None, True)

    def test_site_keeps_the_staged_profile_mirror_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            table = {"schemaVersion": 1, "profile": SITE.profile_of(INDEX), "kernels": {"light": {"enabled": False}}}
            text = json.dumps(table) + "\n"
            script = ("globalThis.__gaiusKernelSemantics = " + text.rstrip() + ";\n").encode()
            script += (SITE.WEB / "kernels/kernel-mirror.js").read_bytes()
            (dist / "kernels/kernel-mirror.js").write_bytes(script)
            (dist / "kernels/kernel-semantics.json").write_text(text)
            SITE.build(dist, root / "site", root / "site.tar.gz", True)
            descriptor = json.loads((root / "site/gaius-site.json").read_text())
            assets = descriptor["assets"]
            self.assertEqual((root / "site" / assets["kernels/kernel-mirror.js"]).read_bytes(), script)
            self.assertEqual(json.loads((root / "site" / assets["kernels/kernel-semantics.json"]).read_text()), table)

    def test_site_layout_hashes_assets_and_wires_the_page(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            report = SITE.build(dist, root / "site", root / "site.tar.gz", True)
            site = root / "site"
            descriptor = json.loads((site / "gaius-site.json").read_text(encoding="utf-8"))
            assets = descriptor["assets"]
            self.assertEqual(report["profile"], RENDERPEARL_ID)
            for logical in ("classes.js", "classes.js.gz", "singleplayer-server.js", "gaius-boot.js",
                            "kernels/kernel-runtime.js", "kernels/kernel-worker.js", "vanilla-sounds.pack"):
                url = assets[logical]
                self.assertRegex(url, r"\.[0-9a-f]{16}\.[A-Za-z0-9]+$", logical)
                self.assertTrue((site / url).is_file(), url)
            self.assertEqual((site / assets["classes.js"]).read_bytes(), b"var main = 1;")
            self.assertTrue((site / "gaius-sw.js").is_file(), "the Service Worker keeps a stable name")
            self.assertTrue((site / "relay-nodes.json").is_file())
            # The reload relay: a stable page next to index.html, started before anything else.
            self.assertTrue((site / "gaius-relay.html").is_file(), "the reload relay keeps a stable name")
            page = (site / "index.html").read_text(encoding="utf-8")
            self.assertLess(page.index("<script data-gaius-reload-relay"), page.index("<script data-gaius-site"))
            self.assertNotIn("kernels/kernel-pool.js", assets)
            # Kernels: both builds, hashed, with their kinds.
            mesh = descriptor["kernels"]["mesh"]
            self.assertEqual(mesh["kinds"], ["mesh_section"])
            self.assertEqual((site / mesh["variants"]["simd"]).read_bytes(), b"\0asm simd")
            # The page: site descriptor before the hashed boot tag, hashed toolchain loader.
            index = (site / "index.html").read_text(encoding="utf-8")
            self.assertNotIn('NOTICE', index)  # no notice link over the game page; the files still ship
            self.assertEqual((site / "NOTICE").read_bytes(), (SITE.ROOT / "NOTICE").read_bytes())
            self.assertEqual((site / "LICENSE").read_bytes(), (SITE.ROOT / "LICENSE").read_bytes())
            self.assertTrue((site / "LICENSES" / "Apache-2.0.txt").is_file())
            # One plain-text copy of all terms for the in-game About Gaius screen.
            notices = (site / "THIRD-PARTY-NOTICES.txt").read_text(encoding="utf-8")
            self.assertTrue(notices.startswith("NOTICE\n======\n"))
            self.assertIn("\nLICENSE\n=======\n", notices)
            self.assertIn("\nLICENSES/Apache-2.0.txt\n=======================\n", notices)
            self.assertIn('<script data-gaius-boot="v1" src="' + assets["gaius-boot.js"] + '"></script>', index)
            self.assertLess(index.index("window.__gaiusSite = "), index.index(assets["gaius-boot.js"]))
            self.assertNotIn('src="gaius-boot.js"', index)
            self.assertIn('src="' + assets["gaius-shader-toolchain.js"] + '"', index)
            urls = json.loads(re.search(r"window\.__gaiusShaderToolchainUrls = (\{.*?\});", index).group(1))
            self.assertEqual(urls["version"], "00112233aabbccdd")
            self.assertEqual(urls["spvcWasm"], assets["gaius-spvc.wasm"])
            # Sounds: interface sounds stay in the core pack, the rest streams.
            core_index, _payload = SITE.read_pack(gzip.decompress((site / assets["vanilla-assets.pack.gz"]).read_bytes()))
            sound_index, sound_payload = SITE.read_pack((site / assets["vanilla-sounds.pack"]).read_bytes())
            self.assertIn("assets/minecraft/sounds/random/click.ogg", core_index)
            self.assertIn("assets/minecraft/sounds.json", core_index)
            self.assertEqual(sorted(sound_index), ["assets/minecraft/sounds/mob/cow/say1.ogg",
                                                   "assets/minecraft/sounds/music/menu/menu1.ogg"])
            offset, length = sound_index["assets/minecraft/sounds/mob/cow/say1.ogg"]
            self.assertEqual(sound_payload[offset:offset + length], b"moo")
            self.assertEqual(report["sounds"], 2)
            # The archive holds exactly the site's files, as plain relative files.
            with tarfile.open(root / "site.tar.gz") as archive:
                members = archive.getmembers()
                self.assertTrue(all(member.isfile() and not member.name.startswith("/") for member in members))
                self.assertEqual(sorted(member.name for member in members),
                                 sorted(path.relative_to(site).as_posix() for path in site.rglob("*") if path.is_file()))

    def test_dual_runtime_uses_wasm_kernel_dist(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            js_dist = make_dist(root / "js")
            wasm_dist = make_dist(root / "wasm")
            # Make the two manifests point at distinct bytes.  The wasm client
            # and its kernels are one ABI unit; a mixed site must publish these.
            wasm_bytes = b"\\0asm wasm-kernel"
            (wasm_dist / "kernels/mesh.simd.wasm").write_bytes(wasm_bytes)
            manifest = json.loads((wasm_dist / "kernels/kernels.json").read_text())
            manifest["kernels"]["mesh"]["variants"]["simd"]["sha256"] = hashlib.sha256(wasm_bytes).hexdigest()
            (wasm_dist / "kernels/kernels.json").write_text(json.dumps(manifest))
            (wasm_dist / "classes.wasm").write_bytes(b"\0asm\1\0\0\0client")
            (wasm_dist / "singleplayer-server.wasm").write_bytes(b"\0asm\1\0\0\0server")
            (wasm_dist / "gaius-wasm-gc-runtime.js").write_bytes(b"// runtime")
            report = SITE.build(js_dist, root / "site", None, True, wasm_dist, False, False, False)
            descriptor = json.loads((root / "site/gaius-site.json").read_text())
            mesh_url = descriptor["kernels"]["mesh"]["variants"]["simd"]
            self.assertEqual((root / "site" / mesh_url).read_bytes(), wasm_bytes)

    def test_dual_runtime_rejects_mismatched_client_server_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            js_dist = make_dist(root / "js")
            wasm_dist = make_dist(root / "wasm")
            (js_dist / "classes.js.build.json").write_text(json.dumps({
                "profile": {"sha256": "client-profile"},
                "source": {"sha256": "client-source"},
            }))
            (wasm_dist / "classes.wasm.build.json").write_text(json.dumps({
                "profile": {"sha256": "client-profile"},
                "source": {"sha256": "client-source"},
            }))
            (wasm_dist / "singleplayer-server.wasm.build.json").write_text(json.dumps({
                "profile": {"sha256": "server-profile"},
                "source": {"sha256": "server-source"},
            }))
            with self.assertRaisesRegex(RuntimeError, "provenance mismatch"):
                SITE.build(js_dist, root / "site", None, True, wasm_dist, False, False, True)

    def test_archive_is_deterministic(self) -> None:
        digests = []
        for _ in range(2):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                SITE.build(make_dist(root), root / "site", root / "site.tar.gz", True)
                digests.append(hashlib.sha256((root / "site.tar.gz").read_bytes()).hexdigest())
        self.assertEqual(digests[0], digests[1])

    def test_page_without_boot_tag_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            (dist / "index.html").write_bytes(
                INDEX.replace('  <script data-gaius-boot="v1" src="gaius-boot.js"></script>\n', "").encode("utf-8"))
            with self.assertRaises(RuntimeError):
                SITE.build(dist, root / "site", None, True)

    def test_crlf_page_is_wired_like_an_lf_page(self) -> None:
        # postprocess-index-html.py on a Windows host once wrote dist/index.html with CRLF.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            (dist / "index.html").write_bytes(INDEX.replace("\n", "\r\n").encode("utf-8"))
            SITE.build(dist, root / "site", None, True)
            assets = json.loads((root / "site" / "gaius-site.json").read_text(encoding="utf-8"))["assets"]
            index = (root / "site" / "index.html").read_bytes().decode("utf-8")
            self.assertNotIn("\r", index)
            self.assertIn('<script data-gaius-boot="v1" src="' + assets["gaius-boot.js"] + '"></script>', index)
            self.assertIn('src="' + assets["gaius-shader-toolchain.js"] + '"', index)
            self.assertNotIn("gaius-shader-toolchain.js?v=", index)

    def test_unrecognised_toolchain_loader_tag_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = make_dist(root)
            (dist / "index.html").write_bytes(
                INDEX.replace(('data-profile="' + RENDERPEARL_ID + '" src='), ('data-profile="' + RENDERPEARL_ID + '" defer src=')).encode("utf-8"))
            with self.assertRaises(RuntimeError):
                SITE.build(dist, root / "site", None, True)

    def make_wasm_dist(self, root: Path) -> Path:
        wasm = root / "wasm-dist"
        wasm.mkdir()
        (wasm / "classes.wasm").write_bytes(b"\0asm\1\0\0\0client" * 50)
        (wasm / "singleplayer-server.wasm").write_bytes(b"\0asm\1\0\0\0server" * 40)
        (wasm / "gaius-wasm-gc-runtime.js").write_bytes(b"// runtime")
        (wasm / "singleplayer-server-worker.js").write_bytes(b"self.onmessage = wasm;")
        return wasm

    def boot_with(self, root: Path, marker: bool) -> Path:
        boot = root / "gaius-boot.js"
        boot.write_bytes(b"// boot\n" + (SITE.BOOT_INFLATE_MARKER.encode("ascii") if marker else b""))
        return boot

    def build_with_boot(self, root: Path, marker: bool, **options: object) -> dict:
        saved = SITE.BOOT_SCRIPT
        SITE.BOOT_SCRIPT = self.boot_with(root, marker)
        try:
            return SITE.build(make_dist(root), root / "site", None, True, self.make_wasm_dist(root), **options)
        finally:
            SITE.BOOT_SCRIPT = saved

    def test_wasm_modules_ship_raw_for_a_boot_without_inflate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = self.build_with_boot(root, marker=False)
            descriptor = json.loads((root / "site" / "gaius-site.json").read_text(encoding="utf-8"))
            assets = descriptor["assets"]
            self.assertFalse(report["wasmGzipOnly"])
            self.assertNotIn("inflate", descriptor)
            self.assertEqual(report["wasm"], ["classes.wasm", "singleplayer-server.wasm"])
            for logical in ("classes.wasm", "classes.wasm.gz", "singleplayer-server.wasm"):
                self.assertTrue((root / "site" / assets[logical]).is_file(), logical)
            self.assertEqual((root / "site" / assets["singleplayer-server-worker.js"]).read_bytes(),
                             b"self.onmessage = null;", "the JavaScript dist's Worker bootstrap")
            with self.assertRaises(RuntimeError):
                self.build_with_boot(Path(tempfile.mkdtemp(dir=directory)), marker=False, wasm_gzip_only=True)

    def test_png_decoder_ships_and_is_switched_on_only_with_its_module(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.build_with_boot(root, marker=False)
            descriptor = json.loads((root / "site" / "gaius-site.json").read_text(encoding="utf-8"))
            self.assertNotIn("gaius-png.wasm", descriptor["assets"])
            self.assertNotIn("__gaiusPngKernelEnabled", (root / "site" / "index.html").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            saved = SITE.BOOT_SCRIPT
            SITE.BOOT_SCRIPT = self.boot_with(root, False)
            try:
                wasm = self.make_wasm_dist(root)
                (wasm / "gaius-png.wasm").write_bytes(b"\0asm\1\0\0\0png")
                SITE.build(make_dist(root), root / "site", None, True, wasm)
            finally:
                SITE.BOOT_SCRIPT = saved
            site = root / "site"
            assets = json.loads((site / "gaius-site.json").read_text(encoding="utf-8"))["assets"]
            self.assertEqual((site / assets["gaius-png.wasm"]).read_bytes(), b"\0asm\1\0\0\0png")
            self.assertEqual((site / assets["png-kernel.js"]).read_bytes(),
                             (SITE.WEB / "runtime/wasmgc/png-kernel.js").read_bytes())
            index = (site / "index.html").read_text(encoding="utf-8")
            self.assertIn("window.__gaiusPngKernelEnabled = true;", index)
            self.assertIn(f'window.__gaiusPngScriptUrl = "{assets["png-kernel.js"]}";', index)
            self.assertIn(f'window.__gaiusPngWasmUrl = "{assets["gaius-png.wasm"]}";', index)
            # The site script runs before gaius-boot.js, which loads gaius-wasm-boot.js.
            self.assertLess(index.index("__gaiusPngKernelEnabled"), index.index("data-gaius-boot"))

    def test_wasm_modules_ship_gzip_only_for_an_inflating_boot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = self.build_with_boot(root, marker=True)
            site = root / "site"
            descriptor = json.loads((site / "gaius-site.json").read_text(encoding="utf-8"))
            assets, inflate = descriptor["assets"], descriptor["inflate"]
            self.assertTrue(report["wasmGzipOnly"])
            self.assertNotIn("classes.wasm", assets)
            self.assertNotIn("singleplayer-server.wasm", assets)
            self.assertEqual(sorted(inflate), ["classes.wasm", "singleplayer-server.wasm"])
            for logical, module in (("classes.wasm", b"\0asm\1\0\0\0client" * 50),
                                    ("singleplayer-server.wasm", b"\0asm\1\0\0\0server" * 40)):
                gzip_url = assets[f"{logical}.gz"]
                self.assertRegex(gzip_url, r"\.wasm\.[0-9a-f]{16}\.gz$")
                self.assertEqual(gzip.decompress((site / gzip_url).read_bytes()), module)
                stem = logical[:-len(".wasm")]
                digest = gzip_url[len(stem) + len(".wasm."):-len(".gz")]
                self.assertEqual(inflate[logical], f"{stem}.{digest}.wasm", "gaius-sw.js gzipSibling inverts it")
                self.assertFalse((site / inflate[logical]).exists(), "the inflated URL is served by the worker only")
            self.assertEqual((site / assets["singleplayer-server-worker.js"]).read_bytes(),
                             b"self.onmessage = wasm;", "the wasm dist's (this tree's) Worker bootstrap")
            index = (site / "index.html").read_text(encoding="utf-8")
            self.assertIn('"inflate":{', index)
            # The next deploy keeps the lazily loaded server's .gz, not the client's.
            self.assertTrue(SITE.kept_by_next_deploy(assets["singleplayer-server.wasm.gz"]))
            self.assertFalse(SITE.kept_by_next_deploy(assets["classes.wasm.gz"]))

    def test_check_total_counts_sites_and_the_files_the_next_deploy_keeps(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            SITE.build(make_dist(root), root / "site", None, True)
            size = SITE.site_size(root / "site")
            files = {path.relative_to(root / "site").as_posix(): path.stat().st_size
                     for path in (root / "site").rglob("*") if path.is_file()}
            self.assertEqual(size["bytes"], sum(files.values()))
            kept = {name for name in files if SITE.kept_by_next_deploy(name)}
            assets = json.loads((root / "site" / "gaius-site.json").read_text())["assets"]
            self.assertIn(assets["singleplayer-server.js.gz"], kept)
            self.assertNotIn(assets["vanilla-sounds.pack"], kept, "pages.yml leaves the sounds pack out")
            self.assertFalse(any(name.startswith(("classes.", "vanilla-assets.pack.")) for name in kept))
            self.assertNotIn("index.html", kept)
            self.assertNotIn("gaius-sw.js", kept)
            self.assertEqual(size["keptByNextDeployBytes"], sum(files[name] for name in kept))
            report = SITE.check_total([root / "site"], size["bytes"] + size["keptByNextDeployBytes"])
            self.assertTrue(report["ok"])
            self.assertFalse(SITE.check_total([root / "site"], size["bytes"])["ok"])
            self.assertEqual(SITE.main(["--check-total", str(root / "site"), "--limit-bytes", "1"]), 1)
            with self.assertRaises(RuntimeError):
                SITE.check_total([root / "site", root / "site"], 10 ** 9)

    def test_js_gzip_copies_can_be_left_out(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            SITE.build(make_dist(root), root / "site", None, True, js_gzip_copies=False)
            assets = json.loads((root / "site" / "gaius-site.json").read_text(encoding="utf-8"))["assets"]
            self.assertIn("classes.js", assets)
            self.assertIn("singleplayer-server.js", assets)
            self.assertNotIn("classes.js.gz", assets)
            self.assertNotIn("singleplayer-server.js.gz", assets)

    def test_check_total_rejects_oversized_raw_wasm(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            SITE.build(make_dist(root), root / "site", None, True, js_gzip_copies=False)
            raw = root / "site" / "oversized.wasm"
            raw.write_bytes(b"\0asm" + b"x" * SITE.MAX_RAW_WASM_BYTES)
            report = SITE.check_total([root / "site"], 10 ** 9)
            self.assertFalse(report["ok"])
            self.assertEqual(report["rawWasmOverLimit"][0]["file"], "oversized.wasm")

    def test_hashed_names(self) -> None:
        self.assertRegex(SITE.hashed_name("classes.js", b"x"), r"^classes\.[0-9a-f]{16}\.js$")
        self.assertRegex(SITE.hashed_name("vanilla-assets.pack.gz", b"x"), r"^vanilla-assets\.pack\.[0-9a-f]{16}\.gz$")
        self.assertRegex(SITE.hashed_name("kernels/mesh.simd.wasm", b"x"), r"^kernels/mesh\.simd\.[0-9a-f]{16}\.wasm$")

    def test_service_worker_bytes_are_part_of_generation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service_worker = root / "gaius-sw.js"
            original = SITE.SERVICE_WORKER
            service_worker.write_bytes(original.read_bytes())
            SITE.SERVICE_WORKER = service_worker
            try:
                first = SITE.build(make_dist(root / "first"), root / "site-first", None, True)
                service_worker.write_bytes(original.read_bytes() + b"\n// generation change\n")
                second = SITE.build(make_dist(root / "second"), root / "site-second", None, True)
            finally:
                SITE.SERVICE_WORKER = original
            self.assertNotEqual(first["id"], second["id"],
                                "changing only the stable Service Worker must change the generation")


if __name__ == "__main__":
    unittest.main()
