organization := "fr.maif"
name := "izanami-client"

scalaVersion := "2.12.4"

crossScalaVersions := Seq(scalaVersion.value, "2.11.11")

val akkaVersion = "2.5.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % "10.0.10",
  "com.lightbend.akka" %% "akka-stream-alpakka-sse" % "0.14",
  "io.vavr" % "vavr" % "0.9.1",
  "org.reactivecouchbase" % "json-lib-javaslang" % "2.0.0",
  "com.google.guava" % "guava" % "22.0",
  "com.typesafe.play" %% "play-json" % "2.6.6",
  "com.chuusai" %% "shapeless" % "2.3.2",
  "com.adelegue" %% "playjson-extended" % "0.0.1",
  "junit" % "junit" % "4.12" % Test,
  "org.assertj" % "assertj-core" % "3.5.2" % Test,
  "com.novocode" % "junit-interface" % "0.11" % Test,
  "org.scalatest" %% "scalatest" % "3.0.1" % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "org.mockito" % "mockito-core" % "2.12.0" % Test
)

resolvers ++= Seq(
  "jsonlib-repo" at "https://raw.githubusercontent.com/mathieuancelin/json-lib-javaslang/master/repository/releases",
  "playjson-repo" at "https://raw.githubusercontent.com/larousso/playjson-extended/master/repository/releases/"
)

scalafmtOnCompile in ThisBuild := true

scalafmtTestOnCompile in ThisBuild := true

scalafmtVersion in ThisBuild := "1.2.0" // all projects

publishTo := {
  val localPublishRepo = "../repository"
  if (isSnapshot.value)
    Some(Resolver.file("snapshots", new File(localPublishRepo + "/snapshots")))
  else Some(Resolver.file("releases", new File(localPublishRepo + "/releases")))
}

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { _ =>
  false
}
