#!/usr/bin/env python3
"""Fast regression tests for atomic portable HTML publication."""

from __future__ import annotations

import contextlib
import gzip
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from verification_profiles import CANONICAL_ID, LEGACY_ID, RENDERPEARL_ID


SCRIPT = Path(__file__).with_name("build-portable-html.py")
SPEC = importlib.util.spec_from_file_location("build_portable_html", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PORTABLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PORTABLE)


def load_script(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


POSTPROCESS = load_script("postprocess_index_html", SCRIPT.with_name("postprocess-index-html.py"))
MAKE_WASM_PAGE = load_script(
    "make_wasm_page", SCRIPT.parent.parent / "web" / "runtime" / "wasmgc" / "make-wasm-page.py"
)


class BuildPortableHTMLTest(unittest.TestCase):
    def test_relative_url_check_ignores_encoded_payload_bodies(self) -> None:
        payload = (f'<script type="{PORTABLE.PAYLOAD_TYPE}" data-gaius-asset="a" data-part="0">'
                   'x src="T;%4*g" y</script>')
        PORTABLE.validate_portable_no_relative_urls(payload)
        with self.assertRaises(RuntimeError):
            PORTABLE.validate_portable_no_relative_urls(payload + '<script src="gaius-boot.js"></script>')

    def test_license_notice_is_embedded_and_escaped_for_offline_use(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "LICENSES").mkdir()
            (root / "NOTICE").write_text("Notice <third-party>", encoding="utf-8")
            (root / "LICENSE").write_text("MIT terms", encoding="utf-8")
            (root / "LICENSES" / "Example.txt").write_text("Terms & conditions", encoding="utf-8")
            page = PORTABLE.add_license_notice(
                '<body>game  <p><a href="NOTICE">Third-party notices and licenses</a></p>\n</body>', root)
            PORTABLE.validate_portable_no_relative_urls(page)
            self.assertIn('id="gaius-license-notice"', page)
            self.assertIn('<script type="text/plain" id="gaius-license-notice">', page)
            self.assertIn("Notice <third-party>", page)
            self.assertIn("Terms & conditions", page)
            self.assertNotIn("<details", page)
            self.assertNotIn("Third-party notices and licenses", page)
            sidecar = PORTABLE.write_license_sidecar(root / "out" / "gaius.html", root)
            self.assertIn("MIT terms", sidecar.read_text(encoding="utf-8"))
            self.assertIn("MIT terms", page)
            self.assertLess(page.index("game"), page.index('id="gaius-license-notice"'))

    def test_launcher_template_has_no_license_overlay(self) -> None:
        template = (SCRIPT.parent.parent / "web" / "launcher" / "index.template.html").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("gaius-license-notice", template)

    def test_canvas_is_not_polled_back_to_the_browser_viewport(self) -> None:
        template = (SCRIPT.parent.parent / "web" / "launcher" / "index.template.html").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("__gaiusReconcileCanvasViewport", template)
        self.assertNotIn("__gaiusCanvasViewportTimer", template)
        self.assertIn('addEventListener("resize", update)', template)

    def test_release_portable_path_does_not_expose_javascript(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("frozen_js", source)
        self.assertNotIn("frozen_js=frozen_js", source)

    def test_toolchain_identity_is_strict_for_release_portables(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        signature = source[source.index("def load_shader_toolchain("):]
        self.assertIn("verified_component_identity(", signature)
        self.assertNotIn("carriedFromWasmClient", source[source.index("def build("):])

    def test_strict_identity_path_rejects_toolchain_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "gaius-shaderc.wasm"
            artifact.write_bytes(b"shaderc")
            sidecar = artifact.with_name(artifact.name + ".build.json")
            sidecar.write_text("{}", encoding="utf-8")
            with mock.patch.object(
                PORTABLE.build_identity,
                "verify_sidecar",
                side_effect=RuntimeError("identity mismatch"),
            ):
                with self.assertRaisesRegex(RuntimeError, "identity mismatch"):
                    PORTABLE.verified_component_identity(root, artifact, "shader-toolchain", {}, frozen=False)

    def test_missing_compiler_workspace_is_only_allowed_for_frozen_mode(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "classes.js"
            artifact.write_bytes(b"classes")
            with self.assertRaises((RuntimeError, FileNotFoundError)):
                PORTABLE.verified_compiler_profile(
                    Path(directory), artifact, "client", Path(directory) / "missing-pom.xml", [], frozen=False
                )
            record = PORTABLE.verified_compiler_profile(
                Path(directory), artifact, "client", Path(directory) / "missing-pom.xml", [], frozen=True
            )
            self.assertTrue(record["frozen"])

    def test_wasm_only_payload_lookup_does_not_reuse_previous_candidate(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("candidate = None\n            if not logical", source)

    def test_wasm_only_uses_the_launcher_path(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("build(dist, output, wasm_dist=wasm_dist, wasm_server_dist=wasm_server_dist)", source)
        self.assertNotIn("__gaiusWasmClientBase64", source)

    def test_wasm_portable_declares_utf8_before_payloads_and_omits_js_server(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('portable = "<!doctype html><meta charset=\\"utf-8\\">"', source)
        payload_section = source[source.index("FILE_PAYLOAD_FILES = ("):source.index(")\nWASM_MAGIC", source.index("FILE_PAYLOAD_FILES = ("))]
        self.assertNotIn('"singleplayer-server.js"', payload_section)
        self.assertLess(source.index('<!doctype html><meta charset=\\"utf-8\\">'),
                        source.index("prefix + inert + index"))

    def test_launcher_keeps_embedded_portable_worker_urls(self) -> None:
        template = (SCRIPT.parent.parent / "web" / "launcher" / "index.template.html").read_text(
            encoding="utf-8"
        )
        self.assertIn("if (window.__gaiusPortableBuild !== true)", template)
        self.assertIn("file:/// sibling paths", template)
        self.assertIn("if (window.__gaiusPortableBuild === true) await portableReady;", template)

    def test_portable_start_transfers_a_copy_of_the_server_gzip_buffer(self) -> None:
        # The gzip promise resolves to one ArrayBuffer for the page's lifetime;
        # transferring it to the first Worker detaches it, so every start must
        # transfer a fresh copy or re-entering a world without a reload fails.
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            "const transferBuffer = buffer instanceof ArrayBuffer ? buffer.slice(0) : buffer;",
            script,
        )
        self.assertIn("serverScriptGzipData: transferBuffer,", script)
        self.assertIn(
            "if (transferBuffer instanceof ArrayBuffer) transferList.push(transferBuffer);",
            script,
        )
        self.assertNotIn("transferList.push(buffer)", script)
        self.assertNotIn("serverScriptGzipData: buffer,", script)

    def test_b7_round_trip_and_html_safety(self) -> None:
        samples = [b"", b"\x00", bytes(range(256)), b"<\r\n\x00" * 999, os.urandom(70_001)]
        for data in samples:
            text = PORTABLE.encode_b7(data)
            self.assertEqual(len(text) % 8, 0)
            # Nothing the HTML parser alters (NUL, CR), that could close the element ("<") or
            # that newline translation could rewrite (LF).
            self.assertFalse({"\x00", "\r", "\n", "<"} & set(text))
            self.assertLessEqual(max(map(ord, text), default=0), 0x83)
            self.assertEqual(PORTABLE.decode_b7(text, len(data)), data)

    def test_payload_parts_split_on_block_boundaries(self) -> None:
        data = os.urandom(50_000)
        payload = PORTABLE.PortablePayload()
        with mock.patch.object(PORTABLE, "B64_PART_CHARS", 4 * 1000):
            payload.add("blob", data, gzipped=False)
        self.assertEqual(payload.assets["blob"]["parts"], len(payload.elements))
        self.assertGreater(len(payload.elements), 1)
        html = payload.html() + "const portablePayload = " + json.dumps(payload.index()) + ";\n"
        self.assertEqual(PORTABLE.parse_portable_payload(html)["blob"], data)
        with self.assertRaises(RuntimeError):
            payload.add("blob", b"again", gzipped=False)

    def test_base64_payload_is_ascii_and_detects_corruption(self) -> None:
        data = os.urandom(70_001)
        encoded = PORTABLE.encode_payload(data)
        self.assertTrue(encoded)
        self.assertTrue(all(ord(char) < 128 for char in encoded))
        self.assertEqual(PORTABLE.decode_payload(encoded, len(data)), data)
        with self.assertRaisesRegex(ValueError, "not valid ASCII base64"):
            PORTABLE.decode_payload(encoded[:-1] + "!", len(data))

    def test_page_decoder_matches_reference(self) -> None:
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        source = PORTABLE.PORTABLE_BOOTSTRAP
        start = source.index("      function gaiusPortableDecoder(scope) {")
        end = source.index("\n      const decoder = (() => {", start)
        decoder = source[start:end]
        raw = os.urandom(123_457) + bytes(range(256)) * 3
        compressed = gzip.compress(raw, mtime=0)
        script = decoder + r"""
const fs = require("fs");
const input = JSON.parse(fs.readFileSync(0, "utf8"));
const scope = {postMessage: (message) => scope.results.push(message), results: []};
gaiusPortableDecoder(scope);
let id = 0;
function run(text, bytes, gunzip) {
  id++;
  scope.onmessage({data: {type: "begin", id, name: "test", encoding: "gaius-b7-v1", bytes, gunzip, blobType: null}});
  for (let i = 0; i < text.length; i += 8 * 1024) {
    scope.onmessage({data: {type: "part", id, text: text.slice(i, i + 8 * 1024)}});
  }
  scope.onmessage({data: {type: "end", id}});
}
run(input.plain, input.plainBytes, false);
run(input.gzip, input.gzipBytes, true);
setTimeout(() => {
  const crypto = require("crypto");
  const hashes = scope.results.map((message) => message.type === "done"
    ? crypto.createHash("sha256").update(Buffer.from(message.value)).digest("hex") : message.message);
  process.stdout.write(JSON.stringify(hashes));
}, 500);
"""
        with tempfile.TemporaryDirectory() as directory:
            script_path = Path(directory) / "decoder.js"
            script_path.write_text(script, encoding="utf-8")
            stdin = json.dumps({
                "plain": PORTABLE.encode_b7(raw), "plainBytes": len(raw),
                "gzip": PORTABLE.encode_b7(compressed), "gzipBytes": len(compressed),
            })
            result = subprocess.run([node, str(script_path)], input=stdin, text=True,
                                    capture_output=True, check=True, timeout=60)
        digest = hashlib.sha256(raw).hexdigest()
        self.assertEqual(json.loads(result.stdout), [digest, digest])

    def test_portable_inline_decoder_uses_all_source_bytes(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("gaius-b64-v1", source)
        self.assertIn("scope.atob", source)
        self.assertIn("sha256 mismatch", source)
        self.assertIn("portable asset \" + job.name + \" exceeds its declared length", source)

    def test_runtime_bootstrap_inlines_boot_modules(self) -> None:
        root = SCRIPT.resolve().parents[2]
        inline, hashes = PORTABLE.runtime_bootstrap(root)
        self.assertIn("window.__gaiusBootPortable = true;", inline)
        self.assertIn('id="gaius-kernel-worker-source"', inline)
        self.assertIn('<script data-gaius-boot-module="kernels/kernel-runtime.js">', inline)
        self.assertIn("gaius-boot.js", hashes)
        # The boot script runs last, after every module it would otherwise load.
        self.assertGreater(inline.rindex("__gaiusBoot ="), inline.rindex("data-gaius-boot-module"))
        # The server Worker's scripts ride along as inert text the boot script hands over.
        for name in PORTABLE.SERVER_WORKER_MODULES:
            self.assertIn(f'<script type="text/plain" data-gaius-worker-script="{name}">\n', inline)
            self.assertIn(f"server-worker/{name}", hashes)
        self.assertNotIn('<script data-gaius-boot-module="kernels/worldgen-kernel.js">', inline)
        for name in PORTABLE.QUALITY_MODULES:
            self.assertIn(f'<script data-gaius-boot-module="{name}">', inline)
        template = (root / "port" / "web" / "launcher" / "index.template.html").read_text(encoding="utf-8")
        self.assertEqual(template.count(PORTABLE.BOOT_TAG), 1)
        # The profile id the quality layer reads is set in <head> before the inlined modules.
        early = template.index('<script data-gaius-profile-early="v1">')
        self.assertLess(early, template.index(PORTABLE.BOOT_TAG))
        self.assertLess(template.index(PORTABLE.BOOT_TAG), template.index("</head>"))

    def test_portable_wasm_inlines_the_session_protocol_before_boot(self) -> None:
        root = SCRIPT.resolve().parents[2]
        source = ("Object.defineProperty(globalThis,'__gaiusRelayProtocolDeclaration',"
                  "{value:Object.freeze({formatVersion:1}),writable:false});\n")
        with tempfile.TemporaryDirectory() as directory:
            dist = Path(directory)
            (dist / PORTABLE.WASM_CLIENT_MODULE).write_bytes(b"\0asm\1\0\0\0")
            for name in PORTABLE.WASM_CLIENT_SCRIPTS:
                (dist / name).write_text("void 0;\n", encoding="utf-8")
            (dist / "relay-protocol.js").write_text(source, encoding="utf-8")
            wasm_client = PORTABLE.load_wasm_client(dist)
            inline, hashes = PORTABLE.runtime_bootstrap(root, wasm_client=wasm_client)
            self.assertEqual(inline.count("data-gaius-relay-protocol"), 1)
            self.assertIn(source, inline)
            self.assertLess(inline.index(source), inline.rindex("__gaiusBoot ="))
            self.assertEqual(hashes["wasm/relay-protocol.js"],
                             hashlib.sha256(source.encode("utf-8")).hexdigest())
            clean = PORTABLE.RELAY_PROTOCOL_BLOCK.sub("", inline)
            self.assertNotIn(source, clean)
            self.assertEqual((clean + inline).count("data-gaius-relay-protocol"), 1)
            (dist / "relay-protocol.js").write_text("</script>\n", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "cannot be inlined"):
                PORTABLE.load_wasm_client(dist)
        legacy, legacy_hashes = PORTABLE.runtime_bootstrap(root)
        self.assertNotIn("data-gaius-relay-protocol", legacy)
        self.assertNotIn("wasm/relay-protocol.js", legacy_hashes)

    def test_wasm_primary_payload_aliases_match_boot_logical_names(self) -> None:
        payload = PORTABLE.PortablePayload()
        payload.add(PORTABLE.WASM_CLIENT_ASSET, b"client-gzip", gzipped=True)
        payload.add(PORTABLE.WASM_SERVER_ASSET, b"server-gzip", gzipped=True)
        self.assertEqual(set(payload.index()["assets"]), {"wasmclient", "wasmserver"})
        bootstrap = PORTABLE.apply_portable_wasm_client(
            (PORTABLE.PORTABLE_CLASSES_DECODE + PORTABLE.PORTABLE_CLASSES_URL)
            .replace("__GAIUS_WASM_SERVER_ASSET__", PORTABLE.WASM_SERVER_ASSET)
        )
        self.assertIn('decodeAsset("wasmclient", {gunzip: true, parallel: true})', bootstrap)
        self.assertIn('portablePayloadBytes["classes.wasm"]', bootstrap)
        self.assertIn('decodeAsset("wasmserver", {gunzip: false})', bootstrap)
        self.assertIn('portablePayloadBytes["singleplayer-server.wasm"]', bootstrap)
        self.assertNotIn('siblingUrl("classes.wasm")', bootstrap)

    def test_wasm_server_dist_is_embedded_in_wasm_primary_payload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = root / "client"
            server = root / "server"
            client.mkdir()
            server.mkdir()
            (client / PORTABLE.WASM_CLIENT_MODULE).write_bytes(b"\0asm\1\0\0\0client")
            (server / PORTABLE.WASM_SERVER_MODULE).write_bytes(b"\0asm\1\0\0\0server")
            for name in PORTABLE.WASM_CLIENT_SCRIPTS:
                (client / name).write_text("void 0;\n", encoding="utf-8")
            (server / "gaius-wasm-worker.js").write_text("void 0;\n", encoding="utf-8")
            loaded = PORTABLE.load_wasm_client(client, server)
            self.assertIsNotNone(loaded["server"])
            self.assertEqual(loaded["server"]["manifest"]["rawBytes"], 14)

    def test_file_payloads_round_trip_without_fetch(self) -> None:
        """<file>.payload.js decodes back to the file the way gaius-boot.js reads it."""
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        with tempfile.TemporaryDirectory() as directory:
            dist = Path(directory)
            module = b"\0asm\1\0\0\0" + os.urandom(40_000)
            hotpath = b"\0asm\1\0\0\0hotpath"
            (dist / "classes.wasm").write_bytes(module)
            (dist / "gaius-hotpath.wasm").write_bytes(hotpath)
            # A .gz sibling that inflates to the file is reused as it is.
            sibling = gzip.compress(hotpath, compresslevel=1, mtime=0)
            (dist / "gaius-hotpath.wasm.gz").write_bytes(sibling)
            pack = gzip.compress(b"GAIUSVP1" + os.urandom(1000), mtime=0)
            (dist / "vanilla-assets.pack.gz").write_bytes(pack)
            index = PORTABLE.write_file_payloads(dist)
            self.assertEqual(sorted(index), ["classes.wasm", "gaius-hotpath.wasm", "vanilla-assets.pack.gz"])
            self.assertEqual(index["classes.wasm"]["script"], "classes.wasm.payload.js")
            self.assertTrue(index["classes.wasm"]["gzip"])
            self.assertFalse(index["vanilla-assets.pack.gz"]["gzip"], "a .gz file is carried as it is")
            self.assertEqual(index["classes.wasm"]["sha256"], hashlib.sha256(module).hexdigest())
            self.assertEqual(index["classes.wasm"]["bytes"], len(module))
            for name, entry in index.items():
                text = (dist / entry["script"]).read_text(encoding="ascii")
                self.assertNotIn("\r", text)
                # A getter over the comment of a function (no string literal of the text).
                self.assertTrue(text.startswith("(function(s,k,a){"), name)
                self.assertIn("a.push(\"", text, name)
                self.assertIn("return a.join", text, name)
                self.assertNotIn("<!--", text.lower(), name)
                self.assertNotIn("<script", text.lower(), name)
                self.assertNotIn("</script", text.lower(), name)
            script = r"""
const fs = require("fs");
const vm = require("vm");
const crypto = require("crypto");
const [directory, names] = [process.argv[2], JSON.parse(process.argv[3])];
const context = vm.createContext({});
for (const [name, entry] of Object.entries(names)) {
  vm.runInContext(fs.readFileSync(directory + "/" + entry.script, "utf8"), context);
}
(async () => {
  const out = {};
  for (const [name, entry] of Object.entries(names)) {
    const bytes = Buffer.from(context.__gaiusFilePayloadData[name], "base64");
    out[name + ":payload"] = crypto.createHash("sha256").update(bytes).digest("hex");
    const body = entry.gzip
      ? Buffer.from(await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"))).arrayBuffer())
      : bytes;
    out[name] = crypto.createHash("sha256").update(body).digest("hex");
  }
  process.stdout.write(JSON.stringify(out));
})();
"""
            script_path = dist / "payload-check.js"
            script_path.write_text(script, encoding="utf-8")
            result = subprocess.run([node, str(script_path), str(dist), json.dumps(index)], text=True,
                                    capture_output=True, check=True, timeout=60)
            hashes = json.loads(result.stdout)
            self.assertEqual(hashes["classes.wasm"], hashlib.sha256(module).hexdigest())
            self.assertEqual(hashes["gaius-hotpath.wasm"], hashlib.sha256(hotpath).hexdigest())
            self.assertEqual(hashes["gaius-hotpath.wasm:payload"], hashlib.sha256(sibling).hexdigest())
            self.assertEqual(hashes["vanilla-assets.pack.gz"], hashlib.sha256(pack).hexdigest())

    def test_runtime_configuration_precedes_the_boot_script(self) -> None:
        script = POSTPROCESS.runtime_config_script(
            {"wasm": {"client": "classes.wasm"}},
            {"classes.wasm": {"script": "classes.wasm.payload.js", "bytes": 1, "gzip": True}},
        )
        page = "<head>\n" + PORTABLE.BOOT_TAG + "</head>\n"
        configured = POSTPROCESS.apply_runtime_config(page, script)
        self.assertEqual(POSTPROCESS.apply_runtime_config(configured, script), configured, "replaced, not repeated")
        self.assertLess(configured.index("window.__gaiusRuntimes = "), configured.index(PORTABLE.BOOT_TAG))
        self.assertNotIn('window.__gaiusRuntimeDefault', configured)
        self.assertNotIn("</script", POSTPROCESS.runtime_config_script({"wasm": {"client": "</script>"}})[:-12])
        without_boot = POSTPROCESS.apply_runtime_config("<head></head>", script)
        self.assertIn('data-gaius-runtimes="v1"', without_boot)
        self.assertLess(without_boot.index('data-gaius-runtimes="v1"'), without_boot.index("</head>"))

    def test_early_profile_block_contains_every_renderer_capability(self) -> None:
        profile = POSTPROCESS.load_profile_for_version(RENDERPEARL_ID)
        page = POSTPROCESS.patch_early_profile_id("<head>\n</head>\n", profile)
        for name in ("__gaiusProfileId", "__gaiusRenderSeam", "__gaiusPatchSet",
                     "__gaiusTerrainSectionUniformBlock"):
            self.assertIn(f"window.{name} = ", page)

    def test_launcher_marker_survives_all_profiles_and_runtime_page_variants(self) -> None:
        """The portable anchor follows the Error guard after index postprocessing and Wasm
        runtime configuration, including generated whitespace/comments around the guard."""
        root = SCRIPT.resolve().parents[2]
        template = (root / "port/web/launcher/index.template.html").read_text(encoding="utf-8")
        for profile_id in (RENDERPEARL_ID, CANONICAL_ID, LEGACY_ID):
            profile = POSTPROCESS.load_profile_for_version(profile_id)
            with tempfile.TemporaryDirectory() as directory:
                dist = Path(directory)
                index = dist / "index.html"
                index.write_text(template, encoding="utf-8")
                (dist / "classes.js").write_text("classes", encoding="utf-8")
                for name, contents in (
                    ("vanilla-assets.pack.gz", b"assets"),
                    ("singleplayer-server-worker.js", b"worker"),
                    ("singleplayer-server.js", b"server"),
                ):
                    (dist / name).write_bytes(contents)
                POSTPROCESS.patch_index(index, dist / "classes.js", profile_id, profile=profile)
                js_page = index.read_text(encoding="utf-8")
                self.assertIn('typeof Error === "function"',
                              PORTABLE.portable_launcher_marker(js_page))

                # make-wasm-page.py adds this head block. Keep a formatting mutation in the
                # launcher script to cover the generated page variants that broke the old
                # exact whitespace marker.
                wasm_page = POSTPROCESS.apply_runtime_config(
                    js_page,
                    POSTPROCESS.runtime_config_script(
                        {"wasm": {"client": "classes.wasm"}}
                    ),
                )
                wasm_page = wasm_page.replace(
                    '  <script>\n    if (typeof Error === "function")',
                    '  <script data-gaius-runtime="wasmgc">\r\n'
                    '    /* runtime page guard */\r\n'
                    '    if ( typeof Error === "function" )',
                    1,
                )
                self.assertIn('typeof Error === "function"',
                              PORTABLE.portable_launcher_marker(wasm_page))

    def test_launcher_marker_reports_a_missing_guard(self) -> None:
        with self.assertRaisesRegex(RuntimeError, r"portable launcher insertion point.*found 0"):
            PORTABLE.portable_launcher_marker("<html><body></body></html>")

    def test_legacy_profile_disables_modern_terrain_batch_path(self) -> None:
        profile = POSTPROCESS.load_profile_for_version(LEGACY_ID)
        source = (SCRIPT.parent.parent / "overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java").read_text()
        overlay = (SCRIPT.parent / "build-overlays.sh").read_text()
        self.assertEqual(profile["patchSet"], "legacy-12111")
        self.assertIn("GaiusProfileCapabilities.PATCH_SET", source)
        self.assertIn("const modernProfile=String(patchSet || '').trim()!=='legacy-12111';", source)
        self.assertIn("state.terrainBatchEnabled=modernProfile && !wasmNeedsVanillaTerrain", source)
        self.assertIn("jq -r '.terrainSectionUniformBlock // false'", overlay)

    def test_dual_portable_runs_boot_once_after_the_bootstrap(self) -> None:
        """integ4-t6: a second gaius-boot.js in front of the bootstrap ran first (the __gaiusBoot
        guard keeps the first copy), before window.__gaiusPortableWebGpu existed."""
        root = SCRIPT.resolve().parents[2]
        relay = "Object.defineProperty(globalThis,'__gaiusRelayProtocolDeclaration',{value:1});\n"
        runtime_inline, _hashes = PORTABLE.runtime_bootstrap(root, wasm_client={
            "scripts": {name: "void 0;\n" for name in PORTABLE.WASM_CLIENT_SCRIPTS},
            "server": None, "relayProtocol": relay})
        marker = "  <script>\n    if (typeof Error === \"function\")"
        bootstrap_tag = '  <script data-gaius-portable="1">\n'
        bootstrap = (bootstrap_tag
                     + "      window.__gaiusPortableWebGpu = {translator: {load: () => null}};\n"
                     + "  </script>\n")
        index = ("<head>\n" + PORTABLE.BOOT_TAG + "</head>\n<body>\n" + marker
                 + " { Error.stackTraceLimit = 100; }\n  </script>\n</body>\n")
        index = index.replace(PORTABLE.BOOT_TAG, runtime_inline, 1)
        payload = '<script type="text/x-gaius-b7" data-gaius-asset="classes" data-part="0">a</script>\n'
        unarranged = index.replace(marker, payload + bootstrap + marker, 1)
        page = PORTABLE.arrange_portable_boot(unarranged, marker, runtime_inline)
        boot_marker = "const clientRuntime = selectRuntime"
        self.assertEqual(page.count(boot_marker), 1)
        self.assertLess(page.index(bootstrap_tag), page.index(boot_marker))
        self.assertLess(page.index("window.__gaiusPortableWebGpu"), page.index(boot_marker))
        self.assertLess(page.index(boot_marker), page.index(marker))
        self.assertLess(page.index("window.__gaiusPortableRuntimes = "), page.index(bootstrap_tag))
        self.assertEqual(page.count("window.__gaiusPortableRuntimes = "), 1)
        self.assertEqual(page.count("data-gaius-relay-protocol"), 1)
        self.assertLess(page.index("data-gaius-relay-protocol"), page.index(bootstrap_tag))
        for name in PORTABLE.WASM_CLIENT_SCRIPTS:
            self.assertEqual(page.count(f'data-gaius-wasm-script="{name}"'), 1, name)
        for name in PORTABLE.QUALITY_MODULES:
            self.assertEqual(page.count(f'<script data-gaius-boot-module="{name}">'), 1, name)
        for name in PORTABLE.WEBGPU_MODULES:
            self.assertEqual(page.count(f'<script data-gaius-boot-module="{name}">'), 1, name)
        # The bootstrap now runs before gaius-boot.js, so it may read __gaiusBoot only lazily
        # (when a world starts), never while it runs.
        self.assertIn("const portableWasmServer = () => !!(window.__gaiusBoot",
                      PORTABLE.PORTABLE_BOOTSTRAP)
        self.assertEqual([line for line in PORTABLE.PORTABLE_BOOTSTRAP.splitlines()
                          if "window.__gaiusBoot" in line and "portableWasmServer = () =>" not in line], [])
        # A layout the boot source cannot be placed in is rejected, never shipped without boot.
        with self.assertRaisesRegex(RuntimeError, "boot sources"):
            PORTABLE.arrange_portable_boot(unarranged, "  <script>\n    missing", runtime_inline)

    def test_portable_exposes_wasm_runtime_only(self) -> None:
        runtime, _hashes = PORTABLE.runtime_bootstrap(
            SCRIPT.parents[2], wasm_client={"scripts": {}, "server": None, "relayProtocol": None}
        )
        self.assertNotIn("window.__gaiusRuntimeDefault", runtime)

    def test_make_wasm_page_stages_a_wasmgc_dist(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            js_dist = Path(directory) / "js"
            wasm_dist = Path(directory) / "wasm"
            js_dist.mkdir()
            wasm_dist.mkdir()
            profile = POSTPROCESS.load_profile_for_version(RENDERPEARL_ID)
            source_page = POSTPROCESS.patch_early_profile_id(
                "<head>\n" + PORTABLE.BOOT_TAG + "</head>\n", profile)
            (js_dist / "index.html").write_text(source_page, encoding="utf-8")
            for name in ("classes.js", "Gaius.html", "Gaius.manifest.json", "singleplayer-server-worker.js",
                         "gaius-boot.js"):
                (js_dist / name).write_text(name, encoding="utf-8")
            (js_dist / "vanilla-assets.pack.gz").write_bytes(gzip.compress(b"GAIUSVP1", mtime=0))
            (wasm_dist / "classes.wasm").write_bytes(b"\0asm\1\0\0\0client")
            (wasm_dist / "singleplayer-server.wasm").write_bytes(b"\0asm\1\0\0\0server")
            (wasm_dist / "classes.wasm.gz.js").write_text("stale", encoding="utf-8")
            relay_script = "Object.defineProperty(globalThis, '__gaiusRelayProtocolDeclaration', {value: null});\n"
            (wasm_dist / "relay-protocol.js").write_text(relay_script, encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                status = MAKE_WASM_PAGE.main([str(js_dist), str(wasm_dist), "--gzip-payload"])
            self.assertEqual(status, 0)
            for name in ("classes.js", "singleplayer-server-worker.js", "gaius-boot.js", "gaius-wasm-boot.js",
                         "gaius-jspi.js", "gaius-wasm-worker.js", "classes.wasm.payload.js",
                         "vanilla-assets.pack.gz.payload.js", "index.html", "index-wasm.html"):
                self.assertTrue((wasm_dist / name).is_file(), name)
            for name in ("Gaius.html", "Gaius.manifest.json", "classes.wasm.gz.js"):
                self.assertFalse((wasm_dist / name).exists(), name)
            # The page runtime and the Worker bootstrap come from this tree, not from the
            # JavaScript dist (which may be older); so do the wasm loaders.
            web = SCRIPT.parent.parent / "web"
            self.assertEqual((wasm_dist / "gaius-boot.js").read_bytes(), (web / "boot" / "gaius-boot.js").read_bytes())
            self.assertEqual((wasm_dist / "singleplayer-server-worker.js").read_bytes(),
                             (web / "singleplayer" / "server-worker-bootstrap.js").read_bytes())
            wasmgc = SCRIPT.parent.parent / "web" / "runtime" / "wasmgc"
            self.assertEqual((wasm_dist / "gaius-wasm-worker.js").read_bytes(), (wasmgc / "gaius-wasm-worker.js").read_bytes())
            page = (wasm_dist / "index.html").read_text(encoding="utf-8")
            self.assertIn('data-gaius-profile-early="v1"', page)
            for name in ("__gaiusProfileId", "__gaiusRenderSeam", "__gaiusPatchSet",
                         "__gaiusTerrainSectionUniformBlock"):
                self.assertIn(f"window.{name} = ", page)
            self.assertIn(relay_script, page)
            self.assertEqual(page.count("data-gaius-relay-protocol"), 1)
            self.assertLess(page.index(relay_script), page.index(PORTABLE.BOOT_TAG))
            self.assertIn('window.__gaiusRuntimes = {"wasm":{"client":"classes.wasm","server":"singleplayer-server.wasm"}};', page)
            self.assertNotIn('window.__gaiusRuntimeDefault', page)
            self.assertIn('"classes.wasm":{"bytes":14,"gzip":true', page)
            self.assertEqual(page, (wasm_dist / "index-wasm.html").read_text(encoding="utf-8"))
            with contextlib.redirect_stdout(io.StringIO()):
                MAKE_WASM_PAGE.main([str(js_dist), str(wasm_dist)])
            page = (wasm_dist / "index.html").read_text(encoding="utf-8")
            self.assertNotIn('window.__gaiusRuntimeDefault', page)
            self.assertNotIn("__gaiusFilePayloads", page)
            self.assertEqual(page.count("data-gaius-relay-protocol"), 1)
            self.assertEqual((js_dist / "index.html").read_text(encoding="utf-8"), source_page)
            self.assertNotIn("__gaiusWebGpuShaderToolchain", page)
            # A shader toolchain staged for the WebGPU GLSL front-end (26.2, 1.21.11) is named for
            # gaius-boot.js, which loads it for ?gpu=webgpu only.
            for name in POSTPROCESS.SHADER_TOOLCHAIN_FILES:
                (wasm_dist / name).write_text(name, encoding="utf-8")
            token = POSTPROCESS.content_token(*(wasm_dist / name for name in POSTPROCESS.SHADER_TOOLCHAIN_FILES))
            with contextlib.redirect_stdout(io.StringIO()):
                MAKE_WASM_PAGE.main([str(js_dist), str(wasm_dist)])
            page = (wasm_dist / "index.html").read_text(encoding="utf-8")
            self.assertIn('window.__gaiusWebGpuShaderToolchain = {"loader":"gaius-shader-toolchain.js?v='
                          + token + '","version":"' + token + '"};', page)
            self.assertNotIn(POSTPROCESS.SHADER_TOOLCHAIN_MARKER, page)
            # A source page that already carries an eager loader still publishes the same
            # lazy configuration for the WasmGC page; the release site decides when to load it.
            shader_page = (
                "<head>\n  <script " + POSTPROCESS.SHADER_TOOLCHAIN_MARKER + (' data-profile="' + RENDERPEARL_ID + '" ') +
                'src="gaius-shader-toolchain.js?v=1"></script>\n' + PORTABLE.BOOT_TAG + "</head>\n")
            (js_dist / "index.html").write_text(
                POSTPROCESS.patch_early_profile_id(shader_page, profile), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                MAKE_WASM_PAGE.main([str(js_dist), str(wasm_dist)])
            self.assertIn("__gaiusWebGpuShaderToolchain", (wasm_dist / "index.html").read_text(encoding="utf-8"))

    def test_atomic_write_replaces_complete_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "Gaius.html"
            target.write_text("old release", encoding="utf-8")

            PORTABLE.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), "new release")
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])

    def test_replace_failure_preserves_original_and_cleans_temp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "Gaius.html"
            target.write_text("old release", encoding="utf-8")

            with mock.patch.object(PORTABLE.os, "replace", side_effect=OSError("ENOSPC")):
                with self.assertRaises(OSError):
                    PORTABLE.write_text_atomically(target, "new release")

            self.assertEqual(target.read_text(encoding="utf-8"), "old release")
            self.assertEqual(list(target.parent.glob(f".{target.name}.*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
