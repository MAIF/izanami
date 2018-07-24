organization := "fr.maif"
name := "example-play"

lazy val `example-play` = (project in file("."))
  .enablePlugins(PlayScala)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

scalaVersion := "2.12.6"

val akkaVersion = "2.5.14"

resolvers ++= Seq(
  Resolver.jcenterRepo,
  "Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local"
)

libraryDependencies ++= Seq(
  ws,
  "fr.maif"                  %% "izanami-client" % "1.0.7-SNAPSHOT",
  "com.softwaremill.macwire" %% "macros"         % "2.3.1" % "provided", // Apache 2.0
  "com.typesafe.akka"        %% "akka-actor"     % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-stream"    % akkaVersion, // Apache 2.0
  "org.typelevel"            %% "cats-core"      % "1.1.0", // MIT license
  "org.typelevel"            %% "cats-effect"    % "1.0.0-RC2", // MIT license
  "org.iq80.leveldb"         % "leveldb"         % "0.10", // Apache 2.0
  "com.github.pureconfig"    %% "pureconfig"     % "0.8.0" // Apache 2.0
)

scalafmtOnCompile in ThisBuild := true

scalafmtTestOnCompile in ThisBuild := true

scalafmtVersion in ThisBuild := "1.2.0"
