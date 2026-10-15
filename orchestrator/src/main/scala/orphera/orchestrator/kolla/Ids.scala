// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.kolla

// Small checked values for `Container`. They are opaque types in a file of their
// own, so outside it a `ContainerName` is not a `String` and a `TcpPort` is not an
// `Int`: the only way to make one is through `apply` or `parse`, which check the
// value. A bad name or port can no longer be built, instead of being caught by a
// `require` somewhere later. They cost nothing at run time.

/** The name of a container and of its unit, `orphera-<name>.service`: lowercase
  * letters, digits and dashes, starting with a letter or digit.
  */
opaque type ContainerName = String

object ContainerName:
  private val Valid = "[a-z0-9][a-z0-9-]*"

  def parse(s: String): Either[String, ContainerName] =
    if s.matches(Valid) then Right(s)
    else
      Left(s"container name must be lowercase letters, digits and dashes: $s")

  /** Throws `IllegalArgumentException` for an invalid name. */
  def apply(s: String): ContainerName =
    parse(s).fold(msg => throw IllegalArgumentException(msg), identity)

  extension (n: ContainerName) def value: String = n

/** A TCP port number, 1 to 65535. */
opaque type TcpPort = Int

object TcpPort:
  def parse(n: Int): Either[String, TcpPort] =
    if n >= 1 && n <= 65535 then Right(n)
    else Left(s"not a TCP port (1-65535): $n")

  /** Throws `IllegalArgumentException` for a number outside 1 to 65535. */
  def apply(n: Int): TcpPort =
    parse(n).fold(msg => throw IllegalArgumentException(msg), identity)

  extension (p: TcpPort) def value: Int = p
