// SPDX-License-Identifier: Apache-2.0

// Scala Native publishes its compiler plugin for each new Scala version without a new release; if
// sbt cannot find nscplugin for the scalaVersion in ../build.sbt, raise this to the newest 0.5.x.
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")
