package orphera.orchestrator

case class Node(
    name: String,
    host: String,
    port: Int = 50051
)

object Inventory:
  val all =
    List(
      Node("local-agent", "127.0.0.1")
    )
