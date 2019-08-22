package store

import domains.{AkkaModule, GlobalContext, Key}
import domains.abtesting.{ExperimentContext, ExperimentService, ExperimentVariantEventService}
import domains.apikey.{ApiKeyContext, ApikeyService}
import domains.config.{ConfigContext, ConfigService}
import domains.events.EventStore
import domains.feature.{FeatureContext, FeatureService}
import domains.script.{GlobalScriptContext, GlobalScriptService}
import domains.user.{UserContext, UserService}
import domains.webhook.{WebhookContext, WebhookService}
import store.Result.IzanamiErrors
import zio.ZIO
import zio.RIO

object Healthcheck {

  def check(): RIO[GlobalContext, Unit] = {
    val key = Key("test")

    EventStore.check() *>
    GlobalScriptService.getById(key) *>
    ConfigService.getById(key) *>
    FeatureService.getById(key) *>
    ExperimentService.getById(key) *>
    ExperimentVariantEventService.check() *>
    WebhookService.getById(key) *>
    UserService.getById(key) *>
    ApikeyService.getById(key) *> ZIO.succeed(())
  }

}
