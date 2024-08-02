// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "fr.maif"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// Open-source license of your choice
licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

homepage := Some(url("https://github.com/MAIF/izanami"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/MAIF/izanami"),
    "scm:git@github.com:MAIF/izanami.git"
  )
)

description := "Izanami is an open source centralized feature flag solution."

organization := "fr.maif"

developers := List(
  Developer("ptitFicus", "Benjamin Cavy", email = "benjamin.cavy@maif.fr", url = url("https://github.com/ptitFicus/"))
)
