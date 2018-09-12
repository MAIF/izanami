package store

import akka.actor.ActorSystem
import cats.Applicative
import domains.Key
import domains.abtesting.{ExperimentService, ExperimentVariantEventService}
import domains.apikey.ApikeyService
import domains.config.ConfigService
import domains.events.EventStore
import domains.feature.FeatureService
import domains.script.GlobalScriptService
import domains.user.UserService
import domains.webhook.WebhookService

class Healthcheck[F[_]: Applicative](
    eventStore: EventStore[F],
    globalScriptStore: GlobalScriptService[F],
    configStore: ConfigService[F],
    featureStore: FeatureService[F],
    experimentStore: ExperimentService[F],
    experimentVariantEventStore: ExperimentVariantEventService[F],
    webhookStore: WebhookService[F],
    userStore: UserService[F],
    apikeyStore: ApikeyService[F]
)(implicit system: ActorSystem) {

  import cats.implicits._

  def check(): F[Unit] = {
    val key = Key("test")

    (
      eventStore.check(),
      globalScriptStore.getById(key),
      configStore.getById(key),
      featureStore.getById(key),
      experimentStore.getById(key),
      experimentVariantEventStore.check(),
      webhookStore.getById(key),
      userStore.getById(key),
      apikeyStore.getById(key)
    ).mapN { (_, _, _, _, _, _, _, _, _) =>
      ()
    }
  }

}
