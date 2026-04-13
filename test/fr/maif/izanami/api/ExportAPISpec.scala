package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.TestPersonnalAccessToken
import fr.maif.izanami.api.BaseAPISpec.TestProject
import fr.maif.izanami.api.BaseAPISpec.TestSituationBuilder
import fr.maif.izanami.api.BaseAPISpec.TestTenant
import fr.maif.izanami.api.BaseAPISpec.TestUser
import fr.maif.izanami.api.BaseAPISpec.exportWithTokenSecret
import play.api.http.Status.FORBIDDEN
import play.api.http.Status.OK
import play.api.http.Status.UNAUTHORIZED

import java.time.LocalDateTime
import java.time.ZoneId

class ExportAPISpec extends BaseAPISpec {
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

      situation.exportWithTokenName(
        "tenant",
        situation.user,
        "foo"
      ).status mustBe OK
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

      val resp = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken(name = "foo", allRights = true)
      )

      val secret = (resp.json.get \ "token").as[String]
      exportWithTokenSecret("tenant", situation.user, secret).status mustBe OK
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

      val resp = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken(name = "foo", allRights = true)
      )

      val secret = (resp.json.get \ "token").as[String]
      exportWithTokenSecret(
        "tenant",
        situation.user,
        secret
      ).status mustBe FORBIDDEN
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
      exportWithTokenSecret(
        "tenant",
        situation.user,
        secret
      ).status mustBe UNAUTHORIZED
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
      exportWithTokenSecret("tenant", situation.user, secret).status mustBe OK
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
      exportWithTokenSecret("tenant", situation.user, secret).status mustBe OK
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
      exportWithTokenSecret(
        "tenant",
        situation.user,
        secret
      ).status mustBe UNAUTHORIZED
    }
  }

}
