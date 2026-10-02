#!/usr/bin/env python3
"""Tests for check_docs.py."""

import os
import tempfile
import unittest

import check_docs


class CheckDocsTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def write(self, name, text):
        path = os.path.join(self.root, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path

    def test_existing_relative_link_passes(self):
        self.write("docs/a.md", "target")
        self.write("README.md", "[a](docs/a.md#section)")
        self.assertEqual(check_docs.main(self.root), 0)

    def test_missing_relative_link_fails(self):
        self.write("README.md", "[a](docs/missing.md)")
        self.assertEqual(check_docs.main(self.root), 1)

    def test_external_links_and_anchors_are_not_checked(self):
        self.write("README.md", "[a](https://example.com) [b](#top) [c](mailto:x@example.com)")
        self.assertEqual(check_docs.main(self.root), 0)

    def test_missing_link_with_a_title_fails(self):
        self.write("README.md", '[a](docs/missing.md "A title")')
        self.assertEqual(check_docs.main(self.root), 1)

    def test_existing_link_with_a_title_passes(self):
        self.write("docs/a.md", "target")
        self.write("README.md", '[a](docs/a.md "A title")')
        self.assertEqual(check_docs.main(self.root), 0)

    def test_missing_reference_style_link_fails(self):
        self.write("README.md", "[a][ref]\n\n[ref]: docs/missing.md\n")
        self.assertEqual(check_docs.main(self.root), 1)

    def test_build_directories_are_skipped(self):
        self.write("target/generated.md", "[a](missing.md)")
        self.assertEqual(check_docs.main(self.root), 0)


if __name__ == "__main__":
    unittest.main()
