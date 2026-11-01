// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// The counterpart of kvm_shutdown_all.scala: starts the VMs that it shut down on one or more
// hypervisors, once maintenance on them is finished. It starts exactly the VMs recorded in
// /var/lib/orphera/shutdown-all.vms on each hypervisor, so a VM that was off before stays off,
// and removes each from the record as it is running. With ORPHERA_VM_ALL=1 it starts every
// VM that is defined on the hypervisor and shut off instead.
//
//   ORPHERA_HYPERVISORS="gs2 st0" orphera cluster-playbook manifests/kvm_start_all.scala
//   ORPHERA_HYPERVISORS=gs2 ORPHERA_VM_DRY_RUN=1 orphera cluster-playbook manifests/kvm_start_all.scala
//
//   ORPHERA_HYPERVISORS  required: the hypervisors, separated by spaces or commas
//   ORPHERA_VM_ALL       1: every shut-off VM on the hypervisor, not only the recorded ones
//   ORPHERA_VM_DRY_RUN   1: only list the VMs that would be started
//   ORPHERA_VM_RETRIES   how many times a failed start is tried again, 5 s apart (default 3)
//   ORPHERA_VM_EXCLUDE   VM names to leave alone, separated by spaces or commas
//
// Before each start, stale Open vSwitch vnetN ports are removed (see kvm_vm_start.scala). A VM
// that is already running counts as started; one that is not defined on the hypervisor is
// dropped from the record with a warning. The VMs are started one after another and the
// playbook does not wait for the guests to boot: `orphera uptime --nodes <vm>` shows when one
// has, and kvm_vm_start.scala waits for SSH on a single VM. VMs that depend on each other
// (Ceph before OpenStack, Galera, see galera_cold_start.scala) are not ordered across
// hypervisors; start the hypervisors in the order you need, in separate runs. A failed start
// fails the stage and leaves the VM in the record. Not run where this was written; the first
// run is the first test.
object kvm_start_all extends OrpheraClusterPlaybook:

  private def names(variable: String): List[String] =
    sys.env
      .get(variable)
      .map(_.split("[ ,]+").toList.map(_.trim).filter(_.nonEmpty))
      .getOrElse(Nil)

  private val hypervisors: List[String] = names("ORPHERA_HYPERVISORS").distinct

  require(
    hypervisors.nonEmpty,
    "ORPHERA_HYPERVISORS must name the hypervisors, e.g. ORPHERA_HYPERVISORS=\"gs2 st0\""
  )
  require(
    hypervisors.forall(_.matches("[A-Za-z0-9._-]+")),
    s"unusual characters in ORPHERA_HYPERVISORS: ${hypervisors.mkString(" ")}"
  )

  private val excluded: List[String] = names("ORPHERA_VM_EXCLUDE").distinct

  require(
    excluded.forall(_.matches("[A-Za-z0-9._-]+")),
    s"unusual characters in ORPHERA_VM_EXCLUDE: ${excluded.mkString(" ")}"
  )

  private def flag(variable: String): Boolean =
    sys.env.get(variable).map(_.trim).contains("1")

  private val all: Boolean = flag("ORPHERA_VM_ALL")
  private val dryRun: Boolean = flag("ORPHERA_VM_DRY_RUN")

  private val retries: Int =
    sys.env.get("ORPHERA_VM_RETRIES").map(_.trim).filter(_.nonEmpty) match
      case None    => 3
      case Some(s) =>
        s.toIntOption.filter(_ >= 0).getOrElse {
          throw new RuntimeException(
            s"ORPHERA_VM_RETRIES must be a number (0 or more), not '$s'"
          )
        }

  private val startScript: String =
    """V="virsh --connect qemu:///system"
      |LIST=/var/lib/orphera/shutdown-all.vms
      |EXCLUDE=" @EXCLUDE@ "
      |prune_ovs() {
      |  command -v ovs-vsctl >/dev/null 2>&1 || return 0
      |  for b in $(ovs-vsctl list-br 2>/dev/null); do
      |    for p in $(ovs-vsctl list-ports "$b" 2>/dev/null); do
      |      case "$p" in vnet[0-9]*) ;; *) continue ;; esac
      |      [ -e "/sys/class/net/$p" ] || { ovs-vsctl --if-exists del-port "$b" "$p" && echo "removed stale Open vSwitch port $p from $b"; }
      |    done
      |  done
      |}
      |start_one() {
      |  n=0
      |  while :; do
      |    out=$($V start "$1" 2>&1) && return 0
      |    if [ $n -ge @RETRIES@ ]; then echo "$out" >&2; return 1; fi
      |    n=$((n + 1))
      |    echo "start of $1 failed, retry $n of @RETRIES@ in 5s" >&2
      |    sleep 5
      |    prune_ovs
      |  done
      |}
      |if [ "@ALL@" = "1" ]; then
      |  todo=$($V list --inactive --name 2>/dev/null | tr -s '\n ' ' ')
      |else
      |  todo=$(cat "$LIST" 2>/dev/null | tr -s '\n ' ' ')
      |  if [ -z "$todo" ]; then echo "nothing recorded: kvm_shutdown_all.scala has not shut anything down here (ORPHERA_VM_ALL=1 starts every shut-off VM)"; exit 0; fi
      |fi
      |if [ -z "$todo" ]; then echo "no shut-off VMs here"; exit 0; fi
      |echo "VMs to start:$todo"
      |if [ "@DRY@" = "1" ]; then echo "dry run: nothing was started"; exit 0; fi
      |keep=""; failed=""
      |for d in $todo; do
      |  case "$EXCLUDE" in *" $d "*) echo "leaving $d alone (excluded)"; keep="$keep $d"; continue ;; esac
      |  st=$($V domstate "$d" 2>/dev/null | head -1)
      |  case "$st" in
      |    "")        echo "$d is not defined on this hypervisor; dropping it from the record" ;;
      |    running)   echo "$d is already running" ;;
      |    "shut off")
      |      prune_ovs
      |      if start_one "$d"; then echo "started $d"; else echo "could not start $d" >&2; failed="$failed $d"; fi ;;
      |    *)         echo "$d is $st; not touching it"; keep="$keep $d" ;;
      |  esac
      |done
      |if [ "@ALL@" != "1" ]; then
      |  rest="$keep$failed"
      |  if [ -z "$rest" ]; then rm -f "$LIST"; else for d in $rest; do echo "$d"; done | sort -u > "$LIST"; fi
      |fi
      |if [ -n "$failed" ]; then echo "failed to start:$failed" >&2; exit 1; fi
      |echo "done"
      |""".stripMargin
      .replace("@EXCLUDE@", excluded.mkString(" "))
      .replace("@RETRIES@", retries.toString)
      .replace("@ALL@", if all then "1" else "0")
      .replace("@DRY@", if dryRun then "1" else "0")

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-start-all")(
      stage("start-vms", hypervisors*)
        .task("start the VMs")(
          Task.RunCommand(
            List("sh", "-c", startScript),
            timeoutSeconds = 600 + 70 * retries
          )
        )
        .build
    )
