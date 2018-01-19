lazy val `izanami-clients` = (project in file("."))
  .aggregate(jvm)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val jvm = project
