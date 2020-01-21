package controllers

import domains.AuthorizedPattern
import domains.user.{IzanamiUser, User}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables._
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}

import scala.util.Random
import org.scalatest.BeforeAndAfterAll

abstract class FeatureControllerWildcardAccessSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec

  private lazy val ws = izanamiComponents.wsClient

  private lazy val rootPath = s"http://localhost:$port"

  override def user: IzanamiUser =
    IzanamiUser(id = "id",
                name = "Ragnar Lodbrok",
                email = "ragnar.lodbrok@gmail.com",
                admin = false,
                password = "",
                authorizedPattern = AuthorizedPattern("a:key2:*"))

  s"$name FeatureControllerWildcardAccessSpec" should {

    "wildcard access with a:key1:*" in {

      /* First check */
      ws.url(s"$rootPath/api/features").get().futureValue must beAResponse(
        200,
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      ws.url(s"$rootPath/api/features")
        .post(Json.obj("id" -> "a:key1:12345", "enabled" -> true, "activationStrategy" -> "NO_STRATEGY"))
        .futureValue must beAStatus(403)

      ws.url(s"$rootPath/api/features")
        .post(Json.obj("id" -> "a:key2:12345", "enabled" -> true, "activationStrategy" -> "NO_STRATEGY"))
        .futureValue must beAStatus(201)

      ws.url(s"$rootPath/api/features").get().futureValue must beAResponse(
        200,
        Json.parse(
          """{"results":[{"id":"a:key2:12345","enabled":true,"activationStrategy":"NO_STRATEGY"}],"metadata":{"page":1,"pageSize":15,"count":1,"nbPages":1}}"""
        )
      )
    }
  }

}

class FeatureControllerStrictAccessSpec(name: String, configurationSpec: Configuration)
    extends PlaySpec
    with IzanamiMatchers
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

  override def getConfiguration(configuration: Configuration) =
    configuration ++ configurationSpec ++ Configuration.from(
      Map("izanami.features.db.import" -> "./resources/features.ndjson")
    )

  private lazy val ws = izanamiComponents.wsClient

  private lazy val rootPath = s"http://localhost:$port"

  override def user: IzanamiUser =
    IzanamiUser(id = "id",
                name = "Ragnar Lodbrok",
                email = "ragnar.lodbrok@gmail.com",
                admin = true,
                password = "",
                authorizedPattern = AuthorizedPattern("a:key"))

  s"$name FeatureControllerStrictAccessSpec" should {

    "strict access a:key" in {
      //Wait for the datas to be inserted
      Thread.sleep(1000)

      /* First check */
      ws.url(s"$rootPath/api/features").get().futureValue must beAResponse(
        200,
        Json.parse("""{"results":[],"metadata":{"page":1,"pageSize":15,"count":0,"nbPages":0}}""")
      )

      /* Create */
      ws.url(s"$rootPath/api/features")
        .post(Json.obj("id" -> "a:key2:12345", "enabled" -> true, "activationStrategy" -> "NO_STRATEGY"))
        .futureValue must beAStatus(403)

      ws.url(s"$rootPath/api/features")
        .post(Json.obj("id" -> "a:key:12345", "enabled" -> true, "activationStrategy" -> "NO_STRATEGY"))
        .futureValue must beAStatus(403)

      ws.url(s"$rootPath/api/features")
        .post(Json.obj("id" -> "a:key", "enabled" -> true, "activationStrategy" -> "NO_STRATEGY"))
        .futureValue must beAStatus(201)
    }
  }

}
