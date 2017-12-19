package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import test.OneServerPerSuiteWithMyComponents
import play.api.libs.ws.JsonBodyWritables._

class ConfigControllerSpec(configurationSpec: Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws       = izanamiComponents.wsClient
  private lazy val rootPath = s"http://localhost:$port"

  "ConfigController" should {

    "create read update delete" in {
      val key = "my:path"
      /* First check */
      ws.url(s"$rootPath/api/configs/$key").get().futureValue.status must be(404)
      ws.url(s"$rootPath/api/configs").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Create */
      val config = Json.obj("id" -> key, "value" -> "value")
      ws.url(s"$rootPath/api/configs").post(config).futureValue.status must be(201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/configs/$key").get().futureValue
      getById.status must be(200)
      getById.json must be(config)

      ws.url(s"$rootPath/api/configs").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(config),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val configUpdated = Json.obj("id" -> key, "value" -> "value updated")
      ws.url(s"$rootPath/api/configs/$key").put(configUpdated).futureValue.status must be(200)

      /* Verify */
      val getByIdUpdated = ws.url(s"$rootPath/api/configs/$key").get().futureValue
      getByIdUpdated.status must be(200)
      getByIdUpdated.json must be(configUpdated)

      ws.url(s"$rootPath/api/configs").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(configUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/configs/$key").delete().futureValue.status must be(200)

      /* Verify */
      ws.url(s"$rootPath/api/configs/$key").get().futureValue.status must be(404)
      ws.url(s"$rootPath/api/configs").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }

  }

}
