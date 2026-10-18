import sbt._
import Keys._
import sbtassembly.AssemblyPlugin.autoImport._

ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := IO.read(file("VERSION")).trim
ThisBuild / scalacOptions ++= Seq(
  "-Werror",
  "-Wconf:src=src_managed/.*:silent",
  // Needed by scalafix RemoveUnused / OrganizeImports. With -Werror, any
  // unused import or parameter fails the build: `sbt scalafixAll` clears most.
  "-Wunused:all"
)

// scalafix: Scala 3 writes semanticdb itself, so no semanticdbVersion.
ThisBuild / semanticdbEnabled := true

// wartremover: errors for main code only (tests may use .get/.head). Var,
// Null, Throw, Any and NonUnitStatements are left off: the code has many
// legitimate uses (Java/Netty interop, Cats Effect).
lazy val wartSettings = Seq(
  Compile / compile / wartremoverErrors ++= Seq(
    Wart.AsInstanceOf,
    Wart.IsInstanceOf,
    Wart.Return,
    Wart.OptionPartial,
    Wart.TryPartial,
    Wart.EitherProjectionPartial,
    Wart.IterableOps,
    Wart.ArrayEquals,
    Wart.Enumeration,
    Wart.JavaConversions,
    Wart.StringPlusAny,
    Wart.FinalCaseClass
  ),
  // ScalaPB / fs2-grpc output and the generated BuildInfo
  wartremoverExcluded += (Compile / sourceManaged).value
)

lazy val common = project
  .settings(wartSettings)
  .enablePlugins(Fs2Grpc)
  .settings(
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion
    )
  )

lazy val agent = project
  .settings(wartSettings)
  .dependsOn(common)
  .settings(
    Compile / run / fork := true,
    Compile / mainClass := Some("orphera.agent.Main"),
    assembly / mainClass := Some("orphera.agent.Main"),
    libraryDependencies += "co.fs2" %% "fs2-io" % "3.11.0",
    Compile / sourceGenerators += Def.task {
      val outFile = (Compile / sourceManaged).value / "orphera" / "agent" / "BuildInfo.scala"
      val versionString = IO.read(file("VERSION")).trim
      IO.write(
        outFile,
        s"""// SPDX-License-Identifier: Apache-2.0
           |package orphera.agent
           |
           |object BuildInfo:
           |  val version: String = "$versionString"
           |""".stripMargin
      )
      Seq(outFile)
    }
  )

lazy val orchestrator = project
  .settings(wartSettings)
  .dependsOn(common)
  .settings(
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "co.fs2" %% "fs2-io" % "3.11.0",
      "io.circe" %% "circe-yaml" % "0.15.1",
      "io.circe" %% "circe-generic" % "0.14.10",
      "com.github.spullara.mustache.java" % "compiler" % "0.9.14"
    )
  )

lazy val scripting = project
  .settings(wartSettings)
  .dependsOn(orchestrator)
  .settings(
    assembly / mainClass := Some("orphera.scripting.Main"),
    libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
  )

ThisBuild / assemblyMergeStrategy := {
  case "module-info.class" => MergeStrategy.discard
  case _                   => MergeStrategy.first
}
