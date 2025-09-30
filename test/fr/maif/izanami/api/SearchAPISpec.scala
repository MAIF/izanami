package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{
  TestProject,
  TestSituationBuilder,
  TestTenant
}
import play.api.http.Status.OK
import play.api.libs.json.{JsArray, Json}

class SearchAPISpec extends BaseAPISpec {

  "Search GET endpoint" should {
    "return entity tags related to a search query" in {
      val tenantName = "my-tenant"
      val searchQuery = "tag2"
      val targetTag = "my-tag2"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName).withTagNames("my-tag", targetTag, "my-tag3")
        )
        .build()

      val fetchResponse = situation.fetchSearchEntities(searchQuery)

      fetchResponse.status mustBe OK
      val json = fetchResponse.json.get(0)
      (json \ "type").as[String] mustEqual "tag"
      (json \ "name").as[String] mustEqual "my-tag2"
      (json \ "tenant").as[String] mustEqual "my-tenant"
      val tenantPathPart = json \ "path" \ 0
      (tenantPathPart \ "type").as[String] mustEqual "tenant"
      (tenantPathPart \ "name").as[String] mustEqual "my-tenant"
    }
    "return empty list if search query does not exist" in {
      val tenantName = "my-tenant"
      val searchQuery = "foobar"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName).withTagNames("my-tag", "my-tag2", "my-tag3")
        )
        .build()

      val fetchResponse = situation.fetchSearchEntities(searchQuery)

      fetchResponse.status mustBe OK
      fetchResponse.json.get.as[JsArray].value mustBe empty
    }
    "return entity features related to a search query" in {
      val tenantName = "my-tenant"
      val searchQuery = "comm"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName).withProjects(
            TestProject("project").withFeatureNames("comments", "foo", "bar")
          )
        )
        .build()

      val fetchResponse = situation.fetchSearchEntities(searchQuery)

      fetchResponse.status mustBe OK
      fetchResponse.json.get.as[JsArray].value must have size 1
    }

    // TODO check that result ordering is correct
    // TODO check that there is no more than 10 results
    // TODO check that path array contains correctly ordered entries (for features, local and global context)
    // TODO check that search result on unauthorized tenant don't appear
    // TODO test search on a specific tenant
  }

}
