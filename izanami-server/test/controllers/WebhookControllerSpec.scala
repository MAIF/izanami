package controllers

import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.testkit.SocketUtil
import com.typesafe.config.ConfigFactory
import multi.Configs
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.JsonBodyWritables._
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, Future}

class WebhookControllerSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  private lazy val ws                    = izanamiComponents.wsClient
  private implicit lazy val system       = izanamiComponents.actorSystem
  private implicit lazy val materializer = ActorMaterializer()

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec ++ Configuration(
      ConfigFactory
        .parseString(
          """
        |izanami.webhook.events.within = "100 milliseconds"
        |izanami.webhook.ttl = "2 seconds"
        """.stripMargin
        )
        .resolve()
    )

  private lazy val rootPath = s"http://localhost:$port"

  s"$name WebhookController" should {

    "create read update delete" in {
      val key = "my:path"
      /* First check */
      ws.url(s"$rootPath/api/webhooks/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/webhooks").get().futureValue must beAResponse(
        200,
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      val webhook = Json.obj(
        "clientId"    -> key,
        "callbackUrl" -> "http://localhost:5000",
        "domains"     -> Json.arr(),
        "patterns"    -> Json.arr(),
        "types"       -> Json.arr(),
        "headers"     -> Json.obj(),
        "isBanned"    -> false
      )
      ws.url(s"$rootPath/api/webhooks")
        .post(webhook)
        .futureValue must beAStatus(201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/webhooks/$key").get().futureValue
      getById must beAStatus(200)
      (getById.json.as[JsObject] - "created") must be(webhook)

      formatResults(ws.url(s"$rootPath/api/webhooks").get().futureValue.json) must be(
        Json.obj("results"  -> Json.arr(webhook),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val webhookUpdated = Json.obj(
        "clientId"    -> key,
        "callbackUrl" -> "http://localhost:5000/v2",
        "domains"     -> Json.arr(),
        "patterns"    -> Json.arr(),
        "types"       -> Json.arr(),
        "headers"     -> Json.obj(),
        "isBanned"    -> false
      )
      ws.url(s"$rootPath/api/webhooks/$key")
        .put(webhookUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      val getByIdUpdated =
        ws.url(s"$rootPath/api/webhooks/$key").get().futureValue
      getByIdUpdated must beAStatus(200)
      (getByIdUpdated.json.as[JsObject] - "created") must be(webhookUpdated)

      formatResults(ws.url(s"$rootPath/api/webhooks").get().futureValue.json) must be(
        Json.obj("results"  -> Json.arr(webhookUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/webhooks/$key")
        .delete()
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/webhooks/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/webhooks").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Delete all */
      ws.url(s"$rootPath/api/webhooks")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()
      ws.url(s"$rootPath/api/webhooks").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }

    "update changing id" in {

      val key  = "toto1@maif.fr"
      val key2 = "toto2@maif.fr"

      /* Create */
      val webhook = Json.obj(
        "clientId"    -> key,
        "callbackUrl" -> "http://localhost:5000",
        "domains"     -> Json.arr(),
        "patterns"    -> Json.arr(),
        "types"       -> Json.arr(),
        "headers"     -> Json.obj(),
        "isBanned"    -> false
      )
      ws.url(s"$rootPath/api/webhooks")
        .post(webhook)
        .futureValue must beAStatus(201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/webhooks/$key").get().futureValue
      getById must beAStatus(200)
      (getById.json.as[JsObject] - "created") must be(webhook)

      formatResults(ws.url(s"$rootPath/api/webhooks").get().futureValue.json) must be(
        Json.obj("results"  -> Json.arr(webhook),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val webhookUpdated = Json.obj(
        "clientId"    -> key2,
        "callbackUrl" -> "http://localhost:5000/v2",
        "domains"     -> Json.arr(),
        "patterns"    -> Json.arr(),
        "types"       -> Json.arr(),
        "headers"     -> Json.obj(),
        "isBanned"    -> false
      )
      ws.url(s"$rootPath/api/webhooks/$key")
        .put(webhookUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      val getByIdUpdated =
        ws.url(s"$rootPath/api/webhooks/$key2").get().futureValue
      getByIdUpdated must beAStatus(200)

      ws.url(s"$rootPath/api/webhooks/$key").get().futureValue must beAStatus(404)
    }

    //TO FRAGILE FOR TRAVIS => TODO: FIX IT
//    "call webhook on event" in {
//
//      withServer(buildBasicServer()) { ctx =>
//        val key = "my:webhook:test2"
//        val webhook = Json.obj("clientId" -> key,
//                               "callbackUrl" -> s"http://localhost:${ctx.port}/api/v1/events",
//                               "patterns"    -> Json.arr(),
//                               "headers"     -> Json.obj())
//
//        ws.url(s"$rootPath/api/webhooks")
//          .post(webhook)
//          .futureValue
//          .status must be(201)
//
//        Thread.sleep(1000)
//
//        val config = Json.obj("id" -> key, "value" -> "value")
//        ws.url(s"$rootPath/api/configs")
//          .post(config)
//          .futureValue
//          .status must be(201)
//        val feature = Json.obj("id" -> key, "enabled" -> false, "activationStrategy" -> "NO_STRATEGY")
//        ws.url(s"$rootPath/api/features")
//          .post(feature)
//          .futureValue
//          .status must be(201)
//
//        Thread.sleep(1500)
//
//        val strings: Seq[String] = ctx.state
//          .map(_.as[JsObject])
//          .flatMap { obj =>
//            (obj \ "objectsEdited").as[Seq[JsValue]]
//          }
//          .map { json =>
//            (json \ "type").as[String]
//          }
//        strings must contain theSameElementsAs Seq("CONFIG_CREATED", "FEATURE_CREATED")
//      }
//
//    }

    "call webhook on with filter" in {

      withServer(buildBasicServer()) { ctx =>
        val key = "my:webhook:test4"
        val webhook: JsObject = Json.obj("clientId" -> key,
                                         "callbackUrl" -> s"http://localhost:${ctx.port}/api/v1/events",
                                         "domains"     -> Json.arr("Config"),
                                         "headers"     -> Json.obj())

        ws.url(s"$rootPath/api/webhooks")
          .post(webhook)
          .futureValue must beAStatus(201)

        val config: JsObject = Json.obj("id" -> "my:config:test4", "value" -> "value")
        ws.url(s"$rootPath/api/configs")
          .post(config)
          .futureValue must beAStatus(201)

        val feature: JsObject =
          Json.obj("id" -> "my:feature:test4", "enabled" -> false, "activationStrategy" -> "NO_STRATEGY")
        ws.url(s"$rootPath/api/features")
          .post(feature)
          .futureValue must beAStatus(201)

        Thread.sleep(1100)

        val strings: Seq[String] = ctx.state
          .map(_.as[JsObject])
          .flatMap { obj =>
            (obj \ "objectsEdited").as[Seq[JsValue]]
          }
          .map { json =>
            (json \ "type").as[String]
          }

        strings must be(Seq("CONFIG_CREATED"))
      }

    }

  }

  private def formatResults(jsValue: JsValue): JsValue = jsValue match {
    case o: JsObject =>
      val results: Seq[JsValue] = (o \ "results").as[JsArray].value.map { r =>
        r.as[JsObject] - "created"
      }
      o ++ Json.obj("results" -> JsArray(results))
    case _ => jsValue
  }

  case class Context[T](host: String, port: Int, state: T)

  private def withServer[T](fn: () => (Route, T))(test: Context[T] => Unit): Unit = {

    val host = "localhost"
    val port = SocketUtil.temporaryServerAddress("localhost").getPort

    val (route: Route, state) = fn()

    val bindingFuture: Future[ServerBinding] =
      Http().bindAndHandle(route, host, port)
    val serverBinding = Await.result(bindingFuture, 1.second)

    try {
      test(Context(host, port, state))
    } finally {
      Await.result(serverBinding.unbind(), 1.seconds)
    }
  }

  private def buildBasicServer(): () => (Route, ListBuffer[JsValue]) = {
    import akka.http.scaladsl.model.StatusCodes._
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.Directives._
    import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
    () =>
      {
        val state = ListBuffer.empty[JsValue]

        val route =
          path("api" / "v1" / "events") {
            pathEnd {
              post {
                entity(as[JsValue]) { json =>
                  state.append(json)
                  complete(HttpResponse(OK))
                }
              }
            }
          }

        (route, state)
      }
  }

}
