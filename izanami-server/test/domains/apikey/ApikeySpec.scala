package domains.apikey

import domains.auth.AuthInfo
import domains.AuthorizedPatterns
import domains.events.{EventStore, Events}
import domains.events.Events._
import domains.Key
import libs.logs.ZLogger
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures

import scala.collection.mutable
import store.memory.InMemoryJsonDataStore
import domains.errors.{DataShouldExists, IdMustBeTheSame, Unauthorized, ValidationError}
import test.IzanamiSpec
import test.TestEventStore
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import play.api.libs.json.{JsSuccess, Json}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.data.NonEmptyList
import zio.{Task, ZLayer}
import domains.ImportResult

class ApikeySpec extends IzanamiSpec with ScalaFutures with IntegrationPatience {

  implicit val system = ActorSystem("test")

  import domains.errors.IzanamiErrors._

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPatterns.All, true))

  def authInfo(patterns: AuthorizedPatterns = AuthorizedPatterns.All, admin: Boolean = false) =
    Some(Apikey("1", "name", "****", patterns, admin = admin))

  "Api key serder" must {
    "reads json" in {
      val apiKey = Apikey("key", "akey", "password", AuthorizedPatterns.All, false)
      val json = Json.obj(
        "clientId"          -> "key",
        "name"              -> "akey",
        "clientSecret"      -> "password",
        "authorizedPattern" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D"))),
        "admin"             -> false
      )

      Json.fromJson(json)(ApikeyInstances.format) must be(JsSuccess(apiKey))
    }

    "reads json new version" in {
      val apiKey = Apikey("key", "akey", "password", AuthorizedPatterns.All, false)
      val json = Json.obj(
        "clientId"           -> "key",
        "name"               -> "akey",
        "clientSecret"       -> "password",
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D"))),
        "admin"              -> false
      )

      Json.fromJson(json)(ApikeyInstances.format) must be(JsSuccess(apiKey))
    }

    "wites json" in {
      val apiKey = Apikey("key", "akey", "password", AuthorizedPatterns.All, false)
      val json = Json.obj(
        "clientId"           -> "key",
        "name"               -> "akey",
        "clientSecret"       -> "password",
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D"))),
        "admin"              -> false
      )

      Json.toJson(apiKey)(ApikeyInstances.format) must be(json)
    }
  }

  "ApikeyService" must {

    "create" in {
      val id              = Key("clientId")
      val events          = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx             = testApikeyContext(events = events, apikeyDataStore = apikeyDataStore)
      val apikey          = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val created = run(ctx)(ApikeyService.create(id, apikey))
      created must be(apikey)
      apikeyDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 1
      inside(events.head) {
        case ApikeyCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(apikey)
          auth must be(authInfo)
      }
    }

    "create forbidden" in {
      val id              = Key("clientId")
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx =
        testApikeyContext(apikeyDataStore = apikeyDataStore, user = authInfo(admin = false))
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val value = run(ctx)(ApikeyService.create(id, apikey).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(None)))
      apikeyDataStore.inMemoryStore.contains(id) must be(false)
    }

    "create id not equal" in {
      val id              = Key("test")
      val events          = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx             = testApikeyContext(events = events, apikeyDataStore = apikeyDataStore)
      val apikey          = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val created = run(ctx)(ApikeyService.create(id, apikey).either)
      created must be(Left(IdMustBeTheSame(Key("clientId"), id).toErrors))
      apikeyDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "update if data not exists" in {
      val id     = Key("clientId")
      val ctx    = testApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val updated = run(ctx)(ApikeyService.update(id, id, apikey).either)
      updated must be(Left(DataShouldExists(id).toErrors))
    }

    "update" in {
      val id              = Key("clientId")
      val events          = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx             = testApikeyContext(events = events, apikeyDataStore = apikeyDataStore)
      val apikey          = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val test = for {
        _       <- ApikeyService.create(id, apikey)
        updated <- ApikeyService.update(id, id, apikey)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(apikey)
      apikeyDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 2
      inside(events.last) {
        case ApikeyUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(apikey)
          newValue must be(apikey)
          auth must be(authInfo)
      }
    }

    "update forbidden" in {
      val id              = Key("clientId")
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx             = testApikeyContext(apikeyDataStore = apikeyDataStore, user = authInfo(admin = false))
      val apikey          = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val value = run(ctx)(ApikeyService.update(id, id, apikey).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(None)))
      apikeyDataStore.inMemoryStore.contains(id) must be(false)

    }

    "update changing id" in {
      val id              = Key("clientId")
      val newId           = Key("clientId2")
      val events          = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx             = testApikeyContext(events = events, apikeyDataStore = apikeyDataStore)
      val apikey          = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val test = for {
        _       <- ApikeyService.create(id, apikey)
        updated <- ApikeyService.update(id, newId, apikey)
      } yield updated

      val updated = run(ctx)(test)
      apikeyDataStore.inMemoryStore.contains(id) must be(false)
      apikeyDataStore.inMemoryStore.contains(newId) must be(true)
      events must have size 2
      inside(events.last) {
        case ApikeyUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(apikey)
          newValue must be(apikey)
          auth must be(authInfo)
      }
    }

    "delete" in {
      val id              = Key("clientId")
      val events          = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx             = testApikeyContext(events = events, apikeyDataStore = apikeyDataStore)
      val apikey          = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val test = for {
        _       <- ApikeyService.create(id, apikey)
        deleted <- ApikeyService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      apikeyDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 2
      inside(events.last) {
        case ApikeyDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(apikey)
          auth must be(authInfo)
      }
    }

    "delete forbidden" in {
      val id              = Key("clientId")
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx =
        testApikeyContext(apikeyDataStore = apikeyDataStore, user = authInfo(admin = false))

      val value = run(ctx)(ApikeyService.delete(id).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(None)))
      apikeyDataStore.inMemoryStore.contains(id) must be(false)

    }

    "delete empty data" in {
      val id              = Key("clientId")
      val events          = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val apikeyDataStore = new InMemoryJsonDataStore("apikey-test")
      val ctx             = testApikeyContext(events = events, apikeyDataStore = apikeyDataStore)

      val deleted = run(ctx)(ApikeyService.delete(id).either)
      deleted must be(Left(DataShouldExists(id).toErrors))
      apikeyDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "import data" in {
      val id     = Key("clientId")
      val ctx    = testApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val res = run(ctx)(ApikeyService.importData().flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(List((id.key, ApikeyInstances.format.writes(apikey))))
            .via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(success = 1))
    }

    "import data invalid format" in {
      val id     = Key("clientId")
      val ctx    = testApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val res = run(ctx)(ApikeyService.importData().flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(
            List(
              (id.key, Json.obj())
            )
          ).via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(errors = List(ValidationError.error("json.parse.error", id.key))))
    }

    "import data data exist" in {
      val id     = Key("clientId")
      val ctx    = testApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPatterns.All)

      val test = for {
        _ <- ApikeyService.create(id, apikey)
        res <- ApikeyService.importData().flatMap { flow =>
                Task.fromFuture { implicit ec =>
                  Source(
                    List(
                      (id.key, ApikeyInstances.format.writes(apikey))
                    )
                  ).via(flow)
                    .runWith(Sink.seq)
                }
              }
      } yield res

      val res = run(ctx)(test)
      res must contain only (ImportResult())
    }

  }

  def testApikeyContext(
      events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
      user: Option[AuthInfo.Service] = authInfo,
      apikeyDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("apikey-test")
  ): ZLayer[Any, Throwable, ApiKeyContext] =
    ZLogger.live ++ ApikeyDataStore.value(apikeyDataStore) ++ EventStore.value(new TestEventStore(events)) ++ AuthInfo
      .optValue(user)

}
