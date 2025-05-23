package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{IzanamiError, SearchFilterError, SearchQueryError}
import fr.maif.izanami.models.{Context, GlobalContext, LocalContext}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.PathElement.pathElementWrite
import fr.maif.izanami.web.SearchController.SearchEntityObject
import play.api.libs.json.{JsObject, Json, Writes}
import play.api.mvc._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

class SearchController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val simpleAuthAction: AuthenticatedAction,
    val tenantRightAction: TenantRightsAction
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext


  private def checkSearchParams(query: String, filter: List[String]): Future[Either[IzanamiError, Unit]] = {
    if (query.isEmpty) {
      return Future.successful(Left(SearchQueryError()))
    }
    if (filter.nonEmpty && !filter.forall(SearchEntityObject.parseSearchEntityType(_).isDefined)) {
      return Future.successful(Left(SearchFilterError()))
    }
    Future.successful(Right())
  }

  def search(query: String, filter: List[String]): Action[AnyContent] = tenantRightAction.async {
    implicit request: UserRequestWithTenantRights[AnyContent] => {
      checkSearchParams(query, filter).flatMap {
        case Left(error) => error.toHttpResponse.future
        case Right(_) =>
          val tenants = request.user.tenantRights.keySet
          Future
            .sequence(
              tenants
                .map(tenant =>
                  env.datastores.search
                    .tenantSearch(tenant, request.user.username, query, filter.map( item => SearchEntityObject.parseSearchEntityType(item)))
                    .map(l =>
                      l.map(t => {
                        (t._1, t._2, t._3, tenant)
                      })
                    )
                )
            )
            .map(l => l.flatten.toList.sortBy(_._3)(Ordering.Double.TotalOrdering.reverse).take(10))
            .flatMap(results => {
              Future.sequence(results.map { case (rowType, rowJson, _, tenant) =>
                buildPath(rowType, rowJson, tenant)
                  .map(pathElements => {
                    val name = (rowJson \ "name").asOpt[String].getOrElse("")
                    val jsonPath =
                      Json.toJson(pathElements.prepended(TenantPathElement(tenant)))(Writes.seq(pathElementWrite))
                    Json.obj(
                      "type" -> rowType,
                      "name" -> name,
                      "path" -> jsonPath,
                      "tenant" -> tenant
                    )
                  })
              })
            })
            .map(r => Ok(Json.toJson(r)))
      }
    }
  }

  def searchForTenant(tenant: String, query: String, filter: List[String]): Action[AnyContent] = simpleAuthAction.async {
    implicit request: UserNameRequest[AnyContent] =>
      checkSearchParams(query, filter).flatMap {
        case Left(error) => error.toHttpResponse.future
        case Right(_) =>
          env.datastores.search
            .tenantSearch(tenant, request.user.username, query, filter.map(item => SearchEntityObject.parseSearchEntityType(item)))
            .flatMap(results => {
              Future.sequence(results.map { case (rowType, rowJson, _) =>
                buildPath(rowType, rowJson, tenant)
                  .map(pathElements => {
                    val jsonPath = Json.toJson(pathElements)(Writes.seq(pathElementWrite))
                    val name = (rowJson \ "name").asOpt[String].getOrElse("")
                    val id = (rowJson \ "id").asOpt[String].getOrElse("")
                    Json.obj(
                      "id" -> id,
                      "type" -> rowType,
                      "name" -> name,
                      "path" -> jsonPath,
                      "tenant" -> tenant
                    )
                  })
              })
            })
            .map(res => Ok(Json.toJson(res)))
      }
  }

  private def buildPath(rowType: String, rowJson: JsObject, tenant: String): Future[Seq[PathElement]] = {
    def createContextPath(contexts: List[Context], hasProject: Option[String]): Seq[PathElement] = {
        contexts match {
          case (g:GlobalContext)::(l:LocalContext)::tail => {
            Seq(GlobalContextPathElement(g.name), ProjectPathElement(l.project), LocalContextPathElement(l.name)).concat(createContextPath(tail, hasProject))
          }
          case (g:GlobalContext)::Nil if hasProject.isDefined => {
            Seq(GlobalContextPathElement(g.name), ProjectPathElement(hasProject.get))
          }
          case (g:GlobalContext)::tail => Seq(GlobalContextPathElement(g.name)).concat(createContextPath(tail, hasProject))
          case (g:LocalContext)::tail => Seq(LocalContextPathElement(g.name)).concat(createContextPath(tail, hasProject))
          case _ => Seq()
      }
    }

    rowType match {
      case "feature"        => Seq(ProjectPathElement((rowJson \ "project").as[String])).future
      case "global_context" | "local_context" => {
        val hasProject = (rowJson\"project").asOpt[String]
        (rowJson \ "parent").asOpt[String].map(parent => {
          env.datastores.featureContext.findParents(tenant, parent)
            .map(contexts => {
              createContextPath(contexts, hasProject=hasProject)
            })
        }).getOrElse(hasProject.map(project => Seq(ProjectPathElement(project))).getOrElse(Seq()).future)
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
object SearchController {
  sealed trait SearchEntityType
  object SearchEntityObject {
    case object Project extends SearchEntityType
    case object Feature extends SearchEntityType
    case object Key extends SearchEntityType
    case object Tag extends SearchEntityType
    case object Script extends SearchEntityType
    case object GlobalContext extends SearchEntityType
    case object LocalContext extends SearchEntityType
    case object Webhook extends SearchEntityType
    def parseSearchEntityType(str: String): Option[SearchEntityType] = {
      Option(str).map(_.toUpperCase).flatMap {
        case "PROJECT" => Some(Project)
        case "FEATURE"      => Some(Feature)
        case "KEY"      => Some(Key)
        case "TAG"      => Some(Tag)
        case "SCRIPT"      => Some(Script)
        case "GLOBAL_CONTEXT"      => Some(GlobalContext)
        case "LOCAL_CONTEXT"      => Some(LocalContext)
        case "WEBHOOK"      => Some(Webhook)
        case _           => None
      }
    }

  }

}
