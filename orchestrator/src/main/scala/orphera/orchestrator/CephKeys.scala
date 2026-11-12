// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.util.Base64

import scala.util.Try

/** Cephx keys as text (`AQ...`, `Ag...`): base64 of a 12-byte header (the key
  * type as a little-endian u16, a creation time as two u32, the secret's length
  * as a u16) and the secret.
  *
  * Type 1 is the old `aes`; Ceph 19.2.6 and 20.2.4 added `aes256k` (the
  * CVE-2025-30156 fix), which the monitors may be set to require
  * (`auth_allowed_ciphers aes256k`). A playbook that imports an `aes` key has to
  * allow `aes` first; one that imports an `aes256k` key must not, or it undoes
  * that setting.
  */
object CephKeys:
  val Aes = 1

  private def bytes(key: String): Option[Array[Byte]] =
    Try(Base64.getDecoder.decode(key.trim)).toOption

  private def u16(b: Array[Byte], at: Int): Int =
    (b(at) & 0xff) | ((b(at + 1) & 0xff) << 8)

  /** The key's type, if `key` is a whole cephx key. */
  def keyType(key: String): Option[Int] =
    bytes(key).filter(b => b.length > 12 && b.length == 12 + u16(b, 10)).map(u16(_, 0))

  /** Whether `key` is a whole cephx key (any type). */
  def isKey(key: String): Boolean = keyType(key).isDefined

  /** Whether `key` is an old `aes` key. */
  def isAes(key: String): Boolean = keyType(key).contains(Aes)

  /** The shell line a playbook runs before importing `key` for `entity`: allows
    * `aes` alongside `aes256k` for an `aes` key, and otherwise leaves the
    * monitors' setting as it is (says so).
    */
  def allowCiphersFor(key: String, entity: String): String =
    if isAes(key) then "cephadm shell -- ceph mon set auth_allowed_ciphers aes,aes256k"
    else s"echo '$entity has an aes256k key: the cephx cipher setting is left as it is'"
