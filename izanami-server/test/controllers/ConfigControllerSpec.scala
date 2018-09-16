package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}
import play.api.libs.ws.JsonBodyWritables._
import play.api.libs.ws.WSResponse

class ConfigControllerSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws       = izanamiComponents.wsClient
  private lazy val rootPath = s"http://localhost:$port"

  s"$name ConfigController" should {

    "create read update delete" in {
      val key = "my:path"
      /* First check */
      ws.url(s"$rootPath/api/configs/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/configs").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Create */
      val config = Json.obj("id" -> key, "value" -> "value")
      ws.url(s"$rootPath/api/configs").post(config).futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/configs/$key").get().futureValue must beAResponse(200, config)

      ws.url(s"$rootPath/api/configs").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(config),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val configUpdated = Json.obj("id" -> key, "value" -> "value updated")
      ws.url(s"$rootPath/api/configs/$key")
        .put(configUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      val getByIdUpdated =
        ws.url(s"$rootPath/api/configs/$key").get().futureValue
      getByIdUpdated must beAResponse(200, configUpdated)

      ws.url(s"$rootPath/api/configs").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(configUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/configs/$key").delete().futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/configs/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/configs").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /*Delete all*/
      ws.url(s"$rootPath/api/configs")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()
      ws.url(s"$rootPath/api/configs").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }

    "update changing id" in {

      val key  = "toto1@maif.fr"
      val key2 = "toto2@maif.fr"

      /* Create */
      val config = Json.obj("id" -> key, "value" -> "value")
      ws.url(s"$rootPath/api/configs").post(config).futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/configs/$key").get().futureValue must beAResponse(200, config)

      /* Update */
      val configUpdated = Json.obj("id" -> key2, "value" -> "value updated")
      ws.url(s"$rootPath/api/configs/$key")
        .put(configUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      val getByIdUpdated =
        ws.url(s"$rootPath/api/configs/$key2").get().futureValue
      getByIdUpdated must beAResponse(200, configUpdated)

      ws.url(s"$rootPath/api/configs/$key").get().futureValue must beAStatus(404)
    }
  }

}
