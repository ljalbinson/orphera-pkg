#!/bin/bash

#
# Create virtual machines
#   then install orphera agent
#
for n in {0..7}; do
    orphera cluster-playbook manifests/kvm_vm_provision.scala --config "config/tst${n}.yaml"
    orphera bootstrap --file orphera-agent_0.1.???_amd64.deb --ssh-user localadmin --nodes "tst${n}"
    orphera cluster-playbook manifests/gen_set_dns.scala --config "config/tst${n}.yaml"
    orphera dist-upgrade --nodes "tst${n}"
    orphera cluster-playbook manifests/gen_packages.scala --config "config/tst${n}.yaml"
done


