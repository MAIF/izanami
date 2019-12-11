import BintrayConfig.publishSettings

lazy val `izanami-clients` = (project in file("."))
  .aggregate(jvm, `izanami-spring`)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val jvm = project

lazy val `izanami-spring` = project
  .dependsOn(jvm)
  .settings(publishSettings: _*)
