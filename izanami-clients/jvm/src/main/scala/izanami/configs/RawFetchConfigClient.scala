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

object RawFetchConfigClient {
  def apply(client: HttpClient, clientConfig: ClientConfig, fallback: Configs, events: Source[IzanamiEvent, NotUsed])(
      implicit izanamiDispatcher: IzanamiDispatcher,
      actorSystem: ActorSystem,
      materializer: Materializer
  ): RawFetchConfigClient =
    new RawFetchConfigClient(client, clientConfig, fallback, events)
}

private[configs] class RawFetchConfigClient(
    client: HttpClient,
    clientConfig: ClientConfig,
    fallback: Configs,
    events: Source[IzanamiEvent, NotUsed]
)(implicit val izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem, val materializer: Materializer)
    extends ConfigClient {

  import client._
  import izanamiDispatcher.ec
  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  private val configsSource = events
    .filter(_.domain == "Config")
    .map {
      case evt @ IzanamiEvent(_id, key, t, _, payload, _, _) if t == "CONFIG_CREATED" =>
        Config.format
          .reads(payload)
          .fold(
            err => {
              client.actorSystem.log
                .error(s"Error deserializing config event {}: {}", evt, err)
              None
            },
            c => Some(ConfigCreated(Some(_id), key, c))
          )
      case evt @ IzanamiEvent(_id, key, t, _, payload, Some(oldValue), _) if t == "CONFIG_UPDATED" =>
        val event = for {
          newOne <- Config.format.reads(payload)
          oldOne <- Config.format.reads(oldValue)
        } yield ConfigUpdated(Some(_id), key, newOne, oldOne)

        event.fold(
          err => {
            logger.error(s"Error deserializing config event {}: {}", evt, err)
            None
          },
          e => Some(e)
        )
      case IzanamiEvent(_id, key, t, _, _, _, _) if t == "CONFIG_DELETED" =>
        Some(ConfigDeleted(Some(_id), key))
      case e =>
        client.actorSystem.log.error(s"Event don't match {}", e)
        None
    }
    .mapConcat(_.toList)

  override def configs(pattern: String): Future[Configs] = {
    val convertedPattern =
      Option(pattern).map(_.replace(".", ":")).getOrElse("*")
    val query = Seq("pattern" -> convertedPattern)
    client
      .fetchPages("/api/configs", query)
      .map { json =>
        Configs.fromJson(json, fallback.configs)
      }
  }

  override def config(key: String): Future[JsValue] = {
    require(key != null, "key should not be null")
    val convertedKey = key.replace(".", ":")
    client
      .fetch(s"/api/configs/$convertedKey")
      .flatMap {
        case (code, body) if code == StatusCodes.OK =>
          Json
            .parse(body)
            .validate[Config]
            .fold(
              err => FastFuture.failed(IzanamiException(s"Error parsing config $body, err = $err")),
              c => {
                FastFuture.successful(c.value)
              }
            )
        case (code, _) if code == StatusCodes.NotFound =>
          FastFuture.successful(fallback.get(convertedKey))
        case (code, body) =>
          val message = s"Error getting config, code=$code, response=$body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
      }
  }

  override def configsSource(pattern: String): Source[ConfigEvent, NotUsed] = {
    val matchP = PatternsUtil.matchPattern(Option(pattern).getOrElse("*")) _
    configsSource
      .filter(e => matchP(e.id))
  }

  override def configsStream(pattern: String): Publisher[ConfigEvent] =
    configsSource(pattern).runWith(Sink.asPublisher(true))

}
