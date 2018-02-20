package izanami.features

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import com.github.tomakehurst.wiremock.client.WireMock._
import izanami.FeatureEvent.{FeatureCreated, FeatureDeleted, FeatureUpdated}
import izanami._
import izanami.scaladsl.{Features, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future
import scala.util.{Failure, Success}

class FetchFeatureClientSpec
    extends IzanamiSpec
    with BeforeAndAfterAll
    with MockitoSugar
    with FeatureServer
    with FeatureMockServer {

  implicit val system       = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

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

      //#list
      val futureFeatures: Future[Features] = featureClient.features("*")
      futureFeatures.onComplete {
        case Success(features) =>
          val active: Boolean = features.isActive("test")
          if (active)
            println(s"Feature test is active")
          else
            println(s"Feature test is not active")

          val tree: JsObject = features.tree()
          println(s"All features: ${Json.prettyPrint(tree)}")

        case Failure(e) =>
          e.printStackTrace()
      }
      //#list

      val features: Features = futureFeatures.futureValue

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

      val featureClient = IzanamiClient(
        ClientConfig(host)
      ).featureClient(
        strategy = Strategies.fetchStrategy(),
        fallback = Features(
          DefaultFeature("test2", true)
        )
      )

      val initialFeatures = Seq(
        DefaultFeature("test", true)
      )

      registerCheckFeature("test")

      //#check
      val futureCheck: Future[Boolean] = featureClient.checkFeature("test")
      //#check

      futureCheck.futureValue must be(true)
      mock.verifyThat(
        postRequestedFor(urlPathEqualTo("/api/features/test/check"))
          .withRequestBody(equalTo("{}"))
      )

      registerCheckFeature("test2")

      //#check-context
      val context                           = Json.obj("context" -> true)
      val checkWithContext: Future[Boolean] = featureClient.checkFeature("test", context)
      //#check-context

      //#check-conditional
      val conditonal: Future[String] = featureClient.featureOrElse("test") {
        "Feature is active"
      } {
        "Feature is not active"
      }
      //#check-conditional

      //#check-conditional-context
      val conditonalWithContext: Future[String] = featureClient.featureOrElse("test", context) {
        "Feature is active"
      } {
        "Feature is not active"
      }
      //#check-conditional-context

      conditonal.futureValue must be("Feature is active")
      conditonalWithContext.futureValue must be("Feature is active")
      checkWithContext.futureValue must be(true)

      mock.verifyThat(
        postRequestedFor(urlPathEqualTo("/api/features/test/check"))
          .withRequestBody(equalTo(Json.stringify(context)))
      )

      featureClient.checkFeature("test2").futureValue must be(true)

      featureClient
        .checkFeature("test2", Json.obj("context" -> true))
        .futureValue must be(true)

      featureClient.checkFeature("other").futureValue must be(false)

    }

    "Stream event" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host).sseBackend()
        ).featureClient(
          strategy = Strategies.fetchStrategy()
        )

        val expectedEvents = Seq(
          FeatureCreated(Some(1), "id1", DefaultFeature("id1", true)),
          FeatureUpdated(Some(2), "filter:id2", DefaultFeature("id2", true), DefaultFeature("id2", false)),
          FeatureCreated(Some(3), "filter:id3", ScriptFeature("id3", true, Some(true), "script")),
          FeatureDeleted(Some(4), "id4"),
          FeatureDeleted(Some(5), "id5")
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
        fEvents2.futureValue must be(expectedEvents.filter(_.id.startsWith("filter:")))
      }
    }
  }

  override def afterAll {
    _wireMockServer.stop()
    TestKit.shutdownActorSystem(system)
  }

}
