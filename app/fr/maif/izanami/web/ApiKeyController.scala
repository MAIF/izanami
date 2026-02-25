package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.models.*
import play.api.libs.json.JsError.toJson
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.*

import scala.concurrent.{ExecutionContext, Future}

class ApiKeyController(
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val keyAuthAction: KeyAuthActionFactory,
    val tokenAuthAction: PersonnalAccessTokenKeyAuthActionFactory,
    val pacTenantAuthAction: PersonnalAccessTokenTenantAuthActionFactory
)(implicit val env: Env)
    extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

  def createApiKey(tenant: String): Action[JsValue] = tenantAuthAction(tenant, RightLevel.Write).async(parse.json) {
    implicit request =>
      ApiKey
        .read(request.body, tenant)
        .map(key => {
          env.datastores.users
            .hasRightFor(
              tenant,
              username = request.user.username,
              rights = key.projects
                .map(p => ProjectRightUnit(name = p, rightLevel = ProjectRightLevel.Write)),
              tenantLevel = if (key.admin) Some(RightLevel.Admin) else None
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
    keyAuthAction(tenant, name, RightLevel.Write).async(parse.json) { implicit request: UserNameRequest[JsValue] =>
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
                        ProjectRightUnit(name = p, rightLevel = ProjectRightLevel.Write)
                      ),
                      tenantLevel = if (adminChanged) Some(RightLevel.Admin) else None
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

  def readApiKey(tenant: String): Action[AnyContent] = pacTenantAuthAction(tenant, RightLevel.Read, ReadTenantKeys).async {
    implicit request: UserNameRequest[AnyContent] =>
      env.datastores.apiKeys
        .readApiKeys(tenant, request.user.username)
        .map(keys => Ok(Json.toJson(keys)))
  }

  def deleteApiKey(tenant: String, name: String): Action[AnyContent] =
    (tokenAuthAction(tenant, name, RightLevel.Admin, DeleteKey)).async { implicit request =>
      env.datastores.apiKeys
        .deleteApiKey(tenant, name)
        .map(either => either.fold(err => Results.Status(err.status)(Json.toJson(err)), key => NoContent))

    }
}
