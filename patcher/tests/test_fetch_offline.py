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
        (self.game / "versions" / "9.9" / "9.9.json").write_text(json.dumps(metadata), encoding="utf-8")

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
        (self.bundle / "profiles" / "9.9.json").write_text(json.dumps(profile), encoding="utf-8")
        (self.bundle / "TERMS.md").write_bytes(TERMS.read_bytes())
        (self.bundle / "asset-selection.json").write_text(json.dumps({
            "exact": ["minecraft/sounds.json"],
            "prefixes": [{"prefix": "minecraft/sounds/block/", "suffix": ".ogg"}],
        }), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def patcher(self, *args, accept=True):
        return subprocess.run(
            ["java", f"-Djava.io.tmpdir={os.environ.get('TMPDIR', str(self.root))}", str(PATCHER),
             *args, *(["--accept-terms"] if accept else []),
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
        record = json.loads((self.root / "work" / "terms-accepted.json").read_text(encoding="utf-8"))
        self.assertEqual(record["termsSha256"], hashlib.sha256(TERMS.read_bytes().replace(b"\r\n", b"\n")).hexdigest())
        self.assertIn("You accepted", self.patcher("terms", accept=False).stdout)

        again = self.patcher("fetch", "9.9", "--offline", accept=False)
        self.assertEqual(again.returncode, 0, again.stdout + again.stderr)

        (self.bundle / "TERMS.md").write_text(TERMS.read_text(encoding="utf-8") + "\n6. A new term.\n", encoding="utf-8")
        changed = self.patcher("fetch", "9.9", "--offline", accept=False)
        self.assertNotEqual(changed.returncode, 0)
        self.assertIn("not accepted", changed.stderr)

    def test_offline_fetch_uses_only_the_local_installation(self):
        result = self.patcher("fetch", "9.9", "--offline")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        work = self.root / "work" / "9.9"
        self.assertEqual(sha1((work / "client-original.jar").read_bytes()), self.jar_sha1)
        self.assertTrue((work / "libraries/org/example/lib/1.0/lib-1.0.jar").is_file())
        self.assertIn("lib-1.0.jar", (work / "classpath.txt").read_text(encoding="utf-8"))
        self.assertEqual(json.loads((work / "client-version.json").read_text(encoding="utf-8"))["id"], "9.9")
        objects = list((work / "assets" / "objects").rglob("*"))
        names = {path.name for path in objects if path.is_file()}
        self.assertEqual(len(names), 2)
        self.assertNotIn(self.unselected, names)
        manifest = json.loads((work / "fetch-manifest.json").read_text(encoding="utf-8"))
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
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        metadata["libraries"].append({"name": "org.example:natives:1.0", "rules": [
            {"action": "allow", "os": {"name": "plan9"}}],
            "downloads": {"artifact": {"path": "org/example/natives/1.0/natives-1.0.jar",
                                       "sha1": "0" * 40, "url": "https://invalid.example/n.jar"}}})
        metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
        result = self.patcher("fetch", "9.9", "--offline")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("natives-1.0.jar", (self.root / "work" / "9.9" / "classpath.txt").read_text(encoding="utf-8"))

    def test_mappings_can_come_from_a_local_file(self):
        mappings = b"# mappings\n"
        mapping_file = self.root / "client.txt"
        mapping_file.write_bytes(mappings)
        metadata_path = self.game / "versions" / "9.9" / "9.9.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        metadata["downloads"]["client_mappings"] = {"sha1": sha1(mappings), "url": "https://invalid.example/m.txt"}
        metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
        profile_path = self.bundle / "profiles" / "9.9.json"
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["clientDistribution"] = "obfuscated-with-mappings"
        profile["official"]["clientMappingsSha1"] = sha1(mappings)
        profile_path.write_text(json.dumps(profile), encoding="utf-8")
        offline = self.patcher("fetch", "9.9", "--offline")
        self.assertNotEqual(offline.returncode, 0)
        self.assertIn("--mappings", offline.stderr)
        result = self.patcher("fetch", "9.9", "--offline", "--mappings", str(mapping_file))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / "work" / "9.9" / "client-mappings.txt").read_bytes(), mappings)

    def test_build_json_progress_is_one_object_per_line_and_errors(self):
        result = subprocess.run(
            ["java", f"-Djava.io.tmpdir={os.environ.get('TMPDIR', str(self.root))}", str(PATCHER),
             "build", "missing", "--json-progress", "--accept-terms",
             "--profiles", str(self.bundle / "profiles"), "--minecraft-dir", str(self.game),
             "--work-root", str(self.root / "work"), "--out", str(self.root / "build")],
            capture_output=True, text=True, stdin=subprocess.DEVNULL)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stderr, "")
        lines = result.stdout.splitlines()
        self.assertGreaterEqual(len(lines), 1)
        records = [json.loads(line) for line in lines]
        self.assertEqual(records[-1]["phase"], "error")
        for record in records:
            self.assertEqual(set(record), {"schema", "phase", "step", "pct", "message", "error"})

        malformed = subprocess.run(
            ["java", f"-Djava.io.tmpdir={os.environ.get('TMPDIR', str(self.root))}", str(PATCHER),
             "build", "9.9", "--json-progress", "--profiles", str(self.bundle / "profiles"), "--out"],
            capture_output=True, text=True, stdin=subprocess.DEVNULL)
        self.assertNotEqual(malformed.returncode, 0)
        self.assertEqual(malformed.stderr, "")
        error_record = json.loads(malformed.stdout)
        self.assertEqual(error_record["schema"], "gaius-patcher-progress-v1")
        self.assertEqual(error_record["phase"], "error")
        self.assertIn("needs a value", error_record["error"])

    def test_invalid_build_settings_use_the_json_error_protocol(self):
        cases = [
            (("--quality", "balanced"), "invalid --quality"),
            (("--render-distance", "33"), "between 2 and 32"),
            (("--backend", "canvas"), "invalid --backend"),
            (("--resource-pack", str(self.root / "missing.zip")), "must name a local file"),
        ]
        for options, message in cases:
            with self.subTest(options=options):
                result = self.patcher("build", "9.9", "--json-progress", *options)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stderr, "")
                records = [json.loads(line) for line in result.stdout.splitlines()]
                self.assertEqual(len(records), 1)
                self.assertEqual(records[0]["schema"], "gaius-patcher-progress-v1")
                self.assertEqual(records[0]["phase"], "error")
                self.assertIn(message, records[0]["error"])
                self.assertFalse((self.root / "work" / "9.9").exists())

    def test_263_requires_shader_toolchain_before_reading_launcher_files(self):
        repository = PATCHER.parents[1]
        work_root = self.root / "shader-work"
        output = self.root / "shader-build"
        archive = json.loads((PATCHER.parent / "toolchain.lock").read_text())["wasm"]["shader"]["prebuiltArchive"]
        environment = os.environ.copy()
        environment.pop("GAIUS_SHADER_TOOLCHAIN_PREBUILT", None)
        for mode, reason in (([], "No download URL is configured."),
                             (["--offline"], "Offline mode cannot download it.")):
            with self.subTest(mode=mode):
                result = subprocess.run(
                    ["java", f"-Djava.io.tmpdir={os.environ.get('TMPDIR', str(self.root))}", str(PATCHER),
                     "build", "26.3", "--json-progress", "--accept-terms",
                     "--profiles", str(repository / "port" / "versions"),
                     "--minecraft-dir", str(self.root / "missing-launcher"),
                     "--work-root", str(work_root), "--out", str(output), *mode],
                    capture_output=True, text=True, stdin=subprocess.DEVNULL, env=environment)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stderr, "")
                records = [json.loads(line) for line in result.stdout.splitlines()]
                self.assertEqual(len(records), 1)
                self.assertEqual(records[0]["phase"], "error")
                for text in ("prebuilt shader toolchain", reason, archive["sha256"],
                             str(work_root / "tools" / archive["file"]), "--toolchain-dir <dir>",
                             "--shader-toolchain <dir>"):
                    self.assertIn(text, records[0]["error"])
        self.assertFalse((work_root / "terms-accepted.json").exists())
        self.assertFalse((work_root / "26.3").exists())
        self.assertFalse(output.exists())

    def test_build_output_cannot_be_inside_source_tree(self):
        output = self.bundle / "build-output"
        result = self.patcher("build", "9.9", "--out", str(output))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("outside the source tree", result.stderr)
        self.assertFalse(output.exists())

    def test_build_output_symlink_into_source_tree_is_rejected(self):
        output = self.root / "source-alias"
        output.symlink_to(self.bundle, target_is_directory=True)
        result = self.patcher("build", "9.9", "--out", str(output))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("outside the source tree", result.stderr)
        self.assertFalse((self.bundle / "tool").exists())


if __name__ == "__main__":
    sys.exit(unittest.main())
