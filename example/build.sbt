lazy val `example` = (project in file("."))
  .aggregate(`example-spring`, `example-play`)
  //.aggregate(`example-spring`, jvm)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val `example-spring` = project
//.dependsOn(jvm)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val `example-play` = project
//lazy val jvm = project
