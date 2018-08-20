package store

import akka.actor.ActorSystem
import domains.Key
import domains.abtesting.{ExperimentStore, ExperimentVariantEventStore, VariantBindingKey, VariantBindingStore}
import domains.apikey.ApikeyStore
import domains.config.ConfigStore
import domains.events.EventStore
import domains.feature.FeatureStore
import domains.script.GlobalScriptStore
import domains.user.UserStore
import domains.webhook.WebhookStore

import scala.concurrent.Future

class Healthcheck(
    eventStore: EventStore[Future],
    globalScriptStore: GlobalScriptStore[Future],
    configStore: ConfigStore[Future],
    featureStore: FeatureStore[Future],
    experimentStore: ExperimentStore[Future],
    variantBindingStore: VariantBindingStore[Future],
    experimentVariantEventStore: ExperimentVariantEventStore,
    webhookStore: WebhookStore[Future],
    userStore: UserStore[Future],
    apikeyStore: ApikeyStore[Future]
)(implicit system: ActorSystem) {

  import system.dispatcher
  import cats._
  import cats.implicits._

  def check(): Future[Unit] = {
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
