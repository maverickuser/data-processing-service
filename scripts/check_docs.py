#!/usr/bin/env python3
"""Verify that every relative link in the repository's Markdown files points at an existing path.

Checked: inline links, with or without a title, and reference-style link definitions.
Not checked: external links (http, https, mailto), heading anchors, and raw HTML.
"""

import os
import re
import sys

INLINE_LINK = re.compile(r"\]\(\s*<?([^)\s>]+)>?(?:\s+[^)]*)?\)")
REFERENCE_DEFINITION = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s.*)?$", re.MULTILINE)
SKIPPED_DIRECTORIES = {".git", "target", "node_modules", ".terraform"}
EXTERNAL_PREFIXES = ("http://", "https://", "mailto:", "#")


def markdown_files(root):
    """Yield the path of every Markdown file under root, skipping build and VCS directories."""
    for directory, subdirectories, names in os.walk(root):
        subdirectories[:] = [name for name in subdirectories if name not in SKIPPED_DIRECTORIES]
        for name in names:
            if name.endswith(".md"):
                yield os.path.join(directory, name)


def broken_links(path):
    """Return the relative link targets in the file at path that do not exist."""
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    broken = []
    for target in INLINE_LINK.findall(text) + REFERENCE_DEFINITION.findall(text):
        if target.startswith(EXTERNAL_PREFIXES):
            continue
        resolved = os.path.normpath(os.path.join(os.path.dirname(path), target.split("#")[0]))
        if not os.path.exists(resolved):
            broken.append(target)
    return broken


def main(root="."):
    failures = [(path, target) for path in markdown_files(root) for target in broken_links(path)]
    for path, target in failures:
        print(f"{path}: broken link {target}")
    if failures:
        return 1
    print("Documentation links valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
