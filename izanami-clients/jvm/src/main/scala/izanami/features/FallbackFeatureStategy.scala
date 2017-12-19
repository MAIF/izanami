package izanami.features

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import akka.stream.scaladsl.Source
import izanami.{Feature, FeatureEvent, IzanamiDispatcher}
import izanami.commons.IzanamiException
import izanami.scaladsl._
import org.reactivestreams.{Publisher, Subscriber}
import play.api.libs.json.{JsObject, JsValue}

import scala.concurrent.Future

object FallbackFeatureStategy {
  def apply(fallback: Features)(
      implicit izanamiDispatcher: IzanamiDispatcher,
      materializer: Materializer): FallbackFeatureStategy =
    new FallbackFeatureStategy(fallback)
}

class FallbackFeatureStategy(fallback: Features)(
    implicit val izanamiDispatcher: IzanamiDispatcher,
    val materializer: Materializer)
    extends FeatureClient {

  import izanamiDispatcher.ec

  override def features(pattern: String): Future[Features] =
    Future {
      fallback.copy()
    }

  override def features(pattern: String, context: JsObject): Future[Features] =
    Future {
      fallback.copy()
    }

  override def checkFeature(key: String): Future[Boolean] =
    Future {
      fallback.isActive(key)
    }

  override def checkFeature(key: String, context: JsObject): Future[Boolean] =
    Future {
      fallback.isActive(key)
    }

  override def onEvent(pattern: String)(
      cb: FeatureEvent => Unit): Registration =
    FakeRegistration()

  override def featuresSource(pattern: String): Source[FeatureEvent, NotUsed] =
    Source.failed(IzanamiException("Not implemented"))

  override def featuresStream(pattern: String): Publisher[FeatureEvent] =
    new Publisher[FeatureEvent] {
      override def subscribe(s: Subscriber[_ >: FeatureEvent]): Unit =
        s.onError(IzanamiException("Not implemented"))
    }

}
