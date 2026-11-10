// SPDX-License-Identifier: Apache-2.0
//
// utils/vmctl.sh in Scala, compiled to a native executable with Scala Native. A build of its own, not a
// project of the main build: the main build, `make test` and CI never need the Scala Native toolchain
// (clang, libunwind headers), and this one needs no JVM at run time.
//
//   make vmctl        # sbt nativeLink here, then installs the executable as utils/vmctl
//
// The executable finds the checkout as the parent of its own directory, as vmctl.sh does, so it must
// sit in utils/. No library dependencies: only the Scala and Java standard libraries.

import scala.scalanative.build._

ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := IO.read(file("../VERSION")).trim

lazy val vmctl = (project in file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "vmctl",
    scalacOptions ++= Seq("-Werror", "-Wunused:all", "-deprecation", "-feature"),
    Compile / mainClass := Some("orphera.vmctl.Main"),
    nativeConfig ~= {
      _.withBaseName("vmctl")
        .withMode(Mode.releaseFast)
        .withGC(GC.immix)
    }
  )
