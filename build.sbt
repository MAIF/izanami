import ReleaseTransformations.*
import xerial.sbt.Sonatype.{sonatype01}

name := """izanami"""
organization := "fr.maif"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    Compile / packageBin / publishArtifact := false,
    addArtifact(Artifact("izanami", "jar", "jar"), assembly),
    scalacOptions += {
      if (scalaVersion.value.startsWith("2.12"))
        "-Ywarn-unused-import"
      else
        "-Wunused:imports"
    }
  )

/*packagedArtifacts in publish := {
  Map.empty
}*/

lazy val excludesJackson = Seq(
  ExclusionRule(organization = "com.fasterxml.jackson.core"),
  ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
  ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)

version := (ThisBuild / version).value

scalaVersion := "3.3.1"
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

excludeDependencies ++= Seq(
  ExclusionRule("org.reactivecouchbase.json", "json-lib")
)

libraryDependencies += guice
libraryDependencies += ws
libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.4.0" % "provided"

libraryDependencies += "com.zaxxer" % "HikariCP" % "5.1.0"
libraryDependencies += "io.vertx" % "vertx-pg-client" % "4.5.1"
libraryDependencies += "com.ongres.scram" % "common" % "2.1"
libraryDependencies += "com.ongres.scram" % "client" % "2.1"
libraryDependencies += "org.flywaydb" % "flyway-database-postgresql" % "10.4.1" excludeAll (excludesJackson: _*)
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.2"
libraryDependencies += "com.github.jwt-scala" %% "jwt-play-json" % "9.4.5" excludeAll (excludesJackson: _*)
libraryDependencies += "org.mindrot" % "jbcrypt" % "0.4"
libraryDependencies += "com.mailjet" % "mailjet-client" % "5.2.5"
libraryDependencies += "javax.mail" % "javax.mail-api" % "1.6.2"
libraryDependencies += "com.sun.mail" % "javax.mail" % "1.6.2"
libraryDependencies += "com.github.blemale" %% "scaffeine" % "5.2.1"
libraryDependencies += "net.java.dev.jna" % "jna" % "5.14.0"
libraryDependencies += "commons-codec" % "commons-codec" % "1.16.0"
libraryDependencies += "io.dropwizard.metrics" % "metrics-json" % "4.2.23" excludeAll (excludesJackson: _*)
libraryDependencies += "org.mozilla" % "rhino" % "1.7.14"
libraryDependencies += "com.squareup.okhttp3" % "okhttp" % "4.12.0" excludeAll (excludesJackson: _*)
libraryDependencies += "fr.maif" %% "wasm4s" % "dev" classifier "bundle"
libraryDependencies += "com.auth0" % "java-jwt" % "4.4.0" excludeAll (excludesJackson: _*) // needed by wasm4s
libraryDependencies += "org.apache.pekko" %% "pekko-http" % "1.1.0"
libraryDependencies += "com.github.jknack" % "handlebars" % "4.4.0"
libraryDependencies += "com.github.jknack" % "handlebars-jackson" % "4.4.0" excludeAll (excludesJackson: _*)
libraryDependencies += "net.logstash.logback" % "logstash-logback-encoder" % "8.0" excludeAll (excludesJackson: _*)

libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "6.0.1" % Test
libraryDependencies += "org.scalatest" %% "scalatest-flatspec" % "3.2.12" % "test"
libraryDependencies += "com.github.tomakehurst" % "wiremock-jre8" % "2.34.0" % Test
libraryDependencies += "com.fasterxml.jackson.core" % "jackson-databind" % "2.13.4.2" % Test
libraryDependencies += "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.13.4" % Test
libraryDependencies += jdbc % "test"
libraryDependencies += "org.testcontainers" % "testcontainers" % "1.19.1" % Test
libraryDependencies += "fr.maif" %% "izanami-client" % "1.11.5" % Test cross CrossVersion.for3Use2_13
libraryDependencies += "org.awaitility" % "awaitility-scala" % "4.2.0" % Test
libraryDependencies += "com.github.mifmif" % "generex" % "1.0.1" % Test

routesImport += "fr.maif.izanami.models.CustomBinders._"

assembly / test := {}
assembly / mainClass := Some("play.core.server.ProdServerStart")
assembly / fullClasspath += Attributed.blank(PlayKeys.playPackageAssets.value)
assembly / assemblyJarName := "izanami.jar"
assembly / assemblyMergeStrategy := {
  case manifest if manifest.contains("MANIFEST.MF") =>
    // We don't need manifest files since sbt-assembly will create
    // one with the given settings
    MergeStrategy.discard
  case referenceOverrides if referenceOverrides.contains("reference-overrides.conf") =>
    // Keep the content for all reference-overrides.conf files
    MergeStrategy.concat
  case PathList("reference.conf") => MergeStrategy.concat
  case PathList(ps@_*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
  case referenceOverrides if referenceOverrides.contains("module-info.class") => MergeStrategy.discard
  case referenceOverrides if referenceOverrides.contains("mailcap.default") => MergeStrategy.first
  case PathList("javax", xs@_*) => MergeStrategy.first
  case PathList("javax", xs@_*) => MergeStrategy.first
  case referenceOverrides if referenceOverrides.startsWith("scala/") => MergeStrategy.first
  case referenceOverrides if referenceOverrides.startsWith("play/") => MergeStrategy.first
  case referenceOverrides if referenceOverrides.contains("library.properties") => MergeStrategy.first
  case referenceOverrides if referenceOverrides.contains("mimetypes.default") => MergeStrategy.first
  case PathList("META-INF", x, xs@_*) if x.toLowerCase == "services" => MergeStrategy.filterDistinctLines
  case PathList("META-INF", xs@_*) => MergeStrategy.discard

  case x =>
    // For all the other files, use the default sbt-assembly merge strategy
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

crossPaths := false

/*publish / packagedArtifacts := {
  val log = streams.value.log
  Map(Artifact(moduleName.value, "jar", "jar") -> assembly.value)
}*/

val sonatypeCentralDeploymentName =
  settingKey[String](s"fr.maif-izanami")

releaseVersionBump := sbtrelease.Version.Bump.Bugfix
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies, // : ReleaseStep
  inquireVersions, // : ReleaseStep
  //runClean,                  // : ReleaseStep
  //runTest,                   // : ReleaseStep
  setReleaseVersion, // : ReleaseStep
  commitReleaseVersion, // : ReleaseStep, performs the initial git checks
  tagRelease, // : ReleaseStep
  //publishArtifacts,          // : ReleaseStep, checks whether `publishTo` is properly set up
  releaseStepCommand("publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
  setNextVersion, // : ReleaseStep
  commitNextVersion, // : ReleaseStep
  pushChanges // : ReleaseStep, also checks that an upstream branch is properly configured
)

ThisBuild / sonatypeCredentialHost := sonatype01
publishTo := sonatypePublishToBundle.value
