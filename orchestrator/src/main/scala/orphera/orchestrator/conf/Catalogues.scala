// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.conf

import java.nio.file.{Files, Path, Paths}

/** Loads a project's `Catalogue` from `catalogues/<project>-<release>.json`,
  * made by `manifests/dump_oslo_options.sh` and slimmed by
  * `manifests/slim_catalogue.py`. The directory is `catalogues` in the working
  * directory, or `$ORPHERA_CATALOGUES`.
  *
  * `openSections` are sections that also take options the image does not
  * declare. `[keystone_authtoken]` always does: keystonemiddleware takes the
  * options of the auth plugin named by `auth_type` (`auth_url`, `username`,
  * `password` ...). A backend group named at run time by `enabled_backends`
  * (`[ceph]` in Cinder and Glance) is another. An open section still checks the
  * options the catalogue knows, and refuses a near miss of one of them.
  */
object Catalogues:
  val Release = "2025.1-ubuntu-noble"

  val AlwaysOpen: Set[String] = Set("keystone_authtoken")

  def dir: Path =
    Paths.get(sys.env.getOrElse("ORPHERA_CATALOGUES", "catalogues"))

  def load(
      project: String,
      openSections: Set[String] = Set.empty,
      from: Path = dir
  ): Catalogue =
    val file = from.resolve(s"$project-$Release.json")
    if !Files.exists(file) then
      throw new RuntimeException(
        s"$file not found: run manifests/dump_oslo_options.sh <image> on a node, slim it with manifests/slim_catalogue.py, " +
          "and put it there (or set ORPHERA_CATALOGUES)"
      )
    OsloJson
      .load(project, Files.readString(file), AlwaysOpen ++ openSections)
      .fold(e => throw new RuntimeException(s"$file: $e"), identity)
