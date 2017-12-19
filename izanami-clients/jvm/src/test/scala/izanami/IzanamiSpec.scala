package izanami

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.path
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.scaladsl.{BroadcastHub, Keep, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.testkit.SocketUtil
import izanami.FeatureEvent.{FeatureCreated, FeatureDeleted, FeatureUpdated}
import izanami.scaladsl.ConfigEvent.{ConfigCreated, ConfigDeleted, ConfigUpdated}
import izanami.scaladsl.{Config, ConfigEvent}
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures, Waiters}
import play.api.libs.json._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, Future}
import scala.util.Random

trait IzanamiSpec extends WordSpec with MustMatchers with OptionValues with ScalaFutures with Waiters with IntegrationPatience with BeforeAndAfterAll


case class Context[T, Event](host: String, feature: ListBuffer[T], calls: ListBuffer[String], queue: SourceQueueWithComplete[Event]) {
  def setValues(f: Seq[T]): Unit = {
    feature.appendAll(f)
  }

  def clear(): Unit = {
    feature.clear()
  }

  def clearCalls(): Unit = {
    calls.clear()
  }

  def push(event: Event): Unit = {
    queue.offer(event)
  }
}

trait ExperimentServer  {

  case class BindingKey(experiment: String, clientId: String)

  def runServer(test: Context[Experiment, AnyRef] => Unit)(implicit s: ActorSystem, m: Materializer): Unit = {
    import izanami.PlayJsonSupport._
    import s.dispatcher

    val state = ListBuffer.empty[Experiment]
    val calls = ListBuffer.empty[String]
    import Directives._
    val host = "localhost"
    val port = SocketUtil.temporaryServerAddress("localhost").getPort

    val (queue, source) = Source.queue[AnyRef](50, OverflowStrategy.backpressure).toMat(BroadcastHub.sink(1024))(Keep.both).run()

    val binding = mutable.HashMap.empty[BindingKey, Variant]

    var lastVariant = 1

    val route: Route =
      path("api" / "tree" / "experiments")  {
        parameters('clientId.as[String]) { clientId =>

          val tree = state.map { experiment =>
            val v = if (lastVariant == 1) {
              lastVariant = 2
              experiment.variants.head
            } else {
              lastVariant = 1
              experiment.variants.tail.head
            }
            binding.put(BindingKey(experiment.id, clientId), v)
            val jsPath = experiment.id.split(":").foldLeft[JsPath](JsPath) { (p, s) =>
              p \ s
            }
            (jsPath \ "variant").write[String].writes(v.id)
          }.foldLeft(Json.obj()) {
            _ deepMerge _
          }
          complete(tree)
        }
      } ~ path("api" / "experiments" / Segment / "variant") { id =>
        get {
          parameters('clientId.as[String]) { clientId =>
            calls.append(s"api/experiments/$id/variant")
            binding.get(BindingKey(id, clientId)).map { variant =>
              complete(variant)
            }.getOrElse {
              complete(StatusCodes.NotFound -> Json.obj())
            }
          }
        }
      } ~ path("api" / "experiments" / Segment / "displayed") { id =>
        post {
          parameters('clientId.as[String]) { clientId =>
            calls.append(s"api/experiments/$id/displayed")

            binding.get(BindingKey(id, clientId)).map { variant =>
              complete(ExperimentVariantDisplayed(s"$id:$clientId:${Random.nextInt(10)}", id, clientId, variant, LocalDateTime.now(), 0, variant.id))
            }.getOrElse {
              state.find(_.id == id).map { experiment =>
                val v = if ( lastVariant == 1 ) {
                  lastVariant = 2
                  experiment.variants.head
                } else {
                  lastVariant = 1
                  experiment.variants.tail.head
                }
                binding.put(BindingKey(id, clientId), v)
                complete(ExperimentVariantDisplayed(s"$id:$clientId:${Random.nextInt(10)}", id, clientId, v, LocalDateTime.now(), 0, v.id))
              }.getOrElse {
                complete(StatusCodes.BadRequest -> Json.obj())
              }
            }
          }
        }
      } ~ path("api" / "experiments" / Segment / "won") { id =>
          post {
            parameters('clientId.as[String]) { clientId =>
              calls.append(s"api/experiments/$id")

              binding.get(BindingKey(id, clientId)).map { variant =>
                complete(ExperimentVariantWon(s"$id:$clientId:${Random.nextInt(10)}", id, clientId, variant, LocalDateTime.now(), 0, variant.id))
              }.getOrElse {
                state.find(_.id == id).map { experiment =>
                  val v = if ( lastVariant == 1 ) {
                    lastVariant = 2
                    experiment.variants.head
                  } else {
                    lastVariant = 1
                    experiment.variants.tail.head
                  }
                  binding.put(BindingKey(id, clientId), v)
                  complete(ExperimentVariantWon(s"$id:$clientId:${Random.nextInt(10)}", id, clientId, v, LocalDateTime.now(), 0, v.id))
                }.getOrElse {
                  complete(StatusCodes.BadRequest -> Json.obj())
                }
              }
            }
          }
        } ~
        path("api" / "experiments" / Segment) { id =>
        get {
          calls.append(s"api/experiments/$id")
          state.find(_.id == id).map{ c =>
            complete(c)
          }.getOrElse {
            complete(StatusCodes.NotFound -> Json.obj())
          }
        }
      } ~ path("api" / "experiments") {
        parameters((
          'pattern.as[String] ? "*",
          'pageSize.as[Int] ? 2,
          'page.as[Int] ? 1
        )) { (p, pageSize, page) =>
          calls.append(s"api/features")
          val drop = (page - 1) * pageSize

          val jsons = state.slice(drop, drop + pageSize).map(Experiment.format.writes).toSeq
          val resp = Json.obj(
            "results" -> JsArray(jsons),
            "metadata" -> Json.obj(
              "page" -> page,
              "pageSize" -> pageSize,
              "count" -> state.size,
              "nbPages" -> Math.ceil(state.size.toFloat / pageSize)
            )
          )
          complete(resp)
        }
      } ~ path(Remaining) { rest =>
        val resp = Json.stringify(Json.obj("message" -> s"Unknow path $rest"))
        complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(string = resp, contentType = ContentTypes.`application/json`)))
      }

    val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(route, host, port)
    val serverBinding = Await.result(bindingFuture, 1.second)

    try {
      test(Context(s"http://$host:$port", state, calls, queue))
    } finally {
      serverBinding.unbind()
    }
  }

}


