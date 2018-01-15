package izanami.features

import akka.actor.ActorSystem
import akka.event.Logging
import akka.{Done, NotUsed}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.util.FastFuture
import akka.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, Sink, Source}
import izanami.FeatureEvent._
import izanami.commons.{HttpClient, IzanamiException, PatternsUtil}
import izanami.scaladsl.{DefaultRegistration, FeatureClient, Features, Registration}
import izanami._
import org.reactivestreams.Publisher
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.Future

object FetchFeatureClient {
  def apply(client: HttpClient, clientConfig: ClientConfig, fallback: Features, events: Source[IzanamiEvent, NotUsed])(
      implicit izanamiDispatcher: IzanamiDispatcher,
      actorSystem: ActorSystem,
      materializer: Materializer
  ): FetchFeatureClient =
    new FetchFeatureClient(client, clientConfig, fallback, events)
}

private[features] class FetchFeatureClient(
    client: HttpClient,
    clientConfig: ClientConfig,
    fallback: Features,
    events: Source[IzanamiEvent, NotUsed]
)(implicit val izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem, val materializer: Materializer)
    extends FeatureClient {

  import client._
  import izanamiDispatcher.ec
  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  private val featuresSource = events
    .filter(_.domain == "Feature")
    .map {
      case evt @ IzanamiEvent(key, t, _, payload, _, _) if t == "FEATURE_CREATED" =>
        Feature.format
          .reads(payload)
          .fold(
            err => {
              client.actorSystem.log
                .error(s"Error deserializing feature event {}: {}", evt, err)
              None
            },
            f => Some(FeatureCreated(key, f))
          )
      case evt @ IzanamiEvent(key, t, _, payload, Some(oldValue), _) if t == "FEATURE_UPDATED" =>
        val event = for {
          newOne <- Feature.format.reads(payload)
          oldOne <- Feature.format.reads(oldValue)
        } yield FeatureUpdated(key, newOne, oldOne)

        event.fold(
          err => {
            logger
              .error(s"Error deserializing feature event {}: {}", evt, err)
            None
          },
          e => Some(e)
        )
      case IzanamiEvent(key, t, _, _, _, _) if t == "FEATURE_DELETED" =>
        Some(FeatureDeleted(key))
      case e =>
        logger.error(s"Event don't match {}", e)
        None
    }
    .mapConcat(_.toList)

  override def features(pattern: String): Future[Features] = {
    val convertedPattern =
      Option(pattern).map(_.replace(".", ":")).getOrElse("*")
    val query = Seq("pattern" -> convertedPattern, "active" -> "true")
    client
      .fetchPages("/api/features", query)
      .map(json => Features(clientConfig, parseFeatures(json), fallback.featuresSeq))
  }

  override def features(pattern: String, context: JsObject): Future[Features] = {
    val convertedPattern =
      Option(pattern).map(_.replace(".", ":")).getOrElse("*")
    val query = Seq("pattern" -> convertedPattern)
    client
      .fetchPagesWithContext("/api/features/_checks", context, query)
      .map(json => {
        Features(clientConfig, parseFeatures(json), fallback.featuresSeq)
      })
  }

  override def checkFeature(key: String): Future[Boolean] =
    checkFeature(key, Json.obj())

  override def checkFeature(key: String, context: JsObject): Future[Boolean] = {
    val convertedKey = key.replace(".", ":")
    client
      .fetchWithContext(s"/api/features/$convertedKey/check", context)
      .flatMap {
        case (status, json) if status == StatusCodes.OK =>
          val feature = Json.parse(json)
          FastFuture.successful((feature \ "active").asOpt[Boolean].getOrElse(false))
        case (status, _) if status == StatusCodes.NotFound =>
          FastFuture.successful(fallback.isActive(convertedKey))
        case (status, body) =>
          logger.error("Error checking feature {}, with context {} : status={}, response={}",
                       key,
                       context,
                       status,
                       body)
          FastFuture
            .failed(IzanamiException(s"Error while checking feature $convertedKey, status: $status, response: $body"))
      }
  }

  override def featuresSource(pattern: String): Source[izanami.FeatureEvent, NotUsed] = {
    val matchP = PatternsUtil.matchPattern(pattern) _
    featuresSource
      .filter(e => matchP(e.id))
      .alsoTo(Sink.foreach(e => logger.debug(s"Event $e")))
  }

  override def featuresStream(pattern: String): Publisher[izanami.FeatureEvent] =
    featuresSource(pattern).runWith(Sink.asPublisher(true))

  def parseFeatures(featuresJson: Seq[JsValue]): Seq[Feature] =
    featuresJson.flatMap(
      f =>
        f.validate[Feature]
          .fold(
            err => {
              logger.error(s"Error deserializing feature {}: {}", f, err)
              None
            },
            f => Some(f)
        )
    )

}
