// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Lustre clients on Ubuntu 24.04, for the file system lustre_cluster.scala
// builds (run that first). Default client: tst49, mounting lfs0 at /mnt/lfs0.
//
// Run:   orphera cluster-playbook manifests/lustre_client.scala
//
// Whamcloud's Ubuntu client modules are built for one Ubuntu kernel, which the
// lab VMs (kept up to date by dist-upgrade) are not running. So the client is
// built on the node from Whamcloud's lustre-source package, against the
// running kernel's headers, without the server parts or InfiniBand
// (--with-o2ib=no; LNet here is TCP only), and installed under /usr. gcc-12, as
// the Lustre 2.17 source does not build cleanly with Ubuntu 24.04's default
// gcc-13. The build takes a few minutes; its log is
// /var/log/lustre-client-build.log on the node.
//
// Stages:
//   preflight   Ubuntu 24.04, the source package is reachable. Nothing changed.
//   build       only when /var/lib/orphera/lustre-client.built does not name this
//               Lustre version and the running kernel, or the modules or
//               mount.lustre are missing. Then the kernel metapackages are held
//               (apt-mark hold), so a dist-upgrade does not move the node to a
//               kernel without the modules; after a kernel change made anyway,
//               re-running this playbook rebuilds.
//   lnet        LNet on tcp0, on the interface that holds cluster_ip; port
//               988/tcp opened when ufw is active
//   lnet-ping   reaches the MGS over LNet
//   mount       /mnt/<fsname> (not in /etc/fstab, like the servers: re-run this
//               playbook after the servers are back)
//   verify      the client sees every MDT and OST listed in the inventory
//
// Settings (environment variables):
//   LUSTRE_UBUNTU_CLIENTS  client nodes (space separated)          default tst49
//   LUSTRE_FSNAME          file system name                         default lfs0
//   LUSTRE_MGS             the node with the MGT                    default tst43
//   LUSTRE_SERVERS         nodes with targets, for the verify count default tst43-tst48
//   LUSTRE_VERSION         Lustre version to build; keep it equal to the servers'
//                          (lustre_cluster.scala LUSTRE_RELEASE)     default 2.17.0
//
// Needs outbound HTTPS from the clients to downloads.whamcloud.com.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object lustre_client extends OrpheraClusterPlaybook:

  private def envList(name: String, default: String): List[String] =
    sys.env
      .getOrElse(name, default)
      .split("[ ,]+")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  private val clients = envList("LUSTRE_UBUNTU_CLIENTS", "tst49")
  private val fsName = sys.env.getOrElse("LUSTRE_FSNAME", "lfs0")
  private val mgs = sys.env.getOrElse("LUSTRE_MGS", "tst43")
  private val servers =
    envList("LUSTRE_SERVERS", "tst43 tst44 tst45 tst46 tst47 tst48")
  private val version = sys.env.getOrElse("LUSTRE_VERSION", "2.17.0")

  require(clients.nonEmpty, "LUSTRE_UBUNTU_CLIENTS is empty")
  require(
    clients.forall(c => !servers.contains(c)),
    s"a node in LUSTRE_UBUNTU_CLIENTS is also a server: ${clients.filter(servers.contains).mkString(" ")}"
  )

  private val repo =
    s"https://downloads.whamcloud.com/public/lustre/lustre-$version/ubuntu2404/client/"
  private val srcDeb = s"lustre-source_$version-1_all.deb"
  private val mgsNid = s"{{{nodes.$mgs.cluster_ip}}}@tcp"
  private val allTargets =
    servers.map(n => s"{{{nodes.$n.lustre_targets}}}").mkString(",")

  private def fill(script: String): String =
    ("""fail() { echo "FAILED: $(hostname -s): $*"; exit 1; }
       |""".stripMargin + script)
      .replace("@FSNAME@", fsName)
      .replace("@MGSNID@", mgsNid)
      .replace("@REPO@", repo)
      .replace("@SRCDEB@", srcDeb)
      .replace("@VERSION@", version)
      .replace("@ALL_TARGETS@", allTargets)

  private val preflightScript = fill(
    """set -e
      |. /etc/os-release
      |[ "$ID" = ubuntu ] || fail "runs $ID; this playbook is for Ubuntu 24.04 clients"
      |[ "$VERSION_ID" = 24.04 ] || fail "is Ubuntu $VERSION_ID; the Lustre @VERSION@ client source is packaged for 24.04"
      |curl -fsSI -o /dev/null --max-time 30 "@REPO@@SRCDEB@" ||
      |  fail "cannot reach @REPO@@SRCDEB@ (wrong LUSTRE_VERSION, or no route to downloads.whamcloud.com)"
      |echo "$(hostname -s): Ubuntu $VERSION_ID, kernel $(uname -r), @SRCDEB@ reachable"""".stripMargin
  )

  private val buildScript = fill(
    """set -e
      |KV=$(uname -r)
      |MARK=/var/lib/orphera/lustre-client.built
      |LOG=/var/log/lustre-client-build.log
      |if [ "$(cat "$MARK" 2>/dev/null)" = "@VERSION@ $KV" ] && modinfo -k "$KV" lustre >/dev/null 2>&1 &&
      |   command -v mount.lustre >/dev/null; then
      |  echo "already built: Lustre @VERSION@ client for $KV"
      |  exit 0
      |fi
      |export DEBIAN_FRONTEND=noninteractive
      |apt-get -q update >/dev/null
      |apt-get -y -q install "linux-headers-$KV" build-essential gcc-12 flex bison pkg-config \
      |  zlib1g-dev libssl-dev libmount-dev libyaml-dev libnl-3-dev libnl-genl-3-dev libkeyutils-dev \
      |  curl ca-certificates >/dev/null
      |W=$(mktemp -d /var/tmp/lustre-build.XXXXXX)
      |trap 'rm -rf "$W"' EXIT
      |curl -fsSL -o "$W/src.deb" "@REPO@@SRCDEB@"
      |dpkg -x "$W/src.deb" "$W/x"
      |T=$(find "$W/x/usr/src" -name 'lustre*.tar.bz2' | head -1)
      |[ -n "$T" ] || fail "no lustre*.tar.bz2 inside @SRCDEB@"
      |mkdir "$W/src"
      |tar xjf "$T" -C "$W/src"
      |C=$(find "$W/src" -maxdepth 4 -name configure -type f | head -1)
      |[ -n "$C" ] || fail "no configure script in $T"
      |cd "${C%/configure}"
      |echo "building Lustre @VERSION@ client for $KV (log: $LOG)"
      |: > "$LOG"
      |step() { "$@" >> "$LOG" 2>&1 || { tail -40 "$LOG"; fail "'$1 $2' failed; see $LOG"; }; }
      |step ./configure --disable-server --with-o2ib=no --with-linux="/usr/src/linux-headers-$KV" \
      |  --prefix=/usr --sysconfdir=/etc --localstatedir=/var CC=gcc-12
      |step make -j"$(nproc)" CC=gcc-12
      |step make install CC=gcc-12
      |depmod -a "$KV"
      |ldconfig
      |modinfo -k "$KV" lustre >/dev/null 2>&1 || fail "built, but modinfo finds no lustre module for $KV"
      |command -v mount.lustre >/dev/null || fail "built, but mount.lustre is not on the PATH"
      |held=""
      |for p in linux-generic linux-image-generic linux-headers-generic linux-virtual linux-image-virtual \
      |         linux-headers-virtual linux-kvm linux-image-kvm linux-headers-kvm; do
      |  dpkg-query -W -f '${Status}' "$p" 2>/dev/null | grep -q 'install ok installed' && held="$held $p"
      |done
      |[ -z "$held" ] || apt-mark hold $held >/dev/null
      |mkdir -p /var/lib/orphera
      |echo "@VERSION@ $KV" > "$MARK"
      |echo "built and installed Lustre @VERSION@ client for $KV; held:${held:- nothing}"""".stripMargin
  )

  private val lnetScript = fill(
    """set -e
      |IF=$(ip -o -4 addr show | awk -v ip='{{{cluster_ip}}}' '{ split($4, a, "/"); if (a[1] == ip) print $2 }' | head -1)
      |[ -n "$IF" ] || fail "no interface has {{{cluster_ip}}}"
      |printf 'options lnet networks="tcp0(%s)"\n' "$IF" > /etc/modprobe.d/lustre.conf
      |if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then
      |  ufw allow 988/tcp >/dev/null
      |fi
      |if ! lctl list_nids 2>/dev/null | grep -qx '{{{cluster_ip}}}@tcp'; then
      |  modprobe lnet
      |  lnetctl lnet configure --all
      |fi
      |modprobe lustre
      |lctl list_nids | grep -qx '{{{cluster_ip}}}@tcp' || fail "LNet NIDs are '$(lctl list_nids | tr '\n' ' ')', expected {{{cluster_ip}}}@tcp"
      |echo "$(hostname -s): LNet up on $IF, NID {{{cluster_ip}}}@tcp"""".stripMargin
  )

  private val lnetPingScript = fill(
    """for _ in 1 2 3 4 5 6; do
      |  lctl ping @MGSNID@ >/dev/null 2>&1 && { echo "$(hostname -s) reaches the MGS at @MGSNID@"; exit 0; }
      |  sleep 5
      |done
      |fail "lctl ping @MGSNID@ failed (LNet down on the MGS, or port 988/tcp blocked)"""".stripMargin
  )

  private val mountScript = fill("""set -e
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
      |mount -t lustre @MGSNID@:/@FSNAME@ "$MP" || fail "mount of @MGSNID@:/@FSNAME@ failed (dmesg | tail)"
      |echo "mounted @MGSNID@:/@FSNAME@ at /mnt/@FSNAME@"""".stripMargin)

  private val verifyScript = fill(
    """ALL='@ALL_TARGETS@'
      |want_mdt=$(printf '%s\n' "$ALL" | tr ',' '\n' | grep -c '^mdt')
      |want_ost=$(printf '%s\n' "$ALL" | tr ',' '\n' | grep -c '^ost')
      |mdt=0; ost=0
      |for _ in $(seq 1 24); do
      |  out=$(lfs df /mnt/@FSNAME@ 2>/dev/null)
      |  mdt=$(printf '%s\n' "$out" | grep -c '\[MDT:')
      |  ost=$(printf '%s\n' "$out" | grep -c '\[OST:')
      |  if [ "$mdt" = "$want_mdt" ] && [ "$ost" = "$want_ost" ]; then break; fi
      |  sleep 5
      |done
      |lfs df -h /mnt/@FSNAME@
      |if [ "$mdt" != "$want_mdt" ] || [ "$ost" != "$want_ost" ]; then
      |  fail "the client sees $mdt MDT(s) and $ost OST(s); inventory has $want_mdt and $want_ost"
      |fi
      |echo "@FSNAME@: $mdt MDT(s), $ost OST(s), mounted at /mnt/@FSNAME@ on $(hostname -s) (Lustre $(lctl get_param -n version 2>/dev/null | head -1))"""".stripMargin
  )

  private def sh(script: String, timeout: Int = 60): Task =
    Task.RunCommand(List("sh", "-c", script), timeoutSeconds = timeout)

  val playbook: ClusterPlaybook =
    clusterPlaybook("lustre-client")(
      stage("preflight", clients*)
        .task("check OS and the source package")(sh(preflightScript, 120))
        .build,
      stage("build", clients*)
        .task("build and install the Lustre client")(sh(buildScript, 3600))
        .build,
      stage("lnet", clients*)
        .task("LNet on tcp0")(sh(lnetScript, 120))
        .build,
      stage("lnet-ping", clients*)
        .task("reach the MGS over LNet")(sh(lnetPingScript, 120))
        .build,
      stage("mount", clients*)
        .task(s"mount $fsName at /mnt/$fsName")(sh(mountScript, 300))
        .build,
      stage("verify", clients*)
        .task("every MDT and OST is visible")(sh(verifyScript, 300))
        .build
    )
