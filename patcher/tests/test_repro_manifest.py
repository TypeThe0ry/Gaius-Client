"""Exercise the public CLI with small synthetic release artifacts."""

import hashlib
import json
import platform
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "patcher" / "GaiusPatcher.java"
PROFILES = sorted(path.stem for path in (ROOT / "port" / "versions").glob("*.json"))
GAME, OTHER_GAME = PROFILES[-1], PROFILES[0]
PORTABLE = f"Gaius-{GAME}.html"
OS = {"Darwin": "macos", "Linux": "linux", "Windows": "windows"}.get(platform.system())


def sha256(data):
    return hashlib.sha256(data).hexdigest()


@unittest.skipUnless(OS, "reproduction manifests require a supported OS")
class ReproManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        classes = cls.enterClassContext(tempfile.TemporaryDirectory(prefix="repro-javac-"))
        subprocess.run(["javac", f"-J-Djava.io.tmpdir={classes}", "-d", classes, str(SOURCE)],
                       cwd=ROOT, check=True)
        cls.cli = ["java", f"-Djava.io.tmpdir={classes}", "-cp", classes, "GaiusPatcher"]
        version = subprocess.check_output(cls.cli + ["version"], cwd=ROOT, text=True).splitlines()[0].split()[1]
        cls.filename = f"expected-{version}-{OS}.json"
        cls.patcher_version = version

    def setUp(self):
        self.base = Path(self.enterContext(tempfile.TemporaryDirectory(prefix="repro-")))
        self.build = self.base / "first build"
        self.site = self.build / "site"
        self.site.mkdir(parents=True)
        (self.site / "runtime").mkdir()
        self.artifact = "site/runtime/sample.bin"
        self.files = {
            PORTABLE: b"<!DOCTYPE html>\n<main>Portable</main>\n",
            "site/index.html": b"<!DOCTYPE html>\n<main>Site</main>\n",
            "site/runtime/script.js": b"x" * 65535 + b"\r\ny\r\nz\r",
            self.artifact: b"\x00\x01\r\n\x02",
        }
        for path, content in self.files.items():
            (self.build / path).write_bytes(content)
        self.sidecar = self.build / "expected-hashes.json"
        self.sidecar.write_text(json.dumps({
            "schema": "gaius-patcher-hashes-v1",
            "version": GAME,
            "html": {"path": PORTABLE, "sha256": sha256(self.files[PORTABLE])},
            "site": [{"path": path.removeprefix("site/"), "sha256": sha256(content)}
                     for path, content in self.files.items() if path.startswith("site/")],
        }), encoding="utf-8")
        self.reference = self.base / self.filename

    def run_cli(self, *args, code, phase):
        result = subprocess.run(self.cli + [str(arg) for arg in args] + ["--json-progress"], cwd=ROOT,
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, code, result.stdout + result.stderr)
        self.assertEqual(len(result.stdout.splitlines()), 1, result.stdout)
        event = json.loads(result.stdout)
        self.assertEqual(event["schema"], "gaius-patcher-progress-v1")
        self.assertEqual(event["phase"], phase)
        self.assertEqual(event["pct"], 100)
        return event

    def make_reference(self):
        event = self.run_cli("manifest", self.build, "--out", self.base, code=0, phase="manifest")
        self.assertIsNone(event["error"])
        return json.loads(self.reference.read_bytes())

    def verify(self, status, code, *, default=False):
        args = [] if default else ["--manifest", self.reference]
        event = self.run_cli("verify", self.build, *args, code=code, phase="verify")
        self.assertEqual(event["status"], status)
        self.assertIsNone(event["error"])
        return event["differences"]

    def test_manifest_and_match_after_relocation_and_line_ending_changes(self):
        manifest = self.make_reference()
        self.assertEqual(set(manifest), {"schema", "patcherVersion", "gameVersion", "os",
                                         "toolchainLockSha256", "files"})
        self.assertEqual(manifest["schema"], "gaius-patcher-repro-v1")
        self.assertEqual(manifest["patcherVersion"], self.patcher_version)
        self.assertEqual(manifest["gameVersion"], GAME)
        self.assertEqual(manifest["os"], OS)
        lock = (ROOT / "patcher" / "toolchain.lock").read_bytes()
        self.assertEqual(manifest["toolchainLockSha256"], sha256(lock.replace(b"\r\n", b"\n")))
        self.assertEqual(manifest["files"], {
            path: sha256(content if path == self.artifact else content.replace(b"\r\n", b"\n"))
            for path, content in self.files.items()
        })
        for internal in ("build", "dist", "tool", "work", "work-root"):
            (self.build / internal).mkdir()
            (self.build / internal / "internal.bin").write_bytes(b"private fixture")
        (self.build / "build.log").write_bytes(b"fixture log")
        self.assertEqual(self.verify("MATCH", 0), [])
        self.sidecar.unlink()
        self.build = self.build.rename(self.base / "second build")
        self.reference = self.reference.rename(self.build / self.filename)
        self.reference.write_bytes(self.reference.read_bytes().replace(b"\n", b"\r\n"))
        for path in [PORTABLE, "site/index.html"]:
            (self.build / path).write_bytes(self.files[path].replace(b"\n", b"\r\n"))
        (self.build / "site/runtime/script.js").write_bytes(self.files["site/runtime/script.js"].replace(b"\r\n", b"\n"))
        self.assertEqual(self.verify("MATCH", 0, default=True), [])

    def test_changed_missing_and_unrecognized_extra_list_all_hashes(self):
        self.make_reference()
        changed = b"\x00\x00\r\n\x02"
        (self.build / self.artifact).write_bytes(changed)
        (self.build / PORTABLE).unlink()
        (self.build / "unexpected.txt").write_bytes(b"extra\n")
        self.assertEqual(self.verify("MISMATCH", 2), [
            {"path": PORTABLE, "kind": "missing", "expectedSha256": sha256(self.files[PORTABLE]),
             "actualSha256": None, "modded": False},
            {"path": self.artifact, "kind": "changed", "expectedSha256": sha256(self.files[self.artifact]),
             "actualSha256": sha256(changed), "modded": False},
            {"path": "unexpected.txt", "kind": "extra", "expectedSha256": None,
             "actualSha256": sha256(b"extra\n"), "modded": False},
        ])

    def test_mod_installation_and_hook_edits_with_mismatch_precedence(self):
        self.make_reference()
        (self.site / "index.html").write_bytes(b"local hook\n")
        self.assertFalse(self.verify("MISMATCH", 2)[0]["modded"])
        (self.build / "mods").mkdir()
        (self.build / "mods" / "local.bin").write_bytes(b"local extension fixture")
        (self.site / "loader.config.json").write_bytes(b"{}\n")
        changes = self.verify("MODDED", 3)
        self.assertEqual([(item["path"], item["kind"], item["modded"]) for item in changes], [
            ("mods/local.bin", "extra", True),
            ("site/index.html", "changed", True),
            ("site/loader.config.json", "extra", True),
        ])
        (self.build / self.artifact).unlink()
        self.assertIn((self.artifact, False), [(x["path"], x["modded"]) for x in self.verify("MISMATCH", 2)])

    def test_wrong_game_and_invalid_manifest_exit_as_input_errors(self):
        manifest = self.make_reference()
        cases = [
            ("not JSON", "JSON"),
            (json.dumps({**manifest, "schema": "unsupported"}), "schema"),
            (json.dumps({**manifest, "gameVersion": OTHER_GAME}), "game version"),
            (json.dumps({**manifest, "toolchainLockSha256": "0" * 64}), "toolchain.lock"),
            (json.dumps({**manifest, "files": {**manifest["files"], "site/../outside.bin": "0" * 64}}),
             "artifact path"),
            (json.dumps({**manifest, "files": {**manifest["files"], "site/invalid.bin": "bad"}}), "SHA-256"),
            (json.dumps(manifest).replace('"files": {', '"files": {"site/index.html": "duplicate",', 1),
             "duplicate key"),
        ]
        for contents, message in cases:
            with self.subTest(message=message):
                self.reference.write_text(contents, encoding="utf-8")
                event = self.run_cli("verify", self.build, "--manifest", self.reference, code=1, phase="error")
                self.assertIn(message, event["error"])
                self.assertNotIn("status", event)

    def test_generation_rejects_unrecorded_and_modified_files(self):
        extra = self.site / "unknown.bin"
        extra.write_bytes(b"extra")
        event = self.run_cli("manifest", self.build, "--out", self.base, code=1, phase="error")
        self.assertIn("inventory differs", event["error"])
        self.assertFalse(self.reference.exists())
        extra.unlink()
        (self.build / self.artifact).write_bytes(b"changed")
        event = self.run_cli("manifest", self.build, "--out", self.base, code=1, phase="error")
        self.assertIn(self.artifact, event["error"])
        self.assertFalse(self.reference.exists())

    def test_generation_preserves_an_existing_reference(self):
        self.make_reference()
        previous = self.reference.read_bytes()
        self.run_cli("manifest", self.build, "--out", self.base, code=1, phase="error")
        self.assertEqual(self.reference.read_bytes(), previous)

    def test_staged_toolchain_lock_must_match(self):
        self.make_reference()
        staged = self.build / "tool" / "patcher" / "toolchain.lock"
        staged.parent.mkdir(parents=True)
        staged.write_bytes(b"different toolchain fixture\n")
        self.reference.unlink()
        event = self.run_cli("manifest", self.build, "--out", self.base, code=1, phase="error")
        self.assertIn("build toolchain.lock", event["error"])
        staged.write_bytes((ROOT / "patcher" / "toolchain.lock").read_bytes())
        self.make_reference()
        staged.write_bytes(b"different toolchain fixture\n")
        event = self.run_cli("verify", self.build, "--manifest", self.reference, code=1, phase="error")
        self.assertIn("build toolchain.lock", event["error"])

    def test_symlink_is_rejected_without_reading_its_target(self):
        self.make_reference()
        outside = self.base / "outside.bin"
        outside.write_bytes(b"unrelated fixture")
        output_file = self.build / self.artifact
        output_file.unlink()
        try:
            output_file.symlink_to(outside)
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")
        event = self.run_cli("verify", self.build, "--manifest", self.reference, code=1, phase="error")
        self.assertIn(self.artifact, event["error"])
        self.assertNotIn(str(outside), event["error"])
