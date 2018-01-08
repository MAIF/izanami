name := """simulation"""
organization := "fr.maif"

lazy val simulation = (project in file("."))
  .enablePlugins(GatlingPlugin)

scalacOptions := Seq("-encoding",
                     "UTF-8",
                     "-target:jvm-1.8",
                     "-deprecation",
                     "-feature",
                     "-unchecked",
                     "-language:implicitConversions",
                     "-language:postfixOps")

libraryDependencies += "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.3.0" % "test,it"
libraryDependencies += "io.gatling"            % "gatling-test-framework"    % "2.3.0" % "test,it"
