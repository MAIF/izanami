import xerial.sbt.Sonatype.autoImport.{sonatypeCredentialHost, sonatypeRepository}

object Publish {
  val organization                = "fr.maif"
  val sonatypeRepositoryValue     = "https://s01.oss.sonatype.org/service/local"
  val sonatypeCredentialHostValue = "s01.oss.sonatype.org"
  val settings = List(
    sonatypeRepository := sonatypeRepositoryValue,
    sonatypeCredentialHost := sonatypeCredentialHostValue
  )
}
