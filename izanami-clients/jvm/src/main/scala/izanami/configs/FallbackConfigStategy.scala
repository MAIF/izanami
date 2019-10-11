package izanami.configs

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import izanami.IzanamiDispatcher
import izanami.commons.IzanamiException
import izanami.scaladsl._
import org.reactivestreams.{Publisher, Subscriber}
import play.api.libs.json.JsValue

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicReference

object FallbackConfigStategy {
  def apply(fallback: Configs)(implicit izanamiDispatcher: IzanamiDispatcher,
                               materializer: Materializer): FallbackConfigStategy =
    new FallbackConfigStategy(fallback)
}

class FallbackConfigStategy(f: Configs)(
    implicit val izanamiDispatcher: IzanamiDispatcher,
    val materializer: Materializer
) extends ConfigClient {

  import izanamiDispatcher.ec

  val fallbackRef = new AtomicReference[Configs](f)

  def fallback: Configs = fallbackRef.get()

  override val cudConfigClient: CUDConfigClient = new CUDConfigClient {

    override val ec: ExecutionContext = izanamiDispatcher.ec

    override def createConfig(id: String, config: JsValue): Future[JsValue] = {
      fallbackRef.set(
        fallback.copy(
          configs = fallback.configs :+ Config(id, config)
        )
      )
      FastFuture.successful(config)
    }

    override def createConfig(config: Config): Future[Config] = {
      fallbackRef.set(
        fallback.copy(
          configs = fallback.configs :+ config
        )
      )
      FastFuture.successful(config)
    }

    override def importConfigs(configs: Seq[Config]): Future[Unit] = {
      fallbackRef.set(
        fallback.copy(
          configs = fallback.configs ++ configs
        )
      )
      FastFuture.successful(())
    }  

    override def updateConfig(oldId: String, id: String, config: JsValue): Future[JsValue] = {
      fallbackRef.set(
        fallback.copy(
          configs = fallback.configs.filterNot { _.id == oldId } :+ Config(id, config)
        )
      )
      FastFuture.successful(config)
    }

    override def deleteConfig(id: String): Future[Unit] = {
      fallbackRef.set(
        fallback.copy(
          configs = fallback.configs.filterNot { _.id == id }
        )
      )
      FastFuture.successful(())
    }
  }

  override def configs(pattern: String): Future[Configs] =
    Future {
      fallback
    }

  override def configs(pattern: Seq[String]): Future[Configs] =
    Future {
      fallback
    }

  override def config(key: String): Future[JsValue] =
    Future {
      fallback.get(key)
    }

  override def onEvent(pattern: String)(cb: ConfigEvent => Unit): Registration =
    FakeRegistration()

  override def configsSource(pattern: String): Source[ConfigEvent, NotUsed] =
    Source.failed(IzanamiException("Not implemented"))

  override def configsStream(pattern: String): Publisher[ConfigEvent] =
    new Publisher[ConfigEvent] {
      override def subscribe(s: Subscriber[_ >: ConfigEvent]): Unit =
        s.onError(IzanamiException("Not implemented"))
    }
}
