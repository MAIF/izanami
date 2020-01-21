package controllers

import com.auth0.jwt.algorithms.Algorithm
import domains.{AuthorizedPattern, Key}
import domains.user.{IzanamiUser, User, UserContext, UserService}
import env.{DefaultFilter, Env}
import libs.crypto.Sha
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._
import store.Query
import zio.{Runtime, ZIO}

case class Auth(userId: String, password: String)

object Auth {
  implicit val format = Json.format[Auth]
}

class AuthController(_env: Env, cc: ControllerComponents)(implicit R: Runtime[UserContext])
    extends AbstractController(cc) {

  import domains.user.UserNoPasswordInstances._
  import cats.implicits._
  import libs.http._

  lazy val _config: DefaultFilter = _env.izanamiConfig.filter match {
    case env.Default(config) => config
    case _                   => throw new RuntimeException("Wrong config")
  }
  lazy val algorithm: Algorithm = Algorithm.HMAC512(_config.sharedKey)

  def authenticate: Action[JsValue] = Action.asyncZio[UserContext](parse.json) { req =>
    val auth: Auth = req.body.as[Auth]

    UserService
      .getById(Key(auth.userId))
      .mapError(_ => InternalServerError(""))
      .flatMap {
        case Some(user: User) =>
          ZIO.succeed {
            user match {
              case IzanamiUser(_, _, _, password, _, _) if password === Sha.hexSha512(auth.password) =>
                val token: String = User.buildToken(user, _config.issuer, algorithm)

                Ok(Json.toJson(user).as[JsObject] - "password")
                  .withCookies(Cookie(name = _env.cookieName, value = token))
              case _ =>
                Forbidden
            }
          }
        case None =>
          UserService
            .count(Query.oneOf("*"))
            .map {
              case count
                  if count === 0 && auth.userId === _env.izanamiConfig.user.initialize.userId && auth.password === _env.izanamiConfig.user.initialize.password => {
                val userId = _env.izanamiConfig.user.initialize.userId
                val user: User = IzanamiUser(id = userId,
                                             name = userId,
                                             email = s"$userId@admin.fr",
                                             password = "",
                                             admin = true,
                                             authorizedPattern = AuthorizedPattern("*"))

                val token: String = User.buildToken(user, _config.issuer, algorithm)

                Ok(Json.toJson(user).as[JsObject] ++ Json.obj("changeme" -> true))
                  .withCookies(Cookie(name = _env.cookieName, value = token))
              }
              case _ =>
                Forbidden
            }
            .mapError(_ => InternalServerError(""))
      }
  }

}
