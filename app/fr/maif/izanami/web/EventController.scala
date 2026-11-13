package fr.maif.izanami.web

import fr.maif.izanami.datastores.EventDatastore.{
  AscOrder,
  TenantEventRequest,
  parseSortOrder
}
import fr.maif.izanami.datastores.{
  EventDatastore,
  PersonnalAccessTokenDatastore,
  ProjectsDatastore
}
import fr.maif.izanami.errors.BadBodyFormat
import fr.maif.izanami.events.*
import fr.maif.izanami.jobs.WebhookListener
import fr.maif.izanami.models.*
import fr.maif.izanami.services.FeatureService
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.v1.V1FeatureEvents.{
  createEvent,
  deleteEvent,
  keepAliveEvent,
  updateEvent
}
import fr.maif.izanami.v1.V2FeatureEvents.*
import fr.maif.izanami.web.ProjectController.parseStringSet
import fr.maif.izanami.web.formats.FeatureFormats
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Merge, Source}
import play.api.Logger
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.EventSource.{
  EventDataExtractor,
  EventIdExtractor,
  EventNameExtractor
}
import play.api.libs.json.*
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

class EventController(
    val controllerComponents: ControllerComponents,
    clientKeyAction: => ClientApiKeyAction,
    adminAuthAction: => AdminAuthAction,
    tenantAuthAction: => TenantAuthActionFactory,
    featureService: => FeatureService,
    webhookListener: => WebhookListener,
    eventService: => EventService,
    eventDatastore: => EventDatastore, // FIXME use service instead
    projectsDatastore: => ProjectsDatastore,
    personalAccessTokenDatastore: => PersonnalAccessTokenDatastore
)(implicit val ec: ExecutionContext, val materializer: Materializer)
    extends BaseController {
  val logger = Logger("EventController")
  // FIXME create dedicated object instead
  private implicit val nameExtractor: EventNameExtractor[JsObject] =
    EventNameExtractor[JsObject](_ => None) // Some(event.`type`))
  private implicit val idExtractor: EventIdExtractor[JsObject] =
    EventIdExtractor[JsObject](event => {
      (event \ "_id").asOpt[Long].map(_.toString)
    }) // Some(event.key.key))
  private implicit val dataExtractor: EventDataExtractor[JsObject] =
    EventDataExtractor[JsObject](event => Json.stringify(event))

  def events(pattern: String): Action[AnyContent] = clientKeyAction.async {
    request =>
      val key = request.key

      val regexpPattern = pattern.replaceAll("\\*", ".*")
      val tenant = key.tenant

      val source = eventService.consume(tenant)

      val resultSource = source.source
        .filter {
          case f: FeatureEvent =>
            f.id.matches(regexpPattern) && (key.admin || key.projects.exists(
              ap => ap.name == f.project
            ))
          case _ => false
        }
        .map(e => processForLegacyEndpoint(e.asInstanceOf[FeatureEvent]))

      val s = resultSource via keepAlive(25.seconds) via EventSource.flow
      Future.successful(
        Ok.chunked(
          s.watchTermination()((_, future) =>
            future.onComplete {
              case Failure(exception) =>
                logger.error("Event source failed", exception)
              case Success(_) => {
                logger.debug("Event source closed")
              }
            }
          )
        ).as(ContentTypes.EVENT_STREAM)
      )
  }

  def readEventsForTenant(
      tenant: String,
      order: Option[String],
      users: Option[String],
      types: Option[String],
      start: Option[String],
      end: Option[String],
      cursor: Option[Long],
      count: Int,
      total: Option[Boolean],
      features: Option[String],
      projects: Option[String],
      unknownIds: Option[String]
  ): Action[AnyContent] = tenantAuthAction(tenant, RightLevel.Read).async {
    implicit request =>
      val unknownIdsAsSet = parseStringSet(unknownIds)
      eventDatastore
        .listEventsForTenant(
          tenant,
          TenantEventRequest(
            sortOrder =
              order.flatMap(o => parseSortOrder(o)).getOrElse(AscOrder),
            cursor = cursor,
            count = count,
            users = parseStringSet(users),
            begin = start.flatMap(s => Try { Instant.parse(s) }.toOption),
            end = end.flatMap(e => Try { Instant.parse(e) }.toOption),
            eventTypes = parseStringSet(types)
              .map(t => EventService.parseEventType(t))
              .collect { case Some(t) => t },
            total = total.getOrElse(false),
            features = parseStringSet(features).concat(unknownIdsAsSet),
            projects = parseStringSet(projects).concat(unknownIdsAsSet)
          )
        )
        .flatMap {
          case (events, maybeCount) => {
            val tokenIds = events
              .map(_.authentication)
              .collect {
                case EventAuthentication.TokenAuthentication(tokenId) =>
                  tokenId
              }
              .toSet

            personalAccessTokenDatastore
              .findAccessTokenByIds(tokenIds)
              .map(tokenNamesByIds => {
                (
                  events.map(e => {
                    val json = Json
                      .toJson(e)(EventService.eventFormat.writes(_))
                      .as[JsObject]
                    e.authentication match {
                      case EventAuthentication.TokenAuthentication(tokenId) => {
                        val tokenName = tokenNamesByIds.getOrElse(
                          tokenId,
                          s"<Deleted token> (token id was $tokenId)"
                        )
                        json ++ Json.obj("tokenName" -> tokenName)
                      }
                      case EventAuthentication.BackOfficeAuthentication => json
                      case EventAuthentication.RootAuthentication       => json
                    }
                  }),
                  maybeCount
                )
              })
          }
        }
        .map {
          case (events, maybeCount) => {
            val jsonCount = maybeCount.map(JsNumber(_)).getOrElse(JsNull)
            Ok(Json.obj("events" -> Json.toJson(events), "count" -> jsonCount))
          }
        }
  }

  def processForLegacyEndpoint(event: FeatureEvent): JsObject = {
    event match {
      case FeatureCreated(_, _, _, _, _, Some(strategiesByContext), _, _, _) =>
        createEvent(
          event.id,
          FeatureFormats.writeFeatureInLegacyFormat(strategiesByContext(""))
        )
      case FeatureUpdated(
            _,
            _,
            _,
            _,
            _,
            Some(strategiesByContext),
            _,
            _,
            _,
            _
          ) =>
        updateEvent(
          event.id,
          FeatureFormats.writeFeatureInLegacyFormat(strategiesByContext(""))
        )
      case _ => deleteEvent(event.id)
    }
  }

  def keepAlive(interval: FiniteDuration): Flow[JsObject, JsObject, NotUsed] =
    Flow[JsObject]
      .keepAlive(
        interval,
        () => keepAliveEvent()
      )

  def newBatchEvents(
      user: String,
      conditions: Boolean,
      refreshInterval: Int,
      keepAliveInterval: Int,
      context: Option[String],
      groups: Seq[String]
  ): Action[JsValue] =
    clientKeyAction.async(parse.json) { request =>
      implicit val nameExtractor: EventNameExtractor[JsObject] =
        EventNameExtractor[JsObject](event => Some((event \ "type").as[String]))

      val parsedContext =
        context.map(ctx => ctx.split("/").toSeq).getOrElse(Seq())
      val maybeParsedBody =
        BatchFeatureRequest.reads(request.body, parsedContext).asOpt;
      maybeParsedBody.fold(Future.successful(BadBodyFormat().toHttpResponse))(
        req => {
          val key = request.key
          val tenant = key.tenant
          val clientRequest = req.featureRequest
          val maybeBody = req.scriptPayload

          val source = eventService.consume(tenant)
          projectsDatastore
            .readProjectsById(tenant, clientRequest.projects)
            .map(m => m.values.map(p => p.name).toSet)
            .map(allowedProjects => {
              val resultSource = source.source
                .filter {
                  case event: FeatureEvent => {
                    key.admin || key.projects.exists(ap =>
                      ap.name == event.project
                    )
                  }
                  case _ => false
                }
                .filter {
                  case event: FeatureEvent => {
                    // TODO handle tag
                    allowedProjects.contains(event.project) ||
                    clientRequest.features.contains(event.id)
                  }
                  case _ => false
                }
                .mapAsync(1)(e =>
                  eventService.internalToExternalEvent(
                    e,
                    RequestContext(
                      tenant,
                      user,
                      FeatureContextPath(elements = clientRequest.context),
                      groups = FeatureRequest.processInputSeqString(groups)
                    ),
                    conditions
                  )
                )
                .filter(_.isDefined)
                .map(_.get)

              val refreshProvider = () => {
                val res = evaluateFeatures(
                  tenant,
                  user,
                  conditions,
                  clientRequest,
                  request,
                  Some(maybeBody),
                  FeatureRequest.processInputSeqString(groups)
                )

                res.value.map {
                  case Left(err)    => errorEvent(err.message)
                  case Right(value) => value
                }
              }
              val refreshSource = if (refreshInterval > 0) {
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
                  ) via keepAliveV2(
                  keepAliveInterval.seconds
                ) via EventSource.flow via source.killswitch.flow
              Ok.chunked(
                s.watchTermination()((_, future) =>
                  future.onComplete {
                    case Failure(exception) =>
                      logger.error("Event source failed", exception)
                    case Success(foo) => {
                      logger.debug("Event source closed")
                    }
                  }
                )
              ).as(ContentTypes.EVENT_STREAM)
            })
        }
      )
    }

  def newEvents(
      user: String,
      conditions: Boolean,
      refreshInterval: Int,
      keepAliveInterval: Int,
      clientRequest: FeatureRequest,
      groups: Seq[String]
  ): Action[AnyContent] =
    clientKeyAction.async { request =>
      implicit val nameExtractor: EventNameExtractor[JsObject] =
        EventNameExtractor[JsObject](event => Some((event \ "type").as[String]))
      val key = request.key
      val tenant = key.tenant
      val maybeBody =
        request.body.asJson.flatMap(jsValue => jsValue.asOpt[JsObject])

      val source = eventService.consume(tenant)
      projectsDatastore
        .readProjectsById(tenant, clientRequest.projects)
        .map(m => m.values.map(p => p.name).toSet)
        .map(allowedProjects => {
          val resultSource = source.source
            .filter {
              case event: FeatureEvent => {
                key.admin || key.projects.exists(ap => ap.name == event.project)
              }
              case _ => false
            }
            .filter {
              case event: FeatureEvent => {
                // TODO handle tag
                allowedProjects.contains(event.project) ||
                clientRequest.features.contains(event.id)
              }
              case _ => false
            }
            .mapAsync(1)(e =>
              eventService.internalToExternalEvent(
                e,
                RequestContext(
                  tenant,
                  user,
                  FeatureContextPath(elements = clientRequest.context),
                  groups = FeatureRequest.processInputSeqString(groups)
                ),
                conditions
              )
            )
            .filter(_.isDefined)
            .map(_.get)

          val refreshProvider = () => {
            val res = evaluateFeatures(
              tenant,
              user,
              conditions,
              clientRequest,
              request,
              maybeBody,
              FeatureRequest.processInputSeqString(groups)
            )

            res.value.map {
              case Left(err)    => errorEvent(err.message)
              case Right(value) => value
            }
          }
          val refreshSource = if (refreshInterval > 0) {
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
              ) via keepAliveV2(
              keepAliveInterval.seconds
            ) via EventSource.flow via source.killswitch.flow
          Ok.chunked(
            s.watchTermination()((_, future) =>
              future.onComplete {
                case Failure(exception) =>
                  logger.error("Event source failed", exception)
                case Success(foo) => {
                  logger.debug("Event source closed")
                }
              }
            )
          ).as(ContentTypes.EVENT_STREAM)
        })
    }

  def keepAliveV2(interval: FiniteDuration): Flow[JsObject, JsObject, NotUsed] =
    Flow[JsObject]
      .keepAlive(
        interval,
        () => keepAliveEventV2()
      )

  private def evaluateFeatures(
      tenant: String,
      user: String,
      conditions: Boolean,
      featureRequest: FeatureRequest,
      request: ClientKeyRequest[?],
      maybeBody: Option[JsObject],
      groups: Set[String]
  ): FutureEither[JsObject] = {
    val requestContext = RequestContext(
      tenant = tenant,
      user = user,
      groups = groups,
      now = Instant.now(),
      context = FeatureContextPath(featureRequest.context),
      data = maybeBody.getOrElse(Json.obj())
    )
    featureService
      .evaluateFeatures(
        conditions,
        requestContext,
        featureRequest,
        request.key.clientId,
        request.key.clientSecret
      )
      .map(result =>
        initialEvent(FeatureService.formatFeatureResponse(result, conditions))
      )
  }

  def killAllSources(): Action[AnyContent] = adminAuthAction.async { _ =>
    Future
      .sequence(
        Seq(
          webhookListener.onStop(),
          eventService.killAllSources(excludeIzanamiChannel = true)
        )
      )
      .map(_ => NoContent)

  }
}
