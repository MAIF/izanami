package izanami.features

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import izanami.commons.IzanamiException
import izanami.scaladsl._
import izanami.{Feature, FeatureEvent, FeatureType, IzanamiDispatcher}
import org.reactivestreams.{Publisher, Subscriber}
import play.api.libs.json.{JsError, JsObject, JsSuccess, Json}

import scala.concurrent.Future

object FallbackFeatureStategy {
  def apply(fallback: Features)(implicit izanamiDispatcher: IzanamiDispatcher,
                                materializer: Materializer,
                                cudFeatureClient: CUDFeatureClient): FallbackFeatureStategy =
    new FallbackFeatureStategy(fallback)
}

class FallbackFeatureStategy(fallback: Features)(implicit val izanamiDispatcher: IzanamiDispatcher,
                                                 val materializer: Materializer,
                                                 val cudFeatureClient: CUDFeatureClient)
    extends FeatureClient {

  import izanamiDispatcher.ec

  override def createFeature(
      id: String,
      feature: Feature,
      parameters: Option[JsObject]
  ): Future[Feature] = {
    fallback.copy(
      featuresSeq = fallback.featuresSeq ++ Seq(feature)
    )
    FastFuture.successful(feature)
  }

  override def createJsonFeature(
      id: String,
      enabled: Boolean,
      activationStrategy: FeatureType,
      parameters: Option[JsObject]
  ): Future[Feature] = {
    val payload = Json.obj("id" -> id, "enabled" -> enabled, "activationStrategy" -> activationStrategy.name) ++ parameters
      .map(value => Json.obj("parameters" -> value))
      .getOrElse(Json.obj())

    Feature.reads.reads(payload).asOpt match {
      case None => {
        val message = s"Error creating feature $id : parsingFailed"
        FastFuture.failed(IzanamiException(message))
      }
      case Some(feature) => {
        fallback.copy(
          featuresSeq = fallback.featuresSeq ++ Seq(feature)
        )
        FastFuture.successful(feature)
      }
    }
  }

  override def features(pattern: Seq[String]): Future[Features] =
    Future {
      fallback.copy().filterWith(pattern)
    }

  override def features(pattern: Seq[String], context: JsObject): Future[Features] =
    Future {
      fallback.copy().filterWith(pattern)
    }

  override def checkFeature(key: String): Future[Boolean] =
    Future {
      fallback.isActive(key)
    }

  override def checkFeature(key: String, context: JsObject): Future[Boolean] =
    Future {
      fallback.isActive(key)
    }

  override def onEvent(pattern: String)(cb: FeatureEvent => Unit): Registration =
    FakeRegistration()

  override def featuresSource(pattern: String): Source[FeatureEvent, NotUsed] =
    Source.failed(IzanamiException("Not implemented"))

  override def featuresStream(pattern: String): Publisher[FeatureEvent] =
    new Publisher[FeatureEvent] {
      override def subscribe(s: Subscriber[_ >: FeatureEvent]): Unit =
        s.onError(IzanamiException("Not implemented"))
    }

}
