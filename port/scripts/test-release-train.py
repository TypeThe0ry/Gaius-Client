#!/usr/bin/env python3
"""Exercise release receipts and refusal paths without TeaVM or Chrome."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release_train", Path(__file__).with_name("release-train.py"))
train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(train)


class TrainTests(unittest.TestCase):
    def test_build_environment_removes_diagnostic_and_skip_flags(self):
        with patch.dict(os.environ, {"GAIUS_SKIP_KERNELS": "1", "GAIUS_PREPARE_ONLY": "true",
                                     "MAVEN_OPTS": "-Xmx1g", "GAIUS_TEAVM_WORKER_STRIP_TELEMETRY": "false"}):
            env = train.build_env(Path("output"), train.profiles()[0], "server", "wasmgc")
        for name in ("GAIUS_SKIP_KERNELS", "GAIUS_PREPARE_ONLY", "MAVEN_OPTS"):
            self.assertNotIn(name, env)
        self.assertEqual("true", env["GAIUS_TEAVM_WORKER_STRIP_TELEMETRY"])

    def test_client_heap_override_does_not_change_worker_budget(self):
        with patch.dict(os.environ, {"GAIUS_TEAVM_MAX_HEAP": "11g"}):
            client = train.build_env(Path("output"), train.profiles()[0], "client", "wasmgc")
            worker = train.build_env(Path("output"), train.profiles()[0], "server", "wasmgc")
        self.assertEqual("11g", client["GAIUS_TEAVM_MAX_HEAP"])
        self.assertEqual("14g", worker["GAIUS_TEAVM_MAX_HEAP"])

    def test_preflight_refuses_dirty_source_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / "train"
            with patch.object(train, "clean", side_effect=RuntimeError("dirty")):
                with self.assertRaisesRegex(RuntimeError, "dirty"):
                    train.preflight(out, Path("resources"), "rebuild")
            self.assertFalse(out.exists())

    def test_preflight_refuses_existing_output(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            (out / "receipt").write_text("old")
            with self.assertRaisesRegex(RuntimeError, "new or empty"):
                with patch.object(train, "clean"):
                    train.preflight(out, Path("resources"), "rebuild")

    def test_manifest_refuses_incomplete_or_duplicate_acceptance(self):
        cases = ["site-wasm-auto", "site-wasm-webgl2", "site-wasm-webgpu", "acceptance-default",
                 "reload-soak", "portable-file", "quit-game-site", "quit-game-portable",
                 "fallback-webgpu-unavailable", "unsupported-browser"]
        expected = [(profile, case) for profile in train.profiles() for case in cases]
        for rows in (expected[:-1], [expected[0]] * 30):
            with self.subTest(rowCount=len(rows)), tempfile.TemporaryDirectory() as temp:
                out = Path(temp)
                (out / "accept").mkdir()
                (out / "SOURCE.json").write_text(json.dumps({"commit": "test", "internalJsMode": "rebuild"}))
                (out / "accept/matrix.json").write_text(json.dumps({"verdict": "PASS", "pending": [],
                    "rows": [{"profile": profile, "id": case, "verdict": "PASS"} for profile, case in rows]}))
                with patch.object(train, "clean"):
                    with self.assertRaisesRegex(RuntimeError, "30 distinct"):
                        train.manifest(out)
                self.assertFalse((out / "release-manifest.json").exists())

    def test_disk_gate_refuses_low_space(self):
        with patch.object(train.shutil, "disk_usage") as usage:
            usage.return_value.free = train.MIN_FREE - 1
            with self.assertRaisesRegex(RuntimeError, "10 GiB"):
                train.disk(train.ROOT)

if __name__ == "__main__":
    unittest.main()
