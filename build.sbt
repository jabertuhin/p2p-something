val scala3Version = "3.8.4"

lazy val root = project
  .in(file("."))
  .settings(
    name := "p2p-something",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies ++= Seq(
      "org.rogach" %% "scallop" % "6.0.0",
      "ch.qos.logback" % "logback-classic" % "1.5.18",   // full-featured, configurable via logback.xml
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",

      // Recursive native filesystem watcher. util.Watcher is an experiment and is not wired into
      // the current sender.
      "io.methvin" %% "directory-watcher-better-files" % "0.19.0",

      // Concurrency dependencies are installed but not used by the current blocking prototype.
      "org.typelevel" %% "cats-effect" % "3.7.0",
      "co.fs2" %% "fs2-core" % "3.13.0",

//      "org.scalameta" %% "munit" % "1.3.2" % Test,
//      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,   // assert on IO-returning code
      "org.scalatest" %% "scalatest-funsuite" % "3.2.20" % Test

    )
  )
