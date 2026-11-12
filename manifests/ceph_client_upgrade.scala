// SPDX-License-Identifier: Apache-2.0

import cats.effect.IO

import orphera.orchestrator.*

// Gives the OpenStack services that use Ceph a Ceph client that knows the
// aes256k key type, the first step towards rotating client.cinder,
// client.glance and client.manila off the old `aes` keys and clearing the
// AUTH_INSECURE_* health warnings (see the CVE notes in cephadm_install.scala).
//
// Why: the Kolla 2025.1 ubuntu-noble images and the compute hosts have Ubuntu's
// Ceph 19.2.3, which cannot use aes256k keys (that needs 19.2.6 / 20.2.4 or
// later); Ubuntu has not released a fixed package for noble yet. Ceph's own
// packages for the cluster's release (20.2.5) are used instead.
//
// What it does:
//   1. On tst21 (kolla_build.scala's registry), for each image below: pulls
//      the image from quay.io, builds a thin image on top of it that upgrades
//      the Ceph client packages it has (librados2, librbd1, python3-rados, ...)
//      from download.ceph.com, checks the version, and pushes it to tst21's
//      registry under the same name and tag. The nodes already try tst21 first
//      for quay.io images (orphera-kolla-cache.conf), so the service manifests
//      need no change, and a later run of them keeps the new image.
//   2. On each service node: pulls the image (which now comes from tst21),
//      checks its Ceph client version, restarts the service's unit, and for
//      Cinder, Glance and Manila connects to the cluster with the service's own
//      key from inside the container.
//   3. On the compute hosts (QEMU opens Cinder volumes with the host's librbd):
//      the same packages from download.ceph.com. Running VMs keep the library
//      they started with until they are restarted.
//
// It changes no key and no Ceph setting: the rotation is a separate step, to
// run once every client here reports the new version.
//
// Settings:
//   CEPH_CLIENT_REPO     apt repository (default https://download.ceph.com/debian-20.2.5)
//   CEPH_CLIENT_VERSION  version the clients must end on (default 20.2.5; a prefix)
//
// Run:   orphera playbook manifests/ceph_client_upgrade.scala   (--resume after a failure)
//
// Undo, if a service misbehaves on the new client: delete the image's tag from
// tst21's registry, then `podman pull` on the node (it falls back to tst9 and
// quay.io) and restart the unit; on a compute host, remove
// /etc/apt/sources.list.d/ceph-upstream.list and downgrade the packages.
object ceph_client_upgrade extends OrpheraScript:

  private val repo = sys.env.getOrElse("CEPH_CLIENT_REPO", "https://download.ceph.com/debian-20.2.5").stripSuffix("/")
  private val version = sys.env.getOrElse("CEPH_CLIENT_VERSION", "20.2.5")

  private val builder = "tst21"
  private val tag = "2025.1-ubuntu-noble"

  // The service nodes, their container, and the Ceph user the service connects as (None: none of its own).
  private final case class Service(node: String, image: String, cephUser: Option[String])
  private val services = List(
    Service("tst10", "cinder-volume", Some("cinder")),
    Service("tst13", "glance-api", Some("glance")),
    Service("tst22", "manila-share", Some("manila")),
    Service("tst17", "nova-compute", None),
    Service("tst18", "nova-compute", None)
  )
  private val computeHosts = List("tst17", "tst18")

  // The Ceph client packages upgraded where they are installed (never added).
  private val cephPackages =
    "librados2 librbd1 libcephfs2 libradosstriper1 librgw2 python3-rados python3-rbd python3-cephfs " +
      "python3-rgw python3-ceph-argparse python3-ceph-common ceph-common"

  private val helpers =
    """fail() { echo "FAILED: $(hostname -s): $*"; exit 1; }
      |set -e
      |""".stripMargin

  // The thin image: the Kolla image with Ceph's packages for the client.
  private val containerfile =
    """ARG BASE
      |FROM ${BASE}
      |ARG RUN_USER=root
      |ARG CEPH_REPO
      |USER root
      |COPY ceph-release.asc /etc/apt/keyrings/ceph-release.asc
      |RUN echo "deb [signed-by=/etc/apt/keyrings/ceph-release.asc] ${CEPH_REPO}/ noble main" > /etc/apt/sources.list.d/ceph-upstream.list \
      | && apt-get update \
      | && pkgs="" \
      | && for p in @PACKAGES@; do if dpkg -s "$p" >/dev/null 2>&1; then pkgs="$pkgs $p"; fi; done \
      | && echo "upgrading:$pkgs" \
      | && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgs \
      | && apt-get clean && rm -rf /var/lib/apt/lists/*
      |LABEL orphera.ceph-client="${CEPH_REPO}"
      |USER ${RUN_USER}
      |""".stripMargin.replace("@PACKAGES@", cephPackages)

  // Builds and pushes one image on tst21.
  private def buildScript(image: String): String =
    helpers +
      """NAME='@NAME@'; TAG='@TAG@'; REPO='@REPO@'; WANT='@VERSION@'
        |curl -sf http://localhost:5000/v2/ >/dev/null || fail "no image registry on localhost:5000 (kolla_build.scala sets it up)"
        |curl -fsI "$REPO/dists/noble/Release" >/dev/null || fail "$REPO has no packages for Ubuntu noble (set CEPH_CLIENT_REPO)"
        |W=/var/lib/orphera/ceph-client-images/$NAME
        |mkdir -p "$W"
        |curl -fsSL https://download.ceph.com/keys/release.asc -o "$W/ceph-release.asc"
        |cat > "$W/Containerfile" <<'CONTAINERFILE'
        |@CONTAINERFILE@CONTAINERFILE
        |# The Kolla image straight from quay.io: an empty registries config, so that no mirror (tst21's own
        |# registry, which may already hold a built image) is used. podman pull, not skopeo copy into
        |# containers-storage: podman build refuses a Docker-format image copied that way ("unsupported
        |# MIME type for compression").
        |BASE="quay.io/openstack.kolla/$NAME:$TAG"
        |: > "$W/registries.conf"
        |echo "pulling $BASE from quay.io"
        |CONTAINERS_REGISTRIES_CONF="$W/registries.conf" podman pull -q "$BASE" >/dev/null
        |U=$(podman image inspect -f '{{.Config.User}}' "$BASE")
        |podman build -q --pull=never --build-arg "BASE=$BASE" --build-arg "RUN_USER=${U:-root}" \
        |  --build-arg "CEPH_REPO=$REPO" -t "localhost/orphera-ceph/$NAME:$TAG" "$W" >/dev/null
        |V=$(podman run --rm --user root --entrypoint dpkg-query "localhost/orphera-ceph/$NAME:$TAG" -W -f='${Version}' librados2)
        |case "$V" in "$WANT"*) ;; *) fail "the built $NAME image has librados2 $V, not $WANT" ;; esac
        |podman push -q --tls-verify=false "localhost/orphera-ceph/$NAME:$TAG" "docker://localhost:5000/openstack.kolla/$NAME:$TAG"
        |echo "$NAME:$TAG with librados2 $V (user ${U:-root}) pushed to tst21's registry"
        |""".stripMargin
        .replace("@NAME@", image)
        .replace("@TAG@", tag)
        .replace("@REPO@", repo)
        .replace("@VERSION@", version)
        .replace("@CONTAINERFILE@", containerfile)

  // Pulls the new image on a service node, checks it, restarts the service.
  private def useScript(image: String): String =
    helpers +
      """NAME='@NAME@'; REF='quay.io/openstack.kolla/@NAME@:@TAG@'; WANT='@VERSION@'
        |podman pull -q "$REF" >/dev/null
        |V=$(podman run --rm --user root --entrypoint dpkg-query "$REF" -W -f='${Version}' librados2)
        |case "$V" in "$WANT"*) ;; *) fail "the pulled $NAME image has librados2 $V, not $WANT: is tst21 first in /etc/containers/registries.conf.d/orphera-kolla-cache.conf?" ;; esac
        |systemctl restart "orphera-$NAME"
        |sleep 15
        |systemctl is-active --quiet "orphera-$NAME" || { journalctl -u "orphera-$NAME" -n 30 --no-pager; fail "orphera-$NAME did not stay up on the new image"; }
        |echo "$NAME restarted on librados2 $(podman exec "$NAME" dpkg-query -W -f='${Version}' librados2)"
        |""".stripMargin
        .replace("@NAME@", image)
        .replace("@TAG@", tag)
        .replace("@VERSION@", version)

  // Connects to the cluster from inside the container, as the service does.
  private def connectScript(image: String, user: String): String =
    helpers +
      s"""podman exec $image /usr/bin/python3 -c "import rados; r = rados.Rados(conffile='/etc/ceph/ceph.conf', name='client.$user', conf={'keyring': '/etc/ceph/ceph.client.$user.keyring'}); r.connect(timeout=20); print('client.$user connects from $image, cluster', r.get_fsid()); r.shutdown()"
         |""".stripMargin

  // The same packages on a compute host, for QEMU's RBD driver.
  private val hostScript =
    helpers +
      """REPO='@REPO@'; WANT='@VERSION@'
        |mkdir -p /etc/apt/keyrings
        |curl -fsSL https://download.ceph.com/keys/release.asc -o /etc/apt/keyrings/ceph-release.asc
        |echo "deb [signed-by=/etc/apt/keyrings/ceph-release.asc] $REPO/ noble main" > /etc/apt/sources.list.d/ceph-upstream.list
        |apt-get update -q >/dev/null
        |pkgs=""
        |for p in @PACKAGES@; do if dpkg -s "$p" >/dev/null 2>&1; then pkgs="$pkgs $p"; fi; done
        |echo "upgrading:$pkgs"
        |DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends $pkgs >/dev/null
        |V=$(dpkg-query -W -f='${Version}' librbd1)
        |case "$V" in "$WANT"*) ;; *) fail "librbd1 is $V, not $WANT" ;; esac
        |echo "librbd1 $V; running VMs keep their old library until they are restarted"
        |""".stripMargin
        .replace("@REPO@", repo)
        .replace("@VERSION@", version)
        .replace("@PACKAGES@", cephPackages)

  def script(using orphera: Orphera): IO[Unit] =
    val build = orphera.node(builder)
    val images = services.map(_.image).distinct
    for
      _ <- images.foldLeft(IO.unit)((before, image) =>
        before >> build.sh(s"build $image with Ceph $version", buildScript(image), timeout = 1800).void
      )
      _ <- orphera.nodes(services.map(_.node).distinct*).each { node =>
        services.filter(_.node == node.name).foldLeft(IO.unit)((before, s) =>
          before >>
            node.sh(s"run ${s.image} on the new image", useScript(s.image), timeout = 600).void >>
            s.cephUser.fold(IO.unit)(user =>
              node.query(s"client.$user connects to Ceph", connectScript(s.image, user), 60).flatMap(r =>
                IO.raiseWhen(r.exitCode != 0)(
                  new IllegalStateException(s"${node.name}: client.$user cannot connect from ${s.image} (see above)")
                )
              )
            )
        )
      }
      _ <- orphera.nodes(computeHosts*).each(node =>
        node.sh(s"Ceph $version client on the host", hostScript, timeout = 900)
      )
      _ <- IO.println(
        s"[$builder] Ceph clients on $version: ${services.map(s => s"${s.node} ${s.image}").mkString(", ")}; hosts ${computeHosts.mkString(", ")}"
      )
    yield ()
