import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("stage-train.py")
SPEC = importlib.util.spec_from_file_location("stage_train", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class StageTrainTest(unittest.TestCase):
    def test_ready_discovers_only_clients(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "READY.txt").write_text(
                "alpha server x/singleplayer-server.wasm hash\n"
                "alpha client x/classes.wasm hash\n"
                "beta client y/classes.wasm hash\n", encoding="utf-8")
            self.assertEqual(MODULE.profiles_from_ready(root), ["alpha", "beta"])

    def test_prepare_input_uses_current_launcher(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            frozen = root / "client"
            output = root / "input"
            frozen.mkdir()
            (frozen / "classes.wasm").write_bytes(b"\0asm\x01\0\0\0")
            (frozen / "vanilla-assets.pack.gz").write_bytes(b"fixture")
            output.mkdir()
            (output / "stale").write_bytes(b"preserve")
            with mock.patch.object(MODULE, "run") as run:
                MODULE.prepare_input("fixture", frozen, output)
            self.assertEqual((output / "classes.wasm").read_bytes(), b"\0asm\x01\0\0\0")
            self.assertEqual((output / "vanilla-assets.pack.gz").read_bytes(), b"fixture")
            self.assertEqual((output / "index.html").read_bytes(),
                             (MODULE.ROOT / "port/web/launcher/index.template.html").read_bytes())
            self.assertFalse((output / "stale").exists())
            self.assertEqual((root / "input.previous-1/stale").read_bytes(), b"preserve")
            self.assertEqual(run.call_args.args[0][-1], "fixture")

    def test_prepare_input_requires_wasm_client(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = root / "client"
            output = root / "input"
            client.mkdir()
            with mock.patch.object(MODULE, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "no classes.wasm"):
                    MODULE.prepare_input("fixture", client, output)

    def test_prepare_input_copies_wasm_support_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = root / "client"
            output = root / "input"
            client.mkdir()
            (client / "classes.wasm").write_bytes(b"wasm")
            for name in MODULE.SHADER_TOOLCHAIN_PAYLOAD:
                (client / name).write_bytes(name.encode())
            with mock.patch.object(MODULE, "run"):
                MODULE.prepare_input("fixture", client, output)
            self.assertEqual((output / "classes.wasm").read_bytes(), b"wasm")

    def test_prepare_input_carries_phase_two_relay_declaration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = root / "client"
            output = root / "input"
            client.mkdir()
            (client / "classes.wasm").write_bytes(b"wasm")
            (client / "relay-protocol.js").write_text("phase-2", encoding="utf-8")
            with mock.patch.object(MODULE, "run"):
                MODULE.prepare_input("fixture", client, output)
            self.assertEqual((output / "relay-protocol.js").read_text(encoding="utf-8"), "phase-2")

    def test_stale_server_requires_ready_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for role, file in (("client", "classes.wasm"), ("server", "singleplayer-server.wasm")):
                artifact = root / "dist" / f"wasm-fixture-{role}" / file
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"fixture")
            (root / "READY.txt").write_text("fixture client x/classes.wasm hash\n", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "no READY.txt server gate"):
                MODULE.wasm_paths(root, "fixture")

    def test_release_staging_has_no_frozen_js_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            train = root / "train"
            train.mkdir()
            (train / "READY.txt").write_text("fixture client x/classes.wasm hash\n", encoding="utf-8")
            (train / "dist/wasm-fixture-client").mkdir(parents=True)
            output = root / "staged"
            with mock.patch("sys.stdout"):
                status = MODULE.main([str(train), "--profile", "fixture", "--output", str(output),
                                      "--scratch", str(root / "scratch")])
            receipt = json.loads((output / "STAGED.txt").read_text(encoding="utf-8"))
            self.assertEqual(status, 2)
            self.assertEqual(receipt["profiles"], [])
            self.assertIn("client artifact is not ready", receipt["pending"][0])

    def test_requested_profile_missing_from_ready_is_a_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            train = root / "train"
            train.mkdir()
            (train / "READY.txt").write_text("other client x/classes.wasm hash\n", encoding="utf-8")
            output = root / "staged"
            with mock.patch("sys.stdout"):
                status = MODULE.main([str(train), "--profile", "fixture", "--output", str(output)])
            receipt = json.loads((output / "STAGED.txt").read_text(encoding="utf-8"))
            self.assertEqual(status, 2)
            self.assertIn("fixture: no client artifact listed in READY.txt", receipt["pending"])

    def test_wasm_only_site_and_portable_use_acceptance_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client, server = root / "client", root / "server"
            staged = root / "staged"
            with mock.patch.object(MODULE, "wasm_paths", return_value=(client, server)), \
                    mock.patch.object(MODULE, "prepare_input", return_value=root / "input"), \
                    mock.patch.object(MODULE, "run") as run:
                MODULE.stage_profile(root, "fixture", staged, root / "scratch")
            commands = [call.args[0] for call in run.call_args_list]
            self.assertEqual(len(commands), 3)
            self.assertTrue(all("--no-js-fallback" in command for command in commands))
            self.assertEqual(commands[-1][-3:-1], ["--site-shell", str(staged / "acceptance-fixture")])

    def test_portable_always_uses_wasm_only_mode_and_creates_output_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staged = root / "new-staged"
            client, server = root / "client", root / "server"
            with mock.patch.object(MODULE, "wasm_paths", return_value=(client, server)), \
                    mock.patch.object(MODULE, "prepare_input", return_value=root / "input"), \
                    mock.patch.object(MODULE, "run") as run:
                MODULE.stage_profile(root, "fixture", staged, root / "scratch")
            commands = [call.args[0] for call in run.call_args_list]
            portable_command = commands[-1]
            self.assertIn("--no-js-fallback", portable_command)
            self.assertNotIn("--frozen-js", portable_command)
            self.assertIn("--site-shell", portable_command)
            self.assertTrue((staged / "portable-fixture").is_dir())


if __name__ == "__main__":
    unittest.main()
