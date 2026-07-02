package fr.maif.izanami.services

import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.models.LightWebhook
import fr.maif.izanami.errors.GenericBadRequest
import scala.util.Try
import fr.maif.izanami.datastores.WebhooksDatastore
import fr.maif.izanami.web.UserInformation
import com.github.jknack.handlebars.Handlebars
import fr.maif.izanami.models.Webhook
import scala.concurrent.Future
import fr.maif.izanami.utils.Done
import java.util.UUID
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.services.WebhookService.checkWebhook

class WebhookService(datastore: WebhooksDatastore) {
  def createWebhook(
      tenant: String,
      webhook: LightWebhook,
      user: UserInformation
  ): FutureEither[String] = {
    val maybeError = checkWebhook(webhook)

    maybeError.fold(datastore.createWebhook(
      tenant,
      webhook,
      user
    ))(error => FutureEither.failure(error))
  }

  def listWebhook(tenant: String, user: String): Future[Seq[Webhook]] = {
    datastore.listWebhook(tenant, user = user)
  }

  def deleteWebhook(
      tenant: String,
      webhook: String
  ): FutureEither[Done] = {
    datastore.deleteWebhook(tenant = tenant, webhook = webhook)
  }

  def updateWebhook(
      tenant: String,
      id: UUID,
      webhook: LightWebhook
  ): FutureEither[Done] = {

    val maybeError = checkWebhook(webhook)
    maybeError.fold(datastore
      .updateWebhook(tenant, id, webhook))(error => FutureEither.failure(error))
  }

}

object WebhookService {
  private val handlebars = new Handlebars();

  private def checkWebhook(webhook: LightWebhook): Option[IzanamiError] = {
    if (
      webhook.global && (webhook.features.nonEmpty || webhook.projects.nonEmpty)
    ) {
      Some(GenericBadRequest(
        "Webhook can't be global and specify features or projects"
      ))
    } else if (
      !webhook.global && webhook.projects.isEmpty && webhook.features.isEmpty
    ) {
      Some(GenericBadRequest(
        "Webhook must either be global or specify features or projects"
      ))
    } else {
      webhook.bodyTemplate.flatMap(template => {
        Try {
          handlebars.compileInline(template)
          webhook
        }.toEither
          .left
          .map(_ => GenericBadRequest("Bad handlebar template"))
          .swap
          .toOption
      })
    }
  }
}
