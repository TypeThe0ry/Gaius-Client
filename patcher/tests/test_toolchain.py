"""Public CI checks for toolchain lock integrity and patcher self-tests.

Maintainer-only checks that require the excluded checkout shader loader live in
patcher/tests/maintainer_test_toolchain.py and can be run with:
  python3 -m unittest discover -s patcher/tests -p 'maintainer_test_*.py' -v
"""

import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

PATCHER_DIR = Path(__file__).resolve().parents[1]
ROOT = PATCHER_DIR.parent
LOCK = json.loads((PATCHER_DIR / "toolchain.lock").read_text(encoding="utf-8"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ToolchainLockTest(unittest.TestCase):
    def test_versions_match_build_configuration(self):
        config = json.loads((ROOT / "port/config.json").read_text(encoding="utf-8"))
        self.assertEqual(LOCK["maven"]["version"], config["mavenVersion"])
        self.assertEqual(LOCK["teavm"]["version"], config["teaVMVersion"])
        source_patches = json.loads((PATCHER_DIR / "source-patches.json").read_text(encoding="utf-8"))
        self.assertEqual(LOCK["vineflower"]["sha256"], source_patches["decompiler"]["sha256"])

    def test_wasm_source_and_shader_hashes_match(self):
        wasm = LOCK["wasm"]
        self.assertEqual(wasm["kernel"]["cargoLockSha256"], sha256(ROOT / "port/native/Cargo.lock"))
        self.assertEqual(wasm["kernel"]["kernelMirrorSha256"], sha256(ROOT / "port/native/kernel-mirror.toml"))
        self.assertEqual(wasm["shader"]["pinsSha256"], sha256(ROOT / "port/wasm/shader-toolchain/pins.env"))

    def test_sha256_fields_are_well_formed(self):
        values = [
            LOCK["jdk"]["distributions"]["linux-x64"]["sha256"],
            LOCK["jdk"]["distributions"]["windows-x64"]["sha256"],
            LOCK["vineflower"]["sha256"],
            LOCK["maven"]["sha256"],
            LOCK["wasm"]["shader"]["manifestSha256"],
        ]
        values.extend(item["sha256"] for item in LOCK["teavm"]["artifacts"])
        values.extend(LOCK["wasm"]["shader"]["files"].values())
        self.assertTrue(all(len(value) == 64 and all(c in "0123456789abcdef" for c in value)
                            for value in values))

    def test_maven_closure_profiles_paths_and_legacy_pins(self):
        repository = LOCK["mavenRepository"]
        self.assertCountEqual(repository["profiles"], [p.stem for p in (ROOT / "port/versions").glob("*.json")])
        artifacts = repository["artifacts"]
        entries = {item["path"]: item for item in artifacts}
        self.assertEqual(len(entries), len(artifacts))
        for path, item in entries.items():
            with self.subTest(path=path):
                self.assertRegex(path, r"^[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+)+\.(jar|pom)$")
                self.assertNotIn("..", path)
                self.assertEqual(item["url"], "https://repo.maven.apache.org/maven2/" + path)
                self.assertIs(type(item["bytes"]), int)
                self.assertGreater(item["bytes"], 0)
                self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
                if path.endswith(".jar"):
                    group_artifact, version, _ = path.rsplit("/", 2)
                    artifact = group_artifact.rsplit("/", 1)[-1]
                    self.assertIn(f"{group_artifact}/{version}/{artifact}-{version}.pom", entries)
        for direct in LOCK["teavm"]["artifacts"]:
            self.assertEqual(entries[direct["path"]]["sha256"], direct["sha256"])

    def test_shader_release_archives_pin_whole_bytes(self):
        shader = LOCK["wasm"]["shader"]
        for pin in shader["sdk"]["downloads"]:
            self.assertIs(type(pin["bytes"]), int)
            self.assertGreater(pin["bytes"], 0)
            self.assertRegex(pin["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn("storage.googleapis.com/webassembly/emscripten-releases-builds/", pin["url"])
        self.assertEqual({pin["platform"] for pin in shader["sdk"]["downloads"]},
                         {"linux-x64", "windows-x64"})
        archive = shader["prebuiltArchive"]
        self.assertTrue(archive["file"].endswith(".zip"))
        self.assertIs(type(archive["bytes"]), int)
        self.assertGreater(archive["bytes"], 0)
        self.assertRegex(archive["sha256"], r"^[0-9a-f]{64}$")

    def test_wasm_host_distributions_pin_layout_and_whole_archives(self):
        host = LOCK["wasm"]["hostTools"]
        self.assertEqual(host["binaryen"]["version"], "133")
        self.assertRegex(host["llvm"]["version"], r"^\d+\.\d+\.\d+$")
        for name, tools in (("binaryen", ("wasm-opt",)), ("llvm", ("clang", "wasm-ld"))):
            pins = host[name]["distributions"]
            self.assertEqual(set(pins), {"linux-x64", "windows-x64"})
            for platform, pin in pins.items():
                suffix = ".exe" if platform == "windows-x64" else ""
                self.assertTrue(all(f"bin/{tool}{suffix}" in pin["members"] for tool in tools))
        pins = [pin for name in ("binaryen", "llvm") for pin in host[name]["distributions"].values()]
        for pin in pins + host["linuxRuntime"]:
            self.assertIs(type(pin["bytes"]), int)
            self.assertGreater(pin["bytes"], 0)
            self.assertRegex(pin["sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(pin["url"].startswith("https://"))

    def test_shader_distribution_is_metadata_only(self):
        shader = LOCK["wasm"]["shader"]
        self.assertEqual(shader["distribution"], {
            "status": "planned", "manifestUrl": None, "manifestSha256": None,
        })
        self.assertNotIn("url", shader["prebuiltArchive"])

    def test_synthetic_hash_tamper_hint_and_staging_checks(self):
        temp_root = Path(tempfile.gettempdir()).resolve()
        if temp_root.is_relative_to(ROOT):
            temp_root = ROOT.parent
        with tempfile.TemporaryDirectory(dir=temp_root, prefix="gaius-toolchain-test-") as tempdir:
            result = subprocess.run(
                ["java", f"-Djava.io.tmpdir={tempdir}", str(PATCHER_DIR / "GaiusPatcher.java"), "selftest"],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("selftest: all checks passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
