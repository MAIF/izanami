package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{
  disabledFeatureBase64,
  enabledFeatureBase64,
  TestSituationBuilder,
  TestTenant,
  TestWasmConfig
}
import play.api.http.Status.{BAD_REQUEST, NO_CONTENT}
import play.api.libs.json.{JsArray, Json}

class PluginAPISpec extends BaseAPISpec {

  "Plugin endpoint" should {
    "retrieve scripts names from wasm-manager" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo"))
        .withAllwaysInactiveWasmScript("izanami-disabled")
        .loggedInWithAdminRights()
        .build()

      val response = situation.fetchWasmManagerScripts()
      response.json.get.as[JsArray].value.length mustEqual 1
    }
  }

  "Tenant script endpoint" should {
    "retrieve existing script for a givent tenant" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo"))
        .loggedInWithAdminRights()
        .build()

      situation.createFeature(
        "feature",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      val response = situation.fetchTenantScripts("tenant")
      (response.json.get \\ "name").map(jsv =>
        jsv.as[String]
      ) must contain theSameElementsAs Seq("wasmScript")
    }
  }

  "Tenant single script endpoint" should {
    "retrieve existing script for a givent tenant" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo"))
        .loggedInWithAdminRights()
        .build()

      situation.createFeature(
        "feature",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      val response = situation.fetchTenantScript("tenant", "wasmScript")
      val json = response.json.get
      (json \ "name").as[String] mustEqual "wasmScript"
      (json \ "source" \ "kind").as[String] mustEqual "Base64"
      (json \ "source" \ "path").as[String] must not be null
    }
  }

  "Script DELETE endpoint" should {
    "delete script if it has no associated features" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo"))
        .loggedInWithAdminRights()
        .build()

      val featureresponse = situation.createFeature(
        "feature",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      situation.deleteFeature("tenant", featureresponse.id.get)

      val deleteScriptResponse = situation.deleteScript("tenant", "wasmScript")
      deleteScriptResponse.status mustEqual NO_CONTENT

      situation
        .fetchTenantScripts("tenant")
        .json
        .get
        .as[JsArray]
        .value
        .length mustEqual 0
    }

    "prevent script deletion if feature depends on it" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo"))
        .loggedInWithAdminRights()
        .build()

      val featureresponse = situation.createFeature(
        "feature",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      val deleteScriptResponse = situation.deleteScript("tenant", "wasmScript")
      deleteScriptResponse.status mustEqual BAD_REQUEST

      situation
        .fetchTenantScripts("tenant")
        .json
        .get
        .as[JsArray]
        .value
        .length mustEqual 1
    }
  }

  "Script update (PUT) endpoint" should {
    "allow to update script name" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo"))
        .loggedInWithAdminRights()
        .build()

      situation.createFeature(
        "feature",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      val updateScriptResponse = situation.updateScript(
        "tenant",
        "wasmScript",
        TestWasmConfig(
          name = "wasmScript2",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      updateScriptResponse.status mustBe NO_CONTENT
      val response = situation.fetchProject("tenant", "foo")

      (response.json.get \\ "wasmConfig")
        .map(js => js.as[String])
        .head mustEqual "wasmScript2"
    }

    "allow to update script configuration" in {
      // FIXME ????
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo", "bar"))
        .loggedInWithAdminRights()
        .build()

      situation.createFeature(
        "feature",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      situation.createFeature(
        "feature",
        enabled = true,
        project = "bar",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript2",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> enabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      val updateScriptResponse = situation.updateScript(
        "tenant",
        "wasmScript",
        TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> enabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      updateScriptResponse.status mustBe NO_CONTENT
      val response = situation.fetchProject("tenant", "foo")

      (response.json.get \\ "wasmConfig")
        .map(js => js.as[String])
        .head mustEqual "wasmScript"
    }
  }

}
