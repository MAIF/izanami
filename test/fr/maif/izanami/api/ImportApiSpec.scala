package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.TestFeature
import fr.maif.izanami.api.BaseAPISpec.TestFeatureContext
import fr.maif.izanami.api.BaseAPISpec.TestPersonnalAccessToken
import fr.maif.izanami.api.BaseAPISpec.TestProject
import fr.maif.izanami.api.BaseAPISpec.TestRights
import fr.maif.izanami.api.BaseAPISpec.TestSituationBuilder
import fr.maif.izanami.api.BaseAPISpec.TestTenant
import fr.maif.izanami.api.BaseAPISpec.TestUser
import fr.maif.izanami.api.BaseAPISpec.TestWasmConfig
import fr.maif.izanami.api.BaseAPISpec.enabledFeatureBase64
import fr.maif.izanami.api.BaseAPISpec.importWithToken
import org.scalatest.matchers.should.Matchers.should
import org.awaitility.Awaitility.await
import play.api.http.Status.ACCEPTED
import play.api.http.Status.CREATED
import play.api.http.Status.FORBIDDEN
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.NO_CONTENT
import play.api.http.Status.OK
import play.api.http.Status.UNAUTHORIZED
import play.api.libs.json.JsArray
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.http.Status.*

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
import fr.maif.izanami.api.BaseAPISpec.TestApiKey
import scala.concurrent.duration.SECONDS
import play.api.libs.json.JsValue

class ImportApiSpec extends BaseAPISpec {
  "V2 feature import" should {

    "fail on incorrect row with known type" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b9","description":""},"_type":"project"}""",
        """{"row":{"name":"test-project2","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"name":"foobar2","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea7","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      val res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual BAD_REQUEST

      def extractRowField(
          rows: scala.collection.IndexedSeq[JsValue],
          fieldName: String
      ): scala.collection.IndexedSeq[String] = {
        rows.flatMap(json =>
          (json \ fieldName).asOpt[String].toSeq
        )
      }

      val featureAnomalies =
        (res.json.get \ "details" \ "Feature").as[JsArray].value;
      val projectAnomalies =
        (res.json.get \ "details" \ "Project").as[JsArray].value;

      featureAnomalies must have size 2
      extractRowField(
        featureAnomalies,
        "id"
      ) must contain theSameElementsAs (Seq(
        "00273cce-5b8e-447b-8a2e-0ba8d39bdea7"
      ));

      extractRowField(
        featureAnomalies,
        "name"
      ) must contain theSameElementsAs (Seq(
        "foobar2"
      ))

      extractRowField(
        projectAnomalies,
        "id"
      ) must contain theSameElementsAs (Seq(
        "f049894f-fc2d-4335-b3a5-1a2a9af242b9"
      ))
      extractRowField(
        projectAnomalies,
        "name"
      ) must contain theSameElementsAs (Seq("test-project2"))

    }

    "fail on incorrect row" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"projectFOOBAR"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"name":"foobar2","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null}}""",
        """{"_type":"feature"}"""
      )

      val res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual BAD_REQUEST

      val erroneousRows =
        (res.json.get \ "details" \ "Unknown").as[JsArray].value.map(json =>
          json.as[String]
        );

