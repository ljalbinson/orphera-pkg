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

  /** Keys, in `key = value` lines, whose values are secret. The check never
    * sends them to the node and never prints them; see `Resource.checkTask`.
    */
  def secretKeys(a: A): List[String] = Nil

  /** The secret values themselves, to be hidden from the orchestrator's output
    * wherever a command or its output echoes them (see `Secrets`).
    */
  def secretValues(a: A): List[String] = Nil

  /** The task that writes the file. */
  def write(a: A): Task =
    Secrets.registerAll(secretValues(a))
    Task.WriteFile(content(a), path(a), owner(a), group(a), mode(a))

  /** The task that compares the file on the node with `content`. Exit 0 in
    * sync, 1 differs (diff on stderr), 2 missing. Does not write anything.
    */
  def check(a: A): Task =
    Secrets.registerAll(secretValues(a))
    Resource.checkTask(label(a), path(a), content(a), secretKeys(a))

object Resource:
  extension [A](a: A)(using r: Resource[A])
    def write: Task = r.write(a)
    def check: Task = r.check(a)

  private val Marker = "ORPHERA_EXPECTED_EOF"

  private val KeyChars = "[A-Za-z0-9_.-]+".r

  /** `text` with the value of every `key = value` line for the given keys
    * replaced by `<redacted>`. The node does the same to its own file with sed,
    * so the two can be diffed without either side showing a secret.
    */
  def redact(text: String, secretKeys: List[String]): String =
    if secretKeys.isEmpty then text
    else
      val patterns = secretKeys.map { k =>
        require(KeyChars.matches(k), s"secret key $k has characters other than letters, digits, _ . -")
        java.util.regex.Pattern.compile("^(" + java.util.regex.Pattern.quote(k) + "[ \\t]*=[ \\t]*).*$")
      }
      text.linesWithSeparators.map { line =>
        val body = line.stripSuffix("\n")
        val nl = line.substring(body.length)
        patterns.find(_.matcher(body).matches()) match
          case Some(p) => p.matcher(body).replaceFirst("$1" + Secrets.Placeholder) + nl
          case None    => line
      }.mkString

  private def sedFor(secretKeys: List[String]): String =
    if secretKeys.isEmpty then "cat"
    else
      "sed -E " + secretKeys
        .map { k =>
          require(KeyChars.matches(k), s"secret key $k has characters other than letters, digits, _ . -")
          val escaped = k.replace(".", "\\.")
          s"-e 's/^($escaped[[:space:]]*=[[:space:]]*).*$$/\\1${Secrets.Placeholder}/'"
        }
        .mkString(" ")

  def sha256(text: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** The comparison, as a command for the node.
    *
    * It first compares a SHA-256 of the whole file with the expected one, so the
    * secret values are never in the command. Only when they differ does it show a
    * diff, and then both sides have the values of `secretKeys` replaced by
    * `<redacted>`: the expected text is redacted here, the node's file by sed. If
    * the redacted texts agree but the hashes do not, a secret value differs and
    * the message says so without showing it. The expected text is a quoted
    * here-document, so nothing in it is expanded by the shell.
    */
  def checkTask(label: String, path: String, expected: String, secretKeys: List[String] = Nil): Task =
    require(!expected.contains(Marker), s"content of $label contains $Marker")
    val shown = redact(expected, secretKeys)
    val body = if shown.endsWith("\n") then shown else shown + "\n"
    val script =
      s"""if [ ! -f $path ]; then
         |  echo "MISSING $label: $path does not exist" >&2
         |  exit 2
         |fi
         |if [ "$$(sha256sum < $path | cut -d' ' -f1)" = "${sha256(expected)}" ]; then
         |  echo "in sync: $label"
         |  exit 0
         |fi
         |T=$$(mktemp)
         |trap 'rm -f "$$T"' EXIT
         |cat > "$$T" <<'$Marker'
         |$body$Marker
         |if ${sedFor(secretKeys)} $path | diff -u --label "node: $path" --label "playbook" - "$$T"; then
         |  echo "DRIFT $label: a secret value in $path differs from what the playbook writes (values are not shown)" >&2
         |else
         |  echo "DRIFT $label: $path differs from what the playbook writes (diff above, - is the node, + is the playbook)" >&2
         |fi
         |exit 1""".stripMargin
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
