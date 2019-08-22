package domains.webhook

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.{JsSuccess, Json}
import test.IzanamiSpec
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import domains.apikey.Apikey
import domains.{AuthorizedPattern, Key}
import scala.collection.mutable
import domains.AuthInfo
import store.memory.InMemoryJsonDataStore
import libs.logs.ProdLogger
import libs.logs.Logger
import domains.events.EventStore
import test.TestEventStore
import domains.events.Events
import domains.events.Events._
import store.Result.{DataShouldExists, IdMustBeTheSame}
import zio._
import akka.stream.scaladsl.{Sink, Source}
import domains.ImportResult
import store.Result.AppErrors

class WebhookSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience with BeforeAndAfterAll {

  implicit val system = ActorSystem("test")
  implicit val mat    = ActorMaterializer()

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPattern("pattern")))

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
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val created = run(ctx)(WebhookService.create(id, webhook))
      created must be(webhook)
      ctx.webhookDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 1
      inside(ctx.events.head) {
        case WebhookCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(webhook)
          auth must be(authInfo)
      }
    }

    "create id not equal" in {
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(Key("other"), "http://localhost:8080")

      val created = run(ctx)(WebhookService.create(id, webhook).either)
      created must be(Left(IdMustBeTheSame(webhook.clientId, id)))
      ctx.webhookDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "update if data not exists" in {
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val updated = run(ctx)(WebhookService.update(id, id, webhook).either)
      updated must be(Left(DataShouldExists(id)))
    }

    "update" in {
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val test = for {
        _       <- WebhookService.create(id, webhook)
        updated <- WebhookService.update(id, id, webhook)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(webhook)
      ctx.webhookDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case WebhookUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(webhook)
          newValue must be(webhook)
          auth must be(authInfo)
      }
    }

    "update changing id" in {
      val id      = Key("test")
      val newId   = Key("test2")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val test = for {
        _       <- WebhookService.create(id, webhook)
        updated <- WebhookService.update(id, newId, webhook)
      } yield updated

      val updated = run(ctx)(test)
      ctx.webhookDataStore.inMemoryStore.contains(id) must be(false)
      ctx.webhookDataStore.inMemoryStore.contains(newId) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case WebhookUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(webhook)
          newValue must be(webhook)
          auth must be(authInfo)
      }
    }

    "delete" in {
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val test = for {
        _       <- WebhookService.create(id, webhook)
        deleted <- WebhookService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      ctx.webhookDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case WebhookDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(webhook)
          auth must be(authInfo)
      }
    }

    "delete empty data" in {
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val deleted = run(ctx)(WebhookService.delete(id).either)
      deleted must be(Left(DataShouldExists(id)))
      ctx.webhookDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "import data" in {
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val res = run(ctx)(WebhookService.importData.flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(List((id.key, WebhookInstances.format.writes(webhook))))
            .via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(success = 1))
    }

    "import data invalid format" in {
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val res = run(ctx)(WebhookService.importData.flatMap { flow =>
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
      val id      = Key("test")
      val ctx     = TestWebhookContext()
      val webhook = Webhook(id, "http://localhost:8080")

      val test = for {
        _ <- WebhookService.create(id, webhook)
        res <- WebhookService.importData.flatMap { flow =>
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
      res must contain only (ImportResult(errors = AppErrors.error("error.data.exists", id.key)))
    }

  }

  case class TestWebhookContext(events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
                                user: Option[AuthInfo] = None,
                                webhookDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("webhook-test"),
                                logger: Logger = new ProdLogger,
                                authInfo: Option[AuthInfo] = authInfo)
      extends WebhookContext {
    override def eventStore: EventStore                               = new TestEventStore(events)
    override def withAuthInfo(user: Option[AuthInfo]): WebhookContext = this.copy(user = user)
  }

}
