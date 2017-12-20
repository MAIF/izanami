package izanami.javadsl

import java.lang
import java.util.function
import java.util.function.Consumer

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.stream.javadsl.{AsPublisher, Sink, Source}
import io.vavr.{Function0, Tuple, Tuple2}
import io.vavr.collection.List
import io.vavr.concurrent.Future
import io.vavr.control.{Option, Try}
import izanami._
import org.reactivecouchbase.json.{JsObject, JsValue, Json}
import org.reactivestreams.Publisher

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object IzanamiClient {

  def client(actorSystem: ActorSystem, config: ClientConfig): IzanamiClient =
    new IzanamiClient(config,
                      izanami.scaladsl.IzanamiClient(config)(actorSystem))
}

class IzanamiClient(clientConfig: ClientConfig,
                    underlyingClient: izanami.scaladsl.IzanamiClient) {

  import underlyingClient._

  def featureClient(strategy: izanami.Strategy): FeatureClient =
    featureClient(strategy, Features.features())

  def featureClient(strategy: izanami.Strategy,
                    fallback: ClientConfig => Features): FeatureClient =
    new FeatureClient(
      underlyingClient.actorSystem,
      underlyingClient.featureClient(strategy, fallback.andThen(_.underlying)))

  def configClient(strategy: izanami.Strategy): ConfigClient =
    new ConfigClient(
      underlyingClient.actorSystem,
      clientConfig,
      underlyingClient.configClient(strategy,
                                    izanami.scaladsl.Configs(Seq.empty)))

  def configClient(strategy: izanami.Strategy,
                   fallback: Configs): ConfigClient =
    new ConfigClient(
      underlyingClient.actorSystem,
      clientConfig,
      underlyingClient.configClient(strategy, fallback.underlying))

  def experimentClient(strategy: izanami.Strategy): ExperimentsClient =
    new ExperimentsClient(underlyingClient.experimentClient(strategy))

  def experimentClient(strategy: izanami.Strategy,
                       fallback: izanami.Experiments): ExperimentsClient =
    new ExperimentsClient(underlyingClient.experimentClient(strategy, fallback))

  def proxy(): Proxy = new Proxy(underlyingClient.proxy())

  def proxy(featureClient: FeatureClient,
            configClient: ConfigClient,
            experimentClient: ExperimentsClient): Proxy =
    new Proxy(
      underlyingClient.proxy(
        featureClient.underlying,
        configClient.underlying,
        experimentClient.underlying
      )
    )

}

///////////////////////////////////////////////////////////////////////
////////////////////////////   Proxy   ////////////////////////////////
///////////////////////////////////////////////////////////////////////

