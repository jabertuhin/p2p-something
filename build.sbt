val scala3Version = "3.8.4"

lazy val root = project
  .in(file("."))
  .settings(
    name := "p2p-something",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.3.2" % Test,
      "org.rogach" %% "scallop" % "6.0.0",
      "ch.qos.logback" % "logback-classic" % "1.5.18",   // full-featured, configurable via logback.xml
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6"
    )
  )
