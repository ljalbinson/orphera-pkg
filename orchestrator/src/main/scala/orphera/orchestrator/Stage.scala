// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

/** A health gate a stage waits on before the next stage is allowed to start.
  * Scoped deliberately narrow for now: "keep checking this file on this node
  * until CheckFile reports it's in the expected state, or time out" — reusing
  * the existing CheckFile RPC as a sentinel/readiness probe rather than
  * inventing a new mechanism. A genuine arbitrary-command health-check task
  * type is a natural follow-up once one exists in the Task ADT at all.
  */
case class HealthCheck(
    onNode: String,
    sentinelPath: String,
    expectedSha256: String,
    pollIntervalSeconds: Int = 5,
    timeoutSeconds: Int = 120
)

case class Stage(
    name: String,
    nodeNames: List[String],
    tasks: List[NamedTask],
    waitFor: Option[HealthCheck] = None
)

case class ClusterPlaybook(name: String, stages: List[Stage])