case class Proxy(underlying: izanami.scaladsl.Proxy)(
    implicit actorSystem: ActorSystem,
    izanamiDispatcher: IzanamiDispatcher) {

  import izanamiDispatcher._
  import JsonConv._
  import Vavr._

  def withFeatureClient(featureClient: FeatureClient) =
    this.copy(
      underlying = underlying.withFeatureClient(featureClient.underlying))
  def withConfigClient(configClient: ConfigClient) =
    this.copy(underlying = underlying.withConfigClient(configClient.underlying))
  def withExperimentsClient(experimentsClient: ExperimentsClient) =
    this.copy(
      underlying =
        underlying.withExperimentsClient(experimentsClient.underlying))
  def withFeaturePattern(pattern: String) =
    this.copy(underlying = underlying.withFeaturePattern(pattern))
  def withConfigPattern(pattern: String) =
    this.copy(underlying = underlying.withFeaturePattern(pattern))
  def withExperimentPattern(pattern: String) =
    this.copy(underlying = underlying.withExperimentPattern(pattern))

  def statusAndJsonResponse(pattern: String,
                            context: JsObject,
                            userId: String): Future[Tuple2[Integer, JsValue]] =
    statusAndJsonResponse(Option.some(context), Option.some(userId))

  def statusAndJsonResponse(pattern: String): Future[Tuple2[Integer, JsValue]] =
    statusAndJsonResponse(Option.none[JsObject](), Option.none[String]())

  def statusAndJsonResponse(
      context: Option[JsObject],
      userId: Option[String]): Future[Tuple2[Integer, JsValue]] = {
    underlying
      .statusAndJsonResponse(
        context.toScala().map(_.toScala().as[play.api.libs.json.JsObject]),
        userId.toScala()
      )
      .map {
        case (status, json) => Tuple.of(Int.box(status), json.toJava())
      }
  }.toJava

  def statusAndStringResponse(context: JsObject,
                              userId: String): Future[Tuple2[Integer, String]] =
    statusAndStringResponse(Option.some(context), Option.some(userId))

  def statusAndStringResponse(): Future[Tuple2[Integer, String]] =
    statusAndStringResponse(Option.none[JsObject](), Option.none[String]())

  def statusAndStringResponse(
      context: Option[JsObject],
      userId: Option[String]): Future[Tuple2[Integer, String]] =
    statusAndJsonResponse(context, userId).map {
      new function.Function[Tuple2[Integer, JsValue], Tuple2[Integer, String]] {
        override def apply(
            t: Tuple2[Integer, JsValue]): Tuple2[Integer, String] =
          Tuple.of(t._1, Json.stringify(t._2))
      }
    }

  def markVariantDisplayed(experimentId: String,
                           clientId: String): Future[Tuple2[Integer, JsValue]] =
    underlying
      .markVariantDisplayed(experimentId, clientId)
      .map {
        case (status, json) => Tuple.of(Int.box(status), json.toJava())
      }
      .toJava
  def markVariantDisplayedStringResponse(
      experimentId: String,
      clientId: String): Future[Tuple2[Integer, String]] =
    underlying
      .markVariantDisplayedStringResp(experimentId, clientId)
      .map {
        case (status, resp) => Tuple.of(Int.box(status), resp)
      }
      .toJava

  def markVariantWon(experimentId: String,
                     clientId: String): Future[Tuple2[Integer, JsValue]] =
    underlying
      .markVariantWon(experimentId, clientId)
      .map {
        case (status, json) => Tuple.of(Int.box(status), json.toJava())
      }
      .toJava
  def markVariantWonStringResponse(
      experimentId: String,
      clientId: String): Future[Tuple2[Integer, String]] =
    underlying
      .markVariantWonStringResp(experimentId, clientId)
      .map {
        case (status, resp) => Tuple.of(Int.box(status), resp)
      }
      .toJava

}
///////////////////////////////////////////////////////////////////////
/////////////////////////   Features   ////////////////////////////////
///////////////////////////////////////////////////////////////////////

object Features {

  @annotation.varargs
  def features(features: Feature*): ClientConfig => Features = { clientConfig =>
    Features(izanami.scaladsl.Features(clientConfig, features, Seq.empty))
  }

  def empty(): ClientConfig => Features = { clientConfig =>
    Features(izanami.scaladsl.Features(clientConfig, Seq.empty, Seq.empty))
  }

  def features(
      features: java.lang.Iterable[Feature]): ClientConfig => Features = {
    import scala.collection.JavaConverters._
    clientConfig =>
      Features(
        izanami.scaladsl
          .Features(clientConfig, features.asScala.toSeq, Seq.empty)
      )
  }

  def feature(key: String, active: Boolean) = DefaultFeature(key, active)

  def parseJson(json: String): ClientConfig => Features =
    izanami.scaladsl.Features.parseJson(json).andThen(Features.apply _)

}

case class Registration(underlying: izanami.scaladsl.Registration) {
  def onComplete(cb: Try[Done] => Unit): Unit =
    underlying.onComplete {
      case Success(done) => cb.apply(Try.success(done))
      case Failure(e)    => cb.apply(Try.failure(e))
    }
  def close(): Unit = underlying.close()
}

case class Features(underlying: izanami.scaladsl.Features) {
  import JsonConv._
  def isActive(key: String): Boolean =
    underlying.isActive(key)

  def tree(): JsObject =
    underlying.tree().toJava().asObject()

}

