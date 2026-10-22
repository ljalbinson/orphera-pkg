#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

orphera cluster-playbook ceph-mon-keyring.yaml
orphera cluster-playbook ceph-mon-quorum.yaml
orphera cluster-playbook ceph-admin-keyring.yaml
orphera cluster-playbook ceph-health-fixes.
orphera cluster-playbook ceph-health-fixes.yaml
orphera cluster-playbook ceph-mgr.yaml
orphera cluster-playbook ceph-osd.yaml
ssh tst0 sudo ceph -s
