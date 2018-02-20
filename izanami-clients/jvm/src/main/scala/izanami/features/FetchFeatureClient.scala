package izanami.features

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import izanami._
import izanami.scaladsl.{FeatureClient, Features}
import org.reactivestreams.Publisher
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future
import scala.util.control.NonFatal

object FetchFeatureClient {
  def apply(underlying: RawFetchFeatureClient, fallback: Features)(
      implicit izanamiDispatcher: IzanamiDispatcher,
      actorSystem: ActorSystem,
      materializer: Materializer
  ): FetchFeatureClient =
    new FetchFeatureClient(underlying, fallback)
}

private[features] class FetchFeatureClient(
    underlying: FeatureClient,
    fallback: Features
)(implicit val izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem, val materializer: Materializer)
    extends FeatureClient {

  import izanamiDispatcher.ec
  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  private def handleFailure[T](v: T): PartialFunction[Throwable, T] = {
    case NonFatal(_) => v
  }

  override def features(pattern: String): Future[Features] =
    underlying
      .features(pattern)
      .recover { handleFailure(fallback) }

  override def features(pattern: String, context: JsObject): Future[Features] =
    underlying
      .features(pattern, context)
      .recover { handleFailure(fallback) }

  override def checkFeature(key: String): Future[Boolean] =
    checkFeature(key, Json.obj())

  override def checkFeature(key: String, context: JsObject): Future[Boolean] =
    underlying
      .checkFeature(key, context)
      .recover(handleFailure(fallback.isActive(key)))

  override def featuresSource(pattern: String): Source[izanami.FeatureEvent, NotUsed] =
    underlying.featuresSource(pattern)

  override def featuresStream(pattern: String): Publisher[izanami.FeatureEvent] =
    underlying.featuresStream(pattern)

}
