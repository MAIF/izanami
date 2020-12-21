name := """simulation"""
organization := "fr.maif"

scalaVersion := "2.13.3"

lazy val simulation = (project in file("."))
  .enablePlugins(GatlingPlugin)

val akkaVersion = "2.6.10"

scalacOptions := Seq(
  "-encoding",
  "UTF-8",
  "-target:jvm-1.8",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-language:implicitConversions",
  "-language:postfixOps"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka"     %% "akka-actor"               % akkaVersion % "test,it",
  "com.typesafe.akka"     %% "akka-stream"              % akkaVersion % "test,it",
  "com.typesafe.akka"     %% "akka-http"                % "10.1.11"   % "test,it",
  "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.5.0"     % "test,it",
  "io.gatling"            % "gatling-test-framework"    % "3.5.0"     % "test,it"
)
