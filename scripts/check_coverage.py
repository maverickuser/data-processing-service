#!/usr/bin/env python3
"""Enforce the unit line-coverage gate from a JaCoCo CSV report.

Passes only when covered lines / total lines is strictly greater than the threshold.
Exactly the threshold fails. No rounding. Every class in the report counts, including
classes that no test touched. A report with no measurable lines fails unless
--allow-empty is given, so missing coverage data can never pass silently.
"""

import argparse
import csv
import sys
from fractions import Fraction


def read_line_counts(report_path):
    """Return (covered, total) line counts summed over every class in the report."""
    covered = 0
    missed = 0
    with open(report_path, newline="", encoding="utf-8") as report:
        for row in csv.DictReader(report):
            covered += int(row["LINE_COVERED"])
            missed += int(row["LINE_MISSED"])
    return covered, covered + missed


def evaluate(covered, total, threshold, allow_empty):
    """Return (passed, message) for the given counts and threshold (a Fraction)."""
    if total == 0:
        if allow_empty:
            return True, "No measurable production lines; gate not applicable yet."
        return False, "No measurable production lines in the coverage report."
    ratio = Fraction(covered, total)
    percent = float(ratio * 100)
    summary = f"Unit line coverage {covered}/{total} = {percent:.4f}%"
    if ratio > threshold:
        return True, f"{summary} is greater than {float(threshold * 100):g}%."
    return False, f"{summary} is not strictly greater than {float(threshold * 100):g}%."


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", help="path to jacoco.csv")
    parser.add_argument("--threshold", default="95", help="percentage that must be exceeded")
    parser.add_argument(
        "--allow-empty",
        action="store_true",
        help="pass when the report has no measurable lines (only before any logic exists)",
    )
    args = parser.parse_args(argv)
    covered, total = read_line_counts(args.report)
    passed, message = evaluate(covered, total, Fraction(args.threshold) / 100, args.allow_empty)
    print(message)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
