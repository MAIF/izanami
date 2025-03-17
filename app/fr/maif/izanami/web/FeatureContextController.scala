package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{
  BadBodyFormat,
  FeatureContextDoesNotExist,
  IzanamiError,
  NoProtectedContextAccess,
  NotEnoughRights
}
import fr.maif.izanami.models.{FeatureContext, RightLevels, UserWithCompleteRightForOneTenant}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class FeatureContextController(
    val controllerComponents: ControllerComponents,
    val authAction: ProjectAuthActionFactory,
    val tenantAuthAction: TenantAuthActionFactory,
    val detailledRightForTenantFactory: DetailledRightForTenantFactory
)(implicit val env: Env)
    extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext
  val datastore                     = env.datastores.featureContext

  def createFeatureContext(tenant: String, project: String): Action[JsValue] =
    createSubContext(tenant, project, FeatureContextPath(Seq()))

  def updateFeatureContext(tenant: String, project: String, name: String): Action[JsValue] =
    updateFeatureSubContext(tenant, project, parents = FeatureContextPath(Seq()), name = name)

  def updateFeatureSubContext(
      tenant: String,
      project: String,
      parents: FeatureContextPath,
      name: String
  ): Action[JsValue] = authAction(tenant, project, RightLevels.Admin).async(parse.json) { implicit request =>
    {
      val json = request.body
      (json \ "protected").asOpt[Boolean] match {
        case Some(isProtected) =>
          datastore
            .updateLocalFeatureContext(
              tenant,
              project = project,
              name = name,
              isProtected = isProtected,
              parents = parents
            )
            .map {
              case Left(err) => err.toHttpResponse
              case Right(_)  => NoContent
            }
        case None              => BadRequest(Json.obj("message" -> "protected attribute is missing from body")).future
      }
    }
  }

  def createSubContext(tenant: String, project: String, parents: FeatureContextPath): Action[JsValue] =
    // TODO allow only write level users on project
    (detailledRightForTenantFactory(tenant)).async(parse.json) { implicit request =>
      {
        FeatureContext.readFeatureContext(request.body, global = false) match {
          case JsSuccess(value, _) => {
            type ProtectedStatus = Boolean
            val parentProtected: Future[Either[IzanamiError, ProtectedStatus]] = if (parents.elements.isEmpty) {
              if (request.user.hasRightForProject(project, RightLevels.Write)) {
                Right(false).future
              } else {
                Left(NotEnoughRights()).future
              }
            } else {
              readLocalContextIfPermitted(tenant, project, request.user, parents).map(either =>
                either.map(ctx => ctx.isProtected)
              )
            }

            parentProtected.flatMap {
              case Left(err)              => err.toHttpResponse.future
              case Left(err)              => err.toHttpResponse.future
              case Right(parentProtected) => {
                datastore
                  .createFeatureSubContext(
                    tenant,
                    project,
                    parents.elements,
                    value.name,
                    parentProtected || value.isProtected
                  )
                  .map {
                    case Left(err)      => err.toHttpResponse
                    case Right(context) => Created(Json.toJson(context))
                  }
              }
            }
          }
          case JsError(errors)     => BadRequest(Json.obj("message" -> "bad body format")).future
        }
      }
    }

  def deleteFeatureStrategy(
      tenant: String,
      project: String,
      context: FeatureContextPath,
      name: String
  ): Action[AnyContent] =
    detailledRightForTenantFactory(tenant).async { // TODO check that user has write right on project
      implicit request =>
        readLocalContextIfPermitted(tenant, project, request.user, context).flatMap {
          case Left(err) => err.toHttpResponse.future
          case Right(_)  => {
            datastore
              .deleteFeatureStrategy(tenant, project, context.elements, name, request.userInformation)
              .map {
                case Left(err) => err.toHttpResponse
                case Right(_)  => NoContent
              }
          }
        }
    }

  def deleteFeatureContext(tenant: String, project: String, context: FeatureContextPath): Action[AnyContent] =
    detailledRightForTenantFactory(tenant).async { implicit request =>
      readLocalContextIfPermitted(tenant, project, request.user, context).flatMap {
        case Left(err) => err.toHttpResponse.future
        case Right(_)  => {
          datastore
            .findChildrenForLocalContext(tenant, project, path = context)
            .flatMap(contexts => {
              if (
                contexts
                  .exists(ctx => ctx.isProtected) && !request.user.hasRightForProject(project, RightLevels.Admin)
              ) {
                val protectedSubContexts =
                  contexts.filter(c => c.isProtected).map(c => c.id.split("_").tail.mkString("/"))
                Forbidden(
                  Json.obj("message" -> s"Context can't be deleted since it has following protecting subcontexts : ${protectedSubContexts
                    .mkString(", ")}")
                ).future
              } else {
                datastore
                  .deleteContext(tenant, project, context.elements)
                  .map {
                    case Left(err) => err.toHttpResponse
                    case Right(_)  => NoContent
                  }
              }
            })
        }
      }
    }

  def readFeatureContexts(tenant: String, project: String): Action[AnyContent] =
    authAction(tenant, project, RightLevels.Read).async { implicit request: Request[AnyContent] =>
      datastore
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

  def createFeatureStrategy(
      tenant: String,
      project: String,
      parents: FeatureContextPath,
      name: String
  ): Action[JsValue] = {
    // TODO allow only write level users on project
    detailledRightForTenantFactory(tenant).async(parse.json) { implicit request =>
      FeatureContext.readcontextualFeatureStrategyRead(request.body, name) match {
        case JsSuccess(value, path) =>
          datastore
            .getFeatureContext(tenant, project, parents.elements)
            .flatMap {
              case None                                                                                         => FeatureContextDoesNotExist(parents.toUserPath).toHttpResponse.future
              case Some(ctx) if ctx.isProtected && !request.user.hasRightForProject(project, RightLevels.Admin) => {
                Forbidden(
                  Json.obj("message" -> "You are not allowed to create or update a feature stratgy for this context")
                ).future
              }
              case Some(_)                                                                                      => {
                datastore
                  .updateFeatureStrategy(tenant, project, parents.elements, name, value, request.userInformation)
                  .map(eitherCreated => {
                    eitherCreated.fold(
                      err => Results.Status(err.status)(Json.toJson(err)),
                      _ => NoContent
                    )
                  })
              }
            }
        case JsError(errors)        => BadRequest(Json.obj("message" -> "bad body format")).future
      }
    }
  }

  def createGlobalSubContext(tenant: String, parents: FeatureContextPath = FeatureContextPath()): Action[JsValue] =
    detailledRightForTenantFactory(tenant).async(parse.json) { implicit request =>
      FeatureContext.readFeatureContext(request.body, global = true) match {
        case JsSuccess(ctx, _) => {
          type ProtectedStatus = Boolean
          val isParentProtected: Future[Either[IzanamiError, ProtectedStatus]] = if (parents.elements.isEmpty) {
            if (!request.user.hasRightForTenant(RightLevels.Write)) {
              Left(NotEnoughRights()).future
            } else {
              Right(false).future
            }
          } else {
            readGlobalContextIfPermitted(tenant, request.user, parents).map(either => either.map(c => c.isProtected))
          }

          isParentProtected.flatMap {
            case Left(err)              => err.toHttpResponse.future
            case Right(parentProtected) =>
              datastore.createGlobalFeatureContext(
                tenant,
                parents.elements,
                ctx.copy(isProtected = ctx.isProtected || parentProtected)
              ) map {
                case Left(err)     => err.toHttpResponse
                case Right(result) => Created(Json.toJson(result))
              }
          }
        }
        case JsError(error)    => BadBodyFormat().toHttpResponse.future
      }
    }

  def createGlobalRootSubContext(tenant: String): Action[JsValue] = createGlobalSubContext(tenant, FeatureContextPath())

  def updateGlobalContext(
      tenant: String,
      context: FeatureContextPath = FeatureContextPath()
  ): Action[JsValue] = tenantAuthAction(tenant, RightLevels.Admin).async(parse.json) { implicit request =>
    {
      val json = request.body
      (json \ "protected").asOpt[Boolean] match {
        case Some(isProtected) =>
          datastore
            .updateGlobalFeatureContext(
              tenant,
              context = context,
              isProtected = isProtected
            )
            .map {
              case Left(err) => err.toHttpResponse
              case Right(_)  => NoContent
            }
        case None              => BadRequest(Json.obj("message" -> "protected attribute is missing from body")).future
      }
    }
  }

  def readGlobalContexts(tenant: String, all: Boolean): Action[AnyContent] =
    tenantAuthAction(tenant, RightLevels.Read).async { implicit request =>
      {
        if (all) {
          datastore
            .readAllLocalFeatureContexts(tenant)
            .map(createFeatureContextHierarchy)
            .map(ctx => Ok(Json.toJson(ctx)))
        } else {
          datastore
            .readGlobalFeatureContexts(tenant)
            .map(createFeatureContextHierarchy)
            .map(ctx => Ok(Json.toJson(ctx)))
        }
      }
    }

  def deleteGlobalFeatureContext(tenant: String, context: fr.maif.izanami.web.FeatureContextPath): Action[AnyContent] =
    detailledRightForTenantFactory(tenant).async { implicit request =>
      readGlobalContextIfPermitted(tenant, request.user, context).flatMap {
        case Left(err) => err.toHttpResponse.future
        case Right(_)  => {
          datastore
            .findChildrenForGlobalContext(tenant, context)
            .flatMap(ctxs => {
              if (ctxs.exists(c => c.isProtected) && !request.user.hasRightForTenant(RightLevels.Admin)) {
                val protectedSubContexts = ctxs.filter(c => c.isProtected).map(c => c.id.split("_").tail.mkString("/"))
                Forbidden(
                  Json.obj("message" -> s"Context can't be deleted since it has following protecting subcontexts : ${protectedSubContexts
                    .mkString(", ")}")
                ).future
              } else {
                datastore.deleteGlobalFeatureContext(tenant, context.elements).map {
                  case Left(err) => err.toHttpResponse
                  case Right(_)  => NoContent
                }
              }
            })
        }
      }
    }

  def readLocalContextIfPermitted(
      tenant: String,
      project: String,
      user: UserWithCompleteRightForOneTenant,
      contextPath: FeatureContextPath
  ): Future[Either[IzanamiError, FeatureContext]] = {
    // TODO this may be a dedicated Action
    datastore
      .getFeatureContext(tenant, project, contextPath.elements)
      .map {
        case None                                                                                 => {
          Left(FeatureContextDoesNotExist(contextPath.toUserPath))
        }
        case Some(ctx) if ctx.isProtected && !user.hasRightForProject(project, RightLevels.Admin) => {
          Left(NoProtectedContextAccess(contextPath.toUserPath))
        }
        case Some(_) if !user.hasRightForProject(project, RightLevels.Write)                      => {
          Left(NotEnoughRights())
        }
        case Some(ctx)                                                                            => Right(ctx)
      }
  }

  def readGlobalContextIfPermitted(
      tenant: String,
      user: UserWithCompleteRightForOneTenant,
      contextPath: FeatureContextPath
  ): Future[Either[IzanamiError, FeatureContext]] = {
    // TODO this may be a dedicated Action
    datastore
      .getGlobalFeatureContext(tenant, contextPath.elements)
      .map {
        case None                                                                       => {
          Left(FeatureContextDoesNotExist(contextPath.toUserPath))
        }
        case Some(ctx) if ctx.isProtected && !user.hasRightForTenant(RightLevels.Admin) => {
          Left(NoProtectedContextAccess(contextPath.toUserPath))
        }
        case Some(_) if !user.hasRightForTenant(RightLevels.Write)                      => {
          Left(NotEnoughRights())
        }
        case Some(ctx)                                                                  => Right(ctx)
      }
  }

}
