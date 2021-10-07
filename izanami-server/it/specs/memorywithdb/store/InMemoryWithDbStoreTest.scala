package specs.memorywithdb.store

import akka.actor.ActorSystem
import domains.events.Events.{FeatureCreated, FeatureUpdated}
import domains.Key
import domains.feature.{DefaultFeature, FeatureInstances}
import env.{InMemory, InMemoryEventsConfig, InMemoryWithDbConfig}
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import store.memory.InMemoryJsonDataStore
import cats.syntax.option._
import domains.events.impl.BasicEventStore
import domains.events.EventStore
import libs.logs.ZLogger
import store.datastore.DataStoreContext
import zio.{Runtime, ZLayer}

import scala.concurrent.duration._
import domains.auth.AuthInfo
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.time.{Millis, Seconds, Span}
import store.memorywithdb.{InMemoryWithDbStore, OpenResources}
import zio.clock.Clock

import scala.collection.concurrent.TrieMap

class InMemoryWithDbStoreTest extends PlaySpec with ScalaFutures with IntegrationPatience with Eventually {

  implicit val actorSystem: ActorSystem = ActorSystem()

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(1100, Millis)), interval = scaled(Span(50, Millis)))

  implicit class RunOps[T](t: zio.RIO[DataStoreContext with Clock, T]) {
    def unsafeRunSync(): T = Runtime.default.unsafeRun(t.provideLayer(context))
  }

  "InMemoryWithDbStore" must {
    "update his cache on event" in {
      import domains.feature.FeatureInstances

      val name             = "test"
      val key1             = Key("key:1")
      val key2             = Key("key:2")
      val keyAlive         = Key("key:alive")
      val feature1         = DefaultFeature(key1, false, None)
      val feature1Json     = FeatureInstances.format.writes(feature1)
      val featureAlive     = DefaultFeature(keyAlive, false, None)
      val featureAliveJson = FeatureInstances.format.writes(featureAlive)

      val underlyingStore = new InMemoryJsonDataStore(name, TrieMap(key1 -> feature1Json, keyAlive -> featureAliveJson))

      val inMemoryWithDb = new InMemoryWithDbStore(
        InMemoryWithDbConfig(db = InMemory, None),
        name,
        underlyingStore,
        InMemoryWithDbStore.featureEventAdapter,
        Runtime.default.unsafeRun(zio.Ref.make(Option.empty[OpenResources]))
      )
      inMemoryWithDb.start.unsafeRunSync()

      val featureAliveUpdated    = featureAlive.copy(enabled = true)
      eventually(timeout(scaled(Span(2, Seconds)))) { // wait until InMemoryWithDb / EventStream is not started
        actorSystem.eventStream.publish(FeatureUpdated(keyAlive, featureAlive, featureAliveUpdated, authInfo = None))
        inMemoryWithDb.getById(keyAlive).option.unsafeRunSync().flatten mustBe FeatureInstances.format.writes(featureAliveUpdated).some
      }

      val feature1Updated    = feature1.copy(enabled = true)
      val feature2           = DefaultFeature(key2, false, None)
      val featureUpdatedJson = FeatureInstances.format.writes(feature1Updated)
      val feature2Json       = FeatureInstances.format.writes(feature2)

      actorSystem.eventStream.publish(FeatureUpdated(key1, feature1, feature1Updated, authInfo = None))
      actorSystem.eventStream.publish(FeatureCreated(key2, feature2, authInfo = None))

      eventually {
        Thread.`yield`()
        inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe featureUpdatedJson.some
        inMemoryWithDb.getById(key2).option.unsafeRunSync().flatten mustBe feature2Json.some
      }
    }
  }

  "scheduler should reload cache" in {

    val name = "test-reload"

    val key1            = Key("key:1")
    val feature1        = DefaultFeature(key1, false, None)
    val feature1Json    = FeatureInstances.format.writes(feature1)
    val underlyingStore = new InMemoryJsonDataStore(name, TrieMap(key1 -> feature1Json))

    val inMemoryWithDb = new InMemoryWithDbStore(
      InMemoryWithDbConfig(db = InMemory, Some(1.seconds)),
      name,
      underlyingStore,
      InMemoryWithDbStore.featureEventAdapter,
      Runtime.default.unsafeRun(zio.Ref.make(Option.empty[OpenResources]))
    )
    inMemoryWithDb.start.unsafeRunSync()

    eventually {
      inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe feature1Json.some
    }
    val feature1Updated = feature1.copy(enabled = true)
    val featuer1UpdatedJson = FeatureInstances.format.writes(feature1Updated).some
    underlyingStore.update(key1, key1, FeatureInstances.format.writes(feature1Updated)).either.unsafeRunSync()
    List(feature1Json.some, featuer1UpdatedJson) must contain (inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten)

    eventually {
      Thread.`yield`()
      inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe featuer1UpdatedJson
    }

    underlyingStore.delete(key1).either.unsafeRunSync()

    eventually {
      Thread.`yield`()
      inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe None
    }
  }
  val context: ZLayer[Any, Throwable, DataStoreContext with Clock] =
    ZLogger.live ++ EventStore.value(new BasicEventStore(new InMemoryEventsConfig(500))) ++ AuthInfo.empty ++ Clock.live

}
