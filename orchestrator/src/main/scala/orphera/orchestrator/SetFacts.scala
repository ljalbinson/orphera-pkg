// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*

/** Per-node, per-run storage for values set via Task.SetFact. Scoped to a
  * single playbook/stage invocation — nothing here persists beyond one
  * PlaybookRunner.run/ClusterPlaybookRunner.run call.
  *
  * Unlike ClusterContext (real gathered facts, snapshotted once up front), this
  * store is mutable and shared across all nodes in the same run — which is what
  * makes cross-node set_fact references possible: a value set on node A becomes
  * visible to node B's later templates/conditions via `snapshot`, as long as
  * A's task that set it has already completed by the time B reads it. There is
  * no ordering guarantee between nodes running in parallel — see
  * PlaybookRunner/ClusterPlaybookRunner for how this is surfaced.
  */
final class SetFacts private (ref: Ref[IO, Map[String, Map[String, String]]]):

  def set(node: String, key: String, value: String): IO[Unit] =
    ref.update(m =>
      m.updated(node, m.getOrElse(node, Map.empty).updated(key, value))
    )

  def get(node: String): IO[Map[String, String]] =
    ref.get.map(_.getOrElse(node, Map.empty))

  /** A snapshot of every node's set-facts at the moment this is called — used
    * to expose other nodes' set_fact values under nodes.<name>.<key> in
    * templates/conditions.
    */
  def snapshot: IO[Map[String, Map[String, String]]] =
    ref.get

object SetFacts:
  def empty: IO[SetFacts] =
    Ref.of[IO, Map[String, Map[String, String]]](Map.empty).map(new SetFacts(_))
