package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.BadBodyFormat
import fr.maif.izanami.models.FeatureContext
import fr.maif.izanami.models.RightLevels
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.ExecutionContext

class FeatureContextController(
    val controllerComponents: ControllerComponents,
    val authAction: ProjectAuthActionFactory,
    val tenantAuthAction: TenantAuthActionFactory
                              )(implicit val env: Env) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext
  def createFeatureContext(tenant: String, project: String): Action[JsValue] = createSubContext(tenant, project, FeatureContextPath(Seq()))

  def createSubContext(tenant: String, project: String, parents: FeatureContextPath): Action[JsValue] = authAction(tenant, project, RightLevels.Write).async(parse.json) {
    implicit request =>
      FeatureContext.readFeatureContext(request.body, global=false) match {
        case JsSuccess(value, _) =>
          env.datastores.featureContext
            .createFeatureSubContext(tenant, project, parents.elements, value.name)
            .map(either => {
              either.fold(
                err => Results.Status(err.status)(Json.toJson(err)),
                context => Created(Json.toJson(context))
              )
            })
        case JsError(errors)        => BadRequest(Json.obj("message" -> "bad body format")).future
      }
  }

  def deleteFeatureStrategy(tenant: String, project: String, context: FeatureContextPath, name: String): Action[AnyContent] = authAction(tenant, project, RightLevels.Write).async {
    implicit request: ProjectIdUserNameRequest[AnyContent] =>
      env.datastores.featureContext.deleteFeatureStrategy(tenant, project, context.elements, name, request.user)
        .map {
          case Left(err) => err.toHttpResponse
          case Right(_) => NoContent
        }
  }

  def deleteFeatureContext(tenant: String, project: String, context: FeatureContextPath): Action[AnyContent] = authAction(tenant, project, RightLevels.Write).async {
    implicit request: Request[AnyContent] =>
      env.datastores.featureContext.deleteContext(tenant, project, context.elements)
        .map {
          case Left(err) => err.toHttpResponse
          case Right(_) => NoContent
        }
  }

  def readFeatureContexts(tenant: String, project: String): Action[AnyContent] = authAction(tenant, project, RightLevels.Read).async {
    implicit request: Request[AnyContent] =>
      env.datastores.featureContext
        .readFeatureContexts(tenant, project)
        .map(createFeatureContextHierarchy)
        .map(keys => Ok(Json.toJson(keys)))
  }

  def createFeatureContextHierarchy(contexts: Seq[FeatureContext]): Seq[FeatureContext] = {
    val byParent     = contexts.groupBy(g => g.parent)
    val topOfTheTree = byParent.getOrElse(null, Seq())

    def fillChildren(current: FeatureContext): FeatureContext = {
      val children = byParent.getOrElse(current.id, Seq())
      if (children.isEmpty)
        current
      else
        current.copy(children = children.map(g => fillChildren(g)))
    }

    topOfTheTree.map(fillChildren)
  }

  def createFeatureStrategy(tenant: String, project: String, parents: FeatureContextPath, name: String): Action[JsValue] =
    authAction(tenant, project, RightLevels.Write).async(parse.json) { implicit request =>
      FeatureContext.readcontextualFeatureStrategyRead(request.body, name) match {
        case JsSuccess(value, path) =>
          env.datastores.featureContext
            .updateFeatureStrategy(tenant, project, parents.elements, name, value, request.user)
            .map(eitherCreated => {
              eitherCreated.fold(
                err => Results.Status(err.status)(Json.toJson(err)),
                _ => NoContent
              )
            })
        case JsError(errors)        => BadRequest(Json.obj("message" -> "bad body format")).future
      }
    }

  def createGlobalSubContext(tenant: String, parents: FeatureContextPath=FeatureContextPath()): Action[JsValue]  = tenantAuthAction(tenant, RightLevels.Write).async(parse.json) {
    implicit request =>
      FeatureContext.readFeatureContext(request.body, global=true) match {
        case JsSuccess(ctx, _) => env.datastores.featureContext.createGlobalFeatureContext(tenant, parents.elements, ctx) map {
          case Left(err) => err.toHttpResponse
          case Right(result) => Created(Json.toJson(result))
        }
        case JsError(error) => BadBodyFormat().toHttpResponse.future
      }
  }

  def createGlobalRootSubContext(tenant: String): Action[JsValue]  = createGlobalSubContext(tenant, FeatureContextPath())

  def readGlobalContexts(tenant: String, all: Boolean): Action[AnyContent]  = tenantAuthAction(tenant, RightLevels.Read).async {
    implicit request => {
      if(all) {
        env.datastores.featureContext.readAllLocalFeatureContexts(tenant).map(createFeatureContextHierarchy).map(ctx => Ok(Json.toJson(ctx)))
      } else {
        env.datastores.featureContext.readGlobalFeatureContexts(tenant).map(createFeatureContextHierarchy).map(ctx => Ok(Json.toJson(ctx)))
      }
    }
  }

  def deleteGlobalFeatureContext(tenant: String, context: fr.maif.izanami.web.FeatureContextPath): Action[AnyContent] = tenantAuthAction(tenant, RightLevels.Write).async {
    implicit request =>
      env.datastores.featureContext.deleteGlobalFeatureContext(tenant, context.elements).map {
        case Left(err) => err.toHttpResponse
        case Right(_) => NoContent
      }
  }
}


