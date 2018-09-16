package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}
import play.api.libs.ws.JsonBodyWritables._

class FeatureControllerSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec
  private lazy val ws = izanamiComponents.wsClient

  private lazy val rootPath = s"http://localhost:$port"

  s"$name FeatureController" should {

    "create read update delete deleteAll" in {
      val key = "my:path"
      /* First check */
      ws.url(s"$rootPath/api/features/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/features").get().futureValue must beAResponse(
        200,
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      val feature = Json.obj("id" -> key, "enabled" -> false, "activationStrategy" -> "NO_STRATEGY")
      ws.url(s"$rootPath/api/features")
        .post(feature)
        .futureValue must beAStatus(201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/features/$key").get().futureValue
      getById must beAResponse(200, feature)

      ws.url(s"$rootPath/api/features").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(feature),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val featureUpdated = Json.obj("id" -> key, "enabled" -> true, "activationStrategy" -> "NO_STRATEGY")
      ws.url(s"$rootPath/api/features/$key")
        .put(featureUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      val getByIdUpdated =
        ws.url(s"$rootPath/api/features/$key").get().futureValue
      getByIdUpdated must beAResponse(200, featureUpdated)

      ws.url(s"$rootPath/api/features").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(featureUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/features/$key")
        .delete()
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/features/$key").get().futureValue.status must be(404)
      ws.url(s"$rootPath/api/features").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Delete all */
      ws.url(s"$rootPath/api/features")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()
      ws.url(s"$rootPath/api/features").get().futureValue must beAResponse(
        200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }

    "update changing id" in {

      val key  = "my:path:1"
      val key2 = "my:path:2"

      /* Create */
      val feature = Json.obj("id" -> key, "enabled" -> false, "activationStrategy" -> "NO_STRATEGY")
      ws.url(s"$rootPath/api/features")
        .post(feature)
        .futureValue must beAStatus(201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/features/$key").get().futureValue
      getById must beAResponse(200, feature)

      ws.url(s"$rootPath/api/features").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(feature),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val featureUpdated = Json.obj("id" -> key2, "enabled" -> true, "activationStrategy" -> "NO_STRATEGY")
      ws.url(s"$rootPath/api/features/$key")
        .put(featureUpdated)
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/features/$key2").get().futureValue must beAResponse(200, featureUpdated)
      ws.url(s"$rootPath/api/features/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/features/$key2").delete().futureValue must beAStatus(200)

    }

    "return graph with active or inactive features" in {
      /* We start to prune all datas */
      ws.url(s"$rootPath/api/features").delete().futureValue

      val feature = Json.obj("id" -> "my:path", "enabled" -> false, "activationStrategy" -> "NO_STRATEGY")
      ws.url(s"$rootPath/api/features")
        .post(feature)
        .futureValue must beAStatus(201)

      val script = s"""
        |function enabled(context, enabled, disabled) {
        |   if(context.name === 'ragnar') {
        |     enabled()
        |   } else {
        |     disabled()
        |   }
        |}
         """.stripMargin
      val feature2 = Json.obj("id" -> "my:path:withScript",
                              "enabled"            -> true,
                              "activationStrategy" -> "SCRIPT",
                              "parameters"         -> Json.obj("script" -> script))
      val feature2Created =
        ws.url(s"$rootPath/api/features").post(feature2).futureValue
      feature2Created must beAStatus(201)

      ws.url(s"$rootPath/api/tree/features")
        .post(Json.obj())
        .futureValue
        .json must be(
        Json.obj(
          "my" -> Json.obj(
            "path" -> Json.obj(
              "active" -> false,
              "withScript" -> Json.obj(
                "active" -> false
              )
            )
          )
        )
      )

      ws.url(s"$rootPath/api/tree/features")
        .post(Json.obj("name" -> "ragnar"))
        .futureValue
        .json must be(
        Json.obj(
          "my" -> Json.obj(
            "path" -> Json.obj(
              "active" -> false,
              "withScript" -> Json.obj(
                "active" -> true
              )
            )
          )
        )
      )

    }

    "check if feature is active with or without context" in {
      /* We start to prune all datas */
      ws.url(s"$rootPath/api/features").delete().futureValue

      val script = s"""
        |function enabled(context, enabled, disabled) {
        |   if(context.name === 'ragnar') {
        |     enabled()
        |   } else {
        |     disabled()
        |   }
        |}
         """.stripMargin
      val key    = "my:path:withScript2"
      val feature2 = Json.obj("id" -> key,
                              "enabled"            -> true,
                              "activationStrategy" -> "SCRIPT",
                              "parameters"         -> Json.obj("script" -> script))
      val resp = ws.url(s"$rootPath/api/features").post(feature2).futureValue
      resp must beAStatus(201)

      val fValue =
        ws.url(s"$rootPath/api/features/$key/check").get().futureValue
      fValue must beAResponse(
        200,
        Json.obj("active" -> false)
      )
      ws.url(s"$rootPath/api/features/$key/check")
        .post(Json.obj("name" -> "ragnar"))
        .futureValue
        .json must be(
        Json.obj("active" -> true)
      )
    }

  }

}
