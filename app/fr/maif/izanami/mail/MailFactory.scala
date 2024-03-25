package fr.maif.izanami.mail

import fr.maif.izanami.env.Env

class   MailFactory(env: Env) {
  def invitationEmail(target: String, token: String): Mail = {
    val baseUrl = env.expositionUrl
    val completeUrl = s"${baseUrl}/invitation?token=${token}"
    Mail(
      subject = "You've been invited to Izanami",
      targetMail = target,
      textContent =
        s"""
           |You've been invited to Izanami.
           |Click on this link to finalize your account creation : ${baseUrl}?invitation=${token}
           |
           |If you don't know what it's about, you can safely ignore this mail.
           |""".stripMargin,
      htmlContent =
        s"""
           |You've been invited to Izanami.
           |Click <a href="${completeUrl}">here</a> to finalize your account creation.
           |
           |If you don't know what it's about, you can safely ignore this mail.
           |""".stripMargin
    )
  }

  def passwordResetEmail(target: String, token: String): Mail = {
    val baseUrl = env.expositionUrl
      val completeUrl = s"${baseUrl}/password/_reset?token=${token}"
    Mail(
      subject = "Izanami password reset",
      targetMail = target,
      textContent =
        s"""
           |A password reset request has been made for your account.
           |Click on this link to reset your password : ${completeUrl}
           |
           |If you you didn't ask to reset your password, you can safely ignore this mail.
           |""".stripMargin,
      htmlContent =
        s"""
           |A password reset request has been made for your account.
           |Click <a href="${completeUrl}">here</a> to reset your password.
           |
           |If you you didn't ask to reset your password, you can safely ignore this mail.
           |""".stripMargin
    )
  }
}
