package store

import domains.{AuthorizedPattern, AuthorizedPatterns, Key, PatternRights}
import domains.configuration.GlobalContext
import domains.abtesting.ExperimentService
import domains.abtesting.events.ExperimentVariantEventService
import domains.apikey.ApikeyService
import domains.config.ConfigService
import domains.errors.IzanamiErrors
import domains.events.EventStore
import domains.feature.FeatureService
import domains.script.GlobalScriptService
import domains.user.{OauthUser, UserService}
import domains.webhook.WebhookService
import zio.{Has, ZIO}

object Healthcheck {

  def check(): ZIO[GlobalContext, IzanamiErrors, Unit] = {
    val key = Key("test")

    val check = (EventStore.check() *>
    GlobalScriptService.getById(key) *>
    ConfigService.getById(key) *>
    FeatureService.getById(key) *>
    ExperimentService.getById(key) *>
    ExperimentVariantEventService.check() *>
    WebhookService.getById(key) *>
    UserService.getByIdWithoutPermissions(key).orDie *>
    ApikeyService.getByIdWithoutPermissions(key).orDie *> ZIO.succeed(()))

    for {
      ctx <- ZIO.environment[GlobalContext]
      // FIXME test if auth if working for healthcheck
      newCtx = ctx ++ Has(
        Some(OauthUser("health", "health", "health", false, AuthorizedPatterns.of("test" -> PatternRights.R)))
      )
    } yield check.provide(newCtx).provideSomeLayer
  }

}
