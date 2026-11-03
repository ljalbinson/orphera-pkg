#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Counts the Scala source without comments and blank lines, by part (framework, manifests, tests).

Reads the file names (one per line) from stdin; `make lines` feeds it the files git tracks plus new ones
not yet added, minus what .gitignore excludes. A line counts when it is neither blank nor only a
`//` comment nor inside a `/* ... */` block. Scripts embedded in Scala strings count as code.
"""
import sys


def count(path):
    n = 0
    in_block = False
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            s = line.strip()
            if in_block:
                if "*/" not in s:
                    continue
                in_block = False
                s = s.split("*/", 1)[1].strip()
            if not s or s.startswith("//"):
                continue
            if s.startswith("/*"):
                if "*/" in s:
                    if not s.split("*/", 1)[1].strip():
                        continue
                else:
                    in_block = True
                    continue
            n += 1
    return n


def part(path):
    if path.startswith("manifests/"):
        return "Playbooks (manifests/)"
    if "/src/test/" in path or path.startswith("test/"):
        return "Tests"
    return "Framework"


def main():
    files = {}
    lines = {}
    for path in (p.strip() for p in sys.stdin):
        if not path.endswith((".scala", ".sc")):
            continue
        k = part(path)
        files[k] = files.get(k, 0) + 1
        lines[k] = lines.get(k, 0) + count(path)
    order = ["Framework", "Playbooks (manifests/)", "Tests"]
    print("Scala without comments and blank lines")
    print("%-26s %6s %8s" % ("Part", "Files", "Lines"))
    for k in order:
        if k in files:
            print("%-26s %6d %8d" % (k, files[k], lines[k]))
    print("%-26s %6d %8d" % ("Total", sum(files.values()), sum(lines.values())))


main()
