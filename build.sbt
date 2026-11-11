name := "makao-hub-staff-web"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.13.18"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

libraryDependencies ++= Seq(
  guice,
  javaWs,
  filters,
  "junit" % "junit" % "4.13.2" % Test
)

Compile / javacOptions ++= Seq("--release", "17", "-parameters", "-Xlint:all")
Test / javacOptions ++= Seq("--release", "17")
Test / fork := true

