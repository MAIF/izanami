package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import fr.maif.izanami.web.PathElement.pathElementWrite
import play.api.libs.json.{JsObject, Json, Writes}
import play.api.mvc._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

class SearchController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val userDetailedAuthAction: DetailledAuthAction,
    val simpleAuthAction: AuthenticatedAction,
    val tenantRightAction: TenantRightsAction
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

  def searchEntities(query: String): Action[AnyContent] = userDetailedAuthAction.async {
    implicit request: UserRequestWithCompleteRights[AnyContent] =>
      val userTenants = request.user.rights.tenants

      if (request.user.admin) {
        env.datastores.tenants
          .readTenants()
          .flatMap(tenants =>
            env.datastores.searchQueries
              .searchEntitiesForAdmin(tenants.map(tenant => tenant.name), query)
              .map(entities =>
                entities.fold(
                  err => Results.Status(err.status)(Json.toJson(err)),
                  entities => Ok(Json.toJson(entities))
                )
              )
          )

      } else if (userTenants.nonEmpty) {
        env.datastores.searchQueries
          .searchEntities(userTenants, query)
          .map(entities =>
            entities.fold(
              err => Results.Status(err.status)(Json.toJson(err)),
              entities => Ok(Json.toJson(entities))
            )
          )
      } else {
        Future.successful(Forbidden(Json.obj("message" -> "User has no tenants rights ")))
      }

  }

  def searchEntitiesByTenant(tenant: String, query: String): Action[AnyContent] = userDetailedAuthAction.async {
    implicit request: UserRequestWithCompleteRights[AnyContent] =>
      val userTenants = request.user.rights.tenants

      if (request.user.admin) {
        env.datastores.searchQueries
          .searchEntitiesForAdmin(List(tenant), query)
          .map(entities =>
            entities.fold(
              err => Results.Status(err.status)(Json.toJson(err)),
              entities => Ok(Json.toJson(entities))
            )
          )
      } else if (userTenants.nonEmpty && userTenants.contains(tenant)) {
        val filteredTenants = userTenants.filter { case (t, _) => t == tenant }
        env.datastores.searchQueries
          .searchEntities(filteredTenants, query)
          .map(entities =>
            entities.fold(
              err => Results.Status(err.status)(Json.toJson(err)),
              entities => Ok(Json.toJson(entities))
            )
          )
      } else {
        Future.successful(Forbidden(Json.obj("message" -> s"User has no rights for this tenant ${tenant}")))
      }
  }

  def search(query: String): Action[AnyContent] = tenantRightAction.async {
    implicit request: UserRequestWithTenantRights[AnyContent] =>
      {
        val tenants = request.user.tenantRights.keySet
        Future
          .sequence(
            tenants
              .map(tenant => {
                env.datastores.searchQueries
                  .tenantSearch(tenant, request.user.username, query)
                  .flatMap(results => {
                    Future.sequence(results.map { case (rowType, rowJson) =>
                      buildPath(rowType, rowJson, tenant)
                        .map(pathElements => {
                          val name     = (rowJson \ "name").asOpt[String].getOrElse("")
                          val jsonPath =
                            Json.toJson(pathElements.prepended(TenantPathElement(tenant)))(Writes.seq(pathElementWrite))
                          Json.obj(
                            "type"   -> rowType,
                            "name"   -> name,
                            "path"   -> jsonPath,
                            "tenant" -> tenant
                          )
                        })
                    })
                  })
              })
              .toSeq
          )
          .map(_.flatten.toList)
          .map(r => Ok(Json.toJson(r)))
      }
  }

  def searchForTenant(tenant: String, query: String): Action[AnyContent] = simpleAuthAction.async {
    implicit request: UserNameRequest[AnyContent] =>
      {
        env.datastores.searchQueries
          .tenantSearch(tenant, request.user, query)
          .flatMap(results => {
            Future.sequence(results.map { case (rowType, rowJson) =>
              buildPath(rowType, rowJson, tenant)
                .map(pathElements => {
                  val jsonPath = Json.toJson(pathElements)(Writes.seq(pathElementWrite))
                  val name     = (rowJson \ "name").asOpt[String].getOrElse("")
                  Json.obj(
                    "type"   -> rowType,
                    "name"   -> name,
                    "path"   -> jsonPath,
                    "tenant" -> tenant
                  )
                })
            })
          })
          .map(res => Ok(Json.toJson(res)))
      }
  }

  def buildPath(rowType: String, rowJson: JsObject, tenant: String): Future[Seq[PathElement]] = {
    rowType match {
      case "feature"        => Seq(ProjectPathElement((rowJson \ "project").as[String])).future
      case "global_context" => {
        (rowJson \ "parent")
          .asOpt[String]
          .map(parent => {
            parent.split("_").toSeq.drop(1).map(ctx => GlobalContextPathElement(ctx))
          })
          .getOrElse(Seq.empty)
          .future
      }
      case "local_context"  => {
        (rowJson \ "global_parent")
          .asOpt[String]
          .map(parent => {
            val parts   = parent.split("_").toSeq
            val project = (rowJson \ "project").as[String]
            parts
              .drop(1)
              .map(ctx => GlobalContextPathElement(ctx))
              .appended(ProjectPathElement(project))
              .future
          })
          .orElse(
            (rowJson \ "parent")
              .asOpt[String]
              .map(parent => {
                val parts   = parent.split("_")
                val project = parts.head

                env.datastores.featureContext
                  .findLocalContexts(tenant, generateParentCandidates(parts.drop(1)).map(s => s"${project}_${s}"))
                  .map(ctxs => {
                    val parentLocalContext      = ctxs.sortBy(_.length).headOption
                    val parts: Seq[PathElement] = parentLocalContext
                      .map(lc => {
                        val shortestLocalContextParts = lc.split("_")
                        val parentLocalContextName    = shortestLocalContextParts.last
                        val globalContextParts        =
                          generateParentCandidates(shortestLocalContextParts.dropRight(1).drop(1))
                            .map(name => GlobalContextPathElement(name))
                            .toSeq
                            .appended(ProjectPathElement(project))

                        val localContextParts = parent
                          .replace(lc, "")
                          .split("_")
                          .filter(_.nonEmpty)
                          .map(str => LocalContextPathElement(str))
                          .toSeq
                          .prepended(LocalContextPathElement(parentLocalContextName))

                        globalContextParts.concat(localContextParts)
                      })
                      .getOrElse(Seq.empty)
                    parts
                  })
              })
          )
          .getOrElse(Future.successful(Seq()))
      }
      case _                => Seq.empty.future
    }
  }

  private def generateParentCandidates(parentParts: Seq[String]): Set[String] = {
    @tailrec
    def act(current: Seq[String], res: Seq[String], previousItems: Seq[String]): Seq[String] = {
      val newRes = if (previousItems.nonEmpty) res.appended(previousItems.mkString("_")) else res
      if (current.isEmpty) {
        newRes
      } else {
        act(current.drop(1), newRes, previousItems.appended(current.head))
      }
    }

    act(parentParts, Seq(), Seq()).toSet
  }
}

sealed trait PathElement {
  def pathElementType: String
  def name: String
}
case class ProjectPathElement(name: String)       extends PathElement {
  override def pathElementType: String = "project"
}
case class GlobalContextPathElement(name: String) extends PathElement {
  override def pathElementType: String = "global_context"
}
case class LocalContextPathElement(name: String)  extends PathElement {
  override def pathElementType: String = "local_context"
}
case class TenantPathElement(name: String)        extends PathElement {
  override def pathElementType: String = "tenant"
}

object PathElement {
  val pathElementWrite: Writes[PathElement] = { p =>
    Json.obj(
      "type" -> p.pathElementType,
      "name" -> p.name
    )
  }
}
