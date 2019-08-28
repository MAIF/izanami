package izanami.configs

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import izanami._
import izanami.scaladsl.ConfigEvent.{ConfigCreated, ConfigDeleted, ConfigUpdated}
import izanami.scaladsl.{Config, Configs, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import izanami.Strategy.FetchStrategy

class FetchConfigClientSpec
    extends IzanamiSpec
    with BeforeAndAfterAll
    with MockitoSugar
    with ConfigServer
    with ConfigMockServer {

  implicit val system       = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher
  import com.github.tomakehurst.wiremock.client.WireMock._

  "FetchConfigStrategy" should {

    "Create config" in {

      val client = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = FetchStrategy(),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )

      val jsonBody = Json.stringify(Json.toJson(Config("test", Json.obj("value" -> 1))))
      mock.register(
        post(urlPathEqualTo("/api/configs"))
          .withHeader("Content-Type", containing("application/json"))
          .withRequestBody(equalToJson(jsonBody))
          .willReturn(
            aResponse()
              .withStatus(201)
              .withBody(jsonBody)
          )
      )

      val config  = Config("test", Json.obj("value" -> 1))
      val created = client.createConfig(config.id, config).futureValue
      created must be(config)

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/configs"))
          .withHeader("Content-Type", containing("application/json"))
      )
      mock.resetRequests()
    }

    "List configs" in {

      val client = IzanamiClient(
        ClientConfig(host)
      )
      //#config-client
      val configClient = client.configClient(
        strategy = FetchStrategy(),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )
      //#config-client

      val initialConfigs = Seq(
        Config("test", Json.obj("value" -> 1))
      )

      registerPage(initialConfigs)

      val configs: Configs = configClient.configs("*").futureValue

      configs.configs must be(initialConfigs)

      configs.get("test") must be(Json.obj("value"  -> 1))
      configs.get("test2") must be(Json.obj("value" -> 2))
      configs.get("other") must be(Json.obj())
      mock.resetRequests()
    }

    "List configs with multiple pages" in {

      val izanamiClient = IzanamiClient(ClientConfig(host, pageSize = 2))
      //#config-error-strategy
      val configClient = izanamiClient.configClient(
        strategy = FetchStrategy(Crash),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )
      //#config-error-strategy

      val firstGroup = Seq(
        Config("test1", Json.obj("value" -> 1)),
        Config("test2", Json.obj("value" -> 1))
      )

      val secondGroup = Seq(
        Config("test3", Json.obj("value" -> 1)),
        Config("test4", Json.obj("value" -> 1))
      )

      val thirdGroup = Seq(
        Config("test5", Json.obj("value" -> 1))
      )

      val initialConfigs: Seq[Config] = firstGroup ++ secondGroup ++ thirdGroup

      registerPage(group = firstGroup, page = 1, pageSize = 2, count = 5)
      registerPage(group = secondGroup, page = 2, pageSize = 2, count = 5)
      registerPage(group = thirdGroup, page = 3, pageSize = 2, count = 5)

      //#all-configs
      val configs: Future[Configs] = configClient.configs("*")
      configs.onComplete {
        case Success(c) => println(c)
        case Failure(e) => e.printStackTrace()
      }
      //#all-configs

      configs.futureValue.configs must be(initialConfigs)
      mock.resetRequests()
    }

    "Get one config" in {

      val izanamiClient = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = Strategies.fetchStrategy(),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )

      registerConfig(Config("test", Json.obj("value" -> 1)))

      //#one-config
      val futureConfig: Future[JsValue] = izanamiClient.config("test")
      futureConfig.onComplete {
        case Success(c) => println(c)
        case Failure(e) => e.printStackTrace()
      }
      //#one-config
      futureConfig.futureValue must be(Json.obj("value"                  -> 1))
      izanamiClient.config("test2").futureValue must be(Json.obj("value" -> 2))
      izanamiClient.config("other").futureValue must be(Json.obj())
      mock.resetRequests()
    }

    "Stream event" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host).sseBackend()
        ).configClient(
          strategy = Strategies.fetchStrategy()
        )

        val expectedEvents = Seq(
          ConfigCreated(Some(1), "id1", Config("id1", Json.obj("config" -> 1))),
          ConfigUpdated(Some(2),
                        "filter:id2",
                        Config("id1", Json.obj("config"                        -> 1)),
                        Config("id1", Json.obj("config"                        -> 2))),
          ConfigCreated(Some(3), "filter:id3", Config("id1", Json.obj("config" -> 3))),
          ConfigDeleted(Some(4), "id4"),
          ConfigDeleted(Some(5), "id5")
        )

        val fEvents = strategy
          .configsSource("*")
          .take(5)
          .runWith(Sink.seq)
        val fEvents2 = strategy
          .configsSource("filter:*")
          .take(2)
          .runWith(Sink.seq)

        Thread.sleep(50)

        expectedEvents.foreach(e => ctx.queue.offer(e))

        fEvents.futureValue must be(expectedEvents)
        fEvents2.futureValue must be(expectedEvents.filter(_.id.startsWith("filter:")))
      }
    }
  }

  override def afterAll {
    _wireMockServer.stop()
    TestKit.shutdownActorSystem(system)
  }
}
