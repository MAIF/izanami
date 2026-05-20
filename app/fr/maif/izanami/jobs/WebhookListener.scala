package fr.maif.izanami.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.jknack.handlebars.Context
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.jackson.JsonNodeValueResolver
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{WebhookCallError, TenantDoesNotExists}
import fr.maif.izanami.events.*
import fr.maif.izanami.models.LightWebhook
import fr.maif.izanami.models.RequestContext
import fr.maif.izanami.web.FeatureContextPath
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Cancellable
import play.api.libs.json.JsValue
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import play.api.Logger
import scala.collection.concurrent.TrieMap
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.Done
import fr.maif.izanami.utils.syntax.implicits.BetterFuture

class WebhookListener(env: Env, eventService: EventService) {
  private val handlebars = new Handlebars()
  private val mapper = new ObjectMapper()
  private val logger = Logger("izanami-webhooks")
  private implicit val ec: ExecutionContext = env.executionContext
  private implicit val actorSystem: ActorSystem = env.actorSystem
  private val tenantToListen: TrieMap[String, Unit] = TrieMap()
  private val datastore = env.datastores.webhook
  private val retryConfig = env.typedConfiguration.webhooks.retry
  private var handleFailHookCancel: Option[Cancellable] = Option.empty

  def onStop(): Future[Unit] = {
    handleFailHookCancel.foreach(cancel => cancel.cancel())
    Future.successful(())
  }

  def onStart(): Future[Unit] = {
    env.datastores.tenants
      .readTenants()
      .map(tenants => {
        tenants.map(tenant => startListening(tenant.name))
      })

    eventService
      .consume(EventService.IZANAMI_CHANNEL)
      .source
      .runForeach(handleGlobalEvent)

    handleFailHookCancel =
      Some(env.actorSystem.scheduler.scheduleAtFixedRate(
        0.minutes,
        retryConfig.checkInterval.seconds
      )(() =>
        handleFailedHooks()
      ))

    Future.successful(())
  }

  private def handleGlobalEvent(event: IzanamiEvent): Unit = {
    event match {
      case TenantCreated(eventId, tenant, _, _, _, _) => startListening(tenant)
      case TenantDeleted(_, tenant, _, _, _, _) => tenantToListen.remove(tenant)
      case _                                    => ()
    }
  }

  private def startListening(tenant: String): Unit = {
    logger.info(s"Initializing webhook event listener for tenant $tenant")
    tenantToListen.addOne(tenant -> ())
    env.eventService
      .consume(tenant)
      .source
      .runForeach(evt => {
        handleEvent(tenant, evt)
      })
  }

  private def handleFailedHooks(
  ): Unit = {
    tenantToListen.keySet.foldLeft(FutureEither.success(Done.done()))(
      (future, tenant) => {
        future.transformWith(_ => {
          datastore
            .findAbandoneddWebhooks(tenant)
            .fold(
              err => {
                err match {
                  case e: TenantDoesNotExists => {
                    logger.warn(
                      s"Tenant ${tenant} not fount while looking for abandonned webhook, ending taks for this tenant."
                    )
                    tenantToListen.remove(tenant)
                    Future.successful(Done.done())
                  }
                  case e => {
                    logger.error(s"Failed to find abandonned webhook : ${e}")
                    Future.successful(Done.done())
                  }
                }
              },
              s => {
                s.foldLeft(Future.successful(Done.done()))((f, next) => {
                  f.transformWith(_ => {
                    next match {
                      case (hook, f: FeatureEvent, count) => {
                        logger.info(
                          s"Restarting call for previously failed hook ${hook.name}"
                        )
                        handleWebhookCall(tenant, f, hook, count)
                      }
                      case _ => Future.successful(Done.done())
                    }
                  })
                })
                
              }
            ).flatten.mapToFEither
        })

      }
    )

  }

  private def buildHookPayload(
      tenant: String,
      event: FeatureEvent,
      hook: LightWebhook
  ): Future[Option[String]] = {
    createFeatureWebhookEvent(tenant, hook, event)
      .map(maybeJson =>
        maybeJson
          .map(json => {
            hook.bodyTemplate
              .map(bodyTemplate => {
                val template = handlebars.compileInline(bodyTemplate)
                val jacksonJson = mapper.readTree(json.toString())
                val context = Context
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
  ): Future[Done] = {
    buildHookPayload(tenant, event, hook)
      .flatMap {
        case None => {
          logger.error(
            s"Failed to build webhook body for hook ${hook.name}, no call will be performed"
          )
          Future.successful(Done.done())
        }
        case Some(body) =>
          callWebhook(hook, body)
            .transformWith(t => {
              t.fold(
                ex => {
                  handleHookCallFailure(
                    ex,
                    tenant,
                    hook,
                    event,
                    callCount + 1
                  )
                },
                _ => handleHookCallSuccess(tenant, hook, event)
              )
            })
      }
  }

  private def handleEventForHook(
      tenant: String,
      event: FeatureEvent,
      hook: LightWebhook
  ): Future[Done] = {
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
        Math.round(retryConfig.intialDelay * 1000 * (Math.pow(
          retryConfig.multiplier,
          count
        ))).milliseconds
      val duration = Math.min(
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
  ): Future[Done] = {
    logger.error(s""" Hook "${hook.name}" call failed""", ex)
    computeNextCallTime(failureCount).fold {
      logger.error(
        s"Exceeded max retry for hook ${hook.name} ($failureCount), stopping..."
      )
      Future.successful(Done.done())
    }(nextCallTime =>
      datastore.registerWebhookFailure(
        tenant,
        hook.id.get,
        event.eventId,
        failureCount,
        nextCallTime
      )
    )
  }

  private def handleHookCallSuccess(
      tenant: String,
      hook: LightWebhook,
      event: FeatureEvent
  ): Future[Done] = {
    datastore.deleteWebhookCall(tenant, hook.id.get, event.eventId)
  }

  private def handleEvent(tenant: String, event: IzanamiEvent): Future[Unit] = {
    (event match {
      case event: FeatureEvent => {
        datastore
          .findEnabledWebhooksForScope(
            tenant,
            featureIds = Set(event.id),
            projectNames = Set(event.project)
          )
          .flatMap(hooks => {
            Future.sequence(
              hooks.map(hook => handleEventForHook(tenant, event, hook))
            )
          })
      }
      case _ => Future.successful(Seq()) // TODO
    }).map(_ => ())
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

  private def callWebhook(webhook: LightWebhook, body: String): Future[Done] = {
    logger.info(s"Calling ${webhook.url.toString}")
    val hasContentType = webhook.headers.exists { case (name, _) =>
      name.equalsIgnoreCase("Content-Type")
    }
    val headers = if (hasContentType) {
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
            hookName =
              s"${webhook.name}${webhook.id.map(id => s" ($id)").getOrElse("")}"
          )
        } else {
          Done.done()
        }
      })
  }
}
