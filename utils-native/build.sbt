// SPDX-License-Identifier: Apache-2.0
//
// Scripts of utils/ in Scala, compiled to native executables with Scala Native: vmctl (utils/vmctl.sh) and
// move-vm (utils/move-vm.sh). A build of its own, not part of the main build: the main build, `make test` and
// CI never need the Scala Native toolchain (clang), and the executables need no JVM at run time.
//
//   make vmctl          # sbt vmctl/nativeLink here, then installs the executable as utils/vmctl
//   make move-vm        # sbt moveVm/nativeLink, installed as utils/move-vm
//   make native-utils   # both
//
// Each executable finds utils/ as its own directory, so it must sit there, beside the shell version. No library
// dependencies: only the Scala and Java standard libraries (and Scala Native's POSIX bindings).

import scala.scalanative.build._

ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := IO.read(file("../VERSION")).trim

lazy val settings = Seq(
  scalacOptions ++= Seq("-Werror", "-Wunused:all", "-deprecation", "-feature")
)

def executable(base: String) = Seq(
  nativeConfig ~= {
    _.withBaseName(base)
      .withMode(Mode.releaseFast)
      .withGC(GC.immix)
  }
)

// Sources (orphera-sources.sh and vm-common.sh), Proc (running commands) and Out
lazy val shared = (project in file("shared"))
  .enablePlugins(ScalaNativePlugin)
  .settings(settings)

lazy val vmctl = (project in file("vmctl"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared)
  .settings(settings, executable("vmctl"), Compile / mainClass := Some("orphera.vmctl.Main"))

lazy val moveVm = (project in file("move-vm"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared)
  .settings(settings, executable("move-vm"), Compile / mainClass := Some("orphera.movevm.Main"))

lazy val root = (project in file("."))
  .aggregate(shared, vmctl, moveVm)
  .settings(name := "utils-native", publish / skip := true)
