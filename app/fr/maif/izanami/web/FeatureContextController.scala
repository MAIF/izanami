package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors._
import fr.maif.izanami.models._
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json._
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
  ): Action[JsValue] = authAction(tenant, project, ProjectRightLevel.Admin).async(parse.json) { implicit request =>
    {
      (request.body \ "protected").asOpt[Boolean] match {
        case Some(isProtected) =>
          datastore
            .updateLocalFeatureContext(
              tenant,
              project = project,
              isProtected = isProtected,
              path = FeatureContextPath(parents.elements.appended(name))
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
        (request.body \ "name")
          .asOpt[String]
          .map(name => {
            val maybeProtected = (request.body \ "protected").asOpt[Boolean]
            type ProtectedStatus = Boolean
            val parentProtected: Future[Either[IzanamiError, ProtectedStatus]] = if (parents.elements.isEmpty) {
              if (request.user.hasRightForProject(project, ProjectRightLevel.Write)) {
                Right(false).future
              } else {
                Left(NotEnoughRights()).future
              }
            } else {
              canUpdateContext(tenant, request.user, parents).map(either => either.map(ctx => ctx.isProtected))
            }

            parentProtected.flatMap {
              case Left(err)                                     => err.toHttpResponse.future
              case Left(err)                                     => err.toHttpResponse.future
              case Right(true) if maybeProtected.exists(p => !p) =>
                BadRequest(
                  Json.obj("message" -> "Can't create an unprotected context as child of protected context")
                ).future
              case Right(parentProtected)                        => {
                val isProtected = maybeProtected.getOrElse(parentProtected)
                datastore
                  .createContext(
                    tenant,
                    parents,
                    LocalFeatureContextCreationRequest(
                      name = name,
                      parent = parents,
                      global = false,
                      project = project,
                      isProtected = isProtected
                    )
                  )
                  .map {
                    case Left(err)      => err.toHttpResponse
                    case Right(context) => Created(Json.toJson(context)(Context.writes))
                  }
              }
            }
          })
      }.getOrElse(BadRequest(Json.obj("message" -> "Bad body format")).future)
    }

  def deleteFeatureStrategy(
      tenant: String,
      project: String,
      context: FeatureContextPath,
      name: String
  ): Action[AnyContent] =
    detailledRightForTenantFactory(tenant).async { // TODO check that user has write right on project
      implicit request =>
        canUpdateContext(tenant, request.user, context).flatMap {
          case Left(err) => err.toHttpResponse.future
          case Right(_)  => {
            datastore
              .deleteFeatureStrategy(tenant, project, context, name, request.userInformation)
              .map {
                case Left(err) => err.toHttpResponse
                case Right(_)  => NoContent
              }
          }
        }
    }

  def deleteFeatureContext(tenant: String, project: String, context: FeatureContextPath): Action[AnyContent] =
    detailledRightForTenantFactory(tenant).async { implicit request =>
      canUpdateContext(tenant, request.user, context).flatMap {
        case Left(err) => err.toHttpResponse.future
        case Right(_)  => {
          datastore
            .findChildrenForLocalContext(tenant, project, path = context)
            .flatMap(contexts => {
              if (
                contexts
                  .exists(ctx => ctx.isProtected) && !request.user.hasRightForProject(project, ProjectRightLevel.Admin)
              ) {
                val protectedSubContexts =
                  contexts.filter(c => c.isProtected).map(c => c.fullyQualifiedName.mkString("/"))
                Forbidden(
                  Json.obj("message" -> s"Context can't be deleted since it has following protected subcontexts : ${protectedSubContexts
                    .mkString(", ")}")
                ).future
              } else {
                datastore
                  .deleteContext(tenant, project, context)
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
    authAction(tenant, project, ProjectRightLevel.Read).async { implicit request: Request[AnyContent] =>
      datastore
        .readFeatureContexts(tenant, project)
        .map(createFeatureContextHierarchy)
        .map(keys => Ok(Json.toJson(keys)(Writes.seq(ContextNode.writes))))
    }

  def createFeatureContextHierarchy(contexts: Seq[ContextHolder]): Seq[ContextNode] = {
    val byParent     = contexts.groupBy(g => g.context.path)
    val topOfTheTree = byParent.getOrElse(Seq(), Seq())

    def fillChildren(current: ContextHolder): ContextNode = {
      val children = byParent.getOrElse(current.context.fullyQualifiedName, Seq())
      if (children.isEmpty) {
        ContextNode(underlying = current, children = Seq())
      } else {
        ContextNode(underlying = current, children = children.map(g => fillChildren(g)))
      }
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
            .getFeatureContext(tenant, project, parents)
            .flatMap {
              case None    => FeatureContextDoesNotExist(parents.toUserPath).toHttpResponse.future
              case Some(ctx)
                  if ctx.isProtected && !request.user.hasRightForProject(project, ProjectRightLevel.Admin) => {
                Forbidden(
                  Json.obj("message" -> "You are not allowed to create or update a feature stratgy for this context")
                ).future
              }
              case Some(_) => {
                datastore
                  .updateFeatureStrategy(tenant, project, parents, name, value, request.userInformation)
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
      (request.body \ "name")
        .asOpt[String]
        .map(name => {
          val maybeProtected = (request.body \ "protected").asOpt[Boolean]

          type ProtectedStatus = Boolean
          val isParentProtected: Future[Either[IzanamiError, ProtectedStatus]] = if (parents.elements.isEmpty) {
            if (!request.user.hasRightForTenant(RightLevel.Write)) {
              Left(NotEnoughRights()).future
            } else {
              Right(false).future
            }
          } else {
            canUpdateContext(tenant, request.user, parents).map(either => either.map(c => c.isProtected))
          }

          isParentProtected.flatMap {
            case Left(err)                                     => err.toHttpResponse.future
            case Right(true) if maybeProtected.exists(p => !p) =>
              BadRequest(
                Json.obj("message" -> "Can't create an unprotected context as child of protected context")
              ).future
            case Right(parentProtected)                        => {
              val isProtected = maybeProtected.getOrElse(parentProtected)
              datastore.createContext(
                tenant,
                parents,
                GlobalFeatureContextCreationRequest(
                  name = name,
                  parent = parents,
                  global = true,
                  isProtected = isProtected
                )
              ) map {
                case Left(err)     => err.toHttpResponse
                case Right(result) => Created(Json.toJson(result)(Context.writes))
              }
            }
          }
        })
        .getOrElse(BadBodyFormat().toHttpResponse.future)
    }

  def createGlobalRootSubContext(tenant: String): Action[JsValue] = createGlobalSubContext(tenant, FeatureContextPath())

  def updateGlobalContext(
      tenant: String,
      context: FeatureContextPath = FeatureContextPath()
  ): Action[JsValue] = tenantAuthAction(tenant, RightLevel.Admin).async(parse.json) { implicit request =>
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
    tenantAuthAction(tenant, RightLevel.Read).async { implicit request =>
      {
        if (all) {
          datastore
            .readAllLocalFeatureContexts(tenant)
            .map(createFeatureContextHierarchy)
            .map(ctx => Ok(Json.toJson(ctx)(Writes.seq(ContextNode.writes))))
        } else {
          datastore
            .readGlobalFeatureContexts(tenant)
            .map(createFeatureContextHierarchy)
            .map(ctx => Ok(Json.toJson(ctx)(Writes.seq(ContextNode.writes))))
        }
      }
    }

  def deleteGlobalFeatureContext(tenant: String, context: fr.maif.izanami.web.FeatureContextPath): Action[AnyContent] =
    detailledRightForTenantFactory(tenant).async { implicit request =>
      canUpdateContext(tenant, request.user, context).flatMap {
        case Left(err) => err.toHttpResponse.future
        case Right(_)  => {
          datastore
            .findChildrenForGlobalContext(tenant, context)
            .flatMap(ctxs => {
              if (ctxs.exists(c => c.isProtected) && !request.user.hasRightForTenant(RightLevel.Admin)) {
                val protectedSubContexts = ctxs.filter(c => c.isProtected).map(c => c.fullyQualifiedName.mkString("/"))
                Forbidden(
                  Json.obj("message" -> s"Context can't be deleted since it has following protecting subcontexts : ${protectedSubContexts
                    .mkString(", ")}")
                ).future
              } else {
                datastore.deleteGlobalFeatureContext(tenant, context).map {
                  case Left(err) => err.toHttpResponse
                  case Right(_)  => NoContent
                }
              }
            })
        }
      }
    }

  def canUpdateContext(
      tenant: String,
      user: UserWithCompleteRightForOneTenant,
      contextPath: FeatureContextPath
  ): Future[Either[IzanamiError, Context]] = {
    datastore
      .readContext(tenant, contextPath)
      .map(o => o.toRight(FeatureContextDoesNotExist(contextPath.toUserPath)))
      .map(e =>
        e.flatMap {
          case c: GlobalContext if c.isProtected && user.hasRightForTenant(RightLevel.Admin)                   => Right(c)
          case c: GlobalContext if c.isProtected                                                                => Left(NoProtectedContextAccess(contextPath.toUserPath))
          case c: GlobalContext if user.hasRightForTenant(RightLevel.Write)                                    => Right(c)
          case c: GlobalContext                                                                                 => Left(NotEnoughRights())
          case c: LocalContext if c.isProtected && user.hasRightForProject(c.project, ProjectRightLevel.Admin) =>
            Right(c)
          case c: LocalContext if c.isProtected                                                                 => Left(NoProtectedContextAccess(contextPath.toUserPath))
          case c: LocalContext if user.hasRightForProject(c.project, ProjectRightLevel.Write)                  => Right(c)
          case _                                                                                                => Left(NotEnoughRights())
        }
      )
  }

}
