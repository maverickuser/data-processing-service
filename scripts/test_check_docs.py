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

    def test_agents_path_that_exists_passes(self):
        self.write("docs/plans/status.md", "status")
        self.write("AGENTS.md", "Read `docs/plans/status.md` and `docs/plans/`; branch `docs/<slug>`.")
        self.assertEqual(check_docs.main(self.root), 0)

    def test_agents_path_that_is_gone_fails(self):
        self.write("docs/a.md", "a")
        self.write("AGENTS.md", "Read `docs/missing.md`.")
        self.assertEqual(check_docs.main(self.root), 1)

    def test_agents_glob_must_match_a_file(self):
        self.write("contracts/a.yaml", "a")
        self.write("AGENTS.md", "Read `contracts/*.yaml`.")
        self.assertEqual(check_docs.main(self.root), 0)
        self.write("AGENTS.md", "Read `contracts/*.json`.")
        self.assertEqual(check_docs.main(self.root), 1)

    def map_with(self, children):
        lines = "\n".join(f"  {child}/   description" for child in children)
        return f"# A\n\n## Repository map\n\n```text\nsrc/   sources\n{lines}\n```\n\n## Next\n"

    def test_repository_map_listing_every_directory_passes(self):
        self.write("src/job/A.java", "")
        self.write("src/outbox/B.java", "")
        self.write("AGENTS.md", self.map_with(["job", "outbox"]))
        self.assertEqual(check_docs.main(self.root), 0)

    def test_repository_map_missing_a_directory_fails(self):
        self.write("src/job/A.java", "")
        self.write("src/outbox/B.java", "")
        self.write("AGENTS.md", self.map_with(["job"]))
        self.assertEqual(check_docs.main(self.root), 1)

    def test_repository_map_listing_a_removed_directory_fails(self):
        self.write("src/job/A.java", "")
        self.write("AGENTS.md", self.map_with(["job", "orchestration"]))
        self.assertEqual(check_docs.main(self.root), 1)

    def test_make_target_that_exists_passes(self):
        self.write("Makefile", ".PHONY: lint\nlint:\n\techo lint\nVAR := x\n")
        self.write("AGENTS.md", "Run `make lint`.")
        self.assertEqual(check_docs.main(self.root), 0)

    def test_make_target_that_is_missing_fails(self):
        self.write("Makefile", "lint:\n\techo lint\nVAR := x\n")
        self.write("docs/a.md", "Run `make VAR` or `make check`.")
        self.assertEqual(check_docs.main(self.root), 1)

    def test_indexed_decisions_with_unique_numbers_pass(self):
        self.write("docs/decisions/0001-a.md", "# 0001")
        self.write("docs/decisions/0002-b.md", "# 0002")
        self.write("docs/decisions/README.md", "- [0001](0001-a.md)\n- [0002](0002-b.md)\n")
        self.assertEqual(check_docs.main(self.root), 0)

    def test_decision_missing_from_the_index_fails(self):
        self.write("docs/decisions/0001-a.md", "# 0001")
        self.write("docs/decisions/README.md", "No records yet.\n")
        self.assertEqual(check_docs.main(self.root), 1)

    def test_duplicate_decision_number_fails(self):
        self.write("docs/decisions/0001-a.md", "# 0001")
        self.write("docs/decisions/0001-b.md", "# 0001")
        self.write("docs/decisions/README.md", "- [a](0001-a.md)\n- [b](0001-b.md)\n")
        self.assertEqual(check_docs.main(self.root), 1)


if __name__ == "__main__":
    unittest.main()
