"""Synthetic checks for the recursive artifact equality gate."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "compare_builds", Path(__file__).resolve().parents[1] / "tools/compare_builds.py")
COMPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARE)


class CompareBuildsTest(unittest.TestCase):
    def fixture(self, root):
        (root / "site/nested").mkdir(parents=True)
        (root / "site/nested/client.js").write_bytes(b"same code\n")
        (root / "Gaius-fixture.html").write_bytes(b"portable\n")
        (root / "expected-hashes.json").write_bytes(b"{}\n")
        return root

    def test_equal_outputs_in_three_different_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            roots = [self.fixture(Path(directory) / name) for name in ("one", "long path/two", "three")]
            (roots[0] / "build.log").write_text("ignored diagnostic log")
            result = COMPARE.compare(roots)
            self.assertTrue(result["identical"])
            self.assertEqual(len(result["files"][0]), 3)

    def test_content_and_missing_files_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            left = self.fixture(Path(directory) / "left")
            right = self.fixture(Path(directory) / "right")
            (right / "site/nested/client.js").write_bytes(b"same CODE\n")
            (right / "site/extra.js").write_bytes(b"extra")
            result = COMPARE.compare([left, right])
            self.assertFalse(result["identical"])
            by_name = {item["file"]: item for item in result["differences"]}
            self.assertIsNone(by_name["site/extra.js"]["sha256"][0])
            self.assertEqual(by_name["site/nested/client.js"]["content"][0]["offset"], 5)

    def test_difference_after_chunk_boundary_and_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            prefix = b"x" * (COMPARE.CHUNK_SIZE + 3)
            left.write_bytes(prefix)
            right.write_bytes(prefix + b"!")
            self.assertEqual(COMPARE.content_difference(left, right)["offset"], len(prefix))

    def test_host_tool_drift_breaks_determinism_even_for_identical_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            roots = [self.fixture(Path(directory) / name) for name in ("left", "right")]
            for root, version in zip(roots, (133, 120)):
                (root / "wasm-host-tools.json").write_text(f'{{"wasmOpt":"wasm-opt version {version}"}}\n')
            self.assertEqual([item["file"] for item in COMPARE.compare(roots)["differences"]],
                             ["wasm-host-tools.json"])
            (roots[1] / "wasm-host-tools.json").unlink()
            self.assertFalse(COMPARE.compare(roots)["identical"])

    def test_incomplete_build_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                COMPARE.artifacts(Path(directory))


if __name__ == "__main__":
    unittest.main()