trait ConfigServer {
  import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

  def runServer(test: Context[Config, ConfigEvent] => Unit)(implicit s: ActorSystem, m: Materializer): Unit = {
    import izanami.PlayJsonSupport._
    import s.dispatcher

    val state = ListBuffer.empty[Config]
    val calls = ListBuffer.empty[String]
    import Directives._
    val host = "localhost"
    val port = SocketUtil.temporaryServerAddress("localhost").getPort

    val (queue, source) = Source.queue[ConfigEvent](50, OverflowStrategy.backpressure).toMat(BroadcastHub.sink(1024))(Keep.both).run()

    val fakeConfig = ClientConfig("")

    val route: Route =
      path("api" / "configs" / Segment) { id =>
        get {
          calls.append(s"api/configs/$id")
          state.find(_.id == id).map{ c =>
            complete(c)
          }.getOrElse {
            complete(StatusCodes.NotFound -> Json.obj())
          }
        }
      } ~ path("api" / "configs") {
        parameters((
          'pattern.as[String] ? "*",
          'pageSize.as[Int] ? 2,
          'page.as[Int] ? 1
        )) { (p, pageSize, page) =>
          calls.append(s"api/features")
          val drop = (page - 1) * pageSize

          val jsons = state.slice(drop, drop + pageSize).map(Config.format.writes).toSeq
          val resp = Json.obj(
            "results" -> JsArray(jsons),
            "metadata" -> Json.obj(
              "page" -> page,
              "pageSize" -> pageSize,
              "count" -> state.size,
              "nbPages" -> Math.ceil(state.size.toFloat / pageSize)
            )
          )
          complete(resp)
        }
      } ~  path("api" / "events") {
        get {
          complete {
            source
              .map(serializeEvent)
              .map(Json.stringify _)
              .map(str => ServerSentEvent(str))
          }
        }
      } ~ path(Remaining) { rest =>
        val resp = Json.stringify(Json.obj("message" -> s"Unknow path $rest"))
        complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(string = resp, contentType = ContentTypes.`application/json`)))
      }

    val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(route, host, port)
    val serverBinding = Await.result(bindingFuture, 1.second)

    try {
      test(Context(s"http://$host:$port", state, calls, queue))
    } finally {
      serverBinding.unbind()
    }


  }

  def serializeEvent(event: ConfigEvent): JsValue = {
    event match {
      case ConfigCreated(id, c) =>
        Json.obj(
          "type" -> "CONFIG_CREATED",
          "key" -> id,
          "domain" -> "Config",
          "payload" -> Json.toJson(c),
          "timestamp" -> System.currentTimeMillis()
        )
      case ConfigUpdated(id, c, o) =>
        Json.obj(
          "type" -> "CONFIG_UPDATED",
          "key" -> id,
          "domain" -> "Config",
          "payload" -> Json.toJson(c),
          "oldValue" -> Json.toJson(o),
          "timestamp" -> System.currentTimeMillis()
        )
      case ConfigDeleted(id) =>
        Json.obj(
          "type" -> "CONFIG_DELETED",
          "key" -> id,
          "domain" -> "Config",
          "payload" -> Json.obj(),
          "timestamp" -> System.currentTimeMillis()
        )
    }
  }

}

