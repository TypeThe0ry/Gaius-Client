#!/usr/bin/env python3
"""Verify overlay JAR normalization removes timestamp-only differences."""

import hashlib
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "normalize_overlay_jars", Path(__file__).with_name("normalize-overlay-jars.py")
)
NORMALIZER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(NORMALIZER)


class NormalizeOverlayJarsTest(unittest.TestCase):
    def test_timestamp_variants_normalize_to_identical_archives(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jars = [root / "first.jar", root / "second.jar"]
            timestamps = ((2026, 1, 2, 3, 4, 6), (2026, 9, 8, 7, 6, 4))
            for jar, timestamp in zip(jars, timestamps):
                with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
                    for name, content in (("z/Second.class", b"second class"), ("a/First.class", b"first class")):
                        archive.writestr(zipfile.ZipInfo(name, timestamp), content)

            for jar in jars:
                NORMALIZER.normalize_jar(jar)

            self.assertEqual(hashlib.sha256(jars[0].read_bytes()).digest(),
                             hashlib.sha256(jars[1].read_bytes()).digest())
            for jar in jars:
                with zipfile.ZipFile(jar) as archive:
                    self.assertEqual(archive.namelist(), ["z/Second.class", "a/First.class"])
                    self.assertEqual({entry.date_time for entry in archive.infolist()}, {NORMALIZER.FIXED_TIME})
                    self.assertEqual(archive.read("z/Second.class"), b"second class")
                    self.assertEqual(archive.read("a/First.class"), b"first class")

    def test_normalizing_an_archive_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "overlay.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("example/Overlay.class", b"class bytes")
            NORMALIZER.normalize_jar(jar)
            first = hashlib.sha256(jar.read_bytes()).digest()
            NORMALIZER.normalize_jar(jar)
            self.assertEqual(hashlib.sha256(jar.read_bytes()).digest(), first)


if __name__ == "__main__":
    unittest.main()
