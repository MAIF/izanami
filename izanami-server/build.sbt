name := """izanami-server"""

lazy val `izanami-server` = (project in file("."))
  .enablePlugins(PlayScala)

scalaVersion := "2.12.4"

val akkaVersion = "2.5.8"

resolvers ++= Seq(
  Resolver.bintrayRepo("larousso", "maven")
)

libraryDependencies ++= Seq(
  ws,
  "de.svenkubiak"            % "jBCrypt"                        % "0.4.1", //  ISC/BSD
  "com.auth0"                % "java-jwt"                       % "3.1.0", // MIT license
  "com.softwaremill.macwire" %% "macros"                        % "2.3.0" % "provided", // Apache 2.0
  "com.typesafe.akka"        %% "akka-actor"                    % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-stream"                   % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-typed"                    % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-cluster"                  % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-cluster-tools"            % akkaVersion, // Apache 2.0
  "com.github.etaty"         %% "rediscala"                     % "1.8.0", // Apache 2.0
  "org.iq80.leveldb"         % "leveldb"                        % "0.9", // Apache 2.0
  "org.typelevel"            %% "cats"                          % "0.9.0", // MIT license
  "com.chuusai"              %% "shapeless"                     % "2.3.2", // Apache 2.0
  "com.adelegue"             %% "playjson-extended"             % "0.0.3", // Apache 2.0
  "com.github.pureconfig"    %% "pureconfig"                    % "0.8.0", // Apache 2.0
  "com.lightbend.akka"       %% "akka-stream-alpakka-cassandra" % "0.12", // Apache 2.0
  "com.typesafe.akka"        %% "akka-stream-kafka"             % "0.18", // Apache 2.0
  "com.adelegue"             %% "elastic-scala-http"            % "0.0.9", // Apache 2.0
  "com.datastax.cassandra"   % "cassandra-driver-core"          % "3.3.0", // Apache 2.0
  "com.typesafe.akka"        %% "akka-http"                     % "10.0.6" % Test, // Apache 2.0
  "de.heikoseeberger"        %% "akka-http-play-json"           % "1.16.0" % Test, // Apache 2.0
  "org.scalatestplus.play"   %% "scalatestplus-play"            % "3.1.1" % Test, // Apache 2.0
  "com.github.kstyrc"        % "embedded-redis"                 % "0.6" % Test, // Apache 2.0
  "org.slf4j"                % "slf4j-api"                      % "1.7.25" % Test, // MIT license
  "org.apache.logging.log4j" % "log4j-api"                      % "2.8.2" % Test, // MIT license
  "org.apache.logging.log4j" % "log4j-core"                     % "2.8.2" % Test // MIT license
)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials"
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "org.maifx.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "org.maifx.binders._"

scalafmtOnCompile in ThisBuild := true

scalafmtTestOnCompile in ThisBuild := true

scalafmtVersion in ThisBuild := "1.2.0" // all projects

mainClass in assembly := Some("play.core.server.ProdServerStart")
test in assembly := {}
assemblyJarName in assembly := "izanami.jar"
fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value)
assemblyMergeStrategy in assembly := {
  //case PathList("org", "apache", "commons", "logging", xs @ _*)       => MergeStrategy.first
  case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" =>
    MergeStrategy.first
  case PathList(ps @ _*) if ps.contains("reference-overrides.conf") =>
    MergeStrategy.concat
  case PathList(ps @ _*) if ps.last endsWith ".conf" => MergeStrategy.concat
  case o =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(o)
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
  (dist in Compile).value
  (assembly in Compile).value
}
