import unittest
from collections import Counter

from lint_source_similarity import compare, exit_code, similarity, tokens


def source(path, source_set, code):
    tokenized = tokens(code)
    return {"path": path, "set": source_set, "tokens": tokenized, "counts": Counter(tokenized)}


class SimilarityTests(unittest.TestCase):
    def test_comments_and_whitespace(self):
        self.assertEqual(tokens('class A { String text = "// literal"; }'),
                         tokens('/* ignored */ class\nA { String text="// literal"; }'))

    def test_boundary(self):
        self.assertEqual(similarity(tuple("abcde"), tuple("abcdf")), 0.8)
        self.assertLess(similarity(tuple("abcde"), tuple("abcfg")), 0.8)

    def test_different_names_and_modes(self):
        sources = [source("first.java", "canonical", "class First { int value() { return 7; } }"),
                   source("renamed.java", "generation", "class Other { int value() { return 7; } }")]
        findings = compare(sources, {})
        self.assertEqual(len(findings), 1)
        self.assertEqual(exit_code(findings, False), 0)
        self.assertEqual(exit_code(findings, True), 1)
        allowed = compare(sources, {("first.java", "renamed.java"): "separate ABI adapter"})
        self.assertEqual(exit_code(allowed, True), 0)

    def test_same_set_is_not_a_violation(self):
        sources = [source("one.java", "canonical", "class A {}"),
                   source("two.java", "canonical", "class A {}")]
        self.assertEqual(compare(sources, {}), [])


if __name__ == "__main__":
    unittest.main()
