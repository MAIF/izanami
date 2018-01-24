package izanami.features

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import izanami.Strategy.FetchWithCacheStrategy
import izanami._
import izanami.scaladsl.{Features, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.Json

import scala.concurrent.duration.DurationInt

class FetchWithCacheFeatureClientSpec extends IzanamiSpec with BeforeAndAfterAll with MockitoSugar with FeatureServer {

  implicit val system       = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "FetchWithCacheFeatureStrategy" should {
    "List features" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(
          strategy = FetchWithCacheStrategy(2, 1.second),
          fallback = Features(
            DefaultFeature("test2", true)
          )
        )

        val initialFeatures = Seq(
          DefaultFeature("test1", true)
        )
        ctx.setValues(initialFeatures)

        val features: Features = strategy.features("*").futureValue
        strategy.features("*").futureValue
        strategy.features("*").futureValue

        features.featuresSeq must be(initialFeatures)
        ctx.calls.size must be(1)

        features.isActive("test1") must be(true)
        features.isActive("test2") must be(true)
        features.isActive("other") must be(false)
      }
    }

    "Test feature active" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(strategy = FetchWithCacheStrategy(2, 1.second),
                        fallback = Features(
                          DefaultFeature("test4", true)
                        ))

        val initialFeatures = Seq(
          DefaultFeature("test1", true),
          DefaultFeature("test2", true),
          DefaultFeature("test3", true),
          DefaultFeature("test4", true),
        )
        ctx.setValues(initialFeatures)

        strategy.checkFeature("test1").futureValue must be(true)
        ctx.calls must have size 1
        strategy
          .checkFeature("test1", Json.obj("context" -> true))
          .futureValue must be(true)
        ctx.calls must have size 2
        strategy.checkFeature("test1").futureValue must be(true)
        strategy
          .checkFeature("test1", Json.obj("context" -> true))
          .futureValue must be(true)
        ctx.calls must have size 2

        strategy.checkFeature("test1").futureValue must be(true)
        strategy
          .checkFeature("test1", Json.obj("context" -> true))
          .futureValue must be(true)
        ctx.calls must have size 2

        strategy.checkFeature("test2").futureValue must be(true)
        ctx.calls must have size 3

        strategy.checkFeature("other").futureValue must be(false)
      }
    }
  }

}
