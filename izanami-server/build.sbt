import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}
import sbt.Keys.testOptions

name := """izanami"""

packageName in Universal := "izanami"

name in Universal := "izanami"

scalaVersion := "2.12.8"

lazy val ITest = config("it") extend Test

lazy val `izanami-server` = (project in file("."))
  .configs(ITest)
  .settings(Defaults.itSettings: _*)
  .enablePlugins(PlayScala, DockerPlugin)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

val akkaVersion     = "2.5.23"
val alpakkaVersion  = "1.0.2"
val metricsVersion  = "4.0.2"
val kotlinVersion   = "1.3.0"
val doobieVersion   = "0.6.0"
val akkaHttpVersion = "10.1.8"

resolvers ++= Seq(
  Resolver.jcenterRepo,
  Resolver.sonatypeRepo("releases")
)

libraryDependencies ++= Seq(
  ws,
  jdbc,
  javaWs,
  ehcache,
  "de.svenkubiak"            % "jBCrypt"                        % "0.4.1", //  ISC/BSD
  "com.auth0"                % "java-jwt"                       % "3.3.0", // MIT license
  "org.gnieh"                %% "diffson-play-json"             % "3.1.1", //
  "com.softwaremill.macwire" %% "macros"                        % "2.3.1" % "provided", // Apache 2.0
  "com.typesafe.akka"        %% "akka-actor"                    % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-slf4j"                    % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-stream"                   % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-actor-typed"              % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-cluster"                  % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-cluster-tools"            % akkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-testkit"                  % akkaVersion, // Apache 2.0
  "dev.zio"                  %% "zio"                           % "1.0.0-RC11-1",
  "dev.zio"                  %% "zio-interop-cats"              % "2.0.0.0-RC2",
  "org.reactivemongo"        %% "reactivemongo-akkastream"      % "0.17.1",
  "org.reactivemongo"        %% "play2-reactivemongo"           % "0.17.1-play27",
  "org.scala-lang.modules"   %% "scala-collection-compat"       % "0.1.1",
  "com.lightbend.akka"       %% "akka-stream-alpakka-dynamodb"  % alpakkaVersion, // Apache 2.0
  "io.lettuce"               % "lettuce-core"                   % "5.0.4.RELEASE", // Apache 2.0
  "org.iq80.leveldb"         % "leveldb"                        % "0.10", // Apache 2.0
  "org.typelevel"            %% "cats-core"                     % "2.0.0-RC1", // MIT license
  "org.typelevel"            %% "cats-effect"                   % "2.0.0-M5", // MIT license
  "org.tpolecat"             %% "doobie-core"                   % doobieVersion,
  "org.tpolecat"             %% "doobie-hikari"                 % doobieVersion,
  "org.tpolecat"             %% "doobie-postgres"               % doobieVersion,
  "com.github.krasserm"      %% "streamz-converter"             % "0.10-M2",
  "com.chuusai"              %% "shapeless"                     % "2.3.3", // Apache 2.0
  "com.adelegue"             %% "playjson-extended"             % "0.0.5", // Apache 2.0
  "com.github.pureconfig"    %% "pureconfig"                    % "0.8.0", // Apache 2.0
  "com.lightbend.akka"       %% "akka-stream-alpakka-cassandra" % alpakkaVersion, // Apache 2.0
  "com.typesafe.akka"        %% "akka-stream-kafka"             % "1.0.4", // Apache 2.0
  "com.adelegue"             %% "elastic-scala-http"            % "0.0.13", // Apache 2.0
  "com.datastax.cassandra"   % "cassandra-driver-core"          % "3.7.1", // Apache 2.0
  "io.dropwizard.metrics"    % "metrics-core"                   % metricsVersion, // Apache 2.0
  "io.dropwizard.metrics"    % "metrics-jvm"                    % metricsVersion, // Apache 2.0
  "io.dropwizard.metrics"    % "metrics-jmx"                    % metricsVersion, // Apache 2.0
  "io.dropwizard.metrics"    % "metrics-json"                   % metricsVersion, // Apache 2.0
  "io.prometheus"            % "simpleclient_common"            % "0.5.0", // Apache 2.0
  "io.prometheus"            % "simpleclient_dropwizard"        % "0.5.0", // Apache 2.0
  "org.scala-lang"           % "scala-compiler"                 % scalaVersion.value,
  "org.scala-lang"           % "scala-library"                  % scalaVersion.value,
  "org.jetbrains.kotlin"     % "kotlin-script-runtime"          % kotlinVersion,
  "org.jetbrains.kotlin"     % "kotlin-script-util"             % kotlinVersion,
  "org.jetbrains.kotlin"     % "kotlin-compiler-embeddable"     % kotlinVersion,
  "com.typesafe.akka"        %% "akka-http"                     % akkaHttpVersion % "it,test", // Apache 2.0
  "de.heikoseeberger"        %% "akka-http-play-json"           % "1.25.2" % "it,test" excludeAll ExclusionRule(
    "com.typesafe.play",
    "play-json"
  ), // Apache 2.0
  "org.scalatestplus.play"   %% "scalatestplus-play" % "3.1.1"  % "it,test", // Apache 2.0
  "com.github.kstyrc"        % "embedded-redis"      % "0.6"    % "it,test", // Apache 2.0
  "org.slf4j"                % "slf4j-api"           % "1.7.25" % "it,test", // MIT license
  "org.apache.logging.log4j" % "log4j-api"           % "2.8.2"  % "it,test", // MIT license
  "org.apache.logging.log4j" % "log4j-core"          % "2.8.2"  % "it,test" // MIT license
)

