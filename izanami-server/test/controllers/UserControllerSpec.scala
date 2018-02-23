package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables._
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}

class UserControllerSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws       = izanamiComponents.wsClient
  private lazy val rootPath = s"http://localhost:$port"

  s"$name UserController" should {

    "create read update delete" in {
      val key = "toto@maif.fr"
      /* First check */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/users").get().futureValue must beAResponse(200,
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      val user = Json.obj("id" -> key, "name" -> "toto", "email" -> key, "admin" -> true, "authorizedPattern" -> "*")
      ws.url(s"$rootPath/api/users")
        .post(user ++ Json.obj("password" -> "password"))
        .futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAResponse(200, user)


      ws.url(s"$rootPath/api/users").get().futureValue must beAResponse(200,
        Json.obj("results"  -> Json.arr(user),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val userUpdated =
        Json.obj("id" -> key, "name" -> "toto2", "email" -> key, "admin" -> true, "authorizedPattern" -> "*")
      ws.url(s"$rootPath/api/users/$key")
        .put(userUpdated ++ Json.obj("password" -> "password"))
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAResponse(200, userUpdated)

      ws.url(s"$rootPath/api/users").get().futureValue must beAResponse(200,
        Json.obj("results"  -> Json.arr(userUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/users/$key").delete().futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/users").get().futureValue must beAResponse(200,
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )

      /* Delete all */
      ws.url(s"$rootPath/api/users")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()
      ws.url(s"$rootPath/api/users").get().futureValue must beAResponse(200,
        Json.obj("results"  -> Json.arr(),
          "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }

  }

}
