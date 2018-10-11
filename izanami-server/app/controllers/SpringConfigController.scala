package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.effect.Effect
import controllers.actions.SecuredAuthContext
import domains.apikey.Apikey
import domains.config.{Config, ConfigInstances, ConfigService}
import domains.{Import, ImportResult, IsAllowed, Key}
import env.Env
import libs.functional.EitherTSyntax
import libs.patch.Patch
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

import scala.concurrent.Future

class SpringConfigController[F[_]: Effect](configStore: ConfigService[F],
                                           system: ActorSystem,
                                           AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                           val cc: ControllerComponents)
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.functional.syntax._
  import system.dispatcher
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  def raw(rootKey: String, appName: String, profile: String): Action[Unit] = AuthAction.asyncF(parse.empty) { ctx =>
    import ConfigInstances._
    ???
  }

  def tree(rootKey: String, appName: String, profile: String): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    import ConfigInstances._
    ???
  }

}
