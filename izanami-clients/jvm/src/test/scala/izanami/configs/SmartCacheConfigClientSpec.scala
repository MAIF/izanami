package izanami.configs

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import izanami.FeatureEvent.FeatureUpdated
import izanami.Strategy.{CacheWithPollingStrategy, CacheWithSseStrategy}
import izanami._
import izanami.scaladsl.ConfigEvent.ConfigUpdated
import izanami.scaladsl.{Config, Configs, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.Json

import scala.concurrent.duration.DurationInt

class SmartCacheConfigClientSpec extends IzanamiSpec with BeforeAndAfterAll with MockitoSugar with ConfigServer {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "SmartCacheConfigStrategy" should {
    "Configs by pattern with polling" in {
      runServer { ctx =>
        import akka.pattern
        //Init
        val initialConfigs = Seq(
          Config("test1", Json.obj("value" -> 1))
        )
        ctx.setValues(initialConfigs)


        val fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).configClient(
          strategy = CacheWithPollingStrategy(
            patterns = Seq("*"),
            pollingInterval = 3.second
          ),
          fallback = fallback
        )

        //Waiting for the client to start polling
        val configs: Configs = pattern.after(2.second, system.scheduler){
          strategy.configs("*")
        }.futureValue

        configs.configs must be (fallback.configs ++ initialConfigs)

        //Only one call for the first fetch
        ctx.calls.size must be(1)
        configs.get("test1") must be(Json.obj("value" -> 1))
        configs.get("test2") must be(Json.obj("value" -> 2))
        configs.get("other") must be(Json.obj())

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.config("test1").futureValue must be(Json.obj("value" -> 1))
        strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
        strategy.config("other").futureValue must be(Json.obj())
        ctx.calls.size must be(1)

        //We update the config on thebackend
        val updatedConfigs = Seq(
          Config("test1", Json.obj("value" -> 3))
        )
        ctx.setValues(updatedConfigs)

        //We wait for the polling
        val configsUpdated: Configs = pattern.after(2.second, system.scheduler){
          strategy.configs("*")
        }.futureValue

        //Now we have two call : the first fetch and the polling
        ctx.calls.size must be(2)

        configsUpdated.configs must be (fallback.configs ++ updatedConfigs)
        configsUpdated.get("test1") must be(Json.obj("value" -> 3))
        configsUpdated.get("test2") must be(Json.obj("value" -> 2))
        configsUpdated.get("other") must be(Json.obj())

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.config("test1").futureValue must be(Json.obj("value" -> 3))
        strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
        strategy.config("other").futureValue must be(Json.obj())
        ctx.calls.size must be(2)
      }
    }

    "Features by pattern with sse" in {
      runServer { ctx =>
        import akka.pattern
        //Init
        val initialConfigs = Seq(
          Config("test1", Json.obj("value" -> 1))
        )
        ctx.setValues(initialConfigs)


        val fallback = Configs(
          "test2" -> Json.obj("value" -> 2)
        )
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).configClient(
          strategy = CacheWithSseStrategy(
            patterns = Seq("*")
          ),
          fallback = fallback
        )


        //Waiting for the client to start polling
        val configs: Configs = pattern.after(2.second, system.scheduler){
          strategy.configs("*")
        }.futureValue

        configs.configs must be (fallback.configs ++ initialConfigs)

        //Only one call for the first fetch
        ctx.calls.size must be(1)
        configs.get("test1") must be(Json.obj("value" -> 1))
        configs.get("test2") must be(Json.obj("value" -> 2))
        configs.get("other") must be(Json.obj())

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.config("test1").futureValue must be(Json.obj("value" -> 1))
        strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
        strategy.config("other").futureValue must be(Json.obj())
        ctx.calls.size must be(1)

        // We update config via sse
        ctx.push(ConfigUpdated("test1", Config("test1", Json.obj("value" -> 3)), Config("test1", Json.obj("value" -> 1))))

        //We wait that the events arrive
        val configsUpdated: Configs = pattern.after(500.milliseconds, system.scheduler){
          strategy.configs("*")
        }.futureValue

        //With SSE we should only have the first fetch
        ctx.calls.size must be(1)

        configsUpdated.get("test1") must be(Json.obj("value" -> 3))
        configsUpdated.get("test2") must be(Json.obj("value" -> 2))
        configsUpdated.get("other") must be(Json.obj())


        // As the data are in cache for the pattern, this should not generate an http call
        strategy.config("test1").futureValue must be(Json.obj("value" -> 3))
        strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
        strategy.config("other").futureValue must be(Json.obj())
        ctx.calls.size must be(1)
      }
    }
  }

}
