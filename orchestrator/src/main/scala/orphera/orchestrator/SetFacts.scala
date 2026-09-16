// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*

/** Per-node, per-run storage for values set via Task.SetFact. Scoped
  * to a single playbook/stage invocation — nothing here persists
  * beyond one PlaybookRunner.run/ClusterPlaybookRunner.run call, and
  * nothing here is shared across nodes (a set_fact on node A is not
  * visible to node B — see ClusterContext for genuine cross-node
  * sharing of *gathered* facts, which set_fact does not participate
  * in).
  */
final class SetFacts private (ref: Ref[IO, Map[String, Map[String, String]]]):

  def set(node: String, key: String, value: String): IO[Unit] =
    ref.update(m => m.updated(node, m.getOrElse(node, Map.empty).updated(key, value)))

  def get(node: String): IO[Map[String, String]] =
    ref.get.map(_.getOrElse(node, Map.empty))

object SetFacts:
  def empty: IO[SetFacts] =
    Ref.of[IO, Map[String, Map[String, String]]](Map.empty).map(new SetFacts(_))
