package fr.maif.izanami.web

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Merge, Source}
import akka.stream.Materializer
import fr.maif.izanami.env.Env
import fr.maif.izanami.events.EventService.internalToExternalEvent
import fr.maif.izanami.events._
import fr.maif.izanami.models.features.{ActivationCondition, ResultType}
import fr.maif.izanami.models.{
  CompleteFeature,
  CompleteWasmFeature,
  EvaluatedCompleteFeature,
  Feature,
  FeatureRequest,
  RequestContext,
  SingleConditionFeature
}
import fr.maif.izanami.services.FeatureService
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.V1FeatureEvents.{createEvent, deleteEvent, keepAliveEvent, updateEvent}
import fr.maif.izanami.v1.V2FeatureEvents._
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.EventSource.{EventDataExtractor, EventIdExtractor, EventNameExtractor}
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsObject, JsValue, Json, Writes}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class EventController(
    val controllerComponents: ControllerComponents,
    val clientKeyAction: ClientApiKeyAction,
    val adminAuthAction: AdminAuthAction,
    featureService: FeatureService
)(implicit
    val env: Env
) extends BaseController {
  implicit val ec: ExecutionContext       = env.executionContext;
  implicit val materializer: Materializer = env.materializer
  val eventService: EventService          = env.eventService

  val logger                                                       = env.logger
  // FIXME create dedicated object instead
  private implicit val nameExtractor: EventNameExtractor[JsObject] =
    EventNameExtractor[JsObject](_ => None) //Some(event.`type`))
  private implicit val idExtractor: EventIdExtractor[JsObject] = EventIdExtractor[JsObject](event => {
    (event \ "_id").asOpt[Long].map(_.toString)
  }) //Some(event.key.key))
  private implicit val dataExtractor: EventDataExtractor[JsObject] =
    EventDataExtractor[JsObject](event => Json.stringify(event))

  def processForLegacyEndpoint(event: FeatureEvent): JsObject = {
    event match {
      case FeatureCreated(_, _, _, _, _, Some(strategiesByContext), _, _, _)    =>
        createEvent(event.id, Feature.writeFeatureInLegacyFormat(strategiesByContext.get("").get))
      case FeatureUpdated(_, _, _, _, _, Some(strategiesByContext), _, _, _, _) =>
        updateEvent(event.id, Feature.writeFeatureInLegacyFormat(strategiesByContext.get("").get))
      case _                                                                    => deleteEvent(event.id)
    }
  }

  def keepAlive(interval: FiniteDuration): Flow[JsObject, JsObject, NotUsed] =
    Flow[JsObject]
      .keepAlive(
        interval,
        keepAliveEvent
      )

  def keepAliveV2(interval: FiniteDuration): Flow[JsObject, JsObject, NotUsed] =
    Flow[JsObject]
      .keepAlive(
        interval,
        keepAliveEventV2
      )

  def events(pattern: String): Action[AnyContent] = clientKeyAction.async { request =>
    val key = request.key

    val regexpPattern = pattern.replaceAll("\\*", ".*")
    val tenant        = key.tenant

    val source = eventService.consume(tenant)

    val resultSource = source.source
      .filter {
        case f: FeatureEvent =>
          f.id.matches(regexpPattern) && (key.admin || key.projects.exists(ap => ap.name == f.project))
        case _               => false
      }
      .map(e => processForLegacyEndpoint(e.asInstanceOf[FeatureEvent]))

    val s = resultSource via keepAlive(25.seconds) via EventSource.flow
    Future.successful(
      Ok.chunked(
        s.watchTermination()((_, future) =>
          future.onComplete {
            case Failure(exception) => logger.error("Event source failed", exception)
            case Success(_)         => {
              logger.debug("Event source closed")
            }
          }
        )
      ).as(ContentTypes.EVENT_STREAM)
    )
  }

  def newEvents(
      user: String,
      conditions: Boolean,
      refreshInterval: Int,
      keepAliveInterval: Int,
      clientRequest: FeatureRequest
  ): Action[AnyContent] =
    clientKeyAction.async { request =>
      implicit val nameExtractor: EventNameExtractor[JsObject] =
        EventNameExtractor[JsObject](event => Some((event \ "type").as[String]))
      val key                                                  = request.key
      val tenant                                               = key.tenant
      val maybeBody                                            = request.body.asJson.flatMap(jsValue => jsValue.asOpt[JsObject])

      val source = eventService.consume(tenant)
      env.datastores.projects
        .readProjectsById(tenant, clientRequest.projects)
        .map(m => m.values.map(p => p.name).toSet)
        .map(allowedProjects => {
          val resultSource = source.source
            .filter {
              case event: FeatureEvent => {
                key.admin || key.projects.exists(ap => ap.name == event.project)
              }
              case _                   => false
            }
            .filter {
              case event: FeatureEvent => {
                // TODO handle tag
                allowedProjects.contains(event.project) ||
                clientRequest.features.contains(event.id)
              }
              case _                   => false
            }
            .mapAsync(1)(e =>
              internalToExternalEvent(
                e,
                RequestContext(tenant, user, FeatureContextPath(elements = clientRequest.context)),
                conditions,
                env
              )
            )
            .filter(_.isDefined)
            .map(_.get)

          val refreshProvider = () => evaluateFeatures(tenant, user, conditions, clientRequest, request, maybeBody)
          val refreshSource   = if (refreshInterval > 0) {
            Source
              .tick(refreshInterval.seconds, refreshInterval.seconds, 1)
              .mapAsync(1)(_ => refreshProvider())
          } else {
            Source.empty
          }

          val s =
            Source
              .future(refreshProvider())
              .concat(
                Source.combine(resultSource, refreshSource)(
                  Merge(_)
                )
              ) via keepAliveV2(keepAliveInterval.seconds) via EventSource.flow via source.killswitch.flow
          Ok.chunked(
            s.watchTermination()((_, future) =>
              future.onComplete {
                case Failure(exception) => logger.error("Event source failed", exception)
                case Success(foo)       => {
                  logger.debug("Event source closed")
                }
              }
            )
          ).as(ContentTypes.EVENT_STREAM)
        })
    }

  def killAllSources(): Action[AnyContent] = adminAuthAction.async { request =>
    Future.sequence(
      Seq(env.webhookListener.onStop(),
      env.eventService.killAllSources(excludeIzanamiChannel=true))
    ).map(_ => NoContent)

  }

  private def evaluateFeatures(
      tenant: String,
      user: String,
      conditions: Boolean,
      featureRequest: FeatureRequest,
      request: ClientKeyRequest[AnyContent],
      maybeBody: Option[JsObject]
  ): Future[JsObject] = {
    val requestContext = RequestContext(
      tenant = tenant,
      user = user,
      now = Instant.now(),
      context = FeatureContextPath(featureRequest.context),
      data = maybeBody.getOrElse(Json.obj())
    )
    featureService
      .evaluateFeatures(conditions, requestContext, featureRequest, request.key.clientId, request.key.clientSecret)
      .map(either =>
        either
          .fold(err => errorEvent(err.message), features => initialEvent(formatFeatureResponse(features, conditions)))
      )
  }

  // FIXME dedupe serde code with FeatureController
  private def formatFeatureResponse(
      evaluatedCompleteFeatures: Seq[EvaluatedCompleteFeature],
      conditions: Boolean
  ): JsValue = {
    val fields = evaluatedCompleteFeatures
      .map(evaluated => {
        val active: JsValueWrapper = evaluated.result
        var baseJson               = Json.obj(
          "name"    -> evaluated.baseFeature.name,
          "active"  -> active,
          "project" -> evaluated.baseFeature.project
        )

        if (conditions) {
          val jsonStrategies = Json
            .toJson(evaluated.featureStrategies.strategies.map {
              case (ctx, feature) => {
                (
                  ctx.replace("_", "/"),
                  writeConditions(feature)
                )
              }
            })
            .as[JsObject]
          baseJson = baseJson + ("conditions" -> jsonStrategies)
        }
        (evaluated.baseFeature.id, baseJson)
      })
      .toMap
    Json.toJson(fields)
  }

  // FIXME dedupe serde code with FeatureController
  private def writeConditions(f: CompleteFeature): JsObject = {
    val resultType: JsValueWrapper = Json.toJson(f.resultType)(ResultType.resultTypeWrites)
    val baseJson                   = Json.obj(
      "enabled"    -> f.enabled,
      "resultType" -> resultType
    )
    f match {
      case w: CompleteWasmFeature => baseJson + ("wasmConfig" -> Json.obj("name" -> w.wasmConfig.name))
      case f                      => {
        val conditions = f match {
          case s: SingleConditionFeature => s.toModernFeature.resultDescriptor.conditions
          case f: Feature                => f.resultDescriptor.conditions
        }
        baseJson + ("conditions" -> Json.toJson(conditions)(Writes.seq(ActivationCondition.activationConditionWrite)))
      }
    }
  }
}
