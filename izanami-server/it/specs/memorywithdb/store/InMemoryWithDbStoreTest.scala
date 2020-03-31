package specs.memorywithdb.store

import akka.actor.ActorSystem
import domains.events.Events.{FeatureCreated, FeatureUpdated}
import domains.Key
import domains.feature.{DefaultFeature, FeatureInstances}
import env.{InMemory, InMemoryWithDbConfig}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import store.memory.InMemoryJsonDataStore
import test.{FakeApplicationLifecycle, TestEventStore}
import cats.syntax.option._
import domains.events.impl.BasicEventStore
import domains.events.{EventStore, EventStoreModule}
import libs.logs.ZLogger
import store.datastore.DataStoreContext
import zio.internal.Platform
import zio.{Runtime, ZLayer}

import scala.concurrent.duration.DurationDouble
import domains.user.User
import domains.auth.AuthInfo
import store.memorywithdb.{InMemoryWithDbStore, OpenResources}

class InMemoryWithDbStoreTest extends PlaySpec with ScalaFutures with IntegrationPatience {

  implicit val actorSystem: ActorSystem = ActorSystem()

  implicit class RunOps[T](t: zio.RIO[DataStoreContext, T]) {
    def unsafeRunSync(): T = Runtime.default.unsafeRun(t.provideLayer(context))
  }

  "InMemoryWithDbStore" must {
    "update his cache on event" in {
      import domains.feature.FeatureInstances

      val name            = "test"
      val underlyingStore = new InMemoryJsonDataStore(name)

      val key1     = Key("key:1")
      val key2     = Key("key:2")
      val feature1 = DefaultFeature(key1, false, None)
      underlyingStore.create(key1, FeatureInstances.format.writes(feature1)).either.unsafeRunSync()

      println(s"Creating store")
      val inMemoryWithDb = new InMemoryWithDbStore(
        InMemoryWithDbConfig(db = InMemory, None),
        name,
        underlyingStore,
        InMemoryWithDbStore.featureEventAdapter,
        Runtime.default.unsafeRun(zio.Ref.make(Option.empty[OpenResources]))
      )
      inMemoryWithDb.start.unsafeRunSync()
      Thread.sleep(500)

      val feature1Updated = feature1.copy(enabled = true)
      val feature2        = DefaultFeature(key2, false, None)

      actorSystem.eventStream.publish(FeatureUpdated(key1, feature1, feature1Updated, authInfo = None))
      actorSystem.eventStream.publish(FeatureCreated(key2, feature2, authInfo = None))

      Thread.sleep(500)
      inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe FeatureInstances.format
        .writes(feature1Updated)
        .some
      inMemoryWithDb.getById(key2).option.unsafeRunSync().flatten mustBe FeatureInstances.format.writes(feature2).some

    }
  }

  "scheduler should reload cache" in {

    val name            = "test"
    val underlyingStore = new InMemoryJsonDataStore(name)

    val key1     = Key("key:1")
    val key2     = Key("key:2")
    val feature1 = DefaultFeature(key1, false, None)
    underlyingStore.create(key1, FeatureInstances.format.writes(feature1)).either.unsafeRunSync()

    val inMemoryWithDb = new InMemoryWithDbStore(
      InMemoryWithDbConfig(db = InMemory, Some(500.milliseconds)),
      name,
      underlyingStore,
      InMemoryWithDbStore.featureEventAdapter,
      Runtime.default.unsafeRun(zio.Ref.make(Option.empty[OpenResources]))
    )
    inMemoryWithDb.start.unsafeRunSync()

    Thread.sleep(500)
    inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe FeatureInstances.format.writes(feature1).some
    val feature1Updated = feature1.copy(enabled = true)
    underlyingStore.update(key1, key1, FeatureInstances.format.writes(feature1Updated)).either.unsafeRunSync()
    inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe FeatureInstances.format.writes(feature1).some
    Thread.sleep(600)
    inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe FeatureInstances.format
      .writes(feature1Updated)
      .some
  }

  val context: ZLayer[Any, Throwable, DataStoreContext] =
  ZLogger.live ++ EventStore.value(new BasicEventStore) ++ AuthInfo.empty

}
