package izanami.features

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.util.Timeout
import com.google.common.cache.{Cache, CacheBuilder}
import izanami.Strategy.FetchWithCacheStrategy
import izanami._
import izanami.scaladsl.{FeatureClient, Features}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object FetchWithCacheFeatureClient {
  def apply(
      clientConfig: ClientConfig,
      underlyingStrategy: FeatureClient,
      cacheConfig: FetchWithCacheStrategy
  )(implicit izanamiDispatcher: IzanamiDispatcher,
    actorSystem: ActorSystem,
    materializer: Materializer): FetchWithCacheFeatureClient =
    new FetchWithCacheFeatureClient(clientConfig,
                                    underlyingStrategy,
                                    cacheConfig)
}

private[features] case class CacheKey(key: String, context: JsObject)

private[features] class FetchWithCacheFeatureClient(
    clientConfig: ClientConfig,
    underlyingStrategy: FeatureClient,
    cacheConfig: FetchWithCacheStrategy
)(implicit val izanamiDispatcher: IzanamiDispatcher,
  actorSystem: ActorSystem,
  val materializer: Materializer)
    extends FeatureClient {

  import izanamiDispatcher.ec

  implicit val timeout = Timeout(1.second)

  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  private val cache: Cache[CacheKey, Seq[Feature]] = CacheBuilder
    .newBuilder()
    .maximumSize(cacheConfig.maxElement)
    .expireAfterWrite(cacheConfig.duration.toMillis, TimeUnit.MILLISECONDS)
    .build[CacheKey, Seq[Feature]]()

  override def features(pattern: String) = features(pattern, Json.obj())

  override def features(pattern: String, context: JsObject) = {
    val convertedPattern =
      Option(pattern).map(_.replace(".", ":")).getOrElse("*")
    Option(cache.getIfPresent(CacheKey(convertedPattern, context))) match {
      case Some(features) =>
        FastFuture.successful(Features(clientConfig, features))
      case None =>
        val futureFeatures =
          underlyingStrategy.features(convertedPattern, context)
        futureFeatures.onComplete {
          case Success(f) =>
            cache.put(CacheKey(convertedPattern, context), f.featuresSeq)
          case Failure(e) => logger.error(e, "Error fetching features")
        }
        futureFeatures
    }
  }

  override def checkFeature(key: String) = checkFeature(key, Json.obj())

  override def checkFeature(key: String, context: JsObject) = {
    require(key != null, "Key should not be null")
    val convertedKey = key.replace(".", ":")
    Option(cache.getIfPresent(CacheKey(convertedKey, context))) match {
      case Some(features) =>
        FastFuture.successful(
          features.find(_.id == convertedKey).exists(_.isActive(clientConfig)))
      case None =>
        val futureFeatures = underlyingStrategy.features(convertedKey, context)
        futureFeatures.onComplete {
          case Success(features) =>
            cache.put(CacheKey(convertedKey, context), features.featuresSeq)
          case Failure(e) =>
            logger.error(e, "Error fetching features")
        }
        futureFeatures.map(
          _.featuresSeq
            .find(_.id == convertedKey)
            .exists(_.isActive(clientConfig)))
    }
  }

  override def featuresSource(pattern: String) =
    underlyingStrategy.featuresSource(pattern)

  override def featuresStream(pattern: String) =
    underlyingStrategy.featuresStream(pattern)
}
