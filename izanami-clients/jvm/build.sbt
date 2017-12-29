import sbt.Keys.{organization, scalacOptions}
import sbtrelease.ReleaseStateTransformations._

val akkaVersion = "2.5.6"

lazy val jvm = (project in file("."))
  .disablePlugins(RevolverPlugin)
  .settings(
    organization := "fr.maif",
    name := "izanami-client",
    scalaVersion := "2.12.4",
    crossScalaVersions := Seq(scalaVersion.value, "2.11.11"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka"      %% "akka-stream"             % akkaVersion,
      "com.typesafe.akka"      %% "akka-http"               % "10.0.10",
      "com.lightbend.akka"     %% "akka-stream-alpakka-sse" % "0.14",
      "io.vavr"                % "vavr"                     % "0.9.1",
      "org.reactivecouchbase"  % "json-lib-javaslang"       % "2.0.0",
      "com.google.guava"       % "guava"                    % "22.0",
      "com.typesafe.play"      %% "play-json"               % "2.6.6",
      "com.chuusai"            %% "shapeless"               % "2.3.2",
      "com.adelegue"           %% "playjson-extended"       % "0.0.3",
      "junit"                  % "junit"                    % "4.12" % Test,
      "org.assertj"            % "assertj-core"             % "3.5.2" % Test,
      "com.novocode"           % "junit-interface"          % "0.11" % Test,
      "org.scalatest"          %% "scalatest"               % "3.0.1" % Test,
      "com.typesafe.akka"      %% "akka-testkit"            % akkaVersion % Test,
      "org.mockito"            % "mockito-core"             % "2.12.0" % Test,
      "com.github.tomakehurst" % "wiremock"                 % "2.12.0" % Test,
      "org.assertj"            % "assertj-core"             % "3.8.0" % Test
    ),
    resolvers ++= Seq(
      "jsonlib-repo" at "https://raw.githubusercontent.com/mathieuancelin/json-lib-javaslang/master/repository/releases",
      Resolver.jcenterRepo
    ),
    scalafmtOnCompile in ThisBuild := true,
    scalafmtTestOnCompile in ThisBuild := true,
    scalafmtVersion in ThisBuild := "1.2.0"
  )
  .settings(publishSettings: _*)

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
  publishMavenStyle := true,
  publishArtifact in Test := false,
  bintrayVcsUrl := Some(s"scm:git:git@github.com:$githubRepo.git")
)

lazy val publishSettings =
//  if (sys.env.get("TRAVIS_TAG").isEmpty) {
//    publishCommonsSettings ++ Seq(
//      publishTo := Some("Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local"),
//      bintrayReleaseOnPublish := false,
//      credentials := List(new File(".artifactory")).filter(_.exists).map(Credentials(_))
//    )
//  } else {
publishCommonsSettings ++ Seq(
  bintrayOrganization := Some("maif"),
  bintrayCredentialsFile := file(".credentials"),
  pomIncludeRepository := { _ =>
    false
  }
)
