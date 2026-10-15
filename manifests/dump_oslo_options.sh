#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Dumps the configuration options a Kolla image's service declares, as JSON, for
# the typed-configuration spike (orchestrator/.../conf/Conf.scala). Run it on a
# node that has podman and can pull the image (tst13 for placement-api).
#
#   manifests/dump_oslo_options.sh placement-api
#   manifests/dump_oslo_options.sh placement-api 2025.1-ubuntu-noble /tmp/opts
#
# Writes <outdir>/<image>-namespaces.txt (the oslo.config namespaces the image
# registers) and <outdir>/<image>.json (every option in them), then prints a
# short summary. Nothing is changed on the node except the output directory.
set -euo pipefail

service="${1:?usage: $0 <kolla image name, e.g. placement-api> [tag] [outdir]}"
tag="${2:-2025.1-ubuntu-noble}"
out="${3:-/tmp/oslo-options}"
image="quay.io/openstack.kolla/${service}:${tag}"

mkdir -p "$out"
echo "image: $image"
podman image exists "$image" || podman pull -q "$image" >/dev/null

# The namespaces are entry points in the group oslo.config.opts.
podman run --rm --entrypoint python3 "$image" -c '
import importlib.metadata as m
for e in sorted(m.entry_points(group="oslo.config.opts"), key=lambda e: e.name):
    print(e.name)
' | tee "$out/${service}-namespaces.txt"

args=()
while read -r ns; do args+=(--namespace "$ns"); done < "$out/${service}-namespaces.txt"

# As root inside the container: a Kolla image runs as its service user by default,
# which cannot write to the root-owned output directory.
podman run --rm --user root -v "$out:/out:z" --entrypoint oslo-config-generator "$image" \
    "${args[@]}" --format json --output-file "/out/${service}.json"

echo "wrote $out/${service}.json ($(wc -c < "$out/${service}.json") bytes)"
python3 - "$out/${service}.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print("top-level type:", type(d).__name__, "| keys:", list(d)[:8] if isinstance(d, dict) else len(d))
PY
