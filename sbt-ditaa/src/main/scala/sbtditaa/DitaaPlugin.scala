package sbtditaa

import sbt.AutoPlugin
import sbt._
import Keys._
object DitaaPlugin extends AutoPlugin {

  object autoImport extends DitaaKeys {

    val Ditaa = sbtditaa.Ditaa

  }
  import autoImport.{Ditaa => _ ,_ }

  override lazy val projectSettings: Seq[Def.Setting[_]] = assemblySettings

  lazy val assemblySettings: Seq[sbt.Def.Setting[_]] = Seq(
    ditaa := Ditaa.generateDiagrams(ditaaSource.value, ditaaDestination.value),
    ditaaSource := (sourceDirectory.value / "src" / "main" / "ditaa"),
    ditaaDestination := (target.value / "ditaa"),
  )

}
