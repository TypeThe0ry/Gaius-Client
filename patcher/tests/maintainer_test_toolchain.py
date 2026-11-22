"""Maintainer-only toolchain lock checks requiring excluded shader-loader files.

This entrypoint is intentionally prefixed with `maintainer_` so the public CI's
`python3 -m unittest discover -s patcher/tests -v` only executes tests that run
on the exported public tree. Run this suite in a full maintainer checkout:

    python3 -m unittest patcher/tests/maintainer_test_toolchain.py -v
    python3 -m unittest discover -s patcher/tests -p 'maintainer_test_*.py' -v
"""

import hashlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import lock_shader_toolchain as shader_lock  # noqa: E402

PATCHER_DIR = Path(__file__).resolve().parents[1]
LOCK = json.loads((PATCHER_DIR / "toolchain.lock").read_text(encoding="utf-8"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class MaintainerShaderToolchainLockTest(unittest.TestCase):
    def test_shader_loader_hash_matches(self):
        loader_path = shader_lock.SOURCE / "loader" / shader_lock.LOADER
        self.assertTrue(
            loader_path.is_file(),
            f"maintainer-only shader loader missing: {loader_path}",
        )
        self.assertEqual(
            LOCK["wasm"]["shader"]["files"][shader_lock.LOADER],
            sha256(loader_path),
        )

    def test_shader_lock_generator_rejects_tampering_and_stale_loader(self):
        loader_source = shader_lock.SOURCE / "loader" / shader_lock.LOADER
        self.assertTrue(
            loader_source.is_file(),
            f"maintainer-only shader loader missing: {loader_source}",
        )
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            manifest_path = directory / shader_lock.MANIFEST
            pins = dict(
                line.split("=", 1)
                for line in (shader_lock.SOURCE / "pins.env").read_text(encoding="utf-8").splitlines()
                if line.startswith("GAIUS_ST_")
            )
            manifest = {
                "schema": 1,
                "emscripten": f"{pins['GAIUS_ST_EMSCRIPTEN_VERSION']} ({pins['GAIUS_ST_EMSCRIPTEN_COMMIT']})",
                "sources": {
                    name: pins[f"GAIUS_ST_{name.upper().replace('-', '_')}_COMMIT"]
                    for name in shader_lock.SOURCES
                },
                "files": {},
            }
            for name in shader_lock.FILES:
                data = loader_source.read_bytes() if name == shader_lock.LOADER else name.encode("ascii")
                (directory / name).write_bytes(data)
                manifest["files"][name] = {"bytes": len(data), "sha256": sha256(directory / name)}
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            pins = shader_lock.shader_pins(directory)
            original = (PATCHER_DIR / "toolchain.lock").read_text(encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "--archive is required"):
                shader_lock.refresh_lock(original, pins)
            archive = shader_lock.pack_archive(directory, pins)
            self.assertEqual(shader_lock.pack_archive(directory, pins), archive)
            with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
                self.assertEqual(zipped.namelist(), [shader_lock.MANIFEST, *shader_lock.FILES])
                for name in zipped.namelist():
                    self.assertEqual(zipped.read(name), (directory / name).read_bytes())
            pin = {"file": "shader.zip", "bytes": len(archive), "sha256": shader_lock.sha256(archive)}
            updated = shader_lock.refresh_lock(original, pins, pin)
            self.assertEqual(shader_lock.refresh_lock(updated, pins, pin), updated)
            expected = json.loads(original)
            expected["wasm"]["shader"].update(pins, prebuiltArchive=pin)
            self.assertEqual(json.loads(updated), expected)
            expected["wasm"]["shader"]["distribution"]["status"] = "published"
            with self.assertRaisesRegex(ValueError, "active distribution metadata"):
                shader_lock.refresh_lock(json.dumps(expected), pins, pin)

            loader = directory / shader_lock.LOADER
            loader.write_bytes(b"stale loader")
            with self.assertRaisesRegex(ValueError, "changed after manifest verification"):
                shader_lock.pack_archive(directory, pins)
            with self.assertRaisesRegex(ValueError, "does not match its manifest"):
                shader_lock.shader_pins(directory)
            manifest["files"][shader_lock.LOADER] = {"bytes": loader.stat().st_size, "sha256": sha256(loader)}
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "does not match the loader in this checkout"):
                shader_lock.shader_pins(directory)


if __name__ == "__main__":
    unittest.main()
