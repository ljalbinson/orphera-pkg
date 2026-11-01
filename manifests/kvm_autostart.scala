// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Turns libvirt's autostart (start the VM when the hypervisor boots) on or off for named VMs, or
// for every VM, on one or more hypervisors. The hypervisors are inventory nodes running the
// Orphera agent. Idempotent: a VM that is already as asked is reported and left alone; a VM that
// is not defined on the hypervisor, or is transient, fails the stage. Running state is not
// changed. utils/list-vms.sh (vmctl.sh list) shows the AUTOSTART column.
//
//   ORPHERA_AUTOSTART=on ORPHERA_HYPERVISORS=gs2 ORPHERA_VM_NAMES="tst41 tst23" \
//       orphera cluster-playbook manifests/kvm_autostart.scala
//   ORPHERA_AUTOSTART=off ORPHERA_HYPERVISORS="gs1 gs2 gs3 st0" orphera cluster-playbook manifests/kvm_autostart.scala
//
//   ORPHERA_AUTOSTART   required: on or off
//   ORPHERA_HYPERVISORS required: the hypervisors, separated by spaces or commas
//   ORPHERA_VM_NAMES    the libvirt domain names, separated by spaces or commas; they must all exist on
//                       every hypervisor named. Empty: every VM defined on each hypervisor.
//
// Not run where this was written; the first run is the first test.
object kvm_autostart extends OrpheraClusterPlaybook:

  private def names(variable: String): List[String] =
    sys.env
      .get(variable)
      .map(_.split("[ ,]+").toList.map(_.trim).filter(_.nonEmpty))
      .getOrElse(Nil)

  private val mode: String =
    sys.env.get("ORPHERA_AUTOSTART").map(_.trim).getOrElse("")

  require(
    mode == "on" || mode == "off",
    s"ORPHERA_AUTOSTART must be on or off, not '$mode'"
  )

  private val hypervisors: List[String] = names("ORPHERA_HYPERVISORS").distinct

  require(
    hypervisors.nonEmpty,
    "ORPHERA_HYPERVISORS must name the hypervisors, e.g. ORPHERA_HYPERVISORS=\"gs2 st0\""
  )
  require(
    hypervisors.forall(_.matches("[A-Za-z0-9._-]+")),
    s"unusual characters in ORPHERA_HYPERVISORS: ${hypervisors.mkString(" ")}"
  )

  private val vms: List[String] = names("ORPHERA_VM_NAMES").distinct

  require(
    vms.forall(_.matches("[A-Za-z0-9._-]+")),
    s"unusual characters in ORPHERA_VM_NAMES: ${vms.mkString(" ")}"
  )

  private val autostartScript: String =
    """V="virsh --connect qemu:///system"
      |names="@NAMES@"
      |if [ -z "$names" ]; then names=$($V list --all --name 2>/dev/null | tr -s '\n ' ' '); fi
      |if [ -z "$names" ]; then echo "no VMs defined on this hypervisor"; exit 0; fi
      |if [ "@MODE@" = "on" ]; then want=enable; else want=disable; fi
      |failed=""
      |for d in $names; do
      |  cur=$($V dominfo "$d" 2>/dev/null | awk '/^Autostart:/ {print $2}')
      |  if [ -z "$cur" ]; then echo "$d is not defined on this hypervisor" >&2; failed="$failed $d"; continue; fi
      |  if [ "$cur" = "$want" ]; then echo "$d: autostart is already $cur"; continue; fi
      |  if [ "$want" = "enable" ]; then out=$($V autostart "$d" 2>&1); else out=$($V autostart --disable "$d" 2>&1); fi
      |  if [ $? -eq 0 ]; then echo "$d: autostart $want"; else echo "$d: $out" >&2; failed="$failed $d"; fi
      |done
      |if [ -n "$failed" ]; then echo "failed:$failed" >&2; exit 1; fi
      |""".stripMargin
      .replace("@NAMES@", vms.mkString(" "))
      .replace("@MODE@", mode)

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-autostart")(
      stage("autostart", hypervisors*)
        .task(s"turn autostart $mode")(
          Task.RunCommand(
            List("sh", "-c", autostartScript),
            timeoutSeconds = 120
          )
        )
        .build
    )
