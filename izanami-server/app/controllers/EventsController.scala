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

class EventsController(system: ActorSystem,
                       AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                       cc: ControllerComponents)(implicit r: Runtime[EventStoreContext])
    extends AbstractController(cc) {

  import libs.http._
  import domains.events.Events._

  private implicit val nameExtractor =
    EventNameExtractor[IzanamiEvent](_ => None) //Some(event.`type`))
  private implicit val idExtractor = EventIdExtractor[IzanamiEvent](event => Some(s"${event._id}")) //Some(event.key.key))
  private implicit val dataExtractor =
    EventDataExtractor[IzanamiEvent](event => Json.stringify(event.toJson))

  def allEvents(patterns: String, domains: String) =
    events(domains.split(","), patterns)

  def eventsForADomain(domain: String, patterns: String) =
    events(domain.split(","), patterns)

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
          Ok.chunked(
              source via EventSource.flow
            )
            .as("text/event-stream")
        }
    }

}
