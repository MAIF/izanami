package fr.maif.izanami.jobs

import org.apache.pekko.actor.{ActorSystem, Cancellable}
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.jknack.handlebars.jackson.JsonNodeValueResolver
import com.github.jknack.handlebars.{Context, Handlebars}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.WebhookCallError
import fr.maif.izanami.events._
import fr.maif.izanami.models.{LightWebhook, RequestContext}
import fr.maif.izanami.web.FeatureContextPath
import play.api.libs.json.JsValue
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class WebhookListener(env: Env, eventService: EventService) {
  private val handlebars                                                      = new Handlebars()
  private val mapper                                                          = new ObjectMapper()
  private val logger                                                          = env.logger
  private implicit val ec: ExecutionContext                                   = env.executionContext
  private implicit val actorSystem: ActorSystem                               = env.actorSystem
  private val cancelSwitch: scala.collection.mutable.Map[String, Cancellable] = scala.collection.mutable.Map()
  private val datastore                                                       = env.datastores.webhook
  private val retryConfig                                                     = env.typedConfiguration.webhooks.retry

  def onStop(): Future[Unit] = {
    cancelSwitch.map(_._2.cancel())
    Future.successful(())
  }

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
      env.actorSystem.scheduler.scheduleAtFixedRate(0.minutes, retryConfig.checkInterval.seconds)(() =>
        handleFailedHooks(tenant, cancelRef)
      )

    cancelRef.set(cancellable)
    cancelSwitch.addOne(tenant -> cancellable)
    env.eventService
      .consume(tenant)
      .source
      .runForeach(evt => {
        handleEvent(tenant, evt)
      })
  }

  private def handleFailedHooks(tenant: String, cancelRef: AtomicReference[Cancellable]): Unit = {
    datastore
      .findAbandoneddWebhooks(tenant)
      .map {
        case Some(s) =>
          s.collect {
            case (hook, f: FeatureEvent, count) => {
              logger.info(s"Restarting call for previously failed hook ${hook.name}")
              handleWebhookCall(tenant, f, hook, count)
            }
          }
        case None    => cancelRef.get().cancel()
      }
  }

  private def buildHookPayload(tenant: String, event: FeatureEvent, hook: LightWebhook): Future[Option[String]] = {
    createFeatureWebhookEvent(tenant, hook, event)
      .map(maybeJson =>
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
      )
  }

  private def handleWebhookCall(
      tenant: String,
      event: FeatureEvent,
      hook: LightWebhook,
      callCount: Int = 0
  ): Future[Unit] = {
    buildHookPayload(tenant, event, hook)
      .flatMap {
        case None       => {
          logger.error(s"Failed to build webhook body for hook ${hook.name}, no call will be performed")
          Future.successful(())
        }
        case Some(body) =>
          callWebhook(hook, body)
            .transform(t => {
              t.fold(
                ex => {
                  Try(handleHookCallFailure(ex, tenant, hook, event, callCount + 1))
                },
                _ => Try(handleHookCallSuccess(tenant, hook, event))
              )
            })
      }
  }

  def handleEventForHook(tenant: String, event: FeatureEvent, hook: LightWebhook): Future[Unit] = {
    datastore
      .createWebhookCall(tenant, hook.id.get, event.eventId)
      .filter(wasCreated => wasCreated)
      .flatMap(_ => handleWebhookCall(tenant, event, hook))
  }

  private def computeNextCallTime(count: Int): Option[Instant] = {
    if (count > retryConfig.count) {
      None
    } else {
      val theoricalDurationBeforeNextCall =
        Math.round(retryConfig.intialDelay * 1000 * (Math.pow(retryConfig.multiplier, count))).milliseconds
      val duration                        = Math.min(
        theoricalDurationBeforeNextCall.toSeconds,
        retryConfig.maxDelay
      )
      Some(Instant.now().plusSeconds(duration))
    }
  }

  private def handleHookCallFailure(
      ex: Throwable,
      tenant: String,
      hook: LightWebhook,
      event: FeatureEvent,
      failureCount: Int
  ): Future[Unit] = {
    logger.error(s""" Hook "${hook.name}" call failed""", ex)
    computeNextCallTime(failureCount).fold {
      logger.error(s"Exceeded max retry for hook ${hook.name} ($failureCount), stopping...")
      Future.successful(())
    }(nextCallTime => datastore.registerWebhookFailure(tenant, hook.id.get, event.eventId, failureCount, nextCallTime))
  }

  private def handleHookCallSuccess(tenant: String, hook: LightWebhook, event: FeatureEvent): Future[Unit] = {
    datastore.deleteWebhookCall(tenant, hook.id.get, event.eventId)
  }

  def handleEvent(tenant: String, event: IzanamiEvent): Future[Unit] = {
    (event match {
      case event: FeatureEvent => {
        datastore
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

  /*private def futureWithRetry[T](
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
  }*/

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
