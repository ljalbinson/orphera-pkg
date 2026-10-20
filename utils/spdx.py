#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Adds, or checks for, an SPDX-License-Identifier header in the tracked source files.

  utils/spdx.py --check            list files that lack one; exit 1 if any (make check-spdx, CI)
  utils/spdx.py --dry-run          list the files --apply would change
  utils/spdx.py --apply [ID]       add the header (default Apache-2.0)

A file is covered if it has "SPDX-License-Identifier" in its first 10 lines, or matches a
glob in utils/spdx-exempt.txt (data, docs and files that cannot carry a comment; the
repository LICENSE covers those). The header goes after a shebang, and after a
"# yaml-language-server" modeline, so neither stops working. A tracked file that is neither
covered nor of a type this tool knows how to comment is reported as an error.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SLASH = {".scala", ".sbt", ".proto"}
HASH = {".sh", ".yaml", ".yml", ".py", ".service"}
HASH_NAMES = {"Makefile"}


def glob_to_re(g: str) -> re.Pattern:
    out, i = "", 0
    while i < len(g):
        if g.startswith("**", i):
            out += ".*"; i += 2
        elif g[i] == "*":
            out += "[^/]*"; i += 1
        elif g[i] == "?":
            out += "[^/]"; i += 1
        else:
            out += re.escape(g[i]); i += 1
    return re.compile(out + r"\Z")


def exemptions() -> list:
    pats = []
    for line in (ROOT / "utils" / "spdx-exempt.txt").read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            pats.append(glob_to_re(line))
    return pats


def tracked() -> list:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout
    return [p for p in out.decode().split("\0") if p]


def has_header(text: str) -> bool:
    return "SPDX-License-Identifier" in "".join(text.splitlines(keepends=True)[:10])


def style(path: str, first: str):
    p = Path(path)
    if first.startswith("#!"):
        return "hash"
    if p.suffix in SLASH:
        return "slash"
    if p.suffix in HASH or p.name in HASH_NAMES:
        return "hash"
    return None


def with_header(path: str, text: str, lic: str) -> str:
    lines = text.splitlines(keepends=True)
    first = lines[0] if lines else ""
    st = style(path, first)
    nl = "\r\n" if first.endswith("\r\n") else "\n"
    if st == "slash":
        return f"// SPDX-License-Identifier: {lic}{nl}{nl}" + text
    h = f"# SPDX-License-Identifier: {lic}{nl}"
    if first.startswith("#!") or first.startswith("# yaml-language-server"):
        return first + h + "".join(lines[1:])
    return h + text


def main() -> int:
    args = sys.argv[1:]
    mode, lic = "check", "Apache-2.0"
    for a in args:
        if a in ("--check", "--dry-run", "--apply"):
            mode = a[2:]
        elif not a.startswith("-"):
            lic = a
        else:
            print(__doc__); return 2
    exempt = exemptions()
    missing, unknown, changed = [], [], []
    for f in tracked():
        if any(r.match(f) for r in exempt):
            continue
        path = ROOT / f
        if not path.is_file():
            continue
        try:
            text = path.read_bytes().decode("utf-8")
        except UnicodeDecodeError:
            unknown.append(f); continue
        if has_header(text):
            continue
        first = text.splitlines(keepends=True)[0] if text else ""
        if style(f, first) is None:
            unknown.append(f); continue
        missing.append(f)
        if mode == "apply":
            path.write_bytes(with_header(f, text, lic).encode("utf-8"))
            changed.append(f)
    if mode == "check":
        for f in missing:
            print(f"missing SPDX header: {f}", file=sys.stderr)
    elif mode == "dry-run":
        for f in missing:
            print(f"would add header: {f}")
    else:
        for f in changed:
            print(f"added header: {f}")
    for f in unknown:
        print(f"no rule for {f}: add a comment style to utils/spdx.py or a glob to utils/spdx-exempt.txt", file=sys.stderr)
    if mode == "check":
        print("every tracked file has a header or is exempt" if not (missing or unknown) else f"{len(missing)} missing, {len(unknown)} without a rule", file=sys.stderr if (missing or unknown) else sys.stdout)
        return 1 if (missing or unknown) else 0
    print(f"{len(missing)} file(s) {'to change' if mode == 'dry-run' else 'updated'}")
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
