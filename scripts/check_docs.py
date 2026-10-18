#!/usr/bin/env python3
"""Check that the repository's documentation still matches the checkout.

Checked:
- Every relative link in a Markdown file (inline, with or without a title, or reference-style) points at an existing path.
- Every repository path written in backticks in AGENTS.md exists, and the AGENTS.md repository map lists every
  directory under each path whose children it maps.
- Every `make <target>` named in a Markdown file is a target in the Makefile.
- Decision records in docs/decisions have unique numbers and are all listed in docs/decisions/README.md.

Not checked: external links (http, https, mailto), heading anchors, and raw HTML.
Files that git ignores are left out of the repository-path and decision checks.
"""

import fnmatch
import os
import re
import subprocess
import sys

INLINE_LINK = re.compile(r"\]\(\s*<?([^)\s>]+)>?(?:\s+[^)]*)?\)")
REFERENCE_DEFINITION = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s.*)?$", re.MULTILINE)
BACKTICK_TOKEN = re.compile(r"`([^`\s]+)`")
MAKE_COMMAND = re.compile(r"`make ([A-Za-z0-9_-]+)")
MAKE_TARGET = re.compile(r"^([A-Za-z0-9_-]+)\s*:(?!=)", re.MULTILINE)
DECISION_FILE = re.compile(r"^(\d{4})-.+\.md$")
SKIPPED_DIRECTORIES = {".git", "target", "node_modules", ".terraform", ".claude"}
EXTERNAL_PREFIXES = ("http://", "https://", "mailto:", "#")


def markdown_files(root):
    """Yield the path of every Markdown file under root, skipping build and VCS directories."""
    for directory, subdirectories, names in os.walk(root):
        subdirectories[:] = [name for name in subdirectories if name not in SKIPPED_DIRECTORIES]
        for name in names:
            if name.endswith(".md"):
                yield os.path.join(directory, name)


def read(path):
    """Return the text of the file at path."""
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def broken_links(path):
    """Return the relative link targets in the file at path that do not exist."""
    text = read(path)
    broken = []
    for target in INLINE_LINK.findall(text) + REFERENCE_DEFINITION.findall(text):
        if target.startswith(EXTERNAL_PREFIXES):
            continue
        resolved = os.path.normpath(os.path.join(os.path.dirname(path), target.split("#")[0]))
        if not os.path.exists(resolved):
            broken.append(target)
    return broken


def visible_files(root):
    """Return the repository-relative paths of files git does not ignore, or of every file outside a git checkout."""
    try:
        listed = subprocess.run(
            ["git", "-C", root, "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            capture_output=True, check=True, text=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        files = []
        for directory, subdirectories, names in os.walk(root):
            subdirectories[:] = [name for name in subdirectories if name not in SKIPPED_DIRECTORIES]
            files += [os.path.relpath(os.path.join(directory, name), root) for name in names]
        return {path.replace(os.sep, "/") for path in files}
    # A tracked file deleted in the working tree no longer counts as present.
    return {path for path in listed.split("\0") if path and os.path.exists(os.path.join(root, path))}


def path_exists(path, files):
    """Return whether a repository path, directory, or glob matches at least one visible file."""
    path = path.rstrip("/")
    if any(character in path for character in "*?["):
        return any(fnmatch.fnmatch(name, path) for name in files)
    return path in files or any(name.startswith(path + "/") for name in files)


def repository_map_lines(text):
    """Return (path, indented) pairs from the fenced block under the AGENTS.md "Repository map" heading."""
    section = re.search(r"^## Repository map\s*$(.*?)(?=^## )", text, re.MULTILINE | re.DOTALL)
    block = section and re.search(r"```[a-z]*\n(.*?)```", section.group(1), re.DOTALL)
    if not block:
        return []
    entries = []
    for line in block.group(1).splitlines():
        if line.strip():
            entries.append((line.split()[0], line.startswith(" ")))
    return entries


def agents_findings(root, files):
    """Return problems with the paths that AGENTS.md names."""
    path = os.path.join(root, "AGENTS.md")
    if not os.path.exists(path):
        return []
    text = read(path)
    top_level = {name.split("/")[0] for name in files}
    findings = []
    for token in sorted(set(BACKTICK_TOKEN.findall(text))):
        placeholder = any(character in token for character in "<>{}")
        if "/" in token and not placeholder and token.split("/")[0] in top_level and not path_exists(token, files):
            findings.append(f"AGENTS.md: `{token}` does not exist. Update AGENTS.md to match the checkout, "
                            "or restore the path.")

    parent = None
    mapped = {}
    for entry, indented in repository_map_lines(text):
        if not indented:
            parent = entry if entry.endswith("/") else None
            if not path_exists(entry, files):
                findings.append(f"AGENTS.md repository map: `{entry}` does not exist. Remove or rename it in the map.")
        elif parent:
            child = parent + entry
            mapped.setdefault(parent, set()).add(entry.rstrip("/"))
            if not path_exists(child, files):
                findings.append(f"AGENTS.md repository map: `{child}` does not exist. Remove or rename it in the map.")
    for parent, children in mapped.items():
        on_disk = {name[len(parent):].split("/")[0] for name in files
                   if name.startswith(parent) and "/" in name[len(parent):]}
        for missing in sorted(on_disk - children):
            findings.append(f"AGENTS.md repository map: `{parent}{missing}/` exists but is not listed. "
                            "Add it under its parent with a one-line description.")
    return findings


def make_findings(root):
    """Return the `make` targets named in Markdown files that the Makefile does not define."""
    makefile = os.path.join(root, "Makefile")
    if not os.path.exists(makefile):
        return []
    targets = set(MAKE_TARGET.findall(read(makefile)))
    findings = []
    for path in markdown_files(root):
        for target in sorted(set(MAKE_COMMAND.findall(read(path)))):
            if target not in targets:
                findings.append(f"{os.path.relpath(path, root)}: `make {target}` is not a Makefile target. "
                                "Fix the command in the document, or add the target and list it in AGENTS.md.")
    return findings


def decision_findings(root, files):
    """Return duplicate decision numbers and decision records missing from the decisions index."""
    index_path = os.path.join(root, "docs", "decisions", "README.md")
    records = sorted(name.split("/")[-1] for name in files
                     if name.startswith("docs/decisions/") and DECISION_FILE.match(name.split("/")[-1]))
    if not records:
        return []
    index = read(index_path) if os.path.exists(index_path) else ""
    findings = []
    numbers = {}
    for name in records:
        numbers.setdefault(DECISION_FILE.match(name).group(1), []).append(name)
        if f"({name})" not in index:
            findings.append(f"docs/decisions/{name} is not listed in docs/decisions/README.md. "
                            f"Add a line: - [{name[:4]} — Title]({name})")
    for number, names in sorted(numbers.items()):
        if len(names) > 1:
            findings.append(f"docs/decisions: number {number} is used by {', '.join(names)}. "
                            "Give the newer record the next free number and update its links.")
    return findings


def main(root="."):
    """Print every documentation problem under root and return 1 if there is any, else 0."""
    findings = [f"{path}: broken link {target}" for path in markdown_files(root) for target in broken_links(path)]
    files = visible_files(root)
    findings += agents_findings(root, files) + make_findings(root) + decision_findings(root, files)
    for finding in findings:
        print(finding)
    if findings:
        return 1
    print("Documentation matches the checkout: links, AGENTS.md paths, make targets, and decision records.")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
