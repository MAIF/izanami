package izanami.experiments

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.{HttpMethods, StatusCodes}
import akka.http.scaladsl.util.FastFuture
import izanami._
import izanami.commons.{HttpClient, IzanamiException}
import izanami.scaladsl.{ExperimentClient, ExperimentsClient}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future

object FetchExperimentsStrategy {
  def apply(httpClient: HttpClient, fallback: Experiments, errorStrategy: ErrorStrategy)(
      implicit izanamiDispatcher: IzanamiDispatcher,
      actorSystem: ActorSystem
  ): FetchExperimentsStrategy =
    new FetchExperimentsStrategy(httpClient, fallback, errorStrategy)
}

class FetchExperimentsStrategy(httpClient: HttpClient, fallback: Experiments, errorStrategy: ErrorStrategy)(
    implicit izanamiDispatcher: IzanamiDispatcher,
    actorSystem: ActorSystem
) extends ExperimentsClient {

  import izanamiDispatcher.ec
  import izanami.Experiment._

  private val fallbackStrategy = FallbackExperimentStrategy(fallback)

  private val logger = Logging(actorSystem, this.getClass.getName)

  private def handleFailure[T]: T => PartialFunction[Throwable, Future[T]] =
    commons.handleFailure[T](errorStrategy)(_)

  override def experiment(id: String): Future[Option[ExperimentClient]] = {
    require(id != null, "id should not be null")
    httpClient
      .fetch(s"/api/experiments/$id")
      .flatMap {
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
          FastFuture.successful(None)
      }
      .recoverWith(handleFailure(None))
  }

  override def getVariantFor(experimentId: String, clientId: String): Future[Option[Variant]] = {
    require(experimentId != null, "experimentId should not be null")
    require(clientId != null, "clientId should not be null")
    httpClient
      .fetch(s"/api/experiments/$experimentId/variant", Seq("clientId" -> clientId))
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
          FastFuture.successful(None)
      }
      .recoverWith(handleFailure(None))
  }

  override def markVariantDisplayed(experimentId: String, clientId: String): Future[ExperimentVariantDisplayed] = {
    require(experimentId != null, "experimentId should not be null")
    require(clientId != null, "clientId should not be null")
    val uri = s"/api/experiments/$experimentId/displayed"
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
            s"Error marking variant displayed for experiment $experimentId and client $clientId: status=$status, body= $body, recovering with fallback"

          val experiment: ExperimentFallback = fallback.experiments
            .find(_.id == experimentId)
            .getOrElse(ExperimentFallback(experimentId, "", "", enabled = false, Variant("", "", "")))

          FastFuture.successful(
            ExperimentVariantDisplayed(
              s"${experiment.id}:${experiment.variant.id}:$clientId:${System.currentTimeMillis()}",
              experiment.id,
              clientId,
              experiment.variant,
              LocalDateTime.now(),
              0,
              experiment.variant.id
            )
          )
      }
  }

  override def markVariantWon(experimentId: String, clientId: String): Future[ExperimentVariantWon] = {
    require(experimentId != null, "experimentId should not be null")
    require(clientId != null, "clientId should not be null")
    httpClient
      .fetch(s"/api/experiments/$experimentId/won", Seq("clientId" -> clientId), method = HttpMethods.POST)
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
            s"Error marking variant won for experiment $experimentId and client $clientId: status=$status, body= $body, recovering with fallback"
          logger.error(message)

          val experiment: ExperimentFallback = fallback.experiments
            .find(_.id == experimentId)
            .getOrElse(ExperimentFallback(experimentId, "", "", enabled = false, Variant("", "", "")))

          FastFuture.successful(
            ExperimentVariantWon(
              s"${experiment.id}:${experiment.variant.id}:$clientId:${System.currentTimeMillis()}",
              experiment.id,
              clientId,
              experiment.variant,
              LocalDateTime.now(),
              0,
              experiment.variant.id
            )
          )
      }
  }

  override def list(pattern: Seq[String]): Future[Seq[ExperimentClient]] = {

    val effectivePattern =
      Option(pattern).map(_.map(_.replace(".", ":")).mkString(",")).getOrElse("*")

    val fetchedList: Future[Seq[ExperimentClient]] = httpClient
      .fetchPages(s"/api/experiments", Seq("pattern" -> effectivePattern))
      .map {
        _.flatMap { json =>
          json
            .validate[Experiment]
            .fold(
              err => {
                logger.error(s"Error deserializing experiment for response $json : $err")
                None
              },
              exp => Some(ExperimentClient(this, exp))
            )
        }
      }

    (
      for {
        fetched  <- fetchedList
        fallback <- fallbackStrategy.list(pattern)
      } yield fallback.filter(e => !fetched.exists(_.id == e.id)) ++ fetched
    ).recover {
        case e =>
          logger.error(s"Error getting experiment list for $pattern, recovering with fallback", e)
          fallback.experiments.map(fb => ExperimentClient(this, fb.experiment))
      }
      .map(_.filter(ec => ec.matchPattern(pattern)))
  }

  override def tree(pattern: Seq[String], clientId: String): Future[JsObject] = {

    val effectivePattern =
      Option(pattern).map(_.map(_.replace(".", ":")).mkString(",")).getOrElse("*")

    (
      for {
        fetched <- httpClient
                    .fetch(s"/api/tree/experiments", Seq("pattern" -> effectivePattern, "clientId" -> clientId))
                    .map {
                      case (status, body) if status == StatusCodes.OK =>
                        Json.parse(body).as[JsObject]
                      case (status, body) =>
                        logger.error(s"Error getting experiment: status=$status, body= $body")
                        Json.obj()
                    }
        fallback <- fallbackStrategy.tree(pattern, clientId)
      } yield fallback.deepMerge(fetched)
    ).recoverWith {
      case e =>
        logger.error(s"Error getting experiment tree for $pattern, recovering with fallback", e)
        fallbackStrategy.tree(pattern, clientId)
    }
  }
}
