package controllers

import domains.user.User
import org.scalactic.Prettifier
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.JsonBodyWritables._
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.time.{Millis, Minutes, Span}
import play.api.libs.ws.WSResponse

abstract class UserControllerSpec(name: String, configurationSpec: Configuration)
  extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience
    with Eventually {

  override def getConfiguration(configuration: Configuration) =
    configurationSpec withFallback configuration

  private lazy val ws = izanamiComponents.wsClient
  private lazy val rootPath = s"http://localhost:$port"

  private lazy val defaultUser = Json.parse("""{"id":"admin@izanami.io","name":"admin@izanami.io","email":"admin@izanami.io","admin":true,"temporary":true,"authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"type":"Izanami"}""")

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(1, Minutes)), interval = scaled(Span(50, Millis)))

  s"$name UserController" should {

    "create read update delete" in {
      val key = "toto@maif.fr"
      /* First check */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/users").get().futureValue must beAUsersResponse(
        200,
        defaultUser
      )

      /* Create */
      val user = Json.obj(
        "type"               -> "Izanami",
        "id"                 -> key,
        "name"               -> "toto",
        "email"              -> key,
        "admin"              -> true,
        "temporary"          -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D"))),
        "type"               -> "Izanami"
      )
      ws.url(s"$rootPath/api/users")
        .post(user ++ Json.obj("password" -> "password"))
        .futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAResponse(200, user)

      ws.url(s"$rootPath/api/users").get().futureValue must beAUsersResponse(
        200, user, defaultUser)

      /* Update */
      val userUpdated =
        Json.obj(
          "type"               -> "Izanami",
          "id"                 -> key,
          "name"               -> "toto deux",
          "email"              -> key,
          "admin"              -> true,
          "temporary"          -> false,
          "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D"))),
          "type"               -> "Izanami"
        )
      ws.url(s"$rootPath/api/users/$key")
        .put(userUpdated ++ Json.obj("password" -> "password"))
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAResponse(200, userUpdated)

      ws.url(s"$rootPath/api/users").get().futureValue must beAUsersResponse(
        200, userUpdated, defaultUser)

      /* Delete */
      ws.url(s"$rootPath/api/users/$key").delete().futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAStatus(404)
      ws.url(s"$rootPath/api/users").get().futureValue must beAUsersResponse(200, defaultUser)

      /* Delete all */
      ws.url(s"$rootPath/api/users")
        .addQueryStringParameters("patterns" -> "id*")
        .delete()

      eventually {
        Thread.`yield`()
        ws.url(s"$rootPath/api/users").get().futureValue must beAUsersResponse(200)
      }
    }

    "update changing id" in {

      val key  = "toto1@maif.fr"
      val key2 = "toto2@maif.fr"
      /* Create */
      val user = Json.obj(
        "type"               -> "Izanami",
        "id"                 -> key,
        "name"               -> "toto",
        "email"              -> key,
        "admin"              -> true,
        "temporary"          -> false,
        "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D"))),
        "type"               -> "Izanami"
      )
      ws.url(s"$rootPath/api/users")
        .post(user ++ Json.obj("password" -> "password"))
        .futureValue must beAStatus(201)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAResponse(200, user)

      /* Update */
      val userUpdated =
        Json.obj(
          "type"               -> "Izanami",
          "id"                 -> key2,
          "name"               -> "toto deux",
          "email"              -> key,
          "admin"              -> true,
          "temporary"          -> false,
          "authorizedPatterns" -> Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("C", "R", "U", "D"))),
          "type"               -> "Izanami"
        )
      ws.url(s"$rootPath/api/users/$key")
        .put(userUpdated ++ Json.obj("password" -> "password"))
        .futureValue must beAStatus(200)

      /* Verify */
      ws.url(s"$rootPath/api/users/$key2").get().futureValue must beAResponse(200, userUpdated)
      ws.url(s"$rootPath/api/users/$key").get().futureValue must beAStatus(404)
    }

  }

  def beAUsersResponse(status: Int, users: JsValue*): Matcher[WSResponse] = new Matcher[WSResponse] {
    import scala.math.Integral.Implicits._
    override def apply(left: WSResponse): MatchResult = {
      val metadata = (left.json \ "metadata").as[JsObject]
      val expectedMetadata = Json.obj("page" -> 1, "pageSize" -> 15, "count" -> users.length, "nbPages" -> ((15 + users.length - 1) /% 15)._1)
      val sameMetadata = metadata == expectedMetadata

      import domains.user.UserInstances._
      val results = (left.json \ "results").as[JsArray].value.map(_.as[User]).sortBy(_.id).toList
      val expectedResults = users.map(_.as[User]).sortBy(_.id)

      val sameUsers = results.sameElements(expectedResults) || (results.isEmpty && expectedResults.isEmpty)
      MatchResult(
        left.status == status && sameUsers && sameMetadata,
        s"${left.status} is not the same as $status or the body ${results} is not the same as ${expectedResults} or the metadata ${metadata}  is not the same as ${expectedMetadata}",
        s"${left.status} is the same as $status and the body ${results} is not the same as ${expectedResults} or the metadata ${metadata}  is not the same as ${expectedMetadata}",
        Vector()
      )
    }

    override def toString: String = "be theStatus " + Prettifier.default(status)
  }
}