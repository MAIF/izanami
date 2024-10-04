package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.models.RightLevels.RightLevel
import fr.maif.izanami.models.{Project, RightLevels}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc._

import scala.concurrent.ExecutionContext

class ProjectController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val projectAuthAction: ProjectAuthActionFactory,
    val detailledRightForTenanFactory: DetailledRightForTenantFactory
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;

  def createProject(tenant: String): Action[JsValue] = tenantAuthAction(tenant, RightLevels.Write).async(parse.json) { implicit request =>
    Project.projectReads.reads(request.body) match {
      case JsError(e)            => BadRequest(Json.obj("message" -> "bad body format")).future
      case JsSuccess(project, _) => {
        env.datastores.projects
          .createProject(tenant, project, request.user)
          .map(maybeProject =>
            maybeProject.fold(
              err => Results.Status(err.status)(Json.toJson(err)),
              project => Created(Json.toJson(project))
            )
          )
      }
    }
  }

  def updateProject(tenant: String, project: String): Action[JsValue] = projectAuthAction(tenant, project, RightLevels.Admin).async(parse.json) { implicit request =>
    Project.projectReads.reads(request.body) match {
      case JsSuccess(updatedProject, _) => env.datastores.projects.updateProject(tenant, project, updatedProject).map(_ => NoContent)
      case JsError(_) => BadRequest(Json.obj("message" -> "bad body format")).future
    }
  }

  def readProjects(tenant: String): Action[AnyContent] = detailledRightForTenanFactory(tenant).async { implicit request =>
    val isTenantAdmin = request.user.tenantRight.exists(right => right.level == RightLevels.Admin)
    if(request.user.admin || isTenantAdmin) {
      env.datastores.projects
        .readProjects(tenant)
        .map(projects => Ok(Json.toJson(projects)))
    } else {
      val filter = request.user.tenantRight
        .map(tr => tr.projects.keys.toSet)
        .getOrElse(Set())
      env.datastores.projects
        .readProjectsFiltered(tenant, filter)
        .map(projects => Ok(Json.toJson(projects)))
    }

  }

  def readProject(tenant: String, project: String): Action[AnyContent] = projectAuthAction(tenant, project, RightLevels.Read).async {
    implicit request =>
      env.datastores.projects
        .readProject(tenant, project)
        .map(maybeProject => {
          maybeProject.fold(
            err => Results.Status(err.status)(Json.toJson(err)),
            project => Ok(Json.toJson(project))
          )
        })
  }

  def deleteProject(tenant: String, project: String): Action[JsValue] = projectAuthAction(tenant, project, RightLevels.Admin).async(parse.json) {
    implicit request =>
      (request.body \ "password").asOpt[String] match {
        case Some(password) =>
          env.datastores.users
            .isUserValid(request.user, password)
            .flatMap {
              case Some(user) =>
                env.datastores.projects
                  .deleteProject(tenant, project, request.user).map {
                    case Left(err) => err.toHttpResponse
                    case Right(value) => NoContent
                  }
              case None => BadRequest(Json.obj("message" -> "Your password is invalid.")).future

            }
          case None => BadRequest(Json.obj("message" -> "Missing password.")).future
      }
  }
}
