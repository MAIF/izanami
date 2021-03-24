scalaVersion := Dependencies.scalaVersion

lazy val `izanami-clients` = (project in file("."))
  .aggregate(jvm, `izanami-spring`)
  .settings(skip in publish := true)

lazy val jvm = project

lazy val `izanami-spring` = project
  .dependsOn(jvm)
  .settings(Publish.settings)