//dependencyOverrides ++= Seq(
//  "org.scala-lang.modules" %% "scala-collection-compat" % "0.1.1"
//)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8")

scalaSource in ITest := baseDirectory.value / "it"
resourceDirectory in ITest := (baseDirectory apply { baseDir: File =>
  baseDir / "it/resources"
}).value

scalacOptions ++= Seq(
  "-Ypartial-unification",
  "-Xfatal-warnings",
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-Xfatal-warnings"
)

coverageExcludedPackages := "<empty>;Reverse.*;router\\.*"

sources in (Compile, doc) := Seq.empty

publishArtifact in (Compile, packageDoc) := false

parallelExecution in Test := false

scalafmtOnCompile in ThisBuild := true

scalafmtTestOnCompile in ThisBuild := true

scalafmtVersion in ThisBuild := "1.2.0"

/// ASSEMBLY CONFIG

mainClass in assembly := Some("play.core.server.ProdServerStart")
test in assembly := {}
assemblyJarName in assembly := "izanami.jar"
fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value)
assemblyMergeStrategy in assembly := {
  //case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case PathList("javax", xs @ _*) =>
    MergeStrategy.first
  case PathList("net", "jpountz", xs @ _*) =>
    MergeStrategy.first
  case PathList("org", "jetbrains", xs @ _*) =>
    MergeStrategy.first
  case PathList("META-INF", "native", xs @ _*) =>
    MergeStrategy.first
  case PathList("org", "apache", "commons", "logging", xs @ _*) =>
    MergeStrategy.discard
  case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" =>
    MergeStrategy.first
  case PathList(ps @ _*) if ps.contains("reference-overrides.conf") =>
    MergeStrategy.concat
  case PathList(ps @ _*) if ps.exists(_.endsWith(".kotlin_module")) =>
    MergeStrategy.first
  case PathList(ps @ _*) if ps.last endsWith ".conf" => MergeStrategy.concat
  case PathList(ps @ _*) if ps.contains("buildinfo") =>
    MergeStrategy.discard
  case o =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(o)
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
  (dist in Compile).value
  (assembly in Compile).value
}

/// DOCKER CONFIG
dockerExposedPorts := Seq(
  2551,
  8080
)
packageName in Docker := "izanami"

dockerPackageMappings in Docker += (baseDirectory.value / "docker" / "start.sh") -> "/opt/docker/bin/start.sh"

maintainer in Docker := "MAIF Team <maif@maif.fr>"

dockerBaseImage := "openjdk:11-jre-slim"

dockerCommands := dockerCommands.value.filterNot {
  case ExecCmd("CMD", args @ _*) => true
  case cmd                       => false
}

dockerCommands ++= Seq(
  Cmd("ENV", "APP_NAME izanami"),
  Cmd("ENV", "APP_VERSION 1.0.6-SNAPSHOT"),
  Cmd("ENV", "LEVEL_DB_PARENT_PATH /leveldb"),
  Cmd("ENV", "REDIS_PORT 6379"),
  Cmd("ENV", "REDIS_HOST redis"),
  Cmd("ENV", "CASSANDRA_HOST cassandra"),
  Cmd("ENV", "CASSANDRA_PORT 9042"),
  Cmd("ENV", "CASSANDRA_REPLICATION_FACTOR 1"),
  Cmd("ENV", "CASSANDRA_KEYSPACE izanami"),
  Cmd("ENV", "KAFKA_HOST kafka"),
  Cmd("ENV", "KAFKA_PORT 9092"),
  Cmd("ENV", "HTTP_PORT 8080"),
  Cmd("ENV", "APPLICATION_SECRET 2nJS=TpH/qBfB=NI6:H/jt3@5B3IBhzD4OjWi=tCH50Bjy2=JCXO^]XeZUW47Gv4")
)

dockerExposedVolumes ++= Seq(
  "/leveldb",
  "/data"
)

dockerUsername := Some("maif")

dockerEntrypoint := Seq("/opt/docker/bin/start.sh")

dockerUpdateLatest := true
