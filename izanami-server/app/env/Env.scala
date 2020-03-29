package env

import akka.actor.ActorSystem
import com.codahale.metrics.MetricRegistry
import controllers.AssetsFinder
import libs.logs.IzanamiLogger
import play.api.libs.ws.WSClient
import play.api.{Environment, Mode}
import cats._
import cats.implicits._

import scala.util.Random

object ModeEq {
  implicit val eqMode: Eq[Mode] = Eq.fromUniversalEquals
}

case class Env(
    izanamiConfig: IzanamiConfig,
    environment: Environment,
//    actorSystem: ActorSystem,
    assetsFinder: AssetsFinder
) {

  import ModeEq._

  val env: Mode = izanamiConfig.mode
    .map {
      case "dev"  => Mode.Dev
      case "prod" => Mode.Prod
      case "test" => Mode.Test
    }
    .getOrElse(environment.mode)

  def isPlayDevMode = environment.mode === Mode.Dev

  IzanamiLogger.info(s"Starting izanami with $env mode")
//  val sharedKey: String = izanamiConfig.claim.sharedKey

  def hash = Random.nextInt(100000)

  def getFile(path: String) = environment.getFile(path)

  val cookieName: String = Option(izanamiConfig.filter)
    .collect { case Default(config) => config.cookieClaim }
    .getOrElse("Izanami")

  val contextPath: String = if (izanamiConfig.contextPath.endsWith("/")) {
    izanamiConfig.contextPath.dropRight(1)
  } else {
    izanamiConfig.contextPath
  }
  val baseURL: String = if (izanamiConfig.baseURL.endsWith("/")) {
    izanamiConfig.baseURL.dropRight(1)
  } else {
    izanamiConfig.baseURL
  }
}
