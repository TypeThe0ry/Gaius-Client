import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "overlay_golden_diff", Path(__file__).with_name("overlay-golden-diff.py"))
golden = importlib.util.module_from_spec(spec)
spec.loader.exec_module(golden)


class ReviewedBaselineTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(dir=os.environ.get("GAIUS_G1_TEST_OUT"))
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.key = "libraries/fixture.jar!Fixture.class"
        self.base = {self.key: "a" * 64}
        self.new = {self.key: "b" * 64}
        self.review = {"schemaVersion": 1, "target": "js", "profiles": {
            "fixture": {"entries": [{"key": self.key, "beforeSha256": "a" * 64,
                                      "afterSha256": "b" * 64, "reason": "Reviewed fix"}]}}}

    def compare(self, review=True, **options):
        for name, values in (("base", self.base), ("new", self.new)):
            files = {key: {"type": "plain", "sha256": value} for key, value in values.items()}
            self.root.joinpath(name + ".json").write_text(json.dumps({"files": files}), encoding="utf-8")
        accept = self.root / "accept.json"
        accept.write_text(json.dumps(self.review), encoding="utf-8")
        parameters = {"products": True, "strict_added": True, "profile": "fixture"}
        parameters.update(options)
        with contextlib.redirect_stdout(io.StringIO()):
            return golden.diff(self.root / "base.json", self.root / "new.json", [], True,
                               accept_path=accept if review else None, **parameters)

    def test_exact_change_is_accepted(self):
        self.assertEqual(self.compare(), 0)

    def test_promoted_baseline_remains_strict(self):
        self.base = dict(self.new)
        self.assertEqual(self.compare(), 0)
        self.base[self.key] = self.new[self.key] = "c" * 64
        self.assertEqual(self.compare(), 1)

    def test_exact_added_entry_is_accepted(self):
        self.base = {}
        self.review["profiles"]["fixture"]["entries"][0]["beforeSha256"] = None
        self.assertEqual(self.compare(), 0)

    def test_unreviewed_addition_fails(self):
        self.new["libraries/fixture.jar!Extra.class"] = "c" * 64
        self.assertEqual(self.compare(), 1)

    def test_unreviewed_change_fails(self):
        self.base["libraries/fixture.jar!Extra.class"] = "c" * 64
        self.new["libraries/fixture.jar!Extra.class"] = "d" * 64
        self.assertEqual(self.compare(), 1)

    def test_removed_entry_fails(self):
        self.base["libraries/fixture.jar!Extra.class"] = "c" * 64
        self.assertEqual(self.compare(), 1)

    def test_baseline_mismatch_fails(self):
        self.base[self.key] = "c" * 64
        self.assertEqual(self.compare(), 1)

    def test_candidate_mismatch_or_missing_fails(self):
        self.new[self.key] = "c" * 64
        self.assertEqual(self.compare(), 1)
        self.new = {}
        self.assertEqual(self.compare(), 1)

    def test_duplicate_and_wildcard_reviews_fail(self):
        entry = self.review["profiles"]["fixture"]["entries"][0]
        self.review["profiles"]["fixture"]["entries"].append(copy.copy(entry))
        self.assertEqual(self.compare(), 1)
        self.review["profiles"]["fixture"]["entries"] = [entry]
        entry["key"] = "libraries/*"
        self.assertEqual(self.compare(), 1)

    def test_invalid_review_metadata_fails(self):
        self.assertEqual(self.compare(profile="absent"), 1)
        self.review["target"] = "wasm"
        self.assertEqual(self.compare(), 1)
        self.review["target"] = "js"
        self.review["profiles"]["fixture"]["entries"][0]["reason"] = ""
        self.assertEqual(self.compare(), 1)

    def test_hash_fields_are_explicit(self):
        self.base = {}
        del self.review["profiles"]["fixture"]["entries"][0]["beforeSha256"]
        self.assertEqual(self.compare(), 1)

    def test_review_requires_strict_scope(self):
        self.assertEqual(self.compare(products=False), 1)
        self.assertEqual(self.compare(strict_added=False), 1)
        self.assertEqual(self.compare(allow_added=["*"]), 1)

    def test_no_review_keeps_original_strict_behavior(self):
        self.assertEqual(self.compare(review=False), 1)
        self.base = dict(self.new)
        self.assertEqual(self.compare(review=False), 0)
        self.new["libraries/fixture.jar!Extra.class"] = "c" * 64
        self.assertEqual(self.compare(review=False), 1)


if __name__ == "__main__":
    unittest.main()
