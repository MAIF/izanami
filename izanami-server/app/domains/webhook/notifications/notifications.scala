package domains.webhook.notifications

import java.util.Base64

import akka.Done
import akka.actor.{Actor, Cancellable, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import domains.Domain
import domains.events.Events.{IzanamiEvent, WebhookCreated, WebhookDeleted, WebhookUpdated}
import domains.events.EventStore
import domains.webhook.Webhook.WebhookKey
import domains.webhook.notifications.WebHookActor._
import domains.webhook.{Webhook, WebhookContext, WebhookService}
import env.WebhookConfig
import libs.logs.IzanamiLogger
import play.api.libs.json._
import play.api.libs.ws.WSClient
import store.Query
import zio.{Runtime, ZIO}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object WebHooksActor {

  case object RefreshWebhook

  def props(wSClient: WSClient, config: WebhookConfig, runtime: Runtime[WebhookContext]): Props =
    Props(new WebHooksActor(wSClient, config, runtime))
}

class WebHooksActor(wSClient: WSClient, config: WebhookConfig, runtime: Runtime[WebhookContext]) extends Actor {

  import domains.webhook.notifications.WebHooksActor._
  import akka.actor.SupervisorStrategy._
  import context.dispatcher

  private implicit val mat: Materializer = ActorMaterializer()(context.system)

  private var scheduler: Option[Cancellable] = None

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy() {
      case WebhookBannedException(id) =>
        IzanamiLogger.error(s"Webhook with id $id is banned")
        Stop
      case _ => Restart
    }

  override def receive = {
    case RefreshWebhook =>
      setUpWebHooks()
    case WebhookCreated(_, hook, _, _, _) =>
      IzanamiLogger.debug("WebHook created event, creating webhook actor if missing")
      createWebhook(hook)
    case WebhookUpdated(_, _, hook, _, _, _) =>
      IzanamiLogger.debug("WebHook updated event, creating webhook actor if missing")
      createWebhook(hook)
      context.child(buildId(hook.clientId)).foreach(_ ! UpdateWebHook(hook))
    case WebhookDeleted(_, hook, _, _, _) =>
      IzanamiLogger.info(s"Deleting webhook ${hook.clientId.key}")
      context.child(buildId(hook.clientId)).foreach(_ ! PoisonPill)
    case Terminated(r) =>
      IzanamiLogger.info(s"Webhook stopped $r")
  }

  override def preStart(): Unit = {
    scheduler = Some(
      context.system.scheduler
        .schedule(5.minutes, 5.minutes, self, RefreshWebhook)
    )
    setUpWebHooks().foreach { _ =>
      runtime.unsafeRunToFuture(
        EventStore
          .events(domains = Seq(Domain.Webhook))
          .flatMap { s =>
            ZIO.fromFuture { _ =>
              val f = s.runWith(Sink.foreach { event =>
                self ! event
              })
              f.onComplete {
                case Success(_) => IzanamiLogger.debug(s"Stream finished")
                case Failure(e) => IzanamiLogger.error("Error consuming event stream", e)
              }
              f
            }
          }
      )
    }
  }

  private def setUpWebHooks(): Future[Done] =
    runtime
      .unsafeRunToFuture(
        WebhookService.findByQuery(Query.oneOf("*"))
      )
      .flatMap { s =>
        s.map(_._2)
          .filterNot(_.isBanned)
          .fold(Seq.empty[Webhook]) { _ :+ _ }
          .map { hooks =>
            //Create webhooks if missing
            hooks.foreach { createWebhook }
            //Remove others
            val keys = hooks.map(_.clientId).map(base64)
            context.children
              .filterNot(r => keys.contains(r.path.name.drop("webhook-".length)))
              .foreach(_ ! PoisonPill)
            Done
          }
          .runWith(Sink.head)
      }

  private def createWebhook(hook: Webhook): Unit = {
    val childName = buildId(hook.clientId)
    if (!hook.isBanned && context.child(childName).isEmpty) {
      IzanamiLogger.info(s"Starting new webhook $childName")
      val ref = context.actorOf(WebHookActor.props(wSClient, hook, config, runtime), childName)
      context.watch(ref)
    } else {
      IzanamiLogger.info(s"Webhook $hook not started")
    }
  }

  private def buildId(id: WebhookKey): String = {
    val key = base64(id)
    s"webhook-$key"
  }

  private def base64(key: WebhookKey) =
    Base64.getEncoder.encodeToString(key.key.getBytes)

  override def postStop(): Unit = {
    scheduler.foreach(_.cancel())
    context.children.foreach(_ ! PoisonPill)
  }
}

object WebHookActor {

  case object WebhookBanned
  case object SendEvents
  case object ResetErrors
  case class UpdateWebHook(webhook: Webhook)
  case class WebhookBannedException(id: WebhookKey) extends RuntimeException(s"Too much error on webhook ${id.key}")

  def props(wSClient: WSClient, webhook: Webhook, config: WebhookConfig, runtime: Runtime[WebhookContext]): Props =
    Props(new WebHookActor(wSClient, webhook, config, runtime))
}

class WebHookActor(wSClient: WSClient, webhook: Webhook, config: WebhookConfig, runtime: Runtime[WebhookContext])
    extends Actor {

  import cats.implicits._
  import context.dispatcher

  //Mutables vars
  private var scheduler: Option[Cancellable] = None
  private var reset: Option[Cancellable]     = None
  private var queue                          = Set.empty[IzanamiEvent]
  private var errorCount                     = 0

  override def receive = handleMessages(webhook)

  def handleMessages(webhook: Webhook): Receive = {
    val Webhook(id, callbackUrl, domains, patterns, types, JsObject(headers), _, _) = webhook

    def keepEvent(event: IzanamiEvent): Boolean = {
      def matchP = matchPattern(event) _
      (domains.isEmpty || domains.contains(event.domain)) &&
      (types.isEmpty || types.contains(event.`type`)) &&
      (patterns.isEmpty || patterns.foldLeft(true) { (acc, p) =>
        matchP(p) || acc
      })
    }

    val effectivesHeaders: Seq[(String, String)] = headers.flatMap {
      case (name, JsString(value)) => (name, value).some
      case _                       => none[(String, String)]
    }.toSeq ++ Seq("Accept" -> "application/json", "Content-Type" -> "application/json")

    {
      case UpdateWebHook(wh) =>
        context.become(handleMessages(wh), true)
      case event: IzanamiEvent if keepEvent(event) =>
        IzanamiLogger.debug(s"[Webhook] Queueing event ${event}")
        queue = queue + event
        if (queue.size >= config.events.group) {
          IzanamiLogger.debug(s"[Webhook] Sending events by group ${config.events.group}")
          sendEvents(id, callbackUrl, effectivesHeaders)
        }

      case ResetErrors =>
        errorCount = 0
        reset = Some(
          context.system.scheduler
            .scheduleOnce(config.events.errorReset, self, ResetErrors)
        )

      case SendEvents if queue.nonEmpty =>
        IzanamiLogger.debug(s"[Webhook] Sending events within ${config.events.within}")
        sendEvents(id, callbackUrl, effectivesHeaders)

      case WebhookBanned =>
        throw WebhookBannedException(id)
    }
  }

  private def sendEvents(id: WebhookKey, callbackUrl: String, effectivesHeaders: Seq[(String, String)]): Unit = {
    val json: JsValue =
      Json.obj("objectsEdited" -> JsArray(queue.map(_.toJson).toSeq))
    queue = Set.empty[IzanamiEvent]
    IzanamiLogger.debug(s"Sending events to ${webhook.callbackUrl} : $json")
    try {
      runtime.unsafeRunToFuture(WebhookService.getById(id)).onComplete {
        case Success(Some(w)) if !w.isBanned =>
          wSClient
            .url(webhook.callbackUrl)
            .withHttpHeaders(effectivesHeaders: _*)
            .withRequestTimeout(1.second)
            .post(json)
            .map { resp =>
              if (resp.status != 200) {
                IzanamiLogger.error(s"Error sending to webhook $callbackUrl : ${resp.body}")
                handleErrors(id)
              }
              Done
            }
            .recover {
              case e =>
                IzanamiLogger.error(s"Error sending to webhook $callbackUrl", e)
                handleErrors(id)
                Done
            }
        case Success(_) =>
          context.stop(self)
        case _ =>
      }

    } catch {
      case e: Throwable =>
        IzanamiLogger.error(s"Error sending to webhook $callbackUrl", e)
        handleErrors(id)
    }
  }

  private def handleErrors(id: WebhookKey): Unit = {
    errorCount += 1
    reset.foreach(_.cancel())
    reset = Some(
      context.system.scheduler
        .scheduleOnce(config.events.errorReset, self, ResetErrors)
    )
    IzanamiLogger.error(s"Increasing error to $errorCount/${config.events.nbMaxErrors} for $id")
    if (errorCount > config.events.nbMaxErrors) {
      IzanamiLogger.error(s"$id is banned, updating db")
      runtime
        .unsafeRunToFuture(
          WebhookService.update(id, id, webhook.copy(isBanned = true)).either
        )
        .onComplete { _ =>
          self ! WebhookBanned
        }
    }
  }

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[IzanamiEvent])
    scheduler = Some(
      context.system.scheduler
        .schedule(config.events.within, config.events.within, self, SendEvents)
    )
    reset = Some(
      context.system.scheduler
        .scheduleOnce(config.events.errorReset, self, ResetErrors)
    )
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
    scheduler.foreach(_.cancel())
  }

  private def matchPattern(e: IzanamiEvent)(pattern: String): Boolean = {
    val regex = s"^${pattern.replaceAll("\\*", ".*")}$$"
    e.key.key.matches(regex)
  }

}
