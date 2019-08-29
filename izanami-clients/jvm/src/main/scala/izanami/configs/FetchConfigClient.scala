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
import izanami._
import org.reactivestreams.Publisher
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.Failure

object FetchConfigClient {
  def apply(client: HttpClient,
            clientConfig: ClientConfig,
            fallback: Configs,
            errorStrategy: ErrorStrategy,
            autocreate: Boolean,
            events: Source[IzanamiEvent, NotUsed],
            cuDConfigClient: CUDConfigClient)(
      implicit izanamiDispatcher: IzanamiDispatcher,
      actorSystem: ActorSystem,
      materializer: Materializer
  ): FetchConfigClient =
    new FetchConfigClient(client, clientConfig, fallback, errorStrategy, autocreate, events, cuDConfigClient)
}

private[configs] class FetchConfigClient(
    client: HttpClient,
    clientConfig: ClientConfig,
    fallback: Configs,
    errorStrategy: ErrorStrategy,
    autocreate: Boolean,
    events: Source[IzanamiEvent, NotUsed],
    override val cudConfigClient: CUDConfigClient
)(implicit val izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem, val materializer: Materializer)
    extends ConfigClient {

  import client._
  import izanamiDispatcher.ec
  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  private def handleFailure[T]: T => PartialFunction[Throwable, Future[T]] =
    commons.handleFailure[T](errorStrategy)(_)(actorSystem)

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

  override def configs(pattern: Seq[String]): Future[Configs] = {
    val convertedPattern =
      Option(pattern).map(_.map(_.replace(".", ":")).mkString(",")).getOrElse("*")
    val query = Seq("pattern" -> convertedPattern)
    client
      .fetchPages("/api/configs", query)
      .map { json =>
        val configs = Configs.fromJson(json, fallback.configs)
        if (autocreate) {
          val toCreate: Seq[Config] = fallback.filterWith(pattern).configs.filterNot(configs.configs.contains)
          Future
            .traverse(toCreate) { c =>
              cudConfigClient.createConfig(c)
            }
            .onComplete {
              case Failure(e) => logger.error("Error autocreating configs: ", e)
              case _          =>
            }
        }
        configs
      }
  }.recoverWith(handleFailure(fallback))

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
          if (autocreate) {
            fallback.configs.find(_.id == key).foreach { c =>
              cudConfigClient
                .createConfig(c)
            }
          }
          FastFuture.successful(fallback.get(convertedKey))
        case (code, body) =>
          val message = s"Error getting config, code=$code, response=$body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
      }
      .recoverWith(handleFailure(fallback.get(convertedKey)))
  }

  override def configsSource(pattern: String): Source[ConfigEvent, NotUsed] = {
    val matchP = PatternsUtil.matchPattern(Option(pattern).getOrElse("*")) _
    configsSource
      .filter(e => matchP(e.id))
  }

  override def configsStream(pattern: String): Publisher[ConfigEvent] =
    configsSource(pattern).runWith(Sink.asPublisher(true))

}
