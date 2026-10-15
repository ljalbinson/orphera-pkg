// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.kolla

/** Shell for pulling images with a retry.
  *
  * A pull goes through the Kolla cache and, behind it, quay.io; either can fail
  * for a moment (a 500 on one blob, a reset connection) without anything being
  * wrong with the playbook. A rebuild of the whole stack should not stop for
  * that. The function is part of the script a manifest sends, so it needs
  * nothing installed on the node.
  */
object Pull:
  val Attempts = 3
  val PauseSeconds = 15

  /** Defines `pull_retry`, which takes the arguments of `podman pull`. It tries
    * `Attempts` times, `pauseSeconds` apart, and returns non-zero if the last
    * try fails, after saying what failed. podman's own error is printed on each
    * try, above this message. `where` names the place to look if the image
    * reference itself is wrong; it must not contain a double quote, a backtick
    * or a `$`.
    */
  def shellFunction(where: String, pauseSeconds: Int = PauseSeconds): String =
    require(
      !where.exists(c => c == '"' || c == '`' || c == '$'),
      s"unsafe text in the pull hint: $where"
    )
    s"""pull_retry() {
       |  n=1
       |  until podman pull "$$@"; do
       |    if [ "$$n" -ge $Attempts ]; then
       |      echo "could not pull $$* after $Attempts attempts - see the podman error above; a registry or network failure, or a wrong image (check $where)" >&2
       |      return 1
       |    fi
       |    echo "pull of $$* failed (attempt $$n of $Attempts); trying again in $pauseSeconds s" >&2
       |    n=$$((n + 1))
       |    sleep $pauseSeconds
       |  done
       |}""".stripMargin
