package izanami.features

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import izanami.FeatureEvent.{FeatureCreated, FeatureDeleted, FeatureUpdated}
import izanami._
import izanami.scaladsl.{IzanamiClient, Features}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.Json

class FetchFeatureClientSpec extends IzanamiSpec with BeforeAndAfterAll with MockitoSugar with FeatureServer {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "FetchFeatureStrategy" should {
    "List features" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(
          strategy = Strategies.fetchStrategy(),
          fallback = Features(
            DefaultFeature("test2", true)
          )
        )

        val initialFeatures = Seq(
          DefaultFeature("test", true)
        )
        ctx.setValues(initialFeatures)

        val features: Features = strategy.features("*").futureValue

        features.featuresSeq must be (initialFeatures)

        features.isActive("test") must be(true)
        features.isActive("test2") must be(true)
        features.isActive("other") must be(false)
      }
    }

    "List features multiple pages" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host, pageSize = 2)
        ).featureClient(
          Strategies.fetchStrategy()
        )

        val initialFeatures = Seq(
          DefaultFeature("test1", true),
          DefaultFeature("test2", true),
          DefaultFeature("test3", true),
          DefaultFeature("test4", true),
          DefaultFeature("test5", true)
        )
        ctx.setValues(initialFeatures)

        val features: Features = strategy.features("*").futureValue

        features.featuresSeq must be (initialFeatures)
      }
    }

    "Test feature active" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(
          strategy = Strategies.fetchStrategy(),
          fallback = Features(
            DefaultFeature("test2", true)
          )
        )

        val initialFeatures = Seq(
          DefaultFeature("test", true)
        )
        ctx.setValues(initialFeatures)

        strategy.checkFeature("test").futureValue must be(true)
        strategy.checkFeature("test", Json.obj("context" -> true)).futureValue must be(true)
        strategy.checkFeature("test2").futureValue must be(true)
        strategy.checkFeature("test2", Json.obj("context" -> true)).futureValue must be(true)
        strategy.checkFeature("other").futureValue must be(false)
      }
    }

    "Stream event" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host).sseBackend()
        ).featureClient(
          strategy = Strategies.fetchStrategy()
        )


        val expectedEvents = Seq(
          FeatureCreated("id1", DefaultFeature("id1", true)),
          FeatureUpdated("filter:id2", DefaultFeature("id2", true), DefaultFeature("id2", false)),
          FeatureCreated("filter:id3", ScriptFeature("id3", true, Some(true), "script")),
          FeatureDeleted("id4"),
          FeatureDeleted("id5")
        )

        val fEvents = strategy.featuresSource("*")
            .take(5)
            .runWith(Sink.seq)
        val fEvents2 = strategy.featuresSource("filter:*")
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
