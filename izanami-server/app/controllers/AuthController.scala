package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import controllers.actions.AuthContext
import domains.{AuthorizedPattern, Key}
import domains.user.{User, UserStore}
import env.Env
import libs.crypto.Sha
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._

case class Auth(userId: String, password: String)

object Auth {
  implicit val format = Json.format[Auth]
}

class AuthController(_env: Env,
                     userStore: UserStore,
                     AuthAction: ActionBuilder[AuthContext, AnyContent],
                     system: ActorSystem,
                     cc: ControllerComponents)
    extends AbstractController(cc) {

  import domains.user.UserNoPassword._
  import system.dispatcher

  lazy val _config = _env.izanamiConfig.filter match {
    case env.Default(config) => config
    case _                   => throw new RuntimeException("Wrong config")
  }
  lazy val cookieName           = _config.cookieClaim
  lazy val algorithm: Algorithm = Algorithm.HMAC512(_config.sharedKey)

  def authenticate: Action[JsValue] = Action.async(parse.json) { req =>
    val auth: Auth = req.body.as[Auth]

    userStore.getById(Key(auth.userId)).one.flatMap { maybeUser =>
      {
        maybeUser match {
          case Some(user: User) =>
            FastFuture.successful {
              user match {
                case User(_, _, _, Some(password), _, _) if password == Sha.hexSha512(auth.password) =>
                  val token: String = buildToken(user)

                  Ok(Json.toJson(user).as[JsObject] - "password")
                    .withCookies(Cookie(name = cookieName, value = token))
                case _ =>
                  Forbidden
              }
            }
          case None =>
            userStore
              .count(Seq("*"))
              .map {
                case count
                    if count == 0 && auth.userId == _env.izanamiConfig.user.initialize.userId && auth.password == _env.izanamiConfig.user.initialize.password => {
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
        }
      }
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
