package izanami.configs

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.util.Timeout
import com.google.common.cache.{Cache, CacheBuilder}
import izanami.Strategy.FetchWithCacheStrategy
import izanami.scaladsl._
import izanami._
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object FetchWithCacheConfigClient {
  def apply(
      clientConfig: ClientConfig,
      fallback: Configs,
      underlyingStrategy: ConfigClient,
      cacheConfig: FetchWithCacheStrategy
  )(implicit izanamiDispatcher: IzanamiDispatcher,
    actorSystem: ActorSystem,
    materializer: Materializer): FetchWithCacheConfigClient =
    new FetchWithCacheConfigClient(clientConfig,
                                   fallback,
                                   underlyingStrategy,
                                   cacheConfig,
                                   underlyingStrategy.cudConfigClient)
}

private[configs] class FetchWithCacheConfigClient(
    clientConfig: ClientConfig,
    fallback: Configs,
    underlyingStrategy: ConfigClient,
    cacheConfig: FetchWithCacheStrategy,
    override val cudConfigClient: CUDConfigClient
)(implicit val izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem, val materializer: Materializer)
    extends ConfigClient {

  import actorSystem.dispatcher

  implicit val timeout = Timeout(10.second)

  private val logger = Logging(actorSystem, this.getClass.getName)
  private val cache: Cache[String, Seq[Config]] = CacheBuilder
    .newBuilder()
    .maximumSize(cacheConfig.maxElement)
    .expireAfterWrite(cacheConfig.duration.toMillis, TimeUnit.MILLISECONDS)
    .build[String, Seq[Config]]()

  override def configs(pattern: Seq[String]): Future[Configs] = {
    val convertedPattern =
      Option(pattern).map(_.map(_.replace(".", ":")).mkString(",")).getOrElse("*")
    Option(cache.getIfPresent(convertedPattern)) match {
      case Some(configs) => FastFuture.successful(Configs(configs))
      case None =>
        val futureConfigs = underlyingStrategy.configs(convertedPattern)
        futureConfigs.onComplete {
          case Success(c) => cache.put(convertedPattern, c.configs)
          case Failure(e) => logger.error(e, "Error fetching configs")
        }
        futureConfigs
    }
  }

  override def config(key: String) = {
    require(key != null, "key should not be null")
    val convertedKey: String = key.replace(".", ":")
    Option(cache.getIfPresent(convertedKey)) match {
      case Some(configs) =>
        FastFuture.successful(configs.find(_.id == convertedKey).map(_.value).getOrElse(Json.obj()))
      case None =>
        val futureConfig: Future[Configs] =
          underlyingStrategy.configs(convertedKey)
        futureConfig.onComplete {
          case Success(configs) =>
            cache.put(convertedKey, configs.configs)
          case Failure(e) =>
            logger.error(e, "Error fetching features")
        }
        futureConfig
          .map(
            _.configs
              .find(_.id == convertedKey)
              .map(c => c.value)
              .getOrElse(Json.obj())
          )
    }
  }

  override def configsSource(pattern: String) =
    underlyingStrategy.configsSource(pattern)

  override def configsStream(pattern: String) =
    underlyingStrategy.configsStream(pattern)
}
