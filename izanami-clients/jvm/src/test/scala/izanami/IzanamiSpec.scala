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

trait IzanamiSpec
    extends WordSpec
    with MustMatchers
    with OptionValues
    with ScalaFutures
    with Waiters
    with IntegrationPatience
    with BeforeAndAfterAll

trait MockServer {
  import com.github.tomakehurst.wiremock.WireMockServer
  import com.github.tomakehurst.wiremock.client.WireMock
  import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

  val _wireMockServer: WireMockServer = initServer()
  val mock                            = new WireMock("localhost", _wireMockServer.port())
  val host                            = s"http://localhost:${_wireMockServer.port()}"

  private def initServer(): WireMockServer = {
    val server = new WireMockServer(wireMockConfig().dynamicPort())
    server.start()
    server
  }
}

trait ConfigMockServer extends MockServer {

  import com.github.tomakehurst.wiremock.client.WireMock._

  def registerPage(group: Seq[Config],
                   page: Int = 1,
                   pageSize: Int = 200,
                   pattern: String = "*",
                   count: Int = 5): Unit = {
    val url = s"/api/configs"
    mock.register(
      get(urlPathEqualTo(url))
        .withQueryParam("pattern", equalTo(pattern))
        .withQueryParam("pageSize", equalTo(s"$pageSize"))
        .withQueryParam("page", equalTo(s"$page"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Json.stringify(
                Json.obj(
                  "results" -> Json.toJson(group),
                  "metadata" -> Json.obj(
                    "page"     -> page,
                    "pageSize" -> pageSize,
                    "count"    -> count,
                    "nbPages"  -> Math.ceil(count.toFloat / pageSize)
                  )
                )
              )
            )
        )
    )
  }

  def registerConfig(config: Config): Unit = {
    val url = s"/api/configs/${config.id}"
    mock.register(
      get(urlEqualTo(url)).willReturn(
        aResponse()
          .withStatus(200)
          .withBody(Json.stringify(Json.toJson(config)))
      )
    )
  }
}
trait ExperimentMockServer extends MockServer {
  import com.github.tomakehurst.wiremock.client.WireMock._

  def getExperimentList(pattern: String, experiments: Seq[Experiment], page: Int = 1, pageSize: Int = 200): Unit = {
    val count = experiments.size
    val url   = s"/api/experiments"
    mock.register(
      get(urlPathEqualTo(url))
        .withQueryParam("pattern", equalTo(pattern))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Json.stringify(
                Json.obj(
                  "results" -> Json.toJson(experiments),
                  "metadata" -> Json.obj(
                    "page"     -> page,
                    "pageSize" -> pageSize,
                    "count"    -> count,
                    "nbPages"  -> Math.ceil(count.toFloat / pageSize)
                  )
                )
              )
            )
        )
    )
  }

  def getExperimentTree(pattern: String, clientId: String, variantId: String, experiments: Seq[Experiment]): Unit = {
    val url = s"/api/tree/experiments"

    val tree = experiments
      .filter(_.enabled)
      .map(_.id)
      .map(
        id =>
          (id.split(":").foldLeft[JsPath](JsPath)(_ \ _) \ "variant")
            .write[String]
            .writes(variantId)
      )
      .foldLeft(Json.obj())(_ deepMerge _)

    mock.register(
      get(urlPathEqualTo(url))
        .withQueryParam("pattern", equalTo(pattern))
        .withQueryParam("clientId", equalTo(clientId))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(Json.stringify(tree))
        )
    )
  }

  def getExperiment(experiment: Experiment): Unit = {
    val url = s"/api/experiments/${experiment.id}"
    mock.register(
      get(urlPathEqualTo(url))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Json.stringify(
                Json.toJson(experiment)
              )
            )
        )
    )
  }

  def getVariantNotFound(id: String, client: String): Unit = {
    val url = s"/api/experiments/${id}/variant"
    mock.register(
      get(urlPathEqualTo(url))
        .withQueryParam("clientId", equalTo(client))
        .willReturn(
          aResponse()
            .withStatus(404)
        )
    )
  }

  def getVariant(id: String, client: String, variant: Variant): Unit = {
    val url = s"/api/experiments/${id}/variant"
    mock.register(
      get(urlPathEqualTo(url))
        .withQueryParam("clientId", equalTo(client))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Json.stringify(
                Json.toJson(variant)
              )
            )
        )
    )
  }

  def variantWon(id: String, client: String, variant: Variant): Unit = {
    val url = s"/api/experiments/${id}/won"
    mock.register(
      post(urlPathEqualTo(url))
        .withQueryParam("clientId", equalTo(client))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Json.stringify(
                Json.toJson(
                  ExperimentVariantWon(s"$id:${variant.id}:$client:1",
                                       id,
                                       client,
                                       variant,
                                       LocalDateTime.now(),
                                       0,
                                       variant.id)
                )
              )
            )
        )
    )
  }

  def variantDisplayed(id: String, client: String, variant: Variant): Unit = {
    val url = s"/api/experiments/${id}/displayed"
    mock.register(
      post(urlPathEqualTo(url))
        .withQueryParam("clientId", equalTo(client))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Json.stringify(
                Json.toJson(
                  ExperimentVariantDisplayed(s"$id:${variant.id}:$client:1",
                                             id,
                                             client,
                                             variant,
                                             LocalDateTime.now(),
                                             0,
                                             variant.id)
                )
              )
            )
        )
    )
  }

}

