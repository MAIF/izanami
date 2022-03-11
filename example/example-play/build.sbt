organization := "fr.maif"
name := "example-play"

lazy val `example-play` = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(publish / skip := true)

scalaVersion := Dependencies.scalaVersion

PlayKeys.devSettings := Seq("play.server.http.port" -> "8080")

resolvers ++= Seq(
  Resolver.jcenterRepo,
  ("Artifactory Realm" at "https://s01.oss.sonatype.org/content/repositories/snapshots/").withAllowInsecureProtocol(
    true
  )
)

libraryDependencies ++= Seq(
  ws,
  "de.svenkubiak"            % "jBCrypt"         % "0.4.1", //  ISC/BSD
  "com.auth0"                % "java-jwt"        % "3.11.0", // MIT license
  "com.softwaremill.macwire" %% "macros"         % "2.3.3" % "provided", // Apache 2.0
  "com.typesafe.akka"        %% "akka-stream"    % Dependencies.akkaVersion, // Apache 2.0
  "org.typelevel"            %% "cats-core"      % "2.0.0", // MIT license
  "org.typelevel"            %% "cats-effect"    % "2.0.0", // MIT license
  "org.iq80.leveldb"         % "leveldb"         % "0.10", // Apache 2.0
  "fr.maif"                  %% "izanami-client" % version.value,
  "com.github.pureconfig"    %% "pureconfig"     % "0.12.1" // Apache 2.0
)
