package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{TestProject, TestSituationBuilder, TestTenant, TestUser}
import play.api.http.Status.{FORBIDDEN, OK}
import play.api.libs.json.JsArray

class SearchAPISpec extends BaseAPISpec{

  "Search GET endpoint" should {
    "return entity tags related to a search query" in {
      val tenantName = "my-tenant"
      val searchQuery = "tag2"
      val targetTag = "my-tag2"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withTagNames("my-tag", targetTag, "my-tag3"))
        .build()

      val fetchResponse = situation.fetchSearchEntities(searchQuery)

      fetchResponse.status mustBe OK
      val json = fetchResponse.json.get(0)
      (json \ "name").as[String] mustEqual targetTag
      (json \ "origin_table").as[String] mustEqual "tags"
    }
    "return empty list if search query does not exist" in {
      val tenantName = "my-tenant"
      val searchQuery = "tag4"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withTagNames("my-tag", "my-tag2", "my-tag3"))
        .build()

      val fetchResponse = situation.fetchSearchEntities(searchQuery)

      fetchResponse.status mustBe OK
      fetchResponse.json.get.as[JsArray].value mustBe empty
    }
    "prevent search process if user does not have right on tenant" in {
      val situation = TestSituationBuilder()
        .withTenantNames("my-tenant")
        .withUsers(TestUser("testuser"))
        .loggedAs("testuser")
        .build()
      val searchQuery = "tag4"
      val fetchResponse = situation.fetchSearchEntities(searchQuery)

      fetchResponse.status mustBe FORBIDDEN
    }
    "return entity features related to a search query" in {
      val tenantName = "my-tenant"
      val searchQuery = "f1"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjects(TestProject("project").withFeatureNames("F1", "F2", "F3")))
        .build()

      val fetchResponse = situation.fetchSearchEntities(searchQuery)

      fetchResponse.status mustBe OK
      fetchResponse.json.get.as[JsArray].value must have size 1
    }
  }

}
