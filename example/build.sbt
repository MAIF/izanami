lazy val `example` = (project in file("."))
  .aggregate(`example-spring`, `example-play`)
  .settings(publish / skip := true)

lazy val `example-spring` = project
  .settings(publish / skip := true)

lazy val `example-play` = project
