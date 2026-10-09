package fr.maif.izanami.web

import fr.maif.izanami.models.*
import fr.maif.izanami.models.RightLevel.Read
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.*
import play.api.mvc.*

import scala.concurrent.ExecutionContext
import fr.maif.izanami.services.TenantService

sealed trait ProjectChoiceStrategy
case class DeduceProject(fieldCount: Int = 1) extends ProjectChoiceStrategy
case class FixedProject(name: String) extends ProjectChoiceStrategy

class TenantController(
    val controllerComponents: ControllerComponents,
    private val tenantAuthAction: TenantAuthActionFactory,
    private val personnalAccessTokenAuthAction: PersonnalAccessTokenAdminAuthActionFactory,
    private val personnalAccessTokenTenantAuthAction: PersonnalAccessTokenTenantAuthActionFactory,
    private val personnalAccessTokenTenantRightsAuthAction: PersonnalAccessTokenTenantRightsActionFactory,
    private val tenantService: TenantService
)(implicit ec: ExecutionContext) extends BaseController {

  def updateTenant(name: String): Action[JsValue] =
    tenantAuthAction(name, RightLevel.Admin).async(parse.json) {
      implicit request =>
        Tenant.tenantReads.reads(request.body) match {
          case JsSuccess(value, _) =>
            tenantService.updateTenant(
              name = name,
              updateRequest = value
            ).toResult(_ => NoContent)
          case JsError(errors) =>
            BadRequest(Json.obj("message" -> "Bad body format")).future
        }
    }

  def createTenant(): Action[JsValue] =
    personnalAccessTokenAuthAction(CreateTenant).async(parse.json) {
      implicit request =>
        Tenant.tenantReads.reads(request.body) match {
          case JsError(e) =>
            BadRequest(Json.obj("message" -> "bad body format")).future
          case JsSuccess(tenant, _) => {
            tenantService
              .createTenant(tenant, StandardUserInformation(username=request.user, authentication = request.authentication))
              .toResult(tenant => Created(Json.toJson(tenant)))
          }
        }
    }

  def readTenants(right: Option[RightLevel]): Action[AnyContent] =
    personnalAccessTokenTenantRightsAuthAction(ReadTenants).async {
      implicit request =>
        tenantService.readTenants(user = request.user, right = right)
          .map(tenants =>
            Ok(Json.toJson(tenants)(Writes.seq(Tenant.simpleTenantWrite)))
          )
    }

  def deleteTenant(name: String): Action[AnyContent] =
    (tenantAuthAction(name, RightLevel.Admin)).async { implicit request =>
      tenantService.deleteTenant(name, StandardUserInformation(username=request.user, authentication = request.authentication)).toResult(_ => NoContent)
    }

  def readTenant(name: String): Action[AnyContent] =
    personnalAccessTokenTenantAuthAction(
      tenant = name,
      minimumLevel = Read,
      operation = ReadTenant
    ).async { implicit request =>
      tenantService.readTenant(name = name, user = StandardUserInformation(username=request.user, authentication = request.authentication)).toResult(
        tenant =>
          Ok(
            Json.toJson(
              tenant
            )(Tenant.simpleTenantWithProjectWrites)
          )
      )
    }
}
