import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._

name := """izanami"""
organization := Publish.organization
scalaVersion := Dependencies.scalaVersion

lazy val root = (project in file("."))
  .aggregate(
    `izanami-server`,
    `izanami-clients`
  )
  .enablePlugins(GitVersioning, GitBranchPrompt)
  .settings(skip in publish := true, scalaVersion := Dependencies.scalaVersion)

lazy val `izanami-documentation` = project
  .settings(skip in publish := true)

lazy val `izanami-server` = project

lazy val `izanami-clients` = project

lazy val `example` = project

lazy val simulation = project
  .settings(skip in publish := true)

val setVersionToNpmProject = ReleaseStep(action = st => {
  import sys.process._
  // extract the build state
  val extracted = Project.extract(st)
  // retrieve the value of the organization SettingKey
  val version = extracted.get(Keys.version)

  val regex = "(.*)-SNAPSHOT".r
  version match {
    case regex(v) =>
      s"bash release.sh $v" !
    case _ =>
      s"bash release.sh $version" !
  }

  st
})

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies, // : ReleaseStep
  inquireVersions,           // : ReleaseStep
  runClean,                  // : ReleaseStep
  //runTest, // : ReleaseStep
  setReleaseVersion, // : ReleaseStep
  setVersionToNpmProject,
  commitReleaseVersion, // : ReleaseStep, performs the initial git checks
  tagRelease,           // : ReleaseStep
  //publishArtifacts,                       // : ReleaseStep, checks whether `publishTo` is properly set up
  setNextVersion, // : ReleaseStep
  setVersionToNpmProject,
  commitNextVersion, // : ReleaseStep
  pushChanges        // : ReleaseStep, also checks that an upstream branch is properly configured
)

lazy val githubRepo = "maif/izanami"
inThisBuild(
  List(
    homepage := Some(url(s"https://github.com/$githubRepo")),
    startYear := Some(2017),
    licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    scmInfo := Some(
        ScmInfo(
          url(s"https://github.com/$githubRepo"),
          s"scm:git:https://github.com/$githubRepo.git",
          Some(s"scm:git:git@github.com:$githubRepo.git")
        )
      ),
    developers := List(
        Developer("alexandre.delegue", "Alexandre Delègue", "", url(s"https://github.com/larousso")),
        Developer("pierre.brunin", "Pierre Brunin", "", url(s"https://github.com/pierrebruninmaif"))
      ),
    releaseCrossBuild := true,
    publishMavenStyle := true,
    publishArtifact in Test := false
  )
)

usePgpKeyHex("4CFAF84EA6075473D68FA7D9590BEB09DDA3F0B7")
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
sonatypeCredentialHost := "s01.oss.sonatype.org"
