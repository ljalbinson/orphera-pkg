// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Installs the packages a per-VM config file asks for, on that VM:
//
//   orphera cluster-playbook manifests/vm_packages.scala --config config/tst0.yaml
//
// Reads `pre_basic_packages` and `basic_packages` from the same `params:` file
// kvm_vm_provision.scala / kvm_vm_start.scala take, and installs them in that
// order — `pre_basic_packages` first (the ones other setup depends on, e.g.
// openvswitch-switch, qemu-guest-agent), then `basic_packages`. Either list may
// be absent or empty and is then skipped; if both are, the playbook says so and
// does nothing. `apt-get update` runs once, with the first install.
//
// The target is the VM's `hostname` from the config, as an INVENTORY node: the
// Orphera agent must already be on it and the name must be in inventory.yaml
// (`orphera bootstrap --forget-host-key --nodes <vm>` first, then add it to
// inventory.yaml). That is the reason this is a separate playbook from
// kvm_vm_provision.scala, which cannot install anything on a VM that has no
// agent yet. --config is required; there are no hardcoded fallbacks.
object vm_packages extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "vm_packages requires --config <vm config.yaml> " +
            "(e.g. --config config/tst0.yaml)"
        )

  private val vmName = params.hostname

  // Blank entries (an empty `- ''`) and repeats are dropped; order is kept.
  private def clean(xs: List[String]): List[String] =
    xs.map(_.trim).filter(_.nonEmpty).distinct

  private val prePackages = clean(params.preBasicPackages)
  // A package listed in both is installed once, in the earlier stage.
  private val basicPackages =
    clean(params.basicPackages).filterNot(prePackages.contains)

  private val stages: List[Stage] =
    val pre =
      if prePackages.isEmpty then Nil
      else
        List(
          stage("install-pre-basic-packages", vmName)
            .task(s"install pre_basic_packages: ${prePackages.mkString(", ")}")(
              Task.Install(prePackages, updateCache = true)
            )
            .build
        )
    val basic =
      if basicPackages.isEmpty then Nil
      else
        List(
          stage("install-basic-packages", vmName)
            .task(s"install basic_packages: ${basicPackages.mkString(", ")}")(
              // update_cache only if the pre stage didn't already refresh it
              Task.Install(basicPackages, updateCache = prePackages.isEmpty)
            )
            .build
        )
    pre ++ basic ++ (
      if pre.isEmpty && basic.isEmpty then
        List(
          stage("nothing-to-install", vmName)
            .task("no pre_basic_packages or basic_packages in the config")(
              Task.Debug(
                s"$vmName: the config lists no pre_basic_packages or basic_packages; nothing to install."
              )
            )
            .build
        )
      else Nil
    )

  val playbook: ClusterPlaybook =
    clusterPlaybook("vm-packages")(stages*)
