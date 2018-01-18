package izanami.commons

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.stream.alpakka.sse.scaladsl.EventSource
import akka.stream.scaladsl.Source
import akka.util.ByteString
import izanami.{ClientConfig, IzanamiEvent}
import play.api.libs.json.{JsValue, Json, Reads}

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

private object PagingResult {
  import play.api.libs.json._
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._

  implicit val reads: Reads[PagingResult] = (
    (__ \ "metadata" \ "page").read[Int] and
    (__ \ "metadata" \ "pageSize").read[Int] and
    (__ \ "metadata" \ "nbPages").read[Int] and
    (__ \ "metadata" \ "count").read[Int] and
    (__ \ "results").read[Seq[JsValue]].orElse(Reads.pure(Seq.empty))
  )(PagingResult.apply _)
}

private case class PagingResult(
    page: Int,
    pageSize: Int,
    nbPages: Int,
    count: Int,
    results: Seq[JsValue]
)

object IzanamiException {
  def apply(message: String): IzanamiException =
    new IzanamiException(message, null)
}

case class IzanamiException(message: String, cause: Throwable) extends RuntimeException(message, cause)

private[izanami] object HttpClient {
  def apply(system: ActorSystem, config: ClientConfig): HttpClient =
    new HttpClient(system, config)
}

private[izanami] class HttpClient(system: ActorSystem, config: ClientConfig) {

  import system.dispatcher
  implicit val actorSystem = system
  implicit val mat         = ActorMaterializer(ActorMaterializerSettings(system).withDispatcher(config.dispatcher))

  private val logger = Logging(system, this.getClass.getSimpleName)

  private val http = Http()

  private val headers = (for {
    clientId     <- config.clientId
    clientSecret <- config.clientSecret
  } yield
    immutable.Seq(
      RawHeader(config.clientIdHeaderName, clientId),
      RawHeader(config.clientSecretHeaderName, clientSecret)
    )).getOrElse(immutable.Seq.empty[HttpHeader])

  private def buildUri(path: String, params: Seq[(String, String)]): Uri =
    Uri(config.host).withPath(Path(path)).withQuery(Query(params: _*))

  private def parseResponse(response: HttpResponse): Future[(StatusCode, String)] =
    response.entity.dataBytes
      .runFold(ByteString(""))(_ ++ _)
      .map(_.utf8String)
      .map { (response.status, _) }

  def fetch(path: String, params: Seq[(String, String)] = Seq.empty, method: HttpMethod = HttpMethods.GET) = {
    logger.debug(s"GET ${config.host} $path, params = $params")
    http
      .singleRequest(
        HttpRequest(
          method = method,
          uri = buildUri(path, params),
          headers = headers
        )
      )
      .flatMap { parseResponse }
  }

  private def fetchAllPages(uri: String,
                            params: Seq[(String, String)] = Seq.empty,
                            mayBeContext: Option[JsValue] = None) =
    Source
      .unfoldAsync(1) { pageNum =>
        if (pageNum == -1) {
          FastFuture.successful(None)
        } else {
          fetchOnePage(uri, pageNum, params, mayBeContext).map {
            case PagingResult(_, _, _, _, results) if results.isEmpty =>
              None
            case PagingResult(page, _, nbPages, _, results) if nbPages == page =>
              Some((-1, results))
            case PagingResult(_, _, _, _, results) =>
              Some((pageNum + 1, results))
          }
        }
      }
      .runFold(Seq.empty[JsValue])(_ ++ _)

  private def fetchOnePage(uri: String,
                           pageNum: Long,
                           params: Seq[(String, String)] = Seq.empty,
                           mayBeContext: Option[JsValue] = None): Future[PagingResult] = {
    val allParams = params ++ Seq("pageSize" -> s"${config.pageSize}", "page" -> s"$pageNum")
    val call = mayBeContext match {
      case None =>
        fetch(uri, allParams)
      case Some(context) =>
        fetchWithContext(uri, context, allParams)
    }
    call.flatMap {
      case (code, json) if code != StatusCodes.OK =>
        logger.error("Error calling {} with params {} and context {} : \n{}", uri, allParams, mayBeContext, json)
        FastFuture.failed(IzanamiException(s"Bad status: $code, body = $json"))
      case (_, json) =>
        Try(Json.parse(json))
          .map {
            _.validate[PagingResult]
              .fold(
                err => FastFuture.failed(IzanamiException(s"Invalid format $err for response $json")),
                p => FastFuture.successful(p)
              )
          }
          .recover {
            case e =>
              FastFuture.failed(IzanamiException(s"Error parsing json for response $json", e))
          }
          .getOrElse {
            FastFuture.failed(IzanamiException(s"Error parsing json for response $json"))
          }
    }
  }

  def fetchPages(uri: String, params: Seq[(String, String)] = Seq.empty) =
    fetchAllPages(uri, params, None)

  def fetchPagesWithContext(uri: String, context: JsValue, params: Seq[(String, String)] = Seq.empty) =
    fetchAllPages(uri, params, Some(context))

  def fetchWithContext(path: String, context: JsValue, params: Seq[(String, String)] = Seq.empty) = {
    logger.debug(s"POST ${config.host} $path, params = $params")
    http
      .singleRequest(
        HttpRequest(
          method = HttpMethods.POST,
          uri = buildUri(path, params),
          entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(context)),
          headers = headers
        )
      )
      .flatMap { parseResponse }
  }

  def eventStream(): Source[IzanamiEvent, NotUsed] =
    EventSource(
      uri = buildUri("/api/events", Seq("domains" -> "Config,Feature")),
      send = { req =>
        http.singleRequest(req.withHeaders(req.headers ++ headers))
      },
      initialLastEventId = None
    ).map(sse => Json.parse(sse.data))
      .mapConcat(
        json =>
          json
            .validate[IzanamiEvent]
            .fold(
              err => {
                actorSystem.log
                  .error("Error deserializing event {}: {}", json, err)
                List.empty
              },
              s => List(s)
          )
      )
}