class FeatureClient(actorSystem: ActorSystem,
                    val underlying: scaladsl.FeatureClient)(
    implicit izanamiDispatcher: IzanamiDispatcher
) {

  import Vavr._
  import JsonConv._
  import izanamiDispatcher.ec

  def features(pattern: String): Future[Features] =
    underlying.features(pattern).map(Features.apply _).toJava

  def features(pattern: String, context: JsObject): Future[Features] =
    underlying
      .features(pattern, context.toScala().as[play.api.libs.json.JsObject])
      .map(Features.apply _)
      .toJava

  def featureOrElse[T](key: String,
                       ok: Function0[T],
                       ko: Function0[T]): Future[T] =
    checkFeature(key).map {
      new java.util.function.Function[java.lang.Boolean, T] {
        override def apply(t: lang.Boolean): T = t match {
          case java.lang.Boolean.TRUE  => ok.get()
          case java.lang.Boolean.FALSE => ko.get()
        }
      }
    }

  def featureOrElse[T](key: String,
                       context: JsObject,
                       ok: Function0[T],
                       ko: Function0[T]): Future[T] =
    checkFeature(key, context).map {
      new java.util.function.Function[java.lang.Boolean, T] {
        override def apply(t: lang.Boolean): T = t match {
          case java.lang.Boolean.TRUE  => ok.get()
          case java.lang.Boolean.FALSE => ko.get()
        }
      }
    }

  def checkFeature(key: String): Future[java.lang.Boolean] =
    underlying.checkFeature(key).map(Boolean.box).toJava

  def checkFeature(key: String, context: JsObject): Future[java.lang.Boolean] =
    underlying
      .checkFeature(key, context.toScala().as[play.api.libs.json.JsObject])
      .map(Boolean.box)
      .toJava

  def onFeatureChanged(key: String)(cb: Consumer[Feature]): Registration =
    Registration(underlying.onFeatureChanged(key) { f =>
      cb.accept(f)
      ()
    })

  def onEvent(pattern: String)(cb: Consumer[FeatureEvent]): Registration =
    Registration(underlying.onEvent(pattern) { f =>
      cb.accept(f)
      ()
    })

  def featuresSource(pattern: String): Source[FeatureEvent, NotUsed] =
    underlying.featuresSource(pattern).asJava

  def featuresStream(pattern: String): Publisher[FeatureEvent] =
    underlying.featuresStream(pattern)

}

///////////////////////////////////////////////////////////////////////
//////////////////////////   Configs   ////////////////////////////////
///////////////////////////////////////////////////////////////////////

object Config {
  import JsonConv._
  def config(id: String, value: JsValue): Config =
    Config(izanami.scaladsl.Config(id, value.toScala()))
}

case class Config(underlying: izanami.scaladsl.Config) {
  import JsonConv._
  def value(): JsValue = underlying.value.toJava()
  def id(): String = underlying.id
}

object Configs {
  import JsonConv._
  import scala.collection.JavaConverters._

  @annotation.varargs
  def configs(configs: Config*): Configs = Configs(
    izanami.scaladsl.Configs(
      configs.map(
        c =>
          izanami.scaladsl
            .Config(c.id(), c.value().toScala())
      )
    )
  )

  def configs(configs: java.lang.Iterable[Config]): Configs =
    Configs(
      izanami.scaladsl.Configs(
        configs.asScala.toSeq
          .map(c => izanami.scaladsl.Config(c.id(), c.value().toScala()))
      )
    )

  def empty(): Configs = Configs(
    izanami.scaladsl.Configs(
      Seq.empty
    )
  )

  def config(id: String, value: JsValue): Config = Config.config(id, value)

  def parseJson(jsonStr: String): Configs =
    Configs(izanami.scaladsl.Configs.parseJson(jsonStr))
}

case class Configs(underlying: izanami.scaladsl.Configs) {
  import JsonConv._
  def config(key: String) = underlying.get(key).toJava()
  def tree(): JsObject = underlying.tree().toJava().asObject()
}

sealed trait ConfigEvent {
  def id: String
}

object ConfigEvent {
  case class ConfigCreated(id: String, config: Config) extends ConfigEvent
  case class ConfigUpdated(id: String, config: Config, oldConfig: Config)
      extends ConfigEvent
  case class ConfigDeleted(id: String) extends ConfigEvent
}

