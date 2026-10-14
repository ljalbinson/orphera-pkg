// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

// PROTOTYPE. A `Resource[A]` is something the playbook puts on a node as a file
// whose content is known in advance: a systemd unit, a config file. One
// description of it gives the task that writes it and the task that checks it,
// so a check cannot disagree with what the playbook applies.
//
//   import orphera.orchestrator.Resource.*
//   tasks += NamedTask("write the unit", placementApi.write)      // apply
//   tasks += NamedTask("check the unit", placementApi.check)      // report drift
//
// `check` changes nothing on the node. It fails, with a unified diff, when the
// file differs from what `write` would produce, and fails with its own message
// when the file is missing. A new kind of resource is a `given Resource[X]`; the
// orchestrator and the agent do not change.

trait Resource[A]:
  /** Short label for messages, e.g. the unit name. */
  def label(a: A): String

  /** Where the file lives on the node. */
  def path(a: A): String

  /** Exactly what the file should contain. Pure, so it can be tested with no node. */
  def content(a: A): String

  def owner(a: A): String = "root"
  def group(a: A): String = "root"
  def mode(a: A): Int = 420 // 0644

  /** The task that writes the file. */
  def write(a: A): Task =
    Task.WriteFile(content(a), path(a), owner(a), group(a), mode(a))

  /** The task that compares the file on the node with `content`. Exit 0 in
    * sync, 1 differs (diff on stderr), 2 missing. Does not write anything.
    */
  def check(a: A): Task = Resource.checkTask(label(a), path(a), content(a))

object Resource:
  extension [A](a: A)(using r: Resource[A])
    def write: Task = r.write(a)
    def check: Task = r.check(a)

  private val Marker = "ORPHERA_EXPECTED_EOF"

  /** The comparison, as a command for the node. The expected text is a quoted
    * here-document, so nothing in it is expanded by the shell.
    */
  def checkTask(label: String, path: String, expected: String): Task =
    require(!expected.contains(Marker), s"content of $label contains $Marker")
    val body = if expected.endsWith("\n") then expected else expected + "\n"
    val script =
      s"""if [ ! -f $path ]; then
         |  echo "MISSING $label: $path does not exist" >&2
         |  exit 2
         |fi
         |if diff -u $path - <<'$Marker'
         |$body$Marker
         |then
         |  echo "in sync: $label"
         |else
         |  echo "DRIFT $label: $path differs from what the playbook writes (diff above, - is the node, + is the playbook)" >&2
         |  exit 1
         |fi""".stripMargin
    Task.RunCommand(List("sh", "-c", script), timeoutSeconds = 30)

/** The result of comparing expected content with what a node has. Pure, for
  * tests and for callers that read the file themselves.
  */
enum Drift:
  case InSync
  case Missing
  case Differs(firstDifference: String)

object Drift:
  def compare(expected: String, actual: Option[String]): Drift =
    actual match
      case None => Missing
      case Some(a) if a == expected => InSync
      case Some(a) =>
        val e = expected.linesIterator.toList
        val l = a.linesIterator.toList
        val i = e.zipAll(l, "<end>", "<end>").indexWhere((x, y) => x != y)
        val (want, have) = e.zipAll(l, "<end>", "<end>")(i)
        Differs(s"line ${i + 1}: expected `$want`, found `$have`")
