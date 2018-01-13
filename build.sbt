import ReleaseTransformations._

name := """izanami"""
organization := "fr.maif"
scalaVersion := "2.12.4"

lazy val root = (project in file("."))
  .aggregate(
    `izanami-server`,
    `izanami-clients`
  )
  .enablePlugins(NoPublish, GitVersioning, GitBranchPrompt)
  .disablePlugins(BintrayPlugin)

lazy val `izanami-documentation` = project
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val `izanami-server` = project.enablePlugins(NoPublish)

lazy val `izanami-clients` = project.enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val simulation = project.enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

val setVersionToNpmProject = ReleaseStep(action = st => {
  import sys.process._
  // extract the build state
  val extracted = Project.extract(st)
  // retrieve the value of the organization SettingKey
  val version = extracted.get(Keys.version)

  s"sh release.sh $version" !

  st
})

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies, // : ReleaseStep
  inquireVersions, // : ReleaseStep
  runClean,        // : ReleaseStep
  //runTest, // : ReleaseStep
  setReleaseVersion, // : ReleaseStep
  setVersionToNpmProject,
  commitReleaseVersion, // : ReleaseStep, performs the initial git checks
  tagRelease,           // : ReleaseStep
  //publishArtifacts,                       // : ReleaseStep, checks whether `publishTo` is properly set up
  setNextVersion, // : ReleaseStep
  commitNextVersion, // : ReleaseStep
  pushChanges // : ReleaseStep, also checks that an upstream branch is properly configured
)