trait FeatureMockServer extends MockServer {
  import com.github.tomakehurst.wiremock.client.WireMock._

  def createEnabledFeatureWithNoStrategy(featureId: String): Unit = {
    val url = s"/api/features"
    mock.register(
      post(urlPathEqualTo(url))
        .withRequestBody(
          equalToJson(
            s"""
               |{
               |"id": "$featureId",
               |"enabled": true,
               |"activationStrategy": "NO_STRATEGY"
               |}
             """.stripMargin)
        )
        .willReturn(
          aResponse()
            .withStatus(201)
            .withBody(
              Json.stringify(
                Json.obj(
                  "id" -> featureId,
                  "enabled"-> true,
                  "activationStrategy" -> "NO_STRATEGY"
                )
              )
            )
        )
    )
  }

  def registerPage(group: Seq[Feature],
                   page: Int = 1,
                   pageSize: Int = 200,
                   active: Boolean = true,
                   pattern: String = "*",
                   count: Int = 5): Unit = {
    val url = s"/api/features"
    mock.register(
      get(urlPathEqualTo(url))
        .withQueryParam("pattern", equalTo(pattern))
        .withQueryParam("active", equalTo(s"$active"))
        .withQueryParam("pageSize", equalTo(s"$pageSize"))
        .withQueryParam("page", equalTo(s"$page"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Json.stringify(
                Json.obj(
                  "results" -> Json.toJson(group),
                  "metadata" -> Json.obj(
                    "page"     -> page,
                    "pageSize" -> pageSize,
                    "count"    -> count,
                    "nbPages"  -> Math.ceil(count.toFloat / pageSize)
                  )
                )
              )
            )
        )
    )
  }

  def registerCheckFeature(id: String, active: Boolean = true): Unit =
    mock.register(
      post(urlPathEqualTo(s"/api/features/$id/check"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Json.stringify(
                Json.obj(
                  "active" -> active
                )
              )
            )
        )
    )

}

case class Context[T, Event](host: String,
                             feature: ListBuffer[T],
                             calls: ListBuffer[String],
                             queue: SourceQueueWithComplete[Event]) {
  def setValues(f: Seq[T]): Unit =
    feature.appendAll(f)

  def clear(): Unit =
    feature.clear()

  def clearCalls(): Unit =
    calls.clear()

  def push(event: Event): Unit =
    queue.offer(event)
}

trait ExperimentServer {

  case class BindingKey(experiment: String, clientId: String)

