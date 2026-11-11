// SPDX-License-Identifier: Apache-2.0
//
// Scripts of utils/ in Scala, compiled to native executables with Scala Native, each named as its shell version
// without .sh: vmctl, move-vm, start-vm, stop-vm, reboot-vm, list-vms, galera-cold-start and scan-vm-sizes. A
// build of its own, not part of the main build: the main build, `make test` and CI never need the Scala Native
// toolchain (clang), and the executables need no JVM at run time.
//
//   make native-utils   # all of them, in one sbt run (utils/build-native.sh), installed in utils/
//   make vmctl          # one (each has its own target: make move-vm, make galera-cold-start, ...)
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

lazy val startVm = (project in file("start-vm"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared)
  .settings(settings, executable("start-vm"), Compile / mainClass := Some("orphera.startvm.Main"))

lazy val stopVm = (project in file("stop-vm"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared)
  .settings(settings, executable("stop-vm"), Compile / mainClass := Some("orphera.stopvm.Main"))

lazy val rebootVm = (project in file("reboot-vm"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared)
  .settings(settings, executable("reboot-vm"), Compile / mainClass := Some("orphera.rebootvm.Main"))

lazy val listVms = (project in file("list-vms"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared)
  .settings(settings, executable("list-vms"), Compile / mainClass := Some("orphera.listvms.Main"))

lazy val galeraColdStart = (project in file("galera-cold-start"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared)
  .settings(
    settings,
    executable("galera-cold-start"),
    Compile / mainClass := Some("orphera.galeracoldstart.Main")
  )

lazy val scanVmSizes = (project in file("scan-vm-sizes"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared)
  .settings(settings, executable("scan-vm-sizes"), Compile / mainClass := Some("orphera.scanvmsizes.Main"))

lazy val root = (project in file("."))
  .aggregate(shared, vmctl, moveVm, startVm, stopVm, rebootVm, listVms, galeraColdStart, scanVmSizes)
  .settings(name := "utils-native", publish / skip := true)
