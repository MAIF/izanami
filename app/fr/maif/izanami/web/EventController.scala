package fr.maif.izanami.web

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source}
import fr.maif.izanami.datastores.{FeatureCreated, FeatureDeleted, FeatureUpdated}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.FailedToReadEvent
import fr.maif.izanami.models.Feature
import fr.maif.izanami.v1.V1FeatureEvents.{createEvent, deleteEvent, keepAliveEvent, updateEvent}
import io.vertx.pgclient.pubsub.PgSubscriber
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.EventSource.{EventDataExtractor, EventIdExtractor, EventNameExtractor}
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class EventController(val controllerComponents: ControllerComponents, val clientKeyAction: ClientApiKeyAction)(implicit
    val env: Env
) extends BaseController {
  implicit val ec: ExecutionContext       = env.executionContext;
  implicit val materializer: Materializer = env.materializer
  val sources:scala.collection.mutable.Map[String, Source[InternalEvent, NotUsed]] = scala.collection.mutable.Map()

  val logger                              = env.logger
  // FIXME create dedicated object instead
  private implicit val nameExtractor: EventNameExtractor[JsObject] =
    EventNameExtractor[JsObject](_ => None) //Some(event.`type`))
  private implicit val idExtractor: EventIdExtractor[JsObject] = EventIdExtractor[JsObject](event => {
    (event \ "_id").asOpt[Long].map(_.toString)
  }) //Some(event.key.key))
  private implicit val dataExtractor: EventDataExtractor[JsObject] =
    EventDataExtractor[JsObject](event => Json.stringify(event))

  case class InternalEvent(id: String, project: String, payload: JsObject)

  private def eventSource(tenant: String): Source[InternalEvent, NotUsed] = {
    lazy val (queue, source) = Source
      .queue[InternalEvent](bufferSize = 1024)
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
              val json = Json.parse(payload);

              val maybeFuturEvent =
                for (
                  id <- (json \ "id").asOpt[String];
                  project <- (json \ "project").asOpt[String];
                  eventType <- (json \ "type").asOpt[String]
                )
                yield {
                  val futureEitherEvent = if (eventType.equalsIgnoreCase(FeatureUpdated.toString) || eventType.equalsIgnoreCase(FeatureCreated.toString)) {
                    env.datastores.features.findById(tenant, id).map {
                      case Left(value) => Left(value)
                      case Right(Some(feature)) => Feature.writeFeatureInLegacyFormat(feature) match {
                        case Some(json) if eventType.equalsIgnoreCase(FeatureUpdated.toString) => Right(Some(updateEvent(id, json)))
                        case Some(json) if eventType.equalsIgnoreCase(FeatureCreated.toString) => Right(Some(createEvent(id, json)))
                        case _ => Right(None)
                      }
                      case Right(None) => Right(Some(deleteEvent(id))) // Feature has been deleted since event was emitted
                    }
                  } else if (eventType.equalsIgnoreCase(FeatureDeleted.toString)) {
                    Future.successful(Right(Some(deleteEvent(id))))
                  } else {
                    Future
                      .successful(Left(FailedToReadEvent(payload)))
                  }

                  futureEitherEvent.map {
                    case Right(Some(json)) => Right(Some(InternalEvent(id=id, project=project, payload=json)))
                    case Right(None) => Right(None)
                    case Left(err) => Left(err)
                  }
                }
              maybeFuturEvent.getOrElse(Future.successful(Left(FailedToReadEvent(payload)))).map {
                case Left(error) => env.logger.error(s"Failed to process event : ${error.message}")
                case Right(value) => value.foreach(value => queue.offer((value)))
              }
            })
        } else {
          logger.error("Failed to connect postgres suscriber", ar.cause())
        }
      })
    // TODO close subscriber when source terminate
    //subscriber.close().scala.foreach(v => logger.debug("Postgres suscriber closed"))
    source
  }

  val keepAlive: Flow[JsObject, JsObject, NotUsed] = Flow[JsObject].keepAlive(
    30.seconds,
    () => {
      keepAliveEvent()
    }
  )

  def events(pattern: String): Action[AnyContent] = clientKeyAction.async { request =>
    val key = request.key

    val regexpPattern = pattern.replaceAll("\\*", ".*")
    val tenant          = key.tenant

    val source = sources.getOrElseUpdate(tenant,  eventSource(tenant))

    val resultSource = source.filter{case InternalEvent(id, project, _) =>
      id.matches(regexpPattern) && (key.admin || key.projects.exists(ap => ap.name == project))
    }.map(_.payload)

    val s               = resultSource via keepAlive via EventSource.flow
    Future.successful(
      Ok.chunked(
        s.watchTermination()((_, future) =>
          future.onComplete {
            case Failure(exception) => logger.error("Event source failed", exception)
            case Success(_)         => {
              logger.debug("Event source closed, closing postgres subscriber")
              // TODO how to close parent source ?
            }
          }
        )
      ).as(ContentTypes.EVENT_STREAM)
    )
  }

}
