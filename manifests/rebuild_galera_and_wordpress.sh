#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
#
# DESTRUCTIVE: tears down the Galera cluster (every database on it, including
# Keystone's and Cinder's, is lost) and rebuilds Galera, the VIP and WordPress.
# This is a rebuild chain, not a test. After it, re-run manifests/test_keystone.sh,
# manifests/test_cinder.sh and utils/openstack-client-setup.sh.
# For a non-destructive WordPress check use manifests/test_wordpress.sh.

orphera cluster-playbook mariadb_galera_teardown.scala
orphera cluster-playbook mariadb_galera_cluster.scala
orphera cluster-playbook mariadb_galera_rolling_restart.scala
orphera cluster-playbook mariadb_haproxy_keepalived.scala
orphera cluster-playbook wordpress_site.scala


