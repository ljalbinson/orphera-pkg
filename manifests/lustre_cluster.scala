// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// A Lustre file system (ldiskfs backend, LNet over TCP) on Rocky Linux 9.
//
// Layout (inventory.yaml, var `lustre_targets` on each server):
//   tst43        MGS + MDS: MGT on data1, MDT0 on data2; also the test client
//   tst44-tst47  OSS: two OSTs each (OST0000-OST0007)
//   tst48        MDS: MDT1, the second metadata target (DNE)
// The client mount is /mnt/<fsname> on the client nodes (default tst43 and
// tst50). A client that is not a server (tst50, Rocky 9, no targets) gets the
// Lustre client packages instead of the server ones, and the same kernel,
// reboot and LNet stages. Ubuntu clients: lustre_client.scala.
//
// Run:   orphera cluster-playbook manifests/lustre_cluster.scala
//
// Stages, in order:
//   preflight        every server: EL9, its target disks exist, the Lustre and
//                    e2fsprogs repositories answer. Nothing is changed.
//   preflight-clients  every client that is not a server: EL9, the Lustre
//                    client repository answers. Nothing is changed.
//   packages         repository file, the Lustre server packages and, when the
//                    Lustre repository has one, its own kernel; SELinux set to
//                    permissive (Lustre servers are not run enforcing)
//   packages-clients the client repository, the kernel kmod-lustre-client is built
//                    for (from the Rocky vault if it has left the current
//                    repositories), lustre-client and kmod-lustre-client
//                    (SELinux left as it is: the client supports enforcing)
//   boot-kernel      makes the newest installed kernel that has the Lustre
//                    modules the default (when none has, installs the kernel the
//                    modules were built for, from the Rocky vault if it is no
//                    longer in the current repositories), and locks the kernel and Lustre packages with dnf
//                    versionlock so a later `dnf upgrade` cannot move them apart
//   reboot           only on the nodes not already running that kernel
//   lnet             LNet on tcp0, on the interface that holds cluster_ip; port
//                    988/tcp opened when firewalld is running
//   lnet-ping        every node reaches the MGS over LNet
//   format           mkfs.lustre of each target that is still blank (see below)
//   mount-mgs-mdt0   the MGT, then MDT0
//   mount-mdt        the other MDTs (MDT0 first, so they register behind it)
//   mount-ost        the OSTs
//   mount-client     the client mount
//   verify           the client sees every MDT and OST in the inventory
//
// Repeatable. A target whose disk already carries the expected Lustre label is
// left alone; a disk with any other signature stops the run instead of being
// formatted (lustre_teardown.scala wipes the listed disks). A mounted target is
// left mounted. Nothing goes into /etc/fstab: after the VMs have been off,
// running this playbook again mounts everything in the right order (MGS, MDTs,
// OSTs, clients), which a boot-time mount on each node cannot guarantee.
//
// Settings (environment variables):
//   LUSTRE_FSNAME    file system name, at most 8 characters      default lfs0
//   LUSTRE_MGS       the node with the MGT                        default tst43
//   LUSTRE_SERVERS   every node with targets (space separated)    default tst43-tst48
//   LUSTRE_CLIENTS   nodes that mount the file system: servers, or Rocky 9
//                    nodes with no targets                       default tst43 tst50
//   LUSTRE_RELEASE   directory under .../public/lustre/           default lustre-2.17.0
//   LUSTRE_EL        EL build directory of that release            default el9.7
//
// Needs outbound HTTPS from the servers to downloads.whamcloud.com (the rpm
// cache on tst12 mirrors only Rocky and EPEL). Whamcloud's packages are not
// GPG-signed, so the repository file has gpgcheck=0; the download is over HTTPS.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object lustre_cluster extends OrpheraClusterPlaybook:

  private def envList(name: String, default: String): List[String] =
    sys.env
      .getOrElse(name, default)
      .split("[ ,]+")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  private val fsName = sys.env.getOrElse("LUSTRE_FSNAME", "lfs0")
  private val mgs = sys.env.getOrElse("LUSTRE_MGS", "tst43")
  private val servers =
    envList("LUSTRE_SERVERS", "tst43 tst44 tst45 tst46 tst47 tst48")
  private val clients = envList("LUSTRE_CLIENTS", "tst43 tst50")
  private val release = sys.env.getOrElse("LUSTRE_RELEASE", "lustre-2.17.0")
  private val el = sys.env.getOrElse("LUSTRE_EL", "el9.7")

  require(
    fsName.matches("[A-Za-z0-9_]{1,8}"),
    s"LUSTRE_FSNAME '$fsName': 1-8 letters, digits or _"
  )
  require(servers.contains(mgs), s"LUSTRE_MGS $mgs is not in LUSTRE_SERVERS")
  require(clients.nonEmpty, "LUSTRE_CLIENTS is empty")

  // Clients that are not servers: they get the client packages.
  private val clientOnly = clients.filterNot(servers.contains)
  // Every node that needs the Lustre kernel, a reboot check and LNet.
  private val allNodes = servers ++ clientOnly

  private val serverRepo =
    s"https://downloads.whamcloud.com/public/lustre/$release/$el/server/"
  private val clientRepo =
    s"https://downloads.whamcloud.com/public/lustre/$release/$el/client/"
  private val e2fsRepo =
    "https://downloads.whamcloud.com/public/e2fsprogs/latest/el9/"

  // The MGS NID, rendered by the orchestrator. tcp0 is written `@tcp`.
  private val mgsNid = s"{{{nodes.$mgs.cluster_ip}}}@tcp"

  // Every server's lustre_targets joined, for counting the expected MDTs and OSTs.
  private val allTargets =
    servers.map(n => s"{{{nodes.$n.lustre_targets}}}").mkString(",")

  private def fill(script: String): String =
    script
      .replace("@FSNAME@", fsName)
      .replace("@MGSNID@", mgsNid)
      .replace("@SERVER_REPO@", serverRepo)
      .replace("@CLIENT_REPO@", clientRepo)
      .replace("@E2FS_REPO@", e2fsRepo)
      .replace("@RELEASE@", release)
      .replace("@EL@", el)
      .replace("@ALL_TARGETS@", allTargets)

  // Shared shell helpers. `targets` prints this node's lustre_targets one per
  // line as "<role> <disk>".
  private val helpers =
    """fail() { echo "FAILED: $(hostname -s): $*"; exit 1; }
      |targets() { printf '%s\n' '{{{lustre_targets}}}' | tr ',' '\n' | sed -n 's/^\([a-z0-9]*\):\(.*\)$/\1 \2/p'; }
      |""".stripMargin

  private val preflightScript = fill(
    helpers +
      """set -e
      |. /etc/os-release
      |case "$ID" in rocky|almalinux|rhel) ;; *) fail "runs $ID; the servers must be Rocky, Alma or RHEL 9" ;; esac
      |[ "${VERSION_ID%%.*}" = 9 ] || fail "is EL $VERSION_ID; Lustre @RELEASE@ servers are built for EL9"
      |[ -n "$(targets)" ] || fail "no lustre_targets var in inventory.yaml"
      |targets | while read -r role dev; do
      |  case "$role" in mgt|mdt[0-9]*|ost[0-9]*) ;; *) fail "unknown target role '$role' (mgt, mdtN, ostN)" ;; esac
      |  [ -b "$dev" ] || fail "$dev (for $role) is not a block device"
      |  echo "$role: $dev ($(lsblk -dno SIZE "$dev" | tr -d ' '))"
      |done
      |for u in @SERVER_REPO@ @E2FS_REPO@; do
      |  curl -fsS -o /dev/null --max-time 30 "${u}repodata/repomd.xml" ||
      |    fail "cannot read ${u}repodata/repomd.xml (wrong LUSTRE_RELEASE/LUSTRE_EL, or no route to downloads.whamcloud.com)"
      |done
      |echo "$(hostname -s): EL $VERSION_ID, repositories reachable"""".stripMargin
  )

  private val clientPreflightScript = fill(
    helpers +
      """set -e
      |. /etc/os-release
      |case "$ID" in rocky|almalinux|rhel) ;; *) fail "runs $ID; use lustre_client.scala for Ubuntu clients" ;; esac
      |[ "${VERSION_ID%%.*}" = 9 ] || fail "is EL $VERSION_ID; the Lustre @RELEASE@ @EL@ client is built for EL9"
      |curl -fsS -o /dev/null --max-time 30 "@CLIENT_REPO@repodata/repomd.xml" ||
      |  fail "cannot read @CLIENT_REPO@repodata/repomd.xml (wrong LUSTRE_RELEASE/LUSTRE_EL, or no route to downloads.whamcloud.com)"
      |echo "$(hostname -s): EL $VERSION_ID, client repository reachable"""".stripMargin
  )

  private val clientPackagesScript = fill(
    helpers +
      """set -e
      |cat > /etc/yum.repos.d/orphera-lustre.repo <<'REPO'
      |# Managed by Orphera (lustre_cluster.scala).
      |[orphera-lustre-client]
      |name=Lustre @RELEASE@ client (@EL@)
      |baseurl=@CLIENT_REPO@
      |enabled=1
      |gpgcheck=0
      |REPO
      |dnf -y -q makecache
      |dnf -y -q install python3-dnf-plugin-versionlock dnf-plugins-core
      |# kmod-lustre-client is built for one EL9 kernel, and dnf installs it only next
      |# to a kernel with the same symbol versions (kernel(...) requires). Find that
      |# kernel from the package's own file list and install it first: from the
      |# enabled repositories, else from the Rocky vault for @EL@.
      |D=$(mktemp -d)
      |trap 'rm -rf "$D"' EXIT
      |dnf -q download --repo orphera-lustre-client --destdir "$D" kmod-lustre-client >/dev/null
      |BK=$(rpm -qlp "$D"/kmod-lustre-client-*.rpm 2>/dev/null | sed -n 's|^/lib/modules/\([^/]*\)/.*|\1|p' | head -1)
      |[ -n "$BK" ] || fail "cannot tell which kernel kmod-lustre-client is built for"
      |echo "kmod-lustre-client is built for kernel $BK"
      |if [ ! -e "/boot/vmlinuz-$BK" ]; then
      |  M=$(rpm -E %rhel)
      |  V=@EL@; V=${V#el}
      |  echo "installing kernel $BK (about 100 MB; from the Rocky vault if it is no longer current)"
      |  dnf -y -q install "kernel-$BK" 2>/dev/null ||
      |    dnf -y --repofrompath="orphera-rocky-vault,https://dl.rockylinux.org/vault/rocky/$V/BaseOS/x86_64/os/" \
      |      --setopt=orphera-rocky-vault.gpgcheck=1 --setopt="orphera-rocky-vault.gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-$M" \
      |      install "kernel-$BK" ||
      |    fail "kernel $BK is in neither the enabled repositories nor the Rocky $V vault"
      |  echo "installed kernel $BK"
      |fi
      |dnf -y install lustre-client kmod-lustre-client
      |echo "installed: $(rpm -q lustre-client kmod-lustre-client | tr '\n' ' ')"""".stripMargin
  )

  private val packagesScript = fill(
    helpers +
      """set -e
      |cat > /etc/yum.repos.d/orphera-lustre.repo <<'REPO'
      |# Managed by Orphera (lustre_cluster.scala).
      |[orphera-lustre-server]
      |name=Lustre @RELEASE@ server (@EL@)
      |baseurl=@SERVER_REPO@
      |enabled=1
      |gpgcheck=0
      |
      |[orphera-e2fsprogs-wc]
      |name=e2fsprogs with the Lustre ldiskfs changes
      |baseurl=@E2FS_REPO@
      |enabled=1
      |gpgcheck=0
      |REPO
      |dnf -y -q makecache
      |dnf -y -q install python3-dnf-plugin-versionlock
      |KV=$(dnf -q repoquery --repo orphera-lustre-server --latest-limit 1 --qf '%{version}-%{release}' kernel 2>/dev/null | tail -1)
      |if [ -n "$KV" ]; then
      |  echo "the Lustre repository has its own kernel: $KV"
      |  rpm -q "kernel-$KV" >/dev/null 2>&1 || dnf -y install "kernel-$KV"
      |else
      |  echo "the Lustre repository has no kernel: patchless server on a stock kernel"
      |fi
      |dnf -y install lustre kmod-lustre kmod-lustre-osd-ldiskfs lustre-osd-ldiskfs-mount e2fsprogs
      |dnf -y upgrade --repo orphera-e2fsprogs-wc e2fsprogs e2fsprogs-libs libcom_err libss || true
      |rpm -q e2fsprogs | grep -q wc || fail "e2fsprogs is $(rpm -q e2fsprogs), not the Lustre (wc) build"
      |setenforce 0 2>/dev/null || true
      |sed -i 's/^SELINUX=enforcing/SELINUX=permissive/' /etc/selinux/config
      |echo "installed: $(rpm -q lustre kmod-lustre e2fsprogs | tr '\n' ' ')"""".stripMargin
  )

  private val bootKernelScript = fill(
    helpers +
      """set -e
      |# Kernels with the Lustre module (built for them, or linked in by weak-modules).
      |cands=$(for d in /lib/modules/*; do
      |  find "$d" -name 'lustre.ko*' -print -quit 2>/dev/null | grep -q . && echo "${d##*/}"
      |done | sort -rV)
      |[ -n "$cands" ] || fail "no kernel directory under /lib/modules has the Lustre module"
      |# Prefer kernels that are installed (a kmod package leaves its modules under the
      |# kernel it was built for even when that kernel is not installed).
      |inst=$(printf '%s\n' "$cands" | while read -r k; do [ -e "/boot/vmlinuz-$k" ] && echo "$k"; done)
      |K=$(printf '%s\n' "$inst" | grep _lustre | head -1)
      |[ -n "$K" ] || K=$(printf '%s\n' "$inst" | head -1)
      |if [ -z "$K" ]; then
      |  K=$(printf '%s\n' "$cands" | head -1)
      |  echo "the Lustre modules are built for $K, which is not installed: installing it"
      |  M=$(rpm -E %rhel)
      |  V=@EL@; V=${V#el}
      |  dnf -y -q install "kernel-$K" 2>/dev/null ||
      |    dnf -y -q --repofrompath="orphera-rocky-vault,https://dl.rockylinux.org/vault/rocky/$V/BaseOS/x86_64/os/" \
      |      --setopt=orphera-rocky-vault.gpgcheck=1 --setopt="orphera-rocky-vault.gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-$M" \
      |      install "kernel-$K" ||
      |    fail "kernel $K is in neither the enabled repositories nor the Rocky $V vault"
      |  depmod -a "$K"
      |  [ -e "/boot/vmlinuz-$K" ] || fail "/boot/vmlinuz-$K is missing after installing kernel-$K"
      |fi
      |grubby --set-default "/boot/vmlinuz-$K" >/dev/null
      |# Lock the kernel packages of K and the Lustre packages at what is installed.
      |N=$(rpm -qf "/boot/vmlinuz-$K" --qf '%{version}-%{release}\n' | head -1)
      |dnf -q versionlock delete 'kernel*' 'kmod-lustre*' 'lustre*' >/dev/null 2>&1 || true
      |locks=$(rpm -qa 'kernel*' --qf '%{name}-%{version}-%{release}\n' | grep -F -- "-$N")
      |locks="$locks $(rpm -qa 'kmod-lustre*' 'lustre*' --qf '%{name}-%{version}-%{release}\n')"
      |dnf -q versionlock add $locks >/dev/null
      |echo "default kernel: $K (running $(uname -r)); kernel and Lustre packages version-locked"""".stripMargin
  )

  // Registered as `lustre_reboot`: exactly "yes" or "no", nothing else.
  private val needsRebootScript =
    """[ "$(grubby --default-kernel 2>/dev/null)" = "/boot/vmlinuz-$(uname -r)" ] && echo no || echo yes"""

  private val lnetScript = fill(
    helpers +
      """set -e
      |[ "$(grubby --default-kernel)" = "/boot/vmlinuz-$(uname -r)" ] ||
      |  fail "running $(uname -r), not the Lustre kernel $(grubby --default-kernel)"
      |IF=$(ip -o -4 addr show | awk -v ip='{{{cluster_ip}}}' '{ split($4, a, "/"); if (a[1] == ip) print $2 }' | head -1)
      |[ -n "$IF" ] || fail "no interface has {{{cluster_ip}}}"
      |printf 'options lnet networks="tcp0(%s)"\n' "$IF" > /etc/modprobe.d/lustre.conf
      |if systemctl is-active -q firewalld; then
      |  firewall-cmd -q --permanent --add-port=988/tcp
      |  firewall-cmd -q --reload
      |fi
      |# lnet.service (enabled below) starts LNet at boot from /etc/lnet.conf, with no
      |# network if that file has none; `lnet configure --all` then does nothing, as
      |# LNet is already up. So add tcp0 explicitly, and save it for the next boot.
      |if ! lctl list_nids 2>/dev/null | grep -qx '{{{cluster_ip}}}@tcp'; then
      |  modprobe lnet
      |  lnetctl lnet configure 2>/dev/null || true
      |  lnetctl net del --net tcp0 2>/dev/null || true
      |  lnetctl net add --net tcp0 --if "$IF"
      |  lnetctl export --backup > /etc/lnet.conf.new && mv /etc/lnet.conf.new /etc/lnet.conf
      |fi
      |modprobe lustre
      |systemctl enable -q lnet.service 2>/dev/null || true
      |lctl list_nids | grep -qx '{{{cluster_ip}}}@tcp' || fail "LNet NIDs are '$(lctl list_nids | tr '\n' ' ')', expected {{{cluster_ip}}}@tcp"
      |echo "$(hostname -s): LNet up on $IF, NID {{{cluster_ip}}}@tcp"""".stripMargin
  )

  private val lnetPingScript = fill(
    helpers +
      """for i in 1 2 3 4 5 6; do
      |  lctl ping @MGSNID@ >/dev/null 2>&1 && { echo "$(hostname -s) reaches the MGS at @MGSNID@"; exit 0; }
      |  sleep 5
      |done
      |fail "lctl ping @MGSNID@ failed (LNet down on the MGS, or port 988/tcp blocked)"""".stripMargin
  )

  // Formats each listed target that is blank. The ldiskfs label is MGS for the
  // MGT and <fsname>:<TYPE><index hex> until the target first registers with
  // the MGS, <fsname>-<TYPE><index hex> after; either means "already done".
  private val formatScript = fill(
    helpers +
      """set -e
      |targets | while read -r role dev; do
      |  case "$role" in
      |    mgt)  want=MGS; alt=MGS; set -- --mgs ;;
      |    mdt*) n=$(printf '%04x' "${role#mdt}"); want="@FSNAME@-MDT$n"; alt="@FSNAME@:MDT$n"
      |          set -- --fsname=@FSNAME@ --mdt --index="${role#mdt}" --mgsnode=@MGSNID@ ;;
      |    ost*) n=$(printf '%04x' "${role#ost}"); want="@FSNAME@-OST$n"; alt="@FSNAME@:OST$n"
      |          set -- --fsname=@FSNAME@ --ost --index="${role#ost}" --mgsnode=@MGSNID@ ;;
      |  esac
      |  label=$(blkid -p -s LABEL -o value "$dev" 2>/dev/null || true)
      |  if [ "$label" = "$want" ] || [ "$label" = "$alt" ]; then
      |    echo "$role on $dev: already formatted ($label), left alone"
      |    continue
      |  fi
      |  sigs=$(wipefs --noheadings -O TYPE "$dev" 2>/dev/null | tr '\n' ' ')
      |  [ -z "$sigs" ] || fail "$dev (for $role) already holds: $sigs(label '$label'); not formatting it. lustre_teardown.scala wipes the listed disks"
      |  echo "$role on $dev: mkfs.lustre $*"
      |  out=$(mkfs.lustre "$@" "$dev" 2>&1) || { printf '%s\n' "$out"; fail "mkfs.lustre failed for $role on $dev"; }
      |  echo "$role on $dev: formatted ($(blkid -p -s LABEL -o value "$dev"))"
      |done""".stripMargin
  )

  // Mounts this node's targets whose role starts with one of `roles`, in that
  // order, at /lustre/<fsname>-<role> (/lustre/mgs for the MGT).
  private def mountScript(roles: String): String = fill(
    helpers +
      """set -e
      |for want in @ROLES@; do
      |  targets | while read -r role dev; do
      |    case "$role" in "$want"*) ;; *) continue ;; esac
      |    if [ "$role" = mgt ]; then mp=/lustre/mgs; else mp="/lustre/@FSNAME@-$role"; fi
      |    mkdir -p "$mp"
      |    if mountpoint -q "$mp"; then echo "$role: already mounted at $mp"; continue; fi
      |    mount -t lustre "$dev" "$mp" || fail "mount -t lustre $dev $mp failed (dmesg | tail)"
      |    echo "$role: mounted at $mp"
      |  done
      |done""".stripMargin.replace("@ROLES@", roles)
  )

  // After a restart, each MDT and OST waits for the clients it knew to come
  // back (Lustre recovery, up to 15 minutes), and refuses new clients until then
  // (mds_connect rc = -16). Wait for that here, showing the time left, rather
  // than inside the client mount.
  private val recoveryScript = fill(
    helpers +
      """set -e
      |for p in $(lctl list_param 'mdt.*.recovery_status' 'obdfilter.*.recovery_status' 2>/dev/null); do
      |  t=${p#*.}; t=${t%.recovery_status}
      |  while :; do
      |    s=$(lctl get_param -n "$p")
      |    st=$(printf '%s\n' "$s" | awk '$1 == "status:" { print $2 }')
      |    case "$st" in
      |      COMPLETE|INACTIVE) echo "$t: recovery ${st}"; break ;;
      |    esac
      |    left=$(printf '%s\n' "$s" | awk '$1 == "time_remaining:" { print $2 }')
      |    echo "$t: recovery ${st:-unknown}, ${left:-?}s left"
      |    sleep 15
      |  done
      |done""".stripMargin
  )

  private val clientMountScript = fill(
    helpers +
      """set -e
      |MP=/mnt/@FSNAME@
      |# A Lustre mount whose root cannot be read within 15 s is dead (the client was
      |# evicted, or the file system was rebuilt or force-unmounted under it): detach
      |# it and remount. stat on such a mount can block instead of failing, hence the
      |# timeout; and the lazy unmount, which does not wait for the servers.
      |if grep -q " $MP lustre " /proc/mounts; then
      |  if timeout 15 stat -t "$MP" >/dev/null 2>&1; then echo "$MP already mounted"; exit 0; fi
      |  echo "$MP is a dead Lustre mount: detaching it (umount -l)"
      |  umount -l "$MP" || fail "cannot detach the dead mount at $MP"
      |fi
      |mkdir -p "$MP"
      |mount -t lustre @MGSNID@:/@FSNAME@ "$MP" || fail "client mount of @MGSNID@:/@FSNAME@ failed"
      |echo "mounted @MGSNID@:/@FSNAME@ at /mnt/@FSNAME@"""".stripMargin
  )

  // Waits up to two minutes for every inventory target to show in `lfs df`.
  private val verifyScript = fill(
    helpers +
      """ALL='@ALL_TARGETS@'
      |want_mdt=$(printf '%s\n' "$ALL" | tr ',' '\n' | grep -c '^mdt')
      |want_ost=$(printf '%s\n' "$ALL" | tr ',' '\n' | grep -c '^ost')
      |for i in $(seq 1 24); do
      |  out=$(lfs df /mnt/@FSNAME@ 2>/dev/null)
      |  mdt=$(printf '%s\n' "$out" | grep -c '\[MDT:')
      |  ost=$(printf '%s\n' "$out" | grep -c '\[OST:')
      |  [ "$mdt" = "$want_mdt" ] && [ "$ost" = "$want_ost" ] && break
      |  sleep 5
      |done
      |lfs df -h /mnt/@FSNAME@
      |[ "$mdt" = "$want_mdt" ] && [ "$ost" = "$want_ost" ] ||
      |  fail "the client sees $mdt MDT(s) and $ost OST(s); inventory has $want_mdt and $want_ost"
      |echo "@FSNAME@: $mdt MDT(s), $ost OST(s), mounted at /mnt/@FSNAME@ on $(hostname -s)"""".stripMargin
  )

  private def sh(
      script: String,
      timeout: Int = 60,
      register: String = ""
  ): Task =
    Task.RunCommand(
      List("sh", "-c", script),
      timeoutSeconds = timeout,
      register = register
    )

  private val stages: List[Stage] =
    List(
      stage("preflight", servers*)
        .task("check OS, target disks and repositories")(
          sh(preflightScript, 120)
        )
        .build,
      stage("packages", servers*)
        .task("Lustre repositories, kernel and server packages")(
          sh(packagesScript, 1800)
        )
        .build
    ) ++ (if clientOnly.isEmpty then Nil
          else
            List(
              stage("preflight-clients", clientOnly*)
                .task("check OS and the client repository")(
                  sh(clientPreflightScript, 120)
                )
                .build,
              stage("packages-clients", clientOnly*)
                .task("Lustre client repository and packages")(
                  sh(clientPackagesScript, 1800)
                )
                .build
            )) ++ List(
      stage("boot-kernel", allNodes*)
        .task("default to the Lustre kernel and lock the packages")(
          sh(bootKernelScript, 300)
        )
        .task("is a reboot needed")(
          sh(needsRebootScript, register = "lustre_reboot")
        )
        .build,
      stage("reboot", allNodes*)
        .task("reboot into the Lustre kernel")(
          Task.Reboot(waitForReturn = true, waitTimeoutSeconds = 600)
        )
        .when("lustre_reboot" === "yes")
        .build,
      stage("lnet", allNodes*)
        .task("LNet on tcp0")(sh(lnetScript, 120))
        .build,
      stage("lnet-ping", allNodes*)
        .task("reach the MGS over LNet")(sh(lnetPingScript, 120))
        .build,
      stage("format", servers*)
        .task("mkfs.lustre the blank targets")(sh(formatScript, 1800))
        .build,
      stage("mount-mgs-mdt0", servers*)
        .task("mount the MGT, then MDT0")(sh(mountScript("mgt mdt0"), 600))
        .build,
      stage("mount-mdt", servers*)
        .task("mount the other MDTs")(sh(mountScript("mdt"), 600))
        .build,
      stage("mount-ost", servers*)
        .task("mount the OSTs")(sh(mountScript("ost"), 600))
        .build,
      stage("recovery", servers*)
        .task("wait for MDT and OST recovery")(sh(recoveryScript, 1200))
        .build,
      stage("mount-client", clients*)
        .task(s"mount $fsName at /mnt/$fsName")(sh(clientMountScript, 300))
        .build,
      stage("verify", clients*)
        .task("every MDT and OST is visible to the client")(
          sh(verifyScript, 300)
        )
        .build
    )

  val playbook: ClusterPlaybook = clusterPlaybook("lustre-cluster")(stages*)
