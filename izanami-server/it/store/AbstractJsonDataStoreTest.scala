package store

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.implicits._
import com.typesafe.config.{Config, ConfigFactory}
import domains.Key
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsValue, Json}
import store.Result.{ErrorMessage, Result}

import scala.concurrent.ExecutionContext
import scala.util.Random

abstract class AbstractJsonDataStoreTest(name: String) extends PlaySpec with ScalaFutures with IntegrationPatience {

  def akkaConfig: Option[Config] = None

  implicit val system: ActorSystem = ActorSystem("Test", akkaConfig.map(c => c.withFallback(ConfigFactory.load())).getOrElse(ConfigFactory.load()))
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat : Materializer = ActorMaterializer()

  private val random = Random

  def dataStore(dataStore: String) : JsonDataStore[IO]

  s"$name data store" must {

    "crud" in {
      val ds = dataStore(s"crud:test:${random.nextInt(10000)}")

      val key1 = Key("key:1")
      val value1 = Json.obj("name" -> "test")
      val mayBeCreated: Result[JsValue] = ds.create(key1, value1).unsafeRunSync()

      mayBeCreated must be(Result.ok(value1))

      val foundInDb: Option[JsValue] = ds.getById(key1).unsafeRunSync()

      foundInDb must be(Some(value1))

      val value1Updated = Json.obj("name" -> "test1")
      val mayBeUpdated: Result[JsValue] = ds.update(key1, key1, value1Updated).unsafeRunSync()

      mayBeUpdated must be(Result.ok(value1Updated))

      val deleted: Result[JsValue] = ds.delete(key1).unsafeRunSync()
      deleted must be(Result.ok(value1Updated))

      val emptyInDb: Option[JsValue] = ds.getById(key1).unsafeRunSync()
      emptyInDb must be(None)
    }

    "create twice" in {
      val ds = dataStore(s"crud:test:${random.nextInt(10000)}")
      val key1 = Key("key:1")
      val value1 = Json.obj("name" -> "test")

      val mayBeCreated: Result[JsValue] = ds.create(key1, value1).unsafeRunSync()
      mayBeCreated must be(Result.ok(value1))

      val mayBeCreatedTwice: Result[JsValue] = ds.create(key1, value1).unsafeRunSync()
      mayBeCreatedTwice must be(Result.errors[JsValue](ErrorMessage("error.data.exists", "key:1")))
    }

    "update if not exists" in {
      val ds = dataStore(s"crud:test:${random.nextInt(10000)}")
      val key1 = Key("key:1")
      val value1 = Json.obj("name" -> "test")

      val mayBeCreated: Result[JsValue] = ds.update(key1, key1, value1).unsafeRunSync()

      mayBeCreated must be(Result.errors[JsValue](ErrorMessage("error.data.missing", key1.key)))
    }

    "update changing id " in {
      val ds = dataStore(s"crud:test:${random.nextInt(10000)}")
      val key1 = Key("key:1")
      val key2 = Key("key:2")
      val value1 = Json.obj("name" -> "test")

      val mayBeCreated: Result[JsValue] = ds.create(key1, value1).unsafeRunSync()
      mayBeCreated must be(Result.ok(value1))

      val mayBeUpdated: Result[JsValue] = ds.update(key1, key2, value1).unsafeRunSync()
      mayBeUpdated must be(Result.ok(value1))

      ds.getById(key1).unsafeRunSync() must be(None)
      ds.getById(key2).unsafeRunSync() must be(Some(value1))

    }

    "find by query" in {

      val ds = dataStore(s"crud:test:${random.nextInt(10000)}")


      val v1 = (Key("key:1"), Json.obj("name" -> "value1"))
      val v2 = (Key("key:2"), Json.obj("name" -> "value2"))
      val v3 = (Key("key2:subkey1:subkey1"), Json.obj("name" -> "value3"))
      val v4 = (Key("key2:subkey1:subkey2"), Json.obj("name" -> "value4"))
      val v5 = (Key("key2:subkey2:subkey1"), Json.obj("name" -> "value5"))
      val v6 = (Key("key3:subkey"), Json.obj("name" -> "value6"))

      List(v1, v2, v3, v4, v5, v6).traverse {
        case (k, v) => ds.create(k, v)
      }
        .unsafeRunSync()


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

      val ds = dataStore(s"crud:test:${random.nextInt(10000)}")
      (1 to 10)
        .map { i => (Key(s"key:$i"), Json.obj("name" -> s"value-$i")) }
        .toList
        .traverse { case (k, v) => ds.create(k, v) }
        .unsafeRunSync()

      val result: Seq[(Key, JsValue)] = ds.findByQuery(Query.oneOf("*")).runWith(Sink.seq).futureValue

      result.size must be(10)

      ds.deleteAll(Query.oneOf("*")).unsafeRunSync()

      val resultAfterDelete: PagingResult[JsValue] = ds.findByQuery(Query.oneOf("*"), 1, 50).unsafeRunSync()
      resultAfterDelete.count must be(0)
    }

  }
}
