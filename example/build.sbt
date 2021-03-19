lazy val `example` = (project in file("."))
  .aggregate(`example-spring`, `example-play`)
  .settings(skip in publish := true)

lazy val `example-spring` = project
  .settings(skip in publish := true)

lazy val `example-play` = project
