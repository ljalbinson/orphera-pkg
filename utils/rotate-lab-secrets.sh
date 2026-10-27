#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Replaces the OpenStack lab's service passwords and Ceph client keys in the
# secret store, so that the values that were once published in this repository
# (manifests/seed_test_secrets.sh, now removed; still in git history) are no
# longer the ones in use. TEST LAB ONLY.
#
#   utils/rotate-lab-secrets.sh            # show what it would do
#   utils/rotate-lab-secrets.sh --yes      # do it
#
# What --yes does:
#   1. copies the encrypted store to <store>.bak-<time> (mode 600)
#   2. removes each lab secret listed below, so Orphera makes a new random value
#      the next time a playbook asks for it
#   3. for the two Ceph client keys, which must be a valid cephx key and so cannot
#      be a random string, sets a freshly generated key
#
# NOT touched: orphera/agent-token, orphera/inventory-db-* and any other name
# that is not in the list.
#
# THE RUNNING LAB DOES NOT CHANGE: its databases, Keystone users, RabbitMQ and
# Ceph still have the old values until it is rebuilt with the new ones, so run
# this only as the first step of
#
#   manifests/rebuild_openstack_from_scratch.sh --tests --wordpress
#
# (DESTRUCTIVE: see its header), and then re-apply the tst7 monitoring:
#
#   orphera cluster-playbook manifests/observability_stack.scala
#   orphera cluster-playbook manifests/observability_extended.scala
#
# The store file is $ORPHERA_SECRETS, default ~/.config/orphera/secrets.age.

set -uo pipefail

NAMES=(
  galera/sst galera/clustercheck galera/vrrp
  keystone/admin keystone/db
  placement/db placement/service
  glance/db glance/service
  cinder/db cinder/service cinder/rabbit
  nova/db nova/service nova/rabbit
  neutron/db neutron/service neutron/rabbit
  designate/db designate/service designate/rabbit
  heat/db heat/service heat/rabbit heat/domain-admin
  barbican/db barbican/service barbican/rabbit barbican/kek  # NB: rotating kek makes stored Barbican secrets unreadable
  rgw/service  # also stored in the Ceph configuration database: re-run rgw_single_node.scala after rotating
  manila/db manila/service manila/rabbit manila/ceph-key  # NB: rotating ceph-key needs manila_single_node.scala re-run (it re-imports client.manila)
  wordpress/db
  observability/mysqld-exporter observability/grafana-admin
  horizon/secret-key
)
CEPH_KEYS=(glance/ceph-key cinder/ceph-key)

YES=""
case "${1:-}" in
  --yes) YES=1 ;;
  "") ;;
  -h|--help) sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "unknown option: $1" >&2; exit 2 ;;
esac

# A cephx key: base64 of a 28-byte structure (type 1 = AES, creation time, a
# 16-byte secret), as `ceph-authtool --gen-print-key` makes. Starts with "AQ".
cephx_key() {
  python3 -c 'import base64, os, struct, time; print(base64.b64encode(struct.pack("<HIIH", 1, int(time.time()), 0, 16) + os.urandom(16)).decode())'
}

STORE="${ORPHERA_SECRETS:-$HOME/.config/orphera/secrets.age}"
[ -f "$STORE" ] || { echo "no secret store at $STORE (orphera secrets init)" >&2; exit 1; }

present() { orphera secrets get "$1" >/dev/null 2>&1; }

echo "store: $STORE"
for n in "${NAMES[@]}"; do
  if present "$n"; then echo "  remove  $n  (a new random value on first use)"; else echo "  absent  $n"; fi
done
for n in "${CEPH_KEYS[@]}"; do
  if present "$n"; then echo "  replace $n  (a new cephx key)"; else echo "  absent  $n"; fi
done

if [ -z "$YES" ]; then
  echo "dry run: nothing changed. Run with --yes, and read the header first: the running lab keeps its old values until it is rebuilt."
  exit 0
fi

BACKUP="$STORE.bak-$(date +%Y%m%d-%H%M%S)"
( umask 077; cp "$STORE" "$BACKUP" && chmod 600 "$BACKUP" ) || { echo "could not back up the store" >&2; exit 1; }
echo "backup: $BACKUP"

fail=0
for n in "${NAMES[@]}"; do
  present "$n" || continue
  if orphera secrets rm "$n" >/dev/null 2>&1; then echo "removed  $n"; else echo "FAILED to remove $n" >&2; fail=1; fi
done
for n in "${CEPH_KEYS[@]}"; do
  present "$n" || continue
  if cephx_key | orphera secrets set "$n" >/dev/null 2>&1; then echo "replaced $n"; else echo "FAILED to replace $n" >&2; fail=1; fi
done

[ "$fail" -eq 0 ] || { echo "some names failed; the backup is $BACKUP" >&2; exit 1; }
echo "done. The lab itself still has the old values: rebuild it next (see the header)."
