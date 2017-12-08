import sbt.project

lazy val `izanami-documentation` = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Izanami",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    paradoxProperties in Compile ++= Map(
      "download_zip.base_url" -> s"https://github.com/maif/izanami/release/download/v-${version}/izanami.zip",
      "download_jar.base_url" -> s"https://github.com/maif/izanami/release/download/v-${version}/izanami.jar"
    )
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

