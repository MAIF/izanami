package controllers

import java.security.MessageDigest

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.google.common.base.Charsets
import controllers.actions.SecuredAuthContext
import domains.Key
import domains.config.{ConfigContext, ConfigService}
import env.IzanamiConfig
import org.apache.commons.codec.binary.Hex
import libs.logs.IzanamiLogger
import play.api.libs.json._
import play.api.mvc._
import zio.Runtime

class SpringConfigController(izanamiConfig: IzanamiConfig,
                             AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                             val cc: ControllerComponents)(implicit system: ActorSystem, R: Runtime[ConfigContext])
    extends AbstractController(cc) {

  import libs.http._

  val digester = MessageDigest.getInstance("SHA-256")

  def byteToHexString(bytes: Array[Byte]): String = String.valueOf(Hex.encodeHex(bytes))

  def raw(rootKey: String, appName: String, profileName: String): Action[Unit] = AuthAction.asyncZio(parse.empty) {
    ctx =>
      val appConfigKey     = Key(s"$rootKey:$appName:$profileName:spring-config")
      val profileConfigKey = Key(s"$rootKey:spring-profiles:$profileName:spring-config")
      val globalConfigKey  = Key(s"$rootKey:spring-globals:spring-config")

      val host: String = ctx.request.headers
        .get(izanamiConfig.headerHost)
        .orElse(ctx.request.headers.get("Host"))
        .getOrElse("localhost:9000")

      val result = for {
        app     <- ConfigService.getById(appConfigKey)
        profile <- ConfigService.getById(profileConfigKey)
        global  <- ConfigService.getById(globalConfigKey)
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
                      Json.obj(
                        "name"   -> s"${ctx.request.protocol}://$host/api/configs/$rootKey:$profileName:$appName:spring-config",
                        "source" -> c
                    )
                  ),
                profile
                  .map(_.value)
                  .collect { case o: JsObject => o }
                  .map(
                    c =>
                      Json.obj(
                        "name"   -> s"${ctx.request.protocol}://$host/api/configs/$rootKey:spring-profiles:$profileName:spring-config",
                        "source" -> c
                    )
                  ),
                global
                  .map(_.value)
                  .collect { case o: JsObject => o }
                  .map(
                    c =>
                      Json.obj(
                        "name"   -> s"${ctx.request.protocol}://$host/api/configs/$rootKey:spring-globals:spring-config",
                        "source" -> c
                    )
                  )
              ).flatten
            )
            val payload = Json.obj(
              "name"            -> s"$appName",
              "profiles"        -> Json.arr(s"://$profileName"),
              "label"           -> JsNull,
              "state"           -> JsNull,
              "propertySources" -> propertySources
            )
            IzanamiLogger.debug(s"Spring config request for $rootKey, $appName, $profileName: \n $payload")
            val version: String = byteToHexString(digester.digest(Json.stringify(payload).getBytes(Charsets.UTF_8)))
            Ok(payload ++ Json.obj("version" -> version))
          }
        }
      }
      result.mapError { _ =>
        InternalServerError("")
      }
  }
}
