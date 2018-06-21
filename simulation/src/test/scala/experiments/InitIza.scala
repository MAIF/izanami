package experiments

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.{Future}

object InitIza extends App {

  implicit val system: ActorSystem             = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  private val http = Http()

  private val features = "http://localhost:9000/api/features"
  //private val features = "http://izanami-perfs.cleverapps.io/api/features"

  Source(0 to 2000)
    .mapAsyncUnordered(10) { postFeature }
    .alsoTo(Sink.foreach {
      case (c, s) if c == StatusCodes.Created =>
      case (c, s) =>
        println(s"Oups $c $s")
    })
    .runWith(Sink.ignore)
    .onComplete { _ =>
      println("Done")
    }

  private def postFeature(i: Int): Future[(StatusCode, String)] = {

    val headers: immutable.Seq[HttpHeader] = immutable.Seq(
      RawHeader("Izanami-Client-Id", "xxxx"),
      RawHeader("Izanami-Client-Secret", "xxxx")
    )

    val body =
      s"""
        | {
        |   "id": "a:key:$i",
        |   "enabled": true,
        |   "activationStrategy": "NO_STRATEGY"
        | }
      """.stripMargin

    http
      .singleRequest(
        HttpRequest(
          HttpMethods.POST,
          Uri(features),
          headers = headers,
          entity = HttpEntity.Strict(ContentTypes.`application/json`, ByteString(body))
        )
      )
      .flatMap {
        case HttpResponse(code, _, entity, _) =>
          entity.dataBytes.map(_.utf8String).runFold("")((str, acc) => str + acc).map(s => (code, s))
      }
  }

}
