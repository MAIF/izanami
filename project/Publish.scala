import sbt._
import Keys._
import bintray.BintrayPlugin.autoImport.{bintrayOrganization, bintrayReleaseOnPublish, bintrayVcsUrl}
import sbtrelease.ReleasePlugin.autoImport.releaseCrossBuild

/**
 * For projects that are not to be published.
 */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(
    publishArtifact := false,
    publish := {},
    publishLocal := {}
  )
}

object BintrayConfig {

  lazy val githubRepo = "maif/izanami"

  lazy val publishCommonsSettings = Seq(
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
      Developer("alexandre.delegue", "Alexandre Delègue", "", url(s"https://github.com/larousso"))
    ),
    releaseCrossBuild := true,
    publishMavenStyle := true,
    publishArtifact in Test := false,
    bintrayVcsUrl := Some(s"scm:git:git@github.com:$githubRepo.git")
  )

  lazy val publishSettings =
    if (sys.env.get("TAG_NAME").filterNot(_.isEmpty).isDefined) {
      publishCommonsSettings ++ Seq(
        bintrayOrganization := Some("maif"),
        pomIncludeRepository := { _ =>
          false
        }
      )
    } else {
      publishCommonsSettings ++ Seq(
        publishTo := Some(
          ("Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local").withAllowInsecureProtocol(true)
        ),
        bintrayReleaseOnPublish := false,
        credentials := List(
          Credentials("Artifactory Realm",
                      "oss.jfrog.org",
                      sys.env.getOrElse("BINTRAY_USER", ""),
                      sys.env.getOrElse("BINTRAY_PASS", ""))
        )
      )
    }
}
