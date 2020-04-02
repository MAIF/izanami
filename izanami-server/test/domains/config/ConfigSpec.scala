package domains.config

import domains.apikey.Apikey
import domains.{AuthInfo, AuthorizedPatterns, ImportResult, Key, PatternRight, PatternRights}
import domains.events.Events
import domains.events.Events._
import domains.events.EventStore
import libs.logs.Logger
import libs.logs.ProdLogger
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import play.api.libs.json.Json

import scala.collection.mutable
import store.memory.InMemoryJsonDataStore
import domains.errors.{DataShouldExists, IdMustBeTheSame, Unauthorized, ValidationError}
import test.IzanamiSpec
import test.TestEventStore
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.scalatest.BeforeAndAfterAll
import akka.testkit.TestKit
import zio.Task
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import cats.data.NonEmptyList

object FakeAuth {
  def authInfo(patterns: AuthorizedPatterns = AuthorizedPatterns.All, admin: Boolean = false) =
    Some(Apikey("1", "name", "****", patterns, admin = admin))

}

class ConfigSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience with BeforeAndAfterAll {
  import FakeAuth._

  implicit val system = ActorSystem("test")

  import domains.errors.IzanamiErrors._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "ConfigService" must {

    "create" in {
      val id     = Key("test")
      val ctx    = TestConfigContext()
      val config = Config(id, Json.obj("key" -> "value"))

      val created = run(ctx)(ConfigService.create(id, config))
      created must be(config)
      ctx.configDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 1
      inside(ctx.events.head) {
        case ConfigCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(config)
          auth must be(authInfo())
      }
    }

    "create forbidden" in {
      val id = Key("test")
      val ctx =
        TestConfigContext(authInfo = authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R)))
      val config = Config(id, Json.obj("key" -> "value"))

      val value = run(ctx)(ConfigService.create(id, config).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      ctx.configDataStore.inMemoryStore.contains(id) must be(false)
    }

    "create id not equal" in {
      val id     = Key("test")
      val ctx    = TestConfigContext()
      val config = Config(Key("other"), Json.obj("key" -> "value"))

      val created = run(ctx)(ConfigService.create(id, config).either)
      created must be(Left(IdMustBeTheSame(config.id, id).toErrors))
      ctx.configDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "update if data not exists" in {
      val id     = Key("test")
      val ctx    = TestConfigContext()
      val config = Config(id, Json.obj("key" -> "value"))

      val updated = run(ctx)(ConfigService.update(id, id, config).either)
      updated must be(Left(DataShouldExists(id).toErrors))
    }

    "update" in {
      val id     = Key("test")
      val ctx    = TestConfigContext()
      val config = Config(id, Json.obj("key" -> "value"))

      val test = for {
        _       <- ConfigService.create(id, config)
        updated <- ConfigService.update(id, id, config)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(config)
      ctx.configDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case ConfigUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(config)
          newValue must be(config)
          auth must be(authInfo())
      }
    }

    "update forbidden" in {
      val id = Key("test")
      val ctx =
        TestConfigContext(authInfo = authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.C)))
      val config = Config(id, Json.obj("key" -> "value"))

      val value = run(ctx)(ConfigService.update(id, id, config).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      ctx.configDataStore.inMemoryStore.contains(id) must be(false)
    }

    "update changing id" in {
      val id     = Key("test")
      val newId  = Key("test2")
      val ctx    = TestConfigContext()
      val config = Config(id, Json.obj("key" -> "value"))

      val test = for {
        _       <- ConfigService.create(id, config)
        updated <- ConfigService.update(id, newId, config)
      } yield updated

      val updated = run(ctx)(test)
      ctx.configDataStore.inMemoryStore.contains(id) must be(false)
      ctx.configDataStore.inMemoryStore.contains(newId) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case ConfigUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(config)
          newValue must be(config)
          auth must be(authInfo())
      }
    }

    "delete" in {
      val id     = Key("test")
      val ctx    = TestConfigContext()
      val config = Config(id, Json.obj("key" -> "value"))

      val test = for {
        _       <- ConfigService.create(id, config)
        deleted <- ConfigService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      ctx.configDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case ConfigDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(config)
          auth must be(authInfo())
      }
    }

    "delete forbidden" in {
      val id = Key("test")
      val ctx =
        TestConfigContext(authInfo = authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.C)))

      val value = run(ctx)(ConfigService.delete(id).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      ctx.configDataStore.inMemoryStore.contains(id) must be(false)
    }

    "delete empty data" in {
      val id  = Key("test")
      val ctx = TestConfigContext()

      val deleted = run(ctx)(ConfigService.delete(id).either)
      deleted must be(Left(DataShouldExists(id).toErrors))
      ctx.configDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "import data" in {
      val id     = Key("test")
      val ctx    = TestConfigContext()
      val config = Config(id, Json.obj("key" -> "value"))

      val res = run(ctx)(ConfigService.importData().flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(List((id.key, ConfigInstances.format.writes(config))))
            .via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(success = 1))
    }

    "import data invalid format" in {
      val id     = Key("test")
      val ctx    = TestConfigContext()
      val config = Config(id, Json.obj("key" -> "value"))

      val res = run(ctx)(ConfigService.importData().flatMap { flow =>
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
      val id     = Key("test")
      val ctx    = TestConfigContext()
      val config = Config(id, Json.obj("key" -> "value"))

      val test = for {
        _ <- ConfigService.create(id, config)
        res <- ConfigService.importData().flatMap { flow =>
                Task.fromFuture { implicit ec =>
                  Source(
                    List(
                      (id.key, ConfigInstances.format.writes(config))
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

  case class TestConfigContext(events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
                               user: Option[AuthInfo] = None,
                               configDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("config-test"),
                               logger: Logger = new ProdLogger,
                               authInfo: Option[AuthInfo] = FakeAuth.authInfo())
      extends ConfigContext {
    override def eventStore: EventStore                              = new TestEventStore(events)
    override def withAuthInfo(user: Option[AuthInfo]): ConfigContext = this.copy(user = user)
  }

}
