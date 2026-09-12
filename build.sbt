import sbt._
import Keys._
import sbtassembly.AssemblyPlugin.autoImport._

ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := IO.read(file("VERSION")).trim
ThisBuild / scalacOptions ++= Seq(
  "-Werror",
  "-Wconf:src=src_managed/.*:silent"
)

lazy val common = project
  .enablePlugins(Fs2Grpc)
  .settings(
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion
    )
  )

lazy val agent = project
  .dependsOn(common)
  .settings(
    Compile / run / fork := true,
    Compile / mainClass := Some("orphera.agent.Main"),
    assembly / mainClass := Some("orphera.agent.Main"),
    libraryDependencies += "co.fs2" %% "fs2-io" % "3.11.0"
  )

lazy val orchestrator = project
  .dependsOn(common)
  .settings(
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "co.fs2" %% "fs2-io" % "3.11.0"
    )
  )

ThisBuild / assemblyMergeStrategy := {
  case "module-info.class" => MergeStrategy.discard
  case _                   => MergeStrategy.first
}
