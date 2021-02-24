package store.elastic

import akka.actor.ActorSystem
import elastic.es6.api.{Elastic => Elastic6}
import elastic.es6.client.{ElasticClient => AkkaClient6}
import elastic.es7.api.{Elastic => Elastic7}
import elastic.es7.client.{ElasticClient => AkkaClient7}
import env.ElasticConfig
import libs.logs.IzanamiLogger
import play.api.libs.json.JsValue

object Elastic6Client {

  def apply(elasticConfig: ElasticConfig, actorSystem: ActorSystem): Elastic6[JsValue] = {
    if (elasticConfig.version != 6)
      IzanamiLogger.info(s"Warning: ${elasticConfig.version} configured but Elasticsearch 6 client will be used")
    IzanamiLogger.info(s"Creating elastic client $elasticConfig for Elasticsearch 6")
    (
      for {
        user     <- elasticConfig.user
        password <- elasticConfig.password
      } yield AkkaClient6[JsValue](
        elasticConfig.host,
        elasticConfig.port,
        elasticConfig.scheme,
        user = user,
        password = password
      )(actorSystem)
    ) getOrElse AkkaClient6[JsValue](elasticConfig.host, elasticConfig.port, elasticConfig.scheme)(actorSystem)
  }

}

object Elastic7Client {

  def apply(elasticConfig: ElasticConfig, actorSystem: ActorSystem): Elastic7[JsValue] = {
    if (elasticConfig.version != 7)
      IzanamiLogger.info(s"Warning: ${elasticConfig.version} configured but Elasticsearch 7 client will be used")
    IzanamiLogger.info(s"Creating elastic client $elasticConfig for Elasticsearch 7")
    (
      for {
        user     <- elasticConfig.user
        password <- elasticConfig.password
      } yield AkkaClient7[JsValue](
        elasticConfig.host,
        elasticConfig.port,
        elasticConfig.scheme,
        user = user,
        password = password
      )(actorSystem)
    ) getOrElse AkkaClient7[JsValue](elasticConfig.host, elasticConfig.port, elasticConfig.scheme)(actorSystem)
  }

}
