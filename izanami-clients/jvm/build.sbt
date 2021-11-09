import sbt.Keys.{organization, scalacOptions}
import sbtrelease.ReleaseStateTransformations._

val disabledPlugins = Seq(RevolverPlugin)

scalaVersion := Dependencies.scalaVersion

val settings = List(
    organization := Publish.organization,
    name := "izanami-client",
    libraryDependencies ++= Seq(
        "com.typesafe.akka"          %% "akka-stream"             % Dependencies.akkaVersion,
        "com.typesafe.akka"          %% "akka-slf4j"              % Dependencies.akkaVersion,
        "com.typesafe.akka"          %% "akka-http"               % Dependencies.akkaHttpVersion,
        "com.lightbend.akka"         %% "akka-stream-alpakka-sse" % "2.0.2",
        "org.scala-lang.modules"     %% "scala-collection-compat" % "2.1.2",
        "io.vavr"                    % "vavr"                     % "0.10.0",
        "org.reactivecouchbase.json" % "json-lib"                 % "1.0.0",
        "com.google.guava"           % "guava"                    % "25.1-jre",
        "com.typesafe.play"          %% "play-json"               % "2.7.4",
        "com.chuusai"                %% "shapeless"               % "2.3.3",
        "junit"                      % "junit"                    % "4.12" % Test,
        "org.assertj"                % "assertj-core"             % "3.5.2" % Test,
        "com.novocode"               % "junit-interface"          % "0.11" % Test,
        "org.scalatest"              %% "scalatest"               % "3.0.8" % Test,
        "com.typesafe.akka"          %% "akka-testkit"            % Dependencies.akkaVersion % Test,
        "org.mockito"                % "mockito-core"             % "2.12.0" % Test,
        "com.github.tomakehurst"     % "wiremock-jre8"            % "2.24.1" % Test,
        "org.assertj"                % "assertj-core"             % "3.8.0" % Test
      ),
    resolvers += Resolver.jcenterRepo
  ) ++ Publish.settings

lazy val jvm = (project in file("."))
  .disablePlugins(disabledPlugins: _*)
  .settings(settings)

scalacOptions ++= Seq(
//  "-Ypartial-unification",
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
//  "-Xfatal-warnings",
  "-Ywarn-unused:imports",
  "-Yrangepos",
  "-deprecation"
)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

//addCompilerPlugin(scalafixSemanticdb)
scalafixDependencies in ThisBuild += "org.scala-lang.modules" %% "scala-collection-migrations" % "2.1.2"
