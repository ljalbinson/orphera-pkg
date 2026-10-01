#!/bin/bash

orphera cluster-playbook manifests/mariadb_galera_teardown.scala
orphera cluster-playbook manifests/mariadb_galera_cluster.scala
orphera cluster-playbook manifests/mariadb_galera_rolling_restart.scala
orphera cluster-playbook manifests/mariadb_haproxy_keepalived.scala
orphera cluster-playbook manifests/wordpress_site.scala


