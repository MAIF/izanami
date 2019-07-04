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

object FallbackConfigStategy {
  def apply(fallback: Configs)(implicit izanamiDispatcher: IzanamiDispatcher,
                               materializer: Materializer): FallbackConfigStategy =
    new FallbackConfigStategy(fallback)
}

class FallbackConfigStategy(fallback: Configs)(implicit val izanamiDispatcher: IzanamiDispatcher,
                                               val materializer: Materializer)
    extends ConfigClient {

  import izanamiDispatcher.ec

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
