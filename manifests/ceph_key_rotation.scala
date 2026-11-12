// SPDX-License-Identifier: Apache-2.0

import java.nio.file.{Files, Paths, StandardCopyOption}
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import cats.effect.IO

import orphera.orchestrator.*

// Moves the OpenStack Ceph users (client.cinder, client.glance, client.manila)
// from the old `aes` cephx keys to `aes256k`, then has the monitors accept and
// create `aes256k` only: the fix for the CVE-2025-30156 health warnings
// (AUTH_INSECURE_CLIENT_KEY_TYPE, AUTH_INSECURE_KEYS_ALLOWED,
// AUTH_INSECURE_KEYS_CREATABLE) that the lab carried as a compromise.
//
// Run ceph_client_upgrade.scala first: the services' Ceph client must know
// aes256k (Ceph 19.2.6 / 20.2.4 or later), which this script checks before it
// changes anything.
//
// What it does:
//   1. Checks: the monitors allow aes256k; cinder-volume (tst10), glance-api
//      (tst13), manila-share (tst22) and the compute hosts (tst17, tst18) have
//      a new enough Ceph client; the manifests it re-runs are here.
//   2. Backs up the secret store (secrets.age.bak-<time> beside it).
//   3. For each user whose key is `aes`: `ceph auth rotate --key-type=aes256k`
//      (the old key stops working: the service cannot reach Ceph until step 4),
//      then stores the new key in the secret store (cinder/ceph-key,
//      glance/ceph-key, manila/ceph-key). The key is fetched masked with a
//      one-time pad, so it is not in the output or the run log.
//   4. Re-runs cinder_single_node, glance_single_node, manila_single_node and
//      nova_compute (orphera cluster-playbook), which write the new keys into
//      the services' keyrings and the compute hosts' libvirt secret, and leave
//      the cipher setting alone now that the keys are aes256k.
//   5. Checks each service connects with its new key, and the libvirt secret.
//   6. If no client key is `aes` any more: `auth_allowed_ciphers aes256k` and
//      `auth_preferred_cipher aes256k`, waits for the warnings to clear, and
//      checks the services connect again. If one is left (client.k8s, if the
//      Kubernetes cluster's ceph-csi uses the kernel RBD client, which needs
//      `aes` before Linux 7.0), it says which and leaves `aes` allowed.
//
// Downtime: Cinder, Glance and Manila cannot reach Ceph from a key's rotation
// until their manifest has run (step 4), a few minutes. Running VMs with Cinder
// volumes hold Ceph sessions with the old key: restart them afterwards.
//
// Settings: CEPH_ADMIN (default tst0).
// Run, from the repository: orphera playbook manifests/ceph_key_rotation.scala
//   (--resume after a failure: a rotated key is not rotated again; it is read
//   back from Ceph and stored, and the manifests and checks run again)
object ceph_key_rotation extends OrpheraScript:

  private val admin = sys.env.getOrElse("CEPH_ADMIN", "tst0")

  private final case class User(name: String, secret: String, node: String, container: String)
  private val users = List(
    User("cinder", "cinder/ceph-key", "tst10", "cinder-volume"),
    User("glance", "glance/ceph-key", "tst13", "glance-api"),
    User("manila", "manila/ceph-key", "tst22", "manila-share")
  )
  private val computeHosts = List("tst17", "tst18")
  private val rbdSecretUuid = "8c256f90-1c37-4fb6-b8be-cedcf0fffd3c" // nova_compute.scala, cinder_single_node.scala
  private val manifests = List("cinder_single_node", "glance_single_node", "manila_single_node", "nova_compute")

  private val helpers =
    """fail() { echo "FAILED: $(hostname -s): $*"; exit 1; }
      |c() { cephadm shell -- ceph "$@" 2>/dev/null; }
      |""".stripMargin

  // A Ceph client version is new enough for aes256k from 19.2.6 and 20.2.4.
  private def knowsAes256k(version: String): Boolean =
    "^(\\d+)\\.(\\d+)\\.(\\d+)".r.findFirstMatchIn(version).map(m => (m.group(1).toInt, m.group(2).toInt, m.group(3).toInt)) match
      case Some((major, _, _)) if major >= 21        => true
      case Some((20, minor, patch))                  => minor > 2 || (minor == 2 && patch >= 4)
      case Some((19, minor, patch))                  => minor > 2 || (minor == 2 && patch >= 6)
      case _                                         => false

  // The user's key in Ceph, fetched through a one-time pad so that it never appears in the output.
  private def fetchKey(node: NodeRef, user: User): IO[String] =
    for
      pad <- IO {
        val b = new Array[Byte](128)
        new SecureRandom().nextBytes(b)
        b.map(x => f"${x & 0xff}%02x").mkString
      }
      _ <- IO(Secrets.register(pad))
      r <- node.query(
        s"read client.${user.name}'s key",
        helpers +
          s"""c auth get-key client.${user.name} | tail -n 1 | python3 -c '
             |import sys
             |k = sys.stdin.read().strip().encode()
             |p = bytes.fromhex(sys.argv[1])
             |assert 0 < len(k) <= len(p), "no key"
             |print(bytes(a ^ b for a, b in zip(k, p)).hex())
             |' '$pad'""".stripMargin
      )
      key <- IO.raiseWhen(r.exitCode != 0)(new IllegalStateException(s"cannot read client.${user.name}'s key on ${node.name}")) >>
        IO {
          val masked = r.stdout.trim.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
          val p = pad.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
          new String(masked.zip(p).map((a, b) => (a ^ b).toByte), "US-ASCII")
        }
      _ <- IO(Secrets.register(key))
    yield key

  private def storeSecret(name: String, value: String): IO[Unit] =
    IO.fromEither(SecretStore.default().flatMap(_.set(name, value)).left.map(m => new IllegalStateException(s"secret $name: $m")))

  private def runManifest(name: String): IO[Unit] =
    IO.println(s"[scala0] orphera cluster-playbook manifests/$name.scala") >>
      IO.blocking(new ProcessBuilder("orphera", "cluster-playbook", s"manifests/$name.scala").inheritIO().start().waitFor())
        .flatMap(rc => IO.raiseWhen(rc != 0)(new IllegalStateException(s"manifests/$name.scala failed (exit $rc): see above; then --resume")))

  // Each service connects to the cluster with the keyring it has, from inside its container.
  private def checkConnections(using orphera: Orphera): IO[Unit] =
    users.foldLeft(IO.unit)((before, u) =>
      before >> orphera.node(u.node).query(
        s"client.${u.name} connects from ${u.container}",
        s"""podman exec ${u.container} /usr/bin/python3 -c "import rados; r = rados.Rados(conffile='/etc/ceph/ceph.conf', name='client.${u.name}', conf={'keyring': '/etc/ceph/ceph.client.${u.name}.keyring'}); r.connect(timeout=20); print('client.${u.name} connects, cluster', r.get_fsid()); r.shutdown()"""",
        60
      ).flatMap(r => IO.raiseWhen(r.exitCode != 0)(new IllegalStateException(s"${u.node}: client.${u.name} cannot connect from ${u.container} (see above)")))
    )

  def script(using orphera: Orphera): IO[Unit] =
    val ceph = orphera.node(admin)
    for
      // 1. Checks before anything changes.
      _ <- IO.blocking(manifests.filterNot(m => Files.exists(Paths.get(s"manifests/$m.scala")))).flatMap(missing =>
        IO.raiseWhen(missing.nonEmpty)(new IllegalStateException(s"run this from the repository: manifests/${missing.head}.scala is not here"))
      )
      ciphers <- ceph.query("cephx ciphers allowed", helpers + "c mon dump | sed -n 's/^auth_allowed_ciphers //p'")
      _ <- IO.raiseUnless(ciphers.stdout.contains("aes256k"))(
        new IllegalStateException(s"the monitors do not allow aes256k (auth_allowed_ciphers: ${ciphers.stdout.trim})")
      )
      _ <- users.foldLeft(IO.unit)((before, u) =>
        before >> orphera.node(u.node).query(s"Ceph client in ${u.container}", s"podman exec ${u.container} dpkg-query -W -f='$${Version}' librados2").flatMap(r =>
          IO.raiseUnless(knowsAes256k(r.stdout.trim))(
            new IllegalStateException(s"${u.node}: ${u.container} has librados2 '${r.stdout.trim}', too old for aes256k: run ceph_client_upgrade.scala first")
          )
        )
      )
      _ <- computeHosts.foldLeft(IO.unit)((before, h) =>
        before >> orphera.node(h).query("Ceph client on the host", "dpkg-query -W -f='${Version}' librbd1").flatMap(r =>
          IO.raiseUnless(knowsAes256k(r.stdout.trim))(
            new IllegalStateException(s"$h: librbd1 '${r.stdout.trim}', too old for aes256k: run ceph_client_upgrade.scala first")
          )
        )
      )
      // 2. A copy of the secret store.
      _ <- IO.blocking {
        val store = SecretStore.secretsFile
        val copy = store.resolveSibling(s"${store.getFileName}.bak-${LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}")
        Files.copy(store, copy, StandardCopyOption.COPY_ATTRIBUTES)
        copy
      }.flatMap(copy => IO.println(s"[scala0] secret store backed up to $copy"))
      // 3. Rotate the `aes` keys and store the new ones.
      _ <- users.foldLeft(IO.unit)((before, u) =>
        before >> (for
          key <- fetchKey(ceph, u)
          _ <-
            if CephKeys.isAes(key) then
              ceph.sh(
                s"rotate client.${u.name} to aes256k",
                helpers + s"set -e\ncephadm shell -- ceph auth rotate --key-type=aes256k client.${u.name} >/dev/null 2>&1 || fail 'ceph auth rotate --key-type=aes256k client.${u.name} failed'\necho 'client.${u.name} rotated'",
                timeout = 120
              ).void
            else IO.unit
          newKey <- fetchKey(ceph, u)
          _ <- IO.raiseWhen(CephKeys.isAes(newKey) || !CephKeys.isKey(newKey))(
            new IllegalStateException(s"client.${u.name}'s key is still not an aes256k key after the rotation")
          )
          stored <- IO(Secrets.get(u.secret))
          _ <-
            if stored == newKey then IO.println(s"[scala0] ${u.secret} already holds client.${u.name}'s aes256k key")
            else storeSecret(u.secret, newKey) >> IO.println(s"[scala0] ${u.secret} now holds client.${u.name}'s aes256k key")
        yield ())
      )
      // 4. The services get their new keys.
      _ <- manifests.foldLeft(IO.unit)((before, m) => before >> runManifest(m))
      // 5. Each service connects with its new key; libvirt holds client.cinder's.
      _ <- checkConnections
      cinderKey <- IO(Secrets.get("cinder/ceph-key"))
      _ <- orphera.nodes(computeHosts*).each(h =>
        h.query(
          "libvirt holds client.cinder's new key",
          s"""[ "$$(virsh -c qemu:///system secret-get-value $rbdSecretUuid)" = '$cinderKey' ] && echo 'libvirt secret $rbdSecretUuid holds the new key'"""
        ).flatMap(r => IO.raiseWhen(r.exitCode != 0)(new IllegalStateException(s"${h.name}: the libvirt secret does not hold client.cinder's new key")))
      )
      // 6. aes256k only, if no client key is `aes` any more.
      left <- ceph.query(
        "client keys still aes",
        helpers + """c health detail --format json | python3 -c '
          |import json, sys
          |chk = json.load(sys.stdin).get("checks", {}).get("AUTH_INSECURE_CLIENT_KEY_TYPE") or {}
          |for d in chk.get("detail", []):
          |    m = d.get("message", "")
          |    print(m.split()[1] if m.startswith("entity ") else m)
          |'""".stripMargin
      ).map(
        // The OpenStack users are checked above; health can still name them for a moment after the rotation.
        _.stdout.linesIterator.map(_.trim).filter(_.nonEmpty).filterNot(e => users.exists(u => e == s"client.${u.name}")).toList
      )
      _ <-
        if left.nonEmpty then
          IO.println(
            s"[$admin] still on aes keys: ${left.mkString(", ")}. `aes` stays allowed for them " +
              "(client.k8s: the kernel RBD client needs aes before Linux 7.0); the OpenStack keys are aes256k"
          )
        else
          ceph.sh(
            "monitors allow and create aes256k only",
            helpers + "set -e\nc mon set auth_allowed_ciphers aes256k\nc mon set auth_preferred_cipher aes256k\n" +
              "for code in AUTH_INSECURE_CLIENT_KEY_TYPE AUTH_INSECURE_KEYS_ALLOWED AUTH_INSECURE_KEYS_CREATABLE; do c health unmute $code >/dev/null 2>&1 || true; done\n" +
              "echo \"auth_allowed_ciphers: $(c mon dump | sed -n 's/^auth_allowed_ciphers //p')\"",
            timeout = 180
          ) >> orphera.waitUntil("the AUTH_INSECURE_* warnings have cleared", timeoutSeconds = 300, everySeconds = 15)(
            ceph.query(
              "AUTH_INSECURE_* warnings",
              helpers + "c health detail | grep -o -e AUTH_INSECURE_CLIENT_KEY_TYPE -e AUTH_INSECURE_KEYS_ALLOWED -e AUTH_INSECURE_KEYS_CREATABLE | sort -u || true"
            ).map(_.stdout.trim.isEmpty)
          ) >> checkConnections >> IO.println(s"[$admin] cephx: aes256k only; client.cinder, client.glance and client.manila on aes256k keys")
    yield ()
