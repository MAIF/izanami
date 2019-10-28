package controllers

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import domains.{AuthorizedPattern, Key}
import domains.user.{User, UserContext, UserService}
import env.Env
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

  lazy val _config = _env.izanamiConfig.filter match {
    case env.Default(config) => config
    case _                   => throw new RuntimeException("Wrong config")
  }
  lazy val cookieName           = _config.cookieClaim
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
              case User(_, _, _, Some(password), _, _) if password === Sha.hexSha512(auth.password) =>
                val token: String = buildToken(user)

                Ok(Json.toJson(user).as[JsObject] - "password")
                  .withCookies(Cookie(name = cookieName, value = token))
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
                val user: User = User(id = userId,
                                      name = userId,
                                      email = s"$userId@admin.fr",
                                      password = None,
                                      admin = true,
                                      authorizedPattern = AuthorizedPattern("*"))

                val token: String = buildToken(user)

                Ok(Json.toJson(user).as[JsObject] ++ Json.obj("changeme" -> true))
                  .withCookies(Cookie(name = cookieName, value = token))
              }
              case _ =>
                Forbidden
            }
            .mapError(_ => InternalServerError(""))
      }
  }

  private def buildToken(user: User) =
    JWT
      .create()
      .withIssuer(_config.issuer)
      .withClaim("name", user.name)
      .withClaim("user_id", user.id)
      .withClaim("email", user.email)
      .withClaim("izanami_authorized_patterns", user.authorizedPattern) // FIXME à voir si on doit mettre une liste???
      .withClaim("izanami_admin", user.admin.toString)
      .sign(algorithm)

  def logout() = Action { _ =>
    Redirect(s"${_env.baseURL}/login").withCookies(Cookie(name = cookieName, value = "", maxAge = Some(0)))
  }
}
