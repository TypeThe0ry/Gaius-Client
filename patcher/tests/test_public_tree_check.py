"""Unit checks for public_tree_check.py on small synthetic trees."""

import argparse
import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import public_tree_check  # noqa: E402

DIGESTS = Path(__file__).resolve().parents[1] / "blocked-term-hashes.txt"


def options(**overrides):
    values = dict(vanilla_jar=[], asset_index=[], vanilla_hashes=[], extra_terms_file=None,
                  require_hash_scan=False)
    values.update(overrides)
    return argparse.Namespace(**values)


class PublicTreeCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "tree"
        (self.root / "patcher").mkdir(parents=True)
        (self.root / "patcher" / "blocked-term-hashes.txt").write_text(DIGESTS.read_text())
        (self.root / "README.md").write_text("A clean tree.\n")

    def tearDown(self):
        self.tmp.cleanup()

    def findings(self, **overrides):
        return public_tree_check.check_tree(self.root, options(**overrides))

    def test_clean_tree_passes(self):
        self.assertEqual(self.findings(), [])

    def test_vanilla_package_is_refused_unless_allowlisted(self):
        path = self.root / "src" / "net" / "minecraft" / "Foo.java"
        path.parent.mkdir(parents=True)
        path.write_text("class Foo {}\n")
        self.assertTrue(any("vanilla package" in f for f in self.findings()))
        (self.root / "patcher" / "vanilla-package-allowlist.txt").write_text("src/net/minecraft/Foo.java\n")
        self.assertEqual(self.findings(), [])

    def test_media_and_large_files_are_refused(self):
        (self.root / "logo.png").write_bytes(b"\x89PNG....")
        (self.root / "big.txt").write_text("x" * (public_tree_check.MAX_BYTES + 1))
        found = self.findings()
        self.assertTrue(any("logo.png" in f for f in found))
        self.assertTrue(any("big.txt" in f and "1 MiB" in f for f in found))

    def test_binary_allowlist_requires_matching_digest(self):
        data = b"\x89PNG our own icon"
        (self.root / "icon.png").write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        (self.root / "patcher" / "binary-allowlist.txt").write_text(f"icon.png {'0' * 64}\n")
        self.assertTrue(self.findings())
        (self.root / "patcher" / "binary-allowlist.txt").write_text(f"icon.png {digest}\n")
        self.assertEqual(self.findings(), [])

    def test_copy_of_an_official_file_is_found_by_hash(self):
        content = b"pretend this is a game texture or class " * 4
        jar = Path(self.tmp.name) / "client.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("x/Y.txt", content)
        (self.root / "renamed.txt").write_bytes(content)
        found = self.findings(vanilla_jar=[jar])
        self.assertTrue(any("identical to an official game file" in f for f in found))

    def test_asset_index_hashes_are_used(self):
        content = b"pretend sound data that is long enough to count" * 2
        index = Path(self.tmp.name) / "index.json"
        index.write_text(json.dumps({"objects": {"a.ogg": {"hash": hashlib.sha1(content).hexdigest()}}}))
        (self.root / "notes.txt").write_bytes(content)
        self.assertTrue(self.findings(asset_index=[index]))

    def test_required_hash_scan_needs_inputs(self):
        self.assertTrue(self.findings(require_hash_scan=True))

    def test_blocked_words_are_found_from_digests(self):
        # One of the stored words, assembled at run time so this file itself stays clean.
        (self.root / "doc.md").write_text("Our " + "Bench" + "marks page.\n")
        self.assertTrue(any("blocked word" in f for f in self.findings()))

    def test_term_exception_allows_one_word_in_one_file(self):
        word = "Bench" + "marks"
        digest = hashlib.sha256(("bench" + "mark").encode()).hexdigest()
        (self.root / "gpu.js").write_text("// %s the GPU\n" % word)
        (self.root / "other.js").write_text("// %s the GPU\n" % word)
        (self.root / "patcher" / "term-exceptions.txt").write_text("gpu.js %s\n" % digest)
        found = self.findings()
        self.assertEqual(len(found), 1)
        self.assertIn("other.js", found[0])

    def test_cjk_blocked_word(self):
        (self.root / "doc.md").write_text("性能" + "对" + "标" + "结果\n")
        self.assertTrue(any("CJK" in f for f in self.findings()))

    def test_extra_plain_terms(self):
        extra = Path(self.tmp.name) / "private-terms.txt"
        extra.write_text("secret-host.example\n")
        (self.root / "cfg.json").write_text('{"host": "Secret-Host.example"}\n')
        self.assertTrue(self.findings(extra_terms_file=extra))

    def test_blocked_phrase_is_found_from_its_digest(self):
        phrase = "private-host." + "example"  # assembled so this file has no literal
        digests = self.root / "patcher" / "blocked-term-hashes.txt"
        digests.write_text(digests.read_text() + "%d %s phrase\n" % (len(phrase), hashlib.sha256(phrase.encode()).hexdigest()))
        (self.root / "ok.md").write_text("not-private-host.example is a different host\n")
        self.assertEqual(self.findings(), [])
        (self.root / "cfg.json").write_text('{"host": "Private-Host.Example:25565"}\n')
        self.assertTrue(any("blocked phrase" in f for f in self.findings()))

    def test_ipv4_allowlist_is_per_file(self):
        public = ".".join(["8", "8", "4", "4"])
        (self.root / "a.js").write_text("const net = '%s';\n" % public)
        (self.root / "b.js").write_text("const net = '%s';\n" % public)
        (self.root / "patcher" / "ipv4-allowlist.txt").write_text("a.js %s\n" % public)
        found = self.findings()
        self.assertEqual(len(found), 1)
        self.assertIn("b.js", found[0])

    def test_version_numbers_and_reserved_ranges_are_not_addresses(self):
        version = ".".join(["141", "0", "0", "0"])
        jdk = ".".join(["25", "0", "4", "101"])
        reserved = ".".join(["198", "18", "0", "0"])
        (self.root / "ua.js").write_text("ua = 'Chrome/%s'; jdk = 'jdk-%s'; net = '%s';\n" % (version, jdk, reserved))
        self.assertEqual(self.findings(), [])
        (self.root / "bare.js").write_text("host = '%s';\n" % version)
        self.assertTrue(any("bare.js" in f for f in self.findings()))

    def test_source_patch_may_hold_only_added_lines(self):
        header = "gaius-source-patch 1\nbase " + "a" * 64 + "\nresult " + "b" * 64 + "\n"
        patch = self.root / "patcher" / "patches" / "26.3" / "x.gpatch"
        patch.parent.mkdir(parents=True)
        patch.write_text(header + "@@ 3 1\n+our line\n")
        self.assertEqual(self.findings(), [])
        patch.write_text(header + "@@ 3 1\n-an official line\n+our line\n")
        self.assertTrue(any("x.gpatch" in f and "line 5" in f for f in self.findings()))
        patch.write_text(header + "@@ 3 1\n context\n")
        self.assertTrue(any("x.gpatch" in f for f in self.findings()))
        header2 = header.replace("gaius-source-patch 1", "gaius-source-patch 2")
        patch.write_text(header2 + "@@ 3 1\n=7 2\n~9 8\n~10 t1\n+our line\n")
        self.assertEqual(self.findings(), [])
        patch.write_text(header + "@@ 3 1\n=7 2\n")
        self.assertTrue(any("x.gpatch" in f for f in self.findings()), "version 1 has no copies")
        patch.write_text("--- a/Foo.java\n+++ b/Foo.java\n")
        self.assertTrue(any("not a gaius source patch" in f for f in self.findings()))

    def test_public_ipv4_literal_is_refused(self):
        public = ".".join(["8", "8", "4", "4"])  # assembled so this file has no literal
        (self.root / "cfg.json").write_text('{"a": "127.0.0.1", "b": "10.0.0.2", "c": "%s"}\n' % public)
        found = self.findings()
        self.assertEqual(len(found), 1)
        self.assertIn(public, found[0])


if __name__ == "__main__":
    unittest.main()
