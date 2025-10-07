package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.*
import play.api.http.Status.*
import play.api.libs.json
import play.api.libs.json.{JsArray, JsObject, JsUndefined, JsValue, Json}

import java.time.LocalDateTime

class FeatureContextAPISpec extends BaseAPISpec {
  "Local context PUT endpoint" should {
    "Allow to protect/unprotect local context if user is project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(
          TestUser("padmin")
            .withTenantReadRight("tenant")
            .withProjectAdminRight("project", tenant = "tenant")
        )
        .loggedAs("padmin")
        .build()

      situation.createContext("tenant", project = "project", name = "localctx")
      var response = situation.updateContext(
        "tenant",
        project = "project",
        name = "localctx",
        isProtected = true
      )
      response.status mustEqual NO_CONTENT

      var ctxs =
        situation.fetchContexts(tenant = "tenant", project = "project").json.get
      (ctxs \ 0 \ "protected").as[Boolean] mustBe true

      response = situation.updateContext(
        "tenant",
        project = "project",
        name = "localctx",
        isProtected = false
      )
      response.status mustEqual NO_CONTENT

      ctxs =
        situation.fetchContexts(tenant = "tenant", project = "project").json.get
      (ctxs \ 0 \ "protected").as[Boolean] mustBe false
    }

    "Prevent to protect/unprotect local context if user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
            .withProjectReadWriteRight("project", "tenant")
        )
        .loggedAs("noadmin")
        .build()

      situation.createContext("tenant", project = "project", name = "localctx")
      val response = situation.updateContext(
        "tenant",
        project = "project",
        name = "localctx",
        isProtected = true
      )
      response.status mustEqual FORBIDDEN

      val ctxs =
        situation.fetchContexts(tenant = "tenant", project = "project").json.get
      (ctxs \ 0 \ "protected").as[Boolean] mustBe false
    }

    "Return not found if context does not exist" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .build()

      val response = situation.updateContext(
        "tenant",
        project = "project",
        name = "localctxv2",
        isProtected = true
      )
      response.status mustEqual NOT_FOUND
    }

    "Allow to protect/unprotect subcontext" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .build()

      situation.createContext("tenant", project = "project", name = "localctx")
      situation.createContext(
        "tenant",
        project = "project",
        name = "subctx",
        parents = "localctx"
      )
      situation.createContext(
        "tenant",
        project = "project",
        name = "subsubctx",
        parents = "localctx/subctx"
      )

      val subResponse = situation.updateContext(
        "tenant",
        project = "project",
        name = "subsubctx",
        isProtected = true,
        parents = "localctx/subctx"
      )

      subResponse.status mustEqual NO_CONTENT
    }

    "Prevent subcontext creation if parent context is protected and user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjects(
            TestProject("project").withContexts(
              TestFeatureContext("protectedParent", isProtected = true)
            )
          )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
            .withProjectReadWriteRight("project", "tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response = situation.createContext(
        "tenant",
        project = "project",
        name = "subctx",
        parents = "protectedParent"
      )
      response.status mustEqual FORBIDDEN
    }

    "Prevent creating unprotected context as child of protected context" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjects(
            TestProject("project").withContexts(
              TestFeatureContext("protectedParent", isProtected = true)
            )
          )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.createContext(
        "tenant",
        project = "project",
        name = "subctx",
        parents = "protectedParent",
        isProtected = false
      )
      response.status mustEqual BAD_REQUEST
    }
  }

  "Global context PUT endpoint" should {
    "Should protect existing subcontexts when protecting parent context" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext("global")
                .withSubContexts(TestFeatureContext("subglobal"))
            )
            .withProjects(TestProject("project"))
        )
        .loggedInWithAdminRights()
        .build()

      situation.createContext(
        "tenant",
        project = "project",
        name = "sublocal",
        parents = "global"
      )
      situation.createContext(
        "tenant",
        project = "project",
        name = "subglobalsublocal",
        parents = "global/subglobal"
      )
      situation.createContext(
        "tenant",
        project = "project",
        name = "subsublocal",
        parents = "global/sublocal"
      )

      situation.updateGlobalContext(
        "tenant",
        name = "global",
        isProtected = true
      )
      val ctxs =
        situation.fetchGlobalContext(tenant = "tenant", all = true).json.get
      val protecteds = (ctxs \\ "protected").map(js => js.as[Boolean])
      protecteds must contain only true
    }

    "Allow to protect/unprotect local context if user is tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withGlobalContext(
            TestFeatureContext("globalCtx")
          )
        )
        .withUsers(TestUser("tadmin").withTenantAdminRight("tenant"))
        .loggedAs("tadmin")
        .build()

      val response = situation.updateGlobalContext(
        "tenant",
        name = "globalCtx",
        isProtected = true
      )
      response.status mustEqual NO_CONTENT
    }

    "Prevent to protect/unprotect local context if user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withGlobalContext(
            TestFeatureContext("globalctx")
          )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response = situation.updateContext(
        "tenant",
        project = "project",
        name = "globalctx",
        isProtected = true
      )
      response.status mustEqual FORBIDDEN

      val ctxs = situation.fetchGlobalContext(tenant = "tenant").json.get
      (ctxs \ 0 \ "protected").as[Boolean] mustBe false
    }

    "Return not found if context does not exist" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant("tenant"))
        .build()

      val response = situation.updateGlobalContext(
        "tenant",
        name = "globalCtx",
        isProtected = true
      )
      response.status mustEqual NOT_FOUND
    }

    "Allow to protect/unprotect subcontext" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext("localctx")
                .withSubContexts(
                  TestFeatureContext("subctx")
                    .withSubContexts(TestFeatureContext("subsubctx"))
                )
            )
        )
        .build()

      val subResponse = situation.updateGlobalContext(
        "tenant",
        name = "subsubctx",
        isProtected = true,
        parents = "localctx/subctx"
      )

      subResponse.status mustEqual NO_CONTENT
    }
  }

  "Global context POST endpoint" should {
    "Prevent subcontext creation if parent context is protected and user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withGlobalContext(
            TestFeatureContext("protectedParent", isProtected = true)
          )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response = situation.createGlobalContext(
        "tenant",
        name = "subctx",
        parents = "protectedParent"
      )
      response.status mustEqual FORBIDDEN
    }

    "Prevent creating unprotected context as child of a protected context" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withGlobalContext(
            TestFeatureContext("protectedParent", isProtected = true)
          )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.createGlobalContext(
        "tenant",
        name = "subctx",
        parents = "protectedParent",
        isProtected = false
      )
      response.status mustEqual BAD_REQUEST
    }

    "Prevent global context creation if name is too long" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .loggedInWithAdminRights()
        .build()

      var response = situation.createGlobalContext("tenant", "abcdefghij" * 21)
      response.status mustEqual BAD_REQUEST
    }

    "Allow to recreate deleted global context" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjectNames("project")
            .withGlobalContext(
              TestFeatureContext(
                "prod",
                subContext = Set(
                  TestFeatureContext(
                    "mobile",
                    subContext = Set(
                      TestFeatureContext(
                        "foo",
                        subContext = Set(TestFeatureContext("bar"))
                      )
                    )
                  )
                )
              )
            )
        )
        .loggedInWithAdminRights()
        .build()
      val deleteResponse = situation.deleteGlobalContext("tenant", "prod")

      deleteResponse.status mustBe NO_CONTENT

      val response = situation.createGlobalContext("tenant", "prod")
      val response2 =
        situation.createGlobalContext("tenant", "mobile", parents = "prod")
      val response3 =
        situation.createGlobalContext("tenant", "foo", parents = "prod/mobile")
      val response4 = situation.createGlobalContext(
        "tenant",
        "bar",
        parents = "prod/mobile/foo"
      )

      response.status mustEqual CREATED
      response2.status mustEqual CREATED
      response3.status mustEqual CREATED
      response4.status mustEqual CREATED
      // (contexts.json.get \\ "name").map(v => v.as[String]) must contain theSameElementsAs Seq("context")
    }

    "Allow to create context for tenants" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.createGlobalContext("tenant", "context")
      val contexts = situation.fetchContexts("tenant", "project")

      response.status mustEqual CREATED
      (contexts.json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("context")
    }

    "Allow to create global subcontext for global context" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("context"))
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val response =
        situation.createGlobalContext("tenant", "subcontext", "context")
      val contexts = situation.fetchContexts("tenant", "project")

      response.status mustEqual CREATED
      (contexts.json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("context", "subcontext")
    }
  }

  "Global context DELETE endpoint" should {
    "prevent global context delete if context has protected subcontexts" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withGlobalContext(
            TestFeatureContext("notprotected", isProtected = false)
              .withSubContexts(
                TestFeatureContext("protectedchildren", isProtected = true)
              )
          )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response =
        situation.deleteGlobalContext(tenant = "tenant", path = "notprotected")
      response.status mustBe FORBIDDEN
    }

    "prevent global context delete if context is protected and user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withGlobalContext(
            TestFeatureContext("protected", isProtected = true)
          )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response =
        situation.deleteGlobalContext(tenant = "tenant", path = "protected")
      response.status mustBe FORBIDDEN
    }

    "prevent global subcontext delete if subcontext is protected and user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withGlobalContext(
            TestFeatureContext("unprotectedparent", isProtected = false)
              .withSubContexts(
                TestFeatureContext("protected", isProtected = true)
              )
          )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response = situation.deleteGlobalContext(
        tenant = "tenant",
        path = "unprotectedparent/protected"
      )
      response.status mustBe FORBIDDEN
    }

    "Allow to delete global context" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("context"))
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()
      val result = situation.deleteGlobalContext("tenant", "context")
      val contexts = situation.fetchContexts("tenant", "project")

      result.status mustBe NO_CONTENT
      (contexts.json.get \\ "name").map(v => v.as[String]) mustBe empty
    }

    "Allow to delete global subcontext" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext(
                "context",
                subContext = Set(TestFeatureContext("subcontext"))
              )
            )
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()
      val result = situation.deleteGlobalContext("tenant", "context/subcontext")
      val contexts = situation.fetchContexts("tenant", "project")

      result.status mustBe NO_CONTENT
      (contexts.json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("context")
    }

  }

  "Context POST endpoint" should {
    "Prevent context creation if name is too long" in {
      val tenant = "context-tenant"
      val project = "context-project"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenant).withProjectNames(project))
        .build();
      val context = "abcdefghij" * 21
      val result = situation.createContext(tenant, project, context)

      result.status mustBe BAD_REQUEST
    }

    "Allow to recreate deleted local context" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext(
                "prod",
                subContext = Set(TestFeatureContext("mobile"))
              )
            )
            .withProjects(TestProject("project"))
        )
        .loggedInWithAdminRights()
        .build()
      val response = situation.createContext(
        "tenant",
        "project",
        name = "localsubmobile",
        parents = "prod/mobile"
      )
      val response2 = situation.createContext(
        "tenant",
        "project",
        name = "localsubprod",
        parents = "prod"
      )
      response.status mustBe CREATED
      response2.status mustBe CREATED

      val deleteResponse = situation.deleteGlobalContext("tenant", "prod")
      deleteResponse.status mustBe NO_CONTENT

      val response3 = situation.createGlobalContext("tenant", name = "prod")
      val response4 = situation.createGlobalContext(
        "tenant",
        name = "mobile",
        parents = "prod"
      )
      val response5 = situation.createContext(
        "tenant",
        "project",
        "localsubmobile",
        parents = "prod/mobile"
      )
      val response6 = situation.createContext(
        "tenant",
        "project",
        "localsubprod",
        parents = "prod"
      )

      response3.status mustEqual CREATED
      response4.status mustEqual CREATED
      response5.status mustEqual CREATED
      response6.status mustEqual CREATED
    }
    "allow context creation" in {
      val tenant = "context-tenant"
      val project = "context-project"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenant).withProjectNames(project))
        .build();
      val context = "my-context"
      val result = situation.createContext(tenant, project, context)

      result.status mustBe CREATED
      (result.json.get \ "name").as[String] mustEqual context
    }

    "allow creating subcontext" in {
      val tenant = "context-tenant"
      val project = "context-project"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenant).withProjectNames(project))
        .build();
      val context = "my-context"
      situation.createContext(tenant, project, context)
      val result = situation.createContext(
        tenant,
        project,
        "my-subcontext",
        parents = context
      )

      result.status mustBe CREATED
    }

    "allow to create local subcontext for existing global contexts" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("context"))
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.createContext(
        "tenant",
        project = "project",
        name = "subcontext",
        parents = "context"
      )
      response.status mustBe CREATED
    }

    "return 404 if parent context does not exist" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.createContext(
        "tenant",
        project = "project",
        name = "subcontext",
        parents = "context"
      )
      response.status mustBe NOT_FOUND
    }

    "prevent creating subcontext if global context with the same name exist" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext("context").withSubContextNames("foo")
            )
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.createContext(
        "tenant",
        project = "project",
        name = "foo",
        parents = "context"
      )
      response.status mustBe BAD_REQUEST
    }

    "prevent creating global subcontext if local context with the same name exist" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("context"))
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val localCtxResponse = situation.createContext(
        "tenant",
        project = "project",
        name = "foo",
        parents = "context"
      )
      localCtxResponse.status mustBe CREATED
      val response = situation.createGlobalContext(
        "tenant",
        name = "foo",
        parents = "context"
      )
      response.status mustBe BAD_REQUEST
    }

    "prevent creating root local context when global context with the same name exist" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("context"))
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val response =
        situation.createContext("tenant", project = "project", name = "context")
      response.status mustBe BAD_REQUEST
    }

    "prevent creating root global context when local context with the same name exist" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val localResponse =
        situation.createContext("tenant", project = "project", name = "context")
      localResponse.status mustBe CREATED
      val response = situation.createGlobalContext("tenant", name = "context")
      response.status mustBe BAD_REQUEST
    }
  }

  "Context GET endpoint" should {
    "return all contexts for given project" in {
      val tenant = "my-tenant"
      val project = "my-project"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(TestProject(project), TestProject("my-project2"))
        )
        .build()

      situation.createContext(tenant, project, "my-context")
      situation.createContext(tenant, project, "my-context2")
      situation.createContext(tenant, "my-project2", "my-context3")
      val result = situation.fetchContexts(tenant, project)

      result.status mustBe OK
      (result.json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("my-context", "my-context2")
    }

    "return hierarchy of contexts for given project" in {
      val tenant = "my-tenant"
      val project = "my-project"
      val myContext = "my-context"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(TestProject(project), TestProject("my-project2"))
        )
        .build()

      situation.createContext(tenant, project, myContext)
      situation.createContext(
        tenant,
        project,
        "subcontext",
        parents = myContext
      )
      situation.createContext(
        tenant,
        project,
        "subcontext2",
        parents = myContext
      )
      situation.createContext(
        tenant,
        project,
        "subsubcontext",
        parents = s"${myContext}/subcontext"
      )
      situation.createContext(
        tenant,
        project,
        "subsubcontext12",
        parents = s"${myContext}/subcontext"
      )
      situation.createContext(
        tenant,
        project,
        "subsubcontext21",
        parents = s"${myContext}/subcontext2"
      )

      val result = situation.fetchContexts(tenant, project)

      result.status mustBe OK
      val json = result.json.get
      val first = json.as[JsArray].head
      (first \ "name").as[String] mustEqual myContext
      val children = (first \ "children").as[JsArray]
      children.value.map(v =>
        (v \ "name").as[String]
      ) must contain theSameElementsAs Seq("subcontext", "subcontext2")

      (children.value
        .filter(v => (v \ "name").as[String] equals "subcontext")
        .head \ "children" \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("subsubcontext", "subsubcontext12")
      (children.value
        .filter(v => (v \ "name").as[String] equals "subcontext2")
        .head \ "children" \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("subsubcontext21")
    }

    "return mixed global / local context hierarchy" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext(
                "global",
                subContext = Set(TestFeatureContext("subglobal"))
              )
            )
            .withProjects(
              TestProject("project")
                .withContexts(TestFeatureContext("toplocal"))
            )
        )
        .build()

      situation.createContext(
        "tenant",
        "project",
        name = "localchildofglobal",
        parents = "global"
      )
      situation.createContext(
        "tenant",
        "project",
        name = "localsubchild",
        parents = "global/localchildofglobal"
      )
      situation.createContext(
        "tenant",
        "project",
        name = "localchild",
        parents = "toplocal"
      )
      situation.createContext(
        "tenant",
        "project",
        name = "subgloballocalchild",
        parents = "subglobal"
      )

      val result = situation.fetchContexts("tenant", "project")

      result.status mustBe OK
      val json = result.json.get.as[JsArray].value.map(v => v.as[JsObject])

      val topLocal =
        json.find(obj => (obj \ "name").get.as[String] == "toplocal").get
      val localchild = (topLocal \ "children").as[JsArray].value.head
      (localchild \ "name").get.as[String] mustEqual "localchild"

      val global =
        json.find(obj => (obj \ "name").get.as[String] == "global").get
      (global \ "children").get
        .as[JsArray]
        .value
        .map(v =>
          (v \ "name").get.as[String]
        ) must contain theSameElementsAs Seq("localchildofglobal", "subglobal")

      val localchildofglobal =
        (global \ "children").get
          .as[JsArray]
          .value
          .find(v => (v \ "name").get.as[String] == "localchildofglobal")
          .get
      (localchildofglobal \ "children").get
        .as[JsArray]
        .value
        .map(js =>
          (js \ "name").get.as[String]
        ) must contain theSameElementsAs Seq("localsubchild")
    }

    "return true global attribue when context is global" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext(
                "global",
                subContext = Set(TestFeatureContext("subglobal"))
              )
            )
            .withProjects(
              TestProject("project")
                .withContexts(TestFeatureContext("toplocal"))
            )
        )
        .build()

      situation.createContext(
        "tenant",
        "project",
        name = "localchildofglobal",
        parents = "global"
      )
      situation.createContext(
        "tenant",
        "project",
        name = "localsubchild",
        parents = "global/localchildofglobal"
      )
      situation.createContext(
        "tenant",
        "project",
        name = "localchild",
        parents = "toplocal"
      )
      situation.createContext(
        "tenant",
        "project",
        name = "subgloballocalchild",
        parents = "subglobal"
      )

      val result = situation.fetchContexts("tenant", "project")

      result.status mustBe OK
      val json = result.json.get.as[JsArray].value.map(v => v.as[JsObject])

      val topLocal =
        json.find(obj => (obj \ "name").get.as[String] == "toplocal").get
      (topLocal \ "global").as[Boolean] mustBe false

      val localchild = (topLocal \ "children").as[JsArray].value.head
      (localchild \ "global").get.as[Boolean] mustBe false

      val global =
        json.find(obj => (obj \ "name").get.as[String] == "global").get
      (global \ "global").get.as[Boolean] mustBe true

      val localchildofglobal =
        (global \ "children").get
          .as[JsArray]
          .value
          .find(v => (v \ "name").get.as[String] == "localchildofglobal")
          .get
      (localchildofglobal \ "global").get.as[Boolean] mustBe false

      val localsubchild = (localchildofglobal \ "children").get
        .as[JsArray]
        .value
        .find(v => (v \ "name").get.as[String] == "localsubchild")
        .get
      (localsubchild \ "global").get.as[Boolean] mustBe false

      val subglobal =
        (global \ "children").get
          .as[JsArray]
          .value
          .find(v => (v \ "name").get.as[String] == "subglobal")
          .get
      (subglobal \ "global").get.as[Boolean] mustBe true

    }
  }

  "Feature context DELETE endpoint" should {
    "prevent context delete if context has protected subcontexts" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("proj")
                .withContexts(
                  TestFeatureContext("parent")
                    .withSubContexts(
                      TestFeatureContext("subcontext", isProtected = true)
                    )
                )
            )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
            .withProjectReadWriteRight("proj", tenant = "tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response = situation.deleteContext(
        tenant = "tenant",
        project = "proj",
        path = "parent"
      )
      response.status mustBe FORBIDDEN
    }

    "prevent context delete if context is protected and user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjects(
            TestProject("project").withContexts(
              TestFeatureContext("protected", isProtected = true)
            )
          )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
            .withProjectReadWriteRight("project", "tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response = situation.deleteContext(
        tenant = "tenant",
        project = "project",
        path = "protected"
      )
      response.status mustBe FORBIDDEN
    }

    "prevent subcontext delete if subcontext is protected and user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjects(
            TestProject("project")
              .withContexts(
                TestFeatureContext("unprotectedparent", isProtected = false)
                  .withSubContexts(
                    TestFeatureContext("protected", isProtected = true)
                  )
              )
          )
        )
        .withUsers(
          TestUser(username = "noadmin")
            .withTenantReadWriteRight("tenant")
            .withProjectReadWriteRight("project", "tenant")
        )
        .loggedAs("noadmin")
        .build()

      val response = situation.deleteContext(
        tenant = "tenant",
        project = "project",
        path = "unprotectedparent/protected"
      )
      response.status mustBe FORBIDDEN
    }

    "allow to delete context if user has project write right" in {
      val situation = TestSituationBuilder()
        .withUsers(
          TestUser("testu")
            .withTenantReadRight("tenant")
            .withProjectReadWriteRight(tenant = "tenant", project = "project")
        )
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withContextNames("context")
            )
        )
        .loggedAs("testu")
        .build()

      val response = situation.deleteContext(
        tenant = "tenant",
        project = "project",
        path = "context"
      )
      response.status mustBe NO_CONTENT
    }
  }

  "Overloaded feature DELETE endpoint" should {
    "prevent deleting an overload in protected context if user it not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(TestFeature(name = "F1", enabled = false))
                .withContexts(
                  TestFeatureContext("context", isProtected = true)
                    .withFeatureOverload(TestFeature("F1", enabled = true))
                )
            )
        )
        .withUsers(
          TestUser("testu")
            .withTenantReadRight("tenant")
            .withProjectReadWriteRight(project = "project", tenant = "tenant")
        )
        .loggedAs("testu")
        .build()

      val response =
        situation.deleteFeatureOverload("tenant", "project", "context", "F1")

      response.status mustBe FORBIDDEN
    }

    "delete feature context if user has project write right" in {
      val situation = TestSituationBuilder()
        .withUsers(
          TestUser("testu")
            .withTenantReadRight("tenant")
            .withProjectReadWriteRight(project = "project", tenant = "tenant")
        )
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(TestFeature(name = "F1", enabled = false))
                .withContexts(
                  TestFeatureContext("context")
                    .withFeatureOverload(TestFeature("F1", enabled = true))
                )
            )
        )
        .loggedAs("testu")
        .build()

      val response =
        situation.deleteFeatureOverload("tenant", "project", "context", "F1")

      response.status mustBe NO_CONTENT

      val contextResponse =
        situation.fetchContexts("tenant", "project").json.get

      (contextResponse \\ "overloads").flatMap(js =>
        js.as[JsArray].value
      ) mustBe empty
    }

    "forbid feature overload delete if user does not have project write right" in {
      val situation = TestSituationBuilder()
        .withUsers(
          TestUser("testu")
            .withTenantReadRight("tenant")
            .withProjectReadRight(project = "project", tenant = "tenant")
        )
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(TestFeature(name = "F1", enabled = false))
                .withContexts(
                  TestFeatureContext("context")
                    .withFeatureOverload(TestFeature("F1", enabled = true))
                )
            )
        )
        .loggedAs("testu")
        .build()

      val response =
        situation.deleteFeatureOverload("tenant", "project", "context", "F1")

      response.status mustBe FORBIDDEN

      val contextResponse =
        situation.fetchContexts("tenant", "project").json.get

      contextResponse.as[JsArray].value must have size 1
    }

    "allow to delete overload for global context" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("context"))
            .withProjects(
              TestProject("project")
                .withFeatures(TestFeature(name = "F1", enabled = false))
            )
        )
        .build()

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true
      )

      val result =
        situation.deleteFeatureOverload("tenant", "project", "context", "F1")
      result.status mustBe NO_CONTENT

    }
  }

  "Context feature PUT endpoint" should {
    "not create preserved stratgey in protected context child of another protected context for which old strategy was preserved" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("proj")
                .withFeatures(TestFeature("F1", enabled = true))
                .withContexts(
                  TestFeatureContext(
                    "production",
                    isProtected = true
                  ),
                  TestFeatureContext(
                    "ctx",
                    subContext =
                      Set(TestFeatureContext("prod", isProtected = true, subContext = Set(TestFeatureContext("mobile", isProtected = true))))
                  )
                )
            )
        )
        .build()

      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "proj",
        "ctx",
        "F1",
        enabled = false,
        preserveProtectedContexts = true
      )

      res.status mustEqual NO_CONTENT

      val contexts =
        situation.fetchContexts("tenant", project = "proj").json.get
      val ctx = (contexts)
        .as[JsArray]
        .value
        .find(json => (json \ "name").as[String] == "ctx")
        .get
        .as[JsObject]
      val prodCtx = (ctx \ "children" \ 0).as[JsObject]
      val mobileCtx = (prodCtx \ "children" \ 0).as[JsObject]

      def overloads(context: JsObject): Seq[JsValue] =
        (context \ "overloads").as[JsArray].value.toSeq

      overloads(ctx) must have size 1
      overloads(prodCtx) must have size 1
      overloads(mobileCtx) must have size 0
    }

    "prevent overload upsert in context with protected subcontext without overload if user is not project admin and old strategy preservation is not set" in {
      var situation = TestSituationBuilder()
        .withUsers(
          TestUser("testu")
            .withTenantReadRight("tenant")
            .withProjectReadWriteRight(project = "proj", tenant = "tenant")
        )
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("proj")
                .withFeatures(TestFeature("F1", enabled = true))
                .withContexts(
                  TestFeatureContext(
                    "production",
                    isProtected = true
                  ),
                  TestFeatureContext(
                    "ctx",
                    subContext =
                      Set(TestFeatureContext("prod", isProtected = true))
                  )
                )
            )
        )
        .loggedAs("testu")
        .build()

      var res = situation.changeFeatureStrategyForContext(
        "tenant",
        "proj",
        "ctx",
        "F1",
        enabled = false,
        preserveProtectedContexts = false
      )

      res.status mustEqual FORBIDDEN

      var contexts =
        situation.fetchContexts("tenant", project = "proj").json.get
      var ctx = (contexts)
        .as[JsArray]
        .value
        .find(json => (json \ "name").as[String] == "ctx")
        .get
        .as[JsObject]
      var prodCtx = (ctx \ "children" \ 0).as[JsObject]

      def overloads(context: JsObject): Seq[JsValue] =
        (context \ "overloads").as[JsArray].value.toSeq

      overloads(ctx) must have size 0
      overloads(prodCtx) must have size 0


      situation = situation.loggedAsAdmin()
      res = situation.changeFeatureStrategyForContext(
        "tenant",
        "proj",
        "ctx",
        "F1",
        enabled = false,
        preserveProtectedContexts = false
      )
      res.status mustEqual NO_CONTENT

      contexts =
        situation.fetchContexts("tenant", project = "proj").json.get
      ctx = (contexts)
        .as[JsArray]
        .value
        .find(json => (json \ "name").as[String] == "ctx")
        .get
        .as[JsObject]
      prodCtx = (ctx \ "children" \ 0).as[JsObject]

      overloads(ctx) must have size 1
      overloads(prodCtx) must have size 0
    }

    "preserve old strategy when creating an overload in context with protected subcontext" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("proj")
                .withFeatures(TestFeature("F1", enabled = true))
                .withContexts(
                  TestFeatureContext(
                    "production",
                    isProtected = true
                  ),
                  TestFeatureContext(
                    "ctx",
                    subContext =
                      Set(TestFeatureContext("prod", isProtected = true))
                  )
                )
            )
        )
        .build()

      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "proj",
        "ctx",
        "F1",
        enabled = false,
        preserveProtectedContexts = true
      )

      res.status mustEqual NO_CONTENT

      val contexts =
        situation.fetchContexts("tenant", project = "proj").json.get

      val production = (contexts)
        .as[JsArray]
        .value
        .find(json => (json \ "name").as[String] == "production")
        .get
        .as[JsObject]
      val ctx = (contexts)
        .as[JsArray]
        .value
        .find(json => (json \ "name").as[String] == "ctx")
        .get
        .as[JsObject]
      val prodCtx = (ctx \ "children" \ 0).as[JsObject]

      def overloads(context: JsObject): Seq[JsValue] =
        (context \ "overloads").as[JsArray].value.toSeq

      overloads(ctx) must have size 1
      overloads(prodCtx) must have size 1
      overloads(production) must have size 0
    }

    "Reject overload creation with incorrect resultType" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("prod"))
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1"
                  ),
                  TestFeature(
                    "F2",
                    resultType = "string",
                    value = "foo"
                  )
                )
            )
        )
        .build()

      situation
        .changeFeatureStrategyForContext(
          "tenant",
          "project",
          contextPath = "prod",
          feature = "F1",
          enabled = true,
          resultType = "string",
          value = "foo"
        )
        .status mustBe BAD_REQUEST

      situation
        .changeFeatureStrategyForContext(
          "tenant",
          "project",
          contextPath = "prod",
          feature = "F2",
          enabled = true,
          resultType = "boolean"
        )
        .status mustBe BAD_REQUEST

      situation
        .changeFeatureStrategyForContext(
          "tenant",
          "project",
          contextPath = "prod",
          feature = "F2",
          enabled = true,
          resultType = "number",
          value = "1.5"
        )
        .status mustBe BAD_REQUEST
    }

    "Allow to overload a base script feature to classical feature" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("prod"))
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    wasmConfig = TestWasmConfig(
                      name = "wasmScript",
                      source = Json.obj(
                        "kind" -> "Base64",
                        "path" -> enabledFeatureBase64,
                        "opts" -> Json.obj()
                      )
                    )
                  )
                )
            )
        )
        .build()

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        contextPath = "prod",
        feature = "F1",
        enabled = false
      )

      val response = situation.fetchContexts("tenant", "project")

      val json = response.json.get
      val jsonOverload = json \ 0 \ "overloads" \ 0
      jsonOverload.as[JsObject].keys must not contain "wasmConfig"
    }

    "Allow modifying enabling of a feature for this context" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withContextNames("context")
                .withFeatures(TestFeature(name = "F1", enabled = false))
            )
        )
        .build()

      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true,
        conditions = Set {
          TestCondition(
            rule = TestPercentageRule(80),
            period = TestDateTimePeriod().beginAt(LocalDateTime.now())
          )
        }
      )

      res.status mustBe NO_CONTENT
    }

    "Allow modifying enabling of a feature for this context twice" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withContextNames("context")
                .withFeatures(TestFeature(name = "F1", enabled = false))
            )
        )
        .build()

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true
      )
      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = false
      )

      res.status mustBe NO_CONTENT
    }

    "Return 404 if tenant does not exist" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()
      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true
      )

      res.status mustBe NOT_FOUND
    }

    "Return 404 if project does not exist" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant("tenant"))
        .build()
      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true
      )

      res.status mustBe NOT_FOUND
    }

    "Return 404 if context does not exist" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(TestProject("project").withFeatureNames("F1"))
        )
        .build()
      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true
      )

      res.status mustBe NOT_FOUND
    }

    "Return 404 if feature does not exist" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(TestProject("project").withContextNames("context"))
        )
        .build()
      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true
      )

      res.status mustBe NOT_FOUND
    }

    "Allow to add overload on global context" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("context"))
            .withProjects(
              TestProject("project")
                .withFeatures(TestFeature(name = "F1", enabled = false))
            )
        )
        .build()

      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true,
        conditions = Set {
          TestCondition(
            rule = TestPercentageRule(80),
            period = TestDateTimePeriod().beginAt(LocalDateTime.now())
          )
        }
      )

      res.status mustBe NO_CONTENT

      val contextsResponse = situation.fetchContexts("tenant", "project")
      val json = contextsResponse.json.get

      (json \ 0 \ "overloads").as[JsArray].value must not be empty

    }

    "Prevent overload creation on protected context if user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withContexts(TestFeatureContext("ctx", isProtected = true))
                .withFeatures(TestFeature(name = "F1", enabled = false))
            )
        )
        .withUsers(
          TestUser("notAdmin")
            .withTenantReadRight("tenant")
            .withProjectReadWriteRight("project", tenant = "tenant")
        )
        .loggedAs("notAdmin")
        .build()

      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "ctx",
        "F1",
        enabled = true
      )

      res.status mustBe FORBIDDEN
    }

    "Prevent overload creation on protected global context if user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(TestFeature(name = "F1", enabled = false))
            )
            .withGlobalContext(TestFeatureContext("ctx", isProtected = true))
        )
        .withUsers(TestUser("notAdmin").withTenantReadRight("tenant"))
        .loggedAs("notAdmin")
        .build()

      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "ctx",
        "F1",
        enabled = true
      )

      res.status mustBe FORBIDDEN
    }

    "Allow to add overload on local context that inherit global context" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(TestFeatureContext("context"))
            .withProjects(
              TestProject("project")
                .withFeatures(TestFeature(name = "F1", enabled = false))
            )
        )
        .build()

      situation.createContext(
        "tenant",
        "project",
        "subcontext",
        parents = "context"
      )

      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context/subcontext",
        "F1",
        enabled = true
      )

      res.status mustBe NO_CONTENT
    }
  }

  "Tenant context GET endpoint" should {
    "Return correctly organized global and local contexts when all is true" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext(
                "global",
                subContext = Set(TestFeatureContext("subglobal"))
              )
            )
            .withProjects(
              TestProject("project").withContexts(
                TestFeatureContext(
                  "toplocal",
                  subContext = Set(TestFeatureContext("sublocal"))
                )
              )
            )
        )
        .build()

      var status = situation
        .createContext(
          "tenant",
          "project",
          name = "localsubglobal",
          parents = "global"
        )
        .status
      status mustBe CREATED
      status = situation
        .createContext(
          "tenant",
          "project",
          name = "localsubsubglobal",
          parents = "global/subglobal"
        )
        .status
      status mustBe CREATED
      val result = situation.fetchGlobalContext("tenant", all = true)
      result.status mustBe OK

      val json = result.json.get.as[JsArray]

      // Checking global part
      val globalCtx =
        json.value.find(node => (node \ "name").as[String] == "global").get
      val children = (globalCtx \ "children").as[JsArray].value
      val childrenNames = children.map(node => (node \ "name").as[String])
      childrenNames must contain theSameElementsAs Seq(
        "localsubglobal",
        "subglobal"
      )
      val subglobal =
        children.find(node => (node \ "name").as[String] == "subglobal").get
      val subglobalChildren = (subglobal \ "children").as[JsArray].value
      subglobalChildren must have length 1
      (subglobalChildren.head \ "name").as[String] mustEqual "localsubsubglobal"

      // Checking local part
      val localCtx =
        json.value.find(node => (node \ "name").as[String] == "toplocal").get
      val localChildren = (localCtx \ "children").as[JsArray].value
      localChildren must have length 1
      (localChildren.head \ "name").as[String] mustEqual "sublocal"
    }

    "Return only global context if all is false" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withGlobalContext(
              TestFeatureContext(
                "global",
                subContext = Set(TestFeatureContext("subglobal"))
              )
            )
            .withProjects(
              TestProject("project").withContexts(
                TestFeatureContext(
                  "toplocal",
                  subContext = Set(TestFeatureContext("sublocal"))
                )
              )
            )
        )
        .build()

      var status = situation
        .createContext(
          "tenant",
          "project",
          name = "localsubglobal",
          parents = "global"
        )
        .status
      status mustBe CREATED
      status = situation
        .createContext(
          "tenant",
          "project",
          name = "localsubsubglobal",
          parents = "global/subglobal"
        )
        .status
      status mustBe CREATED
      val result = situation.fetchGlobalContext("tenant", all = false)
      result.status mustBe OK

      val json = result.json.get.as[JsArray].value
      json must have length 1

      val globalCtx =
        json.find(node => (node \ "name").as[String] == "global").get
      val children = (globalCtx \ "children").as[JsArray].value
      children must have length 1
      (children.head \ "name").as[String] mustEqual "subglobal"
    }
  }
}
