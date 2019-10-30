package izanami.features

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.github.tomakehurst.wiremock.client.WireMock.{containing, equalTo, postRequestedFor, urlEqualTo}
import izanami.Strategy.FetchWithCacheStrategy
import izanami._
import izanami.scaladsl.{Features, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class FetchWithCacheFeatureClientSpec
    extends IzanamiSpec
    with BeforeAndAfterAll
    with MockitoSugar
    with FeatureServer
    with FeatureMockServer {

  implicit val system       = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "FetchWithCacheFeatureStrategy" should {
    "List features" in {
      runServer { ctx =>
        //#fetch-cache
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(
          strategy = FetchWithCacheStrategy(maxElement = 2, duration = 1.second),
          fallback = Features(
            DefaultFeature("test2", true)
          )
        )
        //#fetch-cache

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

    "autocreate checking feature" in {
      mock.resetRequests()
      val feature =
        DateRangeFeature("test", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))
      val client = IzanamiClient(ClientConfig(host))
      val featureClient = client
        .featureClient(
          FetchWithCacheStrategy(2, 1.second),
          autocreate = true,
          fallback = Features(feature)
        )

      registerPage(group = Seq.empty, pattern = "test")

      val futureCheck: Future[Boolean] = featureClient.checkFeature(feature.id)

      futureCheck.futureValue must be(false)

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/features.ndjson"))
          .withRequestBody(equalTo(s"""${Json.stringify(Json.toJson(feature))}""".stripMargin))
          .withHeader("Content-Type", containing("application/nd-json"))
      )
    }

    "autocreate searching by pattern" in {
      mock.resetRequests()

      val feature1 = DefaultFeature("test1", true)
      val feature2 =
        DateRangeFeature("test2", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))
      val featureClient = IzanamiClient(ClientConfig(host))
        .featureClient(
          FetchWithCacheStrategy(2, 1.second),
          autocreate = true,
          fallback = Features(
            feature1,
            feature2
          )
        )
      registerPage(group = Seq.empty)
      featureClient.features("*").futureValue

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/features.ndjson"))
          .withRequestBody(equalTo(s"""${Json.stringify(Json.toJson(feature1))}
                                      |${Json.stringify(Json.toJson(feature2))}""".stripMargin))
          .withHeader("Content-Type", containing("application/nd-json"))
      )
    }
  }

}
