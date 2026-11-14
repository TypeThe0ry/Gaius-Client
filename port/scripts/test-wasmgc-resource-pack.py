#!/usr/bin/env python3
"""Keep the active Wasm resource pack while adding a JavaScript fallback."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "web/runtime/wasmgc/make-wasm-page.py"
SPEC = importlib.util.spec_from_file_location("make_wasm_page", SOURCE)
PAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAGE)
PACK = "vanilla-assets.pack.gz"
SIDECAR = PACK + ".build.json"


class WasmResourcePackTest(unittest.TestCase):
    def test_current_pack_survives_a_newer_fallback_timestamp(self):
        for has_sidecar in (False, True):
            with self.subTest(has_sidecar=has_sidecar), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                js, wasm = root / "js", root / "wasm"
                js.mkdir(); wasm.mkdir()
                for name in (PACK, SIDECAR):
                    (js / name).write_bytes(b"frozen-" + name.encode())
                    os.utime(js / name, (2_000_000_000, 2_000_000_000))
                (wasm / PACK).write_bytes(b"current-wasm-pack")
                if has_sidecar:
                    (wasm / SIDECAR).write_bytes(b"current-wasm-identity")
                PAGE.copy_javascript_dist(js, wasm)
                self.assertEqual((wasm / PACK).read_bytes(), b"current-wasm-pack")
                if has_sidecar:
                    self.assertEqual((wasm / SIDECAR).read_bytes(), b"current-wasm-identity")
                else:
                    self.assertFalse((wasm / SIDECAR).exists())

    def test_unihex_table_of_the_fallback_is_not_copied_next_to_the_wasm_pack(self):
        for has_table in (False, True):
            with self.subTest(has_table=has_table), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                js, wasm = root / "js", root / "wasm"
                js.mkdir(); wasm.mkdir()
                (js / "gaius-unihex.bin.gz").write_bytes(b"fallback-table")
                os.utime(js / "gaius-unihex.bin.gz", (2_000_000_000, 2_000_000_000))
                (wasm / PACK).write_bytes(b"current-wasm-pack")
                if has_table:
                    (wasm / "gaius-unihex.bin.gz").write_bytes(b"current-wasm-table")
                PAGE.copy_javascript_dist(js, wasm)
                if has_table:
                    self.assertEqual((wasm / "gaius-unihex.bin.gz").read_bytes(), b"current-wasm-table")
                else:
                    self.assertFalse((wasm / "gaius-unihex.bin.gz").exists())

    def test_historical_dist_without_a_pack_can_copy_the_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            js, wasm = root / "js", root / "wasm"
            js.mkdir(); wasm.mkdir()
            for name in (PACK, SIDECAR):
                (js / name).write_bytes(name.encode())
            PAGE.copy_javascript_dist(js, wasm)
            for name in (PACK, SIDECAR):
                self.assertEqual((wasm / name).read_bytes(), name.encode())

    def test_other_newer_support_files_still_refresh(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            js, wasm = root / "js", root / "wasm"
            js.mkdir(); wasm.mkdir()
            (wasm / PACK).write_bytes(b"current-wasm-pack")
            (wasm / "gaius-hotpath.wasm").write_bytes(b"old-js")
            (js / "gaius-hotpath.wasm").write_bytes(b"new-js")
            os.utime(js / "gaius-hotpath.wasm", (2_000_000_000, 2_000_000_000))
            PAGE.copy_javascript_dist(js, wasm)
            self.assertEqual((wasm / "gaius-hotpath.wasm").read_bytes(), b"new-js")


if __name__ == "__main__":
    unittest.main()
