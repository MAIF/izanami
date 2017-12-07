import sbt.project

lazy val `izanami-documentation` = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Izanami",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    target in Compile := {
      project.base.getParentFile / "docs"
    }
  )
