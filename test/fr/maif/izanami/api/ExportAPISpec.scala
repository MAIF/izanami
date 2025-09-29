package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{ADMIN_BASE_URL, RequestResult, TestFeature, TestPersonnalAccessToken, TestProject, TestSituationBuilder, TestTenant, TestUser, ws}
import play.api.http.Status.{FORBIDDEN, OK, UNAUTHORIZED}
import play.api.libs.json.Json
import play.api.test.Helpers.await

import java.time.{LocalDateTime, ZoneId}
import java.util.Base64
import scala.util.Try

import play.api.libs.ws.writeableOf_JsValue

class ExportAPISpec extends BaseAPISpec {
  def exportWithToken(tenant: String, user: String, secret: String): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/$tenant/_export")
        .addHttpHeaders(
          "Authorization" -> s"Basic ${Base64.getEncoder.encodeToString(s"${user}:$secret".getBytes)}"
        )
        .post(
          Json.obj(
            "allProjects" -> true,
            "allKeys"     -> true,
            "allWebhooks" -> true,
            "userRights"  -> false,
            "webhooks"    -> Seq[String](),
            "projects"    -> Seq[String](),
            "keys"        -> Seq[String]()
          )
        )
    )

    RequestResult(json = Try { response.json }, status = response.status)
  }

  "Export endpoint" should {
    "Allow to export with personnal access tokens with all rights if is global admin" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2", "f3")
            )
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .build()

      val secret = situation.findTokenSecret(situation.user, "foo")
      exportWithToken("tenant", situation.user, secret).status mustBe OK
    }

    "Allow export with personnal access tokens with all rights if user is tenant admin" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2", "f3")
            )
        )
        .withUsers(TestUser("testu").withTenantAdminRight("tenant"))
        .loggedAs("testu")
        .build()

      val resp = situation.createPersonnalAccessToken(TestPersonnalAccessToken(name = "foo", allRights = true))

      val secret = (resp.json.get \ "token").as[String]
      exportWithToken("tenant", situation.user, secret).status mustBe OK
    }

    "Prevent export with personnal access tokens with all rights isn't tenant admin" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2", "f3")
            )
        )
        .withUsers(TestUser("testu").withTenantReadWriteRight("tenant"))
        .loggedAs("testu")
        .build()

      val resp = situation.createPersonnalAccessToken(TestPersonnalAccessToken(name = "foo", allRights = true))

      val secret = (resp.json.get \ "token").as[String]
      exportWithToken("tenant", situation.user, secret).status mustBe FORBIDDEN
    }

    "Prevent export with personnal access tokens with all rights if token is expired" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2", "f3")
            )
        )
        .build()

      val resp = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken(
          name = "foo",
          allRights = true,
          expiresAt = Some(LocalDateTime.of(2019, 1, 1, 0, 0)),
          expirationTimezone = Some(ZoneId.systemDefault())
        )
      )

      val secret = (resp.json.get \ "token").as[String]
      exportWithToken("tenant", situation.user, secret).status mustBe UNAUTHORIZED
    }


    "Allow export with personnal access tokens with all rights if token is not expired" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2", "f3")
            )
        )
        .build()

      val resp = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken(
          name = "foo",
          allRights = true,
          expiresAt = Some(LocalDateTime.of(2999, 1, 1, 0, 0)),
          expirationTimezone = Some(ZoneId.systemDefault())
        )
      )

      val secret = (resp.json.get \ "token").as[String]
      exportWithToken("tenant", situation.user, secret).status mustBe OK
    }

    "Allow export with personnal access tokens with only export right" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2", "f3")
            )
        )
        .build()

      val resp = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken(
          name = "foo",
          allRights = false,
          rights = Map("tenant" -> Set("EXPORT"))
        )
      )

      val secret = (resp.json.get \ "token").as[String]
      exportWithToken("tenant", situation.user, secret).status mustBe OK
    }

    "Prevent export with personnal access tokens with only import right" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2", "f3")
            )
        )
        .build()

      val resp = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken(
          name = "foo",
          allRights = false,
          rights = Map("tenant" -> Set("IMPORT"))
        )
      )

      val secret = (resp.json.get \ "token").as[String]
      exportWithToken("tenant", situation.user, secret).status mustBe UNAUTHORIZED
    }
  }

}
