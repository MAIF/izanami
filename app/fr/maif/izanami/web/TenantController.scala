package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.models.RightLevels.{superiorOrEqualLevels, RightLevel}
import fr.maif.izanami.models._
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.WasmManagerClient
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

sealed trait ProjectChoiceStrategy
case class DeduceProject(fieldCount: Int = 1) extends ProjectChoiceStrategy
case class FixedProject(name: String)         extends ProjectChoiceStrategy

class TenantController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val adminAuthAction: AdminAuthAction,
    val tenantRightsAuthAction: TenantRightsAction,
    val wasmManagerClient: WasmManagerClient,
    val eventController: EventController
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;

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

  def deleteTenant(name: String): Action[AnyContent] = tenantAuthAction(name, RightLevels.Admin).async {
    implicit request =>
      env.datastores.tenants.deleteTenant(name, request.user).map {
        case Left(err)    => err.toHttpResponse
        case Right(value) => NoContent
      }
  }

  def readTenant(name: String): Action[AnyContent] = tenantAuthAction(name, RightLevels.Read).async {
    implicit request =>
      env.datastores.tenants
        .readTenantByName(name)
        .flatMap(maybeTenant =>
          maybeTenant.fold(
            err => Future.successful(Results.Status(err.status)(Json.toJson(err))),
            tenant => {
              for (
                projects <- {
                  env.datastores.projects.readTenantProjectForUser(tenant.name, request.user)
                };
                tags     <- env.datastores.tags.readTags(tenant.name)
              )
                yield Ok(
                  Json.toJson(
                    Tenant(name = tenant.name, projects = projects, tags = tags, description = tenant.description)
                  )
                )
            }
          )
        )
  }
}
