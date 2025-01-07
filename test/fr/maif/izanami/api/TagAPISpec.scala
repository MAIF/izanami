package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import play.api.http.Status.{BAD_REQUEST, CREATED, FORBIDDEN, NOT_FOUND, NO_CONTENT, OK}

class TagAPISpec extends BaseAPISpec {

  "Tag POST endpoint" should {
    "prevent tag creation if tag name or description is too long" in {
      val tenantName  = "my-tenant"
      val situation = TestSituationBuilder()
        .withTenantNames(tenantName)
        .loggedInWithAdminRights()
        .build()
      var tagResponse = situation.createTag("abcdefghij" * 21, tenantName, "My description")
      tagResponse.status mustBe BAD_REQUEST

      tagResponse = situation.createTag("abcdefghij", tenantName, "abcdefghij" * 51)
      tagResponse.status mustBe BAD_REQUEST
    }

    "allow tag creation" in {
      val tenantName  = "my-tenant"
      val situation = TestSituationBuilder()
        .withTenantNames(tenantName)
        .loggedInWithAdminRights()
        .build()
      val tagResponse = situation.createTag("my-tag", tenantName, "My description")

      tagResponse.status mustBe CREATED
      val json = tagResponse.json
      (json.get \ "name").get.as[String] mustEqual "my-tag"
      (json.get \ "description").get.as[String] mustEqual "My description"
    }

    "prevent tag creation if user does not have write right on tenant" in {
      val testSituation = TestSituationBuilder()
        .withTenantNames("foo")
        .withUsers(TestUser("testuser").withTenantReadRight("foo"))
        .loggedAs("testuser")
        .build()

      val result = testSituation.createTag("my-tag", "foo")

      result.status mustBe FORBIDDEN
    }
  }

  "Tag DELETE  endpoint" should {
    "allow to delete existing tag if user has write right on tenant" in {
      val testSituation = TestSituationBuilder()
        .withTenants(TestTenant("foo").withTagNames("my-tag"))
        .withUsers(TestUser("testuser").withTenantReadWriteRight("foo"))
        .loggedAs("testuser")
        .build()

      val response = testSituation.deleteTag("foo", "my-tag")

      testSituation.fetchTag("foo", "my-tag").status mustBe NOT_FOUND

      response.status mustBe NO_CONTENT
    }

    "prevent tag suppression if user does not have write right on tenant" in {
      val testSituation = TestSituationBuilder()
        .withTenants(TestTenant("foo").withTagNames("my-tag"))
        .withUsers(TestUser("testuser").withTenantReadRight("foo"))
        .loggedAs("testuser")
        .build()

      val response = testSituation.deleteTag("foo", "my-tag")

      testSituation.fetchTag("foo", "my-tag").status mustBe OK


      response.status mustBe FORBIDDEN
    }

    "return 404 if tag does not exist" in {
      val testSituation = TestSituationBuilder()
        .withTenants(TestTenant("foo"))
        .withUsers(TestUser("testuser").withTenantReadWriteRight("foo"))
        .loggedAs("testuser")
        .build()

      val response = testSituation.deleteTag("foo", "my-tag")

      response.status mustBe NOT_FOUND
    }
  }

  "Tag GET endpoint" should {
    "retrieve existing tag by name" in {
      val tenantName = "my-tenant"
      val tagName    = "my-tag"

      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withTags(TestTag(name = tagName, description = "My description")))
        .build();

      val fetchResponse = situation.fetchTag(tenantName, tagName)

      fetchResponse.status mustBe OK
      val json = fetchResponse.json
      (json.get \ "name").get.as[String] mustEqual "my-tag"
      (json.get \ "description").get.as[String] mustEqual "My description"
    }

    "retrieve existing tags" in {
      val tenantName = "my-tenant"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withTagNames("my-tag", "my-tag2", "my-tag3"))
        .build()

      val fetchResponse = situation.fetchTags(tenantName)

      fetchResponse.status mustBe OK
      (fetchResponse.json.get \\ "name").map(v => v.as[String]) must contain theSameElementsAs Seq(
        "my-tag",
        "my-tag2",
        "my-tag3"
      )
    }
  }

  "Tag PUT endpoint" should {
    "prevent tag update if new name or description is too long" in {
      val tenantName = "my-tenant"
      val situation = TestSituationBuilder()
        .withTenants(TestTenant(tenantName).withTagNames("Tag"))
        .loggedInWithAdminRights()
        .build()

      var updateResponse = situation.updateTag(tenantName,TestTag("abcdefghij" * 21,"my-description"),"Tag")
      updateResponse.status mustBe BAD_REQUEST

      updateResponse = situation.updateTag(tenantName,TestTag("supertag", "abcdefghij" * 51),"Tag")
      updateResponse.status mustBe BAD_REQUEST
    }

    "allow tag update" in {
      val tenantName = "my-tenant"
      val situation = TestSituationBuilder()
        .withTenants(TestTenant(tenantName).withTagNames("Tag"))
        .loggedInWithAdminRights()
        .build()
      val updateResponse = situation.updateTag(tenantName,TestTag("my-tag","my-description"),"Tag")
      updateResponse.status mustBe NO_CONTENT

      val fetchResponse = situation.fetchTag(tenantName,"my-tag")
      fetchResponse.status mustBe OK
      val json= fetchResponse.json
      (json.get \ "name").get.as[String] mustEqual "my-tag"
      (json.get \ "description").get.as[String] mustEqual "my-description"
    }
    "should return 404 if tag does not exist" in {
      val tenantName = "my-tenant"
      val situation = TestSituationBuilder()
        .withTenants(TestTenant(tenantName))
        .loggedInWithAdminRights()
        .build()
      val updateResponse = situation.updateTag(tenantName,TestTag("my-tag","my-description"),"Tag")
      updateResponse.status mustBe NOT_FOUND
    }
  }

}
