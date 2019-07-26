package domains.apikey

import domains.AuthInfo
import domains.AuthorizedPattern
import domains.events.Events
import domains.events.Events._
import domains.events.EventStore
import domains.Key
import libs.logs.Logger
import libs.logs.ProdLogger
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import scala.collection.mutable
import store.JsonDataStore
import store.memory.InMemoryJsonDataStore
import store.Result.DataShouldExists
import test.IzanamiSpec
import test.TestEventStore
import zio.internal.PlatformLive
import zio.RIO
import zio.ZIO
import org.scalatest.run
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import play.api.libs.json.Json
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import zio.Task
import domains.ImportResult
import store.Result.AppErrors
import store.Result.IdMustBeTheSame

class ApikeySpec extends IzanamiSpec with ScalaFutures with IntegrationPatience {

  implicit val system = ActorSystem("test")
  implicit val mat    = ActorMaterializer()

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPattern("pattern")))

  "ApikeyService" must {

    "create" in {
      val id     = Key("clientId")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val created = run(ctx)(ApikeyService.create(id, apikey))
      created must be(apikey)
      ctx.apikeyDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 1
      inside(ctx.events.head) {
        case ApikeyCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(apikey)
          auth must be(authInfo)
      }
    }

    "create id not equal" in {
      val id     = Key("test")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val created = run(ctx)(ApikeyService.create(id, apikey).either)
      created must be(Left(IdMustBeTheSame(Key("clientId"), id)))
      ctx.apikeyDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "update if data not exists" in {
      val id     = Key("clientId")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val updated = run(ctx)(ApikeyService.update(id, id, apikey).either)
      updated must be(Left(DataShouldExists(id)))
    }

    "update" in {
      val id     = Key("clientId")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val test = for {
        _       <- ApikeyService.create(id, apikey)
        updated <- ApikeyService.update(id, id, apikey)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(apikey)
      ctx.apikeyDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case ApikeyUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(apikey)
          newValue must be(apikey)
          auth must be(authInfo)
      }
    }

    "update changing id" in {
      val id     = Key("clientId")
      val newId  = Key("clientId2")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val test = for {
        _       <- ApikeyService.create(id, apikey)
        updated <- ApikeyService.update(id, newId, apikey)
      } yield updated

      val updated = run(ctx)(test)
      ctx.apikeyDataStore.inMemoryStore.contains(id) must be(false)
      ctx.apikeyDataStore.inMemoryStore.contains(newId) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case ApikeyUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(apikey)
          newValue must be(apikey)
          auth must be(authInfo)
      }
    }

    "delete" in {
      val id     = Key("clientId")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val test = for {
        _       <- ApikeyService.create(id, apikey)
        deleted <- ApikeyService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      ctx.apikeyDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case ApikeyDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(apikey)
          auth must be(authInfo)
      }
    }

    "delete empty data" in {
      val id     = Key("clientId")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val deleted = run(ctx)(ApikeyService.delete(id).either)
      deleted must be(Left(DataShouldExists(id)))
      ctx.apikeyDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "import data" in {
      val id     = Key("clientId")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val res = run(ctx)(ApikeyService.importData.flatMap { flow =>
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
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val res = run(ctx)(ApikeyService.importData.flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(
            List(
              (id.key, Json.obj())
            )
          ).via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(errors = AppErrors.error("json.parse.error", id.key)))
    }

    "import data data exist" in {
      val id     = Key("clientId")
      val ctx    = TestApikeyContext()
      val apikey = Apikey("clientId", "name", "secret", AuthorizedPattern("pattern"))

      val test = for {
        _ <- ApikeyService.create(id, apikey)
        res <- ApikeyService.importData.flatMap { flow =>
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
      res must contain only (ImportResult(errors = AppErrors.error("error.data.exists", id.key)))
    }

  }

  case class TestApikeyContext(events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
                               user: Option[AuthInfo] = None,
                               apikeyDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("apikey-test"),
                               logger: Logger = new ProdLogger,
                               authInfo: Option[AuthInfo] = authInfo)
      extends ApiKeyContext {
    override def eventStore: EventStore                              = new TestEventStore(events)
    override def withAuthInfo(user: Option[AuthInfo]): ApiKeyContext = this.copy(user = user)
  }

}
