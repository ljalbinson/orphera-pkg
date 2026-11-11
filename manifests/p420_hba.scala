// SPDX-License-Identifier: Apache-2.0

import io.circe.yaml.parser
import java.nio.file.{Files, Paths}
import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Makes every disk behind an HP Smart Array P420 / P420i controller visible to the host as a plain
// disk, as in a JBOD. Written for the DL380p Gen8; run on the server itself (it needs an agent there).
//
// How: the P420 can run in HBA mode (`ssacli ctrl slot=N modify hbamode=on`), where the controller passes the
// drives straight through and keeps no logical drives. That is the first choice. If the firmware does not accept
// it, the fallback is one single-drive RAID 0 logical drive per disk, which is the usual substitute (the host
// sees one disk per drive, but the controller still sits in between, with its own metadata on the disk).
//
// THIS DESTROYS DATA. Deleting the logical drives wipes every disk on the controller. So, by default, the
// playbook only reports: the controller, its drives and logical drives. It changes something only when you run
// it with P420_ERASE=yes. It also refuses to change a controller whose logical drives hold a mounted file system
// or swap (the OS disk!) unless P420_ALLOW_MOUNTED=yes.
//
// Use (from scala0, in orphera-pkg). The target is named in a config file, because there is no default node:
//   cat > /tmp/p420.yaml <<'CFG'
//   nodes: [dl380p]
//   CFG
//   orphera cluster-playbook p420_hba.scala --config /tmp/p420.yaml                       # report only
//   P420_ERASE=yes orphera cluster-playbook p420_hba.scala --config /tmp/p420.yaml        # change it
// Optional: P420_MODE=auto (default: HBA mode, else RAID 0 per disk) | hba (HBA mode only) | raid0.
//
// HBA mode takes effect after a REBOOT; the playbook says so and does not reboot. Run it again after the
// reboot to see the disks as the controller now presents them. Notes:
//   - In HBA mode the controller offers no boot volume of its own. Boot the OS from a disk on another
//     controller, or from the network (as Ironic does), not from a P420 drive.
//   - The playbook installs `ssacli` if it is missing: from HPE's package repository
//     (downloads.linux.hpe.com/SDR/repo/mcp, suite <ubuntu codename>/current) or, if you put a .deb at
//     /var/lib/orphera/ssacli/ on the node, from that file. Whether HPE publishes a suite for your Ubuntu release
//     is not checked here; the .deb is the way round it.
//   - Safe to re-run: with the controller already in HBA mode (or already one RAID 0 per disk) nothing changes.
//
// Not compiled where this was written; the output format of ssacli differs between versions, so the first run
// on the machine (report only) is the check that it recognises the controller.
object p420_hba extends OrpheraClusterPlaybook:

  private val erase = sys.env.get("P420_ERASE").contains("yes")
  private val allowMounted = sys.env.get("P420_ALLOW_MOUNTED").contains("yes")
  private val mode = sys.env.getOrElse("P420_MODE", "auto")
  require(
    Set("auto", "hba", "raid0").contains(mode),
    s"P420_MODE must be auto, hba or raid0, not $mode"
  )

  // The nodes come from the `nodes:` list of --config; there is no default, because the playbook can wipe disks.
  private val nodes: List[String] =
    sys.env.get("ORPHERA_CONFIG") match
      case None =>
        throw new IllegalArgumentException(
          "p420_hba.scala needs --config FILE with a `nodes: [name]` list; it has no default node"
        )
      case Some(path) =>
        val listed =
          for
            text <- scala.util
              .Try(Files.readString(Paths.get(path)))
              .toEither
              .left
              .map(e => s"cannot read $path: ${e.getMessage}")
            json <- parser
              .parse(text)
              .left
              .map(e => s"$path is not valid YAML: ${e.getMessage}")
            found <- json.hcursor
              .get[List[String]]("nodes")
              .left
              .map(e => s"$path: ${e.getMessage}")
          yield found
        listed match
          case Left(message) => throw new IllegalArgumentException(message)
          case Right(list)
              if list.isEmpty || list.exists(!_.matches("^[A-Za-z0-9.-]+$")) =>
            throw new IllegalArgumentException(
              s"$path: `nodes` must be a non-empty list of host names"
            )
          case Right(list) => list

  private val waitAptIdleScript =
    """for i in $(seq 1 180); do
      |  if pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null || pgrep -x apt-get >/dev/null || pgrep -x apt >/dev/null || pgrep -x dpkg >/dev/null; then
      |    sleep 5
      |  else
      |    echo "apt and dpkg are idle"
      |    exit 0
      |  fi
      |done
      |echo "apt/dpkg still busy after 15 minutes" >&2
      |exit 1""".stripMargin

  private val installScript =
    """if command -v ssacli >/dev/null 2>&1; then
      |  echo "ssacli is installed: $(ssacli version 2>/dev/null | head -n 1)"
      |  exit 0
      |fi
      |export DEBIAN_FRONTEND=noninteractive
      |DEB=$(ls /var/lib/orphera/ssacli/*.deb 2>/dev/null | head -n 1)
      |if [ -n "$DEB" ]; then
      |  apt-get install -y "$DEB" || exit 1
      |else
      |  CODENAME=$(. /etc/os-release; echo "$VERSION_CODENAME")
      |  apt-get install -y curl gnupg ca-certificates || exit 1
      |  if ! curl -fsSL https://downloads.linux.hpe.com/SDR/hpePublicKey2048_key1.pub | gpg --dearmor --yes -o /usr/share/keyrings/hpe-mcp.gpg; then
      |    echo "cannot fetch the HPE repository key - put an ssacli .deb in /var/lib/orphera/ssacli/ on this node and run again" >&2
      |    exit 1
      |  fi
      |  echo "deb [signed-by=/usr/share/keyrings/hpe-mcp.gpg] https://downloads.linux.hpe.com/SDR/repo/mcp $CODENAME/current non-free" > /etc/apt/sources.list.d/hpe-mcp.list
      |  if ! apt-get update || ! apt-get install -y ssacli; then
      |    rm -f /etc/apt/sources.list.d/hpe-mcp.list
      |    apt-get update >/dev/null 2>&1 || true
      |    echo "ssacli could not be installed from the HPE repository for $CODENAME - put an ssacli .deb in /var/lib/orphera/ssacli/ on this node and run again" >&2
      |    exit 1
      |  fi
      |fi
      |command -v ssacli >/dev/null 2>&1 || { echo "ssacli is still missing" >&2; exit 1; }
      |echo "ssacli installed: $(ssacli version 2>/dev/null | head -n 1)"
      |""".stripMargin

  private val mainScript: String =
    """ERASE=@@ERASE@@
      |MODE=@@MODE@@
      |ALLOWMOUNTED=@@ALLOWMOUNTED@@
      |RC=0
      |REBOOT=0
      |
      |hba_on() {
      |  v=$(ssacli ctrl slot=$1 show detail 2>/dev/null | grep -iE 'hba mode|controller mode' | head -n 1 | sed 's/^[^:]*:[ ]*//' | tr 'A-Z' 'a-z')
      |  case "$v" in
      |    true|yes|on|enabled|hba*) return 0 ;;
      |  esac
      |  return 1
      |}
      |
      |# Fails if a logical drive of this controller carries a mounted file system or swap.
      |guard() {
      |  DEVS=$(ssacli ctrl slot=$1 ld all show detail 2>/dev/null | sed -n 's|^ *Disk Name: *\(/dev/[^ ]*\).*|\1|p')
      |  for d in $DEVS; do
      |    if lsblk -nr -o MOUNTPOINT "$d" 2>/dev/null | grep -q .; then
      |      echo "slot $1: $d is in use (mounted or swap) - it may be the OS disk; nothing was changed. P420_ALLOW_MOUNTED=yes overrides this" >&2
      |      return 1
      |    fi
      |  done
      |  return 0
      |}
      |
      |SLOTS=$(ssacli ctrl all show 2>/dev/null | sed -n '/P420/ s/.* in Slot \([0-9][0-9]*\).*/\1/p')
      |if [ -z "$SLOTS" ]; then
      |  echo "no Smart Array P420 found; ssacli ctrl all show says:" >&2
      |  ssacli ctrl all show >&2 || true
      |  exit 1
      |fi
      |
      |for S in $SLOTS; do
      |  echo "== controller in slot $S =="
      |  ssacli ctrl slot=$S show detail | grep -iE 'model|firmware version|hba mode|controller mode|cache' || true
      |  ssacli ctrl slot=$S pd all show || true
      |  ssacli ctrl slot=$S ld all show || true
      |  if hba_on $S; then echo "slot $S: HBA mode is on"; else echo "slot $S: HBA mode is off or not reported"; fi
      |  if [ "$ERASE" != "yes" ]; then
      |    echo "slot $S: report only (P420_ERASE=yes changes the controller and DESTROYS the data on its disks)"
      |    continue
      |  fi
      |
      |  if [ "$MODE" != "raid0" ]; then
      |    if hba_on $S; then
      |      echo "slot $S: already in HBA mode, nothing to do"
      |      continue
      |    fi
      |    if [ "$ALLOWMOUNTED" != "yes" ]; then guard $S || { RC=1; continue; }; fi
      |    ssacli ctrl slot=$S ld all delete forced || true
      |    if ssacli ctrl slot=$S modify hbamode=on forced; then
      |      echo "slot $S: HBA mode set; REBOOT the server for the disks to appear"
      |      REBOOT=1
      |      continue
      |    fi
      |    if [ "$MODE" = "hba" ]; then
      |      echo "slot $S: the controller did not accept hbamode=on (P420_MODE=hba)" >&2
      |      RC=1
      |      continue
      |    fi
      |    echo "slot $S: HBA mode is not available; using one RAID 0 logical drive per disk"
      |  fi
      |
      |  NPD=$(ssacli ctrl slot=$S pd all show | grep -c '^ *physicaldrive')
      |  LDN=$(ssacli ctrl slot=$S ld all show detail 2>/dev/null | grep -c 'Logical Drive:')
      |  FT0=$(ssacli ctrl slot=$S ld all show detail 2>/dev/null | grep -ciE 'Fault Tolerance: *0 *$')
      |  if [ "$NPD" -gt 0 ] && [ "$LDN" = "$NPD" ] && [ "$FT0" = "$LDN" ]; then
      |    echo "slot $S: already one RAID 0 logical drive per disk, nothing to do"
      |    continue
      |  fi
      |  if [ "$ALLOWMOUNTED" != "yes" ]; then guard $S || { RC=1; continue; }; fi
      |  ssacli ctrl slot=$S ld all delete forced || true
      |  for p in $(ssacli ctrl slot=$S pd allunassigned show 2>/dev/null | sed -n 's/^ *physicaldrive \([^ ]*\).*/\1/p'); do
      |    if ssacli ctrl slot=$S create type=ld drives=$p raid=0 forced; then echo "slot $S: logical drive made for $p"; else echo "slot $S: logical drive for $p failed" >&2; RC=1; fi
      |  done
      |done
      |
      |if [ "$ERASE" = "yes" ]; then
      |  for S in $SLOTS; do
      |    echo "== after: controller in slot $S =="
      |    ssacli ctrl slot=$S pd all show || true
      |    ssacli ctrl slot=$S ld all show || true
      |  done
      |  lsblk -d -o NAME,SIZE,MODEL,TYPE
      |fi
      |if [ "$REBOOT" = "1" ]; then echo "REBOOT REQUIRED: the HBA mode change takes effect at the next boot; run this playbook again afterwards to check"; fi
      |exit $RC
      |""".stripMargin

  private val script = mainScript
    .replace("@@ERASE@@", if erase then "yes" else "no")
    .replace("@@MODE@@", mode)
    .replace("@@ALLOWMOUNTED@@", if allowMounted then "yes" else "no")

  val playbook: ClusterPlaybook =
    clusterPlaybook("p420-hba")(
      stage("p420-hba", nodes*)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(List("sh", "-c", waitAptIdleScript), timeoutSeconds = 960)
        )
        .task("install ssacli if it is missing")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 600)
        )
        .task(
          if erase then "put the P420 disks in HBA mode (or RAID 0 per disk)"
          else "report the P420 controller (no change)"
        )(
          Task.RunCommand(List("sh", "-c", script), timeoutSeconds = 600)
        )
        .build
    )
