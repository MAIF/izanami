package fr.maif.izanami.web

import fr.maif.izanami.datastores.EventDatastore.{AscOrder, TenantEventRequest, parseSortOrder}
import fr.maif.izanami.env.Env
import fr.maif.izanami.events.{EventAuthentication, EventService}
import fr.maif.izanami.models.RightLevels.{RightLevel, superiorOrEqualLevels}
import fr.maif.izanami.models._
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.WasmManagerClient
import fr.maif.izanami.web.ProjectController.parseStringSet
import play.api.libs.json._
import play.api.mvc._

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

sealed trait ProjectChoiceStrategy
case class DeduceProject(fieldCount: Int = 1) extends ProjectChoiceStrategy
case class FixedProject(name: String)         extends ProjectChoiceStrategy

class TenantController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val adminAuthAction: AdminAuthAction,
    val tenantRightsAuthAction: TenantRightsAction,
    val validatePasswordAction: ValidatePasswordActionFactory,
    val wasmManagerClient: WasmManagerClient
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

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
                            projects: Option[String]
                          ): Action[AnyContent] = tenantAuthAction(tenant, RightLevels.Read).async { implicit request =>
    env.datastores.events
      .listEventsForTenant(tenant, TenantEventRequest(
        sortOrder = order.flatMap(o => parseSortOrder(o)).getOrElse(AscOrder),
        cursor = cursor,
        count = count,
        users = parseStringSet(users),
        begin = start.flatMap(s => Try{Instant.parse(s)}.toOption),
        end = end.flatMap(e => Try{Instant.parse(e)}.toOption),
        eventTypes = parseStringSet(types).map(t => EventService.parseEventType(t)).collect{case Some(t) => t},
        total = total.getOrElse(false),
        features = parseStringSet(features),
        projects = parseStringSet(projects)
      ))
      .flatMap{case (events, maybeCount) => {
        val tokenIds = events.map(_.authentication).collect {
          case EventAuthentication.TokenAuthentication(tokenId) => tokenId
        }.toSet

        env.datastores.personnalAccessToken.findAccessTokenByIds(tokenIds).map(tokenNamesByIds => {
          (events.map(e => {
            val json = Json.toJson(e)(EventService.eventFormat.writes).as[JsObject]
            e.authentication match {
              case EventAuthentication.TokenAuthentication(tokenId) => {
                val tokenName = tokenNamesByIds.getOrElse(tokenId, s"<Deleted token> (token id was $tokenId)")
                json ++ Json.obj("tokenName" -> tokenName)
              }
              case EventAuthentication.BackOfficeAuthentication => json
            }
          }), maybeCount)
        })
      }}
      .map{ case (events, maybeCount) => {
        val jsonCount = maybeCount.map(JsNumber(_)).getOrElse(JsNull)
        Ok(Json.obj("events" -> Json.toJson(events), "count" -> jsonCount))
      }}
  }

  def updateTenant(name: String): Action[JsValue] = tenantAuthAction(name, RightLevels.Admin).async(parse.json) {
    implicit request =>
      Tenant.tenantReads.reads(request.body) match {
        case JsSuccess(value, _) =>
          if (name != value.name) {
            BadRequest(Json.obj("message" -> "Modification of a tenant name is not permitted")).future
          } else {
            env.datastores.tenants.updateTenant(name, value).map {
              case Left(err)    => err.toHttpResponse
              case Right(value) => NoContent
            }
          }
        case JsError(errors)     => BadRequest(Json.obj("message" -> "Bad body format")).future
      }
  }

  def createTenant(): Action[JsValue] = adminAuthAction.async(parse.json) { implicit request =>
    Tenant.tenantReads.reads(request.body) match {
      case JsError(e)           => BadRequest(Json.obj("message" -> "bad body format")).future
      case JsSuccess(tenant, _) => {
        env.datastores.tenants
          .createTenant(tenant, request.user)
          .map(maybeTenant =>
            maybeTenant.fold(
              err => Results.Status(err.status)(Json.toJson(err)),
              tenant => Created(Json.toJson(tenant))
            )
          )
      }
    }
  }

  def readTenants(right: Option[RightLevel]): Action[AnyContent] = tenantRightsAuthAction.async { implicit request =>
    if (request.user.admin) {
      env.datastores.tenants
        .readTenants()
        .map(tenants => Ok(Json.toJson(tenants)))
    } else {
      val minimumRightLevel = right.getOrElse(RightLevels.Read)
      val allowedTenants    = Option(request.user.tenantRights)
        .map(m =>
          m.filter { case (name, level) => superiorOrEqualLevels(minimumRightLevel).contains(level) }.keys.toSet
        )
        .getOrElse(Set())
      env.datastores.tenants
        .readTenantsFiltered(allowedTenants)
        .map(tenants => Ok(Json.toJson(tenants)))
    }
  }

  def deleteTenant(name: String): Action[JsValue] =
    (tenantAuthAction(name, RightLevels.Admin) andThen validatePasswordAction()).async(parse.json) { implicit request =>
      env.datastores.tenants.deleteTenant(name, request.user).map {
        case Left(err) => err.toHttpResponse
        case Right(_)  => NoContent
      }
    }

  def readTenant(name: String): Action[AnyContent] = tenantAuthAction(name, RightLevels.Read).async {
    implicit request =>
      env.datastores.tenants
        .readTenantByName(name)
        .flatMap(maybeTenant => {
          maybeTenant.fold(
            err => Future.successful(Results.Status(err.status)(Json.toJson(err))),
            tenant => {
              for (
                projects <- {
                  env.datastores.projects.readTenantProjectForUser(tenant.name, request.user.username)
                };
                tags     <- env.datastores.tags.readTags(tenant.name)
              )
                yield {
                  Ok(
                    Json.toJson(
                      Tenant(name = tenant.name, projects = projects, tags = tags, description = tenant.description)
                    )
                  )
                }
            }
          )
        })
  }
}
