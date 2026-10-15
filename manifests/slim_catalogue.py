#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Shrinks an `oslo-config-generator --format json` dump to what the catalogue
loader (orchestrator/.../conf/OsloJson.scala) reads: for each option its name,
type, default, choices, min/max, required, secret and old names. The help text,
which is most of the file, is dropped.

    manifests/slim_catalogue.py <dump.json> <out.json>
"""
import json
import sys

KEEP = ("name", "dest", "type", "default", "choices", "min", "max", "required", "secret", "deprecated_opts", "deprecated_for_removal")


def slim(doc):
    out = {"deprecated_options": doc.get("deprecated_options", {}), "options": {}}
    for section, body in doc["options"].items():
        out["options"][section] = {
            "opts": [{k: o[k] for k in KEEP if k in o} for o in body.get("opts", [])]
        }
    return out


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    with open(sys.argv[1]) as f:
        doc = json.load(f)
    with open(sys.argv[2], "w") as f:
        json.dump(slim(doc), f, separators=(",", ":"), sort_keys=True)
    n = sum(len(b["opts"]) for b in doc["options"].values())
    print(f"{sys.argv[2]}: {n} options in {len(doc['options'])} sections")
