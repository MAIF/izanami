import sbt.Keys.{organization, scalacOptions}
import sbtrelease.ReleaseStateTransformations._

val disabledPlugins = if (sys.env.get("TRAVIS_TAG").filterNot(_.isEmpty).isDefined) {
  Seq(RevolverPlugin)
} else {
  Seq(RevolverPlugin, BintrayPlugin)
}

val akkaVersion     = "2.5.23"
val akkaHttpVersion = "10.1.8"

lazy val jvm = (project in file("."))
  .disablePlugins(disabledPlugins: _*)
  .settings(
    organization := "fr.maif",
    name := "izanami-client",
    crossScalaVersions := Seq(scalaVersion.value, "2.11.11"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka"          %% "akka-stream"             % akkaVersion,
      "com.typesafe.akka"          %% "akka-slf4j"              % akkaVersion,
      "com.typesafe.akka"          %% "akka-http"               % akkaHttpVersion,
      "com.lightbend.akka"         %% "akka-stream-alpakka-sse" % "1.1.0",
      "io.vavr"                    % "vavr"                     % "0.9.2",
      "org.reactivecouchbase.json" % "json-lib"                 % "1.0.0",
      "com.google.guava"           % "guava"                    % "25.1-jre",
      "com.typesafe.play"          %% "play-json"               % "2.7.1",
      "com.chuusai"                %% "shapeless"               % "2.3.3",
      "com.adelegue"               %% "playjson-extended"       % "0.0.5",
      "junit"                      % "junit"                    % "4.12" % Test,
      "org.assertj"                % "assertj-core"             % "3.5.2" % Test,
      "com.novocode"               % "junit-interface"          % "0.11" % Test,
      "org.scalatest"              %% "scalatest"               % "3.0.1" % Test,
      "com.typesafe.akka"          %% "akka-testkit"            % akkaVersion % Test,
      "org.mockito"                % "mockito-core"             % "2.12.0" % Test,
      "com.github.tomakehurst"     % "wiremock-jre8"            % "2.24.1" % Test,
      "org.assertj"                % "assertj-core"             % "3.8.0" % Test
    ),
    resolvers ++= Seq(
      Resolver.jcenterRepo,
      Resolver.bintrayRepo("larousso", "maven")
    ),
    scalafmtOnCompile in ThisBuild := true,
    scalafmtTestOnCompile in ThisBuild := true,
    scalafmtVersion in ThisBuild := "1.2.0"
  )
  .settings(publishSettings: _*)

scalacOptions ++= Seq(
  "-Ypartial-unification",
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-Xfatal-warnings"
)

lazy val githubRepo = "maif/izanami"

lazy val publishCommonsSettings = Seq(
  homepage := Some(url(s"https://github.com/$githubRepo")),
  startYear := Some(2017),
  licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
  scmInfo := Some(
    ScmInfo(
      url(s"https://github.com/$githubRepo"),
      s"scm:git:https://github.com/$githubRepo.git",
      Some(s"scm:git:git@github.com:$githubRepo.git")
    )
  ),
  developers := List(
    Developer("alexandre.delegue", "Alexandre Delègue", "", url(s"https://github.com/larousso"))
  ),
  releaseCrossBuild := true,
  publishMavenStyle := true,
  publishArtifact in Test := false,
  bintrayVcsUrl := Some(s"scm:git:git@github.com:$githubRepo.git")
)

lazy val publishSettings =
  if (sys.env.get("TRAVIS_TAG").filterNot(_.isEmpty).isDefined) {
    publishCommonsSettings ++ Seq(
      bintrayOrganization := Some("maif"),
      pomIncludeRepository := { _ =>
        false
      }
    )
  } else {
    publishCommonsSettings ++ Seq(
      publishTo := Some(
        "Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local"
      ),
      bintrayReleaseOnPublish := false,
      credentials := List(
        Credentials("Artifactory Realm",
                    "oss.jfrog.org",
                    sys.env.getOrElse("BINTRAY_USER", ""),
                    sys.env.getOrElse("BINTRAY_PASS", ""))
      )
    )
  }
