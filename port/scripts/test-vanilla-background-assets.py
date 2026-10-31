#!/usr/bin/env python3
"""Verify indexed menu textures override the official JAR placeholders."""
import gzip
import hashlib
import importlib.util
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "pack", Path(__file__).with_name("build-vanilla-assets-pack.py"))
pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pack)


class BackgroundAssetsTest(unittest.TestCase):
    def test_indexed_override_and_corrupt_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = "assets/minecraft/textures/gui/title/background/panorama_0.png"
            names = pack.REQUIRED_RESOURCES | {name}
            listing = root / "resources.txt"
            listing.write_text("\n".join(sorted(names)), encoding="utf-8")
            jar = root / "client.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for resource in names:
                    archive.writestr(resource, b"jar-placeholder")
            content = b"official-indexed-background"
            digest = hashlib.sha1(content).hexdigest()
            index = root / "assets/indexes/test.json"
            index.parent.mkdir(parents=True)
            index.write_text(json.dumps({"objects": {name.removeprefix("assets/"): {
                "hash": digest, "size": len(content)}}}), encoding="utf-8")
            obj = root / "assets/objects" / digest[:2] / digest
            obj.parent.mkdir(parents=True)
            obj.write_bytes(content)
            output = root / "output.gz"
            pack.build(listing, jar, root, output, index)
            data = gzip.decompress(output.read_bytes())
            size = struct.unpack_from("<I", data, 8)[0]
            entries = json.loads(data[12:12 + size])
            offset, length = entries[name]
            self.assertEqual(data[12 + size + offset:12 + size + offset + length], content)
            obj.write_bytes(b"corrupt")
            with self.assertRaisesRegex(RuntimeError, "invalid indexed background"):
                pack.build(listing, jar, root, output, index)


if __name__ == "__main__":
    unittest.main()