      erroneousRows must contain theSameElementsAs (Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"projectFOOBAR"}""",
        """{"row":{"name":"foobar2","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null}}""",
        """{"_type":"feature"}"""
      ))

    }

    "fail on non json row" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8",""",
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"projectFOOBAR"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null}}""",
        """{"_type:"feature"}"""
      )

      val res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual BAD_REQUEST
      val erroneousRows =
        (res.json.get \ "details" \ "Unknown").as[JsArray].value.map(json =>
          json.as[String]
        );

      erroneousRows must contain theSameElementsAs (Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8",""",
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"projectFOOBAR"}""",
        """"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null}}""",
        """{"_type:"feature"}"""
      ))

    }

    "indicate all failing rows" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b9","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b7","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea9","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea7","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      val res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual BAD_REQUEST
      val featureAnomalies =
        (res.json.get \ "details" \ "Feature").as[JsArray].value

      featureAnomalies.map(json =>
        (json \ "id").as[String]
      ) must contain theSameElementsAs (Seq(
        "00273cce-5b8e-447b-8a2e-0ba8d39bdea9",
        "00273cce-5b8e-447b-8a2e-0ba8d39bdea7"
      ))

      val projectAnomalies =
        (res.json.get \ "details" \ "Project").as[JsArray].value

      projectAnomalies.map(json =>
        (json \ "id").as[String]
      ) must contain theSameElementsAs (Seq(
        "f049894f-fc2d-4335-b3a5-1a2a9af242b9",
        "f049894f-fc2d-4335-b3a5-1a2a9af242b7"
      ))
    }

    "not fail on duplicated rows" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      val res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK
    }

    "insert / update nothing in case of unplanned conflict (Fail) and return failed rows" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK

      data = Seq(
        """{"row":{"id":"fifou","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"fifou2","name":"foobar2","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "fail"
      )

      res.status mustEqual BAD_REQUEST

      val failures = (res.response.json \ "details").as[JsObject]
      failures.keys must have size 1
      val featureFailures = (failures \ "Feature").as[JsArray].value
      featureFailures must have size 1
      (featureFailures.head \ "id").as[String] mustEqual "fifou"

      val project = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      ).json.get

      (project \ "features").as[JsArray].value must have size 1
    }

    "insert / update nothing in case of unplanned conflict (Overwrite mode) and return failed rows" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK

      data = Seq(
        """{"row":{"id":"fifou","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"fifou2","name":"foobar2","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "overwrite"
      )

      res.status mustEqual BAD_REQUEST

      val failures = (res.response.json \ "details").as[JsObject]
      failures.keys must have size 1
      val featureFailures = (failures \ "Feature").as[JsArray].value
      featureFailures must have size 1
      (featureFailures.head \ "id").as[String] mustEqual "fifou"

      val project = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      ).json.get

      (project \ "features").as[JsArray].value must have size 1
    }

    "insert / update nothing in case of unplanned conflict (Skip mode) and return failed rows" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK

      data = Seq(
        """{"row":{"id":"fifou","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"fifou2","name":"foobar2","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual BAD_REQUEST

      val failures = (res.response.json \ "details").as[JsObject]
      failures.keys must have size 1
      val featureFailures = (failures \ "Feature").as[JsArray].value
      featureFailures must have size 1
      (featureFailures.head \ "id").as[String] mustEqual "fifou"

      val project = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      ).json.get

      (project \ "features").as[JsArray].value must have size 1
    }

    "generate event when only feature tags are updated" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"57c1ff3b-59cd-48b8-8086-f3f84237dcaf","name":"foo","description":""},"_type":"tag"}""",
        """{"row":{"id":"80100f5d-f93f-4dbf-acf0-1789e712b774","name":"bar","description":""},"_type":"tag"}""",
        """{"row":{"id":"35fd79f5-512a-4082-a05e-9561ed3dcfd9","name":"baz","description":""},"_type":"tag"}""",
        """{"row":{"tag":"bar","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}""",
        """{"row":{"tag":"foo","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK

      val projectResponse = situation.fetchProject("testtenant", "test-project")
      val projectId = (projectResponse.json.get \ "id").as[String]

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects =
          Seq(projectId),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )

      data = Seq(
        """{"row":{"tag":"bar","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}""",
        """{"row":{"tag":"baz","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "overwrite"
      )
      res.status mustEqual OK

      await atMost (10, SECONDS) until {
        evts.exists(e => e.eventType.get == "FEATURE_UPDATED")
      }

      val evt = evts.findLast(e => e.eventType.get == "FEATURE_UPDATED").get

      (Json.parse(evt.data) \ "payload" \ "name").as[String] mustEqual "foobar"
    }

    "generate event when only feature overload is updated" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"name":"prod","global":true,"parent":null,"project":null,"protected":false},"_type":"context"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK

      val projectResponse = situation.fetchProject("testtenant", "test-project")
      val projectId = (projectResponse.json.get \ "id").as[String]

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects =
          Seq(projectId),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )

      data = Seq(
        """{"row":{"value":null,"context":"prod","enabled":true,"feature":"foobar","project":"test-project","conditions":[],"result_type":"boolean","script_config":null},"_type":"overload"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "overwrite"
      )
      res.status mustEqual OK

      await atMost (10, SECONDS) until {
        evts.exists(e => e.eventType.get == "FEATURE_UPDATED")
      }

      val evt = evts.findLast(e => e.eventType.get == "FEATURE_UPDATED").get

      (Json.parse(evt.data) \ "payload" \ "name").as[String] mustEqual "foobar"

      data = Seq(
        """{"row":{"value":null,"context":"prod","enabled":false,"feature":"foobar","project":"test-project","conditions":[],"result_type":"boolean","script_config":null},"_type":"overload"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "overwrite"
      )
      res.status mustEqual OK

      await atMost (10, SECONDS) until {
        evts.filter(e => e.eventType.get == "FEATURE_UPDATED").size == 2
      }
    }

    "generate a single event when overload, features and tags are updated" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withApiKeys(TestApiKey(
          name = "mykey",
          enabled = true,
          admin = true
        )))
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"57c1ff3b-59cd-48b8-8086-f3f84237dcaf","name":"foo","description":""},"_type":"tag"}""",
        """{"row":{"id":"80100f5d-f93f-4dbf-acf0-1789e712b774","name":"bar","description":""},"_type":"tag"}""",
        """{"row":{"id":"35fd79f5-512a-4082-a05e-9561ed3dcfd9","name":"baz","description":""},"_type":"tag"}""",
        """{"row":{"tag":"bar","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}""",
        """{"row":{"tag":"foo","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}""",
        """{"row":{"name":"prod","global":true,"parent":null,"project":null,"protected":false},"_type":"context"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK

      val projectResponse = situation.fetchProject("testtenant", "test-project")
      val projectId = (projectResponse.json.get \ "id").as[String]

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects =
          Seq(projectId),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )

      data = Seq(
        """{"row":{"tag":"bar","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}""",
        """{"row":{"tag":"baz","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}""",
        """{"row":{"value":null,"context":"prod","enabled":true,"feature":"foobar","project":"test-project","conditions":[],"result_type":"boolean","script_config":null},"_type":"overload"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "overwrite"
      )
      res.status mustEqual OK

      await atMost (10, SECONDS) until {
        evts.exists(e => e.eventType.get == "FEATURE_UPDATED")
      }
      val evt = evts.findLast(e => e.eventType.get == "FEATURE_UPDATED").get

      Thread.sleep(3_000)

      evts.filter(e => e.eventType.get == "FEATURE_UPDATED").size mustEqual 1

      (Json.parse(evt.data) \ "payload" \ "name").as[String] mustEqual "foobar"
    }

    "allow specifying custom merge rules for feature projects" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"9241408d-d04c-4f90-a45f-a3427eb1119a","name":"another-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK

      data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"9241408d-d04c-4f90-a45f-a3427eb1119a","name":"another-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"another-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip",
        featureProject = Some("overwrite")
      )

      res.status mustEqual OK

      var proj = situation.fetchProject(
        tenant = "testtenant",
        projectId = "another-project"
      )

      (proj.json.get \ "features" \ 0 \ "name").as[String] mustEqual "foobar"

      proj = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      )

      (proj.json.get \ "features").as[JsArray].value mustBe empty

      data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"9241408d-d04c-4f90-a45f-a3427eb1119a","name":"another-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip",
        featureProject = Some("skip")
      )

      res.status mustEqual OK

      proj = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      )

      (proj.json.get \ "features").as[JsArray].value mustBe empty

      proj = situation.fetchProject(
        tenant = "testtenant",
        projectId = "another-project"
      )

      (proj.json.get \ "features" \ 0 \ "name").as[String] mustEqual "foobar"
    }

    "allow specifying custom merge rules for feature tags" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"57c1ff3b-59cd-48b8-8086-f3f84237dcaf","name":"foo","description":""},"_type":"tag"}""",
        """{"row":{"id":"80100f5d-f93f-4dbf-acf0-1789e712b774","name":"bar","description":""},"_type":"tag"}""",
        """{"row":{"id":"35fd79f5-512a-4082-a05e-9561ed3dcfd9","name":"baz","description":""},"_type":"tag"}""",
        """{"row":{"tag":"bar","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}""",
        """{"row":{"tag":"foo","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip"
      )

      res.status mustEqual OK

      data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"57c1ff3b-59cd-48b8-8086-f3f84237dcaf","name":"foo","description":""},"_type":"tag"}""",
        """{"row":{"id":"80100f5d-f93f-4dbf-acf0-1789e712b774","name":"bar","description":""},"_type":"tag"}""",
        """{"row":{"id":"35fd79f5-512a-4082-a05e-9561ed3dcfd9","name":"baz","description":""},"_type":"tag"}""",
        """{"row":{"tag":"baz","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}""",
        """{"row":{"tag":"foo","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip",
        featureTags = Some("overwrite")
      )

      res.status mustEqual OK

      var proj = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      )

      (proj.json.get \ "features" \ 0 \ "tags").as[Seq[
        String
      ]] must contain theSameElementsAs Seq("baz", "foo")

      data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","value":null,"enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule":{"percentage":10},"period":null}],"created_at":"2026-04-13T14:14:39.312741+00:00","description":"foo","result_type":"boolean","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"57c1ff3b-59cd-48b8-8086-f3f84237dcaf","name":"foo","description":""},"_type":"tag"}""",
        """{"row":{"id":"80100f5d-f93f-4dbf-acf0-1789e712b774","name":"bar","description":""},"_type":"tag"}""",
        """{"row":{"id":"35fd79f5-512a-4082-a05e-9561ed3dcfd9","name":"baz","description":""},"_type":"tag"}""",
        """{"row":{"tag":"bar","feature":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8"},"_type":"feature_tag"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "overwrite",
        featureTags = Some("skip")
      )

      res.status mustEqual OK

      proj = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      )

      (proj.json.get \ "features" \ 0 \ "tags").as[Seq[
        String
      ]] must contain theSameElementsAs Seq("baz", "foo")
    }

    "allow specifying custom merge rules for feature properties" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()
      var data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"simple feature","enabled":true,"project":"test-project","metadata":{},"conditions":[],"description":"bar","script_config":null},"_type":"feature"}"""
      )

      var res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip",
        featureName = Some("overwrite")
      )

      res.status mustEqual OK

      data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobar","enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule": {"percentage": 10},"period": null}],"description":"foo","script_config":null},"_type":"feature"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip",
        featureName = Some("overwrite")
      )

      res.status mustEqual OK

      var proj = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      )

      (proj.json.get \ "features" \ 0 \ "name").as[String] mustEqual "foobar"
      (proj.json.get \ "features" \ 0 \ "description").as[String] mustEqual "bar"
      (proj.json.get \ "features" \ 0 \ "enabled").as[Boolean] mustEqual true
      (proj.json.get \ "features" \ 0 \ "conditions").as[JsArray].value mustBe empty

      data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"foobarbaz","enabled":false,"project":"test-project","metadata":{},"conditions":[{"rule": {"percentage": 10},"period": null}],"description":"foo","script_config":null},"_type":"feature"}"""
      )

      res = situation.importV2(
        "testtenant",
        data = data,
        conflictStrategy = "skip",
        featureName = Some("skip"),
        featureDescription = Some("overwrite"),
        featureEnabling = Some("overwrite"),
        featureConditions = Some("overwrite")
      )

      res.status mustEqual OK

      proj = situation.fetchProject(
        tenant = "testtenant",
        projectId = "test-project"
      )

      (proj.json.get \ "features" \ 0 \ "name").as[String] mustEqual "foobar"
      (proj.json.get \ "features" \ 0 \ "description").as[String] mustEqual "foo"
      (proj.json.get \ "features" \ 0 \ "enabled").as[Boolean] mustEqual false
      (proj.json.get \ "features" \ 0 \ "conditions" \ 0 \ "rule" \ "percentage").as[
        Int
      ] mustEqual 10

    }

    "return warning when importing wasm based features in an instance that doesn't allow wasm" in {
      var situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    name = "foo",
                    enabled = true,
                    wasmConfig = TestWasmConfig(
                      name = "wasmScript",
                      source = Json.obj(
                        "kind" -> "Base64",
                        "path" -> enabledFeatureBase64
                      )
                    )
                  ),
                  TestFeature(
                    name = "bar",
                    enabled = true
                  )
                )
            )
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .build()

      val id = situation
        .findFeatureId(tenant = "tenant", project = "project", feature = "foo")
        .get

      val exportResponse =
        situation.exportWithTokenName("tenant", situation.user, "foo")
      exportResponse.status mustBe OK

      situation = situation.restartServerWithConf(
        Map("app.feature.allow-wasm" -> "false")
      )

      val payload = exportResponse.text

      val res =
        situation.importV2(
          "tenant",
          data = payload.split("\n").toIndexedSeq,
          conflictStrategy = "SKIP"
        )

      res.status mustBe OK
      val json = res.json.get

      val msgs = (json \ "messages").as[Seq[String]]

      msgs.size mustEqual 1
      msgs.head must include(id)
    }

    "return warning when importing wasm based overloads in an instance that doesn't allow wasm" in {
      var situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(TestFeature("F1"), TestFeature("F2"))
                .withContexts(
                  TestFeatureContext(
                    "prod",
                    overloads = Seq(
                      TestFeature(
                        name = "F1",
                        enabled = true,
                        wasmConfig = TestWasmConfig(
                          name = "wasmScript",
                          source = Json.obj(
                            "kind" -> "Base64",
                            "path" -> enabledFeatureBase64
                          )
                        )
                      )
                    )
                  ),
                  TestFeatureContext(
                    "dev",
                    overloads = Seq(
                      TestFeature(
                        name = "F1",
                        enabled = true
                      )
                    )
                  )
                )
            )
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .build()

      situation
        .findFeatureId(tenant = "tenant", project = "project", feature = "F1")
        .get

      val exportResponse =
        situation.exportWithTokenName("tenant", situation.user, "foo")
      exportResponse.status mustBe OK

      situation = situation.restartServerWithConf(
        Map("app.feature.allow-wasm" -> "false")
      )

      val payload = exportResponse.text

      val res =
        situation.importV2(
          "tenant",
          data = payload.split("\n").toIndexedSeq,
          conflictStrategy = "SKIP"
        )

      res.status mustBe OK
      val json = res.json.get

      val msgs = (json \ "messages").as[Seq[String]]

      msgs.size mustEqual 1
      msgs.head must include(s"F1 (project project, context prod)")
    }

    "allow importing tags with non uuid ids" in {
      val data = Seq(
        """{"row":{"id":"fifou","name":"ttt","description":""},"_type":"tag"}
          |""".stripMargin
      )

      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("foo")
        )
        .loggedInWithAdminRights()
        .build()

      val res =
        situation.importV2("foo", data = data, conflictStrategy = "FAIL")

      res.status mustBe OK
    }

    "allow importing project with non uuid ids" in {
      val data = Seq(
        """{"row":{"id":"fifou","name":"proj","description":""},"_type":"project"}
          |""".stripMargin
      )

      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("foo")
        )
        .loggedInWithAdminRights()
        .build()

      val res =
        situation.importV2("foo", data = data, conflictStrategy = "FAIL")

      res.status mustBe OK
    }

    "Update context id when importing it from a tenant with different name" in {
      val data = Seq(
        """{"row":{"id":"techtales_prod","name":"prod","parent":null},"_type":"global_context"}
          |{"row":{"id":"proj_prod_local","name":"local","parent":null,"project":"proj","global_parent":"techtales_prod"},"_type":"local_context"}
          |{"row":{"id":"proj_fifou","name":"fifou","parent":null,"project":"proj","global_parent":null},"_type":"local_context"}
          |""".stripMargin
      )

      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("foo").withProjects(
            TestProject("proj").withFeatureNames("f1")
          )
        )
        .loggedInWithAdminRights()
        .build()

      val res = situation.importV2("foo", data = data)

      val response = situation.changeFeatureStrategyForContext(
        "foo",
        "proj",
        contextPath = "prod",
        feature = "f1",
        enabled = false
      )
      val localResponse = situation.changeFeatureStrategyForContext(
        "foo",
        "proj",
        contextPath = "prod/local",
        feature = "f1",
        enabled = true
      )
      val projectLocalResponse = situation.changeFeatureStrategyForContext(
        "foo",
        "proj",
        contextPath = "fifou",
        feature = "f1",
        enabled = false
      )

      res.status mustEqual OK
      response.status mustEqual NO_CONTENT
      localResponse.status mustEqual NO_CONTENT
      projectLocalResponse.status mustEqual NO_CONTENT
    }

    "allow to import features with all rights token" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withPersonnalAccessToken(
          TestPersonnalAccessToken("foo", allRights = true)
        )
        .loggedInWithAdminRights()
        .build()

      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"simple feature","enabled":true,"project":"test-project","metadata":{},"conditions":[],"description":"","script_config":null},"_type":"feature"}"""
      )

      val secret = situation.findTokenSecret(situation.user, "foo");

      val res =
        importWithToken("testtenant", situation.user, secret, data = data)
      res.status mustBe OK
    }

    "allow to import features with import only token" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(
            "foo",
            allRights = false,
            rights = Map("testtenant" -> Set("IMPORT"))
          )
        )
        .loggedInWithAdminRights()
        .build()

      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"simple feature","enabled":true,"project":"test-project","metadata":{},"conditions":[],"description":"","script_config":null},"_type":"feature"}"""
      )

      val secret = situation.findTokenSecret(situation.user, "foo");

      val res =
        importWithToken("testtenant", situation.user, secret, data = data)
      res.status mustBe OK
    }

    "prevent to import features with export only token" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(
            "foo",
            allRights = false,
            rights = Map("testtenant" -> Set("EXPORT"))
          )
        )
        .loggedInWithAdminRights()
        .build()

      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"simple feature","enabled":true,"project":"test-project","metadata":{},"conditions":[],"description":"","script_config":null},"_type":"feature"}"""
      )

      val secret = situation.findTokenSecret(situation.user, "foo");

      val res =
        importWithToken("testtenant", situation.user, secret, data = data)
      res.status mustBe UNAUTHORIZED
    }

    "prevent to import features if user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withUsers(
          TestUser("testu", "testutestu").withTenantReadWriteRight("testtenant")
        )
        .loggedInWithAdminRights()
        .loggedAs("testu")
        .build()

      val resp = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken("foo", allRights = true)
      )
      resp.status mustBe CREATED
      val secret = (resp.json.get \ "token").as[String]

      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"simple feature","enabled":true,"project":"test-project","metadata":{},"conditions":[],"description":"","script_config":null},"_type":"feature"}"""
      )

      val res = importWithToken("testtenant", "testu", secret, data = data)
      res.status mustBe FORBIDDEN
    }

    "prevent to import features if user is not tenant admin, even if token has import rights" in {
      val user = TestUser(
        username = "testu",
        admin = true,
        rights = TestRights().addTenantRight("testtenant", level = "Write")
      )
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withUsers(user)
        .loggedAs(user.username)
        .build()

      val resp = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken(
          "foo",
          allRights = false,
          rights = Map("testtenant" -> Set("IMPORT"))
        )
      )
      resp.status mustBe CREATED

      val userUpdateResponse =
        situation.updateUserRights(user.username, false, user.rights)
      userUpdateResponse.status mustEqual NO_CONTENT

      val secret = (resp.json.get \ "token").as[String]

      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"simple feature","enabled":true,"project":"test-project","metadata":{},"conditions":[],"description":"","script_config":null},"_type":"feature"}"""
      )

      val res = importWithToken("testtenant", "testu", secret, data = data)
      res.status mustBe FORBIDDEN
    }

    "allow to import typeless features" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()
      val data = Seq(
        """{"row":{"id":"f049894f-fc2d-4335-b3a5-1a2a9af242b8","name":"test-project","description":""},"_type":"project"}""",
        """{"row":{"id":"00273cce-5b8e-447b-8a2e-0ba8d39bdea8","name":"simple feature","enabled":true,"project":"test-project","metadata":{},"conditions":[],"description":"","script_config":null},"_type":"feature"}"""
      )

      val res = situation.importV2("testtenant", data = data)

      res.status mustEqual OK
    }

    "allow to import typeless features & overloads" in {
      val data = Seq(
        """{"row":{"id":"a28977e3-bd8f-4d18-8e1f-2c4847105521","name":"proj","description":""},"_type":"project"}""",
        """{"row":{"id":"e8898fb8-9de3-42dd-9874-b0619ccc1048","name":"feat","enabled":false,"project":"proj","metadata":{},"conditions":[],"description":"","script_config":null},"_type":"feature"}""",
        """{"row":{"id":"foo_prod","name":"prod","parent":null},"_type":"global_context"}""",
        """{"row":{"enabled":true,"feature":"feat","project":"proj","conditions":[],"local_context":null,"script_config":null,"global_context":"foo_prod"},"_type":"overload"}"""
      )

      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val res = situation.importV2("testtenant", data = data)

      res.status mustEqual OK
    }
  }

  "Feature import" should {
    "import 'basic' feature" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "project")

      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val feature = jsonFeatures.head
      (feature \ "name").as[String] mustEqual "foo:default-feature"
      (feature \ "id").as[String] mustEqual "project:foo:default-feature"
      (feature \ "enabled").as[Boolean] mustBe false
      (feature \ "description").as[String] mustEqual "An old default feature"
    }

    "give admin right on imported project to current user" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      (situation
        .fetchUserRights()
        .json
        .get \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level")
        .as[String] mustEqual "Admin"
    }

    "import all types of non script features" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          """{"id":"project:test:percentage-feature","enabled":true,"description":"An old style percentage feature","parameters":{"percentage":75},"activationStrategy":"PERCENTAGE"}""",
          """{"id":"project:test:date-range","enabled":true,"description":"An old style date range feature","parameters":{"from":"2023-01-01 00:00:00","to":"2023-12-31 23:59:59"},"activationStrategy":"DATE_RANGE"}""",
          """{"id":"project:another:customer-list-feature","enabled":true,"description":"An old style user list feature","parameters":{"customers":["foo","bar","baz"]},"activationStrategy":"CUSTOMERS_LIST"}""",
          """{"id":"project:baz:release-date","enabled":true,"description":"An old release date feature","parameters":{"releaseDate":"22/07/2023 14:18:11"},"activationStrategy":"RELEASE_DATE"}""",
          """{"id":"project:baz:hour-range-feature","enabled":true,"description":"An old style hour range feature","parameters":{"endAt":"18:00","startAt":"08:00"},"activationStrategy":"HOUR_RANGE"}"""
        )
      )

      val project = situation.fetchProject("testtenant", "project")

      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 5

      val byId = jsonFeatures.map(obj => ((obj \ "id").as[String], obj)).toMap
      (byId("project:test:percentage-feature") \ "conditions" \ "percentage")
        .as[Int] mustEqual 75
      (byId("project:test:percentage-feature") \ "enabled")
        .as[Boolean] mustBe true

      (byId("project:test:date-range") \ "conditions" \ "begin")
        .as[String] mustEqual "2022-12-31T23:00:00Z"
      (byId("project:test:date-range") \ "conditions" \ "end")
        .as[String] mustEqual "2023-12-31T22:59:59Z"
      (byId("project:test:date-range") \ "conditions" \ "timezone")
        .as[String] mustEqual "Europe/Paris"
      (byId("project:test:date-range") \ "enabled").as[Boolean] mustBe true

      (byId("project:another:customer-list-feature") \ "conditions" \ "users")
        .as[Seq[String]] must contain theSameElementsAs Seq("foo", "bar", "baz")
      (byId("project:another:customer-list-feature") \ "enabled")
        .as[Boolean] mustBe true

      (byId("project:baz:release-date") \ "conditions" \ "begin")
        .as[String] mustEqual "2023-07-22T12:18:11Z"
      (byId("project:baz:release-date") \ "conditions" \ "end")
        .asOpt[String] mustBe None
      (byId("project:test:date-range") \ "conditions" \ "timezone")
        .as[String] mustEqual "Europe/Paris"
      (byId("project:test:date-range") \ "enabled").as[Boolean] mustBe true

      (byId("project:baz:hour-range-feature") \ "conditions" \ "startTime")
        .as[String] mustEqual "08:00:00"
      (byId("project:baz:hour-range-feature") \ "conditions" \ "endTime")
        .as[String] mustEqual "18:00:00"
      (byId("project:baz:hour-range-feature") \ "conditions" \ "timezone")
        .as[String] mustEqual "Europe/Paris"
      (byId("project:baz:hour-range-feature") \ "enabled")
        .as[Boolean] mustBe true
    }

    "import all features in a single project if asked" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"baz:foo","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"bar:another","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "fifou")

      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 3
      jsonFeatures.map(js =>
        (js \ "name").as[String]
      ) must contain theSameElementsAs Seq(
        "project:foo:default-feature",
        "baz:foo",
        "bar:another"
      )
      jsonFeatures.map(js =>
        (js \ "id").as[String]
      ) must contain theSameElementsAs Seq(
        "project:foo:default-feature",
        "baz:foo",
        "bar:another"
      )
    }

    "build project name with given number of elements" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = true,
        projectPartSize = Some(2),
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:default-feature2","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"baz:foo:lol","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"baz:another:bi","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "project:foo")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 2
      jsonFeatures.map(js =>
        (js \ "name").as[String]
      ) must contain theSameElementsAs Seq(
        "default-feature",
        "default-feature2"
      )
      jsonFeatures.map(js =>
        (js \ "id").as[String]
      ) must contain theSameElementsAs Seq(
        "project:foo:default-feature",
        "project:foo:default-feature2"
      )

      val project2 = situation.fetchProject("testtenant", "baz:foo")
      val jsonFeatures2 = (project2.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures2 must have size 1
      jsonFeatures2.map(js =>
        (js \ "name").as[String]
      ) must contain theSameElementsAs Seq("lol")
      jsonFeatures2.map(js =>
        (js \ "id").as[String]
      ) must contain theSameElementsAs Seq("baz:foo:lol")

      val project3 = situation.fetchProject("testtenant", "baz:another")
      val jsonFeatures3 = (project3.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures3 must have size 1
      jsonFeatures3.map(js =>
        (js \ "name").as[String]
      ) must contain theSameElementsAs Seq("bi")
      jsonFeatures3.map(js =>
        (js \ "id").as[String]
      ) must contain theSameElementsAs Seq("baz:another:bi")
    }

    "reject import query is project part is too long" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = true,
        projectPartSize = Some(2),
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:bar","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      (response.json.get \ "status").as[String] mustEqual "Failed"
    }

    "allow to import feature with built-in script" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val uuid = UUID.randomUUID().toString.replace("-", "")

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          s"""{"id":"project:foo:script-feature$uuid","enabled":true,"description":"An old style inline script feature","parameters":{"type":"javascript","script":"function enabled(context, enabled, disabled, http) {  if (context.id === 'benjamin') {    return enabled();  }  return disabled();}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = false
      )

      val project =
        situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val first = jsonFeatures.head
      (first \ "wasmConfig")
        .as[String] mustEqual s"project:foo:script-feature${uuid}_script"

      var testResponse =
        situation.testExistingFeature(
          tenant = "testtenant",
          featureId = s"project:foo:script-feature$uuid",
          user = "foo"
        )
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe false

      testResponse = situation.testExistingFeature(
        tenant = "testtenant",
        featureId = s"project:foo:script-feature$uuid",
        user = "benjamin"
      )
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe true
    }

    "fail import if conflict strategy is fail and given project already exists" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withProjectNames("fifou"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      (response.json.get \ "status").as[String] mustEqual "Failed"
    }

    "skip feature that already exists when skip strategy is chosen" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("testtenant").withProjects(
            TestProject("fifou").withFeatures(
              TestFeature("baz:lalala", enabled = true)
            )
          )
        )
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "SKIP",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "fifou")

      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      (jsonFeatures.head \ "enabled").as[Boolean] mustBe true
    }

    "fail import if fail strategy is chosen and importing a feature that already exists" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("testtenant").withProjects(
            TestProject("fifou").withFeatures(
              TestFeature("baz:lalala", enabled = true)
            )
          )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "FAIL",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      (response.json.get \ "status").as[String] mustEqual "Failed"
    }

    "overwrite feature if overwrite strategy is chosen and feature already exists with different id" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("testtenant").withProjects(
            TestProject("fifou").withFeatures(
              TestFeature("baz:lalala", enabled = true)
            )
          )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      (response.json.get \ "status").as[String] mustEqual "Success"

      val project = situation.fetchProject("testtenant", "fifou")
      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      (jsonFeatures.head \ "enabled").as[Boolean] mustBe false
    }

    "overwrite feature if overwrite strategy is chosen and feature already exists with the same id" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      var response = situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      response = situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "fifou")
      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      (jsonFeatures.head \ "enabled").as[Boolean] mustBe true
    }

    "prevent feature import for non admin user" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withUsers(TestUser("testu").withTenantReadWriteRight("testtenant"))
        .loggedAs("testu")
        .build()

      val response = situation.importV1Data(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      response.status mustBe FORBIDDEN
    }

    "allow feature import tenant admin user" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withUsers(TestUser("testu").withTenantAdminRight("testtenant"))
        .loggedAs("testu")
        .build()

      val response = situation.importV1Data(
        tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      response.status mustBe ACCEPTED
    }
  }

  "User import" should {
    "Allow to import users" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "username")
        .as[String] mustEqual "project-create-accredited-user"
      (userJson \ "admin").as[Boolean] mustBe false
      (userJson \ "rights" \ "tenants" \ "testtenant" \ "level")
        .as[String] mustEqual "Read"
      (userJson \ "email").as[String] mustEqual "pcau@maif.fr"
      (userJson \ "userType").as[String] mustEqual "INTERNAL"
    }

    "Give correct project right to imported users (C,R => Write)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "project",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level")
        .as[String] mustEqual "Write"
    }

    "Give correct project right to imported users (R => Read)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "project",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["R"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level")
        .as[String] mustEqual "Read"
    }

    "Give correct project right to imported users (C,R,U => Write)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "project",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R","U"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level")
        .as[String] mustEqual "Write"
    }

    "Give correct project right to imported users (C,R,U,D => Admin)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        deduceProject = false,
        project = "project",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R","U","D"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level")
        .as[String] mustEqual "Admin"
    }

    "Allow to login as imported user" in {
      var situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        users = Seq(
          """{"id":"pcau","name":"pcau","email":"pcau@maif.fr","password":"81da262dd4b4d531576925ab45dcdaad7f8d46c668b2bbbde414939402f01fe16223fc872a179a21bcfbc526ca43a7a972cb89befec69287bd5323a88c650957","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R","U","D"]}],"type":"Izanami"}""".stripMargin
        )
      )

      situation = situation.logout().loggedAs("pcau", "benjamin")

      val rightResponse = situation.fetchUserRights()
      rightResponse.status mustBe OK
    }

    "Allow to import admin user as admin user" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        users = Seq(
          """{"id":"pcau","name":"pcau","email":"pcau@maif.fr","password":"81da262dd4b4d531576925ab45dcdaad7f8d46c668b2bbbde414939402f01fe16223fc872a179a21bcfbc526ca43a7a972cb89befec69287bd5323a88c650957","admin":true,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["R"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("pcau")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "admin").as[Boolean] mustEqual true
    }
  }

  "Key import" should {
    "allow to import a key" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        keys = Seq(
          """{"clientId":"yfsc5ooy3v3hu5z2","name":"local create read key","clientSecret":"sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw","authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"admin":false}""".stripMargin
        )
      )

      val keyResponse = situation.fetchAPIKeys("testtenant")
      val keysJson = keyResponse.json.get.as[Seq[JsObject]]

      keysJson must have size 1

      val keyJson = keysJson.head

      (keyJson \ "name").as[String] mustEqual "local create read key"
      (keyJson \ "clientId").as[String] mustEqual "yfsc5ooy3v3hu5z2"
      (keyJson \ "enabled").as[Boolean] mustBe true
    }

    "make current user admin of imported key" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        keys = Seq(
          """{"clientId":"yfsc5ooy3v3hu5z2","name":"local create read key","clientSecret":"sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw","authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"admin":false}""".stripMargin
        )
      )

      (situation
        .fetchUserRights()
        .json
        .get \ "rights" \ "tenants" \ "testtenant" \ "keys" \ "local create read key" \ "level")
        .as[String] mustEqual "Admin"
    }

    "import usable keys" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          """{"clientId":"yfsc5ooy3v3hu5z2","name":"local create read key","clientSecret":"sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw","authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"admin":false}""".stripMargin
        )
      )

      val pid = (situation
        .fetchProject(tenant = "testtenant", projectId = "project")
        .json
        .get \ "id").as[String]

      val result = situation.checkFeaturesWithRawKey(
        clientId = "yfsc5ooy3v3hu5z2",
        clientSecret =
          "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw",
        projects = Seq(pid)
      )
      result.status mustBe OK

      val activationResult = result.json.get

      (activationResult \ "project:foo" \ "active").as[Boolean] mustBe true
    }
  }

  "Script import" should {
    "Convert basic global js script to wasm script" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val uuid = UUID.randomUUID().toString.replace("-", "")

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          s"""{"id":"project:another:global-script-feature$uuid","enabled":true,"description":"A test global script feature","parameters":{"ref":"project:global-script$uuid"},"activationStrategy":"GLOBAL_SCRIPT"}""".stripMargin
        ),
        scripts = Seq(
          s"""{"id":"project:global-script$uuid","name":"A global script","description":"A test global script","source":{"type":"javascript","script":"function enabled(context, enabled, disabled, http) {  if (context.id === 'benjamin') {    return disabled();  }  return enabled();}"}}""".stripMargin
        ),
        inlineScript = false
      )

      val project =
        situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val first = jsonFeatures.head
      (first \ "wasmConfig").as[String] mustEqual s"project-global-script$uuid"

      var testResponse = situation.testExistingFeature(
        tenant = "testtenant",
        featureId = s"project:another:global-script-feature$uuid",
        user = "foo"
      )
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe true

      testResponse = situation.testExistingFeature(
        tenant = "testtenant",
        featureId = s"project:another:global-script-feature$uuid",
        user = "benjamin"
      )
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe false
    }

    "Convert embeded js script to wasm script" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()
      val uuid = UUID.randomUUID()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          s"""{"id":"project:foo:script-feature$uuid","enabled":true,"description":"An old style inline script feature","parameters":{"type":"javascript","script":"function enabled(context, enabled, disabled, http) {  if (context.id === 'benjamin') {    return enabled();  }  return disabled();}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = false
      )

      val project =
        situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val first = jsonFeatures.head
      (first \ "wasmConfig")
        .as[String] mustEqual s"project:foo:script-feature${uuid}_script"

      var testResponse =
        situation.testExistingFeature(
          tenant = "testtenant",
          featureId = s"project:foo:script-feature$uuid",
          user = "foo"
        )
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe false

      testResponse = situation.testExistingFeature(
        tenant = "testtenant",
        featureId = s"project:foo:script-feature$uuid",
        user = "benjamin"
      )
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe true
    }

    "Import script as embeded Base64 if asked" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val uuid = UUID.randomUUID()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          s"""{"id":"project:foo:script-feature$uuid","enabled":true,"description":"An old style inline script feature","parameters":{"type":"javascript","script":"function enabled(context, enabled, disabled, http) {  if (context.id === 'benjamin') {    return enabled();  }  return disabled();}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = false
      )

      val project =
        situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val first = jsonFeatures.head
      (first \ "wasmConfig")
        .as[String] mustEqual s"project:foo:script-feature${uuid}_script"

      var testResponse =
        situation.testExistingFeature(
          tenant = "testtenant",
          featureId = s"project:foo:script-feature$uuid",
          user = "foo"
        )
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe false

      testResponse = situation.testExistingFeature(
        tenant = "testtenant",
        featureId = s"project:foo:script-feature$uuid",
        user = "benjamin"
      )
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe true
    }

    "indicate incompatible scripts" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          """{"id":"project:simple:feature","enabled":true,"parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:js:feature:","enabled":true,"description":"feature with incompatible js script","parameters":{"type":"javascript","script":"/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  http(\"www.google.com\");\n  if (context.id === 'john.doe@gmail.com') {\n    return enabled();\n  }\n  return disabled();\n}"},"activationStrategy":"SCRIPT"}""".stripMargin,
          """{"id":"project:scala:feature","enabled":true,"parameters":{"type":"scala","script":"/**\n * context:  a play JSON object containing app specific value\n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active\n *           for this request\n * disabled: a callback to mark the feature as inactive\n *           for this request\n * http:     the play http client\n */\n def enabled(context: play.api.libs.json.JsObject,\n             enabled: () => Unit,\n             disabled: () => Unit,\n             http: play.api.libs.ws.WSClient)(implicit ec: ExecutionContext): Unit = {\n             \n    if ( (context \\ \"user\").asOpt[String].contains(\"benjamin\")) {\n      enabled()\n    } else {\n      disabled()\n    }\n    \n}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = true
      )

      val resp = response.json.get

      (resp \ "incompatibleScripts").as[Seq[String]] must not be empty
    }

    "transform feature associated to incompatible script to normal disabled features" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          """{"id":"project:simple:feature","enabled":true,"parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:js:feature:","enabled":true,"description":"feature with incompatible js script","parameters":{"type":"javascript","script":"/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  http(\"www.google.com\");\n  if (context.id === 'john.doe@gmail.com') {\n    return enabled();\n  }\n  return disabled();\n}"},"activationStrategy":"SCRIPT"}""".stripMargin,
          """{"id":"project:scala:feature","enabled":true,"parameters":{"type":"scala","script":"/**\n * context:  a play JSON object containing app specific value\n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active\n *           for this request\n * disabled: a callback to mark the feature as inactive\n *           for this request\n * http:     the play http client\n */\n def enabled(context: play.api.libs.json.JsObject,\n             enabled: () => Unit,\n             disabled: () => Unit,\n             http: play.api.libs.ws.WSClient)(implicit ec: ExecutionContext): Unit = {\n             \n    if ( (context \\ \"user\").asOpt[String].contains(\"benjamin\")) {\n      enabled()\n    } else {\n      disabled()\n    }\n    \n}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = true
      )

      val project =
        situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 3

      val wasmConfig = project.json.get \\ "wasmConfig"
      wasmConfig mustBe empty
    }
  }

  "import process" should {
    "return Accepted and an id as import query response" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importV1Data(
        tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      response.status mustBe ACCEPTED
      (response.json.get \ "id").as[String] must not be null
    }

    "return an object with pending status while import is pending and then return an object with success status" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val uuid = UUID.randomUUID()

      val response = situation.importV1Data(
        tenant = "testtenant",
        features = Seq(
          s"""{"id":"project:another:global-script-feature$uuid","enabled":true,"description":"A test global script feature","parameters":{"ref":"project:global-script$uuid"},"activationStrategy":"GLOBAL_SCRIPT"}""".stripMargin
        ),
        scripts = Seq(
          s"""{"id":"project:global-script$uuid","name":"A global script","description":"A test global script","source":{"type":"javascript","script":"function enabled(context, enabled, disabled, http) {  if (context.id === 'benjamin') {    return disabled();  }  return enabled();}"}}""".stripMargin
        ),
        inlineScript = true
      )

      var checkResponse =
        situation.checkImportStatus("testtenant", response.id.get)
      (checkResponse.json.get \ "status").as[String] mustEqual "Pending"

      while (
        (situation
          .checkImportStatus("testtenant", response.id.get)
          .json
          .get \ "status").as[String] == "Pending"
      ) {
        Thread.sleep(200)
      }

      checkResponse = situation.checkImportStatus("testtenant", response.id.get)
      checkResponse.status mustBe OK
      (checkResponse.json.get \ "status").as[String] mustEqual "Success"
    }

    "return an object with failed status if import failed" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importV1Data(
        tenant = "testtenant",
        features = Seq(
          """{"foo": "bar"}""".stripMargin
        )
      )

      while (
        (situation
          .checkImportStatus("testtenant", response.id.get)
          .json
          .get \ "status").as[String] == "Pending"
      ) {
        Thread.sleep(200)
      }

      val checkResponse =
        situation.checkImportStatus("testtenant", response.id.get)
      checkResponse.status mustBe OK
      (checkResponse.json.get \ "status").as[String] mustEqual "Failed"
    }

    "allow to delete import status" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(
        tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val deleteResponse =
        situation.deleteImportResult("testtenant", response.id.get)
      deleteResponse.status mustBe NO_CONTENT

      val check = situation.checkImportStatus("testtenant", response.id.get)
      check.status mustBe NOT_FOUND
    }
  }

}
