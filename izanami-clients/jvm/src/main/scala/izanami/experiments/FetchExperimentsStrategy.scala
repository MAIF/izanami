package izanami.experiments

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.{HttpMethods, StatusCodes}
import akka.http.scaladsl.util.FastFuture
import izanami._
import izanami.commons.{HttpClient, IzanamiException}
import izanami.scaladsl.{ExperimentClient, ExperimentsClient}
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.Future

object FetchExperimentsStrategy {
  def apply(httpClient: HttpClient, fallback: Experiments)(
      implicit izanamiDispatcher: IzanamiDispatcher,
      actorSystem: ActorSystem): FetchExperimentsStrategy =
    new FetchExperimentsStrategy(httpClient, fallback)
}

class FetchExperimentsStrategy(httpClient: HttpClient, fallback: Experiments)(
    implicit izanamiDispatcher: IzanamiDispatcher,
    actorSystem: ActorSystem)
    extends ExperimentsClient {

  import izanamiDispatcher.ec
  import izanami.Experiment._

  private val fallbackStrategy = FallbackExperimentStrategy(fallback)

  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  override def experiment(id: String): Future[Option[ExperimentClient]] = {
    require(id != null, "id should not be null")
    httpClient.fetch(s"/api/experiments/$id").flatMap {
      case (status, body) if status == StatusCodes.OK =>
        Json
          .parse(body)
          .validate[Experiment]
          .fold(
            err => {
              val message =
                s"Error deserializing experiment for response $body : $err"
              logger.error(message)
              FastFuture.failed(IzanamiException(message))
            },
            exp => FastFuture.successful(Some(ExperimentClient(this, exp)))
          )
      case (status, _) if status == StatusCodes.NotFound =>
        fallbackStrategy.experiment(id)
      case (status, body) =>
        val message =
          s"Error getting experiment $id: status=$status, body= $body"
        logger.error(message)
        FastFuture.failed(IzanamiException(message))
    }
  }

  override def getVariantFor(experimentId: String,
                             clientId: String): Future[Option[Variant]] = {
    require(clientId != null, "clientId should not be null")
    httpClient
      .fetch(s"/api/experiments/${experimentId}/variant",
             Seq("clientId" -> clientId))
      .flatMap {
        case (status, body) if status == StatusCodes.OK =>
          Json
            .parse(body)
            .validate[Variant]
            .fold(
              err => {
                val message =
                  s"Error deserializing variant for response $body : $err"
                logger.error(message)
                FastFuture.failed(IzanamiException(message))
              },
              variant => FastFuture.successful(Some(variant))
            )
        case (status, _) if status == StatusCodes.NotFound =>
          FastFuture.successful(None)
        case (status, body) =>
          val message =
            s"Error getting variant for experiment $experimentId and client $clientId: status=$status, body= $body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
      }
  }

  override def markVariantDisplayed(
      experimentId: String,
      clientId: String): Future[ExperimentVariantDisplayed] = {
    require(clientId != null, "clientId should not be null")
    val uri = s"/api/experiments/${experimentId}/displayed"
    httpClient
      .fetch(uri, Seq("clientId" -> clientId), method = HttpMethods.POST)
      .flatMap {
        case (status, body) if status == StatusCodes.OK =>
          Json
            .parse(body)
            .validate[ExperimentVariantDisplayed]
            .fold(
              err => {
                val message =
                  s"Error deserializing variant for response $body : $err"
                logger.error(message)
                FastFuture.failed(IzanamiException(message))
              },
              variant => FastFuture.successful(variant)
            )
        case (status, body) =>
          val message =
            s"Error marking variant displayed for experiment $experimentId and client $clientId: status=$status, body= $body"
          FastFuture.failed(IzanamiException(message))
      }
  }

  override def markVariantWon(
      experimentId: String,
      clientId: String): Future[ExperimentVariantWon] = {
    require(clientId != null, "clientId should not be null")
    httpClient
      .fetch(s"/api/experiments/${experimentId}/won",
             Seq("clientId" -> clientId),
             method = HttpMethods.POST)
      .flatMap {
        case (status, body) if status == StatusCodes.OK =>
          Json
            .parse(body)
            .validate[ExperimentVariantWon]
            .fold(
              err => {
                val message =
                  s"Error deserializing variant for response $body : $err"
                logger.error(message)
                FastFuture.failed(IzanamiException(message))
              },
              variant => FastFuture.successful(variant)
            )
        case (status, body) =>
          val message =
            s"Error marking variant won for experiment $experimentId and client $clientId: status=$status, body= $body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
      }
  }

  override def list(pattern: String): Future[Seq[ExperimentClient]] = {
    val effectivePattern =
      Option(pattern).map(_.replace(".", ":")).getOrElse("*")
    val fetchedList: Future[Seq[ExperimentClient]] = httpClient
      .fetchPages(s"/api/experiments", Seq("pattern" -> effectivePattern))
      .map {
        _.flatMap { json =>
          json
            .validate[Experiment]
            .fold(
              err => {
                logger.error(
                  s"Error deserializing experiment for response $json : $err")
                None
              },
              exp => Some(ExperimentClient(this, exp))
            )
        }
      }
    for {
      fetched <- fetchedList
      fallback <- fallbackStrategy.list(pattern)
    } yield fallback.filter(e => !fetched.exists(_.id == e.id)) ++ fetched
  }

  override def tree(pattern: String, clientId: String): Future[JsObject] = {
    val effectivePattern: String =
      Option(pattern).map(_.replace(".", ":")).getOrElse("*")
    for {
      fetched <- httpClient
        .fetch(s"/api/tree/experiments",
               Seq("pattern" -> effectivePattern, "clientId" -> clientId))
        .flatMap {
          case (status, body) if status == StatusCodes.OK =>
            FastFuture.successful(Json.parse(body).as[JsObject])
          case (status, body) =>
            logger.error(
              s"Error getting experiment: status=$status, body= $body")
            FastFuture.failed(
              IzanamiException(
                s"Error getting experiment: status=$status, body= $body"))
        }
      fallback <- fallbackStrategy.tree(pattern, clientId)
    } yield fallback deepMerge fetched
  }
}
