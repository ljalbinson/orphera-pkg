#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Counts the Scala and Go source by part: raw lines (as `wc -l` counts them) and code lines.

Reads the file names (one per line) from stdin; `make lines` feeds it the files git tracks plus new ones
not yet added, minus what .gitignore excludes and the protobuf-generated Go in internal/pb/. Code lines are
the lines that are neither blank nor only a comment (`//`, or inside a `/* ... */` block). Scripts embedded
in Scala strings count as code.
"""
import sys


def count(path):
    """Returns (raw lines, code lines) of one file."""
    with open(path, "rb") as f:
        data = f.read()
    raw = data.count(b"\n")
    code = 0
    in_block = False
    for line in data.decode("utf-8", errors="replace").split("\n"):
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
        code += 1
    return raw, code


def part(path):
    if path.endswith((".scala", ".sc")):
        if path.startswith("manifests/"):
            return "Scala playbooks (manifests/)"
        if "/src/test/" in path or path.startswith("test/"):
            return "Scala tests"
        return "Scala framework"
    if path.endswith(".go"):
        return "Go tests" if path.endswith("_test.go") else "Go agent and tools"
    return None


ORDER = ["Scala framework", "Scala playbooks (manifests/)", "Scala tests", "Go agent and tools", "Go tests"]


def main():
    tot = {k: [0, 0, 0] for k in ORDER}  # files, raw, code
    for path in (p.strip() for p in sys.stdin):
        k = part(path)
        if k is None:
            continue
        raw, code = count(path)
        tot[k][0] += 1
        tot[k][1] += raw
        tot[k][2] += code
    fmt = "%-30s %6s %9s %9s"
    print(fmt % ("Part", "Files", "Raw", "Code"))
    for title, keys in (("Scala", ORDER[:3]), ("Go", ORDER[3:])):
        for k in keys:
            if tot[k][0]:
                print(fmt % (k, tot[k][0], tot[k][1], tot[k][2]))
        files = sum(tot[k][0] for k in keys)
        print(fmt % (title + " total", files, sum(tot[k][1] for k in keys), sum(tot[k][2] for k in keys)))
    print(fmt % ("Total", sum(v[0] for v in tot.values()), sum(v[1] for v in tot.values()),
                 sum(v[2] for v in tot.values())))
    print("Raw = lines as wc -l counts them; Code = without blank lines and comments.")


main()