  def runServer(test: Context[Experiment, AnyRef] => Unit)(implicit s: ActorSystem, m: Materializer): Unit = {
    import izanami.PlayJsonSupport._
    import s.dispatcher

    val state = ListBuffer.empty[Experiment]
    val calls = ListBuffer.empty[String]
    import Directives._
    val host = "localhost"
    val port = SocketUtil.temporaryServerAddress("localhost").getPort

    val (queue, source) = Source
      .queue[AnyRef](50, OverflowStrategy.backpressure)
      .toMat(BroadcastHub.sink(1024))(Keep.both)
      .run()

    val binding = mutable.HashMap.empty[BindingKey, Variant]

    var lastVariant = 1

    val route: Route =
    path("api" / "tree" / "experiments") {
      parameters('clientId.as[String]) { clientId =>
        val tree = state
          .map { experiment =>
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
          }
          .foldLeft(Json.obj()) {
            _ deepMerge _
          }
        complete(tree)
      }
    } ~ path("api" / "experiments" / Segment / "variant") { id =>
      get {
        parameters('clientId.as[String]) { clientId =>
          calls.append(s"api/experiments/$id/variant")
          binding
            .get(BindingKey(id, clientId))
            .map { variant =>
              complete(variant)
            }
            .getOrElse {
              complete(StatusCodes.NotFound -> Json.obj())
            }
        }
      }
    } ~ path("api" / "experiments" / Segment / "displayed") { id =>
      post {
        parameters('clientId.as[String]) { clientId =>
          calls.append(s"api/experiments/$id/displayed")

          binding
            .get(BindingKey(id, clientId))
            .map { variant =>
              complete(
                ExperimentVariantDisplayed(s"$id:$clientId:${Random.nextInt(10)}",
                                           id,
                                           clientId,
                                           variant,
                                           LocalDateTime.now(),
                                           0,
                                           variant.id)
              )
            }
            .getOrElse {
              state
                .find(_.id == id)
                .map { experiment =>
                  val v = if (lastVariant == 1) {
                    lastVariant = 2
                    experiment.variants.head
                  } else {
                    lastVariant = 1
                    experiment.variants.tail.head
                  }
                  binding.put(BindingKey(id, clientId), v)
                  complete(
                    ExperimentVariantDisplayed(s"$id:$clientId:${Random.nextInt(10)}",
                                               id,
                                               clientId,
                                               v,
                                               LocalDateTime.now(),
                                               0,
                                               v.id)
                  )
                }
                .getOrElse {
                  complete(StatusCodes.BadRequest -> Json.obj())
                }
            }
        }
      }
    } ~ path("api" / "experiments" / Segment / "won") { id =>
      post {
        parameters('clientId.as[String]) { clientId =>
          calls.append(s"api/experiments/$id")

          binding
            .get(BindingKey(id, clientId))
            .map { variant =>
              complete(
                ExperimentVariantWon(s"$id:$clientId:${Random.nextInt(10)}",
                                     id,
                                     clientId,
                                     variant,
                                     LocalDateTime.now(),
                                     0,
                                     variant.id)
              )
            }
            .getOrElse {
              state
                .find(_.id == id)
                .map { experiment =>
                  val v = if (lastVariant == 1) {
                    lastVariant = 2
                    experiment.variants.head
                  } else {
                    lastVariant = 1
                    experiment.variants.tail.head
                  }
                  binding.put(BindingKey(id, clientId), v)
                  complete(
                    ExperimentVariantWon(s"$id:$clientId:${Random.nextInt(10)}",
                                         id,
                                         clientId,
                                         v,
                                         LocalDateTime.now(),
                                         0,
                                         v.id)
                  )
                }
                .getOrElse {
                  complete(StatusCodes.BadRequest -> Json.obj())
                }
            }
        }
      }
    } ~
    path("api" / "experiments" / Segment) { id =>
      get {
        calls.append(s"api/experiments/$id")
        state
          .find(_.id == id)
          .map { c =>
            complete(c)
          }
          .getOrElse {
            complete(StatusCodes.NotFound -> Json.obj())
          }
      }
    } ~ path("api" / "experiments") {
      parameters(
        (
          'pattern.as[String] ? "*",
          'pageSize.as[Int] ? 2,
          'page.as[Int] ? 1
        )
      ) { (p, pageSize, page) =>
        calls.append(s"api/features")
        val drop = (page - 1) * pageSize

        val jsons = state
          .slice(drop, drop + pageSize)
          .map(Experiment.format.writes)
          .toSeq
        val resp = Json.obj(
          "results" -> JsArray(jsons),
          "metadata" -> Json.obj(
            "page"     -> page,
            "pageSize" -> pageSize,
            "count"    -> state.size,
            "nbPages"  -> Math.ceil(state.size.toFloat / pageSize)
          )
        )
        complete(resp)
      }
    } ~ path(Remaining) { rest =>
      val resp = Json.stringify(Json.obj("message" -> s"Unknow path $rest"))
      complete(
        HttpResponse(StatusCodes.BadRequest,
                     entity = HttpEntity(string = resp, contentType = ContentTypes.`application/json`))
      )
    }

    val bindingFuture: Future[ServerBinding] =
      Http().bindAndHandle(route, host, port)
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

    val (queue, source) = Source
      .queue[ConfigEvent](50, OverflowStrategy.backpressure)
      .toMat(BroadcastHub.sink(1024))(Keep.both)
      .run()

    val fakeConfig = ClientConfig("")

    val route: Route =
    path("api" / "configs" / Segment) { id =>
      get {
        calls.append(s"api/configs/$id")
        state
          .find(_.id == id)
          .map { c =>
            complete(c)
          }
          .getOrElse {
            complete(StatusCodes.NotFound -> Json.obj())
          }
      }
    } ~ path("api" / "configs") {
      parameters(
        (
          'pattern.as[String] ? "*",
          'pageSize.as[Int] ? 2,
          'page.as[Int] ? 1
        )
      ) { (p, pageSize, page) =>
        calls.append(s"api/features")
        val drop = (page - 1) * pageSize

        val jsons =
          state.slice(drop, drop + pageSize).map(Config.format.writes).toSeq
        val resp = Json.obj(
          "results" -> JsArray(jsons),
          "metadata" -> Json.obj(
            "page"     -> page,
            "pageSize" -> pageSize,
            "count"    -> state.size,
            "nbPages"  -> Math.ceil(state.size.toFloat / pageSize)
          )
        )
        complete(resp)
      }
    } ~ path("api" / "events") {
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
      complete(
        HttpResponse(StatusCodes.BadRequest,
                     entity = HttpEntity(string = resp, contentType = ContentTypes.`application/json`))
      )
    }

    val bindingFuture: Future[ServerBinding] =
      Http().bindAndHandle(route, host, port)
    val serverBinding = Await.result(bindingFuture, 1.second)

    try {
      test(Context(s"http://$host:$port", state, calls, queue))
    } finally {
      serverBinding.unbind()
    }

  }

