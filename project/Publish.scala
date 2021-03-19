import xerial.sbt.Sonatype.autoImport.{sonatypeCredentialHost, sonatypeRepository}

object Publish {
  val organization = "fr.maif"
  val settings = List(
    sonatypeRepository := "https://s01.oss.sonatype.org/service/local",
    sonatypeCredentialHost := "s01.oss.sonatype.org"
  )
}
