package store.elastic

import akka.actor.ActorSystem
import elastic.api.Elastic
import elastic.client.{ElasticClient => AkkaClient}
import env.ElasticConfig
import play.api.Logger
import play.api.libs.json.JsValue

object ElasticClient {

  def apply(elasticConfig: ElasticConfig, actorSystem: ActorSystem): Elastic[JsValue] = {
    Logger.info(s"Creating elastic client $elasticConfig")
    (
      for {
        user     <- elasticConfig.user
        password <- elasticConfig.password
      } yield
        AkkaClient[JsValue](elasticConfig.host,
                            elasticConfig.port,
                            elasticConfig.scheme,
                            user = user,
                            password = password)(actorSystem)
    ) getOrElse AkkaClient[JsValue](elasticConfig.host, elasticConfig.port, elasticConfig.scheme)(actorSystem)
  }

}
