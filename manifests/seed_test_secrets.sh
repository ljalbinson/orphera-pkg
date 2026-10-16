#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# TEST LAB ONLY. Puts the passwords that the lab's OpenStack services were first
# built with into the encrypted secret store, so a playbook that now reads its
# passwords from the store (`Secrets.get("placement/db")`) agrees with the
# databases and Keystone users that already exist. A new deployment does not need
# this: leave the names unset and Orphera makes a random value on first use.
#
#   orphera secrets init          # once
#   manifests/seed_test_secrets.sh
#
# A name that already has a value is left alone, so this is safe to run again.
set -euo pipefail

seed() {
  local name="$1" value="$2"
  if orphera secrets get "$name" >/dev/null 2>&1; then
    echo "kept   $name"
  else
    printf '%s\n' "$value" | orphera secrets set "$name" >/dev/null
    echo "seeded $name"
  fi
}

seed galera/sst            orphera-test-sst-password
seed galera/clustercheck    orphera-test-clustercheck-password
seed keystone/admin        orphera-test-keystone-admin-password
seed keystone/db           orphera-test-keystone-db-password
seed placement/db          orphera-test-placement-db-password
seed placement/service     orphera-test-placement-service-password
seed glance/db             orphera-test-glance-db-password
seed glance/service        orphera-test-glance-service-password
seed glance/ceph-key       'AQDNtctqAAAAABAA2XKzaceaWSLHtjaNxQ4qeg=='
seed cinder/db             orphera-test-cinder-db-password
seed cinder/service        orphera-test-cinder-service-password
seed cinder/rabbit         orphera-test-rabbit-password
seed cinder/ceph-key       'AQAAeOdoAAAAABAAZtOwIBqwSdv7t+vxPfUfpQ=='
seed nova/db               orphera-test-nova-db-password
seed nova/service          orphera-test-nova-service-password
seed nova/rabbit           orphera-test-nova-rabbit-password
seed neutron/db            orphera-test-neutron-db-password
seed neutron/service       orphera-test-neutron-service-password
seed neutron/rabbit        orphera-test-neutron-rabbit-password
seed wordpress/db           orphera-test-wordpress-password
seed observability/mysqld-exporter orphera-test-mysqld-exporter-password
seed horizon/secret-key    orphera-test-horizon-secret-key-not-for-real-use
