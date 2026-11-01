// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Shuts down every running VM on one or more hypervisors, gracefully (virsh shutdown, an
// ACPI power-button press), and waits until they are off: the way to empty a hypervisor
// before maintenance on it. The hypervisors are inventory nodes running the Orphera agent;
// the VMs do not need theirs. The names of the VMs it shuts down are recorded on the
// hypervisor (/var/lib/orphera/shutdown-all.vms) for kvm_start_all.scala, which starts exactly those again.
//
//   ORPHERA_HYPERVISORS="gs2 st0" orphera cluster-playbook manifests/kvm_shutdown_all.scala
//   ORPHERA_HYPERVISORS=gs2 ORPHERA_VM_CONFIRM=1 orphera cluster-playbook manifests/kvm_shutdown_all.scala
//
//   ORPHERA_HYPERVISORS  required: the hypervisors, separated by spaces or commas. There is no
//                        default, so that nothing is shut down by leaving a name out.
//   ORPHERA_VM_CONFIRM   1 to shut the VMs down. Without it the playbook only lists the VMs it
//                        would shut down on each hypervisor.
//   ORPHERA_VM_WAIT      seconds to wait for the guests to shut down (default 120)
//   ORPHERA_VM_FORCE     1: power off (virsh destroy) any VM still running after that time;
//                        without it such a VM is reported and the stage fails
//   ORPHERA_VM_EXCLUDE   VM names to leave running, separated by spaces or commas
//
// The VM whose name is the host name of the machine running this playbook is always left
// running, so that the session that started the shutdown does not cut itself off. All the
// VMs of one hypervisor are asked at once, and the hypervisors run in parallel, so VMs that
// depend on each other (Galera, Ceph) go down together; galera_cold_start.scala brings Galera
// back. Only running VMs are touched. Not run where this was written; the first run is the
// first test.
object kvm_shutdown_all extends OrpheraClusterPlaybook:

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

  private val localHost: String =
    scala.util
      .Try(java.net.InetAddress.getLocalHost.getHostName)
      .toOption
      .map(_.takeWhile(_ != '.'))
      .getOrElse("")

  private val excluded: List[String] =
    (names("ORPHERA_VM_EXCLUDE") ++ List(localHost).filter(_.nonEmpty)).distinct

  require(
    excluded.forall(_.matches("[A-Za-z0-9._-]+")),
    s"unusual characters in ORPHERA_VM_EXCLUDE: ${excluded.mkString(" ")}"
  )

  private val confirm: Boolean =
    sys.env.get("ORPHERA_VM_CONFIRM").map(_.trim).contains("1")

  private val force: Boolean =
    sys.env.get("ORPHERA_VM_FORCE").map(_.trim).contains("1")

  private val waitSeconds: Int =
    sys.env.get("ORPHERA_VM_WAIT").map(_.trim).filter(_.nonEmpty) match
      case None    => 120
      case Some(s) =>
        s.toIntOption.filter(_ >= 0).getOrElse {
          throw new RuntimeException(
            s"ORPHERA_VM_WAIT must be a number of seconds, not '$s'"
          )
        }

  private val shutdownScript: String =
    """V="virsh --connect qemu:///system"
      |EXCLUDE=" @EXCLUDE@ "
      |todo=""
      |for d in $($V list --state-running --name 2>/dev/null); do
      |  case "$EXCLUDE" in *" $d "*) echo "leaving $d running (excluded)"; continue ;; esac
      |  todo="$todo $d"
      |done
      |if [ -z "$todo" ]; then echo "no running VMs to shut down on this hypervisor"; exit 0; fi
      |echo "running VMs:$todo"
      |if [ "@CONFIRM@" != "1" ]; then
      |  echo "dry run: nothing was shut down (set ORPHERA_VM_CONFIRM=1, or use vmctl.sh shutdown-all --yes)"
      |  exit 0
      |fi
      |# remember what is being shut down, for kvm_start_all.scala (a list from an earlier run is kept)
      |LIST=/var/lib/orphera/shutdown-all.vms
      |mkdir -p /var/lib/orphera
      |{ cat "$LIST" 2>/dev/null; for d in $todo; do echo "$d"; done; } | sort -u > "$LIST.new" && mv "$LIST.new" "$LIST"
      |for d in $todo; do
      |  $V shutdown "$d" >/dev/null 2>&1 || echo "the shutdown request for $d failed" >&2
      |done
      |still_on() {
      |  left=""
      |  for d in $todo; do
      |    st=$($V domstate "$d" 2>/dev/null | head -1)
      |    case "$st" in "shut off"|"") ;; *) left="$left $d" ;; esac
      |  done
      |}
      |i=0
      |while [ $i -lt @WAIT@ ]; do
      |  still_on
      |  if [ -z "$left" ]; then echo "all shut off after ${i}s:$todo"; exit 0; fi
      |  sleep 2; i=$((i + 2))
      |done
      |still_on
      |if [ -z "$left" ]; then echo "all shut off:$todo"; exit 0; fi
      |if [ @FORCE@ -eq 1 ]; then
      |  for d in $left; do $V destroy "$d" >/dev/null 2>&1 && echo "did not shut down in @WAIT@s; powered off $d"; done
      |  still_on
      |  if [ -z "$left" ]; then exit 0; fi
      |fi
      |echo "still running after @WAIT@s:$left (set ORPHERA_VM_FORCE=1 to power them off)" >&2
      |exit 1
      |""".stripMargin
      .replace("@EXCLUDE@", excluded.mkString(" "))
      .replace("@CONFIRM@", if confirm then "1" else "0")
      .replace("@WAIT@", waitSeconds.toString)
      .replace("@FORCE@", if force then "1" else "0")

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-shutdown-all")(
      stage("shutdown-vms", hypervisors*)
        .task("shut down the running VMs")(
          Task.RunCommand(
            List("sh", "-c", shutdownScript),
            timeoutSeconds = waitSeconds + 60
          )
        )
        .build
    )
