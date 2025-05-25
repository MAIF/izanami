package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.models.{ApiKey, RightLevels, RightTypes, RightUnit}
import play.api.libs.json.JsError.toJson
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class ApiKeyController(
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val keyAuthAction: KeyAuthActionFactory
)(implicit val env: Env)
    extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

  def createApiKey(tenant: String): Action[JsValue] = tenantAuthAction(tenant, RightLevels.Write).async(parse.json) {
    implicit request =>
      ApiKey
        .read(request.body, tenant)
        .map(key => {
          env.datastores.users
            .hasRightFor(
              tenant,
              username = request.user.username,
              rights = key.projects
                .map(p => RightUnit(name = p, rightType = RightTypes.Project, rightLevel = RightLevels.Write)),
              tenantLevel = if (key.admin) Some(RightLevels.Admin) else None
            )
            .flatMap(authorized => {
              if (!authorized) {
                Future.successful(
                  Forbidden(
                    Json.obj(
                      "message" -> s"${request.user} does not have right on one or more of these projects : ${key.projects
                        .mkString(",")} or is not tenant admin (if admin key was required)"
                    )
                  )
                )
              } else {
                env.datastores.apiKeys
                  .createApiKey(
                    key
                      .withNewSecret()
                      .withNewClientId(),
                    request.user
                  )
                  .map(eitherKey => {
                    eitherKey.fold(
                      err => Results.Status(err.status)(Json.toJson(err)),
                      key => Created(Json.toJson(key))
                    )
                  })
              }
            })
        })
        .recoverTotal(jsError => Future.successful(BadRequest(toJson(jsError))))
  }

  def updateApiKey(tenant: String, name: String): Action[JsValue] =
    keyAuthAction(tenant, name, RightLevels.Write).async(parse.json) { implicit request: UserNameRequest[JsValue] =>
      ApiKey
        .read(request.body, tenant)
        .map(key => {
          env.datastores.apiKeys
            .readApiKey(tenant, name)
            .map(maybeKey =>
              maybeKey
                .toRight(NotFound(Json.obj("message" -> s"Key ${name} not found")))
                .map(oldKey => (key.projects.filter(!oldKey.projects.contains(_)), oldKey.admin != key.admin))
            )
            .flatMap(eitherRightChanges =>
              eitherRightChanges.map {
                case (newProjects, false) if newProjects.isEmpty => Future.successful(true)
                case (newProjects, adminChanged)                 => {
                  env.datastores.users
                    .hasRightFor(
                      tenant,
                      username = request.user.username,
                      rights = newProjects.map(p =>
                        RightUnit(name = p, rightType = RightTypes.Project, rightLevel = RightLevels.Write)
                      ),
                      tenantLevel = if (adminChanged) Some(RightLevels.Admin) else None
                    )
                }
              } match {
                case Left(err)     => Future.successful(Left(err))
                case Right(future) => future.map(Right(_))
              }
            )
            .map(eitherAuthorized =>
              eitherAuthorized.filterOrElse(
                b => b,
                Forbidden(Json.obj("message" -> s"${request.user} does not have right on key projects"))
              )
            )
            .flatMap(e =>
              e.map(_ => {
                env.datastores.apiKeys
                  .updateApiKey(tenant, name, key)
                  .map(eitherName => {
                    eitherName.fold(
                      err => err.toHttpResponse,
                      _ => NoContent
                    )
                  })
              }) fold (r => Future(r), r => r)
            )
        })
        .recoverTotal(jsError => Future.successful(BadRequest(toJson(jsError))))
    }

  def readApiKey(tenant: String): Action[AnyContent] = tenantAuthAction(tenant, RightLevels.Read).async {
    implicit request: UserNameRequest[AnyContent] =>
      env.datastores.apiKeys
        .readApiKeys(tenant, request.user.username)
        .map(keys => Ok(Json.toJson(keys)))
  }

  def deleteApiKey(tenant: String, name: String): Action[AnyContent] =
    (keyAuthAction(tenant, name, RightLevels.Admin)).async { implicit request =>
      env.datastores.apiKeys
        .deleteApiKey(tenant, name)
        .map(either => either.fold(err => Results.Status(err.status)(Json.toJson(err)), key => NoContent))

    }
}
