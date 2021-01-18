package izanami.features

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import izanami.FeatureEvent.{FeatureCreated, FeatureDeleted, FeatureUpdated}
import izanami._
import izanami.scaladsl.{Features, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import izanami.Strategy.FetchStrategy

import java.time.LocalDateTime
import java.time.LocalTime

class FetchFeatureClientSpec
    extends IzanamiSpec
    with BeforeAndAfterAll
    with MockitoSugar
    with FeatureServer
    with FeatureMockServer {

  implicit val system       = ActorSystem("test")
  implicit val materializer = Materializer.createMaterializer(system)

  import com.github.tomakehurst.wiremock.client.WireMock._
  import system.dispatcher

  "FetchFeatureStrategy" should {

    "create json feature" in {
      mock.resetRequests()
      val izanamiClient = IzanamiClient(
        ClientConfig(host)
      )
      //#error-strategy
      val featureClient = izanamiClient.featureClient(
        FetchStrategy(Crash)
      )
      //#error-strategy
      val featureId = "feature:test"

      val featureToCreate =
        HourRangeFeature(featureId, true, None, LocalTime.of(5, 25), LocalTime.of(16, 30))

      createFeature(featureToCreate)

      //#create-feature-json
      val featureCreated = featureClient.createJsonFeature(
        "feature:test",
        true,
        FeatureType.HOUR_RANGE,
        Some(Json.obj("startAt" -> "05:25", "endAt" -> "16:30"))
      )
      //#create-feature-json
      val feature = featureCreated.futureValue

      feature.id must be("feature:test")
      feature.enabled must be(true)
    }

    "create feature" in {
      mock.resetRequests()

      val izanamiClient = IzanamiClient(
        ClientConfig(host)
      )
      val featureClient = izanamiClient.featureClient(
        FetchStrategy(Crash)
      )
      val featureToCreate =
        DateRangeFeature("test2", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))

      createFeature(featureToCreate)
      //#create-feature
      val featureCreated = featureClient.createFeature(
        DateRangeFeature("test2", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))
      )
      //#create-feature
      val feature = featureCreated.futureValue

      feature must be(featureToCreate)

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/features"))
          .withRequestBody(equalToJson(Json.stringify(Json.toJson(featureToCreate))))
          .withHeader("Content-Type", containing("application/json"))
      )
    }

    "update feature" in {
      mock.resetRequests()

      val izanamiClient = IzanamiClient(
        ClientConfig(host)
      )
      val featureClient = izanamiClient.featureClient(
        FetchStrategy(Crash)
      )
      val featureToCreate =
        DateRangeFeature("test2", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))

      updateFeature("test", featureToCreate)

      //#update-feature
      val featureCreated = featureClient.updateFeature(
        "test",
        DateRangeFeature("test2", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))
      )
      //#update-feature
      val feature = featureCreated.futureValue

      feature must be(featureToCreate)

      mock.verifyThat(
        putRequestedFor(urlEqualTo("/api/features/test"))
          .withRequestBody(equalToJson(Json.stringify(Json.toJson(featureToCreate))))
          .withHeader("Content-Type", containing("application/json"))
      )
    }

    "switch feature" in {
      mock.resetRequests()

      val izanamiClient = IzanamiClient(
        ClientConfig(host)
      )
      val featureClient = izanamiClient.featureClient(
        FetchStrategy(Crash)
      )
      val featureToSwitch =
        DateRangeFeature("test", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))

      patchFeature("test", false, featureToSwitch)

      //#activate-feature
      val activated = featureClient.switchFeature("test", false)
      //#activate-feature
      activated.futureValue must be(featureToSwitch)
      mock.verifyThat(
        patchRequestedFor(urlEqualTo("/api/features/test"))
          .withRequestBody(
            equalToJson(
              Json.stringify(
                Json.arr(
                  Json.obj(
                    "op"    -> "replace",
                    "path"  -> "/enabled",
                    "value" -> false
                  )
                )
              )
            )
          )
          .withHeader("Content-Type", containing("application/json"))
      )
    }

    "delete feature" in {
      mock.resetRequests()

      val izanamiClient = IzanamiClient(
        ClientConfig(host)
      )
      val featureClient = izanamiClient.featureClient(
        FetchStrategy(Crash)
      )
      deleteFeature("test")

      //#delete-feature
      val deleted = featureClient.deleteFeature("test")
      //#delete-feature
      deleted.futureValue must be(())

      mock.verifyThat(
        deleteRequestedFor(urlEqualTo("/api/features/test"))
      )
    }

    "autocreate checking feature" in {
      mock.resetRequests()
      val feature =
        DateRangeFeature("test", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))
      val client = IzanamiClient(ClientConfig(host))
      //#feature-client-autocreate
      val featureClient = client
        .featureClient(
          FetchStrategy(Crash),
          autocreate = true,
          fallback = Features(feature)
        )
      //#feature-client-autocreate

      registerNoFeature()
      createFeature(feature)

      val futureCheck: Future[Boolean] = featureClient.checkFeature(feature.id)

      futureCheck.futureValue must be(false)

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/features"))
          .withRequestBody(equalToJson(Json.stringify(Json.toJson(feature))))
          .withHeader("Content-Type", containing("application/json"))
      )
    }

    "autocreate searching by pattern" in {
      mock.resetRequests()

      val feature1 = DefaultFeature("test1", true)
      val feature2 =
        DateRangeFeature("test2", true, LocalDateTime.of(2019, 4, 12, 0, 0, 0), LocalDateTime.of(2019, 5, 13, 0, 0, 0))
      val featureClient = IzanamiClient(ClientConfig(host))
        .featureClient(
          FetchStrategy(Crash),
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

    "List features" in {
      mock.resetRequests()

      val client = IzanamiClient(
        ClientConfig(host)
      )

      //#feature-client
      val featureClient = client.featureClient(
        strategy = FetchStrategy(),
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
      mock.resetRequests()

      //#fetch-strategy
      val strategy = IzanamiClient(
        ClientConfig(host, pageSize = 2)
      ).featureClient(
        Strategies.fetchStrategy()
      )
      //#fetch-strategy

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
      mock.resetRequests()

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
          FeatureCreated(Some(3), "filter:id3", ScriptFeature("id3", true, Some(true), Script("javascript", "{}"))),
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

  override def afterAll: Unit = {
    _wireMockServer.stop()
    TestKit.shutdownActorSystem(system)
  }

}
