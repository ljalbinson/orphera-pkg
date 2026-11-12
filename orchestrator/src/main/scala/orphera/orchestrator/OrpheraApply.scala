// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.{IO, IOApp}

/** A declarative description, as a file `orphera apply` runs
  * (docs/APPLY-DESIGN.md):
  *
  * {{{
  * object chrony extends OrpheraApply:
  *   override def nodes = List("tst8")
  *   def describe(node: Node) =
  *     import Resources.*
  *     val restart = command("restart chronyd", "systemctl restart chronyd", onChangeOnly = true)
  *     List(file("/etc/chrony.conf", chronyConf, mode = "0644").notifies(restart), restart)
  * }}}
  *
  * `describe` is called for each node, so the description can use the node's
  * name and inventory vars. `orphera apply <file> [--nodes a,b] [--check]
  * [--stop-on-failure]`: the nodes are `--nodes`, or else `nodes`; they are
  * applied in parallel, each resource on a node in order.
  */
trait OrpheraApply extends IOApp.Simple:

  /** The nodes when `orphera apply` is given no `--nodes`. */
  def nodes: List[String] = Nil

  /** The resources wanted on `node`. */
  def describe(node: Node): List[ApplyResource]

  def name: String = getClass.getSimpleName.stripSuffix("$")

  // Set by `orphera apply` (Main.runScalaPlaybookScript), as ORPHERA_RESUME is for playbooks.
  def run: IO[Unit] =
    val check = sys.env.get("ORPHERA_CHECK").contains("true")
    val stopOnFailure = sys.env.get("ORPHERA_STOP_ON_FAILURE").contains("true")
    val chosen = sys.env
      .get("ORPHERA_APPLY_NODES")
      .map(_.split(",").toList.map(_.trim).filter(_.nonEmpty))
      .filter(_.nonEmpty)
      .getOrElse(nodes)
    for
      _ <- IO.raiseWhen(chosen.isEmpty)(
        new PlaybookFailed(s"'$name' names no nodes: give --nodes, or override `nodes`")
      )
      _ <- IO.println(s"Applying '$name' to ${chosen.mkString(", ")}${if check then " (check: nothing will be changed)" else ""}")
      _ <- Orphera.run(s"apply-$name", resume = false, kind = "Apply") { orphera =>
        orphera
          .nodes(chosen*)
          .each(n => IO.defer(Apply.plan(name, describe(n.node))).flatMap(plan => n.apply(plan, check, stopOnFailure)))
          .void
      }
    yield ()
