// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

case class Node(
    name: String,
    host: String,
    port: Int = 50051
)

object Inventory:
  val all =
    List(
      Node("tst0", "tst0.ljalbinson.com"),
      Node("tst1", "tst1.ljalbinson.com"),
      Node("tst2", "tst2.ljalbinson.com"),
      Node("tst3", "tst3.ljalbinson.com"),
      Node("tst4", "tst4.ljalbinson.com"),
      Node("tst5", "tst5.ljalbinson.com")
    )
