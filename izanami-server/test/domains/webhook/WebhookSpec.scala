package domains.webhook

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.{JsSuccess, Json}
import test.IzanamiSpec
import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import domains.apikey.Apikey
import domains.{AuthorizedPatterns, ImportResult, Key, PatternRights}
import domains.auth.AuthInfo
import scala.collection.mutable
import store.memory.InMemoryJsonDataStore
import libs.logs.ZLogger
import domains.events.EventStore
import test.TestEventStore
import domains.events.Events
import domains.events.Events._
import domains.errors.{DataShouldExists, IdMustBeTheSame, Unauthorized, ValidationError}
import zio._
import akka.stream.scaladsl.{Sink, Source}
import cats.data.NonEmptyList

class WebhookSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience with BeforeAndAfterAll {

  implicit val system = ActorSystem("test")
  import domains.errors.IzanamiErrors._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPatterns.All))

  def authInfo(patterns: AuthorizedPatterns = AuthorizedPatterns.All, admin: Boolean = false) =
    Some(Apikey("1", "name", "****", patterns, admin = admin))

  "Webhook" should {

    "read json" in {
      import WebhookInstances._
      val date = DateTimeFormatter.ISO_TIME.format(LocalDateTime.now())
      val json = Json.obj("clientId" -> "my:path",
                          "callbackUrl"         -> "http://localhost:5000",
                          "notificationPattern" -> "*",
                          "headers"             -> Json.obj(),
                          "created"             -> date)
      val result = json.validate[Webhook]
      result mustBe an[JsSuccess[_]]
    }

    "read json missing fields" in {
      import WebhookInstances._
      val json =
        Json.obj("clientId" -> "my:path", "callbackUrl" -> "http://localhost:5000", "notificationPattern" -> "*")
      val result = json.validate[Webhook]
      result mustBe an[JsSuccess[_]]
    }
  }

  "WebhookService" must {

    "create" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)
      val webhook          = Webhook(id, "http://localhost:8080")

      val created = run(ctx)(WebhookService.create(id, webhook))
      created must be(webhook)
      webhookDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 1
      inside(events.head) {
        case WebhookCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(webhook)
          auth must be(authInfo)
      }
    }

    "create forbidden" in {
      val id               = Key("test")
      val webhook          = Webhook(id, "http://localhost:8080")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx =
        testWebhookContext(events, webhookDataStore, authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R)))

      val value = run(ctx)(WebhookService.create(id, webhook).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      webhookDataStore.inMemoryStore.contains(id) must be(false)
    }

    "create id not equal" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)
      val webhook          = Webhook(Key("other"), "http://localhost:8080")

      val created = run(ctx)(WebhookService.create(id, webhook).either)
      created must be(Left(IdMustBeTheSame(webhook.clientId, id).toErrors))
      webhookDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "update if data not exists" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)
      val webhook          = Webhook(id, "http://localhost:8080")

      val updated = run(ctx)(WebhookService.update(id, id, webhook).either)
      updated must be(Left(DataShouldExists(id).toErrors))
    }

    "update" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)
      val webhook          = Webhook(id, "http://localhost:8080")

      val test = for {
        _       <- WebhookService.create(id, webhook)
        updated <- WebhookService.update(id, id, webhook)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(webhook)
      webhookDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 2
      inside(events.last) {
        case WebhookUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(webhook)
          newValue must be(webhook)
          auth must be(authInfo)
      }
    }

    "update forbidden" in {
      val id               = Key("test")
      val webhook          = Webhook(id, "http://localhost:8080")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx =
        testWebhookContext(events, webhookDataStore, authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R)))

      val value = run(ctx)(WebhookService.update(id, id, webhook).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      webhookDataStore.inMemoryStore.contains(id) must be(false)
    }

    "update changing id" in {
      val id               = Key("test")
      val newId            = Key("test2")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)
      val webhook          = Webhook(id, "http://localhost:8080")

      val test = for {
        _       <- WebhookService.create(id, webhook)
        updated <- WebhookService.update(id, newId, webhook)
      } yield updated

      val updated = run(ctx)(test)
      webhookDataStore.inMemoryStore.contains(id) must be(false)
      webhookDataStore.inMemoryStore.contains(newId) must be(true)
      events must have size 2
      inside(events.last) {
        case WebhookUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(webhook)
          newValue must be(webhook)
          auth must be(authInfo)
      }
    }

    "delete" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)
      val webhook          = Webhook(id, "http://localhost:8080")

      val test = for {
        _       <- WebhookService.create(id, webhook)
        deleted <- WebhookService.delete(id)
      } yield deleted

      run(ctx)(test)
      webhookDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 2
      inside(events.last) {
        case WebhookDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(webhook)
          auth must be(authInfo)
      }
    }

    "delete forbidden" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx =
        testWebhookContext(events, webhookDataStore, authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R)))

      val value = run(ctx)(WebhookService.delete(id).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      webhookDataStore.inMemoryStore.contains(id) must be(false)
    }

    "delete empty data" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)

      val deleted = run(ctx)(WebhookService.delete(id).either)
      deleted must be(Left(DataShouldExists(id).toErrors))
      webhookDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "import data" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)
      val webhook          = Webhook(id, "http://localhost:8080")

      val res = run(ctx)(WebhookService.importData().flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(List((id.key, WebhookInstances.format.writes(webhook))))
            .via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(success = 1))
    }

    "import data invalid format" in {
      val id  = Key("test")
      val ctx = testWebhookContext()

      val res = run(ctx)(WebhookService.importData().flatMap { flow =>
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
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val webhookDataStore = new InMemoryJsonDataStore("webhook-test")
      val ctx              = testWebhookContext(events, webhookDataStore)
      val webhook          = Webhook(id, "http://localhost:8080")

      val test = for {
        _ <- WebhookService.create(id, webhook)
        res <- WebhookService.importData().flatMap { flow =>
                Task.fromFuture { implicit ec =>
                  Source(
                    List(
                      (id.key, WebhookInstances.format.writes(webhook))
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

  def testWebhookContext(events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
                         webhookDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("webhook-test"),
                         user: Option[AuthInfo.Service] = authInfo): ZLayer[Any, Throwable, WebhookContext] =
    ZLogger.live ++ WebhookDataStore.value(webhookDataStore) ++ EventStore.value(new TestEventStore(events)) ++ AuthInfo
      .optValue(user)

}
