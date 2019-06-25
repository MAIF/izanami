package store.mongo

import akka.actor.ActorSystem
import cats.effect.IO
import domains.Key
import env.{DbDomainConfig, DbDomainConfigDetails, Mongo}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.{JsValue, Json}
import play.modules.reactivemongo.DefaultReactiveMongoApi
import reactivemongo.api.MongoConnection
import store.{Query, Result}
import store.Result.Result
import test.FakeApplicationLifecycle

import scala.concurrent.ExecutionContext
import scala.util.Random

class MongoJsonDataStoreTest extends PlaySpec with ScalaFutures with IntegrationPatience {

  implicit val system: ActorSystem = ActorSystem("TestMongo")
  implicit val ec: ExecutionContext = system.dispatcher

  def dataStore(name: String): MongoJsonDataStore[IO] = MongoJsonDataStore[IO](
    new DefaultReactiveMongoApi(
      MongoConnection.parseURI("mongodb://localhost:27017").get,
      "test", false, Configuration.empty, FakeApplicationLifecycle()
    ),
    DbDomainConfig(Mongo, DbDomainConfigDetails(name, None), None)
  )

  "MongoDataStore" must {

    "crud" in {
      val ds = dataStore(s"crud-test-${Random.nextInt(50)}")

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
  }


  "find by query" in {
    import cats._
    import cats.syntax._
    import cats.implicits._

    val ds = dataStore(s"crud-test-${Random.nextInt(50)}")


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
    results.results must be(Seq(v1._2, v2._2))


    val results2 = ds.findByQuery(Query.oneOf("key:1", "key:2", "key4").and(Query.oneOf("key:1")), 1, 10).unsafeRunSync()

    results2.count must be(1)
    results2.results must be(Seq(v1._2))
  }

}
