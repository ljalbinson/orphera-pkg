// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.common.Facts

/** Facts for every node targeted by a playbook run, gathered once up front
  * rather than per-node — this is what lets one node's template reference
  * another node's facts (e.g. a mgr host's config needing every mon host's IP
  * address), which per-node-only fact gathering can't express at all.
  */
case class ClusterContext(factsByNode: Map[String, Facts])
