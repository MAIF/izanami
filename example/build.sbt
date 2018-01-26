lazy val `example` = (project in file("."))
  .aggregate(`example-spring`)
  //.aggregate(`example-spring`, jvm)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val `example-spring` = project
//.dependsOn(jvm)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

//lazy val jvm = project
