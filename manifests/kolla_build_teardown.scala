// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes kolla_build.scala on tst21, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): stops
// the registry and devpi, and removes the helper, the kolla-build and devpi installations, the unit files and
// the config. The data is kept unless KOLLA_BUILD_PURGE=yes is set in the environment of the run: the built
// images (/var/lib/orphera/kolla-registry), the source tarballs (/srv/kolla-sources) and the pip cache
// (/var/lib/orphera/devpi) are what takes hours to make again. podman, nginx and skopeo stay installed. Nodes
// keep their mirror setting for tst21; podman goes on to tst9 and quay.io when tst21 does not answer.
//
//   orphera apply manifests/kolla_build_teardown.scala --check
//   orphera apply manifests/kolla_build_teardown.scala
//   KOLLA_BUILD_PURGE=yes orphera apply manifests/kolla_build_teardown.scala    # also deletes the data
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing.
object kolla_build_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst21"
  private val purge = sys.env.get("KOLLA_BUILD_PURGE").contains("yes")
  private val units = "orphera-kolla-registry orphera-devpi"

  override def nodes: List[String] = List(node)

  // True when ANY unit has a unit file, or is active or enabled, or the registry container exists.
  private val anyUnit =
    s"""for u in $units; do if [ -f /etc/systemd/system/$$u.service ] || systemctl is-active --quiet $$u || systemctl is-enabled --quiet $$u; then exit 0; fi; done
       |command -v podman >/dev/null 2>&1 && podman container exists kolla-registry && exit 0
       |exit 1""".stripMargin
  private val stopScript =
    s"""systemctl disable --now $units 2>/dev/null || true
       |rm -f /etc/systemd/system/orphera-kolla-registry.service /etc/systemd/system/orphera-devpi.service
       |systemctl daemon-reload
       |if command -v podman >/dev/null 2>&1; then podman rm -f kolla-registry 2>/dev/null || true; fi
       |echo "registry and devpi stopped, units removed"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val stopped = command(
      "stop the Kolla registry and devpi and remove their units",
      stopScript,
      onlyIf = anyUnit,
      timeout = 120
    )
    // nginx is reloaded only when the site file was there to remove.
    val reloadNginx = command(
      "reload nginx after its Kolla sources site is gone",
      "systemctl reload nginx 2>/dev/null || true",
      onChangeOnly = true
    )
    val cleaned = List(
      absentFile("/etc/nginx/conf.d/orphera-kolla-sources.conf")
        .notifies(reloadNginx),
      absentFile(
        "/etc/containers/registries.conf.d/orphera-kolla-registry.conf"
      ),
      absentFile("/usr/local/sbin/orphera-kolla-build"),
      absentDirectory("/opt/kolla-build", recursive = true),
      absentDirectory("/opt/devpi", recursive = true),
      absentDirectory("/etc/kolla-build", recursive = true)
    ).map(_.after(stopped))
    val installed = cleaned :+ reloadNginx.after(cleaned*)
    if purge then
      // The built images, source tarballs and pip cache (KOLLA_BUILD_PURGE=yes).
      val purged = List(
        "/var/lib/orphera/kolla-registry",
        "/srv/kolla-sources",
        "/var/lib/orphera/devpi",
        "/var/lib/orphera/kolla-build"
      ).map(d => absentDirectory(d, recursive = true).after(installed*))
      List(stopped) ++ installed ++ purged
    else List(stopped) ++ installed
