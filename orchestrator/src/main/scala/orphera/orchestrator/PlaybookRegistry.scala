// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

object PlaybookRegistry:
  val all: Map[String, Playbook] = Map(
    "web-baseline" -> WebBaseline.playbook
  )
