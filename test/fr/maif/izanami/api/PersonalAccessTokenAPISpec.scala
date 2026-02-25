package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{TestPersonnalAccessToken, TestSituationBuilder, TestTenant}
import play.api.http.Status.{CREATED, NO_CONTENT, OK}

class PersonalAccessTokenAPISpec extends BaseAPISpec {
  "POST endpoint" should {
    "allow to create token with multiple rights for a single tenant" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      val res = situation.createPersonnalAccessToken(TestPersonnalAccessToken(
        "foo",
        allRights = false,
        rights = Map("testtenant" -> Set("DELETE FEATURE", "READ KEYS"))
      ))

      res.status mustBe CREATED

    }
  }

  "PUT endpoint" should {
    "allow to update token with multiple rights for one tenant" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(
            "foo",
            allRights = false,
            rights = Map("testtenant" -> Set("DELETE FEATURE"))
          )
        )
        .loggedInWithAdminRights()
        .build()

      val id = situation.findTokenId(situation.user, "foo")


      val res = situation.updatePersonnalAccessToken(id, TestPersonnalAccessToken(
        "foo",
        id = id,
        allRights = false,
        rights = Map("testtenant" -> Set("READ KEYS", "DELETE FEATURE"))
      ))

      res.status mustBe OK
    }
  }
}
