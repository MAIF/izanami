lazy val `example` = (project in file("."))
  .aggregate(`example-spring`)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val `example-spring` = project
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)
