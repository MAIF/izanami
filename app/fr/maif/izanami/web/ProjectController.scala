package fr.maif.izanami.web

import fr.maif.izanami.datastores.EventDatastore.AscOrder
import fr.maif.izanami.datastores.EventDatastore.FeatureEventRequest
import fr.maif.izanami.datastores.EventDatastore.parseSortOrder
import fr.maif.izanami.env.Env
import fr.maif.izanami.events.EventAuthentication
import fr.maif.izanami.events.EventService
import fr.maif.izanami.models.Project
import fr.maif.izanami.models.RightLevels
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ProjectController.parseStringSet
import play.api.libs.json.JsError
import play.api.libs.json.JsNull
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc._

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.util.Try

class ProjectController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val projectAuthAction: ProjectAuthActionFactory,
    val validatePasswordAction: ValidatePasswordActionFactory,
    val detailledRightForTenanFactory: DetailledRightForTenantFactory
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;

  def readEventsForProject(
        tenant: String,
        project: String,
        order: Option[String],
        users: Option[String],
        types: Option[String],
        features: Option[String],
        start: Option[String],
        end: Option[String],
        cursor: Option[Long],
        count: Int,
        total: Option[Boolean]
    ): Action[AnyContent] =
    projectAuthAction(tenant, project, RightLevels.Read).async { implicit request =>
      env.datastores.events
        .listEventsForProject(tenant, project, FeatureEventRequest(
          sortOrder = order.flatMap(o => parseSortOrder(o)).getOrElse(AscOrder),
          cursor = cursor,
          count = count,
          users = parseStringSet(users),
          begin = start.flatMap(s => Try{Instant.parse(s)}.toOption),
          end = end.flatMap(e => Try{Instant.parse(e)}.toOption),
          eventTypes = parseStringSet(types).map(t => EventService.parseFeatureEventType(t)).collect{case Some(t) => t},
          features = parseStringSet(features),
          total = total.getOrElse(false)
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

  def createProject(tenant: String): Action[JsValue] = tenantAuthAction(tenant, RightLevels.Write).async(parse.json) {
    implicit request =>
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

  def updateProject(tenant: String, project: String): Action[JsValue] =
    projectAuthAction(tenant, project, RightLevels.Admin).async(parse.json) { implicit request =>
      Project.projectReads.reads(request.body) match {
        case JsSuccess(updatedProject, _) =>
          env.datastores.projects.updateProject(tenant, project, updatedProject).map(_ => NoContent)
        case JsError(_)                   => BadRequest(Json.obj("message" -> "bad body format")).future
      }
    }

  def readProjects(tenant: String): Action[AnyContent] = detailledRightForTenanFactory(tenant).async {
    implicit request =>
      val isTenantAdmin = request.user.tenantRight.exists(right => right.level == RightLevels.Admin)
      if (request.user.admin || isTenantAdmin) {
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

  def readProject(tenant: String, project: String): Action[AnyContent] =
    projectAuthAction(tenant, project, RightLevels.Read).async { implicit request =>
      env.datastores.projects
        .readProject(tenant, project)
        .map(maybeProject => {
          maybeProject.fold(
            err => Results.Status(err.status)(Json.toJson(err)),
            project => Ok(Json.toJson(project))
          )
        })
    }

  def deleteProject(tenant: String, project: String): Action[JsValue] =
    (projectAuthAction(tenant, project, RightLevels.Admin) andThen validatePasswordAction()).async(parse.json) {
      implicit request =>
        env.datastores.projects
          .deleteProject(tenant, project, request.user)
          .map {
            case Left(err)    => err.toHttpResponse
            case Right(value) => NoContent
          }

    }
}

object ProjectController {
  def parseStringSet(str: Option[String]): Set[String] = {
    str.map(s => s.split(",").toSet).getOrElse(Set())
  }
}
