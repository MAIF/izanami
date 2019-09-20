package specs.memorywithdb.store

import akka.actor.ActorSystem
import domains.events.Events.{FeatureCreated, FeatureUpdated}
import domains.Key
import domains.feature.DefaultFeature
import env.{InMemory, InMemoryWithDbConfig}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import store.memory.InMemoryJsonDataStore
import test.FakeApplicationLifecycle
import cats.syntax.option._
import domains.events.impl.BasicEventStore
import domains.events.{EventStore, EventStoreModule}
import libs.logs.{Logger, ProdLogger}
import store.DataStoreContext
import zio.internal.PlatformLive
import zio.Runtime

import scala.concurrent.duration.DurationDouble
import domains.user.User
import domains.AuthInfo
import store.memorywithdb.InMemoryWithDbStore

class InMemoryWithDbStoreTest extends PlaySpec with ScalaFutures with IntegrationPatience {

  implicit val actorSystem: ActorSystem = ActorSystem()

  implicit class RunOps[T](t: zio.RIO[DataStoreContext, T]) {
    def unsafeRunSync()(implicit runtime: Runtime[DataStoreContext]): T = runtime.unsafeRun(t)
  }

  "InMemoryWithDbStore" must {
    "update his cache on event" in {
      import domains.feature.FeatureInstances._

      implicit val runtime: Runtime[DataStoreContext] = buildRuntime

      val name            = "test"
      val underlyingStore = new InMemoryJsonDataStore(name)

      val key1     = Key("key:1")
      val key2     = Key("key:2")
      val feature1 = DefaultFeature(key1, false, None)
      underlyingStore.create(key1, Json.toJson(feature1)).either.unsafeRunSync()

      val inMemoryWithDb = new InMemoryWithDbStore(
        InMemoryWithDbConfig(db = InMemory, None),
        name,
        underlyingStore,
        InMemoryWithDbStore.featureEventAdapter,
        new FakeApplicationLifecycle()
      )
      runtime.unsafeRun(inMemoryWithDb.start)
      Thread.sleep(500)

      val feature1Updated = feature1.copy(enabled = true)
      val feature2        = DefaultFeature(key2, false, None)

      actorSystem.eventStream.publish(FeatureUpdated(key1, feature1, feature1Updated, authInfo = None))
      actorSystem.eventStream.publish(FeatureCreated(key2, feature2, authInfo = None))

      Thread.sleep(500)

      inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe Json.toJson(feature1Updated).some
      inMemoryWithDb.getById(key2).option.unsafeRunSync().flatten mustBe Json.toJson(feature2).some

    }
  }

  "scheduler should reload cache" in {
    import domains.feature.FeatureInstances._
    implicit val runtime: Runtime[DataStoreContext] = buildRuntime

    val name            = "test"
    val underlyingStore = new InMemoryJsonDataStore(name)

    val key1     = Key("key:1")
    val key2     = Key("key:2")
    val feature1 = DefaultFeature(key1, false, None)
    underlyingStore.create(key1, Json.toJson(feature1)).either.unsafeRunSync()

    val inMemoryWithDb = new InMemoryWithDbStore(
      InMemoryWithDbConfig(db = InMemory, Some(500.milliseconds)),
      name,
      underlyingStore,
      InMemoryWithDbStore.featureEventAdapter,
      new FakeApplicationLifecycle()
    )
    runtime.unsafeRun(inMemoryWithDb.start)

    Thread.sleep(500)
    inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe Json.toJson(feature1).some
    val feature1Updated = feature1.copy(enabled = true)
    underlyingStore.update(key1, key1, Json.toJson(feature1Updated)).either.unsafeRunSync()
    inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe Json.toJson(feature1).some
    Thread.sleep(600)
    inMemoryWithDb.getById(key1).option.unsafeRunSync().flatten mustBe Json.toJson(feature1Updated).some
  }


  def buildRuntime: Runtime[DataStoreContext] = {
    val module = new DataStoreContext {
      override def eventStore: EventStore = new BasicEventStore
      override def logger: Logger         = new ProdLogger
      override def withAuthInfo(authInfo: Option[AuthInfo]): DataStoreContext = this
      override def authInfo: Option[AuthInfo]                        = None
    }
    Runtime(module, PlatformLive.Default)
  }
}
