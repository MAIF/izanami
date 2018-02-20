package izanami.configs

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import izanami.commons.{HttpClient, IzanamiException, PatternsUtil}
import izanami.scaladsl.ConfigEvent.{ConfigCreated, ConfigDeleted, ConfigUpdated}
import izanami.scaladsl._
import izanami.{ClientConfig, IzanamiDispatcher, IzanamiEvent}
import org.reactivestreams.Publisher
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Future
import scala.util.control.NonFatal

object FetchConfigClient {
  def apply(underlying: ConfigClient, fallback: Configs)(
      implicit izanamiDispatcher: IzanamiDispatcher,
      actorSystem: ActorSystem,
      materializer: Materializer
  ): FetchConfigClient =
    new FetchConfigClient(underlying, fallback)
}

private[configs] class FetchConfigClient(
    underlying: ConfigClient,
    fallback: Configs
)(implicit val izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem, val materializer: Materializer)
    extends ConfigClient {

  import izanamiDispatcher.ec
  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  private def handleFailure[T](v: T): PartialFunction[Throwable, T] = {
    case NonFatal(_) => v
  }

  override def configs(pattern: String): Future[Configs] =
    underlying
      .configs(pattern)
      .recover(handleFailure(fallback))

  override def config(key: String): Future[JsValue] = {
    val convertedKey: String = key.replace(".", ":")
    underlying
      .config(convertedKey)
      .recover(handleFailure(fallback.get(convertedKey)))
  }

  override def configsSource(pattern: String): Source[ConfigEvent, NotUsed] =
    underlying.configsSource(pattern)

  override def configsStream(pattern: String): Publisher[ConfigEvent] =
    underlying.configsStream(pattern)

}
