package fr.maif.izanami.models

case class OidcLoginAttempt(nonce: String, verifier: Option[String])
