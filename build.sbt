import ReleaseTransformations._

name := """izanami-v2"""
organization := "fr.maif"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala).enablePlugins(BuildInfoPlugin)

lazy val excludesJackson = Seq(
  ExclusionRule(organization = "com.fasterxml.jackson.core"),
  ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
  ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)

scalaVersion := "2.13.12"

resolvers ++= Seq(
  "jsonlib-repo" at "https://raw.githubusercontent.com/mathieuancelin/json-lib-javaslang/master/repository/releases",
  Resolver.jcenterRepo
)

libraryDependencies += guice
libraryDependencies += ws
libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.3.7" % "provided"

libraryDependencies += "com.zaxxer"            % "HikariCP"                   % "5.1.0"
libraryDependencies += "io.vertx"              % "vertx-pg-client"            % "4.5.1"
libraryDependencies += "com.ongres.scram"      % "common"                     % "2.1"
libraryDependencies += "com.ongres.scram"      % "client"                     % "2.1"
libraryDependencies += "org.flywaydb"          % "flyway-database-postgresql" % "10.4.1" excludeAll (excludesJackson: _*)
libraryDependencies += "org.postgresql"        % "postgresql"                 % "42.7.2"
libraryDependencies += "com.github.jwt-scala" %% "jwt-play-json"              % "9.4.5" excludeAll (excludesJackson: _*)
libraryDependencies += "org.mindrot"           % "jbcrypt"                    % "0.4"
libraryDependencies += "com.mailjet"           % "mailjet-client"             % "5.2.5"
libraryDependencies += "javax.mail"            % "javax.mail-api"             % "1.6.2"
libraryDependencies += "com.sun.mail"          % "javax.mail"                 % "1.6.2"
libraryDependencies += "com.github.blemale"   %% "scaffeine"                  % "5.2.1"
libraryDependencies += "net.java.dev.jna"      % "jna"                        % "5.14.0"
libraryDependencies += "commons-codec"         % "commons-codec"              % "1.16.0"
libraryDependencies += "io.dropwizard.metrics" % "metrics-json"               % "4.2.23" excludeAll (excludesJackson: _*)
libraryDependencies += "org.mozilla"           % "rhino"                      % "1.7.14"
libraryDependencies += "com.squareup.okhttp3"  % "okhttp"                     % "4.12.0" excludeAll (excludesJackson: _*)
libraryDependencies += "fr.maif"              %% "wasm4s"                     % "3.4.0" classifier "bundle"
libraryDependencies += "com.auth0"             % "java-jwt"                   % "4.4.0" excludeAll (excludesJackson: _*) // needed by wasm4s
libraryDependencies += "com.typesafe.akka"    %% "akka-http"                  % "10.2.10"
libraryDependencies += "com.github.jknack"     % "handlebars"                 % "4.4.0"
libraryDependencies += "com.github.jknack"     % "handlebars-jackson"         % "4.4.0" excludeAll (excludesJackson: _*)

libraryDependencies += "org.scalatestplus.play"       %% "scalatestplus-play"   % "5.0.0"    % Test
libraryDependencies += "org.scalatest"                %% "scalatest-flatspec"   % "3.2.12"   % "test"
libraryDependencies += "com.github.tomakehurst"        % "wiremock-jre8"        % "2.34.0"   % Test
libraryDependencies += "com.fasterxml.jackson.core"    % "jackson-databind"     % "2.13.4.2" % Test
libraryDependencies += "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.13.4"   % Test
libraryDependencies += jdbc                            % "test"
libraryDependencies += "org.testcontainers"            % "testcontainers"       % "1.19.1"   % Test
libraryDependencies += "fr.maif"                      %% "izanami-client"       % "1.11.5"   % Test
libraryDependencies += "org.awaitility"                % "awaitility-scala"     % "4.2.0"    % Test
libraryDependencies += "com.github.mifmif"             % "generex"              % "1.0.1"    % Test

routesImport += "fr.maif.izanami.models.CustomBinders._"

assembly / mainClass := Some("play.core.server.ProdServerStart")
assembly / fullClasspath += Attributed.blank(PlayKeys.playPackageAssets.value)
assembly / assemblyJarName := "izanami.jar"
assembly / assemblyMergeStrategy := {
  case manifest if manifest.contains("MANIFEST.MF")                                  =>
    // We don't need manifest files since sbt-assembly will create
    // one with the given settings
    MergeStrategy.discard
  case referenceOverrides if referenceOverrides.contains("reference-overrides.conf") =>
    // Keep the content for all reference-overrides.conf files
    MergeStrategy.concat
  case PathList("reference.conf")                                                    => MergeStrategy.concat
  case PathList(ps @ _*) if ps.last == "io.netty.versions.properties"                => MergeStrategy.first
  case referenceOverrides if referenceOverrides.contains("module-info.class")        => MergeStrategy.discard
  case referenceOverrides if referenceOverrides.contains("mailcap.default")          => MergeStrategy.first
  case PathList("javax", xs @ _*)                                                    => MergeStrategy.first
  case PathList("javax", xs @ _*)                                                    => MergeStrategy.first
  case referenceOverrides if referenceOverrides.startsWith("scala/")                 => MergeStrategy.first
  case referenceOverrides if referenceOverrides.startsWith("play/")                  => MergeStrategy.first
  case referenceOverrides if referenceOverrides.contains("library.properties")       => MergeStrategy.first
  case referenceOverrides if referenceOverrides.contains("mimetypes.default")        => MergeStrategy.first
  case PathList("META-INF", x, xs @ _*) if x.toLowerCase == "services"               => MergeStrategy.filterDistinctLines
  case PathList("META-INF", xs @ _*)                                                 => MergeStrategy.discard

  case x =>
    // For all the other files, use the default sbt-assembly merge strategy
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

releaseVersionBump := sbtrelease.Version.Bump.Bugfix
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies, // : ReleaseStep
  inquireVersions,           // : ReleaseStep
  //runClean,                  // : ReleaseStep
  //runTest,                   // : ReleaseStep
  setReleaseVersion,         // : ReleaseStep
  commitReleaseVersion,      // : ReleaseStep, performs the initial git checks
  tagRelease,                // : ReleaseStep
  //publishArtifacts,          // : ReleaseStep, checks whether `publishTo` is properly set up
  setNextVersion             // : ReleaseStep
  //commitNextVersion,         // : ReleaseStep
  //pushChanges                // : ReleaseStep, also checks that an upstream branch is properly configured
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "fr.maif.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "fr.maif.binders._"
