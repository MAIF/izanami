package fr.maif.izanami.jobs

import akka.actor.{ActorSystem, Cancellable}
import akka.pattern.Patterns.after
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.jknack.handlebars.{Context, Handlebars}
import com.github.jknack.handlebars.jackson.JsonNodeValueResolver
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{WebhookCallError, WebhookRetryCountExceeded}
import fr.maif.izanami.events._
import fr.maif.izanami.models.{LightWebhook, RequestContext}
import fr.maif.izanami.web.FeatureContextPath
import play.api.libs.json.JsValue

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class WebhookListener(env: Env, eventService: EventService) {
  private val handlebars                             = new Handlebars()
  private val mapper                                 = new ObjectMapper()
  private val logger                                 = env.logger
  private implicit val ec: ExecutionContext          = env.executionContext
  private implicit val actorSystem: ActorSystem      = env.actorSystem
  private val cancelSwitch: Map[String, Cancellable] = Map()
  private val retryCount: Long                       = env.typedConfiguration.webhooks.retry.count
  private val retryInitialDelay                      = env.typedConfiguration.webhooks.retry.intialDelay
  private val retryMaxDelay                          = env.typedConfiguration.webhooks.retry.maxDelay
  private val retryMultiplier                        = env.typedConfiguration.webhooks.retry.multiplier

  def onStart(): Future[Unit] = {
    env.datastores.tenants
      .readTenants()
      .map(tenants => {
        tenants.map(tenant => startListening(tenant.name))
      })

    env.eventService
      .consume(EventService.IZANAMI_CHANNEL)
      .source
      .runForeach(handleGlobalEvent)

    Future.successful(())
  }

  def handleGlobalEvent(event: IzanamiEvent): Unit = {
    event match {
      case TenantCreated(eventId, tenant, _, _, _, _) => startListening(tenant)
      case TenantDeleted(_, tenant, _, _, _, _)       => cancelSwitch.get(tenant).map(c => c.cancel())
      case _                                          => ()
    }
  }

  def startListening(tenant: String): Unit = {
    logger.info(s"Initializing webhook event listener for tenant $tenant")
    val cancelRef   = new AtomicReference[Cancellable]()
    val cancellable =
      env.actorSystem.scheduler.scheduleAtFixedRate(0.minutes, env.houseKeepingIntervalInSeconds.seconds)(() => {
        env.datastores.webhook
          .findAbandoneddWebhooks(tenant)
          .map {
            case Some(s) =>
              s.filter { case (_, event) =>
                event.isInstanceOf[FeatureEvent]
              }.foreach {
                case (webhook, event) => {
                  logger.info(s"Restarting call for abandonned hook ${webhook.name}")
                  handleEventForHook(tenant, event.asInstanceOf[FeatureEvent], webhook)
                }
              }
            case None    => cancelRef.get().cancel()
          }
      })

    cancelRef.set(cancellable)
    env.eventService
      .consume(tenant)
      .source
      .runForeach(evt => {
        handleEvent(tenant, evt)
      })
  }

  def handleEventForHook(tenant: String, event: FeatureEvent, hook: LightWebhook): Future[Unit] = {
    env.datastores.webhook
      .createWebhookCall(tenant, hook.id.get, event.eventId)
      .flatMap(created => {
        if (created) {
          createFeatureWebhookEvent(tenant, hook, event)
            .flatMap(maybeJson =>
              maybeJson
                .map(json => {
                  hook.bodyTemplate
                    .map(bodyTemplate => {
                      val template    = handlebars.compileInline(bodyTemplate)
                      val jacksonJson = mapper.readTree(json.toString())
                      val context     = Context
                        .newBuilder(jacksonJson)
                        .resolver(JsonNodeValueResolver.INSTANCE)
                        .build()
                      template.apply(context)
                    })
                    .getOrElse(json.toString())
                })
                .map(json =>
                  futureWithRetry(
                    () => callWebhook(hook, json),
                    () =>
                      env.datastores.webhook.updateWebhookCallDate(
                        tenant = tenant,
                        webhook = hook.id.get,
                        eventId = event.eventId
                      )
                  )
                )
                .getOrElse(Future.successful(()))
            )
            .flatMap(_ => env.datastores.webhook.deleteWebhookCall(tenant, hook.id.get, event.eventId))
        } else {
          Future.successful(())
        }
      })
  }

  def handleEvent(tenant: String, event: IzanamiEvent): Future[Unit] = {
    (event match {
      case event: FeatureEvent => {
        env.datastores.webhook
          .findWebhooksForScope(tenant, featureIds = Set(event.id), projectNames = Set(event.project))
          .flatMap(hooks => {
            Future.sequence(
              hooks.map(hook => handleEventForHook(tenant, event, hook))
            )
          })
      }
      case _                   => Future.successful(Seq()) // TODO
    }).map(_ => ())
  }

  private def futureWithRetry[T](
      expression: () => Future[T],
      onFailure: () => Future[Any],
      cur: Int = 0
  )(implicit as: ActorSystem): Future[T] = {
    expression().recoverWith { case e =>
      onFailure().flatMap(_ => {
        logger.error(s"Call failed", e)
        var next: FiniteDuration = Math.round(retryInitialDelay * 1000 * (Math.pow(retryMultiplier, cur))).milliseconds
        if (next > retryMaxDelay.seconds) {
          next = retryMaxDelay.seconds
        }

        if (cur >= retryCount) {
          logger.error(s"Exceeded max retry ($retryCount), stopping...")
          Future.failed(WebhookRetryCountExceeded())
        } else {
          logger.error(s"Will retry after ${next.toSeconds} seconds (${cur + 1} / $retryCount)")
          after(next, as.scheduler, global, () => Future.successful(1)).flatMap { _ =>
            futureWithRetry(expression, onFailure, cur + 1)(actorSystem)
          }
        }
      })
    }
  }

  private def createFeatureWebhookEvent(
      tenant: String,
      webhook: LightWebhook,
      event: FeatureEvent
  ): Future[Option[JsValue]] = {
    EventService.internalToExternalEvent(
      event = event,
      context = RequestContext(
        tenant = tenant,
        user = webhook.user,
        context = FeatureContextPath(webhook.context.split("/").toSeq)
      ),
      conditions = true, // TODO make this parametric
      env = env
    )
  }

  private def callWebhook(webhook: LightWebhook, body: String): Future[Unit] = {
    logger.info(s"Calling ${webhook.url.toString}")
    val hasContentType = webhook.headers.exists { case (name, _) =>
      name.equalsIgnoreCase("Content-Type")
    }
    val headers        = if (hasContentType) {
      webhook.headers
    } else {
      webhook.headers + ("Content-Type" -> "application/json")
    }
    env.Ws
      .url(webhook.url.toString)
      .withHttpHeaders(headers.toList: _*)
      .post(body)
      .map(response => {
        logger.debug(s"Status code is $response.status")
        if (response.status >= 400) {
          throw WebhookCallError(
            callStatus = response.status,
            body = Option(response.body),
            hookName = s"${webhook.name}${webhook.id.map(id => s" ($id)").getOrElse("")}"
          )
        }
      })
  }
}
