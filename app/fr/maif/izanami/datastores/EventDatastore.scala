package fr.maif.izanami.datastores

import akka.actor.Cancellable
import fr.maif.izanami.datastores.EventDatastore.{AscOrder, FeatureEventRequest, TenantEventRequest}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.{EventNotFound, FailedToReadEvent, IzanamiError}
import fr.maif.izanami.events.EventService.{FeatureEventType, IZANAMI_CHANNEL, eventFormat}
import fr.maif.izanami.events.IzanamiEvent
import fr.maif.izanami.utils.Datastore

import java.time.{Duration, Instant, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class EventDatastore(val env: Env) extends Datastore {
  var eventCleanerCancellation: Cancellable    = Cancellable.alreadyCancelled

  override def onStart(): Future[Unit] = {
    eventCleanerCancellation = env.actorSystem.scheduler.scheduleAtFixedRate(5.minutes, 5.minutes)(() =>{
      deleteExpiredEvents(env.configuration.get[Int]("app.audit.events-hours-ttl"))
    })
    Future.successful(())
  }

  override def onStop(): Future[Unit] = {
    eventCleanerCancellation.cancel()
    Future.successful(())
  }

  def deleteExpiredEvents(hours: Int): Future[Unit] = {
    env.datastores.tenants.readTenants()
      .flatMap(tenants => Future.sequence(tenants.map(t => deleteExpiredEventsForTenant(t.name, hours))))
      .map(_ => ())
  }


  def deleteExpiredEventsForTenant(tenant: String, hours: Int): Future[Unit] = {
    env.postgresql.queryRaw(
      s"""
         |DELETE FROM events WHERE EXTRACT(HOUR FROM NOW() - emitted_at) > $$1
         |""".stripMargin,
      params=List(java.lang.Integer.valueOf(hours)),
      schemas = Set(tenant)
    ){_ => Some(())}
  }



  def readEventFromDb(tenant: String, id: Long): Future[Either[IzanamiError, IzanamiEvent]] = {
    val global = tenant.equals(IZANAMI_CHANNEL)
    env.postgresql
      .queryOne(
        s"""
           |SELECT event FROM ${if (global) "izanami.global_events" else "events"} WHERE id=$$1
           |""".stripMargin,
        List(java.lang.Long.valueOf(id)),
        schemas = if (global) Set() else Set(tenant)
      ) { r => r.optJsObject("event") }
      .map(o => o.toRight(EventNotFound(tenant, id)))
      .map(e => e.flatMap(js => eventFormat.reads(js).asOpt.toRight(FailedToReadEvent(js.toString()))))

  }

  def listEventsForTenant(tenant: String, request: TenantEventRequest): Future[(Seq[IzanamiEvent], Option[Long])] = {
    def queryBody(startIndex: Int): (String, List[AnyRef]) = {
      var index = startIndex
      val query =
        s"""
           |FROM events e
           |WHERE 1 = 1
           |${if (request.users.nonEmpty) s"AND e.username = ANY($$${index += 1; index}::TEXT[])" else ""}
           |${request.begin.map(_ => s"AND e.emitted_at >= $$${index += 1; index}").getOrElse("")}
           |${request.end.map(_ => s"AND e.emitted_at <= $$${index += 1; index}").getOrElse("")}
           |${if (request.eventTypes.nonEmpty) s"AND e.event_type = ANY($$${index += 1; index}::izanami.LOCAL_EVENT_TYPES[])" else ""}
           |""".stripMargin

      (query, List(
        Option(request.users.toArray).filter(_.nonEmpty), request.begin.map(_.atOffset(ZoneOffset.UTC)), request.end.map(_.atOffset(ZoneOffset.UTC)), Option(request.eventTypes.map(_.name).toArray).filter(_.nonEmpty)
      ).collect { case Some(t: AnyRef) => t })
    }

    val maybeFutureCount = if (request.total) {
      val (body, params) = queryBody(0)
      env.postgresql.queryOne(
        s"""
           |SELECT COUNT(*) as total
           |${body}
           |""".stripMargin, params = params, schemas = Set(tenant)
      ) { r => r.optLong("total") }
    } else {
      Future.successful(None)
    }

    val futureResult = {
      val (body, ps) = queryBody(request.cursor.map(_ => 2).getOrElse(1))
      env.postgresql
        .queryAll(
          s"""
             |SELECT e.event
             |${body}
             |${request.cursor.map(_ => s"AND e.id ${if (request.sortOrder == AscOrder) ">" else "<"} $$2").getOrElse("")}
             |ORDER BY e.id ${request.sortOrder.toDb}
             |LIMIT $$1
             |""".stripMargin,
          params = List(Some(java.lang.Integer.valueOf(request.count)), request.cursor.map(java.lang.Long.valueOf)).collect { case Some(t) => t }.concat(ps),
          schemas = Set(tenant)
        ) { r => r.optJsObject("event") }
    }.map(jsons => {
      jsons
        .map(json => {
          val readResult = eventFormat.reads(json)
          readResult.fold(
            err => {
              logger.error(s"Failed to read event : ${err}")
              None
            },
            evt => Some(evt)
          )
        })
        .collect({ case Some(evt) => evt })
    })

    maybeFutureCount.flatMap(maybeCount => {
      futureResult.map(result => (result, maybeCount))
    })
  }

  def listEventsForProject(tenant: String, projectId: String, request: FeatureEventRequest): Future[(Seq[IzanamiEvent], Option[Long])] = {

    def queryBody(startIndex: Int): (String, List[AnyRef]) = {
      var index = startIndex
      val query =
        s"""
           |FROM events e, projects p
           |WHERE e.event->>'projectId'=p.id::TEXT
           |AND p.name=$$${index}
           |${if (request.users.nonEmpty) s"AND e.username = ANY($$${index += 1; index}::TEXT[])" else ""}
           |${request.begin.map(_ => s"AND e.emitted_at >= $$${index += 1; index}").getOrElse("")}
           |${request.end.map(_ => s"AND e.emitted_at <= $$${index += 1; index}").getOrElse("")}
           |${if (request.eventTypes.nonEmpty) s"AND e.event_type = ANY($$${index += 1; index}::izanami.LOCAL_EVENT_TYPES[])" else ""}
           |${if (request.features.nonEmpty) s"AND e.entity_id = ANY($$${index += 1; index}::TEXT[])" else ""}
           |""".stripMargin

      (query, List(projectId).concat(List(
        Option(request.users.toArray).filter(_.nonEmpty), request.begin.map(_.atOffset(ZoneOffset.UTC)), request.end.map(_.atOffset(ZoneOffset.UTC)), Option(request.eventTypes.map(_.name).toArray).filter(_.nonEmpty), Option(request.features.toArray).filter(_.nonEmpty)
      ).collect { case Some(t: AnyRef) => t }))
    }

    val maybeFutureCount = if (request.total) {
      val (body, params) = queryBody(1)
      env.postgresql.queryOne(
        s"""
           |SELECT COUNT(*) as total
           |${body}
           |""".stripMargin, params = params, schemas = Set(tenant)
      ) { r => r.optLong("total") }
    } else {
      Future.successful(None)
    }

    val futureResult = {
      val (body, ps) = queryBody(request.cursor.map(_ => 3).getOrElse(2))
      env.postgresql
        .queryAll(
          s"""
             |SELECT e.event
             |${body}
             |${request.cursor.map(_ => s"AND e.id ${if (request.sortOrder == AscOrder) ">" else "<"} $$2").getOrElse("")}
             |ORDER BY e.id ${request.sortOrder.toDb}
             |LIMIT $$1
             |""".stripMargin,
          params = List(Some(java.lang.Integer.valueOf(request.count)), request.cursor.map(java.lang.Long.valueOf)).collect { case Some(t) => t }.concat(ps),
          schemas = Set(tenant)
        ) { r => r.optJsObject("event") }
    }.map(jsons => {
      jsons
        .map(json => {
          val readResult = eventFormat.reads(json)
          readResult.fold(
            err => {
              logger.error(s"Failed to read event : ${err}")
              None
            },
            evt => Some(evt)
          )
        })
        .collect({ case Some(evt) => evt })
    })

    maybeFutureCount.flatMap(maybeCount => {
      futureResult.map(result => (result, maybeCount))
    })
  }


}


object EventDatastore {

  sealed trait SortOrder {
    def toDb: String
  }

  case object AscOrder extends SortOrder {
    override def toDb: String = "ASC"
  }

  case object DescOrder extends SortOrder {
    override def toDb: String = "DESC"
  }

  def parseSortOrder(order: String): Option[SortOrder] = {
    Option(order).map(_.toUpperCase).collect {
      case "ASC" => AscOrder
      case "DESC" => DescOrder
    }
  }

  case class FeatureEventRequest(
                                  sortOrder: SortOrder,
                                  cursor: Option[Long],
                                  count: Int,
                                  users: Set[String] = Set(),
                                  features: Set[String] = Set(),
                                  begin: Option[Instant] = None,
                                  end: Option[Instant] = None,
                                  eventTypes: Set[FeatureEventType] = Set(),
                                  total: Boolean
                                )

  case class TenantEventRequest(
                                  sortOrder: SortOrder,
                                  cursor: Option[Long],
                                  count: Int,
                                  users: Set[String] = Set(),
                                  begin: Option[Instant] = None,
                                  end: Option[Instant] = None,
                                  eventTypes: Set[FeatureEventType] = Set(),
                                  total: Boolean,
                                  features: Set[String] = Set(),
                                  projects: Set[String] = Set()
                                )
}