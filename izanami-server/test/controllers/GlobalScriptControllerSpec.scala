package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}
import play.api.libs.ws.JsonBodyWritables._

class GlobalScriptControllerSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws = izanamiComponents.wsClient

  private lazy val rootPath = s"http://localhost:$port"

  s"$name GlobalScriptController" should {

    "create read update delete deleteAll" in {
      val key = "my:path"
      /* First check */
      ws.url(s"$rootPath/api/scripts/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/scripts").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Create */
      val script =
        Json.obj("id" -> key, "name" -> "test", "description" -> "A test script", "source" -> "function() {}")
      ws.url(s"$rootPath/api/scripts").post(script).futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/scripts/$key").get().futureValue must beAResponse(200, script)

      ws.url(s"$rootPath/api/scripts").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(script),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val scriptUpdated =
        Json.obj("id"          -> key,
                 "name"        -> "test",
                 "description" -> "A test script",
                 "source"      -> "function() { console.log('hello')}")
      ws.url(s"$rootPath/api/scripts/$key")
        .put(scriptUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      val getByIdUpdated =
      ws.url(s"$rootPath/api/scripts/$key").get().futureValue must beAResponse(200, scriptUpdated)

      ws.url(s"$rootPath/api/scripts").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(scriptUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/scripts/$key").delete().futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/scripts/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/scripts").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Delete all */
      ws.url(s"$rootPath/api/scripts")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()
      ws.url(s"$rootPath/api/scripts").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }

    "update changing id" in {

      val key  = "toto1@maif.fr"
      val key2 = "toto2@maif.fr"

      /* Create */
      val script =
        Json.obj("id" -> key, "name" -> "test", "description" -> "A test script", "source" -> "function() {}")
      ws.url(s"$rootPath/api/scripts").post(script).futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/scripts/$key").get().futureValue must beAResponse(200, script)

      /* Update */
      val scriptUpdated =
        Json.obj("id"          -> key2,
                 "name"        -> "test",
                 "description" -> "A test script",
                 "source"      -> "function() { console.log('hello')}")
      ws.url(s"$rootPath/api/scripts/$key")
        .put(scriptUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/scripts/$key2").get().futureValue must beAResponse(200, scriptUpdated)

      ws.url(s"$rootPath/api/scripts/$key").get().futureValue must beAStatus(404)
    }
  }
}
