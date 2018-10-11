package controllers

import java.security.MessageDigest
import java.util.Base64

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.effect.Effect
import com.google.common.base.Charsets
import controllers.actions.SecuredAuthContext
import domains.Key
import domains.config.ConfigService
import libs.functional.EitherTSyntax
import play.api.libs.json._
import play.api.mvc._

class SpringConfigController[F[_]: Effect](configStore: ConfigService[F],
                                           system: ActorSystem,
                                           AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                           val cc: ControllerComponents)
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  val digester = MessageDigest.getInstance("MD5")
  val encoder  = Base64.getEncoder

  def raw(rootKey: String, appName: String, profileName: String): Action[Unit] = AuthAction.asyncF(parse.empty) { ctx =>
    val appConfigKey     = Key(s"$rootKey:$appName:$profileName:spring-config")
    val profileConfigKey = Key(s"$rootKey:spring-profiles:$profileName:spring-config")
    val globalConfigKey  = Key(s"$rootKey:spring-globals:spring-config")

    val result = for {
      app     <- configStore.getById(appConfigKey)
      profile <- configStore.getById(profileConfigKey)
      global  <- configStore.getById(globalConfigKey)
    } yield {
      (app, profile, global) match {
        case (None, None, None) => NotFound(Json.obj("error" -> "No config found !"))
        case _ => {
          val propertySources = JsArray(
            Seq(
              app
                .map(_.value)
                .collect { case o: JsObject => o }
                .map(
                  c =>
                    Json.obj("name" -> s"izanami://configs/$rootKey/$profileName/$appName/spring-config", "source" -> c)
                ),
              profile
                .map(_.value)
                .collect { case o: JsObject => o }
                .map(
                  c =>
                    Json.obj("name"   -> s"izanami://configs/$rootKey/spring-profiles/$profileName/spring-config",
                             "source" -> c)
                ),
              global
                .map(_.value)
                .collect { case o: JsObject => o }
                .map(
                  c => Json.obj("name" -> s"izanami://configs/$rootKey/spring-globals//spring-config", "source" -> c)
                )
            ).flatten
          )
          val payload = Json.obj(
            "name"            -> s"$appName",
            "profiles"        -> Json.arr(s"$profileName"),
            "label"           -> JsNull,
            "state"           -> JsNull,
            "propertySources" -> propertySources
          )
          val version =
            new String(encoder.encode(digester.digest(Json.stringify(payload).getBytes(Charsets.UTF_8))),
                       Charsets.UTF_8)
          Ok(payload ++ Json.obj("version" -> version))
        }
      }
    }

    result
  }
}
