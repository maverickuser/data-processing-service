#!/usr/bin/env python3
"""Tests for check_coverage.py: the gate is strict, unrounded, and counts untested classes."""

import os
import tempfile
import unittest
from fractions import Fraction

import check_coverage

HEADER = (
    "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,"
    "LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n"
)


def report(*classes):
    """Write a JaCoCo-shaped CSV for (name, missed, covered) rows and return its path."""
    handle = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8")
    handle.write(HEADER)
    for name, missed, covered in classes:
        handle.write(f"app,pkg,{name},0,0,0,0,{missed},{covered},0,0,0,0\n")
    handle.close()
    return handle.name


class CheckCoverageTest(unittest.TestCase):
    def run_gate(self, *classes, extra=()):
        path = report(*classes)
        try:
            return check_coverage.main([path, *extra])
        finally:
            os.unlink(path)

    def test_exactly_95_percent_fails(self):
        self.assertEqual(self.run_gate(("A", 5, 95)), 1)

    def test_just_above_95_percent_passes(self):
        self.assertEqual(self.run_gate(("A", 49, 951)), 0)

    def test_value_that_would_round_up_to_95_fails(self):
        self.assertEqual(self.run_gate(("A", 5001, 94999)), 1)

    def test_untested_class_counts_against_the_total(self):
        self.assertEqual(self.run_gate(("Tested", 0, 100), ("Untested", 10, 0)), 1)

    def test_ratio_is_weighted_by_lines_not_averaged_per_class(self):
        self.assertEqual(self.run_gate(("Big", 0, 990), ("Small", 10, 0)), 0)

    def test_empty_report_fails_by_default(self):
        self.assertEqual(self.run_gate(), 1)

    def test_empty_report_passes_only_when_explicitly_allowed(self):
        self.assertEqual(self.run_gate(extra=("--allow-empty",)), 0)

    def test_evaluate_uses_exact_arithmetic(self):
        passed, _ = check_coverage.evaluate(95, 100, Fraction(95, 100), False)
        self.assertFalse(passed)


if __name__ == "__main__":
    unittest.main()
