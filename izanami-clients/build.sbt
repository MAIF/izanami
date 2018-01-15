lazy val `izanami-clients` = (project in file("."))
  .aggregate(jvm, `example-spring`)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)


lazy val jvm = project

lazy val `example-spring` = project
  .dependsOn(jvm)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)
