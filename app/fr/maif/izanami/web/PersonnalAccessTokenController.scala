package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.models.PersonnalAccessToken.{
  completePersonnalAccessTokenWrites,
  consultationTokenWrites,
  personnalAccessTokenCreationRequestRead
}
import fr.maif.izanami.models.{
  AllRights,
  LimitedRights,
  PersonnalAccessTokenCreationRequest
}
import play.api.libs.json.{JsValue, Json, Writes}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class PersonnalAccessTokenController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val authAction: AuthenticatedAction,
    val tenantRightAction: TenantRightsAction,
    val detailledAuthAction: DetailledAuthAction
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

  def readTokens(user: String): Action[AnyContent] = tenantRightAction.async {
    implicit request =>
      {
        if (user != request.user.username && !request.user.admin) {
          Future.successful(
            Forbidden(
              Json.obj(
                "error" -> "You can only read your own tokens unless you're admin"
              )
            )
          )
        } else {
          env.datastores.personnalAccessToken
            .listUserTokens(user)
            .map(tokens =>
              Ok(Json.toJson(tokens)(Writes.seq(consultationTokenWrites)))
            )
        }
      }
  }

  def updateToken(user: String, tokenId: String): Action[JsValue] =
    detailledAuthAction.async(parse.json) { implicit request =>
      {
        val queryId = Try(UUID.fromString(tokenId)).toOption
        val maybeId = (request.body \ "id")
          .asOpt[String]
          .flatMap(str => Try(UUID.fromString(str)).toOption);
        if (maybeId.isEmpty || !maybeId.exists(id => queryId.contains(id))) {
          Future.successful(
            BadRequest(
              Json.obj(
                "error" -> "Mismatch token id: you must provide the same id in the body as in the url"
              )
            )
          )
        } else if (user != request.user.username && !request.user.admin) {
          Future.successful(
            Forbidden(
              Json.obj(
                "error" -> "You can't update user users token unless you're admin"
              )
            )
          )
        } else
          {
            request.body
              .asOpt[PersonnalAccessTokenCreationRequest](
                personnalAccessTokenCreationRequestRead(user)
              )
              .map(t => {
                val missingRightErrors = t.rights match {
                  case AllRights                           => Set.empty[String]
                  case LimitedRights(rights, globalRights) => {
                    val missingTenantRightErrors = rights
                      .flatMap { (tenant, rights) =>
                        rights.map(right => (tenant, right))
                      }
                      .filter { (tenant, right) =>
                        {
                          right.requiredCreationRight.exists(rr =>
                            !request.user.hasRightForTenant(tenant, rr)
                          )
                        }
                      }
                      .map { (tenant, right) =>
                        s"${right.name} (tenant ${tenant})"
                      }
                    val missingGlobalRightErrors = globalRights
                      .filter(gr => {
                        gr.requiredCreationRight
                          .exists(rr => !request.user.hasRight(rr))
                      })
                      .map(r => r.name)

                    missingTenantRightErrors.concat(missingGlobalRightErrors)
                  }
                }
                if (missingRightErrors.nonEmpty) {
                  Future.successful(
                    Forbidden(
                      s"You don't have enough rights to create a token with rights: ${missingRightErrors.mkString(",")}"
                    )
                  )
                } else {
                  env.datastores.personnalAccessToken
                    .updateAccessToken(id = maybeId.get, user = user, data = t)
                    .map {
                      case Right(token) =>
                        Ok(Json.toJson(token)(consultationTokenWrites))
                      case Left(err) => err.toHttpResponse
                    }
                }
              })
          }.getOrElse(
            Future.successful(
              BadRequest(Json.obj("error" -> "Bad body format"))
            )
          )
      }
    }

  def createToken(user: String): Action[JsValue] =
    detailledAuthAction.async(parse.json) { implicit request =>
      if (user != request.user.username) {
        Future.successful(
          BadRequest(
            Json.obj(
              "error" -> "Mismatch users: you can only create tokens for yourself"
            )
          )
        )
      } else
        {
          request.body
            .asOpt[PersonnalAccessTokenCreationRequest](
              personnalAccessTokenCreationRequestRead(request.user.username)
            )
            .map(t => {
              val missingRightErrors = t.rights match {
                case AllRights                           => Set.empty[String]
                case LimitedRights(rights, globalRights) => {
                  val missingTenantRightErrors = rights
                    .flatMap { (tenant, rights) =>
                      rights.map(right => (tenant, right))
                    }
                    .filter { (tenant, right) =>
                      {
                        right.requiredCreationRight.exists(rr =>
                          !request.user.hasRightForTenant(tenant, rr)
                        )
                      }
                    }
                    .map { (tenant, right) =>
                      s"${right.name} (tenant ${tenant})"
                    }
                  val missingGlobalRightErrors = globalRights
                    .filter(gr => {
                      gr.requiredCreationRight
                        .exists(rr => !request.user.hasRight(rr))
                    })
                    .map(r => r.name)

                  missingTenantRightErrors.concat(missingGlobalRightErrors)
                }
              }
              if (missingRightErrors.nonEmpty) {
                Future.successful(
                  Forbidden(
                    s"You don't have enough rights to create a token with rights: ${missingRightErrors.mkString(",")}"
                  )
                )
              } else {
                env.datastores.personnalAccessToken.createAcessToken(t).map {
                  case Right(token) =>
                    Created(
                      Json.toJson(token)(completePersonnalAccessTokenWrites)
                    )
                  case Left(err) => err.toHttpResponse
                }
              }
            })
        }.getOrElse(
          Future.successful(BadRequest(Json.obj("error" -> "Bad body format")))
        )
    }

  def deleteToken(user: String, id: String): Action[AnyContent] =
    tenantRightAction.async { implicit request =>
      if (user != request.user.username && !request.user.admin) {
        Future.successful(
          Forbidden(
            Json.obj(
              "error" -> "You can't update user users token unless you're admin"
            )
          )
        )
      } else {
        env.datastores.personnalAccessToken.deleteAcessToken(id, user).map {
          case Right(_)  => NoContent
          case Left(err) =>
            err.toHttpResponse
        }
      }
    }

}
