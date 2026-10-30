#!/usr/bin/env python3
"""Regression tests for the Anvil region-file analyzer."""

from __future__ import annotations

import importlib.util
import struct
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("analyze-mca.py")
SPEC = importlib.util.spec_from_file_location("analyze_mca", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AnalyzerTests(unittest.TestCase):
    def write_region(self, name: str, raw: bytes) -> Path:
        path = Path(self.id().replace(".", "_") + "-" + name + ".mca")
        path.write_bytes(raw)
        self.addCleanup(path.unlink)
        return path

    def region(self, padded: bool) -> Path:
        raw = bytearray(2 * MODULE.SECTOR)
        struct.pack_into(">I", raw, 0, (2 << 8) | 1)
        raw.extend(struct.pack(">IB4s", 5, 3, b"data"))
        if padded:
            raw.extend(b"\0" * ((-len(raw)) % MODULE.SECTOR))
        return self.write_region("padded" if padded else "short", raw)

    def test_complete_record_with_missing_sector_tail_is_classified(self):
        result = MODULE.audit(self.region(False))
        kinds = [item["kind"] for item in result["anomalies"]]
        self.assertIn("file_not_sector_aligned", kinds)
        self.assertIn("location_out_of_range", kinds)
        self.assertIn("record_complete_but_unpadded", kinds)
        self.assertNotIn("payload_out_of_file", kinds)

    def test_sector_padded_record_is_clean(self):
        result = MODULE.audit(self.region(True))
        self.assertEqual(result["anomalies"], [])

    def test_location_in_header_and_zero_sector_count(self):
        raw = bytearray(2 * MODULE.SECTOR)
        struct.pack_into(">I", raw, 0, 1)
        struct.pack_into(">I", raw, 4, 2 << 8)
        result = MODULE.audit(self.write_region("bad-location", raw))
        kinds = [item["kind"] for item in result["anomalies"]]
        self.assertIn("location_in_header", kinds)
        self.assertIn("zero_sector_count", kinds)

    def test_declared_length_exceeds_sector_allocation(self):
        raw = bytearray(3 * MODULE.SECTOR)
        struct.pack_into(">I", raw, 0, (2 << 8) | 1)
        struct.pack_into(">IB", raw, 2 * MODULE.SECTOR, 4093, 3)
        result = MODULE.audit(self.write_region("length-allocation", raw))
        self.assertIn("payload_length_exceeds_allocation",
                      [item["kind"] for item in result["anomalies"]])

    def test_payload_truncated_before_declared_end(self):
        raw = bytearray(2 * MODULE.SECTOR + 100)
        struct.pack_into(">I", raw, 0, (2 << 8) | 1)
        struct.pack_into(">IB", raw, 2 * MODULE.SECTOR, 200, 3)
        result = MODULE.audit(self.write_region("payload-short", raw))
        self.assertIn("location_out_of_range", [item["kind"] for item in result["anomalies"]])
        self.assertIn("payload_out_of_file", [item["kind"] for item in result["anomalies"]])
        self.assertNotIn("record_complete_but_unpadded", [item["kind"] for item in result["anomalies"]])

    def test_unknown_compression_and_overlap(self):
        raw = bytearray(3 * MODULE.SECTOR)
        struct.pack_into(">I", raw, 0, (2 << 8) | 1)
        struct.pack_into(">I", raw, 4, (2 << 8) | 1)
        struct.pack_into(">IBb", raw, 2 * MODULE.SECTOR, 1, 9, 7)
        result = MODULE.audit(self.write_region("compression-overlap", raw))
        kinds = [item["kind"] for item in result["anomalies"]]
        self.assertIn("unknown_compression", kinds)
        self.assertIn("overlapping_entries", kinds)


if __name__ == "__main__":
    unittest.main()
