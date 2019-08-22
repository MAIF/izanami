organization := "fr.maif"
name := "example-play"

lazy val `example-play` = (project in file("."))
  .enablePlugins(PlayScala)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

scalaVersion := "2.12.8"

val akkaVersion = "2.5.21"

resolvers ++= Seq(
  Resolver.jcenterRepo,
  "Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local"
)

libraryDependencies ++= Seq(
  ws,
  "de.svenkubiak"            % "jBCrypt"         % "0.4.1", //  ISC/BSD
  "com.auth0"                % "java-jwt"        % "3.3.0", // MIT license
  "fr.maif"                  %% "izanami-client" % "1.4.2",
  "com.softwaremill.macwire" %% "macros"         % "2.3.1" % "provided", // Apache 2.0
  "com.typesafe.akka"        %% "akka-actor"     % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-stream"    % akkaVersion, // Apache 2.0
  "org.typelevel"            %% "cats-core"      % "1.6.0", // MIT license
  "org.typelevel"            %% "cats-effect"    % "1.2.0", // MIT license
  "org.iq80.leveldb"         % "leveldb"         % "0.10", // Apache 2.0
  "com.github.pureconfig"    %% "pureconfig"     % "0.8.0" // Apache 2.0
)

scalafmtOnCompile in ThisBuild := true

scalafmtTestOnCompile in ThisBuild := true

scalafmtVersion in ThisBuild := "1.2.0"
