package controllers

import java.util.concurrent.atomic.AtomicReference

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cats.data.NonEmptyList
import domains.Key
import domains.abtesting._
import multi.Configs
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.JsonBodyWritables._
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Random

class ExperimentControllerSpec(name: String, configurationSpec: Configuration, strict: Boolean = true)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration): Configuration =
    configuration ++ configurationSpec

  private lazy val ws                    = izanamiComponents.wsClient
  private implicit lazy val system       = izanamiComponents.actorSystem
  private implicit lazy val materializer = ActorMaterializer()

  private lazy val rootPath = s"http://localhost:$port"

  import ExperimentInstances._

  s"$name ExperimentController" should {

    "create read update delete deleteAll" in {
      val key = "my:path"
      /* First check */
      ws.url(s"$rootPath/api/experiments/$key")
        .get()
        .futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/experiments").get().futureValue must beAResponse(
        200,
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      val experiment = Experiment(
        id = Key(key),
        name = "a name",
        description = Some("A description"),
        enabled = false,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A", traffic = Traffic(0.4)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.6))
        )
      )
      /* Create */
      val jsonExperiment = Json.toJson(experiment)

      ws.url(s"$rootPath/api/experiments")
        .post(jsonExperiment)
        .futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/experiments/$key").get().futureValue must beAResponse(200, jsonExperiment)

      ws.url(s"$rootPath/api/experiments").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(jsonExperiment),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val experimentUpdated     = experiment.copy(enabled = true)
      val experimentUpdatedJson = Json.toJson(experimentUpdated)
      ws.url(s"$rootPath/api/experiments/$key")
        .put(experimentUpdatedJson)
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/experiments/$key").get().futureValue must beAResponse(200, experimentUpdatedJson)

      ws.url(s"$rootPath/api/experiments").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(experimentUpdatedJson),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/experiments/$key")
        .delete()
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/experiments/$key")
        .get()
        .futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/experiments").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Delete all */
      ws.url(s"$rootPath/api/experiments")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()
      ws.url(s"$rootPath/api/experiments").get().futureValue must beAResponse(
        200,
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )
    }

    "update changing id" in {

      val key  = "my:path:1"
      val key2 = "my:path:2"
      val experiment = Experiment(
        id = Key(key),
        name = "a name",
        description = Some("A description"),
        enabled = false,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A", traffic = Traffic(0.4)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.6))
        )
      )
      /* Create */
      val jsonExperiment = Json.toJson(experiment)

      ws.url(s"$rootPath/api/experiments")
        .post(jsonExperiment)
        .futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/experiments/$key").get().futureValue must beAResponse(200, jsonExperiment)

      /* Update */
      val experimentUpdatedJson = Json.toJson(experiment.copy(id = Key(key2), enabled = true))
      ws.url(s"$rootPath/api/experiments/$key")
        .put(experimentUpdatedJson)
        .futureValue must beAStatus(400)
    }

    "A/B testing population binding" in {

      /* Create */
      val key = "test1:ab:scenario"
      val experiment = Json.obj(
        "id"          -> key,
        "name"        -> "a name",
        "description" -> "A description",
        "enabled"     -> true,
        "variants" -> Json.arr(
          Json.obj("id" -> "A", "name" -> "A", "description" -> "A scenario", "traffic" -> 0.4),
          Json.obj("id" -> "B", "name" -> "B", "description" -> "B scenario", "traffic" -> 0.6)
        )
      )
      ws.url(s"$rootPath/api/experiments")
        .post(experiment)
        .futureValue must beAStatus(201)

      val variants: Seq[String] = Source(1 to 100)
        .mapAsync(10) { i =>
          Future {
            (ws
              .url(s"$rootPath/api/experiments/$key/variant")
              .addQueryStringParameters("clientId" -> s"$i")
              .get()
              .futureValue
              .json \ "id").as[String]
          }(system.dispatcher)
        }
        .runWith(Sink.seq)
        .futureValue

      val variantCount: Map[String, Int] = variants
        .groupBy(a => a)
        .map {
          case (k, l) =>
            (k, l.size)
        }

      variantCount("A") must equal(40 +- 5)
      variantCount("B") must equal(60 +- 5)

    }

    "A/B testing scenario" in {
      import cats.implicits._
      /* Create */
      val key = "test2:ab:scenario"
      val experiment: JsObject = Json.obj(
        "id"          -> key,
        "name"        -> "a name",
        "description" -> "A description",
        "enabled"     -> true,
        "variants" -> Json.arr(
          Json.obj("id" -> "A", "name" -> "A", "description" -> "A scenario", "traffic" -> 0.5),
          Json.obj("id" -> "B", "name" -> "B", "description" -> "B scenario", "traffic" -> 0.5)
        )
      )
      ws.url(s"$rootPath/api/experiments")
        .post(experiment)
        .futureValue must beAStatus(201)

      //val lastWin = new AtomicBoolean(true)
      val lastWin = new AtomicReference[Boolean](true)

      val variants = Await.result(
        Source(1 to 100)
          .mapAsync(10) { i =>
            Future {
              val variant = (ws
                .url(s"$rootPath/api/experiments/$key/variant")
                .addQueryStringParameters("clientId" -> s"$i")
                .get()
                .futureValue
                .json \ "id").as[String]
              // Client ! displayed and won
              ws.url(s"$rootPath/api/experiments/$key/displayed")
                .addQueryStringParameters("clientId" -> s"$i")
                .post("")
                .futureValue must beAStatus(200)
              ws.url(s"$rootPath/api/experiments/$key/displayed")
                .addQueryStringParameters("clientId" -> s"$i")
                .post("")
                .futureValue must beAStatus(200)

              // A always win and B 1/2
              if (variant == "A" || (variant == "B" && lastWin.getAndUpdate(v => !v))) {
                ws.url(s"$rootPath/api/experiments/$key/won")
                  .addQueryStringParameters("clientId" -> s"$i")
                  .post("")
                  .futureValue must beAStatus(200)
              }
              variant

            }(system.dispatcher)
          }
          .runWith(Sink.seq),
        5.minutes
      )

      val aCount = variants.count(_ == "A")
      val bCount = variants.count(_ == "B")

      println(s"A: $aCount")
      println(s"B: $bCount")

      val results =
        ws.url(s"$rootPath/api/experiments/$key/results").get().futureValue
      results must beAStatus(200)
      val eResult: ExperimentResult = results.json.validate[ExperimentResult].get

      // 2 Variants
      eResult.results.size must be(2)

      val variantA: VariantResult =
        eResult.results.find(v => v.variant.get.id == "A").get
      val variantB: VariantResult =
        eResult.results.find(v => v.variant.get.id == "B").get

      variantA.displayed must be(aCount * 2)
      variantA.won must be(aCount)

      Math.floor(variantA.transformation) must equal(50.0 +- 5)
      val eventsA: Seq[ExperimentResultEvent] = variantA.events
      if (strict) {
        eventsA.size must be(aCount * 3)
      } else {
        eventsA.size must be > 0
      }

      variantB.displayed must be(bCount * 2)
      variantB.won must be(bCount / 2)
      variantB.transformation must equal(25.0 +- 5)
      val eventsB: Seq[ExperimentResultEvent] = variantB.events
      if (strict) {
        eventsB.size must be((bCount * 2) + (bCount / 2))
      } else {
        eventsB.size must be > 0
      }
    }
  }
}
