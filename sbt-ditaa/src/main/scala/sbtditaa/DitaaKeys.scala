package sbtditaa


import sbt._
import Keys._

trait DitaaKeys {

  lazy val ditaa = taskKey[File]("Generate ditaa diagram")
  lazy val ditaaSource = taskKey[File]("Source file")
  lazy val ditaaDestination = taskKey[File]("Destination folder")

}
