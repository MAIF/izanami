package domains.user

import domains.auth.AuthInfo
import domains.{AuthorizedPatterns, ImportResult, Key, PatternRights}
import domains.events.EventStore
import libs.crypto.Sha
import libs.logs.ZLogger
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.{JsSuccess, JsValue, Json}
import domains.errors.{DataShouldExists, IdMustBeTheSame, IzanamiErrors, Unauthorized, ValidationError}
import store.datastore.JsonDataStore
import store.memory.InMemoryJsonDataStore
import test.{FakeApplicationLifecycle, IzanamiSpec, TestEventStore}
import zio.{Runtime, Task, ULayer, ZLayer}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import domains.apikey.Apikey
import domains.events.Events
import domains.events.Events._
import akka.stream.scaladsl.{Sink, Source}
import cats.data.NonEmptyList
import domains.configuration.PlayModule
import domains.configuration.PlayModule.PlayModuleProd
import play.api.{Configuration, Environment}

class UserSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience with BeforeAndAfterAll {

  implicit val system = ActorSystem("test")
  import IzanamiErrors._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPatterns.All, true))

  def authInfo(patterns: AuthorizedPatterns = AuthorizedPatterns.All, admin: Boolean = false) =
    Some(Apikey("1", "name", "****", patterns, admin = admin))

  implicit val runtime = Runtime.default

  "User" must {

    "Hash password" in {
      val context = testUserContext()

      val key = Key("user1")
      val ragnard =
        IzanamiUser("user1",
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val created: Either[IzanamiErrors, User] = run(context)(UserService.create(key, ragnard).either)

      created mustBe Right(ragnard.copy(password = Some(Sha.hexSha512("ragnar123456"))))

      run(context)(UserService.getById(key).option).flatten mustBe Some(
        ragnard.copy(password = Some(Sha.hexSha512("ragnar123456")))
      )

      val toUpdate                             = ragnard.copy(password = Some("ragnar1234"))
      val updated: Either[IzanamiErrors, User] = run(context)(UserService.update(key, key, toUpdate).either)
      updated mustBe Right(toUpdate.copy(password = Some(Sha.hexSha512("ragnar1234"))))

      run(context)(UserService.getById(key).option).flatten mustBe Some(
        toUpdate.copy(password = Some(Sha.hexSha512("ragnar1234")))
      )
    }
  }

  "User serder" must {

    "read IzanamiUser" in {
      val user =
        IzanamiUser("user1",
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"              -> "Izanami",
        "id"                -> "user1",
        "name"              -> "Ragnard",
        "email"             -> "ragnard@gmail.com",
        "password"          -> "ragnar123456",
        "admin"             -> false,
        "authorizedPattern" -> "*"
      )

      val read = Json.fromJson(json)(UserInstances.format)
      read must be(JsSuccess(user))
    }

    "read IzanamiUser new version" in {
      val user =
        IzanamiUser("user1",
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"               -> "Izanami",
        "id"                 -> "user1",
        "name"               -> "Ragnard",
        "email"              -> "ragnard@gmail.com",
        "password"           -> "ragnar123456",
        "admin"              -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D")))
      )

      val read = Json.fromJson(json)(UserInstances.format)
      read must be(JsSuccess(user))
    }

    "read IzanamiUser without type" in {
      val user =
        IzanamiUser("user1",
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "id"                -> "user1",
        "name"              -> "Ragnard",
        "email"             -> "ragnard@gmail.com",
        "password"          -> "ragnar123456",
        "admin"             -> false,
        "authorizedPattern" -> "*"
      )

      val read = Json.fromJson(json)(UserInstances.format)
      read must be(JsSuccess(user))
    }

    "write IzanamiUser" in {
      val user =
        IzanamiUser("user1",
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"               -> "Izanami",
        "password"           -> "ragnar123456",
        "id"                 -> "user1",
        "name"               -> "Ragnard",
        "email"              -> "ragnard@gmail.com",
        "admin"              -> false,
        "temporary"          -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D")))
      )

      val written: JsValue = UserInstances.format.writes(user)
      written must be(json)
    }

    "write IzanamiUser without password" in {
      val user =
        IzanamiUser("user1",
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"               -> "Izanami",
        "id"                 -> "user1",
        "name"               -> "Ragnard",
        "email"              -> "ragnard@gmail.com",
        "admin"              -> false,
        "temporary"          -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D")))
      )

      val written: JsValue = UserNoPasswordInstances.format.writes(user)
      written must be(json)
    }

    "read OauthUser" in {
      val user = OauthUser("user1", "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"              -> "OAuth",
        "id"                -> "user1",
        "name"              -> "Ragnard",
        "email"             -> "ragnard@gmail.com",
        "admin"             -> false,
        "authorizedPattern" -> "*"
      )

      val read = UserInstances.format.reads(json)
      read must be(JsSuccess(user))
    }

    "read OauthUser new version" in {
      val user = OauthUser("user1", "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"               -> "OAuth",
        "id"                 -> "user1",
        "name"               -> "Ragnard",
        "email"              -> "ragnard@gmail.com",
        "admin"              -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D")))
      )

      val read = UserInstances.format.reads(json)
      read must be(JsSuccess(user))
    }

    "write OauthUser" in {
      val user = OauthUser("user1", "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"               -> "OAuth",
        "id"                 -> "user1",
        "name"               -> "Ragnard",
        "email"              -> "ragnard@gmail.com",
        "admin"              -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D")))
      )

      val written: JsValue = UserInstances.format.writes(user)
      written must be(json)
    }

    "read OtoroshiUser" in {
      val user = OtoroshiUser("user1", "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"              -> "Otoroshi",
        "id"                -> "user1",
        "name"              -> "Ragnard",
        "email"             -> "ragnard@gmail.com",
        "admin"             -> false,
        "authorizedPattern" -> "*"
      )

      val read = Json.fromJson(json)(UserInstances.format)
      read must be(JsSuccess(user))
    }

    "read OtoroshiUser new version" in {
      val user = OtoroshiUser("user1", "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"               -> "Otoroshi",
        "id"                 -> "user1",
        "name"               -> "Ragnard",
        "email"              -> "ragnard@gmail.com",
        "admin"              -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D")))
      )

      val read = Json.fromJson(json)(UserInstances.format)
      read must be(JsSuccess(user))
    }

    "write OtoroshihUser" in {
      val user = OtoroshiUser("user1", "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val json = Json.obj(
        "type"               -> "Otoroshi",
        "id"                 -> "user1",
        "name"               -> "Ragnard",
        "email"              -> "ragnard@gmail.com",
        "admin"              -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D")))
      )

      val written: JsValue = UserInstances.format.writes(user)
      written must be(json)
    }

  }

  "UserService" must {

    "create IzanamiUser" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val expectedUser = user.copy(password = Some(Sha.hexSha512("ragnar123456")))

      val created = run(ctx)(UserService.create(id, user))

      created must be(expectedUser)
      userDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 1
      inside(events.head) {
        case UserCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "create OAuthUser" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user          = OauthUser(id.key, "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val expectedUser  = user

      val created = run(ctx)(UserService.create(id, user))

      created must be(expectedUser)
      userDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 1
      inside(events.head) {
        case UserCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "create forbidden" in {
      val id            = Key("test")
      val user          = OauthUser(id.key, "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx =
        testUserContext(events, userDataStore, authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R)))

      val value = run(ctx)(UserService.create(id, user).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(id))))
      userDataStore.inMemoryStore.contains(id) must be(false)
    }

    "create id not equal" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser("user1",
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))

      val created = run(ctx)(UserService.create(id, user).either)
      created must be(Left(IdMustBeTheSame(Key(user.id), id).toErrors))
      userDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "update if data not exists" in {
      val id  = Key("test")
      val ctx = testUserContext()
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))

      val updated = run(ctx)(UserService.update(id, id, user).either)
      updated must be(Left(DataShouldExists(id).toErrors))
    }

    "update OAuthUser" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        OauthUser(id.key, "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val expectedUser = user

      val test = for {
        _       <- UserService.create(id, user)
        updated <- UserService.update(id, id, user)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(expectedUser)
      userDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 2
      inside(events.last) {
        case UserUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(expectedUser)
          newValue must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "update changing password" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val expectedUser = user.copy(password = Some(Sha.hexSha512("ragnar123456")))

      val test = for {
        _       <- UserService.create(id, user)
        updated <- UserService.update(id, id, user)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(expectedUser)
      userDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 2
      inside(events.last) {
        case UserUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(expectedUser)
          newValue must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "update keeping password" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val oldUser      = user.copy(password = Some(Sha.hexSha512("ragnar123456")))
      val expectedUser = user.copy(name = "Ragnard Lodbrok", password = Some(Sha.hexSha512("ragnar123456")))

      val test = for {
        created <- UserService.create(id, user)
        updated <- UserService.update(id, id, user.copy(name = "Ragnard Lodbrok", password = extractPassword(created)))
      } yield updated

      val updated = run(ctx)(test)
      updated must be(expectedUser)
      userDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 2
      inside(events.last) {
        case UserUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(oldUser)
          newValue must be(expectedUser)
          auth must be(authInfo)
      }
    }

    def extractPassword(user: User): Option[String] = user match {
      case u: IzanamiUser => u.password
      case _              => None
    }

    "update changing id" in {
      val id            = Key("test")
      val newId         = Key("test2")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val expectedUser = user.copy(password = Some(Sha.hexSha512("ragnar123456")))

      val test = for {
        _       <- UserService.create(id, user)
        updated <- UserService.update(id, newId, user)
      } yield updated

      val updated = run(ctx)(test)
      userDataStore.inMemoryStore.contains(id) must be(false)
      userDataStore.inMemoryStore.contains(newId) must be(true)
      events must have size 2
      inside(events.last) {
        case UserUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(expectedUser)
          newValue must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "update forbidden" in {
      val id            = Key("test")
      val user          = OauthUser(id.key, "Ragnard", "ragnard@gmail.com", false, AuthorizedPatterns.fromString("*"))
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx =
        testUserContext(events, userDataStore, authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R)))

      val value = run(ctx)(UserService.update(id, id, user).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(id))))
      userDataStore.inMemoryStore.contains(id) must be(false)
    }

    "delete" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))
      val expectedUser = user.copy(password = Some(Sha.hexSha512("ragnar123456")))

      val test = for {
        _       <- UserService.create(id, user)
        deleted <- UserService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      userDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 2
      inside(events.last) {
        case UserDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(expectedUser)
          auth must be(authInfo)
      }
    }

    "delete forbidden" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx =
        testUserContext(events, userDataStore, authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R)))

      val value = run(ctx)(UserService.delete(id).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(id))))
      userDataStore.inMemoryStore.contains(id) must be(false)
    }

    "delete empty data" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)

      val deleted = run(ctx)(UserService.delete(id).either)
      deleted must be(Left(DataShouldExists(id).toErrors))
      userDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "import data" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))

      val res = run(ctx)(UserService.importData().flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(List((id.key, UserInstances.format.writes(user))))
            .via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(success = 1))
    }

    "import data invalid format" in {
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))

      val res = run(ctx)(UserService.importData().flatMap { flow =>
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
      val id            = Key("test")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val userDataStore = new InMemoryJsonDataStore("user-test")
      val ctx           = testUserContext(events, userDataStore)
      val user =
        IzanamiUser(id.key,
                    "Ragnard",
                    "ragnard@gmail.com",
                    Some("ragnar123456"),
                    false,
                    false,
                    AuthorizedPatterns.fromString("*"))

      val test = for {
        _ <- UserService.create(id, user)
        res <- UserService.importData().flatMap { flow =>
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
      res must contain only (ImportResult())
    }

  }

  def testUserContext(events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
                      userDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("user-test"),
                      user: Option[AuthInfo.Service] = authInfo): ZLayer[Any, Throwable, UserContext] =
    ZLogger.live ++ UserDataStore.value(userDataStore) ++ EventStore.value(new TestEventStore(events)) ++ AuthInfo
      .optValue(user)

}
