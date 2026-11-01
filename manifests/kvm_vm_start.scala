// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Starts a VM that already exists on its libvirt/KVM hypervisor — the
// companion to kvm_zfs_vm_provision.scala, and the way back up after
// `orphera shutdown` (or a hypervisor-side power-off) on a VM.
//
//   orphera cluster-playbook manifests/kvm_vm_start.scala --config config/tst0.yaml
//
// The SAME per-VM config file kvm_zfs_vm_provision.scala takes. Only three of its
// fields are used: `hypervisor` (which inventory node to run `virsh` on),
// `hostname` (the libvirt domain name) and `nic0.ipaddress` (what to probe for
// SSH once it is up). Unlike kvm_zfs_vm_provision.scala there are NO hardcoded
// fallbacks: --config is required, because "start some default VM on some
// default hypervisor" is never what anyone meant.
//
// Idempotent: running and reachable -> reported and left alone; paused ->
// resumed; shut off -> started; not defined on that hypervisor -> fails (this
// playbook never creates a VM — use kvm_zfs_vm_provision.scala for that). Never
// destroys or redefines anything.
//
// A failed start, resume or reset is tried again after 5 s, up to 3 more times
// (so up to 4 attempts); ORPHERA_VM_RETRIES=<n> changes that, 0 turns it off:
//   ORPHERA_VM_RETRIES=5 orphera cluster-playbook manifests/kvm_vm_start.scala --config config/tst0.yaml
// Each attempt looks at the VM's state again. A domain that is not defined is not retried.
//
// Before a VM is started, Open vSwitch ports named vnetN whose network device no longer exists
// are removed from the hypervisor's bridges. libvirt leaves them behind when a VM stops
// uncleanly, reuses the names, and then fails the start with "vnetN is actually attached to
// bridge ...". Only vnetN ports with no device are touched; hypervisors without Open vSwitch skip this.
//
// One deliberate exception to "leave a running VM alone": libvirt reports a
// guest that has HALTED (OS stopped, QEMU still alive) or hung as `running`,
// forever. So if the domain is `running` but the guest answers neither ping nor
// TCP 22 from the hypervisor for 24s, it is hard-reset (`virsh reset`, like the
// reset button) — found the hard way on tst0, which sat halted and "running"
// until reset by hand. A guest that is merely slow to boot is covered by the
// 24s window only if it was already running; a VM this playbook itself starts
// is never reset, it just gets the 180s SSH wait below.
//
// Needs the hypervisor to be an inventory node running the Orphera agent
// (same requirement as kvm_zfs_vm_provision.scala's stages). The guest itself does
// NOT need the agent: readiness is a plain SSH probe, as in provisioning.
object kvm_vm_start extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "kvm_vm_start requires --config <vm config.yaml> " +
            "(e.g. --config config/tst0.yaml)"
        )

  private val hypervisorNode = params.hypervisor
  private val vmName = params.hostname
  private val ipAddress = params.nic0.ipaddress

  // `$$` is a literal `$` in an s-interpolated string; the shell variables and
  // command substitutions below must reach `sh` untouched.
  // How many times a failed start, resume or reset is tried again after the
  // first failure (so 3 means up to 4 attempts, 5 s apart). Set with
  // ORPHERA_VM_RETRIES; 0 turns retrying off. A domain that is not defined on
  // the hypervisor is not retried: trying again cannot help.
  private val retries: Int =
    sys.env.get("ORPHERA_VM_RETRIES").map(_.trim).filter(_.nonEmpty) match
      case None    => 3
      case Some(s) =>
        s.toIntOption.filter(_ >= 0).getOrElse {
          throw new RuntimeException(
            s"ORPHERA_VM_RETRIES must be a number (0 or more), not '$s'"
          )
        }

  // `$$` is a literal `$` in an s-interpolated string; the shell variables and
  // command substitutions below must reach `sh` untouched.
  private val startVmScript =
    s"""reachable() { ping -c1 -W1 $ipAddress >/dev/null 2>&1 || timeout 3 bash -c 'echo > /dev/tcp/$ipAddress/22' >/dev/null 2>&1; }
       |prune_ovs() {
       |  command -v ovs-vsctl >/dev/null 2>&1 || return 0
       |  for b in $$(ovs-vsctl list-br 2>/dev/null); do
       |    for p in $$(ovs-vsctl list-ports "$$b" 2>/dev/null); do
       |      case "$$p" in vnet[0-9]*) ;; *) continue ;; esac
       |      [ -e "/sys/class/net/$$p" ] || { ovs-vsctl --if-exists del-port "$$b" "$$p" && echo "removed stale Open vSwitch port $$p from $$b"; }
       |    done
       |  done
       |}
       |attempt() {
       |  state=$$(virsh --connect qemu:///system domstate '$vmName' 2>/dev/null) || { echo "ERROR: domain '$vmName' is not defined on this hypervisor - provision it with kvm_zfs_vm_provision.scala" >&2; return 2; }
       |  case "$$state" in
       |    running)
       |      ok=0
       |      for i in 1 2 3 4 5 6 7 8; do
       |        if reachable; then ok=1; break; fi
       |        sleep 3
       |      done
       |      if [ "$$ok" = 1 ]; then
       |        echo "'$vmName' is already running and reachable"
       |      else
       |        echo "'$vmName' is marked running but answers neither ping nor ssh after 24s - hard-resetting it"
       |        virsh --connect qemu:///system reset '$vmName'
       |      fi ;;
       |    paused) virsh --connect qemu:///system resume '$vmName' && echo "'$vmName' resumed" ;;
       |    *) prune_ovs; virsh --connect qemu:///system start '$vmName' && echo "'$vmName' started (was: $$state)" ;;
       |  esac
       |}
       |n=0
       |while :; do
       |  attempt; rc=$$?
       |  [ "$$rc" -eq 0 ] && exit 0
       |  [ "$$rc" -eq 2 ] && exit 1
       |  if [ "$$n" -ge $retries ]; then echo "giving up on '$vmName' after $$((n + 1)) attempt(s)" >&2; exit 1; fi
       |  n=$$((n + 1))
       |  echo "attempt $$n failed (exit $$rc); retry $$n of $retries in 5s" >&2
       |  sleep 5
       |done""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-vm-start")(
      stage("start-vm", hypervisorNode)
        .task(s"start $vmName if it is not already running")(
          Task.RunCommand(
            List("sh", "-c", startVmScript),
            timeoutSeconds = 60 * (retries + 1) + 5 * retries
          )
        )
        .build,
      // HealthCheck.Ssh carries its own `host`, so this stage's node list
      // (the hypervisor) only decides where the check is driven from, same as
      // kvm_zfs_vm_provision.scala's confirm-vm-reachable. 180s covers a cold boot
      // (sshd up ~11s, pubkey auth working ~60s after boot on a real run).
      stage("confirm-vm-reachable", hypervisorNode)
        .waitFor(
          HealthCheck.Ssh(
            onNode = vmName,
            host = ipAddress,
            sshUser = "localadmin",
            sshKeyPath = None,
            remoteCommand = "true",
            pollIntervalSeconds = 10,
            timeoutSeconds = 180
          )
        )
        .task(s"$vmName is up")(
          Task.Debug(s"$vmName ($ipAddress) is running and answering SSH.")
        )
        .build
    )
