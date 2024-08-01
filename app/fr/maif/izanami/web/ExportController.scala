package fr.maif.izanami.web

import akka.stream.scaladsl.Source
import akka.util.ByteString
import fr.maif.izanami.env.Env
import fr.maif.izanami.models.Feature.lightweightFeatureWrite
import fr.maif.izanami.models.{LightWeightFeature, RightLevels}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ExportController.{exportResultWrites, parseExportedType}
import play.api.http.HttpEntity
import play.api.libs.Files
import play.api.libs.json._
import play.api.mvc.{Action, BaseController, ControllerComponents, MultipartFormData, ResponseHeader, Result}

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.Try

class ExportController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val authAction: TenantAuthActionFactory
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

  def exportTenantData(tenant: String): Action[JsValue] = authAction(tenant, RightLevels.Admin).async(parse.json) {
    implicit request =>
      {
        ExportController.tenantExportRequestReads
          .reads(request.body)
          .asEither
          .map(req => {
            env.datastores.exportDatastore.exportTenantData(tenant, req)
          })
          .fold(
            _ => Future.successful(BadRequest(Json.obj("message" -> "Bad body format"))),
            futureResult =>
              futureResult.map(jsons => {
                Result(
                  header =
                    ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "export.ndjson")),
                  body = HttpEntity.Streamed(
                    akka.stream.scaladsl.Source.single(ByteString(jsons.mkString("\n"), "UTF-8")),
                    None,
                    Some("application/x-ndjson")
                  )
                )
              })
          )
      }
  }
}

object ExportController {
  sealed trait ExportedType {
    def order: Int
    def table: String
  }
  case object ScriptType         extends ExportedType {
    override def order: Int = 0

    override def table: String = "wasm_script_configurations"
  }
  case object ProjectType        extends ExportedType {
    override def order: Int    = 1
    override def table: String = "projects"
  }
  case object KeyType            extends ExportedType {
    override def order: Int    = 2
    override def table: String = "apikeys"
  }
  case object KeyProjectType     extends ExportedType {
    override def order: Int    = 3
    override def table: String = "apikeys_projects"
  }
  case object GlobalContextType  extends ExportedType {
    override def order: Int    = 4
    override def table: String = "global_feature_contexts"
  }
  case object LocalContextType   extends ExportedType {
    override def order: Int    = 5
    override def table: String = "feature_contexts"
  }
  case object WebhookType        extends ExportedType {
    override def order: Int    = 6
    override def table: String = "webhooks"
  }
  case object TagType            extends ExportedType {
    override def order: Int    = 7
    override def table: String = "tags"
  }
  case object FeatureType        extends ExportedType {
    override def order: Int    = 8
    override def table: String = "features"
  }
  case object FeatureTagType     extends ExportedType {
    override def order: Int    = 9
    override def table: String = "features_tags"
  }
  case object OverloadType       extends ExportedType {
    override def order: Int    = 10
    override def table: String = "feature_contexts_strategies"
  }
  case object ProjectRightType   extends ExportedType {
    override def order: Int    = 11
    override def table: String = "users_projects_rights"
  }
  case object KeyRightType       extends ExportedType {
    override def order: Int = 12

    override def table: String = "users_keys_rights"
  }
  case object WebhookRightType   extends ExportedType {
    override def order: Int = 13

    override def table: String = "users_webhooks_rights"
  }
  case object WebhookFeatureType extends ExportedType {
    override def order: Int = 14

    override def table: String = "webhooks_features"
  }
  case object WebhookProjectType extends ExportedType {
    override def order: Int    = 15
    override def table: String = "webhooks_projects"
  }

  def parseExportedType(typestr: String): Option[ExportedType] = {
    Option(typestr match {
      case "project"            => ProjectType
      case "feature"            => FeatureType
      case "tag"                => TagType
      case "feature_tag"        => FeatureTagType
      case "overload"           => OverloadType
      case "local_context"      => LocalContextType
      case "global_context"     => GlobalContextType
      case "key"                => KeyType
      case "apikey_project"     => KeyProjectType
      case "webhook"            => WebhookType
      case "webhook_feature"    => WebhookFeatureType
      case "webhook_project"    => WebhookProjectType
      case "user_webhook_right" => WebhookRightType
      case "project_right"      => ProjectRightType
      case "key_right"          => KeyRightType
      case "script"             => ScriptType
      case _                    => null
    })
  }

  val exportRequestReads: Reads[ExportRequest] = json => {
    (json \ "tenants")
      .asOpt[Map[String, TenantExportRequest]](Reads.map(tenantExportRequestReads))
      .map(projects => JsSuccess(ExportRequest(projects)))
      .getOrElse(JsError("Bad body format"))
  }

  val tenantExportRequestReads: Reads[TenantExportRequest] = json => {
    (for (
      projects: ExportList <- (json \ "allProjects")
                                .asOpt[Boolean]
                                .flatMap(isAllProjects =>
                                  if (isAllProjects) Some(ExportAllItems)
                                  else (json \ "projects").asOpt[Set[String]].map(set => ExportItemList(set))
                                );
      keys: ExportList     <- (json \ "allKeys")
                                .asOpt[Boolean]
                                .flatMap(isAllProjects =>
                                  if (isAllProjects) Some(ExportAllItems)
                                  else (json \ "keys").asOpt[Set[String]].map(set => ExportItemList(set))
                                );
      webhooks: ExportList <- (json \ "allWebhooks")
                                .asOpt[Boolean]
                                .flatMap(isAllProjects =>
                                  if (isAllProjects) Some(ExportAllItems)
                                  else (json \ "webhooks").asOpt[Set[String]].map(set => ExportItemList(set))
                                )
    ) yield JsSuccess(TenantExportRequest(projects, keys, webhooks))).getOrElse(JsError("Bad body format"))
  }

  val exportResultWrites: Writes[ExportResult] = exportResult => {
    Json.obj(
      "tenants" -> Json.toJson(exportResult.tenants)(Writes.map(tenantExportResultWrites))
    )
  }

  val tenantExportResultWrites: Writes[TenantExportResult] = exportResult => {
    Json.obj("projects" -> Json.toJson(exportResult.projects)(Writes.map(projectExportResultWrites)))
  }

  val projectExportResultWrites: Writes[ProjectExportResult] = exportResult => {
    Json.obj("features" -> Json.toJson(exportResult.features)(Writes.list(lightweightFeatureWrite)))
  }

  case class ExportRequest(tenants: Map[String, TenantExportRequest]) {}
  case class TenantExportRequest(projects: ExportList, keys: ExportList, webhooks: ExportList)

  sealed trait ExportList
  case object ExportAllItems                    extends ExportList
  case class ExportItemList(items: Set[String]) extends ExportList

  case class ExportResult(
      tenants: Map[String, TenantExportResult]
  )

  case class TenantExportResult(
      projects: Map[String, ProjectExportResult]
  )

  case class ProjectExportResult(features: List[LightWeightFeature])

}
