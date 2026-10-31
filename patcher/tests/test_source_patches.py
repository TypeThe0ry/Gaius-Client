"""End-to-end check of `decompile` and `prepare` with a stub decompiler.

The stub is a tiny Java program compiled here. It writes, for each class it
is given, a fake "decompiled" source that depends on the class bytes, the
options and the number of library jars, so the check sees whether the
patcher feeds the decompiler the same inputs every time. The game jar holds
random bytes; no real game file or decompiler is involved and the network is
never used.
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

PATCHER_DIR = Path(__file__).resolve().parents[1]
PATCHER = PATCHER_DIR / "GaiusPatcher.java"
sys.path.insert(0, str(PATCHER_DIR / "tools"))
import make_source_patches  # noqa: E402

STUB = r"""
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class StubDecompiler {
    public static void main(String[] args) throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> options = new ArrayList<>();
        int libraries = 0;
        for (String arg : args) {
            if (arg.startsWith("-e=")) libraries++;
            else if (arg.startsWith("-")) options.add(arg);
            else paths.add(arg);
        }
        Path in = Paths.get(paths.get(0));
        Path out = Paths.get(paths.get(1));
        List<Path> files;
        try (var stream = Files.walk(in)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            String rel = in.relativize(file).toString().replace('\\', '/');
            if (rel.contains("$")) continue;
            String name = rel.substring(0, rel.length() - ".class".length());
            StringBuilder text = new StringBuilder("// " + name + " " + options + " libraries " + libraries + "\n");
            for (Path part : files) {
                String partRel = in.relativize(part).toString().replace('\\', '/');
                if (partRel.equals(rel) || partRel.startsWith(name + "$")) {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(part));
                    text.append("// ").append(HexFormat.of().formatHex(digest)).append("\n");
                }
            }
            text.append("class Official {\n    private final String officialField = \"official value\";\n}\n");
            Path target = out.resolve(name + ".java");
            Files.createDirectories(target.getParent());
            Files.writeString(target, text.toString());
        }
    }
}
"""


def sha1(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_jar(path: Path, entries: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)


class SourcePatchesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tools = tempfile.TemporaryDirectory()
        tools = Path(cls.tools.name)
        (tools / "StubDecompiler.java").write_text(STUB, encoding="utf-8", newline="\n")
        subprocess.run(["javac", "-d", str(tools / "classes"), str(tools / "StubDecompiler.java")], check=True)
        cls.stub = tools / "stub.jar"
        subprocess.run(["jar", "--create", "--file", str(cls.stub), "--main-class", "StubDecompiler",
                        "-C", str(tools / "classes"), "."], check=True)
        cls.other = tools / "other.jar"
        write_jar(cls.other, {"x": b"not the pinned decompiler"})

    @classmethod
    def tearDownClass(cls):
        cls.tools.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        work = self.root / "port" / "work" / "9.9"
        client = work / "client-original.jar"
        write_jar(client, {
            "version.json": b"{}",
            "net/minecraft/Foo.class": os.urandom(64),
            "net/minecraft/Foo$Inner.class": os.urandom(64),
            "net/minecraft/FooBar.class": os.urandom(64),
        })
        write_jar(work / "libraries" / "com" / "mojang" / "logging" / "0.1" / "logging-0.1.jar",
                  {"com/mojang/logging/LogUtils.class": os.urandom(64)})
        write_jar(work / "libraries" / "org" / "example" / "1.0" / "example-1.0.jar", {"a": b"b"})

        versions = self.root / "port" / "versions"
        versions.mkdir(parents=True)
        (versions / "9.9.json").write_text(json.dumps({
            "id": "9.9", "javaVersion": 21, "clientDistribution": "named",
            "official": {"clientSha1": sha1(client.read_bytes()), "assetIndexId": "7",
                         "assetIndexSha1": "0" * 40},
        }), encoding="utf-8", newline="\n")
        patcher = self.root / "patcher"
        patcher.mkdir()
        shutil.copy(PATCHER_DIR / "TERMS.md", patcher / "TERMS.md")
        self.config_path = patcher / "source-patches.json"
        self.config = {
            "decompiler": {"url": "https://invalid.example/stub.jar", "sha256": sha256_file(self.stub),
                           "options": ["-nls=1", "-ind=    "]},
            "patches": [
                {"id": "t-Foo", "target": "port/mc/x/net/minecraft/Foo.java", "class": "net/minecraft/Foo",
                 "jar": "client", "versions": ["9.9"]},
                {"id": "t-LogUtils", "target": "port/overrides/com/mojang/logging/LogUtils.java",
                 "class": "com/mojang/logging/LogUtils", "jar": "library:com/mojang/logging", "versions": ["9.9"]},
            ],
        }
        self.write_config()

    def tearDown(self):
        self.tmp.cleanup()

    def write_config(self):
        self.config_path.write_text(json.dumps(self.config, indent=2), encoding="utf-8", newline="\n")

    def patcher(self, *args, ok=True):
        result = subprocess.run(
            ["java", str(PATCHER), *args, "--profiles", str(self.root / "port" / "versions"), "--accept-terms"],
            cwd=self.root, capture_output=True, text=True, stdin=subprocess.DEVNULL)
        output = result.stdout + result.stderr
        if ok:
            self.assertEqual(result.returncode, 0, output)
        else:
            self.assertNotEqual(result.returncode, 0, output)
        return output

    def decompile(self):
        self.patcher("decompile", "9.9", "--out", str(self.root / "bases" / "9.9"), "--decompiler", str(self.stub))
        return {path.stem: path.read_text(encoding="utf-8") for path in (self.root / "bases" / "9.9").glob("*.java")}

    def write_gaius_sources(self, bases, extra_foo_line=None):
        foo = bases["t-Foo"].replace(
            '    private final String officialField = "official value";\n',
            '    // Gaius: read the value from the browser archive instead.\n'
            '    private final String officialField = BrowserArchive.value();\n')
        foo += "// added by Gaius\n"
        if extra_foo_line:
            foo += extra_foo_line + "\n"
        targets = {"t-Foo": foo, "t-LogUtils": bases["t-LogUtils"] + "// Gaius logging\n"}
        for entry in self.config["patches"]:
            path = self.root / entry["target"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(targets[entry["id"]].encode())
        return targets

    def make_patches(self, *extra):
        return make_source_patches.main(["9.9", "--config", str(self.config_path), "--root", str(self.root),
                                         "--bases", str(self.root / "bases"), *extra])

    def test_decompile_sees_the_class_its_nested_classes_and_the_libraries(self):
        bases = self.decompile()
        self.assertEqual(set(bases), {"t-Foo", "t-LogUtils"})
        foo = bases["t-Foo"]
        self.assertIn("// net/minecraft/Foo [-nls=1, -ind=    ] libraries 2\n", foo)
        self.assertEqual(foo.count("\n// "), 2, "Foo and Foo$Inner, not FooBar")
        self.assertIn("libraries 2", bases["t-LogUtils"], "client jar and the other library")
        self.assertEqual(bases, self.decompile(), "decompiling is repeatable")

    def test_prepare_rebuilds_the_gaius_sources_exactly(self):
        targets = self.write_gaius_sources(self.decompile())
        self.assertEqual(self.make_patches(), 0)
        script = (self.root / "patcher" / "patches" / "9.9" / "t-Foo.gpatch").read_text(encoding="utf-8")
        self.assertNotIn("official value", script)
        self.assertIn("+// added by Gaius", script)
        self.assertEqual(self.make_patches("--check"), 0)

        for entry in self.config["patches"]:
            (self.root / entry["target"]).unlink()
        output = self.patcher("prepare", "9.9", "--decompiler", str(self.stub))
        self.assertIn("2 written", output)
        for entry in self.config["patches"]:
            self.assertEqual((self.root / entry["target"]).read_bytes(), targets[entry["id"]].encode())
        self.assertIn("2 already up to date", self.patcher("prepare", "9.9", "--decompiler", str(self.stub)))

        (self.root / self.config["patches"][0]["target"]).write_text("edited\n", encoding="utf-8", newline="\n")
        self.assertIn("exists and differs", self.patcher("prepare", "9.9", "--decompiler", str(self.stub), ok=False))

    def test_a_crlf_checkout_makes_the_same_patches(self):
        targets = self.write_gaius_sources(self.decompile())
        self.assertEqual(self.make_patches(), 0)
        for entry in self.config["patches"]:
            (self.root / entry["target"]).write_bytes(targets[entry["id"]].replace("\n", "\r\n").encode())
        self.assertEqual(self.make_patches("--check"), 0, "CRLF in the checkout is not a change")

    def test_prepare_into_another_directory(self):
        targets = self.write_gaius_sources(self.decompile())
        self.assertEqual(self.make_patches(), 0)
        into = self.root / "elsewhere"
        self.patcher("prepare", "9.9", "--decompiler", str(self.stub), "--into", str(into))
        self.assertEqual((into / "port/mc/x/net/minecraft/Foo.java").read_text(encoding="utf-8"), targets["t-Foo"])

    def test_moved_official_lines_become_copies(self):
        bases = self.decompile()
        official = '    private final String officialField = "official value";'
        # Written again at the end and re-indented: both are copies of the user's own line, not text.
        targets = self.write_gaius_sources(bases, extra_foo_line=official + "\n  " + official.strip())
        self.assertEqual(self.make_patches(), 0)
        script = (self.root / "patcher" / "patches" / "9.9" / "t-Foo.gpatch").read_text()
        self.assertNotIn("official value", script)
        for entry in self.config["patches"]:
            (self.root / entry["target"]).unlink()
        self.patcher("prepare", "9.9", "--decompiler", str(self.stub))
        self.assertEqual((self.root / self.config["patches"][0]["target"]).read_text(), targets["t-Foo"])

    def test_official_text_that_cannot_be_copied_is_refused(self):
        bases = self.decompile()
        # A mixed space-and-tab indent cannot be expressed as a copy, so the line would travel as text.
        mixed = ' \tprivate final String officialField = "official value";'
        self.write_gaius_sources(bases, extra_foo_line=mixed)
        self.assertEqual(self.make_patches(), 1)
        self.assertFalse((self.root / "patcher" / "patches" / "9.9" / "t-Foo.gpatch").exists())
        stripped = 'private final String officialField = "official value";'
        foo = self.config["patches"][0]
        foo["reviewedLines"] = [hashlib.sha256(stripped.encode()).hexdigest()]
        self.write_config()
        self.assertEqual(self.make_patches(), 0)

    def test_the_decompiler_must_match_its_pin(self):
        self.assertIn("SHA-256 of", self.patcher("decompile", "9.9", "--out", str(self.root / "b"),
                                                  "--decompiler", str(self.other), ok=False))
        self.assertIn("--offline is set", self.patcher("decompile", "9.9", "--out", str(self.root / "b"),
                                                       "--offline", ok=False))
        self.config["decompiler"]["sha256"] = None
        self.write_config()
        self.assertIn("not pinned yet", self.patcher("prepare", "9.9", "--decompiler", str(self.stub), ok=False))

    def test_a_different_base_is_refused(self):
        self.write_gaius_sources(self.decompile())
        self.assertEqual(self.make_patches(), 0)
        client = self.root / "port" / "work" / "9.9" / "client-original.jar"
        with zipfile.ZipFile(client, "a") as archive:
            archive.writestr("net/minecraft/Foo$Extra.class", b"changed")
        profile_path = self.root / "port" / "versions" / "9.9.json"
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        profile["official"]["clientSha1"] = sha1(client.read_bytes())
        profile_path.write_text(json.dumps(profile), encoding="utf-8", newline="\n")
        output = self.patcher("prepare", "9.9", "--decompiler", str(self.stub), ok=False)
        self.assertIn("is not the text the patch was made for", output)

    def test_prepare_needs_fetched_files(self):
        shutil.rmtree(self.root / "port" / "work")
        self.assertIn("run `fetch 9.9` first", self.patcher("prepare", "9.9", ok=False))


class MakeScriptTest(unittest.TestCase):
    def test_script_round_trips_and_keeps_only_new_lines(self):
        base = "a line from the class\nsecond official line\nthird\n"
        result = "a line from the class\nour replacement\nthird\nour tail\n"
        script, inserted = make_source_patches.make_script(base, result)
        self.assertEqual(make_source_patches.apply_script(script, base), result)
        self.assertNotIn("second official line", script)
        self.assertEqual([line for _, line in inserted], ["our replacement", "our tail"])

    def test_moved_and_reindented_lines_are_copied_by_number(self):
        base = "class A {\n   int first = officialCall();\n   int second = otherCall();\n}\n"
        result = "class A {\n    int second = otherCall();\n    int first = officialCall();\n    int ours = 1;\n}\n"
        script, inserted = make_source_patches.make_script(base, result)
        self.assertEqual(make_source_patches.apply_script(script, base), result)
        self.assertNotIn("officialCall", script)
        self.assertNotIn("otherCall", script)
        self.assertEqual([line for _, line in inserted], ["    int ours = 1;"])

    def test_a_line_only_another_version_has_is_a_leak(self):
        older = "class A {\n    int removedInNewer = legacyOfficialCall();\n}\n"
        newer = "class A {\n}\n"
        result = "class A {\n    int removedInNewer = legacyOfficialCall();\n}\n"
        _, inserted = make_source_patches.make_script(newer, result)
        official = make_source_patches.official_lines([older, newer])
        self.assertEqual(len(make_source_patches.leaked_lines(inserted, official, set())), 1)
        _, inserted = make_source_patches.make_script(older, result)
        self.assertEqual(inserted, [])

    def test_consecutive_copies_are_merged(self):
        self.assertEqual(make_source_patches.merge_copies(["=4 1", "=5 1", "=6 2", "+x", "=9 1"]),
                         ["=4 4", "+x", "=9 1"])

    def test_identical_text_needs_no_hunks(self):
        script, inserted = make_source_patches.make_script("x\n", "x\n")
        self.assertEqual(inserted, [])
        self.assertEqual(script.count("\n"), 3)


if __name__ == "__main__":
    unittest.main()
