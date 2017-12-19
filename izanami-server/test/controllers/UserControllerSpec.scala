package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables._
import test.OneServerPerSuiteWithMyComponents

class UserControllerSpec(configurationSpec: Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws       = izanamiComponents.wsClient
  private lazy val rootPath = s"http://localhost:$port"

  "UserController" should {

    "create read update delete" in {
      val key = "toto@maif.fr"
      /* First check */
      ws.url(s"$rootPath/api/users/$key").get().futureValue.status must be(404)
      ws.url(s"$rootPath/api/users").get().futureValue.json must be(
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      val user = Json.obj("id" -> key, "name" -> "toto", "email" -> key, "admin" -> true, "authorizedPattern" -> "*")
      ws.url(s"$rootPath/api/users").post(user ++ Json.obj("password" -> "password")).futureValue.status must be(201)

      /* Verify */
      val getById = ws.url(s"$rootPath/api/users/$key").get().futureValue
      getById.status must be(200)
      getById.json must be(user)

      ws.url(s"$rootPath/api/users").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(user),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Update */
      val userUpdated =
        Json.obj("id" -> key, "name" -> "toto2", "email" -> key, "admin" -> true, "authorizedPattern" -> "*")
      ws.url(s"$rootPath/api/users/$key")
        .put(userUpdated ++ Json.obj("password" -> "password"))
        .futureValue
        .status must be(200)

      /* Verify */
      val getByIdUpdated = ws.url(s"$rootPath/api/users/$key").get().futureValue
      getByIdUpdated.status must be(200)
      getByIdUpdated.json must be(userUpdated)

      ws.url(s"$rootPath/api/users").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(userUpdated),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 1, "nbPages" -> 1))
      )

      /* Delete */
      ws.url(s"$rootPath/api/users/$key").delete().futureValue.status must be(200)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key").get().futureValue.status must be(404)
      ws.url(s"$rootPath/api/users").get().futureValue.json must be(
        Json.obj("results"  -> Json.arr(),
                 "metadata" -> Json.obj("page" -> 1, "pageSize" -> 15, "count" -> 0, "nbPages" -> 0))
      )
    }

  }

}