trait FeatureServer {
  import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

  def runServer(test: Context[Feature, FeatureEvent] => Unit)(implicit s: ActorSystem, m: Materializer): Unit = {
    import izanami.PlayJsonSupport._
    import s.dispatcher

    val state = ListBuffer.empty[Feature]
    val calls = ListBuffer.empty[String]
    import Directives._
    val host = "localhost"
    val port = SocketUtil.temporaryServerAddress("localhost").getPort

    val (queue, source) = Source.queue[FeatureEvent](50, OverflowStrategy.backpressure).toMat(BroadcastHub.sink(1024))(Keep.both).run()

    val fakeConfig = ClientConfig("")

    val route: Route =
      path("api" / "features" / "_checks") {
        post {
          parameters((
            'pattern.as[String] ? "*",
            'active.as[Boolean] ? false,
            'pageSize.as[Int] ? 2,
            'page.as[Int] ? 1
          )) { (p, a, pageSize, page) =>
            calls.append(s"api/features")
            val drop = (page - 1) * pageSize

            val jsons = state
              .slice(drop, drop + pageSize)
              .map(f => Feature.format.writes(f).as[JsObject] ++ Json.obj("active" -> f.isActive(fakeConfig)))
            val resp = Json.obj(
              "results" -> JsArray(jsons),
              "metadata" -> Json.obj(
                "page" -> page,
                "pageSize" -> pageSize,
                "count" -> state.size,
                "nbPages" -> Math.ceil(state.size.toFloat / pageSize)
              )
            )
            complete(resp)
          }
        }
      } ~ path("api" / "features" / Segment / "check") { id =>
        post {
          entity(as[Option[JsValue]]) { mayBeBody =>
            calls.append(s"api/features/$id")
            state.find(_.id == id).map{ f =>
              val j = Json.toJson(f).as[JsObject] ++ Json.obj("active" -> f.isActive(fakeConfig))
              complete(j)
            }.getOrElse {
              complete(StatusCodes.NotFound -> Json.obj())
            }
          }
        }
      } ~ path("api" / "features") {
        parameters((
          'pattern.as[String] ? "*",
          'active.as[Boolean] ? false,
          'pageSize.as[Int] ? 2,
          'page.as[Int] ? 1
        )) { (p, a, pageSize, page) =>
          calls.append(s"api/features")
          val drop = (page - 1) * pageSize

          val jsons = state.slice(drop, drop + pageSize).map(Feature.format.writes).toSeq
          val resp = Json.obj(
            "results" -> JsArray(jsons),
            "metadata" -> Json.obj(
              "page" -> page,
              "pageSize" -> pageSize,
              "count" -> state.size,
              "nbPages" -> Math.ceil(state.size.toFloat / pageSize)
            )
          )
          complete(resp)
        }
      } ~  path("api" / "events") {
        get {
          complete {
            source
              .map(serializeEvent)
              .map(Json.stringify _)
              .map(str => ServerSentEvent(str))
          }
        }
      } ~ path(Remaining) { rest =>
        val resp = Json.stringify(Json.obj("message" -> s"Unknow path $rest"))
        complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(string = resp, contentType = ContentTypes.`application/json`)))
      }

    val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(route, host, port)
    val serverBinding = Await.result(bindingFuture, 1.second)

    try {
      test(Context(s"http://$host:$port", state, calls, queue))
    } finally {
      serverBinding.unbind()
    }


  }

  def serializeEvent(event: FeatureEvent): JsValue = {
    event match {
      case FeatureCreated(id, f) =>
        Json.obj(
          "type" -> "FEATURE_CREATED",
          "key" -> id,
          "domain" -> "Feature",
          "payload" -> Json.toJson(f),
          "timestamp" -> System.currentTimeMillis()
        )
      case FeatureUpdated(id, f, o) =>
        Json.obj(
          "type" -> "FEATURE_UPDATED",
          "key" -> id,
          "domain" -> "Feature",
          "payload" -> Json.toJson(f),
          "oldValue" -> Json.toJson(o),
          "timestamp" -> System.currentTimeMillis()
        )
      case FeatureDeleted(id) =>
        Json.obj(
          "type" -> "FEATURE_DELETED",
          "key" -> id,
          "domain" -> "Feature",
          "payload" -> Json.toJson(DefaultFeature(id, false)),
          "timestamp" -> System.currentTimeMillis()
        )
    }
  }

}