  def serializeEvent(event: ConfigEvent): JsValue =
    event match {
      case ConfigCreated(eventId, id, c) =>
        Json.obj(
          "_id"       -> eventId,
          "type"      -> "CONFIG_CREATED",
          "key"       -> id,
          "domain"    -> "Config",
          "payload"   -> Json.toJson(c),
          "timestamp" -> System.currentTimeMillis()
        )
      case ConfigUpdated(eventId, id, c, o) =>
        Json.obj(
          "_id"       -> eventId,
          "type"      -> "CONFIG_UPDATED",
          "key"       -> id,
          "domain"    -> "Config",
          "payload"   -> Json.toJson(c),
          "oldValue"  -> Json.toJson(o),
          "timestamp" -> System.currentTimeMillis()
        )
      case ConfigDeleted(eventId, id) =>
        Json.obj(
          "_id"       -> eventId,
          "type"      -> "CONFIG_DELETED",
          "key"       -> id,
          "domain"    -> "Config",
          "payload"   -> Json.obj(),
          "timestamp" -> System.currentTimeMillis()
        )
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

    val (queue, source) = Source
      .queue[FeatureEvent](2, OverflowStrategy.backpressure)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

    val fakeConfig = ClientConfig("")

    val route: Route =
    path("api" / "features" / "_checks") {
      post {
        parameters(
          (
            'pattern.as[String] ? "*",
            'active.as[Boolean] ? false,
            'pageSize.as[Int] ? 2,
            'page.as[Int] ? 1
          )
        ) { (p, a, pageSize, page) =>
          calls.append(s"api/features")
          val drop = (page - 1) * pageSize

          val jsons = state
            .slice(drop, drop + pageSize)
            .map(f => Feature.format.writes(f).as[JsObject] ++ Json.obj("active" -> f.isActive(fakeConfig)))
          val resp = Json.obj(
            "results" -> JsArray(jsons),
            "metadata" -> Json.obj(
              "page"     -> page,
              "pageSize" -> pageSize,
              "count"    -> state.size,
              "nbPages"  -> Math.ceil(state.size.toFloat / pageSize)
            )
          )
          complete(resp)
        }
      }
    } ~ path("api" / "features" / Segment / "check") { id =>
      post {
        entity(as[Option[JsValue]]) { mayBeBody =>
          calls.append(s"api/features/$id")
          state
            .find(_.id == id)
            .map { f =>
              val j = Json.toJson(f).as[JsObject] ++ Json.obj("active" -> f.isActive(fakeConfig))
              complete(j)
            }
            .getOrElse {
              complete(StatusCodes.NotFound -> Json.obj())
            }
        }
      }
    } ~ path("api" / "features") {
      parameters(
        (
          'pattern.as[String] ? "*",
          'active.as[Boolean] ? false,
          'pageSize.as[Int] ? 2,
          'page.as[Int] ? 1
        )
      ) { (p, a, pageSize, page) =>
        calls.append(s"api/features")
        val drop = (page - 1) * pageSize

        val jsons =
          state.slice(drop, drop + pageSize).map(Feature.format.writes).toSeq
        val resp = Json.obj(
          "results" -> JsArray(jsons),
          "metadata" -> Json.obj(
            "page"     -> page,
            "pageSize" -> pageSize,
            "count"    -> state.size,
            "nbPages"  -> Math.ceil(state.size.toFloat / pageSize)
          )
        )
        complete(resp)
      }
    } ~ path("api" / "events") {
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
      complete(
        HttpResponse(StatusCodes.BadRequest,
                     entity = HttpEntity(string = resp, contentType = ContentTypes.`application/json`))
      )
    }

    val bindingFuture: Future[ServerBinding] =
      Http().bindAndHandle(route, host, port)
    val serverBinding = Await.result(bindingFuture, 1.second)

    try {
      test(Context(s"http://$host:$port", state, calls, queue))
    } finally {
      serverBinding.unbind()
    }

  }

  def serializeEvent(event: FeatureEvent): JsValue =
    event match {
      case FeatureCreated(eventId, id, f) =>
        Json.obj(
          "_id"       -> eventId,
          "type"      -> "FEATURE_CREATED",
          "key"       -> id,
          "domain"    -> "Feature",
          "payload"   -> Json.toJson(f),
          "timestamp" -> System.currentTimeMillis()
        )
      case FeatureUpdated(eventId, id, f, o) =>
        Json.obj(
          "_id"       -> eventId,
          "type"      -> "FEATURE_UPDATED",
          "key"       -> id,
          "domain"    -> "Feature",
          "payload"   -> Json.toJson(f),
          "oldValue"  -> Json.toJson(o),
          "timestamp" -> System.currentTimeMillis()
        )
      case FeatureDeleted(eventId, id) =>
        Json.obj(
          "_id"       -> eventId,
          "type"      -> "FEATURE_DELETED",
          "key"       -> id,
          "domain"    -> "Feature",
          "payload"   -> Json.toJson(DefaultFeature(id, false)),
          "timestamp" -> System.currentTimeMillis()
        )
    }

}
