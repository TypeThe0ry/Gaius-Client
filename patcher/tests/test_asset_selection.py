import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class AssetSelectionTest(unittest.TestCase):
    def test_offline_fetch_includes_the_release_language_objects(self):
        fetch_script = (ROOT / "port/scripts/fetch-version.sh").read_text(encoding="utf-8")
        loop = re.search(
            r'^for locale in ([a-z_ ]+); do\n'
            r'  browser_font_assets\+=\("minecraft/lang/\$locale\.json"\)$',
            fetch_script,
            re.MULTILINE,
        )
        self.assertIsNotNone(loop, "update the test when the release locale list moves")
        expected = {f"minecraft/lang/{locale}.json" for locale in loop[1].split()}
        selection = json.loads((ROOT / "patcher/asset-selection.json").read_text(encoding="utf-8"))
        selected = {path for path in selection["exact"] if path.startswith("minecraft/lang/")}
        self.assertEqual(expected, selected)
