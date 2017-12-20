package izanami.features

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import izanami.FeatureEvent.{FeatureCreated, FeatureDeleted, FeatureUpdated}
import izanami._
import izanami.scaladsl.{Features, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.{JsArray, Json}

class FetchFeatureClientSpec
    extends IzanamiSpec
    with BeforeAndAfterAll
    with MockitoSugar
    with FeatureServer
    with FeatureMockServer {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  "FetchFeatureStrategy" should {
    "List features" in {

      val client = IzanamiClient(
        ClientConfig(host)
      )

      //#feature-client
      val featureClient = client.featureClient(
        strategy = Strategies.fetchStrategy(),
        fallback = Features(
          DefaultFeature("test2", true)
        )
      )
      //#feature-client

      val initialFeatures = Seq(
        DefaultFeature("test", true)
      )

      registerPage(group = initialFeatures)

      val features: Features = featureClient.features("*").futureValue

      features.featuresSeq must be(initialFeatures)

      features.isActive("test") must be(true)
      features.isActive("test2") must be(true)
      features.isActive("other") must be(false)

    }

    "List features multiple pages" in {

      val strategy = IzanamiClient(
        ClientConfig(host, pageSize = 2)
      ).featureClient(
        Strategies.fetchStrategy()
      )

      val firstGroup =
        Seq(DefaultFeature("test1", true), DefaultFeature("test2", true))
      val secondGroup =
        Seq(DefaultFeature("test3", true), DefaultFeature("test4", true))
      val thirdGroup = Seq(DefaultFeature("test5", true))

      registerPage(group = firstGroup, pageSize = 2, count = 5)
      registerPage(group = secondGroup, page = 2, pageSize = 2, count = 5)
      registerPage(group = thirdGroup, page = 3, pageSize = 2, count = 5)

      val initialFeatures = firstGroup ++ secondGroup ++ thirdGroup

      val features: Features = strategy.features("*").futureValue

      features.featuresSeq must be(initialFeatures)

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
        strategy
          .checkFeature("test", Json.obj("context" -> true))
          .futureValue must be(true)
        strategy.checkFeature("test2").futureValue must be(true)
        strategy
          .checkFeature("test2", Json.obj("context" -> true))
          .futureValue must be(true)
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
          FeatureUpdated("filter:id2",
                         DefaultFeature("id2", true),
                         DefaultFeature("id2", false)),
          FeatureCreated("filter:id3",
                         ScriptFeature("id3", true, Some(true), "script")),
          FeatureDeleted("id4"),
          FeatureDeleted("id5")
        )

        val fEvents = strategy
          .featuresSource("*")
          .take(5)
          .runWith(Sink.seq)
        val fEvents2 = strategy
          .featuresSource("filter:*")
          .take(2)
          .runWith(Sink.seq)

        Thread.sleep(50)

        expectedEvents.foreach(e => ctx.queue.offer(e))

        fEvents.futureValue must be(expectedEvents)
        fEvents2.futureValue must be(
          expectedEvents.filter(_.id.startsWith("filter:")))
      }
    }
  }
  override def afterAll {
    _wireMockServer.stop()
    TestKit.shutdownActorSystem(system)
  }

}
