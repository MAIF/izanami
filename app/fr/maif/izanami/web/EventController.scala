package fr.maif.izanami.web

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Merge, Source}
import akka.stream.{Materializer}
import fr.maif.izanami.env.Env
import fr.maif.izanami.events._
import fr.maif.izanami.models.{Feature, FeatureRequest, RequestContext}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.V1FeatureEvents.{createEvent, deleteEvent, keepAliveEvent, updateEvent}
import fr.maif.izanami.v1.V2FeatureEvents._
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.EventSource.{EventDataExtractor, EventIdExtractor, EventNameExtractor}
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class EventController(
    val controllerComponents: ControllerComponents,
    val clientKeyAction: ClientApiKeyAction,
    val adminAuthAction: AdminAuthAction
)(implicit
    val env: Env
) extends BaseController {
  implicit val ec: ExecutionContext                                   = env.executionContext;
  implicit val materializer: Materializer                             = env.materializer
  val eventService = env.eventService

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
      case FeatureCreated(_, _, _, Some(strategiesByContext)) =>
        createEvent(event.id, Feature.writeFeatureInLegacyFormat(strategiesByContext.get("").get))
      case FeatureUpdated(_, _, _, Some(strategiesByContext)) =>
        updateEvent(event.id, Feature.writeFeatureInLegacyFormat(strategiesByContext.get("").get))
      case _                                               => deleteEvent(event.id)
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

  def processForModernEndpoint(
      event: IzanamiEvent,
      context: RequestContext,
      conditions: Boolean,
      env: Env
  ): Future[Option[JsObject]] = {
    event match {
      case FeatureDeleted(id, _, _)                                    => Future.successful(Some(deleteEventV2(id)))
      case f: ConditionFeatureEvent if f.conditionByContext.isEmpty =>
        Future.successful(Some(deleteEventV2(f.asInstanceOf[FeatureEvent].id)))
      case f: ConditionFeatureEvent                                 => {
        val maybeContextmap = f match {
          case FeatureCreated(_, _, _, map) => map
          case FeatureUpdated(_, _, _, map) => map
        }
        Feature.processMultipleStrategyResult(maybeContextmap.get, context, conditions, env).map {
          case Left(error) => {
            logger.error(s"Failed to write feature : ${error.message}")
            None
          }
          case Right(json) => {
            f match {
              case FeatureCreated(id, _, _, _) => Some(createEventV2(json) ++ Json.obj("id" -> id))
              case FeatureUpdated(id, _, _, _) => Some(updateEventV2(json) ++ Json.obj("id" -> id))
            }
          }
        }
      }
      case _                                                        => Future.successful(None)
    }
  }

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
              processForModernEndpoint(
                e,
                RequestContext(tenant, user, FeatureContextPath(elements = clientRequest.context)),
                conditions,
                env
              )
            )
            .filter(_.isDefined)
            .map(_.get)

          val refreshProvider = () =>
            FeatureController
              .queryFeatures(
                user,
                conditions,
                None,
                clientRequest,
                request.key.clientId,
                request.key.clientSecret,
                maybeBody,
                env
              )
              .map(either => either.fold(err => errorEvent(err.message), json => initialEvent(json)))
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
                  logger.info("Event source closed")
                }
              }
            )
          ).as(ContentTypes.EVENT_STREAM)
        })
    }

  def killAllSources(): Action[AnyContent] = adminAuthAction.async { request =>
    env.eventService.killAllSources()
    NoContent.future
  }

}
