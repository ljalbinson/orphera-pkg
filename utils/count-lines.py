#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Counts the Scala, Go, Python and shell source by part: raw lines (as `wc -l` counts them) and code lines.

Reads the file names (one per line) from stdin; `make lines` feeds it the files git tracks plus new ones
not yet added, minus what .gitignore excludes and the protobuf-generated Go in internal/pb/. Code lines are
the lines that are neither blank nor only a comment (`//`, or inside a `/* ... */` block; `#` in Python, where
docstrings count as comments too; `#` in shell, shebang line included). Scripts embedded in Scala strings and heredoc
text in shell scripts count as code. A file is a shell script when it is named *.sh or *.bash, or has no extension and
starts with a #! line naming sh, bash or dash.
"""
import os
import sys


def python_code_lines(text):
    """Python: a line is code when a token other than a comment sits on it; docstrings are left out."""
    import ast
    import io
    import tokenize
    skip = {tokenize.COMMENT, tokenize.NL, tokenize.NEWLINE, tokenize.INDENT, tokenize.DEDENT, tokenize.ENDMARKER}
    lines = set()
    for tok in tokenize.generate_tokens(io.StringIO(text).readline):
        if tok.type not in skip:
            lines.update(range(tok.start[0], tok.end[0] + 1))
    for node in ast.walk(ast.parse(text)):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            body = node.body
            if body and isinstance(body[0], ast.Expr) and isinstance(getattr(body[0], "value", None), ast.Constant) \
                    and isinstance(body[0].value.value, str):
                lines.difference_update(range(body[0].lineno, body[0].end_lineno + 1))
    return len(lines)


def count(path):
    """Returns (raw lines, code lines) of one file."""
    with open(path, "rb") as f:
        data = f.read()
    raw = data.count(b"\n")
    if path.endswith(".py"):
        return raw, python_code_lines(data.decode("utf-8", errors="replace"))
    if part(path) in ORDER[7:]:
        return raw, sum(1 for line in data.decode("utf-8", errors="replace").split("\n")
                        if line.strip() and not line.lstrip().startswith("#"))
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


def is_extensionless_shell(path):
    """A file with no extension whose first line is a #! naming sh, bash or dash."""
    if "." in os.path.basename(path):
        return False
    try:
        with open(path, "rb") as f:
            first = f.readline(200).decode("utf-8", errors="replace")
    except OSError:
        return False
    words = first[2:].split() if first.startswith("#!") else []
    if words and os.path.basename(words[0]) == "env":
        words = words[1:]
    return bool(words) and os.path.basename(words[0]) in ("sh", "bash", "dash")


def part(path):
    if path.endswith((".scala", ".sc")):
        if path.startswith("manifests/"):
            return "Scala playbooks (manifests/)"
        if "/src/test/" in path or path.startswith("test/"):
            return "Scala tests"
        return "Scala framework"
    if path.endswith(".go"):
        return "Go tests" if path.endswith("_test.go") else "Go agent and tools"
    if path.endswith(".py"):
        return "Python tests" if os.path.basename(path).startswith("test") else "Python tools"
    if path.endswith((".sh", ".bash")) or is_extensionless_shell(path):
        return "Shell tests" if os.path.basename(path).startswith("test") else "Shell scripts"
    return None


ORDER = ["Scala framework", "Scala playbooks (manifests/)", "Scala tests", "Go agent and tools", "Go tests",
         "Python tools", "Python tests", "Shell scripts", "Shell tests"]


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
    for title, keys in (("Scala", ORDER[:3]), ("Go", ORDER[3:5]), ("Python", ORDER[5:7]), ("Shell", ORDER[7:])):
        for k in keys:
            if tot[k][0]:
                print(fmt % (k, tot[k][0], tot[k][1], tot[k][2]))
        files = sum(tot[k][0] for k in keys)
        print(fmt % (title + " total", files, sum(tot[k][1] for k in keys), sum(tot[k][2] for k in keys)))
    print(fmt % ("Total", sum(v[0] for v in tot.values()), sum(v[1] for v in tot.values()),
                 sum(v[2] for v in tot.values())))
    print("Raw = lines as wc -l counts them; Code = without blank lines and comments.")


main()
