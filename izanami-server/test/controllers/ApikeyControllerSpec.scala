package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables._
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}

class ApikeyControllerSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws       = izanamiComponents.wsClient
  private lazy val rootPath = s"http://localhost:$port"

  s"$name ApikeyController" should {

    "create read update delete" in {
      val key = "toto@maif.fr"
      /* First check */
      ws.url(s"$rootPath/api/apikeys/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/apikeys").get().futureValue must beAResponse(
        200,
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      val apikey =
        Json.obj("name" -> key, "clientId" -> key, "clientSecret" -> "clientSecret", "authorizedPattern" -> "*")
      ws.url(s"$rootPath/api/apikeys").post(apikey).futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/apikeys/$key").get().futureValue must beAResponse(200, apikey)

      ws.url(s"$rootPath/api/apikeys").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(apikey),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val apikeyUpdated =
        Json.obj("name"              -> key,
                 "clientId"          -> key,
                 "clientSecret"      -> "clientSecret1",
                 "authorizedPattern" -> "monclubfacile:*")
      ws.url(s"$rootPath/api/apikeys/$key")
        .put(apikeyUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      val getByIdUpdated =
        ws.url(s"$rootPath/api/apikeys/$key").get().futureValue
      getByIdUpdated must beAResponse(200, apikeyUpdated)

      ws.url(s"$rootPath/api/apikeys").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(apikeyUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/apikeys/$key").delete().futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/apikeys/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/apikeys").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /*Delete all*/
      ws.url(s"$rootPath/api/apikeys")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()
      ws.url(s"$rootPath/api/apikeys").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }

    "update changing id" in {

      val key  = "toto1@maif.fr"
      val key2 = "toto2@maif.fr"

      /* Create */
      val apikey =
        Json.obj("name" -> key, "clientId" -> key, "clientSecret" -> "clientSecret", "authorizedPattern" -> "*")
      ws.url(s"$rootPath/api/apikeys").post(apikey).futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/apikeys/$key").get().futureValue must beAResponse(200, apikey)

      /* Update */
      val apikeyUpdated =
        Json.obj("name"              -> key2,
                 "clientId"          -> key2,
                 "clientSecret"      -> "clientSecret1",
                 "authorizedPattern" -> "monclubfacile:*")
      ws.url(s"$rootPath/api/apikeys/$key")
        .put(apikeyUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/apikeys/$key2").get().futureValue must beAResponse(200, apikeyUpdated)

      ws.url(s"$rootPath/api/apikeys/$key").get().futureValue must beAStatus(404)
    }
  }

}
