// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes skyline_database.scala and skyline_single_node.scala, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): removes the Keystone user `skyline` through the helper installed by the main
// description if it is still there, stops and removes the container, its unit, config, state (including the
// PyMySQL directory, the copy of the certificate and the helper) on tst13, and drops the skyline database and
// user on tst3. podman, the image, the certificate from dashboard_tls.scala and Keystone's CA stay, as do the
// secrets (skyline/db, skyline/service, skyline/secret-key). Horizon is not touched. Keep the names in step
// with skyline_single_node.
//
//   orphera apply manifests/skyline_teardown.scala --check
//   orphera apply manifests/skyline_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing. A database that cannot be queried fails the step instead of being skipped.
object skyline_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val dbNode = "tst3"
  private val dbName = "skyline"
  private val dbUser = "skyline"
  private val helper = "/usr/local/sbin/orphera-skyline-api"

  override def nodes: List[String] = List(dbNode, node)

  private val anyUnit =
    """[ -f /etc/systemd/system/orphera-skyline.service ] || systemctl is-active --quiet orphera-skyline || systemctl is-enabled --quiet orphera-skyline || { command -v podman >/dev/null 2>&1 && { podman container exists skyline || podman container exists skyline_bootstrap; }; }"""
  private val stopScript =
    """systemctl disable --now orphera-skyline 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-skyline.service
      |systemctl daemon-reload
      |if command -v podman >/dev/null 2>&1; then
      |  # One container at a time: with a name that does not exist in the same call, podman can leave the others.
      |  for c in skyline skyline_bootstrap; do
      |    if podman container exists $c; then podman rm -f $c; fi
      |  done
      |  for c in skyline skyline_bootstrap; do
      |    if podman container exists $c; then echo "container $c is still there" >&2; exit 1; fi
      |  done
      |fi
      |echo "skyline container and unit removed"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        absentSqlDatabase(
          s"drop the $dbName database and the $dbUser user on the Galera cluster",
          List(dbName),
          dbUser
        )
      )
    else
      val unregistered = absentKeystoneEntries(
        "remove the Skyline user from Keystone",
        "skyline"
      )
      val stopped = command(
        "stop and remove the skyline container and unit",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unregistered)
      // Typed removal, no script: the three directories with their contents, the bootstrap log, the helper.
      val cleaned = List(
        absentDirectory("/etc/skyline", recursive = true),
        absentDirectory("/var/lib/orphera/skyline", recursive = true),
        absentDirectory("/var/log/skyline", recursive = true),
        absentFile("/tmp/skyline-bootstrap.log"),
        absentFile(helper),
        absentFile(keystoneHelperPath("skyline"))
      ).map(_.after(stopped))
      List(unregistered, stopped) ++ cleaned
