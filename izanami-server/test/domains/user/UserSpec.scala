package domains.user
import domains.{AuthorizedPattern, Key}
import domains.events.{EventStore, Events}
import libs.crypto.Sha
import libs.logs.{Logger, ProdLogger}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.JsValue
import store.Result.IzanamiErrors
import store.JsonDataStore
import store.memory.InMemoryJsonDataStore
import test.{IzanamiSpec, TestEventStore}
import zio.{DefaultRuntime, Task, ZIO}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import domains.AuthInfo
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import domains.apikey.Apikey
import domains.events.Events
import domains.events.Events._
import store.Result.IdMustBeTheSame
import store.Result.DataShouldExists
import akka.stream.scaladsl.{Flow, Sink, Source}
import play.api.libs.json.Json
import domains.ImportResult
import store.Result.AppErrors

class UserSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience with BeforeAndAfterAll {

  implicit val system = ActorSystem("test")
  implicit val mat    = ActorMaterializer()

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPattern("pattern")))

  implicit val runtime = new DefaultRuntime {}

  "User" must {

    "Hash passsword" in {
      val context = userContext()

      val key                                  = Key("user1")
      val ragnard                              = User("user1", "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))
      val created: Either[IzanamiErrors, User] = run(context)(UserService.create(key, ragnard).either)

      created mustBe Right(ragnard.copy(password = Some(Sha.hexSha512("ragnar123456"))))

      run(context)(UserService.getById(key).refineToOrDie[IzanamiErrors].option).flatten mustBe Some(
        ragnard.copy(password = Some(Sha.hexSha512("ragnar123456")))
      )

      val toUpdate                             = ragnard.copy(password = Some("ragnar1234"))
      val updated: Either[IzanamiErrors, User] = run(context)(UserService.update(key, key, toUpdate).either)
      updated mustBe Right(toUpdate.copy(password = Some(Sha.hexSha512("ragnar1234"))))

      run(context)(UserService.getById(key).refineToOrDie[IzanamiErrors].option).flatten mustBe Some(
        toUpdate.copy(password = Some(Sha.hexSha512("ragnar1234")))
      )
    }

  }

  "UserService" must {

    "create" in {
      val id           = Key("test")
      val ctx          = TestUserContext()
      val user         = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))
      val expectedUser = user.copy(password = Some(Sha.hexSha512("ragnar123456")))

      val created = run(ctx)(UserService.create(id, user))

      created must be(expectedUser)
      ctx.userDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 1
      inside(ctx.events.head) {
        case UserCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "create id not equal" in {
      val id   = Key("test")
      val ctx  = TestUserContext()
      val user = User("user1", "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))

      val created = run(ctx)(UserService.create(id, user).either)
      created must be(Left(IdMustBeTheSame(Key(user.id), id)))
      ctx.userDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "update if data not exists" in {
      val id   = Key("test")
      val ctx  = TestUserContext()
      val user = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))

      val updated = run(ctx)(UserService.update(id, id, user).either)
      updated must be(Left(DataShouldExists(id)))
    }

    "update" in {
      val id           = Key("test")
      val ctx          = TestUserContext()
      val user         = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))
      val expectedUser = user.copy(password = Some(Sha.hexSha512("ragnar123456")))

      val test = for {
        _       <- UserService.create(id, user)
        updated <- UserService.update(id, id, user)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(expectedUser)
      ctx.userDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case UserUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(expectedUser)
          newValue must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "update changing id" in {
      val id           = Key("test")
      val newId        = Key("test2")
      val ctx          = TestUserContext()
      val user         = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))
      val expectedUser = user.copy(password = Some(Sha.hexSha512("ragnar123456")))

      val test = for {
        _       <- UserService.create(id, user)
        updated <- UserService.update(id, newId, user)
      } yield updated

      val updated = run(ctx)(test)
      ctx.userDataStore.inMemoryStore.contains(id) must be(false)
      ctx.userDataStore.inMemoryStore.contains(newId) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case UserUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(expectedUser)
          newValue must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "delete" in {
      val id           = Key("test")
      val ctx          = TestUserContext()
      val user         = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))
      val expectedUser = user.copy(password = Some(Sha.hexSha512("ragnar123456")))

      val test = for {
        _       <- UserService.create(id, user)
        deleted <- UserService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      ctx.userDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case UserDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "delete empty data" in {
      val id   = Key("test")
      val ctx  = TestUserContext()
      val user = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))

      val deleted = run(ctx)(UserService.delete(id).either)
      deleted must be(Left(DataShouldExists(id)))
      ctx.userDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "import data" in {
      val id   = Key("test")
      val ctx  = TestUserContext()
      val user = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))

      val res = run(ctx)(UserService.importData.flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(List((id.key, UserInstances.format.writes(user))))
            .via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(success = 1))
    }

    "import data invalid format" in {
      val id   = Key("test")
      val ctx  = TestUserContext()
      val user = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))

      val res = run(ctx)(UserService.importData.flatMap { flow =>
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
      val id   = Key("test")
      val ctx  = TestUserContext()
      val user = User(id.key, "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))

      val test = for {
        _ <- UserService.create(id, user)
        res <- UserService.importData.flatMap { flow =>
                Task.fromFuture { implicit ec =>
                  Source(
                    List(
                      (id.key, UserInstances.format.writes(user))
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

  case class TestUserContext(events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
                             user: Option[AuthInfo] = None,
                             userDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("user-test"),
                             logger: Logger = new ProdLogger,
                             authInfo: Option[AuthInfo] = authInfo)
      extends UserContext {
    override def eventStore: EventStore                            = new TestEventStore(events)
    override def withAuthInfo(user: Option[AuthInfo]): UserContext = this.copy(user = user)
  }

  def userContext(store: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue],
                  events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty): UserContext =
    new UserContext {
      override def logger: Logger = new ProdLogger
      override def userDataStore: JsonDataStore =
        new InMemoryJsonDataStore("users", store)
      override def eventStore: EventStore                                = new TestEventStore(events)
      override def withAuthInfo(authInfo: Option[AuthInfo]): UserContext = this
      override def authInfo: Option[AuthInfo]                            = None
    }

}
