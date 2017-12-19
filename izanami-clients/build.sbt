lazy val `izanami-clients` = (project in file("."))
  .aggregate(jvm, `example-spring`)

lazy val jvm = project.disablePlugins(RevolverPlugin)

lazy val `example-spring` = project.dependsOn(jvm)
