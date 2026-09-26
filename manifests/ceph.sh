#!/bin/bash

orphera cluster-playbook manifests/ceph-mon-keyring.yaml
orphera cluster-playbook manifests/ceph-mon-quorum.yaml
orphera cluster-playbook manifests/ceph-admin-keyring.yaml
orphera cluster-playbook manifests/ceph-health-fixes.
orphera cluster-playbook manifests/ceph-health-fixes.yaml
orphera cluster-playbook manifests/ceph-mgr.yaml
orphera cluster-playbook manifests/ceph-osd.yaml
ssh tst0 sudo ceph -s
