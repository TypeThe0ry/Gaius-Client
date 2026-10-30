"""Checks the public root files: the export mapping and the issue guard."""

import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

PATCHER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PATCHER / "tools"))
import export_public_tree  # noqa: E402

# In the private repository these files live in patcher/public-root/; the
# export puts them at the root of the public repository.
PUBLIC_ROOT = PATCHER / "public-root" if (PATCHER / "public-root").is_dir() else PATCHER.parent
GUARD = PUBLIC_ROOT / ".github" / "scripts" / "issue-guard.js"


class ExportMappingTest(unittest.TestCase):
    def test_public_root_files_go_to_the_root(self):
        self.assertEqual(export_public_tree.export_path("patcher/public-root/README.md"), "README.md")
        self.assertEqual(export_public_tree.export_path("patcher/public-root/.github/ISSUE_TEMPLATE/config.yml"),
                         ".github/ISSUE_TEMPLATE/config.yml")
        self.assertEqual(export_public_tree.export_path("patcher/README.md"), "patcher/README.md")

    def test_public_root_has_the_legal_files(self):
        for name in ("README.md", "DISCLAIMER.md", "CONTRIBUTING.md"):
            text = (PUBLIC_ROOT / name).read_text(encoding="utf-8")
            self.assertIn("DISCLAIMER.md" if name != "DISCLAIMER.md" else "minecraft.net/eula", text)
        disclaimer = (PUBLIC_ROOT / "DISCLAIMER.md").read_text(encoding="utf-8")
        self.assertIn("Not an official Minecraft product".lower(), disclaimer.lower().replace("**", ""))


class VersionProfilesTest(unittest.TestCase):
    def test_every_profile_is_valid_json_with_official_hashes(self):
        profiles = sorted((PATCHER.parent / "port" / "versions").glob("*.json"))
        self.assertTrue(profiles)
        for path in profiles:
            with self.subTest(profile=path.name):
                official = json.loads(path.read_text(encoding="utf-8"))["official"]
                self.assertRegex(official["clientSha1"], "^[0-9a-f]{40}$")
                self.assertRegex(official["assetIndexSha1"], "^[0-9a-f]{40}$")


@unittest.skipUnless(shutil.which("node"), "node is not installed")
class IssueGuardTest(unittest.TestCase):
    CASES = {
        "Crash when opening a world, see https://github.com/TypeThe0ry/Gaius-Client/issues/1": False,
        "Screenshot https://github.com/user-attachments/assets/abc.png": False,
        "Console: TypeError at classes.js line 4": False,
        "here https://www.mediafire.com/file/abc/gaius.zip/file": True,
        "attached https://github.com/user-attachments/files/123/gaius-26.3.html.": True,
        "play at https://someone.github.io/gaius/": True,
        "where can i download it?": True,
        "求下载链接": True,
        "broken %E0 https://x.example/%E0%A4%A.jar": True,
    }

    def test_scan(self):
        script = "const {scan} = require(process.argv[1]); const cases = JSON.parse(process.argv[2]);" \
                 "console.log(JSON.stringify(Object.keys(cases).map((t) => scan(t).length > 0)));"
        result = subprocess.run(["node", "-e", script, str(GUARD), json.dumps(self.CASES)],
                                capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(result.stdout), list(self.CASES.values()))


if __name__ == "__main__":
    unittest.main()
