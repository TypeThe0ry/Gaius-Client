#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("check_hotfixes", Path(__file__).with_name("check_hotfixes.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class HotfixGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def fixes(self, *signatures):
        return [{"id": "fixture", "description": "fixture fix", "commit": "fixture", "signatures": list(signatures)}]

    def sig(self, **extra):
        return {"file": "source.js", "substring": "fixed()", "must_exist": True, **extra}

    def test_positive_regex_and_absence(self):
        (self.root / "source.js").write_text("fixed()")
        self.assertEqual(gate.check(self.fixes(self.sig(), {"file":"source.js", "regex":r"broken\(\)", "must_exist":False}), self.root), [])

    def test_lists_every_missing_and_negative_failure(self):
        (self.root / "source.js").write_text("broken()")
        failures = gate.check(self.fixes(self.sig(), self.sig(file="absent.js"), self.sig(substring="broken()", must_exist=False)), self.root)
        self.assertEqual(len(failures), 3)
        self.assertIn("absent.js", failures[1])

    def test_absent_file_fails_negative_signature_too(self):
        self.assertEqual(len(gate.check(self.fixes(self.sig(must_exist=False)), self.root)), 1)

    def test_rejects_malformed_schema(self):
        cases = [{}, {"hotfixes":[]}, {"hotfixes":self.fixes(self.sig(regex="x"))}, {"hotfixes":self.fixes(self.sig(file="../escape"))}, {"hotfixes":self.fixes(self.sig(must_exist="true"))}]
        for data in cases:
            with self.subTest(data=data):
                path=self.root/"signatures.json"; path.write_text(json.dumps(data))
                with self.assertRaises(ValueError): gate.load_signatures(path)

    def test_staged_checks_every_site_and_portable(self):
        fixes=self.fixes(self.sig(staged="boot"))
        self.assertTrue(gate.check(fixes,self.root,self.root))
        for name in ("a","b"):
            site=self.root/f"site-{name}"/"boot"; site.mkdir(parents=True)
            (site/"gaius-boot.js").write_text("fixed()")
            portable=self.root/f"portable-{name}"; portable.mkdir()
            (portable/"Gaius.html").write_text("<script>fixed()</script>")
        self.assertEqual(gate.check(fixes,self.root,self.root),[])
        (self.root/"site-b/boot/gaius-boot.js").write_text("stale")
        failures=gate.check(fixes,self.root,self.root)
        self.assertEqual(len(failures),1)
        self.assertIn("site-b",failures[0])


if __name__ == "__main__": unittest.main()
