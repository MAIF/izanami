package controllers

import com.auth0.jwt.algorithms.Algorithm
import domains.Key
import domains.user.{IzanamiUser, User, UserContext, UserService}
import env.{DefaultFilter, Env}
import libs.crypto.Sha
import libs.http.HttpContext
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._
import zio.ZIO

case class Auth(userId: String, password: String)

object Auth {
  implicit val format = Json.format[Auth]
}

class AuthController(_env: Env, cc: ControllerComponents)(implicit R: HttpContext[UserContext])
    extends AbstractController(cc) {

  import cats.implicits._
  import domains.user.UserNoPasswordInstances._
  import libs.http._

  lazy val _config: DefaultFilter = _env.izanamiConfig.filter match {
    case env.Default(config) => config
    case _                   => throw new RuntimeException("Wrong config")
  }
  lazy val algorithm: Algorithm = Algorithm.HMAC512(_config.sharedKey)

  def authenticate: Action[JsValue] = Action.asyncZio[UserContext](parse.json) { req =>
    val auth: Auth = req.body.as[Auth]
    UserService
      .getByIdWithoutPermissions(Key(auth.userId))
      .mapError(_ => InternalServerError(""))
      .flatMap {
        case Some(user: User) =>
          ZIO.succeed {
            user match {
              case IzanamiUser(_, _, _, Some(password), _, _, _) if password === Sha.hexSha512(auth.password) =>
                val token: String = User.buildToken(user, _config.issuer, algorithm)
                if (user.temporary) {
                  Ok(Json.toJson(user).as[JsObject] - "password" ++ Json.obj("changeme" -> true))
                    .withCookies(Cookie(name = _env.cookieName, value = token))
                } else {
                  Ok(Json.toJson(user).as[JsObject] - "password")
                    .withCookies(Cookie(name = _env.cookieName, value = token))
                }
              case _ => Forbidden
            }
          }
        case None => ZIO.succeed(Forbidden)
      }
  }
}
