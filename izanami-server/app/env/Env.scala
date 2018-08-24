package env

import akka.actor.ActorSystem
import controllers.AssetsFinder
import domains.script.{GlobalScriptService, ScriptExecutionContext}
import play.api.libs.ws.WSClient
import play.api.{Environment, Logger, Mode}

import scala.concurrent.Future
import scala.util.Random

case class Env(
    izanamiConfig: IzanamiConfig,
    environment: Environment,
    actorSystem: ActorSystem,
    wSClient: WSClient,
    assetsFinder: AssetsFinder
) {

  val env: String = izanamiConfig.mode.getOrElse(environment.mode match {
    case Mode.Dev  => "dev"
    case Mode.Prod => "prod"
    case Mode.Test => "test"
  })

  def isPlayDevMode = environment.mode == Mode.Dev

  Logger.info(s"Starting izanami with $env mode")
//  val sharedKey: String = izanamiConfig.claim.sharedKey

  def hash = Random.nextInt(100000)

  def getFile(path: String) = environment.getFile(path)

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

  implicit val scriptExecutionContext: ScriptExecutionContext =
    ScriptExecutionContext(actorSystem)
}
