package store

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import cats.implicits._
import com.typesafe.config.{Config, ConfigFactory}
import domains.Key
import domains.events.EventStore
import libs.logs.{Logger, ProdLogger}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsValue, Json}
import store.Result.{ErrorMessage, IzanamiErrors}
import test.TestEventStore
import zio.internal.PlatformLive
import zio.{DefaultRuntime, Runtime}

import scala.concurrent.ExecutionContext
import scala.util.Random
import store.Result.DataShouldNotExists
import store.Result.DataShouldExists
import domains.user.User
import domains.AuthInfo

abstract class AbstractJsonDataStoreTest(name: String) extends PlaySpec with ScalaFutures with IntegrationPatience {

  def akkaConfig: Option[Config] = None

  implicit val system: ActorSystem = ActorSystem("Test", akkaConfig.map(c => c.withFallback(ConfigFactory.load())).getOrElse(ConfigFactory.load()))
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat : Materializer = ActorMaterializer()
  private val context = new DataStoreContext {
    override def eventStore: EventStore = new TestEventStore()
    override def logger: Logger         = new ProdLogger
    override def withAuthInfo(authInfo: Option[AuthInfo]): DataStoreContext = this
    override def authInfo: Option[AuthInfo]                        = None
  }
  implicit val runtime: Runtime[DataStoreContext] = Runtime(context, PlatformLive.Default)
  private val random = Random
  import zio._
  import zio.interop.catz._

  implicit class RunOps[T](t: zio.RIO[DataStoreContext, T]) {
    def unsafeRunSync(): T = runtime.unsafeRun(t)
  }

  def dataStore(dataStore: String) : JsonDataStore

  def initAndGetDataStore(name: String): JsonDataStore = {
    val store = dataStore(name)
    runtime.unsafeRun(store.start)
    store
  }

  s"$name data store" must {

    "crud" in {
      val ds = initAndGetDataStore(s"crud:test:${random.nextInt(10000)}")

      val key1 = Key("key:1")
      val value1 = Json.obj("name" -> "test")
      val mayBeCreated: Either[IzanamiErrors, JsValue] = ds.create(key1, value1).either.unsafeRunSync()

      mayBeCreated must be(Result.ok(value1))

      val foundInDb: Option[JsValue] = ds.getById(key1).option.unsafeRunSync().flatten

      foundInDb must be(Some(value1))

      val value1Updated = Json.obj("name" -> "test1")
      val mayBeUpdated: Either[IzanamiErrors, JsValue] = ds.update(key1, key1, value1Updated).either.unsafeRunSync()

      mayBeUpdated must be(Result.ok(value1Updated))

      val deleted: Either[IzanamiErrors, JsValue] = ds.delete(key1).either.unsafeRunSync()
      deleted must be(Result.ok(value1Updated))

      val emptyInDb: Option[JsValue] = ds.getById(key1).option.unsafeRunSync().flatten
      emptyInDb must be(None)
    }

    "create twice" in {
      val ds = initAndGetDataStore(s"crud:test:${random.nextInt(10000)}")
      val key1 = Key("key:1")
      val value1 = Json.obj("name" -> "test")

      val mayBeCreated: Either[IzanamiErrors, JsValue] = ds.create(key1, value1).either.unsafeRunSync()
      mayBeCreated must be(Result.ok(value1))

      val mayBeCreatedTwice: Either[IzanamiErrors, JsValue] = ds.create(key1, value1).either.unsafeRunSync()
      mayBeCreatedTwice must be(Result.error[JsValue](DataShouldNotExists(Key("key:1"))))
    }

    "update if not exists" in {
      val ds = initAndGetDataStore(s"crud:test:${random.nextInt(10000)}")
      val key1 = Key("key:1")
      val value1 = Json.obj("name" -> "test")

      val mayBeCreated: Either[IzanamiErrors, JsValue] = ds.update(key1, key1, value1).either.unsafeRunSync()

      mayBeCreated must be(Result.error[JsValue](DataShouldExists(key1)))
    }

    "update changing id " in {
      val ds = initAndGetDataStore(s"crud:test:${random.nextInt(10000)}")
      val key1 = Key("key:1")
      val key2 = Key("key:2")
      val value1 = Json.obj("name" -> "test")

      val mayBeCreated: Either[IzanamiErrors, JsValue] = ds.create(key1, value1).either.unsafeRunSync()
      mayBeCreated must be(Result.ok(value1))

      val mayBeUpdated: Either[IzanamiErrors, JsValue] = ds.update(key1, key2, value1).either.unsafeRunSync()
      mayBeUpdated must be(Result.ok(value1))

      ds.getById(key1).option.unsafeRunSync().flatten must be(None)
      ds.getById(key2).option.unsafeRunSync().flatten must be(Some(value1))

    }

    "find by query" in {

      val ds = initAndGetDataStore(s"crud:test:${random.nextInt(10000)}")


      val v1 = (Key("key:1"), Json.obj("name" -> "value1"))
      val v2 = (Key("key:2"), Json.obj("name" -> "value2"))
      val v3 = (Key("key2:subkey1:subkey1"), Json.obj("name" -> "value3"))
      val v4 = (Key("key2:subkey1:subkey2"), Json.obj("name" -> "value4"))
      val v5 = (Key("key2:subkey2:subkey1"), Json.obj("name" -> "value5"))
      val v6 = (Key("key3:subkey"), Json.obj("name" -> "value6"))

      List(v1, v2, v3, v4, v5, v6).traverse {
        case (k, v) => ds.create(k, v)
      }.either.unsafeRunSync()


      val results = ds.findByQuery(Query.oneOf("key:1", "key:2", "key4"), 1, 10).unsafeRunSync()
      results.count must be(2)
      results.results must contain theSameElementsAs Seq(v1._2, v2._2)


      val results2 = ds.findByQuery(Query.oneOf("key:1", "key:2", "key4").and(Query.oneOf("key:1")), 1, 10).unsafeRunSync()

      results2.count must be(1)
      results2.results must contain theSameElementsAs Seq(v1._2)

      val resultsLike = ds.findByQuery(Query.oneOf("key:1", "key2:subkey1:*", "key4"), 1, 10).unsafeRunSync()
      resultsLike.count must be(3)
      resultsLike.results must contain theSameElementsAs Seq(v1._2, v3._2, v4._2)
    }

    "delete all" in {

      val ds = initAndGetDataStore(s"crud:test:${random.nextInt(10000)}")
      (1 to 10)
        .map { i => (Key(s"key:$i"), Json.obj("name" -> s"value-$i")) }
        .toList
        .traverse { case (k, v) => ds.create(k, v) }
        .either
        .unsafeRunSync()

      val result: Seq[(Key, JsValue)] = ds.findByQuery(Query.oneOf("*")).unsafeRunSync().runWith(Sink.seq).futureValue

      result.size must be(10)

      ds.deleteAll(Query.oneOf("*")).either.unsafeRunSync()

      val resultAfterDelete: PagingResult[JsValue] = ds.findByQuery(Query.oneOf("*"), 1, 50).unsafeRunSync()
      resultAfterDelete.count must be(0)
    }

  }
}
