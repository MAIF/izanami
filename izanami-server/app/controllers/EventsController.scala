package controllers

import akka.actor.ActorSystem
import controllers.actions.SecuredAuthContext
import domains.Domain.Domain
import domains.events.{EventStore, EventStoreContext}
import play.api.libs.EventSource
import play.api.libs.EventSource.{EventDataExtractor, EventIdExtractor, EventNameExtractor}
import play.api.libs.json.{JsString, Json}
import play.api.mvc.{AbstractController, ActionBuilder, AnyContent, ControllerComponents}
import zio.Runtime
import akka.stream.scaladsl.Flow
import scala.util.Success
import scala.util.Failure
import libs.logs.IzanamiLogger
import java.time.LocalDateTime
import play.api.libs.json.JsValue
import scala.concurrent.duration.DurationDouble
import domains.AuthInfo
import domains.Key

class EventsController(system: ActorSystem,
                       AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                       cc: ControllerComponents)(implicit r: Runtime[EventStoreContext])
    extends AbstractController(cc) {

  import libs.http._
  import domains.events.Events._
  import system.dispatcher

  private implicit val nameExtractor =
    EventNameExtractor[IzanamiEvent](_ => None) //Some(event.`type`))
  private implicit val idExtractor = EventIdExtractor[IzanamiEvent](event => Some(s"${event._id}")) //Some(event.key.key))
  private implicit val dataExtractor =
    EventDataExtractor[IzanamiEvent](event => Json.stringify(event.toJson))

  def allEvents(patterns: String, domains: String) =
    events(domains.split(",").toIndexedSeq, patterns)

  def eventsForADomain(domain: String, patterns: String) =
    events(domain.split(",").toIndexedSeq, patterns)

  val logEvent = Flow[IzanamiEvent].map { event =>
    event
  }

  case class KeepAliveEvent() extends IzanamiEvent {
    val _id: Long                  = 0
    val domain: Domain             = domains.Domain.Unknown
    val authInfo: Option[AuthInfo] = None
    val key: Key                   = Key("na")
    def timestamp: LocalDateTime   = LocalDateTime.now()
    val `type`: String             = "KEEP_ALIVE"
    val payload: JsValue           = Json.obj()
  }

  val keepAlive = Flow[IzanamiEvent].keepAlive(30.seconds, () => KeepAliveEvent())

  // TODO abilitations
  private def events[T <: IzanamiEvent](domains: Seq[String], patterns: String) =
    AuthAction.asyncTask[EventStoreContext] { ctx =>
      val allPatterns: Seq[String] = ctx.authorizedPatterns ++ patterns
        .split(",")
        .toList

      val lastEventId = ctx.request.headers.get("Last-Event-ID").map(_.toLong)
      val allDomains  = domains.map(JsString).flatMap(_.validate[Domain].asOpt)

      EventStore
        .events(allDomains, allPatterns, lastEventId)
        .map { source =>
          val eventSource = (source via keepAlive via logEvent via EventSource.flow).watchTermination() { (_, fDone) =>
            fDone.onComplete {
              case Success(_) =>
                IzanamiLogger.debug("SSE disconnected")
              case Failure(e) =>
                IzanamiLogger.error("Error during SSE ", e)
            }
            fDone
          }
          Ok.chunked(eventSource).as("text/event-stream")
        }
    }

}
