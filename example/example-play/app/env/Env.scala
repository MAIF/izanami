package env

import akka.actor.ActorSystem
import controllers.AssetsFinder
import play.api.Environment
import play.api.libs.ws.WSClient

case class Env(environment: Environment, actorSystem: ActorSystem, wSClient: WSClient, assetsFinder: AssetsFinder) {
  def mode = environment.mode

}
