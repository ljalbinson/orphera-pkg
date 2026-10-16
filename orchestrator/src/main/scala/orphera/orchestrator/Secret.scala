// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** A secret value (a password, a key) that does not print.
  *
  * `toString`, string interpolation (`s"$password"`) and a log line all give
  * `<redacted>`; the text is only available through `reveal`, so every place a
  * playbook puts a secret into a command or a file shows up as `.reveal` in the
  * source and can be found with a search. Forgetting it does not leak the value:
  * it writes `<redacted>` where the password should be, and the service then
  * refuses it.
  *
  * Make one with `Secrets.secret("service/name")`, which reads the encrypted
  * store and also registers the value so that anything echoed back from a node
  * is masked.
  */
final class Secret(private val text: String):
  /** The text of the secret. Use it where the value is needed, nowhere else. */
  def reveal: String = text

  override def toString: String = Secrets.Placeholder

  /** Compared without stopping at the first difference. */
  override def equals(other: Any): Boolean = other match
    case s: Secret => MessageDigest.isEqual(text.getBytes(UTF_8), s.reveal.getBytes(UTF_8))
    case _         => false

  /** The same for every secret, so a hash never says anything about the value. */
  override def hashCode: Int = 0

object Secret:
  def apply(text: String): Secret = new Secret(text)
