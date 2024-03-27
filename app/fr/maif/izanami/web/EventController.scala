package fr.maif.izanami.web

import akka.NotUsed
import akka.stream.{BoundedSourceQueue, KillSwitch, KillSwitches, Materializer, SharedKillSwitch, UniqueKillSwitch}
import akka.stream.scaladsl.{BroadcastHub, Concat, Flow, Keep, Merge, Sink, Source}
import fr.maif.izanami.datastores.{EventType, FeatureCreated, FeatureDeleted, FeatureUpdated}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{FailedToReadEvent, IzanamiError}
import fr.maif.izanami.models.{AbstractFeature, Feature, FeatureRequest, RequestContext}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.V1FeatureEvents.{createEvent, deleteEvent, keepAliveEvent, updateEvent}
import fr.maif.izanami.v1.V2FeatureEvents.{createEventV2, deleteEventV2, errorEvent, initialEvent, keepAliveEventV2, updateEventV2}
import io.vertx.pgclient.pubsub.PgSubscriber
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.EventSource.{EventDataExtractor, EventIdExtractor, EventNameExtractor}
import play.api.libs.json.{JsObject, JsValue, Json}
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
  val sources: scala.collection.mutable.Map[String, SourceDescriptor] = scala.collection.mutable.Map()

  val logger                                                       = env.logger
  // FIXME create dedicated object instead
  private implicit val nameExtractor: EventNameExtractor[JsObject] =
    EventNameExtractor[JsObject](_ => None) //Some(event.`type`))
  private implicit val idExtractor: EventIdExtractor[JsObject] = EventIdExtractor[JsObject](event => {
    (event \ "_id").asOpt[Long].map(_.toString)
  }) //Some(event.key.key))
  private implicit val dataExtractor: EventDataExtractor[JsObject] =
    EventDataExtractor[JsObject](event => Json.stringify(event))

  case class GenericEvent(
      eventType: EventType,
      project: String,
      maybeFeature: Option[Map[String, AbstractFeature]],
      id: String
  )
  case class SourceDescriptor(
      source: Source[GenericEvent, NotUsed],
      killswitch: SharedKillSwitch,
      pgSubscriber: PgSubscriber
  )

  private def eventSource(tenant: String): SourceDescriptor = {
    logger.info(s"Creating source for $tenant")
    val sharedKillSwitch     = KillSwitches.shared(s"$tenant-killswitch")
    lazy val (queue, source) = Source
      .queue[GenericEvent](bufferSize = 1024)
      .toMat(BroadcastHub.sink(bufferSize = 1024))(Keep.both)
      .run()

    lazy val subscriber = PgSubscriber.subscriber(env.postgresql.vertx, env.postgresql.connectOptions)
    subscriber
      .connect()
      .onComplete(ar => {
        if (ar.succeeded()) {
          subscriber
            .channel(s"${tenant}-features")
            .handler(payload => {
              val json            = Json.parse(payload);
              val maybeFuturEvent =
                for (
                  id        <- (json \ "id").asOpt[String];
                  project   <- (json \ "project").asOpt[String];
                  eventType <- (json \ "type").asOpt[String]
                )
                  yield {
                    val formalType: EventType = eventType match {
                      case eventType if eventType.equalsIgnoreCase(FeatureUpdated.toString) => FeatureUpdated
                      case eventType if eventType.equalsIgnoreCase(FeatureCreated.toString) => FeatureCreated
                      case eventType if eventType.equalsIgnoreCase(FeatureDeleted.toString) => FeatureDeleted
                    }

                    formalType match {
                      case f @ (FeatureUpdated | FeatureCreated) =>
                        env.datastores.features
                          .findActivationStrategiesForFeature(tenant, id)
                          .map(maybeFeature =>
                            GenericEvent(
                              eventType = formalType,
                              project = project,
                              maybeFeature = maybeFeature,
                              id = id
                            )
                          )
                      case f @ FeatureDeleted                    =>
                        Future.successful(
                          GenericEvent(eventType = formalType, project = project, maybeFeature = None, id = id)
                        )
                    }
                  }

              maybeFuturEvent.fold(logger.error(s"Failed to read event $payload"))(futureValue => {
                futureValue.map(value => queue.offer(value))
              })
            })
        } else {
          logger.error("Failed to connect postgres suscriber", ar.cause())
        }
      })

    SourceDescriptor(source via sharedKillSwitch.flow, sharedKillSwitch, subscriber)
  }

  def processForLegacyEndpoint(event: GenericEvent): JsObject = {
    if (event.eventType == FeatureDeleted) {
      deleteEvent(event.id)
    } else {
      event.maybeFeature.fold(deleteEvent(event.id))(f => {
        val legacyFormatFeature = Feature.writeFeatureInLegacyFormat(f.get("").get)
        if (event.eventType == FeatureCreated) {
          createEvent(event.id, legacyFormatFeature)
        } else {
          updateEvent(event.id, legacyFormatFeature)
        }
      })
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
      event: GenericEvent,
      context: RequestContext,
      conditions: Boolean,
      env: Env
  ): Future[Option[JsObject]] = {
    if (event.eventType == FeatureDeleted) {
      Future.successful(Some(deleteEventV2(event.id)))
    } else {
      event.maybeFeature.fold(Future.successful(Some(deleteEventV2(event.id)): Option[JsObject]))(f => {
        Feature.processMultipleStrategyResult(f, context, conditions, env).map {
          case Left(error) => {
            logger.error(s"Failed to write feature : ${error.message}")
            None
          }
          case Right(json) => {
            if (event.eventType == FeatureCreated) {
              Some(createEventV2(json) ++ Json.obj("id" -> event.id))
            } else {
              Some(updateEventV2(json) ++ Json.obj("id" -> event.id))
            }
          }
        }
      })
    }
  }

  def events(pattern: String): Action[AnyContent] = clientKeyAction.async { request =>
    val key = request.key

    val regexpPattern = pattern.replaceAll("\\*", ".*")
    val tenant        = key.tenant

    val source = sources.getOrElseUpdate(tenant, eventSource(tenant))

    val resultSource = source.source
      .filter { case GenericEvent(_, project, _, id) =>
        id.matches(regexpPattern) && (key.admin || key.projects.exists(ap => ap.name == project))
      }
      .map(e => processForLegacyEndpoint(e))

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

      val source = sources.getOrElseUpdate(tenant, eventSource(tenant))
      env.datastores.projects
        .readProjectsById(tenant, clientRequest.projects)
        .map(m => m.values.map(p => p.name).toSet)
        .map(allowedProjects => {
          val resultSource = source.source
            .filter { case GenericEvent(_, project, _, _) =>
              key.admin || key.projects.exists(ap => ap.name == project)
            }
            .filter { case GenericEvent(_, project, _, id) =>
              // TODO handle tags
              allowedProjects.contains(project) ||
                clientRequest.features.contains(id)
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

  def killSource(tenant: String) = {
    sources.get(tenant).map {
      case SourceDescriptor(_, killswitch, pgSuscriber) => {
        sources.remove(tenant)
        logger.info(s"Closing SSE source for $tenant")
        killswitch.shutdown()
        pgSuscriber
          .close()
          .onComplete(_ => {
            logger.info(s"Done closing PG suscriber for $tenant")
          })
      }
    }
  }

  def killAllSources(): Action[AnyContent] = adminAuthAction.async { request =>
    sources.keys.foreach(killSource)
    NoContent.future
  }

}
