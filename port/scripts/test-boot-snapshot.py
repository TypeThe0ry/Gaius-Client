#!/usr/bin/env python3
"""Check the compact build-time boot snapshot framing and payload hashes."""

import gzip
import hashlib
import importlib.util
import json
import struct
import tempfile
import unittest
from pathlib import Path
from verification_profiles import RENDERPEARL_ID


SPEC = importlib.util.spec_from_file_location(
    "snapshot", Path(__file__).with_name("build-boot-snapshot.py")
)
SNAPSHOT = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(SNAPSHOT)


class BootSnapshotTest(unittest.TestCase):
    def test_data_entries_are_framed_and_hashed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resources = {
                "assets/minecraft/textures/block/stone.png": b"asset",
                "data/minecraft/worldgen/biome/test.json": b"{\"x\":1}",
                "data/minecraft/loot/test.json": b"{\"p\":2}",
            }
            payload = bytearray()
            index = {}
            for name, content in resources.items():
                index[name] = [len(payload), len(content)]
                payload.extend(content)
            pack = b"GAIUSVP1" + struct.pack("<I", len(json.dumps(index, separators=(",", ":")).encode()))
            pack += json.dumps(index, separators=(",", ":")).encode() + payload
            pack_path = root / "vanilla-assets.pack.gz"
            with pack_path.open("wb") as raw:
                with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as stream:
                    stream.write(pack)
            output = root / "gaius-init-snapshot.bin"
            result = SNAPSHOT.build(pack_path, RENDERPEARL_ID, output)
            self.assertEqual(result["entries"], 2)
            data = output.read_bytes()
            self.assertEqual(data[:8], b"GAIUSIS1")
            version, profile_length, count, payload_length = struct.unpack_from("<HHII", data, 8)
            self.assertEqual((version, profile_length, count), (1, len(RENDERPEARL_ID.encode()), 2))
            self.assertEqual(payload_length, 0)
            self.assertEqual(hashlib.sha256(data).hexdigest(), result["sha256"])

    def test_snapshot_can_follow_a_repacked_core_pack(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entries = [
                ("assets/minecraft/sounds/late.ogg", b"sound"),
                ("data/minecraft/registry/test.json", b"registry"),
            ]
            payload = bytearray()
            index = {}
            for name, content in entries:
                index[name] = [len(payload), len(content)]
                payload.extend(content)
            index_bytes = json.dumps(index, separators=(",", ":")).encode()
            full = b"GAIUSVP1" + struct.pack("<I", len(index_bytes)) + index_bytes + payload
            core_entries = [entries[1]]
            core_payload = core_entries[0][1]
            core_index = {core_entries[0][0]: [0, len(core_payload)]}
            core_index_bytes = json.dumps(core_index, separators=(",", ":")).encode()
            core = b"GAIUSVP1" + struct.pack("<I", len(core_index_bytes)) + core_index_bytes + core_payload
            full_gz = gzip.compress(full, mtime=0)
            core_gz = gzip.compress(core, mtime=0)
            data, result = SNAPSHOT.build_compressed(core_gz, RENDERPEARL_ID)
            self.assertEqual(result["entries"], 1)
            version, profile_length, count, _ = struct.unpack_from("<HHII", data, 8)
            self.assertEqual((version, profile_length, count), (1, 4, 1))
            self.assertNotEqual(full_gz, core_gz)


if __name__ == "__main__":
    unittest.main()
