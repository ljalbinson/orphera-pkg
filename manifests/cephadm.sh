#!/bin/bash

orphera bootstrap orphera-agent_0.*.deb --ssh-user localadmin
orphera reboot --wait
orphera version
orphera cluster-playbook manifests/cephadm_install.scala
orphera cluster-playbook manifests/cephadm_add_mons.scala
orphera cluster-playbook manifests/cephadm_add_osds.scala
ssh tst0 sudo cephadm shell -- ceph -s
