#!/bin/bash

cephadm shell -- ceph osd pool create testbench 128 128
cephadm shell -- rados bench -p testbench 10 write --no-cleanup
cephadm shell -- rados bench -p testbench 10 seq
cephadm shell -- rados bench -p testbench 10 rand
cephadm shell -- rados bench -p testbench 10 write -t 4 --run-name client1
cephadm shell -- rados -p testbench cleanup

# modprobe rbd
# rbd create image01 --size 1024 --pool testbench
# rbd map image01 --pool testbench --name client.admin
# mkfs.ext4 /dev/rbd0
# mkdir /mnt/ceph-block-device
# mount /dev/rbd0 /mnt/ceph-block-device
# rbd bench --io-type write image01 --pool=testbench
