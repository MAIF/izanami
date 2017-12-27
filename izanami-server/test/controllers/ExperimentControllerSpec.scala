package controllers

import domains.abtesting._
import elastic.client.ElasticClient
import multi.Configs
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.JsonBodyWritables._
import redis.embedded.RedisServer
import test.OneServerPerSuiteWithMyComponents

import scala.concurrent.Await
import scala.concurrent.duration.DurationDouble
import scala.concurrent.ExecutionContext.Implicits.global

class ExperimentControllerSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws = izanamiComponents.wsClient

  private lazy val rootPath = s"http://localhost:$port"

  s"$name ExperimentController" should {

    "create read update delete deleteAll" in {
      val key = "my:path"
      /* First check */
      ws.url(s"$rootPath/api/experiments/$key")
        .get()
        .futureValue
        .status must be(404)
      ws.url(s"$rootPath/api/experiments").get().futureValue.json must be(
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      val experiment = Json.obj("id" -> key,
                                "name"        -> "a name",
                                "description" -> "A description",
                                "enabled"     -> false,
                                "variants"    -> Json.arr())
      ws.url(s"$rootPath/api/experiments")
        .post(experiment)
        .futureValue
        .status must be(201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/experiments/$key").get().futureValue
      getById.status must be(200)
      getById.json must be(experiment)

      ws.url(s"$rootPath/api/experiments").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(experiment),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val experimentUpdated = Json.obj("id" -> key,
                                       "name"        -> "a name",
                                       "description" -> "A description",
                                       "enabled"     -> true,
                                       "variants"    -> Json.arr())
      ws.url(s"$rootPath/api/experiments/$key")
        .put(experimentUpdated)
        .futureValue
        .status must be(200)

      /* Verify */
      val getByIdUpdated =
        ws.url(s"$rootPath/api/experiments/$key").get().futureValue
      getByIdUpdated.status must be(200)
      getByIdUpdated.json must be(experimentUpdated)

      ws.url(s"$rootPath/api/experiments").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(experimentUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/experiments/$key")
        .delete()
        .futureValue
        .status must be(200)

      /* Verify */
      ws.url(s"$rootPath/api/experiments/$key")
        .get()
        .futureValue
        .status must be(404)
      ws.url(s"$rootPath/api/experiments").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Delete all */
      ws.url(s"$rootPath/api/experiments")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()
      ws.url(s"$rootPath/api/experiments").get().futureValue.json must be(
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )
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
        .futureValue
        .status must be(201)

      var variants = Seq.empty[String]

      // Client 1 displayed and won
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "1")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "1")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 2 displayed and won
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "2")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "2")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 3 displayed and won
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "3")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "3")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 4 displayed
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "4")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "4")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 5 displayed
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "5")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "5")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 6 displayed
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "6")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "6")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 7 displayed
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "7")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "7")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 8 displayed
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "8")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "8")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 9 displayed
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "9")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "9")
        .get()
        .futureValue
        .json \ "id").as[String]

      // Client 10 displayed
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "10")
        .post("")
        .futureValue
        .status must be(200)
      variants = variants :+ (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "10")
        .get()
        .futureValue
        .json \ "id").as[String]

      val variantCount: Map[String, Int] = variants.groupBy(a => a).map {
        case (key, l) => (key, l.size)
      }

      variantCount("A") must be(4)
      variantCount("B") must be(6)

    }

    "A/B testing scenario" in {

      /* Create */
      val key = "test2:ab:scenario"
      val experiment = Json.obj(
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
        .futureValue
        .status must be(201)

      // Client 1 displayed and won
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "1")
        .post("")
        .futureValue
        .status must be(200)
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "1")
        .post("")
        .futureValue
        .status must be(200)
      ws.url(s"$rootPath/api/experiments/$key/won")
        .addQueryStringParameters("clientId" -> "1")
        .post("")
        .futureValue
        .status must be(200)

      (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "1")
        .get()
        .futureValue
        .json \ "id").as[String] must be("A")
      // Client 2 displayed and won
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "2")
        .post("")
        .futureValue
        .status must be(200)
      (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "2")
        .get()
        .futureValue
        .json \ "id").as[String] must be("B")
      ws.url(s"$rootPath/api/experiments/$key/won")
        .addQueryStringParameters("clientId" -> "2")
        .post("")
        .futureValue
        .status must be(200)

      // Client 3 displayed and won
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "3")
        .post("")
        .futureValue
        .status must be(200)
      (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "3")
        .get()
        .futureValue
        .json \ "id").as[String] must be("A")
      ws.url(s"$rootPath/api/experiments/$key/won")
        .addQueryStringParameters("clientId" -> "3")
        .post("")
        .futureValue
        .status must be(200)

      // Client 4 displayed
      ws.url(s"$rootPath/api/experiments/$key/displayed")
        .addQueryStringParameters("clientId" -> "4")
        .post("")
        .futureValue
        .status must be(200)
      (ws
        .url(s"$rootPath/api/experiments/$key/variant")
        .addQueryStringParameters("clientId" -> "4")
        .get()
        .futureValue
        .json \ "id").as[String] must be("B")

      val results =
        ws.url(s"$rootPath/api/experiments/$key/results").get().futureValue
      results.status must be(200)

      val eResult: ExperimentResult =
        ExperimentResult.format.reads(results.json).get

      eResult.results.size must be(2)

      val variantA: VariantResult =
        eResult.results.find(v => v.variant.get.id == "A").get
      val variantB: VariantResult =
        eResult.results.find(v => v.variant.get.id == "B").get

      variantA.displayed must be(3)
      variantA.won must be(2)
      Math.floor(variantA.transformation) must be(66)
      val eventsA: Seq[ExperimentVariantEvent] = variantA.events
      eventsA.size must be > 0
      eventsA(0).isInstanceOf[ExperimentVariantDisplayed] must be(true)
      val eventA0: ExperimentVariantDisplayed =
        eventsA(0).asInstanceOf[ExperimentVariantDisplayed]
      eventA0.variantId must be("A")
      eventA0.variant.id must be("A")
      eventA0.transformation must be(0)
      eventsA.last match {
        case e: ExperimentVariantWon       => e.transformation must be(66.66666666666667)
        case e: ExperimentVariantDisplayed => e.transformation must be(66.66666666666667)
      }
      variantB.displayed must be(2)
      variantB.won must be(1)
      variantB.transformation must be(50)
      val eventsB: Seq[ExperimentVariantEvent] = variantB.events
      eventsB.size must be > 0
      eventsB.head.isInstanceOf[ExperimentVariantDisplayed] must be(true)
      val eventB0: ExperimentVariantDisplayed =
        eventsB(0).asInstanceOf[ExperimentVariantDisplayed]
      eventB0.variantId must be("B")
      eventB0.variant.id must be("B")
      eventB0.transformation must be(0)
      eventsB.last match {
        case e: ExperimentVariantWon       => e.transformation must be(50)
        case e: ExperimentVariantDisplayed => e.transformation must be(50)
      }
    }
  }
}