class ConfigClient(actorSystem: ActorSystem,
                   clientConfig: ClientConfig,
                   val underlying: izanami.scaladsl.ConfigClient)(
    implicit izanamiDispatcher: IzanamiDispatcher
) {

  import Vavr._
  import JsonConv._
  import ConfigEvent._

  import izanamiDispatcher.ec

  private val materializer =
    ActorMaterializer(
      ActorMaterializerSettings(actorSystem).withDispatcher(
        clientConfig.dispatcher))(actorSystem)

  def configs(pattern: String = "*"): Future[Configs] =
    underlying
      .configs(pattern)
      .map { c =>
        Configs(c)
      }
      .toJava

  def config(key: String): Future[JsValue] =
    underlying
      .config(key)
      .map { c =>
        c.toJava()
      }
      .toJava

  def onConfigChanged(key: String)(cb: Consumer[JsValue]): Registration =
    Registration(
      underlying.onConfigChanged(key)(json => cb.accept(json.toJava()))
    )

  def onEvent(pattern: String = "*", cb: Consumer[ConfigEvent]): Registration =
    Registration(
      underlying
        .onEvent(pattern) { c =>
          cb.accept(convertEvent(c))
          ()
        }
    )

  def configsSource(pattern: String): Source[ConfigEvent, NotUsed] =
    underlying
      .configsSource(pattern)
      .map { convertEvent }
      .asJava

  private def convertEvent(evt: izanami.scaladsl.ConfigEvent): ConfigEvent =
    evt match {
      case izanami.scaladsl.ConfigEvent.ConfigCreated(id, c) =>
        ConfigCreated(id, Config(c))
      case izanami.scaladsl.ConfigEvent.ConfigUpdated(id, c, o) =>
        ConfigUpdated(id, Config(c), Config(o))
      case izanami.scaladsl.ConfigEvent.ConfigDeleted(id) => ConfigDeleted(id)
    }

  def configsStream(pattern: String = "*"): Publisher[ConfigEvent] =
    configsSource(pattern)
      .runWith(Sink.asPublisher(AsPublisher.WITH_FANOUT), materializer)

}

///////////////////////////////////////////////////////////////////////
/////////////////////////   Experiments   /////////////////////////////
///////////////////////////////////////////////////////////////////////

class ExperimentsClient(val underlying: izanami.scaladsl.ExperimentsClient)(
    implicit izanamiDispatcher: IzanamiDispatcher
) {
  import izanamiDispatcher.ec
  import Vavr._
  import scala.collection.JavaConverters._
  import JsonConv._

  def experiment(id: String): Future[Option[ExperimentClient]] =
    underlying
      .experiment(id)
      .map(
        mayBe =>
          mayBe
            .map(e => Option.of(new ExperimentClient(e)))
            .getOrElse(Option.none())
      )
      .toJava

  def list(pattern: String): Future[List[ExperimentClient]] =
    underlying
      .list(pattern)
      .map(exps => List.ofAll(exps.map(e => new ExperimentClient(e)).asJava))
      .toJava

  def tree(pattern: String, clientId: String): Future[JsValue] =
    underlying.tree(pattern, clientId).map(_.toJava()).toJava

}

class ExperimentClient(underlying: izanami.scaladsl.ExperimentClient)(
    implicit izanamiDispatcher: IzanamiDispatcher) {
  import izanamiDispatcher.ec
  import Vavr._

  def getVariantFor(clientId: String): Future[Option[Variant]] =
    underlying
      .getVariantFor(clientId)
      .map(_.map(v => Option.of(v)).getOrElse(Option.none()))
      .toJava

  def markVariantDisplayed(
      clientId: String): Future[ExperimentVariantDisplayed] =
    underlying.markVariantDisplayed(clientId).toJava

  def markVariantWon(clientId: String): Future[ExperimentVariantWon] =
    underlying.markVariantWon(clientId).toJava

}

private[javadsl] object JsonConv {

  implicit class JsontoJava(json: play.api.libs.json.JsValue) {
    def toJava(): JsValue =
      Json.parse(play.api.libs.json.Json.stringify(json))
  }

  implicit class JsontoScala(json: JsValue) {
    def toScala(): play.api.libs.json.JsValue =
      play.api.libs.json.Json.parse(Json.stringify(json))
  }
}

private[javadsl] object Vavr {

  implicit class ToScalaOption[T](o: Option[T]) {
    def toScala(): scala.Option[T] =
      if (o.isDefined) {
        Some(o.get())
      } else {
        None
      }
  }

  implicit class ToJavaFuture[T](f: concurrent.Future[T]) {
    def toJava(implicit ec: ExecutionContext) = {
      val promise = io.vavr.concurrent.Promise.make[T]()
      f.onComplete {
        case Success(s) => promise.trySuccess(s)
        case Failure(e) => promise.tryFailure(e)
      }
      promise.future()
    }
  }
}
