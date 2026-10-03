// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

/** A health gate a stage waits on before the next stage is allowed to start.
  * Scoped deliberately narrow for now: "keep checking this file on this node
  * until CheckFile reports it's in the expected state, or time out" — reusing
  * the existing CheckFile RPC as a sentinel/readiness probe rather than
  * inventing a new mechanism. A genuine arbitrary-command health-check task
  * type is a natural follow-up once one exists in the Task ADT at all.
  */
enum HealthCheck:
  case Sentinel(
      onNode: String,
      sentinelPath: String,
      expectedSha256: String,
      pollIntervalSeconds: Int = 5,
      timeoutSeconds: Int = 120
  )
  case Command(
      onNode: String,
      command: List[String],
      pollIntervalSeconds: Int = 5,
      timeoutSeconds: Int = 120
  )

  /** Waits until at least `requiredCount` of `nodes` each report the given
    * command exiting 0 — a real quorum gate, as opposed to Command's
    * single-node check. Each poll round checks every node in `nodes` once; the
    * stage proceeds as soon as the healthy count reaches `requiredCount`,
    * without waiting for the rest.
    */
  case Quorum(
      nodes: List[String],
      command: List[String],
      requiredCount: Int,
      pollIntervalSeconds: Int = 5,
      timeoutSeconds: Int = 120
  )

  /** Polls `onNode` over plain SSH instead of Orphera's own agent RPC —
    * for exactly one situation: confirming a brand-new VM is alive before
    * it can possibly be running the Orphera agent yet (kvm_vm_provision.scala's
    * confirm-vm-reachable stage is the motivating case — its first attempt
    * used HealthCheck.Command, which goes through NodeClient to an agent
    * that cloud-init never installs, so every poll failed the same way
    * until the overall timeout gave up). SSH is the one thing a stock
    * cloud image's cloud-init guarantees is listening once boot finishes
    * (the users/ssh_authorized_keys block every cloud-init template in
    * this project already sets), so it's the only honest liveness probe
    * available at this point in a VM's life — deliberately NOT a vehicle
    * for installing or starting the agent itself; that stays a separate,
    * explicit `orphera bootstrap` step run by hand afterward, same as
    * every other node in this project's history.
    */
  case Ssh(
      onNode: String,
      sshUser: String,
      sshKeyPath: Option[String] = None,
      remoteCommand: String = "true",
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
