// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Package update + dist-upgrade (apt or dnf, chosen by the agent) on the VM described by a per-VM config:
//
//   orphera cluster-playbook manifests/gen_dist_upgrade.scala --config config/tst0.yaml
//
// The target is the VM's `hostname` from the config, as an INVENTORY node, so
// the VM needs the Orphera agent and an inventory.yaml entry first
// (`orphera bootstrap ...`, or the `@bootstrap` step of a cluster-playbook
// chain). Same rules as `orphera dist-upgrade`: existing config files are
// kept, nothing prompts. --config is required; there are no hardcoded
// fallbacks. Typical chain, for several VMs at once:
//
//   orphera cluster-playbook manifests/kvm_vm_provision.scala @bootstrap \
//     manifests/gen_set_dns.scala manifests/gen_dist_upgrade.scala \
//     manifests/gen_packages.scala --ssh-user localadmin --config config/tst*.yaml
object gen_dist_upgrade extends OrpheraClusterPlaybook:

  private val vmName: String =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p.hostname
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "gen_dist_upgrade requires --config <vm config.yaml> " +
            "(e.g. --config config/tst0.yaml)"
        )

  val playbook: ClusterPlaybook =
    clusterPlaybook("gen-dist-upgrade")(
      stage("dist-upgrade", vmName)
        .task(s"update all packages (dist-upgrade) on $vmName")(
          Task.DistUpgrade(updateCache = true)
        )
        .build
    )
