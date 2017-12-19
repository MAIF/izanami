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
import play.api.libs.json.Json

class FetchConfigClientSpec extends IzanamiSpec with BeforeAndAfterAll with MockitoSugar with ConfigServer {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "FetchConfigStrategy" should {
    "List configs" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).configClient(
          strategy = Strategies.fetchStrategy(),
          fallback = Configs(
            "test2" -> Json.obj("value" -> 2)
          )
        )

        val initialConfigs = Seq(
          Config("test", Json.obj("value" -> 1))
        )
        ctx.setValues(initialConfigs)

        val configs: Configs = strategy.configs("*").futureValue

        configs.configs must be (initialConfigs)

        configs.get("test") must be(Json.obj("value" -> 1))
        configs.get("test2") must be(Json.obj("value" -> 2))
        configs.get("other") must be(Json.obj())
      }
    }

    "List configs with multiple pages" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host, pageSize = 2)
        ).configClient(
          strategy = Strategies.fetchStrategy(),
          fallback = Configs(
            "test2" -> Json.obj("value" -> 2)
          )
        )
        val initialConfigs = Seq(
          Config("test1", Json.obj("value" -> 1)),
          Config("test2", Json.obj("value" -> 1)),
          Config("test3", Json.obj("value" -> 1)),
          Config("test4", Json.obj("value" -> 1)),
          Config("test5", Json.obj("value" -> 1))
        )

        ctx.setValues(initialConfigs)

        val configs: Configs = strategy.configs("*").futureValue

        configs.configs must be (initialConfigs)
      }
    }

    "Get one config" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).configClient(
          strategy = Strategies.fetchStrategy(),
          fallback = Configs(
            "test2" -> Json.obj("value" -> 2)
          )
        )

        val initialConfigs = Seq(
          Config("test", Json.obj("value" -> 1))
        )
        ctx.setValues(initialConfigs)


        strategy.config("test").futureValue must be(Json.obj("value" -> 1))
        strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
        strategy.config("other").futureValue must be(Json.obj())
      }
    }

    "Stream event" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host).sseBackend()
        ).configClient(
          strategy = Strategies.fetchStrategy()
        )

        val expectedEvents = Seq(
          ConfigCreated("id1", Config("id1", Json.obj("config" -> 1))),
          ConfigUpdated("filter:id2", Config("id1", Json.obj("config" -> 1)), Config("id1", Json.obj("config" -> 2))),
          ConfigCreated("filter:id3", Config("id1", Json.obj("config" -> 3))),
          ConfigDeleted("id4"),
          ConfigDeleted("id5")
        )

        val fEvents = strategy.configsSource("*")
            .take(5)
            .runWith(Sink.seq)
        val fEvents2 = strategy.configsSource("filter:*")
            .take(2)
            .runWith(Sink.seq)

        Thread.sleep(50)

        expectedEvents.foreach(e => ctx.queue.offer(e))

        fEvents.futureValue must be (expectedEvents)
        fEvents2.futureValue must be (expectedEvents.filter(_.id.startsWith("filter:")))
      }
    }
  }

}
