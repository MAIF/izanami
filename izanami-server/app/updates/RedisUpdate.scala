package updates

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import domains.config.ConfigStore.ConfigKey
import domains.config.{Config, ConfigStore}
import domains.events.impl.BasicEventStore
import env.RedisConfig
import play.api.libs.json.Json
import redis.RedisClientMasterSlaves
import store.redis.{RedisClient, RedisJsonDataStore}

case class OldConfig(id: ConfigKey, value: String)

object OldConfig {
  val format = Json.format[OldConfig]
}
object RedisUpdate extends App {

  implicit val actorSystem  = ActorSystem()
  implicit val materializer = ActorMaterializer()

  private val redisClient: Option[RedisClientMasterSlaves] =
    RedisClient.redisClient(Some(RedisConfig("localhost", 6379, None, None)), actorSystem)

  private val jsonStore   = RedisJsonDataStore(redisClient.get, actorSystem, "izanami:configuration")
  private val configStore = ConfigStore(jsonStore, new BasicEventStore(actorSystem), actorSystem)

  jsonStore
    .getByIdLike(Seq("*"))
    .stream
    .mapAsync(2) { l =>
      val config: OldConfig = OldConfig.format.reads(l).get

      configStore.update(config.id,
                         config.id,
                         Config(
                           config.id,
                           Json.parse(config.value)
                         ))
    }
    .runWith(Sink.foreach(println))

}
