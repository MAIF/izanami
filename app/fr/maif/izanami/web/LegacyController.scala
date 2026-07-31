package fr.maif.izanami.web

import fr.maif.izanami.datastores.FeaturesDatastore
import fr.maif.izanami.env.Postgresql
import fr.maif.izanami.models.Feature
import fr.maif.izanami.models.RequestContext
import fr.maif.izanami.services.FeatureService
import fr.maif.izanami.utils.FutureEither
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class LegacyController(
    val controllerComponents: ControllerComponents,
    clientKeyAction: =>ClientApiKeyAction,
    featuresDatastore: =>FeaturesDatastore, // FIXME
    postgresql: =>Postgresql, // FIXME use a new dedicated HealthService for this
    featureService: =>FeatureService
)(implicit val ec: ExecutionContext) extends BaseController {

  def healthcheck(): Action[AnyContent] = Action.async {
    postgresql
      .queryOne(
        s"""
         |SELECT 1
         |""".stripMargin
      ) { _ =>
        {
          Some(true)
        }
      }
      .recoverWith(_ => {
        Future.successful(Some(false))
      })
      .map {
        case Some(true) => Ok(Json.obj("database" -> true))
        case _          => InternalServerError(Json.obj("database" -> false))
      }
  }

  def legacyFeature(pattern: String): Action[AnyContent] =
    clientKeyAction.async { implicit request =>
      {
        val ctx = if (request.hasBody) {
          val maybeObject =
            request.body.asJson.flatMap(js => js.asOpt[JsObject])
          val maybeId = maybeObject.flatMap(json => (json \ "id").asOpt[String])

          RequestContext(
            tenant = request.key.tenant,
            user = maybeId.getOrElse(""),
            data = maybeObject.getOrElse(Json.obj())
          )
        } else {
          RequestContext(tenant = request.key.tenant, user = "")
        }
        featuresDatastore
          .findByIdForKeyWithoutCheck(
            request.key.tenant,
            pattern,
            request.key.clientId
          )
          .flatMap {
            case Left(value)          => Future.successful(value.toHttpResponse)
            case Right(Some(feature)) =>
              featureService.writeFeatureForCheckInLegacyFormat(
                feature,
                ctx
              ).value.map {
                case Right(Some(jsValue)) => Ok(jsValue)
                case Right(None)          =>
                  BadRequest(Json.obj(
                    "message" -> s"Feature $pattern is not a legacy compatible feature"
                  ))
                case Left(error) => error.toHttpResponse
              }
            case Right(None) => Future.successful(
                NotFound(Json.obj("message" -> s"No feature $pattern"))
              )
          }
      }
    }

  def legacyFeatures(
      pattern: String = "*",
      page: Int,
      pageSize: Int
  ): Action[AnyContent] = clientKeyAction.async {
    implicit request => {
      val ctx = if (request.hasBody) {
        val maybeObject =
          request.body.asJson.flatMap(js => js.asOpt[JsObject])
        val maybeId = maybeObject.flatMap(json => (json \ "id").asOpt[String])

        RequestContext(
          tenant = request.key.tenant,
          user = maybeId.getOrElse(""),
          data = maybeObject.getOrElse(Json.obj())
        )
      } else {
        RequestContext(tenant = request.key.tenant, user = "")
      }
      featuresDatastore
        .findFeatureMatching(
          request.key.tenant,
          pattern,
          request.key.clientId,
          count = pageSize,
          page = page
        )
        .flatMap { case (totalCount, features) =>
          features.foldLeft(Future.successful(Seq()): Future[Seq[JsObject]])((futureResultList, feature) => {
            futureResultList.flatMap(resultList => {
              featureService.writeFeatureForCheckInLegacyFormat(feature, ctx).value.map {
                case Left(error) => Json.obj("error" -> error.message)
                case Right(None) =>
                  Json.obj("error" -> "This feature is not a legacy feature")
                case Right(Some(json)) => json
              }.map(jsObject => resultList.appended(jsObject))
            })
          }).map(jsons => {
            Json.obj(
              "results" -> jsons,
              "metadata" -> Json.obj(
                "count" -> totalCount,
                "page" -> page,
                "pageSize" -> pageSize,
                "nbPages" -> Math.ceil(totalCount.toFloat / pageSize)
              )
            )
          }).map(jsonResult => Ok(Json.toJson(jsonResult)))
        }
    }
  }
}
