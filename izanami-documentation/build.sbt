import sbt.project

lazy val `izanami-documentation` = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Izanami",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
//    target in Compile := {
//      project.base.getParentFile / "docs"
//    }
  )


lazy val generateDoc = taskKey[Unit]("Copy doc")

generateDoc := {
  (paradox in Compile).value
  val p = project
  val paradoxFile = target.value / "paradox" / "site" / "main"
  val targetDocs = p.base.getParentFile / "docs"
  IO.delete(targetDocs)
  IO.copyDirectory(paradoxFile, targetDocs)
}

