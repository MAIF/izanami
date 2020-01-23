package store

import domains.{GlobalContext, Key}
import domains.abtesting.{ExperimentService, ExperimentVariantEventService}
import domains.apikey.ApikeyService
import domains.config.ConfigService
import domains.errors.IzanamiErrors
import domains.events.EventStore
import domains.feature.FeatureService
import domains.script.GlobalScriptService
import domains.user.UserService
import domains.webhook.WebhookService
import zio.ZIO

object Healthcheck {

  def check(): ZIO[GlobalContext, IzanamiErrors, Unit] = {
    val key = Key("test")

    EventStore.check() *>
    GlobalScriptService.getById(key) *>
    ConfigService.getById(key) *>
    FeatureService.getById(key) *>
    ExperimentService.getById(key) *>
    ExperimentVariantEventService.check() *>
    WebhookService.getById(key) *>
    UserService.getByIdWithoutPermissions(key).refineToOrDie[IzanamiErrors] *>
    ApikeyService.getByIdWithoutPermissions(key).refineToOrDie[IzanamiErrors] *> ZIO.succeed(())
  }

}
