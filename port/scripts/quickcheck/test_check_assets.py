#!/usr/bin/env python3
"""Exercise icon integrity failures without network access or build outputs."""
from pathlib import Path
import shutil
import tempfile
import unittest

from check_assets import ROOT, check_assets


class AssetTests(unittest.TestCase):
    def test_checked_in_assets(self):
        self.assertLess(check_assets(), 1_000_000)

    def test_missing_or_changed_asset_is_rejected(self):
        for damage in ("missing", "changed"):
            with self.subTest(damage=damage), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = root / "port/web/launcher/icons"
                shutil.copytree(ROOT / "port/web/launcher/icons", directory)
                icon = directory / "favicon-32.png"
                if damage == "missing":
                    icon.unlink()
                else:
                    icon.write_bytes(icon.read_bytes() + b"changed")
                with self.assertRaises((OSError, ValueError)):
                    check_assets(root)


if __name__ == "__main__":
    unittest.main()
