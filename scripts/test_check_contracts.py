#!/usr/bin/env python3
"""Tests for check_contracts.rb: run it against small repository trees."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_contracts.rb")
REPOSITORY = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class CheckContractsTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        for directory in ("contracts", os.path.join("docs", "specs")):
            shutil.copytree(os.path.join(REPOSITORY, directory), os.path.join(self.root, directory))

    def tearDown(self):
        shutil.rmtree(self.root)

    def run_check(self):
        return subprocess.run(["ruby", SCRIPT, self.root], capture_output=True, text=True)

    def path(self, *parts):
        return os.path.join(self.root, *parts)

    def test_committed_contracts_pass(self):
        self.assertEqual(self.run_check().returncode, 0)

    def test_missing_contract_fails(self):
        os.remove(self.path("contracts", "nsdl-security-mapping-v1.yaml"))
        self.assertEqual(self.run_check().returncode, 1)

    def test_unexpected_contract_fails(self):
        with open(self.path("contracts", "extra-v1.yaml"), "w", encoding="utf-8") as handle:
            handle.write("id: extra\nversion: v1\n")
        self.assertEqual(self.run_check().returncode, 1)

    def test_contract_without_required_keys_fails(self):
        with open(self.path("contracts", "nsdl-security-json-v1.yaml"), "w", encoding="utf-8") as handle:
            handle.write("id: nsdl-security-json\nversion: v1\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing dataset, format", result.stderr)

    def test_mapping_naming_unknown_source_contract_fails(self):
        target = self.path("contracts", "nsdl-security-mapping-v1.yaml")
        with open(target, encoding="utf-8") as handle:
            text = handle.read().replace("sourceContract: nsdl-security-json-v1", "sourceContract: nope-v1")
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(text)
        self.assertEqual(self.run_check().returncode, 1)

    def test_yaml_and_json_mismatch_fails(self):
        target = self.path("docs", "specs", "data-processing-service-read-openapi.json")
        with open(target, encoding="utf-8") as handle:
            document = json.load(handle)
        document["info"]["version"] = "9.9.9"
        with open(target, "w", encoding="utf-8") as handle:
            json.dump(document, handle)
        self.assertEqual(self.run_check().returncode, 1)

    def test_document_that_is_not_openapi_fails(self):
        for extension, text in (("yaml", "a: 1\n"), ("json", '{"a": 1}')):
            with open(self.path("docs", "specs", f"data-processing-service-openapi.{extension}"), "w", encoding="utf-8") as handle:
                handle.write(text)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing openapi, info, paths, components", result.stderr)


if __name__ == "__main__":
    unittest.main()
