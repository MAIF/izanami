package izanami.configs

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import izanami._
import izanami.commons.{PatternsUtil, SmartCacheStrategyActor}
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

  import akka.pattern._
  import izanami.commons.SmartCacheStrategyActor._
  import izanamiDispatcher.ec

  implicit val timeout = Timeout(1.second)

  private val ref = actorSystem.actorOf(
    SmartCacheStrategyActor.props(
      izanamiDispatcher,
      config.patterns,
      fetchDatas,
      config,
      fallback.configs.map(c => (c.id, c)).toMap,
      onValueUpdated
    )
  )

  underlyingStrategy
    .configsSource("*")
    .runWith(Sink.foreach {
      case ConfigCreated(id, c) =>
        ref ! SetValues(Seq((id, c)), triggerEvent = false)
      case ConfigUpdated(id, c, _) =>
        ref ! SetValues(Seq((id, c)), triggerEvent = false)
      case ConfigDeleted(id) =>
        ref ! RemoveValues(Seq(id), triggerEvent = false)
    })

  private val (queue, internalEventSource) = Source
    .queue[ConfigEvent](100, OverflowStrategy.backpressure)
    .toMat(BroadcastHub.sink(1024))(Keep.both)
    .run()

  def onValueUpdated(updates: Seq[CacheEvent[Config]]): Unit =
    updates.foreach {
      case ValueCreated(k, v)      => queue.offer(ConfigCreated(k, v))
      case ValueUpdated(k, v, old) => queue.offer(ConfigUpdated(k, v, old))
      case ValueDeleted(k, _)      => queue.offer(ConfigDeleted(k))
    }
  override def configs(pattern: String): Future[Configs] = {
    val convertedPattern: String =
      Option(pattern).map(_.replace(".", ":")).getOrElse("*")
    (ref ? GetByPattern(convertedPattern))
      .mapTo[Seq[Config]]
      .map(configs => Configs(configs, fallback = fallback.configs))
  }

  override def config(key: String): Future[JsValue] = {
    require(key != null, "key should not be null")
    val convertedKey: String = key.replace(".", ":")
    (ref ? Get(convertedKey))
      .mapTo[Option[Config]]
      .map(f => f.map(_.value).getOrElse(fallback.get(convertedKey)))
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
