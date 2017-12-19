package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import test.OneServerPerSuiteWithMyComponents
import play.api.libs.ws.JsonBodyWritables._

class GlobalScriptControllerSpec(configurationSpec: Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws = izanamiComponents.wsClient

  private lazy val rootPath = s"http://localhost:$port"

  "GlobalScriptController" should {

    "create read update delete deleteAll" in {
      val key = "my:path"
      /* First check */
      ws.url(s"$rootPath/api/scripts/$key").get().futureValue.status must be(404)
      ws.url(s"$rootPath/api/scripts").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Create */
      val script =
        Json.obj("id" -> key, "name" -> "test", "description" -> "A test script", "source" -> "function() {}")
      ws.url(s"$rootPath/api/scripts").post(script).futureValue.status must be(201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/scripts/$key").get().futureValue
      getById.status must be(200)
      getById.json must be(script)

      ws.url(s"$rootPath/api/scripts").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(script),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val scriptUpdated = Json.obj("id" -> key,
                                   "name"        -> "test",
                                   "description" -> "A test script",
                                   "source"      -> "function() { console.log('hello')}")
      ws.url(s"$rootPath/api/scripts/$key").put(scriptUpdated).futureValue.status must be(200)

      /* Verify */
      val getByIdUpdated = ws.url(s"$rootPath/api/scripts/$key").get().futureValue
      getByIdUpdated.status must be(200)
      getByIdUpdated.json must be(scriptUpdated)

      ws.url(s"$rootPath/api/scripts").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(scriptUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/scripts/$key").delete().futureValue.status must be(200)

      /* Verify */
      ws.url(s"$rootPath/api/scripts/$key").get().futureValue.status must be(404)
      ws.url(s"$rootPath/api/scripts").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Delete all */
      ws.url(s"$rootPath/api/scripts").addQueryStringParameters("patterns" -> "id*").delete()
      ws.url(s"$rootPath/api/scripts").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }
  }
}
