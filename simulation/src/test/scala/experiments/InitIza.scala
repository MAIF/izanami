package experiments

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext, Future}

object InitIza extends App {

  implicit val system: ActorSystem             = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()

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
    val feature = DefaultFeature(Key(s"a:key:$i"), enabled = true)

    val headers: immutable.Seq[HttpHeader] = immutable.Seq(
      RawHeader("Izanami-Client-Id", "xxxx"),
      RawHeader("Izanami-Client-Secret", "xxxx")
    )

    val body = Json.stringify(Json.toJson(feature))

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
