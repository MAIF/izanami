package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsError, JsSuccess, JsValue, Reads}
import play.api.mvc.{Action, BaseController, ControllerComponents}

class ExportController(
val env: Env,
val controllerComponents: ControllerComponents,
val tenantAuthAction: DetailledRightForTenantFactory) extends BaseController {

  def exportTenantData(tenant: String): Action[JsValue] = tenantAuthAction(tenant).async(parse.json) { implicit request =>
    /*exportRequestReads.reads(request.body).map(request => {

    })*/
    Ok("").future
  }
}


object ExportController {
  case class ExportRequest(projects: Map[String, ProjectExportRequest])
  case class ProjectExportRequest(features: Set[String])

  val exportRequestReads: Reads[ExportRequest] = json => {
    (json \ "projects").asOpt[Map[String, ProjectExportRequest]](Reads.map(projectExportRequestReads)).map(projects => JsSuccess(ExportRequest(projects))).getOrElse(JsError("Bad body format"))
  }

  val projectExportRequestReads: Reads[ProjectExportRequest] = json => {
    (json \ "features").asOpt[Set[String]].map(features => JsSuccess(ProjectExportRequest(features))).getOrElse(JsError("Bad body format"))
  }
}