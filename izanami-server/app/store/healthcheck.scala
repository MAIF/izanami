package store

import akka.actor.ActorSystem
import cats.Applicative
import domains.Key
import domains.abtesting.{ExperimentStore, ExperimentVariantEventStore, VariantBindingKey, VariantBindingStore}
import domains.apikey.ApikeyService
import domains.config.ConfigService
import domains.events.EventStore
import domains.feature.FeatureService
import domains.script.GlobalScriptService
import domains.user.UserStore
import domains.webhook.WebhookStore

class Healthcheck[F[_]: Applicative](
    eventStore: EventStore[F],
    globalScriptStore: GlobalScriptService[F],
    configStore: ConfigService[F],
    featureStore: FeatureService[F],
    experimentStore: ExperimentStore[F],
    variantBindingStore: VariantBindingStore[F],
    experimentVariantEventStore: ExperimentVariantEventStore[F],
    webhookStore: WebhookStore[F],
    userStore: UserStore[F],
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
      variantBindingStore.getById(VariantBindingKey(key)),
      experimentVariantEventStore.check(),
      webhookStore.getById(key),
      userStore.getById(key),
      apikeyStore.getById(key)
    ).mapN { (_, _, _, _, _, _, _, _, _, _) =>
      ()
    }
  }

}
