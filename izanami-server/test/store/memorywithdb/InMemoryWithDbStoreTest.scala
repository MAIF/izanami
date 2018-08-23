package store.memorywithdb

import akka.actor.ActorSystem
import cats.effect.IO
import domains.events.Events.{FeatureCreated, FeatureUpdated}
import domains.Key
import domains.events.impl.BasicEventStore
import domains.feature.DefaultFeature
import env.{InMemory, InMemoryWithDbConfig}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import store.memory.InMemoryJsonDataStore
import test.FakeApplicationLifecycle
import cats.syntax.option._

import scala.concurrent.duration.DurationDouble

class InMemoryWithDbStoreTest extends PlaySpec with ScalaFutures with IntegrationPatience {

  implicit val actorSystem: ActorSystem = ActorSystem()
  import actorSystem.dispatcher

  "InMemoryWithDbStore" must {
    "update is cache on event" in {

      val name            = "test"
      val underlyingStore = new InMemoryJsonDataStore[IO](name)

      val key1     = Key("key:1")
      val key2     = Key("key:2")
      val feature1 = DefaultFeature(key1, false)
      underlyingStore.create(key1, Json.toJson(feature1))
      val eventStore = new BasicEventStore[IO]

      val inMemoryWithDb = new InMemoryWithDbStore[IO](
        InMemoryWithDbConfig(db = InMemory, None),
        name,
        underlyingStore,
        eventStore,
        InMemoryWithDbStore.featureEventAdapter,
        FakeApplicationLifecycle()
      )
      Thread.sleep(500)

      val feature1Updated = feature1.copy(enabled = true)
      val feature2        = DefaultFeature(key2, false)

      actorSystem.eventStream.publish(FeatureUpdated(key1, feature1, feature1Updated))
      actorSystem.eventStream.publish(FeatureCreated(key2, feature2))

      Thread.sleep(500)

      inMemoryWithDb.getById(key1) mustBe Json.toJson(feature1Updated).some
      inMemoryWithDb.getById(key2) mustBe Json.toJson(feature2).some

    }
  }

  "scheduler should reload cache" in {
    val name            = "test"
    val underlyingStore = new InMemoryJsonDataStore[IO](name)

    val key1     = Key("key:1")
    val key2     = Key("key:2")
    val feature1 = DefaultFeature(key1, false)
    underlyingStore.create(key1, Json.toJson(feature1))
    val eventStore = new BasicEventStore[IO]

    val inMemoryWithDb = new InMemoryWithDbStore[IO](
      InMemoryWithDbConfig(db = InMemory, Some(500.milliseconds)),
      name,
      underlyingStore,
      eventStore,
      InMemoryWithDbStore.featureEventAdapter,
      FakeApplicationLifecycle()
    )
    Thread.sleep(500)
    inMemoryWithDb.getById(key1) mustBe Json.toJson(feature1).some
    val feature1Updated = feature1.copy(enabled = true)
    underlyingStore.update(key1, key1, Json.toJson(feature1Updated))
    inMemoryWithDb.getById(key1) mustBe Json.toJson(feature1).some
    Thread.sleep(600)
    inMemoryWithDb.getById(key1) mustBe Json.toJson(feature1Updated).some
  }

}
