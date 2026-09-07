import sbt._
import Keys._
import sbtassembly.AssemblyPlugin.autoImport._
import scalapb.GeneratorOption.*

ThisBuild / scalaVersion := "3.8.4"

lazy val common = project
  .enablePlugins(Fs2Grpc)
  .settings(
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion
    )
  )

lazy val orchestrator = project
  .dependsOn(common)
  .settings(
    Compile / run / fork := true,
    libraryDependencies +=
      "org.typelevel" %% "cats-effect" % "3.7.1"
  )

lazy val agent = project
  .dependsOn(common)
  .settings(
    Compile / run / fork := true,
    Compile / mainClass := Some("orphera.agent.Main"),
    assembly / mainClass := Some("orphera.agent.Main")
  )

ThisBuild / assemblyMergeStrategy := {
  case "module-info.class" => MergeStrategy.discard
  case _                   => MergeStrategy.first
}
