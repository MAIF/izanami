package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables._
import test.OneServerPerSuiteWithMyComponents

class ApikeyControllerSpec(configurationSpec: Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws = izanamiComponents.wsClient
  private lazy val rootPath = s"http://localhost:$port"

  "ApikeyController" should {

    "create read update delete" in {
      val key = "toto@maif.fr"
      /* First check */
      ws.url(s"$rootPath/api/apikeys/$key").get().futureValue.status must be(
        404)
      ws.url(s"$rootPath/api/apikeys").get().futureValue.json must be(
        Json.parse(
          """{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      val apikey =
        Json.obj("name" -> key,
                 "clientId" -> key,
                 "clientSecret" -> "clientSecret",
                 "authorizedPattern" -> "*")
      ws.url(s"$rootPath/api/apikeys").post(apikey).futureValue.status must be(
        201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/apikeys/$key").get().futureValue
      getById.status must be(200)
      getById.json must be(apikey)

      ws.url(s"$rootPath/api/apikeys").get().futureValue.json must be(
        Json.obj("results" -> Json.arr(apikey),
                 "metadata" -> Json.obj("page" -> 1,
                                        "pageSize" -> 15,
                                        "count" -> 1,
                                        "nbPages" -> 1))
      )

      /* Update */
      val apikeyUpdated =
        Json.obj("name" -> key,
                 "clientId" -> key,
                 "clientSecret" -> "clientSecret1",
                 "authorizedPattern" -> "monclubfacile:*")
      ws.url(s"$rootPath/api/apikeys/$key")
        .put(apikeyUpdated)
        .futureValue
        .status must be(200)

      /* Verify */
      val getByIdUpdated =
        ws.url(s"$rootPath/api/apikeys/$key").get().futureValue
      getByIdUpdated.status must be(200)
      getByIdUpdated.json must be(apikeyUpdated)

      ws.url(s"$rootPath/api/apikeys").get().futureValue.json must be(
        Json.obj("results" -> Json.arr(apikeyUpdated),
                 "metadata" -> Json.obj("page" -> 1,
                                        "pageSize" -> 15,
                                        "count" -> 1,
                                        "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/apikeys/$key").delete().futureValue.status must be(
        200)

      /* Verify */
      ws.url(s"$rootPath/api/apikeys/$key").get().futureValue.status must be(
        404)
      ws.url(s"$rootPath/api/apikeys").get().futureValue.json must be(
        Json.obj("results" -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1,
                                        "pageSize" -> 15,
                                        "count" -> 0,
                                        "nbPages" -> 0))
      )
    }

  }

}
