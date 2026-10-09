// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import java.nio.file.{Files, Paths}

/** Which system package manager this host uses, decided by what is installed
  * rather than by distribution name, so derivatives (Rocky, Alma, CentOS
  * Stream, Fedora; Debian, Ubuntu, Mint) need no list of names.
  */
object PackageManager:

  enum Kind:
    case Apt, Dnf

  def detect: IO[Option[Kind]] =
    IO.blocking {
      def has(path: String): Boolean = Files.isExecutable(Paths.get(path))
      if has("/usr/bin/apt-get") then Some(Kind.Apt)
      else if has("/usr/bin/dnf") then Some(Kind.Dnf)
      else None
    }
