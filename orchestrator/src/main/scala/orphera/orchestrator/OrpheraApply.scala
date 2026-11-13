// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.{IO, IOApp, Ref, std}

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
    OrpheraApply.runOn(name, chosen, describe, check, stopOnFailure, "override `nodes`")

object OrpheraApply:

  /** Applies the description `describe` (called per node) to `chosen`, in
    * parallel; fails if any node failed. Shared by `OrpheraApply` and YAML
    * descriptions. `nodesHint` says where nodes can be named, for the error
    * when there are none.
    */
  def runOn(
      name: String,
      chosen: List[String],
      describe: Node => List[ApplyResource],
      check: Boolean,
      stopOnFailure: Boolean,
      nodesHint: String
  ): IO[Unit] =
    for
      _ <- IO.raiseWhen(chosen.isEmpty)(
        new PlaybookFailed(s"'$name' names no nodes: give --nodes, or $nodesHint")
      )
      _ <- IO.println(s"Applying '$name' to ${chosen.mkString(", ")}${if check then " (check: nothing will be changed)" else ""}")
      lock <- std.Mutex[IO]
      reports <- Ref.of[IO, Map[String, ApplyReport]](Map.empty)
      // With several nodes, each node's lines come out together when it finishes, then a total.
      together = Option.when(chosen.size > 1)(lock)
      _ <- Orphera.run(s"apply-$name", resume = false, kind = "Apply") { orphera =>
        orphera
          .nodes(chosen*)
          .each(n =>
            IO.defer(Apply.plan(name, describe(n.node)))
              .flatMap(plan => n.applyGrouped(plan, check, stopOnFailure, together))
              .flatTap(r => reports.update(_ + (n.name -> r)))
          )
          .attempt
          .flatTap(_ => if chosen.size > 1 then reports.get.flatMap(rs => IO.println(total(chosen, rs, check))) else IO.unit)
          .rethrow
          .void
      }
    yield ()

  /** One line for all nodes: how many changed (or would), were unchanged, or
    * failed; a node with no report could not be read or was stopped.
    */
  private[orchestrator] def total(chosen: List[String], reports: Map[String, ApplyReport], check: Boolean): String =
    val done = chosen.flatMap(reports.get)
    val changed = done.count(r => !r.failed && r.changed)
    val unchanged = done.count(r => !r.failed && !r.changed)
    // A node whose apply failed raises rather than reporting, so it has no report.
    val failedNames = done.filter(_.failed).map(_.node) ++ chosen.filterNot(reports.contains)
    val parts = List(
      s"$changed ${if check then "to change" else "changed"}",
      s"$unchanged unchanged"
    ) ++ Option.when(failedNames.nonEmpty)(s"${failedNames.size} failed (${failedNames.mkString(", ")})")
    s"All ${chosen.size} nodes: ${parts.mkString(", ")}"
