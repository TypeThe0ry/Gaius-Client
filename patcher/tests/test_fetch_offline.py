"""End-to-end check of `fetch --offline` against a synthetic launcher directory.

Builds a fake game directory (jar, libraries, asset index, objects) whose
files are random bytes, pins them in a throwaway profile, and checks that the
patcher copies and verifies everything into the build layout. No real game
file is involved and the network is never used.
"""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

PATCHER = Path(__file__).resolve().parents[1] / "GaiusPatcher.java"
TERMS = Path(__file__).resolve().parents[1] / "TERMS.md"


def sha1(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


class FetchOfflineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.game = self.root / "game"
        self.bundle = self.root / "bundle"
        (self.bundle / "profiles").mkdir(parents=True)

        jar = self.game / "versions" / "9.9" / "9.9.jar"
        jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("version.json", json.dumps({"id": "9.9"}))
            archive.writestr("a/B.class", os.urandom(64))
        self.jar_sha1 = sha1(jar.read_bytes())

        lib_data = os.urandom(128)
        lib_path = "org/example/lib/1.0/lib-1.0.jar"
        (self.game / "libraries" / lib_path).parent.mkdir(parents=True)
        (self.game / "libraries" / lib_path).write_bytes(lib_data)

        sound = os.urandom(32)
        other = os.urandom(32)
        objects = {
            "minecraft/sounds.json": {"hash": sha1(b"{}"), "size": 2},
            "minecraft/sounds/block/x.ogg": {"hash": sha1(sound), "size": 32},
            "minecraft/sounds/music/y.ogg": {"hash": sha1(other), "size": 32},
        }
        for data in (b"{}", sound):
            digest = sha1(data)
            target = self.game / "assets" / "objects" / digest[:2] / digest
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        index = json.dumps({"objects": objects}).encode()
        (self.game / "assets" / "indexes").mkdir(parents=True)
        (self.game / "assets" / "indexes" / "7.json").write_bytes(index)
        self.unselected = sha1(other)

        metadata = {
            "id": "9.9",
            "downloads": {"client": {"sha1": self.jar_sha1, "url": "https://invalid.example/c.jar"}},
            "libraries": [
                {"name": "org.example:lib:1.0", "downloads": {"artifact": {
                    "path": lib_path, "sha1": sha1(lib_data), "url": "https://invalid.example/l.jar"}}},
                {"name": "org.example:natives-only:1.0"},
            ],
            "assetIndex": {"id": "7", "sha1": sha1(index), "url": "https://invalid.example/7.json"},
        }
        (self.game / "versions" / "9.9" / "9.9.json").write_text(json.dumps(metadata))

        profile = {
            "id": "9.9",
            "javaVersion": 21,
            "clientDistribution": "named",
            "official": {
                "clientSha1": self.jar_sha1,
                "clientMappingsSha1": None,
                "assetIndexId": "7",
                "assetIndexSha1": sha1(index),
            },
        }
        (self.bundle / "profiles" / "9.9.json").write_text(json.dumps(profile))
        (self.bundle / "TERMS.md").write_bytes(TERMS.read_bytes())
        (self.bundle / "asset-selection.json").write_text(json.dumps({
            "exact": ["minecraft/sounds.json"],
            "prefixes": [{"prefix": "minecraft/sounds/block/", "suffix": ".ogg"}],
        }))

    def tearDown(self):
        self.tmp.cleanup()

    def patcher(self, *args, accept=True):
        return subprocess.run(
            ["java", str(PATCHER), *args, *(["--accept-terms"] if accept else []),
             "--profiles", str(self.bundle / "profiles"),
             "--minecraft-dir", str(self.game),
             "--work-root", str(self.root / "work")],
            capture_output=True, text=True, stdin=subprocess.DEVNULL)

    def test_fetch_needs_the_terms_accepted_first(self):
        refused = self.patcher("fetch", "9.9", "--offline", accept=False)
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("--accept-terms", refused.stderr)
        self.assertFalse((self.root / "work").exists())

        accepted = self.patcher("fetch", "9.9", "--offline")
        self.assertEqual(accepted.returncode, 0, accepted.stdout + accepted.stderr)
        record = json.loads((self.root / "work" / "terms-accepted.json").read_text())
        self.assertEqual(record["termsSha256"], hashlib.sha256(TERMS.read_bytes().replace(b"\r\n", b"\n")).hexdigest())
        self.assertIn("You accepted", self.patcher("terms", accept=False).stdout)

        again = self.patcher("fetch", "9.9", "--offline", accept=False)
        self.assertEqual(again.returncode, 0, again.stdout + again.stderr)

        (self.bundle / "TERMS.md").write_text(TERMS.read_text() + "\n6. A new term.\n")
        changed = self.patcher("fetch", "9.9", "--offline", accept=False)
        self.assertNotEqual(changed.returncode, 0)
        self.assertIn("not accepted", changed.stderr)

    def test_offline_fetch_uses_only_the_local_installation(self):
        result = self.patcher("fetch", "9.9", "--offline")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        work = self.root / "work" / "9.9"
        self.assertEqual(sha1((work / "client-original.jar").read_bytes()), self.jar_sha1)
        self.assertTrue((work / "libraries/org/example/lib/1.0/lib-1.0.jar").is_file())
        self.assertIn("lib-1.0.jar", (work / "classpath.txt").read_text())
        self.assertEqual(json.loads((work / "client-version.json").read_text())["id"], "9.9")
        objects = list((work / "assets" / "objects").rglob("*"))
        names = {path.name for path in objects if path.is_file()}
        self.assertEqual(len(names), 2)
        self.assertNotIn(self.unselected, names)
        manifest = json.loads((work / "fetch-manifest.json").read_text())
        self.assertEqual(manifest["downloaded"], 0)

    def test_tampered_jar_is_rejected_offline(self):
        jar = self.game / "versions" / "9.9" / "9.9.jar"
        jar.write_bytes(jar.read_bytes() + b"x")
        result = self.patcher("fetch", "9.9", "--offline")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing or modified", result.stderr)

    def test_missing_game_file_is_never_downloaded(self):
        (self.game / "assets" / "indexes" / "7.json").unlink()
        result = self.patcher("fetch", "9.9")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("official launcher", result.stderr)
        self.assertNotIn("downloading", result.stdout)

    def test_library_for_another_platform_is_optional(self):
        metadata_path = self.game / "versions" / "9.9" / "9.9.json"
        metadata = json.loads(metadata_path.read_text())
        metadata["libraries"].append({"name": "org.example:natives:1.0", "rules": [
            {"action": "allow", "os": {"name": "plan9"}}],
            "downloads": {"artifact": {"path": "org/example/natives/1.0/natives-1.0.jar",
                                       "sha1": "0" * 40, "url": "https://invalid.example/n.jar"}}})
        metadata_path.write_text(json.dumps(metadata))
        result = self.patcher("fetch", "9.9", "--offline")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("natives-1.0.jar", (self.root / "work" / "9.9" / "classpath.txt").read_text())

    def test_mappings_can_come_from_a_local_file(self):
        mappings = b"# mappings\n"
        mapping_file = self.root / "client.txt"
        mapping_file.write_bytes(mappings)
        metadata_path = self.game / "versions" / "9.9" / "9.9.json"
        metadata = json.loads(metadata_path.read_text())
        metadata["downloads"]["client_mappings"] = {"sha1": sha1(mappings), "url": "https://invalid.example/m.txt"}
        metadata_path.write_text(json.dumps(metadata))
        profile_path = self.bundle / "profiles" / "9.9.json"
        profile = json.loads(profile_path.read_text())
        profile["clientDistribution"] = "obfuscated-with-mappings"
        profile["official"]["clientMappingsSha1"] = sha1(mappings)
        profile_path.write_text(json.dumps(profile))
        offline = self.patcher("fetch", "9.9", "--offline")
        self.assertNotEqual(offline.returncode, 0)
        self.assertIn("--mappings", offline.stderr)
        result = self.patcher("fetch", "9.9", "--offline", "--mappings", str(mapping_file))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / "work" / "9.9" / "client-mappings.txt").read_bytes(), mappings)


if __name__ == "__main__":
    sys.exit(unittest.main())
