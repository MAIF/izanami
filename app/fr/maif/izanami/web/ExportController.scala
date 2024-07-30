package fr.maif.izanami.web

import akka.stream.scaladsl.Source
import akka.util.ByteString
import fr.maif.izanami.env.Env
import fr.maif.izanami.models.Feature.lightweightFeatureWrite
import fr.maif.izanami.models.{LightWeightFeature, RightLevels}
import fr.maif.izanami.web.ExportController.exportResultWrites
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc.{Action, BaseController, ControllerComponents, ResponseHeader, Result}

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

  def exportTenantData(tenant: String): Action[JsValue] = authAction(tenant, RightLevels.Admin).async(parse.json) { implicit request =>
    {
      ExportController.tenantExportRequestReads
        .reads(request.body)
        .asEither
        .map(req => {
          env.datastores.exportDatastore.tenantData(tenant, req)
        })
        .fold(
          _ => Future.successful(BadRequest(Json.obj("message" -> "Bad body format"))),
          futureResult =>
            futureResult.map(jsons => {
              Result(
                header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "export.ndjson")),
                body = HttpEntity.Streamed(
                  Source.single(ByteString(jsons.mkString("\n"), "UTF-8")),
                  None,
                  Some("application/x-ndjson")
                )
              )
            })
        )
    }
  }

  def importTenantData(tenant: String): Action[JsValue] = authAction(tenant, RightLevels.Admin).async(parse.multipartFormData) { implicit request =>
    val files: Map[String, URI] = request.body.files.map(f => (f.key, f.ref.path.toUri)).toMap
    files.get("export").toRight().map(uri => {
      Try(scala.io.Source.fromFile(uri)).map(bf => {
        bf.getLines()
          .map(line => {
            val result = Json.parse(line).as[JsObject]
            val objectType = (result \ "_type").asOpt[String]
            (objectType, result)
          })
          .toSeq
          .collect{
            case (Some(t), json) => (t, json)
          }
          .toMap
      }).toEither
    })
  }
}

object ExportController {
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
