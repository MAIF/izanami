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

    "create config" in {
      mock.resetRequests()
      val client = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = FetchStrategy(),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )

      val config   = Config("test", Json.obj("value" -> 1))
      val jsonBody = Json.stringify(Json.toJson(config))
      registerCreateConfig(config)

      //#create-config
      val jsoncreated = client.createConfig(Config("test", Json.obj("value" -> 1)))
      //#create-config
      jsoncreated.futureValue must be(config)
      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/configs"))
          .withRequestBody(equalToJson(jsonBody))
          .withHeader("Content-Type", containing("application/json"))
      )

    }

    "create config json" in {
      mock.resetRequests()
      val client = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = FetchStrategy(),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )
      val config   = Config("test", Json.obj("value" -> 1))
      val jsonBody = Json.stringify(Json.toJson(config))
      registerCreateConfig(config)

      //#create-config-json
      val configCreated = client.createConfig("test", Json.obj("value" -> 1))
      //#create-config-json
      configCreated.futureValue must be(Json.toJson(config))

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/configs"))
          .withRequestBody(equalToJson(jsonBody))
          .withHeader("Content-Type", containing("application/json"))
      )

    }

    "update config" in {
      mock.resetRequests()
      val client = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = FetchStrategy(),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )

      val config   = Config("newtest", Json.obj("value" -> 1))
      val jsonBody = Json.stringify(Json.toJson(config))

      registerUpdateConfig("test", config)

      //#update-config
      val configUpdated = client.updateConfig("test", Config("newtest", Json.obj("value" -> 1)))
      //#update-config
      configUpdated.futureValue must be(config)

      mock.verifyThat(
        putRequestedFor(urlEqualTo("/api/configs/test"))
          .withRequestBody(equalToJson(jsonBody))
          .withHeader("Content-Type", containing("application/json"))
      )

    }

    "update config json" in {
      mock.resetRequests()
      val client = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = FetchStrategy(),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )
      val config   = Config("newtest", Json.obj("value" -> 1))
      val jsonBody = Json.stringify(Json.toJson(config))
      registerUpdateConfig("test", config)

      //#update-config-json
      val configUpdated = client.updateConfig("test", "newtest", Json.obj("value" -> 1))
      //#update-config-json
      configUpdated.futureValue must be(Json.toJson(config))
      mock.verifyThat(
        putRequestedFor(urlEqualTo("/api/configs/test"))
          .withRequestBody(equalToJson(jsonBody))
          .withHeader("Content-Type", containing("application/json"))
      )

    }

    "delete config" in {
      mock.resetRequests()
      val client = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = FetchStrategy(),
        fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
      )
      mock.register(
        delete(urlPathEqualTo("/api/configs/test"))
          .willReturn(
            aResponse()
              .withStatus(204)
          )
      )
      //#delete-config
      val configDeleted = client.deleteConfig("test")
      //#delete-config
      configDeleted.futureValue must be(())

      mock.verifyThat(
        deleteRequestedFor(urlEqualTo("/api/configs/test"))
      )

    }

    "autocreate getting config" in {
      mock.resetRequests()

      val client = IzanamiClient(
        ClientConfig(host)
      )
      //#config-autocreate
      val izanamiClient = client.configClient(
        strategy = Strategies.fetchStrategy(),
        fallback = Configs(
          "test" -> Json.obj("value" -> 2)
        ),
        autocreate = true
      )
      //#config-autocreate

      registerNoConfig()
      registerCreateConfig(Config("test", Json.obj("value" -> 2)))

      val futureConfig = izanamiClient.config("test").futureValue

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/configs.ndjson"))
          .withRequestBody(equalTo(Json.stringify(Json.obj("id" -> "test", "value" -> Json.obj("value" -> 2)))))
          .withHeader("Content-Type", containing("application/json"))
      )

    }

    "autocreate listing config" in {
      mock.resetRequests()

      val client = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = FetchStrategy(),
        fallback = Configs(
          "test" -> Json.obj("value" -> 2)
        ),
        autocreate = true
      )
      registerPage(Seq.empty)
      registerCreateConfig(Config("test", Json.obj("value" -> 2)))

      val configs: Configs = client.configs("*").futureValue

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/configs"))
          .withRequestBody(equalToJson(Json.stringify(Json.obj("id" -> "test", "value" -> Json.obj("value" -> 2)))))
          .withHeader("Content-Type", containing("application/json"))
      )

    }

    "List configs" in {
      mock.resetRequests()

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

    }

    "List configs with multiple pages" in {
      mock.resetRequests()

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

    }

    "Get one config" in {
      mock.resetRequests()

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
