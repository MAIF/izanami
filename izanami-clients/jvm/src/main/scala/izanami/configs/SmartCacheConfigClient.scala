package izanami.configs

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import izanami.Strategy._
import izanami._
import izanami.commons.{PatternsUtil, SmartCacheStrategyHandler}
import izanami.scaladsl.ConfigEvent.{ConfigCreated, ConfigDeleted, ConfigUpdated}
import izanami.scaladsl._
import org.reactivestreams.Publisher
import play.api.libs.json.JsValue

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object SmartCacheConfigClient {

  def apply(clientConfig: ClientConfig,
            underlyingStrategy: ConfigClient,
            fallback: Configs,
            config: SmartCacheStrategy)(implicit izanamiDispatcher: IzanamiDispatcher,
                                        actorSystem: ActorSystem,
                                        materializer: Materializer): SmartCacheConfigClient =
    new SmartCacheConfigClient(clientConfig, underlyingStrategy, fallback, config)
}

class SmartCacheConfigClient(
    clientConfig: ClientConfig,
    underlyingStrategy: ConfigClient,
    fallback: Configs,
    config: SmartCacheStrategy
)(implicit val izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem, val materializer: Materializer)
    extends ConfigClient {

  import izanamiDispatcher.ec

  private implicit val timeout: Timeout = Timeout(10.second)

  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  private def handleFailure[T]: T => PartialFunction[Throwable, Future[T]] =
    commons.handleFailure[T](config.errorStrategy)(_)

  private val smartCacheStrategyHandler = new SmartCacheStrategyHandler[Config](
    izanamiDispatcher,
    config.patterns,
    fetchDatas,
    config,
    fallback.configs.map(c => (c.id, c)).toMap,
    onValueUpdated
  )

  underlyingStrategy
    .configsSource("*")
    .runWith(Sink.foreach {
      case ConfigCreated(eventId, id, c) =>
        smartCacheStrategyHandler.setValues(Seq((id, c)), eventId, triggerEvent = false)
      case ConfigUpdated(eventId, id, c, _) =>
        smartCacheStrategyHandler.setValues(Seq((id, c)), eventId, triggerEvent = false)
      case ConfigDeleted(eventId, id) =>
        smartCacheStrategyHandler.removeValues(Seq(id), eventId, triggerEvent = false)
    })

  private val (queue, internalEventSource) = Source
    .queue[ConfigEvent](100, OverflowStrategy.backpressure)
    .toMat(BroadcastHub.sink(1024))(Keep.both)
    .run()

  def onValueUpdated(updates: Seq[CacheEvent[Config]]): Unit =
    updates.foreach {
      case ValueCreated(k, v)      => queue.offer(ConfigCreated(None, k, v))
      case ValueUpdated(k, v, old) => queue.offer(ConfigUpdated(None, k, v, old))
      case ValueDeleted(k, _)      => queue.offer(ConfigDeleted(None, k))
    }

  override def configs(pattern: Seq[String]): Future[Configs] = {
    val convertedPattern =
      Option(pattern).map(_.map(_.replace(".", ":")).mkString(",")).getOrElse("*")
    smartCacheStrategyHandler
      .getByPattern(convertedPattern)
      .mapTo[Seq[Config]]
      .map(configs => Configs(configs, fallback = fallback.configs))
      .recoverWith(handleFailure(fallback))
  }

  override def config(key: String): Future[JsValue] = {
    require(key != null, "key should not be null")
    val convertedKey: String = key.replace(".", ":")
    smartCacheStrategyHandler
      .get(convertedKey)
      .mapTo[Option[Config]]
      .map(f => f.map(_.value).getOrElse(fallback.get(convertedKey)))
      .recoverWith(handleFailure(fallback.get(convertedKey)))
  }

  override def configsSource(pattern: String): Source[ConfigEvent, NotUsed] =
    underlyingStrategy
      .configsSource(pattern)
      .merge(
        internalEventSource.filter(f => PatternsUtil.matchPattern(pattern)(f.id))
      )

  override def configsStream(pattern: String): Publisher[ConfigEvent] =
    configsSource(pattern).runWith(Sink.asPublisher(fanout = true))

  private def fetchDatas(patterns: Seq[String]): Future[Seq[(String, Config)]] =
    Source(patterns.toList)
      .mapAsync(4) { key =>
        underlyingStrategy.configs(key) map (_.configs.map(f => (f.id, f)))
      }
      .mapConcat(s => s.toList)
      .runWith(Sink.seq)

}